# Naada v51 — Native audio: the actual fix for "stops after 2-3 songs"

## I was treating a symptom for several releases

Removing battery optimisation did not help because battery optimisation was
never the problem. Here is what the APK was really doing:

```
  foreground service  ──► holds a MediaSession, plays NOTHING
  WebView <audio>     ──► produces all the sound
```

The service was protected. **The thing making sound was not.** A WebView
belongs to the Activity, so when the screen goes off Android freezes its
media and its JavaScript timers — and the `ended` handler that starts the
next track never runs. A healthy foreground service does not help if the
service is not the audio source.

Everything I added earlier — deep URL caching, byte buffering, gapless
arming, auto-resume, wake locks — was work on the frozen side of that line.
It could delay the stop. It could never prevent it.

## What changed

ExoPlayer (media3) now runs **inside the foreground service** and owns the
queue.

```
  JS   ──► resolves stream URLs, hands over the WHOLE queue, owns the UI
  Native ──► plays it, advances by itself, draws the notification
```

The key property: native receives the **entire** playlist in one call, so
it never needs to ask JavaScript for anything mid-queue. This page can be
frozen solid and the music keeps going. That is the difference between
"survives longer" and "does not depend on the WebView at all".

Also gained, because it is a real player now:
- **audio focus** — pauses for calls, ducks for navigation
- **becoming-noisy** — pauses when headphones are unplugged
- **`WAKE_MODE_NETWORK`** — a genuine CPU/network wake lock held by a
  foreground service, unlike the web Screen Wake Lock which is released the
  instant the page hides
- **error recovery** — a dead track is skipped instead of stalling the queue
- **survives task-swipe** — the notification remains the way back in

## Album art, solved as a side effect

media3 renders the notification from each `MediaItem`'s metadata, including
`artworkUri`. The queue now carries `title`, `artist`, `album` and `art` per
track, so the hand-written artwork fetcher — with its timeouts, redirects
and silent failures — is gone entirely. Verified in the payload:

```json
{"id":"s1","url":"...","title":"Song 1","artist":"A",
 "album":"Naada","art":"https://img/1.jpg"}
```

## Verified

Browser path, unchanged: **14/14 regression checks pass.** `NATIVE_PLAYBACK`
is false without the bridge, so the PWA still uses `<audio>` exactly as
before.

Native path, with a mock bridge installed before page scripts run:

```
native mode detected      True
web <audio> muted         True      (two engines can never both sound)
setQueue sent             1 call, 5 tracks, start=0
first item                id/url/title/artist/album/art all present
transport -> native       playPause, next, prev
state callback applied    S.playing updated from the player
index synced from native  queueIdx followed the player
```

## Not verified

There is no Android runtime here, so ExoPlayer itself is unexercised — the
JS handoff, payload shape and browser fallback are tested, the playback is
reasoned. It is the standard media3 MediaSessionService pattern.

## Test that the real problem is fixed

```bash
adb logcat -c
# start a long queue, lock the screen, wait past 3 songs
adb logcat -s NaadaMediaService:*
```

Expect a `track -> [n] Title` line per song with the screen off, continuing
past track 3. If it still stops, that log will show whether the player
paused, errored, or was killed — three different causes with three
different fixes, and we would no longer be guessing between them.

## Apply

```bash
cp -r naada-android/android/app/src/main/assets android/app/src/main/
cp naada-android/android/app/src/main/java/com/adabala/naada/*.kt \
   android/app/src/main/java/com/adabala/naada/
cp naada-android/android/app/src/main/AndroidManifest.xml android/app/src/main/
cp naada-android/android/app/build.gradle android/app/
cp naada-android/android/app/src/main/python/server.py android/app/src/main/python/
git add -A && git commit -m "feat: native ExoPlayer audio in foreground service" && git push
adb uninstall com.adabala.naada && adb install naada-1.0.NN.apk
```
