package com.example.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class AuditionMode {
    DI_CLEAN,
    SNAPTONE_NAM,
    REFERENCE
}

class WaveNetInferenceEngine {

    private val isPlayingFlag = AtomicBoolean(false)
    private val _activeMode = MutableStateFlow<AuditionMode?>(null)
    val activeMode: StateFlow<AuditionMode?> = _activeMode.asStateFlow()

    fun stop() {
        isPlayingFlag.set(false)
        _activeMode.value = null
    }

    /**
     * Generates a realistic dry electric guitar DI test riff using Karplus-Strong plucked string synthesis
     * (power chords + palm-muted chugs + lead sustain note) at 48 kHz.
     */
    fun synthesizeGuitarDiRiff(sampleRate: Int = 48000): FloatArray {
        val totalSamples = sampleRate * 4 // 4 seconds riff
        val out = FloatArray(totalSamples)

        // Notes: E2 (82.4 Hz) + B2 (123.5 Hz), G2 (98.0 Hz) + D3 (146.8 Hz), A2 (110 Hz) + E3 (164.8 Hz), E4 lead (329.6 Hz)
        addPluckedNote(out, startSec = 0.02f, freqHz = 82.41f, durSec = 0.45f, damp = 0.992f, amp = 0.55f, sr = sampleRate)
        addPluckedNote(out, startSec = 0.02f, freqHz = 123.47f, durSec = 0.45f, damp = 0.991f, amp = 0.42f, sr = sampleRate)

        addPluckedNote(out, startSec = 0.52f, freqHz = 82.41f, durSec = 0.25f, damp = 0.975f, amp = 0.52f, sr = sampleRate)
        addPluckedNote(out, startSec = 0.78f, freqHz = 82.41f, durSec = 0.25f, damp = 0.975f, amp = 0.54f, sr = sampleRate)

        addPluckedNote(out, startSec = 1.08f, freqHz = 98.00f, durSec = 0.75f, damp = 0.993f, amp = 0.52f, sr = sampleRate)
        addPluckedNote(out, startSec = 1.08f, freqHz = 146.83f, durSec = 0.75f, damp = 0.992f, amp = 0.40f, sr = sampleRate)

        addPluckedNote(out, startSec = 1.95f, freqHz = 110.00f, durSec = 0.85f, damp = 0.994f, amp = 0.55f, sr = sampleRate)
        addPluckedNote(out, startSec = 1.95f, freqHz = 164.81f, durSec = 0.85f, damp = 0.993f, amp = 0.42f, sr = sampleRate)

        addPluckedNote(out, startSec = 2.88f, freqHz = 329.63f, durSec = 1.05f, damp = 0.996f, amp = 0.48f, sr = sampleRate)

        // Normalize DI peak to -3 dBFS
        var peak = 1e-5f
        for (s in out) peak = max(peak, abs(s))
        val gain = 0.7f / peak
        for (i in out.indices) out[i] *= gain
        return out
    }

    /**
     * Generates a realistic 4-string standard-tuned electric bass DI groove (E1=41.2Hz, A1=55Hz, D2=73.4Hz, G2=98Hz)
     * using Karplus-Strong plucked string synthesis at 48 kHz.
     */
    fun synthesizeBass4DiRiff(sampleRate: Int = 48000): FloatArray {
        val totalSamples = sampleRate * 4 // 4 seconds bass groove
        val out = FloatArray(totalSamples)

        // Standard 4-string E-A-D-G groove: E1 (41.20 Hz) -> G1 (49.00 Hz) -> A1 (55.00 Hz) -> D2 (73.42 Hz) -> G2 (98.00 Hz)
        addPluckedNote(out, startSec = 0.02f, freqHz = 41.20f, durSec = 0.44f, damp = 0.995f, amp = 0.68f, sr = sampleRate)
        addPluckedNote(out, startSec = 0.48f, freqHz = 41.20f, durSec = 0.24f, damp = 0.990f, amp = 0.58f, sr = sampleRate)
        addPluckedNote(out, startSec = 0.76f, freqHz = 49.00f, durSec = 0.24f, damp = 0.992f, amp = 0.60f, sr = sampleRate)
        addPluckedNote(out, startSec = 1.04f, freqHz = 55.00f, durSec = 0.65f, damp = 0.995f, amp = 0.66f, sr = sampleRate)
        addPluckedNote(out, startSec = 1.78f, freqHz = 73.42f, durSec = 0.48f, damp = 0.994f, amp = 0.62f, sr = sampleRate)
        addPluckedNote(out, startSec = 2.32f, freqHz = 98.00f, durSec = 0.55f, damp = 0.994f, amp = 0.58f, sr = sampleRate)
        addPluckedNote(out, startSec = 2.94f, freqHz = 41.20f, durSec = 0.98f, damp = 0.996f, amp = 0.70f, sr = sampleRate)

        var peak = 1e-5f
        for (s in out) peak = max(peak, abs(s))
        val gain = 0.78f / peak
        for (i in out.indices) out[i] *= gain
        return out
    }

    private fun addPluckedNote(
        dest: FloatArray,
        startSec: Float,
        freqHz: Float,
        durSec: Float,
        damp: Float,
        amp: Float,
        sr: Int
    ) {
        val period = max(4, (sr / freqHz).toInt())
        val startIdx = (startSec * sr).toInt()
        val len = min((durSec * sr).toInt(), dest.size - startIdx)
        if (len <= period) return

        val ring = FloatArray(period)
        // Deterministic pick/finger transient excitation
        var seed = (freqHz * 100).toInt()
        for (i in 0 until period) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val noise = (seed / 1073741824f) - 1f
            val fundamentalWeight = if (freqHz < 105f) 0.72f else 0.45f
            val body = sin(2.0 * PI * i / period).toFloat() * fundamentalWeight
            ring[i] = (noise * (if (freqHz < 105f) 0.42f else 0.65f) + body) * amp
        }

        var prev = 0f
        for (n in 0 until len) {
            val idx = n % period
            val cur = ring[idx]
            val filtered = damp * 0.5f * (cur + prev)
            prev = cur
            ring[idx] = filtered
            val fadeOut = if (n > len - 400) (len - n) / 400f else 1f
            dest[startIdx + n] += cur * fadeOut
        }
    }

    /**
     * Runs real WaveNet inference from serialized .nam JSON + optional Cab IR convolution.
     */
    fun processWithNamAndIr(
        diSamples: FloatArray,
        namJson: String,
        irSamples: FloatArray?,
        applyIr: Boolean
    ): FloatArray {
        val root = JSONObject(namJson)
        val config = root.getJSONObject("config")
        val layerObj = config.getJSONArray("layers").getJSONObject(0)
        val channels = layerObj.getInt("channels")
        val kernelSize = layerObj.getInt("kernel_size")
        val dilationsArr = layerObj.getJSONArray("dilations")
        val dilations = List(dilationsArr.length()) { dilationsArr.getInt(it) }
        val headScale = config.optDouble("head_scale", 1.0).toFloat()

        val weightsArr = root.getJSONArray("weights")
        val weights = FloatArray(weightsArr.length()) { weightsArr.getDouble(it).toFloat() }

        val nSamples = diSamples.size
        var layerInput = Array(channels) { FloatArray(nSamples) }
        for (c in 0 until channels) {
            val rw = weights[c]
            for (n in 0 until nSamples) {
                layerInput[c][n] = diSamples[n] * rw
            }
        }

        val headAccum = Array(channels) { FloatArray(nSamples) }
        var cursor = channels
        val convWeightSize = channels * channels * kernelSize
        val projWeightSize = channels * channels

        for (dilation in dilations) {
            val convOut = Array(channels) { FloatArray(nSamples) }
            for (outC in 0 until channels) {
                val bias = weights[cursor + convWeightSize + outC]
                for (n in 0 until nSamples) {
                    var sum = bias
                    for (inC in 0 until channels) {
                        val wBase = cursor + (outC * channels + inC) * kernelSize
                        for (k in 0 until kernelSize) {
                            val tapIdx = n - (kernelSize - 1 - k) * dilation
                            if (tapIdx >= 0) {
                                sum += layerInput[inC][tapIdx] * weights[wBase + k]
                            }
                        }
                    }
                    convOut[outC][n] = tanh(sum.toDouble()).toFloat()
                }
            }
            val projOffset = cursor + convWeightSize + channels + channels
            val nextInput = Array(channels) { FloatArray(nSamples) }
            for (outC in 0 until channels) {
                val pBias = weights[projOffset + projWeightSize + outC]
                for (n in 0 until nSamples) {
                    var sum = pBias
                    for (inC in 0 until channels) {
                        sum += convOut[inC][n] * weights[projOffset + outC * channels + inC]
                    }
                    nextInput[outC][n] = layerInput[outC][n] + sum
                    headAccum[outC][n] += convOut[outC][n]
                }
            }
            layerInput = nextInput
            cursor += convWeightSize + channels + channels + projWeightSize + channels
        }

        val headWeightOffset = cursor
        val headBiasOffset = cursor + channels
        val namOut = FloatArray(nSamples)
        for (n in 0 until nSamples) {
            var sum = weights[headBiasOffset]
            for (c in 0 until channels) {
                sum += headAccum[c][n] * weights[headWeightOffset + c]
            }
            namOut[n] = (sum * headScale).coerceIn(-1.5f, 1.5f)
        }

        val finalOut = if (applyIr && irSamples != null && irSamples.isNotEmpty()) {
            val taps = min(irSamples.size, 512)
            FloatArray(nSamples) { n ->
                var acc = 0f
                val limit = min(taps, n + 1)
                for (k in 0 until limit) {
                    acc += namOut[n - k] * irSamples[k]
                }
                acc
            }
        } else {
            namOut
        }

        // Normalize output safely to -1 dBFS
        var peak = 1e-5f
        for (s in finalOut) peak = max(peak, abs(s))
        val norm = 0.88f / peak
        for (i in finalOut.indices) finalOut[i] = (finalOut[i] * norm).coerceIn(-1f, 1f)
        return finalOut
    }

    suspend fun playAudition(
        mode: AuditionMode,
        namJson: String,
        irSamples: FloatArray?,
        captureMode: CaptureMode,
        referenceSamples: FloatArray?,
        isBass: Boolean = false
    ) = withContext(Dispatchers.IO) {
        stop()
        isPlayingFlag.set(true)
        _activeMode.value = mode

        val sampleRate = 48000
        val audioToPlay = when (mode) {
            AuditionMode.DI_CLEAN -> {
                if (isBass) synthesizeBass4DiRiff(sampleRate) else synthesizeGuitarDiRiff(sampleRate)
            }
            AuditionMode.SNAPTONE_NAM -> {
                val di = if (isBass) synthesizeBass4DiRiff(sampleRate) else synthesizeGuitarDiRiff(sampleRate)
                processWithNamAndIr(
                    diSamples = di,
                    namJson = namJson,
                    irSamples = irSamples,
                    applyIr = captureMode == CaptureMode.AMP_PLUS_IR
                )
            }
            AuditionMode.REFERENCE -> {
                val raw = referenceSamples ?: synthesizeGuitarDiRiff(sampleRate)
                val maxLen = min(raw.size, sampleRate * 5)
                val slice = raw.copyOfRange(0, maxLen)
                var pk = 1e-5f
                for (s in slice) pk = max(pk, abs(s))
                val g = 0.85f / pk
                FloatArray(slice.size) { (slice[it] * g).coerceIn(-1f, 1f) }
            }
        }

        val pcmShorts = ShortArray(audioToPlay.size) { i ->
            (audioToPlay[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }

        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            var offset = 0
            val chunkShorts = minBufBytes / 2
            while (isPlayingFlag.get() && offset < pcmShorts.size) {
                val toWrite = min(chunkShorts, pcmShorts.size - offset)
                track.write(pcmShorts, offset, toWrite)
                offset += toWrite
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            isPlayingFlag.set(false)
            if (_activeMode.value == mode) {
                _activeMode.value = null
            }
        }
    }
}
