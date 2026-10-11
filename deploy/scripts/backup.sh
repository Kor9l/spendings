#!/usr/bin/env bash
# Один бэкап: pg_dump в /backups, проверка восстановлением во временную базу, удаление старых,
# копия вне сервера (BACKUP_COPY_COMMAND). Переменные PG* — как у psql.
set -euo pipefail

dir=${BACKUP_DIR_IN_CONTAINER:-/backups}
keep=${BACKUP_KEEP_DAYS:-30}
stamp=$(date +%Y-%m-%d_%H%M)
file="$dir/spendings_$stamp.dump"

pg_dump --format=custom --compress=9 --file="$file.part" "$PGDATABASE"
mv "$file.part" "$file"
echo "$(date -Is) бэкап: $file ($(du -h "$file" | cut -f1))"

/bin/bash "$(dirname "$0")/verify.sh" "$file"

find "$dir" -name 'spendings_*.dump' -mtime +"$keep" -print -delete | sed 's/^/удалён старый: /'

if [[ -n "${BACKUP_COPY_COMMAND:-}" ]]; then
  BACKUP_FILE="$file" bash -c "$BACKUP_COPY_COMMAND"
  echo "$(date -Is) копия вне сервера: готово"
fi
