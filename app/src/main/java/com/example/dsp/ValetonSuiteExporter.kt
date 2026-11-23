package com.example.dsp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.example.data.TonePatchEntity
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ExportPatchRequest(
    val patch: TonePatchEntity,
    val exportFileName: String
)

data class ExportedPatchEntry(
    val patchId: Long,
    val assignedSlot: String,
    val namFileName: String,
    val irFileName: String?,
    val presetFileName: String
)

data class ValetonExportReport(
    val exportedEntries: List<ExportedPatchEntry>,
    val folderDisplayName: String,
    val nextEmptySlotAfterExport: String
)

/**
 * Abstraction over a SAF DocumentTree or local directory for organizing
 * SnapTone (.nam), UserIR (.wav), and numbered Presets (P01..P99) folders.
 */
interface ValetonFolderTarget {
    val displayName: String
    fun ensureSubfolder(folderName: String): ValetonSubfolderTarget
}

interface ValetonSubfolderTarget {
    fun listEntryNames(): List<String>
    fun writeFile(fileName: String, mimeType: String, bytes: ByteArray): Boolean
}

class LocalFileValetonFolderTarget(private val rootDir: File) : ValetonFolderTarget {
    override val displayName: String = rootDir.name.ifBlank { rootDir.absolutePath }

    override fun ensureSubfolder(folderName: String): ValetonSubfolderTarget {
        val sub = File(rootDir, folderName).apply { mkdirs() }
        return object : ValetonSubfolderTarget {
            override fun listEntryNames(): List<String> =
                sub.listFiles()?.map { it.name }.orEmpty()

            override fun writeFile(fileName: String, mimeType: String, bytes: ByteArray): Boolean {
                val safeName = PackExporter.sanitizeReadableFileName(fileName.substringBeforeLast('.')) +
                    if (fileName.contains('.')) ".${fileName.substringAfterLast('.')}" else ""
                val out = File(sub, safeName)
                out.writeBytes(bytes)
                return out.exists() && out.length() == bytes.size.toLong()
            }
        }
    }
}

class SafValetonFolderTarget(
    private val context: Context,
    private val treeUri: Uri
) : ValetonFolderTarget {

    private val resolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootDocUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

    override val displayName: String
        get() = queryDisplayName(rootDocUri) ?: treeUri.lastPathSegment?.substringAfterLast(':') ?: "Valeton Suite"

    override fun ensureSubfolder(folderName: String): ValetonSubfolderTarget {
        val existingDocUri = findChildDocumentUri(rootDocId, folderName, onlyDirectories = true)
        val folderDocUri = existingDocUri ?: DocumentsContract.createDocument(
            resolver,
            rootDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            folderName
        ) ?: error("No se pudo crear la carpeta '$folderName' en Valeton Suite.")

        val folderDocId = DocumentsContract.getDocumentId(folderDocUri)
        return object : ValetonSubfolderTarget {
            override fun listEntryNames(): List<String> {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
                val names = ArrayList<String>()
                runCatching {
                    resolver.query(
                        childrenUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        while (col >= 0 && cursor.moveToNext()) {
                            cursor.getString(col)?.let { names.add(it) }
                        }
                    }
                }
                return names
            }

            override fun writeFile(fileName: String, mimeType: String, bytes: ByteArray): Boolean {
                val existing = findChildDocumentUri(folderDocId, fileName, onlyDirectories = false)
                if (existing != null) {
                    runCatching { DocumentsContract.deleteDocument(resolver, existing) }
                }
                val createdUri = DocumentsContract.createDocument(
                    resolver,
                    folderDocUri,
                    mimeType,
                    fileName
                ) ?: return false
                resolver.openOutputStream(createdUri, "wt")?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: return false
                return true
            }
        }
    }

    private fun findChildDocumentUri(parentDocId: String, targetName: String, onlyDirectories: Boolean): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: ""
                    if (name.equals(targetName, ignoreCase = true)) {
                        if (!onlyDirectories || mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val docId = cursor.getString(idCol)
                            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun queryDisplayName(docUri: Uri): String? {
        return runCatching {
            resolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}

/**
 * Exports generated SnapTones (.nam), Cabinet IRs (.wav), and Valeton GP-5 Preset descriptors
 * directly into the user-granted Valeton Suite directory, organizing files into `SnapTone/`,
 * `UserIR/`, and `Presets/` while sequentially occupying the next empty preset slots (P01..P99).
 */
object ValetonSuiteExporter {

    private const val PREFS_NAME = "valeton_suite_prefs"
    private const val KEY_TREE_URI = "valeton_tree_uri"
    private const val FOLDER_SNAPTONE = "SnapTone"
    private const val FOLDER_USER_IR = "UserIR"
    private const val FOLDER_PRESETS = "Presets"

    private val VALETON_CANDIDATE_PACKAGES = listOf(
        "com.valeton.gp5",
        "com.valeton.suite",
        "com.valeton.valetonsuite",
        "com.valeton.gp200",
        "com.valeton.gp100"
    )

    private val SLOT_PREFIX_REGEX = Regex("^[Pp](\\d{1,3})[\\s_.\\-].*$")

    fun getSavedTreeUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val persisted = context.contentResolver.persistedUriPermissions.any { perm ->
            perm.uri == uri && perm.isWritePermission
        }
        return if (persisted) uri else null
    }

    fun saveTreeUri(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .apply()
    }

    fun findInstalledValetonPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in VALETON_CANDIDATE_PACKAGES) {
            val installed = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            }.isSuccess
            if (installed && pm.getLaunchIntentForPackage(pkg) != null) {
                return pkg
            }
        }
        return null
    }

    fun launchValetonSuite(context: Context): Boolean {
        val pkg = findInstalledValetonPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun scanOccupiedSlotNumbers(existingPresetNames: List<String>): Set<Int> {
        val occupied = HashSet<Int>()
        for (entry in existingPresetNames) {
            val match = SLOT_PREFIX_REGEX.matchEntire(entry.trim()) ?: continue
            val slotNum = match.groupValues[1].toIntOrNull()
            if (slotNum != null && slotNum in 1..999) {
                occupied.add(slotNum)
            }
        }
        return occupied
    }

    fun formatSlotCode(slotNumber: Int): String =
        "P${slotNumber.toString().padStart(2, '0')}"

    fun findNextEmptySlotNumbers(occupiedSlots: Set<Int>, count: Int): List<Int> {
        val result = ArrayList<Int>(count)
        var candidate = 1
        while (result.size < count) {
            if (candidate !in occupiedSlots) {
                result.add(candidate)
            }
            candidate++
        }
        return result
    }

    fun previewNextEmptySlot(target: ValetonFolderTarget): String {
        val presetsFolder = target.ensureSubfolder(FOLDER_PRESETS)
        val occupied = scanOccupiedSlotNumbers(presetsFolder.listEntryNames())
        val nextNum = findNextEmptySlotNumbers(occupied, 1).firstOrNull() ?: 1
        return formatSlotCode(nextNum)
    }

    fun exportPatchesSequentially(
        target: ValetonFolderTarget,
        requests: List<ExportPatchRequest>
    ): ValetonExportReport {
        require(requests.isNotEmpty()) { "Selecciona al menos un parche para exportar." }

        val snapToneFolder = target.ensureSubfolder(FOLDER_SNAPTONE)
        val userIrFolder = target.ensureSubfolder(FOLDER_USER_IR)
        val presetsFolder = target.ensureSubfolder(FOLDER_PRESETS)

        val occupied = scanOccupiedSlotNumbers(presetsFolder.listEntryNames()).toMutableSet()
        val existingNamNames = snapToneFolder.listEntryNames().toMutableSet()
        val existingIrNames = userIrFolder.listEntryNames().toMutableSet()
        val assignedNumbers = findNextEmptySlotNumbers(occupied, requests.size)

        val exportedEntries = ArrayList<ExportedPatchEntry>(requests.size)

        requests.forEachIndexed { index, req ->
            val patch = req.patch
            val slotNum = assignedNumbers[index]
            occupied.add(slotNum)
            val slotCode = formatSlotCode(slotNum)

            val rawBase = req.exportFileName.ifBlank { patch.name }
            var baseName = PackExporter.sanitizeReadableFileName(rawBase)
            if ("$baseName.nam" in existingNamNames) {
                baseName = "${baseName}_$slotCode"
            }
            existingNamNames.add("$baseName.nam")

            val namFile = File(patch.namFilePath)
            require(namFile.exists() && namFile.length() > 0L) {
                "No se encontró el archivo .nam para '${patch.name}'."
            }
            val namFileName = "$baseName.nam"
            require(snapToneFolder.writeFile(namFileName, "application/octet-stream", namFile.readBytes())) {
                "No se pudo escribir '$namFileName' en $FOLDER_SNAPTONE."
            }

            var irFileName: String? = null
            val irFile = File(patch.irFilePath)
            if (irFile.exists() && irFile.length() > 0L) {
                var candidateIr = "${baseName}_IR.wav"
                if (candidateIr in existingIrNames) {
                    candidateIr = "${baseName}_${slotCode}_IR.wav"
                }
                existingIrNames.add(candidateIr)
                if (userIrFolder.writeFile(candidateIr, "audio/wav", irFile.readBytes())) {
                    irFileName = candidateIr
                }
            }

            val presetFileName = "${slotCode}_$baseName.json"
            val presetJson = buildValetonPresetDescriptorJson(
                slotCode = slotCode,
                slotNumber = slotNum,
                displayTitle = rawBase.trim().ifBlank { patch.name },
                patch = patch,
                namFileName = namFileName,
                irFileName = irFileName
            )
            require(presetsFolder.writeFile(presetFileName, "application/json", presetJson.toByteArray(Charsets.UTF_8))) {
                "No se pudo escribir '$presetFileName' en $FOLDER_PRESETS."
            }

            exportedEntries.add(
                ExportedPatchEntry(
                    patchId = patch.id,
                    assignedSlot = slotCode,
                    namFileName = namFileName,
                    irFileName = irFileName,
                    presetFileName = presetFileName
                )
            )
        }

        val nextAfter = formatSlotCode(findNextEmptySlotNumbers(occupied, 1).firstOrNull() ?: 1)
        return ValetonExportReport(
            exportedEntries = exportedEntries,
            folderDisplayName = target.displayName,
            nextEmptySlotAfterExport = nextAfter
        )
    }

    private fun buildValetonPresetDescriptorJson(
        slotCode: String,
        slotNumber: Int,
        displayTitle: String,
        patch: TonePatchEntity,
        namFileName: String,
        irFileName: String?
    ): String {
        val chain = ToneEngine.decodeGp5Chain(patch.gp5RecipeJson)
        val metrics = ToneEngine.decodeMetrics(patch.metricsJson)
        val modulesArr = JSONArray()
        for (m in chain.modules) {
            val paramsObj = JSONObject()
            m.params.forEach { (k, v) -> paramsObj.put(k, v) }
            modulesArr.put(
                JSONObject().apply {
                    put("code", m.moduleCode)
                    put("name", m.moduleName)
                    put("enabled", m.enabled)
                    put("model", m.modelOrType)
                    put("params", paramsObj)
                }
            )
        }

        return JSONObject().apply {
            put("format", "Valeton_GP5_Suite_Preset")
            put("version", "1.0")
            put("slot_code", slotCode)
            put("slot_index", slotNumber)
            put("preset_name", displayTitle.take(32))
            put("instrument_type", patch.instrumentType)
            put("category", patch.category)
            put("capture_mode", patch.captureMode)
            put("snap_tone_file", "$FOLDER_SNAPTONE/$namFileName")
            if (irFileName != null) {
                put("user_ir_file", "$FOLDER_USER_IR/$irFileName")
            }
            put("sample_rate", patch.sampleRate)
            put("ir_samples", patch.irSamples)
            put("amp_archetype", metrics.ampArchetype)
            put("cabinet_character", metrics.cabinetCharacter)
            put("signal_chain", modulesArr)
        }.toString(2)
    }
}
