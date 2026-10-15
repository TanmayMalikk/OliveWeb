package com.olive.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

val LocalOliveLanguage = staticCompositionLocalOf { getSystemDefaultLanguage() }

/**
 * Detects the OS system language and matches it against supported app languages.
 * Defaults to "English" if the OS system language is not supported.
 */
fun getSystemDefaultLanguage(): String {
    val systemLocale = Locale.getDefault()
    val lang = systemLocale.language.lowercase()
    return when {
        lang == "fr" -> "French"
        lang == "es" -> "Spanish"
        lang == "ar" -> "Arabic"
        lang == "tl" || lang == "fil" -> "Tagalog"
        lang == "zh" -> "Chinese"
        lang == "en" -> "English"
        else -> "English"
    }
}

/**
 * Resolves a language display name or code (e.g. "French", "fr", "Español", "es") to its BCP 47 language tag.
 */
fun getLanguageTag(language: String): String {
    return when (language.trim().lowercase()) {
        "french", "fr", "français" -> "fr"
        "spanish", "es", "español" -> "es"
        "arabic", "ar", "العربية" -> "ar"
        "tagalog", "tl", "filipino" -> "tl"
        "chinese", "zh", "zh-cn", "中文" -> "zh"
        "cree", "cr" -> "cr"
        else -> "en"
    }
}

/**
 * Resolves a language tag or name to its human-readable display string.
 */
fun getDisplayLanguage(language: String): String {
    return when (getLanguageTag(language)) {
        "fr" -> "Français"
        "es" -> "Español"
        "ar" -> "العربية"
        "tl" -> "Tagalog"
        "zh" -> "中文"
        "cr" -> "Cree"
        else -> "English"
    }
}

/**
 * Converts any raw text key into a valid Android strings.xml resource key name.
 */
fun sanitizeKey(text: String): String {
    var k = text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    if (k.isEmpty()) k = "string_item"
    if (k.first().isDigit()) k = "str_$k"
    return k
}

// Text sizing/color: use this instead of plain Text when copy should translate; its optional fontSize, lineHeight, color, and maxLines control local overrides.
@Composable fun LocalizedText(text: String, modifier: Modifier=Modifier, color: Color=Color.Unspecified,
    fontSize: TextUnit=TextUnit.Unspecified, fontWeight: FontWeight?=null, fontFamily: FontFamily?=null,
    letterSpacing: TextUnit=TextUnit.Unspecified, textAlign: TextAlign?=null, lineHeight: TextUnit=TextUnit.Unspecified,
    overflow: TextOverflow=TextOverflow.Clip, maxLines: Int=Int.MAX_VALUE, style: TextStyle=LocalTextStyle.current) {
    val context = LocalContext.current
    MaterialText(translate(text, LocalOliveLanguage.current, context), modifier=modifier, color=color, fontSize=fontSize, fontWeight=fontWeight, fontFamily=fontFamily,
        letterSpacing=letterSpacing, textAlign=textAlign, lineHeight=lineHeight, overflow=overflow, maxLines=maxLines, style=style)
}

/**
 * Translates a text string dynamically using Android's XML string resources.
 */
fun translate(text: String, language: String, context: Context? = null): String {
    val langTag = getLanguageTag(language)
    if (langTag == "en") return text

    // 1. Dynamic regex pattern checks with Context resource lookup
    val dynamicPatterns = listOf(
        Regex("Incorrect PIN\\. ([0-9]+) attempts remaining\\.") to "incorrect_pin_attempts_remaining",
        Regex("PIN attempt history · ([0-9]+)") to "pin_attempt_history_count",
        Regex("Future feature ([0-9]+)") to "future_feature_count",
        Regex("([0-9]+) dishes") to "dishes_count",
        Regex("Add another · ([0-9]+) in cart") to "add_another_in_cart",
        Regex("Subtotal: (.+)") to "subtotal_amount",
        Regex("News near (.+)") to "news_near_location",
        Regex("No activity\\. Returning to your cover in ([0-9]+) seconds\\.") to "no_activity_returning_in_seconds"
    )

    for ((pattern, resName) in dynamicPatterns) {
        val match = pattern.matchEntire(text)
        if (match != null) {
            val arg = match.groupValues[1]
            val argInt = arg.toIntOrNull()
            val formattedRes = getResourceString(context, langTag, resName)
            if (formattedRes != null) {
                return if (argInt != null) String.format(Locale.forLanguageTag(langTag), formattedRes, argInt)
                       else String.format(Locale.forLanguageTag(langTag), formattedRes, arg)
            }
        }
    }

    // 2. Static key lookup from Android string resources
    val key = sanitizeKey(text)
    val resValue = getResourceString(context, langTag, key)
    if (resValue != null) {
        return resValue
    }

    // 3. Fallbacks for JVM unit tests (when Android Context is null)
    if (langTag == "fr") {
        frenchFallback[text]?.let { return it }
        val fallbackPatterns = listOf(
            Regex("Incorrect PIN\\. ([0-9]+) attempts remaining\\.") to "NIP incorrect. %s tentatives restantes.",
            Regex("PIN attempt history · ([0-9]+)") to "Historique des tentatives de NIP · %s",
            Regex("Future feature ([0-9]+)") to "Fonction future %s",
            Regex("([0-9]+) dishes") to "%s plats",
            Regex("Add another · ([0-9]+) in cart") to "Ajouter encore · %s dans le panier",
            Regex("Subtotal: (.+)") to "Sous-total : %s",
            Regex("News near (.+)") to "Actualités près de %s",
            Regex("No activity\\. Returning to your cover in ([0-9]+) seconds\\.") to "Aucune activité. Retour à l’application de couverture dans %s secondes."
        )
        for ((pattern, format) in fallbackPatterns) {
            pattern.matchEntire(text)?.let { return format.format(it.groupValues[1]) }
        }
    }

    return text
}

@SuppressLint("DiscouragedApi")
/* This warning is being surpressed since it is the best method to dynamically get the name of our string resources.
other methods require major refactoring and changing string referecnes across the board.
the second method requires keeping a second stringresourcemap that is maintenance heavy.
 */
private fun getResourceString(context: Context?, langTag: String, resName: String): String? {
    if (context != null) {
        val resId = context.resources.getIdentifier(resName, "string", context.packageName)
        if (resId != 0) {
            val locale = Locale.forLanguageTag(langTag)
            val config = Configuration(context.resources.configuration).apply {
                setLocale(locale)
            }
            val localizedContext = context.createConfigurationContext(config)
            return localizedContext.resources.getString(resId)
        }
    }
    return null
}

private val frenchFallback = """
Use shipping address|Utiliser l’adresse de livraison
Enter a complete shipping address.|Saisissez une adresse de livraison complète.
Recipient name is required.|Le nom du destinataire est obligatoire.
Name on card is required.|Le nom figurant sur la carte est obligatoire.
Settings|Paramètres
Payment declined|Paiement refusé
""".trimIndent().lines().filter { '|' in it }.associate { line ->
    val (english, french) = line.split('|', limit = 2)
    english to french
}
