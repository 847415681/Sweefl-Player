package com.sweefl.player.core.designsystem

import androidx.compose.runtime.compositionLocalOf

/** Zero keeps sharp transmission, refraction and reflections without optical blur. */
val LocalGlassBlurAmount = compositionLocalOf { 0f }

internal val LocalVideoGlassBackdrop = compositionLocalOf { false }
