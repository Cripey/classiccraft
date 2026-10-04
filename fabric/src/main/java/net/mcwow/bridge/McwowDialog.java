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
 *    bows, crossbows (and guns), shields; fishing poles a fishing rod;
 *  - the slots Minecraft lacks (wrist, hands, back, neck, finger, trinket, wands, off-hand items):
 *    an enchanted book of the item's level;
 *  - bags a bundle, arrows and bullets arrows, food and drink cooked meat or bread, potions a
 *    healing potion; anything else nothing (the WoW item still lands in the WoW bags).
 *  Quality sets the rarity (the name's colour); uncommon and better gear comes enchanted.
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
                          int req, String name) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WowItem> CODEC = new StreamCodec<>() {
            @Override
            public WowItem decode(RegistryFriendlyByteBuf b) {
                return new WowItem(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                        b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf b, WowItem i) {
                b.writeVarInt(i.id()).writeVarInt(i.count()).writeVarInt(i.quality()).writeVarInt(i.itemClass())
                        .writeVarInt(i.subclass()).writeVarInt(i.inventoryType()).writeVarInt(i.ilvl()).writeVarInt(i.req());
                b.writeUtf(i.name());
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

    /** An enchanted book worth a WoW item of this level and quality. */
    private static ItemStack book(WowItem w, RegistryAccess access, RandomSource random) {
        int level = Math.clamp(w.ilvl() / 2 + (w.quality() - 1) * 4, 1, 30);
        ItemStack b = EnchantmentHelper.enchantItem(random, new ItemStack(Items.BOOK), level, access, Optional.empty());
        b.set(DataComponents.RARITY, rarity(w.quality()));
        return b;
    }

    /**
     * What a WoW item is in Minecraft (EMPTY for none). With {@code random} null the result is a
     * preview: no enchantments rolled (the screen shows "enchanted" instead).
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
                    case 3, 18 -> {
                        return finish(gearless(Items.CROSSBOW, ilvl, w.req()), w, access, random);
                    }
                    case 16 -> {
                        return new ItemStack(Items.ARROW, Math.max(16, w.count()));
                    }
                    case 20 -> {
                        return new ItemStack(Items.FISHING_ROD);
                    }
                    case 19 -> {
                        return random == null ? new ItemStack(Items.ENCHANTED_BOOK) : book(w, access, random);
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
                return random == null ? new ItemStack(Items.ENCHANTED_BOOK) : book(w, access, random);
            }
            return ItemStack.EMPTY;
        }
        // Cloth and leather armor: a leather; mail, plate and weapons: a metal.
        String material = armor && w.subclass() <= 2 ? leather(ilvl) : metal(ilvl);
        return finish(piece(material, piece, ilvl, w.req()), w, access, random);
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
        if (random != null && w.quality() >= 2) {
            int level = w.quality() >= 4 ? 30 : w.quality() == 3 ? Math.clamp(w.ilvl() / 2, 5, 30) : Math.clamp(w.ilvl() / 3, 1, 15);
            s = EnchantmentHelper.enchantItem(random, s, level, access, Optional.empty());
        }
        return s;
    }
}
