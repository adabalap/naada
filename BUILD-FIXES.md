# Build failure — root causes and fixes

The build failed with `No APK found` because Gradle never produced one.
That message was my error check firing; the real failures were upstream and
were being swallowed. On inspection, **this project could never have
compiled.** Six separate problems:

## 1. `gradle-wrapper.jar` was missing (the immediate killer)

`android/gradle/wrapper/` contained only `gradle-wrapper.properties`. The
`.jar` that bootstraps Gradle was never committed, so `./gradlew` died
instantly with "Unable to access jarfile" before any build output existed.

**Fix:** the workflow now installs Gradle directly via
`gradle/actions/setup-gradle@v4` and calls `gradle` instead of `./gradlew`.
This removes the wrapper dependency entirely and keeps a binary blob out of
the repo. The broken `gradlew` script and empty wrapper directory are
deleted.

## 2. Coroutines used but never declared — hard compile error

`FlaskServerService.kt` and `NaadaMediaService.kt` both use `CoroutineScope`,
`Dispatchers`, `launch` and `withContext`, but
`kotlinx-coroutines-android` was not in `dependencies`.

**Fix:** added `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`.

## 3. `NaadaApplication` was never registered — silent runtime failure

`NaadaMediaService` does `application as? NaadaApplication` to reach the
activity. The manifest never declared `android:name=".NaadaApplication"`, so
that cast returned null forever and **every lock-screen and headphone
button would have done nothing.**

**Fix:** registered in the manifest; `MainActivity` now sets
`mainActivity = this` in `onCreate` and clears it in `onDestroy` (the clear
is guarded by an identity check so a newer instance isn't wiped).

## 4. `webView` was private — compile error

`NaadaMediaService.sendToWebView()` reads `mainActivity.webView`, but the
field was `private`.

**Fix:** changed to `internal`. Also made the access null- and
lateinit-safe: the activity can be destroyed while playback continues, and
`webView` is `lateinit`, so an early read would throw
`UninitializedPropertyAccessException`. Now wrapped in `runCatching`.

## 5. Missing `Bundle` import — compile error

`onGetRoot(..., rootHints: Bundle?)` had no `import android.os.Bundle`.

**Fix:** import added.

## 6. Two latent problems removed

- **ExoPlayer** (`exoplayer-core`, `extension-mediasession`) was pulled in
  but never used — the WebView's `<audio>` element does the actual playback.
  It also clashes with `androidx.media` over the same `MediaSessionCompat`
  classes. Removed.
- **Minification was on.** R8 has to be taught about Chaquopy's Python
  runtime and the `@JavascriptInterface` bridge; get it wrong and you get an
  APK that builds cleanly then crashes at runtime. The saving is negligible
  beside a bundled CPython interpreter. Now `minifyEnabled false`.

## Also fixed: the JS bridge was fragile

`injectBridgeScript()` monkey-patched `MediaSession.prototype` to intercept
metadata writes — dependent on internal property descriptors and liable to
break silently.

**Replaced with an explicit call.** `app.js`'s `updateMediaSession()` now
calls `window.NaadaBridge.onTrackChange(...)` directly when it exists, and
`setPlayIcons()` mirrors play/pause state. Both are guarded by a presence
check, so **the identical file still runs unchanged in a plain browser.**

Verified both ways: with a bridge present the native layer receives the
track and play state; with no bridge, `navigator.mediaSession` behaves
exactly as before.

## Better diagnostics

The build step now runs with `--stacktrace`, tees the full log, and on
failure prints the last 120 lines. You will see the actual Gradle error next
time instead of `No APK found`.

## Verification

14/14 checks pass in the consolidated regression suite: boot, navigation,
runtime Gemini config (save / header / no-key bypass), native bridge in both
modes, queue-end stop, repeat=all wrap, smart shelves, drag-to-playlist drop
zones, in-album filter, settings UI, and zero JS errors.

**Not verified:** the Gradle build itself. There is no Android SDK in my
environment, so compilation is reasoned from source, not observed. The six
fixes above are definite errors that were definitely blocking it; if
something still fails, the new log output will name it precisely.
