package com.example.data.remote

data class OhlcvCandle(
    val timestampSec: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class MarketQuote(
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
    val price: Double,
    val previousClose: Double,
    val change: Double,
    val changePercent: Double,
    val dayHigh: Double,
    val dayLow: Double,
    val fiftyTwoWeekHigh: Double,
    val fiftyTwoWeekLow: Double,
    val volume: Long,
    val marketState: String,
    val candles: List<OhlcvCandle> = emptyList(),
    val sector: String = ""
)

data class FundamentalSnapshot(
    val symbol: String,
    val sector: String? = null,
    val industry: String? = null,
    val summary: String? = null,
    val website: String? = null,
    val employees: Long? = null,
    val marketCap: Double? = null,
    val trailingPE: Double? = null,
    val forwardPE: Double? = null,
    val pegRatio: Double? = null,
    val priceToBook: Double? = null,
    val priceToSales: Double? = null,
    val evToEbitda: Double? = null,
    val dividendYield: Double? = null,
    val dividendRate: Double? = null,
    val payoutRatio: Double? = null,
    val beta: Double? = null,
    val grossMargin: Double? = null,
    val operatingMargin: Double? = null,
    val profitMargin: Double? = null,
    val returnOnEquity: Double? = null,
    val returnOnAssets: Double? = null,
    val debtToEquity: Double? = null,
    val currentRatio: Double? = null,
    val quickRatio: Double? = null,
    val freeCashFlow: Double? = null,
    val operatingCashFlow: Double? = null,
    val totalRevenue: Double? = null,
    val revenueGrowth: Double? = null,
    val earningsGrowth: Double? = null,
    val targetMeanPrice: Double? = null,
    val targetHighPrice: Double? = null,
    val targetLowPrice: Double? = null,
    val recommendationKey: String? = null,
    val recommendationMean: Double? = null,
    val analystCount: Int? = null
)

data class MarketSearchItem(
    val symbol: String,
    val shortName: String,
    val exchange: String,
    val quoteType: String,
    val sector: String
)

data class MarketNewsItem(
    val title: String,
    val publisher: String,
    val link: String,
    val publishedEpochSec: Long
)

data class CanadianMacroSnapshot(
    val cadUsdRate: Double?,
    val bocOvernightRate: Double?,
    val observationDate: String?
)

enum class ChartRange(
    val label: String,
    val yahooRange: String,
    val yahooInterval: String
) {
    FIVE_DAYS("5D", "5d", "15m"),
    ONE_MONTH("1M", "1mo", "1d"),
    THREE_MONTHS("3M", "3mo", "1d"),
    SIX_MONTHS("6M", "6mo", "1d"),
    ONE_YEAR("1Y", "1y", "1d"),
    FIVE_YEARS("5Y", "5y", "1wk")
}
