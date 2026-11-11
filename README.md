# Lyra Music

An open-source, offline-first music player for Android (built for the vivo X100s Pro, works on any Android 10+ phone).

**Design goals**

- **Zero software-side quality loss.** Float PCM pipeline, no app-side resampling or gain, DSP chain fully bypassed unless the user turns something on, bit-perfect USB output on Android 14+.
- **Plays everything.** MP3 / AAC / FLAC / WAV / OGG / Opus / ALAC natively; APE / WavPack / DSD / TTA / WMA via an FFmpeg extension (CI-built).
- **Fixes your tags for you.** Songs missing title / artist / album / cover / lyrics are identified online (AcoustID fingerprint → MusicBrainz, LRCLIB, NetEase, QQ Music) with a review step, and written back to the files so other players see them too.
- **Non-destructive audio editor.** 10-band EQ, pitch/tempo, channel mixing (mono merge, L/R, swap) stored per-song as settings – original files are never modified.
- **Your data is yours.** No analytics, no accounts, HTTPS-only, strict response validation for every online source. Full backup / restore and TuneMyMusic-compatible playlist import/export.

Full requirements: [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md).

## Install

Every push to `main` builds APKs on GitHub Actions. Open **Actions → latest run → Artifacts → `lyra-music-debug`**, download, unzip and install the APK (allow "install unknown apps" for your browser / file manager). Tagged releases (`v*`) also attach APKs to the GitHub Release page.

On vivo / OriginOS, after installing: **Settings → Battery → Background power consumption management → Lyra → Allow background high power consumption**, otherwise OriginOS may kill playback.

## Build locally

Requires JDK 17 and Android SDK 35.

```bash
./gradlew assembleDebug
```

## Tech

Kotlin · Jetpack Compose (Material 3) · Media3 / ExoPlayer · Room · Hilt · Coil · jaudiotagger · kotlinx.serialization · OkHttp/Retrofit

## License

GPL-3.0 – see [LICENSE](LICENSE).
