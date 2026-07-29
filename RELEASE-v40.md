# Naada v40 — Moved to OCI: the background-playback problem is largely solved

You moved the server from Termux (on the phone) to an always-on OCI free-tier
VM behind Cloudflare. That's the single most impactful change in this whole
saga, and it reshapes the background-playback story.

## Why this matters so much

The "plays 2-3 songs then stops when the phone sleeps" bug had ONE root
cause: the stream-URL server lived on the phone, and Android froze it during
sleep. Every track change needed that frozen server, so playback died.

**On OCI, the server can't be frozen by your phone's battery manager.** It's
a cloud VM. The dependency that caused the bug is simply gone — the server
is always there to hand out the next track's URL, sleep or not.

Combined with the deep URL cache (v39) and wake-lock/auto-resume (v37), the
web-platform side is now as robust as it gets. The remaining gap to Spotify
is only Android's native foreground-service guarantee, which no PWA can have.

## What changed in this release

**1. `start-cloud` now binds correctly for OCI.**
It defaults to `0.0.0.0` so Cloudflare's edge can reach the origin over your
public IP. If you use a Cloudflare Tunnel (cloudflared on the box), lock it
down with:
```
NAADA_HOST=127.0.0.1 ./naada.sh start-cloud
```
so nothing listens on the public interface at all.

**2. Security headers for public exposure.**
The app is on the public internet now, so every response carries:
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: SAMEORIGIN` (anti-clickjacking)
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Permissions-Policy` locking off geolocation/mic/camera

HSTS is deliberately left to Cloudflare (it owns TLS and can apply it edge-
wide without risking an origin lockout).

**3. Access gate reminder.**
Your app is reachable by anyone with the URL, and it spends your Gemini
quota. Two options, either works:
- `NAADA_TOKEN=something ./naada.sh start-cloud`, then visit
  `https://your-domain/?k=something` once (year-long cookie), OR
- Put Cloudflare Access in front (zero-trust, no app change).

Verified: with a token set, protected paths return 401, icons/manifest stay
open so the PWA still installs, and the correct token sets a secure
HttpOnly cookie.

**4. Docs/labels updated from Termux → OCI.**
The About panel and install help no longer say "Termux". The deep-cache and
prefetch code comments now explain they're for latency + offline resilience
(still valuable) rather than working around a frozen phone server.

## The updated Naada vs Spotify picture

| | Spotify | Naada on OCI |
|---|---|---|
| Server frozen on sleep? | N/A | **No longer** — it's a cloud VM |
| URL available between tracks? | Always | **Always** (cloud) + cached 20 deep |
| Plays with no phone network? | Downloads | 6-track byte buffer |
| OS can freeze the audio tab? | Protected service | Possible, but wake-lock + install make it rare |

The realistic outcome: **installed as a PWA + battery Unrestricted + OCI
server**, a long queue should now play continuously through a drive or jog.
The last increment (a truly unkillable native foreground service) needs the
TWA/APK wrapper, which remains paused.

## Your setup checklist (unchanged, still the biggest levers)

1. Install as a PWA from your Cloudflare HTTPS domain.
2. Battery → Unrestricted for the installed app.
3. On Samsung/Xiaomi/OnePlus, clear it from "sleeping apps".

## Honest note

I still can't reach live JioSaavn or your OCI box from my build environment,
so the server-side changes (headers, binding, token gate) are verified with
a local Flask test client, and the deep-cache behaviour with a mocked
server. The real-device confirmation is the satisfying one: lock your phone
on a long queue and it should now keep going.

## Testing

All nine regression suites pass with no JS errors. Security headers, token
gate, and cookie flags verified against a live Flask test client.
