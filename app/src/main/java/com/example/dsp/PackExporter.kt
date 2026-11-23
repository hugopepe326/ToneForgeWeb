package com.example.dsp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.TonePatchEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PackExporter {

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("\\.+"), ".")
            .trim('_', '.')
        return if (cleaned.isEmpty()) "ToneForge_Patch" else cleaned.take(40)
    }

    /**
     * Preserves readable spaces and hyphens for artist/song/sound export filenames
     * while stripping all path separators and filesystem-unsafe characters.
     */
    fun sanitizeReadableFileName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
            .replace("..", "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_')
        return if (cleaned.isEmpty()) "ToneForge_Patch" else cleaned.take(64)
    }

    fun buildGp5RecipeText(
        patchName: String,
        slot: String,
        captureMode: String,
        sampleRate: Int,
        irSamples: Int,
        metrics: ToneMetrics,
        chain: Gp5SignalChain
    ): String {
        val sb = StringBuilder()
        sb.appendLine("====================================================")
        sb.appendLine("TONEFORGE - GUÍA DE PARCELA PARA VALETON GP-5")
        sb.appendLine("====================================================")
        sb.appendLine("Parche: $patchName")
        val instrLabel = if (metrics.instrumentType == InstrumentMode.BASS_4.code) {
            "Bajo Eléctrico 4 Cuerdas (Std E1-A1-D2-G2)"
        } else {
            "Guitarra Eléctrica (Std E2-E4)"
        }
        sb.appendLine("Instrumento: $instrLabel (f0 ≈ ${metrics.detectedFundamentalHz} Hz)")
        sb.appendLine("Slot GP-5 asignado: $slot")
        sb.appendLine("Modo SnapTone: $captureMode")
        sb.appendLine("Audio IR: ${sampleRate} Hz · 24-bit Mono · $irSamples samples")
        sb.appendLine("Arquetipo detectado: ${metrics.ampArchetype}")
        sb.appendLine("Pantalla / Cab: ${metrics.cabinetCharacter}")
        sb.appendLine("Gain: ${metrics.gainLevel}/100 | THD: ${metrics.thdPercent}% | Pares/Impares: ${metrics.evenOddHarmonicRatio}")
        sb.appendLine("----------------------------------------------------")
        sb.appendLine("CADENA DE SEÑAL VALETON GP-5:")
        for (mod in chain.modules) {
            val state = if (mod.enabled) "ON " else "OFF"
            val paramsStr = mod.params.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            sb.appendLine("  [${mod.moduleCode}] $state | ${mod.modelOrType} ($paramsStr)")
        }
        sb.appendLine("----------------------------------------------------")
        sb.appendLine("PASOS PARA CARGAR EN VALETON GP-5:")
        sb.appendLine("1. Abre la app Valeton Suite (PC/Mac o móvil por Bluetooth/USB).")
        sb.appendLine("2. Ve al módulo AMP -> SnapTone e importa el archivo .nam generado.")
        sb.appendLine("3. Valeton Suite lo convertirá automáticamente al formato SnapTone de tu GP-5.")
        if (captureMode == CaptureMode.AMP_PLUS_IR.code) {
            sb.appendLine("4. Ve al módulo CAB -> User IR e importa el archivo _IR.wav (24-bit).")
        } else {
            sb.appendLine("4. Como este archivo es Full Rig, desactiva el módulo CAB del GP-5.")
        }
        sb.appendLine("5. Ajusta los bloques NR, PRE, EQ y REV según la tabla superior.")
        return sb.toString()
    }

    fun writeZipBundleBytes(patches: List<TonePatchEntity>): ByteArray =
        writeZipBundleBytesWithNames(patches.map { it to it.name })

    fun writeZipBundleBytesWithNames(patchesWithNames: List<Pair<TonePatchEntity, String>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val usedNames = HashSet<String>()
            for ((patch, customName) in patchesWithNames) {
                var base = sanitizeReadableFileName(customName.ifBlank { patch.name })
                if (!usedNames.add(base)) {
                    base = "${base}_${patch.id}"
                }
                val namFile = File(patch.namFilePath)
                if (namFile.exists()) {
                    zos.putNextEntry(ZipEntry("$base.nam"))
                    zos.write(namFile.readBytes())
                    zos.closeEntry()
                }
                val irFile = File(patch.irFilePath)
                if (irFile.exists()) {
                    zos.putNextEntry(ZipEntry("${base}_IR.wav"))
                    zos.write(irFile.readBytes())
                    zos.closeEntry()
                }
                val metrics = ToneEngine.decodeMetrics(patch.metricsJson)
                val chain = ToneEngine.decodeGp5Chain(patch.gp5RecipeJson)
                val guideText = buildGp5RecipeText(
                    patchName = customName.ifBlank { patch.name },
                    slot = patch.gp5Slot,
                    captureMode = patch.captureMode,
                    sampleRate = patch.sampleRate,
                    irSamples = patch.irSamples,
                    metrics = metrics,
                    chain = chain
                )
                zos.putNextEntry(ZipEntry("${base}_GP5_Guia.txt"))
                zos.write(guideText.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    fun writeBytesToUri(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: error("No se pudo escribir el archivo en la ubicación seleccionada.")
    }

    fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
