package com.example.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

object ToneEngine {

    private val EVAL_FREQS_HZ = listOf(
        30f, 41f, 55f, 75f, 100f, 135f, 180f, 240f, 320f, 430f, 580f,
        780f, 1050f, 1400f, 1900f, 2550f, 3400f, 4500f, 6000f, 8000f, 10500f, 14000f
    )

    data class InstrumentDetectionResult(
        val detectedInstrument: InstrumentMode,
        val dominantFundamentalHz: Float,
        val spectralCentroidHz: Float,
        val subBassEnergyRatio: Float
    )

    /**
     * Detects whether a trimmed audio excerpt corresponds to a 4-string Standard-Tuned Electric Bass
     * (E1=41.2Hz, A1=55.0Hz, D2=73.4Hz, G2=98.0Hz) or an Electric Guitar (E2=82.4Hz+).
     */
    fun detectInstrument(
        samples48k: FloatArray,
        trimStartFrac: Float = 0f,
        trimEndFrac: Float = 1f
    ): InstrumentDetectionResult {
        val total = samples48k.size
        if (total == 0) {
            return InstrumentDetectionResult(InstrumentMode.GUITAR, 110f, 1200f, 0.1f)
        }
        val startIdx = (trimStartFrac.coerceIn(0f, 0.95f) * total).toInt().coerceIn(0, max(0, total - 1))
        val endIdx = (trimEndFrac.coerceIn(trimStartFrac + 0.03f, 1f) * total).toInt().coerceIn(startIdx + 1, total)
        val slice = samples48k.copyOfRange(startIdx, endIdx)

        val fftSize = 8192
        val mag = FftProcessor.computeAveragedMagnitudeSpectrum(slice, fftSize = fftSize, hopSize = 2048)
        val binHz = 48000f / fftSize
        return classifyInstrumentFromSpectrum(mag, binHz)
    }

    private fun classifyInstrumentFromSpectrum(mag: FloatArray, binHz: Float): InstrumentDetectionResult {
        val subBassE = bandEnergy(mag, binHz, 38f, 118f) // 4-string bass E1 (41.2Hz) .. Bb2 (116.5Hz)
        val lowMidGuitarE = bandEnergy(mag, binHz, 160f, 850f)
        val upperPresenceE = bandEnergy(mag, binHz, 1400f, 5200f)
        val subBassRatio = subBassE / max(1e-5f, lowMidGuitarE + upperPresenceE * 0.6f)
        val centroidHz = computeSpectralCentroid(mag, binHz)

        // Find strongest fundamental candidate in 38..360 Hz
        val loBin = (38f / binHz).toInt().coerceAtLeast(1)
        val hiBin = (360f / binHz).toInt().coerceAtMost(mag.lastIndex)
        var bestBin = loBin
        var bestScore = -1f
        for (b in loBin..hiBin) {
            val f = b * binHz
            // Check 2nd & 3rd harmonic reinforcement for true pitch fundamental
            val h2 = mag[min(mag.lastIndex, (b * 2))]
            val h3 = mag[min(mag.lastIndex, (b * 3))]
            val score = mag[b] + 0.45f * h2 + 0.25f * h3
            if (score > bestScore && f >= 38f) {
                bestScore = score
                bestBin = b
            }
        }
        val f0 = bestBin * binHz

        val isBass = (subBassRatio > 0.72f && centroidHz < 980f) ||
            (f0 < 105f && subBassRatio > 0.52f && centroidHz < 1150f)

        return InstrumentDetectionResult(
            detectedInstrument = if (isBass) InstrumentMode.BASS_4 else InstrumentMode.GUITAR,
            dominantFundamentalHz = (f0 * 10f).roundToInt() / 10f,
            spectralCentroidHz = centroidHz,
            subBassEnergyRatio = subBassRatio
        )
    }

    fun analyzeAndSynthesize(
        decoded: DecodedAudio,
        patchName: String,
        instrumentMode: InstrumentMode,
        trimStartFrac: Float,
        trimEndFrac: Float,
        captureMode: CaptureMode,
        modelQuality: ModelQuality,
        irSampleRate: Int,
        irLengthSamples: Int,
        onProgress: (Float, String) -> Unit
    ): ToneAnalysisResult {
        onProgress(0.10f, "Recortando segmento de audio...")
        val total = decoded.samples48k.size
        val startIdx = (trimStartFrac.coerceIn(0f, 0.95f) * total).toInt().coerceIn(0, max(0, total - 1))
        val endIdx = (trimEndFrac.coerceIn(trimStartFrac + 0.03f, 1f) * total).toInt().coerceIn(startIdx + 1, total)
        val trimmed = decoded.samples48k.copyOfRange(startIdx, endIdx)

        // 8192-point FFT (~5.86 Hz/bin at 48 kHz) resolves 41.2 Hz E1 bass fundamental cleanly
        val fftSize = 8192
        onProgress(0.28f, "Calculando espectro Welch FFT (8192 pts)...")
        val magSpectrum = FftProcessor.computeAveragedMagnitudeSpectrum(trimmed, fftSize = fftSize, hopSize = 2048)
        val binHz = 48000f / fftSize

        val detection = classifyInstrumentFromSpectrum(magSpectrum, binHz)
        val resolvedInstrument = when (instrumentMode) {
            InstrumentMode.AUTO -> detection.detectedInstrument
            InstrumentMode.GUITAR -> InstrumentMode.GUITAR
            InstrumentMode.BASS_4 -> InstrumentMode.BASS_4
        }
        val isBass = resolvedInstrument == InstrumentMode.BASS_4

        onProgress(
            0.40f,
            if (isBass) "Extrayendo perfil de Bajo 4C (E1–G2)..." else "Extrayendo perfil de Guitarra Eléctrica..."
        )

        // Calibrated Reference DI Spectral Baseline per instrument
        val targetTransferMag = FloatArray(magSpectrum.size)
        for (k in magSpectrum.indices) {
            val freq = max(20f, k * binHz)
            val diBaseline = if (isBass) {
                // Standard 4-string P/J-Bass passive/active DI curve: strong E1..G2 fundamental weight
                val bassTilt = (120f / freq).pow(0.36f).coerceIn(0.06f, 2.6f)
                val pickupGrowl = 1f + 0.25f * exp(-((freq - 1400f) / 700f).pow(2))
                bassTilt * pickupGrowl
            } else {
                // Electric guitar pickup DI curve: -3.2 dB/oct rolloff + 2.35 kHz pickup resonance
                val diTilt = (300f / freq).pow(0.42f).coerceIn(0.08f, 2.2f)
                val pickupRes = 1f + 0.35f * exp(-((freq - 2350f) / 900f).pow(2))
                diTilt * pickupRes
            }
            targetTransferMag[k] = magSpectrum[k] / max(1e-4f, diBaseline)
        }

        // Smooth transfer magnitude using 1/6-octave Gaussian smoothing
        val smoothedTransfer = smoothSpectrum(targetTransferMag, binHz)
        // Normalize instrument passband median to unity
        val passLowHz = if (isBass) 55f else 200f
        val passHighHz = if (isBass) 1200f else 2000f
        val midStartBin = (passLowHz / binHz).toInt()
        val midEndBin = (passHighHz / binHz).toInt()
        var midSum = 0f
        for (k in midStartBin..midEndBin) midSum += smoothedTransfer[k]
        val midAvg = max(1e-4f, midSum / max(1, midEndBin - midStartBin + 1))
        for (k in smoothedTransfer.indices) {
            smoothedTransfer[k] = (smoothedTransfer[k] / midAvg).coerceIn(0.04f, 5.5f)
        }

        // Instrument-specific cabinet bandpass protection
        val hpCutoffHz = if (isBass) 32f else 68f
        val lpCutoffHz = when {
            isBass && captureMode == CaptureMode.FULL_RIG -> 5200f
            isBass -> 6200f
            captureMode == CaptureMode.FULL_RIG -> 7200f
            else -> 8800f
        }
        for (k in smoothedTransfer.indices) {
            val f = k * binHz
            val hp = (f / hpCutoffHz).pow(2) / (1f + (f / hpCutoffHz).pow(2))
            val lp = 1f / (1f + (f / lpCutoffHz).pow(3))
            smoothedTransfer[k] = (smoothedTransfer[k] * hp * lp).coerceAtLeast(1e-4f)
        }

        onProgress(0.55f, "Analizando armónicos, THD y dinámica...")
        val harmonicStats = analyzeHarmonicsAndDynamics(trimmed, magSpectrum, binHz, isBass)
        val bandEq = extractBandEqDb(smoothedTransfer, binHz, isBass)
        val centroidHz = detection.spectralCentroidHz

        val category = classifyToneCategory(
            isBass = isBass,
            gainLevel = harmonicStats.gainLevel,
            centroidHz = centroidHz
        )
        val ampArchetype = inferAmpArchetype(isBass, category, harmonicStats.evenOddRatio, bandEq.midDb)
        val cabCharacter = inferCabCharacter(isBass, centroidHz, bandEq.bassDb, bandEq.trebleDb)

        onProgress(0.72f, "Reconstruyendo Impulse Response de fase mínima...")
        val ir48k = FftProcessor.minimumPhaseImpulseResponse(
            targetMagHalf = smoothedTransfer,
            fftSize = fftSize,
            outLength = if (irSampleRate == 48000) irLengthSamples else ((irLengthSamples * 48000f) / irSampleRate).roundToInt()
        )
        val finalIr = if (irSampleRate == 48000) {
            ir48k
        } else {
            val resampled = FftProcessor.resampleLinear(ir48k, 48000, irSampleRate)
            resampled.copyOf(irLengthSamples)
        }

        onProgress(0.86f, "Entrenando WaveNet (.nam) y cadena Valeton GP-5...")
        val driveGain = if (isBass) {
            0.78f + (harmonicStats.gainLevel / 100f) * 3.8f
        } else {
            0.85f + (harmonicStats.gainLevel / 100f) * 4.6f
        }
        val asymBias = when {
            harmonicStats.evenOddRatio > 1.15f -> 0.22f
            harmonicStats.evenOddRatio > 0.9f -> 0.12f
            else -> 0.04f
        }

        val namResult = NamModelExporter.buildCalibratedWaveNetNam(
            patchName = patchName,
            quality = modelQuality,
            sampleRate = irSampleRate,
            irSamples = finalIr,
            driveGain = driveGain,
            asymmetryBias = asymBias,
            evalFreqsHz = EVAL_FREQS_HZ,
            captureMode = captureMode,
            isBass = isBass
        )

        val refAnchorBin = (if (isBass) 110f else 300f) / binHz
        val refAnchorMag = max(1e-4f, magSpectrum[refAnchorBin.toInt().coerceIn(1, magSpectrum.lastIndex)])
        val refEvalDb = EVAL_FREQS_HZ.map { freq ->
            val bin = (freq / binHz).toInt().coerceIn(1, magSpectrum.lastIndex)
            (20.0 * log10((magSpectrum[bin] / refAnchorMag).toDouble().coerceAtLeast(1e-4))).toFloat()
                .coerceIn(-24f, 18f)
        }
        val irEvalDb = EVAL_FREQS_HZ.map { freq ->
            val bin = (freq / binHz).toInt().coerceIn(1, smoothedTransfer.lastIndex)
            (20.0 * log10(smoothedTransfer[bin].toDouble().coerceAtLeast(1e-4))).toFloat().coerceIn(-24f, 18f)
        }

        val refMed = refEvalDb.sorted()[refEvalDb.size / 2]
        val modMed = namResult.modeledSpectrumDb.sorted()[namResult.modeledSpectrumDb.size / 2]
        val alignedModeledDb = namResult.modeledSpectrumDb.map { (it + (refMed - modMed)).coerceIn(-24f, 18f) }

        val metrics = ToneMetrics(
            instrumentType = resolvedInstrument.code,
            detectedFundamentalHz = harmonicStats.fundamentalHz,
            gainLevel = harmonicStats.gainLevel,
            thdPercent = harmonicStats.thdPercent,
            evenOddHarmonicRatio = harmonicStats.evenOddRatio,
            compressionSag = harmonicStats.compressionSag,
            spectralCentroidHz = centroidHz,
            bassDb = bandEq.bassDb,
            midDb = bandEq.midDb,
            trebleDb = bandEq.trebleDb,
            presenceDb = bandEq.presenceDb,
            lowCutHz = if (isBass) 35 else 80,
            highCutHz = if (isBass) 5200 else if (centroidHz > 2400f) 7800 else 6400,
            detectedCategory = category,
            cabinetCharacter = cabCharacter,
            ampArchetype = ampArchetype,
            trainingLossDb = namResult.trainingLossDb
        )

        val spectralCurve = SpectralCurveData(
            freqsHz = EVAL_FREQS_HZ,
            referenceDb = refEvalDb,
            modeledDb = alignedModeledDb,
            irCorrectionDb = irEvalDb
        )

        val gp5Chain = buildGp5SignalChain(metrics, captureMode, patchName, isBass)

        onProgress(1.0f, "SnapTone .nam e IR .wav generados.")
        return ToneAnalysisResult(
            patchName = patchName,
            resolvedInstrument = resolvedInstrument,
            metrics = metrics,
            spectralCurve = spectralCurve,
            gp5Chain = gp5Chain,
            namJson = namResult.namJson,
            irSamples = finalIr,
            irSampleRate = irSampleRate,
            trimmedInputSamples = trimmed,
            captureMode = captureMode,
            modelQuality = modelQuality
        )
    }

    private fun smoothSpectrum(mag: FloatArray, binHz: Float): FloatArray {
        val out = FloatArray(mag.size)
        for (k in mag.indices) {
            val f = max(20f, k * binHz)
            val bw = max(1, ((f * 0.14f) / binHz).toInt())
            val lo = max(0, k - bw)
            val hi = min(mag.lastIndex, k + bw)
            var sum = 0f
            for (j in lo..hi) sum += mag[j]
            out[k] = sum / (hi - lo + 1)
        }
        return out
    }

    private data class HarmonicStats(
        val fundamentalHz: Float,
        val gainLevel: Int,
        val thdPercent: Float,
        val evenOddRatio: Float,
        val compressionSag: Int
    )

    private fun analyzeHarmonicsAndDynamics(
        samples: FloatArray,
        mag: FloatArray,
        binHz: Float,
        isBass: Boolean
    ): HarmonicStats {
        var sumSq = 0.0
        var peak = 1e-5f
        var zeroCrossings = 0
        for (i in 1 until samples.size) {
            val s = samples[i]
            sumSq += s * s
            val a = abs(s)
            if (a > peak) peak = a
            if ((samples[i - 1] >= 0f && s < 0f) || (samples[i - 1] < 0f && s >= 0f)) {
                zeroCrossings++
            }
        }
        val rms = sqrt(sumSq / max(1, samples.size)).toFloat().coerceAtLeast(1e-5f)
        val crestDb = (20.0 * log10((peak / rms).toDouble())).toFloat().coerceIn(3f, 22f)
        val compressionSag = (((18f - crestDb) / 14f) * 100f).roundToInt().coerceIn(10, 96)

        // Search fundamental range tailored to 4-string standard bass (38..165 Hz) vs guitar (75..420 Hz)
        val minF = if (isBass) 38f else 75f
        val maxF = if (isBass) 165f else 420f
        val loBin = (minF / binHz).toInt().coerceAtLeast(1)
        val hiBin = (maxF / binHz).toInt().coerceAtMost(mag.size - 1)
        var f0Bin = loBin
        var f0Mag = 0f
        for (b in loBin..hiBin) {
            if (mag[b] > f0Mag) {
                f0Mag = mag[b]
                f0Bin = b
            }
        }
        val f0 = max(minF, f0Bin * binHz)

        fun harmonicEnergy(mult: Int): Float {
            val targetBin = ((f0 * mult) / binHz).roundToInt()
            if (targetBin >= mag.lastIndex) return 0f
            var mx = 0f
            for (d in -2..2) {
                val idx = (targetBin + d).coerceIn(0, mag.lastIndex)
                mx = max(mx, mag[idx])
            }
            return mx
        }

        val h1 = max(1e-5f, harmonicEnergy(1))
        val h2 = harmonicEnergy(2)
        val h3 = harmonicEnergy(3)
        val h4 = harmonicEnergy(4)
        val h5 = harmonicEnergy(5)

        val thdRatio = sqrt((h2 * h2 + h3 * h3 + h4 * h4 + h5 * h5).toDouble()) / h1
        val hfLow = if (isBass) 900f else 2400f
        val hfHigh = if (isBass) 4200f else 5800f
        val fundLow = if (isBass) 40f else 90f
        val fundHigh = if (isBass) 220f else 550f

        val hfDensity = bandEnergy(mag, binHz, hfLow, hfHigh) / max(1e-5f, bandEnergy(mag, binHz, fundLow, fundHigh))
        val zcrRate = zeroCrossings.toFloat() / max(1, samples.size)

        val rawThd = (thdRatio.toFloat() * 22f + hfDensity * (if (isBass) 15f else 18f) + zcrRate * 42f)
            .coerceIn(2.0f, 44.0f)
        val evenOdd = ((h2 + h4) / max(1e-5f, h3 + h5)).coerceIn(0.35f, 2.2f)
        val gainLevel = ((rawThd * 1.55f) + (compressionSag * 0.35f)).roundToInt().coerceIn(8, 98)

        return HarmonicStats(
            fundamentalHz = (f0 * 10f).roundToInt() / 10f,
            gainLevel = gainLevel,
            thdPercent = (rawThd * 10f).roundToInt() / 10f,
            evenOddRatio = (evenOdd * 100f).roundToInt() / 100f,
            compressionSag = compressionSag
        )
    }

    private fun bandEnergy(mag: FloatArray, binHz: Float, fLow: Float, fHigh: Float): Float {
        val lo = (fLow / binHz).toInt().coerceIn(0, mag.lastIndex)
        val hi = (fHigh / binHz).toInt().coerceIn(lo, mag.lastIndex)
        var sum = 0.0
        for (b in lo..hi) sum += mag[b] * mag[b]
        return sqrt(sum / max(1, hi - lo + 1)).toFloat()
    }

    private data class BandEq(val bassDb: Float, val midDb: Float, val trebleDb: Float, val presenceDb: Float)

    private fun extractBandEqDb(transfer: FloatArray, binHz: Float, isBass: Boolean): BandEq {
        fun dbFor(fLo: Float, fHi: Float): Float {
            val e = bandEnergy(transfer, binHz, fLo, fHi)
            return ((20.0 * log10(e.toDouble().coerceAtLeast(1e-4))).toFloat()).coerceIn(-12f, 12f)
        }
        return if (isBass) {
            BandEq(
                bassDb = (dbFor(40f, 115f) * 10f).roundToInt() / 10f,
                midDb = (dbFor(220f, 580f) * 10f).roundToInt() / 10f,
                trebleDb = (dbFor(950f, 2200f) * 10f).roundToInt() / 10f,
                presenceDb = (dbFor(2600f, 4800f) * 10f).roundToInt() / 10f
            )
        } else {
            BandEq(
                bassDb = (dbFor(75f, 200f) * 10f).roundToInt() / 10f,
                midDb = (dbFor(420f, 1100f) * 10f).roundToInt() / 10f,
                trebleDb = (dbFor(1800f, 3800f) * 10f).roundToInt() / 10f,
                presenceDb = (dbFor(4000f, 6500f) * 10f).roundToInt() / 10f
            )
        }
    }

    private fun computeSpectralCentroid(mag: FloatArray, binHz: Float): Float {
        var num = 0.0
        var den = 0.0
        for (k in 1 until mag.size) {
            val f = k * binHz
            if (f in 35f..12000f) {
                val w = mag[k].toDouble() * mag[k].toDouble()
                num += f * w
                den += w
            }
        }
        return (num / max(1e-9, den)).toFloat().coerceIn(110f, 6500f)
    }

    private fun classifyToneCategory(isBass: Boolean, gainLevel: Int, centroidHz: Float): String {
        if (isBass) return "Bass"
        return when {
            gainLevel < 32 -> "Clean"
            gainLevel < 58 -> "Crunch"
            gainLevel >= 76 && centroidHz in 1350f..2600f -> "Lead"
            else -> "High Gain"
        }
    }

    private fun inferAmpArchetype(isBass: Boolean, category: String, evenOdd: Float, midDb: Float): String {
        if (isBass) {
            return when {
                evenOdd < 0.85f -> "Darkglass / Modern Drive Bass"
                midDb > 1.5f -> "SVT Classic Tube 8x10 Growl"
                evenOdd > 1.15f -> "B-15 Vintage Motown Tube Clean"
                else -> "GK / Solid-State Punch Bass"
            }
        }
        return when (category) {
            "Clean" -> if (evenOdd > 1.05f) "US Blackface Tube Clean" else "JC Studio Solid-State Clean"
            "Crunch" -> if (midDb > 1.5f) "UK Plexi / JCM Crunch" else "Boutique Chime Class-A"
            "Lead" -> "Hot-Rodded Lead / Mark Boogie"
            else -> if (evenOdd < 0.85f) "Modern Recto / 5150 High-Gain" else "British High-Gain Stack"
        }
    }

    private fun inferCabCharacter(isBass: Boolean, centroidHz: Float, bassDb: Float, trebleDb: Float): String {
        if (isBass) {
            return when {
                centroidHz > 1100f -> "4x10 + Tweeter Modern Bass"
                bassDb > 2.2f && trebleDb < 0f -> "1x15 Vintage Warm Bass"
                trebleDb > 1.2f -> "SansAmp / Studio Bass DI"
                else -> "8x10 Sealed Classic Fridge"
            }
        }
        return when {
            bassDb > 2.0f && trebleDb < 0f -> "4x12 Sealed Vintage 30"
            centroidHz > 2350f -> "2x12 Bright / Greenback"
            else -> "1x12 / 2x12 Studio Mic SM57"
        }
    }

    private fun buildGp5SignalChain(
        metrics: ToneMetrics,
        captureMode: CaptureMode,
        patchName: String,
        isBass: Boolean
    ): Gp5SignalChain {
        fun dbToKnob(db: Float): Int = (50f + db * 3.8f).roundToInt().coerceIn(5, 95)

        if (isBass) {
            val bassDrive = metrics.gainLevel > 52
            val modules = listOf(
                Gp5ModuleSetting(
                    moduleCode = "NR",
                    moduleName = "Bass Gate",
                    enabled = metrics.gainLevel >= 40,
                    modelOrType = "Low-End Smart Gate",
                    params = mapOf("Threshold" to (metrics.gainLevel * 0.55f).roundToInt().coerceIn(20, 62), "Decay" to 42)
                ),
                Gp5ModuleSetting(
                    moduleCode = "PRE",
                    moduleName = if (bassDrive) "Bass Drive" else "Opto Comp",
                    enabled = true,
                    modelOrType = if (bassDrive) "Micro Dark OD (Blend)" else "Bass Optical Comp",
                    params = if (bassDrive) {
                        mapOf("Drive" to (metrics.gainLevel * 0.45f).roundToInt().coerceIn(15, 65), "Blend" to 58, "Level" to 72)
                    } else {
                        mapOf("Sustain" to metrics.compressionSag.coerceIn(25, 78), "Level" to 74)
                    }
                ),
                Gp5ModuleSetting(
                    moduleCode = "AMP",
                    moduleName = "SnapTone (.nam)",
                    enabled = true,
                    modelOrType = patchName.take(18),
                    params = mapOf(
                        "Gain" to metrics.gainLevel,
                        "Level" to 74,
                        "Bass" to dbToKnob(metrics.bassDb),
                        "Mid" to dbToKnob(metrics.midDb),
                        "Treble" to dbToKnob(metrics.trebleDb)
                    )
                ),
                Gp5ModuleSetting(
                    moduleCode = "CAB",
                    moduleName = "Bass IR (.wav)",
                    enabled = captureMode == CaptureMode.AMP_PLUS_IR,
                    modelOrType = if (captureMode == CaptureMode.FULL_RIG) "OFF (Full Rig en .nam)" else metrics.cabinetCharacter,
                    params = mapOf(
                        "LowCut" to 12, // ~35 Hz preserving 41.2 Hz E1
                        "HighCut" to 52,
                        "Level" to 78
                    )
                ),
                Gp5ModuleSetting(
                    moduleCode = "EQ",
                    moduleName = "Bass Para EQ",
                    enabled = abs(metrics.midDb) > 1.2f || abs(metrics.presenceDb) > 1.2f,
                    modelOrType = "4-Band Bass EQ",
                    params = mapOf(
                        "60Hz" to dbToKnob(metrics.bassDb * 0.45f),
                        "400Hz" to dbToKnob(metrics.midDb * 0.45f),
                        "1.6kHz" to dbToKnob(metrics.trebleDb * 0.5f)
                    )
                ),
                Gp5ModuleSetting(
                    moduleCode = "REV",
                    moduleName = "Ambience",
                    enabled = false,
                    modelOrType = "Bypass (Graves limpios)",
                    params = emptyMap()
                )
            )
            return Gp5SignalChain(
                summary = "Bajo 4C (E-A-D-G) · ${metrics.ampArchetype} · ${metrics.cabinetCharacter}",
                modules = modules
            )
        }

        val nrThresh = when (metrics.detectedCategory) {
            "High Gain", "Lead" -> 58
            "Crunch" -> 38
            else -> 18
        }
        val preEnabled = metrics.gainLevel > 62 || metrics.detectedCategory == "Lead"
        val preModel = if (metrics.detectedCategory == "Lead") "Green OD (TS-Style)" else "Boost / Tight OD"

        val modules = listOf(
            Gp5ModuleSetting(
                moduleCode = "NR",
                moduleName = "Noise Gate",
                enabled = metrics.gainLevel >= 35,
                modelOrType = "Smart Gate",
                params = mapOf("Threshold" to nrThresh, "Decay" to 35)
            ),
            Gp5ModuleSetting(
                moduleCode = "PRE",
                moduleName = "Drive / Comp",
                enabled = preEnabled,
                modelOrType = preModel,
                params = mapOf(
                    "Drive" to if (metrics.detectedCategory == "High Gain") 18 else 42,
                    "Tone" to 62,
                    "Level" to 72
                )
            ),
            Gp5ModuleSetting(
                moduleCode = "AMP",
                moduleName = "SnapTone (.nam)",
                enabled = true,
                modelOrType = patchName.take(18),
                params = mapOf(
                    "Gain" to metrics.gainLevel,
                    "Level" to 70,
                    "Bass" to dbToKnob(metrics.bassDb),
                    "Mid" to dbToKnob(metrics.midDb),
                    "Treble" to dbToKnob(metrics.trebleDb)
                )
            ),
            Gp5ModuleSetting(
                moduleCode = "CAB",
                moduleName = "Cab IR (.wav)",
                enabled = captureMode == CaptureMode.AMP_PLUS_IR,
                modelOrType = if (captureMode == CaptureMode.FULL_RIG) "OFF (Full Rig en .nam)" else metrics.cabinetCharacter,
                params = mapOf(
                    "LowCut" to 28,
                    "HighCut" to ((metrics.highCutHz - 3000) / 70).coerceIn(30, 90),
                    "Level" to 75
                )
            ),
            Gp5ModuleSetting(
                moduleCode = "EQ",
                moduleName = "Post EQ",
                enabled = abs(metrics.presenceDb) > 1.5f,
                modelOrType = "Guitar EQ",
                params = mapOf(
                    "100Hz" to dbToKnob(metrics.bassDb * 0.4f),
                    "800Hz" to dbToKnob(metrics.midDb * 0.4f),
                    "4kHz" to dbToKnob(metrics.presenceDb * 0.6f)
                )
            ),
            Gp5ModuleSetting(
                moduleCode = "REV",
                moduleName = "Ambience",
                enabled = metrics.detectedCategory in listOf("Clean", "Lead"),
                modelOrType = if (metrics.detectedCategory == "Lead") "Plate Studio" else "Room Natural",
                params = mapOf("Mix" to if (metrics.detectedCategory == "Clean") 28 else 22, "Decay" to 38)
            )
        )

        val summary = "Guitarra · ${metrics.detectedCategory} · ${metrics.ampArchetype} · ${metrics.cabinetCharacter}"
        return Gp5SignalChain(summary = summary, modules = modules)
    }

    // Serialization helpers for Room persistence
    fun encodeSpectralCurve(curve: SpectralCurveData): String {
        return JSONObject().apply {
            put("freqsHz", JSONArray(curve.freqsHz.map { it.toDouble() }))
            put("referenceDb", JSONArray(curve.referenceDb.map { it.toDouble() }))
            put("modeledDb", JSONArray(curve.modeledDb.map { it.toDouble() }))
            put("irCorrectionDb", JSONArray(curve.irCorrectionDb.map { it.toDouble() }))
        }.toString()
    }

    fun decodeSpectralCurve(json: String): SpectralCurveData {
        val obj = JSONObject(json)
        fun arrToList(key: String): List<Float> {
            val a = obj.getJSONArray(key)
            return List(a.length()) { a.getDouble(it).toFloat() }
        }
        return SpectralCurveData(
            freqsHz = arrToList("freqsHz"),
            referenceDb = arrToList("referenceDb"),
            modeledDb = arrToList("modeledDb"),
            irCorrectionDb = arrToList("irCorrectionDb")
        )
    }

    fun encodeMetrics(m: ToneMetrics): String {
        return JSONObject().apply {
            put("instrumentType", m.instrumentType)
            put("detectedFundamentalHz", m.detectedFundamentalHz.toDouble())
            put("gainLevel", m.gainLevel)
            put("thdPercent", m.thdPercent.toDouble())
            put("evenOddHarmonicRatio", m.evenOddHarmonicRatio.toDouble())
            put("compressionSag", m.compressionSag)
            put("spectralCentroidHz", m.spectralCentroidHz.toDouble())
            put("bassDb", m.bassDb.toDouble())
            put("midDb", m.midDb.toDouble())
            put("trebleDb", m.trebleDb.toDouble())
            put("presenceDb", m.presenceDb.toDouble())
            put("lowCutHz", m.lowCutHz)
            put("highCutHz", m.highCutHz)
            put("detectedCategory", m.detectedCategory)
            put("cabinetCharacter", m.cabinetCharacter)
            put("ampArchetype", m.ampArchetype)
            put("trainingLossDb", m.trainingLossDb.toDouble())
        }.toString()
    }

    fun decodeMetrics(json: String): ToneMetrics {
        val o = runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return ToneMetrics(
            instrumentType = o.optString("instrumentType", InstrumentMode.GUITAR.code),
            detectedFundamentalHz = o.optDouble("detectedFundamentalHz", 110.0).toFloat(),
            gainLevel = o.optInt("gainLevel", 45),
            thdPercent = o.optDouble("thdPercent", 8.0).toFloat(),
            evenOddHarmonicRatio = o.optDouble("evenOddHarmonicRatio", 1.0).toFloat(),
            compressionSag = o.optInt("compressionSag", 25),
            spectralCentroidHz = o.optDouble("spectralCentroidHz", 1200.0).toFloat(),
            bassDb = o.optDouble("bassDb", 0.0).toFloat(),
            midDb = o.optDouble("midDb", 0.0).toFloat(),
            trebleDb = o.optDouble("trebleDb", 0.0).toFloat(),
            presenceDb = o.optDouble("presenceDb", 0.0).toFloat(),
            lowCutHz = o.optInt("lowCutHz", 80),
            highCutHz = o.optInt("highCutHz", 6800),
            detectedCategory = o.optString("detectedCategory", "Crunch"),
            cabinetCharacter = o.optString("cabinetCharacter", "4x12 Studio"),
            ampArchetype = o.optString("ampArchetype", "SnapTone Custom"),
            trainingLossDb = o.optDouble("trainingLossDb", -24.0).toFloat()
        )
    }

    fun encodeGp5Chain(chain: Gp5SignalChain): String {
        val modulesArr = JSONArray()
        for (mod in chain.modules) {
            val paramsObj = JSONObject()
            for ((k, v) in mod.params) paramsObj.put(k, v)
            modulesArr.put(JSONObject().apply {
                put("moduleCode", mod.moduleCode)
                put("moduleName", mod.moduleName)
                put("enabled", mod.enabled)
                put("modelOrType", mod.modelOrType)
                put("params", paramsObj)
            })
        }
        return JSONObject().apply {
            put("summary", chain.summary)
            put("modules", modulesArr)
        }.toString()
    }

    fun decodeGp5Chain(json: String): Gp5SignalChain {
        val o = runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val arr = o.optJSONArray("modules") ?: JSONArray()
        val modules = List(arr.length()) { idx ->
            val m = arr.optJSONObject(idx) ?: JSONObject()
            val pObj = m.optJSONObject("params") ?: JSONObject()
            val params = LinkedHashMap<String, Int>()
            val keys = pObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                params[key] = pObj.optInt(key, 50)
            }
            Gp5ModuleSetting(
                moduleCode = m.optString("moduleCode", "AMP"),
                moduleName = m.optString("moduleName", "SnapTone"),
                enabled = m.optBoolean("enabled", true),
                modelOrType = m.optString("modelOrType", "Custom"),
                params = params
            )
        }
        return Gp5SignalChain(summary = o.optString("summary", "Cadena GP-5"), modules = modules)
    }
}
