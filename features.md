# Features

Mini Audiobookshelf is a GTK4/libadwaita client for one Audiobookshelf server (2.26.0 or newer). It signs you in, streams audio through GStreamer, and writes listening position back through the playback session so progress follows you to other clients.

This catalog is what the code does. Two plans in `docs/plans/` defined the original product. Later commits changed some of that. Drift is listed at the end.

---

## Connect and sign-in

**One server at a time.** You type a base URL. The app probes `/status` before any credential field appears. Servers older than 2.26.0 are refused with a stated reason.

**Auth paths follow the server.** Username/password is offered only if the server reports local auth. An admin-issued API key is always offered, which is how SSO-only servers stay usable. API keys live in the keyring until an admin revokes them. Password mode stores the rotating refresh token, not the password, and refreshes about 60 seconds before the expiry the server reports. If the response has no expiry, it falls back to a 10-minute lifetime.

**Plain HTTP is not silent.** Before any secret is typed, the app warns that the password, token, audio, and cover art can be read or altered in transit. You have to check "I understand the risk." The scheme is never upgraded for you.

**Failed TLS can be pinned, not ignored.** You see the SHA-256 fingerprint and can pin that exact certificate for that origin. Verification is never turned off. A later certificate change is refused rather than re-prompted. Pins live in GSettings as PEM.

**Relaunch restores the session.** The last server URL is in GSettings. The long-lived secret is in the system keyring (Secret Service on Linux, login keychain on macOS). If the keyring is missing or a write fails, the credential stays in memory for this run and you get a toast: "You'll need to sign in again next time." Nothing is written to disk in plaintext.

**Sign-out.** A header-bar button on the library and detail screens. It logs out on the server, deletes the local secret, and returns to Connect. API-key sign-out warns that the key stays valid until an admin revokes it. Sign-out does not kill in-progress audio. If the mini player is up, the library is restored first so the dialog has a parent.

**429 on login or refresh** stops automatic retry and shows a rate-limit message. The copy mentions a countdown. There is no live countdown widget.

**Core dumps are disabled** at process start (`RLIMIT_CORE` hard zero) so credentials in memory are not written to disk on crash.

---

## Libraries and browsing

**Library dropdown.** Book and podcast libraries are both listed. The last selected library id is remembered. Switching libraries clears search and reloads that library's home.

**Book home is continue listening.** Up to 25 in-progress books from the selected library, sorted by progress. The original `/api/me/items-in-progress` path was abandoned because it expanded every in-progress item first (about 5.3s on a real library). The current filter query returns in tens of milliseconds. There is no full "all books" browse and no infinite scroll.

**Podcast home is latest episodes.** Up to 50 recent episodes for the selected podcast library.

**Search.** Debounced 250ms as you type, cancels the previous request. Empty query returns to home. Hits are books or podcasts depending on the selected library.

**Cover grid.** Recycled `Gtk.GridView` tiles. Covers load only for visible tiles; unbind cancels in-flight fetches. Square 176px art, title, author. Numbered series get a `#N` badge. Hover reveals a play button. Items with no playable audio show that instead of play. Episode tiles can open the show via the author line.

**Cover cache.** Shared in-memory cache (300 textures). JPEG from the server, center-cropped to a square. Used by the grid, detail, player bar, and mini player.

**Errors.** Failed library loads show a status page with Retry.

---

## Item detail

**Books.** Cover, title, author, Play / Resume / Start over from server progress. Finished items offer Start over rather than resuming at the end. Unstarted books hide the progress area. Started books show a bar and a line like `25% · 1h 30m left`. Chapter list with timestamps; hidden when there are no chapters. Activating a chapter starts playback and seeks there. Transcode-only items are refused with an explanation.

**Podcast shows.** Play starts the first unfinished episode in the current sort. Newest-first / oldest-first. Episode rows (plain text, so titles with `&` still display) with progress or duration, plus an add-to-queue button. Clicking a row plays that episode.

**Podcast episodes** (opened from recent-episodes tiles). Episode title, show as subtitle, Play/Resume, and a queue button. No chapter list.

Progress on detail requires `include=progress`. The parser accepts both `userMediaProgress` (item payload) and `mediaProgress` (list payload). Series can come from a structured array or from the continue-listening `seriesName` string (`Name, #N`).

---

## Playback

**Direct play only.** Opening an item POSTs `/api/items/{id}/play` (or `.../play/{episodeId}`) with `forceDirectPlay: true`. If the server can only transcode, the session is closed and playback is refused. Audio is never downloaded.

**Unauthenticated stream.** Audio comes from `GET /public/session/{sessionId}/track/{index}`. Account credentials never appear in URIs. The session id is the media credential: not logged, not in MPRIS metadata, not in user-facing errors.

**Whole-book clock.** Multi-file books are one timeline. Seek, skip, chapters, and progress all use that clock. Skip is ±30 seconds. Previous chapter restarts the current chapter if you are more than 3 seconds in.

**Gapless multi-file.** The next track is queued in `about-to-finish`. Position reporting is suppressed across the URI change so the seek bar and stall detector do not jump backward.

**Speed.** 0.5×–3.0× via presets (0.5, 0.75, 1, 1.25, 1.5, 2, 2.5, 3). Pitch is held with GStreamer's `scaletempo`. The last rate is saved in GSettings and reapplied across tracks and sessions.

**Resume.** User-initiated play uses the server's session position, not a local cache. Mid-playback session re-open keeps the local position (see sync).

**Pipeline.** `playbin3`, audio-only. Bus errors are classified (HTTP status on `souphttpsrc`). Silent hung transfers are a separate path: if position does not advance for 5 seconds while intended-playing, the engine retries twice, then surfaces a stall with Retry. Ctrl+R retries. Position is preserved.

---

## Progress sync

Writes go only through `POST /api/session/{id}/sync`. The `PATCH /api/me/progress` path is unused, because mixing the two wedges items in Continue Listening.

Cadence: first write after 20 seconds of accumulated play, then every 10 seconds, flush on pause/seek/stop, coalesced to at most one forced flush per second. Listening time is reported as a delta. Sub-second position moves are skipped.

If a sync returns 404 (server dropped the session), the client re-opens once, re-points the pipeline, and seeks to the local position. If the new session is more than 60 seconds ahead, a dialog asks whether to keep this device or take the other. Behind is overwritten without asking.

If progress cannot be saved, audio keeps playing. "Progress isn't being saved" stays on the player bar and the mini player until a write succeeds.

Quit hides windows, pauses, flushes sync, closes the session, and waits up to 4 seconds. Closing the library window or Ctrl+Q while playing asks for confirmation.

---

## Player bar

Shown once an item is loaded. Cover, current chapter title, play/pause, skip ±30s, previous/next chapter (hidden when there are no chapters), chapters popover with the current chapter highlighted, speed menu, seek scale with elapsed/duration, mini-player button.

Below 500sp, chapter skip, skip, chapters, and speed hide. Play/pause, queue, mini player, and the seek bar stay.

Stall and unsaved-progress text plus Retry live here.

---

## Podcast queue

Episodes only. Add from a show's episode rows or an episode detail page. Duplicates and the currently playing episode are blocked.

The player-bar queue popover ("Up next") lists title and show. You can reorder, remove, skip to a row (plays immediately), or clear. When the current item finishes, the next queued episode starts. The queue button appears while a podcast is playing or the queue is non-empty. Sign-out and reconnect clear it.

There is no book queue.

---

## Mini player

A separate non-resizable `Adw.Window`: 280px square cover, transport strip below. Enter from the player-bar button. The library window is hidden, not destroyed. Restore (strip button, titlebar close, or Escape) presents the same library window with its previous view.

Dock click, second activation, and MPRIS Raise while mini is up present the mini player, not a second library.

Strip: skip back, play/pause, skip forward, restore. No chapters, no speed. Progress on the cover is display-only (the plan asked for overlay seek; a later fix dropped seeking on that bar). Stall / unsaved-progress / Retry stay visible. Window title follows the playing title. Not always-on-top; stacking is the compositor's job.

Idle-inhibit is rebound to whichever window is mapped.

---

## Desktop integration

**GActions** (one implementation for UI, keyboard, and MPRIS):

| Action | Shortcut |
|---|---|
| Play/pause | Space |
| Skip back / forward | Left / Right |
| Previous / next chapter | `[` / `]` |
| Retry | Ctrl+R |
| Quit | Ctrl+Q |
| Seek, set speed, stop | no default accel |

Menu: Keyboard Shortcuts (`Adw.ShortcutsDialog`) and Quit. Sign-out is the header button, not that menu.

**MPRIS2** on the session bus: play/pause/stop, seek, rate 0.5–3, next/previous mapped to chapters, Raise, Quit (with the playing confirmation). Hyphens in item ids are escaped in D-Bus object paths. No session bus (typical macOS Homebrew) means MPRIS is a no-op.

**Idle inhibit** while intended-playing: idle and suspend.

**Window geometry** (size, maximized) persisted. Mini-player size/position is not.

**Icons.** Full-color bookshelf mark and a 16px symbolic. Desktop file categories include Audio/Player; keywords include audiobook and podcast.

---

## Platform and packaging

**Linux.** Meson/ninja, Vala, GTK 4.22+, libadwaita 1.9+, GStreamer with good plugins (`scaletempo`) and **gst-libav** (m4b/AAC). Local run target sets `GSETTINGS_SCHEMA_DIR`. Arch `PKGBUILD` plus a GitHub Actions job that attaches a `.pkg.tar.zst` to `v*` tags. GPL-3.0-or-later.

**macOS.** Homebrew vapidirs, Adwaita icon search path, GLib keyfile settings backend (`~/.config/glib-2.0/settings/keyfile`), login keychain for tokens.

**Not shipped.** Flatpak, AppStream metainfo, translations (`po/` deferred; strings are marked), Flathub.

---

## Developer-only

Env vars `MINI_ABS_URL`, `MINI_ABS_TOKEN`, `MINI_ABS_ITEM`, optional `MINI_ABS_PIN` skip the connect UI and start playback. That is a hard-coded test path, not a user feature.

Core library (models, HTTP, credentials, timeline, stall/sync policy, queue, view-logic) is GTK-free and covered by `meson test`. The HTTP client injects transport so tests do not need a live server. Cover URLs request JPEG so the image loader stays narrow.

---

## How the plans drifted

The first plan (`docs/plans/2026-08-26-001-feat-audiobookshelf-gtk-client-plan.md`) is still the spine: connect, stream, sync, MPRIS, GActions, keyring, TLS pin, HTTP warning, transcode refusal, whole-book time, gapless, stall detection.

What later commits changed:

- **Podcasts.** Originally filtered out. Now first-class: library switch, recent episodes, show pages, episode play, queue.
- **Browse.** Originally a sidebar plus a paged full library. Now a dropdown and a continue-listening (or recent-episodes) grid. `fetch_library_items` still exists in the API client and is unused by the UI.
- **Continue listening across libraries** (R31). Originally one list across all book libraries. Now it is per selected library.
- **Mini overlay seek.** Plan 002 required seeking on the cover. Shipped as a non-interactive progress bar.

Still deferred, as the plans said: offline download, multiple server profiles, sleep timer, bookmarks, per-book speed, disk cover cache, volume UI, ebooks, library admin, servers older than 2.26.0.
