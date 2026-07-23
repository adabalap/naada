# ─────────────────────────────────────────────────────────────────────────
# Naada · nginx site config
#
# Install:
#   sudo cp /opt/naada/deploy/naada.adabala.com /etc/nginx/sites-available/naada.adabala.com
#   sudo ln -s /etc/nginx/sites-available/naada.adabala.com /etc/nginx/sites-enabled/
#   sudo nginx -t && sudo systemctl reload nginx
#
# Uses the SAME wildcard/base cert you already have for adabala.com — no new
# certs to provision. Adjust server_name to the subdomain you want.
# ─────────────────────────────────────────────────────────────────────────

server {
    # HTTP → HTTPS redirect
    listen 80;
    listen [::]:80;
    server_name naada.adabala.com;
    return 301 https://$host$request_uri;
}

server {
    # HTTPS reverse proxy
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name naada.adabala.com;

    # Reuse the existing adabala.com certificate (same as your other sites)
    ssl_certificate     /etc/nginx/ssl/adabala.com/adabala.com.pem;
    ssl_certificate_key /etc/nginx/ssl/adabala.com/adabala.com.key;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384';
    ssl_prefer_server_ciphers on;

    access_log /var/log/nginx/naada.adabala.com.access.log;
    error_log  /var/log/nginx/naada.adabala.com.error.log;

    # Audio streams are large; allow generous bodies/timeouts.
    client_max_body_size 10m;

    # ── Service worker: never cache at the edge, so updates land immediately ──
    location = /sw.js {
        proxy_pass http://127.0.0.1:5334;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        add_header Cache-Control "no-cache, no-store, must-revalidate" always;
    }

    location / {
        proxy_pass http://127.0.0.1:5334;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Longer timeouts for slow upstream calls (Gemini, JioSaavn, lyrics)
        proxy_read_timeout    120;
        proxy_connect_timeout 120;
    }
}
