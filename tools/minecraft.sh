#!/usr/bin/env bash
# Launch Minecraft (Fabric dev client) with the classiccraft bridge mod, in the distrobox, on the
# host NVIDIA GL (fabric/build.gradle's runClient wiring). Log: fabric/run/logs/latest.log.
# Usage: tools/minecraft.sh [Character] [gradle args] - a save is a WoW character + its own world:
# with a name, that character's world (fabric/run/saves/<Character>) opens, made on the Void preset
# the first time; without one, the world played last. Pair it with tools/play.sh <Character>.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$root/tools/local.env" ] && . "$root/tools/local.env"
cd "$root/fabric"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
export DISPLAY="${DISPLAY:-:1}"
# The save's world goes into the dev client's config/mcwow.json ("world"; removed without a name).
world=""
if [[ $# -gt 0 && $1 != -* ]]; then world="$1"; shift; fi
python3 - "$root/fabric/run/config/mcwow.json" "$world" <<'PY'
import json, os, sys
path, world = sys.argv[1], sys.argv[2]
cfg = {}
if os.path.exists(path):
    with open(path) as f:
        cfg = json.load(f)
if world:
    cfg["world"] = world
else:
    cfg.pop("world", None)
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w") as f:
    json.dump(cfg, f, indent=2)
PY
[[ -n $world ]] && echo "Minecraft: the world of $world"
exec ./gradlew runClient "$@"
