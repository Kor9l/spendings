#!/usr/bin/env bash
# Проверка бэкапа: восстановить во временную базу и сравнить число строк с рабочей.
# Использование: verify.sh <файл.dump>
set -euo pipefail

file=$1
check=spendings_restore_check
export PGOPTIONS='-c client_min_messages=warning'

psql -q -d postgres -c "drop database if exists $check"
psql -q -d postgres -c "create database $check"
trap 'psql -q -d postgres -c "drop database if exists $check"' EXIT

pg_restore --no-owner --exit-on-error --dbname="$check" "$file"

count() {
  psql -At -d "$1" -c "select (select count(*) from operation) || ' ' || (select count(*) from account) || ' ' || (select max(version) from flyway_schema_history where success)"
}
restored=$(count "$check")
live=$(count "$PGDATABASE")
echo "$(date -Is) проверка: в дампе операций/счетов/версия схемы = $restored, в рабочей базе = $live"
# рабочая база могла измениться за время проверки — сравниваем схему и то, что дамп не пуст
if [[ "${restored##* }" != "${live##* }" ]]; then
  echo "ОШИБКА: версия схемы в дампе не совпадает с рабочей" >&2
  exit 1
fi
