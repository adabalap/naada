#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────
# Build Naada as an installable Android .apk (a Trusted Web Activity wrapper
# around the live https://naada.adabala.com PWA).
#
# Run this on any machine — laptop, or a Termux install with more room. It
# needs Node 14+ and a JDK; Bubblewrap fetches the Android SDK itself the
# first time. It CANNOT run in Claude's sandbox because that download is
# blocked there.
# ─────────────────────────────────────────────────────────────────────────
set -e
cd "$(dirname "$0")"

echo "==> 1/5  Checking prerequisites"
command -v node >/dev/null || { echo "Install Node 14+ first"; exit 1; }
command -v java >/dev/null || { echo "Install a JDK (JDK 17 recommended) first"; exit 1; }
command -v keytool >/dev/null || { echo "keytool missing — install a full JDK"; exit 1; }

echo "==> 2/5  Installing Bubblewrap (Google's official TWA builder)"
npm install -g @bubblewrap/cli

echo "==> 3/5  Creating a release signing key (first run only)"
if [ ! -f naada-release.keystore ]; then
  echo "    You'll set a keystore password now. WRITE IT DOWN —"
  echo "    losing it means you can never ship an update to this app."
  keytool -genkeypair \
    -dname "CN=Naada, O=adabala, C=IN" \
    -alias naada \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -keystore naada-release.keystore
else
  echo "    naada-release.keystore already exists — reusing it."
fi

echo "==> 4/5  Building the APK from twa-manifest.json"
# init pulls the live web manifest; build compiles + signs
bubblewrap init --manifest="https://naada.adabala.com/manifest.json" --directory=. || true
bubblewrap build --skipPwaValidation

echo "==> 5/5  Done"
echo
echo "  Signed app:  $(pwd)/app-release-signed.apk"
echo
echo "  To install on your phone over USB (with adb):"
echo "      adb install -r app-release-signed.apk"
echo
echo "  Or copy the .apk to the phone and tap it (enable"
echo "  'Install unknown apps' for your file manager first)."
echo
echo "  IMPORTANT — remove the browser address bar:"
echo "  Bubblewrap prints an 'assetlinks.json' block. Save it to"
echo "      /.well-known/assetlinks.json   on naada.adabala.com"
echo "  (served by Flask, see the snippet in APK-BUILD.md). Without"
echo "  it the app works but shows a URL bar at the top."
