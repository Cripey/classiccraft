# classiccraft

Play World of Warcraft 1.12.1 as a Minecraft player. A real Minecraft Java client runs next to a
WoW client and the two are bridged: you walk, fight, mine, craft and build in Minecraft, inside
WoW's world - its zones, creatures, NPCs and quests. Minecraft's blocks, mobs and your hand are
drawn straight into WoW's picture.

It is three projects working together:

| Piece | What it is |
|---|---|
| this repo | The Minecraft mod (Fabric), the bridge protocol and the setup/run scripts |
| [benilla-classiccraft](https://github.com/Cripey/benilla-classiccraft) | WoW client - a fork of [benilla](https://github.com/samwhosung/benilla), an open-source 1.12.1 client |
| [VMaNGOS-classiccraft](https://github.com/Cripey/VMaNGOS-classiccraft) | WoW server - a fork of [VMaNGOS](https://github.com/vmangos/core) |

Everything runs on your own PC, offline, just for you.

> **Work in progress.** Expect rough edges. Linux only for now (Windows is planned).

## What you need

- **Linux** - Ubuntu/Debian (Ubuntu 26.04 tested) or Arch. Other distributions work if you install
  the packages yourself.
- **A graphics card with Vulkan** (NVIDIA, AMD or Intel with current drivers).
- **Your own World of Warcraft 1.12.1 (build 5875) client.** classiccraft never ships Blizzard's
  files; it reads them from your client folder and never changes it.
- **Minecraft: Java Edition** with the official launcher, started and logged in at least once.
- About **15 GB** of free disk space and 30-60 minutes for the first setup (mostly compiling and
  extracting map data).

## Install

```bash
git clone https://github.com/Cripey/classiccraft.git
cd classiccraft
tools/setup.sh
```

`setup.sh` asks for your WoW client folder and an account name and password, then:

1. installs the system packages it needs (asks first; uses `sudo`) and Rust,
2. downloads the client and server code,
3. builds everything,
4. extracts the server's map data from your WoW client (the slowest step),
5. sets up a private database (a MariaDB of its own in `data/`, no system service),
6. creates your game account,
7. adds a **classiccraft** profile to the Minecraft launcher, with its own game folder so your
   other worlds and mods are left alone.

If something fails, fix the cause and run `tools/setup.sh` again; it continues where it stopped.

## Play

1. Start the server: `tools/server.sh start`
2. In the Minecraft launcher, pick the **classiccraft** profile and press Play. Create a world:
   *Create New World > World Type: Superflat > Customize > Presets > The Void*, and load it.
3. Start WoW: `tools/play.sh`, log in and enter the world. Steve appears where your character is.
4. When you're done: `tools/server.sh stop`

### Controls

- You play with Minecraft's controls, in the WoW window.
- **`** (backtick) switches between Minecraft controls and WoW's own UI (menus, chat, settings).
- **Right-click** an NPC or object to talk, take quests, trade or use it; **J** opens the quest log.
- Type `/dance` in Minecraft chat to dance. Lines starting with `.` are sent to the server as GM
  commands (your account is a game master), e.g. `.cheat god off`.
- **Numpad +** puts Steve back onto your WoW character if they ever drift apart.

## Update

```bash
tools/update.sh
```

Close the Minecraft launcher first. One command updates everything: pulls the latest code for all
three projects, rebuilds, updates the database, refreshes the server settings when classiccraft's
changed (e.g. the XP rate), builds the WoW weapon models from your own WoW install, and reinstalls
the mod into the Minecraft launcher. The server is restarted if it was running.

## Settings

Defaults live in `tools/config.sh`; your own values go in `tools/local.env` (setup writes it).

| Setting | Default | |
|---|---|---|
| `WOW_CLIENT` | asked by setup | Your WoW 1.12.1 folder |
| `CC_DB_PORT`, `CC_REALM_PORT`, `CC_WORLD_PORT` | 3307, 3725, 8086 | Chosen not to clash with another WoW server on the standard ports |
| `CC_DB_MODE` | `private` | `system` uses your system's MariaDB on `CC_DB_PORT` instead |
| `CC_MC_DIR` | `~/.minecraft` (or the Flatpak one) | Minecraft launcher folder |

If you change a port after setup, run `tools/server-config.sh` and `tools/minecraft-install.sh`.

## Troubleshooting

- **Server logs:** `tools/server.sh log` (files in `data/run/`). **WoW client log:** `build/logs/classiccraft.log`.
  **Minecraft log:** `<minecraft dir>/classiccraft/logs/latest.log`.
- **GM commands from a terminal:** `tools/server.sh cmd "<command>"`, e.g. `tools/server.sh cmd "account set password NAME NEW NEW"`.
- **Running inside distrobox/toolbox:** `play.sh` starts the WoW client on the host
  (`distrobox-host-exec`); Vulkan can fail inside the container.

## For developers

Design notes and the full history are in [CLAUDE.md](CLAUDE.md). `tools/build.sh [server|client|mod]`
builds one piece; `tools/minecraft.sh` runs Minecraft as a Fabric development client instead of
the launcher.

## Credits

Built on [benilla](https://github.com/samwhosung/benilla), [VMaNGOS](https://github.com/vmangos/core)
and [Fabric](https://fabricmc.net/). Not affiliated with Blizzard Entertainment or Mojang/Microsoft.
World of Warcraft and Minecraft are trademarks of their owners; you need your own copies of both.
