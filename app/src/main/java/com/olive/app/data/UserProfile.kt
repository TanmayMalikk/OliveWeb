package com.olive.app.data

import org.json.JSONArray
import org.json.JSONObject

// A trusted person can optionally be marked as someone who can provide a safe place to stay.
data class SafeContact(val id:String,val name:String,val details:String,val safeToStay:Boolean=false)
// The account role decides which onboarding flow and app shell this person receives.
enum class AccountRole(val title: String, val description: String) {
    USER("User", "The person using the private cover and safe app"),
    SAFE_CONTACT("Safe Contact", "A trusted person helping someone"),
    STAFF("Staff", "An organization staff member"),
    ADMIN("Admin", "An organization leader who oversees staff");

    companion object { fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: USER }
}
// Pick the visible cover here; add another option before wiring its screen and launcher icon.
enum class CoverType(val title: String) {
    FOOD("Olive"), NEWS("Daily Digest"), CALCULATOR("Better Calculator"), GAME("Game");
    companion object { fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: FOOD }
}
// This is the complete account profile; cover screens only get the small, normal-looking subset they need.
data class UserProfile(
    val username: String,
    val fullName: String,
    val affectedNames: String,
    val abuserName: String,
    val dob: String,
    val language: String,
    val situation: String,
    val region: String,
    val triggerHash: String,
    val contacts: List<SafeContact> = emptyList(),
    val address: DeliveryAddress = DeliveryAddress(),
    val coverType: CoverType = CoverType.FOOD,
    val role: AccountRole = AccountRole.USER,
    val email: String = "",
    val phone: String = "",
) {
    fun toMap(): Map<String, Any> = mapOf(
        "address" to address.toMap(),
        "username" to username, "fullName" to fullName, "affectedNames" to affectedNames, "abuserName" to abuserName,
        "dob" to dob, "language" to language, "situation" to situation, "region" to region,
        "triggerHash" to triggerHash, "contacts" to contacts.map {
            mapOf("id" to it.id,"name" to it.name,"details" to it.details,"safeToStay" to it.safeToStay)
        },
        "coverType" to coverType.name, "role" to role.name, "email" to email, "phone" to phone,
    )
    fun toJson() = JSONObject(toMap())
    companion object {
        fun fromJson(j: JSONObject): UserProfile {
            val contacts = j.optJSONArray("contacts") ?: JSONArray()
            return UserProfile(j.optString("username"), j.optString("fullName"), j.optString("affectedNames"),j.optString("abuserName"),
                j.optString("dob"), j.optString("language"), j.optString("situation"), j.optString("region"),
                j.optString("triggerHash"), (0 until contacts.length()).map {
                    val c = contacts.getJSONObject(it); SafeContact(c.getString("id"),c.getString("name"),c.getString("details"),c.optBoolean("safeToStay"))
                }, DeliveryAddress.fromJson(j.optJSONObject("address")), CoverType.fromStored(j.optString("coverType")),
                AccountRole.fromStored(j.optString("role")),j.optString("email"),j.optString("phone"))
        }
    }
}

// Firebase uses the uid behind the scenes while the human sticks with their username.
data class AccountIdentity(val uid: String, val username: String)
// Failed attempts remember when something happened, never which PIN somebody tried.
data class FailedAttempt(val id: String, val timestamp: Long)

// The one checkout detail Olive is allowed to keep because the food cover needs it later.
data class DeliveryAddress(
    val line1: String = "", val line2: String = "", val city: String = "",
    val province: String = "", val postalCode: String = "", val country: String = "Canada",
) {
    val locationValid get() = city.isNotBlank() && listOf(line1,line2,city,province,postalCode,country).all { it.length <= 160 }
    val valid get() = line1.isNotBlank() && city.isNotBlank() && province.isNotBlank() && postalCode.isNotBlank() && country.isNotBlank()
        && listOf(line1,line2,city,province,postalCode,country).all { it.length <= 160 }
    val label get() = if(valid) "$line1, $city" else ""
    val generalLocation get() = listOf(city,province,country).filter { it.isNotBlank() }.joinToString(", ")
    fun toMap() = mapOf("line1" to line1,"line2" to line2,"city" to city,"province" to province,"postalCode" to postalCode,"country" to country)
    companion object {
        fun fromJson(j: JSONObject?) = if(j==null) DeliveryAddress() else DeliveryAddress(j.optString("line1"),j.optString("line2"),j.optString("city"),j.optString("province"),j.optString("postalCode"),j.optString("country","Canada"))
    }
}
