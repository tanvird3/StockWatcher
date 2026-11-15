package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class AlertConditionType(
    val displayLabel: String,
    val shortBadge: String,
    val unitSuffix: String
) {
    ABOVE("Price Crosses Above", "Price ≥", "CAD"),
    BELOW("Price Drops Below", "Price ≤", "CAD"),
    PCT_GAIN("Intraday Gain ≥", "Gain ≥", "%"),
    PCT_DROP("Intraday Drop ≤", "Drop ≤", "%"),
    RSI_ABOVE("RSI(14) Overbought ≥", "RSI ≥", ""),
    RSI_BELOW("RSI(14) Oversold ≤", "RSI ≤", "");

    companion object {
        fun fromString(raw: String): AlertConditionType {
            return entries.firstOrNull { it.name == raw } ?: ABOVE
        }
    }
}

@IgnoreExtraProperties
data class WatchlistItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val symbol: String = "",
    val name: String = "",
    val exchange: String = "TSX",
    val sector: String = "",
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PriceAlert(
    @DocumentId val id: String = "",
    val userId: String = "",
    val symbol: String = "",
    val stockName: String = "",
    val conditionType: String = AlertConditionType.ABOVE.name,
    val targetValue: Double = 0.0,
    val isActive: Boolean = true,
    val triggeredCount: Long = 0L,
    val lastTriggeredPrice: Double = 0.0,
    val lastTriggeredAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AlertEvent(
    @DocumentId val id: String = "",
    val userId: String = "",
    val alertId: String = "",
    val symbol: String = "",
    val conditionType: String = AlertConditionType.ABOVE.name,
    val triggerPrice: Double = 0.0,
    val targetValue: Double = 0.0,
    val message: String = "",
    val acknowledged: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

fun sanitizeFirestoreDocId(raw: String): String {
    val sanitized = raw.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    return sanitized.take(120).ifBlank { "item" }
}
