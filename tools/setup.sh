#!/usr/bin/env bash
# classiccraft first-time setup. Run it from a clone of github.com/Cripey/classiccraft:
#   tools/setup.sh
# It asks a few questions, then installs, builds and configures everything (30-60 minutes, mostly
# compiling and extracting map data). Each finished step is remembered in data/setup/, so after an
# error just fix it and run the script again - it carries on where it stopped.
set -euo pipefail
. "$(dirname "$0")/config.sh"
cd "$CC_ROOT"

bold=$'\e[1m'; green=$'\e[32m'; yellow=$'\e[33m'; red=$'\e[31m'; off=$'\e[0m'
say()  { echo "${bold}$*${off}"; }
ok()   { echo "${green}✓${off} $*"; }
warn() { echo "${yellow}!${off} $*"; }
die()  { echo "${red}✗ $*${off}" >&2; exit 1; }
yes_no() { # yes_no "question" default(y|n)
  local a; read -r -p "$1 [$( [[ $2 == y ]] && echo Y/n || echo y/N )] " a
  a=${a:-$2}; [[ $a == [yY]* ]]
}
marks=data/setup; mkdir -p "$marks"
done_step() { [[ -f $marks/$1 ]]; }
mark() { date >"$marks/$1"; }
# Save KEY=value into tools/local.env (an environment variable still overrides it).
set_env() {
  touch tools/local.env
  sed -i -E "/^(export )?$1=/d" tools/local.env
  printf 'export %s="${%s:-%s}"\n' "$1" "$1" "$2" >>tools/local.env
  export "$1=$2"
}

say "classiccraft setup"
echo "Minecraft x World of Warcraft 1.12.1. You need: a Linux PC with a Vulkan graphics card, your"
echo "own WoW 1.12.1 (5875) client, and Minecraft Java Edition with the official launcher."
echo

# --- 1. system packages ------------------------------------------------------------------------
. /etc/os-release 2>/dev/null || true
distro="${ID:-unknown} ${ID_LIKE:-}"
if [[ $distro == *debian* || $distro == *ubuntu* ]]; then
  pkgs=(git curl unzip build-essential cmake clang pkg-config libssl-dev zlib1g-dev libmariadb-dev
        libmariadb-dev-compat libasound2-dev libudev-dev mariadb-server-core mariadb-client-core
        openjdk-25-jdk ffmpeg python3)
  missing=(); for p in "${pkgs[@]}"; do dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q "ok installed" || missing+=("$p"); done
  install=(sh -c "sudo apt-get update && sudo apt-get install -y ${missing[*]:-}")
elif [[ $distro == *arch* ]]; then
  pkgs=(git curl unzip base-devel cmake clang pkgconf openssl zlib mariadb mariadb-libs alsa-lib
        systemd-libs jdk-openjdk ffmpeg python)
  missing=(); for p in "${pkgs[@]}"; do pacman -Qq "$p" >/dev/null 2>&1 || missing+=("$p"); done
  install=(sudo pacman -S --needed --noconfirm "${missing[@]}")
else
  missing=()
  warn "Unknown Linux distribution ($distro). Install the equivalents of: git curl unzip, a C/C++"
  warn "toolchain, cmake, clang, pkg-config, OpenSSL/zlib/MariaDB-client/ALSA/udev development"
  warn "packages, MariaDB server, Java 25 JDK, ffmpeg and python3 - then press Enter."
  read -r
fi
if [[ ${#missing[@]} -gt 0 ]]; then
  say "Missing system packages: ${missing[*]}"
  yes_no "Install them now (uses sudo)?" y || die "Install them yourself, then rerun tools/setup.sh."
  "${install[@]}"
fi
java_major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
[[ ${java_major:-0} -ge 25 ]] || die "Java 25 or newer is needed (found: ${java_major:-none}). Install a Java 25 JDK."
ok "system packages"

# --- 2. Rust ---------------------------------------------------------------------------------
[[ -f $HOME/.cargo/env ]] && . "$HOME/.cargo/env"
if ! command -v rustup >/dev/null; then
  say "Rust (rustup) is needed to build the WoW client."
  yes_no "Install rustup from rustup.rs now (into ~/.cargo, no sudo)?" y || die "Install rustup, then rerun."
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
  . "$HOME/.cargo/env"
fi
ok "Rust"

# --- 3. the forks ----------------------------------------------------------------------------
clone() { # dir url branch upstream
  [[ -d $1/.git ]] && return
  say "Downloading $1..."
  git clone --branch "$3" "$2" "$1"
  git -C "$1" remote add upstream "$4"
}
clone benilla https://github.com/Cripey/benilla-classiccraft.git main https://github.com/samwhosung/benilla.git
clone vmangos https://github.com/Cripey/VMaNGOS-classiccraft.git development https://github.com/vmangos/core.git
ok "source code (benilla, vmangos)"

# --- 4. WoW client ---------------------------------------------------------------------------
valid_client() { [[ -f $1/Data/dbc.MPQ && -f $1/Data/model.MPQ ]]; }
if [[ -z ${WOW_CLIENT:-} ]] || ! valid_client "$WOW_CLIENT"; then
  say "Where is your World of Warcraft 1.12.1 client? (the folder containing WoW.exe and Data/)"
  while true; do
    read -r -e -p "> " dir
    dir="${dir%/}"; dir="${dir/#\~/$HOME}"
    valid_client "$dir" && break
    warn "No Data/dbc.MPQ and Data/model.MPQ there - that's not a 1.12.1 client folder."
  done
  set_env WOW_CLIENT "$dir"
fi
ok "WoW client: $WOW_CLIENT (only read, never changed)"

# --- 5. build --------------------------------------------------------------------------------
if ! done_step build; then
  say "Building everything (the first time takes a while)..."
  tools/build.sh all
  mark build
fi
ok "built"

# --- 6. server map data ----------------------------------------------------------------------
if ! done_step extract; then
  say "Extracting map data from your client for the server (~15-30 min)..."
  tools/extract.sh "$WOW_CLIENT"
  mark extract
fi
ok "server map data"

# --- 7. database and server config -------------------------------------------------------------
if ! done_step database; then
  say "Setting up the database..."
  tools/db-setup.sh
  mark database
fi
ok "database"
if [[ ! -f $CC_SERVER_DIR/etc/mangosd.conf ]]; then tools/server-config.sh; fi
ok "server config ($CC_SERVER_DIR/etc)"

# --- 8. account ------------------------------------------------------------------------------
if ! done_step account; then
  say "Create your game account (used to log in to WoW; stays on this PC)."
  while true; do
    read -r -p "Account name: " user
    [[ $user =~ ^[A-Za-z0-9]{3,16}$ ]] && break
    warn "3-16 letters or digits."
  done
  while true; do
    read -r -s -p "Password: " pass; echo
    [[ $pass =~ ^[^[:space:]]{4,16}$ ]] || { warn "4-16 characters, no spaces."; continue; }
    read -r -s -p "Again: " pass2; echo
    [[ $pass == "$pass2" ]] && break
    warn "They don't match."
  done
  tools/server.sh start
  echo "Waiting for the server to come up..."
  for _ in $(seq 300); do grep -q "World initialized" data/run/mangosd.out 2>/dev/null && break; sleep 1; done
  grep -q "World initialized" data/run/mangosd.out || die "The server didn't start - see data/run/mangosd.out"
  tools/server.sh cmd "account create $user $pass" >/dev/null
  # GM level 3: classiccraft uses GM commands (typed as ".command" in Minecraft chat).
  tools/server.sh cmd "account set gmlevel $user 3" >/dev/null
  n=$(tools/db.sh sql -N realmd -e "SELECT COUNT(*) FROM account WHERE username=UPPER('$user')")
  [[ $n == 1 ]] || die "Creating the account failed - see data/run/mangosd.out"
  set_env WOW_USER "$user"
  mark account
  ok "account $user"
else
  ok "account ${WOW_USER:-(created)}"
fi

# --- 9. Minecraft ----------------------------------------------------------------------------
if ! done_step minecraft; then
  say "Installing the Minecraft side (Fabric + the classiccraft mod in its own launcher profile)."
  if tools/minecraft-install.sh; then mark minecraft
  else warn "Skipped for now - rerun tools/minecraft-install.sh once the Minecraft launcher is set up."; fi
fi

echo
say "All set! To play:"
cat <<EOF
  1. tools/server.sh start        (starts the database and the WoW server)
  2. Minecraft launcher: choose the "classiccraft" profile, press Play, and create a world:
     Create New World > World Type: Superflat > Customize > Presets > "The Void". Load it.
  3. tools/play.sh                 (the WoW client; log in as ${WOW_USER:-your account})
  Press \` (backtick) in the WoW window to switch between Minecraft controls and the WoW UI.
  When you're done: tools/server.sh stop
EOF
