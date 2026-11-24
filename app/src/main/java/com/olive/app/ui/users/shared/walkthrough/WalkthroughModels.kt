// Safe-app users and safe contacts: walkthrough topics, multi-step live tour catalog, and helpers.
package com.olive.app.ui.users.shared.walkthrough

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.ui.users.shared.navigation.SafePage

/**
 * Placement of an in-app walkthrough coachmark card over the live target screen.
 */
internal enum class WalkthroughAnchor {
    BottomEnd,
    BottomStart,
    TopEnd,
    TopCenter,
    BottomCenter,
}

/**
 * Directional arrow displayed inside the coachmark card to point toward the target control.
 */
internal enum class WalkthroughArrow {
    SouthEast,
    SouthWest,
    NorthEast,
    North,
    South,
}

/**
 * Top-level Table of Contents groupings for walkthrough features.
 *
 * @property labelRes Localized section heading resource ID.
 */
internal enum class WalkthroughCategory(@param:StringRes val labelRes: Int) {
    PRIVACY(R.string.walkthrough_cat_privacy),
    TOOLS(R.string.walkthrough_cat_tools),
    SUPPORT(R.string.walkthrough_cat_support),
    ACCOUNT(R.string.walkthrough_cat_account),
}

/**
 * High-level topic card shown in the Walkthrough Table of Contents.
 *
 * @property category Grouping section in the Table of Contents.
 * @property id Stable topic identifier linked to one or more [WalkthroughTourStep] items.
 * @property titleRes Localized feature title resource ID.
 * @property summaryRes Localized short summary shown in Table of Contents cards.
 * @property stepResIds Legacy three-step summary resource list preserved for catalog parity tests.
 * @property targetPage Primary [SafePage] associated with this topic.
 * @property targetLabelRes Localized name of [targetPage].
 * @property icon Vector icon representing the feature.
 * @property userOnly Whether the topic is hidden from safe-contact accounts.
 */
internal data class WalkthroughTopic(
    val category: WalkthroughCategory,
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val stepResIds: List<Int>,
    val targetPage: SafePage,
    @param:StringRes val targetLabelRes: Int,
    val icon: ImageVector,
    val userOnly: Boolean = false,
)

/**
 * Individual step rendered as a floating coachmark popup during the live in-app walkthrough.
 *
 * @property id Stable step identifier.
 * @property topicId Parent [WalkthroughTopic.id] in the Table of Contents.
 * @property titleRes Localized popup heading resource ID.
 * @property bodyRes Localized popup instruction resource ID.
 * @property tourPage Live [SafePage] displayed behind this coachmark step.
 * @property anchor Placement alignment for the floating coachmark popup.
 * @property arrow Directional arrow inside the popup pointing to the highlighted feature.
 * @property openSupportMenu Whether the `+` Support menu must stay expanded during this step.
 * @property settingsTabTag Optional `SettingsCategory.tag` selected automatically when on Settings.
 * @property userOnly Whether this step is restricted to primary user accounts.
 */
internal data class WalkthroughTourStep(
    val id: String,
    val topicId: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    val tourPage: SafePage,
    val anchor: WalkthroughAnchor = WalkthroughAnchor.BottomCenter,
    val arrow: WalkthroughArrow = WalkthroughArrow.North,
    val openSupportMenu: Boolean = false,
    val settingsTabTag: String? = null,
    val userOnly: Boolean = false,
)

/**
 * Grouped section returned for rendering the Table of Contents.
 *
 * @property category Section category metadata.
 * @property topics Ordered topics within [category] available to the active role.
 */
internal data class WalkthroughTopicGroup(
    val category: WalkthroughCategory,
    val topics: List<WalkthroughTopic>,
)

internal val allWalkthroughTopics: List<WalkthroughTopic> = listOf(
    WalkthroughTopic(
        category = WalkthroughCategory.PRIVACY,
        id = "quick_exit",
        titleRes = R.string.walkthrough_topic_exit_title,
        summaryRes = R.string.walkthrough_topic_exit_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_exit_step_1,
            R.string.walkthrough_topic_exit_step_2,
            R.string.walkthrough_topic_exit_step_3,
        ),
        targetPage = SafePage.Home,
        targetLabelRes = R.string.home,
        icon = Icons.AutoMirrored.Outlined.ExitToApp,
        userOnly = true,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.SUPPORT,
        id = "staff_help",
        titleRes = R.string.walkthrough_topic_staff_title,
        summaryRes = R.string.walkthrough_topic_staff_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_staff_step_1,
            R.string.walkthrough_topic_staff_step_2,
            R.string.walkthrough_topic_staff_step_3,
        ),
        targetPage = SafePage.StaffHelp,
        targetLabelRes = R.string.support_staff_help,
        icon = Icons.Outlined.SupportAgent,
        userOnly = true,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.SUPPORT,
        id = "emergency_report",
        titleRes = R.string.walkthrough_topic_emergency_title,
        summaryRes = R.string.walkthrough_topic_emergency_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_emergency_step_1,
            R.string.walkthrough_topic_emergency_step_2,
            R.string.walkthrough_topic_emergency_step_3,
        ),
        targetPage = SafePage.Documents,
        targetLabelRes = R.string.support_documents,
        icon = Icons.Outlined.WarningAmber,
        userOnly = true,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "safety_plan",
        titleRes = R.string.walkthrough_topic_plan_title,
        summaryRes = R.string.walkthrough_topic_plan_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_plan_step_1,
            R.string.walkthrough_topic_plan_step_2,
            R.string.walkthrough_topic_plan_step_3,
        ),
        targetPage = SafePage.SafetyPlan,
        targetLabelRes = R.string.support_safety_plan,
        icon = Icons.Outlined.HealthAndSafety,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "notes_incidents",
        titleRes = R.string.walkthrough_topic_notes_title,
        summaryRes = R.string.walkthrough_topic_notes_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_notes_step_1,
            R.string.walkthrough_topic_notes_step_2,
            R.string.walkthrough_topic_notes_step_3,
        ),
        targetPage = SafePage.Notes,
        targetLabelRes = R.string.notes_incidents,
        icon = Icons.Outlined.Description,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "documents_ocr",
        titleRes = R.string.walkthrough_topic_docs_title,
        summaryRes = R.string.walkthrough_topic_docs_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_docs_step_1,
            R.string.walkthrough_topic_docs_step_2,
            R.string.walkthrough_topic_docs_step_3,
        ),
        targetPage = SafePage.Documents,
        targetLabelRes = R.string.support_documents,
        icon = Icons.Outlined.FolderOpen,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "messaging_calls",
        titleRes = R.string.walkthrough_topic_chat_title,
        summaryRes = R.string.walkthrough_topic_chat_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_chat_step_1,
            R.string.walkthrough_topic_chat_step_2,
            R.string.walkthrough_topic_chat_step_3,
        ),
        targetPage = SafePage.Messages,
        targetLabelRes = R.string.messages,
        icon = Icons.Outlined.ChatBubbleOutline,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "location_map",
        titleRes = R.string.walkthrough_topic_location_title,
        summaryRes = R.string.walkthrough_topic_location_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_location_step_1,
            R.string.walkthrough_topic_location_step_2,
            R.string.walkthrough_topic_location_step_3,
        ),
        targetPage = SafePage.Location,
        targetLabelRes = R.string.location_map,
        icon = Icons.Outlined.LocationOn,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.SUPPORT,
        id = "hotlines_contacts",
        titleRes = R.string.walkthrough_topic_hotlines_title,
        summaryRes = R.string.walkthrough_topic_hotlines_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_hotlines_step_1,
            R.string.walkthrough_topic_hotlines_step_2,
            R.string.walkthrough_topic_hotlines_step_3,
        ),
        targetPage = SafePage.Hotlines,
        targetLabelRes = R.string.support_hotlines,
        icon = Icons.Outlined.PhoneInTalk,
        userOnly = true,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.ACCOUNT,
        id = "activity_settings",
        titleRes = R.string.walkthrough_topic_activity_title,
        summaryRes = R.string.walkthrough_topic_activity_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_activity_step_1,
            R.string.walkthrough_topic_activity_step_2,
            R.string.walkthrough_topic_activity_step_3,
        ),
        targetPage = SafePage.Activity,
        targetLabelRes = R.string.account_activity,
        icon = Icons.Outlined.History,
        userOnly = true,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.PRIVACY,
        id = "cover_entry",
        titleRes = R.string.walkthrough_topic_cover_title,
        summaryRes = R.string.walkthrough_topic_cover_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_cover_step_1,
            R.string.walkthrough_topic_cover_step_2,
            R.string.walkthrough_topic_cover_step_3,
        ),
        targetPage = SafePage.Settings,
        targetLabelRes = R.string.settings,
        icon = Icons.Outlined.Lock,
        userOnly = false,
    ),
)

internal val allWalkthroughTourSteps: List<WalkthroughTourStep> = listOf(
    WalkthroughTourStep(
        id = "quick_exit_fab",
        topicId = "quick_exit",
        titleRes = R.string.wt_step_quick_exit_fab_title,
        bodyRes = R.string.wt_step_quick_exit_fab_body,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.BottomEnd,
        arrow = WalkthroughArrow.SouthEast,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "home_header_shortcuts",
        topicId = "quick_exit",
        titleRes = R.string.wt_step_home_header_shortcuts_title,
        bodyRes = R.string.wt_step_home_header_shortcuts_body,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.TopEnd,
        arrow = WalkthroughArrow.NorthEast,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "home_profile_activity",
        topicId = "quick_exit",
        titleRes = R.string.wt_step_home_profile_activity_title,
        bodyRes = R.string.wt_step_home_profile_activity_body,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.South,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "support_menu_staff",
        topicId = "staff_help",
        titleRes = R.string.wt_step_support_menu_staff_title,
        bodyRes = R.string.wt_step_support_menu_staff_body,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.SouthWest,
        openSupportMenu = true,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "support_menu_staff_escalate",
        topicId = "staff_help",
        titleRes = R.string.wt_step_support_menu_staff_escalate_title,
        bodyRes = R.string.wt_step_support_menu_staff_escalate_body,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.SouthWest,
        openSupportMenu = true,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "support_menu_emergency",
        topicId = "emergency_report",
        titleRes = R.string.wt_step_support_menu_emergency_title,
        bodyRes = R.string.wt_step_support_menu_emergency_body,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.SouthWest,
        openSupportMenu = true,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "support_menu_emergency_alert",
        topicId = "emergency_report",
        titleRes = R.string.wt_step_support_menu_emergency_alert_title,
        bodyRes = R.string.wt_step_support_menu_emergency_alert_body,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.SouthWest,
        openSupportMenu = true,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "plan_checklists",
        topicId = "safety_plan",
        titleRes = R.string.wt_step_plan_checklists_title,
        bodyRes = R.string.wt_step_plan_checklists_body,
        tourPage = SafePage.SafetyPlan,
    ),
    WalkthroughTourStep(
        id = "plan_share_consult",
        topicId = "safety_plan",
        titleRes = R.string.wt_step_plan_share_consult_title,
        bodyRes = R.string.wt_step_plan_share_consult_body,
        tourPage = SafePage.SafetyPlan,
    ),
    WalkthroughTourStep(
        id = "notes_create",
        topicId = "notes_incidents",
        titleRes = R.string.wt_step_notes_create_title,
        bodyRes = R.string.wt_step_notes_create_body,
        tourPage = SafePage.Notes,
    ),
    WalkthroughTourStep(
        id = "notes_pin_search",
        topicId = "notes_incidents",
        titleRes = R.string.wt_step_notes_pin_search_title,
        bodyRes = R.string.wt_step_notes_pin_search_body,
        tourPage = SafePage.Notes,
    ),
    WalkthroughTourStep(
        id = "notes_history_trash",
        topicId = "notes_incidents",
        titleRes = R.string.wt_step_notes_history_trash_title,
        bodyRes = R.string.wt_step_notes_history_trash_body,
        tourPage = SafePage.Notes,
    ),
    WalkthroughTourStep(
        id = "docs_encrypted_vault",
        topicId = "documents_ocr",
        titleRes = R.string.wt_step_docs_encrypted_vault_title,
        bodyRes = R.string.wt_step_docs_encrypted_vault_body,
        tourPage = SafePage.Documents,
    ),
    WalkthroughTourStep(
        id = "docs_private_camera",
        topicId = "documents_ocr",
        titleRes = R.string.wt_step_docs_private_camera_title,
        bodyRes = R.string.wt_step_docs_private_camera_body,
        tourPage = SafePage.Documents,
    ),
    WalkthroughTourStep(
        id = "docs_ocr_share",
        topicId = "documents_ocr",
        titleRes = R.string.wt_step_docs_ocr_share_title,
        bodyRes = R.string.wt_step_docs_ocr_share_body,
        tourPage = SafePage.Documents,
    ),
    WalkthroughTourStep(
        id = "chat_add_friends",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_add_friends_title,
        bodyRes = R.string.wt_step_chat_add_friends_body,
        tourPage = SafePage.Messages,
    ),
    WalkthroughTourStep(
        id = "chat_voice_messages",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_voice_messages_title,
        bodyRes = R.string.wt_step_chat_voice_messages_body,
        tourPage = SafePage.Messages,
    ),
    WalkthroughTourStep(
        id = "chat_share_media",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_share_media_title,
        bodyRes = R.string.wt_step_chat_share_media_body,
        tourPage = SafePage.Messages,
    ),
    WalkthroughTourStep(
        id = "chat_calls_search_info",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_calls_search_info_title,
        bodyRes = R.string.wt_step_chat_calls_search_info_body,
        tourPage = SafePage.Messages,
    ),
    WalkthroughTourStep(
        id = "loc_map_places",
        topicId = "location_map",
        titleRes = R.string.wt_step_loc_map_places_title,
        bodyRes = R.string.wt_step_loc_map_places_body,
        tourPage = SafePage.Location,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.South,
    ),
    WalkthroughTourStep(
        id = "loc_live_sharing",
        topicId = "location_map",
        titleRes = R.string.wt_step_loc_live_sharing_title,
        bodyRes = R.string.wt_step_loc_live_sharing_body,
        tourPage = SafePage.Location,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.South,
    ),
    WalkthroughTourStep(
        id = "loc_navigation",
        topicId = "location_map",
        titleRes = R.string.wt_step_loc_navigation_title,
        bodyRes = R.string.wt_step_loc_navigation_body,
        tourPage = SafePage.Location,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.South,
    ),
    WalkthroughTourStep(
        id = "hotlines_directory",
        topicId = "hotlines_contacts",
        titleRes = R.string.wt_step_hotlines_directory_title,
        bodyRes = R.string.wt_step_hotlines_directory_body,
        tourPage = SafePage.Hotlines,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "contacts_directory",
        topicId = "hotlines_contacts",
        titleRes = R.string.wt_step_contacts_directory_title,
        bodyRes = R.string.wt_step_contacts_directory_body,
        tourPage = SafePage.Contacts,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "activity_audit",
        topicId = "activity_settings",
        titleRes = R.string.wt_step_activity_audit_title,
        bodyRes = R.string.wt_step_activity_audit_body,
        tourPage = SafePage.Activity,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_profile_cover",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_profile_cover_title,
        bodyRes = R.string.wt_step_settings_profile_cover_body,
        tourPage = SafePage.Settings,
        settingsTabTag = "settings_tab_profile",
    ),
    WalkthroughTourStep(
        id = "settings_password_trigger",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_password_trigger_title,
        bodyRes = R.string.wt_step_settings_password_trigger_body,
        tourPage = SafePage.Settings,
        settingsTabTag = "settings_tab_password",
    ),
    WalkthroughTourStep(
        id = "settings_privacy_passkey",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_privacy_passkey_title,
        bodyRes = R.string.wt_step_settings_privacy_passkey_body,
        tourPage = SafePage.Settings,
        settingsTabTag = "settings_tab_privacy",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_permissions",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_permissions_title,
        bodyRes = R.string.wt_step_settings_permissions_body,
        tourPage = SafePage.Settings,
        settingsTabTag = "settings_tab_permissions",
    ),
)

/**
 * Filters the walkthrough table of contents to the features supported by the given [role].
 *
 * @param role Account role viewing the walkthrough.
 * @return Ordered list of [WalkthroughTopic] items for that role.
 */
internal fun walkthroughTopicsForRole(role: AccountRole): List<WalkthroughTopic> =
    if (role == AccountRole.SAFE_CONTACT) allWalkthroughTopics.filterNot(WalkthroughTopic::userOnly)
    else allWalkthroughTopics

/**
 * Filters live coachmark tour steps to the features accessible by [role].
 *
 * @param role Account role running the tour.
 * @return Ordered list of [WalkthroughTourStep] items for that role.
 */
internal fun walkthroughTourStepsForRole(role: AccountRole): List<WalkthroughTourStep> =
    if (role == AccountRole.SAFE_CONTACT) allWalkthroughTourSteps.filterNot(WalkthroughTourStep::userOnly)
    else allWalkthroughTourSteps

/**
 * Returns the first [WalkthroughTourStep] belonging to [topicId] for [role], or `null`.
 *
 * @param role Account role launching the tour.
 * @param topicId Table of Contents topic identifier.
 * @return Matching first step, or `null` if unavailable for [role].
 */
internal fun firstWalkthroughStepForTopic(role: AccountRole, topicId: String): WalkthroughTourStep? =
    walkthroughTourStepsForRole(role).firstOrNull { it.topicId == topicId }

/**
 * Counts how many live tour steps belong to [topicId] for [role].
 *
 * @param role Account role viewing the Table of Contents.
 * @param topicId Table of Contents topic identifier.
 * @return Number of coachmark steps under that topic.
 */
internal fun walkthroughStepCountForTopic(role: AccountRole, topicId: String): Int =
    walkthroughTourStepsForRole(role).count { it.topicId == topicId }

/**
 * Groups the given [topics] into ordered Table of Contents sections, omitting empty categories.
 *
 * @param topics Flat list of role-filtered walkthrough topics.
 * @return Ordered list of non-empty [WalkthroughTopicGroup] sections.
 */
internal fun walkthroughTopicGroups(topics: List<WalkthroughTopic>): List<WalkthroughTopicGroup> =
    WalkthroughCategory.entries.mapNotNull { category ->
        val groupTopics = topics.filter { it.category == category }
        if (groupTopics.isEmpty()) null else WalkthroughTopicGroup(category, groupTopics)
    }

/**
 * Clamps a requested step index into the valid step range `[0, totalSteps - 1]`.
 *
 * @param stepIndex Zero-based step index requested by the user.
 * @param totalSteps Total number of steps in the topic.
 * @return Valid zero-based step index.
 */
internal fun clampWalkthroughStep(stepIndex: Int, totalSteps: Int): Int =
    if (totalSteps <= 1) 0 else stepIndex.coerceIn(0, totalSteps - 1)

/**
 * Computes the next topic index when moving forward or backward through the table of contents.
 *
 * @param currentIndex Current zero-based topic index.
 * @param totalTopics Total number of available topics.
 * @param delta Step direction (positive for next, negative for previous).
 * @return Clamped zero-based topic index.
 */
internal fun nextWalkthroughTopicIndex(currentIndex: Int, totalTopics: Int, delta: Int): Int =
    if (totalTopics <= 1) 0 else (currentIndex + delta).coerceIn(0, totalTopics - 1)

/**
 * Returns the next tour step ID during a live in-app walkthrough, or `null` when moving
 * past the last step or before the first step of [steps].
 *
 * @param steps Ordered list of role-filtered tour steps.
 * @param currentStepId Currently displayed coachmark step ID.
 * @param delta Direction to advance (`+1` for Next, `-1` for Back).
 * @return Target step ID, or `null` when out of bounds.
 */
internal fun nextWalkthroughTourStepId(
    steps: List<WalkthroughTourStep>,
    currentStepId: String,
    delta: Int,
): String? {
    val idx = steps.indexOfFirst { it.id == currentStepId }
    if (idx < 0) return null
    val next = idx + delta
    return if (next in steps.indices) steps[next].id else null
}
