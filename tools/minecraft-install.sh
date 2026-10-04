#!/usr/bin/env bash
# Install classiccraft into the official Minecraft launcher: Fabric Loader, a "classiccraft"
# launcher profile with its OWN game folder (<minecraft dir>/classiccraft, so your other mods and
# worlds are untouched), Fabric API and the classiccraft mod. Rerun after every update.
# Usage: tools/minecraft-install.sh   (CC_MC_DIR overrides the Minecraft directory)
# Needs the launcher to have run once (it creates launcher_profiles.json). Build the mod first.
set -euo pipefail
. "$(dirname "$0")/config.sh"
cd "$CC_ROOT"

mc="${CC_MC_DIR:-}"
if [[ -z $mc ]]; then
  for d in "$HOME/.minecraft" "$HOME/.var/app/com.mojang.Minecraft/.minecraft"; do
    [[ -f $d/launcher_profiles.json ]] && { mc=$d; break; }
  done
fi
if [[ -z $mc || ! -f $mc/launcher_profiles.json ]]; then
  echo "No Minecraft launcher found (looked for ~/.minecraft/launcher_profiles.json)." >&2
  echo "Install the official Minecraft launcher, start it once and log in, then rerun this." >&2
  echo "(Another location: CC_MC_DIR=/path/to/.minecraft $0)" >&2
  exit 1
fi

prop() { sed -n "s/^$1=//p" fabric/gradle.properties; }
mcv=$(prop minecraft_version); loader=$(prop loader_version); api=$(prop fabric_api_version)
jar=$(ls "$CC_MOD_JAR_DIR"/classiccraft-bridge-*.jar 2>/dev/null | grep -v sources | head -1)
[[ -n $jar ]] || { echo "mod not built yet - run tools/build.sh mod" >&2; exit 1; }
dl=data/downloads; mkdir -p "$dl"
game="$mc/classiccraft"
version_id="fabric-loader-$loader-$mcv"

echo "Minecraft directory: $mc"
if [[ ! -d $mc/versions/$version_id ]]; then
  echo "Installing Fabric Loader $loader for Minecraft $mcv..."
  url=$(curl -fsSL https://meta.fabricmc.net/v2/versions/installer | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["url"])')
  curl -fsSL -o "$dl/fabric-installer.jar" "$url"
  java -jar "$dl/fabric-installer.jar" client -dir "$mc" -mcversion "$mcv" -loader "$loader" -noprofile
fi

echo "Adding the \"classiccraft\" launcher profile (game folder $game)..."
cp "$mc/launcher_profiles.json" "$mc/launcher_profiles.json.classiccraft-backup"
python3 - "$mc/launcher_profiles.json" "$version_id" "$game" <<'EOF'
import json, sys, datetime
path, version, game = sys.argv[1:]
with open(path) as f:
    data = json.load(f)
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
profile = data.setdefault("profiles", {}).get("classiccraft", {"created": now, "icon": "Grass"})
profile.update({"name": "classiccraft", "type": "custom", "lastVersionId": version,
                "gameDir": game, "lastUsed": now})
data["profiles"]["classiccraft"] = profile
with open(path, "w") as f:
    json.dump(data, f, indent=2)
EOF

mkdir -p "$game/mods" "$game/config"
api_jar="$dl/fabric-api-$api.jar"
if [[ ! -f $api_jar ]]; then
  echo "Downloading Fabric API $api..."
  url=$(curl -fsSL "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22$mcv%22%5D&loaders=%5B%22fabric%22%5D" \
    | python3 -c 'import json,sys
want = sys.argv[1]
for v in json.load(sys.stdin):
    if v["version_number"] == want:
        print(v["files"][0]["url"]); break' "$api")
  [[ -n $url ]] || { echo "Fabric API $api not found on Modrinth" >&2; exit 1; }
  curl -fsSL -o "$api_jar.part" "$url" && mv "$api_jar.part" "$api_jar"
fi
rm -f "$game"/mods/fabric-api-*.jar "$game"/mods/classiccraft-bridge-*.jar
cp "$api_jar" "$jar" "$game/mods/"

# The mod reads the server's character database (instance ids); point it at ours.
cat >"$game/config/mcwow.json" <<EOF
{
  "jdbcUrl": "jdbc:mysql://127.0.0.1:$CC_DB_PORT/characters",
  "user": "$CC_DB_USER",
  "password": "$CC_DB_PASS"
}
EOF

echo "Done. In the Minecraft launcher, pick the \"classiccraft\" profile and press Play."
