# TODO

Follow-ups from the design review (2026-11-26). Split by repository.

## API (SimStim/Logophilia-API)

- [ ] **Client marker before public launch.** Exempt an explicitly marked mobile client
  (e.g. `X-Client: hermes-android` or a reserved `User-Agent` prefix) from the
  CORS/Referer check in `CorsMiddleware`, so the app no longer needs to spoof
  `Referer`. An explicit marker is an honest, stable contract and lets the API
  keep separately trackable download statistics for the mobile client
  (design outline §2). Until then the app spoofs `Referer: https://logophilia.eu/`.
- [ ] **API key renewal.** When a request presents a key found in the retired-keys
  list, respond with the `key_expired` error carrying `new_api_key` (design
  outline §2), so installed apps can rotate their hardcoded key. Until this
  exists, Hermes treats `403` as a plain authentication failure. Key rotation
  happens only when necessary, not on a schedule.
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
