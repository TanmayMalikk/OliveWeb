package com.olive.app.ui.session

import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

// Owns profile, password, trigger and PIN updates.
internal class SessionSettings(private val runtime:SessionRuntime) {
    fun saveSettings(fullName: String, affected: String, abuserName: String, dob: String, selectedLanguage: String, situation: String, address: DeliveryAddress) {
        with(runtime) {
            with(state) {
                if(screen!=SessionScreen.Safe || busy) return
                history.recordSettingsInteraction("Olive Branch","Pressed save profile settings")
                if(fullName.isBlank() || !InputPolicy.validDob(dob) || selectedLanguage.isBlank() || !address.locationValid) { message="Full name, a valid date of birth, language and city are required."; return }
                val updated=profile?.copy(fullName=fullName.trim(),affectedNames=affected.trim(),abuserName=abuserName.trim(),dob=dob,language=selectedLanguage,situation=situation.trim(),region=address.generalLocation,address=address) ?: return
                saveSafeProfile(updated)
            }
        }
    }

    fun saveCoverSettings(fullName: String, selectedLanguage: String) {
        with(runtime) {
            with(state) {
                if(screen!=SessionScreen.Cover || busy) return
                history.recordSettingsInteraction("Cover","Pressed save account settings")
                val supportedLangs = setOf("English","French","Spanish","Arabic","Tagalog","Chinese","Cree","en","fr","es","ar","tl","zh","cr")
                if(fullName.isBlank() || selectedLanguage !in supportedLangs) {
                    message="Enter your full name and preferred language."; return
                }
                val device=enrollment ?: return
                operations.runOperation("Unable to save account settings.") { token ->
                    val updated=withContext(Dispatchers.IO) {
                        val before=store.profile(device.uid)
                        val after=before.copy(fullName=fullName.trim(),language=selectedLanguage)
                        store.cacheProfile(device.uid,after,pending=!localMode,
                            audit=SettingsEvent(area="Cover",action="Saved account settings",changes=profileChanges(before,after)))
                        after
                    }
                    if(epoch==token && screen==SessionScreen.Cover) {
                        coverFullName=updated.fullName; language=updated.language
                        pendingSync=!localMode; message="Account settings saved."
                    }
                    history.queueHistorySync()
                    if(!localMode) syncProfile(device.uid,updated,token)
                }
            }
        }
    }

    fun saveSafeProfile(updated: UserProfile) {
        with(runtime) {
            with(state) {
                val device=enrollment ?: return
                val before=profile ?: return
                operations.runOperation("Unable to save profile changes.") { token ->
                    withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated,pending=!localMode,
                        audit=SettingsEvent(area="Olive Branch",action="Saved profile settings",changes=profileChanges(before,updated))) }
                    if(epoch==token) { profile=updated; coverFullName=updated.fullName; language=updated.language; deliveryAddress=updated.address; pendingSync=!localMode; message="Changes saved." }
                    history.queueHistorySync()
                    if(!localMode) syncProfile(device.uid,updated,token)
                }
            }
        }
    }

    fun changeCover(type: CoverType) {
        with(runtime) {
            with(state) {
                if(screen != SessionScreen.Safe || busy || accountRole!=AccountRole.USER) return
                if(type == CoverType.GAME) { message="In development"; return }
                val before=profile ?: return
                if(type==before.coverType) return
                val updated=before.copy(coverType=type)
                val device=enrollment ?: return
                // Show the chosen cover right away; saving keeps going even if Quick exit is tapped immediately.
                val token=epoch
                profile=updated;coverType=type;busy=true;message=null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated,pending=!localMode,
                            audit=SettingsEvent(area="Olive Branch",action="Changed cover app",changes=profileChanges(before,updated))) }
                        if(epoch==token) { pendingSync=!localMode;message="Cover app changed to ${type.title}." }
                        history.queueHistorySync()
                        if(!localMode) syncProfile(device.uid,updated,token)
                    } catch(e: CancellationException) { throw e }
                    catch(_: Exception) { if(epoch==token) message="Unable to change the cover app." }
                    finally { if(epoch==token) busy=false }
                }
            }
        }
    }

    fun changeTrigger(trigger: String, pin: String) {
        with(runtime) {
            with(state) {
                if(screen!=SessionScreen.Safe || busy || accountRole!=AccountRole.USER) return
                history.recordSettingsInteraction("Olive Branch","Pressed change trigger word")
                val device=enrollment ?: return
                if(!InputPolicy.validTrigger(trigger) || !InputPolicy.validPin(pin)) { message="Enter a trigger of 3–64 characters and your 4–6 digit PIN."; return }
                operations.runOperation("Unable to change the trigger. Please retry.") { token ->
                    val result=withContext(Dispatchers.IO) { store.verify(device.uid,pin) }
                    if(epoch!=token || screen!=SessionScreen.Safe) return@runOperation
                    when(result) {
                        PinResult.Accepted -> {
                            val before=profile!!
                            val updated=before.copy(triggerHash=InputPolicy.triggerHash(trigger))
                            withContext(Dispatchers.IO) { store.updateTrigger(device.uid,updated,!localMode,
                                SettingsEvent(area="Olive Branch",action="Changed trigger word",changes=profileChanges(before,updated))) }
                            enrollment=device.copy(triggerHash=updated.triggerHash)
                            if(epoch==token) { profile=updated; message="Trigger word changed."; pendingSync=!localMode }
                            history.queueHistorySync()
                            if(!localMode) syncProfile(device.uid,updated,token)
                        }
                        is PinResult.Rejected -> {
                            attempts=withContext(Dispatchers.IO) { store.attempts(device.uid) }
                            if(result.failures>=5) navigation.quickExit() else message="Incorrect PIN. ${5-result.failures} attempts remaining."
                        }
                    }
                }
            }
        }
    }

    fun saveContacts(value: List<SafeContact>) {
        with(runtime) {
            with(state) {
                if(screen != SessionScreen.Safe || busy) return
                history.recordSettingsInteraction("Olive Branch","Pressed save safe contacts")
                val device = enrollment ?: return
                val before = profile ?: return
                val updated = before.copy(contacts=value)
                operations.runOperation("Unable to save contacts on this device. Please retry.") { token ->
                    withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated,pending=!localMode,
                        audit=SettingsEvent(area="Olive Branch",action="Saved safe contacts",changes=profileChanges(before,updated))) }
                    if(epoch == token) { profile=updated; pendingSync=!localMode; message="Contacts saved on this device." }
                    history.queueHistorySync()
                    if(!localMode) syncProfile(device.uid,updated,token)
                }
            }
        }
    }

    fun sync() {
        with(runtime) {
            with(state) {
                val device=enrollment ?: return
                val value=profile ?: return
                if(screen != SessionScreen.Safe || busy || localMode) return
                history.recordSettingsInteraction("Olive Branch","Pressed sync changes")
                operations.runOperation("Changes are saved on this device. Cloud sync is unavailable; retry when connected.") { token ->
                    syncProfile(device.uid,value,token)
                    history.queueHistorySync()
                }
            }
        }
    }

    suspend fun syncProfile(uid: String, value: UserProfile, token: Long) {
        with(runtime) {
            with(state) {
                try {
                    withTimeout(15_000.milliseconds) { accounts.saveProfile(AccountIdentity(uid,value.username),value) }
                    withContext(Dispatchers.IO) { store.markSynced(uid) }
                    if(epoch == token) { pendingSync=false; message="Changes synced." }
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) {
                    if(epoch == token) { pendingSync=true; message="Saved on this device. Cloud sync failed; retry when connected." }
                }
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) {
        with(runtime) {
            with(state) {
                if(screen !in setOf(SessionScreen.Safe,SessionScreen.Portal) || busy) return
                val area=if(screen==SessionScreen.Portal) "Olive Tree" else "Olive Branch"
                if(screen==SessionScreen.Safe) history.recordSettingsInteraction(area,"Pressed change account password")
                val device=enrollment ?: return
                val username=profile?.username ?: return
                if(currentPassword.isEmpty()) { message="Enter your current password."; return }
                if(!InputPolicy.validPassword(newPassword)) { message="Use 8–24 characters with a letter, number and special character."; return }
                if(newPassword!=confirmation) { message="The new passwords do not match."; return }
                operations.runOperation("Unable to change the password. Check your current password and connection.") { token ->
                    accounts.changePassword(AccountIdentity(device.uid,username),currentPassword,newPassword)
                    withContext(Dispatchers.IO) { store.recordSettings(device.uid,SettingsEvent(area=area,
                        action="Changed account password",changes=listOf(FieldChange("Password","Hidden","Changed")))) }
                    history.queueHistorySync()
                    if(epoch==token && screen in setOf(SessionScreen.Safe,SessionScreen.Portal)) message="Password changed."
                }
            }
        }
    }

    fun changePin(currentPin: String, newPin: String, confirmation: String) {
        with(runtime) {
            with(state) {
                if(screen!=SessionScreen.Safe || busy) return
                history.recordSettingsInteraction("Olive Branch","Pressed change device PIN")
                val device=enrollment ?: return
                if(!InputPolicy.validPin(currentPin) || !InputPolicy.validPin(newPin)) { message="Enter 4–6 digits for both PINs."; return }
                if(newPin!=confirmation) { message="The new PINs do not match."; return }
                operations.runOperation("Unable to change the PIN.") { token ->
                    when(val result=withContext(Dispatchers.IO) { store.changePin(device.uid,currentPin,newPin) }) {
                        PinResult.Accepted -> {
                            withContext(Dispatchers.IO) { store.recordSettings(device.uid,SettingsEvent(area="Olive Branch",
                                action="Changed device PIN",changes=listOf(FieldChange("Device PIN","Hidden","Changed")))) }
                            history.queueHistorySync()
                            if(epoch==token && screen==SessionScreen.Safe) message="Device PIN changed."
                        }
                        is PinResult.Rejected -> {
                            attempts=withContext(Dispatchers.IO) { store.attempts(device.uid) }
                            if(result.failures>=5) navigation.quickExit() else message="Incorrect PIN. ${5-result.failures} attempts remaining."
                        }
                    }
                }
            }
        }
    }
}
