package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.AccountIdentity
import com.olive.app.data.ChatConversation
import com.olive.app.data.ChatMessage
import com.olive.app.data.PrivateEntry
import com.olive.app.data.PrivateEntryKind
import com.olive.app.security.KeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.PublicKey

/** Coordinates encrypted conversations and private-entry sharing without exposing keys to UI code. */
internal class SessionSecureSharing(private val runtime:SessionRuntime) {
    suspend fun roomKey(conversation:ChatConversation):javax.crypto.SecretKey {
        with(runtime) {
            val device=enrollment ?: error("No active account")
            val repository=messagingRepository ?: error("Messaging unavailable")
            val secure=secureChatRepository ?: error("Secure messaging unavailable")
            val peerUid=conversation.participantIds.first { it!=device.uid }
            val peerName=conversation.participantUsernames[peerUid] ?: error("Conversation participant unavailable")
            val peer=repository.searchUsers(peerName,device.uid).firstOrNull()
                ?: error("The other user needs to open the updated app before secure messages can be sent.")
            require(peer.publicKey.isNotBlank()) {
                "The other user needs to open the updated app before secure messages can be sent."
            }
            val keys:Map<String,PublicKey> = withContext(Dispatchers.Default) { mapOf(
                device.uid to KeyManager.getOrCreatePublicKey(),peerUid to KeyManager.decodePublicKey(peer.publicKey)) }
            return secure.ensureRoomKey(conversation,device.uid,keys)
        }
    }

    fun share(entry:PrivateEntry) {
        with(runtime) { with(state) {
            val conversation=activeConversation ?: return
            val device=enrollment ?: return
            val documents=secureDocumentRepository ?: return
            val chat=secureChatRepository ?: return
            val repository=messagingRepository ?: return
            if(messagingBusy) return
            val token=epoch
            messagingBusy=true;message=null
            scope.launch {
                try {
                    val peerUid=conversation.participantIds.first { it!=device.uid }
                    val peerName=conversation.participantUsernames[peerUid] ?: error("Participant unavailable")
                    val peer=repository.searchUsers(peerName,device.uid).firstOrNull()
                        ?: error("Recipient unavailable")
                    require(peer.publicKey.isNotBlank())
                    val recipients=withContext(Dispatchers.Default) { mapOf(
                        device.uid to KeyManager.getOrCreatePublicKey(),peerUid to KeyManager.decodePublicKey(peer.publicKey)) }
                    documents.saveMultiRecipientEntry(entry.id,device.uid,entry.title,entry.body,recipients)
                    val prefix=if(entry.kind==PrivateEntryKind.Incident) "SHARED_INCIDENT" else "SHARED_NOTE"
                    chat.send(conversation.id,AccountIdentity(device.uid,coverUsername),
                        "$prefix:${entry.id}:${entry.title.ifBlank { runtime.application.getString(R.string.untitled_note) }}",
                        roomKey(conversation))
                    if(epoch==token) message=runtime.application.getString(R.string.private_entry_shared)
                } catch(error:CancellationException) { throw error }
                catch(_:Exception) { if(epoch==token) message=runtime.application.getString(R.string.unable_to_share_private_entry) }
                finally { if(epoch==token) messagingBusy=false }
            }
        } }
    }

    fun open(entryId:String) {
        with(runtime) { with(state) {
            val uid=enrollment?.uid ?: return
            val repository=secureDocumentRepository ?: return
            if(busy) return
            val token=epoch
            busy=true;message=null;viewedSharedEntry=null
            scope.launch {
                try {
                    val entry=repository.loadAndDecryptEntry(entryId,uid)
                    if(epoch==token) viewedSharedEntry=entry
                } catch(error:CancellationException) { throw error }
                catch(_:Exception) { if(epoch==token) message=runtime.application.getString(R.string.unable_to_open_shared_entry) }
                finally { if(epoch==token) busy=false }
            }
        } }
    }

    suspend fun revokeIfShared(message:ChatMessage,conversation:ChatConversation,currentUid:String) {
        val parts=message.body.split(":",limit=3)
        if(parts.firstOrNull() !in setOf("SHARED_NOTE","SHARED_INCIDENT")) return
        val entryId=parts.getOrNull(1) ?: return
        val recipient=conversation.participantIds.firstOrNull { it!=currentUid } ?: return
        runtime.secureDocumentRepository?.revokeAccessToEntry(entryId,recipient)
    }

    fun close() { runtime.state.viewedSharedEntry=null }
}
