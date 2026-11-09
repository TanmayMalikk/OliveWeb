// All safe-app roles: shows staff and admin account settings.
package com.olive.app.ui.users.shared.organization

import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.onboarding.BirthdayPicker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.shared.onboarding.*
import android.util.Patterns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.olive.app.security.InputPolicy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.graphics.vector.ImageVector


// Shows profile and sign-out settings for staff and admin accounts.
@Composable
internal fun OrganizationSettings(session: SessionViewModel, onBack: () -> Unit) {
    val uiText = rememberResourceText()
    val profile = session.profile ?: return
    var fullName by remember(profile) { mutableStateOf(profile.fullName) }
    var dob by remember(profile) { mutableStateOf(profile.dob) }
    var email by remember(profile) { mutableStateOf(profile.email) }
    var phone by remember(profile) { mutableStateOf(profile.phone) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp).padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_back))
            }
            Text(
                stringResource(R.string.account_settings),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            uiText(R.string.ui_profile_information),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Field(
            uiText(R.string.full_name),
            fullName,
            "organization_settings_name",
            enabled = !session.account.busy,
        ) { fullName = it.take(120) }
        BirthdayPicker(dob, "organization_settings_dob", !session.account.busy) { dob = it }
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
            onClick = { session.updateOrganizationProfile(fullName, dob, email, phone) },
            enabled = !session.account.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save_profile)) }
        HorizontalDivider()
        Text(
            stringResource(R.string.change_password),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
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
        Status(session)
    }
}

// All safe-app roles: collects staff and admin setup details.

// Staff and administrators complete organization contact details on first login.
@Composable
internal fun OrganizationProfileSetup(session: SessionViewModel) {
    val uiText = rememberResourceText()
    val existing = session.account.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var dob by remember(existing) { mutableStateOf(existing?.dob.orEmpty()) }
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var page by rememberSaveable(existing?.username) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pages = listOf(uiText(R.string.ui_personal_details), uiText(R.string.ui_contact_details))
    val emailValid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val required = listOf(fullName.isNotBlank(), InputPolicy.validDob(dob), emailValid)
    // Enables the next button only when every field on the current setup page is valid.
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
                dob, "organization_dob", !session.account.busy,
                error = if (showErrors && !InputPolicy.validDob(dob)) uiText(R.string.ui_birthday_required) else null
            ) {
                dob = it
                showErrors = false
            }
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

/** Opens the organization feed and draws each user as a red live or last-known marker. */
@Composable
internal fun OrganizationMap(session: SessionViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    DisposableEffect(session.coverAccountId, session.accountRole) {
        session.adminLocationMap.open(session.accountRole)
        onDispose(session.adminLocationMap::close)
    }
    val state = session.adminLocationMap.state
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.safety_back)) }
        Text(
            stringResource(R.string.organization_live_map),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Box(
            Modifier.fillMaxWidth().height(420.dp),
            contentAlignment = Alignment.Center,
        ) {
            InteractiveLocationMap(
                points = state.points,
                ownPointUid = null,
                detailsOnlyForSelectedPoint = true,
                userSearchEnabled = true,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.loading) CircularProgressIndicator()
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.loading && state.error == null && state.points.isEmpty()) {
            Text(stringResource(R.string.organization_location_feed_empty), color = Muted)
        }
    }
}

// Staff and admins: shows account and support totals on dashboard cards.

/** A large, readable portal action with enough context to explain where it goes. */
@Composable
internal fun OrganizationDashboardCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
