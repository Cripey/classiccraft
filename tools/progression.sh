#!/usr/bin/env bash
# The 1-60 progression sim (tools/progression/): a rough model of one player levelling along a
# route, to check that loot, rewards, gear, ores, vendors and combat still add up after a change.
#   1. WoW data from the DB        -> build/progression/wow.json   (extract.py; cached, --fresh redoes it)
#   2. the mod's own rules         -> build/progression/rules.json (headless server, ./gradlew runSim;
#                                     redone when fabric/src or wow.json changed, ~15 s)
#   3. the walk + report           -> build/progression/report.md / report.json (sim.py, <1 s)
# The report compares itself with tools/progression/baseline.json (committed); --accept makes this
# run the new baseline. Prints the report's head (summary, changes, flags).
# --fit-taken prints the difficulty curve's anchors that meet the targets (sim.py TARGET_TAKEN) instead,
# to paste into McwowCombat.TAKEN_CURVE.
# Usage: tools/progression.sh [--fresh] [--accept] [--fit-taken] [--route tools/progression/route_alliance.json]
set -euo pipefail
. "$(dirname "$0")/config.sh"
cd "$CC_ROOT"

fresh=0 accept=0 fit="" route=tools/progression/route_alliance.json
while [[ $# -gt 0 ]]; do
  case "$1" in
    --fresh) fresh=1 ;;
    --accept) accept=1 ;;
    --fit-taken) fit=--fit-taken ;;
    --route) route="$2"; shift ;;
    *) echo "usage: $0 [--fresh] [--accept] [--fit-taken] [--route <route.json>]" >&2; exit 2 ;;
  esac
  shift
done

out=build/progression
mkdir -p "$out"
wow=$out/wow.json rules=$out/rules.json

# 1. WoW data (changes only with the DB or the route)
if [[ $fresh == 1 || ! -f $wow || $route -nt $wow || tools/progression/extract.py -nt $wow \
      || fabric/src/main/resources/mcwow/chests.json -nt $wow ]]; then
  tools/db.sh status >/dev/null 2>&1 || tools/db.sh start >/dev/null
  python3 tools/progression/extract.py "$route" "$wow"
fi

# 2. The mod's rules, evaluated by the mod
stamp=$( { find fabric/src/main -type f -print0 | sort -z | xargs -0 sha1sum; sha1sum "$wow"; } | sha1sum | cut -c1-16)
if [[ $fresh == 1 || ! -f $rules || "$(cat "$out/rules.stamp" 2>/dev/null)" != "$stamp" ]]; then
  echo "progression: exporting the mod's rules (headless server, log $out/sim-server.log)"
  rm -f "$rules"
  mkdir -p fabric/build/sim-server
  [[ -f fabric/build/sim-server/eula.txt ]] || echo "eula=true" > fabric/build/sim-server/eula.txt
  [[ -f fabric/build/sim-server/server.properties ]] || printf '%s\n' 'level-type=minecraft\:flat' \
    'generate-structures=false' 'online-mode=false' 'view-distance=2' 'simulation-distance=2' \
    'server-port=25599' > fabric/build/sim-server/server.properties
  (cd fabric && ./gradlew runSim -q -PsimIn="$CC_ROOT/$wow" -PsimOut="$CC_ROOT/$rules") >"$out/sim-server.log" 2>&1 || true
  [[ -f $rules ]] || { grep -iE "error|exception" "$out/sim-server.log" | tail -20; echo "progression: rules export failed" >&2; exit 1; }
  echo "$stamp" > "$out/rules.stamp"
fi

# 3. The walk
if [[ -n $fit ]]; then
  python3 tools/progression/sim.py "$wow" "$rules" "$route" "$out" --fit-taken
  exit 0
fi
python3 tools/progression/sim.py "$wow" "$rules" "$route" "$out" tools/progression/baseline.json
if [[ $accept == 1 ]]; then
  python3 - "$out/report.json" tools/progression/baseline.json <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
keep = {k: r[k] for k in ("route", "total_hours", "final_level", "quests_done", "quests_skipped", "deaths", "rows", "flags")}
json.dump(keep, open(sys.argv[2], "w"), indent=0)
print("progression: baseline updated ->", sys.argv[2])
EOF
fi
echo
sed -n '1,/^## Zones/p' "$out/report.md" | sed '$d'
echo "(full report: $out/report.md)"
