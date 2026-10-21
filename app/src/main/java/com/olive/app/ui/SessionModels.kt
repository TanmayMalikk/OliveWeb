package com.olive.app.ui

import com.olive.app.data.AccountIdentity
import java.security.MessageDigest
import java.util.Base64

enum class SessionScreen { Loading, Welcome, NewCredentials, ReturningCredentials, ProfileSetup, TriggerSetup, OrganizationProfileSetup, DevicePin, Cover, Pin, Safe, Portal, StorageError }
enum class SafePage { Home, Notes, Messages, Profile, Contacts, Settings, Activity, Hotlines, SafetyPlan, Documents, Camera, Location }
enum class ActivityCategory { Settings, Triggers, PinAttempts, Checkouts, Privacy }
enum class CredentialFieldError { InvalidUsername, InvalidPassword, UsernameTaken, IncorrectPassword }

internal fun messagingIdentityMarker(identity:AccountIdentity,publicKey:ByteArray):String {
    val digest=MessageDigest.getInstance("SHA-256").digest(publicKey)
    val keyId=Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(16)
    return "secure-v2:${identity.uid}:${identity.username.lowercase()}:$keyId"
}

internal fun shouldRegisterMessagingIdentity(savedMarker:String?,identity:AccountIdentity,publicKey:ByteArray)=
    savedMarker!=messagingIdentityMarker(identity,publicKey)
