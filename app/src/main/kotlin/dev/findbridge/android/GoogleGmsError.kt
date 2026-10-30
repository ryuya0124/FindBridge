package dev.findbridge.android

/** Categorize broker errors without exposing account names, tokens or remote error text. */
internal enum class GoogleGmsError {
    CONFIGURATION,
    SCOPE,
    CANCELED,
    OTHER;

    companion object {
        fun classify(code: Int?, message: String?): GoogleGmsError {
            val reason = message.orEmpty()
            return when {
                reason.contains("UNREGISTERED_ON_API_CONSOLE", true) || code == 10 -> CONFIGURATION
                reason.contains("invalid_scope", true) ||
                    reason.contains("invalid scope", true) ||
                    reason.contains("unauthorized_client", true) ||
                    reason.contains("access_denied", true) -> SCOPE
                code == 16 || code == 12501 -> CANCELED
                else -> OTHER
            }
        }
    }
}
