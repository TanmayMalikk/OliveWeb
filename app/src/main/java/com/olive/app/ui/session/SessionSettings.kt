// Contains the session settings code for this part of Olive.
package com.olive.app.ui.session

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.R

import com.olive.app.data.*
import com.olive.app.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

// Saves private profile, cover choice, password, trigger, PIN and trusted-contact settings.
internal class SessionSettings(private val runtime: SessionRuntime) {
    // Saves settings.
    fun saveSettings(
        fullName: String,
        affected: String,
        abuserName: String,
        dob: String,
        situation: String,
        address: DeliveryAddress
    ) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || busy) return
                history.recordSettingsInteraction("Olive Branch", "Pressed save profile settings")
                if (fullName.isBlank() || !InputPolicy.validDob(dob) || !address.locationValid) {
                    message =
                        runtime.application.getString(R.string.ui_profile_required_fields)
                    return
                }
                val updated = profile?.copy(
                    fullName = fullName.trim(),
                    affectedNames = affected.trim(),
                    abuserName = abuserName.trim(),
                    dob = dob,
                    situation = situation.trim(),
                    region = address.generalLocation,
                    address = address
                ) ?: return
                saveSafeProfile(updated)
            }
        }
    }

    // Saves cover settings.
    fun saveCoverSettings(fullName: String, dob: String, address: DeliveryAddress) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Cover || busy) return
                history.recordSettingsInteraction("Cover", "Pressed save account settings")
                if (fullName.isBlank() || !InputPolicy.validDob(dob) || !address.locationValid) {
                    message =
                        runtime.application.getString(R.string.ui_profile_required_fields)
                    return
                }
                val device = enrollment ?: return
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_account_settings)) { token ->
                    val updated = withContext(Dispatchers.IO) {
                        val before = store.profile(device.uid)
                        val after = before.copy(
                            fullName = fullName.trim(),
                            dob = dob,
                            address = address,
                            region = address.generalLocation
                        )
                        store.cacheProfile(
                            device.uid, after, pending = !localMode,
                            audit = SettingsEvent(
                                area = "Cover",
                                action = "Saved account settings",
                                changes = profileChanges(before, after)
                            )
                        )
                        after
                    }
                    if (epoch == token && screen == SessionScreen.Cover) {
                        coverDob = updated.dob
                        coverFullName = updated.fullName
                        language =
                            updated.language
                        deliveryAddress = updated.address
                        pendingSync = !localMode
                        message =
                            runtime.application.getString(R.string.account_settings_saved)
                    }
                    history.queueHistorySync()
                    if (!localMode) syncProfile(device.uid, updated, token)
                }
            }
        }
    }

    // Saves safe profile.
    fun saveSafeProfile(updated: UserProfile) {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                val before = profile ?: return
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_profile_changes)) { token ->
                    withContext(Dispatchers.IO) {
                        store.cacheProfile(
                            device.uid, updated, pending = !localMode,
                            audit = SettingsEvent(
                                area = "Olive Branch",
                                action = "Saved profile settings",
                                changes = profileChanges(before, updated)
                            )
                        )
                    }
                    if (epoch == token) {
                        profile = updated
                        coverDob = updated.dob
                        coverFullName =
                            updated.fullName
                        language = updated.language
                        deliveryAddress =
                            updated.address
                        pendingSync = !localMode
                        message =
                            runtime.application.getString(R.string.changes_saved)
                    }
                    history.queueHistorySync()
                    if (!localMode) syncProfile(device.uid, updated, token)
                }
            }
        }
    }

    fun changeCover(type: CoverType) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return
                val before = profile ?: return
                if (type == before.coverType) return
                val updated = before.copy(coverType = type)
                val device = enrollment ?: return
                // Show the chosen cover right away; saving keeps going even if Quick exit is tapped immediately.
                val token = epoch
                profile = updated
                coverType = type
                busy = true
                message = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            store.cacheProfile(
                                device.uid, updated, pending = !localMode,
                                audit = SettingsEvent(
                                    area = "Olive Branch",
                                    action = "Changed cover app",
                                    changes = profileChanges(before, updated)
                                )
                            )
                        }
                        if (epoch == token) {
                            pendingSync = !localMode
                            message = runtime.application.getString(
                                R.string.ui_cover_app_changed_to_value,
                                type.title
                            )
                        }
                        history.queueHistorySync()
                        if (!localMode) syncProfile(device.uid, updated, token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (epoch == token) message =
                            runtime.application.getString(R.string.ui_unable_to_change_the_cover_app)
                    } finally {
                        if (epoch == token) busy = false
                    }
                }
            }
        }
    }

    fun changeTrigger(trigger: String, pin: String) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return
                history.recordSettingsInteraction("Olive Branch", "Pressed change trigger word")
                val device = enrollment ?: return
                if (!InputPolicy.validTrigger(trigger) || !InputPolicy.validPin(pin)) {
                    message =
                        runtime.application.getString(R.string.enter_a_trigger_of_3_64_characters_and_your_4_6_digit_pin)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_change_the_trigger_please_retry)) { token ->
                    val result = withContext(Dispatchers.IO) { store.verify(device.uid, pin) }
                    if (epoch != token || screen != SessionScreen.Safe) return@runOperation
                    when (result) {
                        PinResult.Accepted -> {
                            saveVerifiedTrigger(device, trigger, token)
                        }

                        is PinResult.Rejected -> {
                            attempts = withContext(Dispatchers.IO) { store.attempts(device.uid) }
                            if (result.failures >= 5) navigation.quickExit() else message =
                                runtime.application.resources.getQuantityString(
                                    R.plurals.ui_incorrect_pin_value_attempts_remaining,
                                    5 - result.failures,
                                    5 - result.failures
                                )
                        }
                    }
                }
            }
        }
    }

    // Saves verified trigger.
    internal suspend fun saveVerifiedTrigger(
        device: DeviceEnrollment,
        trigger: String,
        token: Long
    ): Unit = with(runtime) {
        with(state) {
            if (epoch != token || screen != SessionScreen.Safe || enrollment?.uid != device.uid) return@with
            val before = profile ?: return@with
            val updated = before.copy(triggerHash = InputPolicy.triggerHash(trigger))
            withContext(Dispatchers.IO) {
                store.updateTrigger(
                    device.uid, updated, !localMode,
                    SettingsEvent(
                        area = "Olive Branch",
                        action = "Changed trigger word",
                        changes = profileChanges(before, updated)
                    )
                )
            }
            if (epoch != token || screen != SessionScreen.Safe || enrollment?.uid != device.uid) return@with
            enrollment = device.copy(triggerHash = updated.triggerHash)
            profile = updated
            message =
                application.getString(R.string.trigger_word_changed)
            pendingSync = !localMode
            history.queueHistorySync()
            if (!localMode) syncProfile(device.uid, updated, token)
        }
    }

    // Saves contacts.
    fun saveContacts(value: List<SafeContact>) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || busy) return
                history.recordSettingsInteraction("Olive Branch", "Pressed save safe contacts")
                val device = enrollment ?: return
                val before = profile ?: return
                val updated = before.copy(contacts = value)
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_contacts_on_this_device_please_retry)) { token ->
                    withContext(Dispatchers.IO) {
                        store.cacheProfile(
                            device.uid, updated, pending = !localMode,
                            audit = SettingsEvent(
                                area = "Olive Branch",
                                action = "Saved safe contacts",
                                changes = profileChanges(before, updated)
                            )
                        )
                    }
                    if (epoch == token) {
                        profile = updated
                        pendingSync = !localMode
                        message =
                            runtime.application.getString(R.string.contacts_saved_on_this_device)
                    }
                    history.queueHistorySync()
                    if (!localMode) syncProfile(device.uid, updated, token)
                }
            }
        }
    }

    fun sync() {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                val value = profile ?: return
                if (screen != SessionScreen.Safe || busy || localMode) return
                history.recordSettingsInteraction("Olive Branch", "Pressed sync changes")
                operations.runOperation(runtime.application.getString(R.string.changes_are_saved_on_this_device_cloud_sync_is_unavailable_retry_when_connected)) { token ->
                    syncProfile(device.uid, value, token)
                    history.queueHistorySync()
                }
            }
        }
    }

    // Syncs profile.
    suspend fun syncProfile(uid: String, value: UserProfile, token: Long) {
        with(runtime) {
            with(state) {
                try {
                    withTimeout(15_000.milliseconds) {
                        accounts.saveProfile(
                            AccountIdentity(
                                uid,
                                value.username
                            ), value
                        )
                    }
                    withContext(Dispatchers.IO) { store.markSynced(uid) }
                    if (epoch == token) {
                        pendingSync = false
                        message =
                            runtime.application.getString(R.string.changes_synced)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (epoch == token) {
                        pendingSync = true
                        message =
                            runtime.application.getString(R.string.saved_on_this_device_cloud_sync_failed_retry_when_connected)
                    }
                }
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) {
        with(runtime) {
            with(state) {
                if (screen !in setOf(SessionScreen.Safe, SessionScreen.Portal) || busy) return
                val area = if (screen == SessionScreen.Portal) "Olive Tree" else "Olive Branch"
                if (screen == SessionScreen.Safe) history.recordSettingsInteraction(
                    area,
                    "Pressed change account password"
                )
                val device = enrollment ?: return
                val username = profile?.username ?: return
                if (currentPassword.isEmpty()) {
                    message =
                        runtime.application.getString(R.string.enter_your_current_password)
                    return
                }
                if (!InputPolicy.passwordExcludesUsername(
                        newPassword,
                        profile?.username ?: coverUsername
                    )
                ) {
                    message =
                        runtime.application.getString(R.string.password_excludes_username)
                    return
                }
                if (!InputPolicy.validAccountPassword(
                        newPassword,
                        profile?.username ?: coverUsername
                    )
                ) {
                    message =
                        runtime.application.getString(R.string.ui_use_8_24_characters_with_a_letter_number_and_special_character)
                    return
                }
                if (newPassword != confirmation) {
                    message =
                        runtime.application.getString(R.string.the_new_passwords_do_not_match)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_change_the_password_check_your_current_password_and_connection)) { token ->
                    accounts.changePassword(
                        AccountIdentity(device.uid, username),
                        currentPassword,
                        newPassword
                    )
                    withContext(Dispatchers.IO) {
                        store.recordSettings(
                            device.uid, SettingsEvent(
                                area = area,
                                action = "Changed account password",
                                changes = listOf(FieldChange("Password", "Hidden", "Changed"))
                            )
                        )
                    }
                    history.queueHistorySync()
                    if (epoch == token && screen in setOf(
                            SessionScreen.Safe,
                            SessionScreen.Portal
                        )
                    ) message = runtime.application.getString(R.string.password_changed)
                }
            }
        }
    }

}
