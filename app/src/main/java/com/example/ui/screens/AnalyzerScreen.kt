package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.dsp.AudioOutputFormat
import com.example.dsp.AuditionMode
import com.example.dsp.CaptureMode
import com.example.dsp.Gp5ModuleSetting
import com.example.dsp.InstrumentMode
import com.example.dsp.PackExporter
import com.example.ui.ToneForgeUiState
import com.example.ui.components.SpectralResponseChart
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
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TitaniumText

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyzerScreen(
    uiState: ToneForgeUiState,
    onToggleAudition: (AuditionMode) -> Unit,
    onUpdateExportFileName: (String) -> Unit,
    onDownloadNam: () -> Unit,
    onDownloadIr: () -> Unit,
    onDownloadZipPack: () -> Unit,
    onShareNam: () -> Unit,
    onShareIr: () -> Unit,
    onSelectValetonFolder: () -> Unit,
    onExportToValetonSuite: () -> Unit,
    onLaunchValetonSuite: () -> Unit,
    onConvertIrFormat: (AudioOutputFormat) -> Unit,
    onGoToCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val patch = uiState.selectedPatch
    val metrics = uiState.selectedMetrics
    val curve = uiState.selectedCurve
    val chain = uiState.selectedGp5Chain

    if (patch == null || metrics == null || curve == null || chain == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = "Sin análisis activo",
                    tint = CopperAmber,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "Ningún SnapTone seleccionado",
                    style = MaterialTheme.typography.titleLarge,
                    color = IvoryText
                )
                Text(
                    text = "Genera un tono en Capturar o elige uno de tus parches guardados para inspeccionar su curva espectral, escucharlo en vivo y descargarlo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TitaniumText
                )
                Button(
                    onClick = onGoToCapture,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CopperAmber,
                        contentColor = ObsidianBg
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("empty_go_capture_button")
                ) {
                    Text("Ir a Capturar", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        return
    }

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
            // Patch Title & Specs Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = RackCard),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = patch.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = IvoryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(CopperContainer)
                                .border(1.dp, CopperAmber.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "GP-5 · ${patch.gp5Slot}",
                                style = MaterialTheme.typography.labelMedium,
                                color = CopperAmberBright
                            )
                        }
                    }
                    val isBass = patch.instrumentType == InstrumentMode.BASS_4.code
                    Text(
                        text = "${if (isBass) "Bajo 4 Cuerdas (E-A-D-G)" else "Guitarra Eléctrica"} · ${metrics.ampArchetype} · ${metrics.cabinetCharacter}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TitaniumText
                    )
                    Text(
                        text = "${if (patch.captureMode == CaptureMode.FULL_RIG.code) "Full Rig (.nam)" else "Amp (.nam) + IR (.wav)"} · f0 ${metrics.detectedFundamentalHz.toInt()} Hz · ${patch.sampleRate / 1000f} kHz · ${patch.irSamples} pts",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrecisionTeal
                    )
                }
            }

            // Real-Time A/B Audition Rack
            Card(
                colors = CardDefaults.cardColors(containerColor = RackCard),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AUDICIÓN EN VIVO A/B (MOTOR WAVENET)",
                            style = MaterialTheme.typography.labelMedium,
                            color = TitaniumText
                        )
                        if (uiState.activeAuditionMode != null) {
                            Text(
                                text = "REPRODUCIENDO",
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalEmerald
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val diLabel = if (patch.instrumentType == InstrumentMode.BASS_4.code) {
                            "DI Bajo 4C"
                        } else {
                            stringResource(R.string.audition_di)
                        }
                        AuditionButton(
                            label = diLabel,
                            isPlaying = uiState.activeAuditionMode == AuditionMode.DI_CLEAN,
                            onClick = { onToggleAudition(AuditionMode.DI_CLEAN) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("audition_di_button")
                        )
                        AuditionButton(
                            label = stringResource(R.string.audition_snaptone),
                            isPlaying = uiState.activeAuditionMode == AuditionMode.SNAPTONE_NAM,
                            highlightAccent = true,
                            onClick = { onToggleAudition(AuditionMode.SNAPTONE_NAM) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("audition_snaptone_button")
                        )
                        AuditionButton(
                            label = stringResource(R.string.audition_ref),
                            isPlaying = uiState.activeAuditionMode == AuditionMode.REFERENCE,
                            onClick = { onToggleAudition(AuditionMode.REFERENCE) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("audition_ref_button")
                        )
                    }
                }
            }

            // Dual-curve Spectral Response Graph
            SpectralResponseChart(curve = curve)

            // Harmonic & Dynamic Telemetry Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryTile(
                    title = "GANANCIA",
                    value = "${metrics.gainLevel}/100",
                    sub = metrics.detectedCategory,
                    modifier = Modifier.weight(1f)
                )
                TelemetryTile(
                    title = "THD / SAT.",
                    value = "${metrics.thdPercent}%",
                    sub = if (metrics.evenOddHarmonicRatio >= 1.0f) "Cálido (Pares)" else "Agresivo (Impares)",
                    modifier = Modifier.weight(1f)
                )
                TelemetryTile(
                    title = "DINÁMICA / SAG",
                    value = "${metrics.compressionSag}%",
                    sub = "${metrics.spectralCentroidHz.toInt()} Hz foco",
                    modifier = Modifier.weight(1f)
                )
            }

            // Export / Download Actions for Valeton GP-5
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
                    Text(
                        text = "DESCARGAR Y EXPORTAR PARA VALETON GP-5",
                        style = MaterialTheme.typography.labelMedium,
                        color = CopperAmberBright
                    )

                    val previewBaseName = remember(uiState.exportFileNameInput, patch.name) {
                        PackExporter.sanitizeReadableFileName(
                            uiState.exportFileNameInput.ifBlank { patch.name }
                        )
                    }

                    OutlinedTextField(
                        value = uiState.exportFileNameInput,
                        onValueChange = onUpdateExportFileName,
                        label = { Text("Nombre de archivo (Artista / Canción / Sonido)") },
                        placeholder = { Text("Ej. Metallica - Master Of Puppets", color = MutedSteel) },
                        supportingText = {
                            Text(
                                text = "Archivo: $previewBaseName.nam · ${previewBaseName}_IR.wav",
                                style = MaterialTheme.typography.labelSmall,
                                color = PrecisionTeal,
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
                            unfocusedTextColor = IvoryText,
                            focusedLabelColor = CopperAmberBright,
                            unfocusedLabelColor = TitaniumText
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_filename_input")
                    )

                    // Direct Valeton Suite folder export section
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianBg)
                            .border(1.dp, PrecisionTeal.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "EXPORTAR DIRECTO A VALETON SUITE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PrecisionTeal
                                )
                                Text(
                                    text = if (uiState.valetonFolderDisplayName != null) {
                                        "Carpeta: ${uiState.valetonFolderDisplayName} · Próx. preset vacío: ${uiState.nextEmptySlotPreview}"
                                    } else {
                                        "Vincula la carpeta local de Valeton Suite para guardar en SnapTone / UserIR / Presets (${uiState.nextEmptySlotPreview})"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TitaniumText
                                )
                            }
                            if (uiState.valetonFolderDisplayName != null) {
                                TextButton(
                                    onClick = onSelectValetonFolder,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text("Cambiar", color = CopperAmberBright)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (uiState.valetonFolderDisplayName != null) {
                                        onExportToValetonSuite()
                                    } else {
                                        onSelectValetonFolder()
                                    }
                                },
                                enabled = !uiState.isExportingToValeton,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrecisionTeal,
                                    contentColor = ObsidianBg
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("export_valeton_suite_button")
                            ) {
                                if (uiState.isExportingToValeton) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = ObsidianBg
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Exportando...", style = MaterialTheme.typography.labelLarge)
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.CreateNewFolder,
                                        contentDescription = "Exportar a Valeton Suite",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (uiState.valetonFolderDisplayName != null) {
                                            "Enviar a Valeton Suite (${uiState.nextEmptySlotPreview})"
                                        } else {
                                            "Elegir carpeta Valeton Suite"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (uiState.valetonInstalledPackage != null) {
                                OutlinedButton(
                                    onClick = onLaunchValetonSuite,
                                    border = BorderStroke(1.dp, CopperAmber.copy(alpha = 0.7f)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("open_valeton_suite_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.OpenInNew,
                                        contentDescription = "Abrir Valeton Suite",
                                        tint = CopperAmberBright,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Abrir", color = CopperAmberBright, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = HairlineBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onDownloadNam,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CopperAmber,
                                contentColor = ObsidianBg
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("download_nam_button")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "Descargar NAM", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.export_nam), style = MaterialTheme.typography.labelLarge)
                        }
                        IconButton(
                            onClick = onShareNam,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianBg)
                                .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
                                .testTag("share_nam_button")
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Compartir .nam", tint = IvoryText)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDownloadIr,
                            border = BorderStroke(1.dp, PrecisionTeal),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("download_ir_button")
                        ) {
                            Icon(Icons.Outlined.AudioFile, contentDescription = "Descargar IR", tint = PrecisionTeal, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.export_ir), color = PrecisionTeal, style = MaterialTheme.typography.labelLarge)
                        }
                        IconButton(
                            onClick = onShareIr,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianBg)
                                .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
                                .testTag("share_ir_button")
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Compartir IR .wav", tint = IvoryText)
                        }
                    }

                    OutlinedButton(
                        onClick = onDownloadZipPack,
                        border = BorderStroke(1.dp, HairlineBorder),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("download_zip_pack_button")
                    ) {
                        Icon(Icons.Filled.FolderZip, contentDescription = "Pack ZIP", tint = IvoryText, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${stringResource(R.string.export_pack)} (.nam + IR .wav + Guía)",
                            color = IvoryText,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            // Local FFmpeg Format Converter Card
            var selectedExportFormat by remember { mutableStateOf(AudioOutputFormat.FLAC) }
            val convertibleFormats = remember {
                listOf(
                    AudioOutputFormat.FLAC to "FLAC",
                    AudioOutputFormat.MP3 to "MP3",
                    AudioOutputFormat.M4A_AAC to "M4A",
                    AudioOutputFormat.OGG_VORBIS to "OGG",
                    AudioOutputFormat.OPUS to "OPUS",
                    AudioOutputFormat.WAV_16BIT to "WAV 16b"
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = RackCard),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "CONVERSIÓN LOCAL DE AUDIO (FFMPEG)",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrecisionTeal
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        convertibleFormats.forEach { (fmt, label) ->
                            val isSelected = selectedExportFormat == fmt
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedExportFormat = fmt },
                                label = {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PrecisionTealContainer,
                                    selectedLabelColor = PrecisionTeal,
                                    containerColor = ObsidianBg,
                                    labelColor = TitaniumText
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = HairlineBorder,
                                    selectedBorderColor = PrecisionTeal
                                ),
                                modifier = Modifier.testTag("chip_ffmpeg_${fmt.extension}")
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { onConvertIrFormat(selectedExportFormat) },
                        enabled = !uiState.isConvertingFormat,
                        border = BorderStroke(1.dp, PrecisionTeal.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("convert_ffmpeg_export_button")
                    ) {
                        if (uiState.isConvertingFormat) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = PrecisionTeal
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Convirtiendo en el dispositivo...",
                                color = PrecisionTeal,
                                style = MaterialTheme.typography.labelLarge
                            )
                        } else {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = "Convertir formato",
                                tint = PrecisionTeal,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Convertir IR a .${selectedExportFormat.extension} y guardar",
                                color = PrecisionTeal,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // Valeton GP-5 Signal Chain Recipe
            Card(
                colors = CardDefaults.cardColors(containerColor = RackCard),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Cadena GP-5", tint = CopperAmber, modifier = Modifier.size(18.dp))
                        Text(
                            text = "RECETA CADENA VALETON GP-5",
                            style = MaterialTheme.typography.labelMedium,
                            color = TitaniumText
                        )
                    }
                    chain.modules.forEach { mod ->
                        Gp5ModuleCard(mod = mod)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AuditionButton(
    label: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightAccent: Boolean = false
) {
    val bg = when {
        isPlaying -> SignalEmerald
        highlightAccent -> CopperContainer
        else -> ObsidianBg
    }
    val border = when {
        isPlaying -> SignalEmerald
        highlightAccent -> CopperAmber
        else -> HairlineBorder
    }
    val fg = when {
        isPlaying -> ObsidianBg
        highlightAccent -> CopperAmberBright
        else -> IvoryText
    }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        border = BorderStroke(1.dp, border),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .height(48.dp)
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = label,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TelemetryTile(
    title: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(RackCard)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MutedSteel,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = IvoryText,
            maxLines = 1
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = CopperAmber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Gp5ModuleCard(mod: Gp5ModuleSetting) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianBg)
            .border(
                width = 1.dp,
                color = if (mod.enabled) HairlineBorder else HairlineBorder.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (mod.enabled) CopperContainer else RackCard)
                .border(
                    1.dp,
                    if (mod.enabled) CopperAmber else MutedSteel.copy(alpha = 0.4f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = mod.moduleCode,
                style = MaterialTheme.typography.labelMedium,
                color = if (mod.enabled) CopperAmberBright else MutedSteel
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${mod.moduleName} · ${mod.modelOrType}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (mod.enabled) IvoryText else MutedSteel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (mod.enabled) "ON" else "OFF",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (mod.enabled) SignalEmerald else MutedSteel
                )
            }
            if (mod.enabled && mod.params.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mod.params.forEach { (k, v) ->
                        Text(
                            text = "$k: $v",
                            style = MaterialTheme.typography.labelMedium,
                            color = TitaniumText
                        )
                    }
                }
            }
        }
    }
}
