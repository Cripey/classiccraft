package net.mcwow.bridge.client;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import net.mcwow.bridge.McwowGeomStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Consumer for geom_server's real-geometry ring (protocol/mcwow_geom_protocol.h) - a NEW native
// Linux helper process (not WoW/mcwow.dll - this file is never touched by Wine, both ends are
// native Linux processes) that reads AzerothCore's own, unmodified VMAP/terrain files directly
// and streams real triangles. Byte-for-byte mirrors chasmlol/SkyCraft's own SkyCollision.java
// consumer-thread pattern (a background daemon thread polling head/tail, draining whatever's
// new), confirmed live 2026-10-01: geom_server published ~35 real named models (Sack01, Barrel01,
// Anvil, Stormwindgypsywagon01, ...) and continuous terrain patches for the real Northshire
// Abbey blacksmith-yard scene, this consumer's job is just to read them faithfully.
public final class McwowGeomClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final String SHM_PATH = "/dev/shm/classiccraft_geom_v1.shm";
    private static final int MAGIC = 0x6D636731; // 'mcg1'
    private static final int VERSION = 3; // v2 (2026-10-01): persistent cells + eviction queue; v3: decks

    // protocol/mcwow_geom_protocol.h byte offsets - hand-mirrored (Java can't #include a C
    // header). Cross-checked against geom_server's own startup log, which prints the real total
    // size it mmap'd (16777280 bytes) - matches OFF_RING(64) + RING_BYTES(16*1024*1024) exactly,
    // a free sanity check this project's earlier offset mistakes (protocol/mcwow_protocol.h,
    // same session) didn't have available.
    private static final int OFF_MAGIC = 0, OFF_VERSION = 4, OFF_WRITER_PID = 8,
            OFF_READER_PID = 12, OFF_WRITER_HEARTBEAT = 16, OFF_REFRESH_REQUEST = 24;
    private static final int OFF_RING = 64;
    private static final int OFF_RING_HEAD = OFF_RING;      // u64
    private static final int OFF_RING_TAIL = OFF_RING + 8;  // u64
    private static final int OFF_RING_DATA = OFF_RING + 64;
    private static final long RING_BYTES = 16L * 1024 * 1024;
    private static final long RING_DATA_BYTES = RING_BYTES - 64;
    // v2 eviction queue after the ring: u64 head (we write) then EVICT_SLOTS u64 cell ids.
    private static final long OFF_EVICT = OFF_RING + RING_BYTES;
    private static final int EVICT_SLOTS = 4096;
    // v3 transport decks (2026-10-02): benilla's pose table, then the rider block (we write).
    private static final int OFF_DECKS = (int) (OFF_EVICT + 16 + EVICT_SLOTS * 8L);
    private static final int DECK_SLOTS = 16;
    private static final int OFF_RIDER = OFF_DECKS + 16 + DECK_SLOTS * 32;
    private static final long TOTAL_BYTES = OFF_RIDER + 64;

    private static final int MSG_PAD = 0, MSG_CLEAR = 1, MSG_BATCH = 2, MSG_REMOVE = 3, MSG_TERRAIN = 4,
            MSG_DECK = 5, MSG_DECK_GONE = 6, MSG_BOOK = 7, MSG_DIALOG = 8, MSG_QUESTLOG = 9;
    /** MSG_TERRAIN: i32 cx, i32 cz, then 256 columns of {f32 surface, f32 fillTop, u8 material, u8 flags, u16 area}. */
    private static final int TERRAIN_BYTES = 8 + 256 * 16; // 16-byte columns since outdoor water (2026-10-02)
    private static final int TRI_WALKABLE = 1;

    private static MappedByteBuffer buf;
    private static boolean triedOpen;
    private static long myTail;
    private static boolean everOpened;
    private static long lastStoreLogNanos;
    private static int lastWriterPid = -1; // sentinel: never a real PID

    private McwowGeomClient() {
    }

    public static synchronized void startIfNeeded() {
        if (everOpened || triedOpen) return;
        Thread t = new Thread(McwowGeomClient::run, "mcwow-geom-consumer");
        // Daemon: a non-daemon polling thread kept the JVM alive after Minecraft closed, and the
        // client shutdown watchdog then wrote a crash report on every normal quit.
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        // Opening can race geom_server's own startup (it creates+truncates the file); retry
        // with a short sleep instead of giving up once, same "don't retry every tick" discipline
        // as McwowBridgeClient's own tryOpen, just on a background thread instead of the tick
        // loop so it never blocks rendering.
        while (buf == null) {
            if (tryOpen()) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
        everOpened = true;
        LOGGER.info("mcwow-bridge: geom consumer attached to {}", SHM_PATH);

        while (true) {
            try {
                drainOnce();
                Thread.sleep(50); // 20Hz poll - geom_server itself only publishes at ~5Hz
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LOGGER.error("mcwow-bridge: geom consumer error", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static boolean tryOpen() {
        try {
            RandomAccessFile raf = new RandomAccessFile(SHM_PATH, "rw");
            FileChannel ch = raf.getChannel();
            if (raf.length() < TOTAL_BYTES) return false; // geom_server hasn't ftruncate'd yet
            buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, TOTAL_BYTES);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            if (buf.getInt(OFF_MAGIC) != MAGIC || buf.getInt(OFF_VERSION) != VERSION) {
                buf = null;
                return false;
            }
            buf.putInt(OFF_READER_PID, (int) ProcessHandle.current().pid());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Tells geom_server a cell was dropped from the store (McwowGeomCache), so it sends it again later. */
    public static synchronized void evict(long cellId) {
        MappedByteBuffer b = buf;
        if (b == null) return;
        long head = b.getLong((int) OFF_EVICT);
        b.putLong((int) (OFF_EVICT + 16 + (head % EVICT_SLOTS) * 8), cellId);
        b.putLong((int) OFF_EVICT, head + 1); // single writer; geom_server reads head, then the slots below it
    }

    /** benilla's deck poses this frame (seqlocked; null while it writes or before attach). */
    public static java.util.Map<Long, net.mcwow.bridge.McwowDecks.Pose> readDeckPoses() {
        MappedByteBuffer b = buf;
        if (b == null) return null;
        int seq = b.getInt(OFF_DECKS);
        if ((seq & 1) != 0) return null;
        int count = Math.min(DECK_SLOTS, b.getInt(OFF_DECKS + 4));
        java.util.Map<Long, net.mcwow.bridge.McwowDecks.Pose> out = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            int at = OFF_DECKS + 16 + i * 32;
            out.put(b.getLong(at), new net.mcwow.bridge.McwowDecks.Pose(b.getFloat(at + 8), b.getFloat(at + 12),
                    b.getFloat(at + 16), b.getFloat(at + 20)));
        }
        return b.getInt(OFF_DECKS) == seq ? out : null;
    }

    /** The rider block: Steve's eye, feet and yaw relative to the deck he stands on (guid 0: none). */
    public static void writeRider(long guid, double[] eye, double[] feet, float yawDeg) {
        MappedByteBuffer b = buf;
        if (b == null) return;
        int seq = b.getInt(OFF_RIDER);
        b.putInt(OFF_RIDER, seq | 1);
        b.putLong(OFF_RIDER + 8, guid);
        boolean pose = guid != 0 && eye != null && feet != null;
        if (pose) {
            for (int k = 0; k < 3; k++) {
                b.putFloat(OFF_RIDER + 16 + k * 4, (float) eye[k]);
                b.putFloat(OFF_RIDER + 28 + k * 4, (float) feet[k]);
            }
            b.putFloat(OFF_RIDER + 40, yawDeg);
        }
        // Bit 0: the pose is present; a guid without it is "aboard, deck out of sight".
        b.putInt(OFF_RIDER + 44, pose ? 1 : 0);
        b.putInt(OFF_RIDER, (seq | 1) + 1);
    }

    /** Ask geom_server for an immediate full republish (e.g. Steve was placed somewhere new). */
    public static void requestRefresh() {
        MappedByteBuffer b = buf;
        if (b != null) b.putInt(OFF_REFRESH_REQUEST, b.getInt(OFF_REFRESH_REQUEST) + 1);
    }

    private static long align8(long v) {
        return (v + 7) & ~7L;
    }

    private static void drainOnce() {
        // A fresh geom_server launch resets ITS OWN ring head to 0 unconditionally (it has no
        // way to know a reader is already attached and tracking a possibly-much-larger tail) -
        // confirmed live 2026-10-01: restarting geom_server while this consumer was already
        // running left it silently stuck forever (myTail stayed at its old high value, head
        // restarted from 0, so `myTail >= head` held indefinitely until head grew all the way
        // back past the stale myTail). writerPid changing is this project's existing pattern
        // for "a new instance of the other side" (same idea as McwowHeader's wowPid) - treat it
        // as a hard resync: jump straight to wherever the new writer currently is (not try to
        // replay from 0) and drop any geometry we'd stored from the old instance, since
        // geom_server's own periodic full-refresh (see its kFullRefreshInterval) will republish
        // everything fresh within a bounded time anyway.
        int writerPid = buf.getInt(OFF_WRITER_PID);
        if (writerPid != lastWriterPid) {
            if (lastWriterPid != -1) {
                LOGGER.info("mcwow-bridge: geom writer changed ({} -> {}) - resyncing",
                           lastWriterPid, writerPid);
                McwowGeomStore.clear();
                net.mcwow.bridge.McwowTerrainStore.clear();
            }
            lastWriterPid = writerPid;
            myTail = buf.getLong(OFF_RING_HEAD);
            // Don't wait up to 20s for the periodic refresh - ask for everything now.
            buf.putInt(OFF_REFRESH_REQUEST, buf.getInt(OFF_REFRESH_REQUEST) + 1);
        }

        // Time-throttled (~5s), not gated behind "was there new data this call" - geom_server
        // publishes in bursts (everything from one refresh cycle lands in a single drainOnce()),
        // so a counter of non-empty calls could go hundreds of seconds between increments even
        // while the store is full and healthy - confirmed live 2026-10-01: a counter-based
        // throttle here never fired at all during a real, working test, making it look silently
        // broken when it wasn't.
        long nowNanos = System.nanoTime();
        if (nowNanos - lastStoreLogNanos > 5_000_000_000L) {
            lastStoreLogNanos = nowNanos;
            LOGGER.info("mcwow-bridge: geom store has {} objects, {} triangles",
                       McwowGeomStore.objectCount(), McwowGeomStore.triangleCount());
        }

        long head = buf.getLong(OFF_RING_HEAD);
        if (myTail == head) return;
        // Lapped (writer got a whole ring ahead) or myTail is past head (a previous bad read).
        if (myTail > head || head - myTail > RING_DATA_BYTES) {
            resync(head, "position out of range (tail=" + myTail + " head=" + head + ")");
            return;
        }

        int guard = 0;
        while (myTail < head && guard++ < 10000) {
            long pos = myTail % RING_DATA_BYTES;
            int base = (int) (OFF_RING_DATA + pos);
            int type = buf.getInt(base);
            int payloadBytes = buf.getInt(base + 4);

            if (type == MSG_PAD) {
                myTail += (RING_DATA_BYTES - pos);
                continue;
            }

            // Validate BEFORE trusting anything: one misread header used to send myTail ~1GB past
            // head (live 2026-10-01), after which this reader silently stopped consuming forever
            // and the store froze with stale/partial geometry - "collision with everything stopped
            // working". A bad header now just resyncs and asks geom_server to republish.
            long size = align8(8L + (payloadBytes & 0xFFFFFFFFL));
            if (type < MSG_CLEAR || type > MSG_QUESTLOG || payloadBytes < 0
                    || (type == MSG_TERRAIN && payloadBytes != TERRAIN_BYTES)
                    || pos + size > RING_DATA_BYTES || myTail + size > head
                    || ((type == MSG_BATCH || type == MSG_DECK) && (payloadBytes < 16
                        || 16L + 40L * buf.getInt(base + 8 + 8) != payloadBytes))) {
                resync(buf.getLong(OFF_RING_HEAD), "bad message header type=" + type
                        + " payloadBytes=" + payloadBytes + " at tail=" + myTail);
                return;
            }

            int payloadOff = base + 8;
            // Parse into locals first; only APPLY once we've confirmed the writer didn't overwrite
            // these bytes while we were reading them (it lapped us if head is now a full ring
            // past the END of this message).
            McwowGeomStore.Tri[] batch = null;
            net.mcwow.bridge.McwowTerrainStore.Chunk terrain = null;
            long objectId = 0;
            int epoch = 0;
            String[] book = null;
            net.mcwow.bridge.McwowDialog.Window dialog = null;
            byte[] questLog = null;
            switch (type) {
                case MSG_CLEAR -> epoch = buf.getInt(payloadOff);
                case MSG_REMOVE, MSG_DECK_GONE -> objectId = buf.getLong(payloadOff);
                case MSG_BOOK -> book = readBook(payloadOff, payloadBytes);
                case MSG_DIALOG -> {
                    byte[] raw = new byte[payloadBytes];
                    buf.get(payloadOff, raw);
                    dialog = McwowDialogs.parse(raw);
                }
                case MSG_QUESTLOG -> {
                    questLog = new byte[payloadBytes];
                    buf.get(payloadOff, questLog);
                }
                case MSG_TERRAIN -> terrain = readTerrain(payloadOff);
                default -> {
                    objectId = buf.getLong(payloadOff);
                    batch = readBatch(payloadOff);
                }
            }
            if (buf.getLong(OFF_RING_HEAD) - myTail > RING_DATA_BYTES - size) {
                resync(buf.getLong(OFF_RING_HEAD), "writer overwrote a message while reading it");
                return;
            }
            switch (type) {
                case MSG_CLEAR -> {
                    McwowGeomStore.clear();
                    net.mcwow.bridge.McwowTerrainStore.clear();
                    net.mcwow.bridge.McwowDecks.clear();
                    LOGGER.info("mcwow-bridge: geom CLEAR (epoch={})", epoch);
                }
                case MSG_REMOVE -> McwowGeomStore.remove(objectId);
                case MSG_BOOK -> {
                    if (book != null) McwowInteract.showBook(book);
                }
                case MSG_DIALOG -> {
                    if (dialog != null) McwowDialogs.show(dialog);
                }
                case MSG_QUESTLOG -> {
                    if (questLog != null) McwowQuestLog.update(questLog);
                }
                case MSG_DECK_GONE -> {
                    net.mcwow.bridge.McwowDecks.remove(objectId);
                    LOGGER.info("mcwow-bridge: deck {} gone", Long.toHexString(objectId));
                }
                case MSG_DECK -> {
                    float[] v = new float[batch.length * 9];
                    boolean[] walkable = new boolean[batch.length];
                    for (int i = 0; i < batch.length; i++) {
                        McwowGeomStore.Tri t = batch[i];
                        float[] tv = { t.x0, t.y0, t.z0, t.x1, t.y1, t.z1, t.x2, t.y2, t.z2 };
                        System.arraycopy(tv, 0, v, i * 9, 9);
                        walkable[i] = t.walkable;
                    }
                    net.mcwow.bridge.McwowDecks.put(objectId, v, walkable);
                    LOGGER.info("mcwow-bridge: deck {} ({} triangles)", Long.toHexString(objectId), batch.length);
                }
                case MSG_TERRAIN -> {
                    if (!net.mcwow.bridge.McwowTerrainStore.put(buf.getInt(payloadOff), buf.getInt(payloadOff + 4), terrain)) {
                        LOGGER.info("mcwow-bridge: terrain store full - dropped, asking for a resend");
                        buf.putInt(OFF_REFRESH_REQUEST, buf.getInt(OFF_REFRESH_REQUEST) + 1);
                    }
                }
                default -> {
                    McwowGeomStore.put(objectId, batch);
                    McwowHoleExporter.cellChanged(objectId); // hole walls sample these triangles
                }
            }
            myTail += size;
        }
        buf.putLong(OFF_RING_TAIL, myTail);
    }

    // Jump to the writer's current position and ask it for an immediate full republish. The
    // store is deliberately KEPT (not cleared) - slightly stale geometry is far better than none
    // while the republish arrives; the republish starts with a CLEAR anyway.
    private static void resync(long head, String why) {
        LOGGER.warn("mcwow-bridge: geom ring resync - {}", why);
        myTail = head;
        buf.putLong(OFF_RING_TAIL, myTail);
        buf.putInt(OFF_REFRESH_REQUEST, buf.getInt(OFF_REFRESH_REQUEST) + 1);
    }

    /** MSG_BOOK: title, then the pages; null if malformed. */
    private static String[] readBook(int off, int bytes) {
        int end = off + bytes;
        java.util.List<String> out = new java.util.ArrayList<>();
        int at = off;
        int count = -1;
        while (at + 4 <= end) {
            if (out.size() == 1 && count < 0) {
                count = buf.getInt(at);
                at += 4;
                continue;
            }
            int n = buf.getInt(at);
            if (n < 0 || at + 4 + n > end) return null;
            byte[] b = new byte[n];
            buf.get(at + 4, b);
            out.add(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            at += 4 + n;
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    private static net.mcwow.bridge.McwowTerrainStore.Chunk readTerrain(int payloadOff) {
        float[] surface = new float[256], fillTop = new float[256];
        float[] liquidTop = new float[256];
        byte[] material = new byte[256], liquid = new byte[256];
        short[] area = new short[256];
        for (int i = 0; i < 256; ++i) {
            int o = payloadOff + 8 + i * 16;
            surface[i] = buf.getFloat(o);
            fillTop[i] = buf.getFloat(o + 4);
            material[i] = buf.get(o + 8);
            liquid[i] = buf.get(o + 9);
            area[i] = buf.getShort(o + 10);
            liquidTop[i] = buf.getFloat(o + 12);
        }
        return new net.mcwow.bridge.McwowTerrainStore.Chunk(surface, fillTop, material, area, liquid, liquidTop);
    }

    private static McwowGeomStore.Tri[] readBatch(int payloadOff) {
        int count = buf.getInt(payloadOff + 8);
        // kind (payloadOff + 12) not yet consumed - model vs terrain is informational for now,
        // both are resolved identically by McwowTriCollider.
        int triOff = payloadOff + 16;
        McwowGeomStore.Tri[] tris = new McwowGeomStore.Tri[count];
        for (int i = 0; i < count; ++i) {
            int o = triOff + i * 40;
            float x0 = buf.getFloat(o), y0 = buf.getFloat(o + 4), z0 = buf.getFloat(o + 8);
            float x1 = buf.getFloat(o + 12), y1 = buf.getFloat(o + 16), z1 = buf.getFloat(o + 20);
            float x2 = buf.getFloat(o + 24), y2 = buf.getFloat(o + 28), z2 = buf.getFloat(o + 32);
            int flags = buf.getInt(o + 36);
            tris[i] = new McwowGeomStore.Tri(x0, y0, z0, x1, y1, z1, x2, y2, z2,
                                             (flags & TRI_WALKABLE) != 0);
        }
        return tris;
    }
}
