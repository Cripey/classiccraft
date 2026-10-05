package net.mcwow.bridge.client;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

import net.minecraft.client.Minecraft;
import net.mcwow.bridge.McwowLinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writer side of the Phase 3 overlay file - hand-mirrors protocol/mcwow_overlay_protocol.h (keep
 * the offsets below in sync with it). Same triple-buffer scheme as chasmlol/SkyCraft's
 * {@code SkyLink.publishOverlay}: render into a private back slot, then atomically swap it into
 * the "middle" with the dirty bit set; WoW takes the middle whenever it's dirty.
 */
public final class McwowOverlayLink {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final String NAME = "classiccraft_overlay_v1.shm";

    private static final int MAGIC = 0x6D636F31; // 'mco1'
    private static final int VERSION = 2; // 2: input ring + inputActive
    public static final int MAX_W = 2560, MAX_H = 1600;
    private static final int SLOTS = 3;
    private static final long SLOT_BYTES = (long) MAX_W * MAX_H * 4;
    private static final long OFF_SLOT_HDR = 64, SLOT_HDR_BYTES = 32, OFF_PIXELS = 256;
    private static final long OFF_INPUT = OFF_PIXELS + SLOTS * SLOT_BYTES;
    private static final int INPUT_ENTRIES = 4096;
    private static final long IR_HEAD = 0, IR_TAIL = 64, IR_DATA = 128;
    private static final long TOTAL = OFF_INPUT + IR_DATA + INPUT_ENTRIES * 16L;
    private static final int DIRTY = 1 << 2;
    public static final int MC_CROSSHAIR = 1, MC_SCREEN = 1 << 1;

    private static final long OFF_MAGIC = 0, OFF_VERSION = 4, OFF_WRITER_PID = 8, OFF_STATE = 12,
            OFF_FRAMES_PUBLISHED = 16, OFF_WRITER_HEARTBEAT = 24, OFF_GUI_SCALE = 32,
            OFF_MC_FLAGS = 36, OFF_READER_HEARTBEAT = 40, OFF_INPUT_ACTIVE = 52, OFF_MC_FOV_DEG = 56;

    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

    private static MemorySegment shm;
    private static boolean triedOpen;
    private static int back = 0;
    private static int lastReaderHeartbeat;
    private static long readerSeenNanos;

    private McwowOverlayLink() {
    }

    private static MemorySegment segment() {
        if (shm == null && !triedOpen) {
            triedOpen = true;
            try {
                MemorySegment s = McwowLinks.map(NAME, TOTAL, true);
                s.set(ValueLayout.JAVA_INT, OFF_MAGIC, MAGIC);
                s.set(ValueLayout.JAVA_INT, OFF_VERSION, VERSION);
                s.set(ValueLayout.JAVA_INT, OFF_WRITER_PID, (int) ProcessHandle.current().pid());
                back = 0;
                INT.setVolatile(s, OFF_STATE, 1); // middle = 1, clean; reader starts on 2
                // Skip anything WoW queued before we attached (stale input from a previous run).
                s.set(ValueLayout.JAVA_LONG, OFF_INPUT + IR_TAIL, (long) LONG.getVolatile(s, OFF_INPUT + IR_HEAD));
                shm = s;
                LOGGER.info("mcwow-bridge: overlay link created {} ({} MB)", McwowLinks.describe(NAME), TOTAL >> 20);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("mcwow-bridge: overlay link failed", e);
            }
        }
        return shm;
    }

    /**
     * True while WoW's mcwow.dll is actively reading (its heartbeat moved within the last second).
     * Minecraft only stops drawing its own world while this holds, so it renders normally
     * whenever WoW isn't running.
     */
    public static boolean linked() {
        MemorySegment s = segment();
        if (s == null) return false;
        int hb = (int) INT.getVolatile(s, OFF_READER_HEARTBEAT);
        long now = System.nanoTime();
        if (hb != lastReaderHeartbeat) {
            lastReaderHeartbeat = hb;
            readerSeenNanos = now;
        }
        return readerSeenNanos != 0 && now - readerSeenNanos < 1_000_000_000L;
    }

    /** True while WoW forwards its keyboard/mouse to Minecraft (Numpad+ bridge on, WoW alive). */
    public static boolean inputActive() {
        MemorySegment s = segment();
        return s != null && linked() && (int) INT.getVolatile(s, OFF_INPUT_ACTIVE) != 0;
    }

    public interface InputSink {
        void accept(int type, int code, int a, int b, int c);
    }

    /** Drains WoW-captured input in order - SkyCraft's SkyLink.drainInput. */
    public static void drainInput(InputSink sink) {
        MemorySegment s = segment();
        if (s == null) return;
        long head = (long) LONG.getAcquire(s, OFF_INPUT + IR_HEAD);
        long tail = s.get(ValueLayout.JAVA_LONG, OFF_INPUT + IR_TAIL);
        if (head < tail) tail = head; // WoW side restarted its counter
        if (head - tail > INPUT_ENTRIES) tail = head - INPUT_ENTRIES; // lapped: drop the oldest
        while (tail < head) {
            long e = OFF_INPUT + IR_DATA + (tail & (INPUT_ENTRIES - 1)) * 16L;
            int type = Short.toUnsignedInt(s.get(ValueLayout.JAVA_SHORT, e));
            int code = Short.toUnsignedInt(s.get(ValueLayout.JAVA_SHORT, e + 2));
            int a = s.get(ValueLayout.JAVA_INT, e + 4);
            int b = s.get(ValueLayout.JAVA_INT, e + 8);
            int c = s.get(ValueLayout.JAVA_INT, e + 12);
            tail++;
            sink.accept(type, code, a, b, c);
        }
        LONG.setRelease(s, OFF_INPUT + IR_TAIL, tail);
    }

    /** Once per Minecraft frame: liveness + what the overlay currently shows. */
    public static void writeFrameState(Minecraft mc) {
        MemorySegment s = segment();
        if (s == null) return;
        int flags = 0;
        if (mc.gui.screen() != null) flags |= MC_SCREEN;
        else if (mc.player != null && mc.options.getCameraType().isFirstPerson()) flags |= MC_CROSSHAIR;
        s.set(ValueLayout.JAVA_INT, OFF_GUI_SCALE, mc.getWindow().getGuiScale());
        s.set(ValueLayout.JAVA_INT, OFF_MC_FLAGS, flags);
        // Live FOV incl. bow zoom / sprint; mcwow.dll eases WoW toward it (perf fovrate) because
        // WoW stalls on big FOV jumps (2026-10-01).
        s.set(ValueLayout.JAVA_FLOAT, OFF_MC_FOV_DEG, mc.gameRenderer.mainCamera().getFov());
        LONG.getAndAdd(s, OFF_WRITER_HEARTBEAT, 1L);
    }

    public static long backSlotOffset() {
        return OFF_PIXELS + back * SLOT_BYTES;
    }

    public static MemorySegment shm() {
        return segment();
    }

    /** Publishes the frame just written into the back slot. */
    public static void publish(int width, int height, boolean bottomUp, long frameId, long bandMask) {
        MemorySegment s = segment();
        if (s == null) return;
        long hdr = OFF_SLOT_HDR + back * SLOT_HDR_BYTES;
        s.set(ValueLayout.JAVA_INT, hdr, width);
        s.set(ValueLayout.JAVA_INT, hdr + 4, height);
        s.set(ValueLayout.JAVA_INT, hdr + 8, (bottomUp ? 1 : 0) | 2); // bit1: bandMask valid
        s.set(ValueLayout.JAVA_LONG, hdr + 16, frameId);
        s.set(ValueLayout.JAVA_LONG, hdr + 24, bandMask);
        int old = (int) INT.getAndSet(s, OFF_STATE, back | DIRTY);
        back = old & 3;
        LONG.getAndAdd(s, OFF_FRAMES_PUBLISHED, 1L);
    }
}
