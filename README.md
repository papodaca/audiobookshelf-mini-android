# Mini Audiobookshelf for Android

An Android client for [Audiobookshelf](https://www.audiobookshelf.org/). Same idea as the GTK4 desktop app: one server, sign-in, browse, stream, and sync progress.

Requires an Audiobookshelf server **2.26.0 or newer**.

As of v0.2.1, this is a working player, not a scaffold — plans 001–008 in `docs/plans/` are shipped:

- **Connect and sign-in** — two-step Server → Credentials flow, password or API key, TLS certificate pinning, and a plain-HTTP warning you must acknowledge per origin. Secrets live in a Keystore-backed store.
- **Browse** — Continue Listening (up to 25 books) or Latest Episodes (up to 50) for the selected library, library switching, and debounced search.
- **Playback** — direct play only through Media3 / ExoPlayer, whole-book timeline, background play in a foreground service, chapters, speed 0.5×–3.0×, and progress synced through the playback session only (no `PATCH /api/me/progress`).
- **Player and podcast queue** — now-playing bar, full player, media notification (±10s skip), and an Up next queue for podcast episodes.
- **Downloads and offline** — opt-in per item, all-local-or-all-streamed play, offline sessions uploaded later via `POST /api/session/local-all`.
- **Android Auto** — projected Auto only, with Continue / Downloads / Libraries browse, voice search, and last-played resumption.

See [`features.md`](features.md) for the full catalog of what the code does — and the still-deferred list (sleep timer, bookmarks, per-book speed, multiple server profiles, full-library browse, transcoding fallback).

## Build

JDK 25 and the Android SDK with platform 36.

```bash
./gradlew :app:assembleDebug :core:test
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

`:core` is a Kotlin JVM library. It has no Android SDK types, so policy tests run with JUnit on the JVM.

A `v*` tag builds a signed release APK (when the release keystore secrets are configured) and publishes it to a GitHub Release.

## License

GPL-3.0-or-later. See `COPYING`.
