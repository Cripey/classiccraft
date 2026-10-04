#!/usr/bin/env bash
# Update everything: pull the latest code of all three repos, rebuild, apply new database
# migrations, refresh the server's settings when classiccraft's changed (e.g. the XP rate), build
# the WoW weapon models from your own WoW install, and reinstall the Minecraft mod. Stops the
# server while it rebuilds and starts it again if it was running. Close the Minecraft launcher first.
# Usage: tools/update.sh   (--pulled: skip the pulls; used when it restarts itself after pulling a newer version)
set -euo pipefail
. "$(dirname "$0")/config.sh"
cd "$CC_ROOT"

if [[ ${1:-} != --pulled ]]; then
  before=$(sha1sum tools/update.sh)
  for r in . benilla vmangos; do
    echo "== git pull ($r)"
    git -C "$r" pull --ff-only || { echo "$r has local changes or diverged - sort that out first" >&2; exit 1; }
  done
  # The pull brought a newer update.sh: carry on with that one, so its new steps run now.
  if [[ "$(sha1sum tools/update.sh)" != "$before" ]]; then exec tools/update.sh --pulled; fi
fi

was_running=
if tools/server.sh status | grep -q "mangosd running"; then was_running=1; tools/server.sh stop; fi
tools/build.sh all
tools/db-setup.sh

# Server settings: rewritten from VMaNGOS's defaults + ours when our settings script changed since
# the last update (the old files are kept as .conf.bak).
mkdir -p data/setup
stamp=$(sha1sum tools/server-config.sh | cut -c1-40)
if [[ "$(cat data/setup/server-config.sha 2>/dev/null)" != "$stamp" ]]; then
  echo "== server settings changed: rewriting mangosd.conf / realmd.conf"
  tools/server-config.sh && echo "$stamp" > data/setup/server-config.sha
fi

# WoW weapon models (local resource pack, from your own WoW install): new ones in the list are
# built; all of them again when the converter changed. Not fatal - the game runs without them.
stamp=$(sha1sum benilla/crates/classiccraft/src/bin/cc_weapon.rs | cut -c1-40)
force=; [[ "$(cat data/setup/wow-weapons.sha 2>/dev/null)" != "$stamp" ]] && force=--force
if tools/wow-weapons.sh $force; then echo "$stamp" > data/setup/wow-weapons.sha
else echo "(WoW weapon models not built - run tools/wow-weapons.sh to see why)" >&2; fi

[[ -f data/setup/minecraft ]] && tools/minecraft-install.sh
[[ -n $was_running ]] && tools/server.sh start
echo "Up to date."
