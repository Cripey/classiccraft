#!/usr/bin/env bash
# Build benilla (with the Minecraft bridge) and run it against the local classiccraft server.
# Inside a distrobox it runs on the HOST: there, wgpu's vkCreateDevice fails on a passed-through
# NVIDIA driver (RequestDeviceError Device(Lost)) even though vkcube works, while the same binary
# runs fine natively (2026-10-02). Network and /dev/shm are shared, so nothing else changes.
# Usage: tools/play.sh   (env: WOW_USER, WOW_PASS, WOW_CHAR, WOW_* passed through; WOW_CLIENT, also
#        from tools/local.env; CC_BIN=benilla for stock benilla, default classiccraft = benilla +
#        the Minecraft bridge; CC_NOBUILD=1 skips the build; CC_NOWAIT=1 doesn't wait for a running
#        Minecraft to reach its world)
set -euo pipefail
. "$(dirname "$0")/config.sh"
root="$CC_ROOT"

client="${WOW_CLIENT:?set WOW_CLIENT to the WoW 1.12.1 client directory (or run tools/setup.sh)}"
export WOW_DATA="${WOW_DATA:-$client/Data}"
export WOW_HOST="${WOW_HOST:-127.0.0.1:$CC_REALM_PORT}"

bin="${CC_BIN:-classiccraft}"
if [[ -z ${CC_NOBUILD:-} ]]; then
  [[ -f "$HOME/.cargo/env" ]] && . "$HOME/.cargo/env"
  (cd "$root/benilla" && cargo build --profile play -p "$bin")
fi

# A Minecraft starting up (tools/minecraft.sh: Gradle's runClient, then the game) or still on its
# menus: start WoW once it's in its world (WoW opening over it is the clean picture). The mod
# rewrites minecraft.status every 2 s. No Minecraft = no wait; CC_NOWAIT=1 skips this.
status="${CLASSICCRAFT_DATA_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/classiccraft}/minecraft.status"
fresh() { [[ -f $status ]] && (( $(date +%s) - $(stat -c %Y "$status") < 6 )); }
in_world() { fresh && [[ $(<"$status") == world ]]; }
mc_running() { fresh || pgrep -f '[G]radleWrapperMain runClient' >/dev/null || pgrep -f '[d]evlaunchinjector.Main' >/dev/null; }
if [[ -z ${CC_NOWAIT:-} ]] && mc_running && ! in_world; then
  echo "Waiting for Minecraft to load its world... (Ctrl-C to stop, CC_NOWAIT=1 to skip)"
  while mc_running && ! in_world; do sleep 0.5; done
fi

# Pass every WOW_*/BENILLA_*/RUST_LOG variable through (needed for the host process).
mapfile -t passenv < <(env | grep -E '^(WOW_|BENILLA_|CLASSICCRAFT_|RUST_LOG=)')
run=(env "${passenv[@]}" "$CC_BENILLA_BIN/$bin")
if cc_in_container && command -v distrobox-host-exec >/dev/null; then
  run=(distrobox-host-exec "${run[@]}")
fi
cd "$root/benilla"
# Also kept in build/logs/<bin>.log (ANSI colours stripped) for diagnosing a session afterwards.
mkdir -p "$root/build/logs"
log="$root/build/logs/$bin.log"
"${run[@]}" 2>&1 | tee >(sed -u 's/\x1b\[[0-9;]*m//g' > "$log")
