# Mini Audiobookshelf for Android

An Android client for [Audiobookshelf](https://www.audiobookshelf.org/). Same idea as the GTK4 desktop app: one server, sign-in, browse, stream, and sync progress.

Requires an Audiobookshelf server **2.26.0 or newer**.

This checkout is the scaffold. The app launches an empty Material 3 window. Sign-in, browse, playback, and downloads are later phases. The plans live in `docs/plans/`.

## Build

JDK 17+ and the Android SDK with platform 36.

```bash
./gradlew :app:assembleDebug :core:test
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

`:core` is a Kotlin JVM library. It has no Android SDK types, so policy tests run with JUnit on the JVM.

## License

GPL-3.0-or-later. See `COPYING`.
