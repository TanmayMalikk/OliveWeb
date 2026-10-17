package com.olive.app.security

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.CollectionReference
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.PublicKey
import com.olive.app.data.SecureDocumentRepository

@RunWith(RobolectricTestRunner::class)
class SecureDocumentRepositoryTest {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var collection: CollectionReference
    private lateinit var document: DocumentReference
    private lateinit var repository: SecureDocumentRepository

    @Before
    fun setup() {
        // Mock Firestore to intercept the final upload map before it hits the cloud
        firestore = mockk(relaxed = true)
        collection = mockk(relaxed = true)
        document = mockk(relaxed = true)

        every { firestore.collection("privateEntries") } returns collection
        every { collection.document(any()) } returns document

        repository = SecureDocumentRepository(firestore)
    }

    @Test
    fun `test saveMultiRecipientEntry generates valid ciphertext and wrapped keys`() = runBlocking {
        // 1. Generate standard RSA Test Keys to simulate device Keystores
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val ownerKey: PublicKey = keyGen.generateKeyPair().public
        val staffKey: PublicKey = keyGen.generateKeyPair().public

        val recipientMap = mapOf(
            "user_david" to ownerKey,
            "staff_jane" to staffKey
        )

        // 2. Execute the multi-recipient encryption function
        repository.saveMultiRecipientEntry(
            entryId = "entry_123",
            ownerUid = "user_david",
            title = "Therapy Notes",
            body = "Discussed safety plan.",
            recipientPublicKeys = recipientMap
        )

        // 3. Capture the exact map sent to Firestore
        val slot = slot<Map<String, Any>>()
        verify { document.set(capture(slot), any()) }

        val uploadedData = slot.captured

        // 4. Assert Expected Output
        assertEquals("entry_123", uploadedData["id"])
        assertEquals("user_david", uploadedData["ownerUid"])

        // Assert AES Encryption occurred
        val ciphertext = uploadedData["ciphertext"] as String
        assertTrue("Ciphertext should not be empty", ciphertext.isNotEmpty())
        assertFalse("Ciphertext should not contain plaintext", ciphertext.contains("Therapy Notes"))

        // Assert Multi-Recipient RSA Wrapping occurred
        @Suppress("UNCHECKED_CAST")
        val wrappedKeys = uploadedData["wrappedKeys"] as Map<String, String>
        assertEquals("Should contain exactly 2 wrapped keys", 2, wrappedKeys.size)
        assertTrue("Owner key wrapped", wrappedKeys.containsKey("user_david"))
        assertTrue("Staff key wrapped", wrappedKeys.containsKey("staff_jane"))
        assertNotEquals("Wrapped keys should be cryptographically unique", wrappedKeys["user_david"], wrappedKeys["staff_jane"])

        // Assert Security Rules array matches
        @Suppress("UNCHECKED_CAST")
        val allowedUsers = uploadedData["allowedUsers"] as List<String>
        assertTrue(allowedUsers.containsAll(listOf("user_david", "staff_jane")))

        println("TEST PASSED: Data encrypted and formatted correctly for Firestore rules.")
    }
}