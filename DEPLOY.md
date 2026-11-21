# Naada — Dedicated Server Deployment

Deploying on Ubuntu, behind nginx, with Cloudflare as ingress.
Code lives in `/opt/naada`. Traffic flow:

```
Browser ─► Cloudflare ─► nginx (TLS) ─► gunicorn 127.0.0.1:5000 ─► Naada (Flask)
```

nginx terminates TLS using your **existing adabala.com certificate** — no new
certs needed. The app serves plain HTTP on localhost; it never touches SSL.

---

## 1. One-time setup

```bash
cd /opt/naada

# Virtualenv (if you don't already have .venv)
python3 -m venv .venv
source .venv/bin/activate

# Dependencies (includes gunicorn for production)
pip install -r requirements.txt

# Gemini key — put it in config.json (already present), e.g.:
#   { "gemini_api_key": "AIza..." }
# (or set GEMINI_API_KEY in the systemd unit instead)
```

## 2. Install the systemd service

```bash
sudo cp /opt/naada/deploy/naada.service /etc/systemd/system/naada.service
sudo systemctl daemon-reload
sudo systemctl enable --now naada
sudo systemctl status naada          # should be "active (running)"
```

Quick local check (before nginx):

```bash
curl -s http://127.0.0.1:5000/api/health
```

## 3. Install the nginx site

```bash
sudo cp /opt/naada/deploy/naada.adabala.com /etc/nginx/sites-available/naada.adabala.com
sudo ln -s /etc/nginx/sites-available/naada.adabala.com /etc/nginx/sites-enabled/
sudo nginx -t                        # test config
sudo systemctl reload nginx
```

## 4. DNS / Cloudflare

Add an A/CNAME record for `naada.adabala.com` pointing at this server
(proxied through Cloudflare, same as your other subdomains). With Cloudflare
in front, keep SSL mode **Full (strict)** so it validates your origin cert.

---

## Day-to-day

```bash
sudo systemctl restart naada         # after deploying new code
sudo journalctl -u naada -f          # live logs
tail -f /opt/naada/logs/naada.log    # app's own rotating log
```

### Deploying an update
1. Copy the new files into `/opt/naada` (overwrite app.py / static/ etc.).
2. `sudo systemctl restart naada`
3. In the app: **⚙ → About → Update now** (pulls fresh front-end, keeps data).

---

## Notes & honest caveats

- **`naada.sh` is for Termux, not here.** On the server, systemd manages the
  process. You don't run `./naada.sh start` — `systemctl` does it.

- **Env vars control deployment mode.** `NAADA_HTTP_ONLY=1` (set in the
  service file) makes the app bind plain HTTP to 127.0.0.1:5000 and trust
  nginx's `X-Forwarded-*` headers. Without it, the app falls back to its
  original Termux behavior (auto-HTTPS on 5443). So the *same* app.py works in
  both places — only the environment differs.

- **AI cache & rate-limiter with multiple workers.** gunicorn runs 2 workers;
  the on-disk AI cache (`ai_cache.json`) is shared and works fine, but the
  in-memory per-minute rate-limit counter is per-worker (so the effective
  ceiling is ~2× what's configured). For personal use this is harmless. If you
  ever want a strict shared limit, that needs a shared store (e.g. Redis) —
  not worth it at this scale.

- **config.json holds your Gemini key.** Keep it readable only by the service
  user: `chmod 600 /opt/naada/config.json`.

- **Audio still streams direct from the CDN to the browser** — it does not pass
  through your server. nginx/your bandwidth only carries the small metadata and
  AI calls, so this is very light on the server.

- **Cloudflare caching:** the service worker route (`/sw.js`) is sent with
  no-cache headers by both the app and nginx. If you see stale updates, add a
  Cloudflare Page Rule to bypass cache for `naada.adabala.com/sw.js` and
  `/static/*` during active development.

## Upgrading to v45 — server for the native Android app

v45 adds `api_v1.py` (the API the Android app uses) next to `app.py`. The PWA
keeps working unchanged.

```bash
cd /opt/naada
git fetch origin && git checkout native-app && git pull   # or copy app.py + api_v1.py + static/
sudo systemctl restart naada
curl -s http://127.0.0.1:5334/api/v1/info                 # → {"api_version": 1, ...}
```

- The library (likes, playlists, history) is stored in `/opt/naada/library.db`.
  The `ubuntu` user must be able to write to `/opt/naada` (it already does if
  it owns the folder). Back this file up with the rest of the server.
- **Set a token now.** The API can change your library, so lock it:
  add `Environment=NAADA_TOKEN=<long random string>` to `deploy/naada.service`,
  `sudo systemctl daemon-reload && sudo systemctl restart naada`, then enter the
  same token in the app under Settings, and open the PWA once as
  `https://naada.adabala.com/?k=<token>` so its cookie is set.
- To move your PWA library into the app: PWA → About → **Copy library to the
  Naada app**.

## The Android app

Every push to `android/**` builds a signed APK in GitHub Actions and publishes
it under **Releases** (`android-v2.0.N`). Install it on the phone from the
release page; later builds install over it as updates.

- First install: uninstall the old TWA "Naada" app first (different signing key).
- Builds are signed with `android/app/naada-sideload.jks`, which is committed so
  every CI build can update the last one. Because the repo is public, anyone
  could sign an APK with it — only install Naada from your own Releases page.
  To switch to a private key, add repository secrets `NAADA_KEYSTORE_BASE64`,
  `NAADA_KEYSTORE_PASSWORD`, `NAADA_KEY_ALIAS`, `NAADA_KEY_PASSWORD`
  (switching keys means one uninstall/reinstall).
