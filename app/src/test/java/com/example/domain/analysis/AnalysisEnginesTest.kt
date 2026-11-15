package com.example.domain.analysis

import com.example.data.remote.CanadianMacroSnapshot
import com.example.data.remote.FundamentalSnapshot
import com.example.data.remote.MarketQuote
import com.example.data.remote.OhlcvCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisEnginesTest {

    private fun sampleUptrendQuote(): MarketQuote {
        val candles = (0 until 60).map { day ->
            val base = 100.0 + day * 0.55 + kotlin.math.sin(day * 0.3) * 1.2
            OhlcvCandle(
                timestampSec = 1700000000L + day * 86400L,
                open = base - 0.3,
                high = base + 0.8,
                low = base - 0.7,
                close = base,
                volume = 1_200_000L + (day * 5_000L)
            )
        }
        val last = candles.last().close
        val prev = candles[candles.size - 2].close
        return MarketQuote(
            symbol = "RY.TO",
            name = "Royal Bank of Canada",
            exchange = "TSX",
            currency = "CAD",
            price = last,
            previousClose = prev,
            change = last - prev,
            changePercent = ((last - prev) / prev) * 100.0,
            dayHigh = candles.last().high,
            dayLow = candles.last().low,
            fiftyTwoWeekHigh = last + 2.0,
            fiftyTwoWeekLow = 95.0,
            volume = candles.last().volume,
            marketState = "REGULAR",
            candles = candles,
            sector = "Financials"
        )
    }

    @Test
    fun technicalAnalyzer_computesSmaRsiMacdAndSignals() {
        val quote = sampleUptrendQuote()
        val result = TechnicalAnalyzer.analyze(quote)

        assertNotNull(result.latestSma20)
        assertNotNull(result.latestSma50)
        assertNotNull(result.latestRsi14)
        assertNotNull(result.latestMacd)
        assertTrue(result.latestRsi14!! in 0.0..100.0)
        assertTrue(result.latestSma20!! > result.latestSma50!!)
        assertTrue(result.compositeScore in -100..100)
        assertTrue(result.indicatorBreakdown.isNotEmpty())
    }

    @Test
    fun forecastEngine_projectsConesAndBacktest() {
        val quote = sampleUptrendQuote()
        val forecast = ForecastEngine.generateForecast(quote, ForecastHorizon.THIRTY_DAYS)

        assertEquals(30, forecast.forecastPoints.size)
        assertTrue(forecast.projectedPrice > quote.price)
        assertTrue(forecast.bullTarget95 > forecast.projectedPrice)
        assertTrue(forecast.bearTarget95 < forecast.projectedPrice)
        assertTrue(forecast.backtestMapePct >= 0.0)
    }

    @Test
    fun fundamentalAnalyzer_scoresStrongCanadianBank() {
        val quote = sampleUptrendQuote()
        val fundamentals = FundamentalSnapshot(
            symbol = "RY.TO",
            trailingPE = 13.4,
            forwardPE = 12.1,
            priceToBook = 1.75,
            pegRatio = 1.1,
            dividendYield = 0.041,
            returnOnEquity = 0.16,
            operatingMargin = 0.32,
            profitMargin = 0.25,
            currentRatio = 1.3,
            debtToEquity = 45.0,
            freeCashFlow = 12_000_000_000.0,
            revenueGrowth = 0.08,
            targetMeanPrice = quote.price * 1.15
        )
        val macro = CanadianMacroSnapshot(cadUsdRate = 0.725, bocOvernightRate = 3.75, observationDate = "2026-11-15")
        val eval = FundamentalAnalyzer.evaluate(quote, fundamentals, macro)

        assertTrue(eval.healthScore >= 70)
        assertEquals(4, eval.pillars.size)
        assertTrue(eval.macroNote.contains("BoC Policy Rate"))
    }
}
