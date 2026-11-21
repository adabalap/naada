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

import html
import json
import os
import secrets
import urllib.parse
import sqlite3
import threading
import time
import uuid

from flask import Blueprint, Response, g, jsonify, request

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
        "features": ["library_sync", "share", "history_clear", "stream_resolve", "lyrics", "radio",
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


@bp.route("/library/history", methods=["DELETE"])
def clear_history():
    conn = _db()
    with conn:
        conn.execute("DELETE FROM history")
        rev = _bump_rev(conn)
    return jsonify({"rev": rev})


# ── Sharing ─────────────────────────────────────────────────────────────────
# A share is a frozen snapshot of a playlist with an unguessable id. Anyone
# with the link can VIEW it (no token needed) — on the web page they get
# "open in Spotify / YouTube Music / JioSaavn / Apple Music" buttons, and
# Naada users can open it straight in the app.
SHARE_MAX_SONGS = 300


def _public_base():
    base = os.environ.get("NAADA_PUBLIC_URL", "").rstrip("/")
    return base or request.host_url.rstrip("/")


@bp.route("/share", methods=["POST"])
def create_share():
    body = request.get_json(silent=True) or {}
    name = str(body.get("name", "")).strip()[:120] or "Naada playlist"
    songs = [c for c in (_clean_song(s) for s in (body.get("songs") or [])[:SHARE_MAX_SONGS]) if c]
    if not songs:
        return _bad("songs required")
    sid = secrets.token_urlsafe(6)
    conn = _db()
    with conn:
        conn.execute("INSERT INTO shares VALUES(?,?,?,?)",
                     (sid, name, json.dumps(songs), time.time()))
    return jsonify({"id": sid, "url": f"{_public_base()}/share/{sid}"}), 201


def _load_share(sid):
    row = _db().execute("SELECT * FROM shares WHERE id=?", (sid,)).fetchone()
    if not row:
        return None
    return {"id": row["id"], "name": row["name"], "created_at": row["created_at"],
            "songs": json.loads(row["songs_json"])}


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
<header><div class="m">{covers}</div><h1>{title}</h1><p>{count} songs · shared from Naada</p>
<a class="app" href="naada://share/{esc(sid)}">Open in Naada</a></header>
<ul>{''.join(rows)}</ul>
<footer>Tap a service under any song to listen in your favourite app.</footer>
</body></html>"""
    return Response(page, mimetype="text/html")


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
    app.add_url_rule("/share/<sid>", endpoint="share_page", view_func=share_page)
