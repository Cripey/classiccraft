// mcwow bridge protocol - Phase 0 ("Link")
//
// Shared-memory schema between mcwow.dll (WoW side) and the eventual Fabric mod (Minecraft
// side). Mirrors the shape of chasmlol/SkyCraft's own protocol (a header with magic/version/
// PIDs/heartbeats, plus seqlock-protected "latest value" slots for per-frame data) - found via
// research 2026-09-30.
//
// This file is the single source of truth for the memory layout. It's plain C (no C++-only
// features) so its exact field order/sizes/packing are unambiguous to port by hand into a Java
// class that reads the same bytes via a MappedByteBuffer, the same way SkyCraft's design doc
// describes mirroring one schema into two languages. A future revision may add a generator;
// for Phase 0's tiny schema, keeping both sides hand-written and in sync is simpler.
//
// PHASE 0 SCOPE: just the WoW->MC player-position slot, enough to confirm the handshake and
// pin down the coordinate mapping (WoW yards, Z-up <-> MC blocks, Y-up) empirically. Later
// phases add WorldContext, CollisionSection, ActorUpsert, Input, PlayerState (MC->WoW), etc.,
// following SkyCraft's own message catalog as a template - added when each phase needs them,
// not speculatively now.
#pragma once

#include <stdint.h>

// FIX 2026-09-30: this was originally a Windows named kernel object ("Local\mcwow_v1" via
// CreateFileMapping(INVALID_HANDLE_VALUE, ...)), which works fine when BOTH sides are native
// Windows processes (SkyCraft's actual situation - Skyrim.exe and javaw.exe are both native
// Windows). Ours isn't symmetric: WoW runs under Wine, but the Minecraft/Fabric side will be a
// native LINUX JVM, not running under Wine at all - it has no way to open a Wine-internal,
// page-file-backed anonymous mapping, since that memory was never backed by anything on the
// real filesystem. Fixed by backing the mapping with a REAL FILE at a fixed path instead: the
// WoW-side DLL opens it via its Wine Z:\ drive mapping (CreateFileA on the Windows-style path
// below), the Java side opens the exact same underlying file with a plain
// FileChannel.map(...), and both are just reading/writing the same bytes on the same tmpfs
// file - no Windows-specific IPC object semantics involved on either side.
#define MCWOW_SHM_PATH_WIN "Z:\\dev\\shm\\mcwow_v1.shm" // as WoW/Wine sees it
#define MCWOW_SHM_PATH_UNIX "/dev/shm/classiccraft_v1.shm"     // as any native Linux process sees it

// 'mcw1' - bumped only if the layout below changes incompatibly.
#define MCWOW_MAGIC 0x6D637731u
#define MCWOW_PROTOCOL_VERSION 3 // 2 = fixed coordinates (no anchor), map/teleport handshake;
                                 // 3 = classiccraft: MC ground contact, water, velocity, FOV

#pragma pack(push, 1)

// Fixed-size header: magic/version let a reader refuse a stale or mismatched mapping instead of
// misinterpreting bytes; PIDs and heartbeats let either side detect the other has died (a
// heartbeat that stops incrementing) - same crash-safety idea as SkyCraft's design doc (§10):
// "if either side dies, the other drops to a safe state."
typedef struct McwowHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t wowPid;   // filled in by the WoW side on create
    uint32_t mcPid;    // filled in by the MC side once it attaches
    uint64_t wowHeartbeat; // incremented once per real WoW frame (EndScene)
    uint64_t mcHeartbeat;  // incremented once per MC render tick (Phase 0+: written by MC side)
} McwowHeader;

// Seqlock-protected "latest value" slot (SkyCraft's own term for this pattern, §10): the writer
// increments `seq` (now odd) BEFORE writing the payload, writes it, then increments `seq` again
// (now even) AFTER. A reader takes `seq`, reads the payload, takes `seq` again, and only trusts
// the read if both reads matched and were even - otherwise it raced a write and must retry. No
// OS mutex/critical section needed, safe for a once-per-frame writer and an independent-rate
// reader, and never blocks the writer (a stalled reader can never stop WoW's render thread).
typedef struct McwowWowPlayerState {
    volatile uint32_t seq;
    float x, y, z;     // WoW world position, yards (same convention as wow::GetPlayerPos)
    float facing;      // WoW facing, radians
    uint64_t wowTick;  // copy of wowHeartbeat at the moment this was written, for staleness checks
    // Added 2026-10-01, take 2 - genuinely independent WoW-side ground sensing via
    // `wow::TraceLine` (found via prior-art search, confirmed live - see CLAUDE.md
    // "WorldExporter stage B, take 2"), a real ray-cast against WoW's own terrain/WMO/M2
    // collision, not derived from the puppet's own position at all.
    // UPGRADED same day to a grid (one sample per nearby MC column, not a single scalar) after
    // a live report: "when going up a slope or stairs I fall through the map." A single height
    // value becomes one infinite flat plane in Minecraft's collision (see BlockCollisionsMixin)
    // - fine on flat ground, but it can't represent a staircase or ramp, where neighboring
    // columns genuinely need different heights.
    // WIDENED same day again, 3x3 -> 5x5, after a second live report: still falling through on
    // inclines, and unable to recover once fallen through. Root cause: this grid is reactive and
    // centered on wherever the puppet CURRENTLY is (itself a mirror of MC's own last-resolved
    // position) - a tight 3x3 window gives almost no lead distance, so fast/continuous movement
    // (an incline, not discrete stairs) can outrun it. chasmlol/SkyCraft avoids this entirely by
    // maintaining a wide, render-distance-scale EXPORTED MESH (real triangles) instead of a
    // small reactive sample window - out of scope to replicate in full here, but a wider grid
    // meaningfully narrows the same gap. `groundGrid[i]` is sampled at MC block offset `(dx,dz)`
    // from the player's own column, `dx,dz` each in {-2,-1,0,1,2}, `i = (dx+2)*5 + (dz+2)`
    // (row-major, dx outer) - a fixed pattern both sides hard-code, not sent over the wire. Each
    // entry already converted through the fixed mapping (region-local) into MC's Y-up block coordinate
    // system, same as the single-scalar version was.
    float groundGrid[25];
    uint32_t groundGridMask; // bit i set = groundGrid[i] is a real hit (traced ok)
    // Fixed coordinates (2026-10-01, protocol v2) - replaces the session anchor. WoW -> MC
    // positions follow MCWOW_WOW_TO_MC_* below everywhere, so no anchor is published any more.
    uint32_t mapId;        // wow::GetMapId(): 0 EK, 1 Kalimdor, 530 Outland, 571 Northrend...;
                           // 0xFFFFFFFF = unreadable (not in world)
    // Bumped by mcwow.dll whenever WoW, not Minecraft, decides where the player is: bridge
    // enabled (Numpad+), map change, or the game moving the player (portal, hearth, boat,
    // summon...). Minecraft places Steve at the fixed-mapped WoW position in the map's dimension
    // and echoes the value in McwowMcCameraState::teleportAck; until then the DLL writes neither
    // puppet nor camera.
    uint32_t teleportSeq;
    // Lua GetInstanceDifficulty() (1-based: 1 = normal 5/10, 2 = heroic 5/25, ...), 0 = not read
    // yet / not in an instance. Used with playerGuid to find the instance bind in AzerothCore's DB.
    uint32_t instanceDifficulty;
    uint64_t playerGuid;   // local player GUID (0x00CA1238); low 32 bits = characters.guid
} McwowWowPlayerState;

// MC -> WoW, added Phase 1 ("Walk WoW in MC physics" / CameraDriver) 2026-09-30. Raw Minecraft
// camera state (`net.minecraft.client.Camera`, confirmed against the real 26.3 jar via javap -
// `mainCamera().position()` for x/y/z, `.yRot()`/`.xRot()` for yaw/pitch), units and convention
// UNCONVERTED (Minecraft blocks, Y-up, degrees) - the WoW side owns the yard<->block scale, the
// Y-up<->Z-up axis remap via the fixed MCWOW_MC_TO_WOW_* mapping (protocol v2). Positions are
// REGION-LOCAL (the Fabric mod subtracts its per-instance region offset before publishing).
typedef struct McwowMcCameraState {
    volatile uint32_t seq;
    float mcX, mcY, mcZ; // Minecraft CAMERA position, blocks (Y-up), Camera::position() - the
                         // EYE, not the body. Used only for the CAMERA object's own position.
    float mcYawDeg;      // Minecraft yaw, degrees, Camera::yRot()
    float mcPitchDeg;    // Minecraft pitch, degrees, Camera::xRot()
    uint64_t mcTick;     // copy of mcHeartbeat at write time
    // Added 2026-10-01 - the player ENTITY's own feet/body position (`LocalPlayer.position()`),
    // distinct from the camera/eye position above. Found missing via chasmlol/SkyCraft's own
    // source (the user's request): SkyCraft publishes `feet` and `eye` as two SEPARATE fields
    // (`SkyClient.java`'s `afterRender()`: "mc.x/y/z = feet.x/y/z" vs "mc.eyeX/Y/Z = eye.x/y/z",
    // with an explicit comment "The eye, not the camera: in third person Minecraft's camera sits
    // behind or in front") and uses feet (not eye) for `SetPosition()`. We had been using the
    // CAMERA (eye) position for BOTH the camera's own write AND the puppet's/ground-height's
    // feet-equivalent math, conflating the two - confirmed live 2026-10-01 as the root cause of
    // the Minecraft floor spawning ~1.6 blocks too high (eye height above where feet should be)
    // and the WoW puppet floating/clipping the same way. This field is MC's standing eye height
    // (~1.62 blocks) BELOW the camera in first person - the real fix is to anchor/convert
    // puppet-position and ground-height math off THIS field, keeping the camera's own math
    // using mcX/Y/Z exactly as before (unchanged, that part was always correct).
    float mcFeetX, mcFeetY, mcFeetZ;
    // Added 2026-10-01 - whether MC's own camera is in first person right now
    // (`Options.getCameraType().isFirstPerson()`). Drives `renderhidehook`'s model-hide flag:
    // hide the WoW character while MC is first-person (it would just obstruct the view, same as
    // Minecraft's own convention), show it in third-person (there's no MC-rendered player body
    // composited in yet - that's a later phase - so WoW's own model is the best available stand-
    // in for "seeing your own avatar" until then).
    uint32_t mcFirstPerson;
    // Protocol v2: last McwowWowPlayerState::teleportSeq Minecraft has acted on (Steve placed at
    // the WoW position). The DLL resumes puppet/camera writes only once this matches.
    uint32_t teleportAck;
    // v3 (classiccraft, 2026-10-02): what benilla's movement stream needs to send real MSG_MOVE_*
    // edges (jump, fall, land) instead of bare heartbeats. Velocity is blocks per SECOND (MC's
    // getDeltaMovement() per tick x 20), same axes as the positions.
    uint32_t mcOnGround;   // LocalPlayer.onGround()
    uint32_t mcInWater;    // LocalPlayer.isInWater()
    float mcVelX, mcVelY, mcVelZ;
    float mcFovDeg;        // the vertical FOV Minecraft rendered this frame (sprint/effects included)
} McwowMcCameraState;

typedef struct McwowShm {
    McwowHeader header;
    McwowWowPlayerState wowPlayer; // WoW -> MC. Phase 0's only payload.
    McwowMcCameraState mcCamera;   // MC -> WoW. Phase 1's CameraDriver payload.
} McwowShm;

#pragma pack(pop)

// Byte offsets for the Java side (which can't #include this header - it reads the same bytes
// manually via a MappedByteBuffer set to ByteOrder.LITTLE_ENDIAN, matching x86's native order
// and this struct's #pragma pack(1) - no hidden padding on either side). Recompute these by
// hand if this file changes; there's no generator yet (see the file header comment).
//   McwowHeader   (32 bytes total):
//     magic        u32 @  0
//     version      u32 @  4
//     wowPid       u32 @  8
//     mcPid        u32 @ 12
//     wowHeartbeat u64 @ 16
//     mcHeartbeat  u64 @ 24
//   McwowWowPlayerState (152 bytes total, starts at offset 32):
//     seq             u32 @ 32
//     x               f32 @ 36
//     y               f32 @ 40
//     z               f32 @ 44
//     facing          f32 @ 48
//     wowTick         u64 @ 52
//     groundGrid[25]  f32 @ 60 (100 bytes: indices 0..24 @ 60,64,68,...,156)
//     groundGridMask  u32 @ 160
//     mapId           u32 @ 164
//     teleportSeq     u32 @ 168
//     instanceDifficulty u32 @ 172
//     playerGuid      u64 @ 176
//   McwowMcCameraState (76 bytes total in v3, starts at offset 184):
//     seq          u32 @ 184
//     mcX          f32 @ 188
//     mcY          f32 @ 192
//     mcZ          f32 @ 196
//     mcYawDeg     f32 @ 200
//     mcPitchDeg   f32 @ 204
//     mcTick       u64 @ 208
//     mcFeetX      f32 @ 216
//     mcFeetY      f32 @ 220
//     mcFeetZ      f32 @ 224
//     mcFirstPerson u32 @ 228
//     teleportAck  u32 @ 232
//     mcOnGround   u32 @ 236   (v3)
//     mcInWater    u32 @ 240   (v3)
//     mcVelX       f32 @ 244   (v3)
//     mcVelY       f32 @ 248   (v3)
//     mcVelZ       f32 @ 252   (v3)
//     mcFovDeg     f32 @ 256   (v3)
//   McwowShm total size: 260 bytes (v3; 236 in v2). The build's static_assert checks this; hand-computed
//   offsets have been wrong several times before, it has always caught them.
#define MCWOW_SHM_TOTAL_SIZE 260

// World scale: WoW yards per Minecraft block. The ONE definition shared by mcwow.dll (camera +
// puppet + ground grid) and geom_server (WoW geometry -> MC space) - they must agree exactly.
// User's choice 2026-10-01 (third revision that day): Steve is as tall as the tallest playable
// race in 3.3.5 - female Tauren, collision height 2.111 x model scale 1.25 = 2.64 yd
// (CreatureModelData.dbc, Unit::GetCollisionHeight's formula) - with every Minecraft proportion
// and speed scaled along: 2.64 / 1.8 blocks = 1.4667 yd per block. Steve: 2.64 yd tall, eye
// 2.38 yd, walk 4.317 blocks/s = 6.33 yd/s, sprint 5.612 blocks/s = 8.23 yd/s (WoW run 7.0).
// Earlier the same day: 1.0936 (1 block = 1 m), 1.6215 (walk = WoW run), 1.0 (1 block = 1 yd).
#define MCWOW_MC_BLOCKS_TO_WOW_YARDS 1.4667f

// Fixed WoW <-> Minecraft mapping (protocol v2, 2026-10-01) - replaces the per-session anchor so
// a Minecraft position always means the same WoW spot (needed for persistent blocks). These are
// REGION-LOCAL Minecraft coordinates: the Fabric mod adds a per-instance region offset inside the
// map's own dimension (mcwow:map_<mapId>), never sent over shared memory, so floats here stay
// small (|x|,|z| <= ~11,640 blocks, y in -341..1344 for real WoW terrain). Same axes as the old
// anchored transform (MC X <- WoW Y, MC Y <- WoW Z, MC Z <- WoW X), already live-calibrated
// together with the camera's yaw sign.
#define MCWOW_WOW_TO_MC_X(wowX, wowY, wowZ) ((wowY) / MCWOW_MC_BLOCKS_TO_WOW_YARDS)
#define MCWOW_WOW_TO_MC_Y(wowX, wowY, wowZ) ((wowZ) / MCWOW_MC_BLOCKS_TO_WOW_YARDS)
#define MCWOW_WOW_TO_MC_Z(wowX, wowY, wowZ) ((wowX) / MCWOW_MC_BLOCKS_TO_WOW_YARDS)
#define MCWOW_MC_TO_WOW_X(mcX, mcY, mcZ) ((mcZ) * MCWOW_MC_BLOCKS_TO_WOW_YARDS)
#define MCWOW_MC_TO_WOW_Y(mcX, mcY, mcZ) ((mcX) * MCWOW_MC_BLOCKS_TO_WOW_YARDS)
#define MCWOW_MC_TO_WOW_Z(mcX, mcY, mcZ) ((mcY) * MCWOW_MC_BLOCKS_TO_WOW_YARDS)
