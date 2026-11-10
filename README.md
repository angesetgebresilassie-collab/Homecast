# Homecast

Android app that casts YouTube (H.264 only) to a GS 9000 Ghost satellite receiver over UPnP/DLNA. Give it a link and it does the rest.

## Using it

1. Open the app and paste a YouTube link, or share a link to Homecast from the YouTube app.
2. First time only: it searches the Wi-Fi for receivers and remembers the one you pick (it auto-picks if only one is found).
3. It resolves the video, starts the right local server, and tells the receiver to play it. Stop from the app or the notification.

## How it works

- **720p (seeking works):** `RangeProxyServer` passes the receiver's HTTP range requests straight through to YouTube's muxed H.264 stream (itag 22, fallback 18).
- **1080p (no seeking):** `TsStreamServer` runs on-phone FFmpeg with `-c copy` to merge video-only avc1 and AAC into live MPEG-TS.
- Default order is 720p first, then 1080p. The "Prefer 1080p" switch reverses it.
- The free FFmpeg build has no TLS, so FFmpeg reads loopback http proxies that fetch from YouTube with OkHttp.
- Codec is locked to H.264 (avc1). No VP9, AV1 or HEVC fallback.
- `UpnpCaster` does SSDP discovery and AVTransport SOAP calls itself (no UPnP library).
- `CastService` runs everything as a foreground service with a Wi-Fi lock and wake lock.

## Status

First end-to-end version, untested on the receiver. Known risks: dependency versions, receiver DLNA quirks, FFmpeg throttling on 1080p.

## Build

CI builds a debug APK on every push (see Actions). Locally: `gradle assembleDebug` (Gradle 8.9+, JDK 17).
