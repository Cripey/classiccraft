#!/usr/bin/env bash
# MariaDB for the classiccraft server (port CC_DB_PORT, see tools/config.sh).
# CC_DB_MODE=private (default): our own instance in data/mariadb, run as you - no sudo.
# CC_DB_MODE=system: the system MariaDB (systemd if present, else sudo mysqld_safe - distrobox).
# Usage: tools/db.sh start|stop|status|init|sql [mariadb args...]
#   init: create the private instance's data directory (setup.sh does this)
#   sql:  a mariadb client with admin rights (no password), e.g. tools/db.sh sql -e "show databases"
set -euo pipefail
. "$(dirname "$0")/config.sh"

if [[ $CC_DB_MODE == private ]]; then
  sock="$CC_DB_SOCKET"
  admin() { mariadb --no-defaults --socket="$sock" "$@"; }
  # The plain client (mariadb-client-core), not mariadb-admin: that one is in mariadb-client,
  # which a minimal install doesn't have - the check failed while the server was up.
  ping() { admin -e "SELECT 1" >/dev/null 2>&1; }
else
  sock=/run/mysqld/mysqld.sock
  admin() { sudo mariadb --socket="$sock" "$@"; }
  ping() { sudo mariadb-admin --socket="$sock" ping >/dev/null 2>&1; }
fi
running() { ping; }

wait_up() {
  for _ in $(seq 100); do running && return 0; sleep 0.2; done
  return 1
}

case "${1:-status}" in
  init)
    [[ $CC_DB_MODE == private ]] || { echo "CC_DB_MODE=system: nothing to initialise"; exit 0; }
    if [[ -d "$CC_DB_DATADIR/mysql" ]]; then echo "MariaDB data directory already exists"; exit 0; fi
    mkdir -p "$CC_DB_DATADIR"
    # The OS user running this becomes a passwordless admin over the socket.
    mariadb-install-db --no-defaults --datadir="$CC_DB_DATADIR" \
      --auth-root-authentication-method=socket --skip-test-db >"$CC_ROOT/data/mariadb-init.log" 2>&1 \
      || { echo "mariadb-install-db failed, see data/mariadb-init.log" >&2; exit 1; }
    echo "MariaDB data directory created in data/mariadb"
    ;;
  start)
    if running; then echo "MariaDB already running"; exit 0; fi
    if [[ $CC_DB_MODE == private ]]; then
      [[ -d "$CC_DB_DATADIR/mysql" ]] || { echo "no data/mariadb yet - run tools/setup.sh (or: $0 init)" >&2; exit 1; }
      mkdir -p "$CC_ROOT/data/run"
      mariadbd --no-defaults --datadir="$CC_DB_DATADIR" --port="$CC_DB_PORT" \
        --bind-address=127.0.0.1 --socket="$sock" --pid-file="$CC_ROOT/data/run/mariadb.pid" \
        --log-error="$CC_ROOT/data/run/mariadb.err" >/dev/null 2>&1 &
      disown
      wait_up && { echo "MariaDB started (port $CC_DB_PORT)"; exit 0; }
      echo "MariaDB failed to start. The end of data/run/mariadb.err:" >&2
      tail -n 15 "$CC_ROOT/data/run/mariadb.err" >&2 2>/dev/null || true
      exit 1
    fi
    if [[ -d /run/systemd/system ]]; then
      sudo systemctl start mariadb
    else
      sudo mkdir -p /run/mysqld && sudo chown mysql:mysql /run/mysqld
      sudo mysqld_safe --user=mysql >/dev/null 2>&1 &
    fi
    wait_up && { echo "MariaDB started"; exit 0; }
    echo "MariaDB failed to start; see the system MariaDB log" >&2; exit 1
    ;;
  stop)
    if ! running; then echo "MariaDB not running"; exit 0; fi
    if [[ $CC_DB_MODE == private ]]; then
      pid=$(cat "$CC_ROOT/data/run/mariadb.pid" 2>/dev/null || true)
      admin -e "SHUTDOWN"
      # Wait for the process itself, not just the socket: a quick restart would hit its lock.
      for _ in $(seq 150); do
        if [[ -n $pid ]]; then kill -0 "$pid" 2>/dev/null || break; else running || break; fi
        sleep 0.2
      done
    else
      sudo mariadb-admin --socket="$sock" shutdown
    fi
    echo "MariaDB stopped"
    ;;
  status)
    if running; then echo "MariaDB running ($CC_DB_MODE, port $CC_DB_PORT)"; else echo "MariaDB not running"; exit 1; fi
    ;;
  sql)
    shift; admin "$@"
    ;;
  *) echo "usage: $0 start|stop|status|init|sql [args]" >&2; exit 2 ;;
esac
