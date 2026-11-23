// Pure-JS DSP Engine for ToneForge Web
// 1:1 faithful port of FftProcessor, ToneEngine, NamModelExporter, WavIrExporter, WaveNetInferenceEngine & PackExporter
(function (global) {
  'use strict';

  const TARGET_SAMPLE_RATE = 48000;
  const MAX_ANALYSIS_SECONDS = 60;
  const EVAL_FREQS_HZ = [
    30, 41, 55, 75, 100, 135, 180, 240, 320, 430, 580,
    780, 1050, 1400, 1900, 2550, 3400, 4500, 6000, 8000, 10500, 14000
  ];

  const INSTRUMENT_MODES = {
    AUTO: {
      code: 'AUTO',
      label: 'Auto-detectar',
      shortLabel: 'Auto',
      tuningDescription: 'Detecta automáticamente Guitarra o Bajo 4C (E-A-D-G)'
    },
    GUITAR: {
      code: 'GUITAR',
      label: 'Guitarra Eléctrica',
      shortLabel: 'Guitarra',
      tuningDescription: 'Afinación estándar E2–E4 (82.4 Hz – 6.5 kHz)'
    },
    BASS_4: {
      code: 'BASS_4',
      label: 'Bajo 4 Cuerdas',
      shortLabel: 'Bajo 4C',
      tuningDescription: 'Afinación estándar E1–A1–D2–G2 (41.2 Hz – 98.0 Hz)'
    }
  };

  const CAPTURE_MODES = {
    FULL_RIG: {
      code: 'FULL_RIG',
      label: 'Full Rig (Amp + Cab)',
      description: 'SnapTone .nam completo listo para cargar sin bloque CAB adicional'
    },
    AMP_PLUS_IR: {
      code: 'AMP_PLUS_IR',
      label: 'Amp + IR Separados',
      description: 'SnapTone .nam de amplificador + archivo .wav IR dedicado para bloque CAB'
    }
  };

  const MODEL_QUALITIES = {
    GP5_NANO: {
      code: 'GP5_NANO',
      label: 'GP-5 SnapTone (Nano)',
      channels: 8,
      dilations: [1, 2, 4, 8, 16, 32, 64, 128, 256, 512]
    },
    STUDIO_LITE: {
      code: 'STUDIO_LITE',
      label: 'Estudio (WaveNet Lite)',
      channels: 12,
      dilations: [1, 2, 4, 8, 16, 32, 64, 128, 256, 512]
    }
  };

  function clamp(v, lo, hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  function yieldTick() {
    return new Promise((resolve) => setTimeout(resolve, 0));
  }

  // =========================================================================
  // FftProcessor
  // =========================================================================
  const FftProcessor = {
    hannWindow(size) {
      const win = new Float32Array(size);
      const denom = size - 1;
      for (let i = 0; i < size; i++) {
        win[i] = 0.5 * (1.0 - Math.cos((2.0 * Math.PI * i) / denom));
      }
      return win;
    },

    fft(real, imag, inverse = false) {
      const n = real.length;
      if (n <= 0 || (n & (n - 1)) !== 0) {
        throw new Error('FFT size must be power of 2');
      }

      let j = 0;
      for (let i = 1; i < n; i++) {
        let bit = n >> 1;
        while ((j & bit) !== 0) {
          j ^= bit;
          bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
          const tr = real[i];
          real[i] = real[j];
          real[j] = tr;
          const ti = imag[i];
          imag[i] = imag[j];
          imag[j] = ti;
        }
      }

      const sign = inverse ? 1.0 : -1.0;
      for (let len = 2; len <= n; len <<= 1) {
        const ang = (sign * 2.0 * Math.PI) / len;
        const wLenRe = Math.cos(ang);
        const wLenIm = Math.sin(ang);
        const half = len >> 1;
        for (let i = 0; i < n; i += len) {
          let wRe = 1.0;
          let wIm = 0.0;
          for (let k = 0; k < half; k++) {
            const idx1 = i + k;
            const idx2 = idx1 + half;
            const uRe = real[idx1];
            const uIm = imag[idx1];
            const vRe = real[idx2] * wRe - imag[idx2] * wIm;
            const vIm = real[idx2] * wIm + imag[idx2] * wRe;
            real[idx1] = uRe + vRe;
            imag[idx1] = uIm + vIm;
            real[idx2] = uRe - vRe;
            imag[idx2] = uIm - vIm;
            const nextWRe = wRe * wLenRe - wIm * wLenIm;
            wIm = wRe * wLenIm + wIm * wLenRe;
            wRe = nextWRe;
          }
        }
      }

      if (inverse) {
        const invN = 1.0 / n;
        for (let i = 0; i < n; i++) {
          real[i] *= invN;
          imag[i] *= invN;
        }
      }
    },

    computeAveragedMagnitudeSpectrum(samples, fftSize = 4096, hopSize = 1024) {
      const half = (fftSize >> 1) + 1;
      const out = new Float32Array(half);
      if (!samples || samples.length === 0) {
        out.fill(1e-4);
        return out;
      }

      const window = this.hannWindow(fftSize);
      const accumPower = new Float64Array(half);
      const real = new Float64Array(fftSize);
      const imag = new Float64Array(fftSize);
      let frameCount = 0;

      let padded = samples;
      if (samples.length < fftSize) {
        padded = new Float32Array(fftSize);
        padded.set(samples);
      }

      let pos = 0;
      while (pos + fftSize <= padded.length) {
        let frameEnergy = 0.0;
        for (let i = 0; i < fftSize; i++) {
          const s = padded[pos + i];
          frameEnergy += s * s;
          real[i] = s * window[i];
          imag[i] = 0.0;
        }
        const frameRms = Math.sqrt(frameEnergy / fftSize);
        if (frameRms > 0.0025 || frameCount === 0) {
          this.fft(real, imag, false);
          for (let k = 0; k < half; k++) {
            accumPower[k] += real[k] * real[k] + imag[k] * imag[k];
          }
          frameCount++;
        }
        pos += hopSize;
      }

      const invCount = 1.0 / Math.max(1, frameCount);
      const normScale = 2.0 / fftSize;
      for (let k = 0; k < half; k++) {
        out[k] = Math.max(1e-6, Math.sqrt(accumPower[k] * invCount) * normScale);
      }
      return out;
    },

    minimumPhaseImpulseResponse(targetMagHalf, fftSize = 4096, outLength = 1024) {
      const real = new Float64Array(fftSize);
      const imag = new Float64Array(fftSize);
      const half = fftSize >> 1;
      const lastIdx = targetMagHalf.length - 1;

      for (let k = 0; k <= half; k++) {
        const mag = Math.max(1e-5, targetMagHalf[Math.min(k, lastIdx)]);
        const logMag = Math.log(mag);
        real[k] = logMag;
        if (k > 0 && k < half) {
          real[fftSize - k] = logMag;
        }
      }

      this.fft(real, imag, true);

      const liftedRe = new Float64Array(fftSize);
      const liftedIm = new Float64Array(fftSize);
      liftedRe[0] = real[0];
      for (let n = 1; n < half; n++) {
        liftedRe[n] = 2.0 * real[n];
      }
      liftedRe[half] = real[half];

      this.fft(liftedRe, liftedIm, false);

      const specRe = new Float64Array(fftSize);
      const specIm = new Float64Array(fftSize);
      for (let k = 0; k < fftSize; k++) {
        const amp = Math.exp(clamp(liftedRe[k], -16.0, 16.0));
        const phase = liftedIm[k];
        specRe[k] = amp * Math.cos(phase);
        specIm[k] = amp * Math.sin(phase);
      }

      this.fft(specRe, specIm, true);

      const ir = new Float32Array(outLength);
      const fadeSamples = Math.min(48, Math.floor(outLength / 8));
      for (let i = 0; i < outLength; i++) {
        let v = specRe[i];
        if (i >= outLength - fadeSamples) {
          const t = (outLength - 1 - i) / fadeSamples;
          v *= 0.5 * (1.0 - Math.cos(Math.PI * t));
        }
        ir[i] = v;
      }

      let mean = 0.0;
      for (let i = 0; i < outLength; i++) mean += ir[i];
      mean /= outLength;

      let peakAfterDc = 1e-6;
      for (let i = 0; i < outLength; i++) {
        ir[i] -= mean;
        const a = Math.abs(ir[i]);
        if (a > peakAfterDc) peakAfterDc = a;
      }
      const normFactor = 0.96 / peakAfterDc;
      for (let i = 0; i < outLength; i++) {
        ir[i] *= normFactor;
      }
      return ir;
    },

    resampleLinear(samples, srcRate, dstRate) {
      if (srcRate === dstRate || samples.length === 0) return samples;
      const ratio = srcRate / dstRate;
      const outLen = Math.max(1, Math.floor(samples.length / ratio));
      const out = new Float32Array(outLen);
      const last = samples.length - 1;
      for (let i = 0; i < outLen; i++) {
        const srcPos = i * ratio;
        const idx0 = clamp(Math.floor(srcPos), 0, last);
        const idx1 = Math.min(idx0 + 1, last);
        const frac = srcPos - idx0;
        out[i] = samples[idx0] * (1 - frac) + samples[idx1] * frac;
      }
      return out;
    },

    computeWaveformPeaks(samples, bins = 140) {
      if (!samples || samples.length === 0) return new Array(bins).fill(0);
      const step = Math.max(1, Math.floor(samples.length / bins));
      const peaks = new Array(bins).fill(0);
      let globalMax = 1e-5;
      for (let b = 0; b < bins; b++) {
        const start = b * step;
        const end = Math.min(samples.length, start + step);
        let pk = 0;
        for (let i = start; i < end; i++) {
          const a = Math.abs(samples[i]);
          if (a > pk) pk = a;
        }
        peaks[b] = pk;
        if (pk > globalMax) globalMax = pk;
      }
      return peaks.map((p) => clamp(p / globalMax, 0.03, 1.0));
    }
  };

  // =========================================================================
  // WavIrExporter (24-bit & 16-bit PCM RIFF/WAV + parser)
  // =========================================================================
  const WavIrExporter = {
    encodeWavMono(samples, sampleRate, bitsPerSample = 24) {
      const bytesPerSample = bitsPerSample === 16 ? 2 : 3;
      const dataSize = samples.length * bytesPerSample;
      const buf = new ArrayBuffer(44 + dataSize);
      const view = new DataView(buf);
      const u8 = new Uint8Array(buf);

      function writeAscii(offset, str) {
        for (let i = 0; i < str.length; i++) u8[offset + i] = str.charCodeAt(i);
      }

      writeAscii(0, 'RIFF');
      view.setUint32(4, 36 + dataSize, true);
      writeAscii(8, 'WAVE');
      writeAscii(12, 'fmt ');
      view.setUint32(16, 16, true);
      view.setUint16(20, 1, true); // PCM
      view.setUint16(22, 1, true); // Mono
      view.setUint32(24, sampleRate, true);
      view.setUint32(28, sampleRate * bytesPerSample, true);
      view.setUint16(32, bytesPerSample, true);
      view.setUint16(34, bitsPerSample, true);
      writeAscii(36, 'data');
      view.setUint32(40, dataSize, true);

      let offset = 44;
      if (bitsPerSample === 16) {
        for (let i = 0; i < samples.length; i++) {
          const c = clamp(samples[i], -1, 1);
          const v = clamp(Math.round(c * 32767), -32768, 32767);
          view.setInt16(offset, v, true);
          offset += 2;
        }
      } else {
        const maxInt24 = 8388607;
        for (let i = 0; i < samples.length; i++) {
          const c = clamp(samples[i], -1, 1);
          const v = clamp(Math.round(c * maxInt24), -8388608, 8388607);
          u8[offset++] = v & 0xFF;
          u8[offset++] = (v >> 8) & 0xFF;
          u8[offset++] = (v >> 16) & 0xFF;
        }
      }
      return u8;
    },

    tryParseWav(arrayBuffer) {
      const bytes = arrayBuffer instanceof Uint8Array ? arrayBuffer : new Uint8Array(arrayBuffer);
      if (bytes.length < 44) return null;
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      const ascii = (off, len) => String.fromCharCode(...bytes.slice(off, off + len));
      if (ascii(0, 4) !== 'RIFF' || ascii(8, 4) !== 'WAVE') return null;

      let pos = 12;
      let audioFormat = 1;
      let numChannels = 1;
      let sampleRate = 48000;
      let bitsPerSample = 16;
      let dataOffset = -1;
      let dataLength = 0;

      while (pos + 8 <= bytes.length) {
        const chunkId = ascii(pos, 4);
        const chunkSize = view.getUint32(pos + 4, true);
        pos += 8;
        if (chunkSize > bytes.length - pos) break;
        const nextPos = pos + chunkSize + (chunkSize & 1);

        if (chunkId === 'fmt ' && chunkSize >= 16) {
          audioFormat = view.getUint16(pos, true);
          numChannels = view.getUint16(pos + 2, true);
          sampleRate = view.getUint32(pos + 4, true);
          bitsPerSample = view.getUint16(pos + 14, true);
          if (audioFormat === 0xFFFE && chunkSize >= 40) {
            audioFormat = view.getUint16(pos + 24, true);
          }
        } else if (chunkId === 'data') {
          dataOffset = pos;
          dataLength = chunkSize;
          break;
        }
        pos = nextPos;
      }

      if (dataOffset < 0 || dataLength <= 0 || numChannels <= 0) return null;
      if (audioFormat !== 1 && audioFormat !== 3) return null;
      const bytesPerSample = Math.floor(bitsPerSample / 8);
      if (bytesPerSample <= 0) return null;

      const frameSize = bytesPerSample * numChannels;
      const frameCount = Math.floor(dataLength / frameSize);
      const out = new Float32Array(frameCount);
      let cursor = dataOffset;

      for (let i = 0; i < frameCount; i++) {
        let sum = 0;
        for (let ch = 0; ch < numChannels; ch++) {
          let sample = 0;
          if (audioFormat === 3 && bitsPerSample === 32) {
            sample = view.getFloat32(cursor, true);
          } else if (bitsPerSample === 16) {
            sample = view.getInt16(cursor, true) / 32768;
          } else if (bitsPerSample === 24) {
            const b0 = bytes[cursor];
            const b1 = bytes[cursor + 1];
            const b2 = bytes[cursor + 2];
            let signed = (b2 << 16) | (b1 << 8) | b0;
            if (signed & 0x800000) signed |= ~0xFFFFFF;
            sample = signed / 8388608;
          } else if (bitsPerSample === 32) {
            sample = view.getInt32(cursor, true) / 2147483648;
          } else if (bitsPerSample === 8) {
            sample = (bytes[cursor] - 128) / 128;
          }
          cursor += bytesPerSample;
          sum += sample;
        }
        out[i] = clamp(sum / numChannels, -1, 1);
      }
      return { samplesMono: out, sampleRate };
    }
  };

  // =========================================================================
  // NamModelExporter (NeuralAmpModelerCore v0.5.4 WaveNet)
  // =========================================================================
  const NamModelExporter = {
    solveLinearSystem(a, b) {
      const n = b.length;
      const m = a.map((row) => new Float64Array(row));
      const rhs = new Float64Array(b);

      for (let p = 0; p < n; p++) {
        let maxRow = p;
        for (let i = p + 1; i < n; i++) {
          if (Math.abs(m[i][p]) > Math.abs(m[maxRow][p])) maxRow = i;
        }
        const tmpRow = m[p];
        m[p] = m[maxRow];
        m[maxRow] = tmpRow;
        const tmpVal = rhs[p];
        rhs[p] = rhs[maxRow];
        rhs[maxRow] = tmpVal;

        const pivot = Math.abs(m[p][p]) < 1e-9 ? 1e-9 : m[p][p];
        for (let i = p + 1; i < n; i++) {
          const alpha = m[i][p] / pivot;
          rhs[i] -= alpha * rhs[p];
          for (let j = p; j < n; j++) {
            m[i][j] -= alpha * m[p][j];
          }
        }
      }

      const x = new Float64Array(n);
      for (let i = n - 1; i >= 0; i--) {
        let sum = 0.0;
        for (let j = i + 1; j < n; j++) sum += m[i][j] * x[j];
        const diag = Math.abs(m[i][i]) < 1e-9 ? 1e-9 : m[i][i];
        x[i] = (rhs[i] - sum) / diag;
      }
      return x;
    },

    runWaveNetCollectHead(input, weights, channels, dilations, kernelSize) {
      const len = input.length;
      let layerInput = Array.from({ length: channels }, () => new Float32Array(len));
      for (let c = 0; c < channels; c++) {
        const rw = weights[c];
        const dst = layerInput[c];
        for (let n = 0; n < len; n++) {
          dst[n] = input[n] * rw;
        }
      }

      const headAccum = Array.from({ length: channels }, () => new Float32Array(len));
      let cursor = channels;
      const convWeightSize = channels * channels * kernelSize;
      const projWeightSize = channels * channels;

      for (let dIdx = 0; dIdx < dilations.length; dIdx++) {
        const dilation = dilations[dIdx];
        const convOut = Array.from({ length: channels }, () => new Float32Array(len));
        for (let outC = 0; outC < channels; outC++) {
          const bias = weights[cursor + convWeightSize + outC];
          const outArr = convOut[outC];
          for (let n = 0; n < len; n++) {
            let sum = bias;
            for (let inC = 0; inC < channels; inC++) {
              const wBase = cursor + (outC * channels + inC) * kernelSize;
              const inArr = layerInput[inC];
              for (let k = 0; k < kernelSize; k++) {
                const tapIdx = n - (kernelSize - 1 - k) * dilation;
                if (tapIdx >= 0) {
                  sum += inArr[tapIdx] * weights[wBase + k];
                }
              }
            }
            outArr[n] = Math.tanh(sum);
          }
        }

        const projOffset = cursor + convWeightSize + channels + channels;
        const nextInput = Array.from({ length: channels }, () => new Float32Array(len));
        for (let outC = 0; outC < channels; outC++) {
          const pBias = weights[projOffset + projWeightSize + outC];
          const nxt = nextInput[outC];
          const lay = layerInput[outC];
          const hAcc = headAccum[outC];
          const convC = convOut[outC];
          for (let n = 0; n < len; n++) {
            let sum = pBias;
            for (let inC = 0; inC < channels; inC++) {
              sum += convOut[inC][n] * weights[projOffset + outC * channels + inC];
            }
            nxt[n] = lay[n] + sum;
            hAcc[n] += convC[n];
          }
        }
        layerInput = nextInput;
        cursor += convWeightSize + channels + channels + projWeightSize + channels;
      }

      return headAccum;
    },

    buildCalibratedWaveNetNam({
      patchName,
      quality,
      sampleRate,
      irSamples,
      driveGain,
      asymmetryBias,
      evalFreqsHz,
      captureMode,
      isBass = false
    }) {
      const channels = quality.channels;
      const dilations = quality.dilations;
      const kernelSize = 3;
      const inputSize = 1;
      const conditionSize = 1;
      const headSize = 1;
      const headScale = 1.0;

      const rechannelSize = channels * inputSize;
      const convWeightSize = channels * channels * kernelSize;
      const convBiasSize = channels;
      const inputMixinSize = channels * conditionSize;
      const proj1x1WeightSize = channels * channels;
      const proj1x1BiasSize = channels;
      const perLayerSize = convWeightSize + convBiasSize + inputMixinSize + proj1x1WeightSize + proj1x1BiasSize;
      const headRechannelWeightSize = headSize * channels;
      const headRechannelBiasSize = headSize;
      const totalWeights = rechannelSize + dilations.length * perLayerSize + headRechannelWeightSize + headRechannelBiasSize;

      const weights = new Float32Array(totalWeights);
      const effectiveDrive = clamp(driveGain, 0.6, 6.5);
      weights[0] = effectiveDrive;
      for (let c = 1; c < channels; c++) {
        weights[c] = (0.35 * effectiveDrive) / channels;
      }

      const irResampled = sampleRate !== 48000
        ? FftProcessor.resampleLinear(irSamples, sampleRate, 48000)
        : irSamples;
      const irLast = irResampled.length - 1;

      let cursor = rechannelSize;
      for (let layerIdx = 0; layerIdx < dilations.length; layerIdx++) {
        const dilation = dilations[layerIdx];
        const h0 = irResampled[Math.min(Math.floor(dilation / 2), irLast)];
        const h1 = irResampled[Math.min(dilation, irLast)];
        const h2 = irResampled[Math.min(dilation * 2, irLast)];

        for (let outC = 0; outC < channels; outC++) {
          for (let inC = 0; inC < channels; inC++) {
            const baseIdx = cursor + (outC * channels + inC) * kernelSize;
            if (outC === inC) {
              const scale = outC === 0 ? 0.85 : 0.45 / Math.sqrt(channels);
              weights[baseIdx] = (h2 * 1.4 + 0.15 / (layerIdx + 1)) * scale;
              weights[baseIdx + 1] = (h1 * 1.6 + 0.25 / (layerIdx + 1)) * scale;
              weights[baseIdx + 2] = (h0 * 1.8 + (layerIdx === 0 ? 0.7 : 0.2)) * scale;
            } else if (inC === 0 && outC === (layerIdx % (channels - 1)) + 1) {
              weights[baseIdx] = h2 * 0.55;
              weights[baseIdx + 1] = h1 * 0.65;
              weights[baseIdx + 2] = h0 * 0.75;
            }
          }
        }
        cursor += convWeightSize;

        weights[cursor] = clamp(asymmetryBias, -0.35, 0.35);
        for (let c = 1; c < channels; c++) {
          weights[cursor + c] = asymmetryBias * 0.2;
        }
        cursor += convBiasSize;
        cursor += inputMixinSize;

        for (let outC = 0; outC < channels; outC++) {
          for (let inC = 0; inC < channels; inC++) {
            weights[cursor + outC * channels + inC] = outC === inC ? 0.28 : 0;
          }
        }
        cursor += proj1x1WeightSize;
        cursor += proj1x1BiasSize;
      }

      const headWeightOffset = cursor;
      for (let c = 0; c < channels; c++) {
        weights[headWeightOffset + c] = c === 0 ? 0.52 : 0.48 / Math.max(1, channels - 1);
      }
      const headBiasOffset = headWeightOffset + headRechannelWeightSize;
      weights[headBiasOffset] = 0;

      // Target calibration excitation
      const testLen = 1536;
      const testInput = new Float32Array(testLen);
      for (let i = 0; i < testLen; i++) {
        const t = i / 48000;
        if (isBass) {
          const fSweep = 41.2 + (i / testLen) * 1650;
          testInput[i] = (
            0.42 * Math.sin(2 * Math.PI * 41.2 * t) +
            0.30 * Math.sin(2 * Math.PI * 98.0 * t) +
            0.22 * Math.sin(2 * Math.PI * fSweep * t) +
            0.12 * Math.cos(2 * Math.PI * 220 * t)
          ) * 0.52;
        } else {
          const f0 = 110 + (i / testLen) * 2600;
          testInput[i] = (
            0.45 * Math.sin(2 * Math.PI * f0 * t) +
            0.25 * Math.sin(2 * Math.PI * 196 * t) +
            0.18 * Math.cos(2 * Math.PI * 440 * t)
          ) * 0.55;
        }
      }

      const targetOutput = new Float32Array(testLen);
      const tanhBias = Math.tanh(asymmetryBias);
      for (let n = 0; n < testLen; n++) {
        let acc = 0;
        const maxTap = Math.min(256, Math.min(irResampled.length, n + 1));
        for (let k = 0; k < maxTap; k++) {
          const x = testInput[n - k];
          const sat = Math.tanh(x * effectiveDrive + asymmetryBias) - tanhBias;
          acc += sat * irResampled[k];
        }
        targetOutput[n] = acc;
      }

      const headHistory = this.runWaveNetCollectHead(testInput, weights, channels, dilations, kernelSize);

      const reg = 1e-2;
      const aMat = Array.from({ length: channels }, () => new Float64Array(channels));
      const bVec = new Float64Array(channels);
      const warmup = 256;
      for (let i = 0; i < channels; i++) {
        aMat[i][i] = reg;
        const hi = headHistory[i];
        for (let n = warmup; n < testLen; n++) {
          bVec[i] += hi[n] * targetOutput[n];
          for (let j = 0; j < channels; j++) {
            aMat[i][j] += hi[n] * headHistory[j][n];
          }
        }
      }

      const solvedHead = this.solveLinearSystem(aMat, bVec);
      for (let c = 0; c < channels; c++) {
        weights[headWeightOffset + c] = clamp(solvedHead[c], -3.5, 3.5);
      }

      const modeledOutput = new Float32Array(testLen);
      for (let n = 0; n < testLen; n++) {
        let sum = weights[headBiasOffset];
        for (let c = 0; c < channels; c++) {
          sum += headHistory[c][n] * weights[headWeightOffset + c];
        }
        modeledOutput[n] = clamp(sum * headScale, -1.5, 1.5);
      }

      let errEnergy = 1e-9;
      let refEnergy = 1e-9;
      for (let n = warmup; n < testLen; n++) {
        const e = modeledOutput[n] - targetOutput[n];
        errEnergy += e * e;
        refEnergy += targetOutput[n] * targetOutput[n];
      }
      const esrDb = clamp(10.0 * Math.log10(errEnergy / refEnergy), -28, -1.5);

      const fftMag = FftProcessor.computeAveragedMagnitudeSpectrum(modeledOutput, 2048, 512);
      const modeledSpectrumDb = evalFreqsHz.map((freq) => {
        const bin = clamp(Math.floor((freq / 48000) * 2048), 1, fftMag.length - 1);
        const lo = Math.max(1, bin - 1);
        const hi = Math.min(fftMag.length - 1, bin + 1);
        let sum = 0;
        for (let k = lo; k <= hi; k++) sum += fftMag[k];
        return 20.0 * Math.log10(Math.max(1e-5, sum / (hi - lo + 1)));
      });

      const weightsList = new Array(weights.length);
      for (let i = 0; i < weights.length; i++) {
        weightsList[i] = Number(weights[i].toFixed(6));
      }

      const namObj = {
        version: '0.5.4',
        architecture: 'WaveNet',
        config: {
          layers: [
            {
              input_size: inputSize,
              condition_size: conditionSize,
              channels: channels,
              head_size: headSize,
              kernel_size: kernelSize,
              dilations: dilations,
              activation: 'Tanh',
              gated: false,
              head_bias: true
            }
          ],
          head: null,
          head_scale: headScale
        },
        weights: weightsList,
        sample_rate: sampleRate,
        metadata: {
          name: patchName,
          modeled_by: 'ToneForge Web DSP Engine',
          gear_type: isBass
            ? (captureMode.code === 'FULL_RIG' ? 'Bass Full Rig' : 'Bass Amp')
            : (captureMode.code === 'FULL_RIG' ? 'Full Rig' : 'Amp'),
          tone_type: quality.label
        }
      };

      return {
        namJson: JSON.stringify(namObj),
        modeledSpectrumDb,
        trainingLossDb: esrDb
      };
    }
  };

  // =========================================================================
  // ToneEngine
  // =========================================================================
  const ToneEngine = {
    bandEnergy(mag, binHz, fLow, fHigh) {
      const lo = clamp(Math.floor(fLow / binHz), 0, mag.length - 1);
      const hi = clamp(Math.floor(fHigh / binHz), lo, mag.length - 1);
      let sum = 0.0;
      for (let b = lo; b <= hi; b++) sum += mag[b] * mag[b];
      return Math.sqrt(sum / Math.max(1, hi - lo + 1));
    },

    computeSpectralCentroid(mag, binHz) {
      let num = 0.0;
      let den = 0.0;
      for (let k = 1; k < mag.length; k++) {
        const f = k * binHz;
        if (f >= 35 && f <= 12000) {
          const w = mag[k] * mag[k];
          num += f * w;
          den += w;
        }
      }
      return clamp(num / Math.max(1e-9, den), 110, 6500);
    },

    classifyInstrumentFromSpectrum(mag, binHz) {
      const subBassE = this.bandEnergy(mag, binHz, 38, 118);
      const lowMidGuitarE = this.bandEnergy(mag, binHz, 160, 850);
      const upperPresenceE = this.bandEnergy(mag, binHz, 1400, 5200);
      const subBassRatio = subBassE / Math.max(1e-5, lowMidGuitarE + upperPresenceE * 0.6);
      const centroidHz = this.computeSpectralCentroid(mag, binHz);

      const loBin = Math.max(1, Math.floor(38 / binHz));
      const hiBin = Math.min(mag.length - 1, Math.floor(360 / binHz));
      let bestBin = loBin;
      let bestScore = -1;
      for (let b = loBin; b <= hiBin; b++) {
        const f = b * binHz;
        const h2 = mag[Math.min(mag.length - 1, b * 2)];
        const h3 = mag[Math.min(mag.length - 1, b * 3)];
        const score = mag[b] + 0.45 * h2 + 0.25 * h3;
        if (score > bestScore && f >= 38) {
          bestScore = score;
          bestBin = b;
        }
      }
      const f0 = bestBin * binHz;
      const isBass = (subBassRatio > 0.72 && centroidHz < 980) ||
                     (f0 < 105 && subBassRatio > 0.52 && centroidHz < 1150);

      return {
        detectedInstrument: isBass ? INSTRUMENT_MODES.BASS_4 : INSTRUMENT_MODES.GUITAR,
        dominantFundamentalHz: Math.round(f0 * 10) / 10,
        spectralCentroidHz: centroidHz,
        subBassEnergyRatio: subBassRatio
      };
    },

    detectInstrument(samples48k, trimStartFrac = 0, trimEndFrac = 1) {
      const total = samples48k.length;
      if (total === 0) {
        return {
          detectedInstrument: INSTRUMENT_MODES.GUITAR,
          dominantFundamentalHz: 110,
          spectralCentroidHz: 1200,
          subBassEnergyRatio: 0.1
        };
      }
      const startIdx = clamp(Math.floor(clamp(trimStartFrac, 0, 0.95) * total), 0, Math.max(0, total - 1));
      const endIdx = clamp(Math.floor(clamp(trimEndFrac, trimStartFrac + 0.03, 1) * total), startIdx + 1, total);
      const slice = samples48k.subarray(startIdx, endIdx);
      const fftSize = 8192;
      const mag = FftProcessor.computeAveragedMagnitudeSpectrum(slice, fftSize, 2048);
      return this.classifyInstrumentFromSpectrum(mag, 48000 / fftSize);
    },

    smoothSpectrum(mag, binHz) {
      const out = new Float32Array(mag.length);
      const last = mag.length - 1;
      for (let k = 0; k < mag.length; k++) {
        const f = Math.max(20, k * binHz);
        const bw = Math.max(1, Math.floor((f * 0.14) / binHz));
        const lo = Math.max(0, k - bw);
        const hi = Math.min(last, k + bw);
        let sum = 0;
        for (let j = lo; j <= hi; j++) sum += mag[j];
        out[k] = sum / (hi - lo + 1);
      }
      return out;
    },

    analyzeHarmonicsAndDynamics(samples, mag, binHz, isBass) {
      let sumSq = 0.0;
      let peak = 1e-5;
      let zeroCrossings = 0;
      for (let i = 1; i < samples.length; i++) {
        const s = samples[i];
        sumSq += s * s;
        const a = Math.abs(s);
        if (a > peak) peak = a;
        if ((samples[i - 1] >= 0 && s < 0) || (samples[i - 1] < 0 && s >= 0)) {
          zeroCrossings++;
        }
      }
      const rms = Math.max(1e-5, Math.sqrt(sumSq / Math.max(1, samples.length)));
      const crestDb = clamp(20.0 * Math.log10(peak / rms), 3, 22);
      const compressionSag = clamp(Math.round(((18 - crestDb) / 14) * 100), 10, 96);

      const minF = isBass ? 38 : 75;
      const maxF = isBass ? 165 : 420;
      const loBin = Math.max(1, Math.floor(minF / binHz));
      const hiBin = Math.min(mag.length - 1, Math.floor(maxF / binHz));
      let f0Bin = loBin;
      let f0Mag = 0;
      for (let b = loBin; b <= hiBin; b++) {
        if (mag[b] > f0Mag) {
          f0Mag = mag[b];
          f0Bin = b;
        }
      }
      const f0 = Math.max(minF, f0Bin * binHz);

      const harmonicEnergy = (mult) => {
        const targetBin = Math.round((f0 * mult) / binHz);
        if (targetBin >= mag.length - 1) return 0;
        let mx = 0;
        for (let d = -2; d <= 2; d++) {
          const idx = clamp(targetBin + d, 0, mag.length - 1);
          if (mag[idx] > mx) mx = mag[idx];
        }
        return mx;
      };

      const h1 = Math.max(1e-5, harmonicEnergy(1));
      const h2 = harmonicEnergy(2);
      const h3 = harmonicEnergy(3);
      const h4 = harmonicEnergy(4);
      const h5 = harmonicEnergy(5);

      const thdRatio = Math.sqrt(h2 * h2 + h3 * h3 + h4 * h4 + h5 * h5) / h1;
      const hfLow = isBass ? 900 : 2400;
      const hfHigh = isBass ? 4200 : 5800;
      const fundLow = isBass ? 40 : 90;
      const fundHigh = isBass ? 220 : 550;

      const hfDensity = this.bandEnergy(mag, binHz, hfLow, hfHigh) /
                        Math.max(1e-5, this.bandEnergy(mag, binHz, fundLow, fundHigh));
      const zcrRate = zeroCrossings / Math.max(1, samples.length);
      const rawThd = clamp(thdRatio * 22 + hfDensity * (isBass ? 15 : 18) + zcrRate * 42, 2.0, 44.0);
      const evenOdd = clamp((h2 + h4) / Math.max(1e-5, h3 + h5), 0.35, 2.2);
      const gainLevel = clamp(Math.round(rawThd * 1.55 + compressionSag * 0.35), 8, 98);

      return {
        fundamentalHz: Math.round(f0 * 10) / 10,
        gainLevel,
        thdPercent: Math.round(rawThd * 10) / 10,
        evenOddRatio: Math.round(evenOdd * 100) / 100,
        compressionSag
      };
    },

    extractBandEqDb(transfer, binHz, isBass) {
      const dbFor = (fLo, fHi) => {
        const e = this.bandEnergy(transfer, binHz, fLo, fHi);
        return Math.round(clamp(20.0 * Math.log10(Math.max(1e-4, e)), -12, 12) * 10) / 10;
      };
      if (isBass) {
        return {
          bassDb: dbFor(40, 115),
          midDb: dbFor(220, 580),
          trebleDb: dbFor(950, 2200),
          presenceDb: dbFor(2600, 4800)
        };
      }
      return {
        bassDb: dbFor(75, 200),
        midDb: dbFor(420, 1100),
        trebleDb: dbFor(1800, 3800),
        presenceDb: dbFor(4000, 6500)
      };
    },

    classifyToneCategory(isBass, gainLevel, centroidHz) {
      if (isBass) return 'Bass';
      if (gainLevel < 32) return 'Clean';
      if (gainLevel < 58) return 'Crunch';
      if (gainLevel >= 76 && centroidHz >= 1350 && centroidHz <= 2600) return 'Lead';
      return 'High Gain';
    },

    inferAmpArchetype(isBass, category, evenOdd, midDb) {
      if (isBass) {
        if (evenOdd < 0.85) return 'Darkglass / Modern Drive Bass';
        if (midDb > 1.5) return 'SVT Classic Tube 8x10 Growl';
        if (evenOdd > 1.15) return 'B-15 Vintage Motown Tube Clean';
        return 'GK / Solid-State Punch Bass';
      }
      if (category === 'Clean') return evenOdd > 1.05 ? 'US Blackface Tube Clean' : 'JC Studio Solid-State Clean';
      if (category === 'Crunch') return midDb > 1.5 ? 'UK Plexi / JCM Crunch' : 'Boutique Chime Class-A';
      if (category === 'Lead') return 'Hot-Rodded Lead / Mark Boogie';
      return evenOdd < 0.85 ? 'Modern Recto / 5150 High-Gain' : 'British High-Gain Stack';
    },

    inferCabCharacter(isBass, centroidHz, bassDb, trebleDb) {
      if (isBass) {
        if (centroidHz > 1100) return '4x10 + Tweeter Modern Bass';
        if (bassDb > 2.2 && trebleDb < 0) return '1x15 Vintage Warm Bass';
        if (trebleDb > 1.2) return 'SansAmp / Studio Bass DI';
        return '8x10 Sealed Classic Fridge';
      }
      if (bassDb > 2.0 && trebleDb < 0) return '4x12 Sealed Vintage 30';
      if (centroidHz > 2350) return '2x12 Bright / Greenback';
      return '1x12 / 2x12 Studio Mic SM57';
    },

    buildGp5SignalChain(metrics, captureMode, patchName, isBass) {
      const dbToKnob = (db) => clamp(Math.round(50 + db * 3.8), 5, 95);
      if (isBass) {
        const bassDrive = metrics.gainLevel > 52;
        const modules = [
          {
            moduleCode: 'NR',
            moduleName: 'Bass Gate',
            enabled: metrics.gainLevel >= 40,
            modelOrType: 'Low-End Smart Gate',
            params: { Threshold: clamp(Math.round(metrics.gainLevel * 0.55), 20, 62), Decay: 42 }
          },
          {
            moduleCode: 'PRE',
            moduleName: bassDrive ? 'Bass Drive' : 'Opto Comp',
            enabled: true,
            modelOrType: bassDrive ? 'Micro Dark OD (Blend)' : 'Bass Optical Comp',
            params: bassDrive
              ? { Drive: clamp(Math.round(metrics.gainLevel * 0.45), 15, 65), Blend: 58, Level: 72 }
              : { Sustain: clamp(metrics.compressionSag, 25, 78), Level: 74 }
          },
          {
            moduleCode: 'AMP',
            moduleName: 'SnapTone (.nam)',
            enabled: true,
            modelOrType: patchName.slice(0, 18),
            params: {
              Gain: metrics.gainLevel,
              Level: 74,
              Bass: dbToKnob(metrics.bassDb),
              Mid: dbToKnob(metrics.midDb),
              Treble: dbToKnob(metrics.trebleDb)
            }
          },
          {
            moduleCode: 'CAB',
            moduleName: 'Bass IR (.wav)',
            enabled: captureMode.code === 'AMP_PLUS_IR',
            modelOrType: captureMode.code === 'FULL_RIG' ? 'OFF (Full Rig en .nam)' : metrics.cabinetCharacter,
            params: { LowCut: 12, HighCut: 52, Level: 78 }
          },
          {
            moduleCode: 'EQ',
            moduleName: 'Bass Para EQ',
            enabled: Math.abs(metrics.midDb) > 1.2 || Math.abs(metrics.presenceDb) > 1.2,
            modelOrType: '4-Band Bass EQ',
            params: {
              '60Hz': dbToKnob(metrics.bassDb * 0.45),
              '400Hz': dbToKnob(metrics.midDb * 0.45),
              '1.6kHz': dbToKnob(metrics.trebleDb * 0.5)
            }
          },
          {
            moduleCode: 'REV',
            moduleName: 'Ambience',
            enabled: false,
            modelOrType: 'Bypass (Graves limpios)',
            params: {}
          }
        ];
        return {
          summary: `Bajo 4C (E-A-D-G) · ${metrics.ampArchetype} · ${metrics.cabinetCharacter}`,
          modules
        };
      }

      const nrThresh = (metrics.detectedCategory === 'High Gain' || metrics.detectedCategory === 'Lead')
        ? 58 : (metrics.detectedCategory === 'Crunch' ? 38 : 18);
      const preEnabled = metrics.gainLevel > 62 || metrics.detectedCategory === 'Lead';
      const preModel = metrics.detectedCategory === 'Lead' ? 'Green OD (TS-Style)' : 'Boost / Tight OD';

      const modules = [
        {
          moduleCode: 'NR',
          moduleName: 'Noise Gate',
          enabled: metrics.gainLevel >= 35,
          modelOrType: 'Smart Gate',
          params: { Threshold: nrThresh, Decay: 35 }
        },
        {
          moduleCode: 'PRE',
          moduleName: 'Drive / Comp',
          enabled: preEnabled,
          modelOrType: preModel,
          params: {
            Drive: metrics.detectedCategory === 'High Gain' ? 18 : 42,
            Tone: 62,
            Level: 72
          }
        },
        {
          moduleCode: 'AMP',
          moduleName: 'SnapTone (.nam)',
          enabled: true,
          modelOrType: patchName.slice(0, 18),
          params: {
            Gain: metrics.gainLevel,
            Level: 70,
            Bass: dbToKnob(metrics.bassDb),
            Mid: dbToKnob(metrics.midDb),
            Treble: dbToKnob(metrics.trebleDb)
          }
        },
        {
          moduleCode: 'CAB',
          moduleName: 'Cab IR (.wav)',
          enabled: captureMode.code === 'AMP_PLUS_IR',
          modelOrType: captureMode.code === 'FULL_RIG' ? 'OFF (Full Rig en .nam)' : metrics.cabinetCharacter,
          params: {
            LowCut: 28,
            HighCut: clamp(Math.floor((metrics.highCutHz - 3000) / 70), 30, 90),
            Level: 75
          }
        },
        {
          moduleCode: 'EQ',
          moduleName: 'Post EQ',
          enabled: Math.abs(metrics.presenceDb) > 1.5,
          modelOrType: 'Guitar EQ',
          params: {
            '100Hz': dbToKnob(metrics.bassDb * 0.4),
            '800Hz': dbToKnob(metrics.midDb * 0.4),
            '4kHz': dbToKnob(metrics.presenceDb * 0.6)
          }
        },
        {
          moduleCode: 'REV',
          moduleName: 'Ambience',
          enabled: metrics.detectedCategory === 'Clean' || metrics.detectedCategory === 'Lead',
          modelOrType: metrics.detectedCategory === 'Lead' ? 'Plate Studio' : 'Room Natural',
          params: { Mix: metrics.detectedCategory === 'Clean' ? 28 : 22, Decay: 38 }
        }
      ];

      return {
        summary: `Guitarra · ${metrics.detectedCategory} · ${metrics.ampArchetype} · ${metrics.cabinetCharacter}`,
        modules
      };
    },

    async analyzeAndSynthesize({
      decoded,
      patchName,
      instrumentMode,
      trimStartFrac,
      trimEndFrac,
      captureMode,
      modelQuality,
      irSampleRate,
      irLengthSamples,
      onProgress
    }) {
      onProgress(0.10, 'Recortando segmento de audio...');
      await yieldTick();

      const total = decoded.samples48k.length;
      const startIdx = clamp(Math.floor(clamp(trimStartFrac, 0, 0.95) * total), 0, Math.max(0, total - 1));
      const endIdx = clamp(Math.floor(clamp(trimEndFrac, trimStartFrac + 0.03, 1) * total), startIdx + 1, total);
      const trimmed = decoded.samples48k.slice(startIdx, endIdx);

      const fftSize = 8192;
      onProgress(0.28, 'Calculando espectro Welch FFT (8192 pts)...');
      await yieldTick();

      const magSpectrum = FftProcessor.computeAveragedMagnitudeSpectrum(trimmed, fftSize, 2048);
      const binHz = 48000 / fftSize;
      const detection = this.classifyInstrumentFromSpectrum(magSpectrum, binHz);

      const resolvedInstrument = instrumentMode.code === 'AUTO'
        ? detection.detectedInstrument
        : instrumentMode;
      const isBass = resolvedInstrument.code === 'BASS_4';

      onProgress(
        0.40,
        isBass ? 'Extrayendo perfil de Bajo 4C (E1–G2)...' : 'Extrayendo perfil de Guitarra Eléctrica...'
      );
      await yieldTick();

      const targetTransferMag = new Float32Array(magSpectrum.length);
      for (let k = 0; k < magSpectrum.length; k++) {
        const freq = Math.max(20, k * binHz);
        let diBaseline;
        if (isBass) {
          const bassTilt = clamp(Math.pow(120 / freq, 0.36), 0.06, 2.6);
          const pickupGrowl = 1 + 0.25 * Math.exp(-Math.pow((freq - 1400) / 700, 2));
          diBaseline = bassTilt * pickupGrowl;
        } else {
          const diTilt = clamp(Math.pow(300 / freq, 0.42), 0.08, 2.2);
          const pickupRes = 1 + 0.35 * Math.exp(-Math.pow((freq - 2350) / 900, 2));
          diBaseline = diTilt * pickupRes;
        }
        targetTransferMag[k] = magSpectrum[k] / Math.max(1e-4, diBaseline);
      }

      const smoothedTransfer = this.smoothSpectrum(targetTransferMag, binHz);
      const passLowHz = isBass ? 55 : 200;
      const passHighHz = isBass ? 1200 : 2000;
      const midStartBin = Math.floor(passLowHz / binHz);
      const midEndBin = Math.floor(passHighHz / binHz);
      let midSum = 0;
      for (let k = midStartBin; k <= midEndBin; k++) midSum += smoothedTransfer[k];
      const midAvg = Math.max(1e-4, midSum / Math.max(1, midEndBin - midStartBin + 1));
      for (let k = 0; k < smoothedTransfer.length; k++) {
        smoothedTransfer[k] = clamp(smoothedTransfer[k] / midAvg, 0.04, 5.5);
      }

      const hpCutoffHz = isBass ? 32 : 68;
      const lpCutoffHz = (isBass && captureMode.code === 'FULL_RIG')
        ? 5200 : (isBass ? 6200 : (captureMode.code === 'FULL_RIG' ? 7200 : 8800));
      for (let k = 0; k < smoothedTransfer.length; k++) {
        const f = k * binHz;
        const hp = Math.pow(f / hpCutoffHz, 2) / (1 + Math.pow(f / hpCutoffHz, 2));
        const lp = 1 / (1 + Math.pow(f / lpCutoffHz, 3));
        smoothedTransfer[k] = Math.max(1e-4, smoothedTransfer[k] * hp * lp);
      }

      onProgress(0.55, 'Analizando armónicos, THD y dinámica...');
      await yieldTick();

      const harmonicStats = this.analyzeHarmonicsAndDynamics(trimmed, magSpectrum, binHz, isBass);
      const bandEq = this.extractBandEqDb(smoothedTransfer, binHz, isBass);
      const centroidHz = detection.spectralCentroidHz;

      const category = this.classifyToneCategory(isBass, harmonicStats.gainLevel, centroidHz);
      const ampArchetype = this.inferAmpArchetype(isBass, category, harmonicStats.evenOddRatio, bandEq.midDb);
      const cabCharacter = this.inferCabCharacter(isBass, centroidHz, bandEq.bassDb, bandEq.trebleDb);

      onProgress(0.72, 'Reconstruyendo Impulse Response de fase mínima...');
      await yieldTick();

      const ir48kLen = irSampleRate === 48000
        ? irLengthSamples
        : Math.round((irLengthSamples * 48000) / irSampleRate);
      const ir48k = FftProcessor.minimumPhaseImpulseResponse(smoothedTransfer, fftSize, ir48kLen);
      let finalIr = ir48k;
      if (irSampleRate !== 48000) {
        const resampled = FftProcessor.resampleLinear(ir48k, 48000, irSampleRate);
        finalIr = new Float32Array(irLengthSamples);
        finalIr.set(resampled.subarray(0, Math.min(resampled.length, irLengthSamples)));
      }

      onProgress(0.86, 'Entrenando WaveNet (.nam) y cadena Valeton GP-5...');
      await yieldTick();

      const driveGain = isBass
        ? 0.78 + (harmonicStats.gainLevel / 100) * 3.8
        : 0.85 + (harmonicStats.gainLevel / 100) * 4.6;
      const asymBias = harmonicStats.evenOddRatio > 1.15
        ? 0.22 : (harmonicStats.evenOddRatio > 0.9 ? 0.12 : 0.04);

      const namResult = NamModelExporter.buildCalibratedWaveNetNam({
        patchName,
        quality: modelQuality,
        sampleRate: irSampleRate,
        irSamples: finalIr,
        driveGain,
        asymmetryBias: asymBias,
        evalFreqsHz: EVAL_FREQS_HZ,
        captureMode,
        isBass
      });

      const refAnchorBin = Math.floor((isBass ? 110 : 300) / binHz);
      const refAnchorMag = Math.max(1e-4, magSpectrum[clamp(refAnchorBin, 1, magSpectrum.length - 1)]);
      const refEvalDb = EVAL_FREQS_HZ.map((freq) => {
        const bin = clamp(Math.floor(freq / binHz), 1, magSpectrum.length - 1);
        return clamp(20.0 * Math.log10(Math.max(1e-4, magSpectrum[bin] / refAnchorMag)), -24, 18);
      });
      const irEvalDb = EVAL_FREQS_HZ.map((freq) => {
        const bin = clamp(Math.floor(freq / binHz), 1, smoothedTransfer.length - 1);
        return clamp(20.0 * Math.log10(Math.max(1e-4, smoothedTransfer[bin])), -24, 18);
      });

      const refSorted = [...refEvalDb].sort((a, b) => a - b);
      const modSorted = [...namResult.modeledSpectrumDb].sort((a, b) => a - b);
      const refMed = refSorted[Math.floor(refSorted.length / 2)];
      const modMed = modSorted[Math.floor(modSorted.length / 2)];
      const alignedModeledDb = namResult.modeledSpectrumDb.map((v) => clamp(v + (refMed - modMed), -24, 18));

      const metrics = {
        instrumentType: resolvedInstrument.code,
        detectedFundamentalHz: harmonicStats.fundamentalHz,
        gainLevel: harmonicStats.gainLevel,
        thdPercent: harmonicStats.thdPercent,
        evenOddHarmonicRatio: harmonicStats.evenOddRatio,
        compressionSag: harmonicStats.compressionSag,
        spectralCentroidHz: centroidHz,
        bassDb: bandEq.bassDb,
        midDb: bandEq.midDb,
        trebleDb: bandEq.trebleDb,
        presenceDb: bandEq.presenceDb,
        lowCutHz: isBass ? 35 : 80,
        highCutHz: isBass ? 5200 : (centroidHz > 2400 ? 7800 : 6400),
        detectedCategory: category,
        cabinetCharacter: cabCharacter,
        ampArchetype,
        trainingLossDb: namResult.trainingLossDb
      };

      const spectralCurve = {
        freqsHz: EVAL_FREQS_HZ,
        referenceDb: refEvalDb,
        modeledDb: alignedModeledDb,
        irCorrectionDb: irEvalDb
      };

      const gp5Chain = this.buildGp5SignalChain(metrics, captureMode, patchName, isBass);

      onProgress(1.0, 'SnapTone .nam e IR .wav generados.');
      return {
        patchName,
        resolvedInstrument,
        metrics,
        spectralCurve,
        gp5Chain,
        namJson: namResult.namJson,
        irSamples: finalIr,
        irSampleRate,
        trimmedInputSamples: trimmed,
        captureMode,
        modelQuality
      };
    }
  };

  // =========================================================================
  // WaveNetInferenceEngine (Karplus-Strong DI & real .nam + IR inference)
  // =========================================================================
  const WaveNetInferenceEngine = {
    addPluckedNote(dest, startSec, freqHz, durSec, damp, amp, sr) {
      const period = Math.max(4, Math.floor(sr / freqHz));
      const startIdx = Math.floor(startSec * sr);
      const len = Math.min(Math.floor(durSec * sr), dest.length - startIdx);
      if (len <= period) return;

      const ring = new Float32Array(period);
      let seed = Math.floor(freqHz * 100);
      for (let i = 0; i < period; i++) {
        seed = (seed * 1103515245 + 12345) & 0x7fffffff;
        const noise = (seed / 1073741824) - 1;
        const fundWeight = freqHz < 105 ? 0.72 : 0.45;
        const body = Math.sin((2.0 * Math.PI * i) / period) * fundWeight;
        ring[i] = (noise * (freqHz < 105 ? 0.42 : 0.65) + body) * amp;
      }

      let prev = 0;
      for (let n = 0; n < len; n++) {
        const idx = n % period;
        const cur = ring[idx];
        const filtered = damp * 0.5 * (cur + prev);
        prev = cur;
        ring[idx] = filtered;
        const fadeOut = n > len - 400 ? (len - n) / 400 : 1;
        dest[startIdx + n] += cur * fadeOut;
      }
    },

    synthesizeGuitarDiRiff(sampleRate = 48000) {
      const out = new Float32Array(sampleRate * 4);
      this.addPluckedNote(out, 0.02, 82.41, 0.45, 0.992, 0.55, sampleRate);
      this.addPluckedNote(out, 0.02, 123.47, 0.45, 0.991, 0.42, sampleRate);
      this.addPluckedNote(out, 0.52, 82.41, 0.25, 0.975, 0.52, sampleRate);
      this.addPluckedNote(out, 0.78, 82.41, 0.25, 0.975, 0.54, sampleRate);
      this.addPluckedNote(out, 1.08, 98.00, 0.75, 0.993, 0.52, sampleRate);
      this.addPluckedNote(out, 1.08, 146.83, 0.75, 0.992, 0.40, sampleRate);
      this.addPluckedNote(out, 1.95, 110.00, 0.85, 0.994, 0.55, sampleRate);
      this.addPluckedNote(out, 1.95, 164.81, 0.85, 0.993, 0.42, sampleRate);
      this.addPluckedNote(out, 2.88, 329.63, 1.05, 0.996, 0.48, sampleRate);

      let peak = 1e-5;
      for (let i = 0; i < out.length; i++) peak = Math.max(peak, Math.abs(out[i]));
      const gain = 0.7 / peak;
      for (let i = 0; i < out.length; i++) out[i] *= gain;
      return out;
    },

    synthesizeBass4DiRiff(sampleRate = 48000) {
      const out = new Float32Array(sampleRate * 4);
      this.addPluckedNote(out, 0.02, 41.20, 0.44, 0.995, 0.68, sampleRate);
      this.addPluckedNote(out, 0.48, 41.20, 0.24, 0.990, 0.58, sampleRate);
      this.addPluckedNote(out, 0.76, 49.00, 0.24, 0.992, 0.60, sampleRate);
      this.addPluckedNote(out, 1.04, 55.00, 0.65, 0.995, 0.66, sampleRate);
      this.addPluckedNote(out, 1.78, 73.42, 0.48, 0.994, 0.62, sampleRate);
      this.addPluckedNote(out, 2.32, 98.00, 0.55, 0.994, 0.58, sampleRate);
      this.addPluckedNote(out, 2.94, 41.20, 0.98, 0.996, 0.70, sampleRate);

      let peak = 1e-5;
      for (let i = 0; i < out.length; i++) peak = Math.max(peak, Math.abs(out[i]));
      const gain = 0.78 / peak;
      for (let i = 0; i < out.length; i++) out[i] *= gain;
      return out;
    },

    processWithNamAndIr(diSamples, namJson, irSamples, applyIr) {
      const root = JSON.parse(namJson);
      const config = root.config;
      const layerObj = config.layers[0];
      const channels = layerObj.channels;
      const kernelSize = layerObj.kernel_size;
      const dilations = layerObj.dilations;
      const headScale = config.head_scale ?? 1.0;
      const weights = root.weights;
      const nSamples = diSamples.length;

      let layerInput = Array.from({ length: channels }, () => new Float32Array(nSamples));
      for (let c = 0; c < channels; c++) {
        const rw = weights[c];
        const dst = layerInput[c];
        for (let n = 0; n < nSamples; n++) dst[n] = diSamples[n] * rw;
      }

      const headAccum = Array.from({ length: channels }, () => new Float32Array(nSamples));
      let cursor = channels;
      const convWeightSize = channels * channels * kernelSize;
      const projWeightSize = channels * channels;

      for (let dIdx = 0; dIdx < dilations.length; dIdx++) {
        const dilation = dilations[dIdx];
        const convOut = Array.from({ length: channels }, () => new Float32Array(nSamples));
        for (let outC = 0; outC < channels; outC++) {
          const bias = weights[cursor + convWeightSize + outC];
          const outArr = convOut[outC];
          for (let n = 0; n < nSamples; n++) {
            let sum = bias;
            for (let inC = 0; inC < channels; inC++) {
              const wBase = cursor + (outC * channels + inC) * kernelSize;
              const inArr = layerInput[inC];
              for (let k = 0; k < kernelSize; k++) {
                const tapIdx = n - (kernelSize - 1 - k) * dilation;
                if (tapIdx >= 0) sum += inArr[tapIdx] * weights[wBase + k];
              }
            }
            outArr[n] = Math.tanh(sum);
          }
        }

        const projOffset = cursor + convWeightSize + channels + channels;
        const nextInput = Array.from({ length: channels }, () => new Float32Array(nSamples));
        for (let outC = 0; outC < channels; outC++) {
          const pBias = weights[projOffset + projWeightSize + outC];
          const nxt = nextInput[outC];
          const lay = layerInput[outC];
          const hAcc = headAccum[outC];
          const convC = convOut[outC];
          for (let n = 0; n < nSamples; n++) {
            let sum = pBias;
            for (let inC = 0; inC < channels; inC++) {
              sum += convOut[inC][n] * weights[projOffset + outC * channels + inC];
            }
            nxt[n] = lay[n] + sum;
            hAcc[n] += convC[n];
          }
        }
        layerInput = nextInput;
        cursor += convWeightSize + channels + channels + projWeightSize + channels;
      }

      const headWeightOffset = cursor;
      const headBiasOffset = cursor + channels;
      const namOut = new Float32Array(nSamples);
      for (let n = 0; n < nSamples; n++) {
        let sum = weights[headBiasOffset];
        for (let c = 0; c < channels; c++) {
          sum += headAccum[c][n] * weights[headWeightOffset + c];
        }
        namOut[n] = clamp(sum * headScale, -1.5, 1.5);
      }

      let finalOut = namOut;
      if (applyIr && irSamples && irSamples.length > 0) {
        const taps = Math.min(irSamples.length, 512);
        finalOut = new Float32Array(nSamples);
        for (let n = 0; n < nSamples; n++) {
          let acc = 0;
          const limit = Math.min(taps, n + 1);
          for (let k = 0; k < limit; k++) acc += namOut[n - k] * irSamples[k];
          finalOut[n] = acc;
        }
      }

      let peak = 1e-5;
      for (let i = 0; i < finalOut.length; i++) peak = Math.max(peak, Math.abs(finalOut[i]));
      const norm = 0.88 / peak;
      for (let i = 0; i < finalOut.length; i++) finalOut[i] = clamp(finalOut[i] * norm, -1, 1);
      return finalOut;
    }
  };

  // =========================================================================
  // PackExporter & Valeton Suite Helpers
  // =========================================================================
  const PackExporter = {
    sanitizeFileName(raw) {
      const cleaned = String(raw || '').trim()
        .replace(/[^a-zA-Z0-9._\- ]/g, '_')
        .replace(/\s+/g, '_')
        .replace(/\.+/g, '.')
        .replace(/^[_.]+|[_.]+$/g, '');
      return cleaned ? cleaned.slice(0, 40) : 'ToneForge_Patch';
    },

    sanitizeReadableFileName(raw) {
      const cleaned = String(raw || '').trim()
        .replace(/[/\\:*?"<>|\x00-\x1F]/g, '_')
        .replace(/\.\./g, '_')
        .replace(/\s+/g, ' ')
        .replace(/^[ ._]+|[ ._]+$/g, '');
      return cleaned ? cleaned.slice(0, 64) : 'ToneForge_Patch';
    },

    buildGp5RecipeText({ patchName, slot, captureMode, sampleRate, irSamples, metrics, chain }) {
      const instrLabel = metrics.instrumentType === 'BASS_4'
        ? 'Bajo Eléctrico 4 Cuerdas (Std E1-A1-D2-G2)'
        : 'Guitarra Eléctrica (Std E2-E4)';
      const lines = [
        '====================================================',
        'TONEFORGE WEB - GUÍA DE PARCHE PARA VALETON GP-5',
        '====================================================',
        `Parche: ${patchName}`,
        `Instrumento: ${instrLabel} (f0 ≈ ${metrics.detectedFundamentalHz} Hz)`,
        `Slot GP-5 asignado: ${slot}`,
        `Modo SnapTone: ${captureMode}`,
        `Audio IR: ${sampleRate} Hz · 24-bit Mono · ${irSamples} samples`,
        `Arquetipo detectado: ${metrics.ampArchetype}`,
        `Pantalla / Cab: ${metrics.cabinetCharacter}`,
        `Gain: ${metrics.gainLevel}/100 | THD: ${metrics.thdPercent}% | Pares/Impares: ${metrics.evenOddHarmonicRatio}`,
        '----------------------------------------------------',
        'CADENA DE SEÑAL VALETON GP-5:'
      ];
      for (const mod of chain.modules) {
        const state = mod.enabled ? 'ON ' : 'OFF';
        const paramsStr = Object.entries(mod.params || {}).map(([k, v]) => `${k}: ${v}`).join(', ');
        lines.push(`  [${mod.moduleCode}] ${state} | ${mod.modelOrType} (${paramsStr})`);
      }
      lines.push('----------------------------------------------------');
      lines.push('PASOS PARA CARGAR EN VALETON GP-5:');
      lines.push('1. Abre la app Valeton Suite (PC/Mac o móvil por Bluetooth/USB).');
      lines.push('2. Ve al módulo AMP -> SnapTone e importa el archivo .nam generado.');
      lines.push('3. Valeton Suite lo convertirá automáticamente al formato SnapTone de tu GP-5.');
      if (captureMode === 'AMP_PLUS_IR') {
        lines.push('4. Ve al módulo CAB -> User IR e importa el archivo _IR.wav (24-bit).');
      } else {
        lines.push('4. Como este archivo es Full Rig, desactiva el módulo CAB del GP-5.');
      }
      lines.push('5. Ajusta los bloques NR, PRE, EQ y REV según la tabla superior.');
      return lines.join('\n');
    },

    buildValetonPresetDescriptorJson({ slotCode, slotNumber, displayTitle, patch, namFileName, irFileName }) {
      const modules = (patch.gp5Chain && patch.gp5Chain.modules ? patch.gp5Chain.modules : []).map((m) => ({
        code: m.moduleCode,
        name: m.moduleName,
        enabled: m.enabled,
        model: m.modelOrType,
        params: m.params || {}
      }));
      const obj = {
        format: 'Valeton_GP5_Suite_Preset',
        version: '1.0',
        slot_code: slotCode,
        slot_index: slotNumber,
        preset_name: String(displayTitle || patch.name).slice(0, 32),
        instrument_type: patch.instrumentType,
        category: patch.category,
        capture_mode: patch.captureMode,
        snap_tone_file: `SnapTone/${namFileName}`,
        sample_rate: patch.sampleRate,
        ir_samples: patch.irSamplesCount,
        amp_archetype: patch.metrics ? patch.metrics.ampArchetype : '',
        cabinet_character: patch.metrics ? patch.metrics.cabinetCharacter : '',
        signal_chain: modules
      };
      if (irFileName) {
        obj.user_ir_file = `UserIR/${irFileName}`;
      }
      return JSON.stringify(obj, null, 2);
    }
  };

  global.ToneDsp = {
    TARGET_SAMPLE_RATE,
    MAX_ANALYSIS_SECONDS,
    EVAL_FREQS_HZ,
    INSTRUMENT_MODES,
    CAPTURE_MODES,
    MODEL_QUALITIES,
    FftProcessor,
    WavIrExporter,
    NamModelExporter,
    ToneEngine,
    WaveNetInferenceEngine,
    PackExporter
  };
})(window);
