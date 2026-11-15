package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.remote.ChartRange
import com.example.data.remote.FundamentalSnapshot
import com.example.data.remote.MarketQuote
import com.example.domain.analysis.ForecastHorizon
import com.example.domain.analysis.ForecastResult
import com.example.domain.analysis.FundamentalAnalysisResult
import com.example.domain.analysis.SignalVerdict
import com.example.domain.analysis.TechnicalAnalysisResult
import com.example.ui.components.ForecastConeChart
import com.example.ui.components.InteractivePriceAndTechnicalChart
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.StockDetailState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    state: StockDetailState,
    isWatchlisted: Boolean,
    onBack: () -> Unit,
    onChangeRange: (ChartRange) -> Unit,
    onChangeForecastHorizon: (ForecastHorizon) -> Unit,
    onToggleWatchlist: (MarketQuote) -> Unit,
    onOpenSetAlert: (MarketQuote) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marketColors = LocalMarketColors.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showCandles by remember { mutableStateOf(true) }
    var showSma by remember { mutableStateOf(true) }
    var showBollinger by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.symbol,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1
                        )
                        state.quote?.let { q ->
                            Text(
                                text = "${q.name} • ${q.exchange}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    state.quote?.let { quote ->
                        IconButton(onClick = { onToggleWatchlist(quote) }) {
                            Icon(
                                imageVector = if (isWatchlisted) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                contentDescription = "Toggle watchlist",
                                tint = if (isWatchlisted) marketColors.amber else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = { onOpenSetAlert(quote) },
                            modifier = Modifier.testTag("detail_set_alert_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddAlert,
                                contentDescription = "Set real-time price alert",
                                tint = marketColors.forecast
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val quote = state.quote
        if (state.isLoading && quote == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (state.error != null && quote == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, color = marketColors.bearish)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        } else if (quote != null) {
            val isUp = quote.change >= 0.0
            val deltaColor = if (isUp) marketColors.bullish else marketColors.bearish

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (state.isLoading) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }

                // Price Header Card
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, marketColors.cardBorder, RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column {
                                Text(
                                    text = "${quote.currency} $${String.format(Locale.CANADA, "%.2f", quote.price)}",
                                    style = MaterialTheme.typography.displaySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = String.format(
                                        Locale.CANADA,
                                        "%+.2f (%+.2f%%) today",
                                        quote.change,
                                        quote.changePercent
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = deltaColor
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "52W Range",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = String.format(
                                        Locale.CANADA,
                                        "$%.2f – $%.2f",
                                        quote.fiftyTwoWeekLow,
                                        quote.fiftyTwoWeekHigh
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Analytical Tabs: Technical | Fundamental | Forecast
                item {
                    val tabs = listOf(
                        stringResource(id = R.string.tab_technical),
                        stringResource(id = R.string.tab_fundamental),
                        stringResource(id = R.string.tab_forecast)
                    )
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    ) {
                        tabs.forEachIndexed { idx, title ->
                            Tab(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                text = {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                },
                                modifier = Modifier.testTag("detail_tab_${title.lowercase()}")
                            )
                        }
                    }
                }

                when (selectedTab) {
                    0 -> {
                        // Chart Range & Overlay Controls
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(ChartRange.entries) { range ->
                                        FilterChip(
                                            selected = state.range == range,
                                            onClick = { onChangeRange(range) },
                                            label = { Text(range.label) }
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = showCandles,
                                        onClick = { showCandles = !showCandles },
                                        label = { Text(if (showCandles) "Candles" else "Area Line") }
                                    )
                                    FilterChip(
                                        selected = showSma,
                                        onClick = { showSma = !showSma },
                                        label = { Text("SMA 20/50") }
                                    )
                                    FilterChip(
                                        selected = showBollinger,
                                        onClick = { showBollinger = !showBollinger },
                                        label = { Text("Bollinger") }
                                    )
                                }
                            }
                        }

                        item {
                            InteractivePriceAndTechnicalChart(
                                candles = quote.candles,
                                technical = state.technicalResult,
                                showCandlesticks = showCandles,
                                showSmaOverlays = showSma,
                                showBollinger = showBollinger
                            )
                        }

                        state.technicalResult?.let { tech ->
                            item {
                                TechnicalSummarySection(tech = tech)
                            }
                            items(tech.indicatorBreakdown) { sig ->
                                val sigColor = when {
                                    sig.bias > 0 -> marketColors.bullish
                                    sig.bias < 0 -> marketColors.bearish
                                    else -> marketColors.amber
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(1.dp, marketColors.cardBorder, RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sig.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            sig.interpretation,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = sig.valueText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = sigColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        item {
                            FundamentalSection(
                                fundamentals = state.fundamentals,
                                evaluation = state.fundamentalResult
                            )
                        }
                    }

                    2 -> {
                        item {
                            ForecastSection(
                                forecast = state.forecastResult,
                                selectedHorizon = state.horizon,
                                onSelectHorizon = onChangeForecastHorizon
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TechnicalSummarySection(tech: TechnicalAnalysisResult) {
    val marketColors = LocalMarketColors.current
    val (bg, fg) = when (tech.verdict) {
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Technical Consensus", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Composite score across trend, momentum & volatility",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(color = bg, shape = RoundedCornerShape(10.dp)) {
                Text(
                    text = "${tech.verdict.label} (${tech.compositeScore})",
                    style = MaterialTheme.typography.labelMedium,
                    color = fg,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // Pivot Support / Resistance & ATR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatMiniBlock("Support S1", tech.support1?.let { String.format(Locale.CANADA, "$%.2f", it) } ?: "--")
            StatMiniBlock("Pivot", tech.pivotPoint?.let { String.format(Locale.CANADA, "$%.2f", it) } ?: "--")
            StatMiniBlock("Resist R1", tech.resistance1?.let { String.format(Locale.CANADA, "$%.2f", it) } ?: "--")
            StatMiniBlock("ATR(14)", tech.latestAtr14?.let { String.format(Locale.CANADA, "$%.2f", it) } ?: "--")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatMiniBlock("Ann. Volatility", tech.annualizedVolatilityPct?.let { String.format(Locale.CANADA, "%.1f%%", it) } ?: "--")
            StatMiniBlock("Rel. Volume", tech.volumeRatio?.let { String.format(Locale.CANADA, "%.2fx", it) } ?: "--")
            StatMiniBlock("EMA (12)", tech.latestEma12?.let { String.format(Locale.CANADA, "$%.2f", it) } ?: "--")
            StatMiniBlock("EMA (26)", tech.latestEma26?.let { String.format(Locale.CANADA, "$%.2f", it) } ?: "--")
        }
    }
}

@Composable
private fun FundamentalSection(
    fundamentals: FundamentalSnapshot?,
    evaluation: FundamentalAnalysisResult?
) {
    val marketColors = LocalMarketColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (evaluation != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fundamental Health", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = evaluation.valuationLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = marketColors.forecast
                        )
                    }
                    Surface(
                        color = if (evaluation.healthScore >= 65) marketColors.bullishContainer else marketColors.amberContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Grade ${evaluation.ratingGrade} (${evaluation.healthScore}/100)",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (evaluation.healthScore >= 65) marketColors.bullish else marketColors.amber,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                evaluation.pillars.forEach { pillar ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(pillar.name, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${pillar.score}/100 • ${pillar.summary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { pillar.score / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (pillar.score >= 65) marketColors.bullish else marketColors.amber,
                            trackColor = marketColors.surfaceElevated
                        )
                    }
                }

                Surface(
                    color = marketColors.surfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = evaluation.macroNote,
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.forecast,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                evaluation.keyHighlights.forEach { bullet ->
                    Text(
                        text = "• $bullet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (fundamentals != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Valuation & Financial Ratios", style = MaterialTheme.typography.titleMedium)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatMiniBlock("Market Cap", formatLargeNumber(fundamentals.marketCap))
                    StatMiniBlock("Trailing P/E", fundamentals.trailingPE?.let { String.format(Locale.CANADA, "%.2f", it) } ?: "--")
                    StatMiniBlock("Fwd P/E", fundamentals.forwardPE?.let { String.format(Locale.CANADA, "%.2f", it) } ?: "--")
                    StatMiniBlock("P/B", fundamentals.priceToBook?.let { String.format(Locale.CANADA, "%.2f", it) } ?: "--")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatMiniBlock("Div Yield", fundamentals.dividendYield?.let { String.format(Locale.CANADA, "%.2f%%", it * 100) } ?: "--")
                    StatMiniBlock("ROE", fundamentals.returnOnEquity?.let { String.format(Locale.CANADA, "%.1f%%", it * 100) } ?: "--")
                    StatMiniBlock("Profit Mgn", fundamentals.profitMargin?.let { String.format(Locale.CANADA, "%.1f%%", it * 100) } ?: "--")
                    StatMiniBlock("Debt/Eq", fundamentals.debtToEquity?.let { String.format(Locale.CANADA, "%.1f%%", it) } ?: "--")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatMiniBlock("Rev Growth", fundamentals.revenueGrowth?.let { String.format(Locale.CANADA, "%+.1f%%", it * 100) } ?: "--")
                    StatMiniBlock("Free Cash", formatLargeNumber(fundamentals.freeCashFlow))
                    StatMiniBlock("Target Price", fundamentals.targetMeanPrice?.let { String.format(Locale.CANADA, "$%.2f", it) } ?: "--")
                    StatMiniBlock("Beta", fundamentals.beta?.let { String.format(Locale.CANADA, "%.2f", it) } ?: "--")
                }

                fundamentals.summary?.let { desc ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Company Profile", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ForecastSection(
    forecast: ForecastResult?,
    selectedHorizon: ForecastHorizon,
    onSelectHorizon: (ForecastHorizon) -> Unit
) {
    val marketColors = LocalMarketColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForecastHorizon.entries.forEach { horizon ->
                FilterChip(
                    selected = selectedHorizon == horizon,
                    onClick = { onSelectHorizon(horizon) },
                    label = { Text("${horizon.label} Horizon") }
                )
            }
        }

        if (forecast == null) {
            Text(
                text = "Insufficient historical candles to generate forecast.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }

        ForecastConeChart(forecast = forecast)

        // Bear / Base / Bull Targets
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TargetBox(
                label = "Bear (95%)",
                price = forecast.bearTarget95,
                color = marketColors.bearish,
                modifier = Modifier.weight(1f)
            )
            TargetBox(
                label = "Base (${forecast.horizon.label})",
                price = forecast.projectedPrice,
                color = marketColors.forecast,
                modifier = Modifier.weight(1f)
            )
            TargetBox(
                label = "Bull (95%)",
                price = forecast.bullTarget95,
                color = marketColors.bullish,
                modifier = Modifier.weight(1f)
            )
        }

        // Model Diagnostics & Backtest Accuracy
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Quantitative Ensemble Diagnostics",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = forecast.modelSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatMiniBlock("Projected Δ", String.format(Locale.CANADA, "%+.2f%%", forecast.projectedChangePct))
                StatMiniBlock("Ann. Drift", String.format(Locale.CANADA, "%+.1f%%", forecast.annualizedDriftPct))
                StatMiniBlock("R² Fit", String.format(Locale.CANADA, "%.2f", forecast.rSquared))
                StatMiniBlock("Backtest MAPE", String.format(Locale.CANADA, "%.1f%%", forecast.backtestMapePct))
            }
            Text(
                text = String.format(
                    Locale.CANADA,
                    "Out-of-sample 5-day directional accuracy: %.0f%%  •  Daily σ: %.2f%%",
                    forecast.directionalAccuracyPct,
                    forecast.dailyVolatilityPct
                ),
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.amber
            )
        }
    }
}

@Composable
private fun TargetBox(
    label: String,
    price: Double,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val marketColors = LocalMarketColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, marketColors.cardBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = String.format(Locale.CANADA, "$%.2f", price),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatMiniBlock(label: String, value: String) {
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

private fun formatLargeNumber(value: Double?): String {
    if (value == null) return "--"
    val abs = kotlin.math.abs(value)
    return when {
        abs >= 1e12 -> String.format(Locale.CANADA, "$%.2fT", value / 1e12)
        abs >= 1e9 -> String.format(Locale.CANADA, "$%.2fB", value / 1e9)
        abs >= 1e6 -> String.format(Locale.CANADA, "$%.1fM", value / 1e6)
        else -> String.format(Locale.CANADA, "$%,.0f", value)
    }
}
