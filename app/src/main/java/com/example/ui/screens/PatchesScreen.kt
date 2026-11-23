package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.example.data.TonePatchEntity
import com.example.dsp.CaptureMode
import com.example.dsp.ExportPatchRequest
import com.example.dsp.InstrumentMode
import com.example.dsp.PackExporter
import com.example.dsp.ValetonSuiteExporter
import com.example.ui.ToneForgeUiState
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
import com.example.ui.theme.TitaniumText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PatchesScreen(
    uiState: ToneForgeUiState,
    patches: List<TonePatchEntity>,
    onSearchChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onInspectPatch: (TonePatchEntity) -> Unit,
    onToggleFavorite: (TonePatchEntity) -> Unit,
    onUpdatePatchMeta: (TonePatchEntity, String, String, String, String) -> Unit,
    onDeletePatch: (TonePatchEntity) -> Unit,
    onToggleMultiSelect: (Long) -> Unit,
    onClearMultiSelect: () -> Unit,
    onSelectValetonFolder: () -> Unit,
    onExportRequestsToValetonSuite: (List<ExportPatchRequest>) -> Unit,
    onExportBatchZipWithNames: (Map<Long, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingPatch by remember { mutableStateOf<TonePatchEntity?>(null) }
    var deletingPatch by remember { mutableStateOf<TonePatchEntity?>(null) }
    var showGp5Guide by remember { mutableStateOf(false) }
    var patchesToExport by remember { mutableStateOf<List<TonePatchEntity>?>(null) }

    val categories = listOf("Todos", "Favoritos", "Guitarra", "Bajo 4C", "Clean", "Crunch", "High Gain", "Lead")
    val filtered = remember(patches, uiState.searchQuery, uiState.selectedCategoryFilter) {
        patches.filter { p ->
            val matchesQuery = uiState.searchQuery.isBlank() ||
                p.name.contains(uiState.searchQuery, ignoreCase = true) ||
                p.gp5Slot.contains(uiState.searchQuery, ignoreCase = true) ||
                p.notes.contains(uiState.searchQuery, ignoreCase = true)
            val matchesCat = when (uiState.selectedCategoryFilter) {
                "Todos" -> true
                "Favoritos" -> p.isFavorite
                "Guitarra" -> p.instrumentType != InstrumentMode.BASS_4.code
                "Bajo 4C" -> p.instrumentType == InstrumentMode.BASS_4.code
                else -> p.category.equals(uiState.selectedCategoryFilter, ignoreCase = true)
            }
            matchesQuery && matchesCat
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header + GP-5 Guide button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gestor de Parches GP-5",
                        style = MaterialTheme.typography.headlineMedium,
                        color = IvoryText
                    )
                    Text(
                        text = "${patches.size} tonos locales listos para Valeton Suite",
                        style = MaterialTheme.typography.labelMedium,
                        color = TitaniumText
                    )
                }
                OutlinedButton(
                    onClick = { showGp5Guide = true },
                    border = BorderStroke(1.dp, PrecisionTeal.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("open_gp5_guide_button")
                ) {
                    Icon(Icons.Filled.HelpOutline, contentDescription = "Guía GP-5", tint = PrecisionTeal, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Guía GP-5", color = PrecisionTeal, style = MaterialTheme.typography.labelLarge)
                }
            }

            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Buscar por nombre, slot (P01) o notas...", color = MutedSteel) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Buscar", tint = TitaniumText) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Limpiar búsqueda", tint = TitaniumText)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CopperAmber,
                    unfocusedBorderColor = HairlineBorder,
                    focusedContainerColor = RackCard,
                    unfocusedContainerColor = RackCard,
                    focusedTextColor = IvoryText,
                    unfocusedTextColor = IvoryText
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_patches_input")
            )

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val selected = uiState.selectedCategoryFilter == cat
                    FilterChip(
                        selected = selected,
                        onClick = { onCategoryChange(cat) },
                        label = { Text(cat, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CopperContainer,
                            selectedLabelColor = CopperAmberBright,
                            containerColor = RackCard,
                            labelColor = TitaniumText
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = HairlineBorder,
                            selectedBorderColor = CopperAmber
                        )
                    )
                }
            }

            // Multi-select Batch Export Bar
            if (uiState.multiSelectIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrecisionTealContainer)
                        .border(1.dp, PrecisionTeal, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${uiState.multiSelectIds.size} seleccionados",
                        style = MaterialTheme.typography.labelLarge,
                        color = IvoryText
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onClearMultiSelect) {
                            Text("Cancelar", color = TitaniumText)
                        }
                        Button(
                            onClick = {
                                patchesToExport = patches.filter { it.id in uiState.multiSelectIds }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrecisionTeal,
                                contentColor = ObsidianBg
                            ),
                            modifier = Modifier.testTag("batch_export_zip_button")
                        ) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "Exportar seleccionados", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Exportar / Suite", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 360.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LibraryMusic,
                            contentDescription = "Sin parches",
                            tint = MutedSteel,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.empty_patches_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = IvoryText
                        )
                        Text(
                            text = stringResource(R.string.empty_patches_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TitaniumText
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { patch ->
                        val isSelected = patch.id in uiState.multiSelectIds
                        PatchRackCard(
                            patch = patch,
                            isSelected = isSelected,
                            onInspect = { onInspectPatch(patch) },
                            onExportDirect = { patchesToExport = listOf(patch) },
                            onToggleFavorite = { onToggleFavorite(patch) },
                            onToggleSelect = { onToggleMultiSelect(patch.id) },
                            onEdit = { editingPatch = patch },
                            onDelete = { deletingPatch = patch }
                        )
                    }
                }
            }
        }
    }

    editingPatch?.let { target ->
        EditPatchDialog(
            patch = target,
            onDismiss = { editingPatch = null },
            onSave = { name, slot, notes, cat ->
                onUpdatePatchMeta(target, name, slot, notes, cat)
                editingPatch = null
            }
        )
    }

    deletingPatch?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingPatch = null },
            containerColor = RackElevated,
            title = { Text("Eliminar parche", color = IvoryText) },
            text = {
                Text(
                    "Se borrarán '${target.name}' y sus archivos locales .nam e IR .wav.",
                    color = TitaniumText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePatch(target)
                        deletingPatch = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClipCrimson, contentColor = IvoryText)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPatch = null }) {
                    Text("Cancelar", color = TitaniumText)
                }
            }
        )
    }

    if (showGp5Guide) {
        AlertDialog(
            onDismissRequest = { showGp5Guide = false },
            containerColor = RackElevated,
            title = { Text(stringResource(R.string.gp5_guide_title), color = IvoryText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "1. Descarga el archivo .nam (o el Pack .zip completo) desde Análisis o Parches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = IvoryText
                    )
                    Text(
                        "2. Abre la app oficial Valeton Suite (en móvil por Bluetooth o en PC/Mac por USB-C).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = IvoryText
                    )
                    Text(
                        "3. En el bloque AMP -> SnapTone, importa el archivo .nam; Valeton Suite lo optimiza automáticamente para el DSP de tu GP-5.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = IvoryText
                    )
                    Text(
                        "4. Si elegiste modo 'Amp + IR Separados', carga el archivo _IR.wav en el bloque CAB -> User IR. Si elegiste 'Full Rig', deja CAB apagado.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TitaniumText
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showGp5Guide = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CopperAmber, contentColor = ObsidianBg)
                ) {
                    Text("Entendido")
                }
            }
        )
    }

    patchesToExport?.takeIf { it.isNotEmpty() }?.let { exportList ->
        ValetonExportDialog(
            patches = exportList,
            folderDisplayName = uiState.valetonFolderDisplayName,
            nextEmptySlotPreview = uiState.nextEmptySlotPreview,
            onDismiss = { patchesToExport = null },
            onSelectFolder = onSelectValetonFolder,
            onConfirmValetonExport = { requests ->
                onExportRequestsToValetonSuite(requests)
                patchesToExport = null
            },
            onConfirmZipExport = { customNames ->
                onExportBatchZipWithNames(customNames)
                patchesToExport = null
            }
        )
    }
}

@Composable
private fun PatchRackCard(
    patch: TonePatchEntity,
    isSelected: Boolean,
    onInspect: () -> Unit,
    onExportDirect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) RackElevated else RackCard
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) PrecisionTeal else HairlineBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect)
            .testTag("patch_card_${patch.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleSelect,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = "Seleccionar parche",
                        tint = if (isSelected) PrecisionTeal else MutedSteel
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(CopperContainer)
                                .border(1.dp, CopperAmber.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = patch.gp5Slot,
                                style = MaterialTheme.typography.labelMedium,
                                color = CopperAmberBright
                            )
                        }
                        Text(
                            text = patch.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = IvoryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    val instrTag = if (patch.instrumentType == InstrumentMode.BASS_4.code) "Bajo 4C" else "Guitarra"
                    val formattedDate = remember(patch.createdAt) {
                        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale("es", "ES"))
                            .format(Date(patch.createdAt))
                    }
                    val fileName = remember(patch.namFilePath) {
                        File(patch.namFilePath).name.ifBlank { patch.namFilePath }
                    }
                    Text(
                        text = "$instrTag · ${patch.category} · ${if (patch.captureMode == CaptureMode.FULL_RIG.code) "Full Rig" else "Amp+IR"} · ${patch.sampleRate / 1000f}kHz",
                        style = MaterialTheme.typography.labelSmall,
                        color = PrecisionTeal
                    )
                    Text(
                        text = "$formattedDate · $fileName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSteel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (patch.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorito",
                        tint = if (patch.isFavorite) CopperAmber else TitaniumText
                    )
                }
            }

            if (patch.notes.isNotBlank()) {
                Text(
                    text = patch.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TitaniumText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onExportDirect) {
                    Text("Valeton Suite", color = PrecisionTeal)
                }
                TextButton(onClick = onInspect) {
                    Text("Abrir", color = CopperAmberBright)
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Editar parche", tint = TitaniumText)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Eliminar parche", tint = ClipCrimson)
                }
            }
        }
    }
}

@Composable
private fun EditPatchDialog(
    patch: TonePatchEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(patch.name) }
    var slot by remember { mutableStateOf(patch.gp5Slot) }
    var notes by remember { mutableStateOf(patch.notes) }
    var category by remember { mutableStateOf(patch.category) }
    val cats = listOf("Clean", "Crunch", "High Gain", "Lead", "Bass")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RackElevated,
        title = { Text("Editar parche GP-5", color = IvoryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CopperAmber,
                        unfocusedBorderColor = HairlineBorder,
                        focusedTextColor = IvoryText,
                        unfocusedTextColor = IvoryText
                    )
                )
                OutlinedTextField(
                    value = slot,
                    onValueChange = { slot = it.take(8) },
                    label = { Text("Slot Valeton GP-5 (ej. P01)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CopperAmber,
                        unfocusedBorderColor = HairlineBorder,
                        focusedTextColor = IvoryText,
                        unfocusedTextColor = IvoryText
                    )
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    cats.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notas / Guitarra / Pastillas") },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CopperAmber,
                        unfocusedBorderColor = HairlineBorder,
                        focusedTextColor = IvoryText,
                        unfocusedTextColor = IvoryText
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, slot, notes, category) },
                colors = ButtonDefaults.buttonColors(containerColor = CopperAmber, contentColor = ObsidianBg)
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TitaniumText)
            }
        }
    )
}

@Composable
private fun ValetonExportDialog(
    patches: List<TonePatchEntity>,
    folderDisplayName: String?,
    nextEmptySlotPreview: String,
    onDismiss: () -> Unit,
    onSelectFolder: () -> Unit,
    onConfirmValetonExport: (List<ExportPatchRequest>) -> Unit,
    onConfirmZipExport: (Map<Long, String>) -> Unit
) {
    val editableNames = remember(patches) {
        mutableStateMapOf<Long, String>().apply {
            patches.forEach { put(it.id, it.name) }
        }
    }
    val startSlotNum = remember(nextEmptySlotPreview) {
        nextEmptySlotPreview.removePrefix("P").toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RackElevated,
        title = {
            Text(
                text = if (patches.size == 1) "Exportar parche con nombre" else "Exportar ${patches.size} parches",
                color = IvoryText
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (patches.size > 2) 320.dp else 240.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ObsidianBg)
                        .border(1.dp, PrecisionTeal.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folderDisplayName ?: "Carpeta Valeton Suite no elegida",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (folderDisplayName != null) PrecisionTeal else CopperAmberBright,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Presets vacíos desde $nextEmptySlotPreview en orden",
                            style = MaterialTheme.typography.labelSmall,
                            color = TitaniumText
                        )
                    }
                    TextButton(onClick = onSelectFolder) {
                        Text(if (folderDisplayName != null) "Cambiar" else "Elegir", color = CopperAmberBright)
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(patches.size) { index ->
                        val patch = patches[index]
                        val slotLabel = ValetonSuiteExporter.formatSlotCode(startSlotNum + index)
                        val currentName = editableNames[patch.id] ?: patch.name
                        val previewSafe = PackExporter.sanitizeReadableFileName(currentName.ifBlank { patch.name })
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = currentName,
                                onValueChange = { editableNames[patch.id] = it },
                                label = { Text("Slot $slotLabel · Artista / Canción / Sonido") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CopperAmber,
                                    unfocusedBorderColor = HairlineBorder,
                                    focusedTextColor = IvoryText,
                                    unfocusedTextColor = IvoryText
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dialog_export_name_${patch.id}")
                            )
                            Text(
                                text = "SnapTone/$previewSafe.nam · Presets/${slotLabel}_$previewSafe.json",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSteel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onConfirmZipExport(editableNames.toMap())
                    },
                    border = BorderStroke(1.dp, HairlineBorder)
                ) {
                    Icon(Icons.Filled.FolderZip, contentDescription = "ZIP", tint = IvoryText, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ZIP", color = IvoryText)
                }
                Button(
                    onClick = {
                        if (folderDisplayName == null) {
                            onSelectFolder()
                        } else {
                            val reqs = patches.map { p ->
                                ExportPatchRequest(
                                    patch = p,
                                    exportFileName = editableNames[p.id] ?: p.name
                                )
                            }
                            onConfirmValetonExport(reqs)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrecisionTeal,
                        contentColor = ObsidianBg
                    ),
                    modifier = Modifier.testTag("confirm_valeton_export_button")
                ) {
                    Text(if (folderDisplayName == null) "Elegir carpeta" else "A Valeton Suite")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TitaniumText)
            }
        }
    )
}
