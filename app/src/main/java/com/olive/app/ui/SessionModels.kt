package com.olive.app.ui

import com.olive.app.data.AccountIdentity

enum class SessionScreen { Loading, Welcome, NewCredentials, ReturningCredentials, ProfileSetup, TriggerSetup, OrganizationProfileSetup, DevicePin, Cover, Pin, Safe, Portal, StorageError }
enum class SafePage { Home, Notes, Messages, Profile, Contacts, Settings, Activity }
enum class ActivityCategory { Settings, Triggers, PinAttempts, Checkouts, Privacy }
enum class CredentialFieldError { InvalidUsername, InvalidPassword, UsernameTaken, IncorrectPassword }

internal fun messagingIdentityMarker(identity:AccountIdentity)="${identity.uid}:${identity.username.lowercase()}"
internal fun shouldRegisterMessagingIdentity(savedMarker:String?,identity:AccountIdentity)=
    savedMarker!=messagingIdentityMarker(identity)
