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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.WatchlistItem
import com.example.data.remote.CanadianUniverse
import com.example.data.remote.MarketQuote
import com.example.domain.analysis.SignalVerdict
import com.example.domain.analysis.TechnicalAnalysisResult
import com.example.ui.components.SparklineMiniChart
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.MarketDashboardState
import java.util.Locale

@Composable
fun MarketsScreen(
    state: MarketDashboardState,
    watchlist: List<WatchlistItem>,
    onSearchQueryChange: (String) -> Unit,
    onSelectSector: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenStock: (String) -> Unit,
    onToggleWatchlist: (MarketQuote) -> Unit,
    onQuickAlert: (MarketQuote) -> Unit,
    modifier: Modifier = Modifier
) {
    val marketColors = LocalMarketColors.current
    val watchlistSymbols = watchlist.map { it.symbol.uppercase() }.toSet()

    val filteredQuotes = state.marketQuotes.values
        .filter { it.symbol != CanadianUniverse.TSX_COMPOSITE_INDEX }
        .filter { quote ->
            state.selectedSector == "All" || quote.sector.equals(state.selectedSector, ignoreCase = true)
        }
        .sortedByDescending { kotlin.math.abs(it.changePercent) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search Bar
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text(
                        text = stringResource(id = R.string.search_tsx_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = "Search Canadian stocks")
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tsx_search_input")
            )
        }

        if (state.isSearching) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        // Live Search Results (when user types)
        if (state.searchQuery.trim().length >= 2 && state.searchResults.isNotEmpty()) {
            item {
                Text(
                    text = "Canadian Exchange Matches",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            items(state.searchResults, key = { "search_${it.symbol}" }) { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, marketColors.cardBorder, RoundedCornerShape(14.dp))
                        .clickable { onOpenStock(hit.symbol) }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = hit.symbol,
                                style = MaterialTheme.typography.titleMedium,
                                color = marketColors.bullish,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = marketColors.surfaceElevated,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = hit.exchange,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = hit.shortName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Analyze →",
                        style = MaterialTheme.typography.labelLarge,
                        color = marketColors.forecast
                    )
                }
            }
        }

        // S&P/TSX Composite Hero + Bank of Canada Macro Strip
        item {
            TsxCompositeHeroCard(
                tsxQuote = state.tsxComposite,
                macro = state.macro,
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                onOpenIndex = { onOpenStock(CanadianUniverse.TSX_COMPOSITE_INDEX) }
            )
        }

        // Sector Filter Chips
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(CanadianUniverse.sectors) { sector ->
                    FilterChip(
                        selected = state.selectedSector == sector,
                        onClick = { onSelectSector(sector) },
                        label = { Text(sector) },
                        modifier = Modifier.testTag("sector_chip_${sector.lowercase().replace(" ", "_")}")
                    )
                }
            }
        }

        // Loading / Error states
        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Fetching live TSX & TSXV quotes…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (state.error != null && state.marketQuotes.isEmpty()) {
            item {
                Surface(
                    color = marketColors.bearishContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyLarge,
                            color = marketColors.bearish
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = onRefresh) {
                            Text("Retry Market Feed")
                        }
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.selectedSector == "All") "TSX / TSXV Active Movers" else "${state.selectedSector} Leaders",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${filteredQuotes.size} equities",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(filteredQuotes, key = { it.symbol }) { quote ->
                val tech = state.technicalBySymbol[quote.symbol]
                EquityMarketCard(
                    quote = quote,
                    technical = tech,
                    isWatchlisted = watchlistSymbols.contains(quote.symbol.uppercase()),
                    onClick = { onOpenStock(quote.symbol) },
                    onToggleWatchlist = { onToggleWatchlist(quote) },
                    onQuickAlert = { onQuickAlert(quote) }
                )
            }
        }

        // Canadian Market News Wire
        if (state.marketNews.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Canadian Market Wire",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            items(state.marketNews.take(5)) { news ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, marketColors.cardBorder, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = news.publisher,
                            style = MaterialTheme.typography.labelSmall,
                            color = marketColors.amber
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = news.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TsxCompositeHeroCard(
    tsxQuote: MarketQuote?,
    macro: com.example.data.remote.CanadianMacroSnapshot?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenIndex: () -> Unit
) {
    val marketColors = LocalMarketColors.current
    val isUp = (tsxQuote?.change ?: 0.0) >= 0.0
    val accent = if (isUp) marketColors.bullish else marketColors.bearish

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, marketColors.cardBorder, RoundedCornerShape(20.dp))
            .clickable(enabled = tsxQuote != null, onClick = onOpenIndex)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "S&P/TSX COMPOSITE",
                    style = MaterialTheme.typography.labelMedium,
                    color = marketColors.amber
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tsxQuote?.let { String.format(Locale.CANADA, "%,.2f", it.price) } ?: "Loading…",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tsxQuote != null) {
                    Surface(
                        color = if (isUp) marketColors.bullishContainer else marketColors.bearishContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = String.format(
                                Locale.CANADA,
                                "%+.2f (%+.2f%%)",
                                tsxQuote.change,
                                tsxQuote.changePercent
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("refresh_market_button")
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh TSX prices",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (tsxQuote != null && tsxQuote.candles.size >= 4) {
            Spacer(modifier = Modifier.height(10.dp))
            SparklineMiniChart(
                candles = tsxQuote.candles,
                isPositive = isUp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            )
        }

        if (macro != null && (macro.bocOvernightRate != null || macro.cadUsdRate != null)) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(marketColors.surfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                macro.bocOvernightRate?.let { rate ->
                    Text(
                        text = String.format(Locale.CANADA, "BoC Rate: %.2f%%", rate),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                macro.cadUsdRate?.let { fx ->
                    Text(
                        text = String.format(Locale.CANADA, "CAD/USD: %.4f", fx),
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.forecast
                    )
                }
            }
        }
    }
}

@Composable
fun EquityMarketCard(
    quote: MarketQuote,
    technical: TechnicalAnalysisResult?,
    isWatchlisted: Boolean,
    onClick: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onQuickAlert: () -> Unit
) {
    val marketColors = LocalMarketColors.current
    val isUp = quote.change >= 0.0
    val deltaColor = if (isUp) marketColors.bullish else marketColors.bearish

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("equity_card_${quote.symbol}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.35f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = quote.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = marketColors.surfaceElevated,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = quote.exchange,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = quote.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (technical != null) {
                val (badgeBg, badgeFg) = when (technical.verdict) {
                    SignalVerdict.STRONG_BUY, SignalVerdict.BUY -> marketColors.bullishContainer to marketColors.bullish
                    SignalVerdict.STRONG_SELL, SignalVerdict.SELL -> marketColors.bearishContainer to marketColors.bearish
                    SignalVerdict.NEUTRAL -> marketColors.amberContainer to marketColors.amber
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = badgeBg, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = technical.verdict.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeFg,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    technical.latestRsi14?.let { rsi ->
                        Text(
                            text = String.format(Locale.CANADA, "RSI %.0f", rsi),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        SparklineMiniChart(
            candles = quote.candles,
            isPositive = isUp,
            modifier = Modifier
                .width(64.dp)
                .height(34.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = String.format(Locale.CANADA, "$%.2f", quote.price),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                color = if (isUp) marketColors.bullishContainer else marketColors.bearishContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = String.format(Locale.CANADA, "%+.2f%%", quote.changePercent),
                    style = MaterialTheme.typography.labelMedium,
                    color = deltaColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Row {
                IconButton(
                    onClick = onToggleWatchlist,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isWatchlisted) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (isWatchlisted) "Remove from watchlist" else "Add to watchlist",
                        tint = if (isWatchlisted) marketColors.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onQuickAlert,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddAlert,
                        contentDescription = "Set price alert for ${quote.symbol}",
                        tint = marketColors.forecast,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
