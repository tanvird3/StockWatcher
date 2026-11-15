package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AlertConditionType
import com.example.data.model.AlertEvent
import com.example.data.model.PriceAlert
import com.example.data.model.WatchlistItem
import com.example.data.model.sanitizeFirestoreDocId
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class PortfolioRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun watchlistCollection(userId: String) =
        db.collection("users").document(userId).collection("watchlist")

    private fun alertsCollection(userId: String) =
        db.collection("users").document(userId).collection("alerts")

    private fun alertEventsCollection(userId: String) =
        db.collection("users").document(userId).collection("alertEvents")

    fun observeWatchlist(userId: String): Flow<List<WatchlistItem>> {
        val path = "users/$userId/watchlist"
        return watchlistCollection(userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    WatchlistItem::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                logStreamErrorIfAuthenticated(error, OperationType.LIST, path, userId)
                throw error
            }
    }

    fun observeAlerts(userId: String): Flow<List<PriceAlert>> {
        val path = "users/$userId/alerts"
        return alertsCollection(userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    PriceAlert::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                logStreamErrorIfAuthenticated(error, OperationType.LIST, path, userId)
                throw error
            }
    }

    fun observeAlertEvents(userId: String): Flow<List<AlertEvent>> {
        val path = "users/$userId/alertEvents"
        return alertEventsCollection(userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    AlertEvent::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                logStreamErrorIfAuthenticated(error, OperationType.LIST, path, userId)
                throw error
            }
    }

    suspend fun addToWatchlist(
        userId: String,
        symbol: String,
        name: String,
        exchange: String,
        sector: String = "",
        notes: String = ""
    ): Result<String> {
        val docId = sanitizeFirestoreDocId(symbol)
        val docRef = watchlistCollection(userId).document(docId)
        val payload = mapOf(
            "userId" to userId,
            "symbol" to symbol.trim().take(20),
            "name" to name.trim().ifBlank { symbol }.take(120),
            "exchange" to exchange.trim().ifBlank { "TSX" }.take(24),
            "sector" to sector.trim().take(80),
            "notes" to notes.trim().take(300),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateWatchlistNotes(
        userId: String,
        item: WatchlistItem,
        notes: String
    ): Result<Unit> {
        val docId = item.id.ifBlank { sanitizeFirestoreDocId(item.symbol) }
        val docRef = watchlistCollection(userId).document(docId)
        val updates = mapOf(
            "notes" to notes.trim().take(300),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun removeFromWatchlist(userId: String, docId: String): Result<Unit> {
        val cleanId = sanitizeFirestoreDocId(docId)
        val docRef = watchlistCollection(userId).document(cleanId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createPriceAlert(
        userId: String,
        symbol: String,
        stockName: String,
        conditionType: AlertConditionType,
        targetValue: Double
    ): Result<String> {
        val alertId = "alert_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = alertsCollection(userId).document(alertId)
        val payload = mapOf(
            "userId" to userId,
            "symbol" to symbol.trim().take(20),
            "stockName" to stockName.trim().ifBlank { symbol }.take(120),
            "conditionType" to conditionType.name,
            "targetValue" to targetValue,
            "isActive" to true,
            "triggeredCount" to 0L,
            "lastTriggeredPrice" to 0.0,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(alertId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleAlertActive(
        userId: String,
        alertId: String,
        isActive: Boolean
    ): Result<Unit> {
        val cleanId = sanitizeFirestoreDocId(alertId)
        val docRef = alertsCollection(userId).document(cleanId)
        return try {
            docRef.update(
                mapOf(
                    "isActive" to isActive,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun recordAlertTriggered(
        userId: String,
        alert: PriceAlert,
        triggerPrice: Double,
        message: String,
        autoPause: Boolean = true
    ): Result<String> {
        val cleanAlertId = sanitizeFirestoreDocId(alert.id)
        val alertRef = alertsCollection(userId).document(cleanAlertId)
        val eventId = "evt_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val eventRef = alertEventsCollection(userId).document(eventId)
        return try {
            val batch = db.batch()
            batch.update(
                alertRef,
                mapOf(
                    "isActive" to !autoPause,
                    "triggeredCount" to (alert.triggeredCount + 1L),
                    "lastTriggeredPrice" to triggerPrice,
                    "lastTriggeredAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                eventRef,
                mapOf(
                    "userId" to userId,
                    "alertId" to cleanAlertId,
                    "symbol" to alert.symbol,
                    "conditionType" to alert.conditionType,
                    "triggerPrice" to triggerPrice,
                    "targetValue" to alert.targetValue,
                    "message" to message.take(240),
                    "acknowledged" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(eventId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, eventRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteAlert(userId: String, alertId: String): Result<Unit> {
        val cleanId = sanitizeFirestoreDocId(alertId)
        val docRef = alertsCollection(userId).document(cleanId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun acknowledgeAlertEvent(userId: String, eventId: String): Result<Unit> {
        val cleanId = sanitizeFirestoreDocId(eventId)
        val docRef = alertEventsCollection(userId).document(cleanId)
        return try {
            docRef.update(
                mapOf(
                    "acknowledged" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteAlertEvent(userId: String, eventId: String): Result<Unit> {
        val cleanId = sanitizeFirestoreDocId(eventId)
        val docRef = alertEventsCollection(userId).document(cleanId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    private fun logStreamErrorIfAuthenticated(
        error: Throwable,
        operationType: OperationType,
        path: String,
        userId: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val activeUid = try {
            Firebase.auth.currentUser?.uid
        } catch (_: Exception) {
            null
        }
        if (activeUid == userId) {
            handleFirestoreError(firestoreError ?: error, operationType, path)
        }
    }
}
