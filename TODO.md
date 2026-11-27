# TODO

Follow-ups from the design review (2026-11-26). Split by repository.

## API (SimStim/Logophilia-API)

- [ ] **Client marker before public launch.** Exempt an explicitly marked mobile client
  (e.g. `X-Client: hermes-android` or a reserved `User-Agent` prefix) from the
  CORS/Referer check in `CorsMiddleware`, so the app no longer needs to spoof
  `Referer`. An explicit marker is an honest, stable contract and lets the API
  keep separately trackable download statistics for the mobile client
  (design outline §2). Until then the app spoofs `Referer: https://logophilia.eu/`.
- [ ] **API key renewal — greeting-only channel.** Deliver key updates exclusively
  through the greeting endpoint (`GET /`): when the presented key is found on
  the server's retired-keys list, the greeting responds with the `key_expired`
  error carrying `new_api_key`. Every other endpoint keeps the strict
  single-key check and answers expired keys with plain `403`. Benefits:
  fewer API changes (renewal logic in one controller only); the website never
  calls the greeting, so a replacement key can never be disclosed to web
  clients by mistake; and the app already probes the greeting on every
  trigger event (spec §8), so it always receives a fresh key before any sync
  traffic. App-side consequence: the trigger flow becomes network check →
  greeting probe (reachability + optional key renewal) → sync attempt, and
  the old "retry the original request once" rule becomes unnecessary — a key
  rotated mid-sync simply fails that attempt and renews at the next probe.
  Supersedes SPECIFICATION.md §3.5 (amend the spec when convenient). Until
  this exists, Hermes treats `403` as a plain authentication failure. Key
  rotation happens only when necessary, not on a schedule.
- [ ] **Empty file list status.** `/freeloot` currently answers an empty downloads
  folder with `status: "error"` and `freeloot: []`. Return `status: "success"`
  instead, so clients can distinguish a deliberate empty manifest from a
  malformed response (design outline §4, "Empty manifest"). Edge case — there
  is normally always free loot.
- [ ] **Proper 404 for missing downloads.** `/download` answers a missing or
  invalid `fileName` with HTTP 200 + JSON error body. Send a real 404 (or
  another error status) instead. Mostly a browser UX issue (stale page after
  long idle); the app downloads immediately after fetching the manifest, but
  will still sniff `Content-Type` defensively.

## Hermes (this repo)

- [ ] **Replace Referer spoof with client marker before public launch.** Depends on
  the matching API change above. Development uses the spoof; do not ship it.
- [x] **Amend SPECIFICATION.md (v1.1)** — done 2026-11-27. Applied: §3.5 key
  renewal → greeting-only channel (criterion #16 reworded); §3 `/download`
  sends no `Content-Length` (chunked; client relies on SHA-256); §4/§5
  incremental per-file commit with a per-commit gate re-check (criteria
  #5/#6 reworded, #25 added); §4 `TEMPORARILY_NOT_METERED` accepted; §8
  indicator consolidation + hierarchical Library + recently-added top-level
  items; §7 image MIME types + generic no-handler dialog (criterion #26
  added); §8 bundled fonts and externalized strings; §8 copy/plurals.
- [ ] **[decided: accept] TEMPORARILY_NOT_METERED.** The gate accepts
  `TEMPORARILY_NOT_METERED` alongside `NOT_METERED` (it never bills the
  user) and re-checks the gate before every file commit, so a mid-sync flip
  back to metered stops further commits while keeping what is already
  committed. Spec §4/§5 already amended; implement in M7.
- [ ] **Incremental per-file commit (spec §4/§5 + engine).** Today an attempt is
  all-or-nothing: everything lands in Temp and the Library updates only
  after the complete sync. Change: commit each file (and each extracted ZIP
  directory + marker) immediately after its own verification, then continue
  with the next. On failure: keep everything already committed, perform no
  deletions, mark the attempt failed. Obsolete-content cleanup and the
  empty-manifest wipe still happen only after a fully successful attempt.
  Side benefits: retries never re-download already-committed files
  (previously discarded with Temp), and "recently added" updates in real
  time while the sync runs.
- [ ] **Indicator consolidation (spec §8 + UI).** Remove the big colored circle
  next to the sync symbol — redundant duplicate of the same state. Color
  coding lives on the big sync symbol on the Sync page only; the bottom-nav
  Sync icon shows the rotating animation while a sync runs and stays neutral
  otherwise, so sync status is visible from every tab. [decided] The Wi-Fi
  symbol stays on **Home**, rendered at the same size as the Sync page's
  sync symbol (it was smaller before).
- [ ] **Hierarchical Library (spec §8 + UI; reverses the M5 flattening).** Storage
  is per spec (each OST extracts to `Music/<archive basename>/…`; two
  different albums' `cover.jpg` files coexisting is correct, nothing should
  overwrite). The bug is display-only: M5 flattened all files and used
  relative paths as labels. New model: top level = plain files plus one item
  per extracted archive, displayed as the archive basename without ".zip";
  tapping an archive item lists its contained files (`.hash` markers and
  sidecars never shown); tapping a contained file opens it. Recently-added
  on Home lists top-level items only, never files inside archives.
- [ ] **Bug: image files open nothing.** Tapping `cover.jpg` neither opened a
  viewer nor showed the missing-handler dialog (silent failure). Diagnosed:
  the extension→MIME table has no image types at all (`.jpg` falls through
  to `application/octet-stream`), and the no-handler-without-suggestion path
  apparently ends silently instead of showing a generic "no app found"
  dialog. Fix: add image MIME types (jpg/jpeg/png/webp/gif) and guarantee
  the generic fallback dialog for any unhandled MIME.
- [ ] **Externalize all user-facing strings + author docs/STRINGS.md.** Move every
  literal into `res/values/strings.xml` (including single words like
  "New"/"Updated"/"Removed" and the category labels), use ICU `<plurals>`
  for counts ("1 document" vs "N documents"), and write a short guide
  documenting every string and the edit → `installDebug` workflow so
  wording can be tuned by hand without a session.
- [ ] **Bundle EB Garamond (body) and Comfortaa (headings).** Files already
  fetched from Google Fonts and latin-subset with fonttools:
  `app/src/main/res/font/ebgaramond.ttf`, `ebgaramond_italic.ttf`,
  `comfortaa.ttf` — still fully variable (wght axes intact, ~600 KB total,
  down from 1.8 MB); OFL license files staged in
  `app/src/main/assets/licenses/`. Remaining: `FontFamily` wiring and theme
  update (EB Garamond body, Comfortaa headings). Spec §8 amendment already
  applied.
- [ ] **Formal acceptance pass on a physical device** (spec §11, 24 criteria).
  Needs a real Wi-Fi phone; the engine logic is already covered by 78 tests.
  Uninstall the debug build before installing the first release-signed APK
  (signature change). Includes the states only visible during a real sync:
  blue/green sync indicator, amber Wi-Fi state, populated Library and
  recently-added lists, cooldown countdown on the Manual Sync button.
