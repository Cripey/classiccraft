#!/usr/bin/env bash
# Launch Minecraft (Fabric dev client) with the classiccraft bridge mod, in the distrobox, on the
# host NVIDIA GL (fabric/build.gradle's runClient wiring). Log: fabric/run/logs/latest.log.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$root/tools/local.env" ] && . "$root/tools/local.env"
cd "$root/fabric"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
export DISPLAY="${DISPLAY:-:1}"
exec ./gradlew runClient "$@"
