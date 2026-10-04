#!/usr/bin/env bash
# Run the classiccraft VMaNGOS server (realmd, mangosd; ports in tools/config.sh) in the background.
# mangosd's console reads a named pipe, so GM commands can be sent from any shell.
# Usage: tools/server.sh start|stop|status|cmd "<console command>"|log
set -euo pipefail

. "$(dirname "$0")/config.sh"
root="$CC_ROOT"
bin="$CC_SERVER_DIR/bin"
etc="$CC_SERVER_DIR/etc"
run="$root/data/run"
pipe="$run/mangosd.in"
mkdir -p "$run/logs"

pid_of() { [[ -f "$run/$1.pid" ]] && kill -0 "$(cat "$run/$1.pid")" 2>/dev/null && cat "$run/$1.pid"; }

case "${1:-status}" in
  start)
    "$root/tools/db.sh" start
    if ! pid_of realmd >/dev/null; then
      (cd "$bin" && exec ./realmd -c "$etc/realmd.conf") >"$run/realmd.out" 2>&1 &
      echo $! >"$run/realmd.pid"
    fi
    if ! pid_of mangosd >/dev/null; then
      [[ -p "$pipe" ]] || mkfifo "$pipe"
      # `sleep infinity` holds the pipe's write end open so mangosd never sees EOF.
      sleep infinity >"$pipe" &
      echo $! >"$run/pipe-holder.pid"
      (cd "$bin" && exec ./mangosd -c "$etc/mangosd.conf") <"$pipe" >"$run/mangosd.out" 2>&1 &
      echo $! >"$run/mangosd.pid"
    fi
    echo "starting; follow with: $0 log"
    ;;
  stop)
    if pid=$(pid_of mangosd); then
      echo "server shutdown 1" >"$pipe"
      for _ in $(seq 150); do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
      kill "$pid" 2>/dev/null || true
    fi
    for p in realmd pipe-holder; do
      if pid=$(pid_of $p); then
        kill "$pid" || true
        # Wait for exit, or the next start races the old process for its port.
        for _ in $(seq 50); do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
      fi
      rm -f "$run/$p.pid"
    done
    rm -f "$run/mangosd.pid"
    echo "stopped"
    ;;
  status)
    for p in realmd mangosd; do
      if pid=$(pid_of $p); then echo "$p running (pid $pid)"; else echo "$p not running"; fi
    done
    ;;
  cmd)
    pid_of mangosd >/dev/null || { echo "mangosd not running" >&2; exit 1; }
    shift; echo "$*" >"$pipe"
    sleep 1; tail -n 5 "$run/mangosd.out"
    ;;
  log)
    tail -f "$run/mangosd.out" "$run/realmd.out"
    ;;
  *) echo "usage: $0 start|stop|status|cmd \"<command>\"|log" >&2; exit 2 ;;
esac
