package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.WatchlistItem
import com.example.data.remote.MarketQuote
import com.example.domain.analysis.TechnicalAnalysisResult
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.FirestoreUiState
import java.util.Locale

@Composable
fun WatchlistScreen(
    watchlistState: FirestoreUiState<List<WatchlistItem>>,
    quotes: Map<String, MarketQuote>,
    technicals: Map<String, TechnicalAnalysisResult>,
    onOpenStock: (String) -> Unit,
    onRemoveWatchlist: (WatchlistItem) -> Unit,
    onSaveNotes: (WatchlistItem, String) -> Unit,
    onQuickAlert: (WatchlistItem, MarketQuote?) -> Unit,
    onExploreMarkets: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marketColors = LocalMarketColors.current
    var editingItem by remember { mutableStateOf<WatchlistItem?>(null) }

    when (watchlistState) {
        is FirestoreUiState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is FirestoreUiState.Error -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = watchlistState.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = marketColors.bearish
                )
            }
        }
        is FirestoreUiState.Success -> {
            val items = watchlistState.data
            if (items.isEmpty()) {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.StarOutline,
                            contentDescription = null,
                            tint = marketColors.amber,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(id = R.string.no_watchlist_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(id = R.string.no_watchlist_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(onClick = onExploreMarkets, shape = RoundedCornerShape(12.dp)) {
                            Text("Browse TSX Markets")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Synced Watchlist",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "${items.size} tracked",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(items, key = { it.id }) { item ->
                        val quote = quotes[item.symbol]
                        val tech = technicals[item.symbol]
                        val isUp = (quote?.change ?: 0.0) >= 0.0

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                                .clickable { onOpenStock(item.symbol) }
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${item.symbol} • ${item.exchange}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = quote?.let { String.format(Locale.CANADA, "$%.2f", it.price) } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    quote?.let { q ->
                                        Text(
                                            text = String.format(Locale.CANADA, "%+.2f%%", q.changePercent),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isUp) marketColors.bullish else marketColors.bearish
                                        )
                                    }
                                }
                            }

                            if (item.notes.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = marketColors.surfaceElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = item.notes,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    tech?.let { t ->
                                        Surface(
                                            color = marketColors.surfaceElevated,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "${t.verdict.label} • RSI ${t.latestRsi14?.let { String.format(Locale.CANADA, "%.0f", it) } ?: "--"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = marketColors.forecast,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                Row {
                                    IconButton(onClick = { editingItem = item }) {
                                        Icon(
                                            Icons.Filled.EditNote,
                                            contentDescription = "Edit thesis note for ${item.symbol}",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { onQuickAlert(item, quote) }) {
                                        Icon(
                                            Icons.Filled.AddAlert,
                                            contentDescription = "Create alert for ${item.symbol}",
                                            tint = marketColors.forecast
                                        )
                                    }
                                    IconButton(onClick = { onRemoveWatchlist(item) }) {
                                        Icon(
                                            Icons.Filled.BookmarkRemove,
                                            contentDescription = "Remove ${item.symbol} from watchlist",
                                            tint = marketColors.bearish
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        var noteText by remember(item.id) { mutableStateOf(item.notes) }
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Thesis Note — ${item.symbol}") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it.take(300) },
                    label = { Text("Investment notes & target entry") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveNotes(item, noteText)
                        editingItem = null
                    }
                ) {
                    Text("Save Note")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
