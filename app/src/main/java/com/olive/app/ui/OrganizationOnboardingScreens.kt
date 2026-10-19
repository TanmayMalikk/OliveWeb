package com.olive.app.ui
import com.olive.app.R

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.olive.app.security.InputPolicy

// USER ONLY: an administrator cannot know this private trigger, so a provisioned user sets it personally on first login.
@Composable internal fun TriggerSetup(session:SessionViewModel) {
    val uiText=rememberResourceText()
    var trigger by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val valid=InputPolicy.validTrigger(trigger)
    FormColumn {
        Heading(uiText(R.string.ui_set_your_private_entry),uiText(R.string.ui_choose_your_trigger_word))
        OnboardingProgress(if(valid) 1 else 0,1,0,1,listOf(valid))
        Field(uiText(R.string.trigger_word_or_phrase),trigger,"trigger",secret=true,showSecretToggle=true,enabled=!session.busy,
            error=if(showErrors && !valid) uiText(R.string.ui_invalid_trigger) else null) { trigger=it.take(64);showErrors=false }
        RequirementBullet(uiText(R.string.ui_3_64_characters),valid,trigger.isNotEmpty())
        Status(session)
        OnboardingNavigation(session::back,{showErrors=true;if(valid) session.saveProvisionedTrigger(trigger)},last=true,
            nextTag="trigger_setup_submit",enabled=!session.busy)
    }
}

// STAFF + ADMIN ONLY: first-login setup saves contact details; Firebase Auth keeps the username alias.
@Composable internal fun OrganizationProfileSetup(session:SessionViewModel) {
    val uiText=rememberResourceText()
    val existing=session.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var dob by remember(existing) { mutableStateOf(existing?.dob.orEmpty()) }
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var page by rememberSaveable(existing?.username) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pages=listOf(uiText(R.string.ui_personal_details),uiText(R.string.ui_contact_details))
    val emailValid=android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val required=listOf(fullName.isNotBlank(),InputPolicy.validDob(dob),emailValid)
    fun pageValid()=if(page==0) fullName.isNotBlank() && InputPolicy.validDob(dob) else emailValid
    FormColumn {
        Heading(uiText(R.string.ui_complete_your_value_profile , session.accountRole.localizedTitle(uiText)),pages[page],uiText(R.string.olive_tree))
        OnboardingProgress(required.count { it },required.size,page,pages.size,
            listOf(fullName.isNotBlank() && InputPolicy.validDob(dob),emailValid))
        if(page==0) {
            Field(uiText(R.string.full_name_required),fullName,"organization_full_name",enabled=!session.busy,
                error=if(showErrors && fullName.isBlank()) uiText(R.string.ui_name_required) else null) { fullName=it.take(120);showErrors=false }
            BirthdayPicker(dob,"organization_dob",!session.busy,
                error=if(showErrors && !InputPolicy.validDob(dob)) uiText(R.string.ui_birthday_required) else null) { dob=it;showErrors=false }
        } else {
            Field(uiText(R.string.ui_email_required),email,"organization_email",enabled=!session.busy,
                error=if(showErrors && !emailValid) uiText(R.string.ui_invalid_email) else null) { email=it.take(254);showErrors=false }
            Field(uiText(R.string.ui_phone_optional),phone,"organization_phone",enabled=!session.busy) { phone=it.take(40) }
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
