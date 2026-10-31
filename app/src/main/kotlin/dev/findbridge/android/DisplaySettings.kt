package dev.findbridge.android

import android.app.Activity
import android.widget.LinearLayout
import android.widget.SeekBar
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label
import dev.findbridge.android.Ui.materialSwitch
import dev.findbridge.android.Ui.space

/** Builds the native display panels separately from the map and device screen. */
internal class DisplaySettings(
    private val activity: Activity,
    private val settings: AppearanceSettings,
    private val scene: GlassScene,
    private val onMapChanged: () -> Unit,
    private val present: (LinearLayout) -> Unit,
    private val close: () -> Unit,
) {
    private fun page(title: Int) =
        activity.column().apply {
            setPadding(activity.dp(18), 0, activity.dp(18), activity.dp(12))
            addView(activity.label(activity.getString(title), 21, Ui.ink, true))
            activity.space(this, 12)
        }

    private fun section(content: LinearLayout, title: Int) {
        activity.space(content, 18)
        content.addView(
            activity.label(activity.getString(title), 13, Ui.muted, true).apply {
                setPadding(activity.dp(5), 0, 0, activity.dp(7))
            }
        )
    }

    fun show() {
        val content = page(R.string.map_view)
        content.addView(
            MapModePicker(activity, settings.mapMode) {
                settings.mapMode = it
                onMapChanged()
            }
        )
        activity.space(content, 18)
        content.addView(
            SettingsRow(
                activity,
                activity.getString(R.string.appearance),
                activity.getString(settings.appearance.titleRes),
            ) {
                showAppearance()
            }
        )
        section(content, R.string.glass_intensity)
        content.addView(
            SeekBar(activity).apply {
                contentDescription = activity.getString(R.string.glass_intensity)
                max = 100
                progress = (settings.glassIntensity * 100).toInt()
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            if (fromUser) {
                                settings.glassIntensity = progress / 100f
                                scene.intensity = settings.glassIntensity
                                scene.invalidate()
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    }
                )
            },
            LinearLayout.LayoutParams(-1, activity.dp(44)),
        )
        content.addView(
            activity.label(activity.getString(R.string.imagery_help), 11, Ui.muted).apply {
                setPadding(activity.dp(5), 0, activity.dp(5), 0)
            }
        )
        activity.space(content, 12)
        content.addView(
            activity.materialSwitch().apply {
                text = activity.getString(R.string.reduce_motion)
                textSize = 15f
                isChecked = settings.reduceMotion
                setOnCheckedChangeListener { _, checked -> settings.reduceMotion = checked }
            },
            LinearLayout.LayoutParams(-1, activity.dp(48)),
        )
        content.addView(
            activity.label(activity.getString(R.string.motion_help), 11, Ui.muted).apply {
                setPadding(activity.dp(5), 0, activity.dp(5), 0)
            }
        )
        activity.space(content, 18)
        content.addView(
            SettingsRow(
                    activity,
                    activity.getString(R.string.language),
                    AppLanguage.label(activity),
                ) {
                    showLanguage()
                }
                .apply { id = R.id.display_language }
        )
        present(content)
    }

    private fun showAppearance() {
        val content = page(R.string.appearance)
        Appearance.entries.forEach { choice ->
            content.addView(
                SettingsRow(
                    activity,
                    activity.getString(choice.titleRes),
                    checked = settings.appearance == choice,
                ) {
                    settings.appearance = choice
                    activity.recreate()
                }
            )
            activity.space(content, 6)
        }
        present(content)
    }

    private fun showLanguage() {
        val content = page(R.string.language)
        AppLanguage.choices.forEach { (tag, name) ->
            content.addView(
                SettingsRow(
                    activity,
                    if (tag.isEmpty()) activity.getString(R.string.system) else name,
                    checked = tag == AppLanguage.selected(activity),
                ) {
                    close()
                    AppLanguage.select(activity, tag)
                }
            )
            activity.space(content, 6)
        }
        present(content)
    }
}
