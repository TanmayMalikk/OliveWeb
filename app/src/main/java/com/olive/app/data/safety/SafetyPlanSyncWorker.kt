// Safe-app users and support staff: handles background safety plan sync work.
package com.olive.app.data.safety

import android.content.Context
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.BuildConfig
import com.olive.app.data.account.SafeContact
import com.olive.app.data.network.hasValidatedInternetConnection
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
import com.olive.app.data.media.MediaRepository

private const val SAFETY_SYNC_TRAILING_DELAY_MS = 250L
private const val SAFETY_SYNC_MAXIMUM_DELAY_MS = 1_000L

/**
 * Coalesces live typing while guaranteeing that continuous edits still reach the cloud once per second.
 *
 * @param firstEditAt First edit at supplied to [safetyPlanSyncDelayMillis].
 * @param lastEditAt Last edit at supplied to [safetyPlanSyncDelayMillis].
 * @param now Current epoch timestamp in milliseconds.
 * @param immediate Whether to apply immediate.
 * @return The computed safety plan sync delay milliseconds numeric value.
 */
internal fun safetyPlanSyncDelayMillis(
    firstEditAt: Long,
    lastEditAt: Long,
    now: Long,
    immediate: Boolean = false,
): Long = if (immediate) 0 else minOf(
    (lastEditAt + SAFETY_SYNC_TRAILING_DELAY_MS - now).coerceAtLeast(0),
    (firstEditAt + SAFETY_SYNC_MAXIMUM_DELAY_MS - now).coerceAtLeast(0),
)


// Each upload reads the durable encrypted queue. Replacing a worker is safe because mutations are idempotent.
class SafetyPlanSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    /**
     * Uploads safety-plan changes that were queued while the app was offline.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override suspend fun doWork(): Result {
        if (!BuildConfig.FIREBASE_CONFIGURED) return Result.success()
        val actor = inputData.getString("actor") ?: return Result.failure()
        val owner = inputData.getString("owner") ?: return Result.failure()
        if (FirebaseAuth.getInstance().currentUser?.uid != actor) return Result.success()
        if (!hasValidatedInternetConnection(applicationContext)) return Result.retry()
        return try {
            val store = SafetyPlanStore(applicationContext, false)
            val repository = SafetyPlanRepository()
            val attachment = inputData.getString(ATTACHMENT)
            withTimeout(40.seconds) {
                if (attachment == null) syncSafetyPlan(store, repository, actor, owner)
                else publishAttachment(store, repository, owner, attachment)
            }
            if (store.read(actor, owner)?.pending == true) Result.retry() else Result.success()
        } catch (_: TimeoutCancellationException) {
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirebaseFunctionsException) {
            // Returns the correct WorkManager result for a completed, retryable, or failed sync.
            when (error.code) {
                FirebaseFunctionsException.Code.PERMISSION_DENIED if actor != owner -> {
                    SafetyPlanStore(applicationContext, false).remove(
                        actor,
                        owner
                    )
                    Result.success()
                }
                in setOf(
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                    FirebaseFunctionsException.Code.NOT_FOUND
                ) -> Result.failure()
                else -> Result.retry()
            }
        } catch (_: Exception) { // Network and server failures are temporary; WorkManager retries the encrypted sync later.
            Result.retry()
        }
    }

    /**
     * Publishes attachment.
     *
     * @param store Store supplied to [publishAttachment].
     * @param repository Repository supplied to [publishAttachment].
     * @param owner Owner supplied to [publishAttachment].
     * @param id ID supplied to [publishAttachment].
     */
    private suspend fun publishAttachment(store: SafetyPlanStore, repository: SafetyPlanRepository, owner: String, id: String) {
        val documents = SafetyDocumentRepository(media = MediaRepository())
        val document = documents.getFor(owner, id)
        if (store.read(owner, owner) == null) {
            val remote = repository.get(owner) ?: return
            store.mergeRemote(owner, remote)
        }
        store.update(owner, owner) { plan ->
            plan.copy(fields = plan.fields.mapValues { (key, field) ->
                val value = safetyChecklistWithPublishedDocuments(key, field.value, listOf(document))
                if (value == field.value || value.length > 4000 || field.conflictRevision != null) field
                else field.edit(value)
            })
        }
        syncSafetyPlan(store, repository, owner, owner)
        val confirmed = repository.get(owner) ?: return
        val attached = confirmed.fields.filterKeys { it.endsWith("_documents") }.values.any { field ->
            readSafetyChecklist(field.value).any { id in it.attachmentIds && id !in it.excludedAttachmentIds }
        }
        if (!attached) return
        var failure: Exception? = null
        repository.mediaRecipients().forEach { (uid, username) ->
            if (FirebaseAuth.getInstance().currentUser?.uid != owner) return
            try {
                documents.grantAccess(document, owner, uid, username, forPlan = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val ATTACHMENT = "attachment"

        /**
         * Builds and enqueues a connected-network [SafetyPlanSyncWorker] request.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param uniqueWorkName Unique WorkManager queue name.
         * @param policy Replacement policy for queued work.
         * @param input Input data passed to the worker.
         * @param initialDelaySeconds Optional initial delay and exponential backoff toggle.
         */
        private fun enqueueWork(
            context: Context,
            uniqueWorkName: String,
            policy: ExistingWorkPolicy,
            input: Data,
            initialDelaySeconds: Long? = null,
        ) {
            val builder = OneTimeWorkRequestBuilder<SafetyPlanSyncWorker>()
                .setInputData(input)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            if (initialDelaySeconds != null) {
                builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName, policy, builder.build())
        }

        /**
         * Enqueues attachment.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param owner Owner supplied to [enqueueAttachment].
         * @param documentId Unique identifier of the safety document.
         */
        fun enqueueAttachment(context: Context, owner: String, documentId: String) {
            if (!BuildConfig.FIREBASE_CONFIGURED) return
            enqueueWork(
                context = context,
                uniqueWorkName = "olive_safety_attachment_${owner}_${documentId}",
                policy = ExistingWorkPolicy.KEEP,
                input = workDataOf("actor" to owner, "owner" to owner, ATTACHMENT to documentId),
            )
        }
        /**
         * Queues a background safety-plan sync without creating duplicate work.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param actor Actor supplied to [enqueue].
         * @param owner Owner supplied to [enqueue].
         */
        fun enqueue(context: Context, actor: String, owner: String) {
            if (!BuildConfig.FIREBASE_CONFIGURED) return
            enqueueWork(
                context = context,
                uniqueWorkName = "olive_safety_${actor}_$owner",
                policy = ExistingWorkPolicy.REPLACE,
                input = workDataOf("actor" to actor, "owner" to owner),
                initialDelaySeconds = 1L,
            )
        }
    }
}

/**
 * Uploads queued encrypted safety-plan edits and clears only changes confirmed by the server.
 *
 * @param store Store supplied to [syncSafetyPlan].
 * @param repository Repository supplied to [syncSafetyPlan].
 * @param actor Actor supplied to [syncSafetyPlan].
 * @param owner Owner supplied to [syncSafetyPlan].
 */
internal suspend fun syncSafetyPlan(
    store: SafetyPlanStore,
    repository: SafetyPlanRepository,
    actor: String,
    owner: String
) {
    val sent = store.read(actor, owner) ?: return
    val remote = repository.sync(sent)
    val merged = store.update(actor, owner) { mergeSafetyPlan(it, sent, remote, ownerEditing = actor == owner) }
    val action = merged.shareAction
    if (actor == owner && action.isNotEmpty() && (action == "stop" || !merged.hasEdits)) {
        val result = repository.consult(action)
        store.update(actor, owner) { current ->
            val completed = action == "stop" || result.staffUid.isNotBlank()
            mergeSafetyPlan(current, merged, result).copy(
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

/**
 * Identifies the exact safety-plan checklist row that created an uploaded document.
 *
 * @param fieldKey Field key supplied to [safetyChecklistAttachmentTag].
 * @param itemId Item ID supplied to [safetyChecklistAttachmentTag].
 * @return The resulting safety checklist attachment tag string.
 */
internal fun safetyChecklistAttachmentTag(fieldKey: String, itemId: String): String =
    "$SAFETY_ATTACHMENT_PREFIX$fieldKey:$itemId".take(80)

/**
 * Computes safety checklist with published documents.
 *
 * @param fieldKey Field key supplied to [safetyChecklistWithPublishedDocuments].
 * @param value Input value to store, parse, or format.
 * @param documents Collection of documents values to process.
 * @return The resulting safety checklist with published documents string.
 */
internal fun safetyChecklistWithPublishedDocuments(fieldKey: String, value: String, documents: List<SafetyDocument>): String {
    if (!fieldKey.endsWith("_documents")) return value
    val items = readSafetyChecklist(value)
    val updated = items.map { item ->
        val tag = safetyChecklistAttachmentTag(fieldKey, item.id)
        val uploaded = documents.filter { tag in it.tags && it.id !in item.excludedAttachmentIds }
        item.copy(attachmentIds = (item.attachmentIds + uploaded.map { it.id }).distinct().takeLast(80))
    }
    return if (updated == items) value else writeSafetyChecklist(updated)
}

/**
 * Computes safety checklist attachments.
 *
 * @param fieldKey Field key supplied to [safetyChecklistAttachments].
 * @param item Item supplied to [safetyChecklistAttachments].
 * @param documents Collection of documents values to process.
 * @return List of resulting safety checklist attachments items.
 */
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

/**
 * Creates one trusted-contact checklist item for a new safety plan.
 *
 * @param contact Trusted or emergency contact record.
 * @return The resulting safety plan contact item.
 */
internal fun safetyPlanContactItem(contact: SafeContact) = SafetyChecklistItem(
    SAFETY_CONTACT_PREFIX + contact.id,
    JSONObject().put("name", contact.name).put("details", contact.details).toString()
)

/**
 * Parses one contact row stored inside the safety-plan checklist format.
 *
 * @param item Item supplied to [readSafetyPlanContact].
 * @return The resulting [SafetyPlanContact], or `null` if unavailable.
 */
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

/**
 * Checklist rows remain inside each existing section's revision, so offline/staff conflict handling stays compatible.
 *
 * @param raw Raw supplied to [readSafetyChecklist].
 * @param legacyExtras Collection of legacy extras values to process.
 * @return List of resulting read safety checklist items.
 */
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

/**
 * Encodes a safety checklist so it can be stored in one plan field.
 *
 * @param items Collection of items values to process.
 * @return The resulting write safety checklist string.
 */
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

/**
 * Creates the short checklist summary shown before opening a safety-plan section.
 *
 * @param raw Raw supplied to [safetyChecklistPreview].
 * @return The resulting safety checklist preview.
 */
internal fun safetyChecklistPreview(raw: String) = readSafetyChecklist(raw).joinToString("\n") {
    readSafetyPlanContact(it)?.let { contact -> "• ${contact.name}: ${contact.details}" }
        ?: ((if (it.checked) "☑ " else "☐ ") + it.text)
}

// Safe-app users and support staff: keeps safety-plan drafts on the device.

// Separate encrypted vaults isolate local university accounts from Firebase accounts and each signed-in actor's cache.
internal class SafetyPlanStore(context: Context, localMode: Boolean) {
    private val vault = EncryptedVault(context, if (localMode) "safety-demo" else "safety-firebase")
    /**
     * Reads one account's safety plan from encrypted local storage.
     *
     * @param actorUid Firebase UID of the account performing the action.
     * @param ownerUid Firebase UID of the resource owner.
     * @return The resulting read.
     */
    fun read(actorUid: String, ownerUid: String) =
        vault.read().optJSONObject(actorUid)?.optJSONObject(ownerUid)?.let(SafetyPlan.Companion::fromJson)

    /**
     * Creates a blank safety plan only when this account does not have one yet.
     *
     * @param actorUid Firebase UID of the account performing the action.
     * @param ownerUid Firebase UID of the resource owner.
     * @param ownerName Owner name supplied to [ensure].
     * @param defaults Key-value mapping of defaults entries.
     * @return The resulting [SafetyPlan] instance.
     */
    fun ensure(
        actorUid: String,
        ownerUid: String,
        ownerName: String,
        defaults: Map<String, String>
    ): SafetyPlan {
        vault.update { root ->
            val actor = root.child(actorUid)
            val existing = actor.optJSONObject(ownerUid)?.let(SafetyPlan.Companion::fromJson)
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

    /**
     * Replaces one saved safety plan while holding the encrypted-store lock.
     *
     * @param actorUid Firebase UID of the account performing the action.
     * @param ownerUid Firebase UID of the resource owner.
     * @param change Function used to transform or provide change.
     * @return The resulting [SafetyPlan] instance.
     */
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

    /**
     * Reconciles a server snapshot with the latest draft under the same encrypted-store lock.
     *
     * @param actorUid Firebase UID of the account performing the action.
     * @param remote Remote supplied to [mergeRemote].
     * @return The resulting [SafetyPlan] for merge remote.
     */
    fun mergeRemote(actorUid: String, remote: SafetyPlan): SafetyPlan {
        var merged = remote
        vault.update { root ->
            val actor = root.child(actorUid)
            val local = actor.optJSONObject(remote.ownerUid)?.let(SafetyPlan.Companion::fromJson)
            if (local != null) {
                val combined = local.copy(fields = remote.fields + local.fields)
                merged = mergeSafetyPlan(combined, combined, remote, ownerEditing = actorUid == remote.ownerUid)
            }
            actor.put(remote.ownerUid, merged.toJson())
        }
        return merged
    }

    /**
     * Replaces one safety plan in encrypted local storage.
     *
     * @param actorUid Firebase UID of the account performing the action.
     * @param ownerUid Firebase UID of the resource owner.
     * @param value Input value to store, parse, or format.
     */
    fun write(actorUid: String, ownerUid: String, value: SafetyPlan) {
        require(value.ownerUid == ownerUid)
        vault.update { root -> root.child(actorUid).put(ownerUid, value.toJson()) }
    }

    /**
     * Loads every saved safety-plan section for the signed-in account.
     *
     * @param actorUid Firebase UID of the account performing the action.
     * @return List of resulting all items.
     */
    fun all(actorUid: String): List<SafetyPlan> {
        val actor = vault.read().optJSONObject(actorUid) ?: return emptyList()
        return actor.keys().asSequence()
            .mapNotNull { actor.optJSONObject(it)?.let(SafetyPlan.Companion::fromJson) }.toList()
    }

    /**
     * Removes one locally cached safety plan for this signed-in actor.
     *
     * @param actorUid Firebase UID of the account performing the action.
     * @param ownerUid Firebase UID of the resource owner.
     */
    fun remove(actorUid: String, ownerUid: String) {
        vault.update { it.optJSONObject(actorUid)?.remove(ownerUid) }
    }

    /**
     * Removes every cached plan belonging to one signed-in actor.
     *
     * @param actorUid Firebase UID of the account performing the action.
     */
    fun purge(actorUid: String) {
        vault.update { it.remove(actorUid) }
    }
}

internal data class SafetyChecklistMerge(
    val localValue: String,
    val remoteValue: String,
    val conflicted: Boolean,
)

/**
 * Computes checklist rows.
 *
 * @param value Input value to store, parse, or format, or null when not provided.
 * @return Map containing the resulting checklist rows entries.
 */
private fun checklistRows(value: String?): Map<String, Map<String, Any?>>? = runCatching {
    val document = JSONObject(requireNotNull(value))
    require(document.optInt("oliveSafetyChecklist") == 1)
    val items = document.getJSONArray("items")
    require(items.length() <= 80)
    val result = linkedMapOf<String, Map<String, Any?>>()
    repeat(items.length()) { index ->
        val row = items.getJSONObject(index)
        val id = row.getString("id")
        require(id.isNotBlank() && id !in result && row.get("text") is String && row.get("checked") is Boolean)
        result[id] = row.keys().asSequence().associateWith { key ->
            when (val property = row.get(key)) {
                is JSONArray -> (0 until property.length()).map { property.get(it) }
                JSONObject.NULL -> null
                else -> property
            }
        }
    }
    result
}.getOrNull()

/**
 * Merge individual checklist properties against the text visible when editing began.
 *
 * @param baseValue Optional base value supplied to [mergeSafetyChecklist].
 * @param localValue Local value supplied to [mergeSafetyChecklist].
 * @param remoteValue Remote value supplied to [mergeSafetyChecklist].
 * @return The resulting [SafetyChecklistMerge], or `null` if unavailable.
 */
internal fun mergeSafetyChecklist(baseValue: String?, localValue: String, remoteValue: String): SafetyChecklistMerge? {
    val base = checklistRows(baseValue) ?: return null
    val local = checklistRows(localValue) ?: return null
    val remote = checklistRows(remoteValue) ?: return null
    val mine = mutableListOf<Map<String, Any?>>()
    val incoming = mutableListOf<Map<String, Any?>>()
    var conflicted = false
    for (id in remote.keys + local.keys) {
        val before = base[id]
        val a = local[id]
        val b = remote[id]
        val localRow: Map<String, Any?>?
        val remoteRow: Map<String, Any?>?
        when {
            a == before -> { localRow = b; remoteRow = b }
            b == before || a == b -> { localRow = a; remoteRow = a }
            a == null || b == null -> {
                conflicted = true
                localRow = a
                remoteRow = b
            }
            else -> {
                val localProperties = linkedMapOf<String, Any?>()
                val remoteProperties = linkedMapOf<String, Any?>()
                for (key in before.orEmpty().keys + a.keys + b.keys) {
                    val localProperty: Any?
                    val remoteProperty: Any?
                    when {
                        a[key] == before?.get(key) -> { localProperty = b[key]; remoteProperty = b[key] }
                        b[key] == before?.get(key) || a[key] == b[key] -> { localProperty = a[key]; remoteProperty = a[key] }
                        key in setOf("attachmentIds", "excludedAttachmentIds") -> {
                            val first = a[key] as? List<*>
                            val second = b[key] as? List<*>
                            val beforeList = before?.get(key)
                            if (first != null && second != null && (beforeList == null || beforeList is List<*>)) {
                                val original = beforeList.orEmpty()
                                val removed = original.filter { it !in first || it !in second }.toSet()
                                localProperty = (second + first).distinct().filter { it !in removed }
                                remoteProperty = localProperty
                            } else {
                                conflicted = true
                                localProperty = a[key]
                                remoteProperty = b[key]
                            }
                        }
                        else -> {
                            conflicted = true
                            localProperty = a[key]
                            remoteProperty = b[key]
                        }
                    }
                    if (localProperty != null) localProperties[key] = localProperty
                    if (remoteProperty != null) remoteProperties[key] = remoteProperty
                }
                localRow = localProperties
                remoteRow = remoteProperties
            }
        }
        localRow?.let(mine::add)
        remoteRow?.let(incoming::add)
    }
    /**
     * Encodes the target.
     *
     * @param rows Collection of rows values to process.
     * @return The resulting encode.
     */
    fun encode(rows: List<Map<String, Any?>>) = JSONObject().put("oliveSafetyChecklist", 1)
        .put("items", JSONArray(rows.map { JSONObject(it) })).toString()
    val result = SafetyChecklistMerge(encode(mine), encode(incoming), conflicted)
    return result.takeIf { mine.size <= 80 && incoming.size <= 80 && it.localValue.length <= 4000 && it.remoteValue.length <= 4000 }
}
