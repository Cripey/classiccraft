package net.mcwow.bridge;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * WoW's NPC windows in Minecraft (2026-10-03): the window as benilla sends it (MSG_DIALOG ->
 * McwowDialogs on the client), and what a WoW item becomes in Minecraft - a quest's reward rows
 * are shown as, and granted as, Minecraft items (McwowQuestRewards).
 *
 * WoW item -> Minecraft (by class, subclass, inventory type, item level, quality):
 *  - armor in Minecraft's four slots (head; shoulder/chest/robe; waist/legs; feet): that piece, its
 *    material by item level - cloth and leather armor a leather of the level, mail and plate a
 *    metal - stamped with the WoW item's own item level and required level;
 *  - weapons: swords (and daggers, fist weapons), axes (and maces), spears (staves, polearms),
 *    bows, crossbows, guns (our rifle, or blunderbuss by name), wands (our wand of the item's damage
 *    school), shields; fishing poles a fishing rod;
 *  - the slots Minecraft lacks (wrist, hands, back, neck, finger, trinket, off-hand items):
 *    an enchanted book of the item's level;
 *  - bags a bundle, arrows and bullets arrows, food and drink cooked meat or bread, potions a
 *    healing potion; anything else nothing (the WoW item still lands in the WoW bags).
 *  Quality sets the rarity (the name's colour); enchantments are set from the WoW item's stats
 *  (McwowEnchants.fromStats, 2026-10-04).
 */
public final class McwowDialog {
    public static final int CLOSE = 0, GOSSIP = 1, QUEST_GREETING = 2, QUEST_DETAIL = 3, QUEST_PROGRESS = 4,
            QUEST_REWARD = 5, VENDOR = 6, QUEST_DONE = 7,
            /** A WoW confirmation popup (npc = its number 1..4, options = its buttons). */
            CONFIRM = 8;
    public static final int ACT_SELECT = 1, ACT_ACCEPT = 2, ACT_DECLINE = 3, ACT_CONTINUE = 4, ACT_COMPLETE = 5,
            ACT_CLOSE = 6;
    /** Option icons: gossip, available quest, active quest, vendor, other. */
    public static final int ICON_GOSSIP = 0, ICON_AVAILABLE = 1, ICON_ACTIVE = 2, ICON_VENDOR = 3;

    /** A WoW item row. */
    public record WowItem(int id, int count, int quality, int itemClass, int subclass, int inventoryType, int ilvl,
                          int req, String name, int[] stats, int[] resist, int dmgType) {
        /** stats: (ItemModType, value) pairs; resist: holy, fire, nature, frost, shadow, arcane (2026-10-04). */
        public WowItem(int id, int count, int quality, int itemClass, int subclass, int inventoryType, int ilvl, int req,
                       String name) {
            this(id, count, quality, itemClass, subclass, inventoryType, ilvl, req, name, new int[0], new int[6], 0);
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, WowItem> CODEC = new StreamCodec<>() {
            @Override
            public WowItem decode(RegistryFriendlyByteBuf b) {
                int id = b.readVarInt(), count = b.readVarInt(), quality = b.readVarInt(), cls = b.readVarInt(),
                        sub = b.readVarInt(), inv = b.readVarInt(), ilvl = b.readVarInt(), req = b.readVarInt();
                String name = b.readUtf();
                int[] stats = b.readVarIntArray(), resist = b.readVarIntArray();
                return new WowItem(id, count, quality, cls, sub, inv, ilvl, req, name, stats, resist, b.readVarInt());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf b, WowItem i) {
                b.writeVarInt(i.id()).writeVarInt(i.count()).writeVarInt(i.quality()).writeVarInt(i.itemClass())
                        .writeVarInt(i.subclass()).writeVarInt(i.inventoryType()).writeVarInt(i.ilvl()).writeVarInt(i.req());
                b.writeUtf(i.name());
                b.writeVarIntArray(i.stats());
                b.writeVarIntArray(i.resist());
                b.writeVarInt(i.dmgType());
            }
        };
        public static final StreamCodec<RegistryFriendlyByteBuf, List<WowItem>> LIST =
                CODEC.apply(ByteBufCodecs.list(64));
    }

    /** An option: icon (ICON_*), label. */
    public record Option(int icon, String label) {
    }

    /** One window (benilla's external_dialog::DialogOut). */
    public record Window(long npc, int kind, int questId, String npcName, String title, String text,
                         String objectives, List<Option> options, List<WowItem> choices, List<WowItem> rewards,
                         List<WowItem> required, int money, boolean completable, int xp) {
        /** The WoW creature entry in a creature guid (high 0xF130, entry in bits 24..47). */
        public int npcEntry() {
            return (int) ((npc >>> 24) & 0xFFFFFF);
        }
    }

    private McwowDialog() {
    }

    // ---- WoW item -> Minecraft --------------------------------------------------------------

    private static final int CLASS_CONSUMABLE = 0, CLASS_CONTAINER = 1, CLASS_WEAPON = 2, CLASS_ARMOR = 4,
            CLASS_PROJECTILE = 6;

    /** A metal by item level: copper to 12, bronze 22, iron 32, steel 40, mithril 50, thorium 58, dark iron. */
    public static String metal(int ilvl) {
        return ilvl <= 12 ? "copper" : ilvl <= 22 ? "bronze" : ilvl <= 32 ? "iron" : ilvl <= 40 ? "steel"
                : ilvl <= 50 ? "mithril" : ilvl <= 58 ? "thorium" : "dark_iron";
    }

    /** A leather by item level: light to 17, medium 27, heavy 37, thick 47, rugged. */
    public static String leather(int ilvl) {
        return McwowOres.LEATHERS[ilvl <= 17 ? 0 : ilvl <= 27 ? 1 : ilvl <= 37 ? 2 : ilvl <= 47 ? 3 : 4];
    }

    /** A cloth by item level, as the cloth drops: linen to 14, wool 24, silk 34, mageweave 45, runecloth. */
    public static String cloth(int ilvl) {
        return McwowOres.CLOTHS[ilvl <= 14 ? 0 : ilvl <= 24 ? 1 : ilvl <= 34 ? 2 : ilvl <= 45 ? 3 : 4];
    }

    /** The vanilla look a material's piece has (McwowGear's mapping; mithril tools look iron). */
    public static String look(String material, String piece) {
        boolean armor = List.of("helmet", "chestplate", "leggings", "boots").contains(piece);
        return switch (material) {
            case "copper" -> "copper";
            case "bronze", "gold" -> "golden";
            case "iron", "steel" -> "iron";
            case "mithril" -> armor ? "chainmail" : "iron";
            case "thorium" -> "diamond";
            case "dark_iron" -> "netherite";
            case "wood" -> "wooden";
            case "stone" -> "stone";
            default -> "leather";
        };
    }

    private static Item vanilla(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id));
    }

    /** A gear piece of a material, stamped with the given item level and required level. */
    public static ItemStack piece(String material, String piece, int ilvl, int req) {
        Item item = vanilla(look(material, piece) + "_" + piece);
        if (item == Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        McwowGear.Material m = McwowGear.material(material);
        McwowGear.stamp(stack, m, piece);
        if (ilvl > 0) stack.set(McwowGear.GEAR, new McwowGear.Gear(material, ilvl, Math.max(1, req)));
        return stack;
    }

    private static Rarity rarity(int quality) {
        return quality >= 4 ? Rarity.EPIC : quality == 3 ? Rarity.RARE : quality == 2 ? Rarity.UNCOMMON : Rarity.COMMON;
    }

    /** An enchanted book worth a WoW item: its stats' set enchantments (McwowEnchants.fromStats). */
    private static ItemStack book(WowItem w, RegistryAccess access, RandomSource random) {
        ItemStack b = McwowEnchants.fromStats(new ItemStack(Items.ENCHANTED_BOOK), w, w.itemClass() == CLASS_WEAPON,
                w.inventoryType() == 8, access);
        b.set(DataComponents.RARITY, rarity(w.quality()));
        return b;
    }

    /**
     * What a WoW item is in Minecraft (EMPTY for none). With {@code random} null the result is a
     * preview (enchantments are set from the item, so the preview shows the real ones).
     */
    public static ItemStack toMinecraft(WowItem w, RegistryAccess access, RandomSource random) {
        int ilvl = Math.max(1, w.ilvl());
        String piece = null;
        boolean armor = false;
        switch (w.itemClass()) {
            case CLASS_ARMOR -> {
                if (w.subclass() == 6 || w.inventoryType() == 14) {
                    ItemStack shield = new ItemStack(Items.SHIELD);
                    shield.set(McwowGear.GEAR, new McwowGear.Gear("iron", ilvl, Math.max(1, w.req())));
                    return finish(shield, w, access, random);
                }
                piece = switch (w.inventoryType()) {
                    case 1 -> "helmet";
                    case 3, 5, 20 -> "chestplate";
                    case 6, 7 -> "leggings";
                    case 8 -> "boots";
                    default -> null;
                };
                armor = true;
            }
            case CLASS_WEAPON -> {
                switch (w.subclass()) {
                    case 2 -> {
                        return finish(gearless(Items.BOW, ilvl, w.req()), w, access, random);
                    }
                    case 3 -> {
                        // A gun (2026-10-04): a rifle, or a blunderbuss when WoW calls it one.
                        boolean bb = w.name().toLowerCase().contains("blunderbuss");
                        return finish(ourWeapon(bb ? McwowGuns.BLUNDERBUSS : McwowGuns.RIFLE, ilvl, w.req()), w, access, random);
                    }
                    case 18 -> {
                        return finish(gearless(Items.CROSSBOW, ilvl, w.req()), w, access, random);
                    }
                    case 16 -> {
                        return new ItemStack(Items.ARROW, Math.max(16, w.count()));
                    }
                    case 20 -> {
                        return new ItemStack(Items.FISHING_ROD);
                    }
                    case 19 -> {
                        // A wand (2026-10-04): a wand of its damage school (a physical one: arcane).
                        McwowSpells.School school = McwowSpells.School.byWow(w.dmgType());
                        Item wand = McwowWands.WANDS.get(school != null ? school : McwowSpells.School.ARCANE);
                        return finish(ourWeapon(wand, ilvl, w.req()), w, access, random);
                    }
                    default -> { }
                }
                piece = switch (w.subclass()) {
                    case 0, 1, 4, 5 -> "axe";
                    case 6, 10 -> "spear";
                    case 7, 8, 13, 15 -> "sword";
                    default -> "sword";
                };
            }
            case CLASS_CONTAINER -> {
                return new ItemStack(vanilla("bundle"));
            }
            case CLASS_PROJECTILE -> {
                return new ItemStack(Items.ARROW, Math.clamp(w.count(), 16, 64));
            }
            case CLASS_CONSUMABLE -> {
                String n = w.name().toLowerCase();
                if (n.contains("potion") || n.contains("elixir")) {
                    ItemStack p = new ItemStack(Items.POTION, Math.max(1, Math.min(3, w.count())));
                    p.set(DataComponents.POTION_CONTENTS, new net.minecraft.world.item.alchemy.PotionContents(
                            ilvl >= 30 ? net.minecraft.world.item.alchemy.Potions.STRONG_HEALING
                                    : net.minecraft.world.item.alchemy.Potions.HEALING));
                    return p;
                }
                if (n.contains("bread") || n.contains("biscuit") || n.contains("cake") || n.contains("muffin")) {
                    return new ItemStack(Items.BREAD, Math.clamp(w.count(), 2, 16));
                }
                if (n.contains("water") || n.contains("milk") || n.contains("juice") || n.contains("ale")
                        || n.contains("wine") || n.contains("brew")) {
                    return new ItemStack(Items.HONEY_BOTTLE, Math.clamp(w.count(), 1, 8));
                }
                if (n.contains("meat") || n.contains("steak") || n.contains("ribs") || n.contains("chop")
                        || n.contains("haunch") || n.contains("jerky") || n.contains("sausage") || n.contains("bacon")) {
                    return new ItemStack(Items.COOKED_BEEF, Math.clamp(w.count(), 2, 16));
                }
                if (n.contains("fish") || n.contains("snapper") || n.contains("salmon") || n.contains("trout")) {
                    return new ItemStack(Items.COOKED_COD, Math.clamp(w.count(), 2, 16));
                }
                return ItemStack.EMPTY;
            }
            default -> {
                return ItemStack.EMPTY;
            }
        }
        if (piece == null) {
            // A slot Minecraft lacks (wrist, hands, back, jewellery, off-hand): its worth as a book.
            if (armor || w.itemClass() == CLASS_WEAPON) {
                return book(w, access, random);
            }
            return ItemStack.EMPTY;
        }
        // Armor classes (2026-10-04): cloth (and misc) armor a cloth, leather a leather; mail, plate
        // and weapons a metal.
        String material = armor && w.subclass() <= 1 ? cloth(ilvl) : armor && w.subclass() == 2 ? leather(ilvl) : metal(ilvl);
        return finish(piece(material, piece, ilvl, w.req()), w, access, random);
    }

    /** One of our own weapons (wand, gun) of the item level's metal, stamped with the WoW item level and required level. */
    public static ItemStack ourWeapon(Item item, int ilvl, int req) {
        if (item == null || !(item instanceof McwowGear.Stampable st)) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(item);
        McwowGear.Material m = McwowGear.material(metal(ilvl));
        st.stamp(s, m);
        s.set(McwowGear.GEAR, new McwowGear.Gear(m.id(), ilvl, Math.max(1, req)));
        return s;
    }

    private static ItemStack gearless(Item item, int ilvl, int req) {
        ItemStack s = new ItemStack(item);
        s.set(McwowGear.GEAR, new McwowGear.Gear("wood", ilvl, Math.max(1, req)));
        return s;
    }

    private static ItemStack finish(ItemStack s, WowItem w, RegistryAccess access, RandomSource random) {
        if (s.isEmpty()) return s;
        s.set(DataComponents.RARITY, rarity(w.quality()));
        if (!w.name().isEmpty()) s.set(DataComponents.ITEM_NAME, net.minecraft.network.chat.Component.literal(w.name()));
        // Set enchantments from the WoW item's stats (2026-10-04): the same for everyone, so the
        // reward screen shows the real ones.
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        boolean weapon = w.itemClass() == CLASS_WEAPON && !s.is(Items.SHIELD);
        return McwowEnchants.fromStats(s, w, weapon, path.endsWith("_boots"), access);
    }
}
