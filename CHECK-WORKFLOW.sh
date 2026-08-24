#!/bin/bash
# Run this INSIDE your naada repo to find out why the build isn't triggering.
echo "════════ Naada build diagnostics ════════"
echo

echo "1. Is the workflow file actually in the repo?"
if [ -f .github/workflows/build-apk.yml ]; then
  echo "   ✓ found: .github/workflows/build-apk.yml"
else
  echo "   ✗ MISSING — this is why nothing runs."
  echo "     Likely cause: you copied with 'cp -r src/* dest/'."
  echo "     The shell glob '*' does NOT match dotfiles, so .github was skipped."
  echo "     Fix:  cp -r /path/to/naada-android/.github ."
fi
echo

echo "2. Is it committed to git (not just sitting on disk)?"
if git ls-files --error-unmatch .github/workflows/build-apk.yml >/dev/null 2>&1; then
  echo "   ✓ tracked by git"
else
  echo "   ✗ NOT tracked. Run:  git add .github && git commit -m 'add workflow' && git push"
fi
echo

echo "3. What is your current branch?"
BR=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
echo "   branch: $BR"
if [ "$BR" != "main" ] && [ "$BR" != "master" ]; then
  echo "   ⚠ Workflow triggers on 'main' and 'master' only."
  echo "     Either push to one of those, or add '$BR' to the branches list."
fi
echo

echo "4. Has it been pushed to the remote?"
if git status -sb 2>/dev/null | head -1 | grep -q "ahead"; then
  echo "   ✗ You have unpushed commits. Run: git push"
else
  echo "   ✓ up to date with remote (or nothing to push)"
fi
echo

echo "5. Is a .gitignore blocking it?"
if git check-ignore -q .github/workflows/build-apk.yml 2>/dev/null; then
  echo "   ✗ .gitignore is excluding the workflow. Remove that rule."
else
  echo "   ✓ not ignored"
fi
echo

echo "6. Does the android project exist alongside it?"
[ -f android/app/build.gradle ] && echo "   ✓ android/app/build.gradle found" \
                                || echo "   ✗ android/ is missing or in the wrong place"
echo
echo "════════ If 1-6 are all ✓, check on GitHub ════════"
echo "  • Repo → Settings → Actions → General"
echo "    'Allow all actions and reusable workflows' must be selected."
echo "    On some accounts Actions is disabled by default."
echo "  • Repo → Actions tab — if you see the workflow listed there,"
echo "    click it and use the 'Run workflow' button to trigger manually."
