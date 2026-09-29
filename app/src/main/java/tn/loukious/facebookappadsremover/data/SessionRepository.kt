package tn.loukious.facebookappadsremover.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import tn.loukious.facebookappadsremover.core.SessionBackup

sealed interface SessionResult {
    data class Success(val message: String) : SessionResult
    data class Error(val message: String) : SessionResult
}

/**
 * Repository for session export/import broadcasts and file parsing.
 */
class SessionRepository {

    fun sendSessionBroadcast(context: Context, action: String): SessionResult {
        val intent = Intent(action).setPackage("com.facebook.katana")
        val sent = runCatching { context.sendBroadcast(intent) }.isSuccess
        return if (sent) {
            SessionResult.Success("Asking Facebook…")
        } else {
            SessionResult.Error("Broadcast failed — is Facebook installed?")
        }
    }

    fun processPickedSessionFile(context: Context, uri: Uri?): SessionResult {
        if (uri == null) return SessionResult.Error("Picker cancelled")
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()

        if (text.isNullOrBlank()) {
            return SessionResult.Error("Couldn't read the picked file")
        }
        if (!text.trimStart().startsWith("{") || !text.contains("\"files\"")) {
            return SessionResult.Error("That doesn't look like a session export")
        }

        val intent = Intent(SessionBackup.ACTION_IMPORT_DATA)
            .setPackage("com.facebook.katana")
            .putExtra(SessionBackup.EXTRA_DATA, text)

        val sent = runCatching { context.sendBroadcast(intent) }.isSuccess
        return if (sent) {
            SessionResult.Success("Asking Facebook…")
        } else {
            SessionResult.Error("Broadcast failed — is Facebook installed?")
        }
    }
}
