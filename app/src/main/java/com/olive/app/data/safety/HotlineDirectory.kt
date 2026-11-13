// Safe-app users and support staff: lists support phone numbers for users.
// Suppressing warning SameParameterValue since Hotlinedirectory.checkeon is always a DATASET_DATE which is dynamic anyways
// maybe I am too python-brained to understand why having a val that is always assigned the 'same' thing is a bad idea.
// constants won't work since these aren't known at compile time.
@file:Suppress("SameParameterValue")

package com.olive.app.data.safety

import com.olive.app.data.account.DeliveryAddress
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject


enum class HotlineResourceKind { Hotline, Shelter }

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
)

/** Searchable view of Lars's province-specific hotline and shelter datasets. */
class HotlineDirectory private constructor(
    val checkedOn: String,
    val resources: List<HotlineResource>,
    private val cities: Map<String, String>,
) {
    // Uses the Canadian hotline list when the selected country is Canada.
    fun isCanada(address: DeliveryAddress): Boolean {
        val country = normalize(address.country)
        return country in setOf("canada", "ca", "can", "كندا", "加拿大") ||
                (country.isBlank() && inferRegion(address) != null)
    }

    // Normalizes the province or territory name before looking up local hotlines.
    fun regionFor(address: DeliveryAddress): String? =
        if (isCanada(address)) inferRegion(address) else null

    // Finds the province or territory that matches the saved region text.
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

    // Checks whether a hotline serves the selected province or territory.
    fun matching(region: String, city: String, query: String = ""): List<HotlineResource> {
        val normalizedQuery = normalize(query)
        val normalizedCity = normalize(city)
        val digits = query.filter(Char::isDigit)
        val restrictToCity = normalizedCity.isNotBlank() && resources.any {
            it.region == region && normalize(it.city) == normalizedCity
        }
        return resources.filter { item ->
            (region == "CA" || item.region == region) &&
                    (!restrictToCity || normalize(item.city) == normalizedCity) &&
                    (normalizedQuery.isBlank() || normalizedQuery.split(' ').all {
                        it in normalize("${item.name} ${item.description} ${item.city} ${item.phone} ${item.kind.name}")
                    } || (digits.isNotEmpty() && query.all { it.isDigit() || it in " +-()" } &&
                            digits in item.phone.filter(Char::isDigit)))
        }
            .sortedWith(compareBy<HotlineResource> { if (normalize(it.city) == normalizedCity) 0 else 1 }
                .thenBy { it.kind }.thenBy { it.name })
    }

    companion object {
        private const val DATASET_DATE = "2026-10-15"

        // Formats a hotline phone number for display.
        fun normalize(value: String) =
            Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        // Reads province files from saved text.
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
                            else -> error("Unsupported hotline group: $group")
                        }
                        val entries = groups.getJSONObject(group)
                        entries.keys().asSequence().toList().sorted().map { name ->
                            val row = entries.getJSONObject(name)
                            val phone = row.getString("Phone")
                            require(phone.matches(Regex("[0-9+() -]+")))
                            val website = secureWebsite(row.getString("url"))
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
                                source = website,
                                phoneUri = "tel:" + phone.filter { it.isDigit() || it == '+' },
                            )
                        }
                    }
                }
            }
            require(resources.map { it.id }.distinct().size == resources.size)
            return HotlineDirectory(DATASET_DATE, resources, cities)
        }

        // Adds HTTPS to a hotline website when its saved address has no scheme.
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

        // Turns a region name into the stable key used by the hotline list.
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

// Gives the safety plan the hotline dial number it needs.
internal fun hotlineDialNumber(value: String): String? {
    if (!value.startsWith("tel:")) return null
    return value.removePrefix("tel:").substringBefore('?')
        .takeIf { it.matches(Regex("[+]?[0-9]+")) }
}
