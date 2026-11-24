// Safe-app users and safe contacts: walkthrough topics, multi-step live tour catalog, and target bindings.
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
import android.content.Context
import com.olive.app.data.network.sha256Hex

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
 * Individual step rendered as a non-overlapping coachmark popup paired with a red target highlight.
 *
 * @property id Stable step identifier.
 * @property topicId Parent [WalkthroughTopic.id] in the Table of Contents.
 * @property titleRes Localized popup heading resource ID.
 * @property bodyRes Localized popup instruction resource ID.
 * @property tourPage Live [SafePage] displayed behind this coachmark step.
 * @property targetId Registered `walkthroughTarget` ID highlighted in red on screen.
 * @property avoidId Optional larger `walkthroughTarget` bounds to stay clear of (e.g., open `+` menu).
 * @property openSupportMenu Whether the `+` Support menu must stay expanded during this step.
 * @property settingsTabTag Optional `SettingsCategory.tag` selected automatically when on Settings.
 * @property opensPopup Whether this step automatically opens a popup sheet or dialog in-window.
 * @property action Optional screen-level action identifier consumed via `LocalWalkthroughDirective`.
 * @property userOnly Whether this step is restricted to primary user accounts.
 */
internal data class WalkthroughTourStep(
    val id: String,
    val topicId: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    val tourPage: SafePage,
    val targetId: String? = null,
    val avoidId: String? = null,
    val openSupportMenu: Boolean = false,
    val settingsTabTag: String? = null,
    val opensPopup: Boolean = false,
    val action: String? = null,
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

internal val allWalkthroughTourSteps: List<WalkthroughTourStep> = listOf(
    WalkthroughTourStep(
        id = "home_header_shortcuts",
        topicId = "quick_exit",
        titleRes = R.string.wt_step_home_header_shortcuts_title,
        bodyRes = R.string.wt_step_home_header_shortcuts_body,
        tourPage = SafePage.Home,
        targetId = "home_camera_settings",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "home_profile_activity",
        topicId = "quick_exit",
        titleRes = R.string.wt_step_home_profile_activity_title,
        bodyRes = R.string.wt_step_home_profile_activity_body,
        tourPage = SafePage.Home,
        targetId = "home_profile_activity",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "home_feature_grid",
        topicId = "quick_exit",
        titleRes = R.string.wt_step_home_feature_grid_title,
        bodyRes = R.string.wt_step_home_feature_grid_body,
        tourPage = SafePage.Home,
        targetId = "home_feature_grid",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "quick_exit_fab",
        topicId = "quick_exit",
        titleRes = R.string.wt_step_quick_exit_fab_title,
        bodyRes = R.string.wt_step_quick_exit_fab_body,
        tourPage = SafePage.Home,
        targetId = "quick_exit",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "support_menu_quick_links",
        topicId = "quick_exit",
        titleRes = R.string.wt_step_support_menu_quick_links_title,
        bodyRes = R.string.wt_step_support_menu_quick_links_body,
        tourPage = SafePage.Home,
        targetId = "support_action_list",
        avoidId = "support_action_list",
        openSupportMenu = true,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "support_menu_staff",
        topicId = "staff_help",
        titleRes = R.string.wt_step_support_menu_staff_title,
        bodyRes = R.string.wt_step_support_menu_staff_body,
        tourPage = SafePage.Home,
        targetId = "support_staff_help",
        avoidId = "support_action_list",
        openSupportMenu = true,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "support_menu_emergency",
        topicId = "emergency_report",
        titleRes = R.string.wt_step_support_menu_emergency_title,
        bodyRes = R.string.wt_step_support_menu_emergency_body,
        tourPage = SafePage.Home,
        targetId = "support_emergency",
        avoidId = "support_action_list",
        openSupportMenu = true,
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "plan_primary_tab",
        topicId = "safety_plan",
        titleRes = R.string.wt_step_plan_primary_tab_title,
        bodyRes = R.string.wt_step_plan_primary_tab_body,
        tourPage = SafePage.SafetyPlan,
        targetId = "safety_primary",
        action = "plan_primary",
    ),
    WalkthroughTourStep(
        id = "plan_section_jumps",
        topicId = "safety_plan",
        titleRes = R.string.wt_step_plan_section_jumps_title,
        bodyRes = R.string.wt_step_plan_section_jumps_body,
        tourPage = SafePage.SafetyPlan,
        targetId = "safety_section_jumps",
        action = "plan_primary",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "plan_backup_tab",
        topicId = "safety_plan",
        titleRes = R.string.wt_step_plan_backup_tab_title,
        bodyRes = R.string.wt_step_plan_backup_tab_body,
        tourPage = SafePage.SafetyPlan,
        targetId = "safety_backup",
        action = "plan_backup",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "plan_consultation",
        topicId = "safety_plan",
        titleRes = R.string.wt_step_plan_consultation_title,
        bodyRes = R.string.wt_step_plan_consultation_body,
        tourPage = SafePage.SafetyPlan,
        targetId = "safety_consultation_section",
        action = "plan_scroll_consult",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "plan_share_contacts",
        topicId = "safety_plan",
        titleRes = R.string.wt_step_plan_share_contacts_title,
        bodyRes = R.string.wt_step_plan_share_contacts_body,
        tourPage = SafePage.SafetyPlan,
        targetId = "safety_share_section",
        action = "plan_scroll_share",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "plan_tailor_questionnaire",
        topicId = "safety_plan",
        titleRes = R.string.wt_step_plan_tailor_questionnaire_title,
        bodyRes = R.string.wt_step_plan_tailor_questionnaire_body,
        tourPage = SafePage.SafetyPlan,
        targetId = "safety_questionnaire_dialog",
        avoidId = "safety_questionnaire_dialog",
        opensPopup = true,
        action = "plan_open_questionnaire",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "notes_search_filter_sort",
        topicId = "notes_incidents",
        titleRes = R.string.wt_step_notes_pin_search_title,
        bodyRes = R.string.wt_step_notes_pin_search_body,
        tourPage = SafePage.Notes,
        targetId = "notes_board_controls",
    ),
    WalkthroughTourStep(
        id = "notes_editor_incident",
        topicId = "notes_incidents",
        titleRes = R.string.wt_step_notes_create_title,
        bodyRes = R.string.wt_step_notes_create_body,
        tourPage = SafePage.Notes,
        targetId = "private_incident_fields",
        action = "notes_open_incident_editor",
    ),
    WalkthroughTourStep(
        id = "notes_editor_attachments",
        topicId = "notes_incidents",
        titleRes = R.string.wt_step_notes_attachments_title,
        bodyRes = R.string.wt_step_notes_attachments_body,
        tourPage = SafePage.Notes,
        targetId = "documents_upload_sheet",
        avoidId = "documents_upload_sheet",
        opensPopup = true,
        action = "notes_open_attachment_sheet",
    ),
    WalkthroughTourStep(
        id = "notes_trash_button",
        topicId = "notes_incidents",
        titleRes = R.string.wt_step_notes_history_trash_title,
        bodyRes = R.string.wt_step_notes_history_trash_body,
        tourPage = SafePage.Notes,
        targetId = "open_scratchpad",
    ),
    WalkthroughTourStep(
        id = "docs_upload_popup",
        topicId = "documents_ocr",
        titleRes = R.string.wt_step_docs_encrypted_vault_title,
        bodyRes = R.string.wt_step_docs_encrypted_vault_body,
        tourPage = SafePage.Documents,
        targetId = "documents_upload_sheet",
        avoidId = "documents_upload_sheet",
        opensPopup = true,
        action = "docs_open_upload",
    ),
    WalkthroughTourStep(
        id = "docs_private_camera",
        topicId = "documents_ocr",
        titleRes = R.string.wt_step_docs_private_camera_title,
        bodyRes = R.string.wt_step_docs_private_camera_body,
        tourPage = SafePage.Documents,
        targetId = "documents_choose_camera",
        avoidId = "documents_upload_sheet",
        opensPopup = true,
        action = "docs_open_upload",
    ),
    WalkthroughTourStep(
        id = "docs_filters_sharing",
        topicId = "documents_ocr",
        titleRes = R.string.wt_step_docs_ocr_share_title,
        bodyRes = R.string.wt_step_docs_ocr_share_body,
        tourPage = SafePage.Documents,
        targetId = "documents_filter_chips",
    ),
    WalkthroughTourStep(
        id = "chat_tabs_dms_tickets",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_tabs_title,
        bodyRes = R.string.wt_step_chat_tabs_body,
        tourPage = SafePage.Messages,
        targetId = "messages_chat_tabs",
        action = "messages_tab_dms",
    ),
    WalkthroughTourStep(
        id = "chat_invite_popup",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_add_friends_title,
        bodyRes = R.string.wt_step_chat_add_friends_body,
        tourPage = SafePage.Messages,
        targetId = "friend_invite_dialog",
        avoidId = "friend_invite_dialog",
        opensPopup = true,
        action = "messages_open_invite",
    ),
    WalkthroughTourStep(
        id = "chat_enter_invite_code",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_enter_code_title,
        bodyRes = R.string.wt_step_chat_enter_code_body,
        tourPage = SafePage.Messages,
        targetId = "friend_invite_code_card",
        action = "messages_tab_dms",
    ),
    WalkthroughTourStep(
        id = "chat_in_chat_tools",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_calls_search_info_title,
        bodyRes = R.string.wt_step_chat_calls_search_info_body,
        tourPage = SafePage.Messages,
        targetId = "messages_dms_tab",
        action = "messages_tab_dms",
    ),
    WalkthroughTourStep(
        id = "chat_ticket_tab_archive",
        topicId = "messaging_calls",
        titleRes = R.string.wt_step_chat_ticket_tab_title,
        bodyRes = R.string.wt_step_chat_ticket_tab_body,
        tourPage = SafePage.Messages,
        targetId = "messages_tickets_tab",
        action = "messages_tab_tickets",
    ),
    WalkthroughTourStep(
        id = "loc_gps_destination",
        topicId = "location_map",
        titleRes = R.string.wt_step_loc_navigation_title,
        bodyRes = R.string.wt_step_loc_navigation_body,
        tourPage = SafePage.Location,
        targetId = "map_gps_destination_card",
    ),
    WalkthroughTourStep(
        id = "loc_map_and_controls",
        topicId = "location_map",
        titleRes = R.string.wt_step_loc_map_places_title,
        bodyRes = R.string.wt_step_loc_map_places_body,
        tourPage = SafePage.Location,
        targetId = "location_map_card",
    ),
    WalkthroughTourStep(
        id = "loc_directory_places_popup",
        topicId = "location_map",
        titleRes = R.string.wt_step_loc_directory_places_title,
        bodyRes = R.string.wt_step_loc_directory_places_body,
        tourPage = SafePage.Location,
        targetId = "location_directory_dialog",
        avoidId = "location_directory_dialog",
        opensPopup = true,
        action = "location_open_directory",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "loc_live_sharing_duration",
        topicId = "location_map",
        titleRes = R.string.wt_step_loc_live_sharing_title,
        bodyRes = R.string.wt_step_loc_live_sharing_body,
        tourPage = SafePage.Location,
        targetId = "location_duration_dialog",
        avoidId = "location_duration_dialog",
        opensPopup = true,
        action = "location_open_duration",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "hotlines_region_location",
        topicId = "hotlines_contacts",
        titleRes = R.string.wt_step_hotlines_region_title,
        bodyRes = R.string.wt_step_hotlines_region_body,
        tourPage = SafePage.Hotlines,
        targetId = "hotline_location_bar",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "hotlines_tabs_filters",
        topicId = "hotlines_contacts",
        titleRes = R.string.wt_step_hotlines_directory_title,
        bodyRes = R.string.wt_step_hotlines_directory_body,
        tourPage = SafePage.Hotlines,
        targetId = "hotline_tabs",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "contacts_directory",
        topicId = "hotlines_contacts",
        titleRes = R.string.wt_step_contacts_directory_title,
        bodyRes = R.string.wt_step_contacts_directory_body,
        tourPage = SafePage.Contacts,
        targetId = "contacts_add_form",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "profile_overview",
        topicId = "activity_settings",
        titleRes = R.string.wt_step_profile_overview_title,
        bodyRes = R.string.wt_step_profile_overview_body,
        tourPage = SafePage.Profile,
        targetId = "profile_summary_cards",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "activity_date_filters",
        topicId = "activity_settings",
        titleRes = R.string.wt_step_activity_date_filters_title,
        bodyRes = R.string.wt_step_activity_date_filters_body,
        tourPage = SafePage.Activity,
        targetId = "activity_date_filters",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "activity_categories",
        topicId = "activity_settings",
        titleRes = R.string.wt_step_activity_audit_title,
        bodyRes = R.string.wt_step_activity_audit_body,
        tourPage = SafePage.Activity,
        targetId = "activity_settings_section",
        action = "activity_scroll_logs",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_profile_cover",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_profile_cover_title,
        bodyRes = R.string.wt_step_settings_profile_cover_body,
        tourPage = SafePage.Settings,
        targetId = "settings_card_cover_entry",
        settingsTabTag = "settings_tab_profile",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_profile_details",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_profile_details_title,
        bodyRes = R.string.wt_step_settings_profile_details_body,
        tourPage = SafePage.Settings,
        targetId = "settings_card_profile_header",
        settingsTabTag = "settings_tab_profile",
    ),
    WalkthroughTourStep(
        id = "settings_password_account",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_password_account_title,
        bodyRes = R.string.wt_step_settings_password_account_body,
        tourPage = SafePage.Settings,
        targetId = "settings_card_password",
        settingsTabTag = "settings_tab_password",
    ),
    WalkthroughTourStep(
        id = "settings_password_pin",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_password_pin_title,
        bodyRes = R.string.wt_step_settings_password_pin_body,
        tourPage = SafePage.Settings,
        targetId = "settings_card_entry_pin",
        settingsTabTag = "settings_tab_password",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_password_trigger",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_password_trigger_title,
        bodyRes = R.string.wt_step_settings_password_trigger_body,
        tourPage = SafePage.Settings,
        targetId = "settings_card_trigger",
        settingsTabTag = "settings_tab_password",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_privacy_emergency_capture",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_privacy_capture_title,
        bodyRes = R.string.wt_step_settings_privacy_capture_body,
        tourPage = SafePage.Settings,
        targetId = "settings_privacy_capture_group",
        settingsTabTag = "settings_tab_privacy",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_privacy_idle",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_privacy_idle_title,
        bodyRes = R.string.wt_step_settings_privacy_idle_body,
        tourPage = SafePage.Settings,
        targetId = "settings_privacy_idle_group",
        settingsTabTag = "settings_tab_privacy",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_privacy_cache",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_privacy_cache_title,
        bodyRes = R.string.wt_step_settings_privacy_cache_body,
        tourPage = SafePage.Settings,
        targetId = "settings_privacy_cache_group",
        settingsTabTag = "settings_tab_privacy",
        userOnly = true,
    ),
    WalkthroughTourStep(
        id = "settings_permissions_install",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_permissions_install_title,
        bodyRes = R.string.wt_step_settings_permissions_install_body,
        tourPage = SafePage.Settings,
        targetId = "settings_perm_install_card",
        settingsTabTag = "settings_tab_permissions",
    ),
    WalkthroughTourStep(
        id = "settings_permissions_features",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_permissions_title,
        bodyRes = R.string.wt_step_settings_permissions_body,
        tourPage = SafePage.Settings,
        targetId = "settings_perm_features_header",
        settingsTabTag = "settings_tab_permissions",
    ),
    WalkthroughTourStep(
        id = "settings_permissions_automatic",
        topicId = "cover_entry",
        titleRes = R.string.wt_step_settings_permissions_auto_title,
        bodyRes = R.string.wt_step_settings_permissions_auto_body,
        tourPage = SafePage.Settings,
        targetId = "settings_perm_automatic_card",
        settingsTabTag = "settings_tab_permissions",
    ),
)

/**
 * Persists whether an account has already been shown the automatic full walkthrough upon its
 * initial login to the Safe App.
 *
 * @param context Android context owning app preferences.
 * @param accountId Account identifier scoped cleanly via SHA-256 hash.
 */
internal class WalkthroughVisits(context: Context, accountId: String) {
    private val preferences = context.getSharedPreferences("olive_walkthrough_visits", Context.MODE_PRIVATE)
    private val initialTourKey = sha256Hex("walkthrough:initial:$accountId")

    /**
     * Returns `true` if this account has already opened the automatic first-login walkthrough.
     *
     * @return True when the account has already opened the automatic first-login walkthrough.
     */
    fun hasSeenInitialTour(): Boolean = preferences.getBoolean(initialTourKey, false)

    /**
     * Records that the automatic first-login walkthrough has been opened for this account.
     */
    fun markInitialTourSeen() {
        preferences.edit().putBoolean(initialTourKey, true).apply()
    }
}
