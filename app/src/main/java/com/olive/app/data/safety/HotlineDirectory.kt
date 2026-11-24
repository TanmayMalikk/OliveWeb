// Safe-app users and support staff: lists support phone numbers for users.
// Suppressing warning SameParameterValue since Hotlinedirectory.checkeon is always a DATASET_DATE which is dynamic anyways
// maybe I am too python-brained to understand why having a val that is always assigned the 'same' thing is a bad idea.
// constants won't work since these aren't known at compile time.
@file:Suppress("SameParameterValue")

package com.olive.app.data.safety

import android.content.Context
import android.content.SharedPreferences

import com.olive.app.data.account.DeliveryAddress
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject


enum class HotlineResourceKind {
    Hotline,
    Shelter,
    LegalAid,
    Counseling,
    CrisisService,
}

data class HotlineResource(
    val id: String,
    val region: String,
    val city: String,
    val name: String,
    val description: String,
    val kind: HotlineResourceKind,
    val phone: String,
    val website: String,
    val source: String,
    val phoneUri: String,
    val hours: String = "",
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val textNumber: String = "",
) {
    /** Whether the service operates 24/7. */
    val is247: Boolean
        get() = hours.contains("24/7", ignoreCase = true) ||
            hours.contains("24 hours", ignoreCase = true)

    /** Whether the service has a validated public street address and map coordinates. */
    val hasMapLocation: Boolean
        get() = address.isNotBlank() &&
            latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** Searchable view of Lars's province-specific hotline and shelter datasets. */
class HotlineDirectory private constructor(
    val checkedOn: String,
    val resources: List<HotlineResource>,
    private val cities: Map<String, String>,
) {
    /**
     * Uses the Canadian hotline list when the selected country is Canada.
     *
     * @param address Address supplied to [isCanada].
     * @return `true` if is canada, or `false` otherwise.
     */
    fun isCanada(address: DeliveryAddress): Boolean {
        val country = normalize(address.country)
        return country in setOf("canada", "ca", "can", "كندا", "加拿大") ||
                (country.isBlank() && inferRegion(address) != null)
    }

    /**
     * Normalizes the province or territory name before looking up local hotlines.
     *
     * @param address Address supplied to [regionFor].
     * @return The resulting region for string, or `null` when unavailable.
     */
    fun regionFor(address: DeliveryAddress): String? =
        if (isCanada(address)) inferRegion(address) else null

    /**
     * Finds the province or territory that matches the saved region text.
     *
     * @param address Address supplied to [inferRegion].
     * @return The resulting infer region string, or `null` when unavailable.
     */
    private fun inferRegion(address: DeliveryAddress): String? {
        val province = normalize(address.province).removePrefix("ca ")
        CanadianRegion.entries.firstOrNull { region -> region.aliases.any { normalize(it) == province } }
            ?.let { return it.code }
        val postal = address.postalCode.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        if (postal.matches(Regex("[ABCEGHJ-NPRSTVXY][0-9][A-Z][0-9][A-Z][0-9]"))) {
            if (postal.startsWith("X")) return if (postal.take(3) in setOf(
                    "X0A",
                    "X0B",
                    "X0C"
                )
            ) "NU" else "NT"
            mapOf(
                'A' to "NL",
                'B' to "NS",
                'C' to "PE",
                'E' to "NB",
                'G' to "QC",
                'H' to "QC",
                'J' to "QC",
                'K' to "ON",
                'L' to "ON",
                'M' to "ON",
                'N' to "ON",
                'P' to "ON",
                'R' to "MB",
                'S' to "SK",
                'T' to "AB",
                'V' to "BC",
                'Y' to "YT"
            )[postal.first()]?.let { return it }
        }
        return cities[normalize(address.city)]
    }

    /**
     * Checks whether a hotline serves the selected province or territory and optional filters.
     *
     * @param region Region supplied to [matching].
     * @param city City supplied to [matching].
     * @param query Search query string entered by the user.
     * @param kind Optional category filter.
     * @param savedIds Optional set of saved resource IDs to filter by.
     * @param onlyAlwaysOpen Whether to restrict results to 24/7 services.
     * @return List of resulting matching items.
     */
    fun matching(
        region: String,
        city: String,
        query: String = "",
        kind: HotlineResourceKind? = null,
        savedIds: Set<String>? = null,
        onlyAlwaysOpen: Boolean = false,
    ): List<HotlineResource> {
        val normalizedQuery = normalize(query)
        val normalizedCity = normalize(city)
        val digits = query.filter(Char::isDigit)
        val restrictToCity = region != "CA" && normalizedCity.isNotBlank() && resources.any {
            it.region == region && normalize(it.city) == normalizedCity
        }
        return resources.filter { item ->
            (region == "CA" || item.region == region) &&
                (!restrictToCity || normalize(item.city) == normalizedCity) &&
                (kind == null || item.kind == kind) &&
                (savedIds == null || item.id in savedIds) &&
                (!onlyAlwaysOpen || item.is247) &&
                (normalizedQuery.isBlank() || normalizedQuery.split(' ').all { token ->
                    token in normalize(
                        "${item.name} ${item.description} ${item.city} ${item.address} ${item.phone} ${item.textNumber} ${kindSearchTerms(item.kind)}"
                    )
                } || (digits.isNotEmpty() && query.all { it.isDigit() || it in " +-()" } &&
                    (digits in item.phone.filter(Char::isDigit) || digits in item.textNumber.filter(Char::isDigit))))
        }
            .sortedWith(compareBy<HotlineResource> { if (normalize(it.city) == normalizedCity) 0 else 1 }
                .thenBy { it.kind }.thenBy { it.name })
    }

    /**
     * Returns directory resources that have a validated public address and coordinates for map display.
     *
     * @param region Optional province or territory code filter.
     * @param kind Optional service category filter.
     * @param savedIds Optional saved resource ID filter.
     * @return List of mappable directory resources.
     */
    fun mapLocations(
        region: String? = null,
        kind: HotlineResourceKind? = null,
        savedIds: Set<String>? = null,
    ): List<HotlineResource> = resources.filter {
        it.hasMapLocation &&
            (region == null || it.region == region) &&
            (kind == null || it.kind == kind) &&
            (savedIds == null || it.id in savedIds)
    }

    companion object {
        private const val DATASET_DATE = "2026-11-22"

        /** Returns additional searchable keywords associated with a hotline resource kind. */
        private fun kindSearchTerms(kind: HotlineResourceKind): String = when (kind) {
            HotlineResourceKind.Hotline -> "hotline helpline phone"
            HotlineResourceKind.Shelter -> "shelter housing transition house"
            HotlineResourceKind.LegalAid -> "legalaid legal aid lawyer court"
            HotlineResourceKind.Counseling -> "counseling counselling therapy clinic"
            HotlineResourceKind.CrisisService -> "crisisservice crisis service emergency"
        }

        /**
         * Formats a hotline phone number for display.
         *
         * @param value Input value to store, parse, or format.
         * @return The resulting normalize.
         */
        fun normalize(value: String) =
            Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        @Volatile
        private var cached: HotlineDirectory? = null

        /**
         * Reads province files from saved text.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @return The resulting [HotlineDirectory] for read bundled.
         */
        fun readBundled(context: Context): HotlineDirectory = cached ?: synchronized(this) {
            cached ?: parseProvinceFiles(
                context.assets.list("hotlines/provinces").orEmpty().associate { name ->
                    name.removePrefix("Resources").removeSuffix(".json") to
                        context.assets.open("hotlines/provinces/$name").bufferedReader().use { it.readText() }
                }
            ).also { cached = it }
        }

        /**
         * Parses province files.
         *
         * @param files Key-value mapping of files entries.
         * @return The resulting [HotlineDirectory] for parse province files.
         */
        fun parseProvinceFiles(files: Map<String, String>): HotlineDirectory {
            val expected = CanadianRegion.entries.map { it.code }.toSet()
            require(files.keys == expected) { "A hotline dataset is required for every Canadian province and territory" }
            val cities = mutableMapOf<String, String>()
            val resources = files.toSortedMap().flatMap { (region, text) ->
                val root = JSONObject(text)
                root.keys().asSequence().toList().sorted().flatMap { city ->
                    cities[normalize(city)] = region
                    val groups = root.getJSONObject(city)
                    groups.keys().asSequence().toList().sorted().flatMap { group ->
                        val kind = when (group) {
                            "Hotlines" -> HotlineResourceKind.Hotline
                            "Shelters" -> HotlineResourceKind.Shelter
                            "LegalAid" -> HotlineResourceKind.LegalAid
                            "Counseling" -> HotlineResourceKind.Counseling
                            "CrisisServices" -> HotlineResourceKind.CrisisService
                            else -> error("Unsupported hotline group: $group")
                        }
                        val entries = groups.getJSONObject(group)
                        entries.keys().asSequence().toList().sorted().map { name ->
                            val row = entries.getJSONObject(name)
                            val phone = row.getString("Phone")
                            require(phone.matches(Regex("[0-9+() -]+")))
                            val textNumber = row.optString("Text").trim()
                            if (textNumber.isNotBlank()) {
                                require(textNumber.matches(Regex("[0-9+() -]+")))
                            }
                            val hours = row.optString("hours").trim()
                            require(hours.isNotBlank()) { "Hours are required for $name" }
                            val address = row.optString("address").trim()
                            val hasLat = row.has("lat") && !row.isNull("lat")
                            val hasLng = row.has("lng") && !row.isNull("lng")
                            require(hasLat == hasLng) { "Coordinates must include both lat and lng for $name" }
                            val latitude = if (hasLat) row.getDouble("lat") else null
                            val longitude = if (hasLng) row.getDouble("lng") else null
                            if (latitude != null && longitude != null) {
                                require(
                                    latitude.isFinite() && longitude.isFinite() &&
                                        latitude in -90.0..90.0 && longitude in -180.0..180.0
                                ) { "Coordinates out of bounds for $name" }
                            }
                            if (address.isNotBlank()) {
                                require(latitude != null && longitude != null) {
                                    "Mapped address must include lat and lng for $name"
                                }
                            }
                            val website = secureWebsite(row.getString("url"))
                            val source = row.optString("source").trim().takeIf { it.isNotEmpty() }
                                ?.let(::secureWebsite) ?: website
                            HotlineResource(
                                id = listOf(
                                    region,
                                    city,
                                    group,
                                    name
                                ).joinToString("-") { slug(it) },
                                region = region,
                                city = city,
                                name = name,
                                description = row.getString("Desc"),
                                kind = kind,
                                phone = phone,
                                website = website,
                                source = source,
                                phoneUri = "tel:" + phone.filter { it.isDigit() || it == '+' },
                                hours = hours,
                                address = address,
                                latitude = latitude,
                                longitude = longitude,
                                textNumber = textNumber,
                            )
                        }
                    }
                }
            }
            require(resources.map { it.id }.distinct().size == resources.size)
            return HotlineDirectory(DATASET_DATE, resources, cities)
        }

        /**
         * Adds HTTPS to a hotline website when its saved address has no scheme.
         *
         * @param value Input value to store, parse, or format.
         * @return The resulting secure website string.
         */
        private fun secureWebsite(value: String): String {
            val normalized = when {
                value.startsWith("https://", true) -> value
                value.startsWith("http://", true) -> "https://" + value.substringAfter("://")
                !value.contains("://") -> "https://$value"
                else -> value
            }
            val uri = URI(normalized)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank())
            return normalized
        }

        /**
         * Turns a region name into the stable key used by the hotline list.
         *
         * @param value Input value to store, parse, or format.
         * @return The resulting slug.
         */
        private fun slug(value: String) = normalize(value).replace(' ', '-')
    }
}

// Place-name aliases recognize saved addresses; translated display names are Android resources.
enum class CanadianRegion(val code: String, vararg val aliases: String) {
    AB("AB", "AB", "Alberta", "ألبرتا", "艾伯塔省"),
    BC(
        "BC",
        "BC",
        "British Columbia",
        "Colombie-Britannique",
        "Columbia Británica",
        "كولومبيا البريطانية",
        "不列颠哥伦比亚省"
    ),
    MB("MB", "MB", "Manitoba", "مانيتوبا", "马尼托巴省"),
    NB(
        "NB",
        "NB",
        "New Brunswick",
        "Nouveau-Brunswick",
        "Nuevo Brunswick",
        "نيو برونزويك",
        "新不伦瑞克省"
    ),
    NL(
        "NL",
        "NL",
        "Newfoundland and Labrador",
        "Terre-Neuve-et-Labrador",
        "Terranova y Labrador",
        "نيوفاوندلاند ولابرادور",
        "纽芬兰与拉布拉多省"
    ),
    NS("NS", "NS", "Nova Scotia", "Nouvelle-Écosse", "Nueva Escocia", "نوفا سكوشا", "新斯科舍省"),
    NT(
        "NT",
        "NT",
        "Northwest Territories",
        "Territoires du Nord-Ouest",
        "Territorios del Noroeste",
        "الأقاليم الشمالية الغربية",
        "西北地区"
    ),
    NU("NU", "NU", "Nunavut", "نونافوت", "努纳武特地区"),
    ON("ON", "ON", "Ontario", "أونتاريو", "安大略省"),
    PE(
        "PE",
        "PE",
        "PEI",
        "Prince Edward Island",
        "Île-du-Prince-Édouard",
        "Isla del Príncipe Eduardo",
        "جزيرة الأمير إدوارد",
        "爱德华王子岛省"
    ),
    QC("QC", "QC", "Quebec", "Québec", "كيبيك", "魁北克省"),
    SK("SK", "SK", "Saskatchewan", "ساسكاتشوان", "萨斯喀彻温省"),
    YT("YT", "YT", "Yukon", "Yukón", "يوكون", "育空地区")
}

/**
 * Gives the safety plan the hotline dial number it needs.
 *
 * @param value Input value to store, parse, or format.
 * @return The resulting hotline dial number string, or `null` when unavailable.
 */
internal fun hotlineDialNumber(value: String): String? {
    if (!value.startsWith("tel:")) return null
    return value.removePrefix("tel:").substringBefore('?')
        .takeIf { it.matches(Regex("[+]?[0-9]+")) }
}

/**
 * Account-scoped local store for hearted/saved hotline directory resources.
 * Stores only salted SHA-256 digests so no plaintext service names or UIDs exist on disk.
 */
internal object SavedHotlineStore {
    private const val PREF_NAME = "olive_saved_hotlines"
    private val versionCounter = AtomicLong(0L)
    private val updatesMutable = MutableStateFlow(0L)
    val updates: StateFlow<Long> = updatesMutable.asStateFlow()

    /** Returns the private SharedPreferences file backing saved hotline bookmarks. */
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Returns the hashed preference key scoped to [uid]. */
    private fun accountKey(uid: String): String = "acct_${sha256Hex("acct:$uid")}"

    /** Computes the account-scoped salted token stored for a saved hotline resource. */
    private fun resourceToken(uid: String, resourceId: String): String =
        sha256Hex("res:$uid:$resourceId")

    /** Reads the set of stored hotline bookmark tokens for [uid]. */
    private fun readTokens(context: Context, uid: String?): Set<String> {
        if (uid.isNullOrBlank()) return emptySet()
        return prefs(context).getStringSet(accountKey(uid), emptySet()).orEmpty().toSet()
    }

    /**
     * Resolves the set of saved resource IDs for [uid] from the current catalog.
     */
    fun savedIds(context: Context, uid: String?, resources: List<HotlineResource>): Set<String> {
        if (uid.isNullOrBlank() || resources.isEmpty()) return emptySet()
        val tokens = readTokens(context, uid)
        if (tokens.isEmpty()) return emptySet()
        return resources.mapNotNull { resource ->
            resource.id.takeIf { resourceToken(uid, it) in tokens }
        }.toSet()
    }

    /**
     * Returns whether [resourceId] is currently saved for [uid].
     */
    fun isSaved(context: Context, uid: String?, resourceId: String): Boolean {
        if (uid.isNullOrBlank() || resourceId.isBlank()) return false
        return resourceToken(uid, resourceId) in readTokens(context, uid)
    }

    /**
     * Explicitly sets whether [resourceId] is saved for [uid].
     */
    fun setSaved(context: Context, uid: String, resourceId: String, saved: Boolean) {
        if (uid.isBlank() || resourceId.isBlank()) return
        val key = accountKey(uid)
        val token = resourceToken(uid, resourceId)
        val existing = readTokens(context, uid).toMutableSet()
        val changed = if (saved) existing.add(token) else existing.remove(token)
        if (!changed) return
        val editor = prefs(context).edit()
        if (existing.isEmpty()) editor.remove(key) else editor.putStringSet(key, existing)
        editor.apply()
        updatesMutable.value = versionCounter.incrementAndGet()
    }

    /**
     * Toggles whether [resourceId] is saved for [uid] and returns the updated state.
     */
    fun toggle(context: Context, uid: String, resourceId: String): Boolean {
        val next = !isSaved(context, uid, resourceId)
        setSaved(context, uid, resourceId, next)
        return next
    }

    /**
     * Removes saved bookmarks for [uid], e.g. on explicit account removal.
     */
    fun clearAccount(context: Context, uid: String) {
        if (uid.isBlank()) return
        val key = accountKey(uid)
        val store = prefs(context)
        if (store.contains(key)) {
            store.edit().remove(key).apply()
            updatesMutable.value = versionCounter.incrementAndGet()
        }
    }

    /** Computes a lowercase hexadecimal SHA-256 digest of [value]. */
    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        val hex = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xFF
            chars[index * 2] = hex[unsigned ushr 4]
            chars[index * 2 + 1] = hex[unsigned and 0x0F]
        }
        return String(chars)
    }
}
