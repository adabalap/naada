# Your logs proved the device is running the OLD backend

## What the log told me

Three lines settled it:

```
naada INFO Serving static files from /data/user/0/...
naada INFO Static contents: app.css, app.js, ...
naada INFO Naada Flask ready on 127.0.0.1:5000
```

**Those strings do not exist in the current bundle.** They belong to the
hand-written `server.py` I deleted. The new adapter logs
`static dir ->`, `root_path ->`, and
`Naada backend ready on 127.0.0.1:5000 with 28 routes`, under the logger
name `naada.android` rather than `naada`.

The 404 pattern confirms it independently:

| endpoint | your device | old server.py | naada_app.py |
|---|---|---|---|
| `/api/new-releases` | 404 | absent | **present** |
| `/api/charts/telugu` | 404 | absent | **present** |
| `/api/search/artists` | 404 | absent | **present** |
| `/sw.js` | 404 | absent | **present** |
| `/api/trending/telugu` | 200 | present | present |
| `/api/home/telugu` | 200 | present | present |

That is exactly the old 15-route set. And it explains the screenshot too:
`/api/trending` returned **200 with unusable data**, because the old server
passed raw JioSaavn payloads straight through instead of the normalised
shape the frontend parses. A 200 that renders as "Couldn't load" is the
signature of a shape mismatch, not a network failure.

So the APK on your phone predates the real-backend fix — either it was not
pushed, or the build ran before the change landed.

## Verified against the current bundle

All four now resolve:

```
/api/new-releases?lang=telugu  -> 200
/api/charts/telugu             -> 200
/api/search/artists?q=Chiru    -> 200
/sw.js                         -> 200
```

## So this can never be ambiguous again

The backend now prints a banner and its full route table at startup:

```
==============================================================
NAADA BACKEND  build=v49-real-backend  (bundled naada_app.py, full route set)
==============================================================
root_path -> /data/user/0/com.adabala.naada/files
static dir -> /data/user/0/com.adabala.naada/files/static
Naada backend ready on 127.0.0.1:5000 with 28 routes
  route: /
  route: /api/charts/<lang>
  route: /api/new-releases
  ... (all 28)
```

**One command now answers "which backend is on this phone?":**

```bash
adb logcat -s python.stdout | head -8
```

If you do not see `build=v49-real-backend`, the APK is stale — no need to
guess from 404s. And if a route 404s in future, the dumped table shows
instantly whether it is genuinely missing or the URL is just wrong.

## Getting the new build onto the phone

```bash
# 1. Confirm the repo has the right files
ls android/app/src/main/python/
#    expect: naada_app.py  pure_des.py  server.py
grep -c "@app.route" android/app/src/main/python/naada_app.py   # 27

# 2. Push
git add -A && git commit -m "fix: bundle real backend" && git push

# 3. Wait for the Release, then FULLY reinstall — an in-place install can
#    keep the old extracted assets in filesDir
adb uninstall com.adabala.naada
adb install naada-1.0.NN.apk

# 4. Confirm the right backend is live
adb logcat -c && adb shell monkey -p com.adabala.naada 1 >/dev/null
adb logcat -s python.stdout | head -8
```

Step 3 matters: `extractStaticAssets()` skips re-extraction when the stored
`versionCode` matches, so a fresh install is the reliable way to be sure
you are not looking at cached assets from the previous build.

## Still outstanding

Every JioSaavn request from my sandbox returns **403** — datacenter IPs are
blocked and I have no route around it. Routing, serving and response shapes
are verified; live data is not. Your phone is a normal mobile IP and your
OCI server reaches JioSaavn fine, so I expect it to work. If shelves are
still empty *after* you confirm `build=v49-real-backend` in the log, that is
a genuinely different problem — send the `saavn() error:` lines and we will
chase it properly.
