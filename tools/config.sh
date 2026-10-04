# shellcheck shell=bash disable=SC2034
# Shared settings for the classiccraft scripts. Source me; don't run me.
# Defaults live here; put your own values in tools/local.env (gitignored, written by setup.sh).
# The ports avoid the stock WoW server ports (3306/3724/8085), so classiccraft can run next to
# another private server.

CC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -f "$CC_ROOT/tools/local.env" ]] && . "$CC_ROOT/tools/local.env"

: "${CC_DB_PORT:=3307}"        # MariaDB
: "${CC_REALM_PORT:=3725}"     # realmd (login)
: "${CC_WORLD_PORT:=8086}"     # mangosd (world)
: "${CC_DB_USER:=mangos}"
: "${CC_DB_PASS:=mangos}"
# private = a MariaDB of our own under data/mariadb, run as you (no sudo, no system service).
# system  = the system's MariaDB, already listening on CC_DB_PORT (started with sudo if not).
: "${CC_DB_MODE:=private}"
: "${CC_THREADS:=$(nproc)}"

CC_DB_DATADIR="$CC_ROOT/data/mariadb"
# Unix socket paths are limited to ~107 characters, so not under the repo.
CC_DB_SOCKET="${XDG_RUNTIME_DIR:-/tmp}/classiccraft-mariadb-$CC_DB_PORT.sock"
CC_SERVER_DIR="$CC_ROOT/build/vmangos-run"
CC_BENILLA_BIN="$CC_ROOT/benilla/target/play"
CC_MOD_JAR_DIR="$CC_ROOT/fabric/build/libs"

# Inside a distrobox/toolbox container? (benilla's GPU device can fail there; see play.sh.)
cc_in_container() { [[ -f /run/.containerenv || -f /.dockerenv ]]; }
