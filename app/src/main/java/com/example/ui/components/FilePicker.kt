package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R

data class PickedAudioMetadata(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val mimeType: String?
)

fun resolvePickedAudioMetadata(context: Context, uri: Uri): PickedAudioMetadata {
    var displayName: String? = null
    var sizeBytes: Long? = null
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameCol >= 0) {
                    displayName = cursor.getString(nameCol)
                }
                val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) {
                    sizeBytes = cursor.getLong(sizeCol)
                }
            }
        }
    }
    val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "audio_local"
    val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    return PickedAudioMetadata(
        uri = uri,
        displayName = displayName?.takeIf { it.isNotBlank() } ?: fallbackName,
        sizeBytes = sizeBytes,
        mimeType = mimeType
    )
}

fun formatFileSizeLabel(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.2f MB", mb)
}

/**
 * Reusable zero-permission FilePicker Compose component using
 * [ActivityResultContracts.GetContent] to pick local audio (or video for local FFmpeg extraction)
 * from device storage.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilePicker(
    onFileSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    selectedFileName: String? = null,
    selectedFileSizeBytes: Long? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    primaryMimeType: String = "audio/*",
    allowVideoExtraction: Boolean = true
) {
    val context = LocalContext.current
    var lastPickedMeta by remember { mutableStateOf<PickedAudioMetadata?>(null) }

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lastPickedMeta = resolvePickedAudioMetadata(context, uri)
            onFileSelected(uri)
        }
    }

    val effectiveName = selectedFileName?.takeIf { it.isNotBlank() } ?: lastPickedMeta?.displayName
    val effectiveSize = selectedFileSizeBytes ?: lastPickedMeta?.sizeBytes
    val colorScheme = MaterialTheme.colorScheme
    val supportedTags = remember { listOf("WAV", "MP3", "M4A", "FLAC", "OGG", "OPUS") }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (effectiveName != null) {
                colorScheme.secondary.copy(alpha = 0.55f)
            } else {
                colorScheme.outline
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("file_picker_card")
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colorScheme.surfaceContainerHigh,
                            colorScheme.surfaceContainer
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colorScheme.primaryContainer)
                            .border(
                                width = 1.dp,
                                color = colorScheme.primary.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = "Selector de audio local",
                            tint = colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Almacenamiento local",
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = "Selector nativo sin permisos invasivos",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                supportedTags.forEach { ext ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colorScheme.background)
                            .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = ext,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = effectiveName != null) {
                effectiveName?.let { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colorScheme.secondaryContainer)
                            .border(
                                1.dp,
                                colorScheme.secondary.copy(alpha = 0.45f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("file_picker_selected_badge"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Archivo cargado",
                            tint = colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            effectiveSize?.let { size ->
                                Text(
                                    text = "${formatFileSizeLabel(size)} · Listo para análisis DSP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { getContentLauncher.launch(primaryMimeType) },
                enabled = enabled && !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .height(48.dp)
                    .testTag("pick_audio_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Procesando archivo local...",
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = "Seleccionar archivo de audio",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (effectiveName == null) {
                            stringResource(R.string.pick_audio_file)
                        } else {
                            "Cambiar archivo de audio"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (allowVideoExtraction) {
                OutlinedButton(
                    onClick = { getContentLauncher.launch("video/*") },
                    enabled = enabled && !isLoading,
                    border = BorderStroke(1.dp, colorScheme.outline),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .height(48.dp)
                        .testTag("pick_video_audio_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VideoFile,
                        contentDescription = "Extraer audio de vídeo local",
                        tint = colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Extraer audio desde vídeo (MP4/MKV)",
                        color = colorScheme.secondary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
