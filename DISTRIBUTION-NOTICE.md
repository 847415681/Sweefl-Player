# Sweefl Player 0.3.2 local distribution

The project owner approved local delivery of this combined release under GNU GPL version 3 on 2026-10-19, accompanied by corresponding application and dependency source. See [LICENSE](LICENSE). On 2026-10-23 the owner subsequently requested uploading this project and development documentation to https://github.com/847415681/Sweefl-Player. Private signing keys, local configuration, raw device evidence and user preferences are excluded from that upload.

Copyright notices and separately licensed third-party material retain their original terms. Android/Media3/Compose and dependency notices must be preserved. Nextlib is GPLv3; the pinned FFmpeg configuration is LGPLv3, libvpx BSD-style, and Mbed TLS Apache-2.0. See [source disclosure](docs/FFMPEG-SOURCE-0.3.2.md) and [dependency kit](third-party/nextlib/README.md).

The 0.3.2 modifications include playback UI and glass rendering, bounded preview scheduling/cache, subtitle time synchronization, Android decoder selection, GPU SDR processing, output-evidence tracking, lifecycle recovery and associated tests. Source archives retain complete dependency copyright notices. No warranty is provided; GPL terms govern the covered combination.

Build instructions: [development guide](docs/DEVELOPMENT.md), Gradle wrapper and version catalog. Run `verify :app:assembleRelease` with JDK17 and Android SDK35. The included helper can use the author's isolated local toolchain; its absolute local paths are not a prerequisite to ordinary Gradle builds. SDK/NDK/JDK/Gradle dependencies may require separate downloads.

The retained dependency source inputs and their hashes are documented; this does not assert that the publisher's Nextlib AAR is bit-for-bit reproducible from the retained archives. No original publisher build attestation is available. Preserve this limitation in further distribution.

Use a development signing key of your own when modifying the application; Android requires a matching signer for in-place updates, or installation under a distinct application id. Uninstalling an existing app can delete its local data; back up/export applicable data first. Do not distribute private production signing keys.
