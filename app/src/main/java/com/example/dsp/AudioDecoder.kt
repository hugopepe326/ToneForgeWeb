package com.example.dsp

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AudioDecoder {

    private const val TARGET_SAMPLE_RATE = 48000
    private const val MAX_ANALYSIS_SECONDS = 60

    fun decodeFromUri(context: Context, uri: Uri): DecodedAudio {
        val displayName = resolveDisplayName(context, uri) ?: "audio_importado"
        // Copy to temp cache file so MediaExtractor and WAV parser have random-access FD
        val tempFile = File(context.cacheDir, "decode_tmp_${System.currentTimeMillis()}.bin")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("No se pudo abrir el archivo de audio seleccionado.")

            return decodeFromFile(
                file = tempFile,
                sourceLabel = displayName,
                sourceType = AudioSourceType.FILE,
                context = context
            )
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    fun decodeFromFile(
        file: File,
        sourceLabel: String,
        sourceType: AudioSourceType,
        context: Context? = null
    ): DecodedAudio {
        require(file.exists() && file.length() > 0L) { "El archivo de audio está vacío o no existe." }

        // Fast-path RIFF WAV decoding if under 35 MB
        if (file.length() < 35_000_000L) {
            val headerBytes = file.inputStream().use { stream ->
                val buf = ByteArray(min(file.length().toInt(), 12))
                stream.read(buf)
                buf
            }
            if (headerBytes.size >= 12 &&
                String(headerBytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(headerBytes, 8, 4, Charsets.US_ASCII) == "WAVE"
            ) {
                val parsed = WavIrExporter.tryParseWav(file.readBytes())
                if (parsed != null && parsed.samplesMono.isNotEmpty()) {
                    return buildDecodedAudio(
                        rawMono = parsed.samplesMono,
                        srcRate = parsed.sampleRate,
                        sourceLabel = sourceLabel,
                        sourceType = sourceType
                    )
                }
            }
        }

        // General Android MediaExtractor + MediaCodec decoder (MP3, AAC, M4A, FLAC, OGG, MP4, WEBM)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            require(audioTrackIndex >= 0 && trackFormat != null) {
                "No se encontró ninguna pista de audio decodificable en el archivo."
            }
            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = if (trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            val codec = MediaCodec.createDecoderByType(mime)
            val pcmChunks = ArrayList<FloatArray>()
            var totalMonoSamples = 0
            val maxSamplesCap = sampleRate * MAX_ANALYSIS_SECONDS

            try {
                codec.configure(trackFormat, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone && totalMonoSamples < maxSamplesCap) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, sampleSize, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFmt = codec.outputFormat
                            if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                        outIndex >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = decodePcmBufferToMono(
                                    outBuf, channelCount, pcmEncoding
                                )
                                if (chunk.isNotEmpty()) {
                                    pcmChunks.add(chunk)
                                    totalMonoSamples += chunk.size
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            require(totalMonoSamples > 0) {
                "El decodificador no produjo muestras de audio válidas."
            }

            val merged = FloatArray(totalMonoSamples)
            var offset = 0
            for (chunk in pcmChunks) {
                chunk.copyInto(merged, offset)
                offset += chunk.size
            }

            return buildDecodedAudio(
                rawMono = merged,
                srcRate = sampleRate,
                sourceLabel = sourceLabel,
                sourceType = sourceType
            )
        } catch (mediaErr: Exception) {
            if (context != null) {
                val ffmpegWav = File(context.cacheDir, "ffmpeg_fallback_${System.currentTimeMillis()}.wav")
                try {
                    val processor = AudioProcessor(context)
                    val res = kotlinx.coroutines.runBlocking {
                        processor.extractAudioFromFile(
                            inputFile = file,
                            outputFile = ffmpegWav,
                            targetFormat = AudioOutputFormat.WAV_24BIT,
                            sampleRateHz = TARGET_SAMPLE_RATE,
                            channels = 1
                        )
                    }
                    if (res.isSuccess) {
                        val parsed = WavIrExporter.tryParseWav(ffmpegWav.readBytes())
                        if (parsed != null && parsed.samplesMono.isNotEmpty()) {
                            return buildDecodedAudio(
                                rawMono = parsed.samplesMono,
                                srcRate = parsed.sampleRate,
                                sourceLabel = sourceLabel,
                                sourceType = sourceType
                            )
                        }
                    }
                } finally {
                    runCatching { ffmpegWav.delete() }
                }
            }
            throw mediaErr
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun buildDecodedAudio(
        rawMono: FloatArray,
        srcRate: Int,
        sourceLabel: String,
        sourceType: AudioSourceType
    ): DecodedAudio {
        val cappedSamples = if (rawMono.size > srcRate * MAX_ANALYSIS_SECONDS) {
            rawMono.copyOfRange(0, srcRate * MAX_ANALYSIS_SECONDS)
        } else {
            rawMono
        }
        val resampled = FftProcessor.resampleLinear(cappedSamples, srcRate, TARGET_SAMPLE_RATE)
        var peak = 0f
        var sumSq = 0.0
        for (s in resampled) {
            val a = abs(s)
            if (a > peak) peak = a
            sumSq += s * s
        }
        val rms = sqrt(sumSq / max(1, resampled.size)).toFloat()
        val peakDbfs = (20.0 * log10(max(1e-6f, peak).toDouble())).toFloat()
        val rmsDbfs = (20.0 * log10(max(1e-6f, rms).toDouble())).toFloat()
        val durationSec = resampled.size.toFloat() / TARGET_SAMPLE_RATE
        val peaks = FftProcessor.computeWaveformPeaks(resampled, bins = 140)

        return DecodedAudio(
            samples48k = resampled,
            sampleRate = TARGET_SAMPLE_RATE,
            durationSec = durationSec,
            peakDbfs = peakDbfs,
            rmsDbfs = rmsDbfs,
            waveformPeaks = peaks,
            sourceLabel = sourceLabel,
            sourceType = sourceType
        )
    }

    private fun decodePcmBufferToMono(
        buffer: java.nio.ByteBuffer,
        channels: Int,
        pcmEncoding: Int
    ): FloatArray {
        val chCount = max(1, channels)
        val ordered = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = ordered.asFloatBuffer()
                val frames = floats.remaining() / chCount
                FloatArray(frames) {
                    var sum = 0f
                    for (c in 0 until chCount) sum += floats.get()
                    (sum / chCount).coerceIn(-1f, 1f)
                }
            }
            else -> {
                val shorts = ordered.asShortBuffer()
                val frames = shorts.remaining() / chCount
                FloatArray(frames) {
                    var sum = 0f
                    for (c in 0 until chCount) sum += shorts.get() / 32768f
                    (sum / chCount).coerceIn(-1f, 1f)
                }
            }
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
