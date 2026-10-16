package com.olive.app.ui


import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.olive.app.data.*

// The profile screen displays saved information without mixing it with form editing.
@Composable internal fun SafeProfileScreen(session:SessionViewModel,profile:UserProfile) {
    FormColumn {
            SafeBack(session)
            Heading("My profile", "Your saved account information.")
            val rows=buildList {
                add("Account type" to profile.role.title);add("Username" to profile.username);add("Full name" to profile.fullName)
                if(profile.role==AccountRole.USER) {
                    add("People experiencing abuse" to profile.affectedNames);add("Abuser's name" to profile.abuserName);add("Situation" to profile.situation)
                }
                add("Date of birth" to profile.dob);add("Preferred language" to profile.language);add("Region / city / state" to profile.region)
                if(profile.role==AccountRole.USER) {
                    add("Cover app" to profile.coverType.title)
                    add("Delivery address" to listOf(profile.address.line1,profile.address.line2,profile.address.city,profile.address.province,profile.address.postalCode,profile.address.country).filter { it.isNotBlank() }.joinToString(", "))
                }
            }
            rows
                .forEach { (label,value) ->
                    LocalizedText(label,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                    if(value.isBlank()) LocalizedText("Not provided")
                    else if(label=="Preferred language") LocalizedText(value)
                    else Text(value)
                }
        }
}
