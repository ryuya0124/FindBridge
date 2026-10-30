package dev.findbridge.android

import android.content.Context
import android.content.res.Configuration

internal enum class Appearance(val label: String) {
    SYSTEM("システムに合わせる"),
    LIGHT("ライト"),
    DARK("ダーク"),
}

internal enum class MapMode(val label: String) {
    STANDARD("標準"),
    AERIAL("航空写真"),
    HYBRID("航空写真＋地名"),
}

internal class AppearanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var appearance: Appearance
        get() = runCatching {
            Appearance.valueOf(prefs.getString("theme", "SYSTEM")!!)
        }
            .getOrDefault(Appearance.SYSTEM)
        set(value) {
            prefs.edit().putString("theme", value.name).apply()
        }

    var mapMode: MapMode
        get() = runCatching {
            MapMode.valueOf(prefs.getString("map", "STANDARD")!!)
        }
            .getOrDefault(MapMode.STANDARD)
        set(value) {
            prefs.edit().putString("map", value.name).apply()
        }

    var glassIntensity: Float
        get() = prefs.getFloat("glass", .65f)
        set(value) {
            prefs.edit().putFloat("glass", value.coerceIn(0f, 1f)).apply()
        }

    fun isDark(context: Context): Boolean =
        when (appearance) {
            Appearance.DARK -> true
            Appearance.LIGHT -> false
            Appearance.SYSTEM ->
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
        }
}
