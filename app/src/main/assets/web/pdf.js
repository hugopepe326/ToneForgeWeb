/**
 * ToneForge Web — Generador PDF 1.4 puro (sin dependencias externas)
 * Genera documentos PDF binarios reales con codificación WinAnsi (acentos españoles)
 * y tabla xref exacta para descarga directa en navegador o Android WebView.
 */
(function (global) {
  'use strict';

  const PAGE_W = 595; // A4 width in points (72 dpi)
  const PAGE_H = 842; // A4 height in points
  const MARGIN_X = 40;
  const CONTENT_W = PAGE_W - MARGIN_X * 2;
  const TOP_Y = 804;
  const BOTTOM_Y = 48;

  // Mapeo de caracteres Unicode comunes a Windows-1252 (WinAnsiEncoding)
  const WINANSI_MAP = {
    0x20AC: 0x80, // €
    0x201A: 0x82, // ‚
    0x201E: 0x84, // „
    0x2026: 0x85, // …
    0x2020: 0x86, // †
    0x2021: 0x87, // ‡
    0x2030: 0x89, // ‰
    0x2039: 0x8B, // ‹
    0x2018: 0x91, // ‘
    0x2019: 0x92, // ’
    0x201C: 0x93, // “
    0x201D: 0x94, // ”
    0x2022: 0x95, // •
    0x2013: 0x96, // –
    0x2014: 0x97, // —
    0x2122: 0x99, // ™
    0x203A: 0x9B  // ›
  };

  function normalizeTextForPdf(str) {
    return String(str == null ? '' : str)
      .replace(/→/g, '->')
      .replace(/←/g, '<-')
      .replace(/≈/g, '~')
      .replace(/≥/g, '>=')
      .replace(/≤/g, '<=')
      .replace(/\r\n/g, '\n')
      .replace(/\r/g, '\n')
      .replace(/\t/g, '  ');
  }

  function encodeWinAnsiBytes(str) {
    const clean = normalizeTextForPdf(str);
    const out = [];
    for (let i = 0; i < clean.length; i++) {
      const code = clean.charCodeAt(i);
      if (code >= 0x20 && code <= 0x7E) {
        if (code === 0x28 || code === 0x29 || code === 0x5C) {
          out.push(0x5C); // '\'
        }
        out.push(code);
      } else if (code >= 0xA0 && code <= 0xFF) {
        out.push(code);
      } else if (WINANSI_MAP[code] !== undefined) {
        out.push(WINANSI_MAP[code]);
      } else if (code === 0x0A || code === 0x0D) {
        out.push(0x20);
      } else {
        // Fallback ASCII para caracteres fuera de WinAnsi (ej. emojis)
        out.push(0x3F); // '?'
      }
    }
    return out;
  }

  function concatByteChunks(chunks) {
    let total = 0;
    for (let i = 0; i < chunks.length; i++) total += chunks[i].length;
    const result = new Uint8Array(total);
    let offset = 0;
    for (let i = 0; i < chunks.length; i++) {
      result.set(chunks[i], offset);
      offset += chunks[i].length;
    }
    return result;
  }

  function asciiBytes(str) {
    const arr = new Uint8Array(str.length);
    for (let i = 0; i < str.length; i++) {
      arr[i] = str.charCodeAt(i) & 0xFF;
    }
    return arr;
  }

  // Convierte una cadena ASCII con placeholders de texto literal PDF ya escapado
  // o construye stream binario directo con operadores de texto WinAnsi
  class PageCanvas {
    constructor() {
      this.chunks = [];
    }

    cmd(line) {
      this.chunks.push(asciiBytes(line + '\n'));
    }

    setFillRgb(r, g, b) {
      this.cmd(`${r.toFixed(3)} ${g.toFixed(3)} ${b.toFixed(3)} rg`);
    }

    setStrokeRgb(r, g, b) {
      this.cmd(`${r.toFixed(3)} ${g.toFixed(3)} ${b.toFixed(3)} RG`);
    }

    fillRect(x, y, w, h, r, g, b) {
      this.setFillRgb(r, g, b);
      this.cmd(`${x.toFixed(2)} ${y.toFixed(2)} ${w.toFixed(2)} ${h.toFixed(2)} re f`);
    }

    strokeRect(x, y, w, h, r, g, b, lineWidth) {
      this.setStrokeRgb(r, g, b);
      this.cmd(`${(lineWidth || 0.75).toFixed(2)} w`);
      this.cmd(`${x.toFixed(2)} ${y.toFixed(2)} ${w.toFixed(2)} ${h.toFixed(2)} re S`);
    }

    line(x1, y1, x2, y2, r, g, b, lineWidth) {
      this.setStrokeRgb(r, g, b);
      this.cmd(`${(lineWidth || 0.75).toFixed(2)} w`);
      this.cmd(`${x1.toFixed(2)} ${y1.toFixed(2)} m ${x2.toFixed(2)} ${y2.toFixed(2)} l S`);
    }

    text(x, y, str, opts) {
      const o = opts || {};
      const font = o.bold ? '/F2' : '/F1';
      const size = o.size || 10;
      const color = o.color || [0.08, 0.08, 0.1];
      this.setFillRgb(color[0], color[1], color[2]);
      this.chunks.push(asciiBytes(`BT ${font} ${size.toFixed(1)} Tf 1 0 0 1 ${x.toFixed(2)} ${y.toFixed(2)} Tm (`));
      this.chunks.push(new Uint8Array(encodeWinAnsiBytes(str)));
      this.chunks.push(asciiBytes(`) Tj ET\n`));
    }

    toBytes() {
      return concatByteChunks(this.chunks);
    }
  }

  function wrapText(text, maxCharsPerLine) {
    const normalized = normalizeTextForPdf(text).trim();
    if (!normalized) return [];
    const paragraphs = normalized.split('\n');
    const lines = [];
    for (const para of paragraphs) {
      const trimmed = para.trim();
      if (!trimmed) {
        lines.push('');
        continue;
      }
      const words = trimmed.split(/\s+/);
      let current = '';
      for (const word of words) {
        if (!current) {
          if (word.length > maxCharsPerLine) {
            for (let i = 0; i < word.length; i += maxCharsPerLine) {
              lines.push(word.slice(i, i + maxCharsPerLine));
            }
          } else {
            current = word;
          }
        } else if (current.length + 1 + word.length <= maxCharsPerLine) {
          current += ' ' + word;
        } else {
          lines.push(current);
          if (word.length > maxCharsPerLine) {
            for (let i = 0; i < word.length; i += maxCharsPerLine) {
              const part = word.slice(i, i + maxCharsPerLine);
              if (i + maxCharsPerLine < word.length) lines.push(part);
              else current = part;
            }
          } else {
            current = word;
          }
        }
      }
      if (current) lines.push(current);
    }
    return lines;
  }

  function formatDateEs(timestampMs) {
    try {
      const d = new Date(timestampMs || Date.now());
      return d.toLocaleString('es-ES', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      });
    } catch (_) {
      return new Date(timestampMs || Date.now()).toISOString().slice(0, 16).replace('T', ' ');
    }
  }

  function assemblePdfDocument(pageCanvases) {
    // Objetos:
    // 1: Catalog
    // 2: Pages
    // 3: Font /F1 Helvetica (WinAnsiEncoding)
    // 4: Font /F2 Helvetica-Bold (WinAnsiEncoding)
    // Para cada página i:
    //   pageObjId = 5 + i * 2
    //   contentObjId = 6 + i * 2
    const objects = [];
    const pageIds = [];
    for (let i = 0; i < pageCanvases.length; i++) {
      pageIds.push(5 + i * 2);
    }

    objects.push({
      id: 1,
      body: asciiBytes('<< /Type /Catalog /Pages 2 0 R >>')
    });
    objects.push({
      id: 2,
      body: asciiBytes(`<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${pageIds.length} >>`)
    });
    objects.push({
      id: 3,
      body: asciiBytes('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>')
    });
    objects.push({
      id: 4,
      body: asciiBytes('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>')
    });

    for (let i = 0; i < pageCanvases.length; i++) {
      const pageId = 5 + i * 2;
      const contentId = 6 + i * 2;
      const streamBytes = pageCanvases[i].toBytes();
      objects.push({
        id: pageId,
        body: asciiBytes(
          `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PAGE_W} ${PAGE_H}] ` +
          `/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${contentId} 0 R >>`
        )
      });
      objects.push({
        id: contentId,
        body: concatByteChunks([
          asciiBytes(`<< /Length ${streamBytes.length} >>\nstream\n`),
          streamBytes,
          asciiBytes('\nendstream')
        ])
      });
    }

    const parts = [];
    // Cabecera PDF 1.4 con marcador binario recomendado
    parts.push(new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, 0x25, 0xE2, 0xE3, 0xCF, 0xD3, 0x0A]));
    let cursor = parts[0].length;
    const offsets = [0]; // índice 0 libre

    for (const obj of objects) {
      offsets[obj.id] = cursor;
      const head = asciiBytes(`${obj.id} 0 obj\n`);
      const tail = asciiBytes('\nendobj\n');
      parts.push(head, obj.body, tail);
      cursor += head.length + obj.body.length + tail.length;
    }

    const xrefOffset = cursor;
    const totalObjs = objects.length + 1;
    let xref = `xref\n0 ${totalObjs}\n0000000000 65535 f \n`;
    for (let id = 1; id < totalObjs; id++) {
      const offStr = String(offsets[id]).padStart(10, '0');
      xref += `${offStr} 00000 n \n`;
    }
    const trailer =
      `trailer\n<< /Size ${totalObjs} /Root 1 0 R >>\n` +
      `startxref\n${xrefOffset}\n%%EOF\n`;
    parts.push(asciiBytes(xref + trailer));

    return concatByteChunks(parts);
  }

  /**
   * Construye el PDF simple del resumen del parche y la receta de cadena Valeton GP-5.
   */
  function buildPatchPdf(patch, customDisplayName) {
    if (!patch) throw new Error('No hay parche seleccionado.');

    const pages = [];
    let page = new PageCanvas();
    let y = TOP_Y;

    const DARK = [0.06, 0.06, 0.08];
    const MUTED = [0.36, 0.36, 0.42];
    const RED = [0.898, 0.145, 0.129];
    const ORANGE = [0.95, 0.40, 0.0];
    const YELLOW = [1.0, 0.72, 0.0];
    const WHITE = [1.0, 1.0, 1.0];
    const CARD_BG = [0.965, 0.965, 0.975];
    const BORDER = [0.82, 0.82, 0.86];

    const startNewPage = () => {
      pages.push(page);
      page = new PageCanvas();
      y = TOP_Y;
      // Cabecera compacta de continuación
      page.fillRect(MARGIN_X, y - 22, CONTENT_W, 24, 0.07, 0.07, 0.09);
      page.fillRect(MARGIN_X, y - 24, CONTENT_W, 2.5, ORANGE[0], ORANGE[1], ORANGE[2]);
      page.text(MARGIN_X + 10, y - 14, `TONEFORGE WEB · ${customDisplayName || patch.name || 'Parche GP-5'}`, {
        bold: true, size: 9, color: WHITE
      });
      page.text(PAGE_W - MARGIN_X - 72, y - 14, `Slot ${patch.gp5Slot || 'P01'}`, {
        bold: true, size: 9, color: YELLOW
      });
      y -= 42;
    };

    const ensureSpace = (neededHeight) => {
      if (y - neededHeight < BOTTOM_Y) {
        startNewPage();
      }
    };

    const drawHeader = () => {
      const boxH = 56;
      page.fillRect(MARGIN_X, y - boxH, CONTENT_W, boxH, 0.05, 0.05, 0.07);
      // Franja tricolor rojo / naranja / amarillo
      const segW = CONTENT_W / 3;
      page.fillRect(MARGIN_X, y - boxH - 3, segW, 3, RED[0], RED[1], RED[2]);
      page.fillRect(MARGIN_X + segW, y - boxH - 3, segW, 3, ORANGE[0], ORANGE[1], ORANGE[2]);
      page.fillRect(MARGIN_X + segW * 2, y - boxH - 3, segW, 3, YELLOW[0], YELLOW[1], YELLOW[2]);

      page.text(MARGIN_X + 14, y - 23, 'TONEFORGE WEB', { bold: true, size: 15, color: WHITE });
      page.text(MARGIN_X + 14, y - 40, 'Resumen de Parche y Receta Cadena Valeton GP-5', {
        bold: false, size: 9.5, color: [0.82, 0.82, 0.86]
      });

      const slotLabel = `GP-5 · ${patch.gp5Slot || 'P01'}`;
      page.fillRect(PAGE_W - MARGIN_X - 92, y - 38, 78, 22, RED[0], RED[1], RED[2]);
      page.text(PAGE_W - MARGIN_X - 80, y - 30, slotLabel, { bold: true, size: 10, color: WHITE });
      y -= (boxH + 22);
    };

    drawHeader();

    // ==================== SECCIÓN 1: RESUMEN DEL PARCHE ====================
    page.text(MARGIN_X, y, 'RESUMEN DEL PARCHE', { bold: true, size: 11, color: RED });
    y -= 6;
    page.line(MARGIN_X, y, MARGIN_X + CONTENT_W, y, ORANGE[0], ORANGE[1], ORANGE[2], 1.2);
    y -= 16;

    const displayName = (customDisplayName || patch.name || 'Sin nombre').trim();
    const category = (patch.category || (patch.metrics && patch.metrics.detectedCategory) || 'General').trim();
    const instrumentLabel = patch.instrumentType === 'BASS_4'
      ? 'Bajo 4 Cuerdas (E-A-D-G)'
      : 'Guitarra Eléctrica';
    const captureLabel = patch.captureMode === 'FULL_RIG'
      ? 'Full Rig (.nam completo)'
      : 'Amp (.nam) + IR (.wav)';
    const notesRaw = (patch.notes || '').trim() || 'Sin notas registradas para este parche.';
    const noteLines = wrapText(notesRaw, 84);
    const metaLinesCount = 4;
    const fullSummaryBoxH = 22 + metaLinesCount * 16 + 14 + Math.max(1, noteLines.length) * 13 + 10;
    const maxSinglePageH = y - BOTTOM_Y - 8;

    const labelX = MARGIN_X + 12;
    const valX = MARGIN_X + 108;

    const drawMetaRow = (syPos, label, value, boldValue) => {
      page.text(labelX, syPos, label, { bold: true, size: 9.5, color: MUTED });
      page.text(valX, syPos, String(value || '-').slice(0, 72), { bold: !!boldValue, size: 9.8, color: DARK });
    };

    if (fullSummaryBoxH <= maxSinglePageH) {
      page.fillRect(MARGIN_X, y - fullSummaryBoxH, CONTENT_W, fullSummaryBoxH, CARD_BG[0], CARD_BG[1], CARD_BG[2]);
      page.strokeRect(MARGIN_X, y - fullSummaryBoxH, CONTENT_W, fullSummaryBoxH, BORDER[0], BORDER[1], BORDER[2], 0.75);

      let sy = y - 16;
      drawMetaRow(sy, 'Nombre:', displayName, true); sy -= 16;
      drawMetaRow(sy, 'Categoría:', `${category}   |   Slot Valeton: ${patch.gp5Slot || 'P01'}`, true); sy -= 16;
      drawMetaRow(sy, 'Instrumento:', `${instrumentLabel}   |   Modo: ${captureLabel}`, false); sy -= 16;
      drawMetaRow(sy, 'Fecha:', `${formatDateEs(patch.createdAt)}   |   ${(patch.sampleRate || 48000) / 1000} kHz · ${patch.irSamplesCount || 1024} pts IR`, false); sy -= 20;

      page.text(labelX, sy, 'Notas:', { bold: true, size: 9.5, color: MUTED });
      for (let i = 0; i < noteLines.length; i++) {
        page.text(valX, sy - i * 13, noteLines[i], { bold: false, size: 9.3, color: DARK });
      }
      y -= (fullSummaryBoxH + 18);
    } else {
      // Metadatos en tarjeta fija y notas paginadas línea a línea para notas extensas
      const metaBoxH = 22 + metaLinesCount * 16 + 6;
      ensureSpace(metaBoxH + 30);
      page.fillRect(MARGIN_X, y - metaBoxH, CONTENT_W, metaBoxH, CARD_BG[0], CARD_BG[1], CARD_BG[2]);
      page.strokeRect(MARGIN_X, y - metaBoxH, CONTENT_W, metaBoxH, BORDER[0], BORDER[1], BORDER[2], 0.75);
      let sy = y - 16;
      drawMetaRow(sy, 'Nombre:', displayName, true); sy -= 16;
      drawMetaRow(sy, 'Categoría:', `${category}   |   Slot Valeton: ${patch.gp5Slot || 'P01'}`, true); sy -= 16;
      drawMetaRow(sy, 'Instrumento:', `${instrumentLabel}   |   Modo: ${captureLabel}`, false); sy -= 16;
      drawMetaRow(sy, 'Fecha:', `${formatDateEs(patch.createdAt)}   |   ${(patch.sampleRate || 48000) / 1000} kHz · ${patch.irSamplesCount || 1024} pts IR`, false);
      y -= (metaBoxH + 14);

      page.text(MARGIN_X, y, 'Notas:', { bold: true, size: 9.5, color: MUTED });
      y -= 14;
      for (let i = 0; i < noteLines.length; i++) {
        ensureSpace(14);
        page.text(MARGIN_X + 8, y, noteLines[i], { bold: false, size: 9.3, color: DARK });
        y -= 13;
      }
      y -= 10;
    }

    // ==================== SECCIÓN 2: PERFIL TONAL ====================
    const m = patch.metrics || {};
    ensureSpace(78);
    page.text(MARGIN_X, y, 'PERFIL TONAL Y TELEMETRÍA DSP', { bold: true, size: 10.5, color: ORANGE });
    y -= 6;
    page.line(MARGIN_X, y, MARGIN_X + CONTENT_W, y, BORDER[0], BORDER[1], BORDER[2], 0.8);
    y -= 15;

    const harmonicDesc = (m.evenOddHarmonicRatio || 1) >= 1.0 ? 'Cálido (Armónicos Pares)' : 'Agresivo (Impares)';
    page.text(MARGIN_X + 4, y, `Arquetipo Amp: ${m.ampArchetype || '-'}`, { bold: true, size: 9.2, color: DARK });
    y -= 14;
    page.text(MARGIN_X + 4, y, `Pantalla / Cab: ${m.cabinetCharacter || '-'}`, { bold: false, size: 9.2, color: DARK });
    y -= 14;
    page.text(
      MARGIN_X + 4,
      y,
      `Ganancia: ${m.gainLevel ?? '-'}/100   |   THD: ${m.thdPercent ?? '-'}%   |   Dinámica/Sag: ${m.compressionSag ?? '-'}%   |   Foco: ${Math.round(m.spectralCentroidHz || 0)} Hz (${harmonicDesc})`,
      { bold: false, size: 8.8, color: MUTED }
    );
    y -= 22;

    // ==================== SECCIÓN 3: RECETA CADENA VALETON GP-5 ====================
    const chain = patch.gp5Chain || { summary: '', modules: [] };
    ensureSpace(50);
    page.text(MARGIN_X, y, 'RECETA DE LA CADENA DE SEÑAL — VALETON GP-5', { bold: true, size: 11, color: RED });
    y -= 6;
    page.line(MARGIN_X, y, MARGIN_X + CONTENT_W, y, ORANGE[0], ORANGE[1], ORANGE[2], 1.2);
    y -= 14;

    if (chain.summary) {
      const sumLines = wrapText(chain.summary, 90);
      for (const line of sumLines) {
        ensureSpace(15);
        page.text(MARGIN_X, y, line, { bold: false, size: 9, color: MUTED });
        y -= 13;
      }
      y -= 4;
    }

    const modules = Array.isArray(chain.modules) ? chain.modules : [];
    if (modules.length === 0) {
      ensureSpace(24);
      page.text(MARGIN_X, y, 'No hay bloques configurados en la receta de cadena.', { bold: false, size: 9.5, color: MUTED });
      y -= 18;
    } else {
      for (let i = 0; i < modules.length; i++) {
        const mod = modules[i];
        const paramsEntries = mod.params ? Object.entries(mod.params) : [];
        const paramsStr = (mod.enabled && paramsEntries.length > 0)
          ? paramsEntries.map(([k, v]) => `${k}: ${v}`).join('   |   ')
          : (mod.enabled ? 'Sin ajustes adicionales' : 'Módulo desactivado (Bypass)');
        const paramLines = wrapText(paramsStr, 78);
        const rowH = 26 + Math.max(1, paramLines.length) * 12;

        ensureSpace(rowH + 6);

        const bg = mod.enabled ? [0.985, 0.985, 0.99] : [0.95, 0.95, 0.96];
        const borderCol = mod.enabled ? [0.86, 0.72, 0.56] : BORDER;
        page.fillRect(MARGIN_X, y - rowH, CONTENT_W, rowH, bg[0], bg[1], bg[2]);
        page.strokeRect(MARGIN_X, y - rowH, CONTENT_W, rowH, borderCol[0], borderCol[1], borderCol[2], 0.75);

        // Badge del bloque (NR, PRE, AMP, CAB, EQ, REV)
        const badgeColor = mod.enabled ? ORANGE : [0.55, 0.55, 0.6];
        page.fillRect(MARGIN_X + 8, y - 21, 38, 15, badgeColor[0], badgeColor[1], badgeColor[2]);
        page.text(MARGIN_X + 14, y - 16.5, String(mod.moduleCode || 'MOD').slice(0, 5), {
          bold: true, size: 8.5, color: WHITE
        });

        // Título módulo + modelo
        const titleText = `${mod.moduleName || 'Bloque'}  ·  ${mod.modelOrType || '-'}`;
        page.text(MARGIN_X + 54, y - 16, titleText.slice(0, 68), {
          bold: true, size: 9.6, color: mod.enabled ? DARK : MUTED
        });

        // Estado ON / OFF
        const stateText = mod.enabled ? 'ON' : 'OFF';
        const stateColor = mod.enabled ? RED : MUTED;
        page.text(PAGE_W - MARGIN_X - 32, y - 16, stateText, {
          bold: true, size: 9.2, color: stateColor
        });

        // Parámetros
        for (let pIdx = 0; pIdx < paramLines.length; pIdx++) {
          page.text(MARGIN_X + 54, y - 31 - pIdx * 12, paramLines[pIdx], {
            bold: false, size: 8.7, color: mod.enabled ? [0.22, 0.22, 0.26] : MUTED
          });
        }

        y -= (rowH + 6);
      }
    }

    // Pie de página en todas las páginas
    pages.push(page);
    const totalPages = pages.length;
    for (let p = 0; p < totalPages; p++) {
      pages[p].line(MARGIN_X, 34, MARGIN_X + CONTENT_W, 34, BORDER[0], BORDER[1], BORDER[2], 0.6);
      pages[p].text(
        MARGIN_X,
        22,
        `ToneForge Web · Receta Valeton GP-5 (${displayName.slice(0, 36)})`,
        { bold: false, size: 8, color: MUTED }
      );
      pages[p].text(
        PAGE_W - MARGIN_X - 58,
        22,
        `Página ${p + 1} de ${totalPages}`,
        { bold: false, size: 8, color: MUTED }
      );
    }

    return assemblePdfDocument(pages);
  }

  global.TonePdf = {
    buildPatchPdf,
    normalizeTextForPdf,
    wrapText
  };
})(typeof window !== 'undefined' ? window : globalThis);
