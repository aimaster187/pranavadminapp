#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:8443}"

echo "Checking PRANAV systemd service..."
systemctl is-active --quiet pranav-backend && echo "systemd: ACTIVE" || echo "systemd: INACTIVE (check locally)"

echo "Checking Nginx syntax..."
nginx -t || true

echo "Checking PRANAV health endpoint at $BASE_URL/healthz..."
curl -fsS "$BASE_URL/healthz"
echo ""
echo "Checking UFW firewall status..."
ufw status || true
