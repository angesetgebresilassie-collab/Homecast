# Homecast

Android app that finds YouTube videos and casts them (H.264 only) to a GS 9000 Ghost satellite receiver over UPnP/DLNA.

## How it works

- **720p (seeking works):** `RangeProxyServer` passes the receiver's HTTP range requests straight through to YouTube's muxed H.264 stream (itag 22, fallback 18).
- **1080p (no seeking):** `TsStreamServer` runs on-phone FFmpeg with `-c copy` to merge video-only avc1 and AAC into live MPEG-TS.
- The free FFmpeg build has no TLS, so FFmpeg reads loopback http proxies that fetch from YouTube with OkHttp.
- Codec is locked to H.264 (avc1). No VP9, AV1 or HEVC fallback.

## Status

Early scaffold. Not yet done: UPnP discovery and cast (jupnp), foreground service, search UI.

## Build

CI builds a debug APK on every push (see Actions). Locally: `gradle assembleDebug` (Gradle 8.9+, JDK 17).
