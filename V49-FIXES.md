# Naada v49 — Backup/restore, WebView gaps, and the Gemini "invalid key"

## 1. The Gemini error: your key was fine, my test was wrong

```
models/gemini-1.5-flash is not found for API version v1beta
```

That was **my bug in the Test button**, not a problem with your key. I
hardcoded `gemini-1.5-flash`, and Google has since retired it from
`v1beta`. Your backend was always correct — it asks for
`gemini-2.5-flash`, then `gemini-2.0-flash`, then `gemini-flash-latest`,
falling through on failure. So the real AI features were never broken; only
my probe was.

**The fix stops it going stale again.** Test now calls **ListModels** and
validates the credential itself rather than guessing a model name that may
be retired later. It reports what your key can actually reach:

> ✓ Key works — 47 models, using gemini-2.5-flash

## 2. Backup and restore: both were broken, for the same reason

You reported restore. Backup was silently broken too.

Android WebView blocks two things by default that a browser does freely, and
in both cases it fails **silently** — no picker, no error, no console
message:

| feature | web API used | WebView needs |
|---|---|---|
| Restore from file | `<input type="file">` | `onShowFileChooser()` |
| Back up my data | Blob + `<a download>` | `setDownloadListener()` |

Neither was implemented, so the buttons did nothing at all.

**Restore** now opens the system file picker. If you cancel, the callback is
explicitly resolved with `null` — omitting that leaves the WebView's file
input permanently disabled until the app restarts, which would look like the
button "breaking after the first try".

**Backup** now saves to `Downloads/`. Blob URLs cannot be fetched from the
Java side, so the blob is read back in the page via `FileReader` and passed
over the JS bridge as base64, then written to disk and registered with
DownloadManager so it shows up in the Downloads app.

## 3. Two more WebView gaps found while checking

**External links hijacked the app.** Tapping *aistudio.google.com* in
Settings replaced the entire UI with Google's site, with no way back except
the system Back button. Non-localhost links now open in the real browser.

**The service worker could serve a stale UI after an update.** In the APK
everything is served by the bundled Flask server, so the offline cache buys
nothing — but it would keep serving a cached `index.html` and `app.js` after
an app update, meaning a new APK could still show the old interface. Exactly
the confusion that cost us a round trip earlier. The SW is now skipped
inside the native app, and any registration left over from PWA use is
unregistered with its caches dropped.

`WRITE_EXTERNAL_STORAGE` (API ≤ 28) added for saving backups on older
Android.

## Verified

```
/                200   43,377      /api/health      200
/sw.js           200    3,655      /manifest.json   200
/static/app.js   200  204,060      /static/app.css  200
Kotlin structural check: all files sound
naada_app.py: compiles, pure_des applied, 0 cryptography imports
```

**Not verified:** the file picker and download path need a real device —
there is no Android runtime here. The logic is standard `ActivityResultContracts`
and `DownloadListener`, but the confirmation is yours to do.

## Apply

```bash
cp -r naada-android/android/app/src/main/assets  android/app/src/main/
cp naada-android/android/app/src/main/python/*.py android/app/src/main/python/
cp naada-android/android/app/src/main/java/com/adabala/naada/*.kt \
   android/app/src/main/java/com/adabala/naada/
cp naada-android/android/app/src/main/AndroidManifest.xml android/app/src/main/
git add -A && git commit -m "fix: backup/restore, external links, SW, gemini test" && git push

# then, to be certain you are not on cached assets
adb uninstall com.adabala.naada && adb install naada-1.0.NN.apk
adb logcat -s python.stdout | head -8    # expect build=v49-real-backend
```
