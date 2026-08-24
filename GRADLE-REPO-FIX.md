# Fix: "prefer settings repositories over project repositories"

## Good news first

Gradle is now actually running. The wrapper problem, the missing coroutines
dependency, and the other compile blockers are behind us — this failure
happens during *configuration*, much further along than before.

## The error

```
Build was configured to prefer settings repositories over project
repositories but repository 'Google' was added by build file 'build.gradle'
```

## What causes it

Two files were both trying to declare where dependencies come from.

`settings.gradle` had:
```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
```

`FAIL_ON_PROJECT_REPOS` means: *settings.gradle is the single source of
truth for repositories; abort if any build file declares its own.*

But `build.gradle` also had:
```groovy
allprojects {
    repositories { google(); mavenCentral() }
}
```

So Gradle did exactly what it was told and aborted. (Your log also shows a
`subprojects { repositories { ... } }` block, which fails for the same
reason.)

## The fix

Repositories for module dependencies now live **only** in
`settings.gradle`. The root `build.gradle` no longer has `allprojects` or
`subprojects` repository blocks.

### One subtlety worth knowing

The `buildscript { repositories { ... } }` block in `build.gradle` **stays**,
and that is correct. It is not a mistake or an inconsistency:

- `dependencyResolutionManagement` governs **module dependencies** —
  the libraries your app code imports.
- `buildscript` governs **the Gradle plugins themselves** — AGP, Kotlin,
  Chaquopy. That resolution happens before `dependencyResolutionManagement`
  exists, so it is not covered by it and must declare its own repositories.

Deleting the `buildscript` repositories to "be consistent" would break the
build in a new and more confusing way.

## Corrected files

**android/build.gradle** — buildscript only, no allprojects/subprojects
**android/settings.gradle** — pluginManagement + dependencyResolutionManagement

Verified with comments stripped: exactly one `repositories` block remains,
inside `buildscript`. No `allprojects`, no `subprojects`.

## What to do

Replace both files in your repo and push:

```bash
cp naada-android/android/build.gradle    android/build.gradle
cp naada-android/android/settings.gradle android/settings.gradle
git add android/build.gradle android/settings.gradle
git commit -m "fix: centralise repositories in settings.gradle"
git push
```

## If the next failure is Chaquopy

The most likely remaining unknown is whether `com.chaquo.python:gradle:15.0.1`
resolves. If the log shows "Plugin ... not found", the version needs
adjusting — the build step now prints the full stacktrace, so the error will
name it precisely rather than hiding behind "No APK found".
