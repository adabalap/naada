# Fix: "Couldn't load trending / charts" — the APK ran a different backend

## The app was working. The backend was the wrong one.

Your screenshot shows a complete success everywhere except the data: APK
built, installed, launched, Chaquopy started Python, Flask came up, the
WebView loaded the real UI, Telugu was selected. Only the API calls failed.

## The cause was my mistake

I wrote a **hand-written `server.py`** for Android instead of bundling your
actual `app.py`. Two backends means drift, and it drifted immediately:

| | your app.py | my server.py |
|---|---|---|
| routes | 27 | 15 |
| `/api/charts/<lang>` | yes | **missing** |
| `/api/new-releases` | yes | **missing** |
| `/api/playlist/<id>` | yes | **missing** |
| `/api/search/albums` | yes | **missing** |
| `/api/search/artists` | yes | **missing** |
| response shape | normalised for the frontend | raw JioSaavn payloads |

"Couldn't load trending" and "Couldn't load charts" were exactly this: the
endpoints either didn't exist or returned a shape the frontend couldn't
parse.

## The fix: one backend, not two

The APK now bundles your real `app.py` verbatim as `naada_app.py` — all
1,366 lines, all 27 routes, the same file that runs on OCI. `server.py` is
now a thin ~90-line Android adapter that changes only environmental things
and touches none of the logic.

**Every route the frontend calls now exists.** Verified all 14:
`/api/trending/<lang>`, `/api/charts/<lang>`, `/api/home/<lang>`,
`/api/new-releases`, `/api/search`, `/api/search/albums`,
`/api/search/artists`, `/api/song/<id>`, `/api/album/<id>`,
`/api/artist/<id>`, `/api/playlist/<id>`, `/api/lyrics/<id>`, `/api/radio`,
`/api/ai/*` — no 404s.

### Two adaptations were needed

**1. `app.root_path`, not `chdir`.** Your routes call
`send_from_directory("static", ...)` with a relative path. Flask resolves
that against `app.root_path`, which is fixed when `Flask()` is constructed
— under Chaquopy that points inside the packaged ZIP. Repointing
`root_path` at the extracted assets is what makes it work. I tried `chdir`
first and it was not enough, which is exactly why `/` kept returning 404
while `/static/app.js` succeeded (the latter goes through
`app.static_folder`).

Now serving correctly:
```
/                 200   43,377 bytes
/manifest.json    200    1,284
/sw.js            200    3,655
/static/app.js    200  202,204
/static/app.css   200   88,201
/favicon.png      200    7,960
```

**2. `cryptography` → `pure_des`.** Your `app.py` imports the native
`cryptography` package for JioSaavn's DES-ECB URLs. Chaquopy only ships
Android wheels for selected versions of native packages, so that would make
the build fragile. Swapped for the pure-Python DES (7/7 FIPS vectors,
byte-identical to `cryptography` on random input). **Every pip dependency is
now pure Python**, so the build cannot fail on wheel availability.

Writable paths (`ai_cache.json`, logs, config) are redirected into the app's
private directory, since the APK itself is read-only.

## One thing I could not verify

Every JioSaavn call from my sandbox returns **403 Forbidden** — datacenter
IPs are blocked, and my environment has no route to them. So I verified
routing, serving and shapes, but could not confirm live data.

Your phone is a normal mobile IP and your OCI server already talks to
JioSaavn fine, so this should not affect you. But if the shelves are still
empty after this update, that is the thing to look at next, and it would be
a different problem from the one fixed here. `adb logcat -s python.stdout`
will show the `saavn() error:` lines if so.

## Apply

```bash
rm -f android/app/src/main/python/server.py
cp naada-android/android/app/src/main/python/*.py android/app/src/main/python/
cp naada-android/android/app/build.gradle android/app/
git add -A && git commit -m "fix: bundle the real backend instead of a divergent copy" && git push
```
