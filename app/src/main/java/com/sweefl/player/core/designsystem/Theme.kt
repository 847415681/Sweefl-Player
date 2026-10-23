package com.sweefl.player.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

val Mint = Color(0xFFADF6DC)
val Muted = Color(0xFFA8B8CA)
val Night = Color(0xFF090E19)

@Composable
fun SweeflTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint, onPrimary = Color(0xFF09251E),
            background = Night, surface = Color(0xFF152234),
            onSurface = Color(0xFFF1F5FC), onBackground = Color(0xFFF1F5FC),
            secondary = Color(0xFFB7C8FF), outline = Color(0xFF405164)
        ), content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalGlassEnvironment provides androidx.compose.runtime.remember { GlassEnvironment() },
                androidx.compose.material3.LocalContentColor provides Color(0xFFF1F5FC),
                content = content
            )
        }
    )
}

@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) = OpticalBackground(content)

@Composable
fun Modifier.glass(radius: Int = 28): Modifier = opticalGlass(radius)

/** Content cards stay in the content plane rather than becoming optical controls. */
fun Modifier.contentSurface(radius: Int = 24): Modifier =
    clip(RoundedCornerShape(radius.dp)).background(Color(0xFF172332))
