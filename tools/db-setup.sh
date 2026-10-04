#!/usr/bin/env bash
# Create and fill the server's databases. Safe to rerun (tools/update.sh does after a pull):
#  - creates the databases and the server's DB user if missing
#  - imports VMaNGOS's world dump (release db_latest, downloaded to data/db) into empty databases
#  - applies vmangos/sql/migrations (each one records itself and runs once)
#  - applies classiccraft's own rows (sql/custom/classiccraft_proxies.sql) and the realm entry
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

tables() { sql -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$1'"; }
declare -A file=([mangos]=mangos [characters]=characters [realmd]=logon [logs]=logs)
for d in "${dbs[@]}"; do
  if [[ $(tables "$d") == 0 ]]; then
    echo "Importing $d ($(du -h "$dump/${file[$d]}.sql" | cut -f1))..."
    sql "$d" < "$dump/${file[$d]}.sql"
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
sql mangos < vmangos/sql/custom/classiccraft_proxies.sql
sql realmd <<EOF
INSERT INTO realmlist (id, name, address, localAddress, port, icon, realmflags, timezone,
    allowedSecurityLevel, population, gamebuild_min, gamebuild_max, realmbuilds)
  VALUES (1, 'classiccraft', '127.0.0.1', '127.0.0.1', $CC_WORLD_PORT, 1, 0, 1, 0, 0, 5875, 5875, '5875')
  ON DUPLICATE KEY UPDATE port = $CC_WORLD_PORT;
EOF
echo "Databases ready."
