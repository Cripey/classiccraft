#!/usr/bin/env bash
# Start/stop/status for MariaDB inside the distrobox (no systemd here).
# Usage: tools/db.sh start|stop|status
set -euo pipefail

sock=/run/mysqld/mysqld.sock

running() { sudo mariadb-admin --socket="$sock" ping >/dev/null 2>&1; }

case "${1:-status}" in
  start)
    if running; then echo "MariaDB already running"; exit 0; fi
    sudo mkdir -p /run/mysqld && sudo chown mysql:mysql /run/mysqld
    sudo mysqld_safe --user=mysql >/dev/null 2>&1 &
    for _ in $(seq 50); do running && { echo "MariaDB started"; exit 0; }; sleep 0.2; done
    echo "MariaDB failed to start; see /var/lib/mysql/*.err" >&2; exit 1
    ;;
  stop)
    if running; then sudo mariadb-admin --socket="$sock" shutdown; echo "MariaDB stopped"
    else echo "MariaDB not running"; fi
    ;;
  status)
    if running; then echo "MariaDB running"; else echo "MariaDB not running"; exit 1; fi
    ;;
  *) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
