"""
Android entry point for the Naada backend.

This is deliberately thin. Everything of substance — all 27 routes, the
JioSaavn response normalisation, the AI endpoints, caching, rate limiting —
lives in naada_app.py, which is the SAME file that runs on the OCI server.

That matters. An earlier version of this project shipped a hand-written
`server.py` for Android with a reimplemented subset of the API. It drifted
immediately: three endpoints (/api/charts, /api/new-releases, /api/playlist)
were missing entirely, and the ones that existed returned raw JioSaavn
payloads instead of the normalised shape the frontend expects. The result
was an app that launched perfectly and then showed "Couldn't load" on every
shelf. Maintaining two backends guarantees that outcome, so there is now
only one.

The only things Android needs to change are environmental:

  * where the web files live — Chaquopy packages the Python sources into a
    ZIP, so paths derived from __file__ are not real directories. The files
    are extracted from Android assets by FlaskServerService and the absolute
    path is passed in here.
  * no daemonising, no PID file, no CLI argument parsing; the Android
    service owns the process lifecycle.
  * logging to stdout so it lands in logcat.
"""

import logging
import os
import sys

# Bump this whenever the bundled backend changes, so the startup banner
# identifies the build without needing to diff files on the device.
BACKEND_BUILD = "v51-native-audio"

log = logging.getLogger("naada.android")


def run_server(static_dir=None, port=5000):
    """Called from Kotlin via Chaquopy. Blocks until the server stops."""

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(name)s %(levelname)s %(message)s",
        stream=sys.stdout,
    )

    # Print a loud, unambiguous banner naming the backend that is actually
    # running. Diagnosing a stale build once cost a full round trip: the
    # device was still running an older hand-written server whose routes did
    # not match the frontend, and nothing in the log said so. Now
    #   adb logcat -s python.stdout | head -5
    # answers "which backend is on this phone?" immediately.
    log.info("=" * 62)
    log.info("NAADA BACKEND  build=%s  (bundled naada_app.py, full route set)",
             BACKEND_BUILD)
    log.info("=" * 62)

    # Import here rather than at module scope so that any failure produces a
    # traceback at a point where FlaskServerService can catch and surface it,
    # instead of dying silently during module load.
    import naada_app

    if static_dir:
        # The backend's routes call send_from_directory("static", ...) with a
        # RELATIVE path, which Flask resolves against the process working
        # directory. On the OCI server that is the repo root, so it works. On
        # Android the working directory is meaningless, which is why "/"
        # returned 404 while "/static/app.js" (served via app.static_folder)
        # worked fine.
        #
        # chdir to the parent of the extracted assets so that the relative
        # name "static" resolves correctly. This keeps naada_app.py byte-for-
        # byte the same file that runs on the server — no Android-specific
        # edits to drift out of sync.
        parent = os.path.dirname(static_dir)

        # Flask resolves a relative send_from_directory() path against
        # app.root_path — NOT the process working directory. root_path is
        # fixed when Flask() is constructed, which under Chaquopy points
        # inside the packaged ZIP. Repointing it at the extracted assets'
        # parent is what makes "static/index.html" resolve. (chdir alone was
        # not enough; that was the reason "/" still 404'd while
        # "/static/app.js" worked, since the latter goes through
        # app.static_folder instead.)
        naada_app.app.root_path = parent
        os.chdir(parent)
        log.info("root_path -> %s", parent)

        naada_app.app.static_folder = static_dir
        if hasattr(naada_app, "STATIC_DIR"):
            naada_app.STATIC_DIR = static_dir
        log.info("static dir -> %s", static_dir)
        try:
            entries = sorted(os.listdir(static_dir))
            log.info("static contains %d entries: %s", len(entries), ", ".join(entries[:8]))
            if "index.html" not in entries:
                log.error("index.html missing from %s — the UI will not load", static_dir)
        except OSError as e:
            log.error("cannot read static dir: %s", e)

    # Writable locations. The APK itself is read-only, so anything the
    # backend wants to write must be redirected into the app's private dir.
    if static_dir:
        writable = os.path.dirname(static_dir)   # same dir we chdir'd into
        for attr, name in (("CACHE_FILE", "ai_cache.json"),
                           ("LOG_FILE", "naada.log"),
                           ("PID_FILE", "naada.pid"),
                           ("CONFIG_FILE", "config.json")):
            if hasattr(naada_app, attr):
                setattr(naada_app, attr, os.path.join(writable, name))
        log.info("writable dir -> %s", writable)

    port = int(port)
    from werkzeug.serving import make_server

    # Loopback only: this server exists for this device. Binding 0.0.0.0
    # would expose the whole API to anyone on the same Wi-Fi.
    srv = make_server("127.0.0.1", port, naada_app.app, threaded=True)
    rules = sorted(str(r) for r in naada_app.app.url_map.iter_rules())
    log.info("Naada backend ready on 127.0.0.1:%d with %d routes", port, len(rules))
    # Dump them all. If the frontend 404s, this line shows immediately
    # whether the route is genuinely absent or the URL is just wrong.
    for r in rules:
        log.info("  route: %s", r)
    srv.serve_forever()
