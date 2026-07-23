"""
Naada (నాద) — Personal Indian Music Player
Flask backend · HTTPS · JioSaavn unofficial API
Production grade with daemon support and structured logging

Run:  python app.py           (foreground)
      python app.py --daemon  (background, logs to logs/naada.log)
      python app.py --stop    (stop daemon)
      python app.py --status  (check if running)
"""

import os, re, sys, base64, json, signal, time, logging, logging.handlers
import requests
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.ciphers import Cipher
from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES
from cryptography.hazmat.primitives.ciphers.modes import ECB as DES_ECB
from flask import Flask, jsonify, request, send_from_directory
try:
    from flask_cors import CORS
    _HAS_CORS = True
except ImportError:
    _HAS_CORS = False

# ── Paths ──────────────────────────────────────────────────────────────────
BASE_DIR  = os.path.dirname(os.path.abspath(__file__))
LOG_DIR   = os.path.join(BASE_DIR, "logs")
LOG_FILE  = os.path.join(LOG_DIR, "naada.log")
PID_FILE  = os.path.join(BASE_DIR, "naada.pid")
CONFIG_FILE = os.path.join(BASE_DIR, "config.json")
SSL_CERT  = os.path.expanduser("~/.sms-agent/cert.pem")
SSL_KEY   = os.path.expanduser("~/.sms-agent/key.pem")
os.makedirs(LOG_DIR, exist_ok=True)

# ── Config (Gemini API key, etc.) ───────────────────────────────────────────
# Paste your key into config.json:  { "gemini_api_key": "AIza..." }
# Falls back to the GEMINI_API_KEY environment variable if the file is absent.
def load_config():
    cfg = {}
    try:
        with open(CONFIG_FILE) as f:
            cfg = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        pass
    if not cfg.get("gemini_api_key"):
        cfg["gemini_api_key"] = os.environ.get("GEMINI_API_KEY", "")
    return cfg

CONFIG = load_config()

# ── Logging setup ──────────────────────────────────────────────────────────
def setup_logging(to_file=False):
    fmt = logging.Formatter(
        "%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    )
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.handlers.clear()

    if to_file:
        # Rotating file: 5MB × 5 backups = up to 25MB of logs
        fh = logging.handlers.RotatingFileHandler(
            LOG_FILE, maxBytes=5*1024*1024, backupCount=5, encoding="utf-8"
        )
        fh.setFormatter(fmt)
        fh.setLevel(logging.DEBUG)
        root.addHandler(fh)
    else:
        ch = logging.StreamHandler(sys.stdout)
        ch.setFormatter(fmt)
        root.addHandler(ch)

    # Silence noisy werkzeug access log in file mode
    if to_file:
        wz = logging.getLogger("werkzeug")
        wz.setLevel(logging.WARNING)

log = logging.getLogger("naada")

# ── Flask app ──────────────────────────────────────────────────────────────
app = Flask(__name__, static_folder="static", static_url_path="/static")
if _HAS_CORS:
    CORS(app)

# When running behind a reverse proxy (nginx → Cloudflare), trust the
# X-Forwarded-* headers so request.scheme/host reflect the real client request.
# Harmless on Termux (no proxy sets these headers). Enabled when NAADA_HTTP_ONLY
# is set, i.e. the dedicated-server deployment path.
if os.environ.get("NAADA_HTTP_ONLY", "").lower() in ("1", "true", "yes"):
    try:
        from werkzeug.middleware.proxy_fix import ProxyFix
        app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)
    except Exception as _e:
        pass

# ── JioSaavn session ───────────────────────────────────────────────────────
SAAVN = "https://www.jiosaavn.com/api.php"
SESS  = requests.Session()
SESS.headers.update({
    "User-Agent":      "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120 Safari/537.36",
    "Referer":         "https://www.jiosaavn.com/",
    "Accept":          "application/json, text/plain, */*",
    "Accept-Language": "en-IN,en;q=0.9,te;q=0.8,hi;q=0.7",
    "Origin":          "https://www.jiosaavn.com",
})

# Keep cookies alive (JioSaavn sometimes gates behind a cookie)
def refresh_session():
    try:
        SESS.get("https://www.jiosaavn.com/", timeout=8)
        log.info("Session cookies refreshed")
    except Exception as e:
        log.warning(f"Session refresh failed: {e}")

# ── URL decryption ─────────────────────────────────────────────────────────
#
# JioSaavn uses DES-ECB (NOT CBC) with key b'38346591'.
# The encrypted_media_url is standard base64 of the DES-ECB ciphertext.
# Confirmed by testing: ECB gives clean URLs like:
#   https://aac.saavncdn.com/026/4525eb..._320.mp4
#
def decrypt_url(enc: str) -> str:
    """Decrypt a JioSaavn encrypted_media_url to a playable https:// URL."""
    if not enc:
        return ""
    enc = enc.strip()
    if enc.startswith("http"):
        return _upgrade_quality(enc)
    try:
        key    = b"38346591"
        raw    = base64.b64decode(enc + "==")
        # DES-ECB via TripleDES(key*3) — equivalent to single DES, ECB mode
        cipher = Cipher(TripleDES(key * 3), DES_ECB(), backend=default_backend())
        dec    = cipher.decryptor().update(raw) + cipher.decryptor().finalize()
        # Strip PKCS5 padding
        pad = dec[-1]
        if 1 <= pad <= 8:
            dec = dec[:-pad]
        url = dec.decode("utf-8", errors="ignore").strip()
        if url.startswith("http"):
            return _upgrade_quality(url)
        log.warning(f"decrypt_url: no valid URL | enc={enc[:40]}… dec={url[:50]!r}")
        return ""
    except Exception as e:
        log.warning(f"decrypt_url exception: {e} | enc={enc[:40]}…")
        return ""

def _upgrade_quality(url: str) -> str:
    """Swap lower quality markers for 320kbps."""
    for low, hi in [("_96.", "_320."), ("_160.", "_320."), ("_48.", "_320."),
                    ("_96.mp4", "_320.mp4"), ("_160.mp4", "_320.mp4")]:
        url = url.replace(low, hi)
    return url

def extract_stream_url(song_data: dict) -> str:
    """
    Try multiple fields in priority order:
    1. download_url array (newer API versions, direct CDN links)
    2. media_url (sometimes present, unencrypted)
    3. encrypted_media_url (decrypt with DES)
    4. media_preview_url (30s preview fallback)
    """
    mi = song_data.get("more_info", {}) or {}

    # Strategy 1: download_url array — direct CDN links, no decryption needed
    dl_urls = mi.get("download_url") or song_data.get("download_url") or []
    if isinstance(dl_urls, list) and dl_urls:
        # Sorted by quality: pick 320 > 160 > 128 > 96
        for quality in ["320", "160", "128", "96"]:
            for item in dl_urls:
                if isinstance(item, dict) and str(item.get("quality","")) == quality:
                    u = item.get("link","") or item.get("url","")
                    if u and u.startswith("http"):
                        log.debug(f"Using download_url [{quality}kbps]")
                        return u

    # Strategy 2: unencrypted media_url
    media_url = mi.get("media_url") or song_data.get("media_url","")
    if media_url and media_url.startswith("http"):
        log.debug("Using media_url (unencrypted)")
        return _upgrade_quality(media_url)

    # Strategy 3: encrypted_media_url → DES decrypt
    enc = mi.get("encrypted_media_url","") or song_data.get("encrypted_media_url","")
    if enc:
        url = decrypt_url(enc)
        if url and url.startswith("http"):
            log.debug("Using decrypted encrypted_media_url")
            return url

    # Strategy 4: 320kbps CDN pattern guess from song ID (works for many songs)
    song_id = song_data.get("id","")
    if song_id:
        guess = f"https://aac.saavncdn.com/{song_id}_320.mp4"
        log.debug(f"Guessing CDN URL for song {song_id}")
        return guess

    return ""

# ── Image helper ───────────────────────────────────────────────────────────
def hi_res(img) -> str:
    if not img: return ""
    if isinstance(img, list):
        img = img[-1].get("link","") if img else ""
    s = str(img)
    for r in ["150x150","50x50","175x175","100x100","250x250"]:
        s = s.replace(r, "500x500")
    return s

# ── Song normaliser ────────────────────────────────────────────────────────
def norm(s: dict) -> dict:
    if not s or not isinstance(s, dict): return {}
    mi  = s.get("more_info", {}) or {}
    am  = mi.get("artistMap", {}) or {}
    pri = am.get("primary_artists", []) or []
    artist = (", ".join(a["name"] for a in pri if a.get("name"))
              or mi.get("singers","") or s.get("singers",""))
    url = extract_stream_url(s)
    result = {
        "id":         s.get("id",""),
        "title":      s.get("title","") or s.get("song",""),
        "artist":     artist,
        "album":      mi.get("album","") or s.get("album",""),
        "album_id":   mi.get("albumid","") or s.get("albumid",""),
        "image":      hi_res(s.get("image","")),
        "duration":   int(mi.get("duration","0") or s.get("duration","0") or 0),
        "url":        url,
        "language":   s.get("language",""),
        "year":       s.get("year",""),
        "has_lyrics": str(mi.get("has_lyrics","false")).lower() == "true",
        "lyrics_id":  mi.get("lyrics_id","") or s.get("id",""),
        "label":      mi.get("label",""),
        "explicit":   str(mi.get("explicit_content","0")) == "1",
    }
    if not url:
        log.warning(f"No stream URL resolved for song id={result['id']} title={result['title']!r}")
    return result

# ── API request helper ─────────────────────────────────────────────────────
def saavn(params: dict, timeout=12) -> dict:
    defaults = {"_format":"json","_marker":"0","api_version":"4","ctx":"web6dot0"}
    defaults.update(params)
    try:
        r = SESS.get(SAAVN, params=defaults, timeout=timeout)
        r.raise_for_status()
        data = r.json()
        # JioSaavn sometimes returns literal JSON `null` or a list. Normalise:
        # callers expect a dict and do `"_error" not in d`, so guarantee a dict.
        if data is None:
            return {"_error": "null_response"}
        if isinstance(data, list):
            return {"_list": data}
        if not isinstance(data, dict):
            return {"_error": "unexpected_response"}
        return data
    except requests.exceptions.Timeout:
        log.error(f"Timeout calling JioSaavn __call={params.get('__call','?')}")
        return {"_error":"timeout"}
    except requests.exceptions.ConnectionError as e:
        log.error(f"Connection error: {e}")
        return {"_error":"connection_error"}
    except Exception as e:
        log.error(f"saavn() error: {e}")
        return {"_error":str(e)}

# ── Static routes ──────────────────────────────────────────────────────────
@app.route("/")
def index():
    return send_from_directory("static", "index.html")

@app.route("/manifest.json")
def manifest():
    r = send_from_directory("static","manifest.json")
    r.headers["Content-Type"] = "application/manifest+json"
    return r

@app.route("/sw.js")
def sw():
    r = send_from_directory("static","sw.js")
    r.headers["Content-Type"]           = "application/javascript"
    r.headers["Service-Worker-Allowed"] = "/"
    # Never cache the SW itself, so version bumps always take effect
    r.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    return r

@app.route("/favicon.ico")
def favicon_ico():
    r = send_from_directory("static/icons", "favicon.ico")
    r.headers["Content-Type"] = "image/x-icon"
    return r

@app.route("/favicon.png")
def favicon_png():
    return send_from_directory("static/icons", "icon-192.png")

# ── Search ─────────────────────────────────────────────────────────────────
@app.route("/api/search")
def search():
    q = request.args.get("q","").strip()
    if not q: return jsonify({"error":"No query"}), 400
    log.info(f"Search songs: {q!r}")
    d = saavn({"__call":"search.getResults","n":"20","p":"1","q":q})
    songs = [norm(s) for s in d.get("results",[]) if s.get("type")=="song"]
    return jsonify({"songs":songs})

@app.route("/api/search/albums")
def search_albums():
    q = request.args.get("q","").strip()
    log.info(f"Search albums: {q!r}")
    d = saavn({"__call":"search.getAlbumResults","n":"12","p":"1","q":q})
    albums = []
    for a in d.get("results",[]):
        albums.append({"id":a.get("albumid","") or a.get("id",""),
                       "title":a.get("title","") or a.get("album",""),
                       "artist":a.get("music","") or a.get("subtitle",""),
                       "image":hi_res(a.get("image","")),
                       "year":a.get("year",""),
                       "language":a.get("language","")})
    return jsonify({"albums":albums})

@app.route("/api/search/artists")
def search_artists():
    q = request.args.get("q","").strip()
    log.info(f"Search artists: {q!r}")
    d = saavn({"__call":"search.getArtistResults","n":"10","p":"1","q":q})
    artists = [{"id":a.get("artistid","") or a.get("id",""),
                "name":a.get("title","") or a.get("name",""),
                "image":hi_res(a.get("image","")),
                "role":a.get("role","")}
               for a in d.get("results",[])]
    return jsonify({"artists":artists})

# ── Charts / trending ──────────────────────────────────────────────────────
#
# JioSaavn featured playlists are addressed by a permalink TOKEN (the trailing
# segment of the playlist URL, e.g. .../telugu-india-superhits-top-50/<TOKEN>).
# Tokens are far more stable than the old numeric list IDs, which JioSaavn
# rotates frequently. We resolve token → playlist via the webapi.get endpoint.
#
# "India Superhits Top 50" exists per-language and is JioSaavn's flagship chart.
#
CHART_TOKENS = {
    "hindi":   "zlJfJYVuyjpxWb5,FqsjKg__",   # Hindi: India Superhits Top 50 (45 songs)
    "telugu":  "4O6DwO-qteN613W6L-cCSw__",   # Telugu: India Superhits Top 50
    "tamil":   "I3kvhipIy71ufxkxMEIbIw__",   # Tamil: India Superhits Top 50
    "english": "VuJUPQ9ch77bB,U5Yp5iAA__",   # India Superhits Top 50 (46 songs, global)
}
# Robust fallback: language-targeted search if token resolution fails
CHART_FALLBACK = {
    "hindi":   "trending hindi songs",
    "telugu":  "trending telugu songs",
    "tamil":   "trending tamil songs",
    "english": "trending english songs",
}

def _songs_from_playlist(data):
    """Extract normalised songs from any playlist response shape."""
    if not isinstance(data, dict):
        return []
    raw = data.get("list") or data.get("songs") or data.get("_list") or []
    if isinstance(raw, dict):
        raw = raw.get("list") or raw.get("songs") or []
    return [norm(s) for s in raw if isinstance(s, dict)]

def _albums_from_response(data):
    """content.getAlbums returns albums under varying keys; normalise."""
    if not isinstance(data, dict):
        return []
    raw = data.get("data") or data.get("_list") or data.get("albums") or []
    if isinstance(raw, dict):
        raw = raw.get("data") or raw.get("albums") or []
    out = []
    for a in raw:
        if not isinstance(a, dict):
            continue
        aid = a.get("albumid") or a.get("id") or ""
        if not aid:
            continue
        out.append({"id": aid, "title": a.get("title",""),
                    "artist": a.get("music","") or a.get("subtitle",""),
                    "image": hi_res(a.get("image","")),
                    "year": a.get("year","")})
    return out

@app.route("/api/charts/<lang>")
def charts(lang):
    lang = lang.lower()
    log.info(f"Loading charts for lang={lang}")

    # Strategy 1: resolve featured playlist by permalink token
    token = CHART_TOKENS.get(lang)
    if token:
        d = saavn({"__call":"webapi.get","token":token,
                   "type":"playlist","p":"1","n":"50"}, timeout=15)
        if "_error" not in d:
            songs = _songs_from_playlist(d)
            if songs:
                log.info(f"Charts {lang}: {len(songs)} songs from token {token}")
                return jsonify({"songs":songs,
                                "title":d.get("title") or d.get("listname") or "Top Songs"})
        log.warning(f"Token {token} returned no songs for {lang}, trying search")

    # Strategy 2: language-targeted search
    q  = CHART_FALLBACK.get(lang, "trending songs")
    d2 = saavn({"__call":"search.getResults","n":"30","p":"1","q":q})
    songs = [norm(s) for s in d2.get("results",[]) if s.get("type")=="song"]
    if songs:
        log.info(f"Charts {lang}: {len(songs)} songs from search fallback")
        return jsonify({"songs":songs,"title":f"Trending {lang.title()}"})

    log.error(f"All chart strategies failed for {lang}")
    return jsonify({"error":"Could not load charts","songs":[]}), 200

@app.route("/api/trending/<lang>")
def trending(lang):
    """Real 'trending now' feed — distinct from the Top-50 Chartbusters list.
    Uses JioSaavn's trending content; falls back to a recency-oriented search."""
    lang = lang.lower()
    log.info(f"Loading trending for lang={lang}")
    # Strategy 1: JioSaavn trending songs feed
    d = saavn({"__call":"content.getTrending","language":lang,
               "entity_type":"song","entity_language":lang}, timeout=15)
    songs = []
    if "_error" not in d:
        raw = d.get("_list") if isinstance(d.get("_list"), list) else (d if isinstance(d, list) else [])
        # content.getTrending may return a bare list (normalised to _list by saavn())
        if not raw and isinstance(d, dict):
            raw = d.get("data") or []
        songs = [norm(s) for s in raw if isinstance(s, dict) and s.get("id")]
    if songs:
        log.info(f"Trending {lang}: {len(songs)} songs from trending feed")
        return jsonify({"songs": songs[:40], "title": "Trending Now"})
    # Strategy 2: search fallback skewed to fresh/popular
    q  = CHART_FALLBACK.get(lang, "trending songs")
    d2 = saavn({"__call":"search.getResults","n":"30","p":"1","q":q})
    songs = [norm(s) for s in d2.get("results",[]) if s.get("type")=="song"]
    log.info(f"Trending {lang}: {len(songs)} songs from search fallback")
    return jsonify({"songs": songs, "title": "Trending Now"})

# ── New releases ───────────────────────────────────────────────────────────
@app.route("/api/new-releases")
def new_releases():
    lang = request.args.get("lang","hindi")
    log.info(f"New releases lang={lang}")
    d = saavn({"__call":"content.getAlbums","n":"20","p":"1","language":lang})
    albums = [{"id":a.get("albumid",""),"title":a.get("title",""),
               "artist":a.get("music",""),"image":hi_res(a.get("image","")),
               "year":a.get("year",""),"language":a.get("language","")}
              for a in d.get("data",[])]
    return jsonify({"albums":albums})

# ── Featured playlists (curated shelves) ───────────────────────────────────
@app.route("/api/featured")
def featured():
    lang = request.args.get("lang","hindi")
    log.info(f"Featured playlists lang={lang}")
    d = saavn({"__call":"content.getFeaturedPlaylists","n":"20","p":"1","language":lang})
    out = []
    # Response shape varies; handle both list and trending-wrapped forms
    items = []
    if isinstance(d, dict):
        items = (d.get("data") or
                 d.get("data",{}).get("trending",{}).get("value",[]) if isinstance(d.get("data"),dict) else
                 [])
        if not items and isinstance(d.get("data"), list):
            items = d["data"]
    for p in items:
        if not isinstance(p, dict): continue
        out.append({"id":p.get("listid") or p.get("id",""),
                    "title":p.get("listname") or p.get("title",""),
                    "image":hi_res(p.get("image","")),
                    "count":p.get("list_count") or p.get("listcount","")})
    return jsonify({"playlists": out})

# ── Movie / album shelf (latest film music) ────────────────────────────────
# Curated "new movie songs" via the trending-albums feed filtered to recent
@app.route("/api/movies")
def movies():
    lang = request.args.get("lang","hindi")
    log.info(f"Movie songs lang={lang}")
    # content.getAlbums returns film albums newest-first for most languages
    d = saavn({"__call":"content.getAlbums","n":"25","p":"1","language":lang})
    albums = [{"id":a.get("albumid",""),"title":a.get("title",""),
               "artist":a.get("music",""),"image":hi_res(a.get("image","")),
               "year":a.get("year",""),"language":a.get("language","")}
              for a in d.get("data",[]) if a.get("albumid")]
    return jsonify({"albums":albums})

# ── Unified home shelves — one call powers the whole landing page ──────────
@app.route("/api/home/<lang>")
def home_shelves(lang):
    """
    Returns multiple shelves in one response so the landing page loads fast:
      - trending    (top chart songs)
      - new_albums  (latest releases)
      - playlists   (curated featured playlists)
    Library shelves (recents, liked, your playlists) are client-side.
    """
    lang = lang.lower()
    log.info(f"Home shelves lang={lang}")
    result = {"trending": [], "new_albums": [], "playlists": []}

    # Trending songs (reuse token charts logic)
    token = CHART_TOKENS.get(lang)
    if token:
        d = saavn({"__call":"webapi.get","token":token,"type":"playlist","p":"1","n":"40"}, timeout=15)
        if "_error" not in d:
            result["trending"] = _songs_from_playlist(d)
    if not result["trending"]:
        q  = CHART_FALLBACK.get(lang,"trending songs")
        d2 = saavn({"__call":"search.getResults","n":"30","p":"1","q":q})
        result["trending"] = [norm(s) for s in d2.get("results",[]) if s.get("type")=="song"]

    # New albums
    da = saavn({"__call":"content.getAlbums","n":"20","p":"1","language":lang})
    result["new_albums"] = _albums_from_response(da)

    # Featured playlists
    dp = saavn({"__call":"content.getFeaturedPlaylists","n":"15","p":"1","language":lang})
    pls = []
    if isinstance(dp, dict):
        raw = dp.get("data")
        if isinstance(raw, list):
            pls = raw
        elif isinstance(raw, dict):
            pls = raw.get("trending",{}).get("value",[]) or []
    result["playlists"] = [{"id":p.get("listid") or p.get("id",""),
                            "title":p.get("listname") or p.get("title",""),
                            "image":hi_res(p.get("image",""))}
                           for p in pls if isinstance(p,dict)][:12]

    log.info(f"Home {lang}: {len(result['trending'])} trending, "
             f"{len(result['new_albums'])} albums, {len(result['playlists'])} playlists")
    return jsonify(result)

# ── Song detail (with stream URL) ──────────────────────────────────────────
@app.route("/api/song/<song_id>")
def song_detail(song_id):
    log.info(f"Song detail: {song_id}")
    d = saavn({"__call":"song.getDetails","pids":song_id})
    if "_error" in d:
        return jsonify({"error":d["_error"]}), 500

    # Locate the song object that ACTUALLY matches the requested id.
    # JioSaavn responses vary: sometimes {id: {...}}, sometimes {"songs":[...]},
    # sometimes {id: [ {...} ]}. We must never blindly grab values()[0] — that
    # was returning an unrelated cached song (e.g. requesting a Peddi track
    # returned 'Havana'). Always verify the id matches.
    candidates = []
    val = d.get(song_id)
    if val is not None:
        candidates = val if isinstance(val, list) else [val]
    elif isinstance(d.get("songs"), list):
        candidates = d["songs"]
    else:
        # last resort: scan all values for a dict whose id matches
        for v in d.values():
            items = v if isinstance(v, list) else [v]
            for it in items:
                if isinstance(it, dict) and it.get("id") == song_id:
                    candidates = [it]
                    break
            if candidates:
                break

    raw = None
    for c in candidates:
        if isinstance(c, dict) and c.get("id") == song_id:
            raw = c
            break
    # If still nothing matched by id but we have exactly one candidate, use it
    if raw is None and len(candidates) == 1 and isinstance(candidates[0], dict):
        raw = candidates[0]

    if not raw:
        log.error(f"song_detail: no matching object for id={song_id} (keys={list(d.keys())[:5]})")
        return jsonify({"error":"not found"}), 404

    if raw.get("id") != song_id:
        log.warning(f"song_detail: id mismatch — requested {song_id}, got {raw.get('id')} ({raw.get('title')!r})")

    result = norm(raw)
    if not result.get("url"):
        log.error(f"No stream URL for song {song_id} — all strategies exhausted")
        return jsonify({"error":"no_stream_url","song":result}), 200
    log.info(f"Stream resolved: id={song_id} title={result['title']!r} url={result['url'][:50]}…")
    return jsonify(result)

# ── Album ──────────────────────────────────────────────────────────────────
@app.route("/api/album/<album_id>")
def album(album_id):
    log.info(f"Album: {album_id}")
    d = saavn({"__call":"content.getAlbumDetails","albumid":album_id})
    if "_error" in d: return jsonify({"error":d["_error"]}), 500
    songs = [norm(s) for s in d.get("list",[]) if isinstance(s,dict)]
    # Diagnostic: log the actual tracklist (id + title) so we can confirm
    # the album returns its own songs and not unrelated ones.
    tracklist = ", ".join(f"{s['id']}={s['title']!r}" for s in songs[:8])
    log.info(f"Album {d.get('title',album_id)!r}: {len(songs)} songs → {tracklist}")
    return jsonify({"id":d.get("albumid",album_id),"title":d.get("title",""),
                    "artist":d.get("primary_artists",""),"image":hi_res(d.get("image","")),
                    "year":d.get("year",""),"language":d.get("language",""),"songs":songs})

# ── Artist ─────────────────────────────────────────────────────────────────
@app.route("/api/artist/<artist_id>")
def artist(artist_id):
    log.info(f"Artist: {artist_id}")
    d = saavn({"__call":"artist.getArtistPageDetails","artistId":artist_id,
               "n_song":"20","n_album":"10","page":"0"})
    if "_error" in d: return jsonify({"error":d["_error"]}), 500
    top  = d.get("topSongs",{}).get("songs",[]) or []
    albs = [{"id":a.get("albumid",""),"title":a.get("title",""),
             "image":hi_res(a.get("image","")),"year":a.get("year","")}
            for a in (d.get("topAlbums",{}).get("albums",[]) or [])]
    return jsonify({"id":d.get("artistId",artist_id),"name":d.get("name",""),
                    "image":hi_res(d.get("image","")),"songs":[norm(s) for s in top if isinstance(s,dict)],
                    "albums":albs})

# ── Playlist ───────────────────────────────────────────────────────────────
@app.route("/api/playlist/<playlist_id>")
def playlist(playlist_id):
    log.info(f"Playlist: {playlist_id}")
    d = saavn({"__call":"playlist.getDetails","listid":playlist_id})
    if "_error" in d: return jsonify({"error":d["_error"]}), 500
    songs = [norm(s) for s in d.get("list",[]) if isinstance(s,dict)]
    return jsonify({"id":d.get("listid",playlist_id),"title":d.get("listname","") or d.get("title",""),
                    "image":hi_res(d.get("image","")),"count":d.get("listcount",len(songs)),"songs":songs})

# ── Lyrics ─────────────────────────────────────────────────────────────────
def _lrclib_lyrics(title, artist, album="", duration=0):
    """
    Fetch lyrics from LRCLIB — free, open, no auth.
    Same source SimpMusic uses. Returns synced (LRC) if available, else plain.
    """
    try:
        params = {"track_name": title, "artist_name": artist}
        if album:    params["album_name"] = album
        if duration: params["duration"]   = duration
        r = requests.get("https://lrclib.net/api/get", params=params,
                         headers={"User-Agent": "Naada/1.0 (personal music player)"},
                         timeout=8)
        if r.status_code == 200:
            j = r.json()
            synced = j.get("syncedLyrics") or ""
            plain  = j.get("plainLyrics")  or ""
            return synced, plain
    except Exception as e:
        log.debug(f"LRCLIB lookup failed: {e}")
    return "", ""

@app.route("/api/lyrics/<lyrics_id>")
def lyrics(lyrics_id):
    log.info(f"Lyrics: {lyrics_id}")
    # Optional metadata for LRCLIB fallback, passed by the frontend
    title    = request.args.get("title", "")
    artist   = request.args.get("artist", "")
    album    = request.args.get("album", "")
    duration = request.args.get("duration", "")

    # Strategy 1: JioSaavn native lyrics (plain text, Hindi film songs mostly)
    d = saavn({"__call":"lyrics.getLyrics","lyrics_id":lyrics_id})
    raw = d.get("lyrics", "") if isinstance(d, dict) else ""
    if raw:
        clean = re.sub(r"<br\s*/?>", "\n", raw, flags=re.I)
        clean = re.sub(r"<[^>]+>", "", clean).strip()
        if clean:
            log.info(f"Lyrics from JioSaavn for {lyrics_id}")
            return jsonify({"lyrics": clean, "synced": "", "source": "jiosaavn",
                            "copyright": d.get("lyrics_copyright", "")})

    # Strategy 2: LRCLIB (synced + plain), needs title/artist
    if title and artist:
        try:
            dur = int(duration) if duration else 0
        except ValueError:
            dur = 0
        synced, plain = _lrclib_lyrics(title, artist, album, dur)
        if synced or plain:
            log.info(f"Lyrics from LRCLIB for {title!r} (synced={bool(synced)})")
            return jsonify({"lyrics": plain, "synced": synced,
                            "source": "lrclib", "copyright": ""})

    log.info(f"No lyrics found for {lyrics_id} ({title!r})")
    return jsonify({"lyrics": "", "synced": "", "source": "", "copyright": ""})

# ── Radio ──────────────────────────────────────────────────────────────────
@app.route("/api/radio")
def radio():
    sid  = request.args.get("song_id","")
    aid  = request.args.get("artist_id","")
    lang = request.args.get("lang","hindi")
    if sid:
        d = saavn({"__call":"webradio.createEntityStation","entity_id":sid,
                   "entity_type":"queue","freemium_payload":"1","language":lang})
    elif aid:
        d = saavn({"__call":"webradio.createArtistStation","artist_id":aid,
                   "freemium_payload":"1","language":lang})
    else:
        return jsonify({"songs":[]})
    if not d.get("stationid"): return jsonify({"songs":[]})
    d2   = saavn({"__call":"webradio.getSong","stationid":d["stationid"],"k":"20","next":"1"})
    songs = [norm(item["song"]) for item in (d2 if isinstance(d2,list) else [])
             if isinstance(item,dict) and item.get("song")]
    return jsonify({"songs":songs})

# ── AI features (Gemini) ─────────────────────────────────────
# Free-tier Gemini Flash. Key stays server-side (config.json / env), never
# reaches the browser. Responses cached on disk per song/album so repeats are
# free even across restarts. A client-side debounce plus this server-side
# rate limiter keep us comfortably under the free-tier limits.
GEMINI_MODELS = ["gemini-2.5-flash", "gemini-2.0-flash", "gemini-flash-latest"]
AI_CACHE_FILE = os.path.join(BASE_DIR, "ai_cache.json")

# Rate limiting (server-side safety net; the client also debounces)
AI_MAX_PER_MIN = 12          # hard ceiling of Gemini calls per rolling minute
_ai_call_times = []          # timestamps of recent calls
_ai_cooldown_until = 0.0     # set when Gemini returns 429; pause until then

def _load_ai_cache():
    try:
        with open(AI_CACHE_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}

_ai_cache = _load_ai_cache()

def _save_ai_cache():
    try:
        # Cap size so the file can't grow unbounded
        if len(_ai_cache) > 500:
            for k in list(_ai_cache.keys())[:len(_ai_cache) - 500]:
                _ai_cache.pop(k, None)
        with open(AI_CACHE_FILE, "w") as f:
            json.dump(_ai_cache, f)
    except Exception as e:
        log.warning(f"Could not save AI cache: {e}")

def _ai_rate_ok():
    """Return (allowed, reason). Enforces cooldown + per-minute ceiling."""
    now = time.time()
    if now < _ai_cooldown_until:
        return False, "cooldown"
    cutoff = now - 60
    while _ai_call_times and _ai_call_times[0] < cutoff:
        _ai_call_times.pop(0)
    if len(_ai_call_times) >= AI_MAX_PER_MIN:
        return False, "rate_limited"
    return True, ""

def _parse_retry_delay(body_text):
    """Pull the suggested retry delay (seconds) from a Gemini 429 body, if any.
    Gemini returns a RetryInfo like {"retryDelay": "37s"}. Cap at 5 min."""
    try:
        m = re.search(r'"retryDelay"\s*:\s*"(\d+)s"', body_text)
        if m:
            return min(int(m.group(1)) + 2, 300)   # small buffer, capped
    except Exception:
        pass
    return 0

def gemini_generate(prompt, max_tokens=800, temperature=0.8):
    global _ai_cooldown_until
    key = CONFIG.get("gemini_api_key", "")
    if not key:
        return ""
    allowed, reason = _ai_rate_ok()
    if not allowed:
        log.info(f"AI call skipped ({reason})")
        return ""
    _ai_call_times.append(time.time())
    gen_cfg = {
        "temperature": temperature,
        "maxOutputTokens": max_tokens,
        # Disable internal "thinking" so all tokens go to the visible answer.
        # 2.5-flash otherwise spends the budget thinking and returns empty text,
        # which was silently breaking trivia. Ignored by models without it.
        "thinkingConfig": {"thinkingBudget": 0},
    }
    last_err = ""
    for model in GEMINI_MODELS:
        url = ("https://generativelanguage.googleapis.com/v1beta/models/"
               f"{model}:generateContent")
        for attempt in range(2):
            try:
                r = requests.post(url, params={"key": key},
                    json={"contents": [{"parts": [{"text": prompt}]}],
                          "generationConfig": gen_cfg},
                    timeout=25)
                if r.status_code == 200:
                    data = r.json()
                    cand = (data.get("candidates") or [{}])[0]
                    parts = cand.get("content", {}).get("parts", [])
                    text = "".join(p.get("text", "") for p in parts).strip()
                    if text:
                        return text
                    fr = cand.get("finishReason", "?")
                    last_err = f"{model}: empty (finishReason={fr})"
                    log.warning(f"Gemini {last_err}")
                    break
                elif r.status_code == 429:
                    # Rate limited upstream. Gemini's body often includes a
                    # RetryInfo with the real delay — honor it when present,
                    # otherwise back off 60s. Either way, STOP immediately:
                    # all models share the same quota, so retrying or trying
                    # other models just wastes calls and worsens the limit.
                    delay = _parse_retry_delay(r.text) or 60
                    _ai_cooldown_until = time.time() + delay
                    log.warning(f"Gemini {model}: HTTP 429 — cooling down {delay}s")
                    return ""
                elif r.status_code in (500, 503):
                    last_err = f"{model}: HTTP {r.status_code}"
                    log.warning(f"Gemini {last_err}, retry/fallback")
                    time.sleep(0.6)
                    continue
                else:
                    last_err = f"{model}: HTTP {r.status_code} {r.text[:120]}"
                    log.warning(f"Gemini {last_err}")
                    break
            except Exception as e:
                last_err = f"{model}: {e}"
                log.warning(f"Gemini call failed: {last_err}")
                time.sleep(0.4)
                continue
    log.warning(f"Gemini: all models failed ({last_err})")
    return ""

@app.route("/api/ai/status")
def ai_status():
    return jsonify({"enabled": bool(CONFIG.get("gemini_api_key"))})

@app.route("/api/ai/anecdote")
def ai_anecdote():
    title  = request.args.get("title", "").strip()
    artist = request.args.get("artist", "").strip()
    album  = request.args.get("album", "").strip()
    if not title:
        return jsonify({"error": "title required"}), 400
    if not CONFIG.get("gemini_api_key"):
        return jsonify({"error": "ai_disabled"}), 200
    cache_key = f"anecdote:{title}|{album}|{artist}".lower()
    if cache_key in _ai_cache:
        return jsonify({"text": _ai_cache[cache_key], "cached": True})
    prompt = (
        f'Give me one short, genuinely interesting anecdote or piece of trivia '
        f'about the Indian song "{title}"'
        + (f' from the movie/album "{album}"' if album else "")
        + (f' by {artist}' if artist else "")
        + ". Focus on the film, its making, the composer, or cultural impact. "
        "Keep it to 2-3 sentences, conversational and fun. "
        "If you are not certain about specific facts, share general context about "
        "the film or artist instead of inventing details. Do not use markdown.")
    text = gemini_generate(prompt, max_tokens=300)
    if not text:
        # Tell the client whether we're cooling down vs just no result,
        # so the UI can show an appropriate, calm message.
        if time.time() < _ai_cooldown_until:
            return jsonify({"error": "cooldown"}), 200
        return jsonify({"error": "no_result"}), 200
    _ai_cache[cache_key] = text
    _save_ai_cache()
    log.info(f"AI anecdote generated for {title!r}")
    return jsonify({"text": text, "cached": False})

@app.route("/api/ai/trivia", methods=["POST"])
def ai_trivia():
    if not CONFIG.get("gemini_api_key"):
        return jsonify({"error": "ai_disabled", "cards": []}), 200
    body  = request.get_json(silent=True) or {}
    songs = body.get("songs", [])[:8]
    if not songs:
        return jsonify({"cards": []})
    ids = ",".join(sorted(s.get("id", "") for s in songs if isinstance(s, dict)))
    cache_key = f"trivia:{ids}"
    if cache_key in _ai_cache:
        return jsonify({"cards": _ai_cache[cache_key], "cached": True})
    song_lines = "\n".join(
        f'- "{s.get("title","")}"'
        + (f' from {s.get("album","")}' if s.get("album") else "")
        + (f' by {s.get("artist","")}' if s.get("artist") else "")
        for s in songs if isinstance(s, dict))
    prompt = (
        "Here are songs a user has been listening to:\n" + song_lines + "\n\n"
        "Create 4 fun trivia question-and-answer cards based on these songs, "
        "their movies, composers, or artists. Each answer 1-2 sentences. "
        "If unsure of a specific fact, make the question about general context "
        "you are confident about rather than inventing details. "
        'Respond ONLY with a JSON array, no markdown, in this exact form: '
        '[{"question":"...","answer":"...","song":"<which song>"}]')
    text = gemini_generate(prompt, max_tokens=1200, temperature=0.9)
    if not text:
        log.warning("Trivia: gemini returned empty")
        return jsonify({"cards": []}), 200

    cards = _parse_trivia_json(text)
    cards = [c for c in cards if isinstance(c, dict) and c.get("question") and c.get("answer")]
    if cards:
        _ai_cache[cache_key] = cards
        _save_ai_cache()
        log.info(f"AI trivia generated: {len(cards)} cards")
    else:
        log.warning(f"Trivia: parsed 0 cards from {len(text)}-char response: {text[:120]!r}")
    return jsonify({"cards": cards, "cached": False})

def _parse_trivia_json(text):
    """Extract trivia cards from a Gemini response in any reasonable shape."""
    cleaned = text.strip()
    # Strip markdown fences if present
    if "```" in cleaned:
        cleaned = re.sub(r"```(?:json)?", "", cleaned).replace("```", "").strip()
    # Try 1: a JSON array anywhere in the text
    start, end = cleaned.find("["), cleaned.rfind("]")
    if start != -1 and end != -1 and end > start:
        try:
            arr = json.loads(cleaned[start:end+1])
            if isinstance(arr, list):
                return arr
        except Exception:
            pass
    # Try 2: a JSON object that wraps the array (e.g. {"cards":[...]})
    s2, e2 = cleaned.find("{"), cleaned.rfind("}")
    if s2 != -1 and e2 != -1 and e2 > s2:
        try:
            obj = json.loads(cleaned[s2:e2+1])
            if isinstance(obj, dict):
                for v in obj.values():
                    if isinstance(v, list):
                        return v
                if obj.get("question"):   # single card object
                    return [obj]
        except Exception:
            pass
    # Try 3: salvage individual {...} card objects via regex
    cards = []
    for m in re.finditer(r"\{[^{}]*\"question\"[^{}]*\}", cleaned, re.DOTALL):
        try:
            cards.append(json.loads(m.group()))
        except Exception:
            continue
    return cards


@app.route("/api/ai/mood", methods=["POST"])
def ai_mood():
    """
    Build a playlist from a free-text mood/vibe prompt.
    Body: { "prompt": "rainy evening telugu melodies", "lang": "telugu" }
    Strategy: Gemini suggests search queries that fit the vibe; we run each
    against JioSaavn and return real, playable songs + an AI title/description.
    """
    if not CONFIG.get("gemini_api_key"):
        return jsonify({"error": "ai_disabled", "songs": []}), 200
    body   = request.get_json(silent=True) or {}
    prompt = (body.get("prompt") or "").strip()
    lang   = (body.get("lang") or "").strip()
    if not prompt:
        return jsonify({"error": "prompt required", "songs": []}), 400

    cache_key = f"mood:{prompt}|{lang}".lower()
    if cache_key in _ai_cache:
        cached = _ai_cache[cache_key]
        # Cached entry stores the query plan + meta; re-run searches live so
        # results stay fresh, but skip the Gemini call.
        plan = cached
    else:
        ask = (
            f'A user wants a music playlist for this vibe: "{prompt}". '
            + (f'Preferred language: {lang}. ' if lang else "")
            + "Suggest 6 to 8 specific search queries for an Indian music service "
            "(song names, artists, or movie names) that would fit this mood. "
            "Also give the playlist a short evocative title and a one-line "
            "description. Respond ONLY with JSON, no markdown, as: "
            '{"title":"...","description":"...","queries":["...","..."]}'
        )
        raw = gemini_generate(ask, max_tokens=500, temperature=0.9)
        if not raw:
            return jsonify({"error": "no_result", "songs": []}), 200
        plan = _parse_json_object(raw)
        if not plan or not plan.get("queries"):
            return jsonify({"error": "no_result", "songs": []}), 200
        _ai_cache[cache_key] = plan
        _save_ai_cache()
        log.info(f"AI mood plan for {prompt!r}: {len(plan.get('queries',[]))} queries")

    # Run the searches and collect unique songs
    seen, songs = set(), []
    for q in plan.get("queries", [])[:8]:
        if not isinstance(q, str) or not q.strip():
            continue
        d = saavn({"__call": "search.getResults", "n": "4", "p": "1", "q": q.strip()})
        for s in d.get("results", []):
            if s.get("type") == "song":
                song = norm(s)
                if song.get("id") and song["id"] not in seen:
                    seen.add(song["id"])
                    songs.append(song)
        if len(songs) >= 25:
            break

    return jsonify({
        "title": plan.get("title", prompt.title()),
        "description": plan.get("description", ""),
        "songs": songs,
    })

def _parse_json_object(text):
    """Extract a single JSON object from a model response."""
    cleaned = text.strip()
    if "```" in cleaned:
        cleaned = re.sub(r"```(?:json)?", "", cleaned).replace("```", "").strip()
    s, e = cleaned.find("{"), cleaned.rfind("}")
    if s != -1 and e != -1 and e > s:
        try:
            return json.loads(cleaned[s:e+1])
        except Exception:
            return None
    return None

# ── Health / diagnostics ────────────────────────────────────
@app.route("/api/health")
def health():
    pid  = os.getpid()
    up   = time.time() - _start_time
    return jsonify({"status":"ok","app":"naada","pid":pid,
                    "uptime_seconds":round(up),"log":LOG_FILE})

@app.route("/api/debug/decrypt")
def debug_decrypt():
    """Test decrypt of a given encrypted_media_url — dev helper."""
    enc = request.args.get("enc","")
    if not enc: return jsonify({"error":"pass ?enc=..."}), 400
    return jsonify({"input":enc[:60],"result":decrypt_url(enc)})

_start_time = time.time()

# ── Request logging middleware ─────────────────────────────────────────────
@app.before_request
def log_request():
    if not request.path.startswith("/static") and request.path != "/sw.js":
        log.debug(f"→ {request.method} {request.path} {dict(request.args)}")

@app.after_request
def log_response(resp):
    if not request.path.startswith("/static") and request.path != "/sw.js":
        log.info(f"← {request.method} {request.path} {resp.status_code}")
    return resp

@app.errorhandler(Exception)
def handle_exception(e):
    # Let normal HTTP errors (404 route-miss, 405, etc.) pass through unchanged
    from werkzeug.exceptions import HTTPException
    if isinstance(e, HTTPException):
        return e
    log.exception(f"Unhandled exception on {request.path}: {e}")
    return jsonify({"error":"internal_server_error","detail":str(e)}), 500

# ═══════════════════════════════════════════════════════════════════════════
# Daemon management
# ═══════════════════════════════════════════════════════════════════════════

def daemonize():
    """Double-fork to create a proper Unix daemon."""
    # First fork
    try:
        pid = os.fork()
        if pid > 0:
            sys.exit(0)  # parent exits
    except OSError as e:
        print(f"Fork #1 failed: {e}", file=sys.stderr)
        sys.exit(1)

    os.setsid()
    os.umask(0)

    # Second fork
    try:
        pid = os.fork()
        if pid > 0:
            sys.exit(0)
    except OSError as e:
        print(f"Fork #2 failed: {e}", file=sys.stderr)
        sys.exit(1)

    # Redirect stdio to /dev/null
    sys.stdout.flush()
    sys.stderr.flush()
    with open("/dev/null") as f:
        os.dup2(f.fileno(), sys.stdin.fileno())
    with open(LOG_FILE, "a") as f:
        os.dup2(f.fileno(), sys.stdout.fileno())
        os.dup2(f.fileno(), sys.stderr.fileno())

def write_pid():
    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))

def read_pid():
    try:
        with open(PID_FILE) as f:
            return int(f.read().strip())
    except (FileNotFoundError, ValueError):
        return None

def stop_daemon():
    pid = read_pid()
    if not pid:
        print("Naada is not running (no PID file).")
        return
    try:
        os.kill(pid, signal.SIGTERM)
        # Wait up to 5s for it to die
        for _ in range(50):
            time.sleep(0.1)
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
        try:
            os.remove(PID_FILE)
        except FileNotFoundError:
            pass
        print(f"Naada stopped (pid {pid}).")
    except ProcessLookupError:
        print(f"Process {pid} not found — removing stale PID file.")
        try: os.remove(PID_FILE)
        except FileNotFoundError: pass
    except PermissionError:
        print(f"Permission denied to kill pid {pid}.")

def status_daemon():
    pid = read_pid()
    if not pid:
        print("Naada is NOT running.")
        return
    try:
        os.kill(pid, 0)
        print(f"Naada is running (pid {pid}).")
        use_ssl = os.path.exists(SSL_CERT)
        port    = 5443 if use_ssl else 5000
        proto   = "https" if use_ssl else "http"
        print(f"  URL:  {proto}://localhost:{port}")
        print(f"  Logs: {LOG_FILE}")
    except ProcessLookupError:
        print(f"Naada is NOT running (stale PID {pid}).")
        try: os.remove(PID_FILE)
        except FileNotFoundError: pass

# ═══════════════════════════════════════════════════════════════════════════
# Entry point
# ═══════════════════════════════════════════════════════════════════════════

def run_server(daemon_mode=False):
    setup_logging(to_file=daemon_mode)

    # ── Deployment configuration (env-overridable) ──────────────────────────
    # On a dedicated server behind nginx/Cloudflare, set NAADA_HTTP_ONLY=1 so
    # the app serves plain HTTP on 127.0.0.1:5000 and lets nginx terminate TLS.
    # On Termux (no env set), it keeps the original auto-HTTPS behavior.
    http_only = os.environ.get("NAADA_HTTP_ONLY", "").lower() in ("1", "true", "yes")
    host      = os.environ.get("NAADA_HOST", "127.0.0.1" if http_only else "0.0.0.0")

    if http_only:
        use_ssl, ssl_ctx, proto = False, None, "http"
        port = int(os.environ.get("NAADA_PORT", "5000"))
    else:
        use_ssl = os.path.exists(SSL_CERT) and os.path.exists(SSL_KEY)
        proto   = "https" if use_ssl else "http"
        port    = int(os.environ.get("NAADA_PORT", "5443" if use_ssl else "5000"))
        ssl_ctx = (SSL_CERT, SSL_KEY) if use_ssl else None

    log.info("=" * 60)
    log.info("Naada (నాద) starting up")
    log.info(f"  Mode:  {'daemon' if daemon_mode else 'foreground'}")
    log.info(f"  Bind:  {host}:{port}")
    log.info(f"  URL:   {proto}://localhost:{port}")
    log.info(f"  HTTPS: {use_ssl}")
    log.info(f"  PID:   {os.getpid()}")
    log.info(f"  Logs:  {LOG_FILE}")
    log.info("=" * 60)

    if daemon_mode:
        write_pid()
        log.info(f"PID file written: {PID_FILE}")

    # Warm up session cookies
    refresh_session()

    import werkzeug.serving
    app.run(
        host=host,
        port=port,
        debug=False,
        use_reloader=False,
        ssl_context=ssl_ctx,
    )

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""

    if cmd == "--stop":
        stop_daemon()
    elif cmd == "--status":
        status_daemon()
    elif cmd == "--daemon":
        existing = read_pid()
        if existing:
            try:
                os.kill(existing, 0)
                print(f"Naada already running (pid {existing}). Use --stop first.")
                sys.exit(1)
            except ProcessLookupError:
                pass  # stale PID, proceed
        print(f"Starting Naada in background…")
        print(f"Logs: {LOG_FILE}")
        daemonize()
        run_server(daemon_mode=True)
    elif cmd in ("", "--fg", "--foreground"):
        run_server(daemon_mode=False)
    else:
        print(f"Usage: python app.py [--daemon | --stop | --status | --fg]")
        sys.exit(1)
