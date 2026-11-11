package com.example.myapplication.presentation.send

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.domain.model.SelectedFile
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PickerCategory(
    val label: String,
    val mime: String,
    val icon: ImageVector,
    val isDocumentPicker: Boolean = false
)

private val pickerCategories = listOf(
    PickerCategory("All Files", "*/*", Icons.Default.FolderOpen),
    PickerCategory("Images", "image/*", Icons.Default.Image),
    PickerCategory("Videos", "video/*", Icons.Default.VideoFile),
    PickerCategory("Audio", "audio/*", Icons.Default.AudioFile),
    PickerCategory("Documents", "application/*", Icons.AutoMirrored.Filled.Article, isDocumentPicker = true)
)

private val documentMimeTypes = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain",
    "text/csv"
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SendScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSenderPairing: () -> Unit,
    onNavigateToNearby: () -> Unit,
    viewModel: SendViewModel = viewModel()
) {
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val context = LocalContext.current
    var activeCategoryIndex by remember { mutableStateOf(0) }

    val contentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) viewModel.addFiles(uris) }

    val docsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.addFiles(uris) }

    fun launchCategoryPicker(index: Int) {
        activeCategoryIndex = index
        val category = pickerCategories[index]
        if (category.isDocumentPicker) {
            docsLauncher.launch(documentMimeTypes)
        } else {
            contentLauncher.launch(category.mime)
        }
    }

    Scaffold(
        topBar = {
            TwTopBar(
                title = "Send Files",
                subtitle = if (selectedFiles.isEmpty()) "Choose files to share"
                           else "${selectedFiles.size} file${if (selectedFiles.size > 1) "s" else ""} queued",
                onBack = onNavigateBack,
                actions = {
                    if (selectedFiles.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearFiles() }) {
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = selectedFiles.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                val totalSize = selectedFiles.sumOf { it.size }
                val formattedTotal = Formatter.formatFileSize(context, totalSize)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .padding(TwSpacing.Default)
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ShapeSmall)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${selectedFiles.size} selected",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = formattedTotal,
                                style = MonoMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TwOutlinedButton(
                                text = "Nearby Wi-Fi",
                                onClick = onNavigateToNearby,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                leadingIcon = Icons.Default.Wifi
                            )
                            TwPrimaryButton(
                                text = "Remote QR",
                                onClick = onNavigateToSenderPairing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                leadingIcon = Icons.Default.QrCode2
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Category Filter Bar ───────────────────────────────────────────
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(horizontal = TwSpacing.Default, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(TwSpacing.Small)
            ) {
                items(pickerCategories.size) { index ->
                    val cat = pickerCategories[index]
                    val selected = activeCategoryIndex == index
                    FilterChip(
                        selected = selected,
                        onClick = { launchCategoryPicker(index) },
                        label = { Text(cat.label, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = {
                            Icon(cat.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        shape = ShapePill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                    )
                }
            }

            TwDivider()

            // ── Queue or Empty State ──────────────────────────────────────────
            if (selectedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(TwSpacing.Default),
                    contentAlignment = Alignment.Center
                ) {
                    TwEmptyState(
                        title = "Select Files to Send",
                        subtitle = "Choose a category chip above or add any file from your device. Long-press inside the picker to multi-select.",
                        icon = Icons.Default.FileUpload,
                        action = "Browse Files" to { launchCategoryPicker(0) }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = TwSpacing.Default,
                        end = TwSpacing.Default,
                        top = TwSpacing.Default,
                        bottom = 170.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "add_more_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TwSectionLabel(
                                text = "Transfer Queue",
                                modifier = Modifier.weight(1f),
                                action = "+ Add more" to { launchCategoryPicker(activeCategoryIndex) }
                            )
                        }
                    }

                    items(selectedFiles, key = { it.uri.toString() }) { file ->
                        SendFileCard(
                            file = file,
                            context = context,
                            onRemove = { viewModel.removeFile(file) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SendFileCard(
    file: SelectedFile,
    context: Context,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedSize = remember(file.size) { Formatter.formatFileSize(context, file.size) }
    val isImage = file.mimeType.startsWith("image/", ignoreCase = true)
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, key1 = file.uri, key2 = isImage) {
        if (isImage) {
            value = withContext(Dispatchers.IO) {
                loadSampledThumbnail(context, file.uri, 128)?.asImageBitmap()
            }
        }
    }

    TwCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(ShapeSmall)
                        .border(1.dp, MaterialTheme.colorScheme.outline, ShapeSmall)
                )
            } else {
                TwFileIconBadge(mimeType = file.mimeType, fileName = file.name, size = 46.dp)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedSize,
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TwFileBadge(mimeType = file.mimeType, fileName = file.name, showLabel = true)
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove ${file.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private fun loadSampledThumbnail(context: Context, uri: Uri, targetPx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim <= 0) return null
        var sampleSize = 1
        while (maxDim / sampleSize > targetPx * 2) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    } catch (_: Exception) {
        null
    }
}
