// Safe-app users and support staff: loads and saves safety-plan edits.
package com.olive.app.data.safety

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.data.messaging.Friend
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import com.olive.app.security.child
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import com.olive.app.R
import com.olive.app.data.account.AbuseType
import com.olive.app.data.account.DependantNeed
import com.olive.app.data.account.FinancialSituation
import com.olive.app.data.account.ImmigrationStatus
import com.olive.app.data.account.SafetyCircumstance
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.account.TransportSituation
import java.util.Locale
import java.util.UUID

/**
 * Invokes a protected Firebase HTTPS callable function and parses its Map response as JSON.
 *
 * @receiver The Firebase Functions client used to execute the callable request.
 * @param name Name of the deployed Firebase HTTPS callable function.
 * @param data Request payload map forwarded to the callable function.
 * @return JSON object parsed from the callable's response map.
 */
internal suspend fun FirebaseFunctions.callJsonObject(
    name: String,
    data: Map<String, Any> = emptyMap(),
): JSONObject {
    val result = getHttpsCallable(name).call(data).await().data as? Map<*, *>
        ?: error("Invalid response")
    return JSONObject(result)
}

// Callable Functions verify current Auth roles and sharing on every read and write; no plaintext Firestore disk cache.
internal class SafetyPlanRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    /**
     * Watches a plan that Firestore rules have already approved for this signed-in account.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observe(
        ownerUid: String,
        onUpdate: (SafetyPlan) -> Unit,
        onError: (Exception) -> Unit,
    ): ListenerRegistration = firestore.collection("safetyPlans").document(ownerUid)
        .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            if (snapshot?.metadata?.isFromCache != false) return@addSnapshotListener
            val data = snapshot.data ?: return@addSnapshotListener
            try {
                onUpdate(SafetyPlan.fromJson(JSONObject(data)))
            } catch (failure: Exception) {
                onError(failure)
            }
        }

    /**
     * Reads a server-confirmed plan without modifying any fields.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @return The resulting [SafetyPlan], or `null` if unavailable.
     */
    suspend fun get(ownerUid: String): SafetyPlan? {
        val snapshot = firestore.collection("safetyPlans").document(ownerUid)
            .get(Source.SERVER).await()
        return snapshot.data?.let { SafetyPlan.fromJson(JSONObject(it)) }
    }

    /**
     * Sends changed safety-plan fields and returns the merged server version.
     *
     * @param plan Safety plan data model.
     * @param resolution Key-value mapping of resolution entries.
     * @return The resulting [SafetyPlan] instance.
     */
    suspend fun sync(plan: SafetyPlan, resolution: Map<String, Any>? = null): SafetyPlan {
        val changes =
            plan.fields.filterValues { it.mutationId != null && it.conflictRevision == null }
                .map { (key, field) ->
                    mapOf(
                        "key" to key,
                        "value" to field.value,
                        "revision" to field.revision,
                        "mutationId" to checkNotNull(field.mutationId)
                    ) + (field.baseValue?.let { mapOf("baseValue" to it) } ?: emptyMap()) +
                        if (field.writerId != null && field.clientSequence > 0) mapOf(
                        "writerId" to field.writerId,
                        "clientSequence" to field.clientSequence,
                    ) else emptyMap()
                }
        val result = functions.callJsonObject(
            "safetyPlanSync",
            mapOf(
                "ownerUid" to plan.ownerUid,
                "defaults" to plan.fields.mapValues { it.value.value },
                "changes" to changes
            ) + (resolution?.let { mapOf("resolution" to it) } ?: emptyMap())
        )
        return SafetyPlan.fromJson(result.getJSONObject("plan"))
    }

    /**
     * Opens a new plan-consult ticket and shares the current safety plan with it.
     *
     * @param action Action supplied to [consult].
     * @return The resulting consult.
     */
    suspend fun consult(action: String) = SafetyPlan.fromJson(
        functions.callJsonObject(
            "safetyPlanConsult",
            mapOf("action" to action)
        ).getJSONObject("plan")
    )

    /**
     * Shares the safety plan with the selected trusted contacts.
     *
     * @param friends List of friend records.
     * @return The resulting share contacts.
     */
    suspend fun shareContacts(friends: List<Friend>) =
        SafetyPlan.fromJson(functions.callJsonObject("safetyPlanShareContacts", mapOf("contacts" to friends.map {
            mapOf("uid" to it.uid, "friendshipId" to it.friendshipId, "name" to it.username)
        })).getJSONObject("plan"))

    /**
     * Returns only the organization accounts that may open media attached to this user's plan.
     *
     * @return Map containing the resulting media recipients entries.
     */
    suspend fun mediaRecipients(): Map<String, String> {
        val rows = functions.callJsonObject("safetyPlanMediaRecipients").getJSONArray("recipients")
        return (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            row.getString("uid") to row.getString("username")
        }
    }

    /**
     * Starts observing recipient devices.
     *
     * @param usernames Collection of usernames values to process.
     * @param onChange Callback invoked when the value or observed state changes.
     * @return List of resulting observe recipient devices items.
     */
    fun observeRecipientDevices(usernames: Collection<String>, onChange: () -> Unit): List<ListenerRegistration> =
        usernames.distinct().map { username ->
            firestore.collection("messageDirectory").document(username).collection("devices")
                .addSnapshotListener { snapshot, error -> if (snapshot != null && error == null) onChange() }
        }

    /**
     * Starts observing access.
     *
     * @param uid Firebase UID of the target user account.
     * @param onChange Callback invoked when the value or observed state changes.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observeAccess(uid: String, onChange: () -> Unit): ListenerRegistration {
        var revision = -1L
        return firestore.collection("syncInvalidations").document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null || snapshot?.metadata?.isFromCache != false) return@addSnapshotListener
                val next = snapshot.getLong("safetyRevision") ?: 0L
                if (next != revision) {
                    revision = next
                    onChange()
                }
            }
    }

    /**
     * Saves which trusted contact should receive safety-plan support access.
     *
     * @param uid Firebase UID of the target user account.
     */
    suspend fun setSupportContact(uid: String) {
        functions.callJsonObject("safetySupportContact", mapOf("safeContactUid" to uid))
    }

    /**
     * Loads safety plans that other users shared with this trusted contact.
     *
     * @return List of resulting shared with me items.
     */
    suspend fun sharedWithMe(): List<SharedSafetyPlan> {
        val plans = functions.callJsonObject("safetyPlansSharedWithMe").getJSONArray("plans")
        return (0 until plans.length()).map { index ->
            val row = plans.getJSONObject(index)
            SharedSafetyPlan(
                row.getString("ownerUid"),
                row.optString("ownerName"),
                row.optLong("updatedAt")
            )
        }
    }

    /**
     * Loads open and archived plan-consult tickets for this user.
     *
     * @return The resulting [Pair] instance.
     */
    suspend fun consultations(): Pair<Boolean, List<SafetyConsultation>> {
        val result = functions.callJsonObject("safetyPlanConsultations")
        val plans = result.getJSONArray("plans")
        return result.getBoolean("available") to (0 until plans.length()).map { index ->
            val row = plans.getJSONObject(index)
            SafetyConsultation(
                row.getString("ownerUid"),
                row.optString("ownerName"),
                row.optString("conversationId"),
                row.optString("staffUid"),
                row.optString("staffName"),
                row.optLong("ticketNumber", -1L),
            )
        }
    }

    /**
     * Finds the staff member with the fewest active assignments.
     *
     * @param value Input value to store, parse, or format.
     */
    suspend fun available(value: Boolean) {
        functions.callJsonObject("safetyPlanAvailability", mapOf("available" to value))
    }
}

internal data class SafetyAccessIndex(
    val available: Boolean = true,
    val consultations: List<SafetyConsultation> = emptyList(),
    val sharedPlans: List<SharedSafetyPlan> = emptyList(),
)

internal class SafetyAccessIndexStore(private val vault: JsonVault) {
    constructor(context: Context) : this(EncryptedVault(context.applicationContext, "safety-access-index"))

    /**
     * Reads the safety access index store.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [SafetyAccessIndex] instance.
     */
    fun read(uid: String): SafetyAccessIndex {
        requireUid(uid)
        val value = runCatching { vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(uid) }
            .getOrNull() ?: return SafetyAccessIndex()
        return runCatching {
            SafetyAccessIndex(
                available = value.optBoolean(AVAILABLE, true),
                consultations = value.optJSONArray(CONSULTATIONS).rows { row ->
                    SafetyConsultation(row.getString("ownerUid"), row.optString("ownerName"))
                },
                sharedPlans = value.optJSONArray(SHARED).rows { row ->
                    SharedSafetyPlan(
                        row.getString("ownerUid"),
                        row.optString("ownerName"),
                        row.optLong("updatedAt"),
                    )
                },
            )
        }.getOrElse {
            purge(uid)
            SafetyAccessIndex()
        }
    }

    /**
     * Computes replace consultations.
     *
     * @param uid Firebase UID of the target user account.
     * @param available Whether to apply available.
     * @param values Collection of values values to process.
     */
    fun replaceConsultations(uid: String, available: Boolean, values: List<SafetyConsultation>) {
        requireUid(uid)
        vault.update { root -> root.child(ACCOUNTS).child(uid)
            .put(AVAILABLE, available)
            .put(CONSULTATIONS, JSONArray(values.distinctBy { it.ownerUid }.map { value ->
                JSONObject().put("ownerUid", value.ownerUid).put("ownerName", value.ownerName)
            })) }
    }

    /**
     * Stores the server-confirmed availability without replacing the consultation list.
     *
     * @param uid The account UID used to scope this operation.
     * @param available Whether this staff member can take new consultations.
     */
    fun setAvailability(uid: String, available: Boolean) {
        requireUid(uid)
        vault.update { root -> root.child(ACCOUNTS).child(uid).put(AVAILABLE, available) }
    }

    /**
     * Computes replace shared.
     *
     * @param uid Firebase UID of the target user account.
     * @param values Collection of values values to process.
     */
    fun replaceShared(uid: String, values: List<SharedSafetyPlan>) {
        requireUid(uid)
        vault.update { root -> root.child(ACCOUNTS).child(uid)
            .put(SHARED, JSONArray(values.distinctBy { it.ownerUid }.map { value ->
                JSONObject().put("ownerUid", value.ownerUid).put("ownerName", value.ownerName)
                    .put("updatedAt", value.updatedAt)
            })) }
    }

    /**
     * Purges the safety access index store.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun purge(uid: String) {
        requireUid(uid)
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
    }

    /**
     * Validates required user ID.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting require user ID.
     */
    private fun requireUid(uid: String) = require(uid.isNotBlank() && uid.length <= 128)
    /**
     * Computes rows for this JSON array.
     *
     * @receiver The optional JSON array of consultation rows.
     * @param parse Function used to transform or provide parse.
     * @param T The type of row produced by the JSON parser.
     * @return List of resulting rows items.
     */
    private fun <T> JSONArray?.rows(parse: (JSONObject) -> T): List<T> = this?.let { values ->
        (0 until values.length()).mapNotNull { index -> runCatching { parse(values.getJSONObject(index)) }.getOrNull() }
    }.orEmpty()

    private companion object {
        const val ACCOUNTS = "accounts"
        const val AVAILABLE = "available"
        const val CONSULTATIONS = "consultations"
        const val SHARED = "sharedPlans"
    }
}

// Represents one meaningful remote checklist change without exposing unrelated rows.
internal sealed interface SafetyChecklistChange {
    val id: String

    data class Added(
        override val id: String,
        val text: String,
        val checked: Boolean,
    ) : SafetyChecklistChange

    data class Removed(
        override val id: String,
        val text: String,
    ) : SafetyChecklistChange

    data class TextChanged(
        override val id: String,
        val before: String,
        val after: String,
    ) : SafetyChecklistChange

    data class CompletionChanged(
        override val id: String,
        val text: String,
        val before: Boolean,
        val after: Boolean,
    ) : SafetyChecklistChange

    data class AttachmentsChanged(
        override val id: String,
        val text: String,
    ) : SafetyChecklistChange
}

internal data class SafetyChecklistConflictPreview(
    val item: String,
    val property: String,
    val value: String,
)

/**
 * Shows only checklist row properties that differ between the owner's and incoming versions.
 *
 * @param mine Mine supplied to [safetyChecklistConflictPreview].
 * @param incoming Incoming supplied to [safetyChecklistConflictPreview].
 * @param showMine Whether to show mine.
 * @return List of resulting safety checklist conflict preview items.
 */
internal fun safetyChecklistConflictPreview(
    mine: String,
    incoming: String,
    showMine: Boolean,
): List<SafetyChecklistConflictPreview>? {
    /**
     * Parses the safety checklist conflict preview.
     *
     * @param value Input value to store, parse, or format.
     * @return List of resulting parse items.
     */
    fun parse(value: String): List<SafetyChecklistItem>? = runCatching {
        require(JSONObject(value).optInt("oliveSafetyChecklist") == 1)
        readSafetyChecklist(value)
    }.getOrNull()

    val mineRows = parse(mine) ?: return null
    val incomingRows = parse(incoming) ?: return null
    val mineById = mineRows.associateBy(SafetyChecklistItem::id)
    val incomingById = incomingRows.associateBy(SafetyChecklistItem::id)
    return buildList {
        (mineById.keys + incomingById.keys).forEach { id ->
            val yourItem = mineById[id]
            val incomingItem = incomingById[id]
            when {
                yourItem == incomingItem -> Unit
                yourItem == null || incomingItem == null -> {
                    val item = yourItem ?: incomingItem ?: return@forEach
                    add(
                        SafetyChecklistConflictPreview(
                            item = item.text,
                            property = "Step",
                            value = if (showMine) {
                                if (yourItem == null) "Removed" else "Present"
                            } else {
                                if (incomingItem == null) "Removed" else "Present"
                            },
                        ),
                    )
                }
                else -> {
                    val item = if (showMine) yourItem else incomingItem
                    if (yourItem.text != incomingItem.text) add(
                        SafetyChecklistConflictPreview(item.text, "Text", item.text),
                    )
                    if (yourItem.checked != incomingItem.checked) add(
                        SafetyChecklistConflictPreview(
                            item.text,
                            "Completion",
                            if (item.checked) "Complete" else "Not complete",
                        ),
                    )
                    if (yourItem.attachmentIds != incomingItem.attachmentIds) add(
                        SafetyChecklistConflictPreview(item.text, "Attachments", "Changed"),
                    )
                    if (yourItem.excludedAttachmentIds != incomingItem.excludedAttachmentIds) add(
                        SafetyChecklistConflictPreview(item.text, "Excluded attachments", "Changed"),
                    )
                }
            }
        }
    }
}

/**
 * Finds semantic changes by stable row ID; row order alone is not a plan change.
 *
 * @param local Collection of local values to process.
 * @param remote Collection of remote values to process.
 * @return List of resulting safety checklist incoming changes items.
 */
internal fun safetyChecklistIncomingChanges(
    local: List<SafetyChecklistItem>,
    remote: List<SafetyChecklistItem>,
): List<SafetyChecklistChange> {
    val remoteById = remote.associateBy(SafetyChecklistItem::id)
    val localIds = local.mapTo(mutableSetOf(), SafetyChecklistItem::id)
    return buildList {
        local.forEach { current ->
            val incoming = remoteById[current.id]
            if (incoming == null) {
                add(SafetyChecklistChange.Removed(current.id, current.text))
            } else {
                if (current.text != incoming.text) {
                    add(SafetyChecklistChange.TextChanged(current.id, current.text, incoming.text))
                }
                if (current.checked != incoming.checked) {
                    add(
                        SafetyChecklistChange.CompletionChanged(
                            current.id,
                            incoming.text,
                            current.checked,
                            incoming.checked,
                        ),
                    )
                }
                if (current.excludedAttachmentIds != incoming.excludedAttachmentIds) {
                    add(SafetyChecklistChange.AttachmentsChanged(current.id, incoming.text))
                }
            }
        }
        remote.filterNot { it.id in localIds }.forEach { incoming ->
            add(SafetyChecklistChange.Added(incoming.id, incoming.text, incoming.checked))
        }
    }
}

private const val MAX_SECTION_CHARS = 4000
private const val MAX_SECTION_ITEMS = 80

/**
 * Selects section-specific checklist string resource IDs from the user's [SafetyPlanIntake],
 * returning `null` for unconfigured accounts or uncustomized sections so default prompts apply.
 *
 * @param section Safety plan section identifier (e.g., `"documents"`, `"grab"`, `"finances"`).
 * @param intake Questionnaire selections recorded during onboarding or safety planning.
 * @param hasAffectedPeople Fallback indicator when affected family members were listed in the profile.
 * @return Ordered distinct string resource IDs for the section, or `null` to use legacy defaults.
 */
internal fun safetyPlanSectionPromptIds(
    section: String,
    intake: SafetyPlanIntake,
    hasAffectedPeople: Boolean = false,
): List<Int>? {
    if (!intake.completed && !intake.hasAnySelection) return null
    val ids = when (section) {
        "documents" -> documentPromptIds(intake, hasAffectedPeople)
        "grab" -> grabPromptIds(intake)
        "finances" -> financePromptIds(intake)
        "destination" -> destinationPromptIds(intake)
        "travel" -> travelPromptIds(intake)
        "aftercare" -> aftercarePromptIds(intake)
        else -> return null
    }
    return ids.distinct()
}

/**
 * Resolves the checklist strings for [section], using tailored prompts when [intake] is configured
 * and falling back to legacy defaults otherwise.
 *
 * @param context Android context used to resolve string resources.
 * @param section Safety plan section identifier.
 * @param intake Questionnaire selections recorded on the profile.
 * @param hasAffectedPeople Whether affected people were listed in onboarding personal details.
 * @return List of localized prompt strings for initializing or expanding the section.
 */
internal fun safetyPlanSectionPrompts(
    context: Context,
    section: String,
    intake: SafetyPlanIntake = SafetyPlanIntake(),
    hasAffectedPeople: Boolean = false,
): List<String> {
    val tailoredIds = safetyPlanSectionPromptIds(section, intake, hasAffectedPeople)
    if (tailoredIds != null) return tailoredIds.map(context::getString)
    return when (section) {
        "documents" -> context.resources.getStringArray(R.array.safety_document_checklist).toList() +
            if (hasAffectedPeople) listOf(context.getString(R.string.safety_documents_dependants)) else emptyList()
        "finances" -> listOf(
            context.getString(R.string.safety_finances_access),
            context.getString(R.string.safety_finances_costs),
            context.getString(R.string.safety_finances_support),
        )
        "grab" -> context.resources.getStringArray(R.array.safety_grab_checklist).toList()
        "destination" -> context.resources.getStringArray(R.array.safety_destination_checklist).toList()
        "travel" -> context.resources.getStringArray(R.array.safety_travel_checklist).toList()
        "contacts" -> context.resources.getStringArray(R.array.safety_contacts_checklist).toList()
        "aftercare" -> context.resources.getStringArray(R.array.safety_aftercare_checklist).toList()
        else -> emptyList()
    }
}

/**
 * Generates initial encoded primary and backup safety-plan sections tailored by [intake].
 *
 * @param context Android context used to resolve localized checklist strings.
 * @param intake Questionnaire selections from onboarding.
 * @param hasAffectedPeople Whether affected people were listed in onboarding personal details.
 * @return Field map ready for `SafetyPlanRepository` / `SafetyPlanStore`.
 */
internal fun safetyPlanDefaultsFromIntake(
    context: Context,
    intake: SafetyPlanIntake,
    hasAffectedPeople: Boolean = false,
): Map<String, String> = buildMap {
    val sectionItems = SAFETY_SECTIONS.associateWith { section ->
        fitEncodedChecklist(
            safetyPlanSectionPrompts(context, section, intake, hasAffectedPeople)
                .map { SafetyChecklistItem(UUID.randomUUID().toString(), it) },
        )
    }
    listOf("primary", "backup").forEach { plan ->
        SAFETY_SECTIONS.forEach { section ->
            val items = if (plan == "primary") sectionItems.getValue(section)
            else sectionItems.getValue(section).map { it.copy(id = UUID.randomUUID().toString()) }
            put("${plan}_$section", writeSafetyChecklist(items))
        }
    }
}

/**
 * Updates an encoded checklist to match [tailoredPrompts] by removing unedited items in
 * [removablePrompts] that are no longer relevant and appending missing tailored prompts, while
 * strictly preserving user edits, checkmarks, custom rows, and document attachments.
 *
 * @param existingValue Currently stored encoded checklist string.
 * @param tailoredPrompts Tailored prompts corresponding to updated questionnaire answers.
 * @param removablePrompts All questionnaire or default prompt strings eligible for unedited removal.
 * @param protectedItemIds Item IDs with linked external documents that must never be pruned.
 * @return Updated encoded checklist string within backend length and item count limits.
 */
internal fun mergeTailoredChecklistItems(
    existingValue: String,
    tailoredPrompts: List<String>,
    removablePrompts: Collection<String> = emptyList(),
    protectedItemIds: Set<String> = emptySet(),
): String {
    val existing = readSafetyChecklist(existingValue)
    val desiredNormalized = tailoredPrompts.mapNotNullTo(mutableSetOf()) { prompt ->
        prompt.normalizedPrompt().takeIf(String::isNotEmpty)
    }
    val removableNormalized = removablePrompts.mapNotNullTo(mutableSetOf()) { prompt ->
        prompt.normalizedPrompt().takeIf(String::isNotEmpty)
    }
    val kept = existing.filterNot { item ->
        val normalized = item.text.normalizedPrompt()
        !item.checked &&
            item.attachmentIds.isEmpty() &&
            item.excludedAttachmentIds.isEmpty() &&
            item.id !in protectedItemIds &&
            normalized in removableNormalized &&
            normalized !in desiredNormalized
    }
    val seenTexts = kept.mapTo(mutableSetOf()) { it.text.normalizedPrompt() }
    val merged = kept.toMutableList()
    for (prompt in tailoredPrompts) {
        if (merged.size >= MAX_SECTION_ITEMS) break
        val normalized = prompt.normalizedPrompt()
        if (normalized.isEmpty() || !seenTexts.add(normalized)) continue
        val candidate = SafetyChecklistItem(UUID.randomUUID().toString(), prompt)
        val encoded = writeSafetyChecklist(merged + candidate)
        if (encoded.length <= MAX_SECTION_CHARS) merged += candidate
    }
    return writeSafetyChecklist(merged)
}

/**
 * Resolves the complete set of built-in questionnaire and default prompt strings ever generated for
 * [section], so unedited prompts from previous answers can be removed safely on update.
 *
 * @param context Android context used to resolve localized checklist resources.
 * @param section Safety plan section identifier.
 * @return Set of localized built-in prompt strings for [section].
 */
internal fun safetyPlanManagedSectionPrompts(
    context: Context,
    section: String,
): Set<String> = buildSet {
    addAll(safetyPlanSectionPrompts(context, section, SafetyPlanIntake(completed = false), hasAffectedPeople = false))
    addAll(safetyPlanSectionPrompts(context, section, SafetyPlanIntake(completed = false), hasAffectedPeople = true))
    addAll(safetyPlanSectionPrompts(context, section, SafetyPlanIntake(completed = true), hasAffectedPeople = true))
    ImmigrationStatus.entries.forEach { status ->
        addAll(
            safetyPlanSectionPrompts(
                context,
                section,
                SafetyPlanIntake(completed = true, immigrationStatus = status),
            ),
        )
    }
    FinancialSituation.entries.forEach { financial ->
        addAll(
            safetyPlanSectionPrompts(
                context,
                section,
                SafetyPlanIntake(completed = true, financialSituation = financial),
            ),
        )
    }
    TransportSituation.entries.forEach { transport ->
        addAll(
            safetyPlanSectionPrompts(
                context,
                section,
                SafetyPlanIntake(completed = true, transportSituation = transport),
            ),
        )
    }
    addAll(
        safetyPlanSectionPrompts(
            context,
            section,
            SafetyPlanIntake(
                completed = true,
                abuseTypes = AbuseType.entries.toSet(),
                dependants = DependantNeed.entries.toSet(),
                circumstances = SafetyCircumstance.entries.toSet(),
            ),
        ),
    )
}

/**
 * Builds tailored document checklist string resource IDs from [intake].
 *
 * @param intake The recorded questionnaire answers used to tailor the plan.
 * @param hasAffectedPeople Whether the intake identifies other people affected by the situation.
 * @return The localized prompt resource IDs chosen for this checklist section.
 */
private fun documentPromptIds(intake: SafetyPlanIntake, hasAffectedPeople: Boolean): List<Int> = buildList {
    add(R.string.safety_doc_photo_id)
    add(R.string.safety_doc_birth_certificate)
    add(R.string.safety_doc_health_card)
    if (intake.immigrationStatus != ImmigrationStatus.UNDOCUMENTED_OR_UNCERTAIN) {
        add(R.string.safety_doc_sin)
    }
    when (intake.immigrationStatus) {
        ImmigrationStatus.CITIZEN -> add(R.string.safety_doc_citizen_passport)
        ImmigrationStatus.PERMANENT_RESIDENT -> add(R.string.safety_doc_pr_card)
        ImmigrationStatus.TEMPORARY_OR_SPONSORED -> {
            add(R.string.safety_doc_permit_sponsorship)
            add(R.string.safety_doc_immigration_support)
        }
        ImmigrationStatus.REFUGEE_OR_CLAIMANT -> add(R.string.safety_doc_refugee_papers)
        ImmigrationStatus.UNDOCUMENTED_OR_UNCERTAIN -> {
            add(R.string.safety_doc_any_id_copies)
            add(R.string.safety_doc_immigration_clinic)
        }
        ImmigrationStatus.PREFER_NOT_TO_SAY -> add(R.string.safety_doc_passport_generic)
    }
    if (DependantNeed.CHILDREN in intake.dependants || (!intake.completed && hasAffectedPeople && intake.dependants.isEmpty())) {
        add(R.string.safety_doc_children_records)
    }
    if (DependantNeed.ELDER_OR_ADULT_DEPENDANT in intake.dependants) {
        add(R.string.safety_doc_adult_dependant_records)
    }
    if (DependantNeed.PETS in intake.dependants) {
        add(R.string.safety_doc_pet_records)
    }
    when (intake.financialSituation) {
        FinancialSituation.INDEPENDENT -> add(R.string.safety_doc_independent_banking)
        FinancialSituation.SHARED_OR_MONITORED -> add(R.string.safety_doc_shared_banking)
        FinancialSituation.NO_INDEPENDENT_ACCESS -> add(R.string.safety_doc_financial_proof)
        FinancialSituation.PREFER_NOT_TO_SAY -> add(R.string.safety_doc_banking_generic)
    }
    if (SafetyCircumstance.LEGAL_OR_COURT_ORDERS in intake.circumstances) {
        add(R.string.safety_doc_court_orders)
    }
    if (SafetyCircumstance.MARRIAGE_OR_PARENTING in intake.circumstances) {
        add(R.string.safety_doc_marriage_custody)
    }
    if (SafetyCircumstance.PRESCRIPTIONS_OR_MEDICAL in intake.circumstances) {
        add(R.string.safety_doc_medical_records)
    }
    if (SafetyCircumstance.SHARED_LEASE_OR_MORTGAGE in intake.circumstances) {
        add(R.string.safety_doc_housing_records)
    }
    if (AbuseType.PHYSICAL in intake.abuseTypes) add(R.string.safety_doc_abuse_physical_evidence)
    if (AbuseType.EMOTIONAL_OR_PSYCHOLOGICAL in intake.abuseTypes) add(R.string.safety_doc_abuse_emotional_records)
    if (AbuseType.FINANCIAL in intake.abuseTypes) add(R.string.safety_doc_abuse_financial_records)
    if (AbuseType.SEXUAL in intake.abuseTypes) add(R.string.safety_doc_abuse_sexual_records)
    if (AbuseType.CULTURAL_OR_IMMIGRATION in intake.abuseTypes) add(R.string.safety_doc_abuse_immigration_coercion)
}

/**
 * Builds tailored quick-grab checklist string resource IDs from [intake].
 *
 * @param intake The recorded questionnaire answers used to tailor the plan.
 * @return The localized prompt resource IDs chosen for this checklist section.
 */
private fun grabPromptIds(intake: SafetyPlanIntake): List<Int> = buildList {
    add(
        if (intake.transportSituation == TransportSituation.OWN_VEHICLE) {
            R.string.safety_grab_house_and_car_keys
        } else {
            R.string.safety_grab_house_keys
        },
    )
    add(R.string.safety_grab_phone)
    add(
        when (intake.financialSituation) {
            FinancialSituation.SHARED_OR_MONITORED -> R.string.safety_grab_separate_cash
            FinancialSituation.NO_INDEPENDENT_ACCESS -> R.string.safety_grab_emergency_cash
            FinancialSituation.INDEPENDENT, FinancialSituation.PREFER_NOT_TO_SAY -> R.string.safety_grab_cash_cards
        },
    )
    if (SafetyCircumstance.PRESCRIPTIONS_OR_MEDICAL in intake.circumstances) {
        add(R.string.safety_grab_medicines)
    }
    add(R.string.safety_grab_clothes)
    if (DependantNeed.CHILDREN in intake.dependants) add(R.string.safety_grab_children)
    if (DependantNeed.ELDER_OR_ADULT_DEPENDANT in intake.dependants) add(R.string.safety_grab_adult_dependant)
    if (DependantNeed.PETS in intake.dependants) add(R.string.safety_grab_pets)
    if (AbuseType.DIGITAL_OR_STALKING in intake.abuseTypes) add(R.string.safety_grab_abuse_digital_check)
}

/**
 * Builds tailored financial checklist string resource IDs from [intake].
 *
 * @param intake The recorded questionnaire answers used to tailor the plan.
 * @return The localized prompt resource IDs chosen for this checklist section.
 */
private fun financePromptIds(intake: SafetyPlanIntake): List<Int> = buildList {
    when (intake.financialSituation) {
        FinancialSituation.INDEPENDENT -> {
            add(R.string.safety_fin_indep_passwords)
            add(R.string.safety_finances_costs)
            add(R.string.safety_fin_indep_bills)
        }
        FinancialSituation.SHARED_OR_MONITORED -> {
            add(R.string.safety_fin_shared_private_account)
            add(R.string.safety_fin_shared_cash)
            add(R.string.safety_fin_shared_document)
            add(R.string.safety_fin_emergency_aid)
        }
        FinancialSituation.NO_INDEPENDENT_ACCESS -> {
            add(R.string.safety_fin_emergency_aid)
            add(R.string.safety_fin_trusted_money_help)
            add(R.string.safety_fin_crisis_benefits)
        }
        FinancialSituation.PREFER_NOT_TO_SAY -> {
            add(R.string.safety_finances_access)
            add(R.string.safety_finances_costs)
            add(R.string.safety_finances_support)
        }
    }
    if (DependantNeed.CHILDREN in intake.dependants) add(R.string.safety_fin_children_benefits)
    if (SafetyCircumstance.SHARED_LEASE_OR_MORTGAGE in intake.circumstances) add(R.string.safety_fin_lease_rights)
    if (intake.immigrationStatus in newcomerStatuses) add(R.string.safety_fin_newcomer_support)
    if (AbuseType.FINANCIAL in intake.abuseTypes) add(R.string.safety_fin_abuse_credit_check)
}

/**
 * Builds tailored destination checklist string resource IDs from [intake].
 *
 * @param intake The recorded questionnaire answers used to tailor the plan.
 * @return The localized prompt resource IDs chosen for this checklist section.
 */
private fun destinationPromptIds(intake: SafetyPlanIntake): List<Int> = buildList {
    add(R.string.safety_dest_choose)
    add(R.string.safety_dest_address)
    add(R.string.safety_dest_when)
    if (DependantNeed.CHILDREN in intake.dependants) add(R.string.safety_dest_children)
    if (DependantNeed.ELDER_OR_ADULT_DEPENDANT in intake.dependants) add(R.string.safety_dest_adult_dependant)
    if (DependantNeed.PETS in intake.dependants) add(R.string.safety_dest_pets)
    if (AbuseType.PHYSICAL in intake.abuseTypes) add(R.string.safety_dest_abuse_physical_exit)
}

/**
 * Builds tailored travel checklist string resource IDs from [intake].
 *
 * @param intake The recorded questionnaire answers used to tailor the plan.
 * @return The localized prompt resource IDs chosen for this checklist section.
 */
private fun travelPromptIds(intake: SafetyPlanIntake): List<Int> = buildList {
    when (intake.transportSituation) {
        TransportSituation.OWN_VEHICLE -> {
            add(R.string.safety_travel_vehicle_fuel)
            add(R.string.safety_travel_spare_key)
            add(R.string.safety_travel_vehicle_backup)
        }
        TransportSituation.PUBLIC_TRANSIT -> {
            add(R.string.safety_travel_transit_routes)
            add(R.string.safety_travel_transit_fare)
            add(R.string.safety_travel_pickup_spot)
        }
        TransportSituation.NEEDS_RIDE_SUPPORT -> {
            add(R.string.safety_travel_confirm_driver)
            add(R.string.safety_travel_meeting_spot)
            add(R.string.safety_travel_backup_ride)
        }
        TransportSituation.PREFER_NOT_TO_SAY -> {
            add(R.string.safety_travel_decide)
            add(R.string.safety_travel_confirm_help)
            add(R.string.safety_travel_prepare_fare)
        }
    }
    if (DependantNeed.CHILDREN in intake.dependants) add(R.string.safety_travel_children)
    if (DependantNeed.ELDER_OR_ADULT_DEPENDANT in intake.dependants) add(R.string.safety_travel_adult_dependant)
    if (DependantNeed.PETS in intake.dependants) add(R.string.safety_travel_pets)
    if (AbuseType.DIGITAL_OR_STALKING in intake.abuseTypes) add(R.string.safety_travel_abuse_digital_tracking)
}

/**
 * Builds tailored aftercare checklist string resource IDs from [intake].
 *
 * @param intake The recorded questionnaire answers used to tailor the plan.
 * @return The localized prompt resource IDs chosen for this checklist section.
 */
private fun aftercarePromptIds(intake: SafetyPlanIntake): List<Int> = buildList {
    add(R.string.safety_after_checkin)
    add(R.string.safety_after_essentials)
    var addedTargetedFollowUp = false
    if (intake.immigrationStatus in newcomerStatuses) {
        add(R.string.safety_after_immigration_legal)
        addedTargetedFollowUp = true
    }
    if (DependantNeed.CHILDREN in intake.dependants) {
        add(R.string.safety_after_children_school)
        addedTargetedFollowUp = true
    }
    if (DependantNeed.ELDER_OR_ADULT_DEPENDANT in intake.dependants) {
        add(R.string.safety_after_adult_dependant)
        addedTargetedFollowUp = true
    }
    if (SafetyCircumstance.LEGAL_OR_COURT_ORDERS in intake.circumstances ||
        SafetyCircumstance.MARRIAGE_OR_PARENTING in intake.circumstances
    ) {
        add(R.string.safety_after_legal_aid)
        addedTargetedFollowUp = true
    }
    if (SafetyCircumstance.PRESCRIPTIONS_OR_MEDICAL in intake.circumstances) {
        add(R.string.safety_after_medical)
        addedTargetedFollowUp = true
    }
    if (SafetyCircumstance.SHARED_LEASE_OR_MORTGAGE in intake.circumstances) {
        add(R.string.safety_after_housing)
        addedTargetedFollowUp = true
    }
    if (AbuseType.PHYSICAL in intake.abuseTypes) {
        add(R.string.safety_after_abuse_physical_care)
        addedTargetedFollowUp = true
    }
    if (AbuseType.EMOTIONAL_OR_PSYCHOLOGICAL in intake.abuseTypes) {
        add(R.string.safety_after_abuse_emotional_support)
        addedTargetedFollowUp = true
    }
    if (AbuseType.DIGITAL_OR_STALKING in intake.abuseTypes) {
        add(R.string.safety_after_abuse_digital_security)
        addedTargetedFollowUp = true
    }
    if (AbuseType.SEXUAL in intake.abuseTypes) {
        add(R.string.safety_after_abuse_sexual_support)
        addedTargetedFollowUp = true
    }
    if (AbuseType.CULTURAL_OR_IMMIGRATION in intake.abuseTypes) {
        add(R.string.safety_after_abuse_cultural_support)
        addedTargetedFollowUp = true
    }
    if (!addedTargetedFollowUp) add(R.string.safety_after_support_services)
}

private val newcomerStatuses = setOf(
    ImmigrationStatus.TEMPORARY_OR_SPONSORED,
    ImmigrationStatus.REFUGEE_OR_CLAIMANT,
    ImmigrationStatus.UNDOCUMENTED_OR_UNCERTAIN,
)

/**
 * Truncates checklist items so the encoded JSON string fits within section limits.
 *
 * @param items The checklist items considered for encoding.
 * @return The checklist prefix that fits within the storage size limit.
 */
private fun fitEncodedChecklist(items: List<SafetyChecklistItem>): List<SafetyChecklistItem> {
    val fitted = mutableListOf<SafetyChecklistItem>()
    for (item in items.take(MAX_SECTION_ITEMS)) {
        if (writeSafetyChecklist(fitted + item).length > MAX_SECTION_CHARS) break
        fitted += item
    }
    return fitted
}

/**
 * Normalizes a checklist prompt string for case-insensitive duplicate matching.
 *
 * @receiver The checklist prompt text to normalize.
 * @return The normalized prompt text used for case-insensitive matching.
 */
private fun String.normalizedPrompt(): String = trim().lowercase(Locale.ROOT)
