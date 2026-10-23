# Sweefl Player 0.3.2 r40 — glass and network update

Local arm64 R8 Release, signed with existing local debug certificate, not a production/public distribution signing key. GPLv3 combined delivery retained; no public upload. Android application version0.3.2/code6 retained for update compatibility.

## Changes

- Properties glass copies a cropped backdrop instead of the entire window; capture halo follows actual Gaussian radius plus optical displacement. Blur0 avoids maximum-radius overhead. Blur is platform GPU RenderEffect, foreground/rim stay sharp.
- Video sampling survives control visibility changes; valid paused frames reused, seek epoch rejects old frames. Initial control reveal waits for readiness up to180ms. No additional120ms material-alpha tween. While sample invalid/loading, neutral frosted substrate and stable optical rim replace empty transparency; this is deliberately not fabricated live refraction or stale video retention.
- Library video/folder navigation floats over GPU-recorded content; actual scrolling list is sampled. No PixelCopy/timer for library backdrop. Three-second idle measurement had0newUIframes.
- Network settings:1..32 shared Range acceleration workers (default4), playback buffer target5..600seconds(default30), persisted and effective next player creation.64MiB allocator target is not an overall process-memory hard cap; per-source chunks max8MiB. No disk/offline cache.
- Safe parallel eligibility requires known length>=1MiB, identity GET, exact206 ContentRange and strongETag. Validated chunks preserve order. Essential sequential track connections are outside acceleration limit to prevent starvation. HLS/DASH support is eligible resource-range acceleration, not concurrent playlist-segment scheduling. Unsupported servers fall back;32isnotalwaysfaster.

## Verification

- verify+assembleRelease PASS; architecture73Kotlinfiles, JVM unit tests, Debug lint and R8 Release. Finalbuild1m14s104tasks.
- Same RedmiK20Pro Android15 Release dialog12s samples: baseline9.41%UIjank/p9527ms; cropped3.26%/21ms. Blur-aware samples0:0%/12ms,50:0.41%/19ms,100:3.27%/21ms. Single samples, not decoder-drop statistics or universal benchmarks.
- Actual Android production DataSource instrumentation17contracts PASS: byteSHA full/offsets, unsupportedRange/weakETag/rejection fallback, malformedRange/changedETag/truncation rejection, owner-thread exactly-onceclose1/4/32, two sources shared32saturation and exacthash/noactiveleaks.
- Latest real throttledHTTP32setting peak31activeobserved;Range/ignoredRange/weakETag allplay. Separate deterministic test saturated32.
- UI33rejected;32threads/5seconds persist acrossrestart; short-target playback checked.
- MP4/HLS/DASH play/pause at1and32 pass; finalr40Release smoke passes.
- Native/GPU/FFmpeg paused RGB seeks and rapid convergence pass; immersive gestures pass. Finalr40Release blur0recording660frames/blur50recording509frames show no wrong dominant RGB in4regions. This does not prove exactcompositorPTS or universal zerolatency. Fresh-frame waiting uses neutral material, not actual refraction until source ready.
- Original three preference files, volume and rotation restored by test guard; no user media deleted. Final phone installedr40.

## Platform comparison

pro648 VisualEffectView demo uses UIKitUIBlurEffect/UIVisualEffectView, not a published custom Gaussian implementation. GPU scene reuse is relevant; cross-platform performance superiority was not measured.

Detailed evidence stays under docs/testing-v033 in workspace (excluded from source bundle to avoid user-state evidence). Source bundle contains source, tests/build scripts and retained third-party source/license material.
