#!/usr/bin/env bash
set -euo pipefail

# PRANAV VPS Provisioning & Hardening Script (Ubuntu/Debian Linux)
# Must be run as root on the target production VPS.

DOMAIN="${PRANAV_DOMAIN:-api.pranav.example.com}"
APP_DIR="/opt/pranav/backend"
DATA_DIR="/var/lib/pranav"
BACKUP_DIR="/var/backups/pranav"
LOG_DIR="/var/log/pranav"
ENV_DIR="/etc/pranav"

echo "=== [1/8] Inspecting existing server environment ==="
uname -a
ss -tulnp | head -n 20 || true
systemctl status nginx --no-pager || true

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: Please run vps-setup.sh as root (sudo)." >&2
  exit 1
fi

echo "=== [2/8] Installing Node.js 22 LTS, Nginx, UFW, Certbot, sqlite3 ==="
apt-get update -y
apt-get install -y curl ca-certificates gnupg ufw nginx certbot python3-certbot-nginx logrotate sqlite3

if ! command -v node >/dev/null 2>&1 || [ "$(node -v | sed 's/^v//' | cut -d. -f1)" -lt 22 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

echo "=== [3/8] Creating dedicated unprivileged service user and persistent directories ==="
if ! id -u pranav >/dev/null 2>&1; then
  useradd --system --home-dir /opt/pranav --shell /usr/sbin/nologin pranav
fi

mkdir -p "$APP_DIR" "$DATA_DIR" "$BACKUP_DIR" "$LOG_DIR" "$ENV_DIR"
chown -R pranav:pranav /opt/pranav "$DATA_DIR" "$BACKUP_DIR" "$LOG_DIR"
chmod 750 "$DATA_DIR" "$BACKUP_DIR" "$ENV_DIR"

echo "=== [4/8] Generating production environment file if missing ==="
if [ ! -f "$ENV_DIR/backend.env" ]; then
  MASTER_KEY="$(openssl rand -base64 32)"
  cat > "$ENV_DIR/backend.env" <<EOF
NODE_ENV=production
PORT=8443
HOST=127.0.0.1
PRANAV_DB_PATH=$DATA_DIR/pranav.db
PRANAV_BACKUP_DIR=$BACKUP_DIR
PRANAV_PUBLIC_BASE_URL=https://$DOMAIN
PRANAV_OWNER_TELEGRAM_ID=${PRANAV_OWNER_TELEGRAM_ID:-0}
PRANAV_MASTER_ENCRYPTION_KEY=$MASTER_KEY
EOF
  chown root:pranav "$ENV_DIR/backend.env"
  chmod 640 "$ENV_DIR/backend.env"
  echo "Created $ENV_DIR/backend.env — update PRANAV_OWNER_TELEGRAM_ID before starting."
fi

echo "=== [5/8] Copying backend release & running migrations ==="
SCRIPT_REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cp -r "$SCRIPT_REPO_DIR/backend/." "$APP_DIR/"
chown -R pranav:pranav "$APP_DIR"
cd "$APP_DIR"
su -s /bin/bash pranav -c "npm ci --omit=dev && npm run build"
set -a
# shellcheck source=/dev/null
source "$ENV_DIR/backend.env"
set +a
su -s /bin/bash pranav -c "PRANAV_DB_PATH='$PRANAV_DB_PATH' PRANAV_BACKUP_DIR='$PRANAV_BACKUP_DIR' PRANAV_MASTER_ENCRYPTION_KEY='$PRANAV_MASTER_ENCRYPTION_KEY' npm run migrate"

echo "=== [6/8] Installing systemd unit, Nginx TLS config, and logrotate ==="
cp "$SCRIPT_REPO_DIR/deploy/pranav-backend.service" /etc/systemd/system/pranav-backend.service
sed "s/__PRANAV_DOMAIN__/$DOMAIN/g" "$SCRIPT_REPO_DIR/deploy/nginx-pranav.conf" > /etc/nginx/sites-available/pranav.conf
ln -sf /etc/nginx/sites-available/pranav.conf /etc/nginx/sites-enabled/pranav.conf
rm -f /etc/nginx/sites-enabled/default
cp "$SCRIPT_REPO_DIR/deploy/logrotate-pranav" /etc/logrotate.d/pranav

systemctl daemon-reload
systemctl enable pranav-backend
systemctl restart pranav-backend
nginx -t && systemctl reload nginx

echo "=== [7/8] Configuring UFW Firewall (22, 80, 443 only) ==="
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "=== [8/8] Installing daily verified SQLite WAL backup cron ==="
cat > /etc/cron.d/pranav-backup <<'EOF'
15 3 * * * pranav /usr/bin/env bash -c 'set -a && source /etc/pranav/backend.env && set +a && cd /opt/pranav/backend && npm run backup >> /var/log/pranav/backup.log 2>&1'
EOF
chmod 644 /etc/cron.d/pranav-backup

echo "VPS provisioning complete. Run certbot --nginx -d $DOMAIN to finalize TLS certificates."
