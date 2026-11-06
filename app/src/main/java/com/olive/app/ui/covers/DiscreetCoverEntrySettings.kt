// Cover users: shows cover settings and handles discreet entry into the safe app.
package com.olive.app.ui.covers

import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import com.olive.app.R
import com.olive.app.security.InputPolicy
import com.olive.app.ui.users.shared.onboarding.BirthdayPicker
import com.olive.app.ui.shared.AddressFields
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.olive.app.ui.shared.Cream
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel


// A tiny olive gives every cover the same private-entry shortcut without looking like a normal button.
// Sighted users double-tap it. TalkBack users activate it with TalkBack's normal double-tap gesture.
@Composable
internal fun DiscreetCoverEntry(
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val oliveLabel = stringResource(R.string.olive_icon)
    Canvas(
        modifier.size(48.dp)
            .testTag("discreet_cover_olive")
            .semantics {
                contentDescription = oliveLabel
                if (enabled) onClick {
                    onOpen()
                    true
                }
            }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onDoubleTap = { onOpen() })
            }
    ) {
        val oliveWidth = 11.dp.toPx()
        val oliveHeight = 15.dp.toPx()
        val center = Offset(size.width * .58f, size.height * .58f)
        rotate(-18f, center) {
            drawOval(
                color = OliveGreen.copy(alpha = .50f),
                topLeft = Offset(center.x - oliveWidth / 2f, center.y - oliveHeight / 2f),
                size = Size(oliveWidth, oliveHeight),
            )
        }
        rotate(-35f, Offset(center.x - 3.dp.toPx(), center.y - 8.dp.toPx())) {
            drawOval(
                color = SageGreen.copy(alpha = .45f),
                topLeft = Offset(center.x - 6.dp.toPx(), center.y - 11.dp.toPx()),
                size = Size(8.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
}

// Fitness and Game use this page for cover-account settings and the discreet entry olive.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverSettingsPage(session: SessionViewModel, onBack: () -> Unit) {
    val scrollState = rememberScrollState()
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).oliveScrollbar(scrollState)
                .verticalScroll(scrollState).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CoverAccountPanel(session)
        }
    }
}

// Shows cover-account settings and the hidden olive used for private entry.
@Composable
internal fun CoverAccountPanel(session: SessionViewModel) {
    var fullName by remember(session.coverFullName) { mutableStateOf(session.coverFullName) }
    var dob by remember(session.coverDob) { mutableStateOf(session.coverDob) }
    var address by remember(session.deliveryAddress) { mutableStateOf(session.deliveryAddress) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("@${session.coverUsername}")
        OutlinedTextField(
            fullName,
            { fullName = it.take(120) },
            label = { Text(stringResource(R.string.full_name)) },
            singleLine = true,
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("cover_full_name").onFocusChanged {
                if (it.isFocused) session.recordSettingsInteraction(
                    session.coverType.title,
                    "Focused full name"
                )
            }.keepVisibleWhileTyping()
        )
        BirthdayPicker(dob, "cover_dob", !session.busy) { dob = it }
        Text(stringResource(R.string.location), style = MaterialTheme.typography.titleMedium)
        AddressFields(address, cityOnlyRequired = true) { address = it }
        Button(
            onClick = { session.saveCoverSettings(fullName, dob, address) },
            enabled = !session.busy && fullName.isNotBlank() && InputPolicy.validDob(dob) && address.locationValid,
            modifier = Modifier.fillMaxWidth().testTag("cover_save_settings")
        ) {
            Text(stringResource(R.string.save_account_settings))
        }
        session.message?.let { AccessibleMessage(it) }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        DiscreetCoverEntry(
            enabled = !session.busy,
            onOpen = session.account::beginDiscreetEntry,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp),
        )
    }
}

// Android reads launcher names/icons from aliases; switch them here when the saved cover changes.
internal object CoverLauncher {
    // Keep Android Studio's Run target alive without touching the user's visible cover icon.
    fun ensureStudioLauncher(context: Context) {
        val manager = context.packageManager
        val component = ComponentName(context.packageName, "${context.packageName}.OliveLauncher")
        if (manager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            manager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
    }

    // Switches the visible launcher icon to the selected cover app.
    fun apply(context: Context, selected: CoverType, role: AccountRole) {
        val manager = context.packageManager
        val aliases = listOf(
            "GameLauncher",
            "NewsLauncher",
            "FitnessLauncher",
            "BranchLauncher",
            "TreeLauncher"
        )
        val target = when (role) {
            AccountRole.SAFE_CONTACT -> "BranchLauncher"
            AccountRole.STAFF, AccountRole.ADMIN -> "TreeLauncher"
            AccountRole.USER -> when (selected) {
                CoverType.NEWS -> "NewsLauncher"
                CoverType.FITNESS -> "FitnessLauncher"
                CoverType.GAME -> "GameLauncher"
            }
        }
        val changes = aliases.map { name ->
            val component = ComponentName(context.packageName, "${context.packageName}.$name")
            val state =
                if (name == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            component to state
        }.filter { (component, state) -> manager.getComponentEnabledSetting(component) != state }
        if (changes.isEmpty()) return
        // On Pixel's Android versions, change the aliases together so a launcher always stays enabled.
        if (Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP
                )
            })
        } else {
            changes.sortedByDescending { it.second == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                .forEach { (component, state) ->
                    manager.setComponentEnabledSetting(
                        component,
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
        }
    }
}

// Cover users: shows the olive game cover and its entry controls.

/** A small, usable game cover. The nickname field also accepts the configured safe-entry phrase. */
@Composable
internal fun GameCover(
    sessionViewModel: SessionViewModel? = null,
    interactive: Boolean = true,
    onEntryAttempt: (String) -> Boolean = { false },
) {
    var nickname by remember { mutableStateOf("") }
    var score by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen && sessionViewModel != null) {
        CoverSettingsPage(sessionViewModel) { settingsOpen = false }
        return
    }
    BackHandler(interactive) { }
    Column(
        Modifier.fillMaxSize().background(Cream).safeDrawingPadding().padding(24.dp)
            .testTag("game_cover"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(34.dp).testTag("cover_brand_game"),
                    tint = OliveGreen,
                )
            }
            Text(
                stringResource(R.string.game_cover_title),
                modifier = Modifier.semantics { heading() },
                fontFamily = FontFamily.Serif,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = OliveGreen,
            )
            if (sessionViewModel != null) IconButton(
                onClick = { settingsOpen = true },
                enabled = interactive,
                modifier = Modifier.testTag("game_settings"),
            ) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.game_cover_instructions), color = Muted)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { value ->
                if (onEntryAttempt(value)) nickname = "" else nickname = value.take(64)
            },
            enabled = interactive,
            singleLine = true,
            label = { Text(stringResource(R.string.game_player_name)) },
            modifier = Modifier.fillMaxWidth().testTag("game_entry"),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { score++ },
            enabled = interactive,
            modifier = Modifier.size(112.dp).testTag("game_olive"),
            shape = CircleShape,
        ) {
            Text(stringResource(R.string.game_tap_olive))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.game_score, score),
            modifier = Modifier.testTag("game_score")
                .semantics { liveRegion = LiveRegionMode.Polite },
            fontWeight = FontWeight.SemiBold,
        )
    }
}
