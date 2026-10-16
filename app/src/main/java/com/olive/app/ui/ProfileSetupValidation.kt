package com.olive.app.ui

import com.olive.app.data.DeliveryAddress
import com.olive.app.security.InputPolicy

// Step validation is shared by progress calculation and the Next/Save actions.
internal class ProfileSetupValidation(
    private val safeContact:Boolean,
    private val fullName:String,
    private val dob:String,
    private val language:String,
    private val address:DeliveryAddress,
    private val trigger:String,
    private val contactName:String,
    private val contactDetails:String,
) {
    val personalValid get()=fullName.isNotBlank() && InputPolicy.validDob(dob) && language.isNotBlank()
    val supportValid get()=contactName.isBlank()==contactDetails.isBlank()
    val requiredFields get()=listOf(fullName.isNotBlank(),InputPolicy.validDob(dob),language.isNotBlank(),address.locationValid)+
        if(safeContact) emptyList() else listOf(InputPolicy.validTrigger(trigger))
    val pageCompletion get()=if(safeContact) listOf(personalValid,address.locationValid) else
        listOf(personalValid,address.locationValid,InputPolicy.validTrigger(trigger) && supportValid)

    fun isPageValid(page:String)=when(page) {
        "Personal details" -> personalValid
        "Location" -> address.locationValid
        "Safe entry & cover" -> InputPolicy.validTrigger(trigger) && supportValid
        else -> true
    }
}
