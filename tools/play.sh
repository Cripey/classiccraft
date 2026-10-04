#!/usr/bin/env bash
# Build benilla in the distrobox, then run it on the host against the local classiccraft server.
# Why the host: inside the distrobox, wgpu's vkCreateDevice fails on the passed-through NVIDIA
# driver (RequestDeviceError Device(Lost)) even though vkcube works; the same binary runs fine
# natively on the host (2026-10-02). Network and /dev/shm are shared, so nothing else changes.
# Usage: tools/play.sh   (env: WOW_USER, WOW_PASS, WOW_CHAR, WOW_* passed through; WOW_CLIENT, also from tools/local.env;
#        CC_BIN=benilla for stock benilla, default classiccraft = benilla + the Minecraft bridge)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$root/tools/local.env" ] && . "$root/tools/local.env"
client="${WOW_CLIENT:?set WOW_CLIENT to the WoW 1.12.1 client directory (or put it in tools/local.env)}"
export WOW_DATA="${WOW_DATA:-$client/Data}"
export WOW_HOST="${WOW_HOST:-127.0.0.1:3725}"

bin="${CC_BIN:-classiccraft}"
(cd "$root/benilla" && . "$HOME/.cargo/env" && cargo build --profile play -p "$bin")

# Pass every WOW_*/BENILLA_*/RUST_LOG variable through to the host process.
mapfile -t passenv < <(env | grep -E '^(WOW_|BENILLA_|CLASSICCRAFT_|RUST_LOG=)')
cd "$root/benilla"
# Also kept in build/logs/<bin>.log (ANSI colours stripped) for diagnosing a session afterwards.
mkdir -p "$root/build/logs"
log="$root/build/logs/$bin.log"
distrobox-host-exec env "${passenv[@]}" "$root/benilla/target/play/$bin" 2>&1 \
  | tee >(sed -u 's/\x1b\[[0-9;]*m//g' > "$log")
