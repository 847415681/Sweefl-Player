# Sweefl native-window lifecycle adapter patch

This local patch changes **Nextlib's JNI adapter**, not FFmpeg's codec libraries. The original source kit, archives and provenance manifest remain unchanged. The application may opt into the rebuilt local AAR described below.

## Failure and correction

The pinned `ffvideo.cpp` stores a JNI **local** `jobject` reference in `JniContext` and compares its numeric value with a later call's `Surface`. Local references are not valid across JNI calls. Moreover, a Java `Surface` can acquire a new native backing during recreation without changing Java identity. The cached `ANativeWindow` can therefore point at an abandoned BufferQueue.

The patch resolves `ANativeWindow_fromSurface` on **each render call**. If the acquired native pointer equals the retained native window, it releases the extra reference. If it differs, it releases the former window, adopts the current one and resets dimensions. No Java reference is retained. Resetting dimensions also frees the old swscale context before replacing it.

An `ANativeWindow_lock` result of `-19` (`ENODEV`) previously returned decoder success without posting the frame. A paused player's only output frame could consequently be consumed without display. The patch reacquires and retries once using the **same output buffer**. If it still fails, it returns the existing decoder error code rather than success. Successful rendering still requires `ANativeWindow_unlockAndPost` success. This cannot make a genuinely invalid Surface valid; it prevents silently reporting that a missing frame was shown.

## Rebuild only the adapter

Prerequisites: Windows x64 Android NDK **25.2.9519653 / r25c**, Python 3, the preserved kit archives, and the original Maven AAR whose SHA-256 is checked by the script.

```powershell
python third-party/nextlib/rebuild_native_adapter.py `
  --ndk D:/path/to/android-ndk-r25c `
  --aar D:/path/to/nextlib-media3ext-1.8.0-0.9.0.aar
```

The script:

1. Copies JNI sources to the ignored `app/build/nextlib-native` directory and applies checked source transformations. It writes a unified `native-window.patch` for review.
2. Extracts FFmpeg **6.0 public headers** from the preserved source archive, generating the public AArch64 little-endian/unaligned-access `avconfig.h` definitions.
3. Links the four adapter translation units against the **existing** AAR's avcodec, avutil, swresample and swscale libraries. It does not run FFmpeg's configure/build scripts or rebuild any codec library.
4. Uses API 21, static libc++, and maximum ELF page alignment 16 KiB; the application itself retains its existing minimum SDK.
5. Writes `app/libs/nextlib-media3ext-1.8.0-0.9.0-sweefl.aar`. Only `jni/arm64-v8a/libmedia3ext.so` is replaced. Java classes and every other ZIP member are verified byte-for-byte identical to the original.
6. Writes hashes and the complete compiler command to `app/build/nextlib-native/build-report.json`.

The app dependency must explicitly reference the local AAR instead of including both the original and modified Nextlib dependency. The app must continue to declare the Media3 dependencies required by Nextlib, because a local AAR has no Maven transitive dependency resolution.

Only arm64-v8a receives this patch; that is Sweefl's supported application ABI. Other ABI entries are preserved solely to make the AAR transformation narrow. This is not a reproduction of the upstream binary and does not change its licenses. Device lifecycle validation, app builds and release signing are separate from the adapter rebuild.
