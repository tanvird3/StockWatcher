package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.alerts.AlertNotificationHelper
import com.example.auth.attemptAutoSignIn
import com.example.auth.authStateFlow
import com.example.auth.signOutUser
import com.example.data.remote.YahooMarketClient
import com.example.data.repository.PortfolioRepository
import com.example.ui.components.CreateAlertDialog
import com.example.ui.screens.AlertsScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.MarketsScreen
import com.example.ui.screens.ScreenerScreen
import com.example.ui.screens.StockDetailScreen
import com.example.ui.screens.WatchlistScreen
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MaplePulseTheme
import com.example.ui.viewmodel.FirestoreUiState
import com.example.ui.viewmodel.MaplePulseViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

const val ROUTE_MARKETS = "markets"
const val ROUTE_SCREENER = "screener"
const val ROUTE_WATCHLIST = "watchlist"
const val ROUTE_ALERTS = "alerts"
const val ROUTE_DETAIL = "detail/{symbol}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AlertNotificationHelper.ensureChannel(this)

        setContent {
            MaplePulseTheme {
                MaplePulseRoot()
            }
        }
    }
}

@Composable
private fun MaplePulseRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val currentUser by Firebase.auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    LaunchedEffect(Unit) {
        if (Firebase.auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                scope = scope,
                onComplete = {}
            )
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedMaplePulseApp(
                currentUserId = user.uid,
                userDisplayName = user.displayName ?: user.email ?: "TSX Investor",
                onSignOut = {
                    signOutUser(credentialManager, scope)
                }
            )
        }
    }
}

private data class AlertDialogSeed(
    val symbol: String,
    val stockName: String,
    val currentPrice: Double?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedMaplePulseApp(
    currentUserId: String,
    userDisplayName: String,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    val viewModel: MaplePulseViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                MaplePulseViewModel(
                    portfolioRepository = PortfolioRepository(db),
                    marketClient = YahooMarketClient(),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val watchlistState by viewModel.watchlistState.collectAsStateWithLifecycle()
    val alertsState by viewModel.alertsState.collectAsStateWithLifecycle()
    val eventsState by viewModel.alertEventsState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var alertDialogSeed by remember { mutableStateOf<AlertDialogSeed?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.uiMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.triggeredNotifications.collect { notice ->
            AlertNotificationHelper.postPriceAlertNotification(
                context = context,
                symbol = notice.symbol,
                title = notice.title,
                message = notice.message
            )
            snackbarHostState.showSnackbar(notice.message)
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_MARKETS
    val isDetailRoute = currentRoute.startsWith("detail/")

    val watchlistItems = (watchlistState as? FirestoreUiState.Success)?.data ?: emptyList()
    val unreadAlertsCount = (eventsState as? FirestoreUiState.Success)?.data?.count { !it.acknowledged } ?: 0

    fun navigateTopTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openStockDetail(symbol: String) {
        viewModel.openStockDetail(symbol)
        navController.navigate("detail/${Uri.encode(symbol)}")
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                if (!isDetailRoute) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        title = {
                            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(id = R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "CAD",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = marketColors.bullish
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = onSignOut,
                                modifier = Modifier.testTag("sign_out_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = "Sign out ($userDisplayName)"
                                )
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (!useRail && !isDetailRoute) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_MARKETS,
                            onClick = { navigateTopTab(ROUTE_MARKETS) },
                            icon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null) },
                            label = { Text(stringResource(id = R.string.nav_markets)) },
                            modifier = Modifier.testTag("nav_markets")
                        )
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_SCREENER,
                            onClick = { navigateTopTab(ROUTE_SCREENER) },
                            icon = { Icon(Icons.Filled.FilterAlt, contentDescription = null) },
                            label = { Text(stringResource(id = R.string.nav_screener)) },
                            modifier = Modifier.testTag("nav_screener")
                        )
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_WATCHLIST,
                            onClick = { navigateTopTab(ROUTE_WATCHLIST) },
                            icon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                            label = { Text(stringResource(id = R.string.nav_watchlist)) },
                            modifier = Modifier.testTag("nav_watchlist")
                        )
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_ALERTS,
                            onClick = { navigateTopTab(ROUTE_ALERTS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (unreadAlertsCount > 0) {
                                            Badge { Text(unreadAlertsCount.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(id = R.string.nav_alerts)) },
                            modifier = Modifier.testTag("nav_alerts")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail && !isDetailRoute) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_MARKETS,
                            onClick = { navigateTopTab(ROUTE_MARKETS) },
                            icon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null) },
                            label = { Text(stringResource(id = R.string.nav_markets)) }
                        )
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_SCREENER,
                            onClick = { navigateTopTab(ROUTE_SCREENER) },
                            icon = { Icon(Icons.Filled.FilterAlt, contentDescription = null) },
                            label = { Text(stringResource(id = R.string.nav_screener)) }
                        )
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_WATCHLIST,
                            onClick = { navigateTopTab(ROUTE_WATCHLIST) },
                            icon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                            label = { Text(stringResource(id = R.string.nav_watchlist)) }
                        )
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_ALERTS,
                            onClick = { navigateTopTab(ROUTE_ALERTS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (unreadAlertsCount > 0) {
                                            Badge { Text(unreadAlertsCount.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(id = R.string.nav_alerts)) }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_MARKETS
                    ) {
                        composable(ROUTE_MARKETS) {
                            MarketsScreen(
                                state = dashboardState,
                                watchlist = watchlistItems,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onSelectSector = viewModel::selectSector,
                                onRefresh = { viewModel.refreshMarketDashboard(isInitial = false) },
                                onOpenStock = ::openStockDetail,
                                onToggleWatchlist = { q ->
                                    viewModel.toggleWatchlist(q.symbol, q.name, q.exchange, q.sector)
                                },
                                onQuickAlert = { q ->
                                    alertDialogSeed = AlertDialogSeed(q.symbol, q.name, q.price)
                                }
                            )
                        }
                        composable(ROUTE_SCREENER) {
                            ScreenerScreen(
                                state = dashboardState,
                                onOpenStock = ::openStockDetail
                            )
                        }
                        composable(ROUTE_WATCHLIST) {
                            WatchlistScreen(
                                watchlistState = watchlistState,
                                quotes = dashboardState.marketQuotes,
                                technicals = dashboardState.technicalBySymbol,
                                onOpenStock = ::openStockDetail,
                                onRemoveWatchlist = { item ->
                                    viewModel.toggleWatchlist(item.symbol, item.name, item.exchange, item.sector)
                                },
                                onSaveNotes = viewModel::updateWatchlistNotes,
                                onQuickAlert = { item, q ->
                                    alertDialogSeed = AlertDialogSeed(item.symbol, item.name, q?.price)
                                },
                                onExploreMarkets = { navigateTopTab(ROUTE_MARKETS) }
                            )
                        }
                        composable(ROUTE_ALERTS) {
                            AlertsScreen(
                                alertsState = alertsState,
                                eventsState = eventsState,
                                quotes = dashboardState.marketQuotes,
                                livePollingEnabled = dashboardState.livePollingEnabled,
                                onToggleLivePolling = viewModel::toggleLivePolling,
                                onScanNow = viewModel::checkAlertsNow,
                                onOpenNewAlert = {
                                    alertDialogSeed = AlertDialogSeed("RY.TO", "Royal Bank of Canada", dashboardState.marketQuotes["RY.TO"]?.price)
                                },
                                onToggleAlert = viewModel::toggleAlert,
                                onDeleteAlert = viewModel::deleteAlert,
                                onAcknowledgeEvent = viewModel::acknowledgeAlertEvent,
                                onDeleteEvent = viewModel::deleteAlertEvent,
                                onOpenStock = ::openStockDetail
                            )
                        }
                        composable(
                            route = ROUTE_DETAIL,
                            arguments = listOf(navArgument("symbol") { type = NavType.StringType })
                        ) { entry ->
                            val sym = Uri.decode(entry.arguments?.getString("symbol") ?: "RY.TO")
                            LaunchedEffect(sym) {
                                if (detailState.symbol != sym) {
                                    viewModel.openStockDetail(sym)
                                }
                            }
                            val isSaved = watchlistItems.any { it.symbol.equals(sym, ignoreCase = true) }
                            StockDetailScreen(
                                state = detailState,
                                isWatchlisted = isSaved,
                                onBack = { navController.popBackStack() },
                                onChangeRange = viewModel::changeDetailChartRange,
                                onChangeForecastHorizon = viewModel::changeForecastHorizon,
                                onToggleWatchlist = { q ->
                                    viewModel.toggleWatchlist(q.symbol, q.name, q.exchange, q.sector)
                                },
                                onOpenSetAlert = { q ->
                                    alertDialogSeed = AlertDialogSeed(q.symbol, q.name, q.price)
                                },
                                onRetry = { viewModel.openStockDetail(sym) }
                            )
                        }
                    }
                }
            }
        }
    }

    alertDialogSeed?.let { seed ->
        CreateAlertDialog(
            initialSymbol = seed.symbol,
            initialStockName = seed.stockName,
            currentPrice = seed.currentPrice,
            onDismiss = { alertDialogSeed = null },
            onConfirm = { symbol, name, cond, target ->
                viewModel.createAlert(symbol, name, cond, target)
                alertDialogSeed = null
            }
        )
    }
}
