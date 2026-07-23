# Naada · నాద — Architecture & Engineering Notes

A personal, ad-free Indian-music player that runs as an installable PWA, backed
by a lightweight Python service on the same phone via Termux. Built for one
listener, engineered like a small production system.

---

## 1. What it is

Naada streams 320 kbps Indian music (Hindi, Telugu, Tamil, English) with a
clean, modern interface — no ads, no accounts, no telemetry. It installs to the
home screen, runs full-screen, keeps a local library, shows synced lyrics, and
adds AI-generated story and trivia features. It is intended strictly for
personal use.

---

## 2. System at a glance

```
   ┌─────────────────────────────────────────────────────────────┐
   │                         Your phone                           │
   │                                                              │
   │   ┌────────────────────────┐      ┌───────────────────────┐ │
   │   │  Naada PWA (Chrome)     │      │  Flask server         │ │
   │   │  • UI, player, library  │ ───▶ │  (Termux, localhost)  │ │
   │   │  • Service worker cache │ HTTP │  • Decryption (DES)   │ │
   │   │  • Media Session        │      │  • Metadata proxy     │ │
   │   │  • localStorage data    │ ◀─── │  • Gemini key (safe)  │ │
   │   └───────────┬────────────┘      └───────────┬───────────┘ │
   │               │                               │             │
   └───────────────┼───────────────────────────────┼─────────────┘
                   │                               │
          audio (direct)                   metadata / AI
                   │                               │
                   ▼                               ▼
         aac.saavncdn.com               JioSaavn · LRCLIB · Gemini
         (320 kbps stream)              (search, charts, lyrics, AI)
```

**Key insight:** audio never passes through the local server. The browser
streams it **directly** from the music CDN. The Flask service is a small,
local-only middleman for the few things a browser cannot do itself.

---

## 3. Why a local server exists at all

A pure browser app cannot do three things, so a tiny Flask service handles them:

1. **Cross-origin API access (CORS).** The upstream music API and the lyrics
   service do not send the headers browsers require for direct page-to-API
   calls. A server has no such restriction, so Flask makes those calls and
   hands clean JSON back to the page.

2. **Stream-URL decryption.** Playable URLs are returned encrypted (DES-ECB).
   The server decrypts them and upgrades quality to 320 kbps before the browser
   ever sees a URL.

3. **Secret keeping.** The Gemini API key stays server-side in `config.json`
   and never reaches the browser, where page source is visible to anyone.

Everything else — playback, UI, the entire library, offline shell — lives in
the browser.

---

## 4. Component breakdown

### Frontend (PWA)
- Single-page app: Home, Search, Library, Trivia, Queue, plus a full
  now-playing screen and a compact mini-player.
- **Service worker** with a *network-first* shell strategy: updates always load
  immediately, with the cache as an offline fallback — never stuck on stale code.
- **Media Session API** drives the lock-screen / notification artwork, metadata,
  and transport controls.
- All personal data (liked songs, playlists, history, settings) lives in
  `localStorage` — nothing leaves the device.

### Backend (Flask, Termux)
- ~1,000 lines of Python: metadata proxy, DES-ECB decryption, multi-strategy
  stream-URL resolution, robust chart/album/playlist parsing with search
  fallbacks, LRCLIB synced-lyrics, and the Gemini AI endpoints.
- **Daemon mode** with PID management and a `naada.sh` control script
  (`start / stop / restart / status / logs / fg`).
- **Rotating structured logs** (5 MB × 5) — every request and decode logged in a
  greppable format for fast diagnosis.
- Graceful degradation everywhere: a failed upstream call returns valid empty
  JSON, never a crash.

### Data sources
- **Music + metadata:** unofficial JioSaavn endpoints (search, charts, albums,
  artists, playlists, radio).
- **Lyrics:** native first, then **LRCLIB** (free, open) for synced lyrics.
- **AI:** Google **Gemini Flash** (free tier) for song/movie anecdotes and
  trivia, with a model-fallback chain and in-memory response caching.

---

## 5. Engineering highlights (the hard-won parts)

- **Decryption was DES-ECB, not CBC.** Stream URLs decrypted to `https://`
  followed by binary garbage until the cipher mode was correctly identified as
  ECB. In ECB each 8-byte block decrypts independently; the earlier CBC attempt
  corrupted every block after the first. Fixed by switching modes and stripping
  PKCS5 padding.

- **The service-worker cache trap.** A fixed cache name with a cache-first
  strategy silently served stale JS/CSS for several iterations, masking real
  fixes. Resolved by versioning the cache and moving the app shell to
  network-first — and the lesson is baked into the SW design.

- **A missing helper crashed playback.** A refactor left one function called but
  undefined; it threw at runtime (invisible to syntax checks) and aborted every
  play. Found by building an on-screen mobile debug panel, then hardened against
  by an automated "called-but-undefined" scan.

- **AI "thinking" tokens ate the output.** The reasoning model spent its token
  budget thinking and returned empty text for structured (JSON) trivia. Fixed by
  disabling the thinking budget for short structured calls and adding a
  model-fallback chain plus tolerant JSON parsing.

- **Invisible overlay ate taps.** A full-screen now-playing panel, hidden only
  by transform, still captured touches. Fixed with explicit
  `pointer-events`/`visibility` handling.

Each of these was diagnosed from device logs and an in-app diagnostics panel,
then fixed at the root and guarded against recurrence.

---

## 6. Privacy & data

- No account, no sign-in, no analytics, no third-party tracking.
- Library, history, and preferences never leave the phone (`localStorage`).
- The Gemini key is server-side only; AI features are optional and off if no key
  is configured.
- Audio streams directly from the CDN to the device.

---

## 7. Deliberate non-goals

- **Audio only.** No video — that would require a path that conflicts with
  platform terms of service, deliberately avoided.
- **Not a public service.** Personal use only; no hosting, no distribution.
- **No native wrapper (yet).** The OS media-notification source badge shows the
  browser's icon because the app runs inside the browser — only a native shell
  could change that. Considered a fair trade for the zero-install simplicity.

---

## 8. Tech stack

| Layer        | Technology                                              |
|--------------|---------------------------------------------------------|
| Frontend     | Vanilla JS, HTML, CSS (no framework), PWA + service worker |
| Player       | HTML5 Audio + Media Session API                         |
| Backend      | Python 3, Flask                                         |
| Crypto       | `cryptography` (DES-ECB)                                 |
| AI           | Google Gemini Flash (free tier)                         |
| Lyrics       | LRCLIB (synced) + native fallback                       |
| Runtime      | Termux on Android, HTTPS on localhost                   |
| Storage      | Browser `localStorage` (client), `config.json` (server) |

---

## 9. Operating it

```bash
./naada.sh start      # run in background
./naada.sh logs       # watch live logs
./naada.sh status     # health + URL
./naada.sh restart    # after any update
```

Open `https://localhost:5443` in Chrome → install to home screen. Optional:
`termux-wake-lock` and boot auto-start so it runs hands-free.

---

*Built iteratively, debugged empirically, documented honestly.*
