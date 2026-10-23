package com.sweefl.player.feature.settings

import com.sweefl.player.data.preferences.PlayerPreferences

import android.os.Build
import androidx.compose.runtime.*

@Composable
fun rememberGlassEnabled(prefs: PlayerPreferences): Boolean {
    var enabled by remember(prefs) { mutableStateOf(prefs.glassEnabled) }
    DisposableEffect(prefs) {
        val unsubscribe = prefs.observeGlass { enabled = it }
        onDispose { unsubscribe() }
    }
    return enabled && Build.VERSION.SDK_INT >= 33
}
