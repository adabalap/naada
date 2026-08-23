# Naada Self-Contained APK — Setup Guide

This builds a **true Android app** (not a PWA wrapper) where:
- Python 3.11 + Flask + all dependencies are bundled inside the APK
- A native `MediaSessionService` owns the media notification → **Naada's
  own icon**, not Chrome's
- A foreground service keeps music playing indefinitely — Android cannot
  kill it, unlike a browser tab or WebAPK
- No separate OCI server is needed for the API layer (it runs on-device)

---

## Repository layout after integration

```
your-repo/
├── static/          ← frontend (unchanged, single source of truth)
├── app.py           ← original OCI server (keep for OCI deployment)
├── android/         ← NEW: the Android project
│   ├── app/
│   │   └── src/main/
│   │       ├── java/com/adabala/naada/   ← Kotlin source
│   │       ├── python/server.py          ← Flask for Android
│   │       └── res/                      ← icons, layouts, strings
│   ├── build.gradle
│   ├── settings.gradle
│   └── gradlew
└── .github/workflows/build-apk.yml       ← CI workflow
```

---

## Step 1 — Add the icon images

The build expects launcher icons in the `mipmap-*` directories.
Copy your existing icon files:

```bash
# From the repo root, after checking out
for d in hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p android/app/src/main/res/mipmap-$d
done

# Use the same icons from static/icons/
# hdpi=72px, xhdpi=96px, xxhdpi=144px, xxxhdpi=192px
# For quick setup, copy icon-192.png to all of them:
for d in hdpi xhdpi xxhdpi xxxhdpi; do
  cp static/icons/icon-192.png \
     android/app/src/main/res/mipmap-$d/ic_launcher.png
  cp static/icons/maskable-192.png \
     android/app/src/main/res/mipmap-$d/ic_launcher_round.png
done
```

Also add the notification action drawables (small, 24dp white icons):
```bash
# Create a minimal drawable directory
mkdir -p android/app/src/main/res/drawable
# You need ic_play.xml, ic_pause.xml, ic_prev.xml, ic_next.xml
# These are standard Material Design icons — copy from any Android project,
# or use the ones at: https://fonts.google.com/icons
# Minimum: the build will fail if these files are missing.
```

---

## Step 2 — Generate the signing keystore (ONE TIME ONLY)

```bash
keytool -genkeypair \
  -dname "CN=Naada, O=adabala, C=IN" \
  -alias naada \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -keystore naada-release.keystore

# Encode for GitHub Secrets
base64 naada-release.keystore | tr -d '\n' > keystore.b64

# Print the SHA-256 fingerprint (needed for assetlinks in Step 4)
keytool -list -v \
  -keystore naada-release.keystore \
  -alias naada \
  | grep "SHA256:"
```

**Store the keystore file and passwords somewhere safe.** Losing it means
you can never update the app.

---

## Step 3 — Add GitHub Secrets

Repo → Settings → Secrets and variables → Actions → New repository secret:

| Secret name | Value |
|---|---|
| `KEYSTORE_BASE64` | Full contents of `keystore.b64` (one line) |
| `KEYSTORE_PASSWORD` | Your keystore password |
| `KEY_PASSWORD` | Your key password |
| `NAADA_GEMINI_KEY` | Your Gemini API key (for AI features) |

---

## Step 4 — Push and build

```bash
# Copy the android/ folder and workflow into your repo
cp -r /path/to/android ./android
cp -r /path/to/.github ./.github

git add android/ .github/
git commit -m "feat: add native Android APK build"
git push
```

Actions tab → "Build Naada APK (self-contained)" → watch it build.

First run: ~8-10 min (Gradle + Chaquopy download Python wheels).
Subsequent runs: ~3-4 min (Gradle cache).

The signed APK appears under **Releases** when the build completes.

---

## Step 5 — Remove the address bar (optional, one-time)

On your OCI server:
```bash
export NAADA_APK_FINGERPRINT="AA:BB:...(from Step 2)"
./naada.sh restart
```

Verify: `curl https://naada.adabala.com/.well-known/assetlinks.json`
should return a non-empty JSON array.

Uninstall and reinstall the APK — Android checks assetlinks at install time.

---

## Step 6 — Install

1. Download `naada-*.apk` from the GitHub Release.
2. Phone: Settings → Apps → Special app access → Install unknown apps
   → Files → Allow.
3. Open the APK, tap Install.
4. If you have the old PWA WebAPK (`com.adabala.naada` package), Android
   will refuse to install over it. Uninstall the WebAPK first.

---

## How the app works

```
┌─────────────────────────────────────────────────────────┐
│  Android app: com.adabala.naada                         │
│                                                         │
│  ┌─────────────────┐    ┌──────────────────────────┐   │
│  │  MainActivity   │    │  NaadaMediaService       │   │
│  │  (WebView UI)   │◄──►│  (foreground service)    │   │
│  │                 │    │  • Owns MediaSession      │   │
│  │  loads:         │    │  • Shows Naada's icon     │   │
│  │  localhost:5000 │    │  • Cannot be killed by   │   │
│  └────────┬────────┘    │    battery manager        │   │
│           │ JS bridge   └──────────────────────────┘   │
│           ▼                                             │
│  ┌─────────────────┐                                    │
│  │ FlaskServerSvc  │  ← separate process (:flask)       │
│  │  Python 3.11    │                                    │
│  │  Flask 3.0      │  bundled pip packages:             │
│  │  + all deps     │  flask, requests, cryptography,    │
│  │  bundled in APK │  werkzeug, certifi, ...            │
│  └────────┬────────┘                                    │
│           │ localhost:5000                              │
│           ▼                                             │
│  ┌─────────────────┐                                    │
│  │ JioSaavn API    │  ← internet (still needed)         │
│  │ Gemini AI       │                                    │
│  │ Lyrics / LRC    │                                    │
│  └─────────────────┘                                    │
└─────────────────────────────────────────────────────────┘
```

---

## APK size

Expect 90-130 MB. That's normal when bundling a Python interpreter +
packages. JioSaavn content still streams from the CDN — only the backend
proxy logic is bundled.

---

## Keeping in sync with the PWA

The `static/` folder is the single source of truth. The GitHub Actions
workflow copies it into the Android assets at build time, so you never
maintain two copies. Push a change to `static/`, and both the OCI
deployment and the APK pick it up.

The Python backend (`server.py`) is intentionally a separate file from
`app.py` — it's the same logic but stripped of the daemon/OCI startup
code. When you add a new API endpoint to `app.py`, copy it to
`android/app/src/main/python/server.py` too.

---

## Troubleshooting

**"duplicate class" build error**
→ Dependency conflict in build.gradle. Check the ExoPlayer version matches
  the `androidx.media` version.

**Flask starts but WebView shows blank**
→ Check the network_security_config.xml allows cleartext to localhost.

**"App not installed" on device**
→ Package conflict with the existing PWA WebAPK. Uninstall it first.

**MediaSession notification shows wrong icon**
→ The mipmap icons may not have been copied. Check
  `android/app/src/main/res/mipmap-*`.

**Build slow / times out**
→ Chaquopy downloads Python wheels from PyPI. Add a longer timeout to
  the workflow, or pin to a Chaquopy version with pre-cached wheels.
