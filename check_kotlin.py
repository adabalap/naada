#!/usr/bin/env python3
"""
Structural sanity check for Kotlin sources.

Catches the failure mode that produced 18 bogus "unresolved reference"
errors: Kotlin NESTS block comments, so a stray "/*" inside a comment (for
example in a path written with a wildcard) opens a level that must be closed
again. If it is not, the remainder of the file is swallowed as comment text
and every symbol after that point appears undefined.
"""
import os, re, sys

SRC = 'android/app/src/main/java/com/adabala/naada'
problems = []

for fn in sorted(os.listdir(SRC)):
    if not fn.endswith('.kt'):
        continue
    path = os.path.join(SRC, fn)
    s = open(path).read()

    # ---- 1. nested block-comment balance, tracking string literals -------
    depth = i = 0
    line = 1
    opens = []
    in_str = in_char = in_raw = in_line_comment = False
    # brace/paren counts, only outside comments and strings
    braces = parens = 0

    while i < len(s):
        c, nxt2, nxt3 = s[i], s[i:i+2], s[i:i+3]
        if c == '\n':
            line += 1
            in_line_comment = False
            i += 1
            continue

        if in_line_comment:
            i += 1; continue

        if in_raw:
            if nxt3 == '"""':
                in_raw = False; i += 3; continue
            i += 1; continue

        if in_str:
            if c == '\\': i += 2; continue
            if c == '"': in_str = False
            i += 1; continue

        if depth > 0:
            if nxt2 == '/*':
                depth += 1; opens.append(line); i += 2; continue
            if nxt2 == '*/':
                depth -= 1
                if opens: opens.pop()
                i += 2; continue
            i += 1; continue

        # not in a comment or string
        if nxt3 == '"""': in_raw = True; i += 3; continue
        if c == '"':      in_str = True; i += 1; continue
        if nxt2 == '//':  in_line_comment = True; i += 2; continue
        if nxt2 == '/*':  depth += 1; opens.append(line); i += 2; continue
        if c == '{': braces += 1
        elif c == '}': braces -= 1
        elif c == '(': parens += 1
        elif c == ')': parens -= 1
        i += 1

    if depth != 0:
        problems.append(f"{fn}: unclosed block comment (depth {depth}), opened at line(s) {opens}")
    if braces != 0:
        problems.append(f"{fn}: brace imbalance ({braces:+d})")
    if parens != 0:
        problems.append(f"{fn}: paren imbalance ({parens:+d})")

    # ---- 2. bare `Result<` shadows kotlin.Result -------------------------
    if re.search(r'(?<!MediaBrowserServiceCompat\.)(?<!\w)Result<', s):
        problems.append(f"{fn}: bare 'Result<' shadows kotlin.Result — qualify it")

    # ---- 3. onBind must return a nullable IBinder -----------------------
    m = re.search(r'override fun onBind\([^)]*\)\s*:\s*(IBinder\??)', s)
    if m and not m.group(1).endswith('?'):
        problems.append(f"{fn}: onBind returns {m.group(1)} — parent declares IBinder?")

    # ---- 4. API-level hazards vs minSdk 26 -------------------------------
    for api28 in ['longVersionCode']:
        if re.search(rf'\.{api28}\b', s):
            problems.append(f"{fn}: '{api28}' is API 28+, minSdk is 26 — use a Compat helper")

print("── Kotlin structural check ──")
if problems:
    for p in problems:
        print(f"  FAIL  {p}")
    sys.exit(1)
print("  all files structurally sound")
