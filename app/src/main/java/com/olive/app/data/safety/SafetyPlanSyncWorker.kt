// Safe-app users and support staff: handles background safety plan sync work.
package com.olive.app.data

import android.content.Context
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child


// Each upload reads the durable encrypted queue. Replacing a worker is safe because mutations are idempotent.
class SafetyPlanSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    // Uploads safety-plan changes that were queued while the app was offline.
    override suspend fun doWork(): Result {
        if (!BuildConfig.FIREBASE_CONFIGURED) return Result.success()
        val actor = inputData.getString("actor") ?: return Result.failure()
        val owner = inputData.getString("owner") ?: return Result.failure()
        if (FirebaseAuth.getInstance().currentUser?.uid != actor) return Result.success()
        if (!hasValidatedInternetConnection(applicationContext)) return Result.retry()
        return try {
            withTimeout(40.seconds) {
                syncSafetyPlan(
                    SafetyPlanStore(applicationContext, false),
                    SafetyPlanRepository(),
                    actor,
                    owner
                )
            }
            if (SafetyPlanStore(applicationContext, false).read(
                    actor,
                    owner
                )?.pending == true
            ) Result.retry() else Result.success()
        } catch (_: TimeoutCancellationException) {
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirebaseFunctionsException) {
            // Returns the correct WorkManager result for a completed, retryable, or failed sync.
            when {
                error.code == FirebaseFunctionsException.Code.PERMISSION_DENIED && actor != owner -> {
                    SafetyPlanStore(applicationContext, false).remove(
                        actor,
                        owner
                    )
                    Result.success()
                }

                error.code in setOf(
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                    FirebaseFunctionsException.Code.NOT_FOUND
                ) -> Result.failure()

                else -> Result.retry()
            }
        } catch (_: Exception) { // Network and server failures are temporary; WorkManager retries the encrypted sync later.
            Result.retry()
        }
    }

    companion object {
        // Queues a background safety-plan sync without creating duplicate work.
        fun enqueue(context: Context, actor: String, owner: String) {
            if (!BuildConfig.FIREBASE_CONFIGURED) return
            val request = OneTimeWorkRequestBuilder<SafetyPlanSyncWorker>().setInputData(
                workDataOf(
                    "actor" to actor,
                    "owner" to owner
                )
            )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInitialDelay(1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "olive_safety_${actor}_$owner",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

// Uploads queued encrypted safety-plan edits and clears only changes confirmed by the server.
internal suspend fun syncSafetyPlan(
    store: SafetyPlanStore,
    repository: SafetyPlanRepository,
    actor: String,
    owner: String
) {
    val sent = store.read(actor, owner) ?: return
    val remote = repository.sync(sent)
    val merged = store.update(actor, owner) { mergeSafetyPlan(it, sent, remote) }
    val action = merged.shareAction
    if (actor == owner && action.isNotEmpty() && (action == "stop" || !merged.hasEdits)) {
        val result = repository.consult(action)
        store.update(actor, owner) { current ->
            val completed = action == "stop" || result.staffUid.isNotBlank()
            current.copy(
                staffUid = result.staffUid,
                staffName = result.staffName,
                consultationRequested = result.consultationRequested,
                shareAction = if (current.shareAction == action && completed) "" else current.shareAction
            )
        }
    }
}

// Safe-app users and support staff: defines safety steps shown to users.

internal data class SafetyChecklistItem(
    val id: String,
    val text: String,
    val checked: Boolean = false,
    val attachmentIds: List<String> = emptyList(),
    val excludedAttachmentIds: List<String> = emptyList(),
)

internal data class SafetyPlanContact(val id: String, val name: String, val details: String)

private const val SAFETY_CONTACT_PREFIX = "trusted-contact:"
private const val SAFETY_ATTACHMENT_PREFIX = "safety-plan:"

/** Identifies the exact safety-plan checklist row that created an uploaded document. */
internal fun safetyChecklistAttachmentTag(fieldKey: String, itemId: String): String =
    "$SAFETY_ATTACHMENT_PREFIX$fieldKey:$itemId".take(80)

internal fun safetyChecklistAttachments(
    fieldKey: String,
    item: SafetyChecklistItem,
    documents: List<SafetyDocument>,
): List<SafetyDocument> {
    val tag = safetyChecklistAttachmentTag(fieldKey, item.id)
    return documents.filter {
        (tag in it.tags || it.id in item.attachmentIds) && it.id !in item.excludedAttachmentIds
    }
}

// Creates one trusted-contact checklist item for a new safety plan.
internal fun safetyPlanContactItem(contact: SafeContact) = SafetyChecklistItem(
    SAFETY_CONTACT_PREFIX + contact.id,
    JSONObject().put("name", contact.name).put("details", contact.details).toString()
)

// Parses one contact row stored inside the safety-plan checklist format.
internal fun readSafetyPlanContact(item: SafetyChecklistItem): SafetyPlanContact? {
    if (!item.id.startsWith(SAFETY_CONTACT_PREFIX)) return null
    return runCatching {
        val json = JSONObject(item.text)
        SafetyPlanContact(
            item.id.removePrefix(SAFETY_CONTACT_PREFIX),
            json.getString("name"),
            json.getString("details")
        )
    }.getOrNull()
}

// Checklist rows remain inside each existing section's revision, so offline/staff conflict handling stays compatible.
internal fun readSafetyChecklist(
    raw: String,
    legacyExtras: List<String> = emptyList()
): List<SafetyChecklistItem> {
    val saved = runCatching {
        val json = JSONObject(raw)
        require(json.optInt("oliveSafetyChecklist") == 1)
        val rows = json.getJSONArray("items")
        require(rows.length() <= 80)
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            SafetyChecklistItem(
                row.getString("id"),
                row.getString("text"),
                row.getBoolean("checked"),
                row.optJSONArray("attachmentIds")?.let { values ->
                    require(values.length() <= 80)
                    (0 until values.length()).map(values::getString)
                        .onEach { require(it.length <= 128) }
                        .distinct()
                }.orEmpty(),
                row.optJSONArray("excludedAttachmentIds")?.let { values ->
                    require(values.length() <= 80)
                    (0 until values.length()).map(values::getString)
                        .onEach { require(it.length <= 128) }
                        .distinct()
                }.orEmpty(),
            )
        }.also { items -> require(items.map { it.id }.distinct().size == items.size) }
    }.getOrNull()
    if (saved != null) return saved
    // Older free-text notes are retained verbatim; document suggestions are added only on this first conversion.
    return (listOf(raw).filter(String::isNotBlank) + legacyExtras).mapIndexed { index, text ->
        SafetyChecklistItem(
            UUID.nameUUIDFromBytes("$index:$text".toByteArray(Charsets.UTF_8)).toString(), text
        )
    }
}

// Encodes a safety checklist so it can be stored in one plan field.
internal fun writeSafetyChecklist(items: List<SafetyChecklistItem>): String =
    JSONObject().put("oliveSafetyChecklist", 1)
        .put("items", JSONArray().also { rows ->
            items.forEach { item ->
                rows.put(
                    JSONObject().put("id", item.id).put("text", item.text)
                        .put("checked", item.checked)
                        .put(
                            "attachmentIds",
                            JSONArray(item.attachmentIds.distinct().takeLast(80)),
                        )
                        .put(
                            "excludedAttachmentIds",
                            JSONArray(item.excludedAttachmentIds.distinct().takeLast(80)),
                        )
                )
            }
        }).toString()

// Creates the short checklist summary shown before opening a safety-plan section.
internal fun safetyChecklistPreview(raw: String) = readSafetyChecklist(raw).joinToString("\n") {
    readSafetyPlanContact(it)?.let { contact -> "• ${contact.name}: ${contact.details}" }
        ?: ((if (it.checked) "☑ " else "☐ ") + it.text)
}

// Safe-app users and support staff: keeps safety-plan drafts on the device.

// Separate encrypted vaults isolate local university accounts from Firebase accounts and each signed-in actor's cache.
internal class SafetyPlanStore(context: Context, localMode: Boolean) {
    private val vault = EncryptedVault(context, if (localMode) "safety-demo" else "safety-firebase")
    // Reads one account's safety plan from encrypted local storage.
    fun read(actorUid: String, ownerUid: String) =
        vault.read().optJSONObject(actorUid)?.optJSONObject(ownerUid)?.let(SafetyPlan::fromJson)

    // Creates a blank safety plan only when this account does not have one yet.
    fun ensure(
        actorUid: String,
        ownerUid: String,
        ownerName: String,
        defaults: Map<String, String>
    ): SafetyPlan {
        vault.update { root ->
            val actor = root.child(actorUid)
            val existing = actor.optJSONObject(ownerUid)?.let(SafetyPlan::fromJson)
            val fields = safetyFieldsWithDefaults(existing?.fields.orEmpty(), defaults)
            actor.put(
                ownerUid,
                (existing?.copy(fields = fields) ?: SafetyPlan(
                    ownerUid,
                    ownerName,
                    fields
                )).toJson()
            )
        }
        return checkNotNull(read(actorUid, ownerUid))
    }

    // Replaces one saved safety plan while holding the encrypted-store lock.
    fun update(actorUid: String, ownerUid: String, change: (SafetyPlan) -> SafetyPlan): SafetyPlan {
        var updated: SafetyPlan? = null
        vault.update { root ->
            val actor = root.child(actorUid)
            val old = actor.optJSONObject(ownerUid) ?: error("Plan has not been loaded")
            val next = change(SafetyPlan.fromJson(old))
            updated = next
            actor.put(ownerUid, next.toJson())
        }
        return checkNotNull(updated)
    }

    // Reconciles a server snapshot with the latest draft under the same encrypted-store lock.
    fun mergeRemote(actorUid: String, remote: SafetyPlan): SafetyPlan {
        var merged = remote
        vault.update { root ->
            val actor = root.child(actorUid)
            val local = actor.optJSONObject(remote.ownerUid)?.let(SafetyPlan::fromJson)
            if (local != null) {
                val combined = local.copy(fields = remote.fields + local.fields)
                merged = mergeSafetyPlan(combined, combined, remote)
            }
            actor.put(remote.ownerUid, merged.toJson())
        }
        return merged
    }

    // Replaces one safety plan in encrypted local storage.
    fun write(actorUid: String, ownerUid: String, value: SafetyPlan) {
        require(value.ownerUid == ownerUid)
        vault.update { root -> root.child(actorUid).put(ownerUid, value.toJson()) }
    }

    // Loads every saved safety-plan section for the signed-in account.
    fun all(actorUid: String): List<SafetyPlan> {
        val actor = vault.read().optJSONObject(actorUid) ?: return emptyList()
        return actor.keys().asSequence()
            .mapNotNull { actor.optJSONObject(it)?.let(SafetyPlan::fromJson) }.toList()
    }

    // Removes one locally cached safety plan for this signed-in actor.
    fun remove(actorUid: String, ownerUid: String) {
        vault.update { it.optJSONObject(actorUid)?.remove(ownerUid) }
    }

    // Removes every cached plan belonging to one signed-in actor.
    fun purge(actorUid: String) {
        vault.update { it.remove(actorUid) }
    }
}
