# Nextlib source and licensing kit — Sweefl 0.3.2

This directory accompanies the Android dependency `io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0`. It is a source/provenance kit, **not a claim that the published AAR has been reproduced**. See the [release source disclosure](<../../docs/FFMPEG-SOURCE-0.3.2.md>) for licensing, limitations and recipient-delivery requirements.

## Preserved inputs

| Component | Exact source | License for the described configuration |
| --- | --- | --- |
| Nextlib | commit `1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060` | GPL version 3; original [license](<licenses/nextlib/LICENSE>) |
| FFmpeg | 6.0 | LGPL version 3 (`--enable-version3`, no GPL/nonfree flags); original [license overview](<licenses/ffmpeg-6.0/LICENSE.md>), [LGPLv3](<licenses/ffmpeg-6.0/COPYING.LGPLv3>), [GPLv3 incorporated terms](<licenses/ffmpeg-6.0/COPYING.GPLv3>) |
| libvpx | v1.13.0 | BSD-style [license](<licenses/libvpx-v1.13.0/LICENSE>) and [patent grant](<licenses/libvpx-v1.13.0/PATENTS>) |
| Mbed TLS | v3.4.1 | Apache 2.0 [license](<licenses/mbedtls-v3.4.1/LICENSE>) |

Original downloaded gzip tar archives are retained byte-for-byte. Their URLs, sizes and SHA-256 digests are in [download provenance](<download-provenance.json>); the [SHA-256 manifest](<SHA256SUMS>) additionally covers the extracted copies and kit documentation. Hashes are observations of these downloads, not historical hashes recorded by the upstream AAR publisher. GitHub tag archive bytes can change if regenerated or tags change: keep these files, do not silently replace them.

The `licenses` subtree preserves all regular archive files whose basenames begin LICENSE, LICENCE, COPYING, NOTICE, PATENTS, AUTHORS or COPYRIGHT (case-insensitive). No standalone NOTICE file was present in any of these four archives; copyright notices embedded in source files remain in the complete archives. FFmpeg's full archive includes optional GPL sources; their presence does not mean they were enabled. Third-party licenses within libvpx are retained even where the setup disables their integration.

The `pinned-build/nextlib` subtree is a convenient byte-exact copy of all Nextlib regular files **except legal texts, which are under `licenses/nextlib`**. It includes the JNI implementation, Gradle wrapper, workflows and exact build script. Prefer extracting the original Nextlib archive into a separate work directory when building: it preserves the complete upstream layout and permissions. No local changes to upstream sources were made. No FFmpeg patch files or patch-application step were found at this pin; the integration changes are Nextlib's own source/build files. Do not describe this kit as an additional patched FFmpeg fork.

## Build instructions (not executed for this kit)

Use a Linux x86_64 host (or a Linux environment with its own Linux Android SDK/NDK), JDK 17, Bash, make, curl, tar, pkg-config and standard C/C++ host tools. Use Android SDK platform 36, NDK 25.2.9519653 and SDK CMake 3.22.1. Nextlib pins AGP 8.10.1, Kotlin 2.1.21, Media3 1.8.0 and Gradle wrapper 8.14.2. Android SDK build-tools are resolved by AGP rather than explicitly pinned in this source. Host OS/package versions and JDK vendor patch level are not fully pinned upstream.

1. Verify the manifest from this directory before extracting: `sha256sum -c SHA256SUMS`. This is integrity checking, not independent authentication of upstream provenance.
2. Set `KIT` to this directory's absolute path. Choose a **new** build directory outside the kit. Extract the archives as below, using trusted tar software; do not run as root. The kit preparation inspected archive member paths and copied only regular files, never links.

```bash
# Set these paths for your machine; no local credentials are needed.
export KIT=/absolute/path/to/sweefl-player/third-party/nextlib
export ANDROID_HOME=/absolute/path/to/linux-android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME=/absolute/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

mkdir nextlib-build-0.3.2
cd nextlib-build-0.3.2
tar -xzf "$KIT/archives/nextlib-1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060.tar.gz"
cd nextlib-1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060
mkdir -p ffmpeg/sources
tar -xzf "$KIT/archives/ffmpeg-6.0.tar.gz" -C ffmpeg/sources
tar -xzf "$KIT/archives/libvpx-v1.13.0.tar.gz" -C ffmpeg/sources
tar -xzf "$KIT/archives/mbedtls-v3.4.1.tar.gz" -C ffmpeg/sources
sdkmanager "platforms;android-36" "ndk;25.2.9519653" "cmake;3.22.1"
# Accept Android SDK terms through sdkmanager only if you agree to them.
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew :media3ext:assembleRelease
```

3. Gradle's `ffmpegSetup` task exports `ANDROID_SDK_HOME`, `ANDROID_NDK_HOME` and `ANDROID_CMAKE_VERSION` and invokes [setup.sh](<pinned-build/nextlib/ffmpeg/setup.sh>). The prepopulated source directories prevent its three source downloads. The script still invokes sdkmanager, and Gradle still needs its distribution, plugins and Maven dependencies: this is **not an offline toolchain kit**. The script uses directory existence to skip work; always use a clean build directory, as stale `ffmpeg/build` or `ffmpeg/output` can skip the entire setup.
4. Setup builds Mbed TLS, then libvpx, then FFmpeg, for x86, x86_64, armeabi-v7a and arm64-v8a with native Android API 21. Sweefl's target is arm64-v8a. JNI links avutil, avcodec, swresample and swscale. Output is `media3ext/build/outputs/aar/media3ext-release.aar`; intermediate FFmpeg libraries/headers are under `ffmpeg/output`. The upstream CI upload path still says `ffcodecs`; do not use that stale artifact path.
5. To use a rebuilt AAR, make an explicitly recorded change in a separate copy of the application source: replace the Maven Nextlib dependency with a local AAR dependency, preserving its transitive dependencies (notably Media3). Rebuild the complete app using its own documented build instructions. No app integration change or build was performed while preparing this kit.

## Verification and tooling

[prepare_source_kit.py](<prepare_source_kit.py>) downloads the four archives, validating their gzip tar structure before saving. [extract_source_texts.py](<extract_source_texts.py>) copies legal texts and Nextlib regular files without executing archive content or following archive symlinks. These helper scripts use Python's standard library. Do not rerun download preparation to overwrite the release provenance; retain the shipped manifest for comparison. The source archives remain authoritative.

The inspected reference AAR SHA-256 is `f18b041a8fe823f86b23af6270f1ddd617036dff5753edf7b2a21ed6d806ca11`. It contains the five native libraries `libavcodec.so`, `libavutil.so`, `libmedia3ext.so`, `libswresample.so`, `libswscale.so` for each of the four ABIs. The AAR itself is not duplicated in this kit. This identifies the inspected binary only; no binary-to-source equivalence, byte reproducibility, native build success or device behavior was verified here.
