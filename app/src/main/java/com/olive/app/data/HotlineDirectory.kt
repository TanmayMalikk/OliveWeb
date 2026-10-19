package com.olive.app.data

import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.util.Locale

// Provider facts live in a source-attributed asset, separate from localized interface copy.
data class HotlineResource(val id:String,val region:String,val city:String,val name:String,
    val phone:String,val website:String,val source:String,val phoneUri:String)

class HotlineDirectory private constructor(val checkedOn:String,val resources:List<HotlineResource>,
    private val cities:Map<String,String>,private val provinceAliases:Map<String,String>) {
    fun isCanada(address:DeliveryAddress):Boolean {
        val country=normalize(address.country)
        return country in setOf("canada","ca","can","كندا","加拿大") ||
            (country.isBlank() && inferRegion(address)!=null)
    }
    fun regionFor(address:DeliveryAddress):String? = if(isCanada(address)) inferRegion(address) else null
    private fun inferRegion(address:DeliveryAddress):String? {
        val province=normalize(address.province).removePrefix("ca ")
        provinceAliases[province]?.let { return it }
        CanadianRegion.entries.firstOrNull { region -> region.aliases.any { normalize(it)==province } }?.let { return it.code }
        val postal=address.postalCode.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        if(postal.matches(Regex("[ABCEGHJ-NPRSTVXY][0-9][A-Z][0-9][A-Z][0-9]"))) {
            if(postal.startsWith("X")) return if(postal.take(3) in setOf("X0A","X0B","X0C")) "NU" else "NT"
            mapOf('A' to "NL",'B' to "NS",'C' to "PE",'E' to "NB",'G' to "QC",'H' to "QC",'J' to "QC",
                'K' to "ON",'L' to "ON",'M' to "ON",'N' to "ON",'P' to "ON",'R' to "MB",'S' to "SK",
                'T' to "AB",'V' to "BC",'Y' to "YT")[postal.first()]?.let { return it }
        }
        return cities[normalize(address.city)]
    }
    fun matching(region:String,city:String,query:String=""):List<HotlineResource> {
        val normalized=normalize(query)
        val digits=query.filter(Char::isDigit)
        return resources.filter { item ->
            (item.region=="CA" || item.region==region) &&
                (item.city.isBlank() || normalize(item.city)==normalize(city)) &&
                (normalized.isBlank() || normalized.split(' ').all { it in normalize("${item.name} ${item.city} ${item.phone}") } ||
                    (digits.isNotEmpty() && query.all { it.isDigit() || it in " +-()" } && digits in item.phone.filter(Char::isDigit)))
        }.sortedWith(compareBy<HotlineResource> { if(it.city.isNotBlank()) 0 else if(it.region!="CA") 1 else 2 }.thenBy { it.name })
    }
    companion object {
        fun normalize(value:String)=Normalizer.normalize(value,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
        fun parse(text:String,cityText:String):HotlineDirectory {
            val root=JSONObject(text)
            val array=root.getJSONArray("resources")
            val items=(0 until array.length()).map { index ->
                val row=array.getJSONObject(index)
                val website=row.getString("website");val source=row.getString("source")
                require(listOf(website,source).all { URI(it).let { uri -> uri.scheme=="https" && !uri.host.isNullOrBlank() } })
                val phone=row.getString("phone")
                require(phone.matches(Regex("[0-9+() -]+")))
                val phoneUri=row.optString("phoneUri","tel:"+phone.filter { it.isDigit() || it=='+' })
                require(phoneUri.startsWith("tel:") && phoneUri.substringAfter("tel:").substringBefore('?').filter(Char::isDigit)==phone.filter(Char::isDigit))
                val region=row.getString("region")
                require(region=="CA" || CanadianRegion.entries.any { it.code==region })
                HotlineResource(row.getString("id"),region,row.optString("city"),row.getString("name"),phone,website,source,phoneUri)
            }
            require(items.map { it.id }.distinct().size==items.size)
            val cityRoot=JSONObject(cityText)
            val cities=cityRoot.keys().asSequence().associate { normalize(it) to cityRoot.getString(it) }
            val aliasRoot=root.optJSONObject("provinceAliases") ?: JSONObject()
            val aliases=aliasRoot.keys().asSequence().associate { normalize(it) to aliasRoot.getString(it) }
            require(aliases.values.all { code -> CanadianRegion.entries.any { it.code==code } })
            return HotlineDirectory(root.getString("checkedOn"),items,cities,aliases)
        }
    }
}

// Place-name aliases recognize saved addresses; translated display names are Android resources.
enum class CanadianRegion(val code:String,vararg val aliases:String) {
    AB("AB","AB","Alberta"),BC("BC","BC","British Columbia","Colombie-Britannique"),
    MB("MB","MB","Manitoba"),NB("NB","NB","New Brunswick","Nouveau-Brunswick"),
    NL("NL","NL","Newfoundland and Labrador","Terre-Neuve-et-Labrador"),
    NS("NS","NS","Nova Scotia","Nouvelle-Écosse"),NT("NT","NT","Northwest Territories","Territoires du Nord-Ouest"),
    NU("NU","NU","Nunavut"),ON("ON","ON","Ontario"),PE("PE","PE","PEI","Prince Edward Island","Île-du-Prince-Édouard"),
    QC("QC","QC","Quebec","Québec"),SK("SK","SK","Saskatchewan"),YT("YT","YT","Yukon")
}

// Dial only the published number, never tracking parameters or embedded dial commands.
internal fun hotlineDialNumber(value:String):String? {
    if(!value.startsWith("tel:")) return null
    return value.removePrefix("tel:").substringBefore('?').takeIf { it.matches(Regex("[+]?[0-9]+")) }
}
