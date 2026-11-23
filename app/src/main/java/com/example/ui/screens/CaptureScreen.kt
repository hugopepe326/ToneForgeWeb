package com.example.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.dsp.AudioSourceType
import com.example.dsp.CaptureMode
import com.example.dsp.InstrumentMode
import com.example.dsp.ModelQuality
import com.example.ui.ToneForgeUiState
import com.example.ui.components.FilePicker
import com.example.ui.components.InteractiveWaveformTrimmer
import com.example.ui.components.LiveVuMeterBar
import com.example.ui.theme.ClipCrimson
import com.example.ui.theme.CopperAmber
import com.example.ui.theme.CopperAmberBright
import com.example.ui.theme.CopperContainer
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.IvoryText
import com.example.ui.theme.MutedSteel
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PrecisionTeal
import com.example.ui.theme.PrecisionTealContainer
import com.example.ui.theme.RackCard
import com.example.ui.theme.RackElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TitaniumText

@Composable
fun CaptureScreen(
    uiState: ToneForgeUiState,
    onSelectTab: (AudioSourceType) -> Unit,
    onPickAudioUri: (Uri) -> Unit,
    onUpdateLink: (String) -> Unit,
    onExtractLink: () -> Unit,
    onToggleRecording: () -> Unit,
    onUpdateTrimRange: (Float, Float) -> Unit,
    onSelectInstrumentMode: (InstrumentMode) -> Unit,
    onUpdatePatchName: (String) -> Unit,
    onSelectCaptureMode: (CaptureMode) -> Unit,
    onSelectModelQuality: (ModelQuality) -> Unit,
    onSelectIrSampleRate: (Int) -> Unit,
    onSelectIrLength: (Int) -> Unit,
    onGenerateSnapTone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Studio Header Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ToneForge Studio",
                        style = MaterialTheme.typography.headlineMedium,
                        color = IvoryText
                    )
                    Text(
                        text = stringResource(R.string.subtitle_studio),
                        style = MaterialTheme.typography.labelSmall,
                        color = CopperAmber
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrecisionTealContainer)
                        .border(1.dp, PrecisionTeal.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "100% LOCAL · SIN SERVIDOR",
                        style = MaterialTheme.typography.labelSmall,
                        color = PrecisionTeal
                    )
                }
            }

            // Instrument Selector Rack: Auto / Guitarra / Bajo 4 Cuerdas Std (E-A-D-G)
            Card(
                colors = CardDefaults.cardColors(containerColor = RackCard),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "INSTRUMENTO Y AFINACIÓN",
                            style = MaterialTheme.typography.labelMedium,
                            color = TitaniumText
                        )
                        uiState.detectionResult?.let { det ->
                            val isBassDet = det.detectedInstrument == InstrumentMode.BASS_4
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isBassDet) PrecisionTealContainer else CopperContainer)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "Detectado: ${det.detectedInstrument.shortLabel} (${det.dominantFundamentalHz.toInt()} Hz)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isBassDet) PrecisionTeal else CopperAmberBright
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InstrumentMode.entries.forEach { mode ->
                            val selected = uiState.instrumentMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectInstrumentMode(mode) },
                                label = {
                                    Text(
                                        text = mode.shortLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = if (mode == InstrumentMode.BASS_4) PrecisionTealContainer else CopperContainer,
                                    selectedLabelColor = if (mode == InstrumentMode.BASS_4) PrecisionTeal else CopperAmberBright,
                                    containerColor = ObsidianBg,
                                    labelColor = TitaniumText
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = HairlineBorder,
                                    selectedBorderColor = if (mode == InstrumentMode.BASS_4) PrecisionTeal else CopperAmber
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chip_instrument_${mode.code.lowercase()}")
                            )
                        }
                    }
                    Text(
                        text = uiState.instrumentMode.tuningDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedSteel
                    )
                }
            }

            // Source Selector Segmented Rack
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateSurface)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SourceTabButton(
                    selected = uiState.activeInputTab == AudioSourceType.FILE,
                    icon = Icons.Filled.AudioFile,
                    label = stringResource(R.string.tab_file),
                    onClick = { onSelectTab(AudioSourceType.FILE) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_source_file")
                )
                SourceTabButton(
                    selected = uiState.activeInputTab == AudioSourceType.LINK,
                    icon = Icons.Filled.Link,
                    label = stringResource(R.string.tab_link),
                    onClick = { onSelectTab(AudioSourceType.LINK) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_source_link")
                )
                SourceTabButton(
                    selected = uiState.activeInputTab == AudioSourceType.MIC,
                    icon = Icons.Filled.Mic,
                    label = stringResource(R.string.tab_mic),
                    onClick = { onSelectTab(AudioSourceType.MIC) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_source_mic")
                )
            }

            // Source Input Card
            Card(
                colors = CardDefaults.cardColors(containerColor = RackCard),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (uiState.activeInputTab) {
                        AudioSourceType.FILE -> {
                            Text(
                                text = "Importar pista o riff de referencia",
                                style = MaterialTheme.typography.titleMedium,
                                color = IvoryText
                            )
                            Text(
                                text = stringResource(R.string.supported_formats),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TitaniumText
                            )
                            FilePicker(
                                onFileSelected = onPickAudioUri,
                                selectedFileName = uiState.decodedAudio
                                    ?.takeIf { it.sourceType == AudioSourceType.FILE }
                                    ?.sourceLabel,
                                isLoading = uiState.isLoadingSource,
                                enabled = !uiState.isLoadingSource && !uiState.isAnalyzing
                            )
                        }

                        AudioSourceType.LINK -> {
                            Text(
                                text = "Extraer audio desde enlace web",
                                style = MaterialTheme.typography.titleMedium,
                                color = IvoryText
                            )
                            Text(
                                text = "Admite YouTube, Shorts, TikTok, vistas previas de Spotify y URLs directas (.mp3/.wav/.m4a).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TitaniumText
                            )
                            OutlinedTextField(
                                value = uiState.linkInput,
                                onValueChange = onUpdateLink,
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.link_placeholder),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MutedSteel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CopperAmber,
                                    unfocusedBorderColor = HairlineBorder,
                                    focusedContainerColor = ObsidianBg,
                                    unfocusedContainerColor = ObsidianBg,
                                    focusedTextColor = IvoryText,
                                    unfocusedTextColor = IvoryText
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("link_url_input")
                            )
                            Button(
                                onClick = onExtractLink,
                                enabled = !uiState.isLoadingSource && !uiState.isAnalyzing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CopperAmber,
                                    contentColor = ObsidianBg
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("extract_link_button")
                            ) {
                                if (uiState.isLoadingSource) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = ObsidianBg
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Extrayendo audio...", style = MaterialTheme.typography.labelLarge)
                                } else {
                                    Icon(Icons.Filled.Link, contentDescription = "Extraer")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.fetch_link), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        AudioSourceType.MIC -> {
                            Text(
                                text = "Captura directa por Micrófono o Interfaz USB",
                                style = MaterialTheme.typography.titleMedium,
                                color = IvoryText
                            )
                            Text(
                                text = "Graba tu amplificador, altavoz o salida USB de guitarra a 48 kHz sin compresión.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TitaniumText
                            )
                            LiveVuMeterBar(levelNorm = uiState.recordingLevelNorm)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (uiState.isRecording) "GRABANDO · 48 kHz MONO" else "LISTO PARA CAPTURAR",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (uiState.isRecording) ClipCrimson else TitaniumText
                                )
                                Text(
                                    text = "${String.format("%.1f", uiState.recordingDurationSec)}s / 30.0s",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = IvoryText
                                )
                            }
                            Button(
                                onClick = onToggleRecording,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.isRecording) ClipCrimson else PrecisionTeal,
                                    contentColor = ObsidianBg
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("record_audio_button")
                            ) {
                                Icon(
                                    imageVector = if (uiState.isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = if (uiState.isRecording) "Detener" else "Grabar"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        if (uiState.isRecording) R.string.stop_recording else R.string.start_recording
                                    ),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    if (uiState.isLoadingSource && uiState.activeInputTab == AudioSourceType.FILE) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = CopperAmber
                            )
                            Text(
                                text = "Decodificando PCM a 48 kHz...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TitaniumText
                            )
                        }
                    }

                    uiState.sourceError?.let { errorMsg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(ClipCrimson.copy(alpha = 0.14f))
                                .border(1.dp, ClipCrimson.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Aviso",
                                tint = ClipCrimson,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = errorMsg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = IvoryText
                            )
                        }
                    }
                }
            }

            // Loaded Audio Preview & Interactive Trimmer
            AnimatedVisibility(visible = uiState.decodedAudio != null) {
                uiState.decodedAudio?.let { audio ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = RackCard),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, HairlineBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = audio.sourceLabel,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = IvoryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "48 kHz · ${String.format("%.1f", audio.durationSec)}s · Peak ${String.format("%.1f", audio.peakDbfs)} dBFS · RMS ${String.format("%.1f", audio.rmsDbfs)} dBFS",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = PrecisionTeal
                                        )
                                    }
                                }

                                InteractiveWaveformTrimmer(
                                    peaks = audio.waveformPeaks,
                                    durationSec = audio.durationSec,
                                    trimStartFrac = uiState.trimStartFrac,
                                    trimEndFrac = uiState.trimEndFrac,
                                    onRangeChange = onUpdateTrimRange
                                )
                            }
                        }

                        // Valeton GP-5 SnapTone & IR Configuration Rack
                        Card(
                            colors = CardDefaults.cardColors(containerColor = RackCard),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, HairlineBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Tune,
                                        contentDescription = "Ajustes GP-5",
                                        tint = CopperAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Configuración de Exportación Valeton GP-5",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = IvoryText
                                    )
                                }

                                OutlinedTextField(
                                    value = uiState.patchNameInput,
                                    onValueChange = onUpdatePatchName,
                                    label = { Text("Nombre del SnapTone / Parche") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CopperAmber,
                                        unfocusedBorderColor = HairlineBorder,
                                        focusedContainerColor = ObsidianBg,
                                        unfocusedContainerColor = ObsidianBg,
                                        focusedTextColor = IvoryText,
                                        unfocusedTextColor = IvoryText,
                                        focusedLabelColor = CopperAmber,
                                        unfocusedLabelColor = TitaniumText
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("patch_name_input")
                                )

                                Text(
                                    text = "MODO DE CAPTURA",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TitaniumText
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CaptureMode.entries.forEach { mode ->
                                        val selected = uiState.captureMode == mode
                                        FilterChip(
                                            selected = selected,
                                            onClick = { onSelectCaptureMode(mode) },
                                            label = {
                                                Text(
                                                    text = mode.label,
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = CopperContainer,
                                                selectedLabelColor = CopperAmberBright,
                                                containerColor = ObsidianBg,
                                                labelColor = TitaniumText
                                            ),
                                            border = FilterChipDefaults.filterChipBorder(
                                                enabled = true,
                                                selected = selected,
                                                borderColor = HairlineBorder,
                                                selectedBorderColor = CopperAmber
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("chip_mode_${mode.code.lowercase()}")
                                        )
                                    }
                                }
                                Text(
                                    text = uiState.captureMode.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MutedSteel
                                )

                                Text(
                                    text = "ARQUITECTURA .NAM Y RESOLUCIÓN IR",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TitaniumText
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ModelQuality.entries.forEach { q ->
                                        val selected = uiState.modelQuality == q
                                        FilterChip(
                                            selected = selected,
                                            onClick = { onSelectModelQuality(q) },
                                            label = {
                                                Text(
                                                    text = if (q == ModelQuality.GP5_NANO) "GP-5 Nano" else "Studio Lite",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = PrecisionTealContainer,
                                                selectedLabelColor = PrecisionTeal,
                                                containerColor = ObsidianBg,
                                                labelColor = TitaniumText
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(48000 to "48 kHz", 44100 to "44.1 kHz").forEach { (rate, lbl) ->
                                        val selected = uiState.irSampleRate == rate
                                        FilterChip(
                                            selected = selected,
                                            onClick = { onSelectIrSampleRate(rate) },
                                            label = { Text(lbl, style = MaterialTheme.typography.labelMedium) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = CopperContainer,
                                                selectedLabelColor = CopperAmberBright,
                                                containerColor = ObsidianBg,
                                                labelColor = TitaniumText
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    listOf(1024 to "1024 pts", 2048 to "2048 pts").forEach { (len, lbl) ->
                                        val selected = uiState.irLengthSamples == len
                                        FilterChip(
                                            selected = selected,
                                            onClick = { onSelectIrLength(len) },
                                            label = { Text(lbl, style = MaterialTheme.typography.labelMedium) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = CopperContainer,
                                                selectedLabelColor = CopperAmberBright,
                                                containerColor = ObsidianBg,
                                                labelColor = TitaniumText
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        // Primary Generate Action
                        if (uiState.isAnalyzing) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = RackElevated),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, CopperAmber.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = uiState.analysisStatus,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = IvoryText
                                        )
                                        Text(
                                            text = "${(uiState.analysisProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = CopperAmberBright
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { uiState.analysisProgress },
                                        color = CopperAmber,
                                        trackColor = ObsidianBg,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = onGenerateSnapTone,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CopperAmber,
                                    contentColor = ObsidianBg
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("generate_snaptone_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = "Generar SnapTone"
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.generate_snaptone),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.decodedAudio == null && !uiState.isLoadingSource && !uiState.isRecording) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, HairlineBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Consejo de captura",
                            tint = PrecisionTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Tip: Aísla un riff limpio de guitarra o línea de bajo 4C (E-A-D-G) para un SnapTone más fiel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TitaniumText
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SourceTabButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) RackElevated else SlateSurface)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) CopperAmber.copy(alpha = 0.6f) else SlateSurface,
                shape = RoundedCornerShape(9.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) CopperAmberBright else TitaniumText,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) IvoryText else TitaniumText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
