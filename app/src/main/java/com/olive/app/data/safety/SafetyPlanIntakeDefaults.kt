// Safe-app users: builds tailored safety-plan checklist prompts from onboarding questionnaire selections.
package com.olive.app.data.safety

import android.content.Context
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

/** Builds tailored document checklist string resource IDs from [intake]. */
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

/** Builds tailored quick-grab checklist string resource IDs from [intake]. */
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

/** Builds tailored financial checklist string resource IDs from [intake]. */
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

/** Builds tailored destination checklist string resource IDs from [intake]. */
private fun destinationPromptIds(intake: SafetyPlanIntake): List<Int> = buildList {
    add(R.string.safety_dest_choose)
    add(R.string.safety_dest_address)
    add(R.string.safety_dest_when)
    if (DependantNeed.CHILDREN in intake.dependants) add(R.string.safety_dest_children)
    if (DependantNeed.ELDER_OR_ADULT_DEPENDANT in intake.dependants) add(R.string.safety_dest_adult_dependant)
    if (DependantNeed.PETS in intake.dependants) add(R.string.safety_dest_pets)
    if (AbuseType.PHYSICAL in intake.abuseTypes) add(R.string.safety_dest_abuse_physical_exit)
}

/** Builds tailored travel checklist string resource IDs from [intake]. */
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

/** Builds tailored aftercare checklist string resource IDs from [intake]. */
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

/** Truncates checklist items so the encoded JSON string fits within section limits. */
private fun fitEncodedChecklist(items: List<SafetyChecklistItem>): List<SafetyChecklistItem> {
    val fitted = mutableListOf<SafetyChecklistItem>()
    for (item in items.take(MAX_SECTION_ITEMS)) {
        if (writeSafetyChecklist(fitted + item).length > MAX_SECTION_CHARS) break
        fitted += item
    }
    return fitted
}

/** Normalizes a checklist prompt string for case-insensitive duplicate matching. */
private fun String.normalizedPrompt(): String = trim().lowercase(Locale.ROOT)
