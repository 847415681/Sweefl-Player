package com.sweefl.player.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sweefl.player.core.designsystem.*
import kotlin.math.roundToInt

internal val LocalPreviewGlassBlurAmount = staticCompositionLocalOf<(Int) -> Unit> { {} }
internal val LocalSaveGlassBlurAmount = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun GlassBlurSetting() {
    val raw = LocalGlassBlurAmount.current
    val amount = ((if (raw.isFinite()) raw else 0f).coerceIn(0f, 1f) * 100f).roundToInt()
    val preview = LocalPreviewGlassBlurAmount.current
    val save = LocalSaveGlassBlurAmount.current
    val currentSave by rememberUpdatedState(save)
    val supported = Build.VERSION.SDK_INT >= 33
    val glassEnabled = LocalGlassEnabled.current
    DisposableEffect(Unit) { onDispose { currentSave() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("玻璃模糊程度", Modifier.weight(1f))
            Text("$amount%", color = if (supported) Mint else Muted)
        }
        Text("0% 不模糊，只保留折射与反射；调高后背景更柔和，便于阅读文字。",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        GlassSlider(value = amount.toFloat(), onValueChange = { preview(it.roundToInt()) },
            onValueChangeFinished = save, valueRange = 0f..100f, enabled = supported,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "玻璃模糊程度"; stateDescription = "$amount%"
            })
        if (!supported) Text("实时玻璃模糊需要 Android 13 或更高版本；当前使用兼容材质。",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        else if (!glassEnabled) Text("实时玻璃已关闭，此设置将在开启后生效。",
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
