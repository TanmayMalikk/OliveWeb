// All app roles: chooses translated text for Android screens.
package com.olive.app.ui.shared

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.core.os.LocaleListCompat
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import com.olive.app.R
import java.util.Locale

// Capture native resources during composition so event callbacks can format translated messages too.
internal class ResourceText(private val resolve: (Int, Array<out Any?>) -> String) {
    constructor(resources: Resources) : this({ id, arguments ->
        if (arguments.isEmpty()) resources.getString(id) else resources.getString(id, *arguments)
    })

    operator fun invoke(@StringRes id: Int, vararg arguments: Any?): String =
        resolve(id, arguments)
}

@Composable
internal fun rememberResourceText(): ResourceText {
    val resources = LocalResources.current
    return remember(resources) { ResourceText(resources) }
}

// Pure text helpers carry a message ID; the UI resolves it using Android's current locale.
internal class ResourceFailure(@StringRes val textId: Int, vararg val arguments: Any?) :
    IllegalArgumentException()

internal inline fun requireText(condition: Boolean, message: () -> ResourceFailure) {
    if (!condition) throw message()
}

internal fun ResourceText.failure(error: Throwable, @StringRes fallback: Int): String =
    if (error is ResourceFailure) this(error.textId, *error.arguments) else this(fallback)

internal fun AccountRole.localizedTitle(text: ResourceText) = text(
    when (this) {
        AccountRole.USER -> R.string.user
        AccountRole.SAFE_CONTACT -> R.string.safe_contact
        AccountRole.STAFF -> R.string.staff
        AccountRole.ADMIN -> R.string.admin
    }
)

internal fun AccountRole.localizedDescription(text: ResourceText) = text(
    when (this) {
        AccountRole.USER -> R.string.the_person_using_the_private_cover_and_safe_app
        AccountRole.SAFE_CONTACT -> R.string.a_trusted_person_helping_someone
        AccountRole.STAFF -> R.string.an_organization_staff_member
        AccountRole.ADMIN -> R.string.an_organization_leader_who_oversees_staff
    }
)

internal fun CoverType.localizedTitle(text: ResourceText) = text(
    when (this) {
        CoverType.FOOD -> R.string.ui_olive_8f62f4
        CoverType.NEWS -> R.string.ui_daily_digest
        CoverType.FITNESS -> R.string.fitness_tracker
        CoverType.GAME -> R.string.ui_game
    }
)

internal object AppLocaleManager {

    val supportedLanguages = listOf("English", "French", "Spanish", "Arabic", "Chinese")

    fun getLanguageTag(language: String): String {
        return when (language.trim().lowercase()) {
            "french", "fr", "français" -> "fr"
            "spanish", "es", "español" -> "es"
            "arabic", "ar", "العربية" -> "ar"
            "chinese", "zh", "zh-cn", "zh-rcn", "中文" -> "zh-rCN"
            else -> "en"
        }
    }

    fun getDisplayLanguage(language: String): String {
        return when (getLanguageTag(language)) {
            "fr" -> "Français"
            "es" -> "Español"
            "ar" -> "العربية"
            "zh-rCN" -> "中文"
            else -> "English"
        }
    }

    // Works out system default language tag.
    fun resolveSystemDefaultLanguageTag(): String {
        val systemLocale = Locale.getDefault()
        val lang = systemLocale.language.lowercase()
        return when (lang) {
            "fr" -> "fr"
            "es" -> "es"
            "ar" -> "ar"
            "zh" -> "zh-rCN"
            "en" -> "en"
            else -> "en"
        }
    }

    // Works out system default language name.
    fun resolveSystemDefaultLanguageName(): String {
        return when (resolveSystemDefaultLanguageTag()) {
            "fr" -> "French"
            "es" -> "Spanish"
            "ar" -> "Arabic"
            "zh-rCN" -> "Chinese"
            else -> "English"
        }
    }

    // Saves the selected language and asks Android to recreate UI resources in that locale.
    fun applyLocale(language: String) {
        val tag = getLanguageTag(language)
        val locales = LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun initSystemDefaultLocale() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            val defaultTag = resolveSystemDefaultLanguageTag()
            applyLocale(defaultTag)
        }
    }
}
