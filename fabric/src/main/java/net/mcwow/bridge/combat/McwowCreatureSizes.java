package net.mcwow.bridge.combat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A creature's collision height from its display id, exactly as AzerothCore's
 * Unit::GetCollisionHeight computes it: objectScale * CreatureModelData.CollisionHeight *
 * CreatureModelData.Scale * CreatureDisplayInfo.scale (DBC layouts per DBCfmt.h:
 * CreatureDisplayInfo "nixif..." = id, modelId, -, extendedId, scale; CreatureModelData
 * "nixxf.........fff" = id, flags, -, -, scale, ..., collisionWidth(14), collisionHeight(15)).
 * Read once from the server's own extracted DBCs: system property {@code mcwow.dbcDir}, else
 * environment variable {@code MCWOW_DBC_DIR}; without either, bounding radius only.
 */
public final class McwowCreatureSizes {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final Path DBC_DIR = Path.of(System.getProperty("mcwow.dbcDir",
            System.getenv().getOrDefault("MCWOW_DBC_DIR", "")));
    /** displayId -> {height, width} in yards, before object scale. */
    private static Map<Integer, float[]> sizes;

    private McwowCreatureSizes() {
    }

    private record Dbc(ByteBuffer buf, int records, int fields, int recordSize) {
        int u32(int rec, int field) { return buf.getInt(20 + rec * recordSize + field * 4); }
        float f32(int rec, int field) { return buf.getFloat(20 + rec * recordSize + field * 4); }
    }

    private static Dbc load(String name) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(DBC_DIR.resolve(name))).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x43424457) throw new IOException(name + ": not a WDBC file"); // "WDBC"
        return new Dbc(b, b.getInt(4), b.getInt(8), b.getInt(12));
    }

    private static synchronized void init() {
        if (sizes != null) return;
        sizes = new HashMap<>();
        try {
            Dbc model = load("CreatureModelData.dbc");
            Map<Integer, float[]> models = new HashMap<>();
            for (int r = 0; r < model.records(); r++) {
                models.put(model.u32(r, 0), new float[] {model.f32(r, 4), model.f32(r, 14), model.f32(r, 15)});
            }
            Dbc disp = load("CreatureDisplayInfo.dbc");
            for (int r = 0; r < disp.records(); r++) {
                float[] m = models.get(disp.u32(r, 1));
                if (m == null) continue;
                float s = disp.f32(r, 4) * m[0];
                sizes.put(disp.u32(r, 0), new float[] {m[2] * s, m[1] * s});
            }
            LOGGER.info("mcwow-bridge: creature sizes for {} display ids (from {})", sizes.size(), DBC_DIR);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("mcwow-bridge: creature sizes unavailable ({}) - using bounding radius only", e.toString());
        }
    }

    /** {height, width} in yards for a display id at an object scale; falls back to WoW's defaults. */
    public static float[] yards(int displayId, float objectScale, float boundingRadius) {
        init();
        float scale = objectScale > 0.01F ? objectScale : 1.0F;
        float[] s = sizes.get(displayId);
        float height = s != null && s[0] > 0.01F ? s[0] * scale : 2.03F * scale; // DEFAULT_COLLISION_HEIGHT
        float width = Math.max(boundingRadius * 2.0F, s != null ? s[1] * scale : 0.0F);
        return new float[] {height, Math.max(width, 0.4F)};
    }
}
