#!/usr/bin/env bash
# Build classiccraft: the VMaNGOS server, benilla (the WoW client with the Minecraft bridge) and
# the Minecraft mod jar. Incremental after the first run.
# Usage: tools/build.sh [all|server|client|mod]   (CC_THREADS sets the parallel jobs)
set -euo pipefail
. "$(dirname "$0")/config.sh"
cd "$CC_ROOT"
mkdir -p build/logs
t0=$SECONDS
step() { echo "== $1 ($(( SECONDS - t0 ))s elapsed)"; }

server() {
  step "VMaNGOS server (first build ~2-5 min; log build/logs/vmangos.log)"
  if [[ ! -f build/vmangos/CMakeCache.txt ]]; then
    cmake -S vmangos -B build/vmangos -DCMAKE_BUILD_TYPE=Release -DBUILD_EXTRACTORS=ON \
      -DCMAKE_INSTALL_PREFIX="$CC_SERVER_DIR" >build/logs/vmangos.log 2>&1 \
      || { tail -20 build/logs/vmangos.log; exit 1; }
  fi
  { cmake --build build/vmangos -j "$CC_THREADS" && cmake --install build/vmangos; } >>build/logs/vmangos.log 2>&1 \
    || { grep -E 'error|Error' build/logs/vmangos.log | tail -20; echo "server build failed, see build/logs/vmangos.log" >&2; exit 1; }
}

client() {
  step "benilla client (first build ~5-10 min)"
  [[ -f "$HOME/.cargo/env" ]] && . "$HOME/.cargo/env"
  (cd benilla && cargo build --profile play -p classiccraft)
}

mod() {
  step "Minecraft mod"
  (cd fabric && ./gradlew build -q)
  ls "$CC_MOD_JAR_DIR"/classiccraft-bridge-*.jar | grep -v sources
}

case "${1:-all}" in
  all) server; client; mod ;;
  server|client|mod) "$1" ;;
  *) echo "usage: $0 [all|server|client|mod]" >&2; exit 2 ;;
esac
step "build done"
