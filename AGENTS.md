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
- Per-session DP attach needs `pm grant com.nativeeq.equalizer android.permission.DUMP`.
