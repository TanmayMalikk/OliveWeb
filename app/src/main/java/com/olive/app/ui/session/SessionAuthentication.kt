package com.olive.app.ui.session

import androidx.compose.runtime.*
import com.olive.app.BuildConfig
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.ui.*
import com.olive.app.notification.LocalNotificationEventHandler
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

// Owns account login and onboarding.
internal class SessionAuthentication(private val runtime:SessionRuntime) {
    fun load() {
        with(runtime) {
            with(state) {
                if(busy) return
                busy = true
                screenHistory.clear();safePageHistory.clear()
                screen = SessionScreen.Loading
                scope.launch {
                    try {
                        check(BuildConfig.DEBUG || !localMode)
                        val restoredSession=restoreStoredSession()
                        enrollment=restoredSession?.enrollment
                        restoredSession?.let { restorePresentation(it) }
                        routeRestoredAccount()
                        if(enrollment?.role==AccountRole.ADMIN && !localMode) organization.queueAdminRefresh()
                    } catch(_: Exception) { screen = SessionScreen.StorageError }
                    finally { busy = false }
                }
            }
        }
    }

    fun selectRole(role: AccountRole) {
        with(runtime) {
            with(state) {
                if(!busy) { accountRole=role;message=null;credentialFieldError=null }
            }
        }
    }

    fun chooseNew() {
        with(runtime) {
            with(state) {
                if(busy) return
                if(accountRole==AccountRole.STAFF || accountRole==AccountRole.ADMIN) { message="Staff and Admin accounts are created by an administrator.";return }
                creatingAccount=true;message=null;navigation.openScreen(SessionScreen.NewCredentials)
            }
        }
    }

    fun chooseReturning() {
        with(runtime) {
            with(state) {
                if(!busy) { creatingAccount=false;message=null; navigation.openScreen(SessionScreen.ReturningCredentials) }
            }
        }
    }

    fun setup() {
        with(runtime) {
            with(state) {
                if(!busy) { creatingAccount=false;message=null; navigation.openScreen(SessionScreen.Welcome) }
            }
        }
    }

    fun error(text: String) {
        with(runtime) {
            with(state) {
                message = text
            }
        }
    }

    fun clearCredentialError() {
        with(runtime) {
            with(state) {
                credentialFieldError=null;message=null
            }
        }
    }

    fun authenticate(username: String, password: String, create: Boolean) {
        with(runtime) {
            with(state) {
                if(busy) return
                credentialFieldError=null
                val organizationEmail=accountRole in setOf(AccountRole.STAFF,AccountRole.ADMIN) && '@' in username
                val validUsername=if(create) InputPolicy.validUsername(username) else InputPolicy.validExistingUsername(username)
                if(!organizationEmail && !validUsername) { credentialFieldError=CredentialFieldError.InvalidUsername;message="Invalid username."; return }
                if(create && !InputPolicy.validPassword(password)) { credentialFieldError=CredentialFieldError.InvalidPassword;message="Invalid password."; return }
                if(password.isEmpty()) { credentialFieldError=CredentialFieldError.IncorrectPassword;message="Password required."; return }
                operations.runOperation("Unable to sign in. Check your credentials and connection, then try again.") { token ->
                    val identity = try {
                        if(create) accounts.create(username,password,accountRole) else accounts.authenticate(username,password,accountRole)
                    } catch(error:Exception) {
                        val details=error.message.orEmpty()
                        val usernameUnavailable=create && (error is FirebaseAuthUserCollisionException ||
                            details.contains("unavailable",ignoreCase=true))
                        val incorrectLogin=!create && (error is FirebaseAuthInvalidCredentialsException ||
                            error is FirebaseAuthInvalidUserException || details.contains("sign-in failed",ignoreCase=true))
                        if(!usernameUnavailable && !incorrectLogin) throw error
                        if(epoch==token) {
                            credentialFieldError=if(usernameUnavailable) CredentialFieldError.UsernameTaken else CredentialFieldError.IncorrectPassword
                            message=if(usernameUnavailable) "Username taken." else "Incorrect password."
                        }
                        return@runOperation
                    }
                    val remote = accounts.profile(identity)
                    val existing = withContext(Dispatchers.IO) {
                        if(store.pendingSync(identity.uid)) store.profile(identity.uid) else remote
                    }
                    if(epoch == token) {
                        if(existing==null && !create && (accountRole==AccountRole.STAFF || accountRole==AccountRole.ADMIN)) {
                            accounts.signOut();message="This ${accountRole.title} account has not been provisioned by an administrator.";return@runOperation
                        }
                        if(existing!=null && existing.role!=accountRole) {
                            accounts.signOut();message="This account is registered as ${existing.role.title}. Select that account type to sign in.";return@runOperation
                        }
                        pendingIdentity = identity
                        pendingProfile = existing
                        if(existing!=null && existing.role==AccountRole.SAFE_CONTACT && AccountPolicy.personalProfileNeedsSetup(existing)) {
                            navigation.openScreen(SessionScreen.ProfileSetup)
                        } else if(existing!=null && existing.role in setOf(AccountRole.STAFF,AccountRole.ADMIN) && AccountPolicy.organizationProfileNeedsSetup(existing)) {
                            navigation.openScreen(SessionScreen.OrganizationProfileSetup)
                        } else if(existing!=null && existing.role!=AccountRole.USER) {
                            val saved=withContext(Dispatchers.IO) { store.enrollWithoutPin(identity,existing) }
                            enrollment=saved;profile=existing;coverUsername=existing.username;coverFullName=existing.fullName
                            accountRole=existing.role;language=existing.language;deliveryAddress=existing.address
                            screenHistory.clear();safePageHistory.clear()
                            if(existing.role==AccountRole.SAFE_CONTACT) {
                                page=SafePage.Home;screen=SessionScreen.Safe;history.refreshHistory(saved.uid)
                            } else {
                                screen=SessionScreen.Portal
                                if(existing.role==AccountRole.ADMIN) organization.queueAdminRefresh()
                            }
                        } else navigation.openScreen(when {
                            existing==null || AccountPolicy.personalProfileNeedsSetup(existing) -> SessionScreen.ProfileSetup
                            existing.triggerHash.isBlank() -> SessionScreen.TriggerSetup
                            else -> SessionScreen.DevicePin
                        })
                    }
                }
            }
        }
    }

    fun saveSetupProfile(value: UserProfile) {
        with(runtime) {
            with(state) {
                val identity = pendingIdentity ?: return
                if(busy) return
                if(value.role!=accountRole) { message="Account type mismatch.";return }
                if(value.fullName.isBlank() || !InputPolicy.validDob(value.dob) || value.language.isBlank() || !value.address.locationValid) {
                    message="Full name, a valid date of birth, language and city are required."; return
                }
                operations.runOperation("Your account exists, but the profile was not saved. Retry here, or sign in again to finish setup.") { token ->
                    accounts.saveProfile(identity,value)
                    if(value.role==AccountRole.SAFE_CONTACT) {
                        val saved=withContext(Dispatchers.IO) { store.enrollWithoutPin(identity,value) }
                        if(epoch==token) {
                            enrollment=saved;profile=value;coverUsername=value.username;coverFullName=value.fullName
                            accountRole=value.role;language=value.language;deliveryAddress=value.address
                            screenHistory.clear();safePageHistory.clear();page=SafePage.Home;screen=SessionScreen.Safe
                            history.refreshHistory(saved.uid)
                            if(!localMode) history.loadCloudHistory(saved.uid,value.username)
                        }
                    } else {
                        withContext(Dispatchers.IO) { store.cacheProfile(identity.uid,value) }
                        if(epoch == token) { pendingProfile=value; navigation.openScreen(SessionScreen.DevicePin) }
                    }
                }
            }
        }
    }

    fun createDevicePin(pin: String, confirmation: String) {
        with(runtime) {
            with(state) {
                if(busy) return
                if(!InputPolicy.validPin(pin) || pin != confirmation) { message="Enter the same 4–6 digit PIN in both fields."; return }
                val identity = pendingIdentity ?: return
                val value = pendingProfile ?: return
                operations.runOperation("Unable to save this device's PIN. Please retry.") { token ->
                    val saved = withContext(Dispatchers.IO) { store.enroll(identity,value,pin) }
                    finishEnrollment(saved,value,token)
                }
            }
        }
    }

    internal suspend fun finishEnrollment(saved: DeviceEnrollment, value: UserProfile, token: Long): Unit = with(runtime) {
        with(state) {
            if (epoch!=token || screen!=SessionScreen.DevicePin) return@with
            enrollment=saved
            deliveryAddress=value.address;language=value.language
            coverFullName=value.fullName;coverUsername=value.username
            coverType=value.coverType;accountRole=value.role
            covers.restoreCoverData(saved.uid)
            navigation.quickExit()
            if(!localMode) history.loadCloudHistory(saved.uid,value.username)
        }
    }

    fun saveProvisionedTrigger(trigger:String) {
        with(runtime) {
            with(state) {
                val identity=pendingIdentity ?: return
                val existing=pendingProfile ?: return
                if(!InputPolicy.validTrigger(trigger)) { message="Choose a trigger word or phrase of 3–64 characters.";return }
                val updated=existing.copy(triggerHash=InputPolicy.triggerHash(trigger))
                operations.runOperation("The trigger word could not be saved. Retry here.") { token ->
                    accounts.saveProfile(identity,updated)
                    withContext(Dispatchers.IO) { store.cacheProfile(identity.uid,updated) }
                    if(epoch==token) { pendingProfile=updated;navigation.openScreen(SessionScreen.DevicePin) }
                }
            }
        }
    }

    fun signOut() {
        with(runtime) {
            with(state) {
                if((screen != SessionScreen.Safe && screen != SessionScreen.Portal) || busy || enrollment == null) return
                val uid=enrollment!!.uid
                LocalNotificationEventHandler.cancel(application)
                navigation.quickExit()
                historySyncJob?.cancel();historySyncJob=null
                busy=true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            store.recordSettings(uid,SettingsEvent(area="Olive Branch",action="Signed out on this device"))
                            store.forgetDevice()
                        }
                        if(!localMode) runCatching {
                            withTimeoutOrNull(2_000.milliseconds) {
                                store.pendingHistory(uid).forEach { record ->
                                    accounts.saveHistoryRecord(AccountIdentity(uid,coverUsername),record)
                                    withContext(Dispatchers.IO) { store.markHistorySynced(uid,record) }
                                }
                            }
                        }
                        accounts.signOut(); enrollment=null; deliveryAddress=DeliveryAddress(); language="English"
                        coverFullName=""; coverUsername=""; coverType=CoverType.FOOD;accountRole=AccountRole.USER;profile=null
                        foodCart=emptyMap();foodFavorites=emptySet();foodCustomizations=emptyMap();savedNews=emptyList();coverDataVersion++
                        screen=SessionScreen.Welcome
                    } catch(_: Exception) { /* Fail closed: the interface remains locked. */ }
                    finally { busy=false }
                }
            }
        }
    }

    private suspend fun restoreStoredSession():DeviceSession? = with(runtime) {
        withContext(Dispatchers.IO) {
            store.currentSession() ?: if(!localMode) {
                accounts.authenticatedUid()?.let { uid ->
                    store.restoreActive(uid)
                    store.currentSession()
                }
            } else null
        }
    }

    private suspend fun restorePresentation(restored:DeviceSession) {
        with(runtime) {
            with(state) {
                val device=restored.enrollment
                val saved=restored.profile
                deliveryAddress=saved.address; language=saved.language
                coverFullName=saved.fullName; coverUsername=saved.username
                coverType=saved.coverType
                accountRole=saved.role
                covers.restoreCoverData(device.uid)
                checkoutHistory=withContext(Dispatchers.IO) { store.checkoutHistory(device.uid) }
                if(!localMode) history.loadCloudHistory(device.uid,saved.username)
                if(saved.role!=AccountRole.USER) profile=saved
                if(saved.role==AccountRole.SAFE_CONTACT) withContext(Dispatchers.IO) { store.removePin(device.uid) }
            }
        }
    }

    private fun routeRestoredAccount() {
        with(runtime) {
            with(state) {
                screen = when(enrollment?.role) {
                    null -> SessionScreen.Welcome
                    AccountRole.USER -> SessionScreen.Cover
                    AccountRole.SAFE_CONTACT -> SessionScreen.Safe
                    AccountRole.STAFF,AccountRole.ADMIN -> SessionScreen.Portal
                }
            }
        }
    }
}
