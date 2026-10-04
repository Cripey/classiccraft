package net.mcwow.bridge;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WoW's treasure chests as Minecraft loot chests (2026-10-04, user: chests Minecraftified). Battered
 * Chests, Solid Chests, footlockers, strongboxes and crates out in the world (resources
 * mcwow/chests.json, tools/treasure_chests.py: the server's rule - WoW's Treasure lock, open world,
 * no quest loot) are opened by holding attack on them (client McwowGather): bare-handed (an axe
 * quicker), or - where WoW asks for Lockpicking - pried open with a pickaxe good enough for the
 * lock (pryBlock). The WoW server consumes the chest (CMSG_CC_HARVEST, no WoW loot; it respawns on
 * WoW's own timer and pool) and confirms (McwowNodes.confirm); this then fills a Minecraft chest
 * screen with loot of the chest's zone level and size (roll): emeralds, the level's metal bars,
 * leather or cloth, food, odds and ends, a chance at an enchanted book and at enchanted gear of
 * the level. What's left in it when closed goes to the inventory (or drops), as a crafting grid.
 */
public final class McwowChests {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    /** A kind of chest by name (client: hint, gather time and tool). */
    public record Kind(String name, String size, int pick) {
    }

    /** A chest object entry (server: what it holds). */
    public record Entry(String name, int level, String size, int pick, int spawns) {
    }

    private static final Map<String, Kind> NAMES = new HashMap<>();
    private static final Map<Integer, Entry> ENTRIES = new HashMap<>();

    static {
        try (var in = McwowChests.class.getResourceAsStream("/mcwow/chests.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.getAsJsonObject("names").entrySet()) {
                var a = e.getValue().getAsJsonArray();
                NAMES.put(e.getKey(), new Kind(e.getKey(), a.get(0).getAsString(), a.get(1).getAsInt()));
            }
            for (var e : o.getAsJsonObject("entries").entrySet()) {
                var a = e.getValue().getAsJsonArray();
                ENTRIES.put(Integer.parseInt(e.getKey()), new Entry(a.get(0).getAsString(), a.get(1).getAsInt(),
                        a.get(2).getAsString(), a.get(3).getAsInt(), a.get(4).getAsInt()));
            }
        } catch (Exception ex) {
            LOGGER.warn("mcwow-bridge: cannot read chests: {}", ex.toString());
        }
    }

    private McwowChests() {
    }

    /** The chest kind a WoW object's name is, or null. */
    public static Kind forName(String name) {
        return NAMES.get(name);
    }

    /** Every chest entry (the progression sim's export). */
    public static Map<Integer, Entry> entries() {
        return ENTRIES;
    }

    /**
     * The block a pickaxe must be able to mine to pry a lock open (null: no lock), by WoW's
     * Lockpicking skill: to 100 a stone pickaxe, to 175 iron, beyond that diamond tier (mithril).
     */
    public static BlockState pryBlock(int pick) {
        return pick <= 0 ? null : pick <= 100 ? Blocks.IRON_ORE.defaultBlockState()
                : pick <= 175 ? Blocks.GOLD_ORE.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState();
    }

    /** Blocks' worth of prying (iron ore's time each with the pickaxe in hand) or the open time factor. */
    public static int sizeFactor(String size) {
        return switch (size) {
            case "large" -> 4;
            case "medium" -> 3;
            default -> 2;
        };
    }

    private static int between(RandomSource r, int min, int max) {
        return min + (max > min ? r.nextInt(max - min + 1) : 0);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots", "sword", "axe", "pickaxe"};
    private static final Item[] FOOD = {Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON,
            Items.BAKED_POTATO, Items.APPLE, Items.COOKED_COD};
    private static final Item[] DISCS = {Items.MUSIC_DISC_CAT, Items.MUSIC_DISC_BLOCKS, Items.MUSIC_DISC_CHIRP,
            Items.MUSIC_DISC_FAR, Items.MUSIC_DISC_MALL, Items.MUSIC_DISC_MELLOHI, Items.MUSIC_DISC_STAL,
            Items.MUSIC_DISC_STRAD, Items.MUSIC_DISC_WARD, Items.MUSIC_DISC_WAIT};

    /** A gear piece a chest of a level can hold, with its chance among them (stats only, not enchanted). */
    public record GearOption(ItemStack stack, float weight) {
    }

    /**
     * The gear pieces of a level: every piece alike; armor metal (half), leather or cloth (a quarter
     * each), weapons and pickaxes metal; item level = the level, required level 2 under it.
     */
    public static List<GearOption> gearOptions(int level) {
        List<GearOption> out = new ArrayList<>();
        float each = 1.0F / PIECES.length;
        for (String piece : PIECES) {
            boolean armor = piece.equals("helmet") || piece.equals("chestplate") || piece.equals("leggings") || piece.equals("boots");
            String[] mats = armor ? new String[] {McwowDialog.metal(level), McwowDialog.leather(level), McwowDialog.cloth(level)}
                    : new String[] {McwowDialog.metal(level)};
            float[] w = armor ? new float[] {0.5F, 0.25F, 0.25F} : new float[] {1.0F};
            for (int i = 0; i < mats.length; i++) {
                ItemStack s = McwowDialog.piece(mats[i], piece, level, Math.max(1, level - 2));
                if (!s.isEmpty()) out.add(new GearOption(s, each * w[i]));
            }
        }
        return out;
    }

    /** The enchanting power of a chest's gear piece. */
    public static int gearPower(int level) {
        return Math.clamp(level / 3 + 2, 1, 20);
    }

    /** The enchanting power of a chest's book (small 1, medium 2, large 3). */
    public static int bookPower(int level, int s) {
        return Math.clamp(level / 2 + 2 * s, 1, 30);
    }

    /**
     * Chance of a book by size (small 1, medium 2, large 3) and level: 15 / 35 / 70%, plus 45% to
     * level 20 and 20% to 30 (user, 2026-10-04: the first Battered Chests usually hold one, so
     * enchantments are part of the game from the start).
     */
    public static float bookChance(int s, int level) {
        float base = s == 1 ? 0.15F : s == 2 ? 0.35F : 0.7F;
        return Math.min(0.95F, base + (level <= 20 ? 0.45F : level <= 30 ? 0.2F : 0.0F));
    }

    /** Chance of a gear piece by size. */

    public static float gearChance(int s) {
        return s == 1 ? 0.06F : s == 2 ? 0.15F : 0.35F;
    }

    /** small 1, medium 2, large 3. */
    public static int sizeIndex(String size) {
        return sizeFactor(size) - 1;
    }

    /** One chest's loot (open() fills the screen with it; the progression sim samples it). */
    public static List<ItemStack> roll(int level, String size, String name, RandomSource r, RegistryAccess access) {
        List<ItemStack> out = new ArrayList<>();
        int s = sizeIndex(size);
        out.add(new ItemStack(Items.EMERALD, between(r, s - 1, s + 1)));
        // The level's metal bars (its repair item) - what keeps gear going.
        if (r.nextFloat() < 0.5F + 0.15F * s) {
            String repair = McwowGear.material(McwowDialog.metal(level)).repair();
            if (repair != null) out.add(new ItemStack(item(repair), between(r, s, 2 * s)));
        }
        if (r.nextFloat() < 0.4F) {
            String mat = r.nextBoolean() ? McwowDialog.leather(level) : McwowDialog.cloth(level);
            out.add(new ItemStack(item("mcwow:" + mat), between(r, 1, 2 * s)));
        }
        if (r.nextFloat() < 0.6F) out.add(new ItemStack(FOOD[r.nextInt(FOOD.length)], between(r, 2, 3 + s)));
        if (name.contains("Clam")) {
            if (r.nextFloat() < 0.5F) out.add(new ItemStack(Items.COD, between(r, 1, 2)));
            if (r.nextFloat() < 0.3F) out.add(new ItemStack(Items.PRISMARINE_CRYSTALS, between(r, 1, 3)));
            if (r.nextFloat() < 0.12F) out.add(new ItemStack(Items.NAUTILUS_SHELL));
        } else {
            if (r.nextFloat() < 0.35F) out.add(new ItemStack(Items.TORCH, between(r, 2, 6)));
            if (r.nextFloat() < 0.25F) out.add(new ItemStack(Items.ARROW, between(r, 3, 8)));
            if (r.nextFloat() < 0.15F) out.add(new ItemStack(Items.GUNPOWDER, between(r, 1, 3)));
            if (r.nextFloat() < 0.10F) out.add(new ItemStack(Items.STRING, between(r, 1, 3)));
            if (r.nextFloat() < 0.05F) out.add(new ItemStack(Items.NAME_TAG));
            if (r.nextFloat() < 0.04F) out.add(new ItemStack(Items.SADDLE));
            if (r.nextFloat() < 0.02F) out.add(new ItemStack(DISCS[r.nextInt(DISCS.length)]));
            if (level >= 30 && r.nextFloat() < 0.05F * s) out.add(new ItemStack(Items.ENDER_PEARL));
        }
        if (r.nextFloat() < 0.02F * s * s) out.add(new ItemStack(Items.GOLDEN_APPLE));
        if (s == 3 && r.nextFloat() < 0.01F) out.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
        // The exciting part: a book (small 15%, medium 35%, large 70%) and enchanted gear.
        if (r.nextFloat() < bookChance(s, level)) {
            out.add(McwowEnchants.book(bookPower(level, s), r, access));
        }
        if (r.nextFloat() < gearChance(s)) out.add(McwowEnchants.gearPiece(level, 2, gearPower(level), r, access));
        out.removeIf(ItemStack::isEmpty);
        return out;
    }

    /** A chest the WoW server let us open (McwowNodes.confirm): its loot in a Minecraft chest screen. */
    public static void open(ServerPlayer player, int entry, String name, BlockPos at) {
        Entry e = ENTRIES.get(entry);
        int level = e != null ? e.level() : Math.max(1, McwowGear.wowLevel());
        String size = e != null ? e.size() : forName(name) != null ? forName(name).size() : "small";
        List<ItemStack> loot = roll(level, size, name, player.getRandom(), player.level().registryAccess());
        SimpleContainer box = new SimpleContainer(27);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(i);
        net.minecraft.util.Util.shuffle(slots, player.getRandom());
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < loot.size() && i < 27; i++) {
            ItemStack s = loot.get(i);
            box.setItem(slots.get(i), s);
            log.append(' ').append(s.getCount()).append('x').append(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())
                    .append(s.isEnchanted() || s.has(DataComponents.STORED_ENCHANTMENTS) ? "*" : "");
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new LootMenu(id, inv, box), Component.literal(name)));
        player.level().playSound(null, at, net.minecraft.sounds.SoundEvents.CHEST_OPEN,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.9F + player.getRandom().nextFloat() * 0.2F);
        LOGGER.info("mcwow-bridge: chest {} ({}, level {}, {}) opened:{}", entry, name, level, size, log);
    }

    /** A chest screen whose leftovers go to the player when it closes (nothing to come back to). */
    private static final class LootMenu extends ChestMenu {
        private final Container box;

        LootMenu(int id, net.minecraft.world.entity.player.Inventory inv, Container box) {
            super(MenuType.GENERIC_9x3, id, inv, box, 3);
            this.box = box;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            clearContainer(player, box);
        }
    }
}
