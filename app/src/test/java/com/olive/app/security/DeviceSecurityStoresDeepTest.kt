package com.olive.app.security

import android.app.Application
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.HistoryRecord
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryEdit
import com.olive.app.data.account.TriggerEvent
import com.olive.app.data.account.UserProfile
import com.olive.app.support.TestAndroidKeyStore
import java.security.Security
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DeviceSecurityStoresDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var keysHandle: AutoCloseable? = null

    @Before
    fun setUp() {
        Security.removeProvider("AndroidKeyStore")
        keysHandle = TestAndroidKeyStore.install()
    }

    @After
    fun tearDown() {
        keysHandle?.close()
        Security.removeProvider("AndroidKeyStore")
    }

    @Test
    fun deviceStoreEnrollmentLockoutRestoreAndPreferencesWorkCleanly() {
        val vault = EncryptedVault(context, "device-store-deep")
        val activityVault = EncryptedVault(context, "activity-store-deep")
        val store = DeviceStore(vault, activityVault)

        assertNull(store.currentSession())
        assertNull(store.current())

        val userProfile = UserProfile(
            username = "jordan",
            fullName = "Jordan",
            dob = "1992-01-01",
            language = "en",
            address = DeliveryAddress(city = "Edmonton"),
            role = AccountRole.USER,
            coverType = CoverType.FITNESS,
            triggerHash = "hash-1",
        )
        val identity = AccountIdentity("u1", "jordan")
        val enrolled = store.enroll(identity, userProfile, "1357")
        assertEquals("u1", store.current()?.uid)
        assertNotNull(store.currentSession())

        // PIN verification and 5-attempt lockout
        assertEquals(PinResult.Accepted, store.verify("u1", "1357", now = 1000L))
        repeat(4) { idx ->
            assertTrue(store.verify("u1", "9999", now = 2000L + idx) is PinResult.Rejected)
        }
        val lockout = store.verify("u1", "9999", now = 3000L)
        assertTrue(lockout is PinResult.Rejected && lockout.failures == 5)
        // Still locked out inside cooldown window even with correct PIN
        assertTrue(store.verify("u1", "1357", now = 4000L) is PinResult.Rejected)

        // Device lock enrollment and entry method switch
        val grant = JSONObject().put("wrapped", "abc")
        val deviceLockEnrolled = store.enrollDeviceLock(identity, userProfile, grant)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, deviceLockEnrolled.entryMethod)
        assertEquals("abc", store.deviceLockGrant("u1").optString("wrapped"))
        val updatedEntry = store.updateSafeEntry(deviceLockEnrolled, SafeEntryMethod.DEVICE_LOCK, "2468", grant)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, updatedEntry.entryMethod)

        // Closed/archived conversation retention
        store.setClosedConversationRestored("u1", "conv-1", true)
        store.setClosedConversationRestored("u1", "conv-2", true)
        assertEquals(setOf("conv-1", "conv-2"), store.restoredClosedConversationIds("u1"))
        store.retainConversationPreferences("u1", setOf("conv-1"))
        assertEquals(setOf("conv-1"), store.restoredClosedConversationIds("u1"))
        store.setClosedConversationRestored("u1", "conv-1", false)
        assertTrue(store.restoredClosedConversationIds("u1").isEmpty())

        // Remove PIN and restoreActive
        store.removePin("u1")
        assertFalse(store.current()!!.hasAppPin)
        assertNotNull(store.restoreActive("u1"))
        assertNull(store.restoreActive("missing-uid"))

        // Safe contact enrollWithoutPin
        val scProfile = userProfile.copy(username = "sc", role = AccountRole.SAFE_CONTACT)
        val scEnrolled = store.enrollWithoutPin(AccountIdentity("sc1", "sc"), scProfile)
        assertFalse(scEnrolled.hasAppPin)
    }

    @Test
    fun deviceActivityStoreHandlesTriggerAttemptsTombstonesAndMerges() {
        val activityVault = EncryptedVault(context, "activity-deep-2")
        val actStore = DeviceActivityStore(activityVault)
        val uid = "u-act"

        actStore.recordTrigger(uid, TriggerEvent(id = "t1", timestamp = 100L, source = "Cover", pinAttempts = 0, lastResult = "No PIN attempt"))
        actStore.recordTriggerAttempt(uid, "t1", accepted = true) // first-try skipped
        actStore.recordTriggerAttempt(uid, "t1", accepted = false)
        actStore.recordTriggerAttempt(uid, "t1", accepted = true)
        val triggers = actStore.triggerHistory(uid)
        assertEquals("Accepted", triggers.first().lastResult)

        // Private entries & tombstone expiration
        val oldDeleted = PrivateEntry(
            id = "e-old",
            timestamp = 100L,
            updatedAt = 200L,
            title = "Old",
            body = "Secret",
            deletedAt = 300L,
        )
        val activeEntry = PrivateEntry(
            id = "e-live",
            timestamp = 100L,
            updatedAt = 500L,
            title = "Live",
            body = "Body",
            edits = listOf(PrivateEntryEdit(id = "ed-local", timestamp = 400L, changes = listOf(FieldChange("Body", "Old", "Body")))),
        )
        actStore.savePrivateEntry(uid, oldDeleted)
        actStore.savePrivateEntry(uid, activeEntry)
        assertTrue(actStore.purgeExpiredPrivateEntries(uid, now = 300L + 40L * 24 * 60 * 60 * 1000L))
        assertEquals(1, actStore.privateEntries(uid).size)

        // Pending history & markHistorySynced
        val pending = actStore.pendingHistory(uid)
        assertTrue(pending.isNotEmpty())
        pending.forEach { actStore.markHistorySynced(uid, it) }
        actStore.markHistorySynced(uid, HistoryRecord("unknown", "x", "{}"))

        // Merge cloud records (newer edits + tombstone + trigger)
        val cloudActive = activeEntry.copy(
            updatedAt = 450L,
            edits = listOf(PrivateEntryEdit(id = "ed-cloud", timestamp = 420L, changes = listOf(FieldChange("Title", "A", "Live")))),
        )
        actStore.mergeHistory(
            uid,
            listOf(
                HistoryRecord("private_entries", cloudActive.id, cloudActive.toJson().toString()),
                HistoryRecord("triggers", "t1", TriggerEvent(id = "t1", timestamp = 100L, source = "Cover", pinAttempts = 0, lastResult = "No PIN attempt").toJson().toString()),
            ),
        )
        val merged = actStore.privateEntries(uid).first { it.id == "e-live" }
        assertEquals(2, merged.edits.size)
    }
}
