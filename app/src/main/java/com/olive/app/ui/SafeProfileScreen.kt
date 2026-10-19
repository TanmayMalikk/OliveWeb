package com.olive.app.ui

import androidx.compose.ui.res.stringResource
import com.olive.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.olive.app.data.*

// The profile screen displays saved information without mixing it with form editing.
@Composable internal fun SafeProfileScreen(session:SessionViewModel,profile:UserProfile) {
    val uiText=rememberResourceText()
    FormColumn {
            SafeBack(session)
            Heading(uiText(R.string.my_profile), uiText(R.string.your_saved_account_information))
            val rows=buildList {
                add(uiText(R.string.account_type) to profile.role.localizedTitle(uiText));add(uiText(R.string.username) to profile.username);add(uiText(R.string.full_name) to profile.fullName)
                if(profile.role==AccountRole.USER) {
                    add(uiText(R.string.people_experiencing_abuse) to profile.affectedNames);add(uiText(R.string.abuser_s_name) to profile.abuserName);add(uiText(R.string.situation) to profile.situation)
                }
                add(uiText(R.string.date_of_birth) to profile.dob);add(uiText(R.string.preferred_language) to profile.language);add(uiText(R.string.region_city_state) to profile.region)
                if(profile.role==AccountRole.USER) {
                    add(uiText(R.string.cover_app) to profile.coverType.localizedTitle(uiText))
                    add(uiText(R.string.delivery_address) to listOf(profile.address.line1,profile.address.line2,profile.address.city,profile.address.province,profile.address.postalCode,profile.address.country).filter { it.isNotBlank() }.joinToString(", "))
                }
            }
            rows
                .forEach { (label,value) ->
                    Text(label,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                    if(value.isBlank()) Text(stringResource(R.string.not_provided))
                    else if(label==uiText(R.string.preferred_language)) Text(value)
                    else Text(value)
                }
        }
}
