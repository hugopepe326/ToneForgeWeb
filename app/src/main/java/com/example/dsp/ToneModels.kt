package com.example.dsp

enum class InstrumentMode(
    val code: String,
    val label: String,
    val shortLabel: String,
    val tuningDescription: String
) {
    AUTO(
        code = "AUTO",
        label = "Auto-detectar",
        shortLabel = "Auto",
        tuningDescription = "Detecta automáticamente Guitarra o Bajo 4C (E-A-D-G)"
    ),
    GUITAR(
        code = "GUITAR",
        label = "Guitarra Eléctrica",
        shortLabel = "Guitarra",
        tuningDescription = "Afinación estándar E2–E4 (82.4 Hz – 6.5 kHz)"
    ),
    BASS_4(
        code = "BASS_4",
        label = "Bajo 4 Cuerdas",
        shortLabel = "Bajo 4C",
        tuningDescription = "Afinación estándar E1–A1–D2–G2 (41.2 Hz – 98.0 Hz)"
    )
}

enum class AudioSourceType(val code: String, val label: String) {
    FILE("FILE", "Archivo"),
    LINK("LINK", "Enlace Web"),
    MIC("MIC", "Micro / USB DI")
}

enum class CaptureMode(val code: String, val label: String, val description: String) {
    FULL_RIG(
        "FULL_RIG",
        "Full Rig (Amp + Cab)",
        "SnapTone .nam completo listo para cargar sin bloque CAB adicional"
    ),
    AMP_PLUS_IR(
        "AMP_PLUS_IR",
        "Amp + IR Separados",
        "SnapTone .nam de amplificador + archivo .wav IR dedicado para bloque CAB"
    )
}

enum class ModelQuality(
    val code: String,
    val label: String,
    val channels: Int,
    val dilations: List<Int>
) {
    GP5_NANO(
        "GP5_NANO",
        "GP-5 SnapTone (Nano)",
        channels = 8,
        dilations = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)
    ),
    STUDIO_LITE(
        "STUDIO_LITE",
        "Estudio (WaveNet Lite)",
        channels = 12,
        dilations = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)
    )
}

data class DecodedAudio(
    val samples48k: FloatArray,
    val sampleRate: Int = 48000,
    val durationSec: Float,
    val peakDbfs: Float,
    val rmsDbfs: Float,
    val waveformPeaks: List<Float>,
    val sourceLabel: String,
    val sourceType: AudioSourceType
)

data class ToneMetrics(
    val instrumentType: String = InstrumentMode.GUITAR.code, // "GUITAR" or "BASS_4"
    val detectedFundamentalHz: Float = 110f,
    val gainLevel: Int, // 0..100
    val thdPercent: Float, // 0..45%
    val evenOddHarmonicRatio: Float, // >1.0 = warm tube even, <1.0 = aggressive odd
    val compressionSag: Int, // 0..100
    val spectralCentroidHz: Float,
    val bassDb: Float, // -12..+12
    val midDb: Float, // -12..+12
    val trebleDb: Float, // -12..+12
    val presenceDb: Float, // -12..+12
    val lowCutHz: Int,
    val highCutHz: Int,
    val detectedCategory: String, // "Clean", "Crunch", "High Gain", "Lead", "Bass"
    val cabinetCharacter: String,
    val ampArchetype: String,
    val trainingLossDb: Float
)

data class SpectralCurveData(
    val freqsHz: List<Float>,
    val referenceDb: List<Float>,
    val modeledDb: List<Float>,
    val irCorrectionDb: List<Float>
)

data class Gp5ModuleSetting(
    val moduleCode: String, // "NR", "PRE", "AMP", "CAB", "EQ", "DLY", "REV"
    val moduleName: String,
    val enabled: Boolean,
    val modelOrType: String,
    val params: Map<String, Int> // knob -> 0..100
)

data class Gp5SignalChain(
    val summary: String,
    val modules: List<Gp5ModuleSetting>
)

data class ToneAnalysisResult(
    val patchName: String,
    val resolvedInstrument: InstrumentMode,
    val metrics: ToneMetrics,
    val spectralCurve: SpectralCurveData,
    val gp5Chain: Gp5SignalChain,
    val namJson: String,
    val irSamples: FloatArray,
    val irSampleRate: Int,
    val trimmedInputSamples: FloatArray,
    val captureMode: CaptureMode,
    val modelQuality: ModelQuality
)
