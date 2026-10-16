package com.olive.app.ui

import com.olive.app.ui.covers.olive.AddressFields

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import java.util.UUID

// USER and SAFE CONTACT: shared profile questions live here.
// USER ONLY: abuse details, situation, trigger, optional safe contact, and cover-app choice are inside !safeContact blocks.
// UI sizing/text: section titles use Material title sizes, helper text uses bodySmall, and all fields use the shared responsive form width.
@Composable internal fun ProfileSetup(session: SessionViewModel) {
    val safeContact=session.accountRole==AccountRole.SAFE_CONTACT
    val existing=session.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var affected by remember(existing) { mutableStateOf(existing?.affectedNames.orEmpty()) }
    var abuserName by remember(existing) { mutableStateOf(existing?.abuserName.orEmpty()) }
    var dob by remember(existing) { mutableStateOf(existing?.dob.orEmpty()) }
    var language by remember(existing) { mutableStateOf(existing?.language?.ifBlank { "English" } ?: "English") }
    var situation by remember(existing) { mutableStateOf(existing?.situation.orEmpty()) }
    var address by remember(existing) { mutableStateOf(existing?.address ?: DeliveryAddress()) }
    var trigger by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactDetails by remember { mutableStateOf("") }
    val contacts=remember(existing) { mutableStateListOf<SafeContact>().also { it.addAll(existing?.contacts.orEmpty()) } }
    var coverType by remember(existing) { mutableStateOf(existing?.coverType ?: CoverType.FOOD) }
    val pages=if(safeContact) listOf("Personal details","Location") else
        listOf("Personal details","Location","Safe entry & cover")
    var page by rememberSaveable(existing?.username,safeContact) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val validation=ProfileSetupValidation(safeContact,fullName,dob,language,address,trigger,contactName,contactDetails)
    val required=validation.requiredFields
    val completed=required.count { it }
    val supportValid=validation.supportValid
    val localCompletion=validation.pageCompletion
    val stages=if(safeContact) 3 else 4
    val progressPage=page+1
    val credentialCount=if(session.creatingAccount) 3 else 2
    val totalRequired=credentialCount+required.size+if(safeContact) 0 else 2
    val progressCompleted=credentialCount+completed
    val pageCompletion=listOf(true)+localCompletion
    fun collectedContacts()=(contacts.toList()+if(contactName.isNotBlank() && contactDetails.isNotBlank())
        listOf(SafeContact(UUID.randomUUID().toString(),contactName.trim(),contactDetails.trim())) else emptyList()).take(25)
    fun saveProfile()=session.saveSetupProfile(UserProfile(
        username=session.setupUsername,fullName=fullName.trim(),affectedNames=affected.trim(),abuserName=abuserName.trim(),
        dob=dob.trim(),language=language.trim(),situation=situation.trim(),region=address.generalLocation,
        triggerHash=if(safeContact) "" else InputPolicy.triggerHash(trigger),
        contacts=if(safeContact) emptyList() else collectedContacts(),
        address=address,coverType=coverType,role=session.accountRole))
    FormColumn {
        Heading("Your profile",pages[page])
        OnboardingProgress(progressCompleted,totalRequired,progressPage,stages,pageCompletion)
        AnimatedContent(targetState=pages[page],label="onboarding page") { current -> when(current) {
            "Personal details" -> Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Field("Full name (required)",fullName,"full_name",enabled=!session.busy,
                    error=if(showErrors && fullName.isBlank()) "Name required" else null) {
                    fullName=it.take(120);showErrors=false
                }
                BirthdayPicker(dob,"dob",!session.busy,error=if(showErrors && !InputPolicy.validDob(dob)) "Birthday required" else null) {
                    dob=it;showErrors=false
                }
                LanguagePicker(language,!session.busy) { language=it;showErrors=false }
                if(!safeContact) {
                    HorizontalDivider()
                    Field("Names of people experiencing abuse (optional)",affected,"affected_names",enabled=!session.busy) { affected=it.take(500) }
                    Field("Abuser's name (optional)",abuserName,"abuser_name",enabled=!session.busy) { abuserName=it.take(120) }
                    Field("Brief description (optional)",situation,"situation",multiline=true,enabled=!session.busy) { situation=it.take(2000) }
                }
            }
            "Location" -> Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                LocalizedText("Only your city is required.",color=Muted,style=MaterialTheme.typography.bodySmall)
                AddressFields(address,cityOnlyRequired=true,cityError=if(showErrors && !address.locationValid) "City required" else null) {
                    address=it;showErrors=false
                }
            }
            "Safe entry & cover" -> Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Field("Trigger word or phrase",trigger,"trigger",secret=true,showSecretToggle=true,enabled=!session.busy,
                    error=if(showErrors && !InputPolicy.validTrigger(trigger)) "Invalid trigger" else null) {
                    trigger=it.take(64);showErrors=false
                }
                RequirementBullet("3–64 characters",InputPolicy.validTrigger(trigger),trigger.isNotEmpty())
                HorizontalDivider()
                CoverPicker(coverType,!session.busy) { chosen ->
                    if(chosen==CoverType.GAME) session.error("In development") else coverType=chosen
                }
                LocalizedText("Name and icon update after leaving the app.",color=Muted,style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                LocalizedText("Safe contacts · optional",style=MaterialTheme.typography.titleLarge)
                LocalizedText("Add one or more trusted people. Olive never contacts them automatically.",color=Muted,style=MaterialTheme.typography.bodySmall)
                contacts.forEach { contact ->
                    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(start=14.dp,top=8.dp,bottom=8.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                LocalizedText(contact.name,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                                LocalizedText(contact.details,color=Muted,style=MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick={contacts.remove(contact)},enabled=!session.busy) {
                                Icon(Icons.Outlined.Close,"Remove safe contact")
                            }
                        }
                    }
                }
                Field("Contact name (optional)",contactName,"setup_contact_name",enabled=!session.busy && contacts.size<25,
                    error=if(showErrors && !supportValid) "Complete both fields" else null) {
                    contactName=it.take(120);showErrors=false
                }
                Field("Phone or email (optional)",contactDetails,"setup_contact_details",enabled=!session.busy && contacts.size<25,
                    error=if(showErrors && !supportValid) "Complete both fields" else null) {
                    contactDetails=it.take(200);showErrors=false
                }
                OutlinedButton(onClick={
                    contacts += SafeContact(UUID.randomUUID().toString(),contactName.trim(),contactDetails.trim())
                    contactName="";contactDetails="";showErrors=false
                },enabled=!session.busy && contactName.isNotBlank() && contactDetails.isNotBlank() && contacts.size<25,
                    modifier=Modifier.fillMaxWidth().testTag("add_onboarding_contact")) {
                    Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));LocalizedText("Add another contact")
                }
            }
        } }
        Status(session)
        val finalProfilePage=page==pages.lastIndex
        val last=safeContact && finalProfilePage
        OnboardingNavigation(back={showErrors=false;if(page>0) page-- else session.back()},next={
            showErrors=true
            if(validation.isPageValid(pages[page])) {
                showErrors=false
                if(finalProfilePage) saveProfile() else page++
            }
        },last=last,nextTag=if(page==pages.lastIndex) "profile_submit" else "onboarding_next",enabled=!session.busy,
            nextLabel=if(!safeContact && page==pages.lastIndex) "Next" else null)
    }
}
