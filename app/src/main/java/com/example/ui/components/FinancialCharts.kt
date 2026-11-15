package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.remote.OhlcvCandle
import com.example.domain.analysis.ForecastResult
import com.example.domain.analysis.TechnicalAnalysisResult
import com.example.ui.theme.LocalMarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun SparklineMiniChart(
    candles: List<OhlcvCandle>,
    isPositive: Boolean,
    modifier: Modifier = Modifier
) {
    val marketColors = LocalMarketColors.current
    val strokeColor = if (isPositive) marketColors.bullish else marketColors.bearish
    val values = remember(candles) { candles.takeLast(32).map { it.close } }

    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val minVal = values.minOrNull() ?: 0.0
        val maxVal = values.maxOrNull() ?: 1.0
        val range = (maxVal - minVal).takeIf { it > 1e-6 } ?: 1.0
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)

        val linePath = Path()
        val fillPath = Path()

        values.forEachIndexed { index, v ->
            val x = index * stepX
            val y = size.height - (((v - minVal) / range).toFloat() * (size.height * 0.85f) + size.height * 0.075f)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo((values.size - 1) * stepX, size.height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(strokeColor.copy(alpha = 0.28f), Color.Transparent)
            )
        )
        drawPath(
            path = linePath,
            color = strokeColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun InteractivePriceAndTechnicalChart(
    candles: List<OhlcvCandle>,
    technical: TechnicalAnalysisResult?,
    showCandlesticks: Boolean,
    showSmaOverlays: Boolean,
    showBollinger: Boolean,
    modifier: Modifier = Modifier
) {
    val marketColors = LocalMarketColors.current
    var selectedIdx by remember(candles) { mutableStateOf<Int?>(null) }
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.CANADA) }

    val displayCandles = remember(candles) { candles.takeLast(110) }
    val offset = remember(candles, displayCandles) { (candles.size - displayCandles.size).coerceAtLeast(0) }

    Column(modifier = modifier) {
        val activeCandle = selectedIdx?.let { displayCandles.getOrNull(it) } ?: displayCandles.lastOrNull()
        if (activeCandle != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormatter.format(Date(activeCandle.timestampSec * 1000L)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = String.format(
                        Locale.CANADA,
                        "O %.2f  H %.2f  L %.2f  C %.2f",
                        activeCandle.open,
                        activeCandle.high,
                        activeCandle.low,
                        activeCandle.close
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(displayCandles) {
                        detectTapGestures { pos ->
                            if (displayCandles.isNotEmpty()) {
                                val idx = ((pos.x / size.width) * (displayCandles.size - 1))
                                    .roundToInt()
                                    .coerceIn(0, displayCandles.lastIndex)
                                selectedIdx = idx
                            }
                        }
                    }
                    .pointerInput(displayCandles) {
                        detectDragGestures(
                            onDragEnd = { selectedIdx = null },
                            onDragCancel = { selectedIdx = null },
                            onDrag = { change, _ ->
                                change.consume()
                                if (displayCandles.isNotEmpty()) {
                                    val idx = ((change.position.x / size.width) * (displayCandles.size - 1))
                                        .roundToInt()
                                        .coerceIn(0, displayCandles.lastIndex)
                                    selectedIdx = idx
                                }
                            }
                        )
                    }
            ) {
                if (displayCandles.size < 2) return@Canvas

                val sma20Sub = technical?.sma20Series?.drop(offset) ?: emptyList()
                val sma50Sub = technical?.sma50Series?.drop(offset) ?: emptyList()
                val bbUpSub = technical?.bollingerUpper?.drop(offset) ?: emptyList()
                val bbLowSub = technical?.bollingerLower?.drop(offset) ?: emptyList()

                val allPrices = mutableListOf<Double>()
                displayCandles.forEach {
                    allPrices.add(it.high)
                    allPrices.add(it.low)
                }
                if (showBollinger) {
                    bbUpSub.filterNotNull().forEach { allPrices.add(it) }
                    bbLowSub.filterNotNull().forEach { allPrices.add(it) }
                }
                val minP = allPrices.minOrNull() ?: 0.0
                val maxP = allPrices.maxOrNull() ?: 1.0
                val span = (maxP - minP).takeIf { it > 1e-6 } ?: 1.0

                fun yFor(price: Double): Float {
                    val norm = ((price - minP) / span).toFloat()
                    return size.height - (norm * (size.height * 0.88f) + size.height * 0.06f)
                }

                // Grid lines
                for (g in 0..3) {
                    val gy = size.height * (0.12f + g * 0.25f)
                    drawLine(
                        color = marketColors.gridLine,
                        start = Offset(0f, gy),
                        end = Offset(size.width, gy),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val stepX = size.width / (displayCandles.size - 1).coerceAtLeast(1)

                // Bollinger Band envelope
                if (showBollinger && bbUpSub.size == displayCandles.size && bbLowSub.size == displayCandles.size) {
                    val upperPath = Path()
                    val lowerPath = Path()
                    val bandFill = Path()
                    var started = false
                    val upperPoints = mutableListOf<Offset>()
                    val lowerPoints = mutableListOf<Offset>()
                    for (i in displayCandles.indices) {
                        val u = bbUpSub[i]
                        val l = bbLowSub[i]
                        if (u != null && l != null) {
                            val x = i * stepX
                            val yu = yFor(u)
                            val yl = yFor(l)
                            upperPoints.add(Offset(x, yu))
                            lowerPoints.add(Offset(x, yl))
                            if (!started) {
                                upperPath.moveTo(x, yu)
                                lowerPath.moveTo(x, yl)
                                started = true
                            } else {
                                upperPath.lineTo(x, yu)
                                lowerPath.lineTo(x, yl)
                            }
                        }
                    }
                    if (upperPoints.size >= 2) {
                        bandFill.moveTo(upperPoints.first().x, upperPoints.first().y)
                        upperPoints.drop(1).forEach { bandFill.lineTo(it.x, it.y) }
                        lowerPoints.reversed().forEach { bandFill.lineTo(it.x, it.y) }
                        bandFill.close()
                        drawPath(bandFill, color = marketColors.forecast.copy(alpha = 0.10f))
                        drawPath(
                            upperPath,
                            color = marketColors.forecast.copy(alpha = 0.55f),
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                        drawPath(
                            lowerPath,
                            color = marketColors.forecast.copy(alpha = 0.55f),
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                    }
                }

                // Price series: Candlestick or Line
                if (showCandlesticks) {
                    val candleWidth = max(2.dp.toPx(), (size.width / displayCandles.size) * 0.58f)
                    displayCandles.forEachIndexed { i, c ->
                        val cx = i * stepX
                        val bullish = c.close >= c.open
                        val color = if (bullish) marketColors.bullish else marketColors.bearish
                        val yHigh = yFor(c.high)
                        val yLow = yFor(c.low)
                        val yOpen = yFor(c.open)
                        val yClose = yFor(c.close)
                        drawLine(
                            color = color,
                            start = Offset(cx, yHigh),
                            end = Offset(cx, yLow),
                            strokeWidth = 1.3.dp.toPx()
                        )
                        val top = min(yOpen, yClose)
                        val bodyHeight = max(2.dp.toPx(), kotlin.math.abs(yClose - yOpen))
                        drawRect(
                            color = color,
                            topLeft = Offset(cx - candleWidth / 2f, top),
                            size = Size(candleWidth, bodyHeight)
                        )
                    }
                } else {
                    val isOverallUp = displayCandles.last().close >= displayCandles.first().close
                    val lineColor = if (isOverallUp) marketColors.bullish else marketColors.bearish
                    val linePath = Path()
                    val areaPath = Path()
                    displayCandles.forEachIndexed { i, c ->
                        val x = i * stepX
                        val y = yFor(c.close)
                        if (i == 0) {
                            linePath.moveTo(x, y)
                            areaPath.moveTo(x, size.height)
                            areaPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            areaPath.lineTo(x, y)
                        }
                    }
                    areaPath.lineTo((displayCandles.size - 1) * stepX, size.height)
                    areaPath.close()
                    drawPath(
                        areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent)
                        )
                    )
                    drawPath(
                        linePath,
                        color = lineColor,
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // SMA 20 & SMA 50 overlays
                if (showSmaOverlays) {
                    drawNullableLine(sma20Sub, stepX, ::yFor, marketColors.amber, 1.5.dp.toPx())
                    drawNullableLine(sma50Sub, stepX, ::yFor, marketColors.violet, 1.5.dp.toPx())
                }

                // Crosshair
                selectedIdx?.let { idx ->
                    val cx = idx * stepX
                    val cy = yFor(displayCandles[idx].close)
                    drawLine(
                        color = Color.White.copy(alpha = 0.55f),
                        start = Offset(cx, 0f),
                        end = Offset(cx, size.height),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        // Sub-charts: RSI(14) and MACD
        if (technical != null && displayCandles.size >= 20) {
            Spacer(modifier = Modifier.height(10.dp))
            val rsiSub = technical.rsiSeries.drop(offset)
            val macdSub = technical.macdLine.drop(offset)
            val sigSub = technical.macdSignal.drop(offset)
            val histSub = technical.macdHistogram.drop(offset)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("RSI (14)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = technical.latestRsi14?.let { String.format(Locale.CANADA, "%.1f", it) } ?: "--",
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                (technical.latestRsi14 ?: 50.0) >= 70.0 -> marketColors.bearish
                                (technical.latestRsi14 ?: 50.0) <= 30.0 -> marketColors.bullish
                                else -> marketColors.amber
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Canvas(modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        val validCount = rsiSub.size
                        if (validCount < 2) return@Canvas
                        val stepX = size.width / (validCount - 1).coerceAtLeast(1)
                        val y70 = size.height * (1f - 0.70f)
                        val y30 = size.height * (1f - 0.30f)
                        drawRect(
                            color = marketColors.amber.copy(alpha = 0.08f),
                            topLeft = Offset(0f, y70),
                            size = Size(size.width, y30 - y70)
                        )
                        drawLine(marketColors.bearish.copy(alpha = 0.4f), Offset(0f, y70), Offset(size.width, y70), 1.dp.toPx())
                        drawLine(marketColors.bullish.copy(alpha = 0.4f), Offset(0f, y30), Offset(size.width, y30), 1.dp.toPx())
                        drawNullableLine(
                            values = rsiSub,
                            stepX = stepX,
                            yFor = { v -> size.height * (1f - (v.toFloat().coerceIn(0f, 100f) / 100f)) },
                            color = marketColors.amber,
                            strokeWidth = 1.6.dp.toPx()
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("MACD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = technical.latestMacdHistogram?.let { String.format(Locale.CANADA, "%+.2f", it) } ?: "--",
                            style = MaterialTheme.typography.labelMedium,
                            color = if ((technical.latestMacdHistogram ?: 0.0) >= 0) marketColors.bullish else marketColors.bearish
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Canvas(modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        val n = macdSub.size
                        if (n < 2) return@Canvas
                        val allM = (macdSub + sigSub + histSub).filterNotNull()
                        if (allM.isEmpty()) return@Canvas
                        val maxAbs = max(allM.maxOf { kotlin.math.abs(it) }, 0.05)
                        val stepX = size.width / (n - 1).coerceAtLeast(1)
                        val midY = size.height / 2f
                        fun yFor(v: Double): Float = midY - ((v / maxAbs).toFloat() * (size.height * 0.44f))

                        drawLine(marketColors.gridLine, Offset(0f, midY), Offset(size.width, midY), 1.dp.toPx())
                        val barW = max(1.5.dp.toPx(), (size.width / n) * 0.55f)
                        histSub.forEachIndexed { idx, h ->
                            if (h != null) {
                                val x = idx * stepX
                                val y = yFor(h)
                                val color = if (h >= 0) marketColors.bullish.copy(alpha = 0.65f) else marketColors.bearish.copy(alpha = 0.65f)
                                drawLine(color, Offset(x, midY), Offset(x, y), strokeWidth = barW)
                            }
                        }
                        drawNullableLine(macdSub, stepX, ::yFor, marketColors.forecast, 1.4.dp.toPx())
                        drawNullableLine(sigSub, stepX, ::yFor, marketColors.amber, 1.3.dp.toPx())
                    }
                }
            }
        }
    }
}

@Composable
fun ForecastConeChart(
    forecast: ForecastResult,
    modifier: Modifier = Modifier
) {
    val marketColors = LocalMarketColors.current
    val dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val history = forecast.historicalTail
    val future = forecast.forecastPoints

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(225.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val totalCount = history.size + future.size
            if (totalCount < 3) return@Canvas

            val allVals = mutableListOf<Double>()
            allVals.addAll(history)
            future.forEach {
                allVals.add(it.upper2Sigma)
                allVals.add(it.lower2Sigma)
            }
            val minV = allVals.minOrNull() ?: 0.0
            val maxV = allVals.maxOrNull() ?: 1.0
            val span = (maxV - minV).takeIf { it > 1e-6 } ?: 1.0

            val stepX = size.width / (totalCount - 1).coerceAtLeast(1)
            fun yFor(p: Double): Float {
                val norm = ((p - minV) / span).toFloat()
                return size.height - (norm * (size.height * 0.86f) + size.height * 0.07f)
            }

            // Grid
            for (g in 0..3) {
                val gy = size.height * (0.14f + g * 0.24f)
                drawLine(marketColors.gridLine, Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx())
            }

            val splitIdx = (history.size - 1).coerceAtLeast(0)
            val splitX = splitIdx * stepX
            val splitPrice = history.lastOrNull() ?: forecast.currentPrice
            val splitY = yFor(splitPrice)

            // Vertical divider between Actual and Forecast
            drawLine(
                color = dividerColor,
                start = Offset(splitX, 0f),
                end = Offset(splitX, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )

            // 95% (2-sigma) confidence cone
            val cone95 = Path().apply {
                moveTo(splitX, splitY)
                future.forEachIndexed { idx, pt ->
                    lineTo((history.size + idx) * stepX, yFor(pt.upper2Sigma))
                }
                for (idx in future.indices.reversed()) {
                    lineTo((history.size + idx) * stepX, yFor(future[idx].lower2Sigma))
                }
                close()
            }
            drawPath(cone95, color = marketColors.forecast.copy(alpha = 0.14f))

            // 68% (1-sigma) confidence cone
            val cone68 = Path().apply {
                moveTo(splitX, splitY)
                future.forEachIndexed { idx, pt ->
                    lineTo((history.size + idx) * stepX, yFor(pt.upper1Sigma))
                }
                for (idx in future.indices.reversed()) {
                    lineTo((history.size + idx) * stepX, yFor(future[idx].lower1Sigma))
                }
                close()
            }
            drawPath(cone68, color = marketColors.forecast.copy(alpha = 0.24f))

            // Historical solid line
            val histPath = Path()
            history.forEachIndexed { idx, price ->
                val x = idx * stepX
                val y = yFor(price)
                if (idx == 0) histPath.moveTo(x, y) else histPath.lineTo(x, y)
            }
            drawPath(
                histPath,
                color = marketColors.bullish,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Forecast base projection dashed line
            val projPath = Path().apply {
                moveTo(splitX, splitY)
                future.forEachIndexed { idx, pt ->
                    lineTo((history.size + idx) * stepX, yFor(pt.basePrice))
                }
            }
            drawPath(
                projPath,
                color = marketColors.forecast,
                style = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )
            )

            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = Offset(splitX, splitY)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNullableLine(
    values: List<Double?>,
    stepX: Float,
    yFor: (Double) -> Float,
    color: Color,
    strokeWidth: Float
) {
    val path = Path()
    var active = false
    values.forEachIndexed { i, v ->
        if (v != null) {
            val x = i * stepX
            val y = yFor(v)
            if (!active) {
                path.moveTo(x, y)
                active = true
            } else {
                path.lineTo(x, y)
            }
        } else {
            active = false
        }
    }
    if (active) {
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
