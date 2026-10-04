#!/usr/bin/env bash
# Write the server's mangosd.conf / realmd.conf from VMaNGOS's .dist defaults plus classiccraft's
# settings (ports and DB login from tools/config.sh, data/log paths, 3x XP). Existing files are
# kept as <name>.conf.bak. Usage: tools/server-config.sh [output dir, default the server's etc/]
set -euo pipefail
. "$(dirname "$0")/config.sh"

etc="$CC_SERVER_DIR/etc"
out="${1:-$etc}"
db() { echo "127.0.0.1;$CC_DB_PORT;$CC_DB_USER;$CC_DB_PASS;$1"; }

# set KEY VALUE FILE: replace the uncommented "KEY = ..." line (it must exist in the .dist).
set_key() {
  grep -qE "^$1 *=" "$3" || { echo "server-config: $1 not found in $(basename "$3")" >&2; exit 1; }
  local v=${2//\\/\\\\}; v=${v//&/\\&}; v=${v//|/\\|}
  sed -i -E "s|^$1 *=.*|$1 = $v|" "$3"
}

mkdir -p "$out" "$CC_ROOT/data/run/logs"
for f in mangosd realmd; do
  [[ -f "$etc/$f.conf.dist" ]] || { echo "missing $etc/$f.conf.dist - build the server first" >&2; exit 1; }
  [[ -f "$out/$f.conf" ]] && cp "$out/$f.conf" "$out/$f.conf.bak"
  cp "$etc/$f.conf.dist" "$out/$f.conf"
done

m="$out/mangosd.conf"
set_key DataDir "\"$CC_ROOT/data/server\"" "$m"
set_key LogsDir "\"$CC_ROOT/data/run/logs\"" "$m"
set_key LoginDatabase.Info "\"$(db realmd)\"" "$m"
set_key WorldDatabase.Info "\"$(db mangos)\"" "$m"
set_key CharacterDatabase.Info "\"$(db characters)\"" "$m"
set_key LogsDatabase.Info "\"$(db logs)\"" "$m"
set_key WorldServerPort "$CC_WORLD_PORT" "$m"
set_key BindIP '"127.0.0.1"' "$m"
for r in Kill Kill.Elite Quest Explore; do set_key "Rate.XP.$r" 3 "$m"; done

r="$out/realmd.conf"
set_key LoginDatabaseInfo "\"$(db realmd)\"" "$r"
set_key LogsDir "\"$CC_ROOT/data/run/logs\"" "$r"
set_key RealmServerPort "$CC_REALM_PORT" "$r"
set_key BindIP '"127.0.0.1"' "$r"

echo "wrote $m and $r"
