// mcwow actors protocol (Phase 5 combat, 2026-10-01) - WoW's units near the player, published by
// mcwow.dll every frame so Minecraft can stand an invisible, hittable entity in each one's place
// (SkyCraft's SkyrimActorEntity design).
//
// File: /dev/shm/classiccraft_actors_v1.shm (Wine: Z:\dev\shm\mcwow_actors_v1.shm). mcwow.dll creates it
// and is the only writer; Minecraft reads. Seqlock: the writer makes seq odd, writes, makes it even;
// a reader retries while seq is odd or changed during its copy.
//
// Positions are WoW world yards (the reader maps them with MCWOW_WOW_TO_MC_* and its region offset).
// Source: WoW's object manager ([[0xC79CE0]+0x2ED0], first +0xAC, next +0x3C - CopilotBuddy and
// C-Rotation agree), creatures only, nearest first, within MCWOW_ACTORS_RANGE_YD.
#pragma once
#include <stdint.h>

#define MCWOW_ACTORS_PATH_WIN "Z:\\dev\\shm\\mcwow_actors_v1.shm"
#define MCWOW_ACTORS_PATH_UNIX "/dev/shm/classiccraft_actors_v1.shm"
#define MCWOW_ACTORS_MAGIC 0x6D637761u // "mcwa"
#define MCWOW_ACTORS_VERSION 7u // 7: + model size per actor; 6: self block; 2 (classiccraft): + the damage ring after actors[]; 3: + the text ring; 4: 1 KiB text slots;
                                // 5: + the crosshair focus; 6: + our own state
#define MCWOW_ACTORS_MAX 64u
#define MCWOW_ACTORS_RANGE_YD 60.0f

// McwowActor.flags
#define MCWOW_ACTOR_ATTACKABLE 0x1u // CGUnit_C::CanAttack(player, unit) (0x00729A70)
#define MCWOW_ACTOR_DEAD 0x2u       // health 0
#define MCWOW_ACTOR_TARGETS_ME 0x4u // UNIT_FIELD_TARGET == the player's guid
#define MCWOW_ACTOR_CRITTER 0x8u    // Lua UnitCreatureType "Critter"/"Non-combat Pet", learned per entry on
                                    // the first hit (actors.cpp LearnCreatureType): never fights back

#pragma pack(push, 1)
typedef struct McwowActor {
    uint64_t guid;          // 0
    float x, y, z;          // 8  WoW yards (feet)
    float facing;           // 20 radians
    uint32_t entry;         // 24 creature_template entry
    uint32_t displayId;     // 28 UNIT_FIELD_DISPLAYID
    float scale;            // 32 OBJECT_FIELD_SCALE_X
    float boundingRadius;   // 36 UNIT_FIELD_BOUNDINGRADIUS (yards)
    float combatReach;      // 40 UNIT_FIELD_COMBATREACH
    uint32_t health;        // 44
    uint32_t maxHealth;     // 48
    uint32_t level;         // 52
    uint64_t targetGuid;    // 56 UNIT_FIELD_TARGET
    uint32_t flags;         // 64 MCWOW_ACTOR_*
    uint32_t unitFlags;     // 68 UNIT_FIELD_FLAGS
    float modelHeight;      // 72 v7 (2026-10-04): the visible model's height (yd, idle box x scale; 0 = not loaded)
    float modelWidth;       // 76 v7: ... and its larger flat extent (yd)
} McwowActor; // 80 bytes

typedef struct McwowActorsHeader {
    uint32_t magic;           // 0
    uint32_t version;         // 4
    uint32_t seq;             // 8  seqlock
    uint32_t count;           // 12 valid entries in actors[]
    uint64_t playerGuid;      // 16
    uint32_t playerLevel;     // 24
    uint32_t playerHealth;    // 28
    uint32_t playerMaxHealth; // 32
    uint32_t mapId;           // 36
    uint64_t frame;           // 40 bumped every publish (liveness)
    // WoW's hits on the player (step 4, 2026-10-01). The player is in GM god mode while bridged, so
    // WoW health never drops; the server still reports every hit (as absorbed) and a Lua combat-log
    // watcher sums them. Cumulative - the reader mirrors the difference.
    uint32_t hitTotal;        // 48 WoW damage taken since the watcher started
    uint32_t hitSourceLo;     // 52 low 32 bits of the last attacker's guid
    uint32_t deathState;      // 56 bit0 dead, bit1 ghost (Lua UnitIsDead/UnitIsGhost)
    uint32_t pad;             // 60
    McwowActor actors[MCWOW_ACTORS_MAX]; // 64
} McwowActorsHeader;
#pragma pack(pop)

// v2 (classiccraft, 2026-10-02): the server's hits on what Minecraft owns (SMSG_CC_DAMAGE, relayed by
// benilla), a ring after the actors: u64 head (writer), then MCWOW_ACTORS_DAMAGE_SLOTS entries (slot =
// index % slots). The reader keeps its own tail; more than the slot count behind, it skips ahead.
// hitTotal/hitSourceLo in the header are unused (0) since v2.
#define MCWOW_ACTORS_DAMAGE_SLOTS 256u
#pragma pack(push, 1)
typedef struct McwowActorDamage {
    uint8_t victimKind;     // 0 the player, 1 the Minecraft mob mcId
    uint8_t pad[3];
    uint32_t mcId;          // Minecraft entity id (victimKind 1)
    uint64_t attackerGuid;  // the WoW creature (its stand-in is the Minecraft damage source)
    uint32_t wowDamage;     // after WoW's own combat math (miss, crit, armor)
    uint32_t attackerLevel; // for the WoW -> Minecraft damage scale
    uint32_t flags;         // bit0 crit
    uint32_t school;        // SpellSchoolMask
} McwowActorDamage; // 32 bytes

// Leveling (classiccraft, 2026-10-02): a kill's XP held by the server for Minecraft's XP orbs
// (SMSG_CC_XP_DROP), relayed into the same ring with victimKind MCWOW_ACTORS_XP_DROP:
//   u8 kind (0x10), u8 creature rank, u16 creature level, u32 drop id, u32 WoW XP (0 = grey),
//   f32 x, y, z (corpse, WoW yards), 8 bytes unused.
// The orbs' pickups go back as REN_XP_CLAIM (render ring 14: u32 drop id, u32 XP).
// Header bytes 48 / 52 (hitTotal / hitSourceLo, unused since v2) carry PLAYER_XP / PLAYER_NEXT_LEVEL_XP.
#define MCWOW_ACTORS_XP_DROP 0x10u
// Loot (classiccraft, 2026-10-03): a creature the player killed (SMSG_CC_KILL), for Minecraft's loot:
//   u8 kind (0x11), u8 rank, u8 level, u8 creature type, u32 entry, u32 money (copper on the corpse),
//   f32 x, y, z (corpse, WoW yards), u8 beast family, u8 flags (1 = skinnable), u8 quest items the
//   server put in the WoW bags, 5 bytes unused.
#define MCWOW_ACTORS_KILL 0x11u
// Ore veins (classiccraft, 2026-10-04): a WoW vein mined with a Minecraft pickaxe (SMSG_CC_HARVEST):
//   u8 kind (0x12), u8 ok, u8 used up, u8 0, u32 GO entry, u64 vein guid, f32 x, y, z (WoW yards), 4 unused.
#define MCWOW_ACTORS_HARVEST 0x12u
#pragma pack(pop)
#define MCWOW_ACTORS_RING_HEAD_OFF (64u + MCWOW_ACTORS_MAX * 80u) // 5184
#define MCWOW_ACTORS_RING_DATA_OFF (MCWOW_ACTORS_RING_HEAD_OFF + 8u)
// The text ring (v3, 2026-10-03), after the damage ring: the server's system chat lines (GM command
// replies, NPC speech) for Minecraft's chat. u64 head (benilla), then MCWOW_ACTORS_TEXT_SLOTS slots of 1024 bytes:
// u16 length, UTF-8 (WoW's |c / |H codes left in; the mod strips them).
#define MCWOW_ACTORS_TEXT_HEAD_OFF (MCWOW_ACTORS_RING_DATA_OFF + MCWOW_ACTORS_DAMAGE_SLOTS * 32u) // 13384
#define MCWOW_ACTORS_TEXT_SLOTS 64u
// The crosshair focus (v5, 2026-10-03), after the text ring: what WoW thing a right-click in Minecraft
// would act on (benilla external::CrosshairTarget). u32 seq (odd while written), u32 kind (0 none,
// 1 attack, 2 speak, 3 loot, 4 interact, 5 buy, 6 read, 7 trainer, 8 taxi, 9 skin, 10 mail, 11 mine,
// 12 herbs, 13 picklock, 14 repair, 15 cast), f32 distance (blocks), u32 unable (out of reach),
// u64 guid, u32 name length, name (UTF-8, <= 96 bytes). 128 bytes.
#define MCWOW_ACTORS_FOCUS_OFF (MCWOW_ACTORS_TEXT_HEAD_OFF + 8u + MCWOW_ACTORS_TEXT_SLOTS * 1024u)
// Our own state (v6): u32 stand state (UNIT_FIELD_BYTES_1 byte 0: 1 sit, 2/4/5/6 chair), 28 spare.
#define MCWOW_ACTORS_SELF_OFF (MCWOW_ACTORS_FOCUS_OFF + 128u)
#define MCWOW_ACTORS_TOTAL_BYTES (MCWOW_ACTORS_SELF_OFF + 32u)

#ifdef __cplusplus
static_assert(sizeof(McwowActor) == 72, "McwowActor layout drifted");
static_assert(sizeof(McwowActorsHeader) == MCWOW_ACTORS_RING_HEAD_OFF, "actors header layout drifted");
static_assert(sizeof(McwowActorDamage) == 32, "damage entry layout drifted");
#endif
