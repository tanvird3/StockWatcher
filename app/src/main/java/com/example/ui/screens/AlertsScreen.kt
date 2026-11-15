package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.alerts.AlertNotificationHelper
import com.example.data.model.AlertConditionType
import com.example.data.model.AlertEvent
import com.example.data.model.PriceAlert
import com.example.data.remote.MarketQuote
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.FirestoreUiState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AlertsScreen(
    alertsState: FirestoreUiState<List<PriceAlert>>,
    eventsState: FirestoreUiState<List<AlertEvent>>,
    quotes: Map<String, MarketQuote>,
    livePollingEnabled: Boolean,
    onToggleLivePolling: (Boolean) -> Unit,
    onScanNow: () -> Unit,
    onOpenNewAlert: () -> Unit,
    onToggleAlert: (PriceAlert, Boolean) -> Unit,
    onDeleteAlert: (String) -> Unit,
    onAcknowledgeEvent: (String) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onOpenStock: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    var selectedSubTab by remember { mutableIntStateOf(0) } // 0 = Rules, 1 = Triggered Log
    var hasNotifPerm by remember {
        mutableStateOf(AlertNotificationHelper.hasNotificationPermission(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted
    }

    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.CANADA) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Real-Time Price Alerts",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "20s live TSX quote & RSI(14) threshold monitor",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onOpenNewAlert,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("create_alert_button")
                ) {
                    Icon(Icons.Filled.AddAlert, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("New")
                }
            }
        }

        // Live Monitor Status + OS Notification Permission Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Radar,
                            contentDescription = "Live alert monitor",
                            tint = if (livePollingEnabled) marketColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column {
                            Text(
                                text = "Live Market Monitor",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (livePollingEnabled) "Polling active every 20s" else "Automatic polling paused",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = livePollingEnabled,
                        onCheckedChange = onToggleLivePolling
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onScanNow,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scan_alerts_button")
                    ) {
                        Text("Scan Alerts Now")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) {
                        Button(
                            onClick = {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Enable Push Alerts")
                        }
                    }
                }
            }
        }

        // Segmented Sub-Tab
        item {
            val rulesCount = (alertsState as? FirestoreUiState.Success)?.data?.size ?: 0
            val eventsCount = (eventsState as? FirestoreUiState.Success)?.data?.count { !it.acknowledged } ?: 0
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    label = { Text("Alert Rules ($rulesCount)") }
                )
                FilterChip(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    label = { Text("Triggered Log ($eventsCount unread)") }
                )
            }
        }

        if (selectedSubTab == 0) {
            when (alertsState) {
                is FirestoreUiState.Loading -> {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is FirestoreUiState.Error -> {
                    item {
                        Text(alertsState.message, color = marketColors.bearish)
                    }
                }
                is FirestoreUiState.Success -> {
                    val alerts = alertsState.data
                    if (alerts.isEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.NotificationsNone,
                                        contentDescription = null,
                                        tint = marketColors.forecast,
                                        modifier = Modifier.size(42.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = stringResource(id = R.string.no_alerts_title),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(id = R.string.no_alerts_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(alerts, key = { it.id }) { alert ->
                            val cond = AlertConditionType.fromString(alert.conditionType)
                            val liveQuote = quotes[alert.symbol]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                                    .clickable { onOpenStock(alert.symbol) }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = alert.symbol,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Surface(
                                            color = if (alert.isActive) marketColors.bullishContainer else marketColors.surfaceElevated,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (alert.isActive) "ARMED" else "PAUSED",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (alert.isActive) marketColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${cond.displayLabel}: ${String.format(Locale.CANADA, "%.2f", alert.targetValue)} ${cond.unitSuffix}".trim(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = marketColors.forecast
                                    )
                                    liveQuote?.let { q ->
                                        Text(
                                            text = String.format(Locale.CANADA, "Current: CAD %.2f (%+.2f%%)", q.price, q.changePercent),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (alert.triggeredCount > 0) {
                                        Text(
                                            text = "Triggered ${alert.triggeredCount}x (last @ ${String.format(Locale.CANADA, "%.2f", alert.lastTriggeredPrice)})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = marketColors.amber
                                        )
                                    }
                                }
                                Switch(
                                    checked = alert.isActive,
                                    onCheckedChange = { onToggleAlert(alert, it) }
                                )
                                IconButton(onClick = { onDeleteAlert(alert.id) }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = "Delete alert for ${alert.symbol}",
                                        tint = marketColors.bearish
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            when (eventsState) {
                is FirestoreUiState.Loading -> {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is FirestoreUiState.Error -> {
                    item {
                        Text(eventsState.message, color = marketColors.bearish)
                    }
                }
                is FirestoreUiState.Success -> {
                    val events = eventsState.data
                    if (events.isEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.NotificationsActive,
                                        contentDescription = null,
                                        tint = marketColors.amber,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No Triggered Events Yet", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "When a TSX equity crosses one of your armed conditions, it will be logged here.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(events, key = { it.id }) { evt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (evt.acknowledged) MaterialTheme.colorScheme.surface
                                        else marketColors.amberContainer.copy(alpha = 0.45f)
                                    )
                                    .border(1.dp, marketColors.cardBorder, RoundedCornerShape(16.dp))
                                    .clickable { onOpenStock(evt.symbol) }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = evt.symbol,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = marketColors.amber
                                        )
                                        evt.createdAt?.toDate()?.let { d ->
                                            Text(
                                                text = dateFmt.format(d),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = evt.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (!evt.acknowledged) {
                                    IconButton(onClick = { onAcknowledgeEvent(evt.id) }) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = "Acknowledge alert",
                                            tint = marketColors.bullish
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteEvent(evt.id) }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = "Delete alert event",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
