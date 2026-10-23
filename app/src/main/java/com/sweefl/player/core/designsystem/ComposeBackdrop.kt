package com.sweefl.player.core.designsystem

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.ceil

/** GPU display-list source. Only the content sibling records here, never the overlay. */
class ComposeBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin = Offset.Zero
    internal var ready = false
    internal val readers = mutableSetOf<BackdropSurfaceNode>()
}

@Composable
fun rememberComposeBackdrop(): ComposeBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { ComposeBackdrop(layer) }
}

fun Modifier.composeBackdropSource(backdrop: ComposeBackdrop): Modifier = then(SourceElement(backdrop))

private data class SourceElement(val backdrop: ComposeBackdrop) : ModifierNodeElement<SourceNode>() {
    override fun create() = SourceNode(backdrop)
    override fun update(node: SourceNode) { node.backdrop = backdrop }
    override fun InspectorInfo.inspectableProperties() { name = "composeBackdropSource" }
}

private class SourceNode(var backdrop: ComposeBackdrop) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        backdrop.origin = coordinates.positionInRoot()
        backdrop.readers.forEach { it.invalidateDraw() }
    }
    override fun ContentDrawScope.draw() {
        backdrop.layer.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { this@draw.drawContent() }
        backdrop.ready = true // Plain render bookkeeping, not observable Compose state.
        drawLayer(backdrop.layer)
        // List scrolling, thumbnail arrivals and animated backgrounds invalidate the overlay
        // in the same drawing traversal; no clock, polling coroutine or snapshot mutation.
        backdrop.readers.forEach { it.invalidateDraw() }
    }
    override fun onDetach() { backdrop.ready = false }
}

/** Background-only GPU blur/refraction. The rim and child text/icons draw afterwards. */
@Composable
fun ComposeBackdropGlassSurface(
    backdrop: ComposeBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalGlassEnabled.current,
    content: @Composable BoxScope.() -> Unit
) {
    val sampledLayer = rememberGraphicsLayer()
    val blur = LocalGlassBlurAmount.current.coerceIn(0f, 1f)
    Box(modifier.clip(RoundedCornerShape(100.dp))
        .then(SurfaceElement(backdrop, sampledLayer, enabled, blur))) {
        CompositionLocalProvider(LocalInsideGlass provides true) { content() }
    }
}

private data class SurfaceElement(val backdrop: ComposeBackdrop, val sampled: GraphicsLayer,
    val enabled: Boolean, val blur: Float) : ModifierNodeElement<BackdropSurfaceNode>() {
    override fun create() = BackdropSurfaceNode(backdrop, sampled, enabled, blur)
    override fun update(node: BackdropSurfaceNode) {
        if (node.backdrop !== backdrop) {
            node.backdrop.readers.remove(node)
            node.backdrop = backdrop
            if (node.isAttached) backdrop.readers.add(node)
        }
        node.sampled = sampled; node.enabled = enabled; node.blur = blur
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "composeBackdropGlass" }
}

internal class BackdropSurfaceNode(internal var backdrop: ComposeBackdrop,
    var sampled: GraphicsLayer, var enabled: Boolean, var blur: Float
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var origin = Offset.Zero
    private var effectKey: Triple<IntSize, Float, Float>? = null
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null
    override fun onAttach() { backdrop.readers.add(this) }
    override fun onDetach() { backdrop.readers.remove(this) }
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        origin = coordinates.positionInRoot(); invalidateDraw()
    }
    override fun ContentDrawScope.draw() {
        val radius = size.height / 2f
        if (enabled && Build.VERSION.SDK_INT >= 33 && backdrop.ready) {
            val sigma = blur * 24.dp.toPx()
            val pad = ceil(sigma * 3f + 12.dp.toPx()).toInt()
            val extent = IntSize(ceil(size.width).toInt() + pad * 2, ceil(size.height).toInt() + pad * 2)
            val key = Triple(extent, sigma, density)
            if (effectKey != key) {
                val shader = RuntimeShader(BACKDROP_SHADER)
                shader.setFloatUniform("extent", size.width, size.height)
                shader.setFloatUniform("padding", pad.toFloat())
                shader.setFloatUniform("bend", 7.dp.toPx())
                val refraction = RenderEffect.createRuntimeShaderEffect(shader, "image")
                effect = (if (sigma > 0f) RenderEffect.createChainEffect(refraction,
                    RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)) else refraction).asComposeRenderEffect()
                effectKey = key
            }
            sampled.renderEffect = effect
            val offset = origin - backdrop.origin
            sampled.record(this, layoutDirection, extent) {
                translate(pad - offset.x, pad - offset.y) { drawLayer(backdrop.layer) }
            }
            translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(sampled) }
            drawRoundRect(Color.White.copy(alpha = .045f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius))
        } else {
            // No shader on older APIs or when disabled: legible, non-optical surface.
            drawRoundRect(Color(0xF0202938), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius))
        }
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .48f),
            Color.White.copy(alpha = .06f), Color.White.copy(alpha = .18f))),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius), style = Stroke(1.dp.toPx()))
        drawContent()
    }
}

private const val BACKDROP_SHADER = """
uniform shader image;
uniform float2 extent;
uniform float padding;
uniform float bend;
half4 main(float2 p) {
    float2 local = p - float2(padding);
    float2 q = local - extent * .5;
    float r = extent.y * .5;
    float2 v = float2(max(abs(q.x) - (extent.x*.5-r), 0.), q.y);
    float d = length(v) - r;
    float edge = smoothstep(-18., 0., d);
    float2 normal = normalize(float2(sign(q.x)*v.x, v.y) + float2(.0001));
    return image.eval(p - normal * bend * edge * edge);
}
"""
