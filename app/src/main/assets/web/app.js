// ToneForge Web — Main Application Controller (Zero-Simulation, Pure Vanilla JS)
(function (global) {
  'use strict';

  const {
    TARGET_SAMPLE_RATE,
    MAX_ANALYSIS_SECONDS,
    INSTRUMENT_MODES,
    CAPTURE_MODES,
    MODEL_QUALITIES,
    FftProcessor,
    WavIrExporter,
    ToneEngine,
    WaveNetInferenceEngine,
    PackExporter
  } = global.ToneDsp;

  const nativeBridge = global.ToneForgeNative || null;

  // =========================================================================
  // Application State
  // =========================================================================
  const state = {
    route: 'capture',
    sourceTab: 'FILE',
    instrumentMode: INSTRUMENT_MODES.AUTO,
    captureMode: CAPTURE_MODES.FULL_RIG,
    modelQuality: MODEL_QUALITIES.GP5_NANO,
    irSampleRate: 48000,
    irLengthSamples: 1024,
    decodedAudio: null, // { samples48k, sampleRate, durationSec, peakDbfs, rmsDbfs, waveformPeaks, sourceLabel, sourceType }
    trimStartFrac: 0,
    trimEndFrac: 1,
    detectionResult: null,
    isRecording: false,
    isAnalyzing: false,
    patches: [],
    selectedPatch: null,
    lastTrimmedSamples: null,
    activeAuditionMode: null,
    searchQuery: '',
    categoryFilter: 'Todos',
    multiSelectIds: new Set(),
    convertFormat: 'wav16',
    valetonDirHandle: null, // Browser File System Access API DirectoryHandle
    valetonFolderName: null,
    nextEmptySlotPreview: 'P01',
    valetonInstalled: false,
    editingPatchId: null,
    editingCategory: 'Crunch',
    deletingPatchId: null,
    exportDialogPatches: []
  };

  let audioCtx = null;
  let activePlaybackSource = null;
  let micStream = null;
  let micScriptNode = null;
  let micChunks = [];
  let micSampleCount = 0;
  let micSourceRate = 48000;
  let micTimerId = null;
  let micStartTime = 0;
  let toastTimerId = null;

  // =========================================================================
  // DOM Helpers & Utilities
  // =========================================================================
  const $ = (id) => document.getElementById(id);

  function showToast(msg) {
    const bar = $('toastBar');
    bar.textContent = msg;
    bar.classList.remove('hidden');
    if (toastTimerId) clearTimeout(toastTimerId);
    toastTimerId = setTimeout(() => {
      bar.classList.add('hidden');
    }, 3800);
  }

  function getAudioContext() {
    if (!audioCtx) {
      const Ctx = global.AudioContext || global.webkitAudioContext;
      audioCtx = new Ctx();
    }
    if (audioCtx.state === 'suspended') {
      audioCtx.resume().catch(() => {});
    }
    return audioCtx;
  }

  function uint8ToBase64(u8) {
    let binary = '';
    const chunk = 0x8000;
    for (let i = 0; i < u8.length; i += chunk) {
      binary += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
    }
    return btoa(binary);
  }

  function base64ToUint8(b64) {
    const bin = atob(b64);
    const u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    return u8;
  }

  function float32ToBase64(f32) {
    return uint8ToBase64(new Uint8Array(f32.buffer, f32.byteOffset, f32.byteLength));
  }

  function base64ToFloat32(b64) {
    const u8 = base64ToUint8(b64);
    return new Float32Array(u8.buffer);
  }

  function triggerDownloadBytes(bytes, fileName, mimeType, label) {
    const u8 = bytes instanceof Uint8Array ? bytes : new TextEncoder().encode(String(bytes));
    if (nativeBridge && typeof nativeBridge.saveExportBase64 === 'function') {
      nativeBridge.saveExportBase64(uint8ToBase64(u8), fileName, mimeType, label || fileName);
      return;
    }
    const blob = new Blob([u8], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
    showToast(`${label || fileName} descargado.`);
  }

  function setSourceError(msg) {
    const box = $('sourceErrorBox');
    if (!msg) {
      box.classList.add('hidden');
      box.textContent = '';
    } else {
      box.textContent = msg;
      box.classList.remove('hidden');
    }
  }

  function setSourceLoading(loading, text) {
    const row = $('sourceLoadingRow');
    if (loading) {
      $('sourceLoadingText').textContent = text || 'Decodificando PCM a 48 kHz...';
      row.classList.remove('hidden');
    } else {
      row.classList.add('hidden');
    }
  }

  // =========================================================================
  // Navigation
  // =========================================================================
  function navigateTo(route) {
    if (route !== 'analyzer') stopPlayback();
    state.route = route;
    document.querySelectorAll('.nav-tab').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.route === route);
    });
    $('viewCapture').classList.toggle('active', route === 'capture');
    $('viewAnalyzer').classList.toggle('active', route === 'analyzer');
    $('viewPatches').classList.toggle('active', route === 'patches');

    if (route === 'capture' && state.decodedAudio) {
      requestAnimationFrame(drawWaveform);
    } else if (route === 'analyzer' && state.selectedPatch) {
      requestAnimationFrame(drawSpectralChart);
    } else if (route === 'patches') {
      renderPatchesList();
    }
  }

  // =========================================================================
  // Audio Decoding (Web Audio + WAV + Android Bridge Fallback)
  // =========================================================================
  function buildDecodedAudio(rawMono, srcRate, sourceLabel, sourceType) {
    const maxSamples = srcRate * MAX_ANALYSIS_SECONDS;
    const capped = rawMono.length > maxSamples ? rawMono.subarray(0, maxSamples) : rawMono;
    const resampled = FftProcessor.resampleLinear(capped, srcRate, TARGET_SAMPLE_RATE);

    let peak = 0;
    let sumSq = 0;
    for (let i = 0; i < resampled.length; i++) {
      const s = resampled[i];
      const a = Math.abs(s);
      if (a > peak) peak = a;
      sumSq += s * s;
    }
    const rms = Math.sqrt(sumSq / Math.max(1, resampled.length));
    const peakDbfs = 20 * Math.log10(Math.max(1e-6, peak));
    const rmsDbfs = 20 * Math.log10(Math.max(1e-6, rms));
    const durationSec = resampled.length / TARGET_SAMPLE_RATE;
    const waveformPeaks = FftProcessor.computeWaveformPeaks(resampled, 140);

    return {
      samples48k: resampled,
      sampleRate: TARGET_SAMPLE_RATE,
      durationSec,
      peakDbfs,
      rmsDbfs,
      waveformPeaks,
      sourceLabel,
      sourceType
    };
  }

  async function decodeAudioArrayBuffer(arrayBuffer, sourceLabel, sourceType) {
    // Fast-path RIFF/WAV
    const u8 = new Uint8Array(arrayBuffer);
    if (u8.length >= 12 &&
        String.fromCharCode(u8[0], u8[1], u8[2], u8[3]) === 'RIFF' &&
        String.fromCharCode(u8[8], u8[9], u8[10], u8[11]) === 'WAVE') {
      const wav = WavIrExporter.tryParseWav(u8);
      if (wav && wav.samplesMono.length > 0) {
        return buildDecodedAudio(wav.samplesMono, wav.sampleRate, sourceLabel, sourceType);
      }
    }

    // Web Audio API native decoder (MP3, M4A, AAC, OGG, FLAC, WebM, MP4)
    try {
      const ctx = getAudioContext();
      const audioBuf = await ctx.decodeAudioData(arrayBuffer.slice(0));
      const chCount = audioBuf.numberOfChannels;
      const len = audioBuf.length;
      const mono = new Float32Array(len);
      for (let ch = 0; ch < chCount; ch++) {
        const chData = audioBuf.getChannelData(ch);
        for (let i = 0; i < len; i++) mono[i] += chData[i] / chCount;
      }
      return buildDecodedAudio(mono, audioBuf.sampleRate, sourceLabel, sourceType);
    } catch (webErr) {
      // Fallback to native Android FFmpeg/MediaCodec decoder if running inside APK shell
      if (nativeBridge && typeof nativeBridge.decodeAudioBase64 === 'function') {
        const resJson = await new Promise((resolve) => {
          global.__onNativeDecodeResult = resolve;
          nativeBridge.decodeAudioBase64(uint8ToBase64(u8), sourceLabel, sourceType);
        });
        const parsed = JSON.parse(resJson);
        if (parsed.error) throw new Error(parsed.error);
        const samples = base64ToFloat32(parsed.pcm48kBase64);
        return buildDecodedAudio(samples, TARGET_SAMPLE_RATE, parsed.sourceLabel || sourceLabel, sourceType);
      }
      throw new Error('El navegador no pudo decodificar este formato. Prueba con WAV, MP3, M4A, FLAC u OGG.');
    }
  }

  function applyDecodedAudio(decoded, suggestedName) {
    state.decodedAudio = decoded;
    state.trimStartFrac = 0;
    state.trimEndFrac = 1;
    $('trimStartSlider').value = '0';
    $('trimEndSlider').value = '100';

    const cleanTitle = (suggestedName || decoded.sourceLabel.replace(/\.[^/.]+$/, '')).slice(0, 28);
    $('patchNameInput').value = cleanTitle || 'Mi_SnapTone';
    $('selectedFileLabel').textContent = decoded.sourceType === 'FILE' ? decoded.sourceLabel : 'Seleccionar o arrastrar archivo de audio';

    $('waveformEmptyPlaceholder').classList.add('hidden');
    $('loadedAudioWorkspace').classList.remove('hidden');
    $('captureTipCard').classList.add('hidden');

    $('audioSourceTitle').textContent = decoded.sourceLabel;
    $('audioStatsMeta').textContent =
      `48 kHz · ${decoded.durationSec.toFixed(1)}s · Peak ${decoded.peakDbfs.toFixed(1)} dBFS · RMS ${decoded.rmsDbfs.toFixed(1)} dBFS`;

    updateTrimReadout();
    runLiveDetection();
    requestAnimationFrame(drawWaveform);
  }

  async function handleFileImport(file) {
    if (!file) return;
    setSourceError(null);
    setSourceLoading(true, `Decodificando "${file.name}" a 48 kHz...`);
    try {
      const buf = await file.arrayBuffer();
      const decoded = await decodeAudioArrayBuffer(buf, file.name, 'FILE');
      applyDecodedAudio(decoded, file.name.replace(/\.[^/.]+$/, ''));
    } catch (err) {
      setSourceError(err.message || 'No se pudo decodificar el archivo de audio.');
    } finally {
      setSourceLoading(false);
    }
  }

  // =========================================================================
  // Web Link Extraction
  // =========================================================================
  async function handleExtractLink() {
    const url = $('linkUrlInput').value.trim();
    if (!url) {
      setSourceError('Pega un enlace de YouTube, TikTok, Spotify o audio directo (.mp3/.wav/.m4a).');
      return;
    }
    try {
      const u = new URL(url);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') {
        throw new Error('Solo se admiten enlaces http:// o https://.');
      }
    } catch (_) {
      setSourceError('El enlace no tiene un formato URL válido.');
      return;
    }

    setSourceError(null);
    setSourceLoading(true, 'Extrayendo y decodificando audio del enlace...');

    try {
      // If inside Android APK shell, delegate to native YouTubeAudioExtractor & MediaLinkResolver + FFmpeg
      if (nativeBridge && typeof nativeBridge.extractFromLink === 'function') {
        const resJson = await new Promise((resolve) => {
          global.__onNativeLinkResult = resolve;
          nativeBridge.extractFromLink(url);
        });
        const parsed = JSON.parse(resJson);
        if (parsed.error) throw new Error(parsed.error);
        const samples = base64ToFloat32(parsed.pcm48kBase64);
        const decoded = buildDecodedAudio(samples, TARGET_SAMPLE_RATE, parsed.suggestedTitle || 'Audio Web', 'LINK');
        applyDecodedAudio(decoded, parsed.suggestedTitle);
        return;
      }

      // Standalone browser: direct fetch for CORS-enabled audio URLs
      const resp = await fetch(url);
      if (!resp.ok) throw new Error(`El enlace respondió con código HTTP ${resp.status}.`);
      const ctype = (resp.headers.get('content-type') || '').toLowerCase();
      if (!ctype.startsWith('audio/') && !ctype.startsWith('video/') && !ctype.includes('octet-stream')) {
        throw new Error(
          'Este sitio web no expone audio directo sin proxy CORS en navegador. Usa una URL directa (.mp3/.wav/.m4a), importa el archivo o graba en Micro / USB.'
        );
      }
      const buf = await resp.arrayBuffer();
      const title = url.split('/').pop().split('?')[0].replace(/\.[^/.]+$/, '') || 'Audio Web';
      const decoded = await decodeAudioArrayBuffer(buf, title, 'LINK');
      applyDecodedAudio(decoded, title);
    } catch (err) {
      const msg = err.message && !err.message.includes('Failed to fetch')
        ? err.message
        : 'El navegador bloqueó la descarga directa por política CORS del dominio remoto. Descarga el audio e impórtalo en Archivo, o usa Micro / USB DI.';
      setSourceError(msg);
    } finally {
      setSourceLoading(false);
    }
  }

  // =========================================================================
  // Live Mic / USB DI Capture (Web Audio getUserMedia + Native Fallback)
  // =========================================================================
  async function toggleRecording() {
    if (state.isRecording) {
      stopRecording();
    } else {
      await startRecording();
    }
  }

  function updateRecordingUi(active, levelNorm = 0, durSec = 0) {
    const btn = $('btnToggleRecord');
    const lbl = $('micStatusLabel');
    btn.classList.toggle('recording', active);
    $('btnToggleRecordText').textContent = active ? 'Detener Grabación' : 'Grabar Audio / USB DI';
    lbl.textContent = active ? 'GRABANDO · 48 kHz MONO' : 'LISTO PARA CAPTURAR';
    lbl.classList.toggle('active', active);
    $('vuMeterFill').style.width = `${Math.round(Math.min(1, Math.max(0, levelNorm)) * 100)}%`;
    $('micDurationLabel').textContent = `${durSec.toFixed(1)}s / 30.0s`;
  }

  async function startRecording() {
    setSourceError(null);
    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      try {
        micStream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: false,
            noiseSuppression: false,
            autoGainControl: false,
            channelCount: 1
          }
        });
        const ctx = getAudioContext();
        micSourceRate = ctx.sampleRate;
        const source = ctx.createMediaStreamSource(micStream);
        micScriptNode = ctx.createScriptProcessor(4096, 1, 1);
        micChunks = [];
        micSampleCount = 0;
        micStartTime = performance.now();
        state.isRecording = true;
        updateRecordingUi(true, 0, 0);

        micScriptNode.onaudioprocess = (e) => {
          if (!state.isRecording) return;
          const input = e.inputBuffer.getChannelData(0);
          const copy = new Float32Array(input.length);
          copy.set(input);
          micChunks.push(copy);
          micSampleCount += copy.length;

          let sumSq = 0;
          for (let i = 0; i < copy.length; i++) sumSq += copy[i] * copy[i];
          const rms = Math.sqrt(sumSq / copy.length);
          const lvl = Math.min(1, rms * 5.5);
          const elapsed = (performance.now() - micStartTime) / 1000;
          updateRecordingUi(true, lvl, Math.min(30, elapsed));
          if (elapsed >= 30.0) {
            stopRecording();
          }
        };

        source.connect(micScriptNode);
        micScriptNode.connect(ctx.destination);
        return;
      } catch (err) {
        if (!nativeBridge || typeof nativeBridge.startNativeRecording !== 'function') {
          setSourceError('Permiso de micrófono denegado o dispositivo de audio no disponible.');
          return;
        }
      }
    }

    // Native Android AudioRecorderEngine fallback
    if (nativeBridge && typeof nativeBridge.startNativeRecording === 'function') {
      state.isRecording = true;
      updateRecordingUi(true, 0, 0);
      nativeBridge.startNativeRecording();
    } else {
      setSourceError('Tu navegador no soporta captura de audio por micrófono en este contexto.');
    }
  }

  function stopRecording() {
    if (!state.isRecording) return;
    state.isRecording = false;

    if (micScriptNode) {
      try { micScriptNode.disconnect(); } catch (_) {}
      micScriptNode = null;
    }
    if (micStream) {
      micStream.getTracks().forEach((t) => t.stop());
      micStream = null;
      const merged = new Float32Array(micSampleCount);
      let offset = 0;
      for (const c of micChunks) {
        merged.set(c, offset);
        offset += c.length;
      }
      micChunks = [];
      updateRecordingUi(false, 0, 0);
      if (merged.length < micSourceRate * 0.25) {
        setSourceError('Grabación demasiado corta. Graba al menos 0.5 segundos.');
        return;
      }
      const label = `Toma_Directa_${Date.now() % 1000}`;
      const decoded = buildDecodedAudio(merged, micSourceRate, label, 'MIC');
      applyDecodedAudio(decoded, label);
      return;
    }

    if (nativeBridge && typeof nativeBridge.stopNativeRecording === 'function') {
      nativeBridge.stopNativeRecording();
    }
    updateRecordingUi(false, 0, 0);
  }

  // Native recording callbacks
  global.__onNativeRecMeter = (lvl, dur) => {
    if (state.isRecording) updateRecordingUi(true, lvl, dur);
  };
  global.__onNativeRecDone = (jsonStr) => {
    state.isRecording = false;
    updateRecordingUi(false, 0, 0);
    const res = JSON.parse(jsonStr);
    if (res.error) {
      setSourceError(res.error);
      return;
    }
    const samples = base64ToFloat32(res.pcm48kBase64);
    const label = res.label || `Toma_Directa_${Date.now() % 1000}`;
    const decoded = buildDecodedAudio(samples, TARGET_SAMPLE_RATE, label, 'MIC');
    applyDecodedAudio(decoded, label);
  };

  // =========================================================================
  // Waveform Trimmer Canvas & Live Detection
  // =========================================================================
  function updateTrimRange(startFrac, endFrac) {
    const s = Math.max(0, Math.min(0.94, startFrac));
    const e = Math.max(s + 0.05, Math.min(1, endFrac));
    state.trimStartFrac = s;
    state.trimEndFrac = e;
    $('trimStartSlider').value = String(Math.round(s * 100));
    $('trimEndSlider').value = String(Math.round(e * 100));
    updateTrimReadout();
    drawWaveform();
    runLiveDetection();
  }

  function updateTrimReadout() {
    if (!state.decodedAudio) return;
    const dur = state.decodedAudio.durationSec;
    const sSec = state.trimStartFrac * dur;
    const eSec = state.trimEndFrac * dur;
    $('trimWindowLabel').textContent = `${sSec.toFixed(1)}s — ${eSec.toFixed(1)}s (${(eSec - sSec).toFixed(1)}s)`;
  }

  function runLiveDetection() {
    if (!state.decodedAudio) return;
    const det = ToneEngine.detectInstrument(state.decodedAudio.samples48k, state.trimStartFrac, state.trimEndFrac);
    state.detectionResult = det;
    const badge = $('liveDetectBadge');
    badge.textContent = `Detectado: ${det.detectedInstrument.shortLabel} (${Math.round(det.dominantFundamentalHz)} Hz)`;
    badge.classList.remove('hidden');
  }

  function drawWaveform() {
    const canvas = $('waveformCanvas');
    if (!canvas || !state.decodedAudio) return;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    if (rect.width <= 0) return;
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;

    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
    const w = rect.width;
    const h = rect.height;
    const midY = h / 2;

    ctx.clearRect(0, 0, w, h);

    const startX = state.trimStartFrac * w;
    const endX = state.trimEndFrac * w;

    // Highlight band
    ctx.fillStyle = 'rgba(255, 106, 0, 0.14)';
    ctx.fillRect(startX, 0, Math.max(2, endX - startX), h);

    // Center line
    ctx.strokeStyle = '#282222';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, midY);
    ctx.lineTo(w, midY);
    ctx.stroke();

    // Waveform bars
    const peaks = state.decodedAudio.waveformPeaks;
    const step = w / peaks.length;
    const barW = Math.max(2, step * 0.62);
    for (let i = 0; i < peaks.length; i++) {
      const x = i * step + step * 0.5;
      const frac = i / peaks.length;
      const inside = frac >= state.trimStartFrac && frac <= state.trimEndFrac;
      const barH = Math.max(4, peaks[i] * h * 0.84);
      ctx.fillStyle = inside ? '#FF6A00' : 'rgba(140, 140, 152, 0.35)';
      ctx.beginPath();
      ctx.roundRect(x - barW / 2, midY - barH / 2, barW, barH, barW / 2);
      ctx.fill();
    }

    // Start handle (Yellow)
    ctx.strokeStyle = '#FFB800';
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    ctx.moveTo(startX, 0);
    ctx.lineTo(startX, h);
    ctx.stroke();
    ctx.fillStyle = '#FFB800';
    ctx.beginPath();
    ctx.arc(startX, 10, 6, 0, Math.PI * 2);
    ctx.fill();

    // End handle (Red)
    ctx.strokeStyle = '#E52521';
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    ctx.moveTo(endX, 0);
    ctx.lineTo(endX, h);
    ctx.stroke();
    ctx.fillStyle = '#E52521';
    ctx.beginPath();
    ctx.arc(endX, h - 10, 6, 0, Math.PI * 2);
    ctx.fill();
  }

  function setupWaveformDragging() {
    const canvas = $('waveformCanvas');
    let draggingWhich = null;

    canvas.addEventListener('pointerdown', (e) => {
      if (!state.decodedAudio) return;
      canvas.setPointerCapture(e.pointerId);
      const rect = canvas.getBoundingClientRect();
      const frac = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      draggingWhich = Math.abs(frac - state.trimStartFrac) <= Math.abs(frac - state.trimEndFrac) ? 'start' : 'end';
    });

    canvas.addEventListener('pointermove', (e) => {
      if (!draggingWhich || !state.decodedAudio) return;
      const rect = canvas.getBoundingClientRect();
      const frac = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      if (draggingWhich === 'start') {
        updateTrimRange(Math.min(frac, state.trimEndFrac - 0.05), state.trimEndFrac);
      } else {
        updateTrimRange(state.trimStartFrac, Math.max(frac, state.trimStartFrac + 0.05));
      }
    });

    const endDrag = () => { draggingWhich = null; };
    canvas.addEventListener('pointerup', endDrag);
    canvas.addEventListener('pointercancel', endDrag);
  }

  // =========================================================================
  // Audio Playback (Trim Preview + Live A/B Audition)
  // =========================================================================
  function stopPlayback() {
    if (activePlaybackSource) {
      try { activePlaybackSource.stop(); } catch (_) {}
      activePlaybackSource = null;
    }
    state.activeAuditionMode = null;
    updateAuditionButtonsUi();
  }

  function playFloatSamples(samples, sampleRate = 48000, onEnded = null) {
    stopPlayback();
    const ctx = getAudioContext();
    const buf = ctx.createBuffer(1, samples.length, sampleRate);
    buf.getChannelData(0).set(samples);
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(ctx.destination);
    src.onended = () => {
      if (activePlaybackSource === src) {
        activePlaybackSource = null;
        if (onEnded) onEnded();
      }
    };
    activePlaybackSource = src;
    src.start(0);
  }

  function previewTrimmedSelection() {
    if (!state.decodedAudio) return;
    const all = state.decodedAudio.samples48k;
    const startIdx = Math.floor(state.trimStartFrac * all.length);
    const endIdx = Math.floor(state.trimEndFrac * all.length);
    const maxLen = Math.min(endIdx - startIdx, TARGET_SAMPLE_RATE * 6);
    if (maxLen <= 0) return;
    const slice = all.slice(startIdx, startIdx + maxLen);
    playFloatSamples(slice, TARGET_SAMPLE_RATE);
    showToast('Reproduciendo selección de audio...');
  }

  function updateAuditionButtonsUi() {
    const playing = state.activeAuditionMode;
    $('anPlayingBadge').classList.toggle('hidden', !playing);
    document.querySelectorAll('.btn-audition').forEach((btn) => {
      const isThis = btn.dataset.audition === playing;
      btn.classList.toggle('playing', isThis);
      const icon = btn.querySelector('.play-icon');
      if (icon) icon.textContent = isThis ? '■' : '▶';
    });
  }

  function toggleAudition(mode) {
    if (state.activeAuditionMode === mode) {
      stopPlayback();
      return;
    }
    const patch = state.selectedPatch;
    if (!patch) return;

    const isBass = patch.instrumentType === 'BASS_4';
    let samplesToPlay;

    if (mode === 'DI_CLEAN') {
      samplesToPlay = isBass
        ? WaveNetInferenceEngine.synthesizeBass4DiRiff(48000)
        : WaveNetInferenceEngine.synthesizeGuitarDiRiff(48000);
    } else if (mode === 'SNAPTONE_NAM') {
      const di = isBass
        ? WaveNetInferenceEngine.synthesizeBass4DiRiff(48000)
        : WaveNetInferenceEngine.synthesizeGuitarDiRiff(48000);
      const irWav = patch.irWavBase64 ? WavIrExporter.tryParseWav(base64ToUint8(patch.irWavBase64)) : null;
      samplesToPlay = WaveNetInferenceEngine.processWithNamAndIr(
        di,
        patch.namJson,
        irWav ? irWav.samplesMono : null,
        patch.captureMode === 'AMP_PLUS_IR'
      );
    } else {
      const raw = state.lastTrimmedSamples || (isBass
        ? WaveNetInferenceEngine.synthesizeBass4DiRiff(48000)
        : WaveNetInferenceEngine.synthesizeGuitarDiRiff(48000));
      const maxLen = Math.min(raw.length, 48000 * 5);
      const slice = raw.slice(0, maxLen);
      let pk = 1e-5;
      for (let i = 0; i < slice.length; i++) pk = Math.max(pk, Math.abs(slice[i]));
      const g = 0.85 / pk;
      for (let i = 0; i < slice.length; i++) slice[i] = Math.max(-1, Math.min(1, slice[i] * g));
      samplesToPlay = slice;
    }

    playFloatSamples(samplesToPlay, 48000, () => {
      state.activeAuditionMode = null;
      updateAuditionButtonsUi();
    });
    state.activeAuditionMode = mode;
    updateAuditionButtonsUi();
  }

  // =========================================================================
  // DSP Analysis & Patch Persistence
  // =========================================================================
  async function generateSnapTone() {
    if (!state.decodedAudio || state.isAnalyzing) return;
    const patchName = $('patchNameInput').value.trim() || 'SnapTone_GP5';
    state.isAnalyzing = true;
    $('btnGenerateSnapTone').classList.add('hidden');
    $('analysisProgressCard').classList.remove('hidden');

    try {
      const result = await ToneEngine.analyzeAndSynthesize({
        decoded: state.decodedAudio,
        patchName,
        instrumentMode: state.instrumentMode,
        trimStartFrac: state.trimStartFrac,
        trimEndFrac: state.trimEndFrac,
        captureMode: state.captureMode,
        modelQuality: state.modelQuality,
        irSampleRate: state.irSampleRate,
        irLengthSamples: state.irLengthSamples,
        onProgress: (prog, msg) => {
          $('analysisStatusText').textContent = msg;
          $('analysisPercentText').textContent = `${Math.round(prog * 100)}%`;
          $('analysisProgressFill').style.width = `${Math.round(prog * 100)}%`;
        }
      });

      const irWavBytes = WavIrExporter.encodeWavMono(result.irSamples, result.irSampleRate, 24);
      const existingCount = state.patches.length;
      const nextSlot = `P${String((existingCount % 99) + 1).padStart(2, '0')}`;

      const entity = {
        name: result.patchName,
        instrumentType: result.resolvedInstrument.code,
        sourceType: state.decodedAudio.sourceType,
        sourceLabel: state.decodedAudio.sourceLabel,
        category: result.metrics.detectedCategory,
        gp5Slot: nextSlot,
        isFavorite: false,
        captureMode: result.captureMode.code,
        modelQuality: result.modelQuality.code,
        sampleRate: result.irSampleRate,
        irSamplesCount: result.irSamples.length,
        namJson: result.namJson,
        irWavBase64: uint8ToBase64(irWavBytes),
        spectralCurve: result.spectralCurve,
        metrics: result.metrics,
        gp5Chain: result.gp5Chain,
        notes: `Generado localmente desde ${state.decodedAudio.sourceLabel}`,
        createdAt: Date.now()
      };

      const saved = await global.ToneDb.insertPatch(entity);
      state.lastTrimmedSamples = result.trimmedInputSamples;
      await refreshPatchesFromDb();
      inspectPatch(saved);
      showToast(`Parche '${saved.name}' guardado en slot ${saved.gp5Slot}`);
    } catch (err) {
      setSourceError(`Error en análisis DSP: ${err.message}`);
    } finally {
      state.isAnalyzing = false;
      $('analysisProgressCard').classList.add('hidden');
      $('btnGenerateSnapTone').classList.remove('hidden');
    }
  }

  // =========================================================================
  // Analyzer View Rendering & Spectral Graph
  // =========================================================================
  function inspectPatch(patch) {
    state.selectedPatch = patch;
    $('exportFileNameInput').value = patch.name;
    renderAnalyzerView();
    navigateTo('analyzer');
  }

  function getEffectiveExportBaseName() {
    const raw = $('exportFileNameInput').value.trim() || (state.selectedPatch ? state.selectedPatch.name : 'ToneForge_Patch');
    return PackExporter.sanitizeReadableFileName(raw);
  }

  function updateExportPreview() {
    const base = getEffectiveExportBaseName();
    $('exportFilePreview').textContent = `Archivo: ${base}.nam · ${base}_IR.wav`;
  }

  function renderAnalyzerView() {
    const patch = state.selectedPatch;
    if (!patch) {
      $('analyzerEmptyState').classList.remove('hidden');
      $('analyzerWorkspace').classList.add('hidden');
      return;
    }
    $('analyzerEmptyState').classList.add('hidden');
    $('analyzerWorkspace').classList.remove('hidden');

    const m = patch.metrics;
    const isBass = patch.instrumentType === 'BASS_4';
    $('anPatchName').textContent = patch.name;
    $('anSlotBadge').textContent = `GP-5 · ${patch.gp5Slot}`;
    $('anPatchSubtitle').textContent = `${isBass ? 'Bajo 4 Cuerdas (E-A-D-G)' : 'Guitarra Eléctrica'} · ${m.ampArchetype} · ${m.cabinetCharacter}`;
    $('anSpecsLine').textContent =
      `${patch.captureMode === 'FULL_RIG' ? 'Full Rig (.nam)' : 'Amp (.nam) + IR (.wav)'} · f0 ${Math.round(m.detectedFundamentalHz)} Hz · ${patch.sampleRate / 1000} kHz · ${patch.irSamplesCount} pts`;

    $('auditionDiLabel').textContent = isBass ? 'DI Bajo 4C' : 'DI Limpio';
    $('telGain').textContent = `${m.gainLevel}/100`;
    $('telCategory').textContent = m.detectedCategory;
    $('telThd').textContent = `${m.thdPercent}%`;
    $('telHarmonics').textContent = m.evenOddHarmonicRatio >= 1.0 ? 'Cálido (Pares)' : 'Agresivo (Impares)';
    $('telSag').textContent = `${m.compressionSag}%`;
    $('telCentroid').textContent = `${Math.round(m.spectralCentroidHz)} Hz foco`;

    updateExportPreview();
    renderGp5Chain(patch.gp5Chain);
    updateValetonStatusUi();
    requestAnimationFrame(drawSpectralChart);
  }

  function drawSpectralChart() {
    const canvas = $('spectralCanvas');
    const patch = state.selectedPatch;
    if (!canvas || !patch || !patch.spectralCurve) return;
    const curve = patch.spectralCurve;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    if (rect.width <= 0) return;
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;

    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
    const w = rect.width;
    const h = rect.height;
    ctx.clearRect(0, 0, w, h);

    const minLog = Math.log(30);
    const maxLog = Math.log(14000);
    const freqToX = (f) => ((Math.log(Math.max(30, Math.min(14000, f))) - minLog) / (maxLog - minLog)) * w;
    const dbToY = (db) => h - (((Math.max(-24, Math.min(18, db)) + 24) / 42) * h);

    // Horizontal dB grid (-12, 0, +12)
    [-12, 0, 12].forEach((dbLine) => {
      const y = dbToY(dbLine);
      ctx.save();
      ctx.strokeStyle = dbLine === 0 ? '#382E2E' : 'rgba(56, 46, 46, 0.6)';
      ctx.lineWidth = 1;
      if (dbLine !== 0) ctx.setLineDash([5, 5]);
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
      ctx.restore();
    });

    // Vertical frequency markers
    [41.2, 100, 500, 1000, 3000, 8000].forEach((f) => {
      const x = freqToX(f);
      ctx.strokeStyle = 'rgba(56, 46, 46, 0.5)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, h);
      ctx.stroke();
    });

    const freqs = curve.freqsHz;
    if (!freqs || freqs.length < 2) return;

    // Reference fill + stroke (Orange)
    ctx.beginPath();
    ctx.moveTo(freqToX(freqs[0]), h);
    freqs.forEach((f, i) => ctx.lineTo(freqToX(f), dbToY(curve.referenceDb[i] || 0)));
    ctx.lineTo(freqToX(freqs[freqs.length - 1]), h);
    ctx.closePath();
    const grad = ctx.createLinearGradient(0, 0, 0, h);
    grad.addColorStop(0, 'rgba(255, 106, 0, 0.28)');
    grad.addColorStop(1, 'rgba(255, 106, 0, 0.0)');
    ctx.fillStyle = grad;
    ctx.fill();

    ctx.strokeStyle = '#FF6A00';
    ctx.lineWidth = 2.4;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    freqs.forEach((f, i) => {
      const x = freqToX(f);
      const y = dbToY(curve.referenceDb[i] || 0);
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();

    // Modeled .nam stroke (Yellow)
    ctx.strokeStyle = '#FFB800';
    ctx.lineWidth = 2.2;
    ctx.beginPath();
    freqs.forEach((f, i) => {
      const x = freqToX(f);
      const y = dbToY(curve.modeledDb[i] || 0);
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();
  }

  function renderGp5Chain(chain) {
    const list = $('gp5ChainList');
    list.innerHTML = '';
    if (!chain || !chain.modules) return;
    for (const mod of chain.modules) {
      const item = document.createElement('div');
      item.className = `chain-item ${mod.enabled ? '' : 'off'}`;
      const paramsHtml = (mod.enabled && mod.params)
        ? Object.entries(mod.params).map(([k, v]) => `<span>${k}: ${v}</span>`).join('')
        : '';
      item.innerHTML = `
        <span class="chain-code">${mod.moduleCode}</span>
        <div class="chain-body">
          <div class="chain-title-row">
            <span class="truncate">${mod.moduleName} · ${mod.modelOrType}</span>
            <span class="chain-state ${mod.enabled ? 'on' : 'off'}">${mod.enabled ? 'ON' : 'OFF'}</span>
          </div>
          ${paramsHtml ? `<div class="chain-params">${paramsHtml}</div>` : ''}
        </div>
      `;
      list.appendChild(item);
    }
  }

  // =========================================================================
  // Exports, Sharing, Valeton Suite & Format Conversion
  // =========================================================================
  function buildZipBytesForPairs(pairs) {
    const entries = [];
    const used = new Set();
    for (const { patch, customName } of pairs) {
      let base = PackExporter.sanitizeReadableFileName(customName || patch.name);
      if (used.has(base)) base = `${base}_${patch.id}`;
      used.add(base);

      entries.push({ name: `${base}.nam`, data: patch.namJson });
      if (patch.irWavBase64) {
        entries.push({ name: `${base}_IR.wav`, data: base64ToUint8(patch.irWavBase64) });
      }
      const guideText = PackExporter.buildGp5RecipeText({
        patchName: customName || patch.name,
        slot: patch.gp5Slot,
        captureMode: patch.captureMode,
        sampleRate: patch.sampleRate,
        irSamples: patch.irSamplesCount,
        metrics: patch.metrics,
        chain: patch.gp5Chain
      });
      entries.push({ name: `${base}_GP5_Guia.txt`, data: guideText });
    }
    return global.ToneZip.buildZip(entries);
  }

  async function shareBytes(bytes, fileName, mimeType, title) {
    const u8 = bytes instanceof Uint8Array ? bytes : new TextEncoder().encode(String(bytes));
    if (nativeBridge && typeof nativeBridge.shareExportBase64 === 'function') {
      nativeBridge.shareExportBase64(uint8ToBase64(u8), fileName, mimeType, title);
      return;
    }
    if (navigator.share && navigator.canShare) {
      const file = new File([u8], fileName, { type: mimeType });
      if (navigator.canShare({ files: [file] })) {
        try {
          await navigator.share({ title, files: [file] });
          return;
        } catch (_) {}
      }
    }
    triggerDownloadBytes(u8, fileName, mimeType, title);
  }

  async function convertSelectedIrFormat() {
    const patch = state.selectedPatch;
    if (!patch || !patch.irWavBase64) return;
    const fmt = state.convertFormat;
    const base = getEffectiveExportBaseName();

    if (fmt === 'wav16') {
      const parsed = WavIrExporter.tryParseWav(base64ToUint8(patch.irWavBase64));
      if (!parsed) return;
      const wav16 = WavIrExporter.encodeWavMono(parsed.samplesMono, parsed.sampleRate, 16);
      triggerDownloadBytes(wav16, `${base}_IR_16bit.wav`, 'audio/wav', 'IR WAV 16-bit');
      return;
    }

    if (nativeBridge && typeof nativeBridge.convertIrBase64 === 'function') {
      $('btnConvertIrFormat').disabled = true;
      $('btnConvertIrFormat').textContent = 'Convirtiendo con FFmpeg local...';
      try {
        const resJson = await new Promise((resolve) => {
          global.__onNativeConvertResult = resolve;
          nativeBridge.convertIrBase64(patch.irWavBase64, fmt, base, patch.sampleRate);
        });
        const res = JSON.parse(resJson);
        if (res.error) throw new Error(res.error);
        showToast(`IR convertido a .${fmt} guardado correctamente.`);
      } catch (err) {
        showToast(err.message || 'Error al convertir el formato de audio.');
      } finally {
        $('btnConvertIrFormat').disabled = false;
        updateConvertButtonLabel();
      }
      return;
    }

    // Standalone browser WebM/Opus encoding via MediaRecorder if requested
    if (fmt === 'opus' && typeof MediaRecorder !== 'undefined') {
      try {
        const parsed = WavIrExporter.tryParseWav(base64ToUint8(patch.irWavBase64));
        const ctx = getAudioContext();
        const dest = ctx.createMediaStreamDestination();
        const buf = ctx.createBuffer(1, parsed.samplesMono.length, parsed.sampleRate);
        buf.getChannelData(0).set(parsed.samplesMono);
        const src = ctx.createBufferSource();
        src.buffer = buf;
        src.connect(dest);

        const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
        const rec = new MediaRecorder(dest.stream, { mimeType: mime });
        const chunks = [];
        rec.ondataavailable = (e) => { if (e.data.size > 0) chunks.push(e.data); };
        const done = new Promise((resolve) => { rec.onstop = resolve; });
        rec.start();
        src.start();
        src.onended = () => setTimeout(() => rec.stop(), 80);
        await done;
        const blob = new Blob(chunks, { type: mime });
        const bytes = new Uint8Array(await blob.arrayBuffer());
        triggerDownloadBytes(bytes, `${base}_IR.webm`, mime, 'Audio OPUS/WebM');
      } catch (_) {
        showToast('Tu navegador solo permite exportar WAV 16/24-bit y WebM/OPUS directamente; usa la app Android para FLAC/MP3/M4A/OGG.');
      }
      return;
    }

    showToast(`Para exportar en .${fmt.toUpperCase()} usa la app Android (FFmpeg local), o selecciona WAV 16b / OPUS en navegador.`);
  }

  function updateConvertButtonLabel() {
    const ext = state.convertFormat === 'wav16' ? 'wav (16-bit)' : state.convertFormat;
    $('btnConvertIrFormat').textContent = `Convertir IR a .${ext} y descargar`;
  }

  // =========================================================================
  // Valeton Suite Folder Integration (Browser File System Access + Android SAF)
  // =========================================================================
  function scanOccupiedSlots(names) {
    const occupied = new Set();
    const re = /^[Pp](\d{1,3})[\s_.-].*$/;
    for (const n of names) {
      const m = re.exec(String(n).trim());
      if (m) {
        const num = parseInt(m[1], 10);
        if (num >= 1 && num <= 999) occupied.add(num);
      }
    }
    return occupied;
  }

  function findNextSlots(occupied, count) {
    const res = [];
    let c = 1;
    while (res.length < count) {
      if (!occupied.has(c)) res.push(c);
      c++;
    }
    return res;
  }

  function formatSlot(num) {
    return `P${String(num).padStart(2, '0')}`;
  }

  async function pickValetonFolder() {
    if (nativeBridge && typeof nativeBridge.pickValetonFolder === 'function') {
      nativeBridge.pickValetonFolder();
      return;
    }
    if (typeof window.showDirectoryPicker === 'function') {
      try {
        const dir = await window.showDirectoryPicker({ mode: 'readwrite' });
        state.valetonDirHandle = dir;
        state.valetonFolderName = dir.name || 'Valeton Suite';
        const presetsDir = await dir.getDirectoryHandle('Presets', { create: true });
        const names = [];
        for await (const [entryName] of presetsDir.entries()) names.push(entryName);
        const nextNum = findNextSlots(scanOccupiedSlots(names), 1)[0] || 1;
        state.nextEmptySlotPreview = formatSlot(nextNum);
        updateValetonStatusUi();
        showToast(`Carpeta Valeton Suite vinculada (${state.valetonFolderName}). Próximo slot: ${state.nextEmptySlotPreview}`);
      } catch (err) {
        if (err.name !== 'AbortError') {
          showToast('No se pudo acceder a la carpeta seleccionada.');
        }
      }
      return;
    }
    showToast('Tu navegador móvil no soporta File System Access API. Usa "Pack GP-5 (.zip)" para descargar los archivos.');
  }

  async function exportRequestsToValeton(requests) {
    if (!requests || requests.length === 0) return;

    if (nativeBridge && typeof nativeBridge.exportToValetonSuite === 'function') {
      const payload = requests.map((r) => ({
        patchId: r.patch.id,
        exportFileName: r.exportFileName,
        patchName: r.patch.name,
        instrumentType: r.patch.instrumentType,
        category: r.patch.category,
        captureMode: r.patch.captureMode,
        sampleRate: r.patch.sampleRate,
        irSamplesCount: r.patch.irSamplesCount,
        namJson: r.patch.namJson,
        irWavBase64: r.patch.irWavBase64 || '',
        metrics: r.patch.metrics,
        gp5Chain: r.patch.gp5Chain
      }));
      nativeBridge.exportToValetonSuite(JSON.stringify(payload));
      return;
    }

    if (!state.valetonDirHandle) {
      await pickValetonFolder();
      if (!state.valetonDirHandle) return;
    }

    try {
      const snapDir = await state.valetonDirHandle.getDirectoryHandle('SnapTone', { create: true });
      const irDir = await state.valetonDirHandle.getDirectoryHandle('UserIR', { create: true });
      const presetsDir = await state.valetonDirHandle.getDirectoryHandle('Presets', { create: true });

      const existingPresets = [];
      for await (const [n] of presetsDir.entries()) existingPresets.push(n);
      const existingNams = new Set();
      for await (const [n] of snapDir.entries()) existingNams.add(n);
      const existingIrs = new Set();
      for await (const [n] of irDir.entries()) existingIrs.add(n);

      const occupied = scanOccupiedSlots(existingPresets);
      const assignedNums = findNextSlots(occupied, requests.length);
      const summaryParts = [];

      for (let i = 0; i < requests.length; i++) {
        const { patch, exportFileName } = requests[i];
        const slotNum = assignedNums[i];
        occupied.add(slotNum);
        const slotCode = formatSlot(slotNum);

        let base = PackExporter.sanitizeReadableFileName(exportFileName || patch.name);
        if (existingNams.has(`${base}.nam`)) base = `${base}_${slotCode}`;
        const namFileName = `${base}.nam`;
        existingNams.add(namFileName);

        const namHandle = await snapDir.getFileHandle(namFileName, { create: true });
        const namW = await namHandle.createWritable();
        await namW.write(patch.namJson);
        await namW.close();

        let irFileName = null;
        if (patch.irWavBase64) {
          let candIr = `${base}_IR.wav`;
          if (existingIrs.has(candIr)) candIr = `${base}_${slotCode}_IR.wav`;
          existingIrs.add(candIr);
          const irHandle = await irDir.getFileHandle(candIr, { create: true });
          const irW = await irHandle.createWritable();
          await irW.write(base64ToUint8(patch.irWavBase64));
          await irW.close();
          irFileName = candIr;
        }

        const presetFileName = `${slotCode}_${base}.json`;
        const presetJson = PackExporter.buildValetonPresetDescriptorJson({
          slotCode,
          slotNumber: slotNum,
          displayTitle: exportFileName || patch.name,
          patch,
          namFileName,
          irFileName
        });
        const pHandle = await presetsDir.getFileHandle(presetFileName, { create: true });
        const pW = await pHandle.createWritable();
        await pW.write(presetJson);
        await pW.close();

        patch.gp5Slot = slotCode;
        await global.ToneDb.updatePatch(patch);
        summaryParts.push(`${slotCode} (${namFileName})`);
      }

      const nextAfter = formatSlot(findNextSlots(occupied, 1)[0] || 1);
      state.nextEmptySlotPreview = nextAfter;
      state.multiSelectIds.clear();
      await refreshPatchesFromDb();
      if (state.selectedPatch) {
        state.selectedPatch = state.patches.find((p) => p.id === state.selectedPatch.id) || state.selectedPatch;
        renderAnalyzerView();
      }
      updateValetonStatusUi();
      showToast(`Exportado a Valeton Suite [${state.valetonFolderName}]: ${summaryParts.join(', ')}`);
    } catch (err) {
      showToast(`Error al exportar a Valeton Suite: ${err.message}`);
    }
  }

  function updateValetonStatusUi() {
    const hasFolder = Boolean(state.valetonFolderName);
    $('valetonFolderStatus').textContent = hasFolder
      ? `Carpeta: ${state.valetonFolderName} · Próx. preset vacío: ${state.nextEmptySlotPreview}`
      : `Vincula la carpeta de Valeton Suite para guardar en SnapTone / UserIR / Presets (${state.nextEmptySlotPreview})`;
    $('btnChangeValetonFolder').classList.toggle('hidden', !hasFolder);
    $('btnExportValetonSuite').textContent = hasFolder
      ? `Enviar a Valeton Suite (${state.nextEmptySlotPreview})`
      : 'Elegir carpeta Valeton Suite';
    $('btnLaunchValetonApp').classList.toggle('hidden', !state.valetonInstalled);

    $('modalValetonFolderName').textContent = state.valetonFolderName || 'Carpeta Valeton Suite no elegida';
    $('modalValetonSlotHint').textContent = `Presets vacíos desde ${state.nextEmptySlotPreview} en orden`;
    $('btnModalPickValetonFolder').textContent = hasFolder ? 'Cambiar' : 'Elegir';
    $('btnModalConfirmValeton').textContent = hasFolder ? 'A Valeton Suite' : 'Elegir carpeta';
  }

  global.__onNativeValetonStatus = (jsonStr) => {
    const info = JSON.parse(jsonStr);
    state.valetonFolderName = info.folderName || null;
    state.nextEmptySlotPreview = info.nextSlot || 'P01';
    state.valetonInstalled = Boolean(info.installed);
    updateValetonStatusUi();
    if (info.message) showToast(info.message);
  };

  global.__onNativeValetonExported = async (jsonStr) => {
    const res = JSON.parse(jsonStr);
    if (res.error) {
      showToast(res.error);
      return;
    }
    for (const entry of res.exportedEntries || []) {
      const p = state.patches.find((item) => item.id === entry.patchId);
      if (p) {
        p.gp5Slot = entry.assignedSlot;
        await global.ToneDb.updatePatch(p);
      }
    }
    state.nextEmptySlotPreview = res.nextEmptySlotAfterExport || state.nextEmptySlotPreview;
    state.multiSelectIds.clear();
    await refreshPatchesFromDb();
    if (state.selectedPatch) {
      state.selectedPatch = state.patches.find((p) => p.id === state.selectedPatch.id) || state.selectedPatch;
      renderAnalyzerView();
    }
    updateValetonStatusUi();
    showToast(res.summaryMessage || 'Exportado a Valeton Suite correctamente.');
  };

  // =========================================================================
  // Patches Manager (IndexedDB)
  // =========================================================================
  async function refreshPatchesFromDb() {
    state.patches = await global.ToneDb.getAllPatches();
    $('patchesCountBadge').textContent = String(state.patches.length);
    $('patchesSubtitle').textContent = `${state.patches.length} tonos locales listos para Valeton Suite`;
    renderPatchesList();
  }

  function renderPatchesList() {
    const listEl = $('patchesList');
    const q = state.searchQuery.trim().toLowerCase();
    const cat = state.categoryFilter;

    const filtered = state.patches.filter((p) => {
      const matchesQ = !q ||
        p.name.toLowerCase().includes(q) ||
        p.gp5Slot.toLowerCase().includes(q) ||
        (p.notes || '').toLowerCase().includes(q);
      let matchesCat = true;
      if (cat === 'Favoritos') matchesCat = Boolean(p.isFavorite);
      else if (cat === 'Guitarra') matchesCat = p.instrumentType !== 'BASS_4';
      else if (cat === 'Bajo 4C') matchesCat = p.instrumentType === 'BASS_4';
      else if (cat !== 'Todos') matchesCat = (p.category || '').toLowerCase() === cat.toLowerCase();
      return matchesQ && matchesCat;
    });

    $('batchSelectBar').classList.toggle('hidden', state.multiSelectIds.size === 0);
    $('batchSelectedCount').textContent = `${state.multiSelectIds.size} seleccionados`;

    if (filtered.length === 0) {
      listEl.innerHTML = '';
      $('patchesEmptyState').classList.remove('hidden');
      return;
    }
    $('patchesEmptyState').classList.add('hidden');
    listEl.innerHTML = '';

    for (const patch of filtered) {
      const isSelected = state.multiSelectIds.has(patch.id);
      const card = document.createElement('div');
      card.className = `patch-card ${isSelected ? 'selected' : ''}`;

      const instrTag = patch.instrumentType === 'BASS_4' ? 'Bajo 4C' : 'Guitarra';
      const capTag = patch.captureMode === 'FULL_RIG' ? 'Full Rig' : 'Amp+IR';
      const dateStr = new Date(patch.createdAt || Date.now()).toLocaleDateString('es-ES', {
        day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'
      });

      card.innerHTML = `
        <div class="patch-card-top">
          <input type="checkbox" class="patch-check" ${isSelected ? 'checked' : ''} aria-label="Seleccionar ${patch.name}">
          <div style="flex:1;min-width:0;cursor:pointer;" class="patch-open-area">
            <div style="display:flex;align-items:center;gap:8px;">
              <span class="slot-badge">${patch.gp5Slot}</span>
              <strong class="truncate" style="font-size:1rem;">${escapeHtml(patch.name)}</strong>
            </div>
            <div class="mono-meta" style="margin-top:3px;">${instrTag} · ${escapeHtml(patch.category)} · ${capTag} · ${patch.sampleRate / 1000} kHz</div>
            <div class="hint-text truncate">${dateStr}</div>
          </div>
          <button class="fav-btn ${patch.isFavorite ? 'active' : ''}" title="Favorito" aria-label="Favorito">
            ${patch.isFavorite ? '♥' : '♡'}
          </button>
        </div>
        ${patch.notes ? `<p class="card-sub truncate">${escapeHtml(patch.notes)}</p>` : ''}
        <div class="patch-card-actions">
          <button class="btn btn-ghost btn-sm text-yellow btn-patch-export">Valeton Suite</button>
          <button class="btn btn-ghost btn-sm text-orange btn-patch-open">Abrir</button>
          <button class="btn btn-ghost btn-sm btn-patch-edit">Editar</button>
          <button class="btn btn-ghost btn-sm btn-patch-del" style="color:var(--red);">Eliminar</button>
        </div>
      `;

      card.querySelector('.patch-check').addEventListener('change', () => {
        if (state.multiSelectIds.has(patch.id)) state.multiSelectIds.delete(patch.id);
        else state.multiSelectIds.add(patch.id);
        renderPatchesList();
      });
      card.querySelector('.patch-open-area').addEventListener('click', () => inspectPatch(patch));
      card.querySelector('.btn-patch-open').addEventListener('click', () => inspectPatch(patch));
      card.querySelector('.fav-btn').addEventListener('click', async () => {
        patch.isFavorite = !patch.isFavorite;
        await global.ToneDb.updatePatch(patch);
        if (state.selectedPatch && state.selectedPatch.id === patch.id) state.selectedPatch = patch;
        renderPatchesList();
      });
      card.querySelector('.btn-patch-export').addEventListener('click', () => openExportModal([patch]));
      card.querySelector('.btn-patch-edit').addEventListener('click', () => openEditModal(patch));
      card.querySelector('.btn-patch-del').addEventListener('click', () => openDeleteModal(patch));

      listEl.appendChild(card);
    }
  }

  function escapeHtml(s) {
    return String(s || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  // =========================================================================
  // Modals (Edit, Delete, Guide, Batch Valeton Export)
  // =========================================================================
  function openEditModal(patch) {
    state.editingPatchId = patch.id;
    state.editingCategory = patch.category || 'Crunch';
    $('editPatchName').value = patch.name;
    $('editPatchSlot').value = patch.gp5Slot;
    $('editPatchNotes').value = patch.notes || '';
    document.querySelectorAll('#editPatchCategoryGroup .chip').forEach((c) => {
      c.classList.toggle('active', c.dataset.ecat === state.editingCategory);
    });
    $('modalEditPatch').classList.remove('hidden');
  }

  function openDeleteModal(patch) {
    state.deletingPatchId = patch.id;
    $('deleteConfirmText').textContent = `Se borrarán '${patch.name}' y sus archivos .nam e IR .wav guardados.`;
    $('modalDeletePatch').classList.remove('hidden');
  }

  function openExportModal(patches) {
    state.exportDialogPatches = patches;
    $('valetonModalTitle').textContent = patches.length === 1
      ? 'Exportar parche con nombre'
      : `Exportar ${patches.length} parches`;
    const startNum = parseInt(String(state.nextEmptySlotPreview).replace(/^P/i, ''), 10) || 1;
    const list = $('modalExportItemsList');
    list.innerHTML = '';

    patches.forEach((p, idx) => {
      const slotLabel = formatSlot(startNum + idx);
      const wrap = document.createElement('div');
      wrap.className = 'field-group';
      wrap.innerHTML = `
        <label class="field-label">Slot ${slotLabel} · Artista / Canción / Sonido</label>
        <input type="text" class="text-input modal-export-name-input" data-pid="${p.id}" value="${escapeHtml(p.name)}">
        <span class="hint-text modal-export-preview">SnapTone/${PackExporter.sanitizeReadableFileName(p.name)}.nam · Presets/${slotLabel}_${PackExporter.sanitizeReadableFileName(p.name)}.json</span>
      `;
      const inp = wrap.querySelector('input');
      const prev = wrap.querySelector('.modal-export-preview');
      inp.addEventListener('input', () => {
        const safe = PackExporter.sanitizeReadableFileName(inp.value || p.name);
        prev.textContent = `SnapTone/${safe}.nam · Presets/${slotLabel}_${safe}.json`;
      });
      list.appendChild(wrap);
    });
    updateValetonStatusUi();
    $('modalValetonExport').classList.remove('hidden');
  }

  async function downloadStandaloneWebAppZip() {
    try {
      const files = ['index.html', 'styles.css', 'dsp.js', 'zip.js', 'pdf.js', 'db.js', 'app.js'];
      const entries = [];
      for (const f of files) {
        const resp = await fetch(f);
        if (!resp.ok) throw new Error(`No se pudo empaquetar ${f}`);
        entries.push({ name: `ToneForgeWeb/${f}`, data: new Uint8Array(await resp.arrayBuffer()) });
      }
      const zipBytes = global.ToneZip.buildZip(entries);
      triggerDownloadBytes(zipBytes, 'ToneForge_WebApp.zip', 'application/zip', 'ToneForge WebApp (.zip)');
    } catch (err) {
      showToast(err.message || 'No se pudo exportar el paquete web.');
    }
  }

  // =========================================================================
  // Event Wiring & Initialization
  // =========================================================================
  function init() {
    // Top navigation
    document.querySelectorAll('.nav-tab').forEach((btn) => {
      btn.addEventListener('click', () => navigateTo(btn.dataset.route));
    });
    $('btnEmptyGoCapture').addEventListener('click', () => navigateTo('capture'));

    // Instrument selector
    document.querySelectorAll('#instrumentModeGroup .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.instrumentMode = INSTRUMENT_MODES[btn.dataset.instrument];
        document.querySelectorAll('#instrumentModeGroup .chip').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
        $('instrumentTuningDesc').textContent = state.instrumentMode.tuningDescription;
      });
    });

    // Source tabs
    document.querySelectorAll('.seg-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        const src = btn.dataset.source;
        state.sourceTab = src;
        setSourceError(null);
        document.querySelectorAll('.seg-btn').forEach((b) => {
          b.classList.toggle('active', b.dataset.source === src);
          b.setAttribute('aria-selected', b.dataset.source === src ? 'true' : 'false');
        });
        $('paneFile').classList.toggle('active', src === 'FILE');
        $('paneLink').classList.toggle('active', src === 'LINK');
        $('paneMic').classList.toggle('active', src === 'MIC');
      });
    });

    // File Picker & Drag-and-Drop
    const dropzone = $('fileDropzone');
    $('audioFileInput').addEventListener('change', (e) => {
      if (e.target.files && e.target.files[0]) handleFileImport(e.target.files[0]);
    });
    dropzone.addEventListener('dragover', (e) => { e.preventDefault(); dropzone.classList.add('dragover'); });
    dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
    dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragover');
      if (e.dataTransfer.files && e.dataTransfer.files[0]) handleFileImport(e.dataTransfer.files[0]);
    });

    // Link & Mic
    $('btnExtractLink').addEventListener('click', handleExtractLink);
    $('btnToggleRecord').addEventListener('click', toggleRecording);

    // Waveform trimming
    setupWaveformDragging();
    $('trimStartSlider').addEventListener('input', (e) => {
      updateTrimRange(Number(e.target.value) / 100, state.trimEndFrac);
    });
    $('trimEndSlider').addEventListener('input', (e) => {
      updateTrimRange(state.trimStartFrac, Number(e.target.value) / 100);
    });
    $('btnPreviewTrim').addEventListener('click', previewTrimmedSelection);

    // GP-5 Config
    document.querySelectorAll('#captureModeGroup .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.captureMode = CAPTURE_MODES[btn.dataset.capture];
        document.querySelectorAll('#captureModeGroup .chip').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
        $('captureModeDesc').textContent = state.captureMode.description;
      });
    });

    document.querySelectorAll('#modelQualityGroup .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.modelQuality = MODEL_QUALITIES[btn.dataset.quality];
        document.querySelectorAll('#modelQualityGroup .chip').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
      });
    });

    document.querySelectorAll('[data-ir-rate]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.irSampleRate = Number(btn.dataset.irRate);
        document.querySelectorAll('[data-ir-rate]').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
      });
    });

    document.querySelectorAll('[data-ir-len]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.irLengthSamples = Number(btn.dataset.irLen);
        document.querySelectorAll('[data-ir-len]').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
      });
    });

    $('btnGenerateSnapTone').addEventListener('click', generateSnapTone);

    // Analyzer Audition & Exports
    document.querySelectorAll('.btn-audition').forEach((btn) => {
      btn.addEventListener('click', () => toggleAudition(btn.dataset.audition));
    });

    $('exportFileNameInput').addEventListener('input', updateExportPreview);

    $('btnDownloadNam').addEventListener('click', () => {
      const p = state.selectedPatch;
      if (!p) return;
      const base = getEffectiveExportBaseName();
      triggerDownloadBytes(p.namJson, `${base}.nam`, 'application/octet-stream', 'SnapTone .nam');
    });

    $('btnDownloadIr').addEventListener('click', () => {
      const p = state.selectedPatch;
      if (!p || !p.irWavBase64) return;
      const base = getEffectiveExportBaseName();
      triggerDownloadBytes(base64ToUint8(p.irWavBase64), `${base}_IR.wav`, 'audio/wav', 'Cab IR .wav');
    });

    $('btnDownloadZipPack').addEventListener('click', () => {
      const p = state.selectedPatch;
      if (!p) return;
      const base = getEffectiveExportBaseName();
      const zipBytes = buildZipBytesForPairs([{ patch: p, customName: base }]);
      triggerDownloadBytes(zipBytes, `${base}_GP5_Pack.zip`, 'application/zip', 'Pack GP-5 (.zip)');
    });

    $('btnDownloadPdf').addEventListener('click', () => {
      const p = state.selectedPatch;
      if (!p) return;
      try {
        const base = getEffectiveExportBaseName();
        const pdfBytes = global.TonePdf.buildPatchPdf(p);
        triggerDownloadBytes(pdfBytes, `${base}_GP5_Resumen.pdf`, 'application/pdf', 'Resumen PDF GP-5');
      } catch (err) {
        showToast(err.message || 'No se pudo generar el PDF del parche.');
      }
    });

    $('btnShareNam').addEventListener('click', () => {
      const p = state.selectedPatch;
      if (!p) return;
      const base = getEffectiveExportBaseName();
      shareBytes(p.namJson, `${base}.nam`, 'application/json', 'Compartir SnapTone (.nam)');
    });

    $('btnShareIr').addEventListener('click', () => {
      const p = state.selectedPatch;
      if (!p || !p.irWavBase64) return;
      const base = getEffectiveExportBaseName();
      shareBytes(base64ToUint8(p.irWavBase64), `${base}_IR.wav`, 'audio/wav', 'Compartir Cab IR (.wav)');
    });

    $('btnChangeValetonFolder').addEventListener('click', pickValetonFolder);
    $('btnExportValetonSuite').addEventListener('click', () => {
      if (!state.selectedPatch) return;
      if (state.valetonFolderName) {
        exportRequestsToValeton([{ patch: state.selectedPatch, exportFileName: getEffectiveExportBaseName() }]);
      } else {
        pickValetonFolder();
      }
    });
    $('btnLaunchValetonApp').addEventListener('click', () => {
      if (nativeBridge && typeof nativeBridge.launchValetonSuite === 'function') {
        nativeBridge.launchValetonSuite();
      }
    });

    document.querySelectorAll('#convertFormatChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.convertFormat = btn.dataset.fmt;
        document.querySelectorAll('#convertFormatChips .chip').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
        updateConvertButtonLabel();
      });
    });
    $('btnConvertIrFormat').addEventListener('click', convertSelectedIrFormat);

    // Patches View
    $('patchesSearchInput').addEventListener('input', (e) => {
      state.searchQuery = e.target.value;
      renderPatchesList();
    });
    document.querySelectorAll('#categoryFilterChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.categoryFilter = btn.dataset.cat;
        document.querySelectorAll('#categoryFilterChips .chip').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
        renderPatchesList();
      });
    });
    $('btnClearMultiSelect').addEventListener('click', () => {
      state.multiSelectIds.clear();
      renderPatchesList();
    });
    $('btnBatchExport').addEventListener('click', () => {
      const selected = state.patches.filter((p) => state.multiSelectIds.has(p.id));
      if (selected.length > 0) openExportModal(selected);
    });

    // Modals
    $('btnOpenGuideTop').addEventListener('click', () => $('modalGp5Guide').classList.remove('hidden'));
    $('btnOpenGuidePatches').addEventListener('click', () => $('modalGp5Guide').classList.remove('hidden'));
    $('btnCloseGuide').addEventListener('click', () => $('modalGp5Guide').classList.add('hidden'));
    $('btnDownloadWebApp').addEventListener('click', downloadStandaloneWebAppZip);

    document.querySelectorAll('#editPatchCategoryGroup .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.editingCategory = btn.dataset.ecat;
        document.querySelectorAll('#editPatchCategoryGroup .chip').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
      });
    });
    $('btnCancelEdit').addEventListener('click', () => $('modalEditPatch').classList.add('hidden'));
    $('btnSaveEdit').addEventListener('click', async () => {
      const p = state.patches.find((item) => item.id === state.editingPatchId);
      if (!p) return;
      p.name = $('editPatchName').value.trim() || p.name;
      p.gp5Slot = $('editPatchSlot').value.trim() || p.gp5Slot;
      p.notes = $('editPatchNotes').value.trim();
      p.category = state.editingCategory;
      await global.ToneDb.updatePatch(p);
      if (state.selectedPatch && state.selectedPatch.id === p.id) {
        state.selectedPatch = p;
        renderAnalyzerView();
      }
      $('modalEditPatch').classList.add('hidden');
      await refreshPatchesFromDb();
      showToast(`Parche '${p.name}' actualizado.`);
    });

    $('btnCancelDelete').addEventListener('click', () => $('modalDeletePatch').classList.add('hidden'));
    $('btnConfirmDelete').addEventListener('click', async () => {
      const id = state.deletingPatchId;
      if (id != null) {
        await global.ToneDb.deletePatch(id);
        state.multiSelectIds.delete(id);
        if (state.selectedPatch && state.selectedPatch.id === id) {
          state.selectedPatch = null;
          renderAnalyzerView();
        }
        await refreshPatchesFromDb();
        showToast('Parche eliminado.');
      }
      $('modalDeletePatch').classList.add('hidden');
    });

    $('btnModalCancelExport').addEventListener('click', () => $('modalValetonExport').classList.add('hidden'));
    $('btnModalPickValetonFolder').addEventListener('click', pickValetonFolder);
    $('btnModalExportZip').addEventListener('click', () => {
      const inputs = Array.from(document.querySelectorAll('.modal-export-name-input'));
      const pairs = state.exportDialogPatches.map((p, i) => ({
        patch: p,
        customName: (inputs[i] ? inputs[i].value.trim() : p.name) || p.name
      }));
      const zipBytes = buildZipBytesForPairs(pairs);
      const zipName = pairs.length === 1
        ? `${PackExporter.sanitizeReadableFileName(pairs[0].customName)}_GP5_Pack.zip`
        : 'ToneForge_GP5_Pack.zip';
      triggerDownloadBytes(zipBytes, zipName, 'application/zip', 'Pack GP-5 (.zip)');
      $('modalValetonExport').classList.add('hidden');
    });
    $('btnModalConfirmValeton').addEventListener('click', async () => {
      if (!state.valetonFolderName) {
        await pickValetonFolder();
        return;
      }
      const inputs = Array.from(document.querySelectorAll('.modal-export-name-input'));
      const reqs = state.exportDialogPatches.map((p, i) => ({
        patch: p,
        exportFileName: (inputs[i] ? inputs[i].value.trim() : p.name) || p.name
      }));
      $('modalValetonExport').classList.add('hidden');
      await exportRequestsToValeton(reqs);
    });

    window.addEventListener('resize', () => {
      if (state.route === 'capture' && state.decodedAudio) drawWaveform();
      if (state.route === 'analyzer' && state.selectedPatch) drawSpectralChart();
    });

    // Load saved patches & query native status if inside APK shell
    refreshPatchesFromDb();
    if (nativeBridge && typeof nativeBridge.refreshValetonStatus === 'function') {
      nativeBridge.refreshValetonStatus();
    }
  }

  global.__handleBackPress = () => {
    const modals = ['modalValetonExport', 'modalGp5Guide', 'modalDeletePatch', 'modalEditPatch'];
    for (const id of modals) {
      const el = $(id);
      if (el && !el.classList.contains('hidden')) {
        el.classList.add('hidden');
        return true;
      }
    }
    if (state.route !== 'capture') {
      navigateTo('capture');
      return true;
    }
    return false;
  };

  document.addEventListener('DOMContentLoaded', init);
})(window);
