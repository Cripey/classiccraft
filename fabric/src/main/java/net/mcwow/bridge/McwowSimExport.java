package net.mcwow.bridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.mcwow.bridge.combat.McwowActors;
import net.mcwow.bridge.combat.McwowCombat;
import net.mcwow.bridge.combat.McwowLoot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The progression sim's rules (tools/progression.sh, 2026-10-04). The 1-60 model (tools/progression/
 * sim.py) must not keep its own copy of the mod's rules, so a headless dedicated server
 * (`./gradlew runSim`, run dir build/sim-server) evaluates the real code and writes what it found
 * as JSON, then stops: gear stats per material and piece (stamped stacks), crafting and smelting
 * recipes (assembled, so crafted gear carries its stamp), combat conversions (McwowCombat,
 * McwowGear.armorFactor), the loot of every creature on the route (McwowLoot.roll, sampled), quest
 * rewards as Minecraft items (McwowDialog.toMinecraft), vendor stock (McwowVendors), and ore yields
 * of branch mining under each route zone (McwowTerrainFill.rock with McwowZoneOres) with the drops
 * and dig times of the blocks met. Active only with -Dmcwow.sim.in (WoW data from
 * tools/progression/extract.py) and -Dmcwow.sim.out.
 */
public final class McwowSimExport {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final List<String> PIECES = List.of("helmet", "chestplate", "leggings", "boots", "sword", "axe",
            "pickaxe", "shovel", "spear");
    private static final int LOOT_SAMPLES = 400;
    private static final int[] DEPTHS = {6, 10, 14, 18, 24, 30, 36, 42};

    private McwowSimExport() {
    }

    public static void init() {
        String in = System.getProperty("mcwow.sim.in"), out = System.getProperty("mcwow.sim.out");
        if (in == null || out == null) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                long t0 = System.nanoTime();
                JsonObject wow = JsonParser.parseString(Files.readString(Path.of(in))).getAsJsonObject();
                JsonObject rules = export(server, wow);
                Gson gson = new GsonBuilder().disableHtmlEscaping().create();
                Files.writeString(Path.of(out), gson.toJson(rules), StandardCharsets.UTF_8);
                LOGGER.info("mcwow-bridge: progression sim rules written to {} in {} ms", out, (System.nanoTime() - t0) / 1_000_000);
            } catch (Exception e) {
                LOGGER.error("mcwow-bridge: progression sim export failed", e);
            } finally {
                server.halt(false);
            }
        });
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /** An item stack as {item, count, gear?: [material, ilvl, req], enchanted, name?}. */
    private static JsonObject stack(ItemStack s) {
        JsonObject o = new JsonObject();
        o.addProperty("item", id(s.getItem()));
        o.addProperty("count", s.getCount());
        McwowGear.Gear g = s.get(McwowGear.GEAR);
        if (g != null) {
            JsonArray a = new JsonArray();
            a.add(g.material());
            a.add(g.ilvl());
            a.add(g.req());
            o.add("gear", a);
        }
        if (s.isEnchanted() || s.has(DataComponents.STORED_ENCHANTMENTS)) {
            o.addProperty("enchanted", true);
            JsonObject en = new JsonObject();
            for (var key : List.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS)) {
                for (var e : s.getOrDefault(key, ItemEnchantments.EMPTY).entrySet()) {
                    en.addProperty(e.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"), e.getIntValue());
                }
            }
            o.add("ench", en);
        }
        return o;
    }

    private static JsonObject export(MinecraftServer server, JsonObject wow) {
        JsonObject r = new JsonObject();
        r.add("combat", combat());
        r.add("materials", materials());
        r.add("recipes", recipes(server));
        r.add("loot", loot(server, wow));
        r.add("rewards", rewards(server, wow));
        r.add("vendors", vendors(wow));
        r.add("mining", mining(wow));
        r.add("veins", veins(wow, server.overworld()));
        r.add("brewing", brewing(server));
        r.add("enchanting", enchanting(server));
        r.add("chests", chests(server, wow));
        r.add("blocks", blocks(server.overworld()));
        return r;
    }

    // ---- combat --------------------------------------------------------------------------------

    private static JsonObject combat() {
        JsonObject o = new JsonObject();
        JsonArray wowPerMc = new JsonArray(), mcPerWow = new JsonArray(), emerald = new JsonArray();
        for (int level = 1; level <= 63; ++level) {
            wowPerMc.add(McwowCombat.wowDamage(1000.0F, level) / 1000.0);
            mcPerWow.add(McwowCombat.mcDamage(1_000_000, level) / 1_000_000.0);
            emerald.add(McwowLoot.emeraldCopper(level));
        }
        o.add("wow_per_mc", wowPerMc); // [level-1]: WoW damage per Minecraft damage of a hit at that level
        o.add("mc_per_wow", mcPerWow); // [level-1]: Minecraft damage per WoW damage from an attacker of that level
        o.add("emerald_copper", emerald);
        JsonArray curve = new JsonArray();
        for (int level = 1; level <= 63; ++level) curve.add(McwowCombat.takenCurve(level));
        o.add("taken_curve", curve); // [level-1]: the difficulty curve on hits against the player
        // armor_factor[attacker level - 1][armor ilvl 0..70]
        JsonArray af = new JsonArray();
        for (int level = 1; level <= 63; ++level) {
            JsonArray row = new JsonArray();
            for (int ilvl = 0; ilvl <= 70; ++ilvl) row.add(McwowGear.armorFactor((float) ilvl, level));
            af.add(row);
        }
        o.add("armor_factor", af);
        return o;
    }

    // ---- gear ----------------------------------------------------------------------------------

    private static JsonObject pieceStats(ItemStack s, String piece) {
        JsonObject o = stack(s);
        ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        boolean armor = List.of("helmet", "chestplate", "leggings", "boots").contains(piece);
        if (armor) {
            EquipmentSlot slot = switch (piece) {
                case "helmet" -> EquipmentSlot.HEAD;
                case "chestplate" -> EquipmentSlot.CHEST;
                case "leggings" -> EquipmentSlot.LEGS;
                default -> EquipmentSlot.FEET;
            };
            o.addProperty("armor", mods.compute(Attributes.ARMOR, 0.0, slot));
            o.addProperty("toughness", mods.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot));
            float[] spell = McwowGear.spellProtection(s);
            o.addProperty("spell", spell[0]);
            o.addProperty("spell_toughness", spell[1]);
            o.addProperty("class", McwowGear.armorClass(s));
        } else {
            o.addProperty("attack", mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND));
            o.addProperty("speed", mods.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND));
        }
        Integer dur = s.get(DataComponents.MAX_DAMAGE);
        if (dur != null) o.addProperty("durability", dur);
        return o;
    }

    private static JsonObject materials() {
        JsonObject o = new JsonObject();
        for (McwowGear.Material m : McwowGear.materials()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("title", m.title());
            mo.addProperty("ilvl", m.ilvl());
            mo.addProperty("req", m.req());
            mo.addProperty("repair", m.repair());
            JsonObject pieces = new JsonObject();
            for (String piece : PIECES) {
                ItemStack s = McwowDialog.piece(m.id(), piece, 0, 0);
                if (!s.isEmpty() && (m.armorTwin() != null || !List.of("helmet", "chestplate", "leggings", "boots").contains(piece))
                        && (m.toolTwin() != null || List.of("helmet", "chestplate", "leggings", "boots").contains(piece))) {
                    pieces.add(piece, pieceStats(s, piece));
                }
            }
            mo.add("pieces", pieces);
            o.add(m.id(), mo);
        }
        // The bare hand, for the same model.
        JsonObject hand = new JsonObject();
        hand.addProperty("attack", 1.0);
        hand.addProperty("speed", 4.0);
        o.add("_hand", hand);
        return o;
    }

    // ---- recipes -------------------------------------------------------------------------------

    private static ItemStack first(Ingredient ing) {
        return ing.items().findFirst().map(h -> new ItemStack(h.value())).orElse(ItemStack.EMPTY);
    }

    private static JsonArray options(Ingredient ing) {
        JsonArray a = new JsonArray();
        ing.items().limit(12).forEach(h -> a.add(id(h.value())));
        return a;
    }

    private static JsonArray recipes(MinecraftServer server) {
        JsonArray out = new JsonArray();
        for (RecipeHolder<?> h : server.getRecipeManager().getRecipes()) {
            try {
                JsonObject o = new JsonObject();
                ItemStack result;
                JsonArray ings = new JsonArray();
                switch (h.value()) {
                    case ShapedRecipe s -> {
                        List<ItemStack> grid = new ArrayList<>();
                        for (var oi : s.getIngredients()) {
                            grid.add(oi.map(McwowSimExport::first).orElse(ItemStack.EMPTY));
                            oi.ifPresent(i -> ings.add(options(i)));
                        }
                        result = s.assemble(CraftingInput.of(s.getWidth(), s.getHeight(), grid));
                        o.addProperty("kind", "craft");
                    }
                    case ShapelessRecipe s -> {
                        List<ItemStack> grid = new ArrayList<>();
                        for (Ingredient i : s.placementInfo().ingredients()) {
                            grid.add(first(i));
                            ings.add(options(i));
                        }
                        result = s.assemble(CraftingInput.of(grid.size(), 1, grid));
                        o.addProperty("kind", "craft");
                    }
                    case AbstractCookingRecipe c -> {
                        ings.add(options(c.input()));
                        result = c.assemble(new SingleRecipeInput(first(c.input())));
                        o.addProperty("kind", BuiltInRegistries.RECIPE_TYPE.getKey(c.getType()).getPath());
                    }
                    default -> {
                        continue;
                    }
                }
                if (result.isEmpty()) continue;
                o.addProperty("id", h.id().identifier().toString());
                o.add("result", stack(result));
                o.add("ingredients", ings);
                out.add(o);
            } catch (RuntimeException e) {
                LOGGER.warn("mcwow-bridge: sim export: recipe {} skipped: {}", h.id(), e.toString());
            }
        }
        return out;
    }

    // ---- enchanting and chests -----------------------------------------------------------------

    /**
     * What Minecraft's enchanting gives at each power 1..30 (EnchantmentHelper.enchantItem, sampled):
     * for a book, a sword and a chestplate, {enchantment: [chance, average level when present]};
     * and the powers the mod uses: creature books (McwowLoot.bookPower) by level, chest books and
     * gear (McwowChests) by level and size.
     */
    private static JsonObject enchanting(MinecraftServer server) {
        JsonObject out = new JsonObject();
        RandomSource r = RandomSource.create(5);
        // McwowEnchants (2026-10-04): books of the curated pool; dropped gear (uncommon, rare) on a sword / chestplate.
        String[] bases = {"book", "weapon", "armor", "weapon_rare", "armor_rare"};
        int samples = 600;
        for (String b : bases) {
            JsonArray powers = new JsonArray();
            for (int power = 1; power <= 30; power++) {
                Map<String, double[]> sum = new TreeMap<>();
                for (int n = 0; n < samples; n++) {
                    ItemStack s = b.equals("book") ? McwowEnchants.book(power, r, server.registryAccess())
                            : McwowEnchants.random(new ItemStack(b.startsWith("weapon") ? net.minecraft.world.item.Items.IRON_SWORD
                                    : net.minecraft.world.item.Items.IRON_CHESTPLATE), power,
                            McwowEnchants.count(b.endsWith("_rare") ? 3 : 2, r), r, server.registryAccess());
                    for (var key : List.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS)) {
                        for (var e : s.getOrDefault(key, ItemEnchantments.EMPTY).entrySet()) {
                            double[] a = sum.computeIfAbsent(e.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"),
                                    k -> new double[2]);
                            a[0] += 1;
                            a[1] += e.getIntValue();
                        }
                    }
                }
                JsonObject po = new JsonObject();
                sum.forEach((k, a) -> {
                    JsonArray v = new JsonArray();
                    v.add(Math.round(a[0] / samples * 10000) / 10000.0);
                    v.add(Math.round(a[1] / a[0] * 100) / 100.0);
                    po.add(k, v);
                });
                powers.add(po);
            }
            out.add(b, powers);
        }
        JsonArray creature = new JsonArray(), chestGear = new JsonArray();
        JsonObject chestBook = new JsonObject();
        for (String size : List.of("small", "medium", "large")) chestBook.add(size, new JsonArray());
        for (int level = 1; level <= 63; level++) {
            creature.add(McwowLoot.bookPower(level));
            chestGear.add(McwowChests.gearPower(level));
            for (String size : List.of("small", "medium", "large")) {
                chestBook.getAsJsonArray(size).add(McwowChests.bookPower(level, McwowChests.sizeIndex(size)));
            }
        }
        out.add("creature_book_power", creature);
        out.add("chest_book_power", chestBook);
        out.add("chest_gear_power", chestGear);
        JsonArray creatureGear = new JsonArray();
        JsonObject options = new JsonObject();
        for (int level = 1; level <= 63; level++) {
            creatureGear.add(McwowLoot.gearPower(level, 2));
            JsonArray gear = new JsonArray();
            for (McwowChests.GearOption g : McwowChests.gearOptions(level)) {
                String path = BuiltInRegistries.ITEM.getKey(g.stack().getItem()).getPath();
                JsonObject go = pieceStats(g.stack(), path.substring(path.lastIndexOf('_') + 1));
                go.addProperty("weight", g.weight());
                gear.add(go);
            }
            options.add(Integer.toString(level), gear);
        }
        out.add("creature_gear_power", creatureGear);
        // Enchanting suppliers' books by the buyer's level (McwowVendors.books): [enchantment, level, price, lots].
        JsonObject vendorBooks = new JsonObject();
        for (int level = 1; level <= 60; level++) {
            net.minecraft.world.item.trading.MerchantOffers offers = new net.minecraft.world.item.trading.MerchantOffers();
            McwowVendors.books(offers, level, server.registryAccess());
            JsonArray a = new JsonArray();
            for (var of : offers) {
                for (var e : of.getResult().getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
                    JsonArray row = new JsonArray();
                    row.add(e.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
                    row.add(e.getIntValue());
                    row.add(of.getCostA().getCount());
                    row.add(of.getMaxUses());
                    a.add(row);
                }
            }
            vendorBooks.add(Integer.toString(level), a);
        }
        out.add("vendor_books", vendorBooks);
        out.add("gear_options", options);
        // Smite and Bane (McwowCreatureKinds): "#undead" / "#arthropod" in each creature's loot entry.
        return out;
    }

    /**
     * Each treasure chest on the route (McwowChests): its level, size and lock; the expected loot
     * of one opening without books and gear (sampled), the chance of a book and of a gear piece, and
     * the gear pieces it can hold (stats, unenchanted, with their chance among them).
     */
    private static JsonObject chests(MinecraftServer server, JsonObject wow) {
        JsonObject out = new JsonObject();
        RandomSource random = RandomSource.create(6);
        java.util.Set<String> entries = new java.util.TreeSet<>();
        if (wow.has("chests")) {
            for (Map.Entry<String, JsonElement> z : wow.getAsJsonObject("chests").entrySet()) {
                entries.addAll(z.getValue().getAsJsonObject().keySet());
            }
        }
        for (String id : entries) {
            McwowChests.Entry c = McwowChests.entries().get(Integer.parseInt(id));
            if (c == null) continue;
            int s = McwowChests.sizeIndex(c.size());
            Map<String, Double> sum = new TreeMap<>();
            for (int n = 0; n < LOOT_SAMPLES; ++n) {
                for (ItemStack st : McwowChests.roll(c.level(), c.size(), c.name(), random, server.registryAccess())) {
                    if (st.has(DataComponents.STORED_ENCHANTMENTS) || st.has(McwowGear.GEAR)) continue;
                    sum.merge(id(st.getItem()), (double) st.getCount() / LOOT_SAMPLES, Double::sum);
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("name", c.name());
            o.addProperty("level", c.level());
            o.addProperty("size", c.size());
            o.addProperty("pick", c.pick());
            JsonObject loot = new JsonObject();
            sum.forEach((k, v) -> loot.addProperty(k, Math.round(v * 10000) / 10000.0));
            o.add("loot", loot);
            o.addProperty("book_chance", McwowChests.bookChance(s, c.level()));
            o.addProperty("gear_chance", McwowChests.gearChance(s));
            JsonArray gear = new JsonArray();
            for (McwowChests.GearOption g : McwowChests.gearOptions(c.level())) {
                String path = BuiltInRegistries.ITEM.getKey(g.stack().getItem()).getPath();
                String piece = path.substring(path.lastIndexOf('_') + 1);
                JsonObject go = pieceStats(g.stack(), piece);
                go.addProperty("weight", g.weight());
                gear.add(go);
            }
            o.add("gear", gear);
            // Prying a lock (client McwowGather): a pickaxe correct for pryBlock, iron ore's time x sizeFactor.
            BlockState pry = McwowChests.pryBlock(c.pick());
            JsonObject pryBy = new JsonObject();
            if (pry != null) {
                BlockState iron = Blocks.IRON_ORE.defaultBlockState();
                float hardness = iron.getDestroySpeed(server.overworld(), BlockPos.ZERO);
                for (String m : new String[] {"wood", "stone", "copper", "gold", "bronze", "iron", "steel", "mithril", "thorium", "dark_iron"}) {
                    ItemStack tool = McwowDialog.piece(m, "pickaxe", 0, 0);
                    if (tool.isEmpty() || !tool.isCorrectToolForDrops(pry)) continue;
                    float perTick = tool.getDestroySpeed(iron) / hardness / 30.0F;
                    int perBlock = perTick >= 1.0F ? 1 : (int) Math.ceil(1.0F / perTick);
                    pryBy.addProperty(m, Math.max(6, perBlock * McwowChests.sizeFactor(c.size())) / 20.0);
                }
                o.add("pry", pryBy);
            }
            out.add(id, o);
        }
        return out;
    }

    // ---- loot, rewards, vendors ----------------------------------------------------------------

    /** Expected items per kill of each creature on the route. */
    private static JsonObject loot(MinecraftServer server, JsonObject wow) {
        JsonObject out = new JsonObject();
        RandomSource random = RandomSource.create(1);
        for (Map.Entry<String, JsonElement> e : wow.getAsJsonObject("creatures").entrySet()) {
            JsonObject c = e.getValue().getAsJsonObject();
            int flags = c.get("skinnable").getAsBoolean() ? McwowActors.Kill.SKINNABLE : 0;
            McwowActors.Kill k = new McwowActors.Kill(Integer.parseInt(e.getKey()), c.get("level").getAsInt(),
                    c.get("rank").getAsInt(), c.get("type").getAsInt(), c.get("family").getAsInt(),
                    (int) Math.round(c.get("gold").getAsDouble()), flags, 0, 0, 0, 0);
            Map<String, Double> sum = new TreeMap<>();
            for (int n = 0; n < LOOT_SAMPLES; ++n) {
                for (ItemStack s : McwowLoot.roll(k, random, server.registryAccess())) {
                    if (s.has(McwowGear.GEAR) && !s.has(DataComponents.STORED_ENCHANTMENTS)) continue; // "#gear" below
                    String key = id(s.getItem()) + (s.has(DataComponents.STORED_ENCHANTMENTS) ? "#enchanted" : "");
                    sum.merge(key, (double) s.getCount() / LOOT_SAMPLES, Double::sum);
                }
            }
            JsonObject o = new JsonObject();
            sum.forEach((k2, v) -> o.addProperty(k2, Math.round(v * 10000) / 10000.0));
            float gear = McwowLoot.gearChance(k);
            if (gear > 0) o.addProperty("#gear", gear);
            if (net.mcwow.bridge.combat.McwowCreatureKinds.undead(k.entry())) o.addProperty("#undead", 1);
            if (net.mcwow.bridge.combat.McwowCreatureKinds.arthropod(k.entry())) o.addProperty("#arthropod", 1);
            out.add(e.getKey(), o);
        }
        return out;
    }

    private static int[] ints(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new int[0];
        JsonArray a = e.getAsJsonArray();
        int[] out = new int[a.size()];
        for (int k = 0; k < out.length; k++) out[k] = a.get(k).getAsInt();
        return out;
    }

    /** Each WoW reward item as the Minecraft item a quest turn-in gives. */
    private static JsonObject rewards(MinecraftServer server, JsonObject wow) {
        JsonObject out = new JsonObject();
        RandomSource random = RandomSource.create(2);
        for (Map.Entry<String, JsonElement> e : wow.getAsJsonObject("items").entrySet()) {
            JsonObject i = e.getValue().getAsJsonObject();
            if (!i.has("class")) continue;
            McwowDialog.WowItem w = new McwowDialog.WowItem(Integer.parseInt(e.getKey()), 1, i.get("quality").getAsInt(),
                    i.get("class").getAsInt(), i.get("subclass").getAsInt(), i.get("inv").getAsInt(),
                    i.get("ilvl").getAsInt(), i.get("req").getAsInt(), i.get("name").getAsString(),
                    ints(i.get("stats")), i.has("resist") ? ints(i.get("resist")) : new int[6],
                    i.has("dmg_type") ? i.get("dmg_type").getAsInt() : 0);
            ItemStack s = McwowDialog.toMinecraft(w, server.registryAccess(), random);
            if (s.isEmpty()) continue;
            String piece = null;
            String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            for (String p : PIECES) if (path.endsWith("_" + p)) piece = p;
            out.add(e.getKey(), piece != null ? pieceStats(s, piece) : stack(s));
        }
        return out;
    }

    /** Each route vendor's offers (McwowVendors: stock by type and area level, then what it buys). */
    private static JsonObject vendors(JsonObject wow) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> z : wow.getAsJsonObject("vendors").entrySet()) {
            for (JsonElement ve : z.getValue().getAsJsonArray()) {
                int entry = ve.getAsInt();
                if (out.has(Integer.toString(entry))) continue;
                Object[] v = McwowVendors.vendor(entry);
                JsonObject vo = new JsonObject();
                vo.addProperty("type", (String) v[0]);
                vo.addProperty("level", (Integer) v[1]);
                JsonArray a = new JsonArray();
                for (MerchantOffer of : McwowVendors.offersFor(entry)) {
                    JsonObject o = new JsonObject();
                    ItemStack cost = of.getCostA();
                    o.addProperty("cost", id(cost.getItem()));
                    o.addProperty("cost_count", cost.getCount());
                    ItemStack res = of.getResult();
                    String path = BuiltInRegistries.ITEM.getKey(res.getItem()).getPath();
                    String piece = null;
                    for (String p : PIECES) if (path.endsWith("_" + p)) piece = p;
                    o.add("result", piece != null ? pieceStats(res, piece) : stack(res));
                    if (of.getMaxUses() < Integer.MAX_VALUE) { // limited supply (McwowVendors.LIMITED)
                        o.addProperty("limit", of.getMaxUses());
                        o.addProperty("restock_s", McwowVendors.RESTOCK_MILLIS / 1000);
                    }
                    a.add(o);
                }
                vo.add("offers", a);
                out.add(Integer.toString(entry), vo);
            }
        }
        return out;
    }

    // ---- mining --------------------------------------------------------------------------------

    /**
     * Branch mining under each route zone: 1x2 tunnels at fixed depths under a flat top, every ore
     * block seen on a tunnel face mined with its whole vein. Per zone and depth: blocks dug and ore
     * blocks found (by block id). Caves are left out.
     */
    private static JsonObject mining(JsonObject wow) {
        JsonObject out = new JsonObject();
        final int top = 100, length = 6000, lanes = 4;
        BlockState stone = Blocks.STONE.defaultBlockState(), deep = Blocks.DEEPSLATE.defaultBlockState();
        for (JsonElement ze : wow.getAsJsonArray("zones")) {
            int zone = ze.getAsInt();
            McwowZoneOres.Ore[] ores = McwowZoneOres.oresFor(zone);
            JsonObject byDepth = new JsonObject();
            for (int d : DEPTHS) {
                int y0 = top - d;
                Map<Long, BlockState> cache = new HashMap<>();
                java.util.function.LongFunction<BlockState> at = key -> cache.computeIfAbsent(key, k2 -> {
                    int x = BlockPos.getX(k2), y = BlockPos.getY(k2), z = BlockPos.getZ(k2);
                    return McwowTerrainFill.rock(top - y, x, y, z, ores);
                });
                java.util.Set<Long> dug = new java.util.HashSet<>();
                Map<String, Integer> found = new TreeMap<>();
                int blocks = 0;
                for (int lane = 0; lane < lanes; ++lane) {
                    int z = 1000 * zone + lane * 3 + 17;
                    for (int x = 0; x < length; ++x) {
                        for (int dy = 0; dy < 2; ++dy) {
                            long p = BlockPos.asLong(x, y0 + dy, z);
                            if (dug.add(p)) ++blocks;
                        }
                        long[] faces = {BlockPos.asLong(x, y0 - 1, z), BlockPos.asLong(x, y0 + 2, z),
                                BlockPos.asLong(x, y0, z - 1), BlockPos.asLong(x, y0, z + 1),
                                BlockPos.asLong(x, y0 + 1, z - 1), BlockPos.asLong(x, y0 + 1, z + 1)};
                        for (long f : faces) {
                            BlockState s = at.apply(f);
                            if (s == stone || s == deep || dug.contains(f)) continue;
                            // the vein: flood the same block through face neighbours
                            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
                            queue.add(f);
                            dug.add(f);
                            while (!queue.isEmpty()) {
                                long q = queue.poll();
                                ++blocks;
                                found.merge(id(at.apply(q).getBlock()), 1, Integer::sum);
                                int qx = BlockPos.getX(q), qy = BlockPos.getY(q), qz = BlockPos.getZ(q);
                                for (long n : new long[] {BlockPos.asLong(qx + 1, qy, qz), BlockPos.asLong(qx - 1, qy, qz),
                                        BlockPos.asLong(qx, qy + 1, qz), BlockPos.asLong(qx, qy - 1, qz),
                                        BlockPos.asLong(qx, qy, qz + 1), BlockPos.asLong(qx, qy, qz - 1)}) {
                                    if (!dug.contains(n) && at.apply(n) == at.apply(q)) {
                                        dug.add(n);
                                        queue.add(n);
                                    }
                                }
                            }
                        }
                    }
                }
                JsonObject o = new JsonObject();
                o.addProperty("dug", blocks);
                o.addProperty("rock", d >= McwowTerrainFill.DEEPSLATE_DEPTH ? "minecraft:deepslate" : "minecraft:stone");
                JsonObject f = new JsonObject();
                found.forEach(f::addProperty);
                o.add("ores", f);
                byDepth.add(Integer.toString(d), o);
            }
            out.add(Integer.toString(zone), byDepth);
        }
        return out;
    }

    /**
     * Minecraft's brewing as the herbs meet it: every potion's effects, every herb (its ingredient,
     * gathers' average, whether it fuels the stand), and the edges potion --herb--> potion found by
     * trying each herb's real stack (McwowNodes.herbStack) on every potion against the brewing
     * recipes. Plain potions only (no splash / lingering).
     */
    private static JsonObject brewing(MinecraftServer server) {
        JsonObject out = new JsonObject();
        JsonObject potions = new JsonObject();
        for (var p : BuiltInRegistries.POTION) {
            JsonArray effects = new JsonArray();
            for (var e : p.getEffects()) {
                JsonArray a = new JsonArray();
                a.add(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).toString());
                a.add(e.getAmplifier());
                a.add(e.getDuration());
                effects.add(a);
            }
            potions.add(BuiltInRegistries.POTION.getKey(p).toString(), effects);
        }
        out.add("potions", potions);
        JsonObject herbs = new JsonObject();
        Map<String, ItemStack> stacks = new TreeMap<>();
        for (McwowNodes.Herb h : McwowNodes.herbs()) {
            ItemStack s = McwowNodes.herbStack(h, 1);
            stacks.put(h.name(), s);
            JsonObject o = new JsonObject();
            o.addProperty("item", id(s.getItem()));
            o.addProperty("per_harvest", McwowNodes.herbAverage(h));
            o.addProperty("fuel", s.has(DataComponents.BREWING_FUEL));
            herbs.add(h.name(), o);
        }
        out.add("herbs", herbs);
        List<net.minecraft.world.item.crafting.BrewingRecipe> recipes = new ArrayList<>();
        for (RecipeHolder<?> h : server.getRecipeManager().getRecipes()) {
            if (h.value() instanceof net.minecraft.world.item.crafting.BrewingRecipe b) recipes.add(b);
        }
        JsonArray edges = new JsonArray();
        for (var p : BuiltInRegistries.POTION) {
            ItemStack in = net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                    net.minecraft.world.item.Items.POTION, BuiltInRegistries.POTION.wrapAsHolder(p));
            for (Map.Entry<String, ItemStack> herb : stacks.entrySet()) {
                var input = new net.minecraft.world.item.crafting.BrewingInput(in, herb.getValue());
                for (var r : recipes) {
                    if (!r.matches(input)) continue;
                    ItemStack res = r.assemble(input);
                    var contents = res.get(DataComponents.POTION_CONTENTS);
                    if (!res.is(net.minecraft.world.item.Items.POTION) || contents == null || contents.potion().isEmpty()) continue;
                    JsonArray e = new JsonArray();
                    e.add(BuiltInRegistries.POTION.getKey(p).toString());
                    e.add(herb.getKey());
                    e.add(contents.potion().get().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
                    edges.add(e);
                    break;
                }
            }
        }
        out.add("edges", edges);
        return out;
    }

    /** WoW vein names on the route -> what McwowNodes makes of them: {ore block, blocks}. */
    private static JsonObject veins(JsonObject wow, ServerLevel level) {
        JsonObject out = new JsonObject();
        if (!wow.has("veins")) return out;
        for (Map.Entry<String, JsonElement> z : wow.getAsJsonObject("veins").entrySet()) {
            for (String name : z.getValue().getAsJsonObject().keySet()) {
                McwowNodes.Vein v = McwowNodes.forName(name);
                if (v == null || out.has(name)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("ore", v.ore().toString());
                o.addProperty("blocks", v.blocks()); // mining time, in blocks of the ore
                o.addProperty("item", id(McwowNodes.rawOf(level, v)));
                o.addProperty("per_harvest", (McwowNodes.MIN_ORE + McwowNodes.MAX_ORE) / 2.0);
                out.add(name, o);
            }
        }
        return out;
    }

    /** Per block met while mining and per pickaxe material: correct tool, seconds per block, drops. */
    private static JsonObject blocks(ServerLevel level) {
        List<Block> list = new ArrayList<>(List.of(Blocks.STONE, Blocks.DEEPSLATE));
        for (Block b : BuiltInRegistries.BLOCK) {
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (path.endsWith("_ore")) list.add(b);
        }
        JsonObject out = new JsonObject();
        BlockPos pos = new BlockPos(0, 64, 0);
        for (Block b : list) {
            BlockState state = b.defaultBlockState();
            float hardness = state.getDestroySpeed(level, pos);
            JsonObject bo = new JsonObject();
            bo.addProperty("hardness", hardness);
            bo.addProperty("needs_tool", state.requiresCorrectToolForDrops());
            JsonObject by = new JsonObject();
            for (String m : new String[] {"_hand", "wood", "stone", "copper", "gold", "bronze", "iron", "steel", "mithril",
                    "thorium", "dark_iron"}) {
                ItemStack tool = m.equals("_hand") ? ItemStack.EMPTY : McwowDialog.piece(m, "pickaxe", 0, 0);
                if (!m.equals("_hand") && tool.isEmpty()) continue;
                boolean correct = !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
                float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
                float perTick = speed / hardness / (correct ? 30.0F : 100.0F);
                double seconds = perTick >= 1.0F ? 0.05 : Math.ceil(1.0F / perTick) / 20.0;
                JsonObject mo = new JsonObject();
                mo.addProperty("correct", correct);
                mo.addProperty("seconds", seconds);
                Map<String, Double> drops = new TreeMap<>();
                if (correct) {
                    for (int n = 0; n < 50; ++n) {
                        for (ItemStack s : Block.getDrops(state, level, pos, null, null, tool)) {
                            drops.merge(id(s.getItem()), s.getCount() / 50.0, Double::sum);
                        }
                    }
                }
                JsonObject d = new JsonObject();
                drops.forEach(d::addProperty);
                mo.add("drops", d);
                by.add(m, mo);
            }
            bo.add("pickaxe", by);
            out.add(id(b), bo);
        }
        return out;
    }
}
