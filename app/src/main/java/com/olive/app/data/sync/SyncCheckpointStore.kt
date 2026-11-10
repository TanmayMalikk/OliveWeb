// Signed-in accounts: persists account-scoped reconciliation checkpoints in Keystore-protected storage.
package com.olive.app.data.sync

import android.content.Context
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import com.olive.app.security.child
import org.json.JSONObject

internal class SyncCheckpointStore(
    private val vault: JsonVault,
) : SyncCheckpointDataSource {
    constructor(context: Context) : this(EncryptedVault(context.applicationContext, "priority-sync"))

    override fun read(uid: String): SyncCheckpoint {
        require(validUid(uid))
        val value = runCatching { vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(uid) }
            .getOrNull() ?: return SyncCheckpoint()
        return runCatching {
            SyncCheckpoint(
                initialized = value.optBoolean(INITIALIZED, false),
                revisions = SyncRevisions(
                    revision = value.nonNegative(REVISION),
                    account = value.nonNegative(ACCOUNT),
                    safety = value.nonNegative(SAFETY),
                    media = value.nonNegative(MEDIA),
                    conversation = value.nonNegative(CONVERSATION),
                ),
            )
        }.getOrElse {
            purge(uid)
            SyncCheckpoint()
        }
    }

    override fun write(uid: String, checkpoint: SyncCheckpoint) {
        require(validUid(uid))
        val revisions = checkpoint.revisions
        vault.update { root ->
            root.child(ACCOUNTS).put(uid, JSONObject()
                .put(INITIALIZED, checkpoint.initialized)
                .put(REVISION, revisions.revision)
                .put(ACCOUNT, revisions.account)
                .put(SAFETY, revisions.safety)
                .put(MEDIA, revisions.media)
                .put(CONVERSATION, revisions.conversation))
        }
    }

    override fun purge(uid: String) {
        require(validUid(uid))
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
    }

    private fun JSONObject.nonNegative(name: String): Long = getLong(name).also { require(it >= 0) }
    private fun validUid(uid: String) = uid.isNotBlank() && uid.length <= 128

    private companion object {
        const val ACCOUNTS = "accounts"
        const val INITIALIZED = "initialized"
        const val REVISION = "revision"
        const val ACCOUNT = "accountRevision"
        const val SAFETY = "safetyRevision"
        const val MEDIA = "mediaRevision"
        const val CONVERSATION = "conversationRevision"
    }
}

