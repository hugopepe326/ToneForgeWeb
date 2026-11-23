package com.example.network

import android.content.Context
import com.example.dsp.AudioOutputFormat
import com.example.dsp.AudioProcessResult
import com.example.dsp.AudioProcessor
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class ParsedYouTubeStream(
    val videoId: String,
    val title: String,
    val streamUrl: String,
    val mimeType: String,
    val bitrateBps: Int
)

sealed class YouTubeExtractResult {
    data class Success(
        val processResult: AudioProcessResult,
        val streamInfo: ParsedYouTubeStream
    ) : YouTubeExtractResult()

    data class Failure(val userMessage: String) : YouTubeExtractResult()
}

interface YouTubeHttpGateway {
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String?
    fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String?
    fun downloadToFile(url: String, outFile: File, maxBytes: Long, headers: Map<String, String> = emptyMap()): Boolean
}

class OkHttpYouTubeGateway : YouTubeHttpGateway {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val url = req.url
            val scheme = url.scheme.lowercase()
            if ((scheme != "http" && scheme != "https") || !YouTubeAudioExtractor.isSafePublicHost(url.host.lowercase())) {
                throw java.io.IOException("Dirección de red no permitida por seguridad.")
            }
            chain.proceed(req)
        }
        .build()

    override fun getText(url: String, headers: Map<String, String>): String? {
        if (!YouTubeAudioExtractor.isSafePublicUrl(url)) return null
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.take(1_200_000) else null
            }
        }.getOrNull()
    }

    override fun postJson(url: String, jsonBody: String, headers: Map<String, String>): String? {
        if (!YouTubeAudioExtractor.isSafePublicUrl(url)) return null
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.take(1_200_000) else null
            }
        }.getOrNull()
    }

    override fun downloadToFile(
        url: String,
        outFile: File,
        maxBytes: Long,
        headers: Map<String, String>
    ): Boolean {
        if (!YouTubeAudioExtractor.isSafePublicUrl(url)) return false
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Range", "bytes=0-${maxBytes - 1}")
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return@use false
                val input = resp.body?.byteStream() ?: return@use false
                var total = 0L
                val buf = ByteArray(16 * 1024)
                outFile.outputStream().use { out ->
                    while (total < maxBytes) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        val toWrite = minOf(read.toLong(), maxBytes - total).toInt()
                        out.write(buf, 0, toWrite)
                        total += toWrite
                    }
                }
                total > 2048L
            }
        }.getOrDefault(false)
    }
}

/**
 * Lightweight on-device YouTube URL parser and audio stream extractor.
 * Resolves public audio stream URLs via lightweight JSON/HTML parsing, downloads
 * the compressed stream to private app cache, and delegates transcoding/conversion
 * to [AudioProcessor] (local FFmpeg) with zero external processing servers.
 */
class YouTubeAudioExtractor(
    private val httpGateway: YouTubeHttpGateway = OkHttpYouTubeGateway()
) {

    /**
     * Resolves metadata and a downloadable audio stream from a YouTube URL without
     * invoking external extraction servers.
     */
    suspend fun resolveAudioStream(rawUrl: String): Result<ParsedYouTubeStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val videoId = parseVideoId(rawUrl)
                    ?: error("Enlace de YouTube no válido. Usa una URL de youtube.com o youtu.be.")

                var resolvedTitle = fetchOembedTitle(videoId) ?: "YouTube_$videoId"

                // Strategy 1: Lightweight player endpoint contexts (mobile/VR clients)
                val clientContexts = listOf(
                    Triple("IOS", "19.29.1", IOS_UA),
                    Triple("ANDROID_VR", "1.59.21", ANDROID_UA),
                    Triple("ANDROID", "19.29.37", ANDROID_UA)
                )
                for ((clientName, clientVersion, ua) in clientContexts) {
                    val payload = JSONObject().apply {
                        put("videoId", videoId)
                        put("context", JSONObject().apply {
                            put("client", JSONObject().apply {
                                put("clientName", clientName)
                                put("clientVersion", clientVersion)
                                put("hl", "es")
                                put("gl", "ES")
                            })
                        })
                    }.toString()

                    val body = httpGateway.postJson(
                        url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                        jsonBody = payload,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Origin" to "https://www.youtube.com"
                        )
                    )
                    if (!body.isNullOrBlank()) {
                        val parsed = parsePlayerResponseJson(body, videoId, resolvedTitle)
                        if (parsed != null) return@runCatching parsed
                        extractTitleFromPlayerJson(body)?.let { resolvedTitle = it }
                    }
                }

                // Strategy 2: Lightweight watch-page HTML parsing for ytInitialPlayerResponse
                val watchHtml = httpGateway.getText(
                    url = "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1",
                    headers = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
                        "Cookie" to "CONSENT=YES+cb"
                    )
                )
                if (!watchHtml.isNullOrBlank()) {
                    val playerJson = extractEmbeddedPlayerResponseJson(watchHtml)
                    if (!playerJson.isNullOrBlank()) {
                        val parsed = parsePlayerResponseJson(playerJson, videoId, resolvedTitle)
                        if (parsed != null) return@runCatching parsed
                        extractTitleFromPlayerJson(playerJson)?.let { resolvedTitle = it }
                    }
                }

                error(
                    "YouTube restringió la extracción directa para \"$resolvedTitle\" desde este dispositivo. " +
                        "Importa el archivo local o graba el sonido en Micro / USB."
                )
            }
        }

    /**
     * Downloads the audio stream from a YouTube URL into private staging cache and
     * processes it locally through [AudioProcessor] (FFmpeg) into [targetFormat].
     */
    suspend fun extractAudioToLocalFile(
        context: Context,
        youtubeUrl: String,
        outputFile: File,
        audioProcessor: AudioProcessor = AudioProcessor(context),
        targetFormat: AudioOutputFormat = AudioOutputFormat.WAV_24BIT,
        sampleRateHz: Int = 48000,
        channels: Int = 1,
        bitrateKbps: Int = 256,
        onProgress: ((Float) -> Unit)? = null
    ): YouTubeExtractResult = withContext(Dispatchers.IO) {
        onProgress?.invoke(0.05f)
        val resolved = resolveAudioStream(youtubeUrl).getOrElse { err ->
            return@withContext YouTubeExtractResult.Failure(
                err.message ?: "No se pudo resolver el flujo de audio de YouTube."
            )
        }
        onProgress?.invoke(0.25f)

        val stagingDir = File(context.cacheDir, "yt_staging").apply { mkdirs() }
        val ext = if (resolved.mimeType.contains("webm", ignoreCase = true)) "webm" else "m4a"
        val stagedDownload = File(stagingDir, "yt_${resolved.videoId}_${UUID.randomUUID()}.$ext")

        try {
            val downloaded = httpGateway.downloadToFile(
                url = resolved.streamUrl,
                outFile = stagedDownload,
                maxBytes = MAX_STREAM_DOWNLOAD_BYTES,
                headers = mapOf("User-Agent" to DESKTOP_UA)
            )
            if (!downloaded || !stagedDownload.exists() || stagedDownload.length() <= 2048L) {
                return@withContext YouTubeExtractResult.Failure(
                    "No se pudo descargar el audio de \"${resolved.title}\" desde YouTube."
                )
            }
            onProgress?.invoke(0.55f)

            val ffResult = audioProcessor.extractAudioFromFile(
                inputFile = stagedDownload,
                outputFile = outputFile,
                targetFormat = targetFormat,
                sampleRateHz = sampleRateHz,
                channels = channels,
                bitrateKbps = bitrateKbps,
                onProgress = { p -> onProgress?.invoke(0.55f + p * 0.45f) }
            )

            ffResult.fold(
                onSuccess = { processResult ->
                    onProgress?.invoke(1.0f)
                    YouTubeExtractResult.Success(processResult, resolved)
                },
                onFailure = { err ->
                    YouTubeExtractResult.Failure(
                        err.message ?: "Error al procesar con FFmpeg el audio descargado."
                    )
                }
            )
        } finally {
            runCatching { stagedDownload.delete() }
        }
    }

    /**
     * Downloads the raw audio stream container from YouTube into [stagedOutFile] without
     * invoking FFmpeg (useful when callers want direct fallback decoding).
     */
    suspend fun downloadAudioContainer(
        youtubeUrl: String,
        stagedOutFile: File
    ): Result<ParsedYouTubeStream> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveAudioStream(youtubeUrl).getOrThrow()
            stagedOutFile.parentFile?.mkdirs()
            val ok = httpGateway.downloadToFile(
                url = resolved.streamUrl,
                outFile = stagedOutFile,
                maxBytes = MAX_STREAM_DOWNLOAD_BYTES,
                headers = mapOf("User-Agent" to DESKTOP_UA)
            )
            require(ok && stagedOutFile.exists() && stagedOutFile.length() > 2048L) {
                "No se pudo descargar el audio de \"${resolved.title}\"."
            }
            resolved
        }
    }

    private fun fetchOembedTitle(videoId: String): String? {
        val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
        val body = httpGateway.getText(oembedUrl, mapOf("User-Agent" to DESKTOP_UA)) ?: return null
        return runCatching {
            JSONObject(body).optString("title").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        private const val MAX_STREAM_DOWNLOAD_BYTES = 8L * 1024L * 1024L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val IOS_UA =
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
        private const val ANDROID_UA =
            "com.google.android.youtube/19.29.37 (Linux; U; Android 14; es_ES)"

        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        private val ALLOWED_YOUTUBE_HOSTS = setOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
            "youtu.be",
            "www.youtu.be",
            "youtube-nocookie.com",
            "www.youtube-nocookie.com"
        )

        fun isYouTubeUrl(rawUrl: String): Boolean {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return host in ALLOWED_YOUTUBE_HOSTS
        }

        fun parseVideoId(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (!isYouTubeUrl(trimmed)) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty()

            if (host == "youtu.be" || host == "www.youtu.be") {
                val seg = path.trimStart('/').substringBefore('/')
                return seg.takeIf { VIDEO_ID_REGEX.matches(it) }
            }

            // Query parameter ?v=VIDEO_ID
            uri.rawQuery?.split('&')?.forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = pair.substring(0, idx)
                    val value = pair.substring(idx + 1)
                    if (key == "v" && VIDEO_ID_REGEX.matches(value)) {
                        return value
                    }
                }
            }

            // Path patterns: /shorts/{id}, /embed/{id}, /live/{id}, /v/{id}
            val pathMatch = Regex("^/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})(?:[/?#].*)?$")
                .find(path)?.groupValues?.getOrNull(1)
            return pathMatch?.takeIf { VIDEO_ID_REGEX.matches(it) }
        }

        fun parsePlayerResponseJson(
            jsonText: String,
            videoId: String,
            fallbackTitle: String = "YouTube_$videoId"
        ): ParsedYouTubeStream? {
            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
            val title = extractTitleFromPlayerJson(jsonText) ?: fallbackTitle
            val streamingData = root.optJSONObject("streamingData") ?: return null
            val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: return null

            var best: ParsedYouTubeStream? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until adaptive.length()) {
                val fmt = adaptive.optJSONObject(i) ?: continue
                val mime = fmt.optString("mimeType")
                val directUrl = fmt.optString("url")
                if (!mime.startsWith("audio/", ignoreCase = true) || directUrl.isBlank()) continue
                if (!isSafePublicUrlSyntax(directUrl)) continue

                val bitrate = fmt.optInt("bitrate", 0)
                // Prefer audio/mp4 (AAC) slightly over audio/webm (Opus), both work locally with FFmpeg
                val containerBonus = if (mime.contains("mp4", ignoreCase = true)) 250_000 else 0
                val score = bitrate + containerBonus
                if (score > bestScore) {
                    bestScore = score
                    best = ParsedYouTubeStream(
                        videoId = videoId,
                        title = title,
                        streamUrl = directUrl,
                        mimeType = mime,
                        bitrateBps = bitrate
                    )
                }
            }
            return best
        }

        fun extractTitleFromPlayerJson(jsonText: String): String? {
            return runCatching {
                JSONObject(jsonText)
                    .optJSONObject("videoDetails")
                    ?.optString("title")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        /**
         * Lightweight balanced-brace scanner for `ytInitialPlayerResponse = {...}` in watch HTML.
         */
        fun extractEmbeddedPlayerResponseJson(html: String): String? {
            val markerRegex = Regex("ytInitialPlayerResponse\\s*=\\s*\\{")
            val match = markerRegex.find(html) ?: return null
            val startBrace = html.indexOf('{', match.range.first)
            if (startBrace < 0) return null

            var depth = 0
            var inString = false
            var escaped = false
            val limit = minOf(html.length, startBrace + 600_000)

            for (i in startBrace until limit) {
                val ch = html[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (inString) {
                    if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return html.substring(startBrace, i + 1)
                        }
                    }
                }
            }
            return null
        }

        fun isSafePublicUrlSyntax(rawUrl: String): Boolean {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            if (host.isBlank() || host == "localhost" || host.endsWith(".local")) return false
            return !isDisallowedIpLiteral(host)
        }

        fun isSafePublicUrl(rawUrl: String): Boolean {
            if (!isSafePublicUrlSyntax(rawUrl)) return false
            val host = runCatching { URI(rawUrl.trim()).host?.lowercase() }.getOrNull() ?: return false
            return isSafePublicHost(host)
        }

        fun isSafePublicHost(host: String): Boolean {
            if (host.isBlank() || host == "localhost" || host.endsWith(".local")) return false
            // Quick literal IP checks before DNS lookup so unit tests also catch literals offline
            if (isDisallowedIpLiteral(host)) return false
            val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
                ?: return false
            for (addr in addresses) {
                if (addr.isAnyLocalAddress || addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress
                ) {
                    return false
                }
                if (addr is Inet4Address) {
                    val b = addr.address
                    val b0 = b[0].toInt() and 0xFF
                    val b1 = b[1].toInt() and 0xFF
                    if (b0 == 100 && b1 in 64..127) return false
                    if (b0 == 169 && b1 == 254) return false
                    if (b0 == 0) return false
                } else if (addr is Inet6Address) {
                    val b0 = addr.address[0].toInt() and 0xFF
                    if ((b0 and 0xFE) == 0xFC) return false
                }
            }
            return true
        }

        private fun isDisallowedIpLiteral(host: String): Boolean {
            val clean = host.trim('[', ']')
            if (clean == "::1" || clean == "::" || clean.startsWith("fe80:", ignoreCase = true) ||
                clean.startsWith("fc", ignoreCase = true) || clean.startsWith("fd", ignoreCase = true)
            ) {
                return true
            }
            val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(clean)
                ?: return false
            val parts = ipv4.groupValues.drop(1).map { it.toIntOrNull() ?: return true }
            if (parts.any { it !in 0..255 }) return true
            val (a, b, _, _) = parts
            return a == 0 ||
                a == 10 ||
                a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                a >= 224
        }
    }
}
