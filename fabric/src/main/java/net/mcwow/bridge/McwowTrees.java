package net.mcwow.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chopping WoW's trees (user, 2026-10-04, option B: the tree stays standing). WoW's trees are map
 * doodads, not server objects: benilla's crosshair names the doodad hull under it (focus kind 16,
 * its stable id and model path) and this decides what it is from the model: species by the zone
 * folder and name (Elwynn oak, its pines spruce, Duskwood dark oak / birch / pale oak, Stranglethorn
 * and palms jungle, Barrens acacia, Winterspring spruce, swamps mangrove, Azshara cherry, dead trees
 * stripped logs), logs by size. Holding attack with anything chops it (client McwowGather) as long as
 * Minecraft takes to break that many logs with what's in hand; the server then drops the logs (+ a
 * chance of a sapling, sticks, apples from oaks) and remembers the tree as chopped for REGROW_MILLIS
 * (SavedData `mcwow:chopped_trees`, shared by everyone on the server - co-op sees the same trees),
 * telling every client (Chopped payload) so the hint shows when it regrows.
 */
public final class McwowTrees {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    /** How long a chopped tree rests (user, 2026-10-04: 30 minutes). */
    public static final long REGROW_MILLIS = 30L * 60 * 1000;

    /** A choppable doodad: its log block, how many logs, a sapling (null: none), and a name. */
    public record Tree(Identifier log, int logs, Identifier sapling, boolean apples, String name) {
        public Block block() {
            return BuiltInRegistries.BLOCK.getValue(log);
        }
    }

    /** Zone folder or name words -> species, first match wins (the more specific first). */
    private static final String[][] SPECIES = {
            {"palm", "jungle"}, {"bootybay", "jungle"},
            {"elwynn/", "oak", "pine", "spruce", "fir", "spruce"},
            {"duskwood", "dark_oak", "white", "birch", "spook", "pale_oak"},
            {"strangle", "jungle"}, {"zulgurub", "jungle"}, {"feralas", "jungle"}, {"ungoro", "jungle"},
            {"tanaris", "jungle"},
            {"barrens", "acacia"}, {"durotar", "acacia"}, {"mulgore", "acacia"}, {"desolace", "acacia"},
            {"thousandneedles", "acacia"}, {"badlands", "acacia"},
            {"winterspring", "spruce"}, {"dunmorogh", "spruce"}, {"alterac", "spruce"}, {"aeriepeaks", "spruce"},
            {"hinterland", "spruce"}, {"silverpine", "spruce"}, {"stonetalon", "spruce"}, {"darkshore", "spruce"},
            {"lochmodan", "spruce"}, {"ironforge", "spruce"}, {"hyjal", "spruce"},
            {"swamp", "mangrove"}, {"sorrow", "mangrove"}, {"dustwallow", "mangrove"}, {"wetlands", "mangrove"},
            {"azshara", "cherry"}, {"moonglade", "cherry"},
            {"ashenvale", "dark_oak"}, {"felwood", "dark_oak"}, {"tirisfal", "dark_oak"}, {"plague", "dark_oak"},
            {"teldrassil", "oak"}, {"kalidar", "oak"}, {"darnassus", "oak"},
    };

    private McwowTrees() {
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** What a doodad model path is as a tree, or null (bushes, rocks, fences...). */
    public static Tree forModel(String path) {
        String p = path.toLowerCase().replace('\\', '/');
        String file = p.substring(p.lastIndexOf('/') + 1);
        // "fir" only before a number: AeriePeaksFir01, not fire or firepit.
        if (!has(file, "tree", "pine", "palm", "stump", "trunk", "canopy", "fallen") && !file.matches(".*log\\d*\\.m2")
                && !file.matches(".*fir\\d+.*")) {
            return null;
        }
        if (has(file, "bush", "shrub", "treehouse", "lantern", "sign")) return null;
        String species = "oak";
        for (String[] s : SPECIES) {
            if (!p.contains(s[0])) continue;
            species = s[1];
            for (int k = 2; k + 1 < s.length; k += 2) {
                if (file.contains(s[k])) {
                    species = s[k + 1];
                    break;
                }
            }
            break;
        }
        boolean stump = file.contains("stump"), log = file.contains("log") || file.contains("fallen");
        boolean dead = has(file, "dead", "burnt", "burned", "charred");
        int logs = stump ? 2 : log ? 4 : has(file, "canopy", "huge", "large", "big") ? 10
                : has(file, "small", "young", "sapling") ? 3 : 6;
        String kind = stump ? "Stump" : log ? "Log" : "Tree";
        String title = Character.toUpperCase(species.charAt(0)) + species.substring(1).replace('_', ' ');
        title = title.replace(" o", " O");
        Identifier logId = Identifier.withDefaultNamespace((dead ? "stripped_" : "") + species + "_log");
        Identifier sapling = stump || log || dead ? null
                : Identifier.withDefaultNamespace(species.equals("mangrove") ? "mangrove_propagule" : species + "_sapling");
        return new Tree(logId, logs, sapling, !dead && (species.equals("oak") || species.equals("dark_oak")),
                (dead ? "Dead " : "") + title + " " + kind);
    }

    /** A tree's key: the dimension it stands in and its doodad id. */
    public static String key(Identifier dimension, long id) {
        return dimension + "#" + Long.toHexString(id);
    }

    // ---- payloads --------------------------------------------------------------------------------

    /** Client -> server: a tree chopped (benilla's doodad id and model path). */
    public record Chop(long id, String model) implements CustomPacketPayload {
        public static final Type<Chop> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "chop"));
        static final StreamCodec<RegistryFriendlyByteBuf, Chop> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Chop::id, ByteBufCodecs.STRING_UTF8, Chop::model, Chop::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> clients: chopped trees and when each regrows (epoch millis). */
    public record Chopped(List<String> keys, List<Long> regrow) implements CustomPacketPayload {
        public static final Type<Chopped> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "chopped"));
        static final StreamCodec<RegistryFriendlyByteBuf, Chopped> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Chopped::keys,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), Chopped::regrow, Chopped::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Chopped trees on this client (key -> regrow time), from the server. */
    public static final Map<String, Long> CLIENT_CHOPPED = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- the chopped trees -----------------------------------------------------------------------

    public static final class Store extends SavedData {
        final Map<String, Long> regrow = new HashMap<>();
        static final Codec<Store> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(m -> {
            Store s = new Store();
            s.regrow.putAll(m);
            return s;
        }, s -> s.regrow);
        static final SavedDataType<Store> TYPE = new SavedDataType<>(
                Identifier.fromNamespaceAndPath("mcwow", "chopped_trees"), Store::new, CODEC, null);
    }

    private static Store store(MinecraftServer server) {
        Store s = server.getDataStorage().computeIfAbsent(Store.TYPE);
        long now = System.currentTimeMillis();
        if (s.regrow.values().removeIf(t -> t <= now)) s.setDirty();
        return s;
    }

    private static Chopped all(Store s) {
        List<String> keys = new ArrayList<>(s.regrow.keySet());
        return new Chopped(keys, keys.stream().map(s.regrow::get).toList());
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Chop.TYPE, Chop.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Chopped.TYPE, Chopped.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerPlayNetworking.send(handler.player, all(store(server))));
        ServerPlayNetworking.registerGlobalReceiver(Chop.TYPE, (msg, ctx) -> chop(ctx.player(), msg));
    }

    private static void chop(ServerPlayer player, Chop msg) {
        Tree tree = forModel(msg.model());
        if (tree == null) return;
        MinecraftServer server = player.level().getServer();
        Store s = store(server);
        String key = key(player.level().dimension().identifier(), msg.id());
        long now = System.currentTimeMillis();
        Long until = s.regrow.get(key);
        if (until != null && until > now) {
            player.sendOverlayMessage(Component.literal(tree.name() + " is chopped - regrows in "
                    + Math.max(1, (until - now) / 60_000) + " min").withStyle(ChatFormatting.GRAY));
            return;
        }
        s.regrow.put(key, now + REGROW_MILLIS);
        s.setDirty();
        RandomSource r = player.getRandom();
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(item(tree.log()), tree.logs()));
        if (tree.sapling() != null && r.nextFloat() < 0.3F) drops.add(new ItemStack(item(tree.sapling())));
        if (r.nextFloat() < 0.5F) drops.add(new ItemStack(item(Identifier.withDefaultNamespace("stick")), 1 + r.nextInt(3)));
        if (tree.apples() && r.nextFloat() < 0.1F) drops.add(new ItemStack(item(Identifier.withDefaultNamespace("apple"))));
        StringBuilder log = new StringBuilder();
        for (ItemStack d : drops) {
            ItemEntity e = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), d);
            e.setDefaultPickUpDelay();
            player.level().addFreshEntity(e);
            log.append(' ').append(d.getCount()).append('x').append(BuiltInRegistries.ITEM.getKey(d.getItem()).getPath());
        }
        ItemStack tool = player.getMainHandItem();
        if (tool.isDamageableItem()) tool.hurtAndBreak(tree.logs(), player, EquipmentSlot.MAINHAND);
        Chopped one = new Chopped(List.of(key), List.of(now + REGROW_MILLIS));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(p, one);
        LOGGER.info("mcwow-bridge: {} chopped {} ({}):{}", player.getGameProfile().name(), tree.name(), msg.model(), log);
    }

    private static Item item(Identifier id) {
        return BuiltInRegistries.ITEM.getValue(id);
    }
}
