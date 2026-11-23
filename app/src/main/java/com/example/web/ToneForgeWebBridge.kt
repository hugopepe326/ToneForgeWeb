package com.example.web

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import com.example.data.TonePatchEntity
import com.example.dsp.AudioDecoder
import com.example.dsp.AudioOutputFormat
import com.example.dsp.AudioProcessor
import com.example.dsp.AudioRecorderEngine
import com.example.dsp.AudioSourceType
import com.example.dsp.ExportPatchRequest
import com.example.dsp.Gp5ModuleSetting
import com.example.dsp.Gp5SignalChain
import com.example.dsp.PackExporter
import com.example.dsp.SafValetonFolderTarget
import com.example.dsp.ToneEngine
import com.example.dsp.ToneMetrics
import com.example.dsp.ValetonSuiteExporter
import com.example.network.LinkResolveResult
import com.example.network.MediaLinkResolver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ToneForgeWebBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val webViewProvider: () -> WebView?,
    private val onLaunchSaveDocument: (bytes: ByteArray, fileName: String, mimeType: String, label: String) -> Unit,
    private val onRequestRecordPermission: () -> Unit,
    private val onPickValetonFolder: () -> Unit
) {
    private val recorder = AudioRecorderEngine()
    private var meterJobs: List<Job> = emptyList()

    private fun evalJs(script: String) {
        val wv = webViewProvider() ?: return
        wv.post { wv.evaluateJavascript(script, null) }
    }

    private fun floatArrayToBase64(samples: FloatArray): String {
        val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putFloat(s)
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    @JavascriptInterface
    fun decodeAudioBase64(base64Data: String, label: String, sourceType: String) {
        scope.launch(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "web_dec_${System.currentTimeMillis()}.bin")
            val resultJson = runCatching {
                tmp.writeBytes(Base64.decode(base64Data, Base64.DEFAULT))
                val sType = if (sourceType == "LINK") AudioSourceType.LINK else AudioSourceType.FILE
                val decoded = AudioDecoder.decodeFromFile(tmp, label, sType, context)
                JSONObject().apply {
                    put("sourceLabel", decoded.sourceLabel)
                    put("pcm48kBase64", floatArrayToBase64(decoded.samples48k))
                }.toString()
            }.getOrElse { err ->
                JSONObject().put("error", err.message ?: "No se pudo decodificar el archivo.").toString()
            }
            runCatching { tmp.delete() }
            evalJs("window.__onNativeDecodeResult && window.__onNativeDecodeResult(${JSONObject.quote(resultJson)});")
        }
    }

    @JavascriptInterface
    fun extractFromLink(rawUrl: String) {
        scope.launch(Dispatchers.IO) {
            val resultJson = when (val res = MediaLinkResolver.resolveAndDecode(context, rawUrl)) {
                is LinkResolveResult.Success -> JSONObject().apply {
                    put("suggestedTitle", res.suggestedTitle)
                    put("pcm48kBase64", floatArrayToBase64(res.audio.samples48k))
                }.toString()
                is LinkResolveResult.Failure -> JSONObject().apply {
                    put("error", res.userMessage)
                }.toString()
            }
            evalJs("window.__onNativeLinkResult && window.__onNativeLinkResult(${JSONObject.quote(resultJson)});")
        }
    }

    @JavascriptInterface
    fun startNativeRecording() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            scope.launch(Dispatchers.Main) { onRequestRecordPermission() }
            val errJson = JSONObject().put("error", "Se requiere permiso de micrófono para grabar.").toString()
            evalJs("window.__onNativeRecDone && window.__onNativeRecDone(${JSONObject.quote(errJson)});")
            return
        }
        startNativeRecordingInternal()
    }

    fun startNativeRecordingInternal() {
        meterJobs.forEach { it.cancel() }
        val j1 = scope.launch {
            recorder.liveLevelNorm.collect { lvl ->
                val dur = recorder.liveDurationSec.value
                evalJs("window.__onNativeRecMeter && window.__onNativeRecMeter($lvl, $dur);")
            }
        }
        val j2 = scope.launch {
            val captured = recorder.startCapture(sampleRate = 48000, maxSeconds = 30)
            val resJson = if (captured != null) {
                JSONObject().apply {
                    put("label", "Toma_Directa_${System.currentTimeMillis() % 1000}")
                    put("pcm48kBase64", floatArrayToBase64(captured.samples48k))
                }.toString()
            } else {
                JSONObject().put("error", "Grabación demasiado corta o micrófono no disponible.").toString()
            }
            evalJs("window.__onNativeRecDone && window.__onNativeRecDone(${JSONObject.quote(resJson)});")
        }
        meterJobs = listOf(j1, j2)
    }

    @JavascriptInterface
    fun stopNativeRecording() {
        recorder.stopCapture()
    }

    @JavascriptInterface
    fun saveExportBase64(base64Data: String, fileName: String, mimeType: String, label: String) {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        scope.launch(Dispatchers.Main) {
            onLaunchSaveDocument(bytes, fileName, mimeType, label)
        }
    }

    @JavascriptInterface
    fun shareExportBase64(base64Data: String, fileName: String, mimeType: String, title: String) {
        scope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared_exports").apply { mkdirs() }
            val safe = PackExporter.sanitizeReadableFileName(fileName.substringBeforeLast('.')) +
                if (fileName.contains('.')) ".${fileName.substringAfterLast('.')}" else ""
            val out = File(dir, safe)
            out.writeBytes(Base64.decode(base64Data, Base64.DEFAULT))
            withContext(Dispatchers.Main) {
                PackExporter.shareFile(context, out, mimeType, title)
            }
        }
    }

    @JavascriptInterface
    fun convertIrBase64(irWavBase64: String, fmtCode: String, baseName: String, sampleRate: Int) {
        scope.launch(Dispatchers.IO) {
            val targetFmt = when (fmtCode.lowercase()) {
                "flac" -> AudioOutputFormat.FLAC
                "mp3" -> AudioOutputFormat.MP3
                "m4a" -> AudioOutputFormat.M4A_AAC
                "ogg" -> AudioOutputFormat.OGG_VORBIS
                "opus" -> AudioOutputFormat.OPUS
                else -> AudioOutputFormat.WAV_16BIT
            }
            val tempIn = File(context.cacheDir, "conv_in_${System.currentTimeMillis()}.wav")
            val tempOut = File(context.cacheDir, "conv_out_${System.currentTimeMillis()}.${targetFmt.extension}")
            val resJson = try {
                tempIn.writeBytes(Base64.decode(irWavBase64, Base64.DEFAULT))
                val processor = AudioProcessor(context)
                val res = processor.convertAudioFormat(
                    inputFile = tempIn,
                    outputFile = tempOut,
                    targetFormat = targetFmt,
                    sampleRateHz = sampleRate,
                    channels = 1,
                    bitrateKbps = 256
                )
                if (res.isSuccess) {
                    val outBytes = tempOut.readBytes()
                    val safe = PackExporter.sanitizeReadableFileName(baseName)
                    val outName = "${safe}_IR.${targetFmt.extension}"
                    withContext(Dispatchers.Main) {
                        onLaunchSaveDocument(outBytes, outName, targetFmt.mimeType, "Audio ${targetFmt.name}")
                    }
                    JSONObject().put("ok", true).toString()
                } else {
                    JSONObject().put("error", res.exceptionOrNull()?.message ?: "Error al convertir con FFmpeg.").toString()
                }
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "Error de conversión.").toString()
            } finally {
                runCatching { tempIn.delete() }
                runCatching { tempOut.delete() }
            }
            evalJs("window.__onNativeConvertResult && window.__onNativeConvertResult(${JSONObject.quote(resJson)});")
        }
    }

    @JavascriptInterface
    fun refreshValetonStatus(message: String? = null) {
        scope.launch(Dispatchers.IO) {
            val installedPkg = ValetonSuiteExporter.findInstalledValetonPackage(context)
            val savedTree = ValetonSuiteExporter.getSavedTreeUri(context)
            var folderName: String? = null
            var nextSlot = "P01"
            if (savedTree != null) {
                val target = runCatching { SafValetonFolderTarget(context, savedTree) }.getOrNull()
                folderName = target?.displayName
                nextSlot = target?.let { runCatching { ValetonSuiteExporter.previewNextEmptySlot(it) }.getOrNull() } ?: "P01"
            }
            val json = JSONObject().apply {
                put("installed", installedPkg != null)
                put("folderName", folderName ?: JSONObject.NULL)
                put("nextSlot", nextSlot)
                if (message != null) put("message", message)
            }.toString()
            evalJs("window.__onNativeValetonStatus && window.__onNativeValetonStatus(${JSONObject.quote(json)});")
        }
    }

    fun onValetonTreePicked(treeUri: Uri) {
        scope.launch(Dispatchers.IO) {
            ValetonSuiteExporter.saveTreeUri(context, treeUri)
            val target = runCatching { SafValetonFolderTarget(context, treeUri) }.getOrNull()
            val nextSlot = target?.let { runCatching { ValetonSuiteExporter.previewNextEmptySlot(it) }.getOrNull() } ?: "P01"
            refreshValetonStatus("Carpeta Valeton Suite configurada (${target?.displayName ?: "OK"}). Siguiente preset vacío: $nextSlot")
        }
    }

    @JavascriptInterface
    fun pickValetonFolder() {
        scope.launch(Dispatchers.Main) { onPickValetonFolder() }
    }

    @JavascriptInterface
    fun launchValetonSuite() {
        val ok = ValetonSuiteExporter.launchValetonSuite(context)
        if (!ok) {
            refreshValetonStatus("No se detectó Valeton Suite instalada; los archivos ya están guardados en tu carpeta.")
        }
    }

    @JavascriptInterface
    fun exportToValetonSuite(requestsJson: String) {
        scope.launch(Dispatchers.IO) {
            val treeUri = ValetonSuiteExporter.getSavedTreeUri(context)
            if (treeUri == null) {
                val errJson = JSONObject().put("error", "Primero selecciona la carpeta de Valeton Suite.").toString()
                evalJs("window.__onNativeValetonExported && window.__onNativeValetonExported(${JSONObject.quote(errJson)});")
                return@launch
            }
            val stageDir = File(context.cacheDir, "valeton_stage_${System.currentTimeMillis()}").apply { mkdirs() }
            val resultJson = try {
                val arr = JSONArray(requestsJson)
                val requests = ArrayList<ExportPatchRequest>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val patchId = obj.getLong("patchId")
                    val patchName = obj.getString("patchName")
                    val namFile = File(stageDir, "p_${i}.nam").apply {
                        writeText(obj.getString("namJson"), Charsets.UTF_8)
                    }
                    val irB64 = obj.optString("irWavBase64", "")
                    val irFile = File(stageDir, "p_${i}_IR.wav")
                    if (irB64.isNotBlank()) {
                        irFile.writeBytes(Base64.decode(irB64, Base64.DEFAULT))
                    }
                    val mObj = obj.optJSONObject("metrics") ?: JSONObject()
                    val metrics = ToneEngine.decodeMetrics(mObj.toString())
                    val cObj = obj.optJSONObject("gp5Chain") ?: JSONObject()
                    val chain = ToneEngine.decodeGp5Chain(cObj.toString())

                    val entity = TonePatchEntity(
                        id = patchId,
                        name = patchName,
                        instrumentType = obj.optString("instrumentType", "GUITAR"),
                        sourceType = "FILE",
                        sourceLabel = patchName,
                        category = obj.optString("category", "Crunch"),
                        gp5Slot = "P01",
                        captureMode = obj.optString("captureMode", "FULL_RIG"),
                        modelQuality = obj.optString("modelQuality", "GP5_NANO"),
                        sampleRate = obj.optInt("sampleRate", 48000),
                        irSamples = obj.optInt("irSamplesCount", 1024),
                        namFilePath = namFile.absolutePath,
                        irFilePath = irFile.absolutePath,
                        spectralCurveJson = "{}",
                        metricsJson = ToneEngine.encodeMetrics(metrics),
                        gp5RecipeJson = ToneEngine.encodeGp5Chain(chain)
                    )
                    requests.add(
                        ExportPatchRequest(
                            patch = entity,
                            exportFileName = obj.optString("exportFileName", patchName)
                        )
                    )
                }

                val target = SafValetonFolderTarget(context, treeUri)
                val report = ValetonSuiteExporter.exportPatchesSequentially(target, requests)
                val entriesArr = JSONArray()
                report.exportedEntries.forEach { e ->
                    entriesArr.put(
                        JSONObject().apply {
                            put("patchId", e.patchId)
                            put("assignedSlot", e.assignedSlot)
                            put("namFileName", e.namFileName)
                        }
                    )
                }
                val summary = report.exportedEntries.joinToString(", ") {
                    "${it.assignedSlot} (${it.namFileName})"
                }
                JSONObject().apply {
                    put("exportedEntries", entriesArr)
                    put("nextEmptySlotAfterExport", report.nextEmptySlotAfterExport)
                    put("summaryMessage", "Exportado a Valeton Suite [${report.folderDisplayName}]: $summary")
                }.toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "Error al exportar a Valeton Suite.").toString()
            } finally {
                runCatching { stageDir.deleteRecursively() }
            }
            evalJs("window.__onNativeValetonExported && window.__onNativeValetonExported(${JSONObject.quote(resultJson)});")
        }
    }

    fun cleanup() {
        meterJobs.forEach { it.cancel() }
        recorder.stopCapture()
    }
}
