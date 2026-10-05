#!/usr/bin/env bash
# Create and fill the server's databases. Safe to rerun (tools/update.sh does after a pull):
#  - creates the databases and the server's DB user if missing
#  - imports VMaNGOS's world dump (release db_latest, downloaded to data/db) into empty databases
#  - applies vmangos/sql/migrations (each one records itself and runs once)
#  - applies classiccraft's own rows (sql/custom/classiccraft_*.sql) and the realm entry
set -euo pipefail
. "$(dirname "$0")/config.sh"
cd "$CC_ROOT"
sql() { tools/db.sh sql "$@"; }

tools/db.sh init
tools/db.sh start

# --- databases and user --------------------------------------------------------------------
dbs=(mangos characters realmd logs)
{
  for d in "${dbs[@]}"; do echo "CREATE DATABASE IF NOT EXISTS \`$d\` CHARACTER SET utf8mb4;"; done
  for h in localhost 127.0.0.1; do
    echo "CREATE USER IF NOT EXISTS '$CC_DB_USER'@'$h' IDENTIFIED BY '$CC_DB_PASS';"
    for d in "${dbs[@]}"; do echo "GRANT ALL PRIVILEGES ON \`$d\`.* TO '$CC_DB_USER'@'$h';"; done
  done
  echo "FLUSH PRIVILEGES;"
} | sql

# --- world dump ----------------------------------------------------------------------------
dump=data/db/mysql-dump
if [[ ! -f $dump/mangos.sql ]]; then
  mkdir -p data/db
  echo "Downloading the VMaNGOS database (release db_latest, ~30 MB)..."
  url=$(curl -fsSL https://api.github.com/repos/vmangos/core/releases/tags/db_latest | python3 -c '
import json, sys
for a in json.load(sys.stdin)["assets"]:
    if a["name"].startswith("db-") and "sqlite" not in a["name"]:
        print(a["browser_download_url"]); break')
  [[ -n $url ]] || { echo "could not find the database download on github.com/vmangos/core" >&2; exit 1; }
  curl -fL --progress-bar -o "data/db/${url##*/}" "$url"
  unzip -oq "data/db/${url##*/}" -d data/db
fi

# An import cut short (Ctrl+C, crash) leaves a "db-importing-<db>" marker; the next run drops that
# half-filled database and imports it again. Databases this script didn't start importing (e.g. an
# install set up by hand) are never touched.
mkdir -p data/setup
declare -A file=([mangos]=mangos [characters]=characters [realmd]=logon [logs]=logs)
for d in "${dbs[@]}"; do
  busy="data/setup/db-importing-$d"
  if [[ -f $busy ]]; then
    echo "Redoing the import of $d (the last one didn't finish)..."
    sql -e "DROP DATABASE IF EXISTS \`$d\`; CREATE DATABASE \`$d\` CHARACTER SET utf8mb4;"
  fi
  if [[ $(sql -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$d'") == 0 ]]; then
    echo "Importing $d ($(du -h "$dump/${file[$d]}.sql" | cut -f1))..."
    date > "$busy"
    sql "$d" < "$dump/${file[$d]}.sql"
    rm -f "$busy"
  fi
done

# --- migrations (each file checks the migrations table itself) -------------------------------
declare -A suffix=([mangos]=world [characters]=characters [realmd]=logon [logs]=logs)
for d in "${dbs[@]}"; do
  files=(vmangos/sql/migrations/*_"${suffix[$d]}".sql)
  [[ -e ${files[0]} ]] || continue
  before=$(sql -N "$d" -e "SELECT COUNT(*) FROM migrations" 2>/dev/null || echo 0)
  cat "${files[@]}" | sql "$d"
  after=$(sql -N "$d" -e "SELECT COUNT(*) FROM migrations" 2>/dev/null || echo 0)
  (( after > before )) && echo "$d: applied $((after - before)) migrations"
done

# --- classiccraft ----------------------------------------------------------------------------
for f in vmangos/sql/custom/classiccraft_*.sql; do sql mangos < "$f"; done
sql realmd <<EOF
INSERT INTO realmlist (id, name, address, localAddress, port, icon, realmflags, timezone,
    allowedSecurityLevel, population, gamebuild_min, gamebuild_max, realmbuilds)
  VALUES (1, 'classiccraft', '127.0.0.1', '127.0.0.1', $CC_WORLD_PORT, 1, 0, 1, 0, 0, 5875, 5875, '5875')
  ON DUPLICATE KEY UPDATE port = $CC_WORLD_PORT;
EOF
echo "Databases ready."
