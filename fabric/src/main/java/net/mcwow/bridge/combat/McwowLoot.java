package net.mcwow.bridge.combat;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowOres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft loot off WoW creatures (2026-10-03, game design: WoW creatures drop Minecraft items at
 * the corpse, themed per creature, tiered by level). The server reports each kill (SMSG_CC_KILL ->
 * McwowActors.Kill; it has already put the corpse's quest items in the WoW bags). Per kill:
 *  - skinnable creatures: WoW's leather of their level (light .. rugged);
 *  - humanoids: WoW's cloth of their level (linen .. runecloth);
 *  - the corpse's WoW money as emeralds (one per EMERALD_COPPER(level), the rest by chance);
 *  - the creature's theme (resources mcwow/creature_themes.json, tools/creature_themes.py): kobolds
 *    torches and candles, spiders string, skeletons bones, fire elementals blaze powder...;
 *  - elites, rares and bosses: a chance at an enchanted book of their level.
 * Items pop out of the corpse like a Minecraft mob's drops.
 */
public final class McwowLoot {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final float S = 1.4667f;
    private static final int HUMANOID = 7;
    private static final RandomSource RANDOM = RandomSource.create();

    /** One themed drop: the item, the chance of any, and how many then. */
    private record Drop(Item item, float chance, int min, int max) {
    }

    private static final Map<String, List<Drop>> THEMES = new HashMap<>();
    private static final Map<Integer, String> THEME_OF = new HashMap<>();

    private McwowLoot() {
    }

    private static Drop d(Item item, float chance) {
        return new Drop(item, chance, 1, 1);
    }

    private static Drop d(Item item, float chance, int min, int max) {
        return new Drop(item, chance, min, max);
    }

    private static void themes() {
        // Beasts (leather comes from skinning, below).
        THEMES.put("wolf", List.of(d(Items.BONE, 0.5F)));
        THEMES.put("cat", List.of(d(Items.STRING, 0.3F)));
        THEMES.put("spider", List.of(d(Items.STRING, 0.6F, 1, 2), d(Items.SPIDER_EYE, 0.3F), d(Items.COBWEB, 0.05F)));
        THEMES.put("bear", List.of(d(Items.BEEF, 0.5F, 1, 2)));
        THEMES.put("boar", List.of(d(Items.PORKCHOP, 0.7F, 1, 2)));
        THEMES.put("scaly", List.of(d(Items.BONE, 0.3F)));
        THEMES.put("bird", List.of(d(Items.FEATHER, 0.7F, 1, 3), d(Items.CHICKEN, 0.4F)));
        THEMES.put("crab", List.of(d(Items.COD, 0.3F), d(Items.BONE_MEAL, 0.3F)));
        THEMES.put("scorpid", List.of(d(Items.SPIDER_EYE, 0.3F)));
        THEMES.put("turtle", List.of(d(Items.TURTLE_SCUTE, 0.15F)));
        THEMES.put("fish", List.of(d(Items.COD, 0.6F), d(Items.SALMON, 0.3F)));
        THEMES.put("hoofed", List.of(d(Items.BEEF, 0.4F), d(Items.MUTTON, 0.3F)));
        THEMES.put("insect", List.of(d(Items.STRING, 0.2F)));
        // Humanoids (cloth below).
        THEMES.put("kobold", List.of(d(Items.TORCH, 0.4F, 1, 3), d(Items.CANDLE, 0.15F), d(Items.COAL, 0.3F),
                d(Items.RAW_COPPER, 0.15F)));
        THEMES.put("murloc", List.of(d(Items.COD, 0.4F), d(Items.SALMON, 0.2F), d(Items.INK_SAC, 0.1F)));
        THEMES.put("gnoll", List.of(d(Items.BONE, 0.3F)));
        THEMES.put("ogre", List.of(d(Items.IRON_NUGGET, 0.3F, 1, 3), d(Items.BONE, 0.2F)));
        THEMES.put("troll", List.of(d(Items.ARROW, 0.3F, 1, 4), d(Items.FEATHER, 0.2F)));
        THEMES.put("harpy", List.of(d(Items.FEATHER, 0.6F, 1, 3)));
        THEMES.put("quilboar", List.of(d(Items.PORKCHOP, 0.3F), d(Items.BONE, 0.2F)));
        THEMES.put("centaur", List.of(d(Items.ARROW, 0.3F, 1, 4)));
        THEMES.put("naga", List.of(d(Items.PRISMARINE_SHARD, 0.2F), d(Items.COD, 0.2F)));
        THEMES.put("trogg", List.of(d(Items.COAL, 0.3F), d(Items.FLINT, 0.3F)));
        THEMES.put("furbolg", List.of(d(Items.SWEET_BERRIES, 0.3F, 1, 3)));
        THEMES.put("dark_iron", List.of(d(Items.IRON_NUGGET, 0.4F, 1, 3), d(Items.COAL, 0.3F)));
        THEMES.put("goblin", List.of(d(Items.GUNPOWDER, 0.3F), d(Items.GOLD_NUGGET, 0.3F, 1, 3)));
        THEMES.put("defias", List.of(d(vanilla("red_dye"), 0.2F)));
        // Undead.
        THEMES.put("zombie", List.of(d(Items.ROTTEN_FLESH, 0.7F, 1, 2)));
        THEMES.put("skeleton", List.of(d(Items.BONE, 0.7F, 1, 2), d(Items.ARROW, 0.2F, 1, 3)));
        THEMES.put("ghost", List.of(d(Items.GLOWSTONE_DUST, 0.2F)));
        // Elementals: WoW's elemental reagents as Minecraft's.
        THEMES.put("fire", List.of(d(Items.BLAZE_POWDER, 0.4F, 1, 2), d(Items.MAGMA_CREAM, 0.1F)));
        THEMES.put("water", List.of(d(Items.PRISMARINE_CRYSTALS, 0.3F)));
        THEMES.put("earth", List.of(d(Items.CLAY_BALL, 0.3F, 1, 2), d(Items.FLINT, 0.2F)));
        THEMES.put("air", List.of(d(Items.BREEZE_ROD, 0.25F)));
        THEMES.put("ooze", List.of(d(Items.SLIME_BALL, 0.6F, 1, 2)));
        // The rest.
        THEMES.put("mechanical", List.of(d(Items.IRON_NUGGET, 0.5F, 1, 3), d(Items.REDSTONE, 0.3F), d(Items.GUNPOWDER, 0.2F)));
        THEMES.put("dragonkin", List.of(d(Items.ARMADILLO_SCUTE, 0.3F)));
        THEMES.put("demon", List.of(d(Items.NETHER_WART, 0.2F)));
        THEMES.put("giant", List.of(d(Items.BONE, 0.3F, 1, 2)));
        // Critters: the farm animals they are.
        THEMES.put("rabbit", List.of(d(Items.RABBIT, 0.8F), d(Items.RABBIT_HIDE, 0.5F), d(Items.RABBIT_FOOT, 0.05F)));
        THEMES.put("chicken", List.of(d(Items.CHICKEN, 0.8F), d(Items.FEATHER, 0.6F, 1, 2)));
        THEMES.put("cow", List.of(d(Items.BEEF, 0.8F, 1, 2), d(Items.LEATHER, 0.5F)));
        THEMES.put("sheep", List.of(d(Items.MUTTON, 0.8F), d(vanilla("white_wool"), 0.6F)));
        THEMES.put("pig", List.of(d(Items.PORKCHOP, 0.8F, 1, 2)));
        THEMES.put("deer", List.of(d(Items.BEEF, 0.6F), d(Items.LEATHER, 0.4F)));
    }

    static {
        themes();
        try (var in = McwowLoot.class.getResourceAsStream("/mcwow/creature_themes.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                for (JsonElement entry : e.getValue().getAsJsonArray()) THEME_OF.put(entry.getAsInt(), e.getKey());
            }
        } catch (Exception e) {
            LOGGER.warn("mcwow-bridge: cannot read creature themes: {}", e.toString());
        }
    }

    /** WoW's leather by creature level: light to 17, medium to 27, heavy to 37, thick to 47, rugged. */
    static int leatherTier(int level) {
        return level <= 17 ? 0 : level <= 27 ? 1 : level <= 37 ? 2 : level <= 47 ? 3 : 4;
    }

    /** WoW's cloth by creature level: linen to 14, wool to 24, silk to 34, mageweave to 45, runecloth. */
    static int clothTier(int level) {
        return level <= 14 ? 0 : level <= 24 ? 1 : level <= 34 ? 2 : level <= 45 ? 3 : 4;
    }

    /**
     * Copper per emerald at a creature level: WoW's money per kill grows roughly with the level
     * squared (a level 5 humanoid carries ~7c, 20 ~40c, 60 ~4s), so this keeps an emerald at about
     * one per four humanoid kills at every level.
     */
    public static double emeraldCopper(int level) {
        return 25.0 + 0.4 * level * level;
    }

    /** Items without an Items field in 26.3 (dyes and wool are colour collections). */
    private static Item vanilla(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
    }

    private static Item mod(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("mcwow", id));
    }

    private static int roll(int min, int max) {
        return min + (max > min ? RANDOM.nextInt(max - min + 1) : 0);
    }

    static void drop(ServerLevel level, List<McwowActors.Kill> kills) {
        for (McwowActors.Kill k : kills) {
            double x = k.y() / S + McwowGeomStore.regionOffsetX;
            double y = k.z() / S + 0.5;
            double z = k.x() / S + McwowGeomStore.regionOffsetZ;
            if (!level.isLoaded(BlockPos.containing(x, y, z))) continue;
            boolean elite = k.rank() == 1 || k.rank() == 2 || k.rank() == 3;
            StringBuilder log = new StringBuilder();
            if (k.skinnable() && RANDOM.nextFloat() < 0.75F) {
                int n = (RANDOM.nextFloat() < 0.25F ? 2 : 1) * (elite ? 2 : 1);
                spawn(level, x, y, z, new ItemStack(mod(McwowOres.LEATHERS[leatherTier(k.level())]), n), log);
            }
            if (k.type() == HUMANOID && RANDOM.nextFloat() < (elite ? 0.6F : 0.35F)) {
                int n = (RANDOM.nextFloat() < 0.25F ? 2 : 1) * (elite ? 2 : 1);
                spawn(level, x, y, z, new ItemStack(mod(McwowOres.CLOTHS[clothTier(k.level())]), n), log);
            }
            if (k.money() > 0) {
                double emeralds = k.money() / emeraldCopper(k.level());
                int n = (int) emeralds + (RANDOM.nextDouble() < emeralds - Math.floor(emeralds) ? 1 : 0);
                if (n > 0) spawn(level, x, y, z, new ItemStack(Items.EMERALD, n), log);
            }
            String theme = THEME_OF.get(k.entry());
            for (Drop drop : theme != null ? THEMES.getOrDefault(theme, List.of()) : List.<Drop>of()) {
                if (RANDOM.nextFloat() < drop.chance()) {
                    spawn(level, x, y, z, new ItemStack(drop.item(), roll(drop.min(), drop.max())), log);
                }
            }
            // rank: 1 elite, 2 rare elite, 3 boss, 4 rare
            float book = switch (k.rank()) {
                case 1 -> 0.08F;
                case 2 -> 0.4F;
                case 3 -> 0.6F;
                case 4 -> 0.3F;
                default -> 0.0F;
            };
            if (book > 0 && RANDOM.nextFloat() < book) {
                ItemStack b = EnchantmentHelper.enchantItem(RANDOM, new ItemStack(Items.BOOK),
                        Math.clamp(k.level() / 2, 1, 30), level.registryAccess(), Optional.empty());
                spawn(level, x, y, z, b, log);
            }
            LOGGER.info("mcwow-bridge: loot of creature {} (level {}, rank {}, type {}, theme {}, {} copper, {} quest items bagged):{}",
                    k.entry(), k.level(), k.rank(), k.type(), theme, k.money(), k.questItems(),
                    log.isEmpty() ? " nothing" : log);
        }
    }

    private static void spawn(ServerLevel level, double x, double y, double z, ItemStack stack, StringBuilder log) {
        if (stack.isEmpty()) return;
        ItemEntity e = new ItemEntity(level, x, y, z, stack,
                RANDOM.nextDouble() * 0.2 - 0.1, 0.2, RANDOM.nextDouble() * 0.2 - 0.1);
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
        log.append(' ').append(stack.getCount()).append('x').append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
    }
}
