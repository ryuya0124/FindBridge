package dev.findbridge.android

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import dev.findbridge.android.Ui.actionButton
import dev.findbridge.android.Ui.column
import dev.findbridge.android.Ui.dp
import dev.findbridge.android.Ui.label
import dev.findbridge.android.Ui.space

/** Native editor and final preview. Opening or canceling the form never sends a command. */
internal class LostModeForm(
    private val activity: Activity,
    private val device: AppleDevice,
    private val canceled: () -> Unit,
    private val confirmed: (LostModeDraft) -> Unit,
) : LinearLayout(activity) {
    private val editor = activity.column()
    private val preview = activity.column()
    private val phone = input(R.string.lost_phone, 100, InputType.TYPE_CLASS_PHONE)
    private val message =
        input(
            R.string.lost_message,
            device.lostModeMessageLimit,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
    private val code =
        input(
            R.string.lost_new_code,
            device.lostModePasscodeLength,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
    private val codeAgain =
        input(
            R.string.lost_repeat_code,
            device.lostModePasscodeLength,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
    private var draft: LostModeDraft? = null
    private var committed = false
    private var securedWindow = false

    init {
        orientation = VERTICAL
        addView(activity.label(activity.getString(R.string.lost_mode), 23, Ui.ink, true))
        activity.space(this, 8)
        addView(activity.label(device.displayName(activity), 17, Ui.ink, true))
        addView(activity.label(device.model, 13, Ui.muted))
        activity.space(this, 12)
        editor.addView(
            activity.label(
                activity.getString(
                    if (device.provider == DeviceProvider.GOOGLE) R.string.google_lost_explanation
                    else R.string.lost_explanation
                ),
                14,
                Ui.muted,
            )
        )
        activity.space(editor, 12)
        editor.addView(phone)
        activity.space(editor, 10)
        editor.addView(message)
        if (device.lostModeNeedsPasscode) {
            activity.space(editor, 10)
            editor.addView(code)
            activity.space(editor, 10)
            editor.addView(codeAgain)
        }
        val error =
            activity
                .label(activity.getString(R.string.lost_invalid), 13, 0xffff453a.toInt())
                .apply { visibility = GONE }
        editor.addView(error)
        activity.space(editor, 14)
        editor.addView(
            activity.actionButton(activity.getString(R.string.lost_continue)).apply {
                id = CONTINUE_ID
                setOnClickListener {
                    val candidate =
                        LostModeDraft(
                            phone.text.toString().trim(),
                            message.text.toString(),
                            if (device.lostModeNeedsPasscode) code.text.toString() else "",
                        )
                    val valid =
                        (if (device.provider == DeviceProvider.GOOGLE)
                            GoogleCommandStore.validLostDraft(device, candidate)
                        else runCatching { LostMode.body(device, candidate) }.isSuccess) &&
                            (!device.lostModeNeedsPasscode ||
                                code.text.toString() == codeAgain.text.toString())
                    if (!valid) {
                        error.visibility = VISIBLE
                        return@setOnClickListener
                    }
                    error.visibility = GONE
                    draft = candidate
                    showPreview(candidate)
                }
            }
        )
        editor.addView(
            activity.actionButton(activity.getString(R.string.cancel), false).apply {
                setOnClickListener { canceled() }
            }
        )
        addView(editor)
        addView(preview.apply { visibility = GONE })
    }

    private fun input(hintRes: Int, limit: Int, type: Int): EditText =
        EditText(activity).apply {
            isSaveEnabled = false
            hint = activity.getString(hintRes)
            inputType = type
            filters = arrayOf(InputFilter.LengthFilter(limit))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setTextColor(Ui.ink)
            setHintTextColor(Ui.muted)
            textSize = 15f
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            background = Ui.rounded(Ui.surface, activity.dp(14))
            contentDescription = hint
        }

    private fun showPreview(value: LostModeDraft) {
        preview.removeAllViews()
        preview.addView(
            activity.label(
                activity.getString(R.string.lost_confirm, device.displayName(activity)),
                17,
                Ui.ink,
                true,
            )
        )
        if (value.phone.isNotBlank()) preview.addView(activity.label(value.phone, 15))
        if (value.message.isNotBlank()) preview.addView(activity.label(value.message, 15))
        if (device.lostModeNeedsPasscode)
            preview.addView(
                activity.label(
                    activity.getString(
                        R.string.lost_code_preview,
                        "•".repeat(value.passcode.length),
                    ),
                    14,
                    Ui.muted,
                )
            )
        activity.space(preview, 14)
        preview.addView(
            activity.actionButton(activity.getString(R.string.lost_activate)).apply {
                id = CONFIRM_ID
                setOnClickListener {
                    if (committed) return@setOnClickListener
                    val submitted = draft ?: return@setOnClickListener
                    committed = true
                    isEnabled = false
                    confirmed(submitted)
                }
            }
        )
        preview.addView(
            activity.actionButton(activity.getString(R.string.lost_back), false).apply {
                setOnClickListener {
                    if (!committed) {
                        draft = null
                        preview.visibility = GONE
                        editor.visibility = VISIBLE
                    }
                }
            }
        )
        preview.addView(
            activity.actionButton(activity.getString(R.string.cancel), false).apply {
                setOnClickListener { if (!committed) canceled() }
            }
        )
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
        editor.visibility = GONE
        preview.visibility = VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        securedWindow =
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
        if (securedWindow) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onDetachedFromWindow() {
        draft = null
        for (input in listOf(phone, message, code, codeAgain)) input.text.clear()
        if (securedWindow) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onDetachedFromWindow()
    }

    companion object {
        const val CONTINUE_ID = 0x16fb0131
        const val CONFIRM_ID = 0x16fb0132
    }
}
