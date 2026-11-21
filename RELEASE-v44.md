# Naada v44 — What that screenshot actually shows

## Your screenshot solved it, and it isn't a favicon

The chip reading `19:08 [Chrome] Ye Vaipuga` is the **Android media
notification**, not a browser tab favicon. Those are different things with
different rules, and I'd been fixing the wrong one.

Android draws the small badge on that chip from **whichever app owns the
media session**. It is showing Chrome's logo because Chrome owns it. The web
APIs give us `MediaMetadata` — title, artist, album, artwork — and there is
**no field for that badge**. No amount of favicon, manifest, or icon work
changes it. Your earlier screenshots proved the parts we *do* control already
work: the song title and album art render correctly.

The badge becomes Naada's only when the media session is owned by an
**installed WebAPK** instead of Chrome.

### So why is Chrome still owning it?

One of two reasons, and they look identical on the home screen:

1. **You're opening Naada in a browser tab** rather than from the installed
   app icon.
2. **It installed as a "shortcut" rather than a real WebAPK.** Chrome falls
   back to shortcut mode when its link to the Play Store is broken locally.
   A shortcut is just a bookmark: it opens Chrome, so the badge stays
   Chrome's — and Android freezes it far sooner, which is very likely also
   feeding your background-playback problem.

### The app now tells you which one you're in

About → diagnostics has a new, decisive line:

- **Installed (WebAPK)** → "the media notification should show Naada's icon"
- **Browser tab** → explains that this is exactly why the chip shows Chrome,
  and what to do about it

**How to fix it on your phone:**
1. Open Naada from its **home-screen icon**, not a tab or bookmark.
2. If an address bar appears at the top, it's a shortcut, not an app.
   Uninstall it, then in Chrome use **⋮ menu → Install app** (not "Add to
   Home screen").
3. Confirm in Android **Settings → Apps**: a real WebAPK appears there as its
   own entry named Naada. A shortcut does not.
4. Check About → diagnostics; the top line should now say **installed app**.

## Songs sleeping when the screen is off

I re-audited the media stack and it is correct: `setPositionState` refreshes
every second during playback, playback state is kept in sync, and all action
handlers are registered. That matters because Chrome ties its **media
foreground service** to a live media session — which is the mechanism that
keeps audio running with the screen off. It's wired properly.

Which means the remaining stops are not a code gap. They are the OS
restriction, and the two levers that actually move it are both on the device:

1. **Run the installed WebAPK, not a tab.** A browser tab is a far weaker
   citizen to Android's battery manager than an installed app. Given your
   screenshot shows a tab, this is very likely the dominant factor — and it
   fixes the icon at the same time.
2. **Battery → Unrestricted** for Naada, plus clearing it from any
   "sleeping apps" / "deep sleep" list on Samsung/Xiaomi/OnePlus.

I want to be straight rather than ship another speculative "fix": with the
gapless transitions (v41), the queue-end fix (v43), an always-on OCI server,
and a correct media session, the web-side work is done. Item 1 above is the
missing piece, and it's the one I can't do from here.

The genuinely unkillable version needs Android's native foreground service,
which no PWA can create — that's the TWA/APK wrapper, still paused at your
request.

## Testing

Regression suites pass with no JS errors. The new diagnostic was verified in
an emulated mobile context, where it correctly reports the browser-tab state.
