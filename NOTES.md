# NOTES

- 2026-11-27 — M2 started; §3 re-verified live with curl (greeting 200, /freeloot 200 JSON, no-Referer 403, no-key 401, bad-key 403, /download octet-stream, bogus /download = HTTP 200 + JSON, sha256 round-trip OK, 23 entries). Finding: /download uses chunked encoding, no Content-Length (spec says it is sent) — client must not rely on it; SHA-256 catches truncation. Next: OkHttp/serialization deps + INTERNET permission.
