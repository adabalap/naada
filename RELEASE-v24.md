# Naada v24 — UI, interaction & curation release

Everything here is frontend only (`static/`). No backend changes, no new
dependencies, no build step. Existing user data migrates automatically —
the pinned shelf and theme choice slot into saved configs and backup files.

## Install

```bash
cd ~/naada
./naada.sh stop
# back up your current copy first if you like:
#   cp -r static static.bak
# unzip this archive over your existing folder, then:
./naada.sh start
```

Then open the app and tap **Home → ⚙ → About Naada → Update now** (or just
hard-reload). The service worker version was bumped, so caches invalidate
on their own.

---

## Appearance setting

Home → ⚙ → **Appearance**: Light / Auto / Dark.

Auto follows the OS and switches live (e.g. at sunset). The choice is
resolved before first paint via a tiny inline script, so there's no flash of
the wrong theme on load. Dark is a deep aubergine canvas built from its own
token set rather than an inverted light theme.

## Dynamic colour from album art

The now-playing screen and mini-player take their wash from the artwork of
the current song. Colours are sampled on a 16×16 offscreen canvas, weighted
toward the most saturated pixels (averaging everything gives mud), then
luminance-clamped so the result always reads against the text in either
theme. Results are cached per image URL.

Cross-origin artwork can taint the canvas, which throws — that's caught, and
the default gradient stays. A broken or missing image is a no-op.

## Gestures on the mini-player

- **Swipe left** → next track
- **Swipe right** → previous track
- **Swipe up** → open the full player

The bar follows your finger and flings off in the direction of travel before
the new track springs in. Horizontal vs. vertical intent is locked in after
10px, so a page scroll that happens to start on the bar still scrolls the
page, and a downward drag is handed straight back to the scroller. A
completed swipe won't also register as a tap.

## Long-press any song

Long-press (or right-click on desktop) any song row for a context menu:
Play next · Add to queue · Add to playlist · Like · Pin to Home ·
Start radio · Remove.

**Remove is context-aware** — it means unlike in Liked, remove-from-history
in Recents, remove-from-playlist inside a playlist, and remove-from-queue in
the queue.

## Pinning

Pin songs to a **Pinned** shelf at the top of Home. The shelf appears only
when something is pinned, so it never nags with an empty state. Playlists
can be pinned too, and sort to the front of the Library grid.

## Playlist CRUD

The ⋮ on any playlist card, or in the playlist detail header, opens a proper
action sheet: **Rename · Duplicate · Pin · Queue all songs · Delete**. This
replaces the old text-prompt dialog.

## Curating playlists from playlists

Open a playlist → **Select** → tap songs → **Copy to… / Move to… / Remove**.

The target picker excludes the playlist you're working in, and its **+**
button creates a new playlist *born already containing your selection* —
which is the fast path for splitting a big collection into smaller ones.
Move removes the songs from the source; Copy leaves them.

## Drag to reorder

In Select mode, playlist rows get a grip handle. Drag to reorder, with both
mouse (HTML5 drag-and-drop) and touch (pointer events) paths, matching how
the queue screen already behaves.

## Navigation

Queue left the bottom nav — now **Home · Search · Library · Trivia**. It
lives in the player, reached from the now-playing screen where you actually
want it mid-listen, with a back button to return. Fewer tabs, and each one
is a destination rather than a mode.

---

## Fixed / hardened

- Play/pause icons stay truthful when the **OS** pauses audio (phone call,
  Bluetooth, media notification) — the audio element itself is now the
  source of truth, and state re-syncs when returning from background.
- Backups include pinned songs and the theme choice.
- Sheet stacking: the add-to-playlist picker sits above the playlist detail
  sheet, so bulk Copy/Move taps always land.
- Keyboard focus is visible on every interactive control.
- `prefers-reduced-motion` is respected app-wide, including the new swipe
  and tint transitions.

## Already correct — verified, no changes needed

- **Favicon**: the `/favicon.ico` route, the icon files, and the `<link>`
  tags were all already right.
- **Background playback**: the Media Session API (lock-screen controls,
  artwork, seek) was already fully wired. Audio streams directly from the
  CDN and the service worker never intercepts it, so minimising the app
  doesn't interrupt playback.

## Testing note

These changes were verified in headless Chromium against a static file
server — 9 new feature tests plus a regression pass over the previous
release, with no JS errors. Anything that calls `/api/*` (radio from the
context menu, charts, lyrics) is verified for correct request wiring only,
not end-to-end. Worth a quick smoke test on your Termux setup after
deploying.
