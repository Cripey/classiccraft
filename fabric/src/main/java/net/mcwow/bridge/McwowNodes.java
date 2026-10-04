package net.mcwow.bridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.mcwow.bridge.combat.McwowActors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WoW's ore veins mined with a Minecraft pickaxe (user, 2026-10-04: the real WoW nodes, alongside the
 * ores under the ground). Mining one (client McwowGather, the crosshair on a "Mine" object) takes as
 * long as a few blocks of the ore it's named for would with the pickaxe in hand, and needs a pickaxe
 * that could mine that ore block. At the end the client tells the WoW server (REN_HARVEST ->
 * CMSG_CC_HARVEST: the vein loses one use, WoW's own rule, and despawns when used up) and this
 * server (Harvest payload); the server's answer (SMSG_CC_HARVEST -> actors ring kind 0x12) drops
 * what one WoW loot of a vein gives: 1-2 of the ore's raw item for every metal (user, 2026-10-04:
 * three whole ore blocks per harvest gave ~10 raw copper - vanilla copper ore drops 2-5), Fortune
 * multiplying as on vanilla ore. The pickaxe wears as if those blocks were mined. A vein then
 * gives ~3-6 ore over its uses, as in WoW.
 *
 * <p>WoW's herbs the same way (user, 2026-10-04: herbalism = Minecraft's brewing): gathered with
 * anything (a hoe or shears faster), they drop 1-2 (Black Lotus 1) of a VANILLA brewing ingredient
 * named after the herb and drawn as its own plant (item_model mcwow:herb/<id>), so the brewing stand
 * takes them as that ingredient: Peacebloom a glistering melon slice, Silverleaf nether wart...
 * (resources mcwow/herbs.json, written by tools/gen_mod_data.py).
 *
 * <p>WoW's treasure chests the same way (2026-10-04): the server consumes the chest and McwowChests
 * fills a Minecraft chest screen.
 */
public final class McwowNodes {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final float S = 1.4667f;

    /** A vein: the ore block it is made of and how many blocks of it. */
    public record Vein(Identifier ore, int blocks) {
        public BlockState state() {
            return BuiltInRegistries.BLOCK.getValue(ore).defaultBlockState();
        }
    }

    /** WoW vein names -> ore, first match wins (more specific names first). */
    private static final List<Object[]> VEINS = List.of(
            new Object[] {"rich thorium", "mcwow:thorium_ore", 5},
            new Object[] {"thorium", "mcwow:thorium_ore", 3},
            new Object[] {"dark iron", "mcwow:dark_iron_ore", 3},
            new Object[] {"truesilver", "mcwow:truesilver_ore", 2},
            new Object[] {"mithril", "mcwow:mithril_ore", 3},
            new Object[] {"gold", "minecraft:gold_ore", 2},
            new Object[] {"silver", "mcwow:silver_ore", 2},
            new Object[] {"iron", "minecraft:iron_ore", 3},
            new Object[] {"tin", "mcwow:tin_ore", 3},
            new Object[] {"copper", "minecraft:copper_ore", 3},
            new Object[] {"incendicite", "minecraft:redstone_ore", 2},
            new Object[] {"bloodstone", "minecraft:redstone_ore", 2},
            new Object[] {"indurium", "minecraft:iron_ore", 2});

    /** A WoW herb: its WoW name, item_model id and the vanilla ingredient it is. */
    public record Herb(String name, String id, Identifier item) {
    }

    private static final Map<String, Herb> HERBS = new HashMap<>();

    static {
        try (var in = McwowNodes.class.getResourceAsStream("/mcwow/herbs.json")) {
            var o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (var e : o.entrySet()) {
                var h = e.getValue().getAsJsonObject();
                HERBS.put(e.getKey(), new Herb(e.getKey(), h.get("id").getAsString(), Identifier.parse(h.get("item").getAsString())));
            }
        } catch (Exception ex) {
            LOGGER.warn("mcwow-bridge: cannot read herbs: {}", ex.toString());
        }
    }

    /** Every herb (the progression sim's export). */
    public static java.util.Collection<Herb> herbs() {
        return HERBS.values();
    }

    /** How many of a herb one gather gives: 1-2, the rare Black Lotus 1. */
    public static int herbCount(Herb h, net.minecraft.util.RandomSource r) {
        return h.id().equals("black_lotus") ? 1 : 1 + r.nextInt(2);
    }

    /** ... on average. */
    public static double herbAverage(Herb h) {
        return h.id().equals("black_lotus") ? 1.0 : 1.5;
    }

    /** The herb a WoW object's name is ("Peacebloom"), or null. */
    public static Herb herbFor(String name) {
        return HERBS.get(name);
    }

    /** A herb as the item it drops: the vanilla ingredient, the herb's name and look. */
    public static ItemStack herbStack(Herb h, int count) {
        ItemStack s = new ItemStack(BuiltInRegistries.ITEM.getValue(h.item()), count);
        s.set(net.minecraft.core.component.DataComponents.ITEM_NAME, Component.literal(h.name()));
        s.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("mcwow", "herb/" + h.id()));
        return s;
    }

    /** Raw ore per harvest, as one WoW loot of a vein. */
    public static final int MIN_ORE = 1, MAX_ORE = 2;

    private McwowNodes() {
    }

    /** The raw item a vein's ore block drops (its own loot table, a plain pickaxe). */
    public static net.minecraft.world.item.Item rawOf(ServerLevel level, Vein v) {
        for (ItemStack s : Block.getDrops(v.state(), level, BlockPos.ZERO, null, null,
                new ItemStack(net.minecraft.world.item.Items.NETHERITE_PICKAXE))) {
            if (!s.isEmpty()) return s.getItem();
        }
        return net.minecraft.world.item.Items.AIR;
    }

    /** The vein a WoW object's name stands for ("Ooze Covered Silver Vein" too), or null. */
    public static Vein forName(String name) {
        String n = name.toLowerCase();
        for (Object[] v : VEINS) {
            if (n.contains((String) v[0])) return new Vein(Identifier.parse((String) v[1]), (Integer) v[2]);
        }
        return null;
    }

    /** Client -> server: a vein mined; its ore drops when the WoW server confirms. */
    public record Harvest(long guid, String name) implements CustomPacketPayload {
        public static final Type<Harvest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "harvest"));
        static final StreamCodec<RegistryFriendlyByteBuf, Harvest> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Harvest::guid, ByteBufCodecs.STRING_UTF8, Harvest::name, Harvest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record Pending(UUID player, Vein vein, Herb herb, McwowChests.Kind chest, String name, long nanos) {
    }

    /** Veins mined, waiting for the WoW server's answer (server thread). */
    private static final Map<Long, Pending> PENDING = new HashMap<>();

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Harvest.TYPE, Harvest.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Harvest.TYPE, (msg, ctx) -> {
            McwowChests.Kind c = McwowChests.forName(msg.name());
            Herb h = c == null ? herbFor(msg.name()) : null;
            Vein v = c == null && h == null ? forName(msg.name()) : null;
            if (c == null && h == null && v == null) return;
            PENDING.put(msg.guid(), new Pending(ctx.player().getUUID(), v, h, c, msg.name(), System.nanoTime()));
        });
    }

    /** The WoW server's answers (McwowCombat's server tick): drop the ore of each confirmed vein. */
    public static void confirm(MinecraftServer server, ServerLevel level, List<McwowActors.Harvested> answers) {
        long now = System.nanoTime();
        PENDING.values().removeIf(p -> now - p.nanos() > 30_000_000_000L);
        for (McwowActors.Harvested a : answers) {
            Pending p = PENDING.remove(a.guid());
            if (p == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(p.player());
            if (player == null) continue;
            if (!a.ok()) {
                player.sendOverlayMessage(Component.literal(p.chest() != null
                        ? p.name() + " won't open (already opened, or the WoW server is out of date)"
                        : p.name() + " is gone").withStyle(ChatFormatting.GRAY));
                LOGGER.info("mcwow-bridge: node {} ({}) refused by the server", a.entry(), p.name());
                continue;
            }
            BlockPos pos = BlockPos.containing(a.y() / S + McwowGeomStore.regionOffsetX, a.z() / S + 0.5,
                    a.x() / S + McwowGeomStore.regionOffsetZ);
            if (player.level() != level || !level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 64) {
                pos = player.blockPosition();
            }
            ItemStack tool = player.getMainHandItem();
            var r = player.getRandom();
            if (p.chest() != null) {
                // A treasure chest (2026-10-04): a pried lock wears the pickaxe as its blocks would.
                if (McwowChests.pryBlock(p.chest().pick()) != null && !tool.isEmpty()) {
                    tool.hurtAndBreak(McwowChests.sizeFactor(p.chest().size()), player, EquipmentSlot.MAINHAND);
                }
                McwowChests.open(player, a.entry(), p.name(), pos);
                continue;
            }
            if (p.herb() != null) {
                int n = herbCount(p.herb(), r);
                ItemStack herb = herbStack(p.herb(), n);
                ItemEntity e = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, herb);
                e.setDefaultPickUpDelay();
                level.addFreshEntity(e);
                if (tool.is(net.minecraft.tags.ItemTags.HOES) || tool.is(net.minecraft.world.item.Items.SHEARS)) {
                    tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                }
                LOGGER.info("mcwow-bridge: herb {} ({}) gathered: {}x{}", a.entry(), p.name(), n,
                        BuiltInRegistries.ITEM.getKey(herb.getItem()).getPath());
                continue;
            }
            int n = MIN_ORE + r.nextInt(MAX_ORE - MIN_ORE + 1);
            // Fortune as vanilla ore (apply_bonus ore_drops): x (1 + max(0, rand(level + 2) - 1)).
            int fortune = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FORTUNE), tool);
            if (fortune > 0) n *= Math.max(1, r.nextInt(fortune + 2));
            ItemStack drop = new ItemStack(rawOf(level, p.vein()), n);
            StringBuilder log = new StringBuilder();
            if (!drop.isEmpty()) {
                ItemEntity e = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
                e.setDefaultPickUpDelay();
                level.addFreshEntity(e);
                log.append(' ').append(n).append('x').append(BuiltInRegistries.ITEM.getKey(drop.getItem()).getPath());
            }
            if (!tool.isEmpty()) tool.hurtAndBreak(p.vein().blocks(), player, EquipmentSlot.MAINHAND);
            LOGGER.info("mcwow-bridge: vein {} ({}) mined{}:{}", a.entry(), p.name(), a.depleted() ? ", used up" : "", log);
        }
    }
}
