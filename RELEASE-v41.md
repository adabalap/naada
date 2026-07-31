# Naada v41 — Gapless playback (the real background fix) + UI polish

## The background-playback problem, properly diagnosed

Earlier releases treated this as "Android throttling, mostly out of our
hands." That was incomplete. Looking at the actual transition code, the app
was doing this at **every single track boundary**:

```
song ends -> playNext() -> play() -> await a URL -> set src
          -> download and buffer over the network -> finally, sound
```

So every few minutes the audio element went **silent for an unpredictable
stretch**. Two bad things happen in that gap:

1. A backgrounded tab's network requests can be throttled or deferred
   indefinitely — so the buffering step may simply never finish, and the
   music stops *between* songs rather than mid-song. (That's exactly the
   symptom you described.)
2. **While silent, Chrome no longer counts the tab as "playing audio"** —
   which is precisely what makes it eligible to be frozen. The gap wasn't
   just a victim of throttling; it was inviting it.

## The fix: arm the next track before the current one ends

Naada now resolves the next track's source *while the current song is still
playing* — a blob from the on-device byte buffer if we have it, otherwise a
CDN URL warmed into the HTTP cache via a hidden standby element.

When `ended` fires, we set `src` and call `play()` **synchronously**. No
await, no fetch, no gap. The tab produces audio continuously, which is both
what you want to hear and what stops Chrome freezing it.

**Verified:** firing a track transition made **zero** API calls and advanced
the queue instantly. Arm invalidation is tested across queue jumps, stale
URLs, `repeat=one`, and end-of-queue wrap with `repeat=all`.

Shuffle can't know what's next, so it falls back to the old path — correct
rather than guessing.

## A correction I owe you

v37 added a Screen Wake Lock with a comment claiming it made the OS "less
eager to freeze us" in the background. **That was wrong.** The Screen Wake
Lock is automatically released whenever the document becomes hidden, and
`request()` rejects outright if the page isn't visible — so it does nothing
for background playback. The code stays (it usefully stops the screen
sleeping while you're looking at the player, e.g. phone on a dashboard) but
the comment now says what it actually does.

The things that genuinely keep background audio alive, in order:
1. **Never going silent at a track boundary** — the new arming above.
2. MediaSession metadata + playback state (already in place).
3. Auto-resume when the OS pauses us anyway (v37).
4. Installing as a PWA + battery Unrestricted — still the biggest lever.

## UI polish

**Touch targets.** Measured 30 controls below the ~44px comfortable floor —
icon buttons at 38px, the mini-player like button at 36px, and nav items only
**30px tall inside a 62px bar**, meaning over half the nav was dead space.

- Icon buttons, chips, and player controls got invisible centred hit areas
  via `::after`. Visuals are pixel-identical; the tappable region grows.
  Verified by hit-testing 3px outside each button's visual edge.
- Nav items now stretch to fill the bar: **101×30 -> 101×61**. (A
  pseudo-element was tried first and lost the hit test to something painting
  above it — stretching the real box is simpler and provably works.)
- Search chips raised to a 36px minimum.

**Two new settings, because both spend something that's yours:**
- **Download songs ahead for offline play** — buffering costs mobile data,
  so it's now a choice rather than something done quietly to your data plan.
  The OS Data Saver flag is still honoured on top of it.
- **Vibrate on play, pause and skip** — a 10ms tick on the transport
  controls only. Native apps do this, and its absence is part of why web
  apps feel less solid. Not on every tap; that gets irritating fast.

## Honest limits

- I can't reach your OCI box or live JioSaavn from my build environment, so
  the gapless path is verified against a mocked server (zero API calls
  through a transition, arm invalidation across five scenarios). The
  real-device confirmation is the satisfying one: lock the phone on a long
  queue and it should now run to the end of the playlist.
- Image previews don't render back to me, so every UI claim above is a
  **measurement** (box sizes, hit tests, overlap checks), not a look. If
  something reads wrong on screen, tell me which and I'll adjust.
- The last increment to true Spotify parity is Android's native foreground
  service, which no PWA can create. That needs the TWA/APK wrapper, still
  paused.

## Testing

All 11 regression suites pass with no JS errors, including two new ones for
gapless transitions and arm invalidation.
