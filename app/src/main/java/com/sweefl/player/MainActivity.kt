package com.sweefl.player

import com.sweefl.player.core.designsystem.LocalGlassEnabled
import com.sweefl.player.core.designsystem.SweeflTheme
import com.sweefl.player.feature.library.LibraryRoute
import com.sweefl.player.feature.library.LibraryViewModel
import com.sweefl.player.feature.settings.rememberGlassEnabled

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val container = (application as SweeflApplication).container
        setContent {
            val glass = rememberGlassEnabled(container.settings)
            val interactions = com.sweefl.player.feature.settings.rememberPlaybackInteractions(container.settings)
            CompositionLocalProvider(LocalGlassEnabled provides glass,
                com.sweefl.player.feature.settings.LocalPlaybackInteractions provides
                    com.sweefl.player.feature.settings.InteractionPreferences(interactions) { container.settings.interactions = it }) {
                com.sweefl.player.feature.settings.NetworkPlaybackPreferencesProvider(container.settings) {
                com.sweefl.player.feature.settings.GlassRefreshPreferencesProvider(container.settings) {
                SweeflTheme {
                    val factory = remember { viewModelFactory { initializer { LibraryViewModel(container.videos, container.history) } } }
                    val model: LibraryViewModel = viewModel(factory = factory)
                    val thumbnails = remember { container.thumbnails::load }
                    LibraryRoute(model, container.settings, thumbnails) { uri, title, userAgent, mode, mime ->
                        val target = PlayerActivity.intent(this, uri, title, userAgent, mode)
                        if (userAgent != null) {
                            container.playbackConfig.saveMimeType(uri.toString(), mime)
                            target.setDataAndType(uri, mime)
                        }
                        startActivity(target)
                    }
                }
                }
                }
            }
        }
    }
}
