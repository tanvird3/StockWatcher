package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.AlertConditionType
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateAlertDialog(
    initialSymbol: String,
    initialStockName: String,
    currentPrice: Double?,
    onDismiss: () -> Unit,
    onConfirm: (symbol: String, stockName: String, condition: AlertConditionType, targetValue: Double) -> Unit
) {
    var symbolText by remember(initialSymbol) { mutableStateOf(initialSymbol) }
    var selectedCondition by remember { mutableStateOf(AlertConditionType.ABOVE) }
    var targetText by remember(currentPrice) {
        mutableStateOf(
            currentPrice?.let { String.format(Locale.CANADA, "%.2f", it * 1.03) } ?: ""
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Set Real-Time Alert",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = symbolText,
                    onValueChange = { symbolText = it.uppercase() },
                    label = { Text("TSX / TSXV Ticker") },
                    placeholder = { Text("e.g. RY.TO or SHOP.TO") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alert_symbol_input")
                )

                if (currentPrice != null && symbolText.equals(initialSymbol, ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format(Locale.CANADA, "Live price: CAD %.2f", currentPrice),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Trigger Condition",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AlertConditionType.entries.forEach { cond ->
                        FilterChip(
                            selected = selectedCondition == cond,
                            onClick = {
                                selectedCondition = cond
                                targetText = when (cond) {
                                    AlertConditionType.ABOVE ->
                                        currentPrice?.let { String.format(Locale.CANADA, "%.2f", it * 1.03) } ?: ""
                                    AlertConditionType.BELOW ->
                                        currentPrice?.let { String.format(Locale.CANADA, "%.2f", it * 0.97) } ?: ""
                                    AlertConditionType.PCT_GAIN -> "2.5"
                                    AlertConditionType.PCT_DROP -> "2.5"
                                    AlertConditionType.RSI_ABOVE -> "70.0"
                                    AlertConditionType.RSI_BELOW -> "30.0"
                                }
                            },
                            label = { Text(cond.shortBadge, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = targetText,
                    onValueChange = {
                        targetText = it
                        errorText = null
                    },
                    label = {
                        val unit = if (selectedCondition.unitSuffix.isNotBlank()) {
                            " (${selectedCondition.unitSuffix})"
                        } else ""
                        Text("Target Threshold$unit")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alert_target_input")
                )

                if (errorText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorText ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanSymbol = symbolText.trim().uppercase()
                    val value = targetText.trim().toDoubleOrNull()
                    when {
                        cleanSymbol.isBlank() -> errorText = "Enter a valid Canadian ticker symbol."
                        value == null || value <= 0.0 -> errorText = "Enter a positive numeric threshold."
                        else -> {
                            val name = if (cleanSymbol.equals(initialSymbol, ignoreCase = true) && initialStockName.isNotBlank()) {
                                initialStockName
                            } else {
                                cleanSymbol
                            }
                            onConfirm(cleanSymbol, name, selectedCondition, value)
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("confirm_create_alert_button")
            ) {
                Text("Arm Alert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
