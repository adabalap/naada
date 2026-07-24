# Naada v30 — Podcasts

Short answer to your question: **yes, and the sources are excellent.** But
podcasts are not long songs, and treating them as such is how music apps get
bloated. Here's what I did and why.

---

## The sources

Two free, keyless services working together:

| | what it gives | cost |
|---|---|---|
| **iTunes Search API** | discovery across ~5M shows, and each show's RSS `feedUrl` | free, **no key, no account** |
| **The RSS feed itself** | the full episode archive, audio URLs, durations, artwork | free, it's just XML |

A podcast *is* an RSS feed. Every directory is just a pointer to one. So
once you have the feed URL, nothing can be taken away from you — no key to
expire, no quota, no account to be suspended.

I considered **Podcast Index**, which is the more purist open-source
directory. It needs an API key plus HMAC request signing. For a personal app
this pair is simpler and can't be revoked, so I went with it. Easy to swap
later if you'd rather.

Search defaults to the **Indian store** (`country=IN`), so Telugu, Hindi and
Tamil shows surface first.

## Why podcasts got a nav slot and radio didn't

Radio slotted in as a search chip because a station is just a stream — same
shape as a track. A podcast isn't:

- **Two levels** (show → episode), not one
- **30–120 minutes**, not 3–5
- **Resume is mandatory**, not a nicety
- **Speed and skip** matter; **shuffle and repeat are meaningless**
- You **follow shows**, you don't **like episodes**

Forcing that through the music model would have made both worse.

**So Trivia gave up its nav slot and moved into Library.** My reasoning: a
nav slot is the most expensive real estate in the app — four slots, each a
quarter of your primary navigation. Trivia is an occasional-use AI novelty
that needs a Gemini key. Podcasts, if you listen to them, are daily.

Trivia is now a labelled row at the top of Library with a back button.
Nothing was removed. **If you disagree, it's a one-line swap** — say so and
I'll flip it back.

## What podcasts actually do

**Every episode resumes.** Nobody restarts a 90-minute interview because
they closed the app. Episode rows show a progress bar and "20:00 in", and
finished episodes get a check and dim out.

**Speed control** (1× → 2×) appears in the player *only* for podcasts, and
music never inherits it — switch back to a song and you're at 1× again.

**Prev/next become skip ∓30s** while a podcast is playing. Jumping between
90-minute episodes mid-listen is almost never what you want; missing thirty
seconds is.

**Follow shows** to pin them to the top of the Podcasts tab.

---

## Two bugs found while building

**The full player was rendering *underneath* sheets.** `.now-screen` sat at
z-index 450, `.sheet` at 500. Play an episode from a show sheet, tap the
mini-player, and you'd get the sheet again with the player stranded below.
This was pre-existing — it affected playlist sheets too. The stack is now
stated once, in one place, with the reasoning written down so it stops
drifting:

```
pages 1 · mini-player 400 · sheets 500 · NOW-SCREEN 600
· action menus 800 · pickers 820 · text modals 840 · toast 900
```

**A 1h52m episode displayed as "112:10".** The duration formatter assumed
everything was under an hour — fine for songs, nonsense for podcasts. Now
hour-aware, verified across six cases.

## Security note

The feed URL comes from the client, so `/api/podcast/episodes` could have
been pointed at your own network. It now rejects non-HTTP schemes, localhost
and private ranges (10.x, 192.168.x, 172.16–31.x, 169.254.x). Verified
against eight cases including `file:///etc/passwd`.

Feeds are also size-capped at 6MB and 200 episodes — some archives are
enormous and Termux doesn't have memory to spare.

---

## Honest caveat, same as radio

**I could not test against the live iTunes API or a real RSS feed** — my
sandbox has no route to them.

What I *did* verify: duration parsing across six formats, the SSRF guard
across eight URLs, and RSS extraction against a realistic feed fixture
(audio-less items dropped, episode art preferred over show art, description
falling back correctly). Then the whole UI against mocked responses — search,
episodes, follow, playback, speed, per-episode progress, played state.

What needs your device: that iTunes responds and that real feeds parse. If a
show fails, `./naada.sh logs` will name it.

## Testing

10 new podcast tests, plus full regression over five previous releases —
back navigation, folders, delete flows, theme, pinning, bulk operations,
radio, resume, sleep timer. No JS errors. Layout measured at 360px and
412px with no overflow.
