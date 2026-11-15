package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class YahooMarketClient {

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val list = cookieStore.getOrPut(host) { mutableListOf() }
            synchronized(list) {
                cookies.forEach { incoming ->
                    list.removeAll { it.name == incoming.name }
                    list.add(incoming)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val result = mutableListOf<Cookie>()
            cookieStore.forEach { (host, cookies) ->
                if (url.host == host || url.host.endsWith(".$host") || host.endsWith(".yahoo.com") && url.host.endsWith(".yahoo.com")) {
                    synchronized(cookies) {
                        cookies.removeAll { it.expiresAt < now }
                        result.addAll(cookies)
                    }
                }
            }
            return result
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "en-CA,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    private val crumbMutex = Mutex()
    @Volatile
    private var cachedCrumb: String? = null

    suspend fun fetchChartQuote(
        symbol: String,
        range: ChartRange = ChartRange.ONE_YEAR
    ): Result<MarketQuote> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(symbol.trim(), "UTF-8")
        val urls = listOf(
            "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=${range.yahooInterval}&range=${range.yahooRange}&includePrePost=false",
            "https://query2.finance.yahoo.com/v8/finance/chart/$encoded?interval=${range.yahooInterval}&range=${range.yahooRange}&includePrePost=false"
        )
        var lastError: Throwable? = null
        for (url in urls) {
            try {
                val body = executeGet(url)
                val parsed = parseChartResponse(symbol, body)
                return@withContext Result.success(parsed)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        Result.failure(lastError ?: IllegalStateException("Unable to fetch quote for $symbol"))
    }

    suspend fun fetchBatchQuotes(
        symbols: List<String>,
        range: ChartRange = ChartRange.THREE_MONTHS
    ): List<MarketQuote> = coroutineScope {
        symbols.distinct().map { symbol ->
            async {
                fetchChartQuote(symbol, range).getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun fetchFundamentals(symbol: String): Result<FundamentalSnapshot> = withContext(Dispatchers.IO) {
        try {
            val crumb = ensureCrumb()
            val encoded = URLEncoder.encode(symbol.trim(), "UTF-8")
            val modules = "summaryDetail,defaultKeyStatistics,financialData,assetProfile,price"
            val crumbParam = if (!crumb.isNullOrBlank()) "&crumb=${URLEncoder.encode(crumb, "UTF-8")}" else ""
            val url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/$encoded?modules=$modules$crumbParam"
            val body = try {
                executeGet(url)
            } catch (e: HttpException) {
                if (e.code == 401 || e.code == 403) {
                    cachedCrumb = null
                    val freshCrumb = ensureCrumb(forceRefresh = true)
                    val retryParam = if (!freshCrumb.isNullOrBlank()) "&crumb=${URLEncoder.encode(freshCrumb, "UTF-8")}" else ""
                    executeGet("https://query2.finance.yahoo.com/v10/finance/quoteSummary/$encoded?modules=$modules$retryParam")
                } else {
                    throw e
                }
            }
            Result.success(parseQuoteSummary(symbol, body))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun searchCanadianEquities(query: String): Result<Pair<List<MarketSearchItem>, List<MarketNewsItem>>> =
        withContext(Dispatchers.IO) {
            try {
                val cleanQuery = query.trim().ifBlank { "TSX" }
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val url = "https://query1.finance.yahoo.com/v1/finance/search?q=$encoded&quotesCount=20&newsCount=8&region=CA&lang=en-CA"
                val body = executeGet(url)
                Result.success(parseSearchResponse(body))
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    suspend fun fetchCanadianMacro(): CanadianMacroSnapshot = withContext(Dispatchers.IO) {
        try {
            val body = executeGet("https://www.bankofcanada.ca/valet/observations/FXCADUSD,V39079/json?recent=3")
            val root = JSONObject(body)
            val obs = root.optJSONArray("observations")
            var cadUsd: Double? = null
            var bocRate: Double? = null
            var dateStr: String? = null
            if (obs != null) {
                for (i in obs.length() - 1 downTo 0) {
                    val row = obs.optJSONObject(i) ?: continue
                    val d = row.optString("d", "")
                    val fxObj = row.optJSONObject("FXCADUSD")
                    val rateObj = row.optJSONObject("V39079")
                    if (cadUsd == null && fxObj != null) {
                        cadUsd = fxObj.optString("v").toDoubleOrNull()
                        if (dateStr == null && d.isNotBlank()) dateStr = d
                    }
                    if (bocRate == null && rateObj != null) {
                        bocRate = rateObj.optString("v").toDoubleOrNull()
                        if (dateStr == null && d.isNotBlank()) dateStr = d
                    }
                }
            }
            CanadianMacroSnapshot(cadUsdRate = cadUsd, bocOvernightRate = bocRate, observationDate = dateStr)
        } catch (_: Throwable) {
            CanadianMacroSnapshot(null, null, null)
        }
    }

    private suspend fun ensureCrumb(forceRefresh: Boolean = false): String? {
        if (!forceRefresh && !cachedCrumb.isNullOrBlank()) return cachedCrumb
        return crumbMutex.withLock {
            if (!forceRefresh && !cachedCrumb.isNullOrBlank()) return@withLock cachedCrumb
            try {
                val initReq = Request.Builder().url("https://fc.yahoo.com").get().build()
                client.newCall(initReq).execute().use { /* populates cookies */ }
            } catch (_: Throwable) {
                // ignore redirect/status on fc.yahoo.com
            }
            try {
                val crumbText = executeGet("https://query1.finance.yahoo.com/v1/test/getcrumb").trim()
                if (crumbText.isNotBlank() && !crumbText.startsWith("{")) {
                    cachedCrumb = crumbText
                }
            } catch (_: Throwable) {
                // fallback without crumb if unavailable
            }
            cachedCrumb
        }
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpException(response.code, "HTTP ${response.code} for $url")
            }
            return response.body?.string() ?: throw IllegalStateException("Empty body for $url")
        }
    }

    private fun parseChartResponse(requestedSymbol: String, jsonText: String): MarketQuote {
        val root = JSONObject(jsonText)
        val chart = root.getJSONObject("chart")
        val error = chart.optJSONObject("error")
        if (error != null) {
            throw IllegalStateException(error.optString("description", "Yahoo chart error"))
        }
        val resultArr = chart.getJSONArray("result")
        if (resultArr.length() == 0) {
            throw IllegalStateException("No chart data for $requestedSymbol")
        }
        val result = resultArr.getJSONObject(0)
        val meta = result.getJSONObject("meta")

        val symbol = meta.optString("symbol", requestedSymbol)
        val name = meta.optString("longName")
            .ifBlank { meta.optString("shortName") }
            .ifBlank {
                CanadianUniverse.featuredTickers.firstOrNull { it.symbol.equals(symbol, true) }?.displayName ?: symbol
            }
        val exchange = CanadianUniverse.exchangeLabel(meta.optString("exchangeName"), symbol)
        val currency = meta.optString("currency", "CAD").ifBlank { "CAD" }
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        val prevClose = meta.optDouble("chartPreviousClose", Double.NaN).let {
            if (it.isNaN()) meta.optDouble("previousClose", price) else it
        }

        val timestamps = result.optJSONArray("timestamp")
        val quoteObj = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
        val opens = quoteObj?.optJSONArray("open")
        val highs = quoteObj?.optJSONArray("high")
        val lows = quoteObj?.optJSONArray("low")
        val closes = quoteObj?.optJSONArray("close")
        val volumes = quoteObj?.optJSONArray("volume")

        val candles = mutableListOf<OhlcvCandle>()
        if (timestamps != null && closes != null) {
            for (i in 0 until timestamps.length()) {
                val closeVal = closes.optDouble(i, Double.NaN)
                if (closeVal.isNaN()) continue
                val ts = timestamps.optLong(i, 0L)
                val openVal = opens?.optDouble(i, closeVal)?.takeUnless { it.isNaN() } ?: closeVal
                val highVal = highs?.optDouble(i, closeVal)?.takeUnless { it.isNaN() } ?: maxOf(openVal, closeVal)
                val lowVal = lows?.optDouble(i, closeVal)?.takeUnless { it.isNaN() } ?: minOf(openVal, closeVal)
                val volVal = volumes?.optLong(i, 0L) ?: 0L
                candles.add(
                    OhlcvCandle(
                        timestampSec = ts,
                        open = openVal,
                        high = highVal,
                        low = lowVal,
                        close = closeVal,
                        volume = volVal
                    )
                )
            }
        }

        val resolvedPrice = if (!price.isNaN()) price else candles.lastOrNull()?.close ?: 0.0
        val resolvedPrevClose = if (!prevClose.isNaN() && prevClose > 0.0) {
            prevClose
        } else if (candles.size >= 2) {
            candles[candles.size - 2].close
        } else {
            resolvedPrice
        }
        val change = resolvedPrice - resolvedPrevClose
        val changePct = if (resolvedPrevClose > 0.0) (change / resolvedPrevClose) * 100.0 else 0.0

        val dayHigh = meta.optDouble("regularMarketDayHigh", Double.NaN).let {
            if (it.isNaN()) candles.lastOrNull()?.high ?: resolvedPrice else it
        }
        val dayLow = meta.optDouble("regularMarketDayLow", Double.NaN).let {
            if (it.isNaN()) candles.lastOrNull()?.low ?: resolvedPrice else it
        }
        val fiftyTwoHigh = meta.optDouble("fiftyTwoWeekHigh", Double.NaN).let {
            if (it.isNaN()) candles.maxOfOrNull { c -> c.high } ?: resolvedPrice else it
        }
        val fiftyTwoLow = meta.optDouble("fiftyTwoWeekLow", Double.NaN).let {
            if (it.isNaN()) candles.minOfOrNull { c -> c.low } ?: resolvedPrice else it
        }
        val volume = meta.optLong("regularMarketVolume", candles.lastOrNull()?.volume ?: 0L)
        val marketState = meta.optString("marketState", "REGULAR")

        return MarketQuote(
            symbol = symbol,
            name = name,
            exchange = exchange,
            currency = currency,
            price = resolvedPrice,
            previousClose = resolvedPrevClose,
            change = change,
            changePercent = changePct,
            dayHigh = dayHigh,
            dayLow = dayLow,
            fiftyTwoWeekHigh = fiftyTwoHigh,
            fiftyTwoWeekLow = fiftyTwoLow,
            volume = volume,
            marketState = marketState,
            candles = candles,
            sector = CanadianUniverse.sectorFor(symbol)
        )
    }

    private fun parseQuoteSummary(symbol: String, jsonText: String): FundamentalSnapshot {
        val root = JSONObject(jsonText)
        val qs = root.getJSONObject("quoteSummary")
        val error = qs.optJSONObject("error")
        if (error != null) {
            throw IllegalStateException(error.optString("description", "QuoteSummary error"))
        }
        val res = qs.getJSONArray("result").getJSONObject(0)
        val summaryDetail = res.optJSONObject("summaryDetail")
        val keyStats = res.optJSONObject("defaultKeyStatistics")
        val financialData = res.optJSONObject("financialData")
        val profile = res.optJSONObject("assetProfile")
        val priceObj = res.optJSONObject("price")

        fun JSONObject?.rawDouble(key: String): Double? {
            val obj = this?.optJSONObject(key) ?: return null
            val v = obj.optDouble("raw", Double.NaN)
            return if (v.isNaN()) null else v
        }

        fun JSONObject?.rawLong(key: String): Long? {
            val obj = this?.optJSONObject(key) ?: return null
            return if (obj.has("raw") && !obj.isNull("raw")) obj.optLong("raw") else null
        }

        return FundamentalSnapshot(
            symbol = symbol,
            sector = profile?.optString("sector")?.takeIf { it.isNotBlank() },
            industry = profile?.optString("industry")?.takeIf { it.isNotBlank() },
            summary = profile?.optString("longBusinessSummary")?.takeIf { it.isNotBlank() },
            website = profile?.optString("website")?.takeIf { it.isNotBlank() },
            employees = profile?.optLong("fullTimeEmployees")?.takeIf { it > 0L },
            marketCap = priceObj.rawDouble("marketCap") ?: summaryDetail.rawDouble("marketCap"),
            trailingPE = summaryDetail.rawDouble("trailingPE"),
            forwardPE = summaryDetail.rawDouble("forwardPE") ?: keyStats.rawDouble("forwardPE"),
            pegRatio = keyStats.rawDouble("pegRatio"),
            priceToBook = keyStats.rawDouble("priceToBook"),
            priceToSales = summaryDetail.rawDouble("priceToSalesTrailing12Months"),
            evToEbitda = keyStats.rawDouble("enterpriseToEbitda"),
            dividendYield = summaryDetail.rawDouble("dividendYield"),
            dividendRate = summaryDetail.rawDouble("dividendRate"),
            payoutRatio = summaryDetail.rawDouble("payoutRatio"),
            beta = summaryDetail.rawDouble("beta") ?: keyStats.rawDouble("beta"),
            grossMargin = financialData.rawDouble("grossMargins"),
            operatingMargin = financialData.rawDouble("operatingMargins"),
            profitMargin = financialData.rawDouble("profitMargins"),
            returnOnEquity = financialData.rawDouble("returnOnEquity"),
            returnOnAssets = financialData.rawDouble("returnOnAssets"),
            debtToEquity = financialData.rawDouble("debtToEquity"),
            currentRatio = financialData.rawDouble("currentRatio"),
            quickRatio = financialData.rawDouble("quickRatio"),
            freeCashFlow = financialData.rawDouble("freeCashflow"),
            operatingCashFlow = financialData.rawDouble("operatingCashflow"),
            totalRevenue = financialData.rawDouble("totalRevenue"),
            revenueGrowth = financialData.rawDouble("revenueGrowth"),
            earningsGrowth = financialData.rawDouble("earningsGrowth"),
            targetMeanPrice = financialData.rawDouble("targetMeanPrice"),
            targetHighPrice = financialData.rawDouble("targetHighPrice"),
            targetLowPrice = financialData.rawDouble("targetLowPrice"),
            recommendationKey = financialData?.optString("recommendationKey")?.takeIf { it.isNotBlank() },
            recommendationMean = financialData.rawDouble("recommendationMean"),
            analystCount = financialData.rawLong("numberOfAnalystOpinions")?.toInt()
        )
    }

    private fun parseSearchResponse(jsonText: String): Pair<List<MarketSearchItem>, List<MarketNewsItem>> {
        val root = JSONObject(jsonText)
        val quotesArr = root.optJSONArray("quotes")
        val quotes = mutableListOf<MarketSearchItem>()
        if (quotesArr != null) {
            for (i in 0 until quotesArr.length()) {
                val obj = quotesArr.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol")
                if (sym.isBlank()) continue
                val exch = obj.optString("exchange")
                val isCanadian = sym.endsWith(".TO", true) ||
                    sym.endsWith(".V", true) ||
                    sym.endsWith(".CN", true) ||
                    sym.endsWith(".NE", true) ||
                    sym == "^GSPTSE" ||
                    exch.equals("TOR", true) ||
                    exch.equals("VAN", true)
                if (!isCanadian) continue
                quotes.add(
                    MarketSearchItem(
                        symbol = sym,
                        shortName = obj.optString("shortname")
                            .ifBlank { obj.optString("longname") }
                            .ifBlank { sym },
                        exchange = CanadianUniverse.exchangeLabel(exch, sym),
                        quoteType = obj.optString("quoteType", "EQUITY"),
                        sector = obj.optString("sectorDisp")
                            .ifBlank { CanadianUniverse.sectorFor(sym) }
                    )
                )
            }
        }

        val newsArr = root.optJSONArray("news")
        val news = mutableListOf<MarketNewsItem>()
        if (newsArr != null) {
            for (i in 0 until newsArr.length()) {
                val obj = newsArr.optJSONObject(i) ?: continue
                val title = obj.optString("title")
                if (title.isBlank()) continue
                news.add(
                    MarketNewsItem(
                        title = title,
                        publisher = obj.optString("publisher", "Market Wire"),
                        link = obj.optString("link", ""),
                        publishedEpochSec = obj.optLong("providerPublishTime", 0L)
                    )
                )
            }
        }
        return quotes to news
    }

    private class HttpException(val code: Int, message: String) : Exception(message)
}
