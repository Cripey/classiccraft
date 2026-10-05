# classiccraft — a real Minecraft client bridged into vanilla WoW (1.12.1)

## Premise (decided 2026-10-02)
Play World of Warcraft 1.12.1 as if you were a Minecraft player. A real, unmodified Minecraft
Java client runs alongside a **fork of an open-source WoW client** and is bridged to it: Minecraft
is authoritative for the local player's physics, camera, inventory, crafting, blocks and combat
maths; WoW keeps its own world, quests, NPCs and server. Minecraft's blocks, entities, hand and
HUD are drawn into WoW's frame, depth-tested against WoW's world.

This is the successor to **mcwow** (`../azerothcore-mc/`, a sibling directory),
which does the same thing against the official Blizzard 3.3.5a client under Wine with an
injected DLL. mcwow works (camera, puppet, collision, overlay, blocks, combat), but every WoW-side
piece is a hack around a closed binary: hardcoded build-12340 addresses, inline hooks, D3D9
depth-buffer tricks, hand-built movement packets, GM chat commands for damage. classiccraft
replaces the closed client with one whose source we own. mcwow stays untouched as the working
reference until classiccraft catches up.

### The three pieces
| Piece | What | Why |
|---|---|---|
| WoW client | Fork of **benilla** (github.com/samwhosung/benilla) — a from-scratch 1.12.1 client in Rust + Bevy 0.18 / wgpu 27, MIT/Apache | Most complete open client (whole game, stock FrameXML + addons), native Linux, actively developed. Camera, player model, movement, rendering and packets become ordinary code we edit. |
| Server | **VMaNGOS** (github.com/vmangos/core) | benilla's own reference server (Warden off by default). Native Linux build. Purpose-built for this project — server changes are allowed. |
| Minecraft | Real Minecraft Java client + our Fabric mod, ported from mcwow's `fabric/` | Authenticity: real MC physics, blocks, crafting, mobs. **Not** reimplemented in Rust. |

### Decisions and why
- **Open client over the official one**: the user wants the freedom of editing the client; the
  hacks in mcwow were the cost of a closed binary.
- **Vanilla over WotLK**: user doesn't mind losing WotLK content; benilla (1.12.1) is a much more
  complete client than the 3.3.5a alternatives (WoWee is C++/Vulkan and rougher; WowRust is early).
- **Server changes allowed**: mcwow avoided them only to stay plug-and-play with any AzerothCore
  repack on the official client. That goal doesn't apply with a custom client. Use custom opcodes
  / server code instead of GM-command workarounds (e.g. a "Minecraft hit creature X for N, crit"
  packet instead of `.damage`; server-side damage from a named source instead of `.cast self`
  tricks; server-forwarded incoming hits instead of inflating HP to 10,000,000).
- **Bots don't matter**: AzerothCore+playerbots and CMaNGOS were just the user's normal play
  servers, not requirements.
- **Keep the bridge**: Minecraft stays a separate real client connected over shared memory.

## Repo layout
- `classiccraft/` — top-level git repo: our own code (Fabric mod, protocol, tools, docs).
- `benilla/`, `vmangos/` — independent clones, gitignored by the top-level repo. Remotes:
  `origin` = our public GitHub forks (Cripey/benilla-classiccraft, Cripey/VMaNGOS-classiccraft),
  `upstream` = original project. Our work lives on the fork's default branch (benilla `main`,
  VMaNGOS `development`), on top of upstream; pull upstream fixes by merging `upstream/<branch>`
  when needed (fork drift is accepted).
- `build/`, `data/` — gitignored: build outputs, extracted WoW data, DB dumps.

## Environment
- Claude Code runs inside an Ubuntu 26.04 distrobox (see mcwow's CLAUDE.md "Environment"):
  `apt` with passwordless sudo, OpenJDK 25, `/dev/shm` shared with the host, `DISPLAY=:1`.
  Host hardware: `CLAUDE.local.md`.
- Everything runs inside the distrobox (no Docker). Installed 2026-10-02: rustup (`~/.cargo`;
  benilla pins its toolchain in `rust-toolchain.toml`), build-essential, cmake, clang, pkg-config,
  ALSA/udev/OpenSSL/zlib dev packages, MariaDB 11.8 server + `libmariadb-dev`.
- **MariaDB runs on port 3307**, not 3306: mcwow's AzerothCore repack runs a Wine `mysqld.exe` on
  3306 and the distrobox shares the host network. Config: `/etc/mysql/mariadb.conf.d/60-classiccraft.cnf`.
  No systemd in the box — start/stop with `tools/db.sh start|stop|status`. Datadir `/var/lib/mariadb`.
- **Ports** (mcwow's AzerothCore repack holds 3306/3724/8085 on the shared host network, so
  classiccraft uses its own): MariaDB **3307**, realmd **3725**, mangosd **8086**. All bound to
  127.0.0.1. The stock VMaNGOS configs point at 3306 — never use them unedited.
- **benilla runs on the host, not in the distrobox.** Inside the box wgpu's device creation fails
  on the passed-through NVIDIA driver (`RequestDeviceError Device(Lost)`), though `vkcube` works
  with `tools/nvidia-env.sh`. The same binary runs natively on the host via `distrobox-host-exec`
  (verified 2026-10-02: login, char create, world entry). Build in the box, run on the host.
  On Wayland, winit logs "could not set cursor position" — watch mouselook.
- 1.12.1 client (5875 enUS, verified): path in `WOW_CLIENT` (set in the gitignored `tools/local.env`,
  read by `tools/play.sh` / `tools/minecraft.sh`) — **read only, never write into it**. Has a non-stock
  `patch-2.MPQ` (9 MB); first suspect if benilla's visuals look off.

## Build / run
- Player-facing setup (2026-10-04, README.md): `tools/setup.sh` (guided, resumable - steps marked in
  `data/setup/`), `tools/update.sh`, `tools/build.sh [all|server|client|mod]`, `tools/db-setup.sh`
  (create/import/migrations/custom rows/realm - idempotent), `tools/server-config.sh` (confs from
  `.dist`; byte-identical to the hand-made ones), `tools/minecraft-install.sh` (Fabric installer
  `-noprofile` + own launcher profile "classiccraft" with gameDir `<mc>/classiccraft`, Fabric API
  from Modrinth, mod jar, `config/mcwow.json`). All read `tools/config.sh` (defaults: ports
  3307/3725/8086, `CC_DB_MODE=private` = a user-owned MariaDB in `data/mariadb`, socket in
  `$XDG_RUNTIME_DIR`) then `tools/local.env` (gitignored; entries `KEY="${KEY:-value}"` so env wins).
  This machine's choices: `CLAUDE.local.md`. `play.sh` uses `distrobox-host-exec` only inside a container.
- Progression check (after balance changes): `tools/progression.sh` (plan step 15). Design topics
  discussed with the user and their status: `docs/topics.md` - keep it updated.
- Day to day: `tools/db.sh start`, `tools/server.sh start|stop|status|log|cmd "<GM command>"`,
  `WOW_USER=<account> WOW_PASS=<password> tools/play.sh` (this machine's accounts: `CLAUDE.local.md`).
  Unattended login test: `WOW_USER=.. WOW_PASS=.. WOW_CHAR=.. WOW_UNATTENDED=1 WOW_NOSOUND=1
  WOW_PROBE_EXIT_AT=40 tools/play.sh` (switches: benilla `docs/CONTRIBUTING.md`).
- benilla: profiles `dev` (deps opt-3, own crates opt-1), `play` (release + incremental), `ship`
  (fat LTO). Clean `cargo build --profile play -p benilla` = 4m31s. Binary `benilla/target/play/benilla`.
- VMaNGOS: `cmake -S vmangos -B build/vmangos -DCMAKE_BUILD_TYPE=Release -DBUILD_EXTRACTORS=ON
  -DCMAKE_INSTALL_PREFIX=$PWD/build/vmangos-run && cmake --build build/vmangos -j 12 &&
  cmake --install build/vmangos` (clean 1m58s). Live configs: `build/vmangos-run/etc/{mangosd,realmd}.conf`
  (generated from `.dist` with the ports above, `DataDir`=`data/server`, `LogsDir`=`data/run/logs`).
- Server data: `tools/extract.sh "<client dir>"` -> `data/server/{5875/dbc,maps,vmaps,mmaps}`
  (~15 min total incl. mmaps on 20 threads). mangosd wants DBCs under `<DataDir>/5875/dbc`.
- World DB: VMaNGOS `db_latest` release (`db-4641790.zip`, in `data/db/`), imported into
  `mangos`/`characters`/`realmd`/`logs`, user `mangos`/`mangos`. Matches our source commit, no
  migrations needed. Realm `classiccraft` id 1. Logs: `build/logs/` (builds), `data/run/` (servers).

## Plan
1. ~~Prerequisites~~ — done 2026-10-02.
2. ~~VMaNGOS built natively, data extracted, DB imported, account created~~ — done 2026-10-02.
3. ~~Stock benilla builds, logs in, plays~~ — done 2026-10-02 (runs on the host, see Environment).
4. **Phase 1 in the fork** — DONE, user-confirmed live 2026-10-02 ("flawless"). Pieces:
   - `benilla/crates/benilla-app/src/player/external.rs` + 5 marked edits in `player/controller.rs`
     (`// classiccraft:`): an `ExternalDrive` pose replaces the keyboard axes, `mover::step` and the
     camera seat; flags/gait/`movement_net` run unchanged, so the server gets real `MSG_MOVE_*`
     (start/stop, jump, fall-land, heartbeat). A server move between driven frames yields the
     body and bumps `ExternalDrive::server_moves`. `SelfReport` publishes the body after `control`.
   - `benilla/crates/classiccraft/` (binary `classiccraft`, `run_with`): `shm.rs` (protocol v3
     mirror), `bridge.rs` (placement handshake on world entry / map change / server move /
     Numpad+, ground 5x5 grid via `WorldCollision::ray_body`, Minecraft FOV onto `WorldCamera`),
     `input.rs` (benilla window keeps focus; keys -> SDL scancodes, buttons, raw motion, wheel into
     the overlay file's input ring; benilla's own input cleared; ` = WoW UI mode, Numpad+ = bridge).
   - `fabric/` = mcwow's mod copied; protocol v3 (`protocol/mcwow_protocol.h`: + onGround, inWater,
     velocity blocks/s, FOV; 260 bytes), retries opening the bridge file, DB defaults -> VMaNGOS.
   - All shm files renamed `/dev/shm/classiccraft_*` so mcwow can run alongside.
   - `geom.rs` replaces geom_server: benilla's own body-filter colliders (`faces_near_body`,
     `FaceProbe::verts` made `pub` in benilla-world) cut into 32 yd cells, 5x5 cells around the
     player, resent on `ColliderEpoch` change when the triangles differ, eviction/refresh honoured.
     The mod's collider uses ONLY these triangles (the 5x5 ground grid is published but unused).
     First live run: 45 cells, ~31k triangles near Northshire.
   - The mod ignores WoW data whose heartbeat stopped (stale file once dropped Steve into the void).
5. **Overlay + world rendering** — DONE, user-confirmed 2026-10-02:
   - `classiccraft/src/overlay.rs`: MC hand/HUD/screens from the overlay triple buffer as a
     full-window `ImageNode` (GlobalZIndex 850), `Rgba8UnormSrgb` (benilla's Bevy-UI shader decodes
     then re-encodes), un-premultiplied. While a MC screen is open the cursor is freed and
     `IN_CURSOR` sent in MC framebuffer px; key auto-repeat is dropped; typed text from `ev.text`.
   - `classiccraft/src/render.rs`: reads `classiccraft_render_v1.shm` (MC stops drawing its level
     while we read): atlas/sections/entity textures/scene+avatar meshes drawn with benilla's own
     `WowModelMaterial` via `model_material` (WoW sun/ambient/fog, depth vs WoW), one-sided (MC
     sends both faces of plants; two-sided z-fought). MC lights -> `WorldPointLight`s (light WoW too);
     fork shader bit `clutter_fade.z` 14 anchors point-light choice per vertex for MC meshes.
   - Fork hooks added since Phase 1: `in_world` waits for the loading cover (placement over
     unloaded terrain dropped Steve under the map); placement seq seeded per session; undriven
     frames reset `applied`; under a server ride (taxi) the camera rides the body with MC's look;
     FALLING_FAR + fall clock >1229 ms stripped from the wire while MC drives (no WoW fall damage);
     WoW body always hidden while MC drives (Steve is the avatar). Mod: fall distance reset on
     placement.
   - Parked (user, 2026-10-02): death sync (WoW death doesn't kill Steve; revisit with combat),
     WoW water vs MC (built and parked, see step 10), Steve invisible during taxi rides.
   - `GM.CheatGod = 1`: GM characters are immortal at login; `.cheat god off` to test death.
   - Run MC from the user's own terminal (`tools/minecraft.sh`): background tasks here get killed.
   - Run: `tools/minecraft.sh` (load a Void world) + `tools/play.sh`; dump: `tools/shmdump.py`.
6. **Combat crossover** — DONE, user-confirmed 2026-10-02 (Steve vs WoW creatures, MC mobs vs
   WoW creatures incl. friendly NPCs, tamed wolves). Design: Minecraft owns the player's and MC
   mobs' health, the server owns creatures'.
   - VMaNGOS fork: `src/game/ClassicCraft.h` + `Handlers/ClassicCraftHandler.cpp`; opcodes 828-832
     (`CMSG_CC_HELLO/HIT/ACTORS/DIED`, `SMSG_CC_DAMAGE`); hooks marked `classiccraft` in
     `Unit::DealDamage` (bridged player / proxy victims: hit forwarded, health untouched),
     `WorldObject::GetReactionTo` (proxy hostility), `Player::RemoveFromWorld` + `LogoutPlayer`
     (proxy cleanup). Proxies = creature_template 990001 hostile / 990002 passive / 990003
     companion (`sql/custom/classiccraft_proxies.sql`, invisible display 11686, NullAI).
   - benilla fork: `benilla-protocol` opcode consts + `ServerPacket/SessionEvent::ClassicCraft` +
     `WorldWriter::send_classiccraft`; `external.rs` `CustomPacketOut/In` messages and
     `NearbyUnits` (creatures with `CanAttack`). classiccraft crate `combat.rs` routes it all.
   - shm: actors file v2 (`classiccraft_actors_v1.shm`, + damage ring); render ring `REN_HIT` 24 B
     with attacker id, new `REN_MOBS` (12). Mod: stand-ins for every creature (`attackable` gates
     the player's own hits), per-attacker hits, `applyWowDamage`, mob export 5 Hz; tuning
     `combatgain=` / `takengain=` in `/dev/shm/classiccraft_combat`. Damage scale: 20 MC damage =
     a typical creature's health at the relevant level (player hits: player level).
   - Death: MC death -> `CMSG_CC_DIED` (server lifts god mode for the kill); MC respawn -> repop
     (replaced by 13w: resurrect at bed/hearth).
     User-confirmed live 2026-10-02.
   - Pet follow-teleports snap to WoW ground and clear fall distance (`TamableTeleportMixin`).
   - Since: ghost mode (WoW dead/ghost -> Steve invulnerable, invisible, adventure if survival,
     melee cancelled `PlayerGhostMixin`; benilla sends no hits/proxies, HELLO off); companion
     proxies react as their owner (`ReactionOverride`, so WoW mobs can target the player's wolf);
     stand-in table survives a raced read; bridged players get no WoW auto-attack
     (`Unit::DealDamage`); knockback applied on shield blocks too (vanilla: a full block gives the
     blocker none, only the attacker - user declined pushing WoW creatures back);
     `takengain` default 2.5 (user's choice).
7. **Combat follow-ups** - all user-confirmed live 2026-10-02:
   - Shield durability on blocked WoW hits works (hits >= 3.0 wear it - vanilla).
   - No WoW auto-attack for bridged players; death -> ghost run and ghost mode work.
   - Companion proxies (tamed wolves): `UNIT_FLAG_PLAYER_CONTROLLED` like a hunter pet, so NEUTRAL
     creatures turn on them too (creature-vs-creature needs one side hostile). All proxies use
     `ClassicCraftProxyAI` (never moves; leaves combat in place - NullAI evaded and walked off) and
     any movement generator is cleared on each CMSG_CC_ACTORS update. Despawned with `UnSummon`
     (ForcedDespawn only killed the summon, which lingered and respawned). mangosd logs
     `[classiccraft] proxy ... spawned/despawn`.
   - Proxies hidden from the player: benilla ORs UNIT_FLAG_NOT_SELECTABLE into incoming fields of
     the creature entries the classiccraft crate registers (`external::set_unselectable_entries` /
     `mask_unselectable`, hooked in `net.rs merge_store_fields` and the create path in
     `net/objects.rs`) - no nameplate/target; the server keeps them selectable.
   - MC mob pathing: `GroundPathNavigationMixin` snaps entity path targets onto WoW ground (vanilla
     `findSurfacePosition` sent them to the build limit, y=1536 - mobs stood still at slopes);
     `LeapAtTargetGoalMixin` turns a leaper to its target (wolves leapt backwards).
8. **Breakable terrain** (decisions: project memory `terrain-voxel-design`) - Phases 1-3 DONE and
   user-confirmed 2026-10-02 (Phase 4, no digging in cities, not started).
   - **Fill (Phase 1).** benilla crate `terrain.rs` sends per MC chunk within 6 of the body, per
     block column: lowest WoW surface (3x3 samples; a no-ground sample is retried 0.01 yd to each
     side - MCNK-edge rounding), a fill ceiling lowered under collision faces > 0.75 yd below the
     terrain (cellars, mines), the dominant ground texture's material, the AreaTable id - over the
     geom ring as `MSG_TERRAIN` (4) once tiles are resident 3 s and colliders settled 1 s
     (`TerrainStreamer::tile_handle` added to benilla). Mod `McwowTerrainStore` + `McwowTerrainFill`
     fill loaded chunks nearest-first (15 ms/tick, max 4 chunks/tick) from 0.05 under the surface
     down 48 blocks (surface layers by material, stone, deepslate from 24, hashed ore veins,
     simplex caves - tunnels ~6%, caverns ~2.7%, 6-block crust, 2 over bedrock), bedrock below;
     never replace blocks. setBlock WITHOUT client updates (1.25M per-block updates froze the MC
     client); each chunk is resent whole after its light settles, plus its 8 neighbours' light
     (else caves kept stale daylight on the client). Chunk attachments: `mcwow:wow_ground_tops`
     (int[256] top block per column, NO_TOP = none; persistent, synced to clients - THE truth for
     a column's top; never recompute it) and `mcwow:wow_ground_filled`. Filled chunks get a real
     biome (snowy_plains/desert/plains - the void biome has no spawns). Once per chunk per session
     `repair` fills NO_TOP columns that have terrain data and clears weather snow layers.
   - **Digging (Phase 2).** `McwowColumns` (main, client+server): a column is OPEN when its top block
     isn't natural ground (dirt/sand/stone tags, grass, gravel, snow block, cobblestone...) or
     something with collision is in the gap cell above it (a stair one up); ground put back
     closes it, built blocks keep it open. Mod `McwowHoleExporter` sends `REN_HOLES` (13: i32 cx, cz
     + 8 u32 mask) per chunk on change; `McwowTargeting` aims an upward WoW-ground hit at the real
     block <= 3 below. benilla fork module `benilla-world/src/terrain_holes.rs` (`TerrainHoles`
     32x32-chunk window, storage buffer binding 120 on `TerrainExtension`, `terrain.wgsl`
     `in_open_column` discard) + crate `holes.rs` (masks, window); crate `geom.rs` clips open
     columns out of terrain faces (all corners on the heightfield, checked 1% toward the centroid)
     and resends cells over changed chunks. Hole walls ("skirts", SkyCraft's dig-hole walls) in
     `McwowWorldExporter.addSkirts`: per closed column next to an open one, a strip from its top
     block up to the WoW ground (`McwowTriHeight.lowestSurface`, 8 segments per edge, sampled
     0.002 inside the closed cell, <= 8 blocks, 1-block bands: side texture on top, body texture
     below); re-meshed when columns open/close and whenever a geom cell arrives while holes exist.
     A closed column's top block draws its sides in its body texture (`fillSprite`, tinted overlay
     dropped; `MeshBuilder.buriedSides`): a grass block showed a second fringe a block under the
     wall's (2026-10-02, Duskwood).
     `McwowColumns.isNaturalGround` includes `BlockTags.MUD`: in 26.3 mud left the DIRT tag, so every
     Soggy-ground (MUD) column read as dug and WoW's terrain was cut over whole areas (Tirisfal).
     `BlockCollisionsMixin`: smooth-collider players ignore a closed column's top block while their
     feet are within 0.75 of its top (it was an invisible one-way wall on slopes).
     WoW ground clutter (grass tufts) over a dug column is not built (user, 2026-10-02: it floated
     over holes): fork `terrain_holes::TerrainHoles::is_open_wow` (CPU twin of the shader test) +
     `changed` chunks per rebuild; `clutter.rs` skips those tufts and re-cuts only the clutter chunks
     over changed columns. Placed doodads (trees, bushes, rocks) still float.
   - **Underground (Phase 3).** VMaNGOS `MovementHandler.cpp`: both undermap rescues skip bridged
     players. MC mobs > 1 block under their column's top get no WoW proxy. `NaturalSpawnerMixin`:
     spawn tries in filled chunks pick a random column's ground (bedrock+1..top), never the WoW
     surface (user: MC mobs spawn underground only). Every spawn position is checked too
     (`isValidSpawnPostitionForType`: feet at or under the column's top block, 2026-10-04 - pack members
     spread sideways into the gap over a lower neighbour's top and the buried rescue lifted them out). `ServerLevelPrecipitationMixin`: no MC snow or
     ice in WoW dimensions (snow layers poked through WoW's ground). benilla: `render.rs` sends MC
     sky light in `uv_b.y`; `wow_model.wgsl` (fork, bit-14 MC meshes) lights MC blocks as
     `tint * (WoW sun/ambient + WoW point lights) * sky + MC torch light`; fork
     `terrain_holes::CameraUnderground` (0..1 over 2 yd under the heightfield, set by crate
     `holes.rs`) fades the storm fog (`lighting/resolve.rs`) and gates precipitation as indoors.
9. **Performance** (2026-10-02; user: "just about right"). benilla is main-thread bound (GPU ~35%).
   Fixed: overlay frames go shm -> GPU texture on the render thread, changed bands only
   (`overlay.rs upload_overlay`; was 4 full-frame copies on the main thread); section entities get
   a one-off `Aabb` + `NoAutoAabb` (Bevy re-ran `compute_aabb` on every section each frame - 32% of
   the main thread at ~3,900 sections); the mod drops sections/lights of chunks MC unloaded
   (`McwowWorldExporter.dropUnloaded`; WoW kept every section ever passed - 4,350 after a short
   flight), so MC blocks show only within MC's render distance; buried mobs (> 1 under their
   column top) not exported unless the camera is underground or a dug column is within 1 block.
   Measure: `perf record -t <main tid>` on the host (`distrobox-host-exec`), `--call-graph lbr`.
   Buried sections cost only ~0.3 ms (hidden vs shown A/B). Vsync caps at the monitor's rate
   (this machine's monitors: `CLAUDE.local.md`); `WOW_NOVSYNC=1` for uncapped runs. Open: a WoW area sometimes
   stays unloaded until walked into, MC chunks show there (not yet diagnosed).
   Parked (user, 2026-10-02, "super niche"): with the camera below WoW's ground, a jagged
   head-and-shoulders shape centred on screen hides Minecraft water (translucent) behind it; it
   stays centred when turning, shows in third person and WoW UI mode, never above ground. Not the
   WoW body (the first-person fade already hides its parts, `player/camera.rs`; hiding the unit
   too changed nothing) - suspect a camera-attached benilla pass gated by being under the ground
   (`CameraUnderground` / storm fog / weather) writing depth.
10. **Outdoor water** (2026-10-02) - built, then PARKED by the user as a finishing touch:
   `McwowTerrainFill.WATER_ENABLED = false`; while off each chunk's placed water is removed once per
   session with its data, so Steve walks through WoW water again. What exists: benilla `terrain.rs`
   sends per column the MCLQ surface + kind (16-byte columns; fork: `WaterChunkInfo::surface_z_at`
   made `pub`); the fill puts water sources (`UPDATE_SKIP_ON_PLACE`, no fluid ticks) from over the
   top block to the block holding WoW's surface; attachments `mcwow:wow_water_tops` (top block) and
   `mcwow:wow_water_surface` (exact, 1/1024); `FluidHeightMixin` gives the top block WoW's exact
   height; `FlowingFluidMixin` keeps WoW water from spreading sideways out of its volume;
   `drainStrays` removes spills; the exporter skips WoW-water blocks (WoW draws its own) and
   re-meshes when the attachment arrives late; `AbstractBoatMixin` surfaces a submerged boat and
   floats it on WoW's exact surface. Verified live: swimming, rivers contained, boats float with no
   bob. Open when resumed: WoW's water shows through a boat's hull (vanilla hides it with the
   boat's `water_mask`; plan: export it, draw depth-only in benilla before WoW water - user agreed
   to this over hiding WoW water); magma -> lava (user: later); no water mobs (user).
   Kept on: boats on WoW land (`BoatItemMixin` lifts placement onto WoW ground; boats step 1/3
   block; ground friction from the column's top block) - user picked "vanilla-like on land".
11. **Leveling** (2026-10-02; design in project memory `game-design`): the WoW server keeps the
   character's level/XP; kill XP waits in Minecraft XP orbs. VMaNGOS: `Rate.XP.*` = 2 in the live
   `mangosd.conf` (3 until 2026-10-04; user: 2x so each zone gets explored); `Player::GiveXP` hook `ClassicCraft::HoldKillXP` holds a bridged player's kill
   XP in a per-player ledger and sends `SMSG_CC_XP_DROP` (833: id, victim guid, corpse xyz, XP,
   level, rank; none for 0 XP); `CMSG_CC_XP_CLAIM` (834: id, XP) grants it through `GiveXP` as
   kill XP (`ClaimedKill` guid for the rested bonus and XP message; `SendLogXPGain` takes a guid
   now); logs `[classiccraft] ... held as drop` / `claimed`. benilla: drop -> actors damage ring
   kind 0x10; header 48/52 = PLAYER_XP / NEXT_LEVEL_XP; `REN_XP_CLAIM` (14) -> claim. Mod:
   `McwowXp` drops 3 orbs (5 elite+) sharing the WoW XP (+1-2 vanilla XP each), payload in a
   session-only attachment; `ExperienceOrbMixin` (no merging, claim on pickup, step 0.6 on WoW
   ground). Minecraft's own XP bar IS the WoW level (user): `McwowXp.mirrorLevel` sets level and
   progress every server tick; `PlayerXpMixin` cancels vanilla XP gains and death XP in WoW
   dimensions (enchanting tables are gated by the level and can't lower it; anvils cost no levels (26); enchanting is
   to become books-only eventually). Verified live: Mangy Wolf 225 XP in 3
   orbs, claimed, harming-arrow kills too. Exporter fixes found on the way: `submitCustomGeometry`
   (XP orbs), translucent render types blended, no-cull types two-sided (elytra), WoW stand-ins
   skipped in the 48-entity scene cap (snowballs vanished in Goldshire).
   Quest XP still granted directly (until a Minecraft-side quest UI exists).
12. **Polish batch** (2026-10-02, after leveling; all user-verified unless marked OPEN):
   - Entity export (`McwowEntityExporter`): stand-ins skipped; nearest 96 entities by distance
     (stuck arrows filled the old 48 cap); `submitCustomGeometry` exported (XP orbs); translucent
     render types blended; no-cull types two-sided (batch header flag 2 -> benilla material).
     Clear pixels of MC meshes are discarded in `wow_model.wgsl` (bit 14, alpha < 0.01) instead
     of turning off depth writes (that drew Steve's head behind his body).
   - Animated atlas frames go straight to the GPU texture from the render world (`render.rs
     upload_atlas_regions`; editing the asset re-created the texture, water animated every ~3 s).
   - Placed water squeezed over WoW ground per vertex (`McwowWorldExporter.squeeze`).
   - No MC weather in WoW dims (`LevelWeatherMixin`). XP orbs step 0.6 (`ExperienceOrbMixin`).
   - Minecraft's XP bar = WoW level (see Leveling); purple HUD bar removed.
   - Sounds: benilla `sound/combat.rs` mutes the player's exertion, whoosh, weapon impact/clang
     and own injury vocal while MC drives; `sound/footsteps.rs` mutes our own WoW steps.
   - WoW frames hidden in Minecraft mode (crate `input.rs hide_wow_frames`, Lua reparent under
     `CC_Hider`: MainMenuBar, MultiBars, pet/stance bars, PlayerFrame, TargetFrame; back in WoW UI
     mode; re-applied ~1/s).
   - Hand lighting: benilla `combat.rs HandLight` packs WoW ambient (+3 nearest WoW point lights)
     and sun colour RGB8 into actors header 28/32, sun direction oct-encoded in their high bytes,
     60 = "LIGH"; mod `LightmapMixin` feeds MC's lightmap, `HandSunMixin` (+`LightingAccessor`)
     points the LEVEL light at WoW's sun before `renderItemInHand`. Known gap: indoors MC still
     sees sky (WoW roofs aren't blocks) - interior ambient not used yet.
   - Footsteps (player): benilla `external.rs publish_ground_terrain` -> `SelfReport.ground_terrain`
     (TerrainType, `Subject::Unit(self)` so the WMO room claim counts) -> shm difficulty word bits
     8-15 (+1) -> mod `McwowFootsteps` (steps at vanilla cadence, takeoff/landing step; map Dirt
     gravel, Metallic metal, Stone, Snow, Wood, Grass/Leaves/DustyGrass grass, Sand, Soggy mud,
     None silent, 11 = wool) + `EntityStepSoundMixin` (vanilla step cancelled on WoW ground).
     Floors whose MOMT groundType is None (10) fall back to the floor texture name (fork:
     `WmoModel.material_texture`, `surface_texture_sample`, `WorldPoint::floor_texture`,
     `terrain_of_texture` word list) - OPEN: untested live after the fallback landed.
   - Mob footsteps: `EntityOnPosMixin` points `getOnPosLegacy` and `getOnPos()` (the step's own
     gate in `applyMovementEmissionAndPlaySound`) at the column's hidden top block when the entity
     is onGround over air (REACH 6); a column without ground (WoW's terrain hole under a building)
     takes the nearest column's top (<= 8 away). Ridden horses: `RiddenVehicleGroundMixin` - the
     server replays a client-driven vehicle's move without gravity and vanilla re-tests onGround
     only for vertical motion, finding nothing below, so on WoW slopes it read airborne; there,
     ground within 0.2 under the box = onGround. User-confirmed live 2026-10-02.
   - Objects outdoors (user picked "find the object"): fork module `benilla-world/src/object_surface.rs`
     - colliders carry `SurfaceSource` (welds: each hull's world AABB + model path; WMO walk
     colliders: handle + transform); `ObjectUnderfoot::object_under` down-rays the body world
     (0.5 over the feet to 0.6 under) and names the hull holding the hit (smallest AABB) or the
     WMO walking face and its MOPY material (fork: `WmoModel.group_collision_materials`,
     `benilla_formats::wmo_group_collision_materials`). `external.rs terrain_of_object`, only with
     no room claim: WMO by its face's texture (`terrain_of_texture`, stone without a word; a
     collision-only face - stair ramps - takes the same group's nearest visible face 0.8 under to
     0.3 over), else ground type (not 0/None), else stone - NEVER the WMO file name ("snow_inn"
     made the Kharanos inn's steps snow); doodad by FILE name (folders name zones: Duskwood,
     Ironforge), stone words first, then wooden things, else wood. User-confirmed live 2026-10-02.
   - Clipping (2026-10-02). `McwowTriCollider`: moves over 0.3 blocks resolve
     in 0.3 pieces (vertical pass along the path, not just its end); airborne walkable ground is
     caught up to the step height (was 0.3, while slopes only became walls at 0.6 - a slope in
     between was neither: elytra dives clipped); grounded steep faces count as walls from
     min(step, half the body) (a 0.6-tall glider/crawler had NO walls when grounded). Offline
     harness (scratch, not in repo): old code ended 77-92% of elytra dives under a slope and
     leaked through walls; new 0/2340 and 0/702, walking up/down slopes and stairs unchanged.
     Rescue from the gap (`McwowColumns.buriedSurface`: closed column, feet within top-0.05..top+1.05,
     no WoW surface at the feet, WoW surface 0.3..4 over them): `BuriedMobRescueMixin` (server,
     every 10 ticks per mob) and client `McwowBuriedRescue` (player, every 2 ticks) set the entity
     on that surface (mobs one 1/8 sub-voxel cell higher: set inside a cell they fell back through
     it - a horse re-buried twice a second for 7 s); both log `buried at`. Dismounts land on the hidden top block (vanilla's
     dismount search sees real blocks only) and are fixed by the rescue. User-confirmed live 2026-10-02.
   - Parked: invisible silhouette over below-ground water (see Performance),
     small doodads (skulls) floating over holes (user: leave as is).
13a. **Transports** (2026-10-02; user: walk around on decks as in WoW, companions left behind, no
   building aboard; Deeprun Tram first). Deeprun Tram user-confirmed live 2026-10-02 ("can't find
   any issues"); lifts user-confirmed too. Next: boats/zeppelins (continent crossing:
   the server worldports the rider; Steve must land in the new dimension still aboard).
   - Crossings: user-confirmed live 2026-10-03 (zeppelin both ways, boats Teldrassil-Darkshore
     and Darkshore-Wetlands). benilla already spares the ridden transport at a
     worldport, re-anchors its clock to the destination map and keeps its own body on the deck
     while the bridge is Placing. Mod: `McwowDeckRide` stays aboard while the deck is out of sight
     (map change CLEAR, no export through the loading screen) for <= 20 s, freezing Steve where he
     stood until a placement takes over (no deck = a fall to the sea floor); no carry on the tick
     it returns or across a > 32-block jump; no attach decisions while lost or held.
     `McwowWorldPlacement` aboard: the hold follows WoW's live position (it rides the deck) and
     ends when the deck is in (`McwowDecks.has`), not on static geometry; logs `done aboard deck`.
     Routes: MenethilHarbor <-> Theramore (boat), DurotarZeppelin <-> TirisfalGladesZeppelin.
     First zeppelin crossing: placed on the right zeppelin, but no control and a fall - the deck
     carried Steve off the placement spot before the 2-block arrival check (never acked; Placing
     forever), and the ground hold ran after the deck hold in the same tick and released him at
     the stale spot. Now: aboard, arrival = target dimension within 8 blocks of WoW's live pose;
     the ground hold is the else-branch. Mod leaves a deck only after 3 ticks on other ground
     (single-tick blips mid-flight).
     Second zeppelin crossing (Tirisfal -> Durotar): same symptom - `McwowDeckRide.endTick` cleared
     the ride silently whenever the player's level wasn't the active WoW dimension, which is true
     mid-crossing (the placement switches `activeDimension` before the teleport lands); the hold
     then took the ground branch. A ride now ends only by its rules (3 ticks on other ground,
     water, deck absent 20 s).
   - Elytra over the Teldrassil-Darkshore sea outran terrain streaming: benilla's backstop cover
     ("focus not resident") made `SelfReport.in_world` false, benilla's mover dropped the body,
     and the cover's end placed Steve there ("entered the world"); the ground hold found nothing
     over deep sea (5 s). Fork: `LoadingScreen::streaming_only` (set by the backstop, cleared by
     any raise, blackout or snap); `publish_report` stays in world through such a cover. The
     cover itself still shows briefly.
   - Before: the collision export froze transports into the static cells at their spawn spots
     (invisible collidable trams; the moving ones were walked through) and the rider attach never
     ran (`external::step` reported no ground).
   - benilla: `external::DeckTransport { guid }` inserted at `transport.rs arm_transports`;
     `external::step` reports `ExternalPose::deck` (set by the crate's `compose_rider` from the
     mod's rider block) as `ground`, so `ride::update_attachment` boards the deck the MOD says and
     the wire carries ON_TRANSPORT (a down-ray missed the moving car - avian's colliders trail it a
     frame - and benilla flickered board/deboard the whole ride).
     Crate `decks.rs`: deck faces left out of geom cells (`OnDeck`); each deck within 250 yd sent as
     `MSG_DECK` (5) in its own frame (collider `shape()` through the model's Transform chain - avian's
     ColliderTransform drifted a frame's travel and re-sent each car every frame), `MSG_DECK_GONE`
     (6); a seqlocked pose table every frame; `compose_rider` turns the mod's rider block
     (deck-relative eye/feet/yaw) into the world pose through benilla's LIVE deck pose. Geom
     protocol v3 (`protocol/mcwow_geom_protocol.h`). Tram cars: 550 tris, local [-4.5,-11.5,-6.3]..
     [4.5,0.4,6.3] blocks (origin near the roof).
   - Mod: `McwowDecks` (deck store, poses per tick, `near` joins `McwowTriCollider.trianglesNear` -
     player only), `McwowDeckRide` (START tick: poses + carry incl. yaw; END tick: board when
     standing on a deck, leave on other ground / water, stay aboard in the air; per frame rider
     block, against this tick's deck pose), `UseBlockCallback` FAILs block items while aboard.
     Logs: `board deck` / `deboard`, `deck <guid> (<n> triangles)`.
   - First live try: boarded fine; once the car moved, benilla logged "placement (the server
     moved us)" ~20/s - its own `ride::carry` moved the body before `external::take_pose`'s
     server-move check. Fixed in `controller.rs` (the carry moves `ExternalDrive::applied` along).
     Board/deboard flickered stepping on: the mod keeps a ride while a deck is within 0.35.
   - Second try: no fling, camera fine; mod stayed aboard, benilla flickered (fixed as above).
   - Third try: benilla stayed aboard, but the view sawtoothed at 20 Hz: the rider block measured
     Steve against the deck LERPED between ticks, yet the carry runs before `commonTick`'s
     `setOldPosAndRot`, so xo is already in this tick's deck frame. Now measured against this
     tick's pose only (`McwowDeckRide.publish`).
   - Fourth try: less flicker, steps played the whole ride. `McwowFootsteps` subtracts the tick's
     carry (`McwowDeckRide.carried()`); benilla's transport chain now runs before
     `ExternalDriveSet::Supply` (unordered, `compose_rider` could use last frame's deck pose).
   - Fifth try: first person perfect, no step spam; Steve flickered in third person (his mesh at
     Minecraft's world pose). `decks::RiderShift` (composed feet - Minecraft's) moves the avatar
     parts each frame (`render.rs shift_avatar`). Other MC entities on a deck would need the same.
13b. **todo.txt round** (2026-10-03; OPEN: test live).
   - Footsteps aboard: benilla `publish_ground_terrain` takes the ridden transport's model name
     (`WorldObject.label` of its mesh child) through `terrain_of_doodad` (+ subway/tram and
     non-Thunder-Bluff elevator = metal, zeppelin/transport = wood); logs `footsteps aboard <model>`.
   - Spyglass: bridge FOV filter 1..170 (was 10..170; the spyglass is ~7°).
   - Boat stairs: `McwowTriCollider.WALKABLE_NY` = cos 50° (WoW's limit; was SkyCraft's 0.7 ~45.6°).
   - Jumping off a deck: `McwowDeckRide` lets go after 10 airborne ticks with no deck within 4
     blocks below (a jump on deck stays aboard). benilla follows: rider block +44 flags (bit 0 =
     pose present; guid without it = aboard, deck out of sight); `ExternalPose::deck_known` +
     `deck: None` ends benilla's ride in the air too (it rode on alone, and the server carried
     Steve from Wetlands to Darkshore with the boat he flew behind).
   - Random missing blocks: no lead yet (needs a screenshot + location).
13c. **GM commands from Minecraft chat** (2026-10-03; OPEN: test). Mod `McwowGmChat`: Fabric
   `ALLOW_CHAT` swallows lines starting with "." (echoed grey), queued and flushed by
   `McwowWorldExporter` as `REN_CHAT` (15); crate `combat.rs` `McMsg::Chat` -> benilla
   `external::ChatOut` -> `ClientCommand::Chat` Say (as WoW's edit box sends `.` lines). Replies:
   benilla `ChatIn` (CHAT_MSG_SYSTEM, not addon) -> crate `relay_chat` -> actors text ring (protocol
   v3, 64 x 256 B) -> `McwowActors.drainText` -> Minecraft chat, yellow, |c/|r/|H codes stripped.
   User-confirmed live. WoW's chat panel (ChatFrame1-7, tabs, menu button, edit box) is in
   `input.rs HIDDEN_FRAMES` (hidden in Minecraft mode).
13d. **/dance** (2026-10-03; user-confirmed live: "flawless"). Steve himself dances WoW's race dances (user turned
   down a WoW body standing in). benilla crate `dances.rs`: on start (thread) writes missing
   `~/.local/share/classiccraft/dances/<race>_<sex>.ccd` from the user's install: each character
   model's anim 69 posed like benilla's rig (joint at pivot, offset from parent pivot, keyed T/R/S),
   measured (torso frame from arm key bones 0/1 + hips; hands = attach 1/2; feet = lowest bone per
   side UNDER the waist key bone 5 - the models keep parentless floor bones; head key 6) and turned
   into Steve's parts at 30 fps relative to Stand (root offset/turn about the hip, head, arms
   shoulder->hand, legs hip->foot; no elbows/knees). Also `dances/self` ("race sex", SelfReport
   `race_sex`). Offline check: `target/play/cc_dances <dir>` (prints, needs $WOW_DATA) - all 16
   extract. Mod: `McwowDance` client command `/dance [race] [m|f]`, `/dance stop` ->
   `McwowDanceNet.Dance` payload (C2S, the server relays to trackers + the dancer: co-op ready);
   `PlayerModelDanceMixin` poses `PlayerModel` from the clip by `AvatarRenderState.id`/`ageInTicks`;
   walking > 0.02/tick (deck carry excluded), leaving the ground or dying ends it.
   File v2: every variation of anim 69 (2-6 per race: weight `frequency`, min/max replay); the
   client picks by weight, plays min + rand(max - min) times (>= 1), picks again, seeded by the
   server (payload `seed`) so viewers agree. Elytra (`ElytraModelDanceMixin`, root turn) and armor
   (`HumanoidModelDanceMixin`, full pose, non-PlayerModel) follow; the cape model is a PlayerModel.
13e. **WoW music discs** (2026-10-03; OPEN: test). Four discs (user's pick): Tavern (Alliance)
   `TavernAlliance01`, Sacred `Sacred01`, Main Theme `wow_main_theme`, Thunder Bluff `Thunderbluff
   Walking 03`. benilla crate `music.rs` extracts the MP3s once to `~/.local/share/classiccraft/
   music/<key>.mp3`; mod `McwowMusicFiles` converts each to mono Ogg with ffmpeg (needs ffmpeg in
   the box); `McwowMusic` registers sound events + items (`mcwow:music_disc_<key>`, creative Tools &
   Utilities); data/mcwow/jukebox_song, assets/mcwow/{sounds.json, items, lang}; silent placeholder
   Oggs (ours) let the sounds register and `SoundBufferLibraryMixin` streams the real file. Offline
   helpers: `cc_list <words>` lists client files, `OUT=<dir> cc_extract <words>` copies them out.
   The full "A Call to Arms" (asked for first) is not in the 1.12 data.
13f. **NPC chatter in Minecraft chat** (2026-10-03; user-confirmed live). benilla `ui_chat/feed.rs` hands
   each NPC line, as shown (macros expanded, languages garbled, filtered), to fork
   `external::npc_line`: monster say/yell/emote/whisper + raid boss emote/whisper, formatted the WoW
   way (emote `%s` = speaker) with Minecraft § colours (say white, yell red, emote gold, whisper
   pink) -> `ChatIn` -> actors text ring (v4: 1 KiB slots) -> `McwowGmChat` keeps the colour.
   Next (user): a general way to interact with WoW NPCs and objects from Minecraft mode - the
   foundation for quests, vendors, gossip/dialogue, trainers-as-needed, and the side uses (sit on
   chairs, read plaques/books). Build it as that foundation, not a one-off.
13g. **Interacting with WoW NPCs and objects from Minecraft** (2026-10-03; books/plaques and range
   hint user-confirmed). The
   foundation for quests/vendors/gossip/objects. benilla fork: `external::set_crosshair` (crate
   `input.rs`: driving && !WoW UI) makes the world picks (`target/hover.rs`) aim at the screen centre
   (`external::pick_point`), mouse-look or not; new `target/crosshair.rs` (chained after
   `classify_cursor`) publishes `external::CrosshairTarget` (guid, WoW cursor kind, name, distance,
   unable) and on `CrosshairUse` latches `PressPick` and writes `WorldRightClick`, so WoW's own
   right-click runs (gossip, quests, vendor, GO use, range refusals). Books/plaques: `ui_item_text.rs
   forward_book` sends the whole page chain (`$` expanded) as `external::BookOut` and closes WoW's
   reader (letters stay WoW's). Crate: focus block in the actors file (v5), REN_INTERACT (17) ->
   `CrosshairUse`, BookOut -> geom MSG_BOOK (7). Mod `McwowInteract`: actionbar hint ("Right-click:
   Talk to X", gray "(too far)"), `MinecraftUseMixin` (startUseItem) takes the click when the WoW
   target is nearer than Minecraft's hit (stand-ins count as WoW), once per press; books open in
   `BookViewScreen` (title bold, pages cut ~230 chars). Gossip/quest/vendor windows still open as
   WoW frames (WoW UI mode to use them) until their Minecraft screens exist.
   Chairs (OPEN: test): using one makes the server seat the WoW body (moves it on, stand state 4/5/6);
   `SelfReport::stand_state` -> actors self block (v6) -> `McwowDance.sitting`: Steve sits (vanilla
   riding legs, arms forward, root sunk 12 px), armor/elytra too; `CameraSitMixin` sinks the eye
   0.75 block. Moving stands WoW up through benilla's posture code.
13h. **Waygates** (2026-10-03; user-confirmed live: Ironforge, Stormwind, Darnassus; friend sharing untested - needs co-op). User: custom portals linking bases across continents,
   Warcraft-lore style (mage portals / Titan waygates: a network with a destination menu), mid-game
   recipe, per player shareable through the WoW FRIEND LIST (a Minecraft friend-list UI later),
   open world only. Mod `McwowWaygates`: block `mcwow:waygate` (lodestone look, light 10, portal
   particles, needs diamond pickaxe), recipe OEO/GAG/OOO (obsidian, ender eye, gold, amethyst);
   network = SavedData `mcwow:waygates` (gates: id, name, dim, pos, arrival yaw, owner WoW guid +
   names, shared; per-player attunements); placement refused outside mcwow:map_0/map_1 within
   12000 blocks of the origin (slot 0); placing names it (NamePrompt/Name payloads, the placer's
   WoW guid from `McwowActors.readMe`), using attunes (if yours or shared) and opens the menu
   (Menu payload), owner toggles Private/WoW friends (SetShared). Client `McwowWaygateClient`:
   travel = REN_WAYGATE (18: map, WoW x/y/z/o 1.5 blocks in front facing away, owner guid) ->
   crate `CMSG_CC_WAYGATE` (835) -> VMaNGOS `HandleCCWaygateOpcode`: both ends continents, other
   owner -> `character_social` friend row (owner, me, flag 1) else notification; TeleportTo; logs
   `[classiccraft] <name>: waygate to map`. Placement/worldport brings Steve.
   Arrival sound (user's pick of 8 candidates; OPEN: test): WoW's `Sound\\Spells\\Teleport.wav`, extracted
   by crate `music.rs` (TRACKS key `waygate_teleport`, `<key>.<ext>`), Ogg'd by `McwowMusicFiles`
   (WAV too), event `mcwow:waygate.teleport` (placeholder Ogg + `SoundBufferLibraryMixin`), played
   as a UI sound by `McwowWaygateClient.placed` when a placement finishes <= 60 s after a travel.
13i. **Mines** (REMOVED 2026-10-04, see 13u; 2026-10-03; entering/exiting and worldgen v3 (tunnels + entrance cavern + shafts to caves) user-confirmed live). Direction (project memory `game-design`): the world-wide
   terrain fill goes (chunk loading costs frames; digging in the WoW world becomes an off-by-default
   toggle - NOT YET DONE), mining moves to separate per-tier dimensions that reset. Mod
   `McwowMines`: dimension `mcwow:mine_copper` (type `mcwow:mine`: ceiling, no skylight, dark,
   y -64..128; noise `mcwow:mine` = vanilla `caves` preset with sea_level -64; biome
   `mcwow:copper_mine`: copper x40 + large x10, coal x30, stone variety, dirt/gravel/sand/clay
   pockets, lava lakes, springs, monster rooms, vanilla cave mobs). Blocks (unbreakable, creative
   Functional Blocks, spruce-door look): `mcwow:copper_mine_entrance` (only works in a WoW map
   dimension) and `mcwow:mine_exit`. Areas: SavedData `mcwow:mines` per tier (index, started,
   built); an entry older than RESET_MILLIS (2 h) moves new entries to area index+1 at x = index *
   4096 (old areas stay on disk - cleanup TODO); arrival room (7x7 cobble floor, spruce frame,
   torches, exit door in the north wall) built once per area at y 40. Shared by everyone.
   Client `McwowMineClient.away()` (in a mine dim and not leaving): `LevelRendererMixin` lets
   Minecraft draw its whole level (no cancel, opaque clear, sky), vanilla lightmap; bridge file
   first-person word bit 1 = away; `McwowWorldPlacement.tick` skips. Exit -> `Leave` payload ->
   away off -> placement ("left the WoW dimension") brings Steve to the WoW body. benilla crate
   `bridge.rs` State::Away (pose ignored, input still flows), CMSG_CC_MINE (836, u8) on entering
   and leaving Away, `place("back from a mine")` on leaving. VMaNGOS `ClassicCraft::SetDownMine`:
   down = CombatStop, hostile refs dropped, IMMUNE_TO_PLAYER|NPC, VISIBILITY_OFF; up restores
   (also on CMSG_CC_DIED). Logs: mod `down the copper mine (area n)`, `climbs out`, `mine reset`;
   mangosd `[classiccraft] <name>: down a mine` / `up from a mine`.
   Worldgen v2 (user: too open; a cavern where everyone funnels in is fine, tunnels beyond):
   noise `mcwow:mine` final_density = max(interpolated min(overworld caves/entrances,
   spaghetti_2d + roughness, noodle), solid floor/roof bands) - solid rock with vanilla's tunnel
   caves, no cheese caverns; biome carvers just `minecraft:cave`. Entry built per area: domed
   cavern (r 16, h 10, cobble-patched floor, 8 spruce pillars with lanterns), exit doorway (3 exit
   blocks, spruce frame) at its heart, 4 winding timbered shafts (5 wide, 4 high, frames every 5,
   torches every 10) dug on until each breaks into a natural cave (8+ air cells of its 5x4 face, then 2 more steps; max 220, logs `mine shaft ... reached a cave after n`; v2 stopped at 56, short of any cave). `LAYOUT` (3) in `Current`: a change starts a fresh area.
   First test: entering worked; the exit put Steve under the map - `away` was cached per client tick,
   so frames between the dimension change and the tick published the mine pose without the bit and
   benilla drove the WoW body to x/y ~0 (mine coordinates). Now `away()` reads the client level live
   and stays on until Steve is out of the mine (`staying()` = away && not leaving gates the
   placement); benilla never drives on the frame it leaves Away (Driving -> Placing after place()).
13j. **Digging toggle, mine cleanup, early reset** (2026-10-03; OPEN: test). Digging in the WoW world
   OFF by default then (user: a toggle for fun only, nothing in progression may use it; ON since 13t):
   `McwowTerrainFill.digging` (SavedData `mcwow:terrain`, loaded at SERVER_STARTED); off, the fill
   tick instead UNFILLS filled chunks near the player (blocks from each column's bedrock to its top
   cleared, TOPS/FILLED/WATER/SURFACE removed, chunk resent; logs `terrain unfilled chunk`) - what
   stands on WoW's ground is untouched; on, the old fill. `McwowCommands` (game masters):
   `/mcwow digging [on|off]`, `/mcwow mine reset [tier]` (`McwowMines.reset`: next entries go to
   area index+1). Old areas: `<world>/mcwow_mines.txt` (tier=current index, written on every
   reset/build) read at SERVER_STARTING, before the levels open their files: region/entities/poi
   files of `dimensions/mcwow/mine_<tier>` whose middle x is outside the current area's band
   (index*4096 +- 2048) are deleted (log `deleted n region files of old areas`). JOIN in a mine
   outside the current area -> `Leave` (the placement brings Steve back). Benilla still streams
   terrain columns (unused while digging is off). Still to do (user's call): MC mobs spawning in
   the dark on player-built bases (unfilled chunks keep the void biome = no spawns).
13k. **Mine tiers at WoW's mines** (REMOVED 2026-10-04, see 13u; 2026-10-03; user-confirmed: Fargodeep entrance placed, tin ore found). Tiers (user): Copper, Tin, Iron, Mithril,
   Thorium, Dark Iron - `McwowMines.Tier`, a dimension `mcwow:mine_<tier>` + biome `mcwow:<tier>_mine`
   each (Mithril+ on noise `mcwow:mine_deep`, deepslate); `LAYOUT` 4. WoW ores (`McwowOres`): tin,
   silver, mithril, truesilver, thorium, dark iron (stone + deepslate blocks drawn as vanilla stone +
   our own speck overlay PNG; raw chunks and bars as tinted vanilla item models - no Mojang art
   copied), alloys bronze (copper ingot + tin bar = 2) and steel (iron ingot + coal); smelting and
   blasting. `tools/gen_mod_data.py` writes all their data (models, loot, tags, recipes, worldgen,
   lang) - rerun after changing it. Entrances placed automatically (`McwowMineSites`): `tools/mine_sites.py`
   finds WoW's mines from the DB (ore node spawns >= 6 yd under the ADT terrain, clustered, named by
   area, tier = best common ore) -> `resources/mcwow/mine_sites.json` (63 sites); a player within 40
   blocks + WoW collision loaded -> the tier's entrance on a flat, clear floor cell near the node
   nearest the cluster's middle, once per site (SavedData `mcwow:mine_sites`, log `mine entrance
   placed in`). Headless data check: `./gradlew runDatacheck -Pdatacheck` (dedicated server in
   `build/datacheck`, no window; `-Pdatacheck` makes the client-only mod load there) - verified all
   six tiers generate their ores.
13l. **Creature loot** (2026-10-03; user-confirmed live). VMaNGOS `ClassicCraft::OnKillLoot` (Unit::Kill after the
   corpse's loot): a bridged looter's quest items (and quest starters) go straight into the WoW bags,
   then `SMSG_CC_KILL` (837: guid, entry, xyz, level, rank, type, family, money, flags skinnable,
   quest items bagged); logs `killed ... Minecraft loot`, `quest item ... into the bags`. benilla
   relays it as actors ring kind 0x11. Mod `McwowLoot`: skinnable -> WoW leather of the level (light
   <=17, medium <=27, heavy <=37, thick <=47, rugged), humanoids -> cloth (linen <=14, wool <=24, silk
   <=34, mageweave <=45, runecloth), money -> emeralds (25 + 0.4 L^2 copper each, ~1 per 4 humanoid
   kills), themes per creature (`tools/creature_themes.py` -> `resources/mcwow/creature_themes.json`:
   kobold torches/candles/coal, murloc fish, spider string, skeleton bones, fire elemental blaze
   powder, air breeze rods...), elites/rares/bosses a chance at an enchanted book. Log `loot of creature`.
13m. **Levelled gear** (2026-10-03; copper gear user-confirmed; decisions in memory `game-design`). `McwowGear`: component
   `mcwow:gear` {material, ilvl, req}; crafting stamps the result from the grid's material
   (`ShapedRecipeMixin`): name, the stats of a vanilla twin (bronze pickaxe = iron's), durability,
   repair item, leather dye. Look = material (user): leathers -> leather armor dyed per tier, copper
   -> copper, bronze -> golden, iron/steel -> iron, mithril -> chainmail armor + iron tools, thorium ->
   diamond, dark iron -> netherite; vanilla recipes kept (copper/iron ingot, cow leather = light,
   gold = weak ilvl 10); our recipes `data/mcwow/recipe/gear/` (vanilla shapes). Vanilla diamond/
   netherite recipes stay (26.3's recipe registry can't lose entries) - neither material exists here.
   Unstamped pieces count as their look's material. Combat: player melee lands at the weapon's ilvl
   (`attackLevel`; bare hand / non-gear / under-level = 1; projectiles still the character level),
   WoW hits x `armorFactor` (1 + 0.05 x (attacker level - avg armor ilvl), 0.5..2). Required level
   gates wearing (`ArmorSlotMixin`, `EquippableMixin`); tooltip "Item Level" / "Requires Level" (red).
13n. **NPC windows in Minecraft** (2026-10-03; user-confirmed) - the interaction foundation. benilla fork
   `player/external_dialog.rs`: while the driver has the crosshair, the open gossip menu / questgiver
   panel / vendor goes out as `DialogOut` (snapshots WoW's Lua frames read: `UiScript::gossip()`,
   `quest()`, new in the fork) and choices come back as `DialogIn`, queued as the Lua frames'
   intents (`push_gossip_select`, `push_quest_action`...) so WoW's own logic runs. Quest turn-ins
   (`SMSG_QUESTGIVER_QUEST_COMPLETE`) go out as kind 7. Crate: `MSG_DIALOG` (geom 8) out,
   `REN_DIALOG` (19) in; WoW's Gossip/Quest/Merchant frames are faded (alpha 0), NOT hidden, in
   Minecraft mode - their handlers close the session when the frame can't be visible. In Minecraft
   mode loot windows (quest objects) always auto-loot (`ui_loot`). Mod `McwowDialogs`: one screen
   for gossip/greeting/detail/progress/reward (options, objectives, rewards shown as the Minecraft
   items they become), choices to `REN_DIALOG`; a vendor -> `McwowVendors` (Minecraft trade screen,
   emeralds; stock by vendor type from `tools/vendor_types.py` -> `resources/mcwow/vendors.json`,
   970 vendors, gear of the area level's tier); closing it closes the WoW vendor. Quest done ->
   `McwowQuestRewards`: each WoW reward item -> Minecraft (`McwowDialog.toMinecraft`: armor by slot,
   cloth/leather -> leather tier, mail/plate/weapons -> metal by ilvl, WoW's ilvl/req kept, quality
   -> rarity + enchants; wrist/hands/back/jewellery -> enchanted book; bags -> bundle) + money as
   emeralds. Logs: benilla `dialog kind`, mod `dialog kind`, `vendor ... open`, `quest ... turned in`.
   User-confirmed live 2026-10-03: quest windows, vendors, loot, copper gear, gossip. Since:
   WoW confirmation popups (`StaticPopup1..4`, e.g. "Make this inn your home") -> crate
   `input.rs forward_popups` (polled every 6 frames, faded like the NPC frames) -> `DIALOG_CONFIRM`
   (8, npc = popup number), the pick clicks that button (close = button 2); logs `WoW popup`,
   `popup n button m clicked`. A close only closes its own window's screen. User (2026-10-03): WoW
   looting and bags are to be phased out eventually (quest items in WoW bags is a stopgap).
   Picked-up drops once stayed on screen frozen (deer, cause unknown): benilla `render.rs` hides the
   entity scene after 0.5 s without a `REN_SCENE` (log `no Minecraft scene`), the mod logs 30
   dropped scene frames (`entity mesh ... not sent`).
13o. **Quest log in Minecraft** (2026-10-03; user-confirmed; key J needed InputConstants.KEY_J - 26.3 key codes are SDL scancodes, GLFW's 74 is Home). User: quest items live in the quest log, not
   as Minecraft items. benilla fork `external_dialog.rs forward_quest_log`: `UiScript::quest_log()`
   (new) + the bags' quest items (Lua container verbs every 30 frames; class 12 or quest starters;
   usable = on-use spell or pages) -> `QuestLogOut` on change and every 5 s; `DIALOG_QUEST_LOG` (9)
   choices: `ACT_SELECT` uses bag item (arg bag<<8|slot, `UseContainerItem`), `ACT_DECLINE` abandons
   (`push_quest_log_abandon`). Crate geom `MSG_QUESTLOG` (9). Mod `McwowQuestLog`: key J ("Quest
   Log", Gameplay; L is vanilla's advancements) or `/quests`; quests left (level, complete/failed),
   objectives with progress + objectives text + description right (scroll), quest items with Use,
   Abandon (press twice). Logs: benilla `quest log (n quests, m quest items)`, `quest item in bag`,
   `quest ... abandoned from Minecraft`.
13p. **Idle timer** (2026-10-03): benilla's WoW idle handler (`ui_chat/idle.rs`: sit + AFK at 5 min,
   logout at 30) never saw input while Minecraft drives (the bridge drains it), so the character sat
   down "randomly". Fork: `stamp_input` stamps every frame while `external::crosshair()`.
13q. **Testing round fixes** (2026-10-03; user-confirmed). Quest log item names cut with "..." before
   the Use button (full name as tooltip). A weapon/tool above the character's level can't attack
   (`McwowGear` AttackEntityCallback, "Requires level N"). Starting weapon once per player (entity
   tag `mcwow_starter_kit`): stone sword "Worn Shortsword" (ilvl 3) + 4 bread. Vendors also BUY
   (`McwowVendors.buying`): cloth/leather of the area tier and the one below (8 / 6 per emerald),
   the tier's raw ore (10), rotten flesh 24, bone 16, string 16, feather 16, spider eye 8. Quest
   reward picks: 1.12's QUEST_COMPLETE omits the chosen item; `McwowDialogs` remembers it from the
   reward screen. Later (user): phase out the WoW inventory and WoW loot (no glowing corpses).
13r. **Gathering WoW objects** (2026-10-03; user's pick "harvest it like a block"; user-confirmed "all working"). Mod
   `McwowGather`: holding attack on a WoW game object (guid high 0xF110; cursor kinds loot 3, use 4,
   open 10 - not mining veins/herbs, which need WoW skills) nearer than Minecraft's target: 24
   ticks of arm swings, hit sounds and block particles of a lookalike block (cactus, planks,
   leaves, stone... by name), progress bar on the action bar; then REN_INTERACT (WoW's own use;
   loot auto-looted in Minecraft mode). `MinecraftAttackMixin` keeps Minecraft's attack/mining out
   meanwhile; hint "Hold attack: Gather X". `McwowQuestLog.announce`: any quest item count that
   rises (gather or kill) -> pickup sound + "+1 Name  Name: 3/10" on the action bar (log `quest item +`).
   Timing (user's pick): WoW's Opening cast skipped for bridged players (VMaNGOS `Spell.cpp` hook:
   OPEN_LOCK spells with lock type >= OPEN, not arm-trap/fishing; profession locks keep their casts);
   the hold is the timer, 24 ticks bare-handed, faster with the right tool by Minecraft's own mining
   speed on a lookalike block (axe for wood, hoe/shears for plants and cactus, pickaxe for rock):
   wooden axe 20, stone 14, iron 11, shears 7. Moving is allowed.
13s. **Phasing out the WoW inventory** (2026-10-03, user's plan; user: "seems to be good"). VMaNGOS, bridged players
   only: `ClassicCraft::PrepareInventory` when the bridge switches ON (HELLO repeats every 10 s; only
   the off->on edge) destroys every equipped/carried item that isn't a quest item (class 12, quest
   starter, or an active quest's ReqItem/ReqSource/SrcItem) and fills empty bag slots with
   "20-slot Bag" (1977) for quest items; logs `WoW item ... removed`, `WoW inventory prepared`.
   `OnKillLoot` clears the corpse's loot after bagging quest items (gold already sent for
   emeralds) and Unit::Kill then skips UNIT_DYNFLAG_LOOTABLE (no sparkle). `Player::RewardQuest`:
   no WoW reward items, no positive reward money (`CanRewardQuest` skips the bag-space check);
   SMSG_QUESTGIVER_QUEST_COMPLETE still lists them (built from the template) for Minecraft.
   `SpellCaster::CalcArmorReducedDamage`: no WoW armor for a bridged victim. Known gap: WoW quests
   that ask for trade goods (e.g. Linen Cloth turn-ins) can't be done - Minecraft cloth isn't WoW's.
   Quest windows no longer list rewards without a Minecraft form ("stays in WoW" removed).
13t. **Digging without the fill** (2026-10-03; OPEN: test live). User: breaking WoW terrain as
   before, but no chunks of ground generated under it - blocks placed where needed. Mod
   `McwowGroundReveal`: with `/mcwow digging on` a new chunk gets only a SHELL (each column's top
   block + what holds up sand/gravel; TOPS, `mcwow:wow_ground_kind` material|under-structure,
   `mcwow:wow_ground_placed` bit per ground cell); the ground below is virtual (same
   `McwowTerrainFill.ground/rock/cave`, now WORLD coordinates). `ServerLevelRevealMixin`
   (`updatePOIOnBlockStateChange`, end of `Level.setBlock`): a cell turning non-solid-render floods
   from it - every virtual neighbour placed (UPDATE_CLIENTS, no neighbour updates), into unrevealed
   cave air and an open column's gap (up to its highest neighbour's top) out to 24 blocks; players
   re-flood around themselves every 4 blocks walked (caves longer than 24). Bit = placed (dug stays
   air) or cave air walled. Chunks filled whole before (TOPS, no placed bits) stay as they are.
   `NaturalSpawnerMixin` skips unknown cells (air pockets over tunnel roofs, unopened caves).
   Logs `ground revealed at`, `ground placed where needed`. Digging now ON by default (user;
   SavedData key `digging_on`, the old `digging` key - saved false everywhere - is ignored).
   Fixed on the way (2026-10-03/04): (1) shell blocks never reached clients past the simulation
   distance (`Level.setBlock` tells clients only in BLOCK_TICKING chunks) and TOPS arrived first, so
   columns read as dug: the shelled chunk is now resent whole after its light, THEN its TOPS attach
   (`McwowGroundReveal` PENDING; the fill tick skips pending chunks). (2) DEADLOCK: worldgen on worker
   threads calls `ServerLevel.updatePOIOnBlockStateChange` (WorldGenRegion.setBlock); the reveal
   hook's chunk lookup waited on the server thread - `changed` returns off the server thread.
   (3) Camera dip while walking (user: first person, only with digging on): the SERVER's pose check
   (`Player.canPlayerFitWithinBlocksAndEntitiesWhen`) hit a NEIGHBOUR column's hidden top block at
   chest height where WoW's ground rises steeply (the square box reaches ~0.4 into the next column;
   `isHiddenGroundUnder` only hid tops within 0.75 of the feet), synced a crawl pose (eye 0.4) to the
   client for a tick. Found with per-frame camera + mid-tick pose probes (removed). Fix:
   `McwowColumns.isBuriedFor` (BlockCollisionsMixin): on WoW's surface (own column closed and feet
   less than 0.95 under its top, or no ground) any block at/under a closed column's top is ignored;
   in holes and tunnels (feet a block or more under the top) they collide. First version used "feet
   over own top" and still dipped (also mid-jump): in Dun Morogh (map_0 ~ -642,267,-3972) the player
   walked 0.66 UNDER their own column's top block - snow tops at 268 over WoW ground ~267.3. Benilla
   `terrain.rs` lowers fill_top only for faces whose CENTROID is > 0.75 yd under the terrain; a floor
   partly under it is missed. OPEN: those tops poke above the walked floor (data bug, not fixed).
   That still dipped (user: "you've fixed it several times"): chasing the blocks behind the
   server's verdict missed case after case. Mechanism fix instead: client mixin `OwnPoseMixin`
   (ClientPacketListener.handleSetEntityData) drops server-sent CROUCHING/SWIMMING for the local
   player in WoW dims - the client computes its own pose every tick (crawlspaces included); sleeping,
   dying, gliding still apply. User-confirmed fixed 2026-10-04. Also `McwowWorldExporter.hiddenFaces` (closed tops' up face / sides facing a
   closed gap not exported) - kept, it saves triangles. OPEN: user to confirm the dip is gone.
13u. **Zone ores under WoW's ground; mine dimensions REMOVED** (2026-10-04, user; OPEN: test live).
   User's picks: replace the mines, one tier per zone, keep coal/redstone/lapis (no diamond, vanilla
   iron/copper/gold only as tier ores). `tools/area_levels.py` -> `resources/mcwow/area_levels.json`
   (AreaTable.dbc: sub-areas have levels, zones don't; zone level = median of its sub-areas', every
   area of the zone gets it: Elwynn 8, Westfall 15, Duskwood 25, STV 40, Searing Gorge 47, Burning
   Steppes 54). `McwowZoneOres`: Copper <=10, Tin <=20, Iron <=35, Mithril <=50, Thorium; Searing
   Gorge / Burning Steppes add Dark Iron; per tier rares first (silver/gold/truesilver deeper), own
   metal, tier below, redstone, lapis, coal (ratios after the old mine mixes). Shell keeps the area
   in `mcwow:wow_ground_kind` bits 16+ (columns shelled before: area from this session's terrain,
   else Copper). Old full-fill chunks keep their vanilla ores. Removed: McwowMines, McwowMineSites,
   McwowMineClient (away bit), `/mcwow mine reset`, mine dimensions/biomes/noise/features, entrance
   and exit blocks, `mine_sites.json`, `tools/mine_sites.py`, gen_mod_data's tier section. Placed
   entrances load as air; old `dimensions/mcwow/mine_*` folders stay on disk (not deleted). Still in
   the forks, now unused: benilla bridge State::Away + CMSG_CC_MINE (836), VMaNGOS SetDownMine.
   MISTAKE while removing the mines: `rm -r data/mcwow/dimension` took the 135 WoW map dimensions
   (map_<id>.json, void flat, type mcwow:wow) with the mine ones - a new world had no WoW dimension
   ("cannot place Steve - dimension mcwow:map_1 missing"), the bridge sat in Placing forever.
   Restored from mcwow (`azerothcore-mc/fabric/src/main/resources/data/mcwow/dimension`, identical,
   made by `azerothcore-mc/tools/gen_dimensions.py` from WotLK's Map.dbc - don't rerun it against
   1.12 data, the save folders use these ids). Datacheck: fresh world creates all 135.
13v. **Skipping the race intro from Minecraft mode** (2026-10-04; OPEN: test). The only skip is
   CinematicFrame's ESC, and Minecraft mode sent every key to Minecraft. Fork: `external::set_cinematic`
   (set each frame in `cinematic.rs drive_letterbox`) / `external::cinematic()`; crate `input.rs`:
   no forwarding (and no crosshair) while a cinematic plays - WoW keeps the input as in WoW UI mode.
   First test: fell through the map after the skip. The cinematic moves the streaming focus and
   benilla's colliders to its camera, so `geom.rs` resent the cells around the held body nearly
   empty and Steve fell in Minecraft; after the skip the bridge went straight back to Driving with
   that pose and the WoW body followed (z 38 -> -1449). Now: `stream_cells` sends nothing while a
   cinematic plays (the mod keeps its cells), `bridge.rs` holds Placing during one and places
   afresh ("cinematic over") when it ends. OPEN: test.
13w. **Death: keep items, respawn at bed or hearth** (2026-10-04; OPEN: test live). User: items kept
   on death; Respawn brings the character back at Steve's bed, else at WoW's hearthstone location
   (no ghost run, no graveyard). Mod: `PlayerKeepInventoryMixin` (no drop in a WoW map dim) +
   `McwowCombat` COPY_FROM (inventory handed over); `ServerPlayerMixin.findRespawnPositionAndUseSpawnBlock`
   (dead, died in the active WoW dim): a non-forced respawn point in a `mcwow:map_<id>` dim = the bed
   (vanilla respawns there, WoW coords sent), else Steve respawns where he died (no stored forced
   point any more - /spawnpoint doesn't count as a bed) and the server sends the character home;
   `REN_RESPAWN` (20: u32 kind 0 home / 1 at, u32 map, f32 x, y, z, o) on AFTER_RESPAWN. benilla crate
   `combat.rs` -> `CMSG_CC_RESPAWN` (838; replaces the repop; kept until in world). VMaNGOS
   `HandleCCRespawnOpcode`: ResurrectPlayer(1.0, no sickness) + SpawnCorpseBones, TeleportTo the bed
   (non-instanced map, valid coords) else TeleportToHomebind (no hearth cooldown); no IsBridged check
   (HELLO is off while dead); logs `respawned at the bed` / `at the hearthstone location`. WoW's
   DEATH popup is not forwarded to Minecraft (`input.rs forward_popups` skips `which == "DEATH"`; it
   released to a graveyard before Minecraft's death screen). Releasing through WoW's own UI (WoW UI
   mode) still makes a ghost at the graveyard.
13. **First push** (2026-10-04): all three repos committed and pushed under the Cripey account
   (noreply 337582048+Cripey@users.noreply.github.com): Cripey/classiccraft `main`; the forks' work
   rebased onto upstream's latest and pushed to their default branches (benilla `main`, VMaNGOS
   `development`; both built clean first), the `classiccraft` branches deleted. Personal paths live in the gitignored `tools/local.env`. Further
   commits/pushes still wait for the user.
14. **Next** (2026-10-04 wrap-up; design in project memory `game-design`).
   - Setup/distribution (2026-10-04, pushed as 49fd84a): player setup verified on a fresh copy
     (private DB, server + GM account, Fabric profile into a stand-in launcher dir). OPEN: the mod
     in the OFFICIAL launcher (installed into ~/.minecraft, profile "classiccraft", gameDir
     ~/.minecraft/classiccraft - never launched yet); a real login on a fresh install; Arch-native
     and fresh-machine package installs; minecraft-install.sh should warn when the launcher is
     open (it can overwrite launcher_profiles.json on exit). Windows support later (user).
     No LICENSE file yet (fabric.mod.json says MIT) - user to decide.
   - The running mangosd predates the forks' rebase onto upstream (benilla main af9bc978, VMaNGOS
     development 66dd40fff, both built): `tools/server.sh stop && tools/build.sh server &&
     tools/server.sh start` when the user isn't playing.
   - Hole walls breakable (2026-10-04; user-confirmed): `McwowTargeting.skirtHit` walks the crosshair
     ray column by column; crossing from an open column into a closed one between its top block + 1
     and the WoW ground (the drawn wall) targets that column's top block. Was: the ray passed through
     the wall (render-only) and broke the block behind.
   - Test first (built, not yet confirmed live): 13v race intro (skip with ESC from Minecraft mode,
     no fall; also a full unskipped intro), 13u zone ores (copper in Elwynn, tin in Westfall, iron in
     Duskwood; rares 16-24+ blocks down), 13t leftovers (caves walling themselves as you walk, water
     flowing into dug holes, `/mcwow digging off` unfill).
   - Still untested from earlier rounds: waygate arrival sound (13h), chairs (13g), music discs (13e),
     13b items, stuck-drop log (`drop ... not picked up`) if drops on slopes still fail.
   - Known data bug: benilla `terrain.rs` lowers a column's fill top only for structure faces whose
     CENTROID is > 0.75 yd under the terrain, so some tops sit above the floor actually walked on
     (Dun Morogh, 13t). Movement/camera are fine now (`isBuriedFor`, `OwnPoseMixin`); digging aim
     can pick the wrong block there.
   - Cleanup offered, not done: the now-unused mine code in the forks (benilla bridge State::Away +
     CMSG_CC_MINE 836, VMaNGOS `SetDownMine`), old `dimensions/mcwow/mine_*` folders in saves.
   - Open bugs to watch: picked-up drops once stayed frozen on screen (13n note; logs added).
   - The WoW-inventory phase-out's gap: quests that hand in trade goods (Linen Cloth...).
   - Bows/crossbows item level (projectiles still use the character level); stable masters (farm
     animals); WoW tree chopping; MC mobs spawning in the dark on player bases (user: not now);
     the rest of the Elwynn slice polish (vendor prices, emerald rates, ore rates - tune by playtesting).

15. **Progression sim** (2026-10-04, user: on demand, rough model, output for Claude). `tools/progression.sh`
   [--accept] [--fresh] [--route <json>]: a deterministic expected-value walk of one player 1-60 along
   `tools/progression/route_alliance.json` (Elwynn -> ... -> Eastern Plaguelands, zone ids + leave levels).
   RUN IT after changing loot, gear/materials, recipes, vendors, quest rewards, ores, XP rates or combat
   scaling, read the printed head (summary, changes vs baseline, flags), and say what moved; `--accept`
   only when the user accepts the new numbers (baseline `tools/progression/baseline.json`, committed).
   Pieces: `extract.py` (DB -> `build/progression/wow.json`: quests by zone incl. sub-areas, creatures with
   health/hit from creature_classlevelstats, spawns by .map area, quest-drop chances SIGNED - only negative
   ones reach a bridged player, vendors by zone); `McwowSimExport` (mod, `./gradlew runSim -PsimIn= -PsimOut=`,
   headless server in `fabric/build/sim-server`, ~11 s, rerun when fabric/src/main or wow.json change) writes
   `rules.json` FROM THE MOD'S OWN CODE: stamped gear stats, assembled recipes, `McwowLoot.roll` sampled per
   creature (roll split out of `drop` for this), `McwowDialog.toMinecraft` per reward item,
   `McwowVendors.offersFor` per vendor, branch mining through `McwowTerrainFill.rock` per zone and depth,
   block drops/dig times per pickaxe, `wowDamage`/`mcDamage`/`armorFactor` tables; `sim.py` (mirrors only
   VMaNGOS's kill/quest XP formulas and the quest-money emerald rule; everything else it assumes is a KNOB
   listed in the report). Model: quests in level order (chains within the route, exclusive groups), fights as
   time-to-kill + damage taken (Minecraft armor + armorFactor), food for healing, durability + anvil repairs
   from mined bars, crafting/buying/mining for upgrades (gain per minute, trips <= 90 min), stock of repair
   bars when leaving a zone, grinding when quests run out. Herbs and brewing (2026-10-04, `alchemy.py`; user: "uses
   what it can brew", brewing time not counted): herb nodes per zone gathered on the way, brewing steps from the
   export (Minecraft's own brewing recipes tried with the real herb stacks), Healing in dangerous fights,
   Strength/Swiftness kept up, Regeneration while resting, ingredients and bottles bought from zone vendors when
   missing; the report runs a second walk without alchemy and prints the difference. Report sections: flags, zones, quests the bridge
   can't complete (with ids), hardest fights, per level, gear/mining log, materials ledger.
   First findings (2026-10-04, 3x XP, 45.6 h to 60; at 2x - the accepted baseline - 63.6 h, 461 quests,
   ~1800 emeralds unspent): cloth (all tiers) and medium/heavy leather have no
   use but selling (iron/bronze armor beat leather of the same band); ~1100 emeralds unspent at 60 (few
   sinks); L1-5 fights cost ~8.5 of 20 hp (takengain 2.5) and food runs out before emeralds exist; 26
   quests need normal WoW loot drops (cleared for bridged players); at 3x XP each zone's quests are half
   done when its leave level is reached; Duskwood's ground has no copper (bronze repairs need bars carried in).

16. **Armor classes** (2026-10-04, user; OPEN: test live). Metal / leather / cloth split a tier's base points
   (the material's armor twin) into physical (armor attribute, scaled at stamping) and spell protection
   (`McwowGear.CLASS_WEIGHTS` 1.0/0.4, 0.7/0.7, 0.4/1.0; `spellProtection`). The actors damage ring's school
   (offset 28, SpellSchoolMask) is read now: non-physical hits are `indirectMagic` (vanilla armor skips them)
   cut by spell protection through `CombatRules.getDamageAfterAbsorb`. Cloth tiers linen..runecloth
   (`McwowGear` materials = the cloth item ids, recipes from gen_mod_data.py), WoW cloth rewards -> cloth
   (`McwowDialog.cloth`), armor vendors sell cloth too. Design topics: `docs/topics.md`.

17. **WoW ore veins with a pickaxe** (2026-10-04, user; OPEN: test live - needs the new mangosd, benilla and mod).
   Client `McwowGather` (cursor kind 11 "Mine"; reach by distance, 5 blocks - WoW's unable flag means "no
   Mining skill" there): a pickaxe that is the correct tool for the vein's ore block (`McwowNodes.forName`: copper
   /tin/iron 3 blocks, silver/gold/truesilver 2, mithril/dark iron/thorium 3, rich thorium 5, incendicite/
   bloodstone redstone, ooze-covered = the metal), time = Minecraft's per-block time x blocks. Then REN_HARVEST
   (21, u64 guid) -> crate -> `CMSG_CC_HARVEST` (839): VMaNGOS `HandleCCHarvestOpcode` checks bridged, 10 yd,
   GO_READY chest with a Mining lock, counts one use by LootHandler's vein rule (skill bonus 0), despawns when used
   up, logs `harvested vein`; `SMSG_CC_HARVEST` (840: guid, entry, xyz, ok, used up) -> actors ring kind 0x12 ->
   `McwowNodes.confirm` (pending from the `mcwow:harvest` payload) drops 1-2 of the ore's raw item (`rawOf`: its loot
   table's item), Fortune as vanilla ore, and wears the pickaxe by the vein's blocks (user: 3 blocks' loot per harvest
   was far too much - 39 raw copper from one vein). Logs: mod `vein ... mined`, crate `vein ... harvested`.

18. **Chopping WoW trees, option B** (2026-10-04, user; OPEN: test live - needs the new benilla and mod). benilla
   fork: `NamedHull.id` (FNV-1a of model path + hull bounds to 0.1 yd: stable per tree),
   `ObjectUnderfoot::doodad_on_ray`; `target/crosshair.rs` reports the doodad hull under the crosshair (12 yd)
   when nothing WoW-interactive is there, as focus kind 16 (`external::CROSSHAIR_DOODAD`; guid = hull id, name =
   the model path's last 90 chars), never a WoW right-click. Trees without a collision hull can't be chopped.
   Mod `McwowTrees` (model -> species/logs/sapling, `Chop` C2S, `Chopped` S2C sync, SavedData
   `mcwow:chopped_trees` key `<dimension>#<id hex>` -> regrow millis, 30 min), client `McwowGather` (kind 16,
   5-block reach, `blockTicks` = Minecraft's log break time x logs), `McwowInteract` hint. Log `chopped`.
   Option A (the tree falls: hide it, re-weld its tile's collision without it, the zone's stump model) is next.

19. **Herbs** (2026-10-04, user; OPEN: test live). Herb nodes (cursor kind 12) go the vein path: client
   `McwowGather` (reach by distance, hoe/shears speed via the leaves lookalike), REN_HARVEST -> `CMSG_CC_HARVEST`
   (VMaNGOS now takes Herbalism locks too; logs `harvested node`) -> `McwowNodes.confirm` drops `herbStack`: the
   vanilla ingredient with ITEM_NAME = the WoW herb and ITEM_MODEL `mcwow:herb/<id>`, so vanilla brewing takes it.
   Table: `tools/gen_mod_data.py` HERBS -> `resources/mcwow/herbs.json` + item models (vanilla textures, tinted).
   Herb names are matched before vein words ("Silverleaf" contains "silver"). Log `herb ... gathered`.
   Vendors (same day, user): `McwowVendors.LIMITED` brewing ingredients (nether wart, blaze powder, sugar, glistering
   melon, glowstone, redstone) are limited supply - 4 lots per vendor entry, one back per 10 min after a sale
   (SavedData `mcwow:vendor_stock`, applied when the trade screen opens, `notifyTrade` records sales); general goods
   sell glass bottles and brewing stands.

20. **Difficulty curve** (2026-10-04, user: early easier, later firmer, the hard part is dungeons/raids; hits to kill
   unchanged ~4). WoW hits on the PLAYER x `McwowCombat.takenCurve(attacker level)` (on top of armorFactor and the
   `takengain` tuning; Minecraft mobs hit by WoW creatures don't get it). Targets live in `sim.py TARGET_TAKEN`
   (hits a player in gear of their level takes from an even-level normal creature: 10/9/8/7/6/6 per 10-level band);
   `tools/progression.sh --fit-taken` prints anchors meeting them - paste into `TAKEN_CURVE`, rerun until the
   factors are ~1. The report's "Difficulty curve" table and `curve_off` flag watch it after every change.

21. **Hitboxes** (2026-10-04, user; OPEN: test live - needs the new benilla AND mod: actors file v7). WoW's collision
   boxes are much smaller than big models. (A) `McwowAim` (from `MinecraftAttackMixin.startAttack`): benilla's
   crosshair focus (kind 1 = attack, picked against the posed render mesh) within `entityInteractionRange` and
   nearer than Minecraft's own hit (`McwowInteract.wowNearer`) becomes the hit result on that creature's stand-in
   (`McwowActorEntity` GUID now synced). (B) benilla `UnitSnapshot.model_height/model_width` = the unit's
   `ModelBound` (armed-idle box, model space) x its scale -> actor bytes 72/76 -> stand-in size; `McwowCreatureSizes`
   (DBC collision size, `MCWOW_DBC_DIR` - still the WotLK files on this machine) only until the model loads.

22. **WoW weapon models** (2026-10-04, user: blocky 3D, on demand; OPEN: test live, tune display angles). Add a
   weapon to `fabric/src/main/resources/mcwow/wow_weapons.json` (key, name, model_item = WoW item id, base, voxels,
   creative, rarity, gear) and run `tools/wow-weapons.sh` (--force to rebuild): item display id from the DB ->
   benilla `cc_weapon <display> <key> <voxels>` (M2 opaque/alpha-key batches voxelised, colours quantised to a 16x16
   palette, same-colour cubes merged, long axis = Y with the grip at y 8, display transforms in the generated
   model) -> local pack `~/.local/share/classiccraft/resourcepack` (pack.mcmeta min/max format 97) + previews in
   `.../weapon_previews/`. The mod adds the pack as required/top (client `PackRepositoryMixin` when the repository
   has a `ClientPackSource`) and builds the stacks (`McwowWowWeapons`: ITEM_NAME, ITEM_MODEL `mcwow:wow/<key>`,
   rarity, gear stamp). No WoW art in the repo or the jar. cc_weapon also: poses vertices at the Stand sequence's first
   frame (bone skinning, as dances.rs), centres width/thickness and mirrors meshes that are >= 80% symmetric (user: the
   sword wasn't symmetric), finds the grip by growing from WoW's grip point (the origin) while rows stay narrow (a
   shaft > 8 rows: the origin itself) and computes the hold transforms so the hand sits HAND_BIAS (1.5) above it
   (user: held by the pommel, then clipped it). `CC_WEAPON_DEBUG=1` prints batches, bones and alpha drops. Weapons
   so far: The Sword of a Thousand Truths (The Hungering Cold's model), Thunderfury, Sulfuras, Atiesh - creative only.

23. **Third-person camera** (2026-10-04, user-confirmed vs WoW geometry): client `CameraZoomMixin` (Camera.getMaxZoom
   RETURN) runs vanilla's 8 corner rays against `McwowGeomStore.clip` (WoW triangles + decks, world coords) and
   `McwowTargeting.skirtDistance` (hole walls; shares the column walk with the crosshair's skirtHit). OPEN: hole walls.
24. **Player updates** (2026-10-04): `tools/update.sh` is the one command (pull 3 repos -> re-exec if update.sh changed ->
   build -> db-setup -> server confs when `server-config.sh` changed -> `tools/wow-weapons.sh` -> minecraft-install ->
   restart server). A player on an older update.sh runs `git pull && tools/update.sh` once.

25. **Treasure chests + enchantments in the sim** (2026-10-04, user; OPEN: test live - needs mangosd (built + installed,
   not restarted: a character was online) and the mod). `docs/topics.md` rows 32-35. Server: `HandleCCHarvestOpcode`
   takes chests with WoW's Treasure lock case, continent, no quest loot (`HaveQuestLootFor`), questId 0 -> consumed, no
   loot, `[classiccraft] ... opened treasure`. Mod `McwowChests` (chests.json from `tools/treasure_chests.py`; rerun after
   DB changes), harvest path shared with veins (`McwowNodes` Harvest/confirm); right-click opens an unlocked chest
   (`McwowInteract.tryUse` -> `McwowGather.openChest`, user 2026-10-04), locked ones are pried with a pickaxe by holding
   attack (`McwowGather`, `pryBlock`). First live try failed only because mangosd predated the handler (restarted 18:24).
   Sim: `tools/progression/treasure.py`, export `enchanting` + `chests` (McwowSimExport), extract `chests` per zone (pool
   share of spawns up). Enchantments (row 34, user's 1/2/4/5): `McwowEnchants` (curated pool, random on dropped gear,
   books, set from WoW stats on quest rewards), `McwowLoot` gear drops + Looting, `McwowCreatureKinds` (Smite/Bane by
   entry, `tools/creature_kinds.py`). benilla `DialogItem` + geom MSG_DIALOG item rows now carry stats (n + type/value
   pairs), 6 resistances, damage school - benilla and mod must be updated together. Difficulty curve refitted with
   enchanted gear as the norm (user: enchantments are to be used 1-60): `TAKEN_CURVE` {0.40, 0.80, 1.23, 2.71, 4.43,
   4.82}, refitted again after early books: {0.40, 0.83, 1.23, 2.59, 4.88, 5.38}). Early sources (user 1 + 3): enchanting suppliers sell books
   by the buyer's level (`McwowVendors.books`, limited stock keyed by enchantment), chests to L20/30 +45/20% book chance.
   Sim knobs `enchant_*`, `emerald_reserve` (topics row 36).

26. **WoW anvils** (2026-10-04, user; OPEN: test live): right-click a WoW anvil (spell-focus object "Anvil", focus kind 0,
   or an anvil doodad, kind 16) -> Minecraft's anvil screen (`McwowAnvils`, `McwowInteract.anvil`). Mod only.
   Anvils in WoW dims cost only materials (user): `AnvilMenuMixin` (no level check/charge, no 40-level cut),
   `AnvilScreenMixin` (no cost label).

27. **Ranged weapons** (2026-10-04, user; design in `docs/topics.md` row 38; OPEN: test live). Spell schools on our
   hits: mod `McwowSpells` (school damage types `data/mcwow/damage_type/spell_*.json`, armor-bypassing), stand-in hit
   flags 16 periodic / 32 slow / bits 8-10 school, damage over time per school (`McwowActorEntity.addDot`), Fire Aspect
   as fire DoT; benilla passes them as `CC_HIT_PERIODIC` 4 / `CC_HIT_SLOW` 8 / school bits; VMaNGOS `HandleCCHitOpcode`:
   school -> `CalculateDamageAbsorbAndResist`, immunity -> SPELL_MISS_IMMUNE, log as spell 5019 (Shoot), frost casts
   7321 (Chilled). Hits carry their weapon's item level (`source.getWeaponItem()`), ranged included.
   Wands: `McwowWands` (WandItem per school, SpellBolt entity `mcwow:spell_bolt` rendered as the school's vanilla item
   sprite + particles, `stamp` from the grid's bar via `McwowGear.stampCrafted`, `spellPower` = cloth bonus);
   data from `tools/gen_mod_data.py weapons()` (textures, models, recipes `data/mcwow/recipe/wand/`, tag
   `#mcwow:wand_metals`, names, spell death messages).
   Guns: `McwowGuns` (GunItem rifle/blunderbuss, Bullet entity `mcwow:bullet`, ammo tiers `AMMO`, nuggets), shared
   stamping `McwowGear.Stampable` / `stampWeapon`; data in `gen_mod_data.py weapons()` (`GUN_MAPS` pixel art,
   recipes `data/mcwow/recipe/gun/`, tag `#mcwow:gun_metals`).
   Sim: export `ranged` (per metal: wand/rifle/pellet damage, intervals, ammo tiers); knob `style`
   (melee/wand/rifle/blunderbuss, plus `ranged_free_s`, `blunderbuss_free_s`, `pellet_hit`); every report compares
   the four styles over the whole walk. Next: staves (AoE spells, summons).

28. **Windows port, step 1: portability pass** (2026-10-04, user: "go in the order you decided" - 1 portability,
   2 CI release builds incl. Windows, 3 one cross-platform setup program, 4 Windows server: native VMaNGOS; also the
   "join a friend's server" path). Shared memory and data paths in ONE place per side: benilla crate `link.rs`, mod
   `McwowLinks` (keep in step). Linux unchanged (/dev/shm files, ~/.local/share/classiccraft or $XDG_DATA_HOME);
   Windows = named pagefile-backed mappings `Local\<name>` (kernel32 via FFM in Java) and %APPDATA%\classiccraft;
   overrides `CLASSICCRAFT_SHM_DIR` / `CLASSICCRAFT_DATA_DIR`; tuning file `classiccraft_combat` via
   `McwowLinks.tuningFile`. Links are never shrunk (Windows can't resize a mapped file). Music discs: benilla
   `music.rs` writes the mono Ogg itself (kira decode + vorbis_rs, quality 0.5 = ffmpeg's -q:a 5; same durations) -
   ffmpeg is no longer needed (dropped from setup.sh). Verified: Linux build + mod build; whole client type-checks for
   x86_64-pc-windows-gnu (rustup target + mingw-w64 installed in the box; `CARGO_TARGET_DIR=build/wincheck`); the
   link code both ways Rust<->Java under the host's Wine with a Windows JDK 25 (`build/wincheck/`). Linux live run
   user-confirmed 2026-10-04 (all links, dances, weapon pack, discs re-encoded, waygate sound). OPEN: real Windows.
29. **Windows port, step 2: release builds** (2026-10-05). `.github/workflows/release.yml` (classiccraft repo): one
   snapshot of all three repos (inputs benilla_ref/vmangos_ref, default main/development) -> `classiccraft-linux-x64.tar.gz`
   / `classiccraft-windows-x64.zip`: client/ (classiccraft, cc_weapon; ship profile), server/ (bin, etc/*.conf.dist;
   Linux libs bundled by VMaNGOS's package-linux-release.sh, Windows libmySQL/libeay32 from dep/), mod/ (jar), sql/
   (migrations + custom), VERSIONS.txt. Manual dispatch = run artifacts only; tag v* = DRAFT release. First run green
   (run 37247644032, 51 min - the Windows client's fat LTO is the long pole); Linux mangosd runs, client needs only
   ALSA/udev/libc; Windows mangosd.exe runs under Wine. Pushing workflow files needs gh's `workflow` scope (granted).
   realmd.conf.dist was missing from the Windows zip (installed from source, never built) - fixed, not yet re-run.
   Licenses (2026-10-05, user picked MIT for this repo): `LICENSE` (MIT, Cripey). Each bundle carries `NOTICE.txt`
   (parts, licenses, exact source commits; also the draft release's notes) and `licenses/`: ours, benilla MIT+Apache,
   VMaNGOS GPL-2, `THIRD-PARTY-LICENSES-client.txt` (cargo-about, `tools/licenses/about.toml` + `about.hbs`; ~556
   crates, all permissive/MPL), `server/` (VMaNGOS dep/ licenses; Linux: the bundled .so files' Debian copyright files;
   Windows: OpenSSL 1.0.2k + MySQL 5.5.62 client notices from `tools/licenses/`). GPL-2 source: every run archives the
   built VMaNGOS commit as `VMaNGOS-classiccraft-source-<sha>.tar.gz` (attached to the release). Both forks' READMEs
   open with a "modified fork" note.
30. **Windows port, step 3: the launcher** (2026-10-05; user's picks: terminal program, bash scripts stay the dev path,
   portable MariaDB on Windows). `launcher/` (own Cargo crate, toolchain pinned like benilla; `.cargo/config.toml`
   links the CRT statically on Windows) -> `classiccraft-launcher(.exe)` at the bundle root. Menu: Play / Update /
   Setup again / Quit; subcommands `setup|play|update|minecraft|weapons`. Everything it makes lives in the bundle's
   `data/` (`settings.json` = WoW folder, account, ports 3307/3725/8086, Windows DB root password, Minecraft dir;
   `setup/` step marks; `etc/` live confs; `run/` logs; `server/` extracted data; `mariadb/`). Ports of the scripts:
   db.rs (Linux: system MariaDB programs + socket; Windows: MariaDB 11.8.9 zip pinned by sha256, root password over
   TCP; mariadbd detached), dbsetup.rs, extract.rs, server.rs (configs = server-config.sh's; the launcher holds
   mangosd's stdin, so a Play session = server alive, typed lines = console commands, `quit` stops all),
   minecraft.rs (no Java needed: Fabric's meta profile JSON + empty jar, profile in launcher_profiles.json and the
   Microsoft Store one, Fabric API from Modrinth, mcwow.json), weapons.rs (cc_weapon), update.rs (GitHub
   releases/latest, swaps client/server/mod/sql/licenses, renames its own running exe), winrt.rs (Windows: VC++
   2015-2022 + 2008 - libmySQL.dll needs MSVCR90 - via Microsoft's installers, UAC through PowerShell RunAs).
   Unattended account: CLASSICCRAFT_ACCOUNT/CLASSICCRAFT_PASSWORD. Tested 2026-10-05: full setup on the Linux bundle
   AND the Windows bundle under Wine (MariaDB download/init, import, mangosd.exe account, Fabric profile, cc_weapon.exe),
   isolated in build/launchertest (own ports/DB/fake Minecraft dir); confs match server-config.sh's. Not testable
   here: Play (launches the game - user's rule), real Windows (VC++ check, UAC, Defender). Release workflow builds the
   launcher, adds RELEASE.txt (tag or dev-<sha>), mod/gradle.properties, offmesh/config.json for Windows mmaps.
   First bundle with the launcher: run 37254287946 green (Windows launcher imports only system DLLs). OPEN: real Windows.

## Session 2026-10-04 (second half) - state at wrap-up
- Pushed 2026-10-04 (user's go, after a personal-data scrub): classiccraft f4bb84b, benilla db54490d, VMaNGOS 7c71a08af.
  From now on (user, 2026-10-04): commit each logical change as it's done, small commits in whichever repo it touches,
  push when a task is finished (memory `fork-commit-policy`); scrub personal data per commit; machine/person notes
  live in the gitignored CLAUDE.local.md.
- Built this session, live-test status in `docs/topics.md` (rows 1-31 = every topic discussed, decided or open):
  progression sim (15), 2x XP, copper in iron zones, ore veins + yield fix, herbs + brewing, limited vendor stock,
  armor classes (16), difficulty curve (20), hitboxes (21), WoW weapon models (22), tree chopping B (18), camera (23).
- User-confirmed live: veins (yield fix requested and done), sword model look/symmetry/grip, camera vs WoW geometry.
- Open design topics: emerald storage/sinks (#7, #8), waygate seller (#9), blocked quests (#10, parked), mounts (#17),
  water/fishing (#20), rested XP (#21), reputation menu + At War (#19, decided), bags (#18, decided), tree option A.
- Lesson: an edit script did `open(p,'w').write(open(p).read())` and emptied sim.py (restored from Claude Code's
  file-history). Always read before opening for write.

## Session 2026-10-04 (third part) - state at wrap-up
- Everything committed and pushed in small commits (all three repos clean, in sync with GitHub). mangosd restarted
  on the latest build (spell schools in); benilla and the mod must be run at their latest builds together (dialog item
  stats, hit flags).
- Built this session (`docs/topics.md` rows 32-38, plan steps 25-27): treasure chests (right-click; locked = pry with a
  pickaxe), curated enchantments (random on drops, set from WoW stats on quest rewards, Smite/Bane on WoW creatures,
  Looting), creature gear drops, enchanting suppliers' books + early chest books, difficulty curve refitted with
  enchanted gear as the norm, WoW anvils (right-click) costing only materials, spell schools on hits (server
  resistances, DoTs, Chill), ranged hits at weapon ilvl, Fire Aspect on WoW creatures, wands (6 schools), rifle +
  blunderbuss + 5 ammo tiers, guns/wands from vendors, quest rewards and gear drops; sim: chests, enchanting, styles.
- User-confirmed live: guns held/aimed correctly (after 3 tries: barrel forward, aiming pose in third person), weapon
  damage numbers (rifle 86, wand 16 at copper/L13); chests failed first only because mangosd was stale.
- User decisions: wand damage stays as is for now (my 70/75/100 retune was declined); no XP cost on anvils; magic
  costs nothing for now. User-confirmed live 2026-10-04: blunderbuss pellets (after the `mcwow:bullet` fix), chests,
  vendor books, enchanted drops/quest rewards, anvils. OPEN: Smite/Bane on WoW undead/arthropods, wand schools on
  resistant creatures.
- Next (user): staves - "more interesting spells like AoE spells and maybe summons" (wands came first).
- Known quirk: WoW quest-reward gear says "Requires level 1" (VMaNGOS stores required_level 0); offered a fix, not
  asked for yet.
- Lessons: Minecraft 26.3's hit cooldown is `LivingEntity.damageCooldownTime` (not Entity.invulnerableTime) - use the
  `bypasses_cooldown` damage-type tag; held-item display transforms: in the hand's frame the sprite's in-plane
  rotation runs the barrel along the arm, y +90 points the sprite's right side away from the player.

## What carries over from mcwow
- Fabric mod (`azerothcore-mc/fabric/`): triangle collision (McwowTriCollider), block/entity
  exporters, targeting, fluids, mob pathing, combat stand-ins, overlay/input bridge. The scale
  (1 block = 1.4667 yd) and fixed coordinate mapping (`mcX = wowY/S, mcY = wowZ/S, mcZ = wowX/S`)
  are defined in mcwow's `protocol/mcwow_protocol.h`.
- Shared-memory protocols (`azerothcore-mc/protocol/`). Both sides are native Linux now, so the
  Wine file-backed-mapping workaround no longer matters.
- geom_server (`azerothcore-mc/vmap_query/`) reads AzerothCore vmaps/.map files; VMaNGOS's
  formats differ, so it needs adapting or replacing (see plan step 5).
- Everything learned about WoW's rendering (lighting constants, fog curve, depth mapping) is
  background knowledge only — in benilla those are variables in the source, not things to measure.

## Working notes
- Verify live, don't trust docs or memory (mcwow's standing rule). Search for prior art before
  reverse-engineering anything.
- Don't redistribute Blizzard or Mojang assets; benilla reads the user's own install.
- benilla has its own `AGENTS.md` and `docs/CONTRIBUTING.md` — read them before changing the fork.
