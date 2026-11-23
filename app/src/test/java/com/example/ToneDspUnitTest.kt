package com.example

import com.example.dsp.AudioDecoder
import com.example.dsp.AudioSourceType
import com.example.dsp.CaptureMode
import com.example.dsp.InstrumentMode
import com.example.dsp.ModelQuality
import com.example.dsp.ToneEngine
import com.example.dsp.WavIrExporter
import com.example.dsp.WaveNetInferenceEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToneDspUnitTest {

    @Test
    fun wavIrRoundTrip24BitMono() {
        val sr = 48000
        val ir = FloatArray(1024) { i ->
            if (i == 0) 0.9f else (sin(i * 0.25) * kotlin.math.exp(-i / 180.0)).toFloat() * 0.5f
        }
        val bytes = WavIrExporter.encodeWav24BitMono(ir, sr)
        val parsed = WavIrExporter.tryParseWav(bytes)
        assertNotNull(parsed)
        assertEquals(sr, parsed!!.sampleRate)
        assertEquals(1024, parsed.samplesMono.size)
        for (i in ir.indices) {
            assertTrue(abs(ir[i] - parsed.samplesMono[i]) < 1e-4f)
        }
    }

    @Test
    fun toneEngineGeneratesValidWaveNetNamAndIrForGuitar() {
        val sr = 48000
        // Simulate a saturated guitar riff at 220 Hz (A3) + bright harmonics
        val samples = FloatArray(sr * 2) { i ->
            val t = i.toFloat() / sr
            val raw = 0.5f * sin(2f * PI.toFloat() * 220f * t) +
                0.45f * sin(2f * PI.toFloat() * 660f * t) +
                0.35f * sin(2f * PI.toFloat() * 1760f * t)
            tanh((raw * 2.5f).toDouble()).toFloat() * 0.8f
        }
        val decoded = AudioDecoder.buildDecodedAudio(
            rawMono = samples,
            srcRate = sr,
            sourceLabel = "test_guitar_riff.wav",
            sourceType = AudioSourceType.FILE
        )

        val detection = ToneEngine.detectInstrument(decoded.samples48k)
        assertEquals(InstrumentMode.GUITAR, detection.detectedInstrument)

        val result = ToneEngine.analyzeAndSynthesize(
            decoded = decoded,
            patchName = "British Crunch Test",
            instrumentMode = InstrumentMode.AUTO,
            trimStartFrac = 0.1f,
            trimEndFrac = 0.9f,
            captureMode = CaptureMode.AMP_PLUS_IR,
            modelQuality = ModelQuality.GP5_NANO,
            irSampleRate = 48000,
            irLengthSamples = 1024,
            onProgress = { _, _ -> }
        )

        assertEquals(InstrumentMode.GUITAR, result.resolvedInstrument)
        assertEquals(80, result.metrics.lowCutHz)
        assertEquals(1024, result.irSamples.size)
        assertTrue(result.irSamples.all { it.isFinite() })

        val root = JSONObject(result.namJson)
        assertEquals("0.5.4", root.getString("version"))
        assertEquals("WaveNet", root.getString("architecture"))
        assertEquals(48000, root.getInt("sample_rate"))

        val weights = root.getJSONArray("weights")
        assertEquals(2817, weights.length())
        for (i in 0 until weights.length()) {
            assertTrue(!weights.getDouble(i).isNaN() && !weights.getDouble(i).isInfinite())
        }

        val engine = WaveNetInferenceEngine()
        val di = engine.synthesizeGuitarDiRiff(48000)
        val processed = engine.processWithNamAndIr(
            diSamples = di.copyOfRange(0, 4800),
            namJson = result.namJson,
            irSamples = result.irSamples,
            applyIr = true
        )
        assertEquals(4800, processed.size)
        assertTrue(processed.any { abs(it) > 0.05f })
        assertTrue(processed.all { it.isFinite() })
    }

    @Test
    fun toneEngineDetectsAndExtracts4StringStandardBass() {
        val sr = 48000
        // Simulate 4-string Standard Bass (E1 = 41.20 Hz + A1 = 55.00 Hz + tube growl harmonics)
        val samples = FloatArray(sr * 2) { i ->
            val t = i.toFloat() / sr
            val raw = 0.85f * sin(2f * PI.toFloat() * 41.20f * t) +
                0.35f * sin(2f * PI.toFloat() * 82.41f * t) +
                0.22f * sin(2f * PI.toFloat() * 123.60f * t) +
                0.15f * sin(2f * PI.toFloat() * 330f * t)
            tanh((raw * 1.9f).toDouble()).toFloat() * 0.85f
        }
        val decoded = AudioDecoder.buildDecodedAudio(
            rawMono = samples,
            srcRate = sr,
            sourceLabel = "test_bass_e1.wav",
            sourceType = AudioSourceType.FILE
        )

        val detection = ToneEngine.detectInstrument(decoded.samples48k)
        assertEquals(InstrumentMode.BASS_4, detection.detectedInstrument)
        assertTrue(detection.dominantFundamentalHz in 36f..62f)

        val result = ToneEngine.analyzeAndSynthesize(
            decoded = decoded,
            patchName = "SVT 4-String E1",
            instrumentMode = InstrumentMode.BASS_4,
            trimStartFrac = 0.05f,
            trimEndFrac = 0.95f,
            captureMode = CaptureMode.FULL_RIG,
            modelQuality = ModelQuality.GP5_NANO,
            irSampleRate = 48000,
            irLengthSamples = 1024,
            onProgress = { _, _ -> }
        )

        assertEquals(InstrumentMode.BASS_4, result.resolvedInstrument)
        assertEquals("Bass", result.metrics.detectedCategory)
        assertEquals(35, result.metrics.lowCutHz)
        assertEquals(30f, result.spectralCurve.freqsHz.first(), 0.01f)

        val root = JSONObject(result.namJson)
        assertEquals("Bass Full Rig", root.getJSONObject("metadata").getString("gear_type"))
        assertEquals(2817, root.getJSONArray("weights").length())

        val engine = WaveNetInferenceEngine()
        val bassDi = engine.synthesizeBass4DiRiff(48000)
        val processed = engine.processWithNamAndIr(
            diSamples = bassDi.copyOfRange(0, 4800),
            namJson = result.namJson,
            irSamples = result.irSamples,
            applyIr = false
        )
        assertEquals(4800, processed.size)
        assertTrue(processed.any { abs(it) > 0.05f })
        assertTrue(processed.all { it.isFinite() })
    }
}
