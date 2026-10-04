package net.mcwow.bridge;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ItemLike;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WoW's vendors sell Minecraft items for emeralds (user, 2026-10-03: the original vendor NPCs,
 * Minecraft's trade screen, stock by vendor type). Talking to a WoW vendor from Minecraft mode
 * (benilla's external_dialog: DIALOG_VENDOR -> McwowDialogs) asks the server to open this trade
 * screen (OpenVendor). Each vendor's type and the level of the area it stands in come from
 * resources mcwow/vendors.json (tools/vendor_types.py); gear is of the area's material tier and
 * prices grow with the tier.
 */
public final class McwowVendors {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    /** A vendor's [type, area level]. */
    private static final Map<Integer, Object[]> VENDORS = new HashMap<>();

    private McwowVendors() {
    }

    /** Client -> server: open a WoW vendor's trade screen. */
    public record OpenVendor(int entry, String name) implements CustomPacketPayload {
        public static final Type<OpenVendor> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "open_vendor"));
        static final StreamCodec<RegistryFriendlyByteBuf, OpenVendor> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenVendor::entry, ByteBufCodecs.STRING_UTF8, OpenVendor::name, OpenVendor::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A WoW vendor as a Minecraft merchant: fixed offers, unlimited stock, no XP. */
    private static final class WowMerchant implements Merchant {
        private final MerchantOffers offers;
        private Player trading;

        WowMerchant(MerchantOffers offers) {
            this.offers = offers;
        }

        @Override public void setTradingPlayer(Player p) { trading = p; }
        @Override public Player getTradingPlayer() { return trading; }
        @Override public MerchantOffers getOffers() { return offers; }
        @Override public void overrideOffers(MerchantOffers o) { }
        @Override public void notifyTrade(MerchantOffer o) { o.increaseUses(); }
        @Override public void notifyTradeUpdated(ItemStack s) { }
        @Override public int getVillagerXp() { return 0; }
        @Override public void overrideXp(int xp) { }
        @Override public boolean showProgressBar() { return false; }
        @Override public SoundEvent getNotifyTradeSound() { return SoundEvents.EXPERIENCE_ORB_PICKUP; }
        @Override public boolean isClientSide() { return false; }
        @Override public boolean stillValid(Player p) { return trading == p; }
    }

    // ---- stock --------------------------------------------------------------------------------

    /** A material tier by area level: 0 for 1-10 .. 5 for 51-60. */
    static int tier(int level) {
        return Math.clamp((level - 1) / 10, 0, 5);
    }

    private static final String[] METALS = {"copper", "bronze", "iron", "steel", "mithril", "thorium"};

    private static Item v(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
    }

    private static void sell(MerchantOffers out, ItemStack stack, int emeralds) {
        if (stack.isEmpty()) return;
        out.add(new MerchantOffer(new ItemCost(Items.EMERALD, Math.clamp(emeralds, 1, 64)), stack, Integer.MAX_VALUE, 0, 0.0F));
    }

    private static void sell(MerchantOffers out, ItemLike item, int count, int emeralds) {
        sell(out, new ItemStack(item, count), emeralds);
    }

    /** A gear piece of the tier's metal (or leather), priced by piece and tier. */
    private static void gear(MerchantOffers out, String material, String piece, int t) {
        int base = switch (piece) {
            case "chestplate" -> 4;
            case "leggings", "sword", "axe", "spear", "pickaxe" -> 3;
            case "helmet", "boots" -> 2;
            default -> 1;
        };
        McwowGear.Material m = McwowGear.material(material);
        sell(out, McwowDialog.piece(material, piece, m.ilvl(), m.req()), base * (t + 1));
    }

    static MerchantOffers stock(String type, int level) {
        int t = tier(level);
        String metal = METALS[t];
        String leather = McwowOres.LEATHERS[Math.min(t, 4)];
        MerchantOffers o = new MerchantOffers();
        switch (type) {
            case "weapons" -> {
                for (String p : new String[] {"sword", "axe", "spear"}) gear(o, metal, p, t);
            }
            case "armor" -> {
                for (String p : new String[] {"helmet", "chestplate", "leggings", "boots"}) gear(o, metal, p, t);
                for (String p : new String[] {"helmet", "chestplate", "leggings", "boots"}) gear(o, leather, p, t);
                sell(o, Items.SHIELD, 1, 2 + t);
            }
            case "bowyer" -> {
                sell(o, Items.BOW, 1, 3 + t);
                sell(o, Items.CROSSBOW, 1, 4 + t);
                sell(o, Items.ARROW, 16, 1);
                sell(o, Items.SPECTRAL_ARROW, 8, 2);
            }
            case "innkeeper" -> {
                sell(o, v("red_bed"), 1, 3);
                sell(o, Items.BREAD, 6, 1);
                sell(o, Items.COOKED_BEEF, 4, 1);
                sell(o, Items.CAKE, 1, 2);
                sell(o, Items.HONEY_BOTTLE, 2, 1);
            }
            case "food" -> {
                sell(o, Items.BREAD, 6, 1);
                sell(o, Items.COOKED_BEEF, 4, 1);
                sell(o, Items.COOKED_PORKCHOP, 4, 1);
                sell(o, Items.COOKED_CHICKEN, 4, 1);
                sell(o, Items.APPLE, 6, 1);
                sell(o, Items.COOKIE, 8, 1);
                sell(o, Items.PUMPKIN_PIE, 2, 1);
            }
            case "tailoring" -> {
                sell(o, Items.STRING, 8, 1);
                sell(o, v("white_wool"), 4, 1);
                for (String dye : new String[] {"red_dye", "blue_dye", "green_dye", "yellow_dye", "black_dye"}) {
                    sell(o, v(dye), 4, 1);
                }
                sell(o, v("loom"), 1, 2);
            }
            case "leatherworking" -> {
                sell(o, Items.STRING, 6, 1);
                sell(o, Items.LEATHER, 2, 1);
                sell(o, Items.SADDLE, 1, 4);
                sell(o, Items.LEAD, 2, 2);
            }
            case "blacksmithing" -> {
                sell(o, Items.COAL, 8, 1);
                sell(o, Items.FLINT, 4, 1);
                sell(o, Items.ANVIL, 1, 8);
                sell(o, Items.GRINDSTONE, 1, 3);
                sell(o, Items.SMITHING_TABLE, 1, 2);
                sell(o, Items.BLAST_FURNACE, 1, 4);
            }
            case "engineering" -> {
                sell(o, Items.REDSTONE, 8, 1);
                sell(o, Items.GUNPOWDER, 4, 2);
                sell(o, Items.RAIL, 16, 2);
                sell(o, Items.POWERED_RAIL, 6, 4);
                sell(o, Items.MINECART, 1, 3);
                sell(o, Items.PISTON, 2, 3);
                sell(o, Items.TNT, 1, 4);
            }
            case "alchemy" -> {
                sell(o, Items.GLASS_BOTTLE, 8, 1);
                sell(o, Items.NETHER_WART, 4, 2);
                sell(o, Items.BREWING_STAND, 1, 6);
                sell(o, Items.BLAZE_POWDER, 2, 3);
                sell(o, Items.SUGAR, 4, 1);
                sell(o, Items.GLISTERING_MELON_SLICE, 1, 3);
                sell(o, Items.WHEAT_SEEDS, 8, 1);
            }
            case "enchanting" -> {
                sell(o, Items.LAPIS_LAZULI, 8, 2);
                sell(o, Items.BOOK, 4, 1);
                sell(o, Items.BOOKSHELF, 1, 4);
                sell(o, Items.ENCHANTING_TABLE, 1, 20);
            }
            case "mining" -> {
                gear(o, metal, "pickaxe", t);
                gear(o, metal, "shovel", t);
                sell(o, Items.TORCH, 16, 1);
                sell(o, Items.RAIL, 16, 2);
                sell(o, Items.MINECART, 1, 3);
                sell(o, Items.LANTERN, 2, 1);
            }
            case "fishing" -> {
                sell(o, Items.FISHING_ROD, 1, 2);
                sell(o, Items.STRING, 4, 1);
                sell(o, v("oak_boat"), 1, 2);
            }
            case "cooking" -> {
                sell(o, Items.SMOKER, 1, 3);
                sell(o, Items.CAMPFIRE, 1, 2);
                sell(o, Items.BOWL, 4, 1);
                sell(o, Items.SUGAR, 4, 1);
                sell(o, Items.WHEAT_SEEDS, 8, 1);
            }
            case "reagents" -> {
                sell(o, Items.REDSTONE, 4, 1);
                sell(o, Items.GLOWSTONE_DUST, 4, 2);
                sell(o, Items.ENDER_PEARL, 1, 5);
                sell(o, Items.GUNPOWDER, 2, 1);
            }
            default -> { // general goods
                sell(o, Items.TORCH, 16, 1);
                sell(o, v("bundle"), 1, 3);
                sell(o, Items.ARROW, 16, 1);
                sell(o, Items.BUCKET, 1, 2);
                sell(o, Items.SHEARS, 1, 2);
                sell(o, Items.FLINT_AND_STEEL, 1, 2);
                sell(o, Items.FISHING_ROD, 1, 2);
                sell(o, Items.LEAD, 1, 2);
                sell(o, Items.WHEAT_SEEDS, 8, 1);
                sell(o, v("oak_sapling"), 2, 1);
                sell(o, v("birch_sapling"), 2, 1);
                sell(o, Items.BREAD, 4, 1);
            }
        }
        return o;
    }

    /** A vendor buying: {@code count} of an item for one emerald. */
    private static void buy(MerchantOffers out, ItemLike item, int count) {
        out.add(new MerchantOffer(new ItemCost(item, count), new ItemStack(Items.EMERALD), Integer.MAX_VALUE, 0, 0.0F));
    }

    private static Item mod(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("mcwow", id));
    }

    /** Raw ore of each tier (copper is vanilla's). */
    private static final String[] RAW = {"minecraft:raw_copper", "mcwow:raw_tin", "minecraft:raw_iron",
            "mcwow:raw_mithril", "mcwow:raw_thorium", "mcwow:raw_thorium"};

    /**
     * What every vendor buys for emeralds (user, 2026-10-03: there was no selling): the WoW cloth
     * and leather of its area's tier and the one under it, that tier's raw ore, and the common
     * mob drops. Listed after the goods.
     */
    static void buying(MerchantOffers o, int level) {
        int t = tier(level);
        for (int k = Math.max(0, t - 1); k <= Math.min(t, 4); k++) {
            buy(o, mod(McwowOres.CLOTHS[k]), 8);
            buy(o, mod(McwowOres.LEATHERS[k]), 6);
        }
        buy(o, BuiltInRegistries.ITEM.getValue(Identifier.parse(RAW[t])), 10);
        buy(o, Items.ROTTEN_FLESH, 24);
        buy(o, Items.BONE, 16);
        buy(o, Items.STRING, 16);
        buy(o, Items.FEATHER, 16);
        buy(o, Items.SPIDER_EYE, 8);
    }

    static void open(ServerPlayer p, int entry, String name) {
        Object[] v = VENDORS.get(entry);
        String type = v != null ? (String) v[0] : "general";
        int level = v != null ? (Integer) v[1] : 10;
        MerchantOffers offers = stock(type, level);
        buying(offers, level);
        WowMerchant m = new WowMerchant(offers);
        m.setTradingPlayer(p);
        m.openTradingScreen(p, Component.literal(name.isEmpty() ? "Vendor" : name), 0);
        LOGGER.info("mcwow-bridge: vendor {} ({}, {} goods, area level {}) open for {}", entry, type,
                m.getOffers().size(), level, p.getGameProfile().name());
    }

    public static void register() {
        try (var in = McwowVendors.class.getResourceAsStream("/mcwow/vendors.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                var a = e.getValue().getAsJsonArray();
                VENDORS.put(Integer.parseInt(e.getKey()), new Object[] {a.get(0).getAsString(), a.get(1).getAsInt()});
            }
        } catch (Exception e) {
            LOGGER.warn("mcwow-bridge: cannot read vendors: {}", e.toString());
        }
        PayloadTypeRegistry.serverboundPlay().register(OpenVendor.TYPE, OpenVendor.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OpenVendor.TYPE,
                (msg, ctx) -> open(ctx.player(), msg.entry(), msg.name()));
    }
}
