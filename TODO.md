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
- [ ] **Amend SPECIFICATION.md** with the decisions made after v1.0: §3.5 key
  renewal moves to the greeting-only channel (see the API item above;
  criterion #16 reworded accordingly); §3 documents that `/download` sends
  no `Content-Length` (chunked transfer — found in the M2 live check), so
  the client relies on SHA-256, not on length.
- [ ] **Formal acceptance pass on a physical device** (spec §11, 24 criteria).
  Needs a real Wi-Fi phone; the engine logic is already covered by 78 tests.
  Uninstall the debug build before installing the first release-signed APK
  (signature change). Includes the states only visible during a real sync:
  blue/green sync indicator, amber Wi-Fi state, populated Library and
  recently-added lists, cooldown countdown on the Manual Sync button.
