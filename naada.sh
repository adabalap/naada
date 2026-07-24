#!/data/data/com.termux/files/usr/bin/bash
# ═══════════════════════════════════════════════════════
#  Naada (నాద) — startup script
#  Usage:
#    ./naada.sh start    — start in background (daemon)
#    ./naada.sh stop     — stop daemon
#    ./naada.sh restart  — restart daemon
#    ./naada.sh status   — check if running
#    ./naada.sh logs     — tail live logs
#    ./naada.sh fg       — run in foreground (debug)
# ═══════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$SCRIPT_DIR/app.py"
LOGFILE="$SCRIPT_DIR/logs/naada.log"
PIDFILE="$SCRIPT_DIR/naada.pid"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

banner() {
  echo -e "${CYAN}"
  echo "  ███╗   ██╗ █████╗  █████╗ ██████╗  █████╗ "
  echo "  ████╗  ██║██╔══██╗██╔══██╗██╔══██╗██╔══██╗"
  echo "  ██╔██╗ ██║███████║███████║██║  ██║███████║"
  echo "  ██║╚██╗██║██╔══██║██╔══██║██║  ██║██╔══██║"
  echo "  ██║ ╚████║██║  ██║██║  ██║██████╔╝██║  ██║"
  echo "  ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═╝╚═════╝ ╚═╝  ╚═╝"
  echo -e "  ${YELLOW}నాద · Personal Indian Music Player${NC}"
  echo ""
}

cmd_start() {
  banner
  # Check if already running
  if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE")
    if kill -0 "$PID" 2>/dev/null; then
      echo -e "${YELLOW}Naada is already running (pid $PID)${NC}"
      echo "  Use './naada.sh restart' to restart."
      exit 0
    else
      echo -e "${YELLOW}Stale PID file found, cleaning up…${NC}"
      rm -f "$PIDFILE"
    fi
  fi

  mkdir -p "$SCRIPT_DIR/logs"
  echo -e "${GREEN}Starting Naada in background…${NC}"
  python "$APP" --daemon

  # Wait for PID file to appear
  for i in $(seq 1 20); do
    sleep 0.3
    if [ -f "$PIDFILE" ]; then
      PID=$(cat "$PIDFILE")
      echo -e "${GREEN}✓ Naada started (pid $PID)${NC}"
      # Detect SSL
      if [ -f "$HOME/.sms-agent/cert.pem" ]; then
        echo -e "  URL:  ${CYAN}https://localhost:5443${NC}"
      else
        echo -e "  URL:  ${CYAN}http://localhost:5000${NC}"
      fi
      echo -e "  Logs: $LOGFILE"
      echo ""
      echo -e "${YELLOW}Tip: Keep Termux running in the background${NC}"
      echo "  (Acquire wake lock: termux-wake-lock)"
      exit 0
    fi
  done

  echo -e "${RED}✗ Naada did not start in time. Check logs:${NC}"
  echo "  $LOGFILE"
  exit 1
}

cmd_stop() {
  python "$APP" --stop
}

cmd_restart() {
  cmd_stop
  sleep 1
  cmd_start
}

cmd_status() {
  python "$APP" --status
}

cmd_logs() {
  if [ ! -f "$LOGFILE" ]; then
    echo "No log file yet at $LOGFILE"
    exit 1
  fi
  echo -e "${CYAN}Tailing $LOGFILE (Ctrl+C to stop)${NC}"
  tail -f "$LOGFILE"
}

cmd_fg() {
  banner
  echo -e "${YELLOW}Running in foreground (Ctrl+C to stop)${NC}"
  python "$APP" --fg
}

case "${1:-start}" in
  start)   cmd_start ;;
  # Plain HTTP on localhost. Counter-intuitive but correct: Chrome trusts
  # http://localhost automatically and will offer to INSTALL the app, whereas
  # https:// with a self-signed cert you clicked past is treated as having a
  # certificate error and cannot be installed at all. Installing is what makes
  # background playback and the Naada media-notification icon work.
  start-http)
    export NAADA_HTTP_ONLY=1
    cmd_start
    ;;
  # Behind Cloudflare (tunnel or proxy). Cloudflare terminates TLS with a
  # real certificate, so this app must serve plain HTTP and must NOT try to
  # do its own TLS -- two TLS layers is how you get handshake errors.
  # ProxyFix is enabled so X-Forwarded-Proto/For are trusted.
  # Set NAADA_TOKEN first if you don't want the whole internet using it.
  start-cloud)
    export NAADA_HTTP_ONLY=1
    export NAADA_BEHIND_PROXY=1
    cmd_start
    ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  status)  cmd_status ;;
  logs)    cmd_logs ;;
  fg)      cmd_fg ;;
  *)
    echo "Usage: ./naada.sh {start|start-http|start-cloud|stop|restart|status|logs|fg}"
    echo
    echo "  start-cloud  For Cloudflare. Plain HTTP + trusts X-Forwarded-*."
    echo "               Cloudflare provides the real certificate, so the"
    echo "               PWA installs properly and the icon/background"
    echo "               playback both work."
    echo
    echo "  start-http   Serve plain HTTP on http://localhost:5000."
    echo "               Use this on the phone itself — Chrome will then offer"
    echo "               to install Naada as a real app (proper icon, and music"
    echo "               keeps playing when minimised)."
    exit 1
    ;;
esac
