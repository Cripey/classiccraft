package net.mcwow.bridge.client;

import java.lang.foreign.MemorySegment;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies Minecraft's main render target (hand + HUD + screens on a transparent background, since
 * {@code LevelRendererMixin} skips the world while linked) back from the GPU and publishes it to
 * WoW through {@link McwowOverlayLink}. A port of chasmlol/SkyCraft's {@code FrameExporter}
 * (same Minecraft version, 26.3): the copy is asynchronous - a frame is captured into one of a few
 * staging buffers and shipped once the GPU says the copy finished, typically a frame later - so
 * Minecraft never stalls waiting on a readback.
 */
public final class McwowFrameExporter {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final int STAGING = 3;
    private static final int FREE = 0, PENDING = 1, READY = 2;

    private static final Staging[] staging = new Staging[STAGING];
    private static long nextFrameId = 1;
    private static boolean loggedFormat;
    private static boolean loggedFirstPixel;

    private static final class Staging {
        GpuBuffer buffer;
        int width;
        int height;
        volatile int state = FREE;
        long frameId;
    }

    private McwowFrameExporter() {
    }

    /** Called right after GameRenderer.render() every Minecraft frame. */
    private static long lastFrameNanos;

    public static void afterRender(Minecraft minecraft) {
        long t0 = System.nanoTime();
        McwowOverlayLink.writeFrameState(minecraft);
        McwowBridgeClient.publishFrame(); // camera + feet for WoW, once per rendered frame
        long t1 = System.nanoTime();
        McwowWorldExporter.frame(minecraft); // blocks as geometry for WoW to draw (render ring)
        long t2 = System.nanoTime();
        if (McwowOverlayLink.linked()) capture(minecraft);
        long t3 = System.nanoTime();
        // Spike diagnostics (2026-10-01, "lag spike when releasing a fully drawn bow"): any
        // Minecraft frame over 50 ms, with how much of it was ours.
        if (lastFrameNanos != 0 && t0 - lastFrameNanos > 50_000_000L) {
            LOGGER.info("mcwow-bridge: SLOW FRAME {} ms (ours: state+publish {} ms, world/entity export {} ms, overlay capture {} ms)",
                    (t0 - lastFrameNanos) / 1_000_000, (t1 - t0) / 1_000_000.0, (t2 - t1) / 1_000_000.0, (t3 - t2) / 1_000_000.0);
        }
        lastFrameNanos = t0;
    }

    private static void capture(Minecraft minecraft) {
        shipReadyFrames();

        RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        GpuTexture color = target.getColorTexture();
        if (color == null) return;
        int width = target.width;
        int height = target.height;
        if (width > McwowOverlayLink.MAX_W || height > McwowOverlayLink.MAX_H) return;
        if (!loggedFormat) {
            loggedFormat = true;
            LOGGER.info("mcwow-bridge: overlay capture {}x{} format {}", width, height, color.getFormat());
        }

        Staging slot = null;
        for (Staging s : staging) {
            if (s != null && s.state == FREE) {
                slot = s;
                break;
            }
        }
        if (slot == null) {
            for (int i = 0; i < STAGING; i++) {
                if (staging[i] == null) {
                    staging[i] = slot = new Staging();
                    break;
                }
            }
        }
        if (slot == null) return; // all staging buffers still in flight; skip this frame

        long bytes = (long) width * height * 4L;
        if (slot.buffer == null || slot.width != width || slot.height != height) {
            if (slot.buffer != null) slot.buffer.close();
            // usage 9 = MAP_READ | COPY_DST, as SkyCraft uses
            slot.buffer = RenderSystem.getDevice().createBuffer(() -> "mcwow overlay readback", 9, bytes);
            slot.width = width;
            slot.height = height;
        }
        final Staging captured = slot;
        captured.state = PENDING;
        captured.frameId = nextFrameId++;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, captured.buffer, 0L,
                () -> captured.state = READY, 0);
    }

    private static final int BANDS = 64; // MCWOW_OVERLAY_BANDS

    /**
     * Copies only the row bands that contain a non-transparent pixel (2026-10-01: most of the
     * overlay is empty, and moving the whole 2560x1440 frame cost WoW ~3 ms per frame). Returns
     * the band mask (bit i = band i copied); transparent bands are left unwritten - WoW clears
     * them on its side. Scanned 8 bytes (2 pixels) at a time; any non-zero byte counts.
     */
    private static long copyBands(MemorySegment src, MemorySegment shm, long dstOff, int width,
                                  int height, long bytes) {
        long rowBytes = (long) width * 4L;
        long mask = 0;
        for (int b = 0; b < BANDS; b++) {
            long r0 = (long) b * height / BANDS, r1 = (long) (b + 1) * height / BANDS;
            long start = r0 * rowBytes, end = Math.min(r1 * rowBytes, bytes);
            if (start >= end) continue;
            boolean any = false;
            for (long o = start; o + 8 <= end; o += 8) {
                if (src.get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, o) != 0L) {
                    any = true;
                    break;
                }
            }
            if (!any) continue;
            MemorySegment.copy(src, start, shm, dstOff + start, end - start);
            mask |= 1L << b;
        }
        return mask;
    }

    /** Maps the newest finished readback and copies it into shared memory. */
    private static void shipReadyFrames() {
        Staging newest = null;
        for (Staging s : staging) {
            if (s != null && s.state == READY && (newest == null || s.frameId > newest.frameId)) {
                newest = s;
            }
        }
        if (newest == null) return;
        MemorySegment shm = McwowOverlayLink.shm();
        long bandMask = 0;
        if (shm != null) {
            long bytes = (long) newest.width * newest.height * 4L;
            try (GpuBufferSlice.MappedView view = newest.buffer.map(true, false)) {
                MemorySegment src = MemorySegment.ofBuffer(view.data());
                if (!loggedFirstPixel && src.byteSize() >= 4) {
                    loggedFirstPixel = true;
                    // Must be 00000000 (transparent): proves Minecraft's frame is only hand + HUD
                    // with no cleared/opaque background that would cover WoW's picture.
                    LOGGER.info("mcwow-bridge: first overlay frame corner pixel RGBA={}",
                            String.format("%08x", Integer.reverseBytes(src.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0))));
                }
                bandMask = copyBands(src, shm, McwowOverlayLink.backSlotOffset(), newest.width,
                        newest.height, Math.min(bytes, src.byteSize()));
            }
            // OpenGL backend (confirmed in the log: "Using graphics backend OpenGL") reads rows
            // bottom-up, same as SkyCraft assumes.
            McwowOverlayLink.publish(newest.width, newest.height, true, newest.frameId, bandMask);
        }
        for (Staging s : staging) {
            if (s != null && s.state == READY && s.frameId <= newest.frameId) s.state = FREE;
        }
    }
}
