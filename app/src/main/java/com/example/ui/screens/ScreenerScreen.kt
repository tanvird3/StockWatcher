package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.remote.CanadianUniverse
import com.example.domain.analysis.SignalVerdict
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.MarketDashboardState
import com.example.ui.viewmodel.ScreenerRow
import java.util.Locale

private enum class ScreenerPreset(val label: String) {
    ALL("All TSX"),
    BULLISH("Bullish Signals"),
    OVERSOLD("Oversold RSI ≤ 35"),
    OVERBOUGHT("Overbought RSI ≥ 70"),
    GOLDEN_CROSS("50 > 200 SMA"),
    HIGH_VOL("High Volatility")
}

@Composable
fun ScreenerScreen(
    state: MarketDashboardState,
    onOpenStock: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val marketColors = LocalMarketColors.current
    var selectedPreset by remember { mutableStateOf(ScreenerPreset.ALL) }

    val rows = remember(state.marketQuotes, state.technicalBySymbol, selectedPreset) {
        state.marketQuotes.values
            .filter { it.symbol != CanadianUniverse.TSX_COMPOSITE_INDEX }
            .mapNotNull { q ->
                val tech = state.technicalBySymbol[q.symbol] ?: return@mapNotNull null
                ScreenerRow(q, tech)
            }
            .filter { row ->
                when (selectedPreset) {
                    ScreenerPreset.ALL -> true
                    ScreenerPreset.BULLISH ->
                        row.technical.verdict == SignalVerdict.STRONG_BUY || row.technical.verdict == SignalVerdict.BUY
                    ScreenerPreset.OVERSOLD ->
                        (row.technical.latestRsi14 ?: 50.0) <= 35.0
                    ScreenerPreset.OVERBOUGHT ->
                        (row.technical.latestRsi14 ?: 50.0) >= 70.0
                    ScreenerPreset.GOLDEN_CROSS -> {
                        val s50 = row.technical.latestSma50
                        val s200 = row.technical.latestSma200
                        val s20 = row.technical.latestSma20
                        if (s50 != null && s200 != null) s50 > s200
                        else if (s20 != null && s50 != null) s20 > s50
                        else false
                    }
                    ScreenerPreset.HIGH_VOL ->
                        (row.technical.annualizedVolatilityPct ?: 0.0) >= 24.0
                }
            }
            .sortedByDescending { it.technical.compositeScore }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Canadian Technical Screener",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Scan TSX & TSXV equities by multi-factor momentum, RSI extremes, and trend crossovers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ScreenerPreset.entries) { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        label = { Text(preset.label) }
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "No TSX equities match \"${selectedPreset.label}\" right now.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Select another factor filter or refresh live market quotes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(rows, key = { it.quote.symbol }) { row ->
                val tech = row.technical
                val quote = row.quote
                val (badgeBg, badgeFg) = when (tech.verdict) {
                    SignalVerdict.STRONG_BUY, SignalVerdict.BUY -> marketColors.bullishContainer to marketColors.bullish
                    SignalVerdict.STRONG_SELL, SignalVerdict.SELL -> marketColors.bearishContainer to marketColors.bearish
                    SignalVerdict.NEUTRAL -> marketColors.amberContainer to marketColors.amber
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                        .clickable { onOpenStock(quote.symbol) }
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${quote.symbol}  •  ${quote.sector}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = quote.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(color = badgeBg, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = "${tech.verdict.label} (${tech.compositeScore})",
                                style = MaterialTheme.typography.labelMedium,
                                color = badgeFg,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ScreenerMetricCell(
                            label = "Price",
                            value = String.format(Locale.CANADA, "$%.2f", quote.price)
                        )
                        ScreenerMetricCell(
                            label = "RSI(14)",
                            value = tech.latestRsi14?.let { String.format(Locale.CANADA, "%.1f", it) } ?: "--"
                        )
                        ScreenerMetricCell(
                            label = "MACD Hist",
                            value = tech.latestMacdHistogram?.let { String.format(Locale.CANADA, "%+.2f", it) } ?: "--"
                        )
                        ScreenerMetricCell(
                            label = "Ann. Vol",
                            value = tech.annualizedVolatilityPct?.let { String.format(Locale.CANADA, "%.1f%%", it) } ?: "--"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenerMetricCell(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}
