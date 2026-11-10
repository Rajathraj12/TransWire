package com.example.myapplication.presentation.history

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.db.TransferEntity
import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*
import java.text.SimpleDateFormat
import java.util.*

private enum class HistoryFilter(val label: String) {
    ALL("All"),
    RECEIVED("Received"),
    SENT("Sent"),
    RESUMABLE("Resumable"),
    FAILED("Failed")
}

@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModelFactory(LocalContext.current.applicationContext as android.app.Application)
    )
) {
    val allHistory by viewModel.history.collectAsState()
    val context = LocalContext.current
    val fileStorageManager = remember(context) { FileStorageManager(context) }

    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(HistoryFilter.ALL) }

    val filtered = remember(allHistory, searchQuery, activeFilter) {
        allHistory.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                item.fileName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (activeFilter) {
                HistoryFilter.ALL       -> true
                HistoryFilter.RECEIVED  -> item.isIncoming
                HistoryFilter.SENT      -> !item.isIncoming
                HistoryFilter.RESUMABLE -> item.status in setOf("PAUSED", "INTERRUPTED")
                HistoryFilter.FAILED    -> item.status in setOf("FAILED", "CANCELLED")
            }
            matchesQuery && matchesFilter
        }
    }

    val grouped = remember(filtered) {
        filtered.groupBy { dayLabel(it.timestamp) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // ── Editorial Header ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = TwSpacing.Default, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Transfer History",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${allHistory.size} file${if (allHistory.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Search & Filters ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TwSpacing.Default),
                placeholder = { Text("Search by file name…", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = ShapePill,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor   = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedBorderColor      = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor    = MaterialTheme.colorScheme.outline
                )
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = TwSpacing.Default, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(HistoryFilter.entries.size) { index ->
                    val filter = HistoryFilter.entries[index]
                    val selected = activeFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { activeFilter = filter },
                        label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                        shape = ShapePill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor     = MaterialTheme.colorScheme.primary
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
        }

        TwDivider()

        // ── Timeline List / Empty State ───────────────────────────────────────
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 84.dp),
                contentAlignment = Alignment.Center
            ) {
                TwEmptyState(
                    title = if (allHistory.isEmpty()) "No Transfers Yet" else "No Matching Transfers",
                    subtitle = if (allHistory.isEmpty()) {
                        "Files you send or receive via Nearby or Remote will be logged here with integrity status."
                    } else {
                        "No records match your current search or filter."
                    },
                    icon = if (allHistory.isEmpty()) Icons.Default.History else Icons.Default.SearchOff
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = TwSpacing.Default,
                    end = TwSpacing.Default,
                    top = TwSpacing.Default,
                    bottom = 110.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                grouped.forEach { (dateLabel, items) ->
                    item(key = "header_$dateLabel") {
                        TwSectionLabel(
                            text = dateLabel,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }
                    items(items, key = { it.fileId }) { item ->
                        HistoryTimelineCard(
                            item = item,
                            context = context,
                            onOpen = {
                                fileStorageManager.openCompletedFile(
                                    fileUriString = item.fileUri,
                                    fileName = item.fileName,
                                    mimeType = item.mimeType,
                                    status = item.status
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTimelineCard(
    item: TransferEntity,
    context: Context,
    onOpen: () -> Unit
) {
    val totalFormatted       = Formatter.formatFileSize(context, item.totalBytes)
    val transferredFormatted = Formatter.formatFileSize(context, item.transferredBytes)
    val isCompleted          = item.status == "COMPLETED"
    val isResumable          = item.status in setOf("INTERRUPTED", "PAUSED")
    val timeText             = remember(item.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(item.timestamp))
    }

    TwCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TwFileIconBadge(mimeType = item.mimeType, fileName = item.fileName, size = 44.dp)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (item.isIncoming) Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
                            contentDescription = null,
                            tint = if (item.isIncoming) MaterialTheme.colorScheme.secondary
                                   else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = when {
                                isCompleted -> "$totalFormatted • $timeText"
                                isResumable -> "$transferredFormatted / $totalFormatted • Resumable"
                                !item.errorMessage.isNullOrBlank() -> "$totalFormatted • ${item.errorMessage}"
                                else -> "$totalFormatted • $timeText"
                            },
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TwStatusChip(status = item.status)
                    if (isCompleted) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Open",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open file",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            if (isResumable && item.totalBytes > 0L) {
                val fraction = (item.transferredBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(ShapePill),
                    color = statusColor(item.status),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

private fun dayLabel(timestamp: Long): String {
    val now = Calendar.getInstance()
    val todayDay = now.get(Calendar.DAY_OF_YEAR)
    val todayYear = now.get(Calendar.YEAR)

    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val day = target.get(Calendar.DAY_OF_YEAR)
    val year = target.get(Calendar.YEAR)

    return when {
        year == todayYear && day == todayDay     -> "Today"
        year == todayYear && day == todayDay - 1 -> "Yesterday"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
