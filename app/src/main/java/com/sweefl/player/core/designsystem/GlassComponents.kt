@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sweefl.player.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A functional group owns one optical surface; its children retain ordinary hit targets. */
val LocalInsideGlass = staticCompositionLocalOf { false }

@Composable
fun GlassSurface(modifier: Modifier = Modifier, radius: Int = 28, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalGlassTextSurface provides true) {
        Box(modifier.glass(radius).glassContentContrast()) {
            CompositionLocalProvider(LocalInsideGlass provides true) { content() }
        }
    }
}

/** Standalone actions float; actions inside a group share its material. */
@Composable
fun GlassIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (LocalInsideGlass.current) Modifier else Modifier.glass(100).glassContentContrast()),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface), content = content)
}

@Composable
fun GlassTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.padding(vertical = 3.dp).heightIn(min = 48.dp)
            .then(if (LocalInsideGlass.current) Modifier else Modifier.glass(20).glassContentContrast()),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Mint), content = content)
}

@Composable
fun GlassSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    val offset by animateDpAsState(if (checked) 22.dp else 0.dp, label = "glass-switch")
    Box(modifier.sizeIn(minWidth = 60.dp, minHeight = 48.dp)
        .alpha(if (enabled) 1f else .45f)
        .toggleable(checked, enabled = enabled && onCheckedChange != null, role = Role.Switch) {
            onCheckedChange?.invoke(it)
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(54.dp, 32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (checked) Mint.copy(alpha = .6f) else Color.White.copy(alpha = .18f))) {
            Box(Modifier.padding(3.dp).offset(x = offset).size(26.dp).clip(RoundedCornerShape(13.dp))
                .background(if (checked) Color(0xFF10382C) else Color.White.copy(alpha = .85f)))
        }
    }
}

@Composable
fun GlassSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f) {
    val opacity = if (enabled) 1f else .35f
    Slider(value = value, onValueChange = onValueChange, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, valueRange = valueRange,
        thumb = { Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = opacity))) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val span = valueRange.endInclusive - valueRange.start
                val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
                val start = if (layoutDirection == LayoutDirection.Rtl) size.width else 0f
                val end = size.width - start
                val y = size.height / 2f
                drawLine(Color.White.copy(alpha = .2f * opacity), Offset(start, y), Offset(end, y), size.height, StrokeCap.Round)
                if (fraction > 0f) drawLine(Mint.copy(alpha = opacity), Offset(start, y),
                    Offset(start + (end - start) * fraction, y), size.height, StrokeCap.Round)
            }
        })
}

@Composable
fun GlassTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, singleLine: Boolean = false, maxLines: Int = Int.MAX_VALUE,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default) {
    CompositionLocalProvider(LocalInsideGlass provides true) {
    OutlinedTextField(value, onValueChange, modifier, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, supportingText = supportingText,
        singleLine = singleLine, maxLines = maxLines, shape = shape, keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White.copy(alpha = .07f),
            unfocusedContainerColor = Color.White.copy(alpha = .045f), unfocusedBorderColor = Color.White.copy(alpha = .08f),
            focusedBorderColor = Mint.copy(alpha = .6f)))
    }
}

@Composable
fun GlassDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null) {
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val dismiss by rememberUpdatedState(onDismissRequest)
    // Resolve before entering Dialog: its own window must never feed its own backdrop.
    val backdropWindow = androidx.compose.ui.platform.LocalView.current.glassOwnerWindow()
    val backdropUnderlay = LocalGlassVideoFrame.current
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogBackdropProvider(backdropWindow, backdropUnderlay) { reportGlassBounds ->
        CompositionLocalProvider(LocalGlassTextSurface provides true, LocalInsideGlass provides true) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
                val tight = maxHeight < 220.dp
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures(onTap = { dismiss() }) })
                Column(Modifier.padding(if (tight) 4.dp else if (wide) 12.dp else 20.dp)
                    .widthIn(max = 560.dp).fillMaxWidth()
                     .onGloballyPositioned { coordinates ->
                         val origin = coordinates.positionOnScreen()
                         reportGlassBounds(Rect(origin.x, origin.y,
                             origin.x + coordinates.size.width, origin.y + coordinates.size.height))
                     }
                     .glass(32).glassContentContrast()
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(if (tight) 8.dp else if (wide) 16.dp else 22.dp)) {
                    if (!tight) {
                        ProvideTextStyle(if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, title)
                        Spacer(Modifier.height(if (wide) 10.dp else 16.dp))
                    }
                    // Keep the body in the same composition branch when the IME opens,
                    // so its focused TextField and scroll state survive the resize.
                    if (wide) {
                        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ProvideTextStyle(MaterialTheme.typography.bodyMedium, text) }
                            Spacer(Modifier.width(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                dismissButton?.invoke(); confirmButton()
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f, fill = false)) { ProvideTextStyle(MaterialTheme.typography.bodyMedium, text) }
                        Spacer(Modifier.height(if (tight) 4.dp else 16.dp))
                        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            dismissButton?.invoke(); confirmButton()
                        }
                    }
                }
            }
        }
    }
    }
}

/** Never replay a whole control subtree as a tinted shadow: descendants include ripple,
 * clipping and optical layers, not just glyphs. Replaying them produces rectangular/polygonal
 * patches during glass reveal. Foreground must be drawn once; contrast belongs on leaf glyphs. */
internal fun Modifier.glassContentContrast(): Modifier = this
