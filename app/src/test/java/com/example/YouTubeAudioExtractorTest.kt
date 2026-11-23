package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.dsp.AudioOutputFormat
import com.example.dsp.AudioProcessor
import com.example.dsp.FfmpegExecOutcome
import com.example.dsp.FfmpegExecutionBackend
import com.example.dsp.LocalMediaInfo
import com.example.network.YouTubeAudioExtractor
import com.example.network.YouTubeHttpGateway
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YouTubeAudioExtractorTest {

    private class FakeYouTubeHttpGateway(
        private val playerJson: String? = null,
        private val watchHtml: String? = null,
        private val oembedJson: String? = null
    ) : YouTubeHttpGateway {
        var downloadedUrl: String? = null

        override fun getText(url: String, headers: Map<String, String>): String? {
            return when {
                url.contains("oembed") -> oembedJson
                url.contains("watch?") -> watchHtml
                else -> null
            }
        }

        override fun postJson(url: String, jsonBody: String, headers: Map<String, String>): String? {
            return if (url.contains("youtubei/v1/player")) playerJson else null
        }

        override fun downloadToFile(
            url: String,
            outFile: File,
            maxBytes: Long,
            headers: Map<String, String>
        ): Boolean {
            downloadedUrl = url
            // Write realistic dummy >2KB compressed audio payload
            outFile.writeBytes(ByteArray(4096) { (it % 127).toByte() })
            return true
        }
    }

    private class StubFfmpegBackend : FfmpegExecutionBackend {
        var capturedArgs: Array<String>? = null

        override fun execute(
            arguments: Array<String>,
            expectedDurationMs: Double,
            onProgress: ((Float) -> Unit)?
        ): FfmpegExecOutcome {
            capturedArgs = arguments
            val outPath = arguments.last()
            File(outPath).writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00))
            onProgress?.invoke(1.0f)
            return FfmpegExecOutcome(success = true, returnCode = 0, logs = "ok")
        }

        override fun probe(localFilePath: String): LocalMediaInfo {
            return LocalMediaInfo(
                durationSec = 10.0,
                sampleRateHz = 48000,
                channels = 2,
                codecName = "aac",
                bitrateBps = 128000L
            )
        }

        override fun cancelAll() {}
    }

    @Test
    fun parseVideoIdHandlesAllYouTubeUrlFormatsAndRejectsInvalidUrls() {
        assertEquals("dQw4w9WgXcQ", YouTubeAudioExtractor.parseVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeAudioExtractor.parseVideoId("https://youtu.be/dQw4w9WgXcQ?t=12"))
        assertEquals("dQw4w9WgXcQ", YouTubeAudioExtractor.parseVideoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeAudioExtractor.parseVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeAudioExtractor.parseVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeAudioExtractor.parseVideoId("https://www.youtube.com/live/dQw4w9WgXcQ?feature=share"))

        assertNull(YouTubeAudioExtractor.parseVideoId("https://evil.example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeAudioExtractor.parseVideoId("file:///etc/passwd"))
        assertNull(YouTubeAudioExtractor.parseVideoId("https://127.0.0.1/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeAudioExtractor.parseVideoId("https://youtube.com/watch?v=short"))
    }

    @Test
    fun parsePlayerResponseSelectsBestDirectAudioStreamAndBlocksUnsafeUrls() {
        val json = """
            {
              "videoDetails": { "title": "Mesa Boogie Guitar Tone Demo" },
              "streamingData": {
                "adaptiveFormats": [
                  { "mimeType": "video/mp4; codecs=\"avc1\"", "bitrate": 2000000, "url": "https://rr1.googlevideo.com/video" },
                  { "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "http://169.254.169.254/latest/meta-data" },
                  { "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "url": "https://rr1.googlevideo.com/opus160" },
                  { "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://rr1.googlevideo.com/aac128" }
                ]
              }
            }
        """.trimIndent()

        val parsed = YouTubeAudioExtractor.parsePlayerResponseJson(json, "dQw4w9WgXcQ")
        assertNotNull(parsed)
        assertEquals("Mesa Boogie Guitar Tone Demo", parsed!!.title)
        // Prefers safe public AAC stream over unsafe 169.254.169.254 stream
        assertEquals("https://rr1.googlevideo.com/aac128", parsed.streamUrl)
        assertFalse(YouTubeAudioExtractor.isSafePublicUrlSyntax("http://127.0.0.1/test"))
        assertFalse(YouTubeAudioExtractor.isSafePublicUrlSyntax("http://localhost/test"))
        assertFalse(YouTubeAudioExtractor.isSafePublicUrlSyntax("http://10.0.0.5/audio.mp4"))
        assertFalse(YouTubeAudioExtractor.isSafePublicUrlSyntax("http://169.254.169.254/latest"))
        assertFalse(YouTubeAudioExtractor.isSafePublicUrl("http://127.0.0.1/test"))
    }

    @Test
    fun extractEmbeddedPlayerResponseFromWatchHtmlFallback() {
        val html = """
            <html><head><title>YouTube</title></head><body>
            <script>var ytInitialData = {}; var ytInitialPlayerResponse = {"videoDetails":{"title":"SVT Bass Tone"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/webm; codecs=\"opus\"","bitrate":130000,"url":"https://rr2.googlevideo.com/bass_opus"}]}}; var other = 1;</script>
            </body></html>
        """.trimIndent()

        val extractedJson = YouTubeAudioExtractor.extractEmbeddedPlayerResponseJson(html)
        assertNotNull(extractedJson)
        val parsed = YouTubeAudioExtractor.parsePlayerResponseJson(extractedJson!!, "AbCdEfGhIjK")
        assertNotNull(parsed)
        assertEquals("SVT Bass Tone", parsed!!.title)
        assertEquals("https://rr2.googlevideo.com/bass_opus", parsed.streamUrl)
    }

    @Test
    fun audioProcessorExtractsYouTubeUrlLocallyAfterDownload() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val playerJson = """
                {
                  "videoDetails": { "title": "Fender Twin Clean Riff" },
                  "streamingData": {
                    "adaptiveFormats": [
                      { "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://rr3.googlevideo.com/twin_clean" }
                    ]
                  }
                }
            """.trimIndent()

            val gateway = FakeYouTubeHttpGateway(playerJson = playerJson)
            val extractor = YouTubeAudioExtractor(httpGateway = gateway)
            val ffmpegBackend = StubFfmpegBackend()
            val processor = AudioProcessor(context, ffmpegBackend)

            val outDir = File(context.cacheDir, "yt_test_out").apply { mkdirs() }
            val outputWav = File(outDir, "twin_clean.wav")

            val result = processor.extractAudioFromYouTubeUrl(
                youtubeUrl = "https://youtu.be/AbCdEfGhIjK",
                outputFile = outputWav,
                targetFormat = AudioOutputFormat.WAV_24BIT,
                sampleRateHz = 48000,
                channels = 1,
                extractor = extractor
            )

            assertTrue(result.isSuccess)
            val audioRes = result.getOrThrow()
            assertEquals(AudioOutputFormat.WAV_24BIT, audioRes.format)
            assertEquals(outputWav.canonicalPath, audioRes.outputFile.canonicalPath)
            assertEquals("https://rr3.googlevideo.com/twin_clean", gateway.downloadedUrl)

            val args = ffmpegBackend.capturedArgs!!.toList()
            // Verify FFmpeg only received the local staged file and enforced local-only whitelist
            assertEquals("file,pipe", args[args.indexOf("-protocol_whitelist") + 1])
            val inputPath = args[args.indexOf("-i") + 1]
            assertFalse(inputPath.startsWith("http"))
            // Staging file must be cleaned up after extraction
            assertFalse(File(inputPath).exists())

            outDir.deleteRecursively()
        }
    }
}
