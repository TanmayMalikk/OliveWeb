// Tests account profile serialization, enum compatibility, local repository flows, and malformed stored values.
package com.olive.app.data

import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.Address
import com.olive.app.data.account.Contact
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.HistoryRecord
import com.olive.app.data.account.LocalAccountRepository
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.User
import com.olive.app.data.account.UserProfile
import com.olive.app.security.EncryptedVault
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountModelBoundaryTest {
    // Input: every current account role. Expected: storage names restore the exact role.
    @Test
    fun everyCurrentRoleRoundTripsFromStorage() {
        AccountRole.entries.forEach { role ->
            assertEquals(role, AccountRole.fromStored(role.name))
        }
    }

    // Input: every current cover. Expected: storage names restore the exact cover.
    @Test
    fun everyCurrentCoverRoundTripsFromStorage() {
        CoverType.entries.forEach { cover ->
            assertEquals(cover, CoverType.fromStored(cover.name))
        }
    }

    // Input: a complete Unicode profile with contacts and coordinates. Expected: encrypted JSON loses no fields.
    @Test
    fun completeProfileRoundTripPreservesEveryField() {
        val original = UserProfile(
            username = "olive_user",
            fullName = "Renée 橄榄",
            affectedNames = "Child",
            abuserName = "Private",
            dob = "2000-02-29",
            language = "French",
            situation = "Needs support",
            region = "Edmonton",
            triggerHash = "hash",
            contacts = listOf(SafeContact("c1", "Amina", "555-0100", true, "uid-2")),
            address = DeliveryAddress(
                "1 Olive St",
                "2",
                "Edmonton",
                "AB",
                "T5J 0N3",
                "Canada",
                53.5,
                -113.5
            ),
            coverType = CoverType.NEWS,
            role = AccountRole.USER,
            email = "olive@example.ca",
            phone = "555-0101",
        )
        assertEquals(original, UserProfile.fromJson(original.toJson()))
    }

    // Input: null coordinates. Expected: the serialized map does not invent latitude or longitude values.
    @Test
    fun absentCoordinatesAreOmittedFromStoredMap() {
        val map = DeliveryAddress(city = "Edmonton").toMap()
        assertFalse(map.containsKey("latitude"))
        assertFalse(map.containsKey("longitude"))
    }

    // Input: NaN, infinity, and coordinates outside Earth. Expected: restored coordinates are rejected.
    @Test
    fun unsafeStoredCoordinatesBecomeNull() {
        listOf(
            JSONObject().put("latitude", "NaN").put("longitude", "Infinity"),
            JSONObject().put("latitude", 91).put("longitude", 181),
            JSONObject().put("latitude", -91).put("longitude", -181),
        ).forEach { stored ->
            val address = DeliveryAddress.fromJson(stored)
            assertNull(address.latitude)
            assertNull(address.longitude)
        }
    }

    // Input: non-object contact entries mixed with one valid contact. Expected: bad rows are skipped, not crashed on.
    @Test
    fun malformedContactRowsAreSkipped() {
        val contacts = JSONArray()
            .put("wrong")
            .put(42)
            .put(JSONObject().put("id", "safe-1").put("name", "Trusted").put("details", "555"))
        val restored = UserProfile.fromJson(
            JSONObject().put("username", "olive_user").put("contacts", contacts)
        )
        assertEquals(1, restored.contacts.size)
        assertEquals("safe-1", restored.contacts.single().id)
    }

    // Input: city, province, and country combinations. Expected: general location has no empty separators.
    @Test
    fun generalLocationUsesOnlyAvailableParts() {
        assertEquals("Edmonton, AB, Canada", DeliveryAddress(city = "Edmonton", province = "AB").generalLocation)
        assertEquals("Edmonton, Canada", DeliveryAddress(city = "Edmonton").generalLocation)
        assertEquals("Canada", DeliveryAddress().generalLocation)
        assertEquals("", DeliveryAddress(country = "").generalLocation)
    }

    // Input: exactly 160 and 161 character city names. Expected: the published field boundary is enforced precisely.
    @Test
    fun locationLengthBoundaryIsExact() {
        assertTrue(DeliveryAddress(city = "x".repeat(160)).locationValid)
        assertFalse(DeliveryAddress(city = "x".repeat(161)).locationValid)
    }

    @Test
    fun firestoreModelClassesAndProfileMapPreserveAllFields() {
        val contact = Contact("c1", "Amina", "555-0100", "Sister")
        val firestoreAddress = Address("1 St", "Apt 2", "Edmonton", "AB", "T5J0N3", "Canada")
        val user = User(
            uid = "u1",
            username = "olive_user",
            fullName = "Full Name",
            affectedNames = "Affected",
            dob = "2000-01-01",
            language = "English",
            situation = "Situation",
            region = "Edmonton",
            triggerHash = "hash",
            contacts = listOf(contact),
            address = firestoreAddress,
            coverApp = "NEWS",
        )
        assertEquals("u1", user.uid)
        assertEquals("olive_user", user.username)
        assertEquals("Full Name", user.fullName)
        assertEquals("Affected", user.affectedNames)
        assertEquals("2000-01-01", user.dob)
        assertEquals("English", user.language)
        assertEquals("Situation", user.situation)
        assertEquals("Edmonton", user.region)
        assertEquals("hash", user.triggerHash)
        assertEquals("c1", user.contacts.single().id)
        assertEquals("Amina", user.contacts.single().name)
        assertEquals("555-0100", user.contacts.single().phone)
        assertEquals("Sister", user.contacts.single().details)
        assertEquals("1 St", user.address?.line1)
        assertEquals("Apt 2", user.address?.line2)
        assertEquals("Edmonton", user.address?.city)
        assertEquals("AB", user.address?.province)
        assertEquals("T5J0N3", user.address?.postalCode)
        assertEquals("Canada", user.address?.country)
        assertEquals("NEWS", user.coverApp)

        val defaultUser = User()
        assertEquals("", defaultUser.uid)
        val defaultContact = Contact()
        assertEquals("", defaultContact.id)
        val defaultAddr = Address()
        assertEquals("", defaultAddr.country)

        val profile = UserProfile(
            username = "olive_user",
            fullName = "Renée",
            contacts = listOf(SafeContact("c1", "Amina", "555-0100", true, "uid-2", "Sister")),
            address = DeliveryAddress(city = "Edmonton"),
            role = AccountRole.SAFE_CONTACT,
            coverType = CoverType.NEWS,
            email = "a@b.ca",
            phone = "555-0101",
        )
        val map = profile.toMap()
        assertEquals("olive_user", map["username"])
        assertEquals("SAFE_CONTACT", map["role"])
        assertEquals("NEWS", map["coverType"])
        assertEquals(FieldChange("Title", "A", "B"), FieldChange.fromJson(FieldChange("Title", "A", "B").toJson()))
    }

    @Test
    fun localAccountRepositoryManagesAccountsProfilesAndPasswords() = runBlocking {
        TestAndroidKeyStore.install().use {
            val app = RuntimeEnvironment.getApplication()
            val vault = EncryptedVault(app, "local-account-test")
            val repo = LocalAccountRepository(vault)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.create("!", "ValidPass!123", AccountRole.USER) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.create("olive_local", "weak", AccountRole.USER) }
            }

            val id = repo.create("Olive_Local", "ValidPass!123", AccountRole.USER)
            assertEquals("olive_local", id.username)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repo.create("olive_local", "ValidPass!123", AccountRole.SAFE_CONTACT) }
            }

            assertEquals(id, repo.authenticate("olive_local", "ValidPass!123", AccountRole.USER))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repo.authenticate("olive_local", "WrongPass!123", AccountRole.USER) }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repo.authenticate("missing_user", "ValidPass!123", AccountRole.USER) }
            }

            val savedProfile = UserProfile(username = id.username, fullName = "Local Name", dob = "2000-01-01")
            assertNull(repo.profile(id))
            repo.saveProfile(id, savedProfile)
            assertEquals(savedProfile, repo.profile(id))

            repo.saveHistoryRecord(id, HistoryRecord("settings", "1", "{}"))
            assertTrue(repo.historyRecords(id).isEmpty())

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repo.changePassword(id, "WrongPass!123", "NewValid!123") }
            }
            repo.changePassword(id, "ValidPass!123", "NewValid!123")
            assertEquals(id, repo.authenticate("olive_local", "NewValid!123", AccountRole.USER))

            assertNull(repo.authenticatedUid())
            assertNull(repo.authenticatedUsername())
            repo.signOut()
        }
    }
}
