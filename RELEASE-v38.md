# Naada v38 — New releases & latest songs now follow your language

## The bug

You were seeing mostly Hindi / Bollywood in New Releases and Latest even
when your language was set to something else. Good catch — that's a
relevance bug, not a nitpick.

It was entirely server-side. The frontend was correctly sending your
language on every call; the JioSaavn backend was ignoring it. Two reasons,
both fixed:

**1. The session language cookie was never set per-request.**
JioSaavn weights content (albums, new releases, featured playlists) by a
language PRIORITY list stored in a cookie called `L` — not just the
per-call `language` parameter. With no `L` cookie, a single
`language=telugu` argument gets outvoted and you get Bollywood anyway.

Now every content call sets `L` to lead with your chosen language, keeping
the others as fallbacks so thin catalogues never return an empty shelf.
For example, Telugu now sends `L=telugu,hindi,english,tamil`.

**2. The hardcoded Accept-Language header was biased.**
The HTTP session advertised `en-IN,en,te,hi` — literally telling JioSaavn
to prefer Telugu then Hindi, for everyone, regardless of choice. That's now
neutral (`en-IN,en`), so the per-request cookie is the single source of
truth for language.

## What this fixes

- **New Releases** (`content.getAlbums`) — now returns albums in your
  language
- **Featured playlists** — same
- **Latest songs** — same
- **Trending** was already correct (it uses per-language chart tokens) and
  is unchanged

Your language choice from the pill still persists across sessions as
before; nothing changed about how you pick it. The default (when you've
never chosen) is Telugu, and I aligned a stray internal fallback that still
said "hindi" so a fallback can never silently drag you back to Bollywood.

## Honest caveat

I can't reach the live JioSaavn API from my build environment, so I could
not watch real Telugu albums come back end-to-end. What I *did* verify: the
`L` cookie is constructed and sent correctly for every language, leads with
the right one, and is correctly omitted on calls that aren't
language-specific (so album-detail lookups are untouched). The remaining
confirmation — that JioSaavn honours the cookie as expected — is a quick
check on your device: set the language to Telugu, pull to refresh Home, and
New Releases should shift.

If any shelf still looks off, tell me which one and I'll dig into that
specific endpoint's response shape.

## Testing

All eight regression suites pass with no JS errors. The language-cookie
construction is unit-verified across all four languages plus the
no-language case.
