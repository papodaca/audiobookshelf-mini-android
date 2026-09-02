---
title: Mini Audiobookshelf Android - Browse and detail
type: feat
date: 2026-09-01
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-plan-bootstrap
execution: code
---

# Mini Audiobookshelf Android - Browse and detail

## Goal Capsule

- **Objective:** After sign-in, list the server's book and podcast libraries, show continue listening or latest episodes for the selected library, search, and open item detail. Covers load as JPEG. Play is not wired; that is phase 004. Detail still computes Play / Resume / Start over so 004 has a single place to hang onto.
- **Authority hierarchy:** Requirements win on behavior. This phase does not change auth KTDs. Cover URLs must obey phase 002 R9: no credential in the URI.
- **Execution profile:** Parsers and filter URL encoding in `:core` first. Compose grid and detail second. Coil lives in `:app`.
- **Stop conditions:** Stop and ask if `GET /api/libraries/{id}/items?filter=progress.<b64>` is gone or expanded-only on the target server; if podcast `recent-episodes` requires a different path; or if cover endpoints demand a token query parameter with no header alternative.
- **Tail ownership:** Build, test, commit. No ExoPlayer.

Depends on [2026-09-01-002-feat-android-connect-auth-plan.md](2026-09-01-002-feat-android-connect-auth-plan.md).

## Product Contract

### Summary

A library dropdown, a cover grid, search, and detail pages for books, podcast shows, and episodes. Home is not a full catalog. Books show up to 25 in-progress items for the selected library. Podcasts show up to 50 recent episodes. This matches the GTK app as it shipped, not the abandoned sidebar-plus-paged-library plan.

### Problem Frame

`GET /api/me/items-in-progress` expands every in-progress item before trimming. On a real library that was 5.3s. The filter query returns in tens of milliseconds. Do not "simplify" back to the me-route.

Podcasts were originally out of the GTK product and then became first-class. Android starts with them. Filtering podcasts out would be a regression against `features.md`.

Touch has no hover play button. A tile tap opens detail. That is the right call, not a missing feature.

### Requirements

**Libraries**

- R1. Book and podcast libraries are both listed in a dropdown. Last selected library id is stored in DataStore and restored.
- R2. Switching libraries clears search and reloads that library's home.
- R3. Book home is continue listening: up to 25 in-progress books from the selected library, sorted by progress descending, via the items filter query, not `/api/me/items-in-progress`.
- R4. Podcast home is latest episodes: up to 50 recent episodes for the selected podcast library.

**Search**

- R5. Search is debounced 250ms, cancels the previous request. Empty query returns to home. Hits are books or podcasts matching the selected library type.

**Grid**

- R6. Tiles show square cover, title, author. Numbered series get a `#N` badge. Items with no playable audio say so instead of implying play.
- R7. Covers request JPEG from the server. Coil loads only for visible tiles; leaving the grid cancels or at least drops the request work. In-memory cache is enough in this phase; disk cover cache is still deferred except what Coil does by default.
- R8. Cover URL is `/api/items/{id}/cover?format=jpeg` plus a cache-buster query that is not a credential. Authorization stays in the OkHttp header. Never `?token=`.
- R9. Failed library loads show a status screen with Retry.

**Detail**

- R10. Books: cover, title, author, Play / Resume / Start over from server progress. Finished items offer Start over, not resume-at-end. Unstarted books hide the progress area. Started books show a bar and a line like `25% · 1h 30m left`. Chapter list with timestamps; hidden when there are no chapters. Activating a chapter is a no-op for playback until phase 004, but the click target must exist.
- R11. Podcast shows: newest-first / oldest-first. Episode rows with progress or duration. Row click will play in phase 004. Add-to-queue is visible but can no-op until phase 005 if the queue type is not in core yet; prefer adding a queue type in core here so 005 does not invent models.
- R12. Podcast episode detail from a recent-episodes tile: episode title, show as subtitle, Play/Resume, no chapter list.
- R13. Progress on detail requires `include=progress`. Parser accepts `userMediaProgress` and `mediaProgress`. Series from a structured array or from `seriesName` string `Name, #N`.
- R14. Transcode-only items are marked unplayable with an explanation. Do not offer Play as if direct play would work. Detection uses the same fields the GTK client uses on the item payload.
- R15. Sign-out remains reachable from library and detail, same behavior as phase 002.

### Acceptance Examples

- AE1. Continue listening is per library
  - **Covers:** R1, R3
  - **Given:** two book libraries, each with in-progress titles.
  - **When:** the user switches the dropdown.
  - **Then:** the grid is that library's in-progress set, not a union of all libraries.

- AE2. Search cancel
  - **Covers:** R5
  - **Given:** the user types `dune` then quickly `d`.
  - **When:** 250ms after `d`.
  - **Then:** only the `d` request's results are shown. The `dune` response is ignored.

- AE3. Finished book
  - **Covers:** R10
  - **Given:** the server marks the item finished.
  - **When:** detail opens.
  - **Then:** the primary action is Start over. There is no Resume that would start at 100%.

- AE4. Ampersand in a title
  - **Covers:** R11
  - **Given:** an episode titled `Foo & Bar`.
  - **When:** the show list renders.
  - **Then:** the ampersand displays. Do not feed raw HTML to a WebView. Text composables only.

- AE5. Cover has no token
  - **Covers:** R8
  - **Given:** Coil is fetching a cover.
  - **When:** you inspect the request.
  - **Then:** `Authorization: Bearer` is a header on the shared OkHttp client. The URL has no `token` query.

- AE6. Empty continue listening
  - **Covers:** R3, R9
  - **Given:** the selected book library has nothing in progress.
  - **When:** home loads.
  - **Then:** an empty state, not an error. Retry is for failures, not for zero items.

### Scope Boundaries

**In scope**

Libraries, home grids, search, detail, covers, unplayable/transcode copy, sign-out from these screens, parsers for list and detail payloads.

**Deferred**

Play, download buttons, queue mutations that start audio, full "all books" browse, infinite scroll, disk cover cache beyond Coil, collections, authors, series pages.

**Outside**

Library admin, metadata editing, ebooks.

## Planning Contract

### Key Technical Decisions

- KTD1. **Home is continue listening / recent episodes, not a paged full library.** Governs R3, R4. `fetch_library_items` can exist on the API client for later, unused by UI, same as the GTK client.

- KTD2. **In-progress URL matches the GTK helper.** Governs R3.

```
GET /api/libraries/{id}/items?filter=progress.{base64("in-progress")}&sort=progress&desc=1&page=0&limit=25&minified=1
```

`filter` group encoding: `progress.` + URL-encoded Base64 of the UTF-8 bytes of `in-progress`. Port `filter_fragment` exactly. Tests compare the path string.

- KTD3. **Recent episodes:** `GET /api/libraries/{id}/recent-episodes?limit=50&page=0`.

- KTD4. **Search:** `GET /api/libraries/{id}/search?q=`. Debounce in the ViewModel with a coroutine, not in the API client.

- KTD5. **Item detail:** `GET /api/items/{id}?expanded=1&include=progress`, optional `&episode={episodeId}`.

- KTD6. **Coil uses the composition-root OkHttp client.** Governs R7, R8. That is how the TLS pin and the Bearer header reach images. A default Coil stack would ignore the pin and might try query tokens.

- KTD7. **Navigation is a single Activity with a back stack.** Library is root after sign-in. Detail pushes. No Navigation-for-its-own-sake graph of five tabs. A search field on the library screen toggles home vs results in place.

- KTD8. **ViewLogic in `:core`.** Format `25% · 1h 30m left`, choose Play vs Resume vs Start over, parse `seriesName`, pick first unfinished episode. Port GTK `view-logic` behavior. Unit test it. Compose just renders the strings.

### High-Level Technical Design

Replace the phase 002 stub signed-in screen with `LibraryScreen`. `MiniAbsApplication` already has `ApiClient` and `Credentials`. Add a `LibraryRepository` in core that wraps the GET helpers and maps JSON to immutable data classes.

Grid: `LazyVerticalGrid`. Tile width around 176dp to match the GTK 176px visual, scaled for density, not a pixel-perfect copy.

Play buttons on detail call a `PlaybackStarter` interface with a no-op implementation in this phase so 004 can bind the engine without rewriting the screen.

### Assumptions

- `minified=1` list payloads still carry enough title, author, cover id, and playable flags. If a field is missing, expand that one call rather than dropping minified for the whole grid.
- Podcast libraries are identified the same way as GTK: media type on the library object.

## Implementation Units

### Unit Index

| U-ID | Title | Key files | Depends on |
|---|---|---|---|
| U1 | Models and parsers | `:core` item, library, episode, progress | none |
| U2 | API helpers and ViewLogic | `ApiClient` GETs, `ViewLogic`, URL tests | U1 |
| U3 | Library screen: dropdown, grid, search, empty/error | `LibraryScreen`, ViewModel | U2 |
| U4 | Coil covers through shared OkHttp | Coil setup in Application | U3 |
| U5 | Detail screens | book, show, episode | U2, U4 |

### U1. Models and parsers

- **Requirements:** R13, R14
- **Test scenarios:** fixture JSON from the GTK `tests/fixtures` if they exist, otherwise capture from a 2.36 server. Unknown fields ignored. `userMediaProgress` vs `mediaProgress`. `seriesName` `Expanse, #2` → badge `#2`.

### U2. API helpers and ViewLogic

- **Requirements:** R3, R4, R5, R10
- **Tests:** `in_progress_url_path`, `item_url_path`, `search` path encoding, Start over vs Resume given finished flags and currentTime.

### U3. Library screen

- **Requirements:** R1, R2, R5, R6, R9, R15
- **Verification:** Switch library, search, clear search, failed load Retry, sign out.

### U4. Covers

- **Requirements:** R7, R8
- **Verification:** Charles/proxydump or OkHttp logging with redaction: header auth, jpeg format. Scroll fast; no crash.

### U5. Detail

- **Requirements:** R10 to R14
- **Verification:** book with chapters, book without, finished, unstarted, transcode-only, podcast show sort toggle, episode from recent tile.

## Risks and Dependencies

| Risk | Impact | Mitigation |
|---|---|---|
| Filter encoding mismatch | Empty continue listening forever | Golden-string test against GTK `in_progress_url_path` |
| Coil default loader | Token in URL or pin ignored | KTD6 |
| Huge cover bitmaps | Grid jank | Coil size limiter to tile px |

## Open Questions

- OQ1. Tile size on a 10-inch tablet. Default: more columns from `GridCells.Adaptive(160.dp)`, not a special tablet layout.
- OQ2. Whether transcode-only can be known from minified list tiles or only from expanded detail. Default: list may omit it; detail is authoritative, tiles stay honest when the field is present.

## Sources

- `features.md` libraries, search, item detail.
- GTK `ApiClient.in_progress_url_path`, `item_url_path`, `recent_episodes_url_path`, `cover_url`.
- GTK features drift: podcasts in, full-library browse out, continue listening per selected library.
