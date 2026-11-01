package dev.findbridge.android

/**
 * Authentication progress is indeterminate; page loading is not an account completion percentage.
 */
internal sealed interface AppleSignInState {
    data object Working : AppleSignInState

    data object Verification : AppleSignInState

    data class Failed(val title: Int, val detail: Int) : AppleSignInState
}
