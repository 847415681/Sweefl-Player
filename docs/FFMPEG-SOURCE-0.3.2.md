# Sweefl 0.3.2 — Nextlib / FFmpeg source disclosure

## Scope and licensing

Sweefl uses Android Nextlib dependency `io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0`. The supplied source pin is [anilbeesetti/nextlib commit 1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060](https://github.com/anilbeesetti/nextlib/tree/1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060), whose [original license](<../third-party/nextlib/licenses/nextlib/LICENSE>) is GPL version 3. Nextlib's GPL obligations are separate from the licenses of its native dependencies; calling the entire combination “LGPL-only” would be incorrect.

The pinned [setup script](<../third-party/nextlib/pinned-build/nextlib/ffmpeg/setup.sh>) downloads FFmpeg 6.0, libvpx 1.13.0 and Mbed TLS 3.4.1. Its FFmpeg configuration enables `--enable-version3` and `--enable-mbedtls`, with no `--enable-gpl` or `--enable-nonfree`; this is the LGPLv3 configuration, not an assertion that FFmpeg is always LGPL regardless of build options. libvpx has a BSD-style license and patent grant; Mbed TLS has the Apache 2.0 license. All original legal texts are preserved in the kit, including GPL/LGPL texts distributed in FFmpeg's source archive and nested libvpx third-party notices. No standalone NOTICE file was found in the four archives; embedded copyright notices remain intact in the original source archives. No synthetic upstream NOTICE has been invented.

**Recipient delivery requirement:** before distributing Sweefl 0.3.2 with GPLv3 Nextlib, the release lead must accompany the binary with the complete corresponding application source, including Sweefl's actual modifications, this kit, build/configuration scripts and required interface definitions, under appropriate GPL-compatible distribution terms. Include required installation information where applicable. A link to upstream Nextlib alone, or this dependency kit without the application's corresponding source, is not sufficient. Preserve copyright notices and license terms, identify modifications, and ensure recipients can rebuild/modify the covered work. Do not restrict LGPL-required modification/relinking rights or reverse engineering for debugging those modifications. Confirm the distribution method against the actual GPLv3/LGPLv3 terms; this document is not a blanket certification of legal compliance.

The owner explicitly approved GPLv3 local delivery of the combined 0.3.2 application with full corresponding source on 2026-10-19. The root LICENSE contains GPLv3; this approval does not authorize uploading the owner's code to a public hosting service. The complete-app source bundle remains a required separate release step, not something this dependency-kit task claims to have completed. Third-party tools and system-library exceptions should be assessed under the applicable license terms rather than assumed to exempt the app source.

## Exact retained source archives

| Input | Requested upstream URL | SHA-256 of retained archive |
| --- | --- | --- |
| Nextlib exact commit | [GitHub commit archive](https://github.com/anilbeesetti/nextlib/archive/1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060.tar.gz) | `0e03c22d59dccd996d9c3ac1c5ffaa26ac85de49b2f0af07c06fee2846341343` |
| FFmpeg 6.0 | [FFmpeg release archive](https://ffmpeg.org/releases/ffmpeg-6.0.tar.gz) | `f4ccf961403752c93961927715f524576d1f4dd02cd76d8c76aed3bbe6686656` |
| libvpx v1.13.0 | [GitHub tag archive](https://github.com/webmproject/libvpx/archive/refs/tags/v1.13.0.tar.gz) | `cb2a393c9c1fae7aba76b950bb0ad393ba105409fe1a147ccd61b0aaa1501066` |
| Mbed TLS v3.4.1 | [GitHub tag archive](https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v3.4.1.tar.gz) | `a420fcf7103e54e775c383e3751729b8fb2dcd087f6165befd13f28315f754f5` |

These downloads all succeeded. Keep the original compressed bytes, not only extracted files. See [provenance](<../third-party/nextlib/download-provenance.json>) and [SHA256SUMS](<../third-party/nextlib/SHA256SUMS>) for the retained file paths, sizes and integrity manifest. The source script used release/tag tar inputs **without original cryptographic hashes**. The new manifest documents the inputs obtained for this kit; it does not retrospectively establish which bytes built the published AAR. No signature verification was performed.

## Build and modification information

Detailed commands are in the [kit README](<../third-party/nextlib/README.md>). The upstream versions are JDK 17, SDK platform 36, NDK 25.2.9519653, CMake 3.22.1, AGP 8.10.1, Kotlin 2.1.21, Media3 1.8.0 and Gradle 8.14.2. The script builds native API 21 for four ABIs; Sweefl targets arm64-v8a. The exact Gradle files, JNI source, wrapper, workflow and native build script are retained along with the full Nextlib archive.

Use FFmpeg 6.0 source with the **Nextlib-specific integration/build scripts** from the exact commit, not a current FFmpeg release or an unrelated Windows decoder tree. No `.patch` files or patch-application commands were found at this pin. The kit therefore preserves pristine upstream FFmpeg 6.0 plus Nextlib's own source/build configuration; it does not invent or claim missing patches. If a later binary uses additional patched sources, those exact patches and resulting build recipe must be added before redistribution.

No source build, app build or device operation was performed for this kit. The inspected dependency AAR has SHA-256 `f18b041a8fe823f86b23af6270f1ddd617036dff5753edf7b2a21ed6d806ca11`. No claim is made that the downloaded sources reproduce that AAR, that the supplied commit is independently attested as its publisher's build commit, or that a rebuilt library is byte-identical. Original publisher build logs, original input hashes, fully pinned host toolchain, binary/source attestation and a verified rebuild are **not available in this kit**. It is not an offline SDK/Gradle/Maven distribution. Those limitations must not be hidden when describing source completeness or provenance.

## Sweefl native-window correction

The current application uses a local Nextlib AAR with an arm64 JNI-only correction. The original Java classes and all FFmpeg shared libraries are byte-identical to the inspected upstream AAR; only `jni/arm64-v8a/libmedia3ext.so` changes. The patched AAR SHA-256 is `bcd338efafe7186f20a75885a06b0a3d9c3d7a5f459997628820ffd300dde97e`.

The [canonical patch](<../third-party/nextlib/native-window.patch>) removes a cached JNI local Surface reference, reacquires the current native window on each render, balances native references, and retries an abandoned-window lock once without falsely reporting a dropped paused frame as presented. The [rebuild script](<../third-party/nextlib/rebuild_native_adapter.py>) and [build instructions](<../third-party/nextlib/NATIVE-WINDOW-PATCH.md>) preserve the pristine source kit and verify that all other AAR members remain unchanged. This native adapter was built with NDK r25c; this does not establish a reproducible source build of the upstream FFmpeg binaries. Device regression results are documented separately.

## Playback boundary

This inclusion is Android Nextlib/FFmpeg. Windows LAV Filters, MPC-HC/MPC-BE and their Windows playback cores are **not included or reused** by this kit. Their functionality must not be attributed to this Android decoder path.

At this pin, Nextlib's [native video renderer](<../third-party/nextlib/pinned-build/nextlib/media3ext/src/main/cpp/ffvideo.cpp#L192-L210>) uses an Android YV12 native window and swscale conversion to `AV_PIX_FMT_YUV420P`: an 8-bit planar output path. It is untagged by this renderer (no explicit output dataspace/color tagging), and no HDR tone-mapping stage is implemented. This is not HDR passthrough, color-managed HDR presentation or HDR-to-SDR tone mapping. Other application hardware-decoder paths are outside that statement. Source inspection alone is not a device test or a guarantee of accurate display for every video format.
