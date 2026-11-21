# Features

Mini Audiobookshelf for Android is a Kotlin / Jetpack Compose client for one Audiobookshelf server (2.26.0 or newer). It signs you in, streams audio through Media3 / ExoPlayer, downloads chosen titles for offline play, and writes listening position back through the playback session so progress follows you to other clients.

This is the Android sibling of the GTK4 desktop app. The product contract is shared — one server, direct play, session-only progress writes, whole-book time — but this catalog is what the *Android* code does. It replaces the previous `features.md`, which still described the GTK client (GStreamer, GSettings, the system keyring, and MPRIS).

This catalog is what the code does. Eight plans in `docs/plans/` defined the product (001 scaffold through 008 connect rework). Later commits changed some of that. Drift is listed at the end.

---

## Connect and sign-in

**One server at a time.** You type a base URL. There are no multiple server profiles.

**Connect is two steps, not one growing form.** Step 1 is the server: you type an address — a scheme-less address like `abs.lan:13378` is accepted — and press Continue (or the keyboard Go action). The app probes `/status` before any credential field appears. Servers older than 2.26.0 are refused with a stated reason. Step 2 is the credential: the screen shows the resolved origin and server version with a Change action (system back does the same), and sign-in can only go to the server that step was created for. A rejected password keeps the form on screen so you can fix a typo without re-probing.

**Auth paths follow the server.** Password and API key are offered as a visible choice when the server supports both. Username/password is offered only if the server reports local auth. An admin-issued API key is always offered, which is how SSO-only servers stay usable; an SSO-only server shows a hint that an admin can issue a key in the server UI. Coming back after sign-out or an expired session lands on step 2 with your last username and auth mode for that origin filled in.

**Password mode stores the rotating refresh token, not the password.** It refreshes about 60 seconds before the expiry the server reports. If the response has no expiry, it falls back to a 10-minute lifetime. API keys live in encrypted storage until an admin revokes them.

**Plain HTTP is not silent.** Before any secret is typed, the app warns that the password, token, audio, and cover art can be read or altered in transit. You have to check "I understand the risk" — per origin, remembered in settings. The scheme is never upgraded for you, and the HTTP interceptor refuses a cleartext origin that was not acknowledged, even though the platform network config permits cleartext.

**Failed TLS can be pinned, not ignored.** You see the SHA-256 fingerprint and can pin that exact certificate for that origin. Verification is never turned off. Pins live in DataStore settings as PEM and are honoured by the API client, the cover loader, and downloads.

**Relaunch restores the session.** The last server URL is in DataStore. The long-lived secret is in a Keystore-backed encrypted store (with a one-time migration from the legacy EncryptedSharedPreferences store). If secure storage is unavailable or a write fails, the credential stays in memory for this run and you get a notice: "You'll need to sign in again next time." Nothing is written to disk in plaintext.

**Sign-out.** A Sign out button in the app bar on the library, detail, and downloads screens. It logs out on the server, deletes the local secret, clears the episode queue and the last-played record, and returns to Connect. API-key sign-out warns that the key stays valid until an admin revokes it. If you have downloads, sign-out asks whether to keep or delete them — keep is the default, and kept downloads still need a sign-in to play.

**429 on login or refresh** stops automatic retry and shows a rate-limit message. There is no live countdown widget.

**Secrets stay out of logs.** Authorization, cookie, and refresh-token headers and session URLs are redacted in the HTTP client's log formatting. The playback session id is never used as a media id, never put in notification or Android Auto metadata, and never appears in a URI shown to the user.

---

## Libraries and browsing

**Library switching is in the app bar.** Tapping the library name opens a bottom sheet listing book and podcast libraries (with a Books / Podcasts label per row). The switcher only appears when there is more than one library. The last selected library id is remembered. Switching libraries clears search and reloads that library's home.

**Book home is continue listening.** Up to 25 in-progress books from the selected library. There is no full "all books" browse and no infinite scroll in the UI — `fetchLibraryItems` exists in the API client and is unused by the UI.

**Podcast home is latest episodes.** Up to 50 recent episodes for the selected podcast library.

**Search is behind a floating action button.** It opens a search top bar, is debounced 250ms as you type, and cancels the previous request. Empty query returns to home. Hits are books or podcasts depending on the selected library.

**Cover grid.** A `LazyVerticalGrid` of square cover tiles (Coil, centre-cropped), title, and author. Numbered series get a `#N` badge. Items with no playable audio say so instead of offering play. There is no hover play button on Android — tapping a tile opens detail.

**Cover loading goes through the app's own client.** Coil is configured with the session's OkHttp client, so covers carry authentication and honour a pinned certificate instead of failing or leaking a token in a URL.

**Errors.** Failed library loads show a status page with Retry. An expired session returns you to sign-in instead of showing a raw error.

---

## Item detail

**Books.** Cover, title, author, and Play / Resume / Start over from server progress. Finished items offer Start over rather than resuming at the end. Unstarted books hide the progress area. Started books show a bar and a line like `25% · 1h 30m left`. Chapter list with timestamps; hidden when there are no chapters. Tapping a chapter starts playback and seeks there. Items with no playable audio are explained, not played.

**Podcast shows.** Play starts the first unfinished episode. Newest-first / oldest-first sort toggle. Episode rows with progress or duration, plus an add-to-queue button. Tapping a row opens that episode.

**Podcast episodes** (opened from recent-episodes tiles or a show). Episode title, show as subtitle, Play / Resume / Start over, a queue button, and download controls. No chapter list.

**Offline fallback.** If fetching detail fails and a complete download with a sidecar exists for that item, detail is built from the sidecar — title, author, tracks, chapters, and duration — instead of an error.

Progress on detail requires `include=progress`. The parser accepts both `userMediaProgress` (item payload) and `mediaProgress` (list payload). Series can come from a structured array or from the continue-listening `seriesName` string (`Name, #N`).

---

## Playback

**Direct play only.** Opening an item POSTs `/api/items/{id}/play` (or `.../play/{episodeId}`) with `forceDirectPlay: true`. If the server can only transcode, the session is closed and playback is refused with an explanation. Streaming audio is never downloaded as a side effect; downloads are a separate, opt-in path.

**Unauthenticated stream.** Audio comes from `GET /public/session/{sessionId}/track/{index}`. Account credentials never appear in URIs.

**Whole-book clock.** Multi-file books are one timeline. Seek, skip, chapters, and progress all use that clock, in the app, in the notification, and on Android Auto — controllers see one flattened media item per book or episode, never one row per file. Skip in the app is ±30 seconds. Previous chapter restarts the current chapter if you are more than 3 seconds in.

**Gapless multi-file.** Tracks are one ExoPlayer playlist under the flattened timeline. Position reporting is suppressed across a track change so the seek bar and stall detector do not jump backward.

**Speed.** 0.5×–3.0× via presets (0.5, 0.75, 1, 1.25, 1.5, 2, 2.5, 3). The rate is a single global setting, saved in DataStore and reapplied across tracks and sessions. There is no per-book speed.

**Resume.** User-initiated play uses the server's session position, not a local cache — except for downloads, where the last known server position is cached on connect so offline resume matches it. Mid-playback session re-open keeps the local position (see sync).

**Background play.** Playback runs in a Media3 `MediaLibraryService` foreground service (`mediaPlayback` type), so audio continues after you leave the app or the activity is killed. The app requests the notifications permission on Android 13+ so the media notification can appear.

**Pipeline.** Media3 ExoPlayer, audio-only. If position does not advance for 5 seconds while intended-playing, the stall policy retries twice (with a growing delay), then surfaces "Playback stalled" with Retry. Playback errors are classified, including HTTP status, and offer Retry where a retry can help.

---

## Progress sync

Writes go only through `POST /api/session/{id}/sync`. The `PATCH /api/me/progress` path is unused, because mixing the two wedges items in Continue Listening.

Cadence: first write after 20 seconds of accumulated play, then every 10 seconds, flush on pause / seek / stop, coalesced to at most one forced flush per second. Listening time is reported as a delta. Position moves under a second are skipped.

If a sync returns 404 (server dropped the session), the client re-opens once, re-points the player, and seeks to the local position. If the new session is more than 60 seconds ahead, a dialog asks whether to keep this device or take the other. Behind is overwritten without asking. Android Auto cannot show that dialog, so a conflict raised from the car keeps the local position.

If progress cannot be saved, audio keeps playing. "Progress isn't being saved" stays on the now-playing bar and the full player until a write succeeds.

---

## Player UI

**Now-playing bar.** Shown at the bottom once an item is loaded. Cover, title, chapter or author line, play/pause, and — for podcasts or a non-empty queue — a queue button. Tapping it opens the full player. Stall and unsaved-progress messages with Retry live here too.

**Full player.** Cover, title, current chapter title, seek slider with elapsed / duration, previous / next chapter (hidden when there are no chapters), skip −30s / +30s, play/pause, a speed menu showing the current rate, and a tappable chapter list with the current chapter highlighted. A queue button opens Up next when a podcast is playing or the queue is non-empty.

**Media notification.** An ongoing media-session notification, not a "starting playback" placeholder. The compact view shows play/pause and ±10 second skip — deliberately not chapter next/previous, which the tray is too easy to hit by accident. Session media buttons also expose ±10s skip and a speed button that cycles to the next preset.

There is no mini player on Android. The now-playing bar is that surface.

---

## Podcast queue

Episodes only. Add from a show's episode rows or an episode detail page. Duplicates and the currently playing episode are blocked, with a notice ("Already in Up next", "That's already playing").

The Up next sheet lists episode title and show. You can reorder (move up / down), remove, tap a row to play it immediately, or clear the whole queue. When the current episode finishes, the next queued episode starts automatically. Sign-out and reconnect clear it.

There is no book queue.

---

## Downloads and offline play

**Opt-in, per item.** Nothing is downloaded because it appeared in Continue Listening. Download controls appear on book and episode detail only if your server user has the download permission, and transcode-only / no-audio items cannot be downloaded.

**Transfer.** Audio is fetched from the authenticated library-item file endpoints with the token in a header — never from the `/public/session/...` URLs, which expire with the session. A multi-file book is one WorkManager chain: it is playable only when every file plus its sidecar are present. Partial books are never played as a mix of local and streamed tracks — a play is all-local or all-streamed. Downloads run in the background with a progress notification, and can be paused, cancelled, retried, and deleted.

**Wi-Fi by default.** Download work requires an unmetered network unless you turn on "Allow downloads on cellular" in Downloads.

**Storage.** Files live in app-specific external storage (`getExternalFilesDir`, `abs-downloads/`), so uninstall removes them and other apps do not index them as music. The catalog is a Room database. A sidecar JSON written last — track order, durations, offsets, chapters, file sizes, cover — is what makes a download "complete"; on play, a size mismatch marks the download failed and refuses offline play. The cover JPEG is stored next to the audio, so the Downloads list, detail, and player have art offline. The Downloads list is kept in the order items were added, with cover thumbnails, title, author, status / progress, and delete.

**Online play of a download** still opens a live server session and syncs through it; ExoPlayer just reads the local files instead of streaming.

**Offline play.** With no reachable server and a complete download, play works anyway without calling `/play`. Position and listening time accumulate in a local playback session on disk (UUIDv4 ids). When connectivity returns — watched via a network callback, and also flushed on sign-in — sessions are uploaded in a batch through `POST /api/session/local-all`. Never `PATCH /api/me/progress`.

**Downloads screen** is reachable from the app bar whether or not the network is up. Empty downloads plus no network is an honest empty state ("Downloads you keep stay on this phone and play without a network"), not a retry spinner.

---

## Android Auto

**Projected Android Auto only**, on the phone app, in the same process. No Automotive OS module and no Car App Library templates. Auto is another Media3 controller for the same engine and sync path — no second ExoPlayer, no second progress writer.

**Browse tree.** Three root tabs: **Continue** (or **Episodes** for a podcast library — the last selected library's phone home), **Downloads** (complete downloads only), and **Libraries** (selecting one writes the same library setting as the phone dropdown). No full-library pager, no collections / authors / series pages, and no queue editor in the car.

**One item per book.** Duration, position, seek, and skip are whole-book time. Next / previous are chapter skip when chapters exist and are never "next file." Media ids are library item ids (plus episode id) — never a playback session id or a stream URL.

**Voice search.** "Play {title} on Mini Audiobookshelf" searches and starts the best playable hit. An empty result is an honest miss, not a crash.

**Resumption.** The last played item / episode id, title, and author are stored in settings. Playback resumption replays that item, taking position from the server session — or from the local session for an offline download. A "last played" entry is also what the car dock opens.

**Signed-out Auto still binds.** Browse is empty and a playable-looking sign-in notice carries a resolution intent that opens the phone app to sign in. The service does not kill itself for lack of a credential.

**Controller allowlist.** The library service is exported, so `onGetSession` only accepts trusted controllers, Auto / Automotive companions, and an allowlist (this app, Android Auto, Assistant, Bluetooth, system UI). Unknown packages get no session.

**Cover art is bytes, not a URL.** Artwork is loaded through the pinned, authenticated OkHttp client and handed to the car as data, because the head unit could not fetch an authenticated `artworkUri` itself.

**Car disconnect pauses.** When projection ends or the car connection goes idle, playback pauses and a follow-up "play" from the head unit does not silently resume it on the phone; resuming from the phone clears that block. Speed can be cycled from the car's session buttons. There is no sleep timer or chapter list in the car.

---

## Platform and packaging

**Android 8.0+ (minSdk 26), target / compile SDK 36.** Kotlin, Jetpack Compose, Material 3, following system light / dark. Two Gradle modules: `:app` (Android, Compose, Media3, Room, WorkManager, DataStore) and `:core` (pure Kotlin JVM — models, HTTP, credentials, timeline, stall / sync policy, queue, download policy, Auto browse tree, view logic — with no Android types, so policy tests run with JUnit on the JVM). No Hilt; `MiniAbsApplication` is the composition root. One process only, so WorkManager and the media service can never race over the refresh token.

**Build** needs JDK 25 and the Android SDK with platform 36:

```
./gradlew :app:assembleDebug :core:test
```

**Releases.** CI on push / pull request runs `:core:test` and assembles the debug APK. A `v*` tag builds a signed release APK (when the release keystore secrets are configured) and publishes it to a GitHub Release as `abs-mini-android-<tag>.apk`.

**Icons.** The bookshelf mark from the GTK app, as an adaptive launcher icon, with its symbolic version as the playback notification small icon.

**Not shipped.** Play Store or F-Droid listing, translations (strings live in `res/values/strings.xml` in English only), Android Automotive OS, Chromecast / Cast, and a home-screen widget.

---

## Developer-only

There is no user-facing settings screen. The only settings UI is the cellular-downloads toggle in Downloads; everything else (server URL, library id, playback rate, TLS pins, HTTP acknowledgements, last-played) is written by the flows above and stored in DataStore.

Core library tests use JSON fixtures for libraries, items, search, recent episodes, and play sessions, and the HTTP client injects its transport so tests do not need a live server. Cover URLs request JPEG so the image loader stays narrow.

---

## Still deferred

Named as deferred in the plans and still not in the code:

- Sleep timer
- Bookmarks
- Per-book playback speed (and custom skip intervals — in-app ±30s and notification ±10s are fixed)
- Multiple server profiles
- Full-library browse in the UI (paged "all books", sort, and filters)
- Transcoding / HLS fallback for items that cannot direct-play
- Smart downloads (auto-download next episodes or next in a series), an SD-card / storage-location picker, and storage totals for downloads
- Ebooks, playlists and collections, and library administration
- Servers older than 2.26.0

## How the plans drifted

The eight plans are still the spine: scaffold (001), connect and auth (002), browse (003), playback and sync (004), player UI and queue (005), downloads and offline (006), Android Auto (007), and the connect rework (008).

What later commits changed:

- **Connect (008 superseded part of 002).** One scrolling form became the Server → Trust → Credentials step sequence. A rejected password no longer erases the form, password vs. API key is an explicit choice instead of a hidden precedence rule, and scheme-less server addresses parse properly.
- **Secret storage.** EncryptedSharedPreferences was replaced by a Keystore-backed store, with a one-time migration of the legacy store, after password sessions failed to survive process death.
- **Notification transport.** The compact notification gained ±10s skip and lost chapter next/previous; the full notification and session buttons expose ±10s and cycling speed.
- **Android Auto hardening.** Several post-007 commits fixed signed-out browse, voice search, offline cover art, and Downloads in the car; made Auto pause on disconnect / idle instead of resuming on the phone; and restored the last played title from the car dock.
- **Offline fixes.** Downloaded files are read from disk rather than through OkHttp, and the server position for downloads is cached on connect so offline resume starts where you left off.
- **Downloads list.** Covers were added so titles are recognisable without opening them, rows were tightened to two lines, and the list now stays in the order items were added.
- **Toolchain.** Gradle 9.7.1, AGP 9.4.0, and JDK 25, and a signed-APK release workflow on `v*` tags — the scaffold plan had put signed releases out of scope.
