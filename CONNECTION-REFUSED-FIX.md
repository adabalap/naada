# Fix: ERR_CONNECTION_REFUSED on localhost:5000

## First — this was a milestone

The APK built, signed, installed, launched, and the WebView came up. Every
build problem is behind us. This was the app running and its backend not
answering.

## Two real causes

### 1. Chaquopy zips the Python sources (the architectural one)

`static/` was living in `android/app/src/main/python/static/`, and
`server.py` located it with:

```python
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
```

**Chaquopy packages everything under `src/main/python/` into a ZIP.** At
runtime those paths are not real directories, so `STATIC_DIR` pointed at
somewhere that doesn't exist. Flask had no web files to serve.

**Fix:** the frontend now ships as ordinary Android assets
(`src/main/assets/static/`), which are stored verbatim in the APK.
`FlaskServerService` extracts them to the app's `filesDir` on first launch —
re-extracting whenever `versionCode` changes, so an update never serves a
stale UI — and passes that absolute path into `run_server(static_dir, port)`.

### 2. The startup delay was far too short (the reason you saw *refused*)

```kotlin
webView.postDelayed({ loadApp() }, 2000)   // 2 seconds
```

On first launch Chaquopy unpacks the entire Python standard library plus
every bundled pip package. On a mid-range phone that is **15 seconds or
more**. The WebView was reliably loading before anything was listening on
port 5000, which is exactly `ERR_CONNECTION_REFUSED`.

**Fix:** MainActivity now polls `/api/health` every 500ms on a background
thread and only loads the UI once the server actually answers, with a 45s
budget.

## Also fixed

**Recursive retry could overflow the stack.** `startFlask()` called itself
from inside its own `catch` block. It is now a bounded loop (5 attempts,
2.5s apart).

**Failures were undiagnosable.** You got Chrome's generic "Web page not
available", which says nothing useful. If the server never comes up you now
get a proper page showing the actual Python traceback, a Try again button,
and the exact `adb logcat` command to capture more.

**Wrong URL.** It loaded `/static/index.html`; the app is served at `/`.

## Verified

I simulated the full runtime path in Python — extract assets to a temp
filesDir, start `run_server()` against it, poll for readiness:

```
/api/health      -> 200
/                -> 200   (43,377 bytes — the real UI)
/static/app.js   -> 200   (202,204 bytes)
/static/app.css  -> 200   (88,201 bytes)
/manifest.json   -> 200
/api/ai/status   -> 200
```

**Not verified:** Chaquopy's own bootstrap on a real device — there's no
Android runtime here. If it still fails, the new error page will show the
Python traceback instead of hiding it.

## What to expect on first launch

The first start will sit on the spinner for **10–20 seconds** while Python
unpacks. That is one-time; later launches are fast. If it exceeds 45s you
get the diagnostic page rather than a blank one.

## To apply

```bash
rm -rf android/app/src/main/python/static     # must not live here any more
cp -r naada-android/android/app/src/main/assets  android/app/src/main/
cp naada-android/android/app/src/main/python/server.py  android/app/src/main/python/
cp naada-android/android/app/src/main/java/com/adabala/naada/*.kt \
   android/app/src/main/java/com/adabala/naada/
cp naada-android/.github/workflows/build-apk.yml .github/workflows/

git add -A && git commit -m "fix: serve frontend from Android assets, wait for server readiness" && git push
```
