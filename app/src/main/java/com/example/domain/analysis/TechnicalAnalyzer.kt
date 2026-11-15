package com.example.domain.analysis

import com.example.data.remote.MarketQuote
import com.example.data.remote.OhlcvCandle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class SignalVerdict(val label: String) {
    STRONG_BUY("Strong Buy"),
    BUY("Buy"),
    NEUTRAL("Neutral"),
    SELL("Sell"),
    STRONG_SELL("Strong Sell")
}

data class IndicatorSignal(
    val name: String,
    val valueText: String,
    val interpretation: String,
    val bias: Int // +1 bullish, 0 neutral, -1 bearish
)

data class TechnicalAnalysisResult(
    val symbol: String,
    val currentPrice: Double,
    val sma20Series: List<Double?>,
    val sma50Series: List<Double?>,
    val sma200Series: List<Double?>,
    val bollingerUpper: List<Double?>,
    val bollingerLower: List<Double?>,
    val rsiSeries: List<Double?>,
    val macdLine: List<Double?>,
    val macdSignal: List<Double?>,
    val macdHistogram: List<Double?>,
    val latestSma20: Double?,
    val latestSma50: Double?,
    val latestSma200: Double?,
    val latestEma12: Double?,
    val latestEma26: Double?,
    val latestRsi14: Double?,
    val latestMacd: Double?,
    val latestMacdSignal: Double?,
    val latestMacdHistogram: Double?,
    val latestAtr14: Double?,
    val annualizedVolatilityPct: Double?,
    val volumeRatio: Double?,
    val pivotPoint: Double?,
    val support1: Double?,
    val support2: Double?,
    val resistance1: Double?,
    val resistance2: Double?,
    val compositeScore: Int, // -100 to +100
    val verdict: SignalVerdict,
    val indicatorBreakdown: List<IndicatorSignal>
)

object TechnicalAnalyzer {

    fun analyze(quote: MarketQuote): TechnicalAnalysisResult {
        val candles = quote.candles
        val closes = candles.map { it.close }

        val sma20 = computeSma(closes, 20)
        val sma50 = computeSma(closes, 50)
        val sma200 = computeSma(closes, 200)
        val ema12 = computeEma(closes, 12)
        val ema26 = computeEma(closes, 26)
        val rsi14 = computeRsi(closes, 14)

        val (bbUpper, bbLower) = computeBollinger(closes, sma20, 20, 2.0)
        val (macdLine, macdSig, macdHist) = computeMacd(ema12, ema26, 9)
        val atr14 = computeAtr(candles, 14)
        val annVol = computeAnnualizedVolatility(closes, 20)
        val volRatio = computeVolumeRatio(candles, 20)
        val (pivot, s1, s2, r1, r2) = computePivotLevels(candles)

        val price = quote.price
        val lSma20 = sma20.lastOrNull()
        val lSma50 = sma50.lastOrNull()
        val lSma200 = sma200.lastOrNull()
        val lEma12 = ema12.lastOrNull()
        val lEma26 = ema26.lastOrNull()
        val lRsi = rsi14.lastOrNull()
        val lMacd = macdLine.lastOrNull()
        val lMacdSig = macdSig.lastOrNull()
        val lMacdHist = macdHist.lastOrNull()
        val lBbUpper = bbUpper.lastOrNull()
        val lBbLower = bbLower.lastOrNull()

        val signals = mutableListOf<IndicatorSignal>()

        if (lSma20 != null) {
            val bias = if (price > lSma20) 1 else -1
            signals.add(
                IndicatorSignal(
                    name = "SMA (20)",
                    valueText = formatCad(lSma20),
                    interpretation = if (bias > 0) "Price above 20-day mean" else "Price below 20-day mean",
                    bias = bias
                )
            )
        }
        if (lSma50 != null) {
            val bias = if (price > lSma50) 1 else -1
            signals.add(
                IndicatorSignal(
                    name = "SMA (50)",
                    valueText = formatCad(lSma50),
                    interpretation = if (bias > 0) "Medium-term uptrend intact" else "Below 50-day trendline",
                    bias = bias
                )
            )
        }
        if (lSma50 != null && lSma200 != null) {
            val golden = lSma50 > lSma200
            signals.add(
                IndicatorSignal(
                    name = "50 / 200 Cross",
                    valueText = "${formatCad(lSma50)} / ${formatCad(lSma200)}",
                    interpretation = if (golden) "Golden Cross regime" else "Death Cross regime",
                    bias = if (golden) 1 else -1
                )
            )
        }
        if (lRsi != null) {
            val bias = when {
                lRsi < 35.0 -> 1
                lRsi > 70.0 -> -1
                else -> 0
            }
            val interp = when {
                lRsi < 30.0 -> "Oversold bounce zone"
                lRsi < 40.0 -> "Approaching oversold"
                lRsi > 70.0 -> "Overbought momentum"
                else -> "Balanced momentum"
            }
            signals.add(
                IndicatorSignal(
                    name = "RSI (14)",
                    valueText = String.format("%.1f", lRsi),
                    interpretation = interp,
                    bias = bias
                )
            )
        }
        if (lMacd != null && lMacdSig != null) {
            val bias = if (lMacd > lMacdSig) 1 else -1
            signals.add(
                IndicatorSignal(
                    name = "MACD (12,26,9)",
                    valueText = String.format("%+.2f", lMacd - lMacdSig),
                    interpretation = if (bias > 0) "Bullish MACD crossover" else "Bearish MACD divergence",
                    bias = bias
                )
            )
        }
        if (lBbUpper != null && lBbLower != null && lBbUpper > lBbLower) {
            val pctB = ((price - lBbLower) / (lBbUpper - lBbLower)) * 100.0
            val bias = when {
                pctB < 20.0 -> 1
                pctB > 85.0 -> -1
                else -> 0
            }
            signals.add(
                IndicatorSignal(
                    name = "Bollinger %B",
                    valueText = String.format("%.0f%%", pctB),
                    interpretation = when {
                        pctB < 20.0 -> "Near lower volatility band"
                        pctB > 85.0 -> "Stretched near upper band"
                        else -> "Within normal 2σ channel"
                    },
                    bias = bias
                )
            )
        }

        val rawScore = if (signals.isNotEmpty()) {
            ((signals.sumOf { it.bias }.toDouble() / signals.size) * 100.0).toInt().coerceIn(-100, 100)
        } else {
            0
        }

        val verdict = when {
            rawScore >= 55 -> SignalVerdict.STRONG_BUY
            rawScore >= 20 -> SignalVerdict.BUY
            rawScore <= -55 -> SignalVerdict.STRONG_SELL
            rawScore <= -20 -> SignalVerdict.SELL
            else -> SignalVerdict.NEUTRAL
        }

        return TechnicalAnalysisResult(
            symbol = quote.symbol,
            currentPrice = price,
            sma20Series = sma20,
            sma50Series = sma50,
            sma200Series = sma200,
            bollingerUpper = bbUpper,
            bollingerLower = bbLower,
            rsiSeries = rsi14,
            macdLine = macdLine,
            macdSignal = macdSig,
            macdHistogram = macdHist,
            latestSma20 = lSma20,
            latestSma50 = lSma50,
            latestSma200 = lSma200,
            latestEma12 = lEma12,
            latestEma26 = lEma26,
            latestRsi14 = lRsi,
            latestMacd = lMacd,
            latestMacdSignal = lMacdSig,
            latestMacdHistogram = lMacdHist,
            latestAtr14 = atr14,
            annualizedVolatilityPct = annVol,
            volumeRatio = volRatio,
            pivotPoint = pivot,
            support1 = s1,
            support2 = s2,
            resistance1 = r1,
            resistance2 = r2,
            compositeScore = rawScore,
            verdict = verdict,
            indicatorBreakdown = signals
        )
    }

    fun computeSma(values: List<Double>, period: Int): List<Double?> {
        if (values.isEmpty() || period <= 0) return emptyList()
        val result = MutableList<Double?>(values.size) { null }
        var rollingSum = 0.0
        for (i in values.indices) {
            rollingSum += values[i]
            if (i >= period) {
                rollingSum -= values[i - period]
            }
            if (i >= period - 1) {
                result[i] = rollingSum / period
            }
        }
        return result
    }

    fun computeEma(values: List<Double>, period: Int): List<Double?> {
        if (values.size < period || period <= 0) return List(values.size) { null }
        val result = MutableList<Double?>(values.size) { null }
        val multiplier = 2.0 / (period + 1)
        var ema = values.take(period).average()
        result[period - 1] = ema
        for (i in period until values.size) {
            ema = (values[i] - ema) * multiplier + ema
            result[i] = ema
        }
        return result
    }

    fun computeRsi(values: List<Double>, period: Int = 14): List<Double?> {
        if (values.size <= period) return List(values.size) { null }
        val result = MutableList<Double?>(values.size) { null }
        var gainSum = 0.0
        var lossSum = 0.0
        for (i in 1..period) {
            val diff = values[i] - values[i - 1]
            if (diff >= 0) gainSum += diff else lossSum += abs(diff)
        }
        var avgGain = gainSum / period
        var avgLoss = lossSum / period
        result[period] = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))

        for (i in period + 1 until values.size) {
            val diff = values[i] - values[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) abs(diff) else 0.0
            avgGain = ((avgGain * (period - 1)) + gain) / period
            avgLoss = ((avgLoss * (period - 1)) + loss) / period
            result[i] = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
        }
        return result
    }

    private fun computeBollinger(
        values: List<Double>,
        sma: List<Double?>,
        period: Int,
        stdDevMult: Double
    ): Pair<List<Double?>, List<Double?>> {
        val upper = MutableList<Double?>(values.size) { null }
        val lower = MutableList<Double?>(values.size) { null }
        for (i in values.indices) {
            val mean = sma.getOrNull(i) ?: continue
            if (i < period - 1) continue
            val window = values.subList(i - period + 1, i + 1)
            val variance = window.sumOf { (it - mean).pow(2) } / period
            val sd = sqrt(variance)
            upper[i] = mean + stdDevMult * sd
            lower[i] = mean - stdDevMult * sd
        }
        return upper to lower
    }

    private fun computeMacd(
        ema12: List<Double?>,
        ema26: List<Double?>,
        signalPeriod: Int
    ): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val n = ema12.size
        val macd = MutableList<Double?>(n) { null }
        val validIndices = mutableListOf<Int>()
        val validValues = mutableListOf<Double>()
        for (i in 0 until n) {
            val e12 = ema12.getOrNull(i)
            val e26 = ema26.getOrNull(i)
            if (e12 != null && e26 != null) {
                val m = e12 - e26
                macd[i] = m
                validIndices.add(i)
                validValues.add(m)
            }
        }
        val signalSub = computeEma(validValues, signalPeriod)
        val signal = MutableList<Double?>(n) { null }
        val hist = MutableList<Double?>(n) { null }
        validIndices.forEachIndexed { idx, originalIdx ->
            val s = signalSub.getOrNull(idx)
            signal[originalIdx] = s
            val m = macd[originalIdx]
            if (m != null && s != null) {
                hist[originalIdx] = m - s
            }
        }
        return Triple(macd, signal, hist)
    }

    private fun computeAtr(candles: List<OhlcvCandle>, period: Int): Double? {
        if (candles.size <= period) return null
        val trs = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val curr = candles[i]
            val prevClose = candles[i - 1].close
            val tr = max(
                curr.high - curr.low,
                max(abs(curr.high - prevClose), abs(curr.low - prevClose))
            )
            trs.add(tr)
        }
        if (trs.size < period) return null
        var atr = trs.take(period).average()
        for (i in period until trs.size) {
            atr = ((atr * (period - 1)) + trs[i]) / period
        }
        return atr
    }

    private fun computeAnnualizedVolatility(closes: List<Double>, lookback: Int): Double? {
        if (closes.size <= lookback) return null
        val slice = closes.takeLast(lookback + 1)
        val returns = mutableListOf<Double>()
        for (i in 1 until slice.size) {
            if (slice[i - 1] > 0 && slice[i] > 0) {
                returns.add(kotlin.math.ln(slice[i] / slice[i - 1]))
            }
        }
        if (returns.size < 2) return null
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean).pow(2) } / (returns.size - 1)
        return sqrt(variance) * sqrt(252.0) * 100.0
    }

    private fun computeVolumeRatio(candles: List<OhlcvCandle>, lookback: Int): Double? {
        if (candles.size < lookback + 1) return null
        val avgVol = candles.dropLast(1).takeLast(lookback).map { it.volume.toDouble() }.average()
        if (avgVol <= 0.0) return null
        return candles.last().volume / avgVol
    }

    private fun computePivotLevels(candles: List<OhlcvCandle>): List<Double?> {
        if (candles.size < 2) return listOf(null, null, null, null, null)
        val prev = candles[candles.size - 2]
        val p = (prev.high + prev.low + prev.close) / 3.0
        val r1 = 2.0 * p - prev.low
        val s1 = 2.0 * p - prev.high
        val r2 = p + (prev.high - prev.low)
        val s2 = p - (prev.high - prev.low)
        return listOf(p, s1, s2, r1, r2)
    }

    private fun formatCad(value: Double): String = String.format("$%.2f", value)
}
