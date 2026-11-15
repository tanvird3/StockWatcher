package com.example.domain.analysis

import com.example.data.remote.CanadianMacroSnapshot
import com.example.data.remote.FundamentalSnapshot
import com.example.data.remote.MarketQuote

data class FundamentalPillar(
    val name: String,
    val score: Int, // 0..100
    val summary: String
)

data class FundamentalAnalysisResult(
    val symbol: String,
    val healthScore: Int, // 0..100
    val ratingGrade: String,
    val valuationLabel: String,
    val impliedUpsidePct: Double?,
    val pillars: List<FundamentalPillar>,
    val keyHighlights: List<String>,
    val macroNote: String
)

object FundamentalAnalyzer {

    fun evaluate(
        quote: MarketQuote,
        fundamentals: FundamentalSnapshot?,
        macro: CanadianMacroSnapshot?
    ): FundamentalAnalysisResult {
        if (fundamentals == null) {
            return FundamentalAnalysisResult(
                symbol = quote.symbol,
                healthScore = 50,
                ratingGrade = "N/A",
                valuationLabel = "Price-Action Only",
                impliedUpsidePct = null,
                pillars = emptyList(),
                keyHighlights = listOf("Detailed financial statements unavailable for this security type (e.g., index or ETF)."),
                macroNote = buildMacroNote(macro)
            )
        }

        // 1. Valuation Pillar
        var valScore = 55
        fundamentals.trailingPE?.let { pe ->
            valScore += when {
                pe in 5.0..15.0 -> 25
                pe in 15.0..24.0 -> 12
                pe > 40.0 || pe < 0.0 -> -18
                else -> 0
            }
        }
        fundamentals.priceToBook?.let { pb ->
            valScore += when {
                pb in 0.5..2.0 -> 12
                pb > 6.0 -> -10
                else -> 4
            }
        }
        fundamentals.pegRatio?.let { peg ->
            valScore += when {
                peg in 0.3..1.3 -> 10
                peg > 2.5 -> -10
                else -> 0
            }
        }
        valScore = valScore.coerceIn(10, 100)

        // 2. Profitability Pillar
        var profScore = 50
        fundamentals.returnOnEquity?.let { roe ->
            profScore += when {
                roe >= 0.18 -> 25
                roe >= 0.10 -> 12
                roe < 0.0 -> -20
                else -> 0
            }
        }
        fundamentals.operatingMargin?.let { op ->
            profScore += when {
                op >= 0.22 -> 18
                op >= 0.10 -> 8
                op < 0.0 -> -15
                else -> 0
            }
        }
        fundamentals.profitMargin?.let { pm ->
            profScore += if (pm >= 0.12) 7 else if (pm < 0.0) -10 else 0
        }
        profScore = profScore.coerceIn(10, 100)

        // 3. Financial Health Pillar
        var healthPillar = 55
        fundamentals.currentRatio?.let { cr ->
            healthPillar += when {
                cr >= 1.5 -> 18
                cr >= 1.0 -> 8
                else -> -15
            }
        }
        fundamentals.debtToEquity?.let { dte ->
            healthPillar += when {
                dte < 60.0 -> 15
                dte > 180.0 -> -15
                else -> 0
            }
        }
        fundamentals.freeCashFlow?.let { fcf ->
            healthPillar += if (fcf > 0.0) 12 else -15
        }
        healthPillar = healthPillar.coerceIn(10, 100)

        // 4. Growth & Canadian Dividend Yield Pillar
        var growthScore = 50
        fundamentals.revenueGrowth?.let { rg ->
            growthScore += when {
                rg >= 0.15 -> 22
                rg >= 0.05 -> 10
                rg < -0.05 -> -15
                else -> 0
            }
        }
        fundamentals.dividendYield?.let { dy ->
            growthScore += when {
                dy >= 0.04 -> 18
                dy >= 0.02 -> 10
                else -> 0
            }
        }
        fundamentals.payoutRatio?.let { pr ->
            if (pr > 0.95) growthScore -= 10
        }
        growthScore = growthScore.coerceIn(10, 100)

        val overall = ((valScore * 0.28) + (profScore * 0.28) + (healthPillar * 0.22) + (growthScore * 0.22)).toInt().coerceIn(0, 100)
        val grade = when {
            overall >= 85 -> "A+"
            overall >= 75 -> "A"
            overall >= 65 -> "B+"
            overall >= 55 -> "B"
            overall >= 45 -> "C"
            else -> "D"
        }

        val upsidePct = fundamentals.targetMeanPrice?.let { target ->
            if (quote.price > 0.0) ((target - quote.price) / quote.price) * 100.0 else null
        }

        val valuationLabel = when {
            upsidePct != null && upsidePct >= 12.0 && valScore >= 65 -> "Undervalued vs Targets"
            upsidePct != null && upsidePct <= -8.0 -> "Richly Valued"
            valScore >= 72 -> "Attractive Multiple"
            valScore <= 38 -> "Premium Multiple"
            else -> "Fairly Valued"
        }

        val highlights = mutableListOf<String>()
        fundamentals.dividendYield?.let { dy ->
            if (dy > 0.0) {
                highlights.add(String.format("Annualized TSX dividend yield of %.2f%%.", dy * 100.0))
            }
        }
        fundamentals.trailingPE?.let { pe ->
            highlights.add(String.format("Trading at %.1fx trailing earnings.", pe))
        }
        fundamentals.returnOnEquity?.let { roe ->
            highlights.add(String.format("Return on equity stands at %.1f%%.", roe * 100.0))
        }
        upsidePct?.let { up ->
            highlights.add(
                String.format(
                    "Analyst consensus target implies %+.1f%% from current CAD %.2f.",
                    up,
                    quote.price
                )
            )
        }
        if (highlights.isEmpty()) {
            highlights.add("Fundamental multiples loaded from live exchange filings.")
        }

        val pillars = listOf(
            FundamentalPillar("Valuation", valScore, valuationLabel),
            FundamentalPillar(
                "Profitability",
                profScore,
                fundamentals.returnOnEquity?.let { String.format("ROE %.1f%%", it * 100) } ?: "Operating Efficiency"
            ),
            FundamentalPillar(
                "Balance Sheet",
                healthPillar,
                fundamentals.currentRatio?.let { String.format("Current %.2fx", it) } ?: "Liquidity & FCF"
            ),
            FundamentalPillar(
                "Growth & Yield",
                growthScore,
                fundamentals.dividendYield?.let { String.format("Yield %.2f%%", it * 100) } ?: "Revenue Expansion"
            )
        )

        return FundamentalAnalysisResult(
            symbol = quote.symbol,
            healthScore = overall,
            ratingGrade = grade,
            valuationLabel = valuationLabel,
            impliedUpsidePct = upsidePct,
            pillars = pillars,
            keyHighlights = highlights,
            macroNote = buildMacroNote(macro)
        )
    }

    private fun buildMacroNote(macro: CanadianMacroSnapshot?): String {
        if (macro == null || (macro.cadUsdRate == null && macro.bocOvernightRate == null)) {
            return "Canadian TSX macro rates benchmarked in CAD."
        }
        val parts = mutableListOf<String>()
        macro.bocOvernightRate?.let { parts.add(String.format("BoC Policy Rate: %.2f%%", it)) }
        macro.cadUsdRate?.let { parts.add(String.format("CAD/USD: %.4f", it)) }
        return parts.joinToString("  •  ")
    }
}
