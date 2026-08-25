# Naada v50 — Album art on the lock screen

## First: what your screenshot already proves

The media notification shows **Naada's own icon and name**, with working
transport controls. That is the thing that was flatly impossible for the
entire PWA era — the badge was Chrome's and no web API could change it. The
native MediaSessionService is doing its job.

## The clue in the screenshot

The title read **"Naada · నాద"**, which is the *default* in
`buildNotification()`. If a track had reached the service, that line would
be the song name. So this was not only an artwork problem: **no metadata
was arriving at all.**

## Bug 1 — metadata was being silently discarded

```kotlin
fun onTrackChange(...) {
    mediaService?.updateMetadata(...)   // ← null during binding
}
```

`bindService()` is asynchronous, and it races the first track. Until
`onServiceConnected` fires, `mediaService` is null — and `?.` quietly threw
the metadata away. No error, no log, nothing to notice.

**Fixed:** metadata that arrives before the service is bound is now held and
replayed the moment the connection lands. Playback state too.

## Bug 2 — the artwork fetch was fragile

```kotlin
URL(url).openStream().use { BitmapFactory.decodeStream(it) }
```

No timeout, no redirect handling, no User-Agent. Each of those fails in an
ordinary way against a CDN — a 301 to https, a UA check, a stalled socket —
and every failure fell through to the launcher icon with nothing in the log.

**Fixed:** 6s connect/read timeouts, manual redirect following (max 4), a
browser User-Agent, a 6-entry LRU cache (the same art is requested on every
metadata update), and two-pass decoding so a 500×500 JPEG is subsampled
rather than decoded at full size for a notification-sized slot.

## Bug 3 — the title waited on the image

`updateMetadata()` awaited `loadArtwork()` before publishing *anything*, so
a slow image delayed the song title by up to the full timeout, and a failed
one left the previous track showing.

**Fixed:** two passes. Text publishes immediately; artwork lands in a second
update when it arrives. A late fetch is discarded if you have already
skipped on, so stale art can never attach to a newer track.

Also now setting `METADATA_KEY_ALBUM_ART`, `METADATA_KEY_ART` and
`METADATA_KEY_DISPLAY_ICON` — different launchers read different keys, and
betting on one was optimistic.

## What I could not verify

JioSaavn's CDN returns **403** to my sandbox, exactly as its API does. I
sent a correct User-Agent and still got 403, which tells us the block is by
IP, not by header — my environment is a datacenter address. Your phone is a
normal mobile IP and is already fetching from JioSaavn successfully, so I
expect artwork to work.

I have not guessed at whether it will. Instead the path is now fully
instrumented:

```
NaadaMediaService  metadata: 'Ye Vaipuga' / 'Sid Sriram'  art=yes
NaadaMediaService  artwork 500x500 from https://c.saavncdn.com/...
```

and on failure, precisely why:

```
NaadaMediaService  artwork HTTP 403 for https://...
NaadaMediaService  artwork fetch failed: SocketTimeoutException: ...
NaadaMediaService  no artwork for 'Ye Vaipuga'; keeping the app icon
```

## Check it on the device

```bash
adb logcat -c
# play a song, then:
adb logcat -s NaadaMediaService:* python.stdout:* | head -30
```

Look for `build=v50-artwork` to confirm the new APK is live, then the
`metadata:` and `artwork` lines. If artwork still fails, that log names the
cause and it will be a quick fix.

## Apply

```bash
cp -r naada-android/android/app/src/main/assets android/app/src/main/
cp naada-android/android/app/src/main/python/*.py android/app/src/main/python/
cp naada-android/android/app/src/main/java/com/adabala/naada/*.kt \
   android/app/src/main/java/com/adabala/naada/
git add -A && git commit -m "fix: album art + metadata delivery on lock screen" && git push
adb uninstall com.adabala.naada && adb install naada-1.0.NN.apk
```
