# Root cause: Kotlin nests block comments

## One bug, eighteen error messages

Your read was correct — the unresolved references were symptoms, not causes.
The real problem was a single character sequence.

`FlaskServerService.kt` line 77 had this KDoc:

```kotlin
/**
 * Copy assets/static/** to filesDir/static, once per app version.
 ...
 */
```

**Kotlin allows block comments to NEST. Java does not.** The `/**` inside
`assets/static/**` opened a *second* comment level. The `*/` three lines
later closed only that inner level, so the outer KDoc never closed and
**every line after it was swallowed as comment text**.

That single wildcard produced:
- `74:6 Missing '}'` — the class brace was inside the comment
- `132:1 Unclosed comment` — the compiler reaching EOF still inside it
- 18 unresolved references — `TAG`, `PORT`, `MAX_ATTEMPTS`, `RETRY_MS`,
  `fail()`, `extractStaticAssets()`, `lastError` are all declared after
  line 77, so from the compiler's point of view they did not exist
- 3 more in `MainActivity.kt`, which reads `FlaskServerService.PORT` and
  `.lastError` — the companion object had vanished too

## The fix

`FlaskServerService.kt` is rewritten in full. No wildcard paths in comments,
and a note at the top of the file explaining the hazard so it does not get
reintroduced.

A scan confirmed the other three Kotlin files were unaffected.

## Preventing a repeat

`check_kotlin.py` is included and now runs in CI **before** Gradle. It
parses each file while tracking string literals, raw strings, line comments
and nested block comments, and reports:

- unclosed block comments, with the line where each was opened
- brace and paren imbalance outside comments and strings
- bare `Result<` (shadows `kotlin.Result`)
- `onBind` returning a non-nullable `IBinder`
- API-28 calls such as `longVersionCode` against minSdk 26

I verified it works by reintroducing the exact bug:

```
With the bug reintroduced:
  FAIL  FlaskServerService.kt: unclosed block comment (depth 1), opened at line(s) [88]
  FAIL  FlaskServerService.kt: brace imbalance (+1)
exit code: 1

After restoring:
  all files structurally sound
```

It reproduces both the comment error and the phantom brace imbalance that
follows from it, and runs in about a second instead of a two-minute Gradle
cycle.

## Current state of all files

```
FlaskServerService.kt   sound   (rewritten)
MainActivity.kt         sound
NaadaApplication.kt     sound
NaadaMediaService.kt    sound
server.py               compiles
pure_des.py             compiles, 7/7 FIPS vectors pass
```

## Apply

```bash
cp naada-android/android/app/src/main/java/com/adabala/naada/FlaskServerService.kt \
   android/app/src/main/java/com/adabala/naada/
cp naada-android/check_kotlin.py .
cp naada-android/.github/workflows/build-apk.yml .github/workflows/
git add -A
git commit -m "fix: unclosed nested block comment in FlaskServerService"
git push
```

Run `python3 check_kotlin.py` locally before pushing and it will catch this
class of error immediately.
