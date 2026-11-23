package com.example.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object FftProcessor {

    fun hannWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
    }

    /**
     * In-place Radix-2 Cooley-Tukey FFT. [real] and [imag] length must be a power of 2.
     */
    fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean = false) {
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be power of 2" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val ang = sign * 2.0 * PI / len
            val wLenRe = cos(ang)
            val wLenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val idx1 = i + k
                    val idx2 = idx1 + half
                    val uRe = real[idx1]
                    val uIm = imag[idx1]
                    val vRe = real[idx2] * wRe - imag[idx2] * wIm
                    val vIm = real[idx2] * wIm + imag[idx2] * wRe
                    real[idx1] = uRe + vRe
                    imag[idx1] = uIm + vIm
                    real[idx2] = uRe - vRe
                    imag[idx2] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1.0 / n
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }

    /**
     * Computes Welch-averaged linear magnitude spectrum (0..fftSize/2) across active frames.
     */
    fun computeAveragedMagnitudeSpectrum(
        samples: FloatArray,
        fftSize: Int = 4096,
        hopSize: Int = 1024
    ): FloatArray {
        val half = fftSize / 2 + 1
        if (samples.isEmpty()) return FloatArray(half) { 1e-4f }

        val window = hannWindow(fftSize)
        val accumPower = DoubleArray(half)
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)
        var frameCount = 0

        val padded = if (samples.size < fftSize) {
            FloatArray(fftSize).also { samples.copyInto(it) }
        } else {
            samples
        }

        var pos = 0
        while (pos + fftSize <= padded.size) {
            var frameEnergy = 0.0
            for (i in 0 until fftSize) {
                val s = padded[pos + i].toDouble()
                frameEnergy += s * s
                real[i] = s * window[i]
                imag[i] = 0.0
            }
            val frameRms = sqrt(frameEnergy / fftSize)
            if (frameRms > 0.0025 || frameCount == 0) {
                fft(real, imag, inverse = false)
                for (k in 0 until half) {
                    val p = real[k] * real[k] + imag[k] * imag[k]
                    accumPower[k] += p
                }
                frameCount++
            }
            pos += hopSize
        }

        val invCount = 1.0 / max(1, frameCount)
        val normScale = 2.0 / fftSize
        return FloatArray(half) { k ->
            (sqrt(accumPower[k] * invCount) * normScale).toFloat().coerceAtLeast(1e-6f)
        }
    }

    /**
     * Reconstructs a causal minimum-phase Impulse Response via Real Cepstrum lifting.
     */
    fun minimumPhaseImpulseResponse(
        targetMagHalf: FloatArray,
        fftSize: Int = 4096,
        outLength: Int = 1024
    ): FloatArray {
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)

        // Build full Hermitian-symmetric log-magnitude spectrum
        val half = fftSize / 2
        for (k in 0..half) {
            val mag = targetMagHalf[min(k, targetMagHalf.lastIndex)].toDouble().coerceAtLeast(1e-5)
            val logMag = ln(mag)
            real[k] = logMag
            if (k in 1 until half) {
                real[fftSize - k] = logMag
            }
        }

        // Real cepstrum via IFFT
        fft(real, imag, inverse = true)

        // Causal minimum-phase lifter in cepstral domain
        val liftedRe = DoubleArray(fftSize)
        val liftedIm = DoubleArray(fftSize)
        liftedRe[0] = real[0]
        for (n in 1 until half) {
            liftedRe[n] = 2.0 * real[n]
        }
        liftedRe[half] = real[half]

        // FFT back to complex log spectrum
        fft(liftedRe, liftedIm, inverse = false)

        // Exponentiate complex spectrum
        val specRe = DoubleArray(fftSize)
        val specIm = DoubleArray(fftSize)
        for (k in 0 until fftSize) {
            val amp = exp(liftedRe[k].coerceIn(-16.0, 16.0))
            val phase = liftedIm[k]
            specRe[k] = amp * cos(phase)
            specIm[k] = amp * sin(phase)
        }

        // IFFT to causal minimum-phase impulse response
        fft(specRe, specIm, inverse = true)

        val ir = FloatArray(outLength)
        var maxAbs = 1e-6f
        val fadeSamples = min(48, outLength / 8)
        for (i in 0 until outLength) {
            var v = specRe[i].toFloat()
            if (i >= outLength - fadeSamples) {
                val t = (outLength - 1 - i).toFloat() / fadeSamples
                v *= 0.5f * (1f - cos(PI.toFloat() * t))
            }
            ir[i] = v
            val a = kotlin.math.abs(v)
            if (a > maxAbs) maxAbs = a
        }

        // Remove DC residual & normalize to -0.35 dBFS (~0.96 peak)
        var mean = 0.0
        for (v in ir) mean += v
        mean /= ir.size
        var peakAfterDc = 1e-6f
        for (i in ir.indices) {
            ir[i] = (ir[i] - mean.toFloat())
            val a = kotlin.math.abs(ir[i])
            if (a > peakAfterDc) peakAfterDc = a
        }
        val normFactor = 0.96f / peakAfterDc
        for (i in ir.indices) {
            ir[i] *= normFactor
        }
        return ir
    }

    fun resampleLinear(samples: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || samples.isEmpty()) return samples
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = max(1, (samples.size / ratio).toInt())
        return FloatArray(outLen) { i ->
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, samples.lastIndex)
            val idx1 = min(idx0 + 1, samples.lastIndex)
            val frac = (srcPos - idx0).toFloat()
            samples[idx0] * (1f - frac) + samples[idx1] * frac
        }
    }

    fun computeWaveformPeaks(samples: FloatArray, bins: Int = 140): List<Float> {
        if (samples.isEmpty()) return List(bins) { 0f }
        val step = max(1, samples.size / bins)
        val peaks = MutableList(bins) { 0f }
        var globalMax = 1e-5f
        for (b in 0 until bins) {
            val start = b * step
            val end = min(samples.size, start + step)
            var pk = 0f
            for (i in start until end) {
                val a = kotlin.math.abs(samples[i])
                if (a > pk) pk = a
            }
            peaks[b] = pk
            if (pk > globalMax) globalMax = pk
        }
        return peaks.map { (it / globalMax).coerceIn(0.03f, 1f) }
    }
}
