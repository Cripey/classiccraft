#!/usr/bin/env bash
# Build the Minecraft models of the weapons in fabric/src/main/resources/mcwow/wow_weapons.json from
# YOUR OWN WoW install (benilla's cc_weapon: the item's weapon model as Minecraft cubes) into the
# local resource pack ~/.local/share/classiccraft/resourcepack, which the mod loads (McwowLocalPack).
# Nothing of WoW's art is shipped. Previews: ~/.local/share/classiccraft/weapon_previews/<key>.png.
# Then press F3+T in Minecraft (or restart it) to reload resources.
# Usage: tools/wow-weapons.sh [--force] [key...]   (default: the weapons not built yet)
set -euo pipefail
. "$(dirname "$0")/config.sh"
cd "$CC_ROOT"

force=0 keys=()
for a in "$@"; do [[ $a == --force ]] && force=1 || keys+=("$a"); done
: "${WOW_CLIENT:?set WOW_CLIENT (tools/local.env) to your WoW 1.12 folder}"
export WOW_DATA="${WOW_DATA:-$WOW_CLIENT/Data}"
bin=$CC_BENILLA_BIN/cc_weapon
[[ -x $bin ]] || { echo "building cc_weapon"; (cd benilla && cargo build --profile play -p classiccraft --bin cc_weapon -q); }
tools/db.sh status >/dev/null 2>&1 || tools/db.sh start >/dev/null
pack="$HOME/.local/share/classiccraft/resourcepack"

python3 - "$pack" "$force" "${keys[@]}" <<'PY' | while read -r key display voxels; do
import json, os, sys, subprocess
pack, force, want = sys.argv[1], sys.argv[2] == "1", set(sys.argv[3:])
for w in json.load(open("fabric/src/main/resources/mcwow/wow_weapons.json"))["weapons"]:
    if want and w["key"] not in want:
        continue
    if not force and os.path.exists(f"{pack}/assets/mcwow/models/item/wow/{w['key']}.json"):
        continue
    out = subprocess.run(["mysql", "-h127.0.0.1", f"-P{os.environ.get('CC_DB_PORT', '3307')}", "-umangos", "-pmangos",
                          "mangos", "-N", "-B", "-e",
                          f"select display_id from item_template where entry = {int(w['model_item'])} order by patch desc limit 1"],
                         capture_output=True, text=True, check=True).stdout.split()
    if not out:
        print(f"- {w['key']}: WoW item {w['model_item']} not found", file=sys.stderr)
        continue
    print(w["key"], out[0], w.get("voxels", 40))
PY
  OUT="$pack" "$bin" "$display" "$key" "$voxels"
done
echo "local pack: $pack (F3+T in Minecraft to reload)"
