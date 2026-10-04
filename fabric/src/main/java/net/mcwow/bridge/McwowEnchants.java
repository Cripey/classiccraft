package net.mcwow.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Enchantments on loot (2026-10-04, user's picks 1, 2, 4, 5 of the enchantment options):
 *  - a CURATED pool instead of vanilla's enchanting table (whose books here were mostly Power,
 *    Piercing, Quick Charge...): only what does something in this game - Sharpness, Smite and Bane
 *    (McwowActorEntity applies them to WoW undead / arthropods), Sweeping Edge, Fire Aspect, Looting
 *    (McwowLoot), Unbreaking, the Protections, Thorns, Feather Falling, Efficiency and Fortune. No
 *    Knockback: WoW creatures' stand-ins don't move;
 *  - random enchantments on dropped gear (chests, creatures): count by quality, levels by power
 *    (random); books carry one enchantment of the pool (book);
 *  - SET enchantments on quest rewards from the WoW item's own stats (fromStats): Strength ->
 *    Sharpness on weapons / Thorns on armor, Stamina -> Protection / Unbreaking, Agility -> Sweeping
 *    Edge / Projectile Protection (Feather Falling on boots), Intellect and Spirit -> Looting /
 *    Unbreaking, fire -> Fire Aspect / Fire Protection, holy -> Smite, nature -> Bane; level = value/5
 *    rounded up. The same quest gives everyone the same enchantments.
 */
public final class McwowEnchants {
    /** The pool and each enchantment's weight in random rolls. */
    private static final Map<ResourceKey<Enchantment>, Integer> POOL = new LinkedHashMap<>();

    static {
        POOL.put(Enchantments.SHARPNESS, 10);
        POOL.put(Enchantments.SMITE, 5);
        POOL.put(Enchantments.BANE_OF_ARTHROPODS, 5);
        POOL.put(Enchantments.SWEEPING_EDGE, 4);
        POOL.put(Enchantments.FIRE_ASPECT, 4);
        POOL.put(Enchantments.LOOTING, 3);
        POOL.put(Enchantments.UNBREAKING, 5);
        POOL.put(Enchantments.PROTECTION, 10);
        POOL.put(Enchantments.PROJECTILE_PROTECTION, 5);
        POOL.put(Enchantments.FIRE_PROTECTION, 5);
        POOL.put(Enchantments.THORNS, 2);
        POOL.put(Enchantments.FEATHER_FALLING, 4);
        POOL.put(Enchantments.EFFICIENCY, 6);
        POOL.put(Enchantments.FORTUNE, 3);
    }

    /** What a book's enchantment is meant for, by weight: weapon, chest armor, boots, pickaxe. */
    private static final Item[] BOOK_TARGETS = {Items.IRON_SWORD, Items.IRON_SWORD, Items.IRON_SWORD, Items.IRON_SWORD,
            Items.IRON_CHESTPLATE, Items.IRON_CHESTPLATE, Items.IRON_CHESTPLATE, Items.IRON_CHESTPLATE, Items.IRON_BOOTS,
            Items.IRON_PICKAXE};

    private McwowEnchants() {
    }

    private static Holder<Enchantment> holder(RegistryAccess access, ResourceKey<Enchantment> key) {
        return access.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    /** Every enchantment of the pool (the progression sim's export). */
    public static List<ResourceKey<Enchantment>> pool() {
        return new ArrayList<>(POOL.keySet());
    }

    /** A level for an enchantment at a power 1..30: its max level scaled, rounded at random. */
    private static int level(Holder<Enchantment> e, int power, RandomSource r) {
        int max = e.value().getMaxLevel();
        return Math.clamp((int) (max * Math.clamp(power, 1, 30) / 30.0F + r.nextFloat()), 1, max);
    }

    /** How many enchantments a dropped piece of a quality gets: uncommon 1-2, rare 2-3, epic 3-4. */
    public static int count(int quality, RandomSource r) {
        int base = quality >= 4 ? 3 : quality == 3 ? 2 : 1;
        return base + (r.nextFloat() < 0.45F ? 1 : 0);
    }

    /** Enchant a stack with n random pool enchantments it supports, compatible with each other. */
    public static ItemStack random(ItemStack stack, int power, int n, RandomSource r, RegistryAccess access) {
        List<Holder<Enchantment>> options = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (var e : POOL.entrySet()) {
            Holder<Enchantment> h = holder(access, e.getKey());
            if (h.value().canEnchant(stack)) {
                options.add(h);
                weights.add(e.getValue());
            }
        }
        List<Holder<Enchantment>> chosen = new ArrayList<>();
        for (int k = 0; k < n && !options.isEmpty(); k++) {
            int total = 0;
            for (int w : weights) total += w;
            int pick = r.nextInt(total);
            int i = 0;
            while (pick >= weights.get(i)) pick -= weights.get(i++);
            Holder<Enchantment> h = options.remove(i);
            weights.remove(i);
            chosen.add(h);
            for (int j = options.size() - 1; j >= 0; j--) {
                if (!Enchantment.areCompatible(h, options.get(j))) {
                    options.remove(j);
                    weights.remove(j);
                }
            }
        }
        for (Holder<Enchantment> h : chosen) {
            int lv = level(h, power, r);
            EnchantmentHelper.updateEnchantments(stack, m -> m.upgrade(h, lv));
        }
        return stack;
    }

    /** An enchanted book of one pool enchantment at a power. */
    public static ItemStack book(int power, RandomSource r, RegistryAccess access) {
        ItemStack target = new ItemStack(BOOK_TARGETS[r.nextInt(BOOK_TARGETS.length)]);
        random(target, power, 1, r, access);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        var stored = target.getOrDefault(DataComponents.ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored);
        return book;
    }

    /**
     * A dropped gear piece of a level (chests, creatures): a piece of the level's gear
     * (McwowChests.gearOptions), the quality's rarity and count of random enchantments.
     */
    public static ItemStack gearPiece(int level, int quality, int power, RandomSource r, RegistryAccess access) {
        List<McwowChests.GearOption> options = McwowChests.gearOptions(level);
        float total = 0;
        for (McwowChests.GearOption o : options) total += o.weight();
        float pick = r.nextFloat() * total;
        ItemStack s = ItemStack.EMPTY;
        for (McwowChests.GearOption o : options) {
            s = o.stack().copy();
            if ((pick -= o.weight()) <= 0) break;
        }
        if (s.isEmpty()) return s;
        s.set(DataComponents.RARITY, quality >= 4 ? Rarity.EPIC : quality == 3 ? Rarity.RARE : Rarity.UNCOMMON);
        return random(s, power, count(quality, r), r, access);
    }

    // ---- quest rewards: set enchantments from the WoW item's stats ---------------------------------

    private static final int AGILITY = 3, STRENGTH = 4, INTELLECT = 5, SPIRIT = 6, STAMINA = 7;
    private static final int HOLY = 0, FIRE = 1, NATURE = 2;

    /**
     * The enchantments a WoW item's stats stand for, strongest first, as {key, summed stat value}.
     * Weapons and armor read the same stats differently; boots take agility as Feather Falling.
     */
    public static List<Map.Entry<ResourceKey<Enchantment>, Integer>> statEnchants(McwowDialog.WowItem w, boolean weapon, boolean boots) {
        Map<ResourceKey<Enchantment>, Integer> sum = new LinkedHashMap<>();
        int[] st = w.stats();
        for (int i = 0; st != null && i + 1 < st.length; i += 2) {
            int v = st[i + 1];
            if (v <= 0) continue;
            ResourceKey<Enchantment> k = switch (st[i]) {
                case STRENGTH -> weapon ? Enchantments.SHARPNESS : Enchantments.THORNS;
                case AGILITY -> weapon ? Enchantments.SWEEPING_EDGE : boots ? Enchantments.FEATHER_FALLING : Enchantments.PROJECTILE_PROTECTION;
                case STAMINA -> weapon ? Enchantments.UNBREAKING : Enchantments.PROTECTION;
                case INTELLECT, SPIRIT -> weapon ? Enchantments.LOOTING : Enchantments.UNBREAKING;
                default -> null;
            };
            if (k != null) sum.merge(k, v, Integer::sum);
        }
        int[] res = w.resist();
        for (int i = 0; res != null && i < res.length; i++) {
            if (res[i] <= 0) continue;
            ResourceKey<Enchantment> k = weapon ? (i == FIRE ? Enchantments.FIRE_ASPECT : i == HOLY ? Enchantments.SMITE
                    : i == NATURE ? Enchantments.BANE_OF_ARTHROPODS : Enchantments.SHARPNESS)
                    : i == FIRE ? Enchantments.FIRE_PROTECTION : Enchantments.PROTECTION;
            sum.merge(k, res[i], Integer::sum);
        }
        // A weapon dealing elemental damage: fire burns, holy smites, nature poisons the crawlers.
        if (weapon && w.dmgType() > 0) {
            ResourceKey<Enchantment> k = w.dmgType() == 2 ? Enchantments.FIRE_ASPECT : w.dmgType() == 1 ? Enchantments.SMITE
                    : w.dmgType() == 3 ? Enchantments.BANE_OF_ARTHROPODS : null;
            if (k != null) sum.merge(k, 5 + w.ilvl() / 6, Integer::sum);
        }
        List<Map.Entry<ResourceKey<Enchantment>, Integer>> out = new ArrayList<>(sum.entrySet());
        out.sort((a, b) -> b.getValue() - a.getValue());
        return out;
    }

    /**
     * A quest reward's set enchantments (onto the piece, or stored on a book when {@code target} is
     * an enchanted book): the stats' enchantments the target supports (any, for a book), compatible,
     * at most 2 / 3 / 4 for uncommon / rare / epic, level = stat value / 5 rounded up. An uncommon or
     * better item without such stats rolls random pool enchantments seeded by its id (so it, too,
     * is the same for everyone).
     */
    public static ItemStack fromStats(ItemStack target, McwowDialog.WowItem w, boolean weapon, boolean boots,
                                      RegistryAccess access) {
        boolean book = target.is(Items.ENCHANTED_BOOK);
        ItemStack probe = book ? new ItemStack(weapon ? Items.IRON_SWORD : boots ? Items.IRON_BOOTS : Items.IRON_CHESTPLATE) : target;
        int max = w.quality() >= 4 ? 4 : w.quality() == 3 ? 3 : 2;
        List<Holder<Enchantment>> chosen = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (var e : statEnchants(w, weapon, boots)) {
            if (chosen.size() >= max) break;
            Holder<Enchantment> h = holder(access, e.getKey());
            if (!book && !h.value().canEnchant(probe)) continue;
            boolean ok = true;
            for (Holder<Enchantment> c : chosen) ok &= Enchantment.areCompatible(h, c);
            if (!ok) continue;
            chosen.add(h);
            levels.add(Math.clamp((e.getValue() + 4) / 5, 1, h.value().getMaxLevel()));
        }
        if (chosen.isEmpty()) {
            if (w.quality() < 2) return target;
            RandomSource seeded = RandomSource.create(w.id() * 31L + 7);
            random(probe, Math.clamp(w.ilvl() / 2 + (w.quality() - 2) * 4, 1, 30), count(w.quality(), seeded), seeded, access);
        } else {
            for (int i = 0; i < chosen.size(); i++) {
                Holder<Enchantment> h = chosen.get(i);
                int lv = levels.get(i);
                EnchantmentHelper.updateEnchantments(probe, m -> m.upgrade(h, lv));
            }
        }
        if (book) {
            target.set(DataComponents.STORED_ENCHANTMENTS, probe.getOrDefault(DataComponents.ENCHANTMENTS,
                    net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY));
        }
        return target;
    }
}
