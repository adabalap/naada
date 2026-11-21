"""
Naada API v1 — the contract the native Android client talks to.

  1. /api/v1/* aliases for every existing /api/* route (the PWA keeps /api/*).
  2. GET /api/v1/stream/<id> — a fresh playable URL at play time.
  3. Accounts for family & friends: every person has their own library
     (likes, playlists, history). The server owner is the admin; others join
     with an invite code and a PIN. A phone can hold several profiles.
  4. /api/v1/library/* — the signed-in person's library, with revisions so
     the app can sync cheaply.
  5. Sharing: public, view-only playlist pages.

Who is asking?
  - `X-Naada-Token: <personal token>`  → that person
  - the server's NAADA_TOKEN (or the PWA cookie), or no token on a server
    that has no NAADA_TOKEN                → the owner (admin)
"""

import hashlib
import hmac
import html
import json
import os
import secrets
import sqlite3
import threading
import time
import urllib.parse
import uuid
from datetime import date

from flask import Blueprint, Response, g, jsonify, request

API_VERSION = 1
SERVER_VERSION = "47.0"
HISTORY_CAP = 1000          # rows kept per person
HISTORY_RETURN = 200        # rows returned by GET /library
OWNER_ID = "owner"
INVITE_DAYS = 7
PIN_ROUNDS = 120_000
MAX_PIN_FAILS = 5
LOCK_SECONDS = 15 * 60
AI_DAILY_LIMIT = int(os.environ.get("NAADA_AI_DAILY_LIMIT", "60"))
AVATAR_COLORS = ["#E11D75", "#7B3FA0", "#0891B2", "#F59E0B", "#16A34A", "#EA580C", "#2563EB", "#DB2777"]

bp = Blueprint("api_v1", __name__, url_prefix="/api/v1")

_DB_PATH = None
_schema_lock = threading.Lock()
_schema_ready = False
_resolve_song = None        # injected: fn(song_id) -> dict | None
_ai_enabled = None          # injected: fn() -> bool


def _master_token():
    return os.environ.get("NAADA_TOKEN", "").strip()


# ── SQLite & migrations ─────────────────────────────────────────────────────
# v45 shipped a single-user schema. Migration 1 tags every existing row with
# the owner, so the library you already have becomes the owner's library.
BASE_SCHEMA = """
CREATE TABLE IF NOT EXISTS likes (
    song_id   TEXT PRIMARY KEY,
    song_json TEXT NOT NULL,
    liked_at  REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS playlists (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS playlist_songs (
    playlist_id TEXT NOT NULL,
    pos         INTEGER NOT NULL,
    song_id     TEXT NOT NULL,
    song_json   TEXT NOT NULL,
    PRIMARY KEY (playlist_id, pos)
);
CREATE TABLE IF NOT EXISTS history (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    song_id   TEXT NOT NULL,
    song_json TEXT NOT NULL,
    played_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS shares (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    songs_json TEXT NOT NULL,
    created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS meta (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);
"""

MIGRATION_1 = """
CREATE TABLE users (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    name_key   TEXT NOT NULL UNIQUE,
    color      TEXT NOT NULL,
    pin_hash   TEXT NOT NULL DEFAULT '',
    is_admin   INTEGER NOT NULL DEFAULT 0,
    created_at REAL NOT NULL,
    last_seen  REAL NOT NULL DEFAULT 0,
    pin_fails  INTEGER NOT NULL DEFAULT 0,
    locked_until REAL NOT NULL DEFAULT 0,
    disabled   INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE tokens (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    device     TEXT NOT NULL DEFAULT '',
    created_at REAL NOT NULL,
    last_seen  REAL NOT NULL DEFAULT 0
);
CREATE TABLE invites (
    code       TEXT PRIMARY KEY,
    created_by TEXT NOT NULL,
    user_id    TEXT,               -- set: a sign-in code for an existing person
    created_at REAL NOT NULL,
    expires_at REAL NOT NULL,
    used_by    TEXT,
    used_at    REAL
);
CREATE TABLE ai_usage (
    user_id TEXT NOT NULL,
    day     TEXT NOT NULL,
    n       INTEGER NOT NULL,
    PRIMARY KEY (user_id, day)
);

ALTER TABLE likes RENAME TO likes_v1;
CREATE TABLE likes (
    user_id   TEXT NOT NULL,
    song_id   TEXT NOT NULL,
    song_json TEXT NOT NULL,
    liked_at  REAL NOT NULL,
    PRIMARY KEY (user_id, song_id)
);
INSERT INTO likes SELECT 'owner', song_id, song_json, liked_at FROM likes_v1;
DROP TABLE likes_v1;

ALTER TABLE playlists ADD COLUMN user_id TEXT NOT NULL DEFAULT 'owner';
ALTER TABLE history ADD COLUMN user_id TEXT NOT NULL DEFAULT 'owner';
ALTER TABLE shares ADD COLUMN created_by TEXT NOT NULL DEFAULT 'owner';
CREATE INDEX IF NOT EXISTS history_user ON history(user_id, played_at);
CREATE INDEX IF NOT EXISTS playlists_user ON playlists(user_id);
UPDATE meta SET k = 'rev:owner' WHERE k = 'rev';
"""

MIGRATIONS = [MIGRATION_1]


def _migrate(conn):
    conn.executescript(BASE_SCHEMA)
    ver = conn.execute("PRAGMA user_version").fetchone()[0]
    for i, script in enumerate(MIGRATIONS[ver:], start=ver + 1):
        conn.executescript("BEGIN;" + script + f"PRAGMA user_version={i};COMMIT;")
    # The owner always exists and is the admin.
    if not conn.execute("SELECT 1 FROM users WHERE id=?", (OWNER_ID,)).fetchone():
        name = os.environ.get("NAADA_OWNER_NAME", "Owner").strip() or "Owner"
        conn.execute("INSERT INTO users(id, name, name_key, color, is_admin, created_at) "
                     "VALUES(?,?,?,?,1,?)", (OWNER_ID, name, name.lower(), AVATAR_COLORS[0], time.time()))
        conn.commit()


def _db():
    """One connection per request (gunicorn threads must not share one)."""
    global _schema_ready
    if "naada_db" not in g:
        conn = sqlite3.connect(_DB_PATH, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        if not _schema_ready:
            with _schema_lock:
                if not _schema_ready:
                    _migrate(conn)
                    _schema_ready = True
        g.naada_db = conn
    return g.naada_db


@bp.teardown_app_request
def _close_db(_exc):
    conn = g.pop("naada_db", None)
    if conn is not None:
        conn.close()


def _bad(msg, code=400, **extra):
    return jsonify({"error": msg, **extra}), code


# ── Identity ────────────────────────────────────────────────────────────────
def _sha(s):
    return hashlib.sha256(s.encode()).hexdigest()


def _request_token():
    tok = request.headers.get("X-Naada-Token", "").strip()
    auth = request.headers.get("Authorization", "")
    if not tok and auth.lower().startswith("bearer "):
        tok = auth[7:].strip()
    return tok


def _user_row(conn, uid):
    return conn.execute("SELECT * FROM users WHERE id=? AND disabled=0", (uid,)).fetchone()


def user_token_valid(token):
    """Used by app.py's access gate: a personal token is as good as NAADA_TOKEN."""
    if not token:
        return False
    try:
        conn = _db()
        row = conn.execute("SELECT t.user_id FROM tokens t JOIN users u ON u.id=t.user_id "
                           "WHERE t.token_hash=? AND u.disabled=0", (_sha(token),)).fetchone()
        return row is not None
    except Exception:
        return False


def current_user():
    """The person making this request, or None."""
    if "naada_user" in g:
        return g.naada_user
    conn = _db()
    tok = _request_token()
    user = None
    if tok:
        row = conn.execute("SELECT * FROM tokens WHERE token_hash=?", (_sha(tok),)).fetchone()
        if row:
            user = _user_row(conn, row["user_id"])
            if user and time.time() - (row["last_seen"] or 0) > 300:
                with conn:
                    conn.execute("UPDATE tokens SET last_seen=? WHERE token_hash=?", (time.time(), row["token_hash"]))
                    conn.execute("UPDATE users SET last_seen=? WHERE id=?", (time.time(), user["id"]))
    if user is None:
        master = _master_token()
        if not master:
            # Open server (no NAADA_TOKEN): unauthenticated requests act as the
            # owner, exactly as before accounts existed. A wrong personal
            # token is still rejected below rather than silently becoming owner.
            if not tok:
                user = _user_row(conn, OWNER_ID)
        elif (tok and hmac.compare_digest(tok, master)) or \
                hmac.compare_digest(request.cookies.get("naada_k", ""), master):
            user = _user_row(conn, OWNER_ID)
    g.naada_user = user
    return user


def _uid():
    u = current_user()
    return u["id"] if u else None


def _need_user():
    if not current_user():
        return _bad("sign_in_required", 401)
    return None


def _need_admin():
    u = current_user()
    if not u:
        return _bad("sign_in_required", 401)
    if not u["is_admin"]:
        return _bad("admin_only", 403)
    return None


def _public_user(u, conn=None):
    d = {"id": u["id"], "name": u["name"], "color": u["color"],
         "is_admin": bool(u["is_admin"]), "has_pin": bool(u["pin_hash"]),
         "last_seen": u["last_seen"]}
    return d


# ── PINs, tokens, codes ─────────────────────────────────────────────────────
def _hash_pin(pin):
    salt = secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac("sha256", pin.encode(), bytes.fromhex(salt), PIN_ROUNDS)
    return f"pbkdf2${PIN_ROUNDS}${salt}${dk.hex()}"


def _check_pin(pin, stored):
    try:
        _, rounds, salt, want = stored.split("$")
        dk = hashlib.pbkdf2_hmac("sha256", pin.encode(), bytes.fromhex(salt), int(rounds))
        return hmac.compare_digest(dk.hex(), want)
    except Exception:
        return False


def _valid_pin(pin):
    return isinstance(pin, str) and pin.isdigit() and 4 <= len(pin) <= 8


def _issue_token(conn, uid, device):
    tok = secrets.token_urlsafe(32)
    conn.execute("INSERT INTO tokens VALUES(?,?,?,?,?)", (_sha(tok), uid, device[:80], time.time(), time.time()))
    return tok


_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # no 0/O, 1/I


def _new_code():
    raw = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(8))
    return raw[:4] + "-" + raw[4:]


def _norm_code(c):
    c = str(c or "").upper().replace(" ", "").replace("-", "")
    return c[:4] + "-" + c[4:] if len(c) == 8 else ""


def _clean_name(n):
    n = " ".join(str(n or "").split())[:40]
    return n


# ── Auth endpoints (open even when NAADA_TOKEN is set) ──────────────────────
@bp.route("/auth/join", methods=["POST"])
def auth_join():
    """Redeem an invite (new person) or a sign-in code (existing person)."""
    body = request.get_json(silent=True) or {}
    code = _norm_code(body.get("code"))
    pin = str(body.get("pin", ""))
    device = str(body.get("device", ""))
    if not code:
        return _bad("invalid_code")
    if not _valid_pin(pin):
        return _bad("pin_must_be_4_to_8_digits")
    conn = _db()
    inv = conn.execute("SELECT * FROM invites WHERE code=?", (code,)).fetchone()
    if not inv or inv["used_at"] or inv["expires_at"] < time.time():
        return _bad("invalid_or_expired_code", 403)
    with conn:
        if inv["user_id"]:
            user = _user_row(conn, inv["user_id"])
            if not user:
                return _bad("invalid_or_expired_code", 403)
            uid = user["id"]
            conn.execute("UPDATE users SET pin_hash=?, pin_fails=0, locked_until=0 WHERE id=?", (_hash_pin(pin), uid))
        else:
            name = _clean_name(body.get("name"))
            if len(name) < 2:
                return _bad("name_required")
            if conn.execute("SELECT 1 FROM users WHERE name_key=?", (name.lower(),)).fetchone():
                return _bad("name_taken", 409)
            uid = uuid.uuid4().hex[:12]
            n_users = conn.execute("SELECT COUNT(*) FROM users").fetchone()[0]
            conn.execute("INSERT INTO users(id, name, name_key, color, pin_hash, created_at, last_seen) "
                         "VALUES(?,?,?,?,?,?,?)",
                         (uid, name, name.lower(), AVATAR_COLORS[n_users % len(AVATAR_COLORS)],
                          _hash_pin(pin), time.time(), time.time()))
        conn.execute("UPDATE invites SET used_by=?, used_at=? WHERE code=?", (uid, time.time(), code))
        token = _issue_token(conn, uid, device)
    return jsonify({"token": token, "user": _public_user(_user_row(conn, uid))})


@bp.route("/auth/login", methods=["POST"])
def auth_login():
    body = request.get_json(silent=True) or {}
    name = _clean_name(body.get("name")).lower()
    pin = str(body.get("pin", ""))
    conn = _db()
    u = conn.execute("SELECT * FROM users WHERE name_key=? AND disabled=0", (name,)).fetchone()
    if not u or not u["pin_hash"]:
        return _bad("wrong_name_or_pin", 403)
    now = time.time()
    if u["locked_until"] > now:
        return _bad("locked", 429, retry_after=int(u["locked_until"] - now))
    if not _check_pin(pin, u["pin_hash"]):
        fails = u["pin_fails"] + 1
        lock = now + LOCK_SECONDS if fails >= MAX_PIN_FAILS else 0
        with conn:
            conn.execute("UPDATE users SET pin_fails=?, locked_until=? WHERE id=?",
                         (0 if lock else fails, lock, u["id"]))
        return _bad("wrong_name_or_pin", 403)
    with conn:
        conn.execute("UPDATE users SET pin_fails=0, locked_until=0, last_seen=? WHERE id=?", (now, u["id"]))
        token = _issue_token(conn, u["id"], str(body.get("device", "")))
    return jsonify({"token": token, "user": _public_user(_user_row(conn, u["id"]))})


@bp.route("/auth/logout", methods=["POST"])
def auth_logout():
    tok = _request_token()
    if tok:
        conn = _db()
        with conn:
            conn.execute("DELETE FROM tokens WHERE token_hash=?", (_sha(tok),))
    return jsonify({"ok": True})


@bp.route("/me")
def me():
    u = current_user()
    if not u:
        return _bad("sign_in_required", 401)
    return jsonify(_public_user(u))


@bp.route("/me", methods=["PUT"])
def update_me():
    u = current_user()
    if not u:
        return _bad("sign_in_required", 401)
    body = request.get_json(silent=True) or {}
    conn = _db()
    with conn:
        if "name" in body:
            name = _clean_name(body.get("name"))
            if len(name) < 2:
                return _bad("name_required")
            other = conn.execute("SELECT id FROM users WHERE name_key=?", (name.lower(),)).fetchone()
            if other and other["id"] != u["id"]:
                return _bad("name_taken", 409)
            conn.execute("UPDATE users SET name=?, name_key=? WHERE id=?", (name, name.lower(), u["id"]))
        if "color" in body and str(body["color"]).startswith("#") and len(str(body["color"])) in (7, 9):
            conn.execute("UPDATE users SET color=? WHERE id=?", (str(body["color"]), u["id"]))
        if "pin" in body:
            pin = str(body["pin"])
            if not _valid_pin(pin):
                return _bad("pin_must_be_4_to_8_digits")
            # Changing an existing PIN needs the current one — unless the
            # request comes from the owner's master token (server admin).
            if u["pin_hash"] and not _check_pin(str(body.get("current_pin", "")), u["pin_hash"]):
                return _bad("wrong_current_pin", 403)
            conn.execute("UPDATE users SET pin_hash=?, pin_fails=0, locked_until=0 WHERE id=?", (_hash_pin(pin), u["id"]))
    g.pop("naada_user", None)
    return jsonify(_public_user(_user_row(conn, u["id"])))


# ── Admin (owner) ───────────────────────────────────────────────────────────
@bp.route("/admin/invites", methods=["POST"])
def admin_invite():
    err = _need_admin()
    if err:
        return err
    body = request.get_json(silent=True) or {}
    target = body.get("user_id")         # set → sign-in code for that person
    conn = _db()
    if target and not _user_row(conn, target):
        return _bad("not_found", 404)
    code = _new_code()
    days = min(max(int(body.get("days") or INVITE_DAYS), 1), 30)
    exp = time.time() + days * 86400
    with conn:
        conn.execute("INSERT INTO invites(code, created_by, user_id, created_at, expires_at) VALUES(?,?,?,?,?)",
                     (code, _uid(), target, time.time(), exp))
    return jsonify({"code": code, "expires_at": exp, "user_id": target}), 201


@bp.route("/admin/users")
def admin_users():
    err = _need_admin()
    if err:
        return err
    conn = _db()
    today = date.today().isoformat()
    out = []
    for u in conn.execute("SELECT * FROM users WHERE disabled=0 ORDER BY is_admin DESC, created_at"):
        d = _public_user(u)
        d["likes"] = conn.execute("SELECT COUNT(*) FROM likes WHERE user_id=?", (u["id"],)).fetchone()[0]
        d["playlists"] = conn.execute("SELECT COUNT(*) FROM playlists WHERE user_id=?", (u["id"],)).fetchone()[0]
        d["devices"] = conn.execute("SELECT COUNT(*) FROM tokens WHERE user_id=?", (u["id"],)).fetchone()[0]
        row = conn.execute("SELECT n FROM ai_usage WHERE user_id=? AND day=?", (u["id"], today)).fetchone()
        d["ai_today"] = row["n"] if row else 0
        out.append(d)
    pending = [dict(r) for r in conn.execute(
        "SELECT code, user_id, expires_at FROM invites WHERE used_at IS NULL AND expires_at>? ORDER BY created_at DESC",
        (time.time(),))]
    return jsonify({"users": out, "invites": pending, "ai_daily_limit": AI_DAILY_LIMIT})


@bp.route("/admin/users/<uid>", methods=["DELETE"])
def admin_remove_user(uid):
    err = _need_admin()
    if err:
        return err
    if uid == OWNER_ID:
        return _bad("cannot_remove_owner")
    conn = _db()
    with conn:
        conn.execute("UPDATE users SET disabled=1, name_key=name_key||':removed:'||? WHERE id=?", (str(time.time()), uid))
        conn.execute("DELETE FROM tokens WHERE user_id=?", (uid,))
    return jsonify({"ok": True})


@bp.route("/admin/invites/<code>", methods=["DELETE"])
def admin_cancel_invite(code):
    err = _need_admin()
    if err:
        return err
    conn = _db()
    with conn:
        conn.execute("DELETE FROM invites WHERE code=? AND used_at IS NULL", (_norm_code(code),))
    return jsonify({"ok": True})


# ── AI fair-use limit (per person per day; the owner is unlimited) ──────────
def _ai_gate():
    p = request.path
    if not (p.startswith("/api/v1/ai/") or p.startswith("/api/ai/")) or p.endswith("/status"):
        return None
    u = current_user()
    if not u or u["is_admin"]:
        return None
    conn = _db()
    today = date.today().isoformat()
    row = conn.execute("SELECT n FROM ai_usage WHERE user_id=? AND day=?", (u["id"], today)).fetchone()
    if row and row["n"] >= AI_DAILY_LIMIT:
        return jsonify({"error": "daily_limit", "cards": [], "songs": []}), 200
    with conn:
        conn.execute("INSERT INTO ai_usage VALUES(?,?,1) ON CONFLICT(user_id, day) DO UPDATE SET n=n+1",
                     (u["id"], today))
    return None


# ── Info ────────────────────────────────────────────────────────────────────
@bp.route("/info")
def info():
    u = current_user()
    return jsonify({
        "app": "naada",
        "api_version": API_VERSION,
        "server_version": SERVER_VERSION,
        "ai_enabled": bool(_ai_enabled and _ai_enabled()),
        "features": ["library_sync", "stream_resolve", "lyrics", "radio", "ai_anecdote",
                     "ai_trivia", "ai_mood", "share", "history_clear", "accounts"],
        "accounts": True,
        "token_required": bool(_master_token()),
        "user": _public_user(u) if u else None,
        "time": int(time.time()),
    })


# ── Stream resolution ───────────────────────────────────────────────────────
@bp.route("/stream/<song_id>")
def stream(song_id):
    song = _resolve_song(song_id) if _resolve_song else None
    if not song:
        return _bad("not_found", 404)
    url = song.get("url", "")
    if not url:
        return _bad("no_stream_url", 502)
    return jsonify({"id": song_id, "url": url, "song": _clean_song(song)})


# ── Library (always the signed-in person's) ─────────────────────────────────
_SONG_KEYS = ("id", "title", "artist", "album", "album_id", "image", "duration",
              "language", "year", "has_lyrics", "lyrics_id", "label", "explicit")


def _clean_song(s):
    if not isinstance(s, dict) or not str(s.get("id", "")).strip():
        return None
    out = {k: s.get(k) for k in _SONG_KEYS if k in s}
    out["id"] = str(s["id"]).strip()
    try:
        out["duration"] = int(out.get("duration") or 0)
    except (TypeError, ValueError):
        out["duration"] = 0
    return out


def _rev(conn, uid):
    row = conn.execute("SELECT v FROM meta WHERE k=?", ("rev:" + uid,)).fetchone()
    return int(row["v"]) if row else 0


def _bump_rev(conn, uid):
    rev = _rev(conn, uid) + 1
    conn.execute("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)", ("rev:" + uid, str(rev)))
    return rev


def _library_payload(conn, uid):
    likes = [json.loads(r["song_json"]) for r in conn.execute(
        "SELECT song_json FROM likes WHERE user_id=? ORDER BY liked_at DESC", (uid,))]
    playlists = []
    for p in conn.execute("SELECT * FROM playlists WHERE user_id=? ORDER BY updated_at DESC", (uid,)):
        songs = [json.loads(r["song_json"]) for r in conn.execute(
            "SELECT song_json FROM playlist_songs WHERE playlist_id=? ORDER BY pos", (p["id"],))]
        playlists.append({"id": p["id"], "name": p["name"], "created_at": p["created_at"],
                          "updated_at": p["updated_at"], "songs": songs})
    history = [{"song": json.loads(r["song_json"]), "played_at": r["played_at"]}
               for r in conn.execute(
                   "SELECT song_json, played_at FROM history WHERE user_id=? "
                   "ORDER BY played_at DESC LIMIT ?", (uid, HISTORY_RETURN))]
    row = conn.execute("SELECT v FROM meta WHERE k=?", ("folders:" + uid,)).fetchone()
    folders = json.loads(row["v"]) if row else []
    return {"rev": _rev(conn, uid), "likes": likes, "playlists": playlists, "history": history,
            "folders": folders}


@bp.route("/library")
def library():
    err = _need_user()
    if err:
        return err
    conn, uid = _db(), _uid()
    since = request.args.get("since_rev", type=int)
    if since is not None and since == _rev(conn, uid):
        return jsonify({"rev": since, "unchanged": True})
    return jsonify(_library_payload(conn, uid))


@bp.route("/library/likes/<song_id>", methods=["PUT"])
def like(song_id):
    err = _need_user()
    if err:
        return err
    song = _clean_song(request.get_json(silent=True) or {"id": song_id})
    if not song or song["id"] != song_id:
        return _bad("song body with matching id required")
    conn, uid = _db(), _uid()
    with conn:
        conn.execute("INSERT OR REPLACE INTO likes(user_id, song_id, song_json, liked_at) VALUES(?,?,?,?)",
                     (uid, song_id, json.dumps(song), time.time()))
        rev = _bump_rev(conn, uid)
    return jsonify({"rev": rev})


@bp.route("/library/likes/<song_id>", methods=["DELETE"])
def unlike(song_id):
    err = _need_user()
    if err:
        return err
    conn, uid = _db(), _uid()
    with conn:
        conn.execute("DELETE FROM likes WHERE user_id=? AND song_id=?", (uid, song_id))
        rev = _bump_rev(conn, uid)
    return jsonify({"rev": rev})


def _write_playlist_songs(conn, pid, songs):
    conn.execute("DELETE FROM playlist_songs WHERE playlist_id=?", (pid,))
    pos = 0
    for s in songs or []:
        c = _clean_song(s)
        if c:
            conn.execute("INSERT INTO playlist_songs VALUES(?,?,?,?)", (pid, pos, c["id"], json.dumps(c)))
            pos += 1


def _playlist_owner(conn, pid):
    row = conn.execute("SELECT user_id FROM playlists WHERE id=?", (pid,)).fetchone()
    return row["user_id"] if row else None


@bp.route("/library/playlists", methods=["POST"])
def create_playlist():
    err = _need_user()
    if err:
        return err
    body = request.get_json(silent=True) or {}
    name = str(body.get("name", "")).strip()[:120]
    if not name:
        return _bad("name required")
    conn, uid = _db(), _uid()
    pid = str(body.get("id") or uuid.uuid4())
    owner = _playlist_owner(conn, pid)
    if owner and owner != uid:
        return _bad("id_in_use", 409)
    now = time.time()
    with conn:
        conn.execute("INSERT OR REPLACE INTO playlists(id, name, created_at, updated_at, user_id) VALUES(?,?,?,?,?)",
                     (pid, name, now, now, uid))
        _write_playlist_songs(conn, pid, body.get("songs"))
        rev = _bump_rev(conn, uid)
    return jsonify({"rev": rev, "id": pid}), 201


@bp.route("/library/playlists/<pid>", methods=["PUT"])
def update_playlist(pid):
    err = _need_user()
    if err:
        return err
    body = request.get_json(silent=True) or {}
    conn, uid = _db(), _uid()
    row = conn.execute("SELECT * FROM playlists WHERE id=? AND user_id=?", (pid, uid)).fetchone()
    if not row:
        return _bad("not_found", 404)
    name = str(body.get("name", row["name"])).strip()[:120] or row["name"]
    with conn:
        conn.execute("UPDATE playlists SET name=?, updated_at=? WHERE id=?", (name, time.time(), pid))
        if "songs" in body:
            _write_playlist_songs(conn, pid, body.get("songs"))
        rev = _bump_rev(conn, uid)
    return jsonify({"rev": rev, "id": pid})


@bp.route("/library/playlists/<pid>", methods=["DELETE"])
def delete_playlist(pid):
    err = _need_user()
    if err:
        return err
    conn, uid = _db(), _uid()
    with conn:
        if _playlist_owner(conn, pid) == uid:
            conn.execute("DELETE FROM playlist_songs WHERE playlist_id=?", (pid,))
            conn.execute("DELETE FROM playlists WHERE id=?", (pid,))
        rev = _bump_rev(conn, uid)
    return jsonify({"rev": rev})


def _add_history(conn, uid, items):
    added = 0
    for it in items or []:
        if not isinstance(it, dict):
            continue
        song = _clean_song(it.get("song"))
        if not song:
            continue
        try:
            at = float(it.get("played_at") or time.time())
        except (TypeError, ValueError):
            at = time.time()
        if conn.execute("SELECT 1 FROM history WHERE user_id=? AND song_id=? AND played_at=?",
                        (uid, song["id"], at)).fetchone():
            continue        # a retried upload
        conn.execute("INSERT INTO history(song_id, song_json, played_at, user_id) VALUES(?,?,?,?)",
                     (song["id"], json.dumps(song), at, uid))
        added += 1
    conn.execute("DELETE FROM history WHERE user_id=? AND id NOT IN "
                 "(SELECT id FROM history WHERE user_id=? ORDER BY played_at DESC LIMIT ?)",
                 (uid, uid, HISTORY_CAP))
    return added


@bp.route("/library/history", methods=["POST"])
def history():
    err = _need_user()
    if err:
        return err
    body = request.get_json(silent=True) or {}
    conn, uid = _db(), _uid()
    with conn:
        added = _add_history(conn, uid, body.get("items"))
        rev = _bump_rev(conn, uid) if added else _rev(conn, uid)
    return jsonify({"rev": rev, "added": added})


@bp.route("/library/history", methods=["DELETE"])
def clear_history():
    err = _need_user()
    if err:
        return err
    conn, uid = _db(), _uid()
    with conn:
        conn.execute("DELETE FROM history WHERE user_id=?", (uid,))
        rev = _bump_rev(conn, uid)
    return jsonify({"rev": rev})


def _clean_folders(raw):
    """Folders group playlists (by id) and loose songs (remembering which
    playlist each came from, so deleting a folder can put them back)."""
    out = []
    for f in (raw or [])[:200]:
        if not isinstance(f, dict) or not str(f.get("id", "")).strip():
            continue
        songs = []
        for it in (f.get("songs") or [])[:1000]:
            if isinstance(it, dict):
                c = _clean_song(it.get("song"))
                if c:
                    songs.append({"song": c, "from": str(it.get("from") or "")[:64] or None})
        out.append({
            "id": str(f["id"])[:64],
            "name": str(f.get("name", "")).strip()[:120] or "Folder",
            "playlist_ids": [str(x)[:64] for x in (f.get("playlist_ids") or [])][:500],
            "songs": songs,
            "updated_at": float(f.get("updated_at") or time.time()),
        })
    return out


@bp.route("/library/folders", methods=["PUT"])
def put_folders():
    err = _need_user()
    if err:
        return err
    body = request.get_json(silent=True) or {}
    conn, uid = _db(), _uid()
    with conn:
        conn.execute("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)",
                     ("folders:" + uid, json.dumps(_clean_folders(body.get("folders")))))
        rev = _bump_rev(conn, uid)
    return jsonify({"rev": rev})


@bp.route("/library/import", methods=["POST"])
def import_library():
    """Merge a PWA export or a backup file into this person's library.
    Existing items are kept; nothing is deleted."""
    err = _need_user()
    if err:
        return err
    body = request.get_json(silent=True) or {}
    conn, uid = _db(), _uid()
    now = time.time()
    with conn:
        n_likes = 0
        for s in body.get("likes") or []:
            c = _clean_song(s)
            if c:
                conn.execute("INSERT OR IGNORE INTO likes VALUES(?,?,?,?)", (uid, c["id"], json.dumps(c), now - n_likes))
                n_likes += 1
        n_pl = 0
        for p in body.get("playlists") or []:
            if not isinstance(p, dict) or not str(p.get("name", "")).strip():
                continue
            pid = str(p.get("id") or uuid.uuid4())
            owner = _playlist_owner(conn, pid)
            if owner and owner != uid:
                pid = str(uuid.uuid4())     # same id in someone else's library
            conn.execute("INSERT OR REPLACE INTO playlists(id, name, created_at, updated_at, user_id) VALUES(?,?,?,?,?)",
                         (pid, str(p["name"]).strip()[:120], now, now, uid))
            _write_playlist_songs(conn, pid, p.get("songs"))
            n_pl += 1
        n_hist = _add_history(conn, uid, body.get("history"))
        if body.get("folders"):
            row = conn.execute("SELECT v FROM meta WHERE k=?", ("folders:" + uid,)).fetchone()
            have = json.loads(row["v"]) if row else []
            ids = {f["id"] for f in have}
            merged = have + [f for f in _clean_folders(body.get("folders")) if f["id"] not in ids]
            conn.execute("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)", ("folders:" + uid, json.dumps(merged)))
        rev = _bump_rev(conn, uid)
    return jsonify({"rev": rev, "likes": n_likes, "playlists": n_pl, "history": n_hist})


# ── Sharing ─────────────────────────────────────────────────────────────────
# A share is a frozen snapshot with an unguessable id. Anyone with the link
# can VIEW it (no token) and open each song in their own music app.
SHARE_MAX_SONGS = 300


def _public_base():
    base = os.environ.get("NAADA_PUBLIC_URL", "").rstrip("/")
    return base or request.host_url.rstrip("/")


@bp.route("/share", methods=["POST"])
def create_share():
    err = _need_user()
    if err:
        return err
    body = request.get_json(silent=True) or {}
    name = str(body.get("name", "")).strip()[:120] or "Naada playlist"
    songs = [c for c in (_clean_song(s) for s in (body.get("songs") or [])[:SHARE_MAX_SONGS]) if c]
    if not songs:
        return _bad("songs required")
    sid = secrets.token_urlsafe(6)
    conn = _db()
    with conn:
        conn.execute("INSERT INTO shares(id, name, songs_json, created_at, created_by) VALUES(?,?,?,?,?)",
                     (sid, name, json.dumps(songs), time.time(), _uid()))
    return jsonify({"id": sid, "url": f"{_public_base()}/share/{sid}"}), 201


def _load_share(sid):
    row = _db().execute("SELECT * FROM shares WHERE id=?", (sid,)).fetchone()
    if not row:
        return None
    by = _db().execute("SELECT name FROM users WHERE id=?", (row["created_by"],)).fetchone()
    return {"id": row["id"], "name": row["name"], "created_at": row["created_at"],
            "by": by["name"] if by else "", "songs": json.loads(row["songs_json"])}


@bp.route("/share/<sid>", methods=["GET"])
def get_share(sid):
    sh = _load_share(sid)
    return jsonify(sh) if sh else _bad("not_found", 404)


def _links(song):
    q = " ".join(x for x in (song.get("title", ""), (song.get("artist") or "").split(",")[0]) if x)
    e = urllib.parse.quote(q)
    return [("Spotify", f"https://open.spotify.com/search/{e}"),
            ("YouTube Music", f"https://music.youtube.com/search?q={e}"),
            ("JioSaavn", f"https://www.jiosaavn.com/search/song/{e}"),
            ("Apple Music", f"https://music.apple.com/search?term={e}")]


def share_page(sid):
    """Public, server-rendered page for a shared playlist."""
    sh = _load_share(sid)
    if not sh:
        return Response("<h1>This playlist link has expired or never existed.</h1>", 404,
                        mimetype="text/html")
    esc = html.escape
    rows = []
    for i, s in enumerate(sh["songs"], 1):
        img = esc((s.get("image") or "").replace("http://", "https://"))
        btns = "".join(f'<a class="b" href="{esc(u)}" target="_blank" rel="noopener">{esc(n)}</a>'
                       for n, u in _links(s))
        rows.append(
            f'<li><span class="n">{i}</span>'
            f'<img src="{img}" alt="" loading="lazy">'
            f'<div class="t"><b>{esc(s.get("title",""))}</b><small>{esc(s.get("artist",""))}'
            f'{" · " + esc(s["album"]) if s.get("album") else ""}</small>'
            f'<div class="bs">{btns}</div></div></li>')
    covers = "".join(f'<img src="{esc((s.get("image") or "").replace("http://","https://"))}" alt="">'
                     for s in sh["songs"][:4])
    title = esc(sh["name"])
    count = len(sh["songs"])
    page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title} · Naada</title>
<meta property="og:title" content="{title}">
<meta property="og:description" content="{count} songs · shared from Naada">
<meta property="og:image" content="{esc((sh['songs'][0].get('image') or '').replace('http://','https://'))}">
<style>
:root{{color-scheme:dark}}*{{box-sizing:border-box}}
body{{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#120C1A;color:#EDE6F3}}
header{{padding:32px 20px 20px;text-align:center;background:linear-gradient(160deg,#4A1A6B,#E11D75 70%,#F59E0B)}}
.m{{display:grid;grid-template-columns:1fr 1fr;width:180px;height:180px;margin:0 auto 16px;border-radius:18px;overflow:hidden;box-shadow:0 12px 40px #0008}}
.m img{{width:100%;height:100%;object-fit:cover}}h1{{margin:0;font-size:26px}}header p{{margin:6px 0 0;opacity:.85}}
.app{{display:inline-block;margin-top:16px;padding:11px 22px;border-radius:999px;background:#fff;color:#4A1A6B;font-weight:700;text-decoration:none}}
ul{{list-style:none;margin:0 auto;padding:8px 12px 40px;max-width:720px}}
li{{display:flex;gap:12px;align-items:flex-start;padding:12px 6px;border-bottom:1px solid #2a2033}}
.n{{width:22px;opacity:.5;padding-top:14px;text-align:right;font-size:13px}}
li img{{width:52px;height:52px;border-radius:8px;object-fit:cover;background:#271D33}}
.t{{flex:1;min-width:0}}.t b{{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
.t small{{display:block;opacity:.65;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
.bs{{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}}
.b{{font-size:12px;padding:5px 10px;border-radius:999px;background:#271D33;color:#D7A6FF;text-decoration:none}}
footer{{text-align:center;opacity:.5;font-size:12px;padding-bottom:30px}}
</style></head><body>
<header><div class="m">{covers}</div><h1>{title}</h1><p>{count} songs · shared {('by ' + esc(sh['by']) + ' ') if sh.get('by') else ''}from Naada</p>
<a class="app" href="naada://share/{esc(sid)}">Open in Naada</a></header>
<ul>{''.join(rows)}</ul>
<footer>Tap a service under any song to listen in your favourite app.</footer>
</body></html>"""
    return Response(page, mimetype="text/html")




# ── Registration ────────────────────────────────────────────────────────────
OPEN_PATHS = ("/api/v1/auth/", "/api/v1/info")


def register_v1(app, base_dir, resolve_song, ai_enabled):
    """Attach the v1 blueprint and alias every existing /api/* route under
    /api/v1/*. Call once, after all app routes are defined."""
    global _DB_PATH, _resolve_song, _ai_enabled
    _DB_PATH = os.environ.get("NAADA_DB", os.path.join(base_dir, "library.db"))
    _resolve_song = resolve_song
    _ai_enabled = ai_enabled

    existing = [r for r in app.url_map.iter_rules()
                if r.rule.startswith("/api/") and not r.rule.startswith("/api/v1/")]
    for rule in existing:
        methods = sorted(m for m in (rule.methods or ()) if m not in ("HEAD", "OPTIONS"))
        app.add_url_rule("/api/v1/" + rule.rule[len("/api/"):],
                         endpoint="v1_" + rule.endpoint,
                         view_func=app.view_functions[rule.endpoint],
                         methods=methods)
    app.register_blueprint(bp)
    app.add_url_rule("/share/<sid>", endpoint="share_page", view_func=share_page)
    app.before_request(_ai_gate)
