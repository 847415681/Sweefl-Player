package com.sweefl.player.core.designsystem

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

@Stable
class GlassEnvironment {
    var width by mutableFloatStateOf(1f)
    var height by mutableFloatStateOf(1f)
    var light by mutableStateOf(Offset(200f, 180f))
    var origin by mutableStateOf(Offset.Zero)
}
val LocalGlassEnabled = staticCompositionLocalOf { true }
val LocalGlassTextSurface = staticCompositionLocalOf { false }
val LocalGlassEnvironment = staticCompositionLocalOf { GlassEnvironment() }

// Rounded-rectangle distance and smooth centerward displacement adapted from
// shuding/liquid-glass, © 2025 Shu Ding (MIT). See assets/liquid-glass-LICENSE.txt.
// Native adaptation: pixel-space corners, capped displacement, authored rim lighting.
// This intentionally does not reproduce the demo's 8-bit SVG map clipping exactly.
private const val BACKDROP = """
float3 backdrop(float2 p) {
    float2 uv = p / max(resolution, float2(1.0));
    float3 base = mix(float3(.035,.055,.09), float3(.045,.065,.12), uv.y);
    float teal = exp(-length((uv-float2(.04,.05))*float2(1.8,2.6))*2.4);
    float violet = exp(-length((uv-float2(.98,.83))*float2(1.8,2.5))*2.8);
    float ribbon = .5+.5*sin(uv.x*7.0+uv.y*5.0);
    return base + teal*float3(.10,.22,.23)+violet*float3(.09,.035,.16)+ribbon*.014;
}
"""
private const val UNIFORMS = """
uniform float2 resolution;
uniform float2 origin;
uniform float2 size;
uniform float2 light;
uniform float radius;
uniform float density;
uniform float textSurface;
uniform float blurAmount;
uniform float compositeBackdrop;
uniform float opticsPass;
uniform float2 layerOffset;
"""
private const val OPTICS = """
half4 main(float2 p) {
    p -= layerOffset;
    float2 halfSize=size*.5;
    float2 q=abs(p-halfSize)-(halfSize-radius);
    float d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
    float depth=max(-d,0.0);
    float rimWidth=min(12.0*density, max(radius*.6, 3.0));
    float rim=1.0-smoothstep(0.0,rimWidth,depth);
    float shaped=rim*rim*(3.0-2.0*rim);
    float2 outward=max(q,0.0)+float2(.0001);
    if(q.x<0.0 && q.y<0.0) outward=q.x>q.y?float2(1.0,0.0):float2(0.0,1.0);
    float2 n=normalize(outward)*sign(p-halfSize+float2(.0001));
    float2 lamp=normalize(light-origin-p+float2(.001));
    float glint=pow(max(dot(n,lamp),0.0),2.0);
    float edge=exp(-depth/max(density*1.2,.5));
    float inner=exp(-pow((depth-4.0*density)/max(4.0*density,1.0),2.0));
    float lighting=edge*(.035+glint*.22)+shaped*.016-inner*.022;
    float lightAlpha=abs(lighting);
    float lightColor=lighting>=0.0?1.0:0.0;
    // This separate rim pass stays sharp above the Gaussian-filtered transmission.
    if(opticsPass>1.5) return half4(float3(lightColor*lightAlpha),lightAlpha);
    float2 shift=(p-halfSize)*(.07*shaped)+n*shaped*min(12.0*density,size.y*.20);
    shift *= mix(1.0,.85,textSurface);
    // The padded Gaussian input includes the real surroundings outside the glass shape.
    if(d>0.0) shift=float2(0.0);
    float2 bent=p-clamp(shift,float2(-16.0*density),float2(16.0*density));
    float3 rgb=sampleScene(bent);
    float luminance=dot(rgb,float3(.2126,.7152,.0722));
    if(compositeBackdrop<.5) {
        rgb=mix(float3(luminance),rgb,1.08);
        rgb=clamp((rgb-.5)*1.10+.5,0.0,1.0)*1.035;
        float tint=blurAmount*mix(.12,.30,textSurface);
        rgb=mix(rgb,float3(.035,.065,.095),tint);
    }
    if(opticsPass<.5) rgb=mix(rgb,float3(lightColor),lightAlpha);
    float alpha=.985;
    return half4(clamp(rgb,0.0,1.0)*alpha,alpha);
}
"""
private const val SCENE = UNIFORMS + BACKDROP + """
float3 sampleScene(float2 p) { return backdrop(origin+p); }
""" + OPTICS
private const val VIDEO = UNIFORMS + """
uniform shader video;
uniform float2 sourceOrigin;
uniform float2 sourceSize;
uniform float2 sourceScale;
float3 sampleScene(float2 p) {
    float2 uv=origin+p-sourceOrigin;
    if(uv.x<0.0 || uv.y<0.0 || uv.x>sourceSize.x || uv.y>sourceSize.y) return float3(.025,.035,.05);
    return float3(video.eval(uv*sourceScale).rgb);
}
""" + OPTICS
private const val BACKGROUND = "uniform float2 resolution;" + BACKDROP + "half4 main(float2 p) { return half4(backdrop(p),1.0); }"

@RequiresApi(33)
private fun shader(code: String) = RuntimeShader(code)

@Composable
fun OpticalBackground(content: @Composable BoxScope.() -> Unit) {
    val environment = LocalGlassEnvironment.current
    val view = LocalView.current
    val location = remember { IntArray(2) }
    val enabled = LocalGlassEnabled.current
    val background = remember(enabled) { if (enabled && Build.VERSION.SDK_INT >= 33) shader(BACKGROUND) else null }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        environment.width=it.size.width.toFloat(); environment.height=it.size.height.toFloat()
        view.getLocationOnScreen(location)
        environment.origin=it.positionInRoot()+Offset(location[0].toFloat(),location[1].toFloat())
    }.pointerInput(environment) {
        awaitPointerEventScope { while (true) {
            val event=awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull()?.let { environment.light=it.position+environment.origin }
        } }
    }.background(Brush.linearGradient(listOf(Color(0xFF254854),Night,Color(0xFF251E3E))))
        .drawBehind {
            if (Build.VERSION.SDK_INT >= 33 && background != null) {
                background.setFloatUniform("resolution",size.width,size.height)
                drawRect(ShaderBrush(background))
            }
        }, content=content)
}

@Composable
fun Modifier.opticalGlass(radius: Int): Modifier {
    val enabled=LocalGlassEnabled.current
    val environment=LocalGlassEnvironment.current
    val videoFrame=LocalGlassVideoFrame.current
    val videoBackdrop=LocalVideoGlassBackdrop.current
    // Readiness gates the entire control reveal upstream. Do not restart a separate
    // material-opacity tween on every seek: a fresh valid sample should be fully optical
    // in its first displayed frame, without 120ms of ordinary transparent appearance.
    val transmission = 1f
    val textSurface=LocalGlassTextSurface.current
    val compositeBackdrop = LocalGlassBackdropIsComposite.current
    val blurAmount = LocalGlassBlurAmount.current
    val blurRadius = with(androidx.compose.ui.platform.LocalDensity.current) { (blurAmount * 24f).dp.toPx() }
    val gaussianBlur = remember(blurRadius) {
        if (blurRadius > 0f) BlurEffect(blurRadius, blurRadius, TileMode.Clamp) else null
    }
    val blurredTransmission = rememberGraphicsLayer()
    val view=LocalView.current
    val location=remember { IntArray(2) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scene=remember(enabled) { if(enabled && Build.VERSION.SDK_INT >= 33) shader(SCENE) else null }
    val video=remember(enabled) { if(enabled && Build.VERSION.SDK_INT >= 33) shader(VIDEO) else null }
    val sceneBrush = remember(scene) { if (Build.VERSION.SDK_INT >= 33) scene?.let { ShaderBrush(it) } else null }
    val videoBrush = remember(video) { if (Build.VERSION.SDK_INT >= 33) video?.let { ShaderBrush(it) } else null }
    // A recorded blur layer must not share mutable pass uniforms with its sharp rim.
    val rimShader = remember(enabled) { if (enabled && Build.VERSION.SDK_INT >= 33)
        shader(UNIFORMS + "float3 sampleScene(float2 p) { return float3(0.0); }" + OPTICS) else null }
    val rimBrush = remember(rimShader) { if (Build.VERSION.SDK_INT >= 33) rimShader?.let { ShaderBrush(it) } else null }
    val shape=RoundedCornerShape(radius.dp)
    // Platform elevation shadows assume an opaque caster. On transparent glass their
    // tessellated interior leaks through as polygon/rectangular patches during reveal.
    // Optical rim lighting below supplies depth without an opaque shadow caster.
    return this.drawWithContent {
        // Read at draw time too: a source can invalidate after composition but before this
        // display list is drawn. Never expose a tinted fallback in that one-frame gap.
        if (glassContentReady(enabled, videoBackdrop, videoFrame.value != null)) drawContent()
    }.clip(shape)
        .onGloballyPositioned {
            view.getLocationOnScreen(location)
            origin=it.positionInRoot()+Offset(location[0].toFloat(),location[1].toFloat())
        }.drawBehind {
            val frame=videoFrame.value
            val effect=if(videoBackdrop) { if(frame != null) video else null } else scene
            if(Build.VERSION.SDK_INT >= 33 && effect != null && (frame != null || environment.width>1)) {
                effect.setFloatUniform("resolution",environment.width,environment.height)
                val offset=if(frame != null) origin else origin-environment.origin
                effect.setFloatUniform("origin",offset.x,offset.y)
                effect.setFloatUniform("size",size.width,size.height)
                effect.setFloatUniform("light",environment.light.x,environment.light.y)
                effect.setFloatUniform("radius",radius.dp.toPx().coerceAtMost(size.minDimension/2))
                effect.setFloatUniform("density",density)
                effect.setFloatUniform("textSurface",if(textSurface) 1f else 0f)
                effect.setFloatUniform("blurAmount",blurAmount)
                effect.setFloatUniform("compositeBackdrop",if(compositeBackdrop) 1f else 0f)
                if(frame != null) {
                    effect.setInputShader("video",frame.shader)
                    effect.setFloatUniform("sourceOrigin",frame.origin.x,frame.origin.y)
                    effect.setFloatUniform("sourceSize",frame.width,frame.height)
                    effect.setFloatUniform("sourceScale",frame.bitmap.width/frame.width,frame.bitmap.height/frame.height)
                }
                val brush = if (frame != null) videoBrush!! else sceneBrush!!
                if (gaussianBlur == null) {
                    // Zero is an unfiltered, full-detail optical path.
                    effect.setFloatUniform("opticsPass", 1f)
                    effect.setFloatUniform("layerOffset", 0f, 0f)
                    drawRect(brush, alpha = transmission)
                } else {
                    // RenderEffect uses the platform's continuous GPU Gaussian blur. Padding
                    // by three sigma supplies neighbouring pixels and avoids clipped/dark rims.
                    val pad = kotlin.math.ceil(blurRadius * 3f).toInt()
                    effect.setFloatUniform("opticsPass", 1f)
                    effect.setFloatUniform("layerOffset", pad.toFloat(), pad.toFloat())
                    blurredTransmission.renderEffect = gaussianBlur
                    blurredTransmission.alpha = transmission
                    blurredTransmission.record(this, layoutDirection,
                        IntSize(size.width.toInt() + pad * 2, size.height.toInt() + pad * 2)) {
                        drawRect(brush)
                    }
                    translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(blurredTransmission) }
                }
            } else {
                // No valid video sample must never mean an empty transparent surface.
                // Use a neutral frosted substrate plus the same sharp optical rim while
                // seeking/loading; never retain old-scene pixels merely to hide latency.
                // Controls remain readable and interactive even when a decoder stalls.
                drawRect(Color(0xB31B293A))
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.13f),Color.White.copy(alpha=.025f),Color.White.copy(alpha=.06f))))
            }
            // Optical edge depth does not depend on a captured frame. Retain the same
            // material while transmission waits for a fresh post-seek buffer.
            if (Build.VERSION.SDK_INT >= 33 && enabled && rimShader != null) {
                    val offset = if (videoBackdrop) origin else origin-environment.origin
                    rimShader.setFloatUniform("size", size.width, size.height)
                    rimShader.setFloatUniform("origin", offset.x, offset.y)
                    rimShader.setFloatUniform("light", environment.light.x, environment.light.y)
                    rimShader.setFloatUniform("radius", radius.dp.toPx().coerceAtMost(size.minDimension/2))
                    rimShader.setFloatUniform("density", density)
                    rimShader.setFloatUniform("opticsPass", 2f)
                    rimShader.setFloatUniform("layerOffset", 0f, 0f)
                    drawRect(rimBrush!!)
            }
        }.border(.75.dp,Brush.linearGradient(listOf(Color.White.copy(alpha=.20f),Color.White.copy(alpha=.035f),Color.White.copy(alpha=.09f))),shape)
}
