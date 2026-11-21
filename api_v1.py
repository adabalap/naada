"""
Naada API v1 — the contract the native Android client talks to.

What this module adds on top of app.py:

  1. /api/v1/* aliases for every existing /api/* route. The PWA keeps using
     /api/*; the native app only ever calls /api/v1/*. When a response shape
     has to change one day, it changes under /api/v2 and the installed app
     keeps working.

  2. GET /api/v1/stream/<id> — resolves a FRESH playable URL at play time.
     The app stores song ids, never CDN URLs, so an expired URL can't break
     a queue or a download.

  3. /api/v1/library/* — liked songs, playlists and play history kept in a
     small SQLite file next to app.py. The phone keeps an offline copy and
     syncs; reinstalling the app or switching phones loses nothing.

  4. GET /api/v1/info — version + capability handshake for the app's
     "Test connection" button.

Auth is handled by the existing NAADA_TOKEN gate in app.py, which now also
accepts an `X-Naada-Token` (or `Authorization: Bearer …`) header.
"""

import json
import os
import sqlite3
import threading
import time
import uuid

from flask import Blueprint, g, jsonify, request

API_VERSION = 1
SERVER_VERSION = "45.0"
HISTORY_CAP = 1000          # rows kept on the server
HISTORY_RETURN = 200        # rows returned by GET /library

bp = Blueprint("api_v1", __name__, url_prefix="/api/v1")

_DB_PATH = None
_schema_lock = threading.Lock()
_schema_ready = False
_resolve_song = None        # injected by register_v1: fn(song_id) -> dict | None
_ai_enabled = None          # injected: fn() -> bool


# ── SQLite ──────────────────────────────────────────────────────────────────
SCHEMA = """
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
CREATE INDEX IF NOT EXISTS history_played ON history(played_at);
CREATE TABLE IF NOT EXISTS meta (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);
"""


def _db():
    """One connection per request (gunicorn gthread runs requests on many
    threads; sqlite3 connections must not be shared across them)."""
    global _schema_ready
    if "naada_db" not in g:
        conn = sqlite3.connect(_DB_PATH, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        if not _schema_ready:
            with _schema_lock:
                if not _schema_ready:
                    conn.executescript(SCHEMA)
                    _schema_ready = True
        g.naada_db = conn
    return g.naada_db


@bp.teardown_app_request
def _close_db(_exc):
    conn = g.pop("naada_db", None)
    if conn is not None:
        conn.close()


def _bump_rev(conn):
    """Monotonic library revision. The app compares it to skip a full
    download when nothing changed."""
    row = conn.execute("SELECT v FROM meta WHERE k='rev'").fetchone()
    rev = int(row["v"]) + 1 if row else 1
    conn.execute("INSERT OR REPLACE INTO meta(k, v) VALUES('rev', ?)", (str(rev),))
    return rev


def _rev(conn):
    row = conn.execute("SELECT v FROM meta WHERE k='rev'").fetchone()
    return int(row["v"]) if row else 0


# Songs are stored as the same dict app.py's norm() produces, minus the
# stream URL (it expires; the app asks /stream/<id> at play time).
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


def _bad(msg, code=400):
    return jsonify({"error": msg}), code


# ── Info ────────────────────────────────────────────────────────────────────
@bp.route("/info")
def info():
    return jsonify({
        "app": "naada",
        "api_version": API_VERSION,
        "server_version": SERVER_VERSION,
        "ai_enabled": bool(_ai_enabled and _ai_enabled()),
        "features": ["library_sync", "stream_resolve", "lyrics", "radio",
                     "ai_anecdote", "ai_trivia", "ai_mood"],
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


# ── Library ─────────────────────────────────────────────────────────────────
def _library_payload(conn):
    likes = [json.loads(r["song_json"]) for r in conn.execute(
        "SELECT song_json FROM likes ORDER BY liked_at DESC")]
    playlists = []
    for p in conn.execute("SELECT * FROM playlists ORDER BY updated_at DESC"):
        songs = [json.loads(r["song_json"]) for r in conn.execute(
            "SELECT song_json FROM playlist_songs WHERE playlist_id=? ORDER BY pos",
            (p["id"],))]
        playlists.append({"id": p["id"], "name": p["name"],
                          "created_at": p["created_at"],
                          "updated_at": p["updated_at"], "songs": songs})
    history = [{"song": json.loads(r["song_json"]), "played_at": r["played_at"]}
               for r in conn.execute(
                   "SELECT song_json, played_at FROM history "
                   "ORDER BY played_at DESC LIMIT ?", (HISTORY_RETURN,))]
    return {"rev": _rev(conn), "likes": likes, "playlists": playlists,
            "history": history}


@bp.route("/library")
def library():
    conn = _db()
    since = request.args.get("since_rev", type=int)
    if since is not None and since == _rev(conn):
        return jsonify({"rev": since, "unchanged": True})
    return jsonify(_library_payload(conn))


@bp.route("/library/likes/<song_id>", methods=["PUT"])
def like(song_id):
    song = _clean_song(request.get_json(silent=True) or {"id": song_id})
    if not song or song["id"] != song_id:
        return _bad("song body with matching id required")
    conn = _db()
    with conn:
        conn.execute("INSERT OR REPLACE INTO likes(song_id, song_json, liked_at) "
                     "VALUES(?,?,?)", (song_id, json.dumps(song), time.time()))
        rev = _bump_rev(conn)
    return jsonify({"rev": rev})


@bp.route("/library/likes/<song_id>", methods=["DELETE"])
def unlike(song_id):
    conn = _db()
    with conn:
        conn.execute("DELETE FROM likes WHERE song_id=?", (song_id,))
        rev = _bump_rev(conn)
    return jsonify({"rev": rev})


def _write_playlist_songs(conn, pid, songs):
    conn.execute("DELETE FROM playlist_songs WHERE playlist_id=?", (pid,))
    pos = 0
    for s in songs or []:
        c = _clean_song(s)
        if c:
            conn.execute("INSERT INTO playlist_songs VALUES(?,?,?,?)",
                         (pid, pos, c["id"], json.dumps(c)))
            pos += 1


@bp.route("/library/playlists", methods=["POST"])
def create_playlist():
    body = request.get_json(silent=True) or {}
    name = str(body.get("name", "")).strip()[:120]
    if not name:
        return _bad("name required")
    pid = str(body.get("id") or uuid.uuid4())
    now = time.time()
    conn = _db()
    with conn:
        conn.execute("INSERT OR REPLACE INTO playlists VALUES(?,?,?,?)",
                     (pid, name, now, now))
        _write_playlist_songs(conn, pid, body.get("songs"))
        rev = _bump_rev(conn)
    return jsonify({"rev": rev, "id": pid}), 201


@bp.route("/library/playlists/<pid>", methods=["PUT"])
def update_playlist(pid):
    body = request.get_json(silent=True) or {}
    conn = _db()
    row = conn.execute("SELECT * FROM playlists WHERE id=?", (pid,)).fetchone()
    if not row:
        return _bad("not_found", 404)
    name = str(body.get("name", row["name"])).strip()[:120] or row["name"]
    with conn:
        conn.execute("UPDATE playlists SET name=?, updated_at=? WHERE id=?",
                     (name, time.time(), pid))
        if "songs" in body:
            _write_playlist_songs(conn, pid, body.get("songs"))
        rev = _bump_rev(conn)
    return jsonify({"rev": rev, "id": pid})


@bp.route("/library/playlists/<pid>", methods=["DELETE"])
def delete_playlist(pid):
    conn = _db()
    with conn:
        conn.execute("DELETE FROM playlist_songs WHERE playlist_id=?", (pid,))
        conn.execute("DELETE FROM playlists WHERE id=?", (pid,))
        rev = _bump_rev(conn)
    return jsonify({"rev": rev})


def _add_history(conn, items):
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
        # The app may retry an upload after a flaky network; skip exact dupes.
        dup = conn.execute("SELECT 1 FROM history WHERE song_id=? AND played_at=?",
                           (song["id"], at)).fetchone()
        if dup:
            continue
        conn.execute("INSERT INTO history(song_id, song_json, played_at) "
                     "VALUES(?,?,?)", (song["id"], json.dumps(song), at))
        added += 1
    conn.execute("DELETE FROM history WHERE id NOT IN "
                 "(SELECT id FROM history ORDER BY played_at DESC LIMIT ?)",
                 (HISTORY_CAP,))
    return added


@bp.route("/library/history", methods=["POST"])
def history():
    body = request.get_json(silent=True) or {}
    conn = _db()
    with conn:
        added = _add_history(conn, body.get("items"))
        rev = _bump_rev(conn) if added else _rev(conn)
    return jsonify({"rev": rev, "added": added})


@bp.route("/library/import", methods=["POST"])
def import_library():
    """One-shot merge of a PWA localStorage export (or a backup) into the
    server library. Existing items are kept; nothing is deleted."""
    body = request.get_json(silent=True) or {}
    conn = _db()
    now = time.time()
    with conn:
        n_likes = 0
        for s in body.get("likes") or []:
            c = _clean_song(s)
            if c:
                conn.execute("INSERT OR IGNORE INTO likes VALUES(?,?,?)",
                             (c["id"], json.dumps(c), now - n_likes))
                n_likes += 1
        n_pl = 0
        for p in body.get("playlists") or []:
            if not isinstance(p, dict) or not str(p.get("name", "")).strip():
                continue
            pid = str(p.get("id") or uuid.uuid4())
            conn.execute("INSERT OR REPLACE INTO playlists VALUES(?,?,?,?)",
                         (pid, str(p["name"]).strip()[:120], now, now))
            _write_playlist_songs(conn, pid, p.get("songs"))
            n_pl += 1
        n_hist = _add_history(conn, body.get("history"))
        rev = _bump_rev(conn)
    return jsonify({"rev": rev, "likes": n_likes, "playlists": n_pl,
                    "history": n_hist})


# ── Registration ────────────────────────────────────────────────────────────
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
