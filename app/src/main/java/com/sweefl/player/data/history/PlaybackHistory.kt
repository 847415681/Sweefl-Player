package com.sweefl.player.data.history

import com.sweefl.player.domain.resumePosition

import android.content.Context
import android.net.Uri
import com.sweefl.player.data.common.uriStorageKey

interface PlaybackHistoryRepository {
    fun position(uri: Uri): Long
    fun lastUri(): Uri?
    fun lastTitle(): String
    fun save(uri: Uri, title: String, position: Long, duration: Long)
    fun clear()
}

class PlaybackHistory(context: Context) : PlaybackHistoryRepository {
    private val prefs = context.getSharedPreferences("playback_history", Context.MODE_PRIVATE)
    override fun position(uri: Uri): Long = prefs.getLong("position_${uriStorageKey(uri.toString())}", 0L)
    override fun lastUri(): Uri? = prefs.getString("last_uri", null)?.let(Uri::parse)
    override fun lastTitle(): String = prefs.getString("last_title", "继续播放") ?: "继续播放"
    override fun save(uri: Uri, title: String, position: Long, duration: Long) {
        val resume = resumePosition(position, duration)
        prefs.edit().putLong("position_${uriStorageKey(uri.toString())}", resume)
            .putString("last_uri", uri.toString()).putString("last_title", title).apply()
    }
    override fun clear() = prefs.edit().clear().apply()
}
