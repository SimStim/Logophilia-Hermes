# TODO

Follow-ups from the design review (2026-11-26). Split by repository.

Status: every item below is resolved. The formal acceptance pass on a
physical device (last item in the Hermes list) was completed by the user on
2026-11-27 — the first release build passed the on-device criteria they
exercised; two criteria are only observable at the first real key rotation
(#16) and the first package published through the manifest (#27).

## API (SimStim/Logophilia-API)

- [x] **Client marker before public launch.** Done 2026-11-27 (API commit
  `5f6e946`). `CorsMiddleware` exempts the reserved marker header
  `X-Client: eu.logophilia.hermes` (exact value, not CORS-safelisted) from the
  CORS/Referer checks, so the app never needed to keep the `Referer` spoof:
  it was removed the same day (Hermes item below). Auth and rate limiting
  still apply to exempted requests.
- [x] **API key renewal — greeting-only channel.** Done 2026-11-27 (API commit
  `08e1f91`, app side the same day). A retired app key still authenticates on
  `GET /` only, whose response carries the current key under `usha`
  (Sanskrit: dawn) alongside the regular success fields — not the
  `key_expired` error sketched here. Every other endpoint keeps the strict
  key check and answers a retired key with plain `403`; holders of the
  current app key, the website key, or no key learn nothing. The app checks
  every greeting response for `usha`, overwrites its stored key, and
  persists immediately (SharedPreferences, api/ApiKeyStore.kt). No
  retry-once rule exists — the greet-first + wait/restart cycle self-heals.
  Spec amended to v1.3 (§2, §3, §3.5, §4).
- [x] **Empty file list status.** Done 2026-11-27 (API, working tree): an empty
  downloads folder now answers `status: "success"` with an empty `freeloot`
  array, so clients can distinguish a deliberate empty manifest from a
  malformed response (design outline §4, "Empty manifest"). The app's
  validator always accepted empty+success and the engine already wipes
  obsolete content on it (§5); a non-success status is still never read as
  empty content. Edge case — there is normally always free loot.
- [x] **Proper 404 for missing downloads.** Done 2026-11-27 (API, working tree):
  a missing or invalid `fileName` now answers a real `404` with a JSON error
  body; missing and invalid names share the code, so probing the download
  folder learns nothing. Mostly a browser UX issue (stale page after long
  idle); the app maps the 404 to `ApiException.Server` and still sniffs
  `Content-Type` defensively, so both the old and the new server answers end
  as a typed failed download with nothing written.

## Hermes (this repo)

- [x] **Replace Referer spoof with client marker before public launch.** Done
  2026-11-27, after the matching API change (CorsMiddleware exempts
  `X-Client: eu.logophilia.hermes`, matched by exact value). A single
  interceptor in `LogophiliaApi.kt` adds the marker plus the informational
  `X-Client-Version` to every request; the spoofed-`Referer` interceptor is
  deleted, and no `Origin`, `Referer`, or browser-like `User-Agent` is ever
  sent. `X-Api-key` and rate limiting unchanged. Fail-safe: a missing or
  misspelled marker reverts to the old 403. Spec amended to v1.2 (§3).
- [x] **Amend SPECIFICATION.md (v1.1)** — done 2026-11-27. Applied: §3.5 key
  renewal → greeting-only channel (criterion #16 reworded); §3 `/download`
  sends no `Content-Length` (chunked; client relies on SHA-256); §4/§5
  incremental per-file commit with a per-commit gate re-check (criteria
  #5/#6 reworded, #25 added); §4 `TEMPORARILY_NOT_METERED` accepted; §8
  indicator consolidation + hierarchical Library + recently-added top-level
  items; §7 image MIME types + generic no-handler dialog (criterion #26
  added); §8 bundled fonts and externalized strings; §8 copy/plurals.
- [x] **[decided: accept] TEMPORARILY_NOT_METERED.** The gate accepts
  `TEMPORARILY_NOT_METERED` alongside `NOT_METERED` (it never bills the
  user) and re-checks the gate before every file commit, so a mid-sync flip
  back to metered stops further commits while keeping what is already
  committed. Spec §4/§5 already amended; implemented in M7 (2026-11-27).
- [x] **Incremental per-file commit (spec §4/§5 + engine).** Implemented in M7
  (2026-11-27): each file (and each extracted ZIP directory + marker) commits
  immediately after its own verification. On failure: everything already
  committed is kept, no deletions, the attempt is marked failed. Obsolete-
  content cleanup and the empty-manifest wipe happen only after a fully
  successful attempt. Verified live on the emulator: an interrupted attempt
  (SocketException) kept its committed files, and the retry downloaded only
  the missing ones (7 + 16 = 23 items, zero re-downloads).
- [x] **Indicator consolidation (spec §8 + UI).** Done in M7 (2026-11-27): the
  colored circle and Home's separate sync symbol are gone; color coding lives
  on the big sync symbol on the Sync page only (its tap opens the detail
  popup); the bottom-nav Sync icon rotates while a sync runs and stays
  neutral otherwise; the Wi-Fi symbol stays on Home at the Sync page symbol's
  size (48 dp).
- [x] **Hierarchical Library (spec §8 + UI; reverses the M5 flattening).**
  Done in M7 (2026-11-27): top level = plain files plus one item per
  extracted archive (basename sans ".zip"); tapping an archive lists its
  contained files (markers/sidecars never shown); recently added lists
  top-level items only, archive items timed by the extracted directory's
  mtime. Verified live while a sync filled the Library.
- [x] **Bug: image files open nothing.** Fixed in M7 (2026-11-27): image MIME
  types added (jpg/jpeg/png/webp/gif) plus an image/* <queries> entry, and
  every failed open now surfaces the dialog (generic "no application found"
  with a Play search and Retry when no suggestion applies) — opening a file
  never fails silently. Verified on the emulator: cover.jpg opens in Google
  Photos; an epub without a reader shows the Moon+ Reader dialog.
- [x] **Externalize all user-facing strings + author docs/STRINGS.md.** Done
  in M7 (2026-11-27): every literal lives in `res/values/strings.xml`
  (including "New"/"Updated"/"Removed" inside ICU `<plurals>` and the
  category labels), and docs/STRINGS.md documents every string plus the
  edit → `./gradlew installDebug` workflow.
- [x] **Bundle EB Garamond (body) and Comfortaa (headings).** Done in M7
  (2026-11-27): the latin-subset variable TTFs (wght axes intact) are wired
  into the theme via `FontVariation` (EB Garamond body/label, Comfortaa
  display/headline/title); OFL licenses ship in `app/src/main/assets/licenses/`.
- [x] **Formal acceptance pass on a physical device** (spec §11 criteria).
  Completed 2026-11-27 by the user on their own phone: fresh install of the
  release APK (bootstrapped via Nextcloud/KDE Connect after the Play-Store
  Nextcloud app refused the APK download — browsers do not), first open on
  validated unmetered Wi-Fi started the sync immediately; the full library
  downloaded much faster than on the emulator; the live phase updates (blue
  → animated green → success) ran without visual noise, with Home's counts,
  recently-added list, and the Library filling in real time; About and Sync
  pages vertically centered; indicator colors read well on the phone's OLED
  (the provisional green stays, slightly dark but unproblematic); epub
  opened immediately in Moon+ Reader, .opus showed the app picker, and audio
  played on in the background (VLC, minimized) while the app stayed usable.
  Two criteria stay observable-only: #16 (key rotation through `usha`)
  awaits the first real server-side rotation; #27 (the manifest-delivered
  package opens the installer) awaits the first APK published in the
  downloads folder — the mapping is unit-tested and the installer path is
  standard sideloading.
