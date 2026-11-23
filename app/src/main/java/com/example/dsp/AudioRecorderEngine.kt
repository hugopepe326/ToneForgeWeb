package com.example.dsp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AudioRecorderEngine {
    private val isRecordingFlag = AtomicBoolean(false)
    private val _liveLevelNorm = MutableStateFlow(0f)
    val liveLevelNorm: StateFlow<Float> = _liveLevelNorm.asStateFlow()

    private val _liveDurationSec = MutableStateFlow(0f)
    val liveDurationSec: StateFlow<Float> = _liveDurationSec.asStateFlow()

    private val recordedChunks = ArrayList<FloatArray>()

    @SuppressLint("MissingPermission")
    suspend fun startCapture(
        sampleRate: Int = 48000,
        maxSeconds: Int = 30
    ): DecodedAudio? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        ).let { rec ->
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec
            else {
                rec.release()
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return@withContext null
        }

        recordedChunks.clear()
        isRecordingFlag.set(true)
        _liveDurationSec.value = 0f
        _liveLevelNorm.value = 0f

        val shortBuf = ShortArray(minBuf / 2)
        var totalSamples = 0
        val maxSamples = sampleRate * maxSeconds

        try {
            audioRecord.startRecording()
            while (isRecordingFlag.get() && totalSamples < maxSamples) {
                val read = audioRecord.read(shortBuf, 0, shortBuf.size)
                if (read > 0) {
                    val floats = FloatArray(read)
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val v = shortBuf[i] / 32768f
                        floats[i] = v
                        sumSq += v * v
                    }
                    recordedChunks.add(floats)
                    totalSamples += read
                    val rms = sqrt(sumSq / read).toFloat()
                    val db = (20.0 * log10(max(1e-5f, rms).toDouble())).toFloat()
                    _liveLevelNorm.value = ((db + 54f) / 54f).coerceIn(0f, 1f)
                    _liveDurationSec.value = totalSamples.toFloat() / sampleRate
                }
            }
        } finally {
            isRecordingFlag.set(false)
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            _liveLevelNorm.value = 0f
        }

        if (totalSamples < sampleRate / 4) return@withContext null

        val merged = FloatArray(totalSamples)
        var offset = 0
        for (c in recordedChunks) {
            c.copyInto(merged, offset)
            offset += c.size
        }

        AudioDecoder.buildDecodedAudio(
            rawMono = merged,
            srcRate = sampleRate,
            sourceLabel = "Toma directa (${String.format("%.1f", totalSamples.toFloat() / sampleRate)}s)",
            sourceType = AudioSourceType.MIC
        )
    }

    fun stopCapture() {
        isRecordingFlag.set(false)
    }
}
