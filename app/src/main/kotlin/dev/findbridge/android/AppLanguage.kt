package dev.findbridge.android

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

internal object AppLanguage {
    val choices =
        listOf(
            "" to "",
            "ja" to "日本語",
            "en" to "English",
            "zh-Hans" to "简体中文",
            "zh-Hant" to "繁體中文",
            "ko" to "한국어",
            "es" to "Español",
            "fr" to "Français",
            "de" to "Deutsch",
        )

    fun selected(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else
            context
                .getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getString("language", "")
                .orEmpty()

    fun label(context: Context): String =
        choices
            .firstOrNull { it.first == selected(context) }
            ?.second
            ?.ifBlank { context.getString(R.string.system) } ?: context.getString(R.string.system)

    fun select(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33)
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(tag)
        else {
            activity
                .getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .edit()
                .putString("language", tag)
                .apply()
            activity.recreate()
        }
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = selected(context)
        if (tag.isEmpty()) return context
        val configuration = Configuration(context.resources.configuration)
        val locale = Locale.forLanguageTag(tag)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    fun text(context: Context, id: Int) = wrap(context).getString(id)
}
