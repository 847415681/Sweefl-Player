package com.sweefl.player.core.platform

import com.sweefl.player.domain.PlaybackConfig

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

class DocumentAccess(private val context: Context) {
    fun displayName(uri: Uri): String = if (uri.scheme != "content") uri.lastPathSegment ?: "网络视频" else try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "视频"
    } catch (_: Exception) { "视频" }

    fun retainReadPermission(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) { false }

    fun subtitleMime(uri: Uri, name: String): String? = PlaybackConfig.subtitleMimeType(name)
        ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?.takeIf { it in setOf("application/x-subrip", "text/vtt", "text/x-ssa") }
}
