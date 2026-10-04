package net.mcwow.bridge.client;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writer side of the render ring (protocol/mcwow_render_protocol.h): Minecraft world content as
 * geometry for mcwow.dll to draw inside WoW's frame. Port of SkyCraft's SkyLink.writeRender:
 * flow-controlled byte ring - a message is only written when it fits behind the reader's tail, so
 * nothing is ever overwritten unread. Render thread only.
 */
public final class McwowRenderLink {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final String PATH = "/dev/shm/classiccraft_render_v1.shm";
    private static final int MAGIC = 0x6D637772, VERSION = 1;
    private static final long RING_OFF = 256, RING_BYTES = 64L << 20;
    private static final long HEAD = 0x00, TAIL = 0x40, DATA = 0x80, DATA_BYTES = RING_BYTES - DATA;
    private static final long TOTAL = RING_OFF + RING_BYTES;
    private static final long OFF_MAGIC = 0, OFF_VERSION = 4, OFF_WRITER_PID = 8, OFF_READER_HB = 16,
            OFF_RESEND = 24;

    public static final int REN_PAD = 0, REN_ATLAS = 1, REN_SECTION = 2, REN_CLEAR_ALL = 3, REN_TEXTURE = 4, REN_AVATAR = 5,
            REN_SCENE = 6, REN_ATLAS_REGION = 7;
    public static final int VERTEX_BYTES = 32;

    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

    private static MemorySegment shm;
    private static boolean triedOpen;
    private static long lastReaderHb;
    private static long readerSeenNanos;
    private static int lastResend;
    private static int generation;

    private McwowRenderLink() {
    }

    private static MemorySegment segment() {
        if (shm == null && !triedOpen) {
            triedOpen = true;
            try (FileChannel ch = FileChannel.open(Path.of(PATH), StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                MemorySegment s = ch.map(FileChannel.MapMode.READ_WRITE, 0, TOTAL, Arena.global());
                s.set(ValueLayout.JAVA_INT, OFF_MAGIC, MAGIC);
                s.set(ValueLayout.JAVA_INT, OFF_VERSION, VERSION);
                s.set(ValueLayout.JAVA_LONG, RING_OFF + HEAD, 0L);
                s.set(ValueLayout.JAVA_LONG, RING_OFF + TAIL, 0L);
                lastResend = s.get(ValueLayout.JAVA_INT, OFF_RESEND);
                // PID last: the reader treats a PID change as "new writer: drop everything, start at head".
                INT.setRelease(s, OFF_WRITER_PID, (int) ProcessHandle.current().pid());
                shm = s;
                LOGGER.info("mcwow-bridge: render link created {} ({} MB)", PATH, TOTAL >> 20);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("mcwow-bridge: render link failed", e);
            }
        }
        return shm;
    }

    /** True while mcwow.dll is consuming the ring (its heartbeat advanced within the last 2 s). */
    public static boolean active() {
        MemorySegment s = segment();
        if (s == null) return false;
        long hb = (long) LONG.getVolatile(s, OFF_READER_HB);
        long now = System.nanoTime();
        if (hb != lastReaderHb) {
            lastReaderHb = hb;
            readerSeenNanos = now;
        }
        return readerSeenNanos != 0 && now - readerSeenNanos < 2_000_000_000L;
    }

    /**
     * Bumps whenever everything must be sent again: the reader asked (it attached or restarted) or
     * went away and came back.
     */
    public static int generation() {
        MemorySegment s = segment();
        if (s == null) return generation;
        int req = (int) INT.getVolatile(s, OFF_RESEND);
        if (req != lastResend) {
            lastResend = req;
            generation++;
            LOGGER.info("mcwow-bridge: WoW asked for a full render resend (#{})", req);
        }
        return generation;
    }

    private static final long OFF_LIGHT_SEQ = 32, OFF_WOW_AMBIENT = 36, OFF_WOW_SUN = 48;
    private static int lastLightSeq;
    private static long lightSeenNanos;

    /**
     * WoW's current {ambient r,g,b, sun r,g,b} (written by mcwow.dll every frame), or null if it
     * hasn't updated within the last second.
     */
    public static float[] wowLight() {
        MemorySegment s = segment();
        if (s == null) return null;
        int seq = (int) INT.getVolatile(s, OFF_LIGHT_SEQ);
        long now = System.nanoTime();
        if (seq != lastLightSeq) {
            lastLightSeq = seq;
            lightSeenNanos = now;
        }
        if (seq == 0 || now - lightSeenNanos > 1_000_000_000L) return null;
        float[] out = new float[6];
        for (int i = 0; i < 3; i++) {
            out[i] = s.get(ValueLayout.JAVA_FLOAT, OFF_WOW_AMBIENT + 4L * i);
            out[3 + i] = s.get(ValueLayout.JAVA_FLOAT, OFF_WOW_SUN + 4L * i);
        }
        return out;
    }

    /** Writes a message, waiting up to ~1 s for room. False = not sent (caller retries later). */
    public static boolean write(int type, ByteBuffer header, ByteBuffer body) {
        return write(type, header, body, 500);
    }

    /** Like write, but gives up at once if the ring is full (per-frame data the next frame replaces). */
    public static boolean tryWrite(int type, ByteBuffer header, ByteBuffer body) {
        return write(type, header, body, 1);
    }

    private static boolean write(int type, ByteBuffer header, ByteBuffer body, int attempts) {
        MemorySegment s = segment();
        if (s == null) return false;
        int payload = header.remaining() + (body != null ? body.remaining() : 0);
        long msgBytes = (8 + payload + 7) & ~7L;
        if (msgBytes > DATA_BYTES / 2) {
            LOGGER.warn("mcwow-bridge: render message too large ({} bytes)", msgBytes);
            return false;
        }
        for (int attempt = 0; attempt < attempts; attempt++) {
            long head = s.get(ValueLayout.JAVA_LONG, RING_OFF + HEAD);
            long tail = (long) LONG.getAcquire(s, RING_OFF + TAIL);
            long pos = head % DATA_BYTES;
            long pad = pos + msgBytes > DATA_BYTES ? DATA_BYTES - pos : 0;
            if (DATA_BYTES - (head - tail) < msgBytes + pad) {
                if (attempt + 1 >= attempts) break;
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return false;
                }
                continue;
            }
            if (pad > 0) {
                s.set(ValueLayout.JAVA_INT, RING_OFF + DATA + pos, REN_PAD);
                s.set(ValueLayout.JAVA_INT, RING_OFF + DATA + pos + 4, 0);
                head += pad;
                pos = 0;
            }
            long at = RING_OFF + DATA + pos;
            s.set(ValueLayout.JAVA_INT, at, type);
            s.set(ValueLayout.JAVA_INT, at + 4, payload);
            MemorySegment.copy(MemorySegment.ofBuffer(header), 0, s, at + 8, header.remaining());
            if (body != null && body.remaining() > 0) {
                MemorySegment.copy(MemorySegment.ofBuffer(body), 0, s, at + 8 + header.remaining(), body.remaining());
            }
            LONG.setRelease(s, RING_OFF + HEAD, head + msgBytes);
            return true;
        }
        return false;
    }
}
