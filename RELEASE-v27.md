# Naada v27 — Root cause found (and I was wrong last time)

Checking `hg_ui` was the right call. It settled the question, and it proved
my previous guess wrong.

---

## What I said last time, and why it was wrong

I told you the install problem was "almost always the HTTPS certificate,"
implying you needed a better certificate.

**Quicksilver disproves that.** It runs on the same phone, behind the same
setup, and installs perfectly. So the certificate infrastructure isn't the
problem. Something in how the two apps are *served* differs.

## The actual difference

| | Quicksilver (works) | Naada (doesn't) |
|---|---|---|
| Server | waitress, **no TLS** | Flask with `ssl_context` |
| URL | `http://localhost:PORT` | `https://localhost:5443` |
| Certificate | none needed | **self-signed — README says "accept cert"** |

Chrome's rules, which explain everything:

- **`http://localhost` is on Chrome's trustworthy-origins list.** It gets full
  secure-context privileges — service workers, installability, the lot —
  with no certificate at all. This is why Quicksilver installs.
- **A self-signed certificate you click past leaves the origin flagged with a
  certificate error.** Chrome refuses to fire `beforeinstallprompt` on such an
  origin, so the app can never be installed.

So the fix is genuinely counter-intuitive: **plain HTTP is more installable
than your HTTPS.** The `https://` in your address bar is what's blocking it.

And because installing is what fixes the media-notification icon *and*
background playback, this one certificate issue was behind all three
symptoms you reported.

## The fix — two commands

```bash
./naada.sh stop
./naada.sh start-http
```

Then open **http://localhost:5000** on the phone. Chrome should now offer to
install, and the new **Install Naada** button in About will light up.

`start-http` is new in this build. It binds to `127.0.0.1` only, which is
both safer and required — `http://<your-LAN-IP>:5000` is *not* a trusted
origin, only literal `localhost` is. For access from other devices, use the
`naada.adabala.com` nginx config with your real certificate; that's a proper
CA-signed cert and installs fine.

---

## Icon fixes, taken from hg_ui's proven pattern

**Dedicated maskable icons (new).** Your `icon-192/512.png` have transparent
rounded corners. The manifest was listing those same files as `maskable`,
which is exactly wrong — Android applies *its own* mask and crops up to 20%
from each edge, so a maskable icon must be full-bleed with the artwork inside
the central safe zone. The old setup would have shown a double-rounded,
over-cropped icon with transparent gaps even after installing.

I generated proper `maskable-192.png` and `maskable-512.png`: full-bleed
brand gradient, logo at 78%, verified opaque to all four corners.

**Favicon links now follow hg_ui's ordering**, which your own code comments
there explain well: SVG first, ICO fallback with explicit `sizes`, then 32
and 16 PNGs, then `apple-touch-icon`. Added the missing `favicon-16/32/48.png`.

**Dropped the `?v=22` query strings.** They were stale — four versions
behind — and a versioned favicon URL just parks the old icon under the old
cache key.

**Manifest:** added explicit `"scope": "/"`, removed the SVG entry (hg_ui
doesn't have one and installs fine; SVG manifest icons are unevenly
supported), and pointed maskable at the new dedicated files.

**Added `<meta name="mobile-web-app-capable">`** — the unprefixed name is the
one Chrome reads; you only had the `apple-` variant.

Verified via Chrome DevTools Protocol: manifest parses with zero errors, and
all nine declared icon assets return 200 with correct content types.

---

## About panel now diagnoses this exactly

If it detects HTTPS on localhost with no install prompt, it says so directly
and shows the two commands to run. Otherwise it shows four measured checks:
installed state, secure context, service worker, and installability.

## Testing

Manifest and icon resolution verified through Chrome's own parser, plus full
regression over the previous four releases — back navigation, folders,
delete flows, theme, pinning, bulk operations. No JS errors.
