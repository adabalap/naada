# Naada v33 — Original icons restored

Reverted. The waveform icon is back.

## What was restored

All six original files are **byte-identical to your repo**, restored from
git rather than recreated:

```
icon-512.png   icon-192.png   icon-180.png
icon-32.png    favicon.ico    icon.svg
```

Theme colours went back with them: `#4A1A6B` in the manifest, the meta tag,
and the runtime theme switcher. Splash background back to `#FAFAF8`.

The icon generator script is deleted — it only produced the design we just
reverted away from.

## Two derived files, regenerated from the original artwork

These didn't exist in your original repo; I added them earlier and the
manifest and `<head>` now reference them, so deleting them outright would
have produced 404s and a broken manifest. Both are now regenerated **from
your original icon** rather than from the note design:

- **`favicon-16/32/48.png`** — plain downscales of the original.
- **`maskable-192/512.png`** — the original artwork, full-bleed, centred at
  78%.

The maskable pair is worth one sentence of explanation, because it looks
like a leftover but isn't a design change. Android applies *its own* mask
and can crop 20% from each edge, so a maskable icon must be full-bleed.
Your original icon has transparent rounded corners — exactly what maskable
must not have. Pointing the manifest's `maskable` entries at the original
files directly would give you a double-rounded, over-cropped icon with
transparent gaps on the home screen. These files are the same artwork,
correctly formatted for that one purpose.

If you'd rather have a pure revert with no extra files at all, say so — it's
removing four files and editing four lines of the manifest and `<head>`.

## Unchanged

Everything else from the recent releases stays: Cloudflare mode
(`start-cloud`), the cache-control policy, the optional `NAADA_TOKEN` gate,
podcasts, radio, resume, sleep timer, folders, back-button navigation.

Verified: all six original icons match git exactly, manifest parses with
zero errors through Chrome's own parser, all nine icon assets return 200,
and all six test suites pass with no JS errors.
