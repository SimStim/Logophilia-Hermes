# Logophilia Hermes — user-facing material package

*Source material for anything that speaks to users: a landing page, a future
app-store listing, a newsletter article, social posts. Nothing technical —
high-level concepts only; the engineering lives in `README.md` and
`SPECIFICATION.md`, and this file deliberately does not repeat it.*

Editorial notes:

- Facts are taken from the project's own documentation and verified against
  the shipped app (as of v0.0.4, November 2026). Re-check the facts sheet
  before publishing.
- Fill-in placeholders are marked ⟨angle brackets⟩.
- Tone: warm, playful, honest. The Logophilia brand is about the love of
  words; the copy may enjoy language without drowning in it.

---

## 1. Identity

- **Full name:** Logophilia Hermes
- **Short name (used on the About page, under the logo):** Hermes
- **Pronunciation, if ever needed:** HER-meez — and "logophilia" is Greek for
  "the love of words", which is exactly what the letters on the logo spell.
- **The name fits the job:** Hermes was the messenger of the Greek gods —
  the one who carried things swiftly from one world to another. This Hermes
  carries Logophilia's free loot from the web to your phone.

### One-liner

> Logophilia's free loot, delivered to your phone and kept up to date — over
> Wi-Fi only.

### Shorter options (for bios, footers, share cards)

- The free loot, delivered.
- Your pocket shelf for Logophilia's free loot.
- Hermes carries the loot. You do the reading.

### Fifty words

> Hermes is a small Android companion for the Logophilia free-loot page.
> Open it once and it quietly keeps a local shelf of everything currently on
> offer: new files arrive over Wi-Fi, verified and unpacked, ready to open in
> your favorite reader and player apps. No account, no ads, about three
> megabytes.

### A hundred words

> The Logophilia website regularly offers free loot — books, albums,
> documents, all free to take. Hermes is the native Android companion for
> that shelf. Open the app and it fetches what's new, checks every file's
> fingerprint before it lands, unpacks the music archives neatly into
> albums, and hands everything to the apps you already love — your EPUB
> reader, your music player. It only ever downloads over Wi-Fi, it asks
> before it works, and it takes up about as much space as one photograph.
> When the shelf changes, your library follows. That's the whole idea: the
> loot, delivered.

## 2. The story

The free-loot page is a browser experience: visit, look, download, come back
later to see what changed. Hermes keeps that shelf on the phone instead —
not as a pile of downloaded files, but as a tidy, always-current library.

The mythology writes itself. Logophilia loves words; its logo spells that
love in Greek letters. Hermes was the gods' trusted courier — swift, useful,
and never one to open other people's mail. The app tries to deserve the
name: it delivers what is offered, it verifies every parcel, it stays out of
your way, and it doesn't pretend to be anything it isn't.

## 3. What it does — benefit by benefit

- **Your shelf, kept current.** Hermes compares the online shelf with your
  library on every visit and fetches only what's new or changed. Files that
  leave the shelf leave the library too, so the app stays lean and your
  storage never fills with forgotten relics.
- **Small things first.** When several files arrive at once, the smallest
  are fetched first — you'll see (and can open) new content within seconds,
  while the big albums still travel in the background.
- **Wi-Fi only, by conviction.** Hermes never downloads over mobile data.
  If Android says a network might bill you — hotspot, metered connection —
  the app simply waits, politely, until real Wi-Fi is back.
- **Every parcel is checked.** Each file is verified against the server's
  fingerprint before it enters your library; a corrupted transfer is thrown
  away, never half-installed into your shelf.
- **Archives become albums.** The music offerings arrive as archives; Hermes
  unpacks them into proper albums you can browse — and it does so defensively
  (malformed archives are rejected, not trusted).
- **Works with the apps you already love.** Hermes has no built-in reader or
  player — deliberately. It hands each file to your installed EPUB reader,
  music player, or PDF viewer. If something isn't installed yet, Hermes says
  so and points you to it rather than failing silently.
- **Polite by design.** At most one synchronization attempt per five
  minutes, however eagerly you open the app — the server gets breathing
  room, your battery stays cool, and the status page tells you exactly what
  is happening ("Receiving content…", "Synchronized", "Cooling down").
- **Survives server makeovers.** When the server rotates its access keys,
  Hermes picks up the new one by itself on the next greeting — no update,
  no re-install, no locked doors.
- **Updates like a friend, not a service.** New versions of Hermes itself
  can ride the same shelf: they appear in your library like any content, and
  installing one is a single tap with the usual Android confirmation. No
  background updating, no silent swaps.

## 4. What it deliberately isn't

Saying this up front saves everyone time:

- **Not a permanent media library.** Your Hermes library mirrors what is
  currently offered. To keep something forever, grab it from the website —
  that download is yours for good.
- **Not a reader or a player.** No built-in book view, no music controls.
  Hermes is the courier, not the library café.
- **Not a file manager.** There is nothing to configure, nothing to clean
  up, no folders to dig through (well — albums open like folders; that's
  browsing, not administering).
- **Not a social app.** No accounts, no feed, no notifications you didn't
  ask for.

## 5. Privacy and data, in plain words

- **No account, ever.** Nothing to sign up for, nothing to log into.
- **No analytics, no tracking, no ads.** The app talks to exactly one
  place — the Logophilia server — and only to fetch the shelf and the files
  on it.
- **Your library stays on your phone.** Content lives in the app's private
  storage; it is excluded from Android backups, so nothing syncs to a cloud
  on your behalf.
- **The access key is the only secret,** it lives on your device, and it is
  never written into any message the app sends home about itself.
- **Opening a file shares it with exactly one app** — the one you picked —
  and only for that moment.

## 6. Getting it — and keeping it current

- **Where:** ⟨link to the app's download page / free-loot page⟩.
- **How (once):** download the APK, tap it, confirm Android's
  one-time "allow installs" question for the browser/app that delivered it.
- **After that:** opening Hermes is enough. New content arrives on its own;
  new app versions can arrive the same way — they show up in the library,
  one tap installs them.
- **Requirements:** Android 10 or newer, and a Wi-Fi connection for the
  actual fetching.

## 7. Facts sheet (verify before publishing)

| Fact | Value |
|---|---|
| Name | Logophilia Hermes (About page: "Hermes") |
| Platform | Android 10 (API 29) or newer |
| Size | ≈ 3 MB download |
| Price | Free; the content it fetches is free |
| Ads / tracking / account | None |
| Network use | Wi-Fi only; talks only to the Logophilia server |
| Languages | ⟨English at launch; list more here as they appear⟩ |
| Current version | ⟨check the About page before publishing⟩ |
| Links | Website ⟨logophilia.eu⟩ · Free loot ⟨link⟩ · App page ⟨link⟩ |

## 8. FAQ seeds (answers written to be lifted directly)

- **Why won't it download over mobile data?**
  Because "free loot" shouldn't cost data. Hermes waits for Wi-Fi — the
  status page shows a red symbol when it's waiting and explains why.
- **Why is the sync button greyed out sometimes?**
  Hermes tries at most once every five minutes, however often you open it.
  The countdown on the Sync page tells you when the next attempt is possible.
  This keeps things gentle for the server (and your battery).
- **Where do the files go?**
  Into Hermes's own private shelf. You open them from within the app; the
  files are handed to your reader or player straight from there.
- **Can I keep files forever?**
  Hermes mirrors what's currently offered — when something leaves the shelf,
  it leaves the library. For keeps, download it from the website.
- **Where are the music albums?**
  Under Music: each archive is unpacked into its own album, tracks and
  covers included.
- **How do updates of Hermes itself work?**
  New versions can appear in your library like any content. Tap, confirm,
  done — Android walks you through a one-time "allow installs" question.
- **Is my phone supported?**
  Any Android phone from 2019 onward (Android 10+).
- **What does it cost?**
  Nothing. The app is free, and so is everything it fetches.

## 9. Ready-made copy blocks

### App-store short description (≤ 80 characters)

- `Logophilia's free loot, automatically kept on your phone. Wi-Fi only.`

### Store listing skeleton (for the distant-future Play listing)

> **Logophilia Hermes — the free loot, delivered.**
>
> Hermes is the native Android companion for the Logophilia free-loot page.
> Open it, and your personal shelf of everything currently offered comes to
> you — books, albums, documents — fetched over Wi-Fi only, verified with a
> fingerprint on arrival, and unpacked into a tidy library you can browse by
> category.
>
> Hermes has no built-in reader or player, on purpose: it hands every file
> to the apps you already love. EPUBs open in your reader, albums open in
> your player, and Hermes stays out of the way — about three megabytes of
> courier, nothing more.
>
> - Keeps itself current: only new and changed files are fetched
> - Small files first, so new content appears within seconds
> - Wi-Fi only — it will never touch your mobile data
> - Every file verified before it enters your library
> - Music archives unpacked into proper, browsable albums
> - No account, no ads, no tracking — one server, one purpose
>
> The loot is free. The courier is free. Your data plan is untouched.

### "What's new" template

> This release brings ⟨one user-visible sentence⟩ and ⟨one more, if any⟩.
> Small fixes, no new permissions, same tiny courier.

### Newsletter skeleton

1. **Hook:** "You know the free-loot page. Here's the part nobody enjoys:
   remembering to check it." — then introduce Hermes as the remembering.
2. **The mythology beat:** logophilia in Greek letters; Hermes the courier.
   Two sentences, enjoy it.
3. **What it does:** three bullets, user language (shelf, Wi-Fi only,
   opens in your apps).
4. **The honest-courier beat (optional, delightful):** Hermes introduces
   itself to the server as what it is — an app, not a browser pretending to
   be one. One sentence of "we built it to play fair" goes a long way.
5. **How to get it:** the link, the one-time install question, done.
6. **Sign-off:** "The loot is free. The courier is 3 megabytes."

## 10. Visual identity notes (for the landing page)

- **Logo:** the Logophilia wordmark in Greek letters — `logo-light.png`
  (dark letters) for light backgrounds, `logo-dark.png` (white letters) for
  dark ones. Both ship in the app's `art/` sources.
- **Palette (current app values, refine to taste):**
  - Green — `#36482E` (content flowing / everything good)
  - Amber — `#C8B273` (waiting for Wi-Fi)
  - Blue — `#416392` (reaching for the server)
  - Plus the system's light/dark surfaces; the app follows the phone's theme.
- **Typography:** EB Garamond for body text, Comfortaa for headings — the
  same pairing the app itself uses, so the landing page and the app feel
  like one object.

## 11. Tone and honesty guardrails

- **Do** say "free" — the content is free and the app is free.
- **Do** say "Wi-Fi only" prominently; it is the feature people thank you for.
- **Don't** promise permanent storage (the library mirrors the shelf).
- **Don't** promise formats the app doesn't open itself — it *hands files to
  your apps*, which is a different (and better) promise.
- **Don't** quote technical details (checksums, headers, cooldowns are fine
  as stories; protocol names are not).
- **Do** keep the courier metaphor — it is accurate, and it is charming.

## 12. Under the hood, in plain words (for the curious)

- The app introduces itself to the server with a name tag — "I am Hermes,
  the Android app" — instead of pretending to be a web browser. The server
  treats honest clients kindly and browsers normally.
- Files travel with fingerprints; nothing enters your library unverified.
- The app greets the server before it works, and greets again if something
  was refused — that greeting is also how it learns about new access keys.
- Downloads pause themselves the moment the network stops being genuinely
  Wi-Fi; everything already fetched stays, and the rest resumes later.
