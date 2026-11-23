package com.example.dsp

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AudioOutputFormat(
    val extension: String,
    val codec: String,
    val containerFormat: String?,
    val mimeType: String,
    val supportsBitrate: Boolean
) {
    WAV_24BIT(
        extension = "wav",
        codec = "pcm_s24le",
        containerFormat = "wav",
        mimeType = "audio/wav",
        supportsBitrate = false
    ),
    WAV_16BIT(
        extension = "wav",
        codec = "pcm_s16le",
        containerFormat = "wav",
        mimeType = "audio/wav",
        supportsBitrate = false
    ),
    MP3(
        extension = "mp3",
        codec = "libmp3lame",
        containerFormat = "mp3",
        mimeType = "audio/mpeg",
        supportsBitrate = true
    ),
    M4A_AAC(
        extension = "m4a",
        codec = "aac",
        containerFormat = "ipod",
        mimeType = "audio/mp4",
        supportsBitrate = true
    ),
    FLAC(
        extension = "flac",
        codec = "flac",
        containerFormat = "flac",
        mimeType = "audio/flac",
        supportsBitrate = false
    ),
    OGG_VORBIS(
        extension = "ogg",
        codec = "libvorbis",
        containerFormat = "ogg",
        mimeType = "audio/ogg",
        supportsBitrate = true
    ),
    OPUS(
        extension = "opus",
        codec = "libopus",
        containerFormat = "opus",
        mimeType = "audio/opus",
        supportsBitrate = true
    )
}

data class LocalMediaInfo(
    val durationSec: Double,
    val sampleRateHz: Int,
    val channels: Int,
    val codecName: String,
    val bitrateBps: Long
)

data class AudioProcessResult(
    val outputFile: File,
    val format: AudioOutputFormat,
    val sampleRateHz: Int,
    val channels: Int,
    val fileSizeBytes: Long
)

/**
 * Abstraction over FFmpegKit/FFprobeKit execution enabling both native Android FFmpeg
 * execution and deterministic JVM unit testing without JNI loading.
 */
interface FfmpegExecutionBackend {
    fun execute(
        arguments: Array<String>,
        expectedDurationMs: Double,
        onProgress: ((Float) -> Unit)?
    ): FfmpegExecOutcome

    fun probe(localFilePath: String): LocalMediaInfo?

    fun cancelAll()
}

data class FfmpegExecOutcome(
    val success: Boolean,
    val returnCode: Int,
    val logs: String
)

class AndroidFfmpegKitBackend : FfmpegExecutionBackend {
    override fun execute(
        arguments: Array<String>,
        expectedDurationMs: Double,
        onProgress: ((Float) -> Unit)?
    ): FfmpegExecOutcome {
        if (onProgress != null && expectedDurationMs > 0.0) {
            FFmpegKitConfig.enableStatisticsCallback { stats ->
                val timeMs = stats.time.toDouble()
                if (timeMs >= 0.0) {
                    val progress = (timeMs / expectedDurationMs).toFloat().coerceIn(0f, 0.99f)
                    onProgress(progress)
                }
            }
        }
        return try {
            val session = FFmpegKit.executeWithArguments(arguments)
            val rc = session.returnCode
            val ok = ReturnCode.isSuccess(rc)
            if (ok) onProgress?.invoke(1f)
            FfmpegExecOutcome(
                success = ok,
                returnCode = rc?.value ?: -1,
                logs = session.failStackTrace ?: session.allLogsAsString.orEmpty()
            )
        } finally {
            FFmpegKitConfig.enableStatisticsCallback(null)
        }
    }

    override fun probe(localFilePath: String): LocalMediaInfo? {
        val session = FFprobeKit.getMediaInformation(localFilePath)
        val info = session.mediaInformation ?: return null
        val durationSec = info.duration?.toDoubleOrNull() ?: 0.0
        val bitrate = info.bitrate?.toLongOrNull() ?: 0L
        val audioStream = info.streams?.firstOrNull { it.type == "audio" }
        val sampleRate = audioStream?.sampleRate?.toIntOrNull() ?: 48000
        val channels = audioStream?.getNumberProperty("channels")?.toInt() ?: 1
        val codec = audioStream?.codec ?: "unknown"
        return LocalMediaInfo(
            durationSec = durationSec,
            sampleRateHz = sampleRate,
            channels = channels,
            codecName = codec,
            bitrateBps = bitrate
        )
    }

    override fun cancelAll() {
        FFmpegKit.cancel()
    }
}

/**
 * Local on-device FFmpeg audio processor for extracting audio tracks from local files
 * and converting between audio formats without any external server.
 */
class AudioProcessor(
    private val context: Context,
    private val backend: FfmpegExecutionBackend = AndroidFfmpegKitBackend()
) {

    /**
     * Probes local audio/video file metadata entirely offline using FFprobe.
     */
    suspend fun probeLocalMedia(inputFile: File): Result<LocalMediaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val safeInput = validateLocalInputFile(inputFile)
            backend.probe(safeInput.canonicalPath)
                ?: error("No se pudo leer la información multimedia del archivo local.")
        }
    }

    /**
     * Extracts the audio stream from a local audio or video file (e.g. MP4, MKV, MOV, WEBM, AVI, FLAC)
     * into the specified local output audio file and format, strictly on-device.
     */
    suspend fun extractAudioFromFile(
        inputFile: File,
        outputFile: File,
        targetFormat: AudioOutputFormat = AudioOutputFormat.WAV_24BIT,
        sampleRateHz: Int = 48000,
        channels: Int = 1,
        bitrateKbps: Int = 256,
        onProgress: ((Float) -> Unit)? = null
    ): Result<AudioProcessResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeInput = validateLocalInputFile(inputFile)
            val safeOutput = prepareLocalOutputFile(outputFile)
            val probeInfo = runCatching { backend.probe(safeInput.canonicalPath) }.getOrNull()
            val durationMs = (probeInfo?.durationSec ?: 0.0) * 1000.0

            val args = buildExtractionArgs(
                inputPath = safeInput.canonicalPath,
                outputPath = safeOutput.canonicalPath,
                targetFormat = targetFormat,
                sampleRateHz = sampleRateHz,
                channels = channels,
                bitrateKbps = bitrateKbps
            )

            val outcome = backend.execute(args, durationMs, onProgress)
            if (!outcome.success) {
                safeOutput.delete()
                error("Error de FFmpeg al extraer audio (código ${outcome.returnCode}): ${outcome.logs.takeLast(280)}")
            }
            require(safeOutput.exists() && safeOutput.length() > 0L) {
                "El archivo de salida quedó vacío tras la extracción."
            }

            AudioProcessResult(
                outputFile = safeOutput,
                format = targetFormat,
                sampleRateHz = sampleRateHz.coerceIn(8000, 192000),
                channels = channels.coerceIn(1, 2),
                fileSizeBytes = safeOutput.length()
            )
        }
    }

    /**
     * Extracts audio locally from an Android SAF [Uri] by staging into private app cache,
     * executing FFmpeg locally, and removing temporary files automatically.
     */
    suspend fun extractAudioFromUri(
        inputUri: Uri,
        outputFile: File,
        targetFormat: AudioOutputFormat = AudioOutputFormat.WAV_24BIT,
        sampleRateHz: Int = 48000,
        channels: Int = 1,
        bitrateKbps: Int = 256,
        onProgress: ((Float) -> Unit)? = null
    ): Result<AudioProcessResult> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "ffmpeg_staging").apply { mkdirs() }
        val stagedInput = File(tempDir, "in_${UUID.randomUUID()}.media")
        try {
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                stagedInput.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(
                IllegalStateException("No se pudo abrir el archivo local desde la URI.")
            )
            extractAudioFromFile(
                inputFile = stagedInput,
                outputFile = outputFile,
                targetFormat = targetFormat,
                sampleRateHz = sampleRateHz,
                channels = channels,
                bitrateKbps = bitrateKbps,
                onProgress = onProgress
            )
        } finally {
            runCatching { stagedInput.delete() }
        }
    }

    /**
     * Converts a local audio file into [targetFormat] with optional sample rate conversion,
     * channel mapping, time trimming, and peak/loudness normalization — 100% locally.
     */
    suspend fun convertAudioFormat(
        inputFile: File,
        outputFile: File,
        targetFormat: AudioOutputFormat,
        sampleRateHz: Int = 48000,
        channels: Int = 1,
        bitrateKbps: Int = 256,
        trimStartSec: Float? = null,
        trimDurationSec: Float? = null,
        normalizeAudio: Boolean = false,
        onProgress: ((Float) -> Unit)? = null
    ): Result<AudioProcessResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeInput = validateLocalInputFile(inputFile)
            val safeOutput = prepareLocalOutputFile(outputFile)
            val probeInfo = runCatching { backend.probe(safeInput.canonicalPath) }.getOrNull()
            val expectedDurationMs = when {
                trimDurationSec != null && trimDurationSec > 0f -> trimDurationSec * 1000.0
                probeInfo != null && probeInfo.durationSec > 0.0 -> probeInfo.durationSec * 1000.0
                else -> 0.0
            }

            val args = buildConversionArgs(
                inputPath = safeInput.canonicalPath,
                outputPath = safeOutput.canonicalPath,
                targetFormat = targetFormat,
                sampleRateHz = sampleRateHz,
                channels = channels,
                bitrateKbps = bitrateKbps,
                trimStartSec = trimStartSec,
                trimDurationSec = trimDurationSec,
                normalizeAudio = normalizeAudio
            )

            val outcome = backend.execute(args, expectedDurationMs, onProgress)
            if (!outcome.success) {
                safeOutput.delete()
                error("Error de FFmpeg al convertir formato (código ${outcome.returnCode}): ${outcome.logs.takeLast(280)}")
            }
            require(safeOutput.exists() && safeOutput.length() > 0L) {
                "El archivo convertido no contiene datos válidos."
            }

            AudioProcessResult(
                outputFile = safeOutput,
                format = targetFormat,
                sampleRateHz = sampleRateHz.coerceIn(8000, 192000),
                channels = channels.coerceIn(1, 2),
                fileSizeBytes = safeOutput.length()
            )
        }
    }

    /**
     * Extracts and converts audio from a YouTube URL by resolving/downloading the
     * stream via [YouTubeAudioExtractor] and transcoding strictly on-device with FFmpeg.
     */
    suspend fun extractAudioFromYouTubeUrl(
        youtubeUrl: String,
        outputFile: File,
        targetFormat: AudioOutputFormat = AudioOutputFormat.WAV_24BIT,
        sampleRateHz: Int = 48000,
        channels: Int = 1,
        bitrateKbps: Int = 256,
        extractor: com.example.network.YouTubeAudioExtractor = com.example.network.YouTubeAudioExtractor(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<AudioProcessResult> {
        return when (
            val res = extractor.extractAudioToLocalFile(
                context = context,
                youtubeUrl = youtubeUrl,
                outputFile = outputFile,
                audioProcessor = this,
                targetFormat = targetFormat,
                sampleRateHz = sampleRateHz,
                channels = channels,
                bitrateKbps = bitrateKbps,
                onProgress = onProgress
            )
        ) {
            is com.example.network.YouTubeExtractResult.Success -> Result.success(res.processResult)
            is com.example.network.YouTubeExtractResult.Failure -> Result.failure(
                IllegalStateException(res.userMessage)
            )
        }
    }

    fun cancelActiveProcessing() {
        backend.cancelAll()
    }

    internal fun buildExtractionArgs(
        inputPath: String,
        outputPath: String,
        targetFormat: AudioOutputFormat,
        sampleRateHz: Int,
        channels: Int,
        bitrateKbps: Int
    ): Array<String> {
        return buildConversionArgs(
            inputPath = inputPath,
            outputPath = outputPath,
            targetFormat = targetFormat,
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitrateKbps = bitrateKbps,
            trimStartSec = null,
            trimDurationSec = null,
            normalizeAudio = false
        )
    }

    internal fun buildConversionArgs(
        inputPath: String,
        outputPath: String,
        targetFormat: AudioOutputFormat,
        sampleRateHz: Int,
        channels: Int,
        bitrateKbps: Int,
        trimStartSec: Float?,
        trimDurationSec: Float?,
        normalizeAudio: Boolean
    ): Array<String> {
        val clampedRate = sampleRateHz.coerceIn(8000, 192000)
        val clampedChannels = channels.coerceIn(1, 2)
        val clampedBitrate = bitrateKbps.coerceIn(64, 320)

        val args = ArrayList<String>(24)
        args.add("-y")
        args.add("-nostdin")
        // Enforce local file protocol only — prohibits network protocols inside FFmpeg
        args.add("-protocol_whitelist")
        args.add("file,pipe")

        if (trimStartSec != null && trimStartSec > 0f) {
            args.add("-ss")
            args.add(String.format(java.util.Locale.US, "%.3f", trimStartSec))
        }

        args.add("-i")
        args.add(inputPath)

        if (trimDurationSec != null && trimDurationSec > 0f) {
            args.add("-t")
            args.add(String.format(java.util.Locale.US, "%.3f", trimDurationSec))
        }

        // Strip video/subtitles and map primary audio stream
        args.add("-vn")
        args.add("-sn")
        args.add("-dn")
        args.add("-map")
        args.add("0:a:0")

        if (normalizeAudio) {
            args.add("-af")
            args.add("loudnorm=I=-14:TP=-1.0:LRA=11")
        }

        args.add("-c:a")
        args.add(targetFormat.codec)
        args.add("-ar")
        args.add(clampedRate.toString())
        args.add("-ac")
        args.add(clampedChannels.toString())

        if (targetFormat.supportsBitrate) {
            args.add("-b:a")
            args.add("${clampedBitrate}k")
        }
        if (targetFormat.containerFormat != null) {
            args.add("-f")
            args.add(targetFormat.containerFormat)
        }
        args.add(outputPath)
        return args.toTypedArray()
    }

    private fun validateLocalInputFile(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.exists() && canonical.isFile && canonical.length() > 0L) {
            "El archivo local de entrada no existe o está vacío."
        }
        return canonical
    }

    private fun prepareLocalOutputFile(file: File): File {
        val canonical = file.canonicalFile
        canonical.parentFile?.mkdirs()
        return canonical
    }
}
