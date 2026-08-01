# Naada v42 — Five bug fixes + wake lock

Three of these were regressions I introduced in v41. Detail on each, since
the causes are worth knowing.

## 1. Scroll-up arrow appeared on the left ✅ my regression

The v41 touch-target work added `position: relative` to a list of controls
so their invisible hit areas would anchor correctly. I put `.to-top` in that
list — but it was already `position: fixed`. Declaring `relative` silently
overrode that, so the button dropped out of its fixed bottom-right corner and
rendered inline on the left.

Same bug also hit `.pl-card-menu` (the album card's ⋮), which was
`position: absolute` and had quietly left its corner. You hadn't reported it
yet; it's fixed too.

Both keep the enlarged hit area — `absolute` and `fixed` are positioned
ancestors as well, so `::after` works fine on them.

**Verified:** `position: fixed`, 16px from the right edge.

## 2. Minimised view kept showing the first song's title ✅ my regression

This was a direct consequence of the v41 gapless fix. That change added a
fast path that deliberately bypasses `play()` to avoid a silent gap at track
boundaries — but `updateMediaSession()` was only ever called *inside*
`play()`. So the lock screen and notification kept showing whatever was
playing when the queue started.

`updateMediaSession()` now lives in `updateMeta()`, which every path that
changes track calls. That's the correct single home for it.

**Verified:** firing a gapless transition moves the notification title from
"First Song" to "Second Song".

## 3. No previous button ✅

The mini-player had like / play / next but no previous — you could only skip
forward. Added, and wired to the existing `playPrev()`, which already had the
right behaviour: restart the current track if you're more than 3 seconds in,
otherwise go to the previous one.

**Verified:** no layout overflow at 320px, 360px or 412px.

## 4. User pause was ignored and playback resumed ✅ my regression — the worst one

v37's auto-resume was meant to fight the OS pausing us in the background. It
decided "was this the user?" by watching clicks on `#ctrl-play` and
`#mini-play`.

That was too narrow. Pauses also arrive from `togglePlay()` directly, the
keyboard shortcut, the lock-screen button, and headphone controls. None of
those touched those two handlers, so the app classified a deliberate pause as
OS interference and resumed playback ~500ms later. Infuriating, and entirely
my fault.

Intent is now recorded **at the source** — inside `togglePlay()` and the
MediaSession `pause` handler, the two funnels every human pause actually
flows through.

**Verified with a 30-second test tone** (a short clip ended mid-test and
masked the bug first time round):
- User pause → still paused after 2s, `wantPlaying=false` ✅
- OS-style pause → auto-resumes ✅

The distinction that matters is preserved: we still fight the OS, never you.

## 5. Favicon in the minimised / recents view ⚠️ not a code bug

I checked the whole chain and the server side is correct: the manifest is
served as `application/manifest+json` with `no-cache`, and all four icons
return 200 — `any` icons transparent-cornered, `maskable` icons full-bleed,
exactly as the spec wants.

The reason it still looks wrong: **the app-switcher icon is copied into the
generated WebAPK when you install, and Android keeps using that copy.** It is
not re-read from the manifest. Since the icons changed after you installed,
recents can keep showing the old or blank icon indefinitely.

**The fix is to uninstall Naada and install it again.** That's the only
reliable way to make Android regenerate the WebAPK with current icons. I've
added a line to About → diagnostics stating this, so the app tells you rather
than leaving you guessing.

## Wake lock — now enabled, as a setting

New toggle: **☀️ Keep screen on while playing** (Settings, on by default).

The implementation is also more robust than v37's: it no longer attempts a
request while the document is hidden (which always rejects), re-acquires
properly on return to foreground, and disables the toggle with an
explanation on browsers that don't support the API.

**What it genuinely does:** stops the *screen* sleeping while music plays and
Naada is on screen — the dashboard case, or propped up in the kitchen.

**What it does not do:** keep audio alive when minimised. The spec releases
the lock automatically whenever the document becomes hidden. I want to be
straight about that rather than let the name imply more than it delivers.
Background survival still comes from gapless transitions, MediaSession, and
battery-Unrestricted.

Because it holds the display on, it costs battery — hence a setting you own
rather than something imposed.

## Testing

All 12 regression suites pass with no JS errors, including a new pause-intent
suite covering both the user-pause and OS-pause paths.
