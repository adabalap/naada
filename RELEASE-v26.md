# Naada v26 — Back button, song menus, install prompt

Four things you reported. Two I fixed outright, one I made properly
discoverable, and one I can only partly fix — the honest reasons are below.

---

## 1. Back button now works (fixed)

**This was a real bug and entirely my fault.** The app had *no* History API
integration at all — not a single `pushState` in 3,000 lines. So Android's
back gesture went straight past the app and minimised it, because from the
system's point of view there was only ever one page.

Now every sheet, menu and tab change puts an entry on the history stack:

- Back with a sheet open → **closes the sheet**
- Back with a menu over a sheet → **closes the menu, keeps the sheet**
- Back on Library → **returns to Home**
- Back on Home with nothing open → **minimises**, as any Android app should

Closing something with its X button also stays in step, so you never get a
"dead" back press that appears to do nothing.

Verified across four scenarios including nested overlays.

## 2. Deleting songs — now visible (was hidden)

Every song row has a **⋮ button** on the right. Tap it for: Play next, Add
to queue, Add to playlist, Like, Pin, Start radio, and **Remove**.

Remove does the right thing for wherever you tapped — unlike from Liked,
remove from history, **remove from this album**, or remove from the queue.

The action already existed but only via long-press, which is a gesture you
have to already know about. **A hidden gesture isn't a UI.** That was a
design failure on my part, not a missing feature. Long-press still works if
you've learned it.

For several songs at once: **Select** → tick → Remove.

## 3. The Chrome icon (can't be fixed in code)

Your screenshot confirms it: the media notification's source badge is
Chrome's logo, and there's no Naada icon on your home screen. Naada is
running as a **Chrome tab**.

**A web page cannot override that badge.** Only an installed app can. This
isn't a favicon or manifest problem — I checked, and yours are both correct.

So rather than tell you to go hunting in a menu, About now has a **real
Install button** that fires Chrome's native install prompt, plus **four
measured diagnostics**:

- Running as installed app / in a browser tab
- Secure context (HTTPS or localhost)
- Service worker active
- Whether Chrome considers the app installable

**If the Install button doesn't appear**, Chrome has decided the app isn't
installable, and with a Termux setup that is almost always **the HTTPS
certificate**. A self-signed cert that you click through leaves the page in
a state where Chrome refuses to install it. The diagnostics will show you
which check is failing.

## 4. Playback when minimised (partly fixed)

I traced the actual playback path this time. Audio streams **directly from
the JioSaavn CDN** — Termux isn't in the path — so a sleeping server can't
cut a song off mid-play. But the **URL for each next song** does come from
Termux. So when a track ends while backgrounded and Android has throttled
Termux, the lookup fails and the music stops *between* songs.

**Fixed:** Naada now prefetches the next track's stream URL about four
seconds into the current song, while still in the foreground. Track changes
no longer depend on the server being reachable at that moment.

**Still outstanding, and honestly outside my reach:** if Chrome itself gets
frozen, nothing in the page can run. That is the browser-tab problem in
item 3, and installing to the home screen is the actual fix. On Samsung
specifically, also check **Settings → Battery → Background usage limits**
and make sure both Chrome and Termux are out of "Sleeping apps".

Your Termux notification shows a wake lock held, which is right — keep that.

---

## Also in this build

- The ⋮ tap target is a full 44px even though it looks light, since it's
  the route to destructive actions.
- Row taps still play; only the ⋮ opens the menu.
- In Select mode the ⋮ gives way to checkboxes and drag handles, so the row
  never shows two competing affordances.

## Testing

Back navigation verified across 4 scenarios, delete flows across 5, plus
full regression over the previous three releases. No JS errors. As always,
anything hitting `/api/*` is verified for wiring only — and background
playback can only truly be confirmed on your device.
