// mcwow real-geometry protocol - new 2026-10-01, "pursue real WoW geometry export".
//
// Shared-memory schema between the new native `geom_server` helper process (links AzerothCore's
// own, unmodified VMAP/terrain-reading code - see vmap_query/ for the validated feasibility
// spike this is built from) and the Fabric mod's JVM. Modeled on chasmlol/SkyCraft's own
// collision-ring design (see /tmp/SkyCraft/protocol/skycraft_protocol.h's kColRegion/kColTris
// messages, read directly before writing this) - a byte ring of {type, payloadBytes}-prefixed
// messages, same shape, same "replace by objectId" idea. Two real differences from SkyCraft's
// version, both because our situation is simpler than theirs:
//   1. BOTH ends of this link are native Linux processes (geom_server and the Fabric mod's own
//      JVM) - unlike mcwow_v1.shm, which must cross the Wine boundary (WoW/mcwow.dll is the only
//      Windows-side participant in this whole project), this file is never touched by Wine at
//      all, so there's only one path, no _WIN/_UNIX split.
//   2. SkyCraft ships TWO parallel geometry kinds from ONE live source (Havok). We have TWO
//      SEPARATE real sources (VMAP WMO/M2 models, and the separate ADT terrain heightmap format -
//      see CLAUDE.md "VMAP alone only sees WMO/M2 model collision" for why these are different
//      files/code paths on the WoW side) - unified here into ONE message shape (a batch of real
//      triangles, tagged `kind`) so the Java side needs only one consumer, not two.
//
// geom_server reads the player's live WoW position AND the live camdriver coordinate anchor
// from the EXISTING mcwow_v1.shm (protocol/mcwow_protocol.h's McwowWowPlayerState) - no separate
// input channel needed. All triangle vertices published here are ALREADY converted to MC block
// space (Y-up) using that same anchor + MC_BLOCKS_TO_WOW_YARDS scale, matching the convention
// groundGrid already established - the Fabric mod never needs to know about WoW yards at all.
#pragma once

#include <stdint.h>

#define MCWOW_GEOM_SHM_PATH "/dev/shm/classiccraft_geom_v1.shm"

#define MCWOW_GEOM_MAGIC 0x6D636731u   // 'mcg1'
#define MCWOW_GEOM_VERSION 3 // v2 (2026-10-01): fixed 32-yd cells, persistent, eviction queue;
                             // v3 (2026-10-02): transport decks (MSG_DECK, deck table, rider block)

#pragma pack(push, 1)

typedef struct McwowGeomHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t writerPid;        // geom_server's own PID
    uint32_t readerPid;        // filled in by the Fabric mod once it attaches
    uint64_t writerHeartbeat;  // incremented once per geom_server publish cycle
    uint32_t refreshRequest;   // incremented by the Fabric mod when it had to resync its ring
                               // position (bad/overwritten message); geom_server answers with an
                               // immediate full refresh (CLEAR + republish) instead of waiting
                               // for the next periodic one. Offset 24, inside the 64-byte header.
} McwowGeomHeader;

// Ring size: generous for "nearby real models + one terrain patch," same cost category as
// SkyCraft's own 32MiB collision ring (theirs covers much more ground - a whole render-distance
// mesh - ours is intentionally a bounded local patch, see geom_server's own comment on why
// full terrain-mesh streaming is deferred).
#define MCWOW_GEOM_RING_BYTES (16u * 1024u * 1024u)

#define MCWOW_GEOM_OFF_HEADER 0
#define MCWOW_GEOM_OFF_RING 64
// Eviction queue (v2), after the ring: the Fabric mod keeps cells persistently and drops the
// least-recently-visited ones over its memory budget (never cells with the player's blocks,
// items or mobs in loaded chunks). It appends each dropped cell's objectId here so geom_server
// sends that cell again when the player comes back. u64 head (written by the mod) at +0, then
// MCWOW_GEOM_EVICT_SLOTS u64 ids (slot = index % SLOTS). geom_server keeps its own tail; if it
// falls more than SLOTS behind it forgets everything it sent (the mod keeps its copies).
#define MCWOW_GEOM_OFF_EVICT (MCWOW_GEOM_OFF_RING + MCWOW_GEOM_RING_BYTES)
#define MCWOW_GEOM_EVICT_SLOTS 4096u
#define MCWOW_GEOM_EVICT_BYTES (16u + MCWOW_GEOM_EVICT_SLOTS * 8u)
// Transport decks (v3), after the eviction queue. A transport (boat, zeppelin, tram car, lift)
// is no part of any cell: its walkable collision goes once as MSG_DECK in the deck's own frame,
// and its pose every frame in this table (benilla writes, seqlock: seq odd while writing).
//   +0 u32 seq, +4 u32 count, +8 pad to 16, then MCWOW_GEOM_DECK_SLOTS x 32 bytes:
//   u64 guid, f32 x, y, z (deck origin, MC blocks, region-local), f32 yaw (radians, about +Y:
//   world = origin + R(yaw) * local, x' = x cos + z sin, z' = -x sin + z cos), u32 pad[2].
// Then the rider block (the mod writes, seqlock) at MCWOW_GEOM_OFF_RIDER: while Steve stands on a
// deck his pose relative to it, which benilla composes with its live deck pose (Minecraft ticks
// at 20 Hz, so a world pose would trail a moving deck):
//   +0 u32 seq, +4 pad, +8 u64 guid (0 = not aboard), +16 f32 eye xyz, +28 f32 feet xyz,
//   +40 f32 yaw (MC degrees, deck-relative: world yaw = this - degrees(deck yaw)),
//   +44 u32 flags: bit 0 = the pose is present. guid set without it: aboard, but the deck is out
//   of sight (a crossing); guid 0: not aboard - benilla leaves the deck too, airborne or not.
#define MCWOW_GEOM_OFF_DECKS (MCWOW_GEOM_OFF_EVICT + MCWOW_GEOM_EVICT_BYTES)
#define MCWOW_GEOM_DECK_SLOTS 16u
#define MCWOW_GEOM_OFF_RIDER (MCWOW_GEOM_OFF_DECKS + 16u + MCWOW_GEOM_DECK_SLOTS * 32u)
#define MCWOW_GEOM_TOTAL_BYTES (MCWOW_GEOM_OFF_RIDER + 64u)
// Cells (v2): WoW world XY split into MCWOW_GEOM_CELL_YD squares; cell (cx, cy) =
// (floor(x / CELL), floor(y / CELL)) holds every terrain quad and model triangle whose centre lies
// in it (each triangle in exactly one cell). objectId = MCWOW_GEOM_CELL_ID_BIT |
// (uint16)(cx + 32768) << 16 | (uint16)(cy + 32768). A cell with nothing in it is still sent
// (0 triangles) so the mod knows its ground is loaded.
#define MCWOW_GEOM_CELL_YD 32.0f
#define MCWOW_GEOM_CELL_ID_BIT (1ull << 62)

// Ring header (at MCWOW_GEOM_OFF_RING): u64 head (bytes written, geom_server only) then u64 tail
// (bytes consumed, Fabric mod only) - same lock-free single-writer/single-reader pattern
// SkyCraft's own collision ring uses (see its own doc comment: "Byte ring. Every message starts
// 8-byte aligned..."), not a seqlock - a ring naturally handles "producer stays ahead of
// consumer" without needing retry-on-torn-read semantics.
#define MCWOW_GEOM_RING_HEAD_OFF 0   // u64, written by geom_server
#define MCWOW_GEOM_RING_TAIL_OFF 8   // u64, written by the Fabric mod
#define MCWOW_GEOM_RING_DATA_OFF 64
#define MCWOW_GEOM_RING_DATA_BYTES (MCWOW_GEOM_RING_BYTES - MCWOW_GEOM_RING_DATA_OFF)

enum McwowGeomMsgType {
    MCWOW_GEOM_MSG_PAD = 0,    // skip to the start of the ring (no payload fields used)
    MCWOW_GEOM_MSG_CLEAR = 1,  // payload: McwowGeomClear - drop everything, a new epoch starts
    MCWOW_GEOM_MSG_BATCH = 2,  // payload: McwowGeomBatchHeader + McwowGeomTri[count]
    MCWOW_GEOM_MSG_REMOVE = 3, // payload: McwowGeomRemove - drop one previously-sent object
    MCWOW_GEOM_MSG_TERRAIN = 4, // payload: McwowGeomTerrainChunk - WoW ground per MC block column
    MCWOW_GEOM_MSG_DECK = 5,    // payload: McwowGeomBatchHeader (objectId = guid) + McwowGeomTri[count],
                                // in the deck's frame (see MCWOW_GEOM_OFF_DECKS)
    MCWOW_GEOM_MSG_DECK_GONE = 6, // payload: McwowGeomRemove (objectId = guid)
    MCWOW_GEOM_MSG_BOOK = 7,      // a book/plaque read from the crosshair: u32 title length, title,
                                  // u32 pages, per page u32 length + UTF-8
    MCWOW_GEOM_MSG_DIALOG = 8,    // classiccraft 2026-10-03: a WoW NPC window (gossip, quest panels,
                                  // vendor, close, quest done) for Minecraft's screen; layout in benilla
                                  // crates/classiccraft/src/geom.rs (MSG_DIALOG) and McwowDialogs.parse
};

// Every message starts 8-byte aligned with this header, mirroring SkyCraft's own ring format
// exactly (their own doc comment: "Every message starts 8-byte aligned with {u32 type, u32
// payloadBytes}").
typedef struct McwowGeomMsgHeader {
    uint32_t type;         // McwowGeomMsgType
    uint32_t payloadBytes; // bytes following this header for this message
} McwowGeomMsgHeader;

typedef struct McwowGeomClear {
    uint32_t epoch; // bumped on map change or a geom_server restart; the reader adopts whatever
                     // epoch it first sees, same "a freshly started client joins whatever epoch
                     // is already in progress" rule SkyCraft's own SkyCollision.java uses.
} McwowGeomClear;

typedef struct McwowGeomRemove {
    uint64_t objectId;
} McwowGeomRemove;

enum McwowGeomTriFlags {
    MCWOW_GEOM_TRI_WALKABLE = 1u << 0, // normal.z above the walkable-slope threshold (geom_server
                                        // classifies this per-triangle from real geometry, not a
                                        // guess - see its own comment on the exact threshold)
};

// MC-space (blocks, Y-up), already anchor-converted - see this file's own top comment.
typedef struct McwowGeomTri {
    float v[9];        // 3 vertices x0,y0,z0, x1,y1,z1, x2,y2,z2
    uint32_t flags;     // McwowGeomTriFlags
} McwowGeomTri;

enum McwowGeomBatchKind {
    MCWOW_GEOM_KIND_MODEL = 0,   // a real WMO/M2 ModelInstance
    MCWOW_GEOM_KIND_TERRAIN = 1, // a synthesized terrain patch (see geom_server)
};

// One batch = the real triangles of ONE real-world object, keyed by a stable id so the reader
// can later replace or remove it (MCWOW_GEOM_MSG_REMOVE) without resending everything else -
// same "per-region, not per-frame" granularity as SkyCraft's own COL_TRIS messages.
typedef struct McwowGeomBatchHeader {
    uint64_t objectId; // VMAP::ModelInstance::ID for a model; a synthesized patch id for terrain
    uint32_t count;    // triangle count following this header
    uint32_t kind;     // McwowGeomBatchKind
} McwowGeomBatchHeader;

// classiccraft breakable terrain (Phase 1, 2026-10-02): one Minecraft chunk (16x16 block columns,
// region-local chunk coords) of WoW ground, sent once per epoch by benilla's terrain export. The
// mod fills real blocks under it (McwowTerrainFill).
typedef struct McwowGeomTerrainColumn {
    float surface;    // lowest WoW ground over the column, MC y; NaN = no terrain (hole/off map)
    float fillTop;    // surface, lowered under any structure below the ground (cellar, mine)
    uint8_t material; // 1 grass, 2 dirt, 3 sand, 4 snow, 5 stone, 6 gravel, 7 mud (0 unknown)
    uint8_t liquid;   // WoW terrain liquid (MCLQ) over the column: 0 none, 1 water, 2 ocean,
                      // 3 magma, 4 slime (outdoor water, 2026-10-02)
    uint16_t area;    // AreaTable.dbc id
    float liquidTop;  // that liquid's surface, MC y; NaN = none
} McwowGeomTerrainColumn;

typedef struct McwowGeomTerrainChunk {
    int32_t cx, cz;                        // MC chunk, region-local
    McwowGeomTerrainColumn columns[256];   // index z * 16 + x
} McwowGeomTerrainChunk;

#pragma pack(pop)
