#!/usr/bin/env bash
# Восстановление из дампа В РАБОЧУЮ базу. Приложение на это время останавливается.
# Использование (на сервере, из каталога проекта):
#   ./deploy/scripts/restore.sh backups/spendings_2026-10-11_0330.dump
set -euo pipefail

file=${1:?укажите файл дампа}
[[ -f "$file" ]] || { echo "нет файла $file" >&2; exit 1; }

read -r -p "Текущие данные будут заменены данными из $file. Продолжить? [y/N] " answer
[[ "$answer" == [yY] ]] || exit 1

docker compose stop app
docker compose exec -T db psql -q -U spendings -d postgres -c "drop database spendings with (force)"
docker compose exec -T db psql -q -U spendings -d postgres -c "create database spendings"
docker compose exec -T db pg_restore --no-owner --exit-on-error -U spendings -d spendings < "$file"
docker compose start app
echo "Готово: данные восстановлены из $file"
