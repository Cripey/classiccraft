package net.mcwow.bridge;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ores under WoW's ground by the zone's level (2026-10-04, user: replaces the mine dimensions). Each
 * zone gets one mining tier from its level (resources mcwow/area_levels.json, tools/area_levels.py:
 * the median of the zone's sub-area levels): Copper 1-10, Tin 11-20, Iron 21-35, Mithril 36-50,
 * Thorium 51+; Searing Gorge and Burning Steppes add Dark Iron. A tier's ores: its own metal
 * common, the tier below's less so, the rare ones of its band (silver, gold, truesilver) deeper
 * down, and coal, redstone and lapis everywhere (no diamond - not a material here - and no vanilla
 * gold or iron outside their tiers). Ratios after the mine tiers' mixes.
 */
public final class McwowZoneOres {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    /** A blob of ore: its stone and deepslate blocks, its depth band (blocks under the column's top), per mille of 6^3 cells, radius. */
    public record Ore(BlockState stone, BlockState deep, int minDepth, int maxDepth, int perMille, float radius) {
    }

    public enum Tier { COPPER, TIN, IRON, MITHRIL, THORIUM }

    /** Zones with Dark Iron under them (AreaTable ids): Searing Gorge, Burning Steppes. */
    private static final int SEARING_GORGE = 51, BURNING_STEPPES = 46;

    /** Area id -> {zone level, zone id}. */
    private static Map<Integer, int[]> areas;
    /** Ore tables: [tier][darkIron ? 1 : 0]. */
    private static Ore[][][] tables;

    private McwowZoneOres() {
    }

    public static Tier tierFor(int level) {
        return level <= 10 ? Tier.COPPER : level <= 20 ? Tier.TIN : level <= 35 ? Tier.IRON
                : level <= 50 ? Tier.MITHRIL : Tier.THORIUM;
    }

    /** The ores under an area (AreaTable id; 0 or unknown: Copper's). */
    public static Ore[] oresFor(int area) {
        load();
        int[] a = areas.get(area);
        Tier tier = a == null ? Tier.COPPER : tierFor(a[0]);
        boolean darkIron = a != null && (a[1] == SEARING_GORGE || a[1] == BURNING_STEPPES);
        return tables[tier.ordinal()][darkIron ? 1 : 0];
    }

    private static synchronized void load() {
        if (tables != null) return;
        Map<Integer, int[]> map = new HashMap<>();
        try (var in = McwowZoneOres.class.getResourceAsStream("/mcwow/area_levels.json")) {
            for (Map.Entry<String, JsonElement> e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().entrySet()) {
                var v = e.getValue().getAsJsonArray();
                map.put(Integer.parseInt(e.getKey()), new int[] {v.get(0).getAsInt(), v.get(1).getAsInt()});
            }
        } catch (Exception ex) {
            LOGGER.error("mcwow-bridge: area levels not loaded - every zone mines as Copper", ex);
        }
        areas = map;
        Ore coal = ore(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 2, 40, 0, 1.4F);
        Ore redstone = ore(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 24, 48, 0, 1.2F);
        Ore lapis = ore(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 20, 48, 0, 1.1F);
        Ore copper = ore(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 3, 48, 0, 1.3F);
        Ore iron = ore(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 4, 48, 0, 1.3F);
        Ore gold = ore(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 20, 48, 0, 1.0F);
        Ore tin = ours("tin", 3, 48, 1.3F), silver = ours("silver", 16, 48, 1.0F);
        Ore mithril = ours("mithril", 6, 48, 1.3F), truesilver = ours("truesilver", 24, 48, 1.0F);
        Ore thorium = ours("thorium", 8, 48, 1.3F), darkIron = ours("dark_iron", 8, 48, 1.2F);
        // First match wins in a cell: rares first, then the tier's metal, the one below, the rest.
        Ore[][] base = {
                {rate(copper, 260), rate(tin, 40), rate(redstone, 60), rate(lapis, 30), rate(coal, 320)},
                {rate(silver, 45), rate(tin, 240), rate(copper, 100), rate(redstone, 80), rate(lapis, 40), rate(coal, 300)},
                {rate(gold, 45), rate(silver, 35), rate(iron, 240), rate(tin, 90), rate(redstone, 100), rate(lapis, 50),
                        rate(coal, 220)},
                {rate(truesilver, 40), rate(gold, 50), rate(mithril, 220), rate(iron, 90), rate(redstone, 110),
                        rate(lapis, 55), rate(coal, 150)},
                {rate(truesilver, 50), rate(gold, 35), rate(thorium, 220), rate(mithril, 90), rate(redstone, 110),
                        rate(lapis, 55), rate(coal, 120)},
        };
        Ore[][][] t = new Ore[base.length][2][];
        for (int i = 0; i < base.length; ++i) {
            t[i][0] = base[i];
            Ore[] withDarkIron = new Ore[base[i].length + 1];
            withDarkIron[0] = rate(darkIron, 160);
            System.arraycopy(base[i], 0, withDarkIron, 1, base[i].length);
            t[i][1] = withDarkIron;
        }
        tables = t;
        LOGGER.info("mcwow-bridge: zone ores ready ({} areas with a level)", map.size());
    }

    private static Ore ore(Block stone, Block deep, int min, int max, int perMille, float radius) {
        return new Ore(stone.defaultBlockState(), deep.defaultBlockState(), min, max, perMille, radius);
    }

    private static Ore ours(String id, int min, int max, float radius) {
        return ore(BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("mcwow", id + "_ore")),
                BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("mcwow", "deepslate_" + id + "_ore")),
                min, max, 0, radius);
    }

    private static Ore rate(Ore o, int perMille) {
        return new Ore(o.stone(), o.deep(), o.minDepth(), o.maxDepth(), perMille, o.radius());
    }
}
