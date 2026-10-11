#!/usr/bin/env bash
# Бэкап каждый день в BACKUP_AT (по TZ контейнера) и один раз сразу после старта.
set -uo pipefail

at=${BACKUP_AT:-03:30}
script=/scripts/backup.sh

echo "$(date -Is) бэкап при старте"
bash "$script" || echo "$(date -Is) ОШИБКА бэкапа при старте" >&2

while true; do
  now=$(date +%s)
  next=$(date -d "today $at" +%s)
  if (( next <= now )); then
    next=$(date -d "tomorrow $at" +%s)
  fi
  echo "$(date -Is) следующий бэкап: $(date -d "@$next" -Is)"
  sleep $(( next - now ))
  bash "$script" || echo "$(date -Is) ОШИБКА бэкапа" >&2
done
