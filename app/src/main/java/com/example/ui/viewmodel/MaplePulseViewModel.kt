package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AlertConditionType
import com.example.data.model.AlertEvent
import com.example.data.model.PriceAlert
import com.example.data.model.WatchlistItem
import com.example.data.model.sanitizeFirestoreDocId
import com.example.data.remote.CanadianMacroSnapshot
import com.example.data.remote.CanadianUniverse
import com.example.data.remote.ChartRange
import com.example.data.remote.FundamentalSnapshot
import com.example.data.remote.MarketNewsItem
import com.example.data.remote.MarketQuote
import com.example.data.remote.MarketSearchItem
import com.example.data.remote.YahooMarketClient
import com.example.data.repository.PortfolioRepository
import com.example.domain.analysis.ForecastEngine
import com.example.domain.analysis.ForecastHorizon
import com.example.domain.analysis.ForecastResult
import com.example.domain.analysis.FundamentalAnalysisResult
import com.example.domain.analysis.FundamentalAnalyzer
import com.example.domain.analysis.TechnicalAnalysisResult
import com.example.domain.analysis.TechnicalAnalyzer
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface FirestoreUiState<out T> {
    data object Loading : FirestoreUiState<Nothing>
    data class Success<T>(val data: T) : FirestoreUiState<T>
    data class Error(val message: String) : FirestoreUiState<Nothing>
}

data class ScreenerRow(
    val quote: MarketQuote,
    val technical: TechnicalAnalysisResult
)

data class StockDetailState(
    val symbol: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val range: ChartRange = ChartRange.ONE_YEAR,
    val horizon: ForecastHorizon = ForecastHorizon.THIRTY_DAYS,
    val quote: MarketQuote? = null,
    val fundamentals: FundamentalSnapshot? = null,
    val technicalResult: TechnicalAnalysisResult? = null,
    val fundamentalResult: FundamentalAnalysisResult? = null,
    val forecastResult: ForecastResult? = null
)

data class MarketDashboardState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val tsxComposite: MarketQuote? = null,
    val macro: CanadianMacroSnapshot? = null,
    val selectedSector: String = "All",
    val marketQuotes: Map<String, MarketQuote> = emptyMap(),
    val technicalBySymbol: Map<String, TechnicalAnalysisResult> = emptyMap(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<MarketSearchItem> = emptyList(),
    val marketNews: List<MarketNewsItem> = emptyList(),
    val lastUpdatedEpochMs: Long = 0L,
    val livePollingEnabled: Boolean = true
)

data class TriggeredAlertNotice(
    val symbol: String,
    val title: String,
    val message: String
)

class MaplePulseViewModel(
    private val portfolioRepository: PortfolioRepository,
    private val marketClient: YahooMarketClient,
    private val currentUserId: String
) : ViewModel() {

    val watchlistState: StateFlow<FirestoreUiState<List<WatchlistItem>>> =
        portfolioRepository.observeWatchlist(currentUserId)
            .map<List<WatchlistItem>, FirestoreUiState<List<WatchlistItem>>> { FirestoreUiState.Success(it) }
            .catch { error ->
                val fsError = unwrapFirestoreError(error)
                if (fsError == null && error is CancellationException) throw error
                val root = fsError ?: error
                Log.w(TAG, "Error observing watchlist", root)
                emit(FirestoreUiState.Error(root.message ?: "Failed to load watchlist"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = FirestoreUiState.Loading
            )

    val alertsState: StateFlow<FirestoreUiState<List<PriceAlert>>> =
        portfolioRepository.observeAlerts(currentUserId)
            .map<List<PriceAlert>, FirestoreUiState<List<PriceAlert>>> { FirestoreUiState.Success(it) }
            .catch { error ->
                val fsError = unwrapFirestoreError(error)
                if (fsError == null && error is CancellationException) throw error
                val root = fsError ?: error
                Log.w(TAG, "Error observing alerts", root)
                emit(FirestoreUiState.Error(root.message ?: "Failed to load alerts"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = FirestoreUiState.Loading
            )

    val alertEventsState: StateFlow<FirestoreUiState<List<AlertEvent>>> =
        portfolioRepository.observeAlertEvents(currentUserId)
            .map<List<AlertEvent>, FirestoreUiState<List<AlertEvent>>> { FirestoreUiState.Success(it) }
            .catch { error ->
                val fsError = unwrapFirestoreError(error)
                if (fsError == null && error is CancellationException) throw error
                val root = fsError ?: error
                Log.w(TAG, "Error observing alert events", root)
                emit(FirestoreUiState.Error(root.message ?: "Failed to load alert log"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = FirestoreUiState.Loading
            )

    private val _dashboardState = MutableStateFlow(MarketDashboardState())
    val dashboardState: StateFlow<MarketDashboardState> = _dashboardState.asStateFlow()

    private val _detailState = MutableStateFlow(StockDetailState())
    val detailState: StateFlow<StockDetailState> = _detailState.asStateFlow()

    private val _triggeredNotifications = MutableSharedFlow<TriggeredAlertNotice>(extraBufferCapacity = 16)
    val triggeredNotifications: SharedFlow<TriggeredAlertNotice> = _triggeredNotifications.asSharedFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private val inFlightAlertTriggers = mutableSetOf<String>()

    init {
        refreshMarketDashboard(isInitial = true)
        startRealtimeAlertMonitorLoop()
    }

    fun refreshMarketDashboard(isInitial: Boolean = false) {
        viewModelScope.launch {
            _dashboardState.update {
                it.copy(
                    isLoading = isInitial && it.marketQuotes.isEmpty(),
                    isRefreshing = !isInitial,
                    error = null
                )
            }

            try {
                val watchlistSymbols = (watchlistState.value as? FirestoreUiState.Success)?.data
                    ?.map { it.symbol } ?: emptyList()
                val alertSymbols = (alertsState.value as? FirestoreUiState.Success)?.data
                    ?.filter { it.isActive }
                    ?.map { it.symbol } ?: emptyList()

                val allSymbols = (listOf(CanadianUniverse.TSX_COMPOSITE_INDEX) +
                    CanadianUniverse.featuredTickers.map { it.symbol } +
                    watchlistSymbols +
                    alertSymbols).distinct()

                val quotes = marketClient.fetchBatchQuotes(allSymbols, ChartRange.THREE_MONTHS)
                val macro = marketClient.fetchCanadianMacro()
                val newsPair = marketClient.searchCanadianEquities("TSX Canada stock").getOrNull()

                if (quotes.isEmpty()) {
                    _dashboardState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = "Unable to reach Canadian market feed. Check network connection and retry."
                        )
                    }
                    return@launch
                }

                val quoteMap = quotes.associateBy { it.symbol }
                val techMap = quotes.associate { it.symbol to TechnicalAnalyzer.analyze(it) }
                val tsxQuote = quoteMap[CanadianUniverse.TSX_COMPOSITE_INDEX]

                _dashboardState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                        tsxComposite = tsxQuote ?: it.tsxComposite,
                        macro = macro,
                        marketQuotes = quoteMap,
                        technicalBySymbol = techMap,
                        marketNews = newsPair?.second ?: it.marketNews,
                        lastUpdatedEpochMs = System.currentTimeMillis()
                    )
                }

                evaluateActiveAlerts(quoteMap, techMap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _dashboardState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = e.localizedMessage ?: "Failed to refresh TSX market data"
                    )
                }
            }
        }
    }

    fun selectSector(sector: String) {
        _dashboardState.update { it.copy(selectedSector = sector) }
    }

    fun toggleLivePolling(enabled: Boolean) {
        _dashboardState.update { it.copy(livePollingEnabled = enabled) }
    }

    fun updateSearchQuery(query: String) {
        _dashboardState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _dashboardState.update { it.copy(isSearching = false, searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(320)
            _dashboardState.update { it.copy(isSearching = true) }
            val result = marketClient.searchCanadianEquities(query.trim())
            result.onSuccess { (items, news) ->
                _dashboardState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = items,
                        marketNews = if (news.isNotEmpty()) news else it.marketNews
                    )
                }
            }.onFailure {
                _dashboardState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun openStockDetail(symbol: String, range: ChartRange = _detailState.value.range) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val cachedQuote = _dashboardState.value.marketQuotes[symbol]
            val currentHorizon = _detailState.value.horizon
            _detailState.update {
                it.copy(
                    symbol = symbol,
                    isLoading = true,
                    error = null,
                    range = range,
                    quote = if (it.symbol == symbol) it.quote ?: cachedQuote else cachedQuote
                )
            }

            val quoteRes = marketClient.fetchChartQuote(symbol, range)
            val fundRes = marketClient.fetchFundamentals(symbol)

            val quote = quoteRes.getOrNull() ?: cachedQuote
            if (quote == null) {
                _detailState.update {
                    it.copy(
                        isLoading = false,
                        error = quoteRes.exceptionOrNull()?.localizedMessage
                            ?: "Could not load TSX quote for $symbol"
                    )
                }
                return@launch
            }

            val fundamentals = fundRes.getOrNull()
            val techResult = TechnicalAnalyzer.analyze(quote)
            val fundResult = FundamentalAnalyzer.evaluate(
                quote = quote,
                fundamentals = fundamentals,
                macro = _dashboardState.value.macro
            )
            val forecast = ForecastEngine.generateForecast(quote, currentHorizon)

            _detailState.update {
                it.copy(
                    symbol = symbol,
                    isLoading = false,
                    error = null,
                    range = range,
                    quote = quote,
                    fundamentals = fundamentals,
                    technicalResult = techResult,
                    fundamentalResult = fundResult,
                    forecastResult = forecast
                )
            }

            // Update dashboard cache for this symbol too
            _dashboardState.update { dash ->
                val updatedQuotes = dash.marketQuotes + (symbol to quote)
                val updatedTech = dash.technicalBySymbol + (symbol to techResult)
                dash.copy(marketQuotes = updatedQuotes, technicalBySymbol = updatedTech)
            }
        }
    }

    fun changeDetailChartRange(range: ChartRange) {
        val sym = _detailState.value.symbol
        if (sym.isNotBlank()) {
            openStockDetail(sym, range)
        }
    }

    fun changeForecastHorizon(horizon: ForecastHorizon) {
        val quote = _detailState.value.quote ?: return
        val forecast = ForecastEngine.generateForecast(quote, horizon)
        _detailState.update {
            it.copy(horizon = horizon, forecastResult = forecast)
        }
    }

    fun toggleWatchlist(symbol: String, name: String, exchange: String, sector: String) {
        val currentList = (watchlistState.value as? FirestoreUiState.Success)?.data ?: emptyList()
        val docId = sanitizeFirestoreDocId(symbol)
        val existing = currentList.firstOrNull { it.id == docId || it.symbol.equals(symbol, true) }
        viewModelScope.launch {
            if (existing != null) {
                portfolioRepository.removeFromWatchlist(currentUserId, existing.id)
                    .onSuccess { _uiMessages.tryEmit("Removed $symbol from watchlist") }
                    .onFailure { _uiMessages.tryEmit(it.localizedMessage ?: "Failed to remove $symbol") }
            } else {
                portfolioRepository.addToWatchlist(
                    userId = currentUserId,
                    symbol = symbol,
                    name = name,
                    exchange = exchange,
                    sector = sector
                )
                    .onSuccess { _uiMessages.tryEmit("Added $symbol to watchlist") }
                    .onFailure { _uiMessages.tryEmit(it.localizedMessage ?: "Failed to add $symbol") }
            }
        }
    }

    fun updateWatchlistNotes(item: WatchlistItem, notes: String) {
        viewModelScope.launch {
            portfolioRepository.updateWatchlistNotes(currentUserId, item, notes)
                .onSuccess { _uiMessages.tryEmit("Saved thesis note for ${item.symbol}") }
                .onFailure { _uiMessages.tryEmit(it.localizedMessage ?: "Failed to save note") }
        }
    }

    fun createAlert(
        symbol: String,
        stockName: String,
        conditionType: AlertConditionType,
        targetValue: Double
    ) {
        viewModelScope.launch {
            portfolioRepository.createPriceAlert(
                userId = currentUserId,
                symbol = symbol,
                stockName = stockName,
                conditionType = conditionType,
                targetValue = targetValue
            )
                .onSuccess {
                    _uiMessages.tryEmit("Alert armed for $symbol (${conditionType.shortBadge} $targetValue)")
                    // Ensure we monitor this symbol right away
                    checkSingleAlertSymbolNow(symbol)
                }
                .onFailure {
                    _uiMessages.tryEmit(it.localizedMessage ?: "Failed to create alert")
                }
        }
    }

    fun toggleAlert(alert: PriceAlert, active: Boolean) {
        viewModelScope.launch {
            portfolioRepository.toggleAlertActive(currentUserId, alert.id, active)
        }
    }

    fun deleteAlert(alertId: String) {
        viewModelScope.launch {
            portfolioRepository.deleteAlert(currentUserId, alertId)
                .onSuccess { _uiMessages.tryEmit("Deleted price alert") }
        }
    }

    fun acknowledgeAlertEvent(eventId: String) {
        viewModelScope.launch {
            portfolioRepository.acknowledgeAlertEvent(currentUserId, eventId)
        }
    }

    fun deleteAlertEvent(eventId: String) {
        viewModelScope.launch {
            portfolioRepository.deleteAlertEvent(currentUserId, eventId)
        }
    }

    fun checkAlertsNow() {
        viewModelScope.launch {
            val activeAlerts = (alertsState.value as? FirestoreUiState.Success)?.data
                ?.filter { it.isActive } ?: emptyList()
            if (activeAlerts.isEmpty()) {
                _uiMessages.tryEmit("No active alerts to evaluate.")
                return@launch
            }
            val symbols = activeAlerts.map { it.symbol }.distinct()
            val freshQuotes = marketClient.fetchBatchQuotes(symbols, ChartRange.THREE_MONTHS)
            if (freshQuotes.isEmpty()) {
                _uiMessages.tryEmit("Could not refresh live quotes.")
                return@launch
            }
            val qMap = _dashboardState.value.marketQuotes + freshQuotes.associateBy { it.symbol }
            val tMap = _dashboardState.value.technicalBySymbol +
                freshQuotes.associate { it.symbol to TechnicalAnalyzer.analyze(it) }
            _dashboardState.update {
                it.copy(
                    marketQuotes = qMap,
                    technicalBySymbol = tMap,
                    lastUpdatedEpochMs = System.currentTimeMillis()
                )
            }
            val count = evaluateActiveAlerts(qMap, tMap)
            if (count == 0) {
                _uiMessages.tryEmit("Scanned ${symbols.size} symbols — no thresholds crossed yet.")
            }
        }
    }

    private fun checkSingleAlertSymbolNow(symbol: String) {
        viewModelScope.launch {
            val quote = marketClient.fetchChartQuote(symbol, ChartRange.THREE_MONTHS).getOrNull() ?: return@launch
            val tech = TechnicalAnalyzer.analyze(quote)
            val qMap = _dashboardState.value.marketQuotes + (symbol to quote)
            val tMap = _dashboardState.value.technicalBySymbol + (symbol to tech)
            _dashboardState.update { it.copy(marketQuotes = qMap, technicalBySymbol = tMap) }
            evaluateActiveAlerts(qMap, tMap)
        }
    }

    private fun startRealtimeAlertMonitorLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(REALTIME_POLL_INTERVAL_MS)
                if (!_dashboardState.value.livePollingEnabled) continue
                val activeAlerts = (alertsState.value as? FirestoreUiState.Success)?.data
                    ?.filter { it.isActive } ?: emptyList()
                val watchlistSymbols = (watchlistState.value as? FirestoreUiState.Success)?.data
                    ?.map { it.symbol } ?: emptyList()
                val trackedSymbols = (activeAlerts.map { it.symbol } + watchlistSymbols).distinct()
                if (trackedSymbols.isEmpty()) continue

                val quotes = marketClient.fetchBatchQuotes(trackedSymbols, ChartRange.THREE_MONTHS)
                if (quotes.isNotEmpty()) {
                    val qMap = _dashboardState.value.marketQuotes + quotes.associateBy { it.symbol }
                    val tMap = _dashboardState.value.technicalBySymbol +
                        quotes.associate { it.symbol to TechnicalAnalyzer.analyze(it) }
                    _dashboardState.update {
                        it.copy(
                            marketQuotes = qMap,
                            technicalBySymbol = tMap,
                            lastUpdatedEpochMs = System.currentTimeMillis()
                        )
                    }
                    evaluateActiveAlerts(qMap, tMap)
                }
            }
        }
    }

    private suspend fun evaluateActiveAlerts(
        quotes: Map<String, MarketQuote>,
        technicals: Map<String, TechnicalAnalysisResult>
    ): Int {
        val activeAlerts = (alertsState.value as? FirestoreUiState.Success)?.data
            ?.filter { it.isActive } ?: return 0
        var triggeredCount = 0

        for (alert in activeAlerts) {
            if (!inFlightAlertTriggers.add(alert.id)) continue
            try {
                val quote = quotes[alert.symbol] ?: continue
                val tech = technicals[alert.symbol]
                val cond = AlertConditionType.fromString(alert.conditionType)

                val matched = when (cond) {
                    AlertConditionType.ABOVE -> quote.price >= alert.targetValue
                    AlertConditionType.BELOW -> quote.price <= alert.targetValue
                    AlertConditionType.PCT_GAIN -> quote.changePercent >= alert.targetValue
                    AlertConditionType.PCT_DROP -> quote.changePercent <= -kotlin.math.abs(alert.targetValue)
                    AlertConditionType.RSI_ABOVE -> (tech?.latestRsi14 ?: 50.0) >= alert.targetValue
                    AlertConditionType.RSI_BELOW -> (tech?.latestRsi14 ?: 50.0) <= alert.targetValue
                }

                if (matched) {
                    val msg = buildAlertMessage(alert.symbol, cond, quote, tech?.latestRsi14, alert.targetValue)
                    val res = portfolioRepository.recordAlertTriggered(
                        userId = currentUserId,
                        alert = alert,
                        triggerPrice = quote.price,
                        message = msg,
                        autoPause = true
                    )
                    if (res.isSuccess) {
                        triggeredCount++
                        _triggeredNotifications.tryEmit(
                            TriggeredAlertNotice(
                                symbol = alert.symbol,
                                title = "MaplePulse Alert: ${alert.symbol}",
                                message = msg
                            )
                        )
                    }
                }
            } finally {
                inFlightAlertTriggers.remove(alert.id)
            }
        }
        return triggeredCount
    }

    private fun buildAlertMessage(
        symbol: String,
        cond: AlertConditionType,
        quote: MarketQuote,
        rsi: Double?,
        target: Double
    ): String {
        return when (cond) {
            AlertConditionType.ABOVE ->
                String.format("%s crossed above CAD %.2f (now CAD %.2f)", symbol, target, quote.price)
            AlertConditionType.BELOW ->
                String.format("%s dropped below CAD %.2f (now CAD %.2f)", symbol, target, quote.price)
            AlertConditionType.PCT_GAIN ->
                String.format("%s surged %+.2f%% today (target +%.1f%%)", symbol, quote.changePercent, target)
            AlertConditionType.PCT_DROP ->
                String.format("%s fell %.2f%% today (threshold -%.1f%%)", symbol, quote.changePercent, kotlin.math.abs(target))
            AlertConditionType.RSI_ABOVE ->
                String.format("%s RSI(14) reached %.1f (overbought ≥ %.1f)", symbol, rsi ?: 70.0, target)
            AlertConditionType.RSI_BELOW ->
                String.format("%s RSI(14) dipped to %.1f (oversold ≤ %.1f)", symbol, rsi ?: 30.0, target)
        }
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? {
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
    }

    private companion object {
        const val TAG = "MaplePulseVM"
        const val REALTIME_POLL_INTERVAL_MS = 20_000L
    }
}
