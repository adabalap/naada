# The build isn't triggering — here's how to find out why

## Most likely cause: `.github` is a hidden directory

If you copied the files with:
```bash
cp -r naada-android/* my-repo/
```

then **`.github` was silently skipped**. The shell glob `*` does not match
dotfiles. You'd have got `android/` and the `.md` files but no workflow —
and GitHub can't run a workflow that isn't there.

**Check in one command, from inside your repo:**
```bash
ls -la .github/workflows/
```

**If it's missing, fix it:**
```bash
cp -r /path/to/naada-android/.github .
git add .github
git commit -m "add build workflow"
git push
```

That push will trigger the build immediately.

## Second most likely: your default branch is `master`, not `main`

The old workflow only listened to `main`. On a repo whose default branch is
`master`, it would never fire — and the failure is completely silent. No
run, no error, nothing in the Actions tab.

**Fixed in this version:** the workflow now triggers on `main` *or*
`master`.

## Run the full diagnostic

`CHECK-WORKFLOW.sh` is included. Copy it into your repo root and run it:

```bash
bash CHECK-WORKFLOW.sh
```

It checks all six common causes:
1. Is the workflow file present?
2. Is it committed to git (not just on disk)?
3. What branch are you on?
4. Are there unpushed commits?
5. Is `.gitignore` excluding it?
6. Does `android/` exist alongside it?

## If all six pass, it's a GitHub setting

**Actions may be disabled on the repo.**
Repo → Settings → Actions → General → select
**"Allow all actions and reusable workflows"** → Save.

On some accounts, and on forks, Actions is off by default.

## Trigger it manually once it's visible

Repo → **Actions** tab → **Build Naada APK** in the left sidebar →
**Run workflow** dropdown → green **Run workflow** button.

If the workflow doesn't appear in that sidebar at all, it isn't on your
default branch — go back to step 1.

## Verifying from the command line

```bash
# Is the workflow tracked by git?
git ls-files .github/workflows/

# What does GitHub see on the default branch?
git ls-tree origin/main --name-only .github/workflows/
# (or origin/master)
```

If the second command prints nothing, the workflow never reached GitHub.
