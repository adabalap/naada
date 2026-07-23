# Naada · నాద 🎵
### Personal Indian Music Player — v2

Ad-free · 320kbps · Hindi · Telugu · Tamil · English · Runs on Termux · HTTPS PWA

---

## Quick start

```bash
cd ~/naada
pip install flask requests cryptography flask-cors
./naada.sh start
```

Chrome → `https://localhost:5443` → accept cert → ⋮ → **Add to Home Screen**

> `flask-cors` is optional — the app runs fine without it (same-origin PWA).

---

## Daemon commands

| Command | Action |
|---|---|
| `./naada.sh start` | Start in background |
| `./naada.sh stop` | Stop |
| `./naada.sh restart` | Restart |
| `./naada.sh status` | Status + URL |
| `./naada.sh logs` | Tail live logs |
| `./naada.sh fg` | Foreground (debug) |

---

## What's new in v2

**Rich landing page** — your library comes first: Jump back in (recents), your
liked songs, your playlists, then Trending, New Releases, Latest Movie Songs,
and Made-for-you featured playlists. Each is a horizontal shelf.

**Customizable home** — tap the ⚙ icon (top-right of Home) to toggle which
shelves appear and set your default language. Saved across sessions.

**Mini-player + full Now-Playing screen** — the bottom bar is now a compact
mini-player (art, title, like, play, next) with a live progress line. Tap it to
expand into a full-screen player with a large spinning vinyl, big controls,
lyrics, radio, and add-to-playlist. Tap the chevron to minimize.

**Queue editing** — drag the grip handle to reorder, tap ✕ to remove a track,
"Clear" to empty the queue (keeps the current song).

**Library & playlist editing** — open a playlist → "Edit" to remove songs;
the ⋮ menu lets you rename or delete the playlist.

**Synced lyrics** — JioSaavn lyrics first, then **LRCLIB** fallback (free, open).
If LRCLIB has time-synced lyrics, they highlight line-by-line as the song plays
and auto-scroll. A SYNCED / LRCLIB badge shows the source.

**More charts** — trending, new releases, and latest movie songs per language,
plus curated featured playlists, all from one fast `/api/home/<lang>` call.

> **Audio-only by design.** Naada deliberately does not do video — that would
> require the YouTube/extraction path we chose to avoid for compliance.

---

## Logging & troubleshooting

```bash
./naada.sh logs                              # live tail
grep ERROR ~/naada/logs/naada.log            # errors
grep "Home .* trending" logs/naada.log       # did home shelves populate?
grep "Lyrics from" logs/naada.log            # lyrics source per song
grep "Stream resolved" logs/naada.log        # playback URLs
```

| Problem | Fix |
|---|---|
| Songs skip / decrypt errors | `pip install -U cryptography && ./naada.sh restart` |
| A home shelf is empty | Check `grep "Home" logs/naada.log` — JioSaavn shape may have shifted |
| Charts fall back to search | Token rotated; search fallback still returns relevant songs |
| No lyrics | Many songs have none; LRCLIB covers most popular tracks |
| Port in use | `fuser -k 5443/tcp; ./naada.sh start` |

---

## Stream URL decryption

JioSaavn uses **DES-ECB** (key `38346591`) — not CBC. The `encrypted_media_url`
is base64 of the ECB ciphertext; decrypt, strip PKCS5 padding, upgrade to 320kbps.

## File layout

```
naada/
├── app.py            ← Flask + JioSaavn + LRCLIB + daemon + logging
├── naada.sh          ← start/stop/restart/status/logs/fg
├── requirements.txt
├── logs/naada.log    ← rotating 5MB×5
└── static/
    ├── index.html · app.css · app.js · manifest.json · sw.js
    └── icons/icon.svg
```

---

## AI features (Gemini)

Naada can show an AI "story" card about the playing song's movie/album, and a
Trivia tab that generates cards from your listening history.

### Setup
1. Get a free Gemini API key at https://aistudio.google.com (Get API key)
2. Paste it into `config.json`:
   ```json
   { "gemini_api_key": "AIza...your-key..." }
   ```
   (or set the `GEMINI_API_KEY` environment variable)
3. `./naada.sh restart`

The key stays **server-side** — it's never sent to the browser. Responses are
cached in memory, so each song's story and each history's trivia is generated
once. Uses the free-tier `gemini-2.5-flash` model.

### Controls
- **Story card**: toggle "Show AI story card" in Home → ⚙ settings. Or tap the
  ✨ button on the now-playing screen to fetch on demand.
- **Trivia tab**: bottom nav → Trivia → "New cards" (builds from recent plays).

If no key is configured, AI features hide themselves and the rest of the app
works normally.

> Keep `config.json` private — it holds your key. Share `config.example.json`
> (which has a placeholder) if you ever publish the folder.

## Add album to Library

Open any album → **Add to Library**. It's saved as a playlist (by album name)
under Library → Playlists, and appears in your home "Your playlists" shelf.

---

## Mood playlists (AI)

Search → **✨ Mood** → type a vibe ("rainy evening Telugu melodies",
"high-energy workout", "90s Hindi romance") and Naada builds a playlist:
Gemini suggests fitting search queries, the server finds real songs, and you
get a titled playlist you can play or save. Tap a suggestion chip for ideas.

## AI rate-limit handling

To stay within Gemini's free tier:
- **Story cards are debounced** — fetched only after ~10s of listening, so
  skipping through songs costs zero AI calls.
- **Responses are cached to disk** (`ai_cache.json`) — a song's story or a
  mood plan is generated once, then reused free across restarts.
- **Server-side rate limiter** caps calls per minute and backs off for 60s if
  Gemini returns a rate-limit error, so AI features degrade calmly instead of
  erroring.

---

## Your data: backup & restore

All your data (library, playlists, liked songs, history, settings) is stored
**on your device** in the browser — it is never uploaded anywhere.

To protect it across updates, reinstalls, or a new phone:
- **Home → ⚙ → About Naada → Back up my data** downloads a small JSON file.
- **Restore from file** reads it back (replaces current data on that device).

**Important:** clearing only "Cached images and files" is safe. Clearing
"Cookies and site data", or uninstalling the PWA, **erases your data** — so
back up first. The export file is the universal safety net.

## Updating

**Home → ⚙ → About Naada → Update now** fetches the latest version and reloads,
**without touching your data**. No need to clear cache manually anymore.
(After deploying new files to the server, just tap Update now in the app.)
# naada
