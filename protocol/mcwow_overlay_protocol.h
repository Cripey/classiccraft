// mcwow overlay protocol (Phase 3): Minecraft's own rendered hand + held item + HUD + screens,
// on a transparent background, handed to mcwow.dll every frame to composite over WoW's picture.
//
// Modeled directly on chasmlol/SkyCraft's overlay triple buffer (protocol/skycraft_protocol.h,
// OverlayCtl/OverlaySlotHdr; fabric FrameExporter.java; skse Overlay.cpp - read 2026-10-01),
// which targets the exact same Minecraft version (26.3). Differences: its own file (not part of
// mcwow_v1.shm, which is a small fixed struct), D3D9 instead of D3D11 on the consuming side, and
// two small WoW->MC fields (reader heartbeat, back buffer size).
//
// Writer: the Fabric mod (creates the file). Reader: mcwow.dll inside WoW (via Wine's Z: drive -
// a real file in /dev/shm, the same trick mcwow_v1.shm uses; see MCWOW_SHM_PATH_WIN there).
//
// Triple buffer, exactly SkyCraft's scheme: `state` bits 0-1 = index of the "middle" slot, bit 2
// (MCWOW_OVERLAY_DIRTY) = middle holds an unread frame. The writer renders into its private back
// slot, then atomically exchanges state with (back | DIRTY) and keeps the returned index as its
// new back slot. The reader, only when DIRTY is set, exchanges state with its front index and keeps
// the returned index as its new front slot. Neither side ever waits on the other.
// Initial state (set by the writer at creation): writer back = 0, middle = 1, reader front = 2.
// The reader resets its own front to 2 whenever writerPid changes.
//
// Pixels: RGBA8 exactly as Minecraft's GPU readback produced them, PREMULTIPLIED alpha (as
// SkyCraft's shader comment notes), rows bottom-up when slot flags bit0 is set (GL convention).
//
// Byte layout (hand-mirrored in McwowOverlayLink.java - keep both in sync):
//   0   u32 magic            MCWOW_OVERLAY_MAGIC
//   4   u32 version          MCWOW_OVERLAY_VERSION
//   8   u32 writerPid        Minecraft's PID
//   12  u32 state            triple-buffer state, see above (atomic exchange only)
//   16  u64 framesPublished  writer, +1 per published frame
//   24  u64 writerHeartbeat  writer, +1 per Minecraft frame (liveness)
//   32  u32 guiScale         writer, Minecraft's current GUI scale (crosshair invert rect)
//   36  u32 mcFlags          writer, MCWOW_OVERLAY_MC_* bits
//   40  u32 readerHeartbeat  reader (WoW), +1 per WoW frame - Minecraft only hides its own world
//                            rendering while this is advancing
//   44  u32 wowBackBufferW   reader, WoW's back buffer size (for matching the MC window later)
//   48  u32 wowBackBufferH
//   52  u32 inputActive      reader (WoW): 1 while WoW forwards keyboard/mouse to Minecraft (the
//                            InputBridge is on) - Minecraft then acts focused and reads the ring
//   56  f32 mcFovDeg         writer: Minecraft's actual rendered vertical FOV this frame
//                            (Camera.getFov(), sprint/effects included) - WoW's camera follows it
//   60..63 reserved
//   64  slot headers, MCWOW_OVERLAY_SLOTS x 32 bytes:
//         +0 u32 width, +4 u32 height, +8 u32 flags (bit0 bottom-up rows), +12 u32 pad,
//         +16 u64 frameId, +24 u64 pad
//   MCWOW_OVERLAY_OFF_PIXELS  pixels, MCWOW_OVERLAY_SLOTS x MCWOW_OVERLAY_SLOT_BYTES
//   MCWOW_OVERLAY_OFF_INPUT   input ring (WoW produces, Minecraft consumes) - SkyCraft's own input
//                             ring shape: +0 u64 head (WoW), +64 u64 tail (MC), +128 entries,
//                             MCWOW_INPUT_RING_ENTRIES x 16 bytes {u16 type, u16 code, i32 a, b, c}.
//                             Head counts entries ever written; slot = index & (ENTRIES-1). A
//                             consumer more than ENTRIES behind skips to head-ENTRIES.
#pragma once

#include <stdint.h>

#define MCWOW_OVERLAY_PATH_WIN "Z:\\dev\\shm\\mcwow_overlay_v1.shm"
#define MCWOW_OVERLAY_PATH_UNIX "/dev/shm/classiccraft_overlay_v1.shm"
#define MCWOW_OVERLAY_MAGIC 0x6D636F31u // 'mco1'
#define MCWOW_OVERLAY_VERSION 2u // 2: input ring + inputActive (InputBridge)

#define MCWOW_OVERLAY_MAX_W 2560u
#define MCWOW_OVERLAY_MAX_H 1600u
#define MCWOW_OVERLAY_SLOTS 3u
#define MCWOW_OVERLAY_SLOT_BYTES (MCWOW_OVERLAY_MAX_W * MCWOW_OVERLAY_MAX_H * 4u)
#define MCWOW_OVERLAY_OFF_SLOT_HDR 64u
#define MCWOW_OVERLAY_SLOT_HDR_BYTES 32u
#define MCWOW_OVERLAY_OFF_PIXELS 256u
#define MCWOW_OVERLAY_OFF_INPUT (MCWOW_OVERLAY_OFF_PIXELS + MCWOW_OVERLAY_SLOTS * MCWOW_OVERLAY_SLOT_BYTES)
#define MCWOW_INPUT_RING_ENTRIES 4096u // power of two
#define MCWOW_INPUT_RING_HEAD_OFF 0u
#define MCWOW_INPUT_RING_TAIL_OFF 64u
#define MCWOW_INPUT_RING_DATA_OFF 128u
#define MCWOW_OVERLAY_TOTAL_BYTES \
    (MCWOW_OVERLAY_OFF_INPUT + MCWOW_INPUT_RING_DATA_OFF + MCWOW_INPUT_RING_ENTRIES * 16u)

// Input event types - SkyCraft's own values/semantics (protocol/skycraft_protocol.h InputType),
// plus MCWOW_IN_LOOK for raw relative mouse motion while Minecraft has the mouse grabbed.
#define MCWOW_IN_KEY 1           // code = SDL scancode, a = 1 press / 0 release
#define MCWOW_IN_MOUSE_BUTTON 2  // code = SDL button (1 L, 2 M, 3 R, 4 X1, 5 X2), a = 1/0
#define MCWOW_IN_SCROLL 3        // a = wheel delta, 120 per notch (positive = up)
#define MCWOW_IN_CURSOR 4        // a, b = absolute cursor position in overlay (MC framebuffer) px
#define MCWOW_IN_TEXT 5          // a = unicode code point (only while a MC screen is open)
#define MCWOW_IN_RELEASE_ALL 6   // release every held key/button (WoW lost focus, bridge off)
#define MCWOW_IN_LOOK 9          // a, b = raw relative mouse motion (counts), grabbed mode

#define MCWOW_OVERLAY_DIRTY (1u << 2)

#define MCWOW_OVERLAY_MC_CROSSHAIR (1u << 0) // crosshair (+ attack indicator) visible
#define MCWOW_OVERLAY_MC_SCREEN (1u << 1)    // a Minecraft screen (inventory, menu...) is open

typedef struct McwowOverlayHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t writerPid;
    uint32_t state;
    uint64_t framesPublished;
    uint64_t writerHeartbeat;
    uint32_t guiScale;
    uint32_t mcFlags;
    uint32_t readerHeartbeat;
    uint32_t wowBackBufferW;
    uint32_t wowBackBufferH;
    uint32_t inputActive;
    float mcFovDeg;
    uint32_t reserved[1];
} McwowOverlayHeader;

typedef struct McwowOverlaySlotHdr {
    uint32_t width;
    uint32_t height;
    uint32_t flags;      // bit0 = rows bottom-up; bit1 = bandMask valid
    uint32_t pad;
    uint64_t frameId;
    // 2026-10-01: the frame is split into 64 horizontal bands of rows (band i = buffer rows
    // [i*h/64, (i+1)*h/64)). Bit i set = band i has a non-transparent pixel and was written to
    // this slot; clear = fully transparent and NOT written (the slot may hold stale bytes there).
    // Most of the overlay is transparent, so both sides copy only what's there.
    uint64_t bandMask;
} McwowOverlaySlotHdr;
#define MCWOW_OVERLAY_SLOT_BANDMASK 2u
#define MCWOW_OVERLAY_BANDS 64u

#ifdef __cplusplus
static_assert(sizeof(McwowOverlayHeader) == 64, "overlay header layout drifted");
static_assert(sizeof(McwowOverlaySlotHdr) == MCWOW_OVERLAY_SLOT_HDR_BYTES, "slot header drifted");
static_assert(MCWOW_OVERLAY_OFF_SLOT_HDR + MCWOW_OVERLAY_SLOTS * MCWOW_OVERLAY_SLOT_HDR_BYTES <=
                  MCWOW_OVERLAY_OFF_PIXELS,
              "slot headers overlap pixels");
#endif
