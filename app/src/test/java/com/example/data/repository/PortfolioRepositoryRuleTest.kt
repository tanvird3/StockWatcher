package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.AlertConditionType
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun watchlist_ownerCrud_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = PortfolioRepository(firestore)

        val addResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.addToWatchlist(
                userId = uid,
                symbol = "RY.TO",
                name = "Royal Bank of Canada",
                exchange = "TSX",
                sector = "Financials",
                notes = "Core dividend holding"
            )
        }
        assertTrue(addResult.isSuccess)
        val docId = addResult.getOrThrow()
        assertEquals("RY_TO", docId)

        val list = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeWatchlist(uid).first { items -> items.any { it.id == docId } }
        }
        val item = list.first { it.id == docId }
        assertEquals("RY.TO", item.symbol)

        val updateResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateWatchlistNotes(uid, item, "Updated thesis")
        }
        assertTrue(updateResult.isSuccess)

        val deleteResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.removeFromWatchlist(uid, docId)
        }
        assertTrue(deleteResult.isSuccess)
    }

    @Test
    fun alerts_ownerCreateTriggerAndAcknowledge_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = PortfolioRepository(firestore)

        val alertId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createPriceAlert(
                userId = uid,
                symbol = "SHOP.TO",
                stockName = "Shopify Inc.",
                conditionType = AlertConditionType.ABOVE,
                targetValue = 145.0
            ).getOrThrow()
        }

        val alerts = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeAlerts(uid).first { list -> list.any { it.id == alertId } }
        }
        val createdAlert = alerts.first { it.id == alertId }

        val eventId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.recordAlertTriggered(
                userId = uid,
                alert = createdAlert,
                triggerPrice = 146.25,
                message = "SHOP.TO crossed above CAD 145.00"
            ).getOrThrow()
        }

        val events = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeAlertEvents(uid).first { list -> list.any { it.id == eventId } }
        }
        assertEquals(1, events.count { it.id == eventId })

        val ackResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.acknowledgeAlertEvent(uid, eventId)
        }
        assertTrue(ackResult.isSuccess)
    }

    @Test
    fun watchlist_crossUserAccess_deniedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = PortfolioRepository(firestore)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.addToWatchlist(
                userId = aliceUid,
                symbol = "ENB.TO",
                name = "Enbridge Inc.",
                exchange = "TSX"
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = PortfolioRepository(firestore)
        var captured: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeWatchlist(aliceUid).first()
            }
        } catch (e: Exception) {
            captured = e
        }
        val firestoreEx = checkNotNull(captured?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $captured"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx.code)
    }

    @Test
    fun observeWatchlist_unauthenticatedUser_deniedWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = PortfolioRepository(firestore)
        var captured: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeWatchlist("some_uid").first()
            }
        } catch (e: Exception) {
            captured = e
        }
        val firestoreEx = checkNotNull(captured?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $captured"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_tsx@test.com"
        const val BOB_EMAIL = "bob_tsx@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
