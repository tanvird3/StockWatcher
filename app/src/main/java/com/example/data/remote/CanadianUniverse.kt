package com.example.data.remote

data class CanadianTickerRef(
    val symbol: String,
    val displayName: String,
    val exchange: String,
    val sector: String
)

object CanadianUniverse {

    const val TSX_COMPOSITE_INDEX = "^GSPTSE"

    val sectors = listOf(
        "All",
        "Financials",
        "Energy",
        "Technology",
        "Materials & Gold",
        "Industrials",
        "Consumer",
        "Telecom & Utilities",
        "ETFs"
    )

    val featuredTickers: List<CanadianTickerRef> = listOf(
        CanadianTickerRef("RY.TO", "Royal Bank of Canada", "TSX", "Financials"),
        CanadianTickerRef("TD.TO", "Toronto-Dominion Bank", "TSX", "Financials"),
        CanadianTickerRef("SHOP.TO", "Shopify Inc.", "TSX", "Technology"),
        CanadianTickerRef("ENB.TO", "Enbridge Inc.", "TSX", "Energy"),
        CanadianTickerRef("CNQ.TO", "Canadian Natural Resources", "TSX", "Energy"),
        CanadianTickerRef("CSU.TO", "Constellation Software", "TSX", "Technology"),
        CanadianTickerRef("BN.TO", "Brookfield Corp.", "TSX", "Financials"),
        CanadianTickerRef("CP.TO", "Canadian Pacific Kansas City", "TSX", "Industrials"),
        CanadianTickerRef("CNR.TO", "Canadian National Railway", "TSX", "Industrials"),
        CanadianTickerRef("ATD.TO", "Alimentation Couche-Tard", "TSX", "Consumer"),
        CanadianTickerRef("BMO.TO", "Bank of Montreal", "TSX", "Financials"),
        CanadianTickerRef("BNS.TO", "Bank of Nova Scotia", "TSX", "Financials"),
        CanadianTickerRef("MFC.TO", "Manulife Financial", "TSX", "Financials"),
        CanadianTickerRef("ABX.TO", "Barrick Gold Corp.", "TSX", "Materials & Gold"),
        CanadianTickerRef("AEM.TO", "Agnico Eagle Mines", "TSX", "Materials & Gold"),
        CanadianTickerRef("NTR.TO", "Nutrien Ltd.", "TSX", "Materials & Gold"),
        CanadianTickerRef("BCE.TO", "BCE Inc.", "TSX", "Telecom & Utilities"),
        CanadianTickerRef("FTS.TO", "Fortis Inc.", "TSX", "Telecom & Utilities"),
        CanadianTickerRef("DOL.TO", "Dollarama Inc.", "TSX", "Consumer"),
        CanadianTickerRef("SU.TO", "Suncor Energy Inc.", "TSX", "Energy"),
        CanadianTickerRef("XIU.TO", "iShares S&P/TSX 60 ETF", "TSX", "ETFs"),
        CanadianTickerRef("XEQT.TO", "iShares Core Equity ETF", "TSX", "ETFs"),
        CanadianTickerRef("HIVE.V", "HIVE Digital Technologies", "TSXV", "Technology"),
        CanadianTickerRef("NGD.TO", "New Gold Inc.", "TSX", "Materials & Gold")
    )

    fun sectorFor(symbol: String): String =
        featuredTickers.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }?.sector ?: "TSX Equity"

    fun exchangeLabel(rawExchange: String?, symbol: String): String {
        val upper = rawExchange.orEmpty().uppercase()
        return when {
            upper.contains("VAN") || symbol.endsWith(".V", ignoreCase = true) -> "TSXV"
            upper.contains("TOR") || symbol.endsWith(".TO", ignoreCase = true) -> "TSX"
            symbol == TSX_COMPOSITE_INDEX -> "TSX Index"
            else -> upper.ifBlank { "TSX" }
        }
    }
}
