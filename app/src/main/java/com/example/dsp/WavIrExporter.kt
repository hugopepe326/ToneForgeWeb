package com.example.dsp

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object WavIrExporter {

    /**
     * Writes a 24-bit Mono PCM RIFF/WAV Impulse Response file compatible with Valeton Suite & GP-5.
     */
    fun writeWav24BitMono(samples: FloatArray, sampleRate: Int, outFile: File) {
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(encodeWav24BitMono(samples, sampleRate))
    }

    fun encodeWav24BitMono(samples: FloatArray, sampleRate: Int): ByteArray {
        val bytesPerSample = 3 // 24-bit PCM
        val dataSize = samples.size * bytesPerSample
        val totalSize = 44 + dataSize
        val baos = ByteArrayOutputStream(totalSize)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // Mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * bytesPerSample) // ByteRate
        header.putShort(bytesPerSample.toShort()) // BlockAlign
        header.putShort(24) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        baos.write(header.array())

        val maxInt24 = 8388607.0
        val buf = ByteArray(dataSize)
        var offset = 0
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f).toDouble()
            val v = (clamped * maxInt24).roundToInt().coerceIn(-8388608, 8388607)
            buf[offset++] = (v and 0xFF).toByte()
            buf[offset++] = ((v shr 8) and 0xFF).toByte()
            buf[offset++] = ((v shr 16) and 0xFF).toByte()
        }
        baos.write(buf)
        return baos.toByteArray()
    }

    data class WavPcmData(
        val samplesMono: FloatArray,
        val sampleRate: Int
    )

    /**
     * Parses uncompressed PCM or IEEE-float WAV bytes directly without MediaCodec overhead.
     */
    fun tryParseWav(bytes: ByteArray): WavPcmData? {
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
        bb.int // chunk size
        val wave = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return null

        var audioFormat = 1
        var numChannels = 1
        var sampleRate = 48000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLength = 0

        while (bb.remaining() >= 8) {
            val chunkId = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
            val chunkSize = bb.int
            if (chunkSize < 0 || chunkSize > bb.remaining()) break
            val nextPos = bb.position() + chunkSize + (chunkSize and 1)

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize >= 16) {
                        val fmtStart = bb.position()
                        audioFormat = bb.short.toInt() and 0xFFFF
                        numChannels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        if (audioFormat == 0xFFFE && chunkSize >= 40) {
                            bb.position(fmtStart + 24)
                            audioFormat = bb.short.toInt() and 0xFFFF
                        }
                    }
                }
                "data" -> {
                    dataOffset = bb.position()
                    dataLength = chunkSize
                    break
                }
            }
            if (nextPos <= bytes.size) bb.position(nextPos) else break
        }

        if (dataOffset < 0 || dataLength <= 0 || numChannels <= 0) return null
        if (audioFormat != 1 && audioFormat != 3) return null

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) return null
        val frameSize = bytesPerSample * numChannels
        val frameCount = dataLength / frameSize
        val out = FloatArray(frameCount)
        val dataBuf = ByteBuffer.wrap(bytes, dataOffset, dataLength).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until frameCount) {
            var sum = 0f
            for (ch in 0 until numChannels) {
                val sample = when {
                    audioFormat == 3 && bitsPerSample == 32 -> dataBuf.float
                    bitsPerSample == 16 -> dataBuf.short / 32768f
                    bitsPerSample == 24 -> {
                        val b0 = dataBuf.get().toInt() and 0xFF
                        val b1 = dataBuf.get().toInt() and 0xFF
                        val b2 = dataBuf.get().toInt()
                        val signed = (b2 shl 16) or (b1 shl 8) or b0
                        signed / 8388608f
                    }
                    bitsPerSample == 32 -> dataBuf.int / 2147483648f
                    bitsPerSample == 8 -> ((dataBuf.get().toInt() and 0xFF) - 128) / 128f
                    else -> 0f
                }
                sum += sample
            }
            out[i] = (sum / numChannels).coerceIn(-1f, 1f)
        }
        return WavPcmData(out, sampleRate)
    }
}
