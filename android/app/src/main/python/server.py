"""
Naada Flask server — Android edition.

Runs on localhost:5000 inside the APK via Chaquopy.  All Python dependencies
(Flask, requests, cryptography) are bundled — no pip, no internet needed for
the backend itself.

Key differences from the OCI/Termux version of app.py:
  - No daemon / PID file handling (the Android service manages the process)
  - No SSL (plain HTTP on loopback is correct; Android enforces HTTPS for
    external traffic via network_security_config.xml)
  - No CLI args / naada.sh startup modes
  - Static files served from the directory alongside this file (Chaquopy
    packages src/main/python/ as a unit, so the static/ folder is a sibling)
  - Environment variables injected by the Android service at startup
"""

import os
import sys
import logging
import threading

# ── Path setup (Chaquopy makes this directory the Python root) ───────────
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")

# ── Logging (Android logcat) ─────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(name)s %(levelname)s %(message)s",
    stream=sys.stdout,
)
log = logging.getLogger("naada")

# ── Flask ─────────────────────────────────────────────────────────────────
from flask import Flask, jsonify, request, send_from_directory
import requests as http_requests

app = Flask(
    __name__,
    static_folder=STATIC_DIR,
    static_url_path="/static",
)

# ── Configuration from environment ───────────────────────────────────────
# These are set by FlaskServerService before importing this module
# Gemini key: read from the X-Gemini-Key request header (set by the
# user in Settings), then fall back to any NAADA_GEMINI_KEY env var.
# This means the APK needs NO build-time secret and NO env var — the user
# sets their key once in the app's Settings screen.
_GEMINI_KEY_DEFAULT = os.environ.get("NAADA_GEMINI_KEY", "")

def get_gemini_key():
    return (request.headers.get("X-Gemini-Key") or "").strip() or _GEMINI_KEY_DEFAULT
SAAVN_PROXY   = "https://www.jiosaavn.com/api.php"
SESS          = http_requests.Session()
SESS.headers.update({
    "User-Agent": "Mozilla/5.0 (Android) Naada/1.0",
    "Accept": "application/json",
    "Accept-Language": "en-IN,en;q=0.9",
})

# ── JioSaavn helper ───────────────────────────────────────────────────────
def saavn(params: dict, timeout: int = 12) -> dict:
    defaults = {
        "_format": "json",
        "_marker": "0",
        "api_version": "4",
        "ctx": "web6dot0",
    }
    defaults.update(params)
    lang = params.get("language")
    cookies = None
    if lang:
        order = [lang] + [x for x in ("hindi", "english", "tamil", "telugu") if x != lang]
        cookies = {"L": "%2C".join(order)}
    try:
        r = SESS.get(SAAVN_PROXY, params=defaults, timeout=timeout, cookies=cookies)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        log.warning("saavn() error: %s", e)
        return {}


# ── DES-ECB stream decryption ─────────────────────────────────────────────
# (identical to the main app.py — JioSaavn's stream URLs are encrypted)
import base64
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.ciphers import Cipher
from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES
from cryptography.hazmat.primitives.ciphers.modes import ECB as DES_ECB

_DES_KEY = bytes([0x38, 0x34, 0x36, 0x26, 0x2A, 0x23, 0x24, 0x24])

def _des_decrypt(enc_url: str) -> str:
    try:
        raw = base64.b64decode(enc_url.strip())
        cipher = Cipher(TripleDES(_DES_KEY * 3), DES_ECB(), default_backend())
        dec = cipher.decryptor()
        plain = dec.update(raw) + dec.finalize()
        pad = plain[-1]
        return plain[:-pad].decode("utf-8", errors="ignore")
    except Exception as e:
        log.warning("des_decrypt failed: %s", e)
        return ""


# ── Routes ────────────────────────────────────────────────────────────────


@app.route("/api/health")
def api_health():
    return jsonify({"ok": True, "mode": "android-bundled"})

@app.route("/")
@app.route("/index.html")
def index():
    return send_from_directory(STATIC_DIR, "index.html")


@app.route("/manifest.json")
def manifest():
    r = send_from_directory(STATIC_DIR, "manifest.json")
    r.headers["Cache-Control"] = "no-cache"
    return r


# Song stream URL
@app.route("/api/song/<song_id>")
def api_song(song_id):
    d = saavn({"__call": "song.getDetails", "pids": song_id})
    songs = d.get("songs") or [d]
    if not songs:
        return jsonify({"error": "not_found"}), 404
    s = songs[0]
    enc = (s.get("more_info") or {}).get("encrypted_media_url", "")
    url = _des_decrypt(enc) if enc else s.get("media_url", "")
    return jsonify({
        "url": url,
        "title": s.get("title", ""),
        "artist": ", ".join(a.get("name", "") for a in (s.get("more_info") or {}).get("artistMap", {}).get("primary_artists", [])),
        "image": s.get("image", "").replace("150x150", "500x500"),
        "duration": int(s.get("duration") or 0),
        "id": song_id,
    })


# Search
@app.route("/api/search")
def api_search():
    q = request.args.get("q", "").strip()
    t = request.args.get("type", "songs")
    lang = request.args.get("lang", "telugu")
    n = int(request.args.get("n", 20))
    if not q:
        return jsonify({"results": []})
    d = saavn({"__call": f"search.get{t.capitalize()}",
               "q": q, "language": lang, "n": n, "p": 1})
    return jsonify(d)


# Home / new releases
@app.route("/api/home/<lang>")
def api_home(lang):
    d = saavn({"__call": "content.getAlbums",
               "language": lang, "n": 20, "p": 1})
    return jsonify(d)


# Trending
@app.route("/api/trending/<lang>")
def api_trending(lang):
    d = saavn({"__call": "content.getTrending",
               "language": lang, "n": 20, "p": 1})
    return jsonify(d)


# Album details
@app.route("/api/album/<album_id>")
def api_album(album_id):
    d = saavn({"__call": "content.getAlbumDetails", "albumid": album_id})
    return jsonify(d)


# Artist details
@app.route("/api/artist/<artist_id>")
def api_artist(artist_id):
    d = saavn({"__call": "artist.getArtistPageDetails",
               "artistId": artist_id, "includeMetaTags": 0})
    return jsonify(d)


# JioSaavn song-radio (Start radio from this song)
@app.route("/api/radio")
def api_radio():
    sid = request.args.get("song_id", "")
    aid = request.args.get("artist_id", "")
    if sid:
        d = saavn({"__call": "webradio.createEntityStation",
                   "entity_id": sid, "entity_type": "queue"})
    elif aid:
        d = saavn({"__call": "webradio.createArtistStation",
                   "artist_id": aid, "includeMetaTags": 0})
    else:
        return jsonify({"error": "need song_id or artist_id"}), 400
    station = d.get("stationid", "")
    songs = []
    if station:
        d2 = saavn({"__call": "webradio.getSong", "stationid": station,
                    "k": "20", "next": "1"})
        songs = (d2.get("song") or {}).get("data") or []
    return jsonify({"songs": songs})


# Lyrics (via lrclib.net — free, no key)
@app.route("/api/lyrics")
def api_lyrics():
    title  = request.args.get("title", "")
    artist = request.args.get("artist", "")
    if not title:
        return jsonify({"error": "need title"}), 400
    try:
        r = http_requests.get(
            "https://lrclib.net/api/get",
            params={"track_name": title, "artist_name": artist},
            headers={"User-Agent": "Naada/1.0"},
            timeout=8,
        )
        if r.ok:
            d = r.json()
            return jsonify({
                "lyrics":     d.get("plainLyrics") or d.get("syncedLyrics") or "",
                "synced":     d.get("syncedLyrics") or "",
                "has_lyrics": bool(d.get("plainLyrics") or d.get("syncedLyrics")),
            })
    except Exception as e:
        log.warning("lyrics error: %s", e)
    return jsonify({"lyrics": "", "synced": "", "has_lyrics": False})


# Gemini AI — mood-based playlist + trivia (needs NAADA_GEMINI_KEY)

@app.route("/api/ai/status")
def api_ai_status():
    return jsonify({"enabled": bool(get_gemini_key()), "source": "header"})

@app.route("/api/ai/mood", methods=["POST"])
def api_ai_mood():
    if not get_gemini_key():
        return jsonify({"error": "no_key"}), 503
    data = request.get_json(force=True, silent=True) or {}
    prompt = data.get("prompt", "")
    lang   = data.get("lang", "telugu")
    try:
        payload = {
            "contents": [{"role": "user", "parts": [{"text":
                f"You are a {lang} music expert. Suggest 8 songs for this mood: {prompt}. "
                f"Reply with JSON: {{\"songs\": [{{\"title\": \"...\", \"artist\": \"...\"}}]}}"
            }]}],
            "generationConfig": {"responseMimeType": "application/json"},
        }
        r = http_requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"gemini-1.5-flash:generateContent?key={get_gemini_key()}",
            json=payload, timeout=20,
        )
        if r.ok:
            text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
            import json
            return jsonify(json.loads(text))
    except Exception as e:
        log.warning("ai/mood error: %s", e)
    return jsonify({"error": "ai_failed"}), 500


@app.route("/api/ai/trivia", methods=["POST"])
def api_ai_trivia():
    if not get_gemini_key():
        return jsonify({"error": "no_key"}), 503
    data = request.get_json(force=True, silent=True) or {}
    title = data.get("title", "")
    artist = data.get("artist", "")
    try:
        payload = {
            "contents": [{"role": "user", "parts": [{"text":
                f"Give me one interesting fact about the {title} song by {artist}. "
                f"Keep it under 60 words. Just the fact, no preamble."
            }]}],
        }
        r = http_requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"gemini-1.5-flash:generateContent?key={get_gemini_key()}",
            json=payload, timeout=15,
        )
        if r.ok:
            text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
            return jsonify({"fact": text.strip()})
    except Exception as e:
        log.warning("ai/trivia error: %s", e)
    return jsonify({"error": "ai_failed"}), 500


# ── Security headers ───────────────────────────────────────────────────────
@app.after_request
def _headers(resp):
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["X-Frame-Options"] = "SAMEORIGIN"
    resp.headers["Cache-Control"] = "no-store" if request.path.startswith("/api/") else resp.headers.get("Cache-Control", "no-cache")
    return resp


# ── Entry point called by FlaskServerService ──────────────────────────────
def run_server():
    """Called from Kotlin via Chaquopy. Blocks forever (Flask dev server)."""
    log.info("Naada Flask server starting on 127.0.0.1:5000")
    # Use Werkzeug's threaded server — handles concurrent API + static requests
    from werkzeug.serving import make_server
    srv = make_server("127.0.0.1", 5000, app, threaded=True)
    log.info("Naada Flask ready")
    srv.serve_forever()
