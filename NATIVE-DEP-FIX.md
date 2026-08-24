# Build log visibility + removing the last native dependency

## 1. Why you couldn't see the error (your original question)

**Kotlin compiles in its own daemon process, and that daemon's diagnostics
never reach Gradle's stdout.** Only the wrapper
`CompilationErrorException: See log for more details` arrives on the stream
CI captures. `--stacktrace` and `--info` cannot recover the real message,
because it was never on that stream.

Fixed with `-Pkotlin.compiler.execution.strategy=in-process`, set in both
the workflow and `gradle.properties`.

**The full log is now printed inline in the workflow output.** It has been
about 4KB every run — small enough that making you download an artifact to
read it was pointless friction. Only logs over 400KB fall back to
head/tail.

Action versions bumped to `checkout@v5`, `setup-java@v5`,
`upload-artifact@v5` to clear the Node 20 deprecation notices. (Those were
warnings, not the failure.)

## 2. The likely actual cause: `cryptography`

```gradle
install "cryptography==42.0.8"   // was here
```

`cryptography` is a **native Rust + OpenSSL extension.** Chaquopy can only
install native packages for which it ships a **prebuilt Android wheel**, and
it builds those for a specific, limited set of versions. An arbitrary pin
like `42.0.8` is very likely to fail with "no matching distribution" — and
even a version that works today ties your build to Chaquopy's wheel
availability forever.

Every other dependency (flask, werkzeug, requests, urllib3, certifi,
charset-normalizer, idna) is pure Python and installs unconditionally.

### The fix: pure-Python DES

`cryptography` was used for exactly one thing — decrypting JioSaavn's
DES-ECB stream URLs. That is now `pure_des.py`, about 120 lines, and the
native dependency is gone entirely.

**Verified properly, because a wrong cipher would silently break every
stream URL:**

```
DES known-answer tests (FIPS vectors):     7/7 PASS
Cross-check vs cryptography (random 64B):  MATCH
Round-trip through server._des_decrypt:    MATCH
```

Full server re-verified with no `cryptography` in the path:

```
/api/health      200
/                200   43,377 bytes
/static/app.js   200  202,204 bytes
/manifest.json   200
/api/ai/status   200
```

DES is a small, fully specified algorithm and these payloads are short URLs,
so pure Python costs nothing measurable here.

## 3. Kotlin fixes from the previous round (still included)

- `Result<...>` → `MediaBrowserServiceCompat.Result<...>` — bare `Result`
  resolves to the Kotlin stdlib value class and the override matched nothing
- `onBind(): IBinder` → `IBinder?` to match the parent signature
- `onGetRoot` given an explicit return type
- `longVersionCode` (API 28) → `PackageInfoCompat` (minSdk is 26)

## Apply

```bash
cp -r naada-android/android/app/src/main/python  android/app/src/main/
cp naada-android/android/app/build.gradle        android/app/
cp naada-android/android/gradle.properties       android/
cp naada-android/.github/workflows/build-apk.yml .github/workflows/
git add -A
git commit -m "fix: drop native cryptography dep, expose Kotlin compiler errors"
git push
```

## If it still fails

The log now prints in full in the Actions output. Paste the section under
`══════════ KOTLIN / JAVA COMPILE ERRORS ══════════` (or the full log if
that section is empty) and it should be a quick diagnosis — there is no
longer anywhere for the error to hide.
