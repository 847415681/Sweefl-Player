# Android video rendering adapter — Media3 1.8.0

This is an Android Media3 video graph, not the Windows MPC DirectShow/D3D renderer. The default NATIVE mode never calls setVideoEffects; GPU_SDR opts into a real single-input video graph. The separate seek-preview player must remain unchanged.

Create one immutable PlaybackRenderingPipeline per main Session. Pass a fully configured MediaCodecVideoRenderer.Builder through createVideoRenderer, preserving selector, codec adapter, decoder fallback, event handler/listener, joining time and dropped-frame reporting. Bind the fresh player before prepare and close the pipeline before releasing that player, on applicationLooper. Commit settings by rebuilding the session while preserving position, playback state, speed, subtitle and selections; never rebuild for every slider movement.

The protected override calls super.createPlaybackVideoGraphWrapper, retaining the injected clock, playlist mode, and upstream disabled replay-cache policy. The real wrapper's setRequestOpenGlToneMapping(true) executes before input registration. The mandatory non-no-op GlEffect checks useHdr=false; the inner identity geometry effect records actual texture configuration. GPU first-frame evidence comes from the wrapper, not a generic Player event. Status is processing/callback evidence, not physical panel colorimetry. Errors and release ignore late success callbacks.

## Audited color order

Pinned sources:
- https://fastly.jsdelivr.net/gh/androidx/media@1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/PlaybackVideoGraphWrapper.java
- https://fastly.jsdelivr.net/gh/androidx/media@1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java
- https://fastly.jsdelivr.net/gh/androidx/media@1.8.0/libraries/effect/src/main/java/androidx/media3/effect/SingleInputVideoGraph.java
- https://fastly.jsdelivr.net/gh/androidx/media@1.8.0/libraries/effect/src/main/java/androidx/media3/effect/DefaultVideoFrameProcessor.java
- https://fastly.jsdelivr.net/gh/androidx/media@1.8.0/libraries/effect/src/main/java/androidx/media3/effect/DefaultShaderProgram.java

In this particular single-input/default-working-space chain, the external YUV sampler maps HDR to BT709/Gamma2.2 before user effects. createGlShaderPrograms derives useHdr from output color info. Therefore Brightness, Contrast and HSL saturation can run in the resulting SDR workspace. This does not generalize to native HDR effects, multi-input graphs or custom video sinks. HDR sampling needs real GLES3 and EXT_YUV_target; Media3 verifies these on its GL thread. BT2020 PQ/HLG is the audited path, not every Dolby Vision profile, dynamic-metadata reproduction or DRM/secure output. Missing source color metadata must not be treated as verified HDR interpretation.

API29+ is the conservative GPU gate; native retains the app minimum. Upgrades must re-audit the RestrictedApi hook and working-space order. The factory, per-video preferences, committed settings UI and session reconstruction are wired. Full verify (architecture/domain/app/lint/debug) and Release build passed after FFmpeg integration. REDMI K90 Max/API36 evidence in docs/testing-v032/r11-rendering confirms actual SDR/PQ/HLG GPU graph setup, SDR effect dimensions, wrapper first frame, visible output, and switching back to native. This is not calibrated panel measurement. Remaining release checks include changed-color pixel effects, paused commits/restart persistence, rapid release/rebuild, subtitle preservation and independent preview behavior. Retry reconstructs a fresh session graph rather than retaining sticky failure evidence.
