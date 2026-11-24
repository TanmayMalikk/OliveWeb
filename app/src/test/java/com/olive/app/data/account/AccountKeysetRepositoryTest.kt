package com.olive.app.data.account

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountRecoveryKey
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Tests [AccountKeysetRepository] persistence behavior against Firestore write batches. */
class AccountKeysetRepositoryTest {

    /** Verifies keyset publishing merges into accountKeysets so server-managed fields survive. */
    @Test
    fun publishMergesAccountKeysetEnvelopeToPreservePasswordRecovery() = runBlocking {
        val uid = "user-keyset-publish"
        val recoveryKey = AccountRecoveryKey.generate()
        val envelope = AccountKeysetEnvelope.create(uid, recoveryKey)

        val auth = mock(FirebaseAuth::class.java)
        val user = mock(FirebaseUser::class.java)
        `when`(user.uid).thenReturn(uid)
        `when`(auth.currentUser).thenReturn(user)

        val firestore = mock(FirebaseFirestore::class.java)
        val keysetsCollection = mock(CollectionReference::class.java)
        val directoryCollection = mock(CollectionReference::class.java)
        val keysetDoc = mock(DocumentReference::class.java)
        val directoryDoc = mock(DocumentReference::class.java)
        val batch = mock(WriteBatch::class.java)

        `when`(firestore.collection("accountKeysets")).thenReturn(keysetsCollection)
        `when`(firestore.collection("accountKeyDirectories")).thenReturn(directoryCollection)
        `when`(keysetsCollection.document(uid)).thenReturn(keysetDoc)
        `when`(directoryCollection.document(uid)).thenReturn(directoryDoc)
        `when`(firestore.batch()).thenReturn(batch)
        `when`(batch.commit()).thenReturn(Tasks.forResult(null))

        val repository = AccountKeysetRepository(firestore = firestore, auth = auth)
        repository.publish(envelope)

        verify(batch).set(eq(keysetDoc), anyMap<String, Any>(), eq(SetOptions.merge()))
        verify(batch).commit()
        Unit
    }
}
