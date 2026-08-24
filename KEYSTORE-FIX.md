# Fix: "Keystore file not found" at :app:validateSigningRelease

## Progress

Gradle is now configuring correctly and running real tasks. This failure is
at the signing stage — much further along than the wrapper and repository
errors.

## What happened

The release build needs `android/app/naada-release.keystore`. It is in the
zip I sent, but it never reached your repo.

**Almost certainly your `.gitignore`.** Nearly every standard Android and
Gradle `.gitignore` template contains:

```
*.keystore
*.jks
```

So `git add` silently skipped the binary. Nothing warns you — the file just
never gets committed, and the build fails on a machine that doesn't have
your local copy.

## The fix — three layers

### 1. The keystore is now ALSO committed as base64 text

`android/app/naada-keystore.b64` — 3,488 bytes of plain text. No
`*.keystore` ignore pattern matches a `.b64` file, so it survives any
inherited gitignore template.

Verified: decoding it produces a **byte-identical** keystore with the same
fingerprint.

### 2. The workflow reconstructs the binary if it's missing

```yaml
- name: Ensure signing keystore
  run: |
    KS=android/app/naada-release.keystore
    if [ -f "$KS" ]; then
      echo "Keystore present."
    elif [ -f android/app/naada-keystore.b64 ]; then
      base64 -d < android/app/naada-keystore.b64 > "$KS"
    else
      # generate a throwaway so the build never hard-fails
    fi
```

I simulated your exact failure — deleted the keystore, left only the
base64 — and it recovered a byte-identical file with the matching
fingerprint.

### 3. `.gitignore` force-includes both signing files

```
!android/app/naada-release.keystore
!android/app/naada-keystore.b64
```

The `!` prefix overrides any earlier ignore rule.

## The last-resort fallback, and why it warns loudly

If neither file is present, the workflow generates a fresh key rather than
failing. But it prints warnings, because a new key means:

- a **different signature**, so Android refuses to install over an existing
  Naada — you must uninstall first
- a **different fingerprint**, so your assetlinks entry no longer matches
  and the address bar comes back

That is a real cost, which is why it is the last resort and not the default.

## What to do

```bash
# Copy both signing files plus the updated workflow and gitignore
cp naada-android/android/app/naada-keystore.b64      android/app/
cp naada-android/android/app/naada-release.keystore  android/app/
cp naada-android/.github/workflows/build-apk.yml     .github/workflows/
cp naada-android/.gitignore                          .

# Force-add in case a stale ignore rule is still active
git add -f android/app/naada-keystore.b64 android/app/naada-release.keystore
git add .gitignore .github/workflows/build-apk.yml
git commit -m "fix: commit signing keystore (binary + base64 fallback)"
git push
```

**Verify before pushing** that git is actually tracking them:

```bash
git ls-files android/app/ | grep -i keystore
```

Both filenames should appear. If neither does, a gitignore rule is still
winning — `git check-ignore -v android/app/naada-release.keystore` will name
the exact file and line responsible.

## Your fingerprint (unchanged)

```
5E:2E:0C:68:D9:7A:81:9B:DE:69:30:CD:3F:58:0A:E8:BA:19:1B:BA:5F:7A:F4:35:2F:5A:C4:12:B5:FD:18:15
```
