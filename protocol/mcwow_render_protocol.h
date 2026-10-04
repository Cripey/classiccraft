// mcwow render protocol (2026-10-01) - Minecraft world content as GEOMETRY for mcwow.dll to draw
// inside WoW's own frame (SkyCraft's architecture: chasmlol/SkyCraft protocol/skycraft_protocol.h
// "render ring", Ren* messages - ported subset, same message shapes).
//
// File: /dev/shm/classiccraft_render_v1.shm (Wine: Z:\dev\shm\mcwow_render_v1.shm). Minecraft creates it
// and is the only writer; mcwow.dll reads. Layout:
//   [0, 256)          McwowRenderHeader
//   [256, ...)        byte ring: head u64 @ +0x00, tail u64 @ +0x40, data @ +0x80
// Ring: every message starts 8-byte aligned with {u32 type, u32 payloadBytes}; MCWOW_REN_PAD means
// "skip to the start of the ring". FLOW-CONTROLLED like SkyCraft's: the writer never overwrites
// unread bytes (it waits / retries later), so nothing is lost. The reader must keep consuming.
//
// Coordinates: Minecraft blocks, REGION-LOCAL (the Fabric mod subtracts its instance region
// offset, a multiple of 16, so the section grid is unchanged). mcwow.dll maps them to WoW with the
// fixed MCWOW_MC_TO_WOW_* mapping (protocol/mcwow_protocol.h).
#pragma once
#include <stdint.h>

#define MCWOW_RENDER_PATH_WIN "Z:\\dev\\shm\\mcwow_render_v1.shm"
#define MCWOW_RENDER_PATH_UNIX "/dev/shm/classiccraft_render_v1.shm"
#define MCWOW_RENDER_MAGIC 0x6D637772u // "mcwr"
#define MCWOW_RENDER_VERSION 1u

#define MCWOW_RENDER_RING_OFF 256u
#define MCWOW_RENDER_RING_BYTES (64u << 20) // incl. the 0x80 control area
#define MCWOW_RENDER_RING_HEAD_OFF 0x00u
#define MCWOW_RENDER_RING_TAIL_OFF 0x40u
#define MCWOW_RENDER_RING_DATA_OFF 0x80u
#define MCWOW_RENDER_RING_DATA_BYTES (MCWOW_RENDER_RING_BYTES - MCWOW_RENDER_RING_DATA_OFF)
#define MCWOW_RENDER_TOTAL_BYTES (MCWOW_RENDER_RING_OFF + MCWOW_RENDER_RING_BYTES)

#pragma pack(push, 1)
typedef struct McwowRenderHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t writerPid;       // Minecraft; published LAST at init - a change = new writer, drop all
    uint32_t readerPid;       // mcwow.dll
    uint64_t readerHeartbeat; // bumped by the DLL every frame it consumes; Minecraft only exports
                              // while this advances (a full ring would otherwise stall it)
    uint32_t resendRequest;   // bumped by the DLL (attach, lost state): Minecraft resends everything
    uint32_t pad;
    // WoW's current lighting (2026-10-01), written by the DLL every frame it has it, so Minecraft
    // can light its first-person hand/held item (still drawn by Minecraft, in the overlay) like
    // the WoW-drawn blocks: terrain-block c25 ambient and c26 direct (sun) colour.
    uint32_t lightSeq;        // bumped on every write; 0 = never
    float wowAmbient[3];      // @36
    float wowSun[3];          // @48
} McwowRenderHeader; // padded to 256 bytes in the file
// byte offsets: magic 0, version 4, writerPid 8, readerPid 12, readerHeartbeat 16, resendRequest
// 24, pad 28, lightSeq 32, wowAmbient 36, wowSun 48
#pragma pack(pop)

// Message types (values = SkyCraft's RenType where shared)
#define MCWOW_REN_PAD 0u
#define MCWOW_REN_ATLAS 1u         // McwowRenAtlas + RGBA8 pixels (w*h*4), top row first
#define MCWOW_REN_SECTION 2u       // McwowRenSection + McwowRenVertex[vertexCount]; 0 = remove
#define MCWOW_REN_CLEAR_ALL 3u     // drop every section (world / dimension / region change)
#define MCWOW_REN_TEXTURE 4u       // McwowRenTexture + RGBA8 pixels: an entity texture (skin, mob...)
#define MCWOW_REN_AVATAR 5u        // McwowRenMesh + McwowRenBatch[] + McwowRenVertex[]: the player's
                                   // model this frame (third person); 0 batches = not shown
#define MCWOW_REN_SCENE 6u         // McwowRenMesh + ...: every other entity, block entity and
                                   // particle this frame
#define MCWOW_REN_ATLAS_REGION 7u  // McwowRenAtlasRegion + RGBA8 pixels: an animated sprite frame
#define MCWOW_REN_SELECTION 8u     // McwowRenSelection: the targeted block's outline box (or none)
#define MCWOW_REN_LIGHTS 9u        // McwowRenLights + McwowRenLight[count]: a section's light-emitting
                                   // blocks (sent after its section; count 0 = none). SkyCraft's
                                   // kRenLights (its value 8 is our SELECTION)
#define MCWOW_REN_EVENT 11u        // McwowRenEvent: 1 = player died in Minecraft, 2 = player respawned
#define MCWOW_REN_HIT 10u          // McwowRenHit: Minecraft hit a WoW creature's stand-in (Phase 5)
#define MCWOW_REN_MOBS 12u         // classiccraft: u32 count, u32 pad, McwowRenMob[count] - Minecraft's mobs
#define MCWOW_REN_HOLES 13u        // classiccraft: i32 cx, cz (region-local) + 8 u32 open-column mask
#define MCWOW_REN_XP_CLAIM 14u     // classiccraft leveling: u32 drop id, u32 WoW XP - an XP orb picked up
#define MCWOW_REN_CHAT 15u         // classiccraft: u32 length, UTF-8 - a "." GM command typed in Minecraft's chat
#define MCWOW_REN_INTERACT 17u     // classiccraft: u32 0 - a right-click on the crosshair's WoW NPC/object
#define MCWOW_REN_WAYGATE 18u      // classiccraft: u32 map, f32 x, y, z, o (WoW), u64 owner guid - a waygate travel
#define MCWOW_REN_DIALOG 19u       // classiccraft: u64 npc, u8 kind, u8 action, u16 0, u32 arg - a choice in a
                                   // WoW NPC window (benilla external_dialog::DialogIn)
                                   // near the player, for their WoW server proxies (5 Hz)
#define MCWOW_REN_RESPAWN 20u      // classiccraft: u32 kind (0 hearthstone location, 1 at), u32 map, f32 x, y, z, o
#define MCWOW_REN_HARVEST 21u      // classiccraft: u64 the WoW ore vein mined with a Minecraft pickaxe (CMSG_CC_HARVEST)
                                   // (WoW) - Steve respawned after a death (CMSG_CC_RESPAWN)

#pragma pack(push, 1)
typedef struct McwowRenMsgHeader { uint32_t type, payloadBytes; } McwowRenMsgHeader;
typedef struct McwowRenAtlas { uint32_t width, height; } McwowRenAtlas;
typedef struct McwowRenAtlasRegion { uint32_t x, y, width, height; } McwowRenAtlasRegion;
typedef struct McwowRenSection {
    int32_t sx, sy, sz;   // section coords (16-block cubes), region-local
    uint32_t vertexCount; // multiple of 3 (triangle list)
} McwowRenSection;
typedef struct McwowRenSelection {
    uint32_t has, pad;
    float min[3], max[3]; // region-local Minecraft coords
} McwowRenSelection;
typedef struct McwowRenLights { int32_t sx, sy, sz; uint32_t count; } McwowRenLights; // region-local section
typedef struct McwowRenLight {
    uint8_t x, y, z;   // block within the section
    uint8_t level;     // Minecraft light emission 1-15
    uint32_t color;    // RGB8 (r low byte); top byte: light kind bits 0-3 (0 steady, 1 flame, 2 lava)
} McwowRenLight;
typedef struct McwowRenHit { // 24 bytes since classiccraft (attackerId added)
    uint64_t guid;      // the WoW creature
    uint32_t wowDamage; // already scaled to WoW (McwowCombat.wowDamage), >= 1
    uint32_t flags;     // McwowActorEntity.HIT_*: 1 projectile, 2 critical, 4 fire, 8 thrown (egg/snowball),
                        // 16 damage-over-time tick, 32 frost slow, bits 8-10 spell school (0 physical .. 6 arcane),
                        // (16 "wild" is gone in classiccraft: a mob's hit names it in attackerId)
    uint32_t attackerId; // 0 the player, else the Minecraft mob's entity id (its server proxy hits)
    uint32_t pad;
} McwowRenHit;
typedef struct McwowRenMob {
    uint32_t id;        // Minecraft entity id
    uint8_t kind;       // 1 hostile, 2 passive, 3 the player's companion
    uint8_t hpPct;      // 1-100
    uint16_t pad;
    float x, y, z;      // region-local Minecraft position
    float yaw;          // degrees
} McwowRenMob; // 24 bytes
typedef struct McwowRenEvent { uint32_t kind, pad; uint64_t arg; } McwowRenEvent;
typedef struct McwowRenTexture { uint32_t id, width, height, pad; } McwowRenTexture; // id 1+
// Avatar/scene header. mcwow difference from SkyCraft: the avatar carries an origin too (WoW has
// no feet position of its own to hang it on): region-local Minecraft position, vertices relative.
typedef struct McwowRenMesh {
    double originX, originY, originZ;
    uint32_t batchCount, vertexCount;
} McwowRenMesh;
typedef struct McwowRenBatch {
    uint32_t texture; // 0: the combined block/item atlas, else a McwowRenTexture id
    uint32_t first;   // first vertex
    uint32_t count;   // vertices (multiple of 3)
    uint32_t flags;   // bit0: translucent (blended pass)
} McwowRenBatch;
typedef struct McwowRenVertex {
    float x, y, z;   // Minecraft coords relative to the section origin (sx*16, sy*16, sz*16)
    float u, v;      // combined-atlas UV
    uint8_t r, g, b, a; // tint * ambient occlusion (Minecraft's fixed face shading taken out)
    uint32_t light;  // low byte: block light 0-15, next byte: sky light 0-15
    uint32_t flags;  // bit0 cutout (alpha test), bit1 translucent, bit3 full-detail texture,
                     // bits 4-6: face normal as Minecraft Direction ordinal + 1 (0 = none,
                     // 7 = lit by the triangle's own normal - entities)
} McwowRenVertex;
#pragma pack(pop)

#ifdef __cplusplus
static_assert(sizeof(McwowRenderHeader) <= MCWOW_RENDER_RING_OFF, "render header too big");
static_assert(sizeof(McwowRenVertex) == 32, "RenVertex layout drifted");
static_assert(sizeof(McwowRenSection) == 16, "RenSection layout drifted");
static_assert(sizeof(McwowRenMesh) == 32, "RenMesh layout drifted");
static_assert(sizeof(McwowRenBatch) == 16, "RenBatch layout drifted");
static_assert(sizeof(McwowRenLight) == 8, "RenLight layout drifted");
static_assert(sizeof(McwowRenHit) == 24, "RenHit layout drifted");
static_assert(sizeof(McwowRenMob) == 24, "RenMob layout drifted");
#endif
