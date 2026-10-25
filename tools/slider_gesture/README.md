# Measured Slider gesture helper

This is a tools-only root `app_process` helper using Android `UiAutomation`. It does not install an APK or modify the player. `ui_lab.py` has root tap/swipe helpers but no existing single-pointer UiAutomation driver; the existing instrumentation runner only covers network transport.

Run the usual guarded Bilibili suite with `SWEEFL_BILI_CASES=live` (or a focused `live-quality`, `live-pause`, `live-return`, `live-rewind`). `bilibili_playback_check.py` automatically builds and invokes this helper only for live slider drags. The explicit serial and existing `SWEEFL_PREFERENCES_BACKUP` are required before device operations. The guard owns restoration of application/device state.

A host-only build, with no ADB invocation:

```powershell
python tools/slider_gesture_driver.py --build-only --output docs/testing-slider-driver-build
```

Requirements: JDK (`JAVA_HOME` or workspace `.android-tools/jdk`), Android SDK platform 35 and Build Tools D8 (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or workspace `.android-tools/sdk`). The adapter compiles the Java source and packages `classes.dex` into a source-hashed JAR in the evidence directory. This does not run Gradle. The Android helper uses platform reflection to create the UiAutomation connection, as a standalone root process; device compatibility must be verified by the guarded run.

The driver locates Sweefl's enabled `播放进度` accessibility node and reads its real `RangeInfo`. It records actual density and `ViewConfiguration.scaledTouchSlop`, then:

1. Injects one touchscreen DOWN with pointer ID 0 and a fixed `downTime`.
2. Injects MOVE events over six seconds, retaining the same pointer identity/time origin.
3. Holds before UP, refreshes `RangeInfo.current`, and corrects from the measured fractional error in the same gesture. Touch slop is already consumed; no hard-coded slop compensation is applied.
4. Requires the held fraction to be within 1.5 track pixels of the requested midpoint. Captures a screenshot and displayed time labels; refreshes and verifies the range again before UP.
5. Emits structured JSON and disconnects. Exceptions during the held gesture inject CANCEL before disconnecting.

Geometry comes from the actual semantic bounds and measured density: Material3 1.3.2 expands horizontal semantics by 10dp per side, while `GlassSlider` supplies a 20dp thumb. The test still verifies the actual RangeInfo instead of trusting these coordinates alone.

Evidence for each drag:

- `*-driver.json`: initial/held/final RangeInfo, actual slop, density, geometry, DOWN/UP elapsed time, pointer ID, correction samples and selected fraction.
- `*-held.png`: screenshot taken while the finger is still held at the verified selection.
- `*-driver-transport.json`: captured stdout/stderr/exit codes, including failures.
- Existing suite JSON/XML/screenshots and post-UP decode/shift assertions remain in place.

The fixed one-hour live-window target is anchored to the pre-drag diagnostic origin plus `downElapsedRealtimeMs - diagnostic.elapsedRealtimeMs`, covering host build/push/connection delays. The original ±30s committed-content gate and 1500–2100-second shift check remain unchanged; the measured held selection adds an independent pre-UP midpoint check. No PDT or frame-accurate wall-clock synchronization is claimed.

Only uniquely named `/data/local/tmp/sweefl-slider-<uuid>.jar/.png` files are pushed/created and removed. UiAutomation uses `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`; it does not persist accessibility settings. No CDN URLs, request headers or cookies are part of the helper's report.
