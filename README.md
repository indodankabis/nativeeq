# NativeEQ

Zero-bloat Android equalizer. Real `DynamicsProcessing` 5-band DSP chain,
morphing gain engine, room-sweep calibration, on-device AI tuning
(LiteRT-LM / Gemini Nano / TFLite). No AndroidX UI, no ads, no trackers.
arm64-v8a, minSdk 26, targetSdk 35.

## Install (auto-updates via Obtainium)

[![Get it on Obtainium](https://img.shields.io/badge/Get%20it%20on-Obtainium-1a1a2e?logo=android)](https://github.com/indodankabis/nativeeq/releases/latest)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add App → paste `https://github.com/indodankabis/nativeeq`.
3. Grab `equalizer-release.apk`. Every `v*` tag pushes a new release — Obtainium updates automatically.

Direct download: [Releases](https://github.com/indodankabis/nativeeq/releases/latest/download/equalizer-release.apk)

## Verify your APK (sideload trust)

```bash
sha256sum equalizer-release.apk   # compare to SHA256SUMS.txt on the release
apksigner verify --print-certs equalizer-release.apk
```

Release cert SHA-256:

```
a8492e7d516e9f34f50c57b5303d3704d3cff37fceaccd2d99d844525b02f5ee
```

## Build

```bash
./gradlew :app:assembleDebug
```

Release (needs signing env, see `nativeeq.env` layout): source env, then
`./gradlew :app:assembleRelease`.

## Ship a release

```bash
./ship.sh v1.0.0   # bumps version, commits, tags, pushes -> CI builds + publishes
```

## Support

[ko-fi.com/indodankabis](https://ko-fi.com/indodankabis) — keeps the DSP free.

## License

GPL-3.0-or-later
