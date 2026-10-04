package net.mcwow.bridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.mcwow.bridge.combat.McwowActors;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.enchantment.Repairable;

/**
 * Levelled gear (2026-10-03). Every piece of armor, tool and weapon has a WoW item level and a
 * required level, set by the material it was made of (user: the look follows the material):
 * leathers light..rugged (leather armor, dyed darker per tier), cloths linen..runecloth (leather
 * armor dyed light colours), copper (copper), bronze (golden
 * look), iron and steel (iron), mithril (chainmail armor, iron tools), thorium (diamond), dark iron
 * (netherite), plus vanilla's wood, stone and gold. Crafting stamps the result (ShapedRecipeMixin)
 * with the `mcwow:gear` component, the material's name, durability and repair material, and the
 * stats of a vanilla "twin" of its strength (a bronze pickaxe mines like iron, not gold).
 * Pieces without the component (vanilla loot, creative) count as their look's material.
 * The item level drives combat (McwowCombat): a weapon's sets how hard its hits land on WoW
 * creatures, armor's how much WoW hits hurt. Below the required level armor can't be worn and a
 * weapon hits like a bare hand.
 */
public final class McwowGear {
    /** The stamp: material id, item level, required level. */
    public record Gear(String material, int ilvl, int req) {
        static final Codec<Gear> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("material").forGetter(Gear::material),
                Codec.INT.fieldOf("ilvl").forGetter(Gear::ilvl),
                Codec.INT.fieldOf("req").forGetter(Gear::req)).apply(i, Gear::new));
        static final StreamCodec<ByteBuf, Gear> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Gear::material, ByteBufCodecs.VAR_INT, Gear::ilvl,
                ByteBufCodecs.VAR_INT, Gear::req, Gear::new);
    }

    /**
     * A material: its name, item level and required level, the vanilla looks whose stats its armor
     * and tools take, durability against that twin's, repair item (null = keep the item's own), and
     * a leather dye (-1 none).
     */
    public record Material(String id, String title, int ilvl, int req, String armorTwin, String toolTwin,
                           float durability, String repair, int dye, String armorClass) {
    }

    /**
     * Armor classes (user, 2026-10-04): a piece's base points come from its tier (the armor twin);
     * its class splits them into physical protection (Minecraft's armor attribute) and spell
     * protection (WoW spell hits - McwowCombat). Metal 100% / 40%, leather 70 / 70, cloth 40 / 100.
     */
    public static final Map<String, float[]> CLASS_WEIGHTS = Map.of("metal", new float[] {1.0F, 0.4F},
            "leather", new float[] {0.7F, 0.7F}, "cloth", new float[] {0.4F, 1.0F});

    private static final Map<String, Material> MATERIALS = new HashMap<>();
    /** Grid ingredient -> material. */
    private static final Map<Item, String> BY_INGREDIENT = new HashMap<>();
    /** A vanilla look -> the material a piece of that look counts as when unstamped. */
    private static final Map<String, String> BY_LOOK = Map.of("leather", "light_leather", "copper", "copper",
            "golden", "gold", "iron", "iron", "chainmail", "mithril", "diamond", "thorium", "netherite", "dark_iron",
            "wooden", "wood", "stone", "stone");
    private static final List<String> PIECES = List.of("helmet", "chestplate", "leggings", "boots", "sword", "axe",
            "pickaxe", "shovel", "hoe", "spear");
    private static final List<String> ARMOR = List.of("helmet", "chestplate", "leggings", "boots");
    private static final Map<String, String> LEATHER_NAMES = Map.of("helmet", "Cap", "chestplate", "Tunic",
            "leggings", "Pants", "boots", "Boots");
    private static final Map<String, String> CLOTH_NAMES = Map.of("helmet", "Hood", "chestplate", "Robe",
            "leggings", "Pants", "boots", "Boots");

    public static DataComponentType<Gear> GEAR;

    private McwowGear() {
    }

    private static void material(String id, String title, int ilvl, int req, String armorTwin, String toolTwin,
                                 float durability, String repair, int dye) {
        String cls = armorTwin == null ? null : dye < 0 ? "metal" : repair.endsWith("leather") ? "leather" : "cloth";
        MATERIALS.put(id, new Material(id, title, ilvl, req, armorTwin, toolTwin, durability, repair, dye, cls));
    }

    static {
        material("wood", "Wooden", 1, 1, null, "wooden", 1.0F, null, -1);
        material("stone", "Stone", 3, 1, null, "stone", 1.0F, null, -1);
        material("copper", "Copper", 8, 5, "copper", "copper", 1.0F, "minecraft:copper_ingot", -1);
        material("gold", "Golden", 10, 5, "golden", "golden", 1.0F, "minecraft:gold_ingot", -1);
        material("bronze", "Bronze", 18, 14, "iron", "iron", 0.85F, "mcwow:bronze_bar", -1);
        material("iron", "Iron", 28, 24, "iron", "iron", 1.0F, "minecraft:iron_ingot", -1);
        material("steel", "Steel", 35, 30, "iron", "iron", 1.4F, "mcwow:steel_bar", -1);
        material("mithril", "Mithril", 43, 38, "diamond", "diamond", 0.5F, "mcwow:mithril_bar", -1);
        material("thorium", "Thorium", 54, 50, "diamond", "diamond", 1.0F, "mcwow:thorium_bar", -1);
        material("dark_iron", "Dark Iron", 60, 55, "netherite", "netherite", 1.0F, "mcwow:dark_iron_bar", -1);
        // Leather and cloth take their tier's base points (the metal twin of their band); the class
        // weights split them (CLASS_WEIGHTS). Dyes: leather browns, cloth light colours.
        material("light_leather", "Light Leather", 8, 5, "copper", null, 1.0F, "mcwow:light_leather", 0xC8A070);
        material("medium_leather", "Medium Leather", 20, 16, "iron", null, 1.0F, "mcwow:medium_leather", 0xA07848);
        material("heavy_leather", "Heavy Leather", 30, 26, "iron", null, 1.0F, "mcwow:heavy_leather", 0x7A5434);
        material("thick_leather", "Thick Leather", 42, 38, "diamond", null, 1.0F, "mcwow:thick_leather", 0x5E4030);
        material("rugged_leather", "Rugged Leather", 54, 50, "diamond", null, 1.0F, "mcwow:rugged_leather", 0x463228);
        material("linen_cloth", "Linen", 8, 5, "copper", null, 1.0F, "mcwow:linen_cloth", 0xF2EEE2);
        material("wool_cloth", "Wool", 18, 14, "iron", null, 1.0F, "mcwow:wool_cloth", 0xC8D4E8);
        material("silk_cloth", "Silk", 28, 24, "iron", null, 1.0F, "mcwow:silk_cloth", 0xE8C8F0);
        material("mageweave_cloth", "Mageweave", 42, 38, "diamond", null, 1.0F, "mcwow:mageweave_cloth", 0xB8A8F0);
        material("runecloth", "Runecloth", 54, 50, "diamond", null, 1.0F, "mcwow:runecloth", 0x98D8E8);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    public static void register() {
        GEAR = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.fromNamespaceAndPath("mcwow", "gear"),
                DataComponentType.<Gear>builder().persistent(Gear.CODEC).networkSynchronized(Gear.STREAM_CODEC).build());
        BY_INGREDIENT.put(Items.COPPER_INGOT, "copper");
        BY_INGREDIENT.put(Items.GOLD_INGOT, "gold");
        BY_INGREDIENT.put(Items.IRON_INGOT, "iron");
        BY_INGREDIENT.put(Items.LEATHER, "light_leather");
        for (String m : new String[] {"bronze", "steel", "mithril", "thorium", "dark_iron"}) {
            BY_INGREDIENT.put(item("mcwow:" + m + "_bar"), m);
        }
        for (String l : McwowOres.LEATHERS) BY_INGREDIENT.put(item("mcwow:" + l), l);
        for (String c : McwowOres.CLOTHS) BY_INGREDIENT.put(item("mcwow:" + c), c);
        // A weapon (or tool) above the character's level can't attack (user, 2026-10-03: hitting
        // as level 1 still let it be used).
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            ItemStack held = player.getMainHandItem();
            if (usable(held)) return net.minecraft.world.InteractionResult.PASS;
            if (!level.isClientSide()) player.sendOverlayMessage(Component.literal("Requires level " + of(held).req())
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return net.minecraft.world.InteractionResult.FAIL;
        });
        // A starting weapon (user, 2026-10-03: a fresh character had nothing): once per player.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var p = handler.player;
            if (p.entityTags().contains(STARTER_TAG)) return;
            p.addTag(STARTER_TAG);
            ItemStack sword = new ItemStack(Items.STONE_SWORD);
            stamp(sword, MATERIALS.get("stone"), "sword");
            sword.set(DataComponents.ITEM_NAME, Component.literal("Worn Shortsword"));
            if (!p.getInventory().add(sword)) p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                    p.level(), p.getX(), p.getY() + 0.5, p.getZ(), sword));
            p.getInventory().add(new ItemStack(Items.BREAD, 4));
            org.slf4j.LoggerFactory.getLogger("mcwow-bridge").info("mcwow-bridge: starting weapon for {}", p.getGameProfile().name());
        });
    }

    private static final String STARTER_TAG = "mcwow_starter_kit";

    /** "chestplate" of minecraft:golden_chestplate (null if not a gear piece). */
    private static String piece(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        int us = path.lastIndexOf('_');
        if (us < 0 || !BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft")) return null;
        String piece = path.substring(us + 1);
        return PIECES.contains(piece) && BY_LOOK.containsKey(path.substring(0, us)) ? piece : null;
    }

    /** A piece's gear: its stamp, else what its look counts as; null for anything else. */
    public static Gear of(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Gear g = stack.get(GEAR);
        if (g != null) return g;
        String piece = piece(stack.getItem());
        if (piece == null) return null;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        Material m = MATERIALS.get(BY_LOOK.get(path.substring(0, path.lastIndexOf('_'))));
        return m == null ? null : new Gear(m.id(), m.ilvl(), m.req());
    }

    public static Material material(String id) {
        return MATERIALS.get(id);
    }

    /** Every material (the progression sim's export). */
    public static java.util.Collection<Material> materials() {
        return MATERIALS.values();
    }

    /** ShapedRecipeMixin: a crafted piece takes the material that lay in the grid. */
    public static void stampCrafted(ItemStack result, CraftingInput input) {
        if (result.getItem() instanceof Stampable st) {
            // Our own weapons (wands, guns - 2026-10-04): the bar in the grid is the material.
            for (ItemStack in : input.items()) {
                String mat = in.isEmpty() ? null : BY_INGREDIENT.get(in.getItem());
                if (mat != null && MATERIALS.get(mat).toolTwin() != null) {
                    st.stamp(result, MATERIALS.get(mat));
                    return;
                }
            }
            return;
        }
        String piece = piece(result.getItem());
        if (piece == null) return;
        String mat = null;
        for (ItemStack in : input.items()) {
            if (in.isEmpty()) continue;
            mat = BY_INGREDIENT.get(in.getItem());
            if (mat == null && in.is(ItemTags.PLANKS)) mat = "wood";
            if (mat == null && in.is(ItemTags.STONE_TOOL_MATERIALS)) mat = "stone";
            if (mat != null) break;
        }
        if (mat != null) stamp(result, MATERIALS.get(mat), piece);
    }

    /** One of our own weapons (wand, gun): stamped with the material it was crafted from. */
    public interface Stampable {
        void stamp(ItemStack stack, Material m);
    }

    /**
     * A weapon of our own (wand, gun) of a material: its name, item level and required level, the
     * durability of the material's sword and its repair bar.
     */
    public static void stampWeapon(ItemStack stack, Material m, String name) {
        if (m == null) return;
        Item twin = item("minecraft:" + m.toolTwin() + "_sword");
        Integer max = twin.components().get(DataComponents.MAX_DAMAGE);
        if (max != null) stack.set(DataComponents.MAX_DAMAGE, Math.max(1, Math.round(max * m.durability())));
        if (m.repair() != null) {
            stack.set(DataComponents.REPAIRABLE, new Repairable(HolderSet.direct(item(m.repair()).builtInRegistryHolder())));
        }
        stack.set(DataComponents.ITEM_NAME, Component.literal(name));
        stack.set(GEAR, new Gear(m.id(), m.ilvl(), m.req()));
    }

    /** Make a piece of the material: name, stats of its twin, durability, repair item, dye. */
    public static void stamp(ItemStack stack, Material m, String piece) {
        boolean armor = ARMOR.contains(piece);
        String twinLook = armor ? m.armorTwin() : m.toolTwin();
        Item twin = twinLook == null ? null : item("minecraft:" + twinLook + "_" + piece);
        if (twin != null && twin != Items.AIR && twin != stack.getItem()) {
            var c = twin.components();
            if (c.has(DataComponents.ATTRIBUTE_MODIFIERS)) stack.set(DataComponents.ATTRIBUTE_MODIFIERS, c.get(DataComponents.ATTRIBUTE_MODIFIERS));
            if (c.has(DataComponents.TOOL)) stack.set(DataComponents.TOOL, c.get(DataComponents.TOOL));
            if (c.has(DataComponents.WEAPON)) stack.set(DataComponents.WEAPON, c.get(DataComponents.WEAPON));
        }
        if (armor && m.armorClass() != null) {
            float physical = CLASS_WEIGHTS.get(m.armorClass())[0];
            if (physical != 1.0F) stack.set(DataComponents.ATTRIBUTE_MODIFIERS, scaledArmor(
                    stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY), physical));
        }
        Item durabilityOf = twin != null && twin != Items.AIR ? twin : stack.getItem();
        Integer max = durabilityOf.components().get(DataComponents.MAX_DAMAGE);
        if (max != null) stack.set(DataComponents.MAX_DAMAGE, Math.max(1, Math.round(max * m.durability())));
        if (m.repair() != null) {
            stack.set(DataComponents.REPAIRABLE, new Repairable(HolderSet.direct(item(m.repair()).builtInRegistryHolder())));
        }
        if (m.dye() >= 0) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(m.dye()));
        if (!List.of("wood", "stone", "copper", "gold", "iron").contains(m.id())) {
            // "Bronze Chestplate"; leathers as vanilla names leather armor: "Heavy Leather Tunic".
            String name = "cloth".equals(m.armorClass()) ? CLOTH_NAMES.getOrDefault(piece, piece)
                    : m.dye() >= 0 ? LEATHER_NAMES.getOrDefault(piece, piece)
                    : Character.toUpperCase(piece.charAt(0)) + piece.substring(1);
            stack.set(DataComponents.ITEM_NAME, Component.literal(m.title() + " " + name));
        }
        stack.set(GEAR, new Gear(m.id(), m.ilvl(), m.req()));
    }

    /** Armor and toughness modifiers times a factor (the class's physical share). */
    private static ItemAttributeModifiers scaledArmor(ItemAttributeModifiers mods, float factor) {
        List<ItemAttributeModifiers.Entry> out = new java.util.ArrayList<>();
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.attribute().equals(Attributes.ARMOR) || e.attribute().equals(Attributes.ARMOR_TOUGHNESS)) {
                AttributeModifier m = e.modifier();
                e = new ItemAttributeModifiers.Entry(e.attribute(),
                        new AttributeModifier(m.id(), m.amount() * factor, m.operation()), e.slot(), e.display());
            }
            out.add(e);
        }
        return new ItemAttributeModifiers(out);
    }

    /** The armor slot a piece name goes in. */
    private static EquipmentSlot slotOf(String piece) {
        return switch (piece) {
            case "helmet" -> EquipmentSlot.HEAD;
            case "chestplate" -> EquipmentSlot.CHEST;
            case "leggings" -> EquipmentSlot.LEGS;
            default -> EquipmentSlot.FEET;
        };
    }

    /** The armor class of a worn piece (null: not armor gear). */
    public static String armorClass(ItemStack stack) {
        Gear g = of(stack);
        String piece = piece(stack.getItem());
        if (g == null || piece == null || !ARMOR.contains(piece)) return null;
        Material m = MATERIALS.get(g.material());
        return m == null ? null : m.armorClass();
    }

    /**
     * A piece's spell protection {armor, toughness}: its tier's base points (the twin's own armor
     * attribute) times its class's spell share. Zero for anything that isn't armor gear.
     */
    public static float[] spellProtection(ItemStack stack) {
        Gear g = of(stack);
        String piece = piece(stack.getItem());
        if (g == null || piece == null || !ARMOR.contains(piece)) return new float[2];
        Material m = MATERIALS.get(g.material());
        if (m == null || m.armorClass() == null) return new float[2];
        Item twin = item("minecraft:" + m.armorTwin() + "_" + piece);
        if (twin == Items.AIR) return new float[2];
        ItemAttributeModifiers mods = twin.components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        EquipmentSlot slot = slotOf(piece);
        float spell = CLASS_WEIGHTS.get(m.armorClass())[1];
        return new float[] {(float) mods.compute(Attributes.ARMOR, 0.0, slot) * spell,
                (float) mods.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot) * spell};
    }

    /** The player's spell protection {armor, toughness} over the four armor slots. */
    public static float[] spellProtection(Player player) {
        float[] sum = new float[2];
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET}) {
            float[] p = spellProtection(player.getItemBySlot(slot));
            sum[0] += p[0];
            sum[1] += p[1];
        }
        return sum;
    }

    // ---- the WoW level and what it allows ------------------------------------------------------

    /** Our WoW character's level (both sides read benilla's actors file); 0 when unknown. */
    public static int wowLevel() {
        McwowActors.Me me = McwowActors.readMe();
        return me != null ? me.level() : 0;
    }

    /** Whether the player may use a piece (unknown level: yes, rather than locking gear away). */
    public static boolean usable(ItemStack stack) {
        Gear g = of(stack);
        int level = wowLevel();
        return g == null || level <= 0 || level >= g.req();
    }

    /**
     * The level a player's melee hit lands at (McwowCombat.wowDamage): the main-hand piece's item
     * level; a bare hand, a non-gear item or a piece above the player's level hits as level 1.
     * Projectiles keep the character level for now (bows and crossbows have no material yet).
     */
    public static int attackLevel(Player player, int characterLevel, boolean projectile) {
        if (projectile) return characterLevel;
        ItemStack hand = player.getMainHandItem();
        Gear g = of(hand);
        if (g == null || !usable(hand)) return 1;
        return g.ilvl();
    }

    /** The average item level of the four armor slots (an empty slot counts 0). */
    public static float armorLevel(Player player) {
        int sum = 0;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET}) {
            Gear g = of(player.getItemBySlot(slot));
            if (g != null) sum += g.ilvl();
        }
        return sum / 4.0F;
    }

    /**
     * WoW damage on the player, scaled by how the armor's item level compares with the attacker's
     * level (before Minecraft's own armor): 5% more per level the attacker is above, 5% less per
     * level below, within 0.5x..2x. Naked against a level-10 wolf: 1.5x; full light leather (8): 1.1x.
     */
    public static float armorFactor(Player player, int attackerLevel) {
        return armorFactor(armorLevel(player), attackerLevel);
    }

    /** The same from the armor's average item level. */
    public static float armorFactor(float armorLevel, int attackerLevel) {
        return Math.clamp(1.0F + 0.05F * (attackerLevel - armorLevel), 0.5F, 2.0F);
    }
}
