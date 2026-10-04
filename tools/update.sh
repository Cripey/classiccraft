#!/usr/bin/env bash
# Update classiccraft: pull the latest code of all three repos, rebuild, apply new database
# migrations and reinstall the Minecraft mod. Stops the server while it rebuilds.
# Usage: tools/update.sh
set -euo pipefail
. "$(dirname "$0")/config.sh"
cd "$CC_ROOT"

for r in . benilla vmangos; do
  echo "== git pull ($r)"
  git -C "$r" pull --ff-only || { echo "$r has local changes or diverged - sort that out first" >&2; exit 1; }
done

was_running=
if tools/server.sh status | grep -q "mangosd running"; then was_running=1; tools/server.sh stop; fi
tools/build.sh all
tools/db-setup.sh
[[ -f data/setup/minecraft ]] && tools/minecraft-install.sh
[[ -n $was_running ]] && tools/server.sh start
echo "Up to date."
