package com.olive.app.ui


import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.olive.app.security.InputPolicy

// USER ONLY: an administrator cannot know this private trigger, so a provisioned user sets it personally on first login.
@Composable internal fun TriggerSetup(session:SessionViewModel) {
    var trigger by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val valid=InputPolicy.validTrigger(trigger)
    FormColumn {
        Heading("Set your private entry","Choose your trigger word.")
        OnboardingProgress(if(valid) 1 else 0,1,0,1,listOf(valid))
        Field("Trigger word or phrase",trigger,"trigger",secret=true,showSecretToggle=true,enabled=!session.busy,
            error=if(showErrors && !valid) "Invalid trigger" else null) { trigger=it.take(64);showErrors=false }
        RequirementBullet("3–64 characters",valid,trigger.isNotEmpty())
        Status(session)
        OnboardingNavigation(session::back,{showErrors=true;if(valid) session.saveProvisionedTrigger(trigger)},last=true,
            nextTag="trigger_setup_submit",enabled=!session.busy)
    }
}

// STAFF + ADMIN ONLY: temporary organization accounts replace their alias with a real email during first-login setup.
@Composable internal fun OrganizationProfileSetup(session:SessionViewModel) {
    val existing=session.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var dob by remember(existing) { mutableStateOf(existing?.dob.orEmpty()) }
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var page by rememberSaveable(existing?.username) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pages=listOf("Personal details","Contact details")
    val emailValid=android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val required=listOf(fullName.isNotBlank(),InputPolicy.validDob(dob),emailValid)
    fun pageValid()=if(page==0) fullName.isNotBlank() && InputPolicy.validDob(dob) else emailValid
    FormColumn {
        Heading("Complete your ${session.accountRole.title} profile",pages[page],"Olive Tree")
        OnboardingProgress(required.count { it },required.size,page,pages.size,
            listOf(fullName.isNotBlank() && InputPolicy.validDob(dob),emailValid))
        if(page==0) {
            Field("Full name (required)",fullName,"organization_full_name",enabled=!session.busy,
                error=if(showErrors && fullName.isBlank()) "Name required" else null) { fullName=it.take(120);showErrors=false }
            BirthdayPicker(dob,"organization_dob",!session.busy,
                error=if(showErrors && !InputPolicy.validDob(dob)) "Birthday required" else null) { dob=it;showErrors=false }
        } else {
            Field("Email (required)",email,"organization_email",enabled=!session.busy,
                error=if(showErrors && !emailValid) "Invalid email" else null) { email=it.take(254);showErrors=false }
            Field("Phone (optional)",phone,"organization_phone",enabled=!session.busy) { phone=it.take(40) }
        }
        Status(session)
        OnboardingNavigation(back={showErrors=false;if(page>0) page-- else session.back()},next={
            showErrors=true
            if(pageValid()) {
                showErrors=false
                if(page==pages.lastIndex) session.saveOrganizationProfile(fullName,dob,email,phone) else page++
            }
        },last=page==pages.lastIndex,nextTag=if(page==pages.lastIndex) "organization_profile_submit" else "onboarding_next",
            enabled=!session.busy)
    }
}
