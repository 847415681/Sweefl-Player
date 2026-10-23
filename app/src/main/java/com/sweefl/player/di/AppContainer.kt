package com.sweefl.player.di

import com.sweefl.player.data.history.PlaybackHistory
import com.sweefl.player.data.media.MediaStoreVideoRepository
import com.sweefl.player.data.media.VideoRepository
import com.sweefl.player.data.media.VideoThumbnailLoader
import com.sweefl.player.data.preferences.PlaybackConfigStore
import com.sweefl.player.data.preferences.PlayerPreferences
import com.sweefl.player.playback.engine.PlayerEngineFactory

import android.content.Context

/** Application-context dependencies only. Engines and sessions are never singletons. */
class AppContainer(context: Context) {
    val settings = PlayerPreferences(context.applicationContext)
    val history = PlaybackHistory(context.applicationContext)
    internal val playbackConfig = PlaybackConfigStore(context.applicationContext)
    val videos: VideoRepository = MediaStoreVideoRepository(context.applicationContext)
    val thumbnails = VideoThumbnailLoader(context.applicationContext)
    val playerEngines = PlayerEngineFactory(context.applicationContext) { settings.networkPlayback }
}
