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
    private val choiceBounds: () -> android.graphics.Rect,
    private val onMapChanged: () -> Unit,
    private val present: (LinearLayout) -> Unit,
) {
    private val choices = DisplayChoices(activity, scene, choiceBounds)

    fun dismissChoices() = choices.dismiss()

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
        activity.space(content, 8)
        content.addView(
            activity
                .label(
                    activity.getString(R.string.map_preview_sources),
                    10,
                    Ui.muted,
                )
                .apply {
                    setPadding(activity.dp(5), 0, activity.dp(5), 0)
                }
        )
        activity.space(content, 18)
        content.addView(
            SettingsRow(
                    activity,
                    activity.getString(R.string.appearance),
                    activity.getString(settings.appearance.titleRes),
                ) { anchor ->
                    showAppearance(anchor)
                }
                .apply { id = R.id.display_appearance }
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
            activity.label(activity.getString(R.string.blur_help), 11, Ui.muted).apply {
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
                ) { anchor ->
                    showLanguage(anchor)
                }
                .apply { id = R.id.display_language }
        )
        present(content)
    }

    private fun showAppearance(anchor: android.view.View) {
        choices.show(
            anchor,
            Appearance.entries.map {
                activity.getString(it.titleRes) to (settings.appearance == it)
            },
        ) { index ->
            val choice = Appearance.entries[index]
            if (settings.appearance != choice) {
                settings.appearance = choice
                activity.recreate()
            }
        }
    }

    private fun showLanguage(anchor: android.view.View) {
        choices.show(
            anchor,
            AppLanguage.choices.map { (tag, name) ->
                (if (tag.isEmpty()) activity.getString(R.string.system) else name) to
                    (tag == AppLanguage.selected(activity))
            },
        ) { index ->
            val tag = AppLanguage.choices[index].first
            if (tag != AppLanguage.selected(activity)) AppLanguage.select(activity, tag)
        }
    }
}
