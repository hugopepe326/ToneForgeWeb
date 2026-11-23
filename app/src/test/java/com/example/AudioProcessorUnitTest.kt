package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.dsp.AudioOutputFormat
import com.example.dsp.AudioProcessor
import com.example.dsp.FfmpegExecOutcome
import com.example.dsp.FfmpegExecutionBackend
import com.example.dsp.LocalMediaInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioProcessorUnitTest {

    private class RecordingFfmpegBackend(
        private val shouldSucceed: Boolean = true
    ) : FfmpegExecutionBackend {
        var lastArgs: Array<String>? = null
        var lastProbedPath: String? = null
        var cancelled = false

        override fun execute(
            arguments: Array<String>,
            expectedDurationMs: Double,
            onProgress: ((Float) -> Unit)?
        ): FfmpegExecOutcome {
            lastArgs = arguments
            if (shouldSucceed) {
                val outPath = arguments.last()
                File(outPath).writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00))
                onProgress?.invoke(0.5f)
                onProgress?.invoke(1.0f)
                return FfmpegExecOutcome(success = true, returnCode = 0, logs = "ok")
            }
            return FfmpegExecOutcome(success = false, returnCode = 1, logs = "simulated failure")
        }

        override fun probe(localFilePath: String): LocalMediaInfo {
            lastProbedPath = localFilePath
            return LocalMediaInfo(
                durationSec = 12.5,
                sampleRateHz = 44100,
                channels = 2,
                codecName = "aac",
                bitrateBps = 192000L
            )
        }

        override fun cancelAll() {
            cancelled = true
        }
    }

    @Test
    fun extractAudioBuildsStrictLocalFfmpegArgs() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tempDir = File(context.cacheDir, "audio_proc_test_1").apply { mkdirs() }
            val inFile = File(tempDir, "guitar_video.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val outFile = File(tempDir, "extracted.wav")

            val backend = RecordingFfmpegBackend()
            val processor = AudioProcessor(context, backend)

            var lastProg = 0f
            val result = processor.extractAudioFromFile(
                inputFile = inFile,
                outputFile = outFile,
                targetFormat = AudioOutputFormat.WAV_24BIT,
                sampleRateHz = 48000,
                channels = 1,
                onProgress = { lastProg = it }
            )

            assertTrue(result.isSuccess)
            assertEquals(1.0f, lastProg, 0.001f)
            val args = backend.lastArgs!!.toList()

            // Must enforce local-only protocols and strip video/subtitles
            val protoIdx = args.indexOf("-protocol_whitelist")
            assertTrue(protoIdx >= 0)
            assertEquals("file,pipe", args[protoIdx + 1])
            assertTrue(args.contains("-vn"))
            assertTrue(args.contains("-sn"))
            assertTrue(args.contains("-dn"))
            assertEquals("pcm_s24le", args[args.indexOf("-c:a") + 1])
            assertEquals("48000", args[args.indexOf("-ar") + 1])
            assertEquals("1", args[args.indexOf("-ac") + 1])
            assertEquals("wav", args[args.indexOf("-f") + 1])
            assertEquals(outFile.canonicalPath, args.last())

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun convertFormatSupportsMp3TrimAndLoudnorm() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tempDir = File(context.cacheDir, "audio_proc_test_2").apply { mkdirs() }
            val inFile = File(tempDir, "riff.flac").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
            val outFile = File(tempDir, "riff_320.mp3")

            val backend = RecordingFfmpegBackend()
            val processor = AudioProcessor(context, backend)

            val result = processor.convertAudioFormat(
                inputFile = inFile,
                outputFile = outFile,
                targetFormat = AudioOutputFormat.MP3,
                sampleRateHz = 44100,
                channels = 2,
                bitrateKbps = 320,
                trimStartSec = 1.5f,
                trimDurationSec = 4.25f,
                normalizeAudio = true
            )

            assertTrue(result.isSuccess)
            val processResult = result.getOrThrow()
            assertEquals(AudioOutputFormat.MP3, processResult.format)
            assertEquals(44100, processResult.sampleRateHz)
            assertEquals(2, processResult.channels)

            val args = backend.lastArgs!!.toList()
            assertEquals("1.500", args[args.indexOf("-ss") + 1])
            assertEquals("4.250", args[args.indexOf("-t") + 1])
            assertEquals("libmp3lame", args[args.indexOf("-c:a") + 1])
            assertEquals("320k", args[args.indexOf("-b:a") + 1])
            assertEquals("loudnorm=I=-14:TP=-1.0:LRA=11", args[args.indexOf("-af") + 1])

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun failsCleanlyOnMissingFileAndReportsFfmpegError() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val missing = File(context.cacheDir, "does_not_exist.wav")
            val out = File(context.cacheDir, "out.mp3")

            val backend = RecordingFfmpegBackend(shouldSucceed = false)
            val processor = AudioProcessor(context, backend)

            val missingRes = processor.extractAudioFromFile(missing, out)
            assertTrue(missingRes.isFailure)

            val validIn = File(context.cacheDir, "valid_in.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val failRes = processor.convertAudioFormat(validIn, out, AudioOutputFormat.FLAC)
            assertTrue(failRes.isFailure)
            assertFalse(out.exists())

            processor.cancelActiveProcessing()
            assertTrue(backend.cancelled)
            validIn.delete()
        }
    }

    @Test
    fun probeLocalMediaReturnsOfflineMetadata() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tempFile = File(context.cacheDir, "probe_test.m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val backend = RecordingFfmpegBackend()
            val processor = AudioProcessor(context, backend)

            val res = processor.probeLocalMedia(tempFile)
            assertTrue(res.isSuccess)
            val info = res.getOrThrow()
            assertEquals(44100, info.sampleRateHz)
            assertEquals(2, info.channels)
            assertEquals("aac", info.codecName)
            assertEquals(tempFile.canonicalPath, backend.lastProbedPath)
            tempFile.delete()
        }
    }

    @Test
    fun wavParserSupportsFfmpegExtensible24BitWavHeader() {
        val bb = java.nio.ByteBuffer.allocate(44 + 24 + 6).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + 24 + 6)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(40) // WAVEFORMATEXTENSIBLE fmt chunk size
        bb.putShort(0xFFFE.toShort()) // WAVE_FORMAT_EXTENSIBLE
        bb.putShort(1) // mono
        bb.putInt(48000) // sampleRate
        bb.putInt(48000 * 3)
        bb.putShort(3)
        bb.putShort(24) // bitsPerSample
        bb.putShort(22) // cbSize
        bb.putShort(24) // wValidBitsPerSample
        bb.putInt(4) // dwChannelMask
        bb.putShort(1) // SubFormat PCM = 1
        for (i in 0 until 14) bb.put(0) // remaining GUID bytes
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(6)
        // Sample 1: +0x400000 (~+0.5), Sample 2: -0x400000 (~-0.5)
        bb.put(0x00); bb.put(0x00); bb.put(0x40)
        bb.put(0x00); bb.put(0x00); bb.put(0xC0.toByte())

        val parsed = com.example.dsp.WavIrExporter.tryParseWav(bb.array())
        assertTrue(parsed != null)
        assertEquals(48000, parsed!!.sampleRate)
        assertEquals(2, parsed.samplesMono.size)
        assertEquals(0.5f, parsed.samplesMono[0], 0.01f)
        assertEquals(-0.5f, parsed.samplesMono[1], 0.01f)
    }
}
