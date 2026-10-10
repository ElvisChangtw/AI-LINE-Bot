#!/usr/bin/env bash
# Nightly SQLite backup from the app volume; keeps the newest 7 copies.
set -euo pipefail

DB=/var/lib/docker/volumes/linebot_app-data/_data/app.db
OUT_DIR=/var/backups/linebot
KEEP=7

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"
OUT="$OUT_DIR/app-$(date +%F).db"
sqlite3 "$DB" ".backup '$OUT'"
[[ -s "$OUT" ]] || { echo "backup: $OUT is empty" >&2; exit 1; }
chmod 600 "$OUT"
find "$OUT_DIR" -maxdepth 1 -name 'app-*.db' | sort -r | tail -n +$((KEEP + 1)) | xargs -r rm -f
echo "backup: wrote $OUT ($(du -h "$OUT" | cut -f1))"
