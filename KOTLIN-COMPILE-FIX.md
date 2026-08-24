# Kotlin compilation errors — root cause and fixes

## Why the log was useless (your actual question first)

The log showed only:

```
Caused by: org.jetbrains.kotlin.gradle.tasks.CompilationErrorException:
Compilation error. See log for more details
```

**Kotlin compiles inside its own daemon process by default, and that
daemon's diagnostics never reach Gradle's stdout.** All that arrives on the
stream CI captures is the wrapper exception. The real `e: file:///…kt:47:23:
Unresolved reference` lines are stranded in the daemon's own log, which CI
never sees. No amount of `--stacktrace` or `--info` recovers them, because
they were never on that stream.

### The fix

```
-Pkotlin.compiler.execution.strategy=in-process
```

Compiles Kotlin inside the Gradle process, so errors land on the stream we
capture. Also added to `gradle.properties` so local builds behave the same.

Marginally slower. Vastly more debuggable, which is the right trade in CI.

The workflow now also:
- greps explicitly for `^e: ` / `^w: ` (Kotlin's diagnostic format)
- greps for `Unresolved reference`, `Type mismatch`, `overrides nothing`,
  `cannot find symbol`, `None of the following`
- prints the `FAILURE:` block and every `> Task … FAILED`
- **uploads the full log as an artifact even on success**, so the next
  failure is one download away rather than a guess

## The four actual compile errors

### 1. `Result` resolved to `kotlin.Result`, not the Media one

```kotlin
override fun onLoadChildren(parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>)
```

`Result` is a **Kotlin stdlib value class**. It shadows
`MediaBrowserServiceCompat.Result` from the inherited scope, so the override
matched nothing. Now fully qualified.

### 2. `onBind` returned a non-null `IBinder`

```kotlin
override fun onBind(intent: Intent?): IBinder {     // parent: IBinder?
```

Kotlin rejects the signature. Changed to `IBinder?`, and dropped the now
unsafe `super.onBind(intent)!!`.

### 3. `onGetRoot` used an inferred expression-body return type

Made the `BrowserRoot` return type explicit so it can't drift from the
parent's nullability.

### 4. `longVersionCode` is API 28, `minSdk` is 26

```kotlin
packageManager.getPackageInfo(packageName, 0).longVersionCode
```

Fails the `NewApi` lint check. Replaced with
`PackageInfoCompat.getLongVersionCode(...)`.

## What I checked and found clean

- Imports across all four files (the coroutine "misses" were
  `kotlinx.coroutines.*`; `URL` is fully qualified; "launch"/"Python"/
  "WebView" hits were comment text)
- `ServiceCompat.startForeground` with
  `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` — correct for targetSdk 34
- `buildNotification` default args match both call sites
- `when` on `Long` action constants with an `else` branch
- Brace and paren balance in every file
- `MediaStyle` (androidx.media) alongside `NotificationCompat.Builder`
  (androidx.core) — legal, no ambiguity

## Honest limit

There is no Android SDK or Kotlin compiler in my environment, so this is
static review, not a compile. The four errors above are definite. If
something else remains, the in-process flag means **the next run will print
the exact file, line and message** instead of the useless wrapper exception.

## Apply

```bash
cp naada-android/android/app/src/main/java/com/adabala/naada/*.kt \
   android/app/src/main/java/com/adabala/naada/
cp naada-android/android/gradle.properties  android/
cp naada-android/.github/workflows/build-apk.yml .github/workflows/
git add -A && git commit -m "fix: Kotlin compile errors + expose compiler output in CI" && git push
```
