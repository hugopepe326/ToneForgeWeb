package com.example.network

import android.content.Context
import com.example.dsp.AudioDecoder
import com.example.dsp.AudioOutputFormat
import com.example.dsp.AudioProcessor
import com.example.dsp.AudioSourceType
import com.example.dsp.DecodedAudio
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

sealed class LinkResolveResult {
    data class Success(val audio: DecodedAudio, val suggestedTitle: String) : LinkResolveResult()
    data class Failure(val userMessage: String) : LinkResolveResult()
}

object MediaLinkResolver {

    private const val MAX_DOWNLOAD_BYTES = 18L * 1024L * 1024L // 18 MB cap for fast local processing
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val url = req.url
            val scheme = url.scheme.lowercase()
            if ((scheme != "http" && scheme != "https") || !isSafePublicHost(url.host.lowercase())) {
                throw java.io.IOException("Dirección no permitida por seguridad.")
            }
            chain.proceed(req)
        }
        .build()

    suspend fun resolveAndDecode(context: Context, rawUrl: String): LinkResolveResult =
        withContext(Dispatchers.IO) {
            val trimmed = rawUrl.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: return@withContext LinkResolveResult.Failure("El enlace no tiene un formato URL válido.")

            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return@withContext LinkResolveResult.Failure("Solo se admiten enlaces http:// o https://.")
            }

            val host = uri.host?.lowercase()
                ?: return@withContext LinkResolveResult.Failure("El enlace no contiene un dominio válido.")

            if (!isSafePublicHost(host)) {
                return@withContext LinkResolveResult.Failure("Por seguridad, no se permiten direcciones locales o privadas.")
            }

            val tempFile = File(context.cacheDir, "link_audio_${System.currentTimeMillis()}.bin")
            try {
                when {
                    isDirectAudioUrl(trimmed) -> {
                        val title = uri.path?.substringAfterLast('/')?.substringBeforeLast('.')
                            ?.takeIf { it.isNotBlank() } ?: "Audio Web"
                        if (!downloadStream(trimmed, tempFile)) {
                            return@withContext LinkResolveResult.Failure(
                                "No se pudo descargar el archivo de audio desde esa URL."
                            )
                        }
                        val decoded = AudioDecoder.decodeFromFile(tempFile, title, AudioSourceType.LINK, context)
                        LinkResolveResult.Success(decoded, title)
                    }

                    YouTubeAudioExtractor.isYouTubeUrl(trimmed) -> {
                        resolveYouTube(trimmed, tempFile, context)
                    }

                    host.contains("spotify.com") -> {
                        resolveSpotify(trimmed, tempFile, context)
                    }

                    host.contains("tiktok.com") -> {
                        resolveTikTok(trimmed, tempFile, context)
                    }

                    else -> {
                        resolveGenericOpenGraphMedia(trimmed, tempFile, context)
                    }
                }
            } catch (e: Exception) {
                LinkResolveResult.Failure(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: "No se pudo extraer audio de este enlace. Prueba descargar el audio o usar Micro / USB DI."
                )
            } finally {
                runCatching { tempFile.delete() }
            }
        }

    private fun isDirectAudioUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".mp3") || clean.endsWith(".wav") ||
            clean.endsWith(".m4a") || clean.endsWith(".aac") ||
            clean.endsWith(".ogg") || clean.endsWith(".flac")
    }

    private suspend fun resolveYouTube(url: String, outFile: File, context: Context): LinkResolveResult {
        val extractor = YouTubeAudioExtractor()
        val audioProcessor = AudioProcessor(context)
        val processedWav = File(context.cacheDir, "yt_proc_${System.currentTimeMillis()}.wav")
        try {
            when (
                val ytRes = extractor.extractAudioToLocalFile(
                    context = context,
                    youtubeUrl = url,
                    outputFile = processedWav,
                    audioProcessor = audioProcessor,
                    targetFormat = AudioOutputFormat.WAV_24BIT,
                    sampleRateHz = 48000,
                    channels = 1
                )
            ) {
                is YouTubeExtractResult.Success -> {
                    val decoded = AudioDecoder.decodeFromFile(
                        file = ytRes.processResult.outputFile,
                        sourceLabel = ytRes.streamInfo.title,
                        sourceType = AudioSourceType.LINK,
                        context = context
                    )
                    return LinkResolveResult.Success(decoded, ytRes.streamInfo.title)
                }
                is YouTubeExtractResult.Failure -> {
                    // Fallback: if FFmpeg failed on an unusual environment, try direct stream download + MediaCodec
                    val directRes = extractor.downloadAudioContainer(url, outFile)
                    if (directRes.isSuccess) {
                        val streamInfo = directRes.getOrThrow()
                        val decoded = AudioDecoder.decodeFromFile(
                            file = outFile,
                            sourceLabel = streamInfo.title,
                            sourceType = AudioSourceType.LINK,
                            context = context
                        )
                        return LinkResolveResult.Success(decoded, streamInfo.title)
                    }
                    return LinkResolveResult.Failure(ytRes.userMessage)
                }
            }
        } finally {
            runCatching { processedWav.delete() }
        }
    }

    private fun resolveSpotify(url: String, outFile: File, context: Context): LinkResolveResult {
        val trackId = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?track/([A-Za-z0-9]+)")
            .find(url)?.groupValues?.getOrNull(1)
            ?: return LinkResolveResult.Failure(
                "Solo se pueden analizar enlaces directos de canciones de Spotify (open.spotify.com/track/...)."
            )

        var trackTitle = "Spotify_$trackId"
        runCatching {
            val oembed = "https://open.spotify.com/oembed?url=https://open.spotify.com/track/$trackId"
            client.newCall(Request.Builder().url(oembed).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) {
                        val t = JSONObject(body).optString("title")
                        if (t.isNotBlank()) trackTitle = t
                    }
                }
            }
        }

        // Inspect public Spotify embed page for official 30s MP3 preview URL
        val previewUrl = runCatching {
            val embedReq = Request.Builder()
                .url("https://open.spotify.com/embed/track/$trackId")
                .header("User-Agent", DESKTOP_UA)
                .build()
            client.newCall(embedReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string() ?: return@use null
                Regex("https://p\\.scdn\\.co/mp3-preview/[A-Za-z0-9_\\-?=&]+")
                    .find(html)?.value?.replace("\\u002F", "/")
            }
        }.getOrNull()

        if (!previewUrl.isNullOrBlank() && isValidRemoteMediaUrl(previewUrl)) {
            if (downloadStream(previewUrl, outFile)) {
                val decoded = AudioDecoder.decodeFromFile(outFile, trackTitle, AudioSourceType.LINK, context)
                return LinkResolveResult.Success(decoded, trackTitle)
            }
        }

        return LinkResolveResult.Failure(
            "\"$trackTitle\" en Spotify está protegido por DRM y no expone vista previa MP3 pública. " +
                "Usa la pestaña Micro / USB mientras reproduces el tema, o carga un archivo de audio local."
        )
    }

    private fun resolveTikTok(url: String, outFile: File, context: Context): LinkResolveResult {
        val html = fetchText(url)
            ?: return LinkResolveResult.Failure("No se pudo conectar con el enlace de TikTok.")

        val title = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(48)
            ?.ifBlank { "TikTok_Tone" } ?: "TikTok_Tone"

        val mediaCandidates = listOf(
            Regex("\"playUrl\"\\s*:\\s*\"(https:[^\"]+)\"").find(html)?.groupValues?.getOrNull(1),
            Regex("\"playAddr\"\\s*:\\s*\"(https:[^\"]+)\"").find(html)?.groupValues?.getOrNull(1),
            Regex("<meta\\s+property=\"og:audio\"\\s+content=\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
        ).mapNotNull { it?.replace("\\u002F", "/")?.replace("\\/", "/") }

        for (candidate in mediaCandidates) {
            if (isValidRemoteMediaUrl(candidate) && downloadStream(candidate, outFile)) {
                val decoded = AudioDecoder.decodeFromFile(outFile, title, AudioSourceType.LINK, context)
                return LinkResolveResult.Success(decoded, title)
            }
        }

        return LinkResolveResult.Failure(
            "TikTok bloqueó el flujo directo sin sesión para este clip. " +
                "Guarda el vídeo/audio en tu teléfono e impórtalo desde Archivo, o grábalo con Micro / USB."
        )
    }

    private fun resolveGenericOpenGraphMedia(url: String, outFile: File, context: Context): LinkResolveResult {
        val req = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return LinkResolveResult.Failure("El enlace respondió con código HTTP ${resp.code}.")
            }
            val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
            if (contentType.startsWith("audio/") || contentType.startsWith("video/")) {
                val bytes = resp.body?.byteStream()?.use { input ->
                    copyLimited(input, outFile, MAX_DOWNLOAD_BYTES)
                } ?: 0L
                if (bytes > 1024L) {
                    val decoded = AudioDecoder.decodeFromFile(outFile, "Audio Web", AudioSourceType.LINK, context)
                    return LinkResolveResult.Success(decoded, "Audio Web")
                }
            }
            val html = resp.body?.string()?.take(250_000) ?: ""
            val title = Regex("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"")
                .find(html)?.groupValues?.getOrNull(1)?.take(48) ?: "Web_Tone"
            val mediaUrl = Regex("<meta\\s+property=\"og:(?:audio|video)(?::secure_url)?\"\\s+content=\"([^\"]+)\"")
                .find(html)?.groupValues?.getOrNull(1)
                ?.replace("&amp;", "&")

            if (!mediaUrl.isNullOrBlank() && isValidRemoteMediaUrl(mediaUrl)) {
                if (downloadStream(mediaUrl, outFile)) {
                    val decoded = AudioDecoder.decodeFromFile(outFile, title, AudioSourceType.LINK, context)
                    return LinkResolveResult.Success(decoded, title)
                }
            }
        }
        return LinkResolveResult.Failure(
            "No se encontró un flujo de audio descargable en esa página. " +
                "Importa un archivo .mp3/.wav/.m4a o captura el sonido con Micro / USB."
        )
    }

    private fun fetchText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.take(400_000) else null
            }
        }.getOrNull()
    }

    private fun downloadStream(
        url: String,
        outFile: File,
        rangeLimitBytes: Long? = null
    ): Boolean {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
        if (rangeLimitBytes != null) {
            builder.header("Range", "bytes=0-${rangeLimitBytes - 1}")
        }
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return@use false
                val stream = resp.body?.byteStream() ?: return@use false
                val written = stream.use { copyLimited(it, outFile, MAX_DOWNLOAD_BYTES) }
                written > 2048L
            }
        }.getOrDefault(false)
    }

    private fun copyLimited(input: java.io.InputStream, outFile: File, limit: Long): Long {
        var total = 0L
        val buf = ByteArray(16 * 1024)
        outFile.outputStream().use { out ->
            while (total < limit) {
                val read = input.read(buf)
                if (read <= 0) break
                val toWrite = minOf(read.toLong(), limit - total).toInt()
                out.write(buf, 0, toWrite)
                total += toWrite
            }
        }
        return total
    }

    private fun isValidRemoteMediaUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return false
        return (scheme == "https" || scheme == "http") && isSafePublicHost(host)
    }

    private fun isSafePublicHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return false
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
                // Block 100.64.0.0/10 (CGNAT) and 169.254.0.0/16 (metadata)
                if (b0 == 100 && b1 in 64..127) return false
                if (b0 == 169 && b1 == 254) return false
                if (b0 == 0) return false
            } else if (addr is Inet6Address) {
                val b0 = addr.address[0].toInt() and 0xFF
                if ((b0 and 0xFE) == 0xFC) return false // Unique local fc00::/7
            }
        }
        return true
    }
}
