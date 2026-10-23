package com.sweefl.player.feature.settings

import androidx.compose.runtime.*
import com.sweefl.player.data.preferences.PlayerPreferences
import com.sweefl.player.domain.PlaybackInteractionSettings

@Composable
fun rememberPlaybackInteractions(preferences: PlayerPreferences): PlaybackInteractionSettings {
    var settings by remember(preferences) { mutableStateOf(preferences.interactions) }
    DisposableEffect(preferences) {
        val unsubscribe = preferences.observeInteractions { settings = it }
        onDispose { unsubscribe() }
    }
    return settings
}
