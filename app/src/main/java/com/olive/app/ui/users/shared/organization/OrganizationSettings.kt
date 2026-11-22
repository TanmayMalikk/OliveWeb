// All safe-app roles: shows staff and admin account settings, workload cards, and live organization map.
package com.olive.app.ui.users.shared.organization

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.location.AdminLocationRepository
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.security.InputPolicy
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.onboarding.*
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import com.olive.app.ui.users.user.account.SettingsSection
import com.olive.app.ui.users.user.account.SettingsCategory
import com.olive.app.ui.users.user.account.SettingsHeading
import com.olive.app.ui.users.user.account.accountSettingsTabs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


/**
 * Shows profile and password settings for staff and admin accounts.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onBack Callback invoked when the user navigates back.
 */
@Composable
internal fun OrganizationSettings(session: SessionViewModel, onBack: () -> Unit) {
    val uiText = rememberResourceText()
    val profile = session.profile ?: return
    var fullName by remember(profile) { mutableStateOf(profile.fullName) }
    var birthday by rememberBirthdayInput(profile.dob)
    var showBirthdayErrors by remember(profile.dob) { mutableStateOf(false) }
    var email by remember(profile) { mutableStateOf(profile.email) }
    var phone by remember(profile) { mutableStateOf(profile.phone) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable(session.coverAccountId) { mutableStateOf(SettingsCategory.Profile) }
    OlivePrivateDesign {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_back))
                }
                SettingsHeading(Modifier.weight(1f))
            }
            SecondaryScrollableTabRow(selectedTabIndex = accountSettingsTabs.indexOf(selectedTab), edgePadding = 8.dp) {
                accountSettingsTabs.forEach { tab ->
                    Tab(selected = selectedTab == tab, onClick = { selectedTab = tab },
                        modifier = Modifier.testTag(tab.tag), text = { Text(uiText(tab.label)) })
                }
            }
            FormColumn(scrollResetKey = selectedTab) {
                when (selectedTab) {
                    SettingsCategory.Permissions -> SafePermissionsSettings()
                    SettingsCategory.Profile -> SettingsSection(uiText(R.string.settings_category_profile)) {
                        Field(
                            uiText(R.string.full_name),
                            fullName,
                            "organization_settings_name",
                            enabled = !session.account.busy,
                        ) { fullName = it.take(120) }
                        BirthdayPicker(
                            birthday, "organization_settings_dob", !session.account.busy, showBirthdayErrors,
                        ) { birthday = it }
                        Field(
                            uiText(R.string.ui_email),
                            email,
                            "organization_settings_email",
                            enabled = !session.account.busy,
                        ) { email = it.take(254) }
                        Field(
                            uiText(R.string.ui_phone_optional),
                            phone,
                            "organization_settings_phone",
                            enabled = !session.account.busy,
                        ) { phone = it.take(40) }
                        Button(
                            onClick = {
                                showBirthdayErrors = !InputPolicy.validDob(birthday)
                                if (!showBirthdayErrors) session.updateOrganizationProfile(fullName, birthday, email, phone)
                            },
                            enabled = !session.account.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.save_profile)) }
                    }
                    else -> SettingsSection(uiText(R.string.change_password)) {
                        Field(
                            uiText(R.string.current_password),
                            currentPassword,
                            "organization_current_password",
                            secret = true,
                            showSecretToggle = true,
                            enabled = !session.account.busy,
                        ) { currentPassword = it.take(128) }
                        Field(
                            uiText(R.string.new_password),
                            newPassword,
                            "organization_new_password",
                            secret = true,
                            showSecretToggle = true,
                            enabled = !session.account.busy,
                        ) { newPassword = it.take(24) }
                        Field(
                            uiText(R.string.confirm_new_password),
                            confirmation,
                            "organization_confirm_password",
                            secret = true,
                            showSecretToggle = true,
                            enabled = !session.account.busy,
                        ) { confirmation = it.take(24) }
                        Button(
                            onClick = { session.changePassword(currentPassword, newPassword, confirmation) },
                            enabled = !session.account.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.change_password)) }
                    }
                }
                Status(session)
                AppVersionFooter()
            }
        }
    }
}

// All safe-app roles: collects staff and admin setup details.

/**
 * Staff and administrators complete organization contact details on first login.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun OrganizationProfileSetup(session: SessionViewModel) {
    val uiText = rememberResourceText()
    val existing = session.account.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var birthday by rememberBirthdayInput(existing?.dob.orEmpty())
    val dob = birthday
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var page by rememberSaveable(existing?.username) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pages = listOf(uiText(R.string.ui_personal_details), uiText(R.string.ui_contact_details))
    val emailValid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val required = listOf(fullName.isNotBlank(), InputPolicy.validDob(dob), emailValid)
    /**
     * Enables the next button only when every field on the current setup page is valid.
     *
     * @return The resulting page valid.
     */
    fun pageValid() =
        if (page == 0) fullName.isNotBlank() && InputPolicy.validDob(dob) else emailValid
    FormColumn {
        Heading(
            uiText(
                R.string.ui_complete_your_value_profile,
                session.account.role.localizedTitle(uiText)
            ), pages[page], uiText(R.string.olive_tree)
        )
        OnboardingProgress(
            required.count { it }, required.size, page, pages.size,
            listOf(fullName.isNotBlank() && InputPolicy.validDob(dob), emailValid)
        )
        // Only continue when page is 0.
        if (page == 0) {
            Field(
                uiText(R.string.full_name_required),
                fullName,
                "organization_full_name",
                enabled = !session.account.busy,
                error = if (showErrors && fullName.isBlank()) uiText(R.string.ui_name_required) else null
            ) {
                fullName = it.take(120)
                showErrors = false
            }
            BirthdayPicker(
                birthday, "organization_dob", !session.account.busy, showErrors,
            ) { birthday = it }
        } else {
            Field(
                uiText(R.string.ui_email_required),
                email,
                "organization_email",
                enabled = !session.account.busy,
                error = if (showErrors && !emailValid) uiText(R.string.ui_invalid_email) else null
            ) {
                email = it.take(254)
                showErrors = false
            }
            Field(
                uiText(R.string.ui_phone_optional),
                phone,
                "organization_phone",
                enabled = !session.account.busy
            ) { phone = it.take(40) }
        }
        Status(session)
        OnboardingNavigation(
            back = {
                showErrors = false
                if (page > 0) page-- else session.navigation.back()
            },
            next = {
                showErrors = true
                if (pageValid()) {
                    showErrors = false
                    if (page == pages.lastIndex) session.saveOrganizationProfile(
                        fullName,
                        dob,
                        email,
                        phone
                    ) else page++
                }
            },
            last = page == pages.lastIndex,
            nextTag = if (page == pages.lastIndex) "organization_profile_submit" else "onboarding_next",
            enabled = !session.account.busy
        )
    }
}

// Staff and admins: shows every available user location on the protected organization map.

/**
 * Opens the organization feed and draws each user as a red live or last-known marker.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onBack Callback invoked when the user navigates back.
 * @param initialFollowUid Optional initial follow user ID supplied to [OrganizationMap].
 */
@Composable
internal fun OrganizationMap(session: SessionViewModel, onBack: () -> Unit, initialFollowUid: String? = null) {
    BackHandler(onBack = onBack)
    DisposableEffect(session.coverAccountId, session.accountRole) {
        session.adminLocationMap.open(session.accountRole)
        onDispose(session.adminLocationMap::close)
    }
    val state = session.adminLocationMap.state
    Box(Modifier.fillMaxSize().testTag("organization_location_map")) {
        InteractiveLocationMap(
            points = state.points,
            ownPointUid = null,
            detailsOnlyForSelectedPoint = true,
            userSearchEnabled = true,
            initialFollowUid = initialFollowUid,
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 280.dp),
            shape = MaterialTheme.shapes.large,
            color = Paper,
            contentColor = Ink,
            border = BorderStroke(1.dp, OliveOutline.copy(alpha = 0.55f)),
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("organization_map_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
                Text(
                    stringResource(R.string.organization_live_map),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
        }
        val status = state.error ?: if (!state.loading && state.points.isEmpty())
            stringResource(R.string.organization_location_feed_empty) else null
        status?.let {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = Paper,
                contentColor = Ink,
                border = BorderStroke(1.dp, OliveOutline.copy(alpha = 0.55f)),
                shadowElevation = 4.dp,
            ) {
                AccessibleMessage(
                    it,
                    modifier = Modifier.padding(16.dp),
                    color = if (state.error != null) MaterialTheme.colorScheme.error else Muted,
                )
            }
        }
    }
}

// Staff and admins: shows account and support totals on dashboard cards.

/**
 * A large, readable portal action with enough context to explain where it goes.
 *
 * @param icon Icon supplied to [OrganizationDashboardCard].
 * @param title Primary display title text.
 * @param description Description supplied to [OrganizationDashboardCard].
 * @param onClick Callback invoked when the control is activated.
 * @param badgeCount Badge count supplied to [OrganizationDashboardCard].
 */
@Composable
internal fun OrganizationDashboardCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BadgedBox(badge = {
                if (badgeCount > 0) Badge {
                    Text(if (badgeCount > 99) "99+" else badgeCount.toString())
                }
            }) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
        }
    }
}

// Holds the live user locations shown on the organization map.
internal data class AdminLocationMapState(
    val points: List<MapLocationPoint> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

// Opens the protected organization location feed for staff and admin accounts.
internal class AdminLocationMapController(
    private val repository: AdminLocationRepository?,
) {
    private val stateMutableFlow = MutableStateFlow(AdminLocationMapState())
    val stateFlow: StateFlow<AdminLocationMapState> = stateMutableFlow.asStateFlow()
    private var stateSnapshot by mutableStateOf(stateMutableFlow.value)
    var state: AdminLocationMapState
        get() = stateSnapshot
        private set(value) {
            stateMutableFlow.value = value
            stateSnapshot = value
        }
    private var subscription: ChatSubscription? = null

    /**
     * Starts the organization feed only for an authenticated staff or admin account.
     *
     * @param role Account role determining workspace permissions.
     */
    fun open(role: AccountRole) {
        if (role !in setOf(AccountRole.STAFF, AccountRole.ADMIN) || repository == null || subscription != null) return
        state = AdminLocationMapState(loading = true)
        subscription = repository.observe(
            onUpdate = { points -> state = AdminLocationMapState(points = points) },
            onError = { message -> state = state.copy(loading = false, error = message) },
        )
    }

    /**
     * Stops Firebase updates and clears sensitive map data from memory.
     */
    fun close() {
        subscription?.close()
        subscription = null
        state = AdminLocationMapState()
    }
}
