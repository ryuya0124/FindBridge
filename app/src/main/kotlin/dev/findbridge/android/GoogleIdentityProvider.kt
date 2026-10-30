package dev.findbridge.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Same-UID metadata handoff; no password, access token, cookie or location is exposed. */
class GoogleIdentityProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid())
        val context = requireNotNull(context)
        if (method == "authorizeCommand")
            return Bundle().apply {
                putBoolean(
                    "authorized",
                    GoogleRepository.get(context).authorizeCommand(arg.orEmpty()),
                )
            }
        if (method == "finishCommand") {
            GoogleCommandStore.revoke(arg.orEmpty())
            return Bundle()
        }
        if (method == "takeCommand") {
            val ticket =
                requireNotNull(arg).takeIf { it.matches(Regex("[0-9a-f]{48}")) } ?: return Bundle()
            return Bundle().apply {
                GoogleRepository.get(context).takeCommand(ticket)?.let {
                    putString("command", it.toString())
                }
            }
        }
        require(method == "verifiedIdentity")
        val identity = SecureSessionStore(context, "google_identity").load()
        val connection = SecureSessionStore(context, "google_connection").load()
        val verified = identity?.optBoolean("verified", false) == true
        val account = if (verified) identity?.optString("account").orEmpty() else ""
        return Bundle().apply {
            putBoolean("verified", verified)
            putString("account", account)
            putBoolean(
                "webConnected",
                connection?.optBoolean("connected") == true &&
                    connection.optString("account").equals(account, true),
            )
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
