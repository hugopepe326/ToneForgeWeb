package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PatchRepository
import com.example.data.ToneDatabase
import com.example.data.TonePatchEntity
import com.example.dsp.AudioDecoder
import com.example.dsp.AudioOutputFormat
import com.example.dsp.AudioProcessor
import com.example.dsp.AudioRecorderEngine
import com.example.dsp.AudioSourceType
import com.example.dsp.AuditionMode
import com.example.dsp.CaptureMode
import com.example.dsp.DecodedAudio
import com.example.dsp.ExportPatchRequest
import com.example.dsp.Gp5SignalChain
import com.example.dsp.InstrumentMode
import com.example.dsp.ModelQuality
import com.example.dsp.PackExporter
import com.example.dsp.SafValetonFolderTarget
import com.example.dsp.SpectralCurveData
import com.example.dsp.ToneEngine
import com.example.dsp.ToneMetrics
import com.example.dsp.ValetonExportReport
import com.example.dsp.ValetonSuiteExporter
import com.example.dsp.WavIrExporter
import com.example.dsp.WaveNetInferenceEngine
import com.example.network.LinkResolveResult
import com.example.network.MediaLinkResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToneForgeUiState(
    val activeInputTab: AudioSourceType = AudioSourceType.FILE,
    val linkInput: String = "",
    val isLoadingSource: Boolean = false,
    val sourceError: String? = null,
    val decodedAudio: DecodedAudio? = null,
    val instrumentMode: InstrumentMode = InstrumentMode.AUTO,
    val detectionResult: ToneEngine.InstrumentDetectionResult? = null,
    val trimStartFrac: Float = 0f,
    val trimEndFrac: Float = 1f,
    val patchNameInput: String = "",
    val captureMode: CaptureMode = CaptureMode.FULL_RIG,
    val modelQuality: ModelQuality = ModelQuality.GP5_NANO,
    val irSampleRate: Int = 48000,
    val irLengthSamples: Int = 1024,
    val isRecording: Boolean = false,
    val recordingLevelNorm: Float = 0f,
    val recordingDurationSec: Float = 0f,
    val isAnalyzing: Boolean = false,
    val analysisProgress: Float = 0f,
    val analysisStatus: String = "",
    val selectedPatch: TonePatchEntity? = null,
    val selectedCurve: SpectralCurveData? = null,
    val selectedMetrics: ToneMetrics? = null,
    val selectedGp5Chain: Gp5SignalChain? = null,
    val activeAuditionMode: AuditionMode? = null,
    val searchQuery: String = "",
    val selectedCategoryFilter: String = "Todos",
    val multiSelectIds: Set<Long> = emptySet(),
    val isConvertingFormat: Boolean = false,
    val exportFileNameInput: String = "",
    val valetonFolderDisplayName: String? = null,
    val valetonInstalledPackage: String? = null,
    val nextEmptySlotPreview: String = "P01",
    val isExportingToValeton: Boolean = false,
    val statusMessage: String? = null
)

class ToneForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val repository = PatchRepository(ToneDatabase.getInstance(context).tonePatchDao())
    private val recorder = AudioRecorderEngine()
    private val inferencePlayer = WaveNetInferenceEngine()

    private val _uiState = MutableStateFlow(ToneForgeUiState())
    val uiState: StateFlow<ToneForgeUiState> = _uiState.asStateFlow()

    val patches: StateFlow<List<TonePatchEntity>> = repository.allPatches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var recordJob: Job? = null
    private var auditionJob: Job? = null
    private var lastTrimmedSamples: FloatArray? = null

    init {
        viewModelScope.launch {
            recorder.liveLevelNorm.collect { lvl ->
                _uiState.update { it.copy(recordingLevelNorm = lvl) }
            }
        }
        viewModelScope.launch {
            recorder.liveDurationSec.collect { dur ->
                _uiState.update { it.copy(recordingDurationSec = dur) }
            }
        }
        viewModelScope.launch {
            inferencePlayer.activeMode.collect { mode ->
                _uiState.update { it.copy(activeAuditionMode = mode) }
            }
        }
        refreshValetonSuiteStatus()
    }

    fun updateExportFileName(name: String) {
        _uiState.update { it.copy(exportFileNameInput = name) }
    }

    fun refreshValetonSuiteStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val installedPkg = ValetonSuiteExporter.findInstalledValetonPackage(context)
            val savedTree = ValetonSuiteExporter.getSavedTreeUri(context)
            val folderName: String?
            val nextSlot: String
            if (savedTree != null) {
                val target = runCatching { SafValetonFolderTarget(context, savedTree) }.getOrNull()
                folderName = target?.displayName
                nextSlot = target?.let { runCatching { ValetonSuiteExporter.previewNextEmptySlot(it) }.getOrNull() } ?: "P01"
            } else {
                folderName = null
                nextSlot = "P01"
            }
            _uiState.update {
                it.copy(
                    valetonInstalledPackage = installedPkg,
                    valetonFolderDisplayName = folderName,
                    nextEmptySlotPreview = nextSlot
                )
            }
        }
    }

    fun setValetonSuiteFolderUri(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            ValetonSuiteExporter.saveTreeUri(context, treeUri)
            val target = runCatching { SafValetonFolderTarget(context, treeUri) }.getOrNull()
            val nextSlot = target?.let { runCatching { ValetonSuiteExporter.previewNextEmptySlot(it) }.getOrNull() } ?: "P01"
            _uiState.update {
                it.copy(
                    valetonFolderDisplayName = target?.displayName ?: "Carpeta Valeton Suite",
                    nextEmptySlotPreview = nextSlot,
                    statusMessage = "Carpeta Valeton Suite configurada (${target?.displayName ?: "OK"}). Siguiente preset vacío: $nextSlot"
                )
            }
        }
    }

    fun selectInputTab(tab: AudioSourceType) {
        _uiState.update { it.copy(activeInputTab = tab, sourceError = null) }
    }

    fun updateLinkInput(url: String) {
        _uiState.update { it.copy(linkInput = url, sourceError = null) }
    }

    fun updatePatchNameInput(name: String) {
        _uiState.update { it.copy(patchNameInput = name) }
    }

    fun setInstrumentMode(mode: InstrumentMode) {
        _uiState.update { it.copy(instrumentMode = mode) }
    }

    fun updateTrimRange(start: Float, end: Float) {
        val s = start.coerceIn(0f, 0.94f)
        val e = end.coerceIn(s + 0.05f, 1f)
        _uiState.update { it.copy(trimStartFrac = s, trimEndFrac = e) }
        runLiveDetection()
    }

    private fun runLiveDetection() {
        val audio = _uiState.value.decodedAudio ?: return
        val s = _uiState.value.trimStartFrac
        val e = _uiState.value.trimEndFrac
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                ToneEngine.detectInstrument(audio.samples48k, s, e)
            }
            _uiState.update { it.copy(detectionResult = res) }
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        _uiState.update { it.copy(captureMode = mode) }
    }

    fun setModelQuality(quality: ModelQuality) {
        _uiState.update { it.copy(modelQuality = quality) }
    }

    fun setIrSampleRate(rate: Int) {
        _uiState.update { it.copy(irSampleRate = rate) }
    }

    fun setIrLengthSamples(len: Int) {
        _uiState.update { it.copy(irLengthSamples = len) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSource = true, sourceError = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    AudioDecoder.decodeFromUri(context, uri)
                }
            }.onSuccess { decoded ->
                val defaultName = decoded.sourceLabel.substringBeforeLast('.').take(28)
                _uiState.update {
                    it.copy(
                        isLoadingSource = false,
                        decodedAudio = decoded,
                        trimStartFrac = 0f,
                        trimEndFrac = 1f,
                        patchNameInput = defaultName.ifBlank { "Mi_SnapTone" },
                        sourceError = null
                    )
                }
                runLiveDetection()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingSource = false,
                        sourceError = err.message ?: "No se pudo decodificar el archivo de audio."
                    )
                }
            }
        }
    }

    fun extractFromLink() {
        val url = _uiState.value.linkInput.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(sourceError = "Pega un enlace de YouTube, TikTok, Spotify o audio directo.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSource = true, sourceError = null) }
            when (val res = MediaLinkResolver.resolveAndDecode(context, url)) {
                is LinkResolveResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSource = false,
                            decodedAudio = res.audio,
                            trimStartFrac = 0f,
                            trimEndFrac = 1f,
                            patchNameInput = res.suggestedTitle.take(28),
                            sourceError = null
                        )
                    }
                    runLiveDetection()
                }
                is LinkResolveResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSource = false,
                            sourceError = res.userMessage
                        )
                    }
                }
            }
        }
    }

    fun startRecording() {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(isRecording = true, sourceError = null) }
        recordJob = viewModelScope.launch {
            val captured = recorder.startCapture(sampleRate = 48000, maxSeconds = 30)
            if (captured != null) {
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        decodedAudio = captured,
                        trimStartFrac = 0f,
                        trimEndFrac = 1f,
                        patchNameInput = "Toma_Directa_${System.currentTimeMillis() % 1000}"
                    )
                }
                runLiveDetection()
            } else {
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        sourceError = "Grabación demasiado corta o micrófono no disponible."
                    )
                }
            }
        }
    }

    fun stopRecording() {
        recorder.stopCapture()
    }

    fun analyzeAndGenerate(onCompleteNavigateToAnalyzer: () -> Unit) {
        val state = _uiState.value
        val decoded = state.decodedAudio ?: return
        val patchName = state.patchNameInput.trim().ifBlank { "SnapTone_GP5" }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAnalyzing = true,
                    analysisProgress = 0.05f,
                    analysisStatus = "Iniciando motor DSP local..."
                )
            }
            runCatching {
                withContext(Dispatchers.Default) {
                    ToneEngine.analyzeAndSynthesize(
                        decoded = decoded,
                        patchName = patchName,
                        instrumentMode = state.instrumentMode,
                        trimStartFrac = state.trimStartFrac,
                        trimEndFrac = state.trimEndFrac,
                        captureMode = state.captureMode,
                        modelQuality = state.modelQuality,
                        irSampleRate = state.irSampleRate,
                        irLengthSamples = state.irLengthSamples,
                        onProgress = { prog, msg ->
                            _uiState.update { s ->
                                s.copy(analysisProgress = prog, analysisStatus = msg)
                            }
                        }
                    )
                }
            }.onSuccess { result ->
                val savedPatch = withContext(Dispatchers.IO) {
                    val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
                    val stamp = System.currentTimeMillis()
                    val safeName = PackExporter.sanitizeFileName(result.patchName)
                    val namFile = File(exportDir, "${safeName}_$stamp.nam")
                    val irFile = File(exportDir, "${safeName}_${stamp}_IR.wav")

                    namFile.writeText(result.namJson, Charsets.UTF_8)
                    WavIrExporter.writeWav24BitMono(result.irSamples, result.irSampleRate, irFile)

                    val existingCount = patches.value.size
                    val nextSlot = "P${((existingCount % 99) + 1).toString().padStart(2, '0')}"

                    val entity = TonePatchEntity(
                        name = result.patchName,
                        instrumentType = result.resolvedInstrument.code,
                        sourceType = decoded.sourceType.code,
                        sourceLabel = decoded.sourceLabel,
                        category = result.metrics.detectedCategory,
                        gp5Slot = nextSlot,
                        isFavorite = false,
                        captureMode = result.captureMode.code,
                        modelQuality = result.modelQuality.code,
                        sampleRate = result.irSampleRate,
                        irSamples = result.irSamples.size,
                        namFilePath = namFile.absolutePath,
                        irFilePath = irFile.absolutePath,
                        spectralCurveJson = ToneEngine.encodeSpectralCurve(result.spectralCurve),
                        metricsJson = ToneEngine.encodeMetrics(result.metrics),
                        gp5RecipeJson = ToneEngine.encodeGp5Chain(result.gp5Chain),
                        notes = "Generado localmente desde ${decoded.sourceLabel}"
                    )
                    val id = repository.insert(entity)
                    entity.copy(id = id)
                }

                lastTrimmedSamples = result.trimmedInputSamples
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        analysisProgress = 1f,
                        selectedPatch = savedPatch,
                        exportFileNameInput = savedPatch.name,
                        selectedCurve = result.spectralCurve,
                        selectedMetrics = result.metrics,
                        selectedGp5Chain = result.gp5Chain,
                        statusMessage = "Parche '${savedPatch.name}' guardado en slot ${savedPatch.gp5Slot}"
                    )
                }
                onCompleteNavigateToAnalyzer()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        sourceError = "Error en análisis DSP: ${err.message}"
                    )
                }
            }
        }
    }

    fun inspectPatch(patch: TonePatchEntity, onNavigate: () -> Unit) {
        viewModelScope.launch {
            val curve = runCatching { ToneEngine.decodeSpectralCurve(patch.spectralCurveJson) }.getOrNull()
            val metrics = runCatching { ToneEngine.decodeMetrics(patch.metricsJson) }.getOrNull()
            val chain = runCatching { ToneEngine.decodeGp5Chain(patch.gp5RecipeJson) }.getOrNull()
            _uiState.update {
                it.copy(
                    selectedPatch = patch,
                    exportFileNameInput = patch.name,
                    selectedCurve = curve,
                    selectedMetrics = metrics,
                    selectedGp5Chain = chain
                )
            }
            onNavigate()
        }
    }

    fun toggleAudition(mode: AuditionMode) {
        if (_uiState.value.activeAuditionMode == mode) {
            inferencePlayer.stop()
            auditionJob?.cancel()
            return
        }
        val patch = _uiState.value.selectedPatch ?: return
        auditionJob?.cancel()
        auditionJob = viewModelScope.launch {
            val namJson = withContext(Dispatchers.IO) {
                runCatching { File(patch.namFilePath).readText(Charsets.UTF_8) }.getOrNull()
            } ?: return@launch

            val irSamples = withContext(Dispatchers.IO) {
                runCatching {
                    val wav = WavIrExporter.tryParseWav(File(patch.irFilePath).readBytes())
                    wav?.samplesMono
                }.getOrNull()
            }

            val capMode = if (patch.captureMode == CaptureMode.FULL_RIG.code) {
                CaptureMode.FULL_RIG
            } else {
                CaptureMode.AMP_PLUS_IR
            }

            inferencePlayer.playAudition(
                mode = mode,
                namJson = namJson,
                irSamples = irSamples,
                captureMode = capMode,
                referenceSamples = lastTrimmedSamples,
                isBass = patch.instrumentType == InstrumentMode.BASS_4.code
            )
        }
    }

    fun stopAudition() {
        inferencePlayer.stop()
        auditionJob?.cancel()
    }

    fun getSelectedPatchNamBytes(): ByteArray? {
        val patch = _uiState.value.selectedPatch ?: return null
        return runCatching { File(patch.namFilePath).readBytes() }.getOrNull()
    }

    fun getSelectedPatchIrBytes(): ByteArray? {
        val patch = _uiState.value.selectedPatch ?: return null
        return runCatching { File(patch.irFilePath).readBytes() }.getOrNull()
    }

    fun getEffectiveExportBaseName(): String {
        val raw = _uiState.value.exportFileNameInput.ifBlank {
            _uiState.value.selectedPatch?.name ?: "ToneForge_Patch"
        }
        return PackExporter.sanitizeReadableFileName(raw)
    }

    fun getSelectedPatchZipBytes(): ByteArray? {
        val patch = _uiState.value.selectedPatch ?: return null
        val customName = getEffectiveExportBaseName()
        return runCatching { PackExporter.writeZipBundleBytesWithNames(listOf(patch to customName)) }.getOrNull()
    }

    fun getMultiSelectedZipBytes(customNames: Map<Long, String> = emptyMap()): ByteArray? {
        val selectedIds = _uiState.value.multiSelectIds
        val list = patches.value.filter { it.id in selectedIds }
        if (list.isEmpty()) return null
        return runCatching {
            PackExporter.writeZipBundleBytesWithNames(
                list.map { p -> p to (customNames[p.id] ?: p.name) }
            )
        }.getOrNull()
    }

    fun hasValetonFolderConfigured(): Boolean = ValetonSuiteExporter.getSavedTreeUri(context) != null

    fun exportToValetonSuite(
        requests: List<ExportPatchRequest>,
        onSuccess: ((ValetonExportReport) -> Unit)? = null
    ) {
        val treeUri = ValetonSuiteExporter.getSavedTreeUri(context)
        if (treeUri == null) {
            _uiState.update {
                it.copy(statusMessage = "Primero selecciona la carpeta de Valeton Suite en tu dispositivo.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingToValeton = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = SafValetonFolderTarget(context, treeUri)
                    val report = ValetonSuiteExporter.exportPatchesSequentially(target, requests)
                    report.exportedEntries.forEach { entry ->
                        repository.updateSlot(entry.patchId, entry.assignedSlot)
                    }
                    report
                }
            }.onSuccess { report ->
                val currentSelected = _uiState.value.selectedPatch
                val updatedSelected = currentSelected?.let { sel ->
                    val hit = report.exportedEntries.firstOrNull { it.patchId == sel.id }
                    if (hit != null) sel.copy(gp5Slot = hit.assignedSlot) else sel
                }
                val summary = report.exportedEntries.joinToString(", ") {
                    "${it.assignedSlot} (${it.namFileName})"
                }
                _uiState.update {
                    it.copy(
                        isExportingToValeton = false,
                        selectedPatch = updatedSelected,
                        nextEmptySlotPreview = report.nextEmptySlotAfterExport,
                        multiSelectIds = emptySet(),
                        statusMessage = "Exportado a Valeton Suite [${report.folderDisplayName}]: $summary"
                    )
                }
                onSuccess?.invoke(report)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isExportingToValeton = false,
                        statusMessage = err.message ?: "Error al exportar a la carpeta de Valeton Suite."
                    )
                }
            }
        }
    }

    fun launchValetonSuiteApp() {
        val launched = ValetonSuiteExporter.launchValetonSuite(context)
        if (!launched) {
            _uiState.update {
                it.copy(statusMessage = "No se detectó Valeton Suite instalada; los archivos ya están guardados en tu carpeta.")
            }
        }
    }

    fun shareSelectedFile(isNam: Boolean) {
        val patch = _uiState.value.selectedPatch ?: return
        val file = File(if (isNam) patch.namFilePath else patch.irFilePath)
        if (!file.exists()) {
            _uiState.update { it.copy(statusMessage = "Archivo no encontrado en almacenamiento local.") }
            return
        }
        val mime = if (isNam) "application/json" else "audio/wav"
        val title = if (isNam) "Compartir SnapTone (.nam)" else "Compartir Cab IR (.wav)"
        PackExporter.shareFile(context, file, mime, title)
    }

    fun convertSelectedIrWithFfmpeg(
        targetFormat: AudioOutputFormat,
        onReadyForSave: (bytes: ByteArray, suggestedFileName: String, label: String) -> Unit
    ) {
        val patch = _uiState.value.selectedPatch ?: return
        val irFile = File(patch.irFilePath)
        if (!irFile.exists() || irFile.length() == 0L) {
            _uiState.update { it.copy(statusMessage = "Archivo IR local no encontrado para convertir.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isConvertingFormat = true) }
            val tempOut = File(context.cacheDir, "ff_conv_${System.currentTimeMillis()}.${targetFormat.extension}")
            try {
                val processor = AudioProcessor(context)
                val res = processor.convertAudioFormat(
                    inputFile = irFile,
                    outputFile = tempOut,
                    targetFormat = targetFormat,
                    sampleRateHz = patch.sampleRate,
                    channels = 1,
                    bitrateKbps = 256
                )
                if (res.isSuccess) {
                    val bytes = withContext(Dispatchers.IO) { tempOut.readBytes() }
                    val safeName = getEffectiveExportBaseName()
                    val fileName = "${safeName}_IR.${targetFormat.extension}"
                    _uiState.update { it.copy(isConvertingFormat = false) }
                    onReadyForSave(bytes, fileName, "Audio ${targetFormat.name}")
                } else {
                    val msg = res.exceptionOrNull()?.message ?: "Error al convertir con FFmpeg."
                    _uiState.update { it.copy(isConvertingFormat = false, statusMessage = msg) }
                }
            } finally {
                runCatching { tempOut.delete() }
            }
        }
    }

    fun writeExportToUri(uri: Uri, bytes: ByteArray, label: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PackExporter.writeBytesToUri(context, uri, bytes)
                }
            }.onSuccess {
                _uiState.update { it.copy(statusMessage = "$label guardado correctamente.") }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = "Error al guardar: ${err.message}") }
            }
        }
    }

    // Patch Library management
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectCategoryFilter(category: String) {
        _uiState.update { it.copy(selectedCategoryFilter = category) }
    }

    fun toggleFavorite(patch: TonePatchEntity) {
        viewModelScope.launch {
            repository.setFavorite(patch.id, !patch.isFavorite)
            if (_uiState.value.selectedPatch?.id == patch.id) {
                _uiState.update { it.copy(selectedPatch = patch.copy(isFavorite = !patch.isFavorite)) }
            }
        }
    }

    fun updatePatchMetadata(patch: TonePatchEntity, newName: String, newSlot: String, newNotes: String, newCategory: String) {
        viewModelScope.launch {
            val updated = patch.copy(
                name = newName.trim().ifBlank { patch.name },
                gp5Slot = newSlot.trim().ifBlank { patch.gp5Slot },
                notes = newNotes.trim(),
                category = newCategory
            )
            repository.update(updated)
            if (_uiState.value.selectedPatch?.id == patch.id) {
                _uiState.update { it.copy(selectedPatch = updated) }
            }
            _uiState.update { it.copy(statusMessage = "Parche '${updated.name}' actualizado.") }
        }
    }

    fun deletePatch(patch: TonePatchEntity) {
        viewModelScope.launch {
            repository.delete(patch)
            _uiState.update { state ->
                state.copy(
                    multiSelectIds = state.multiSelectIds - patch.id,
                    selectedPatch = if (state.selectedPatch?.id == patch.id) null else state.selectedPatch,
                    statusMessage = "Parche '${patch.name}' eliminado."
                )
            }
        }
    }

    fun toggleMultiSelect(patchId: Long) {
        _uiState.update { state ->
            val next = if (patchId in state.multiSelectIds) {
                state.multiSelectIds - patchId
            } else {
                state.multiSelectIds + patchId
            }
            state.copy(multiSelectIds = next)
        }
    }

    fun clearMultiSelect() {
        _uiState.update { it.copy(multiSelectIds = emptySet()) }
    }

    override fun onCleared() {
        super.onCleared()
        recorder.stopCapture()
        inferencePlayer.stop()
    }
}
