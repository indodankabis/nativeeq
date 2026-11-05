# NativeEQ — source rebuild of eq-v43 (Grok APK artifact)

Rebuilt from `/tmp/eq43/jadx-v2a` (JADX decompile of eq-v43-v2a.apk).
Package `com.nativeeq.equalizer`, pure-Kotlin port, no AndroidX/UI libs
(programmatic views, same as the original).

## Build

```bash
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-9.1.0-bin/*/gradle-9.1.0/bin/gradle)
$GRADLE :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

System gradle (4.4.1) is too old — use the dists copy above or `./gradlew`
(wrapper pinned to 9.1.0). SDK at ~/Android/Sdk (local.properties, gitignored).
Build on ext4, not /storage/emulated.

## Release

Repo: github.com/indodankabis/nativeeq. Keystore: ~/.keystores/ (NEVER commit;
creds in ~/.keystores/nativeeq.env — source it for local release builds).
CI: tag push `v*` → .github/workflows/release.yml → equalizer-release.apk +
equalizer-foss.apk on GitHub Releases. Ship with `ship v1.0.0` (alias in
~/.bash_aliases → ./ship.sh).

Flavors (`dist` dimension): `full` = GitHub build with ML Kit genai-prompt
(Gemini Nano); `foss` = F-Droid build, NanoBackend stubbed, zero non-free deps.
Tasks: `:app:assembleFullRelease`, `:app:assembleFossRelease` (and *Debug).

## AI backend chain (LiteRtLmAcousticEngine.kt)

Honest tiers, each response tagged `[LITERT-LM]`/`[GEMINI-NANO]`/`[TFLITE-ADVISOR]`/`[HEURISTIC]`:
1. LiteRT-LM (litertlm-android:0.18.1) — needs a downloaded .litertlm in
   filesDir/models/. UI: drawer → "GET GEMMA MODEL". Default: Gemma3-1B-IT
   int4 (584 MB, non-gated HF mirror; catalog in ModelManager.kt).
2. ML Kit genai-prompt (Gemini Nano via AICore) — used when
   `checkStatus()==FeatureStatus.AVAILABLE`, zero download.
3. Bundled `assets/eq_optimizer_v16.tflite` — real trained linear
   regressor [1,7]->[1,5] built in the previous session (build_model.py
   in /tmp/eq43). Original code had an inverted null-check so it never ran;
   wired correctly here.
4. Keyword heuristic — last resort, labeled.

Dropped `LiteRtAdvisor` (was dead code in the APK).
`NpuAudioAgent` is the real closed-loop controller — kept as-is; its
corrections now drive the actual DynamicsProcessing chain.

## Other fixes vs the APK

- Visualizer created once (was re-created every morph tick).
- SWEEP CAL button wired (AcousticSweeper was dead code in v43).
- EQ graph drag/bypass listeners wired (were dead hooks in v43).
- Per-session DP attach is event-driven (`AudioPlaybackCallback`, session ids via
  reflective `getSessionId()`/`getPlayerState()` — @SystemApi but sdk-listed).
  Unprivileged apps get anonymized configs (sessionId 0, needs MODIFY_AUDIO_ROUTING),
  so when DUMP is granted (`pm grant com.nativeeq.equalizer android.permission.DUMP`)
  each callback event does one `dumpsys audio` read — no polling.
- DP writes are dirty-tracked per DP; morph targets snap to a 50mB grid, limiter
  to 0.1dB, so steady state issues zero JNI writes.
- `tuning_events` (DB v17) = training dataset: 24-band spectrum + proposed gains +
  kept/reverted label (TuningOutcomeTracker in TuningLogbook.kt). Drawer → Export
  writes `Android/data/com.nativeeq.equalizer/files/nativeeq_training.json`.

## Flagship layer (perf HUD, EQ broadcasts, AutoEq, taste model)

- `PerfStats` counters + `android.os.Trace` sections (`eq.morph`, `eq.fft`,
  `eq.reconcile`) — drawer shows JNI writes/s, morph fps, FFT cost, wakeups,
  session attach latency. Record a Perfetto trace with the `atrace` app
  category to see the sections.
- Session sources, unioned in `reconcileSessions`: `ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION`
  (`SessionControlReceiver`, runtime-registered in the service for implicit
  broadcasts + manifest for targeted ones) ∪ playback callback ∪ DUMP fallback.
- Headphone correction lives in the DP **postEq** stage (32 log bands
  20Hz–20kHz), separate from the user's 5-band preEq. If the device rejects a
  postEq config, the chain falls back to the old preEq-only layout.
  Data: `assets/autoeq_posteq.bin`, regenerate with `tools/build_autoeq.py`
  (AutoEq, MIT — `assets/autoeq_LICENSE.txt`). Route keys come from
  `AudioDeviceCallback`: `bt:<name>`, `usb:<name>`, `wired`, `dev_internal`.
  Per-route manual pin/off in SharedPreferences `headphone_correction`.
- `TasteModel`: ridge regression spectrum(24) → 5 gains on `tuning_events`
  (kept → proposed, reverted → final gains); "My taste" button, tier `personal`.
