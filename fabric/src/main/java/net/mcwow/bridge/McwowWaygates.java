package net.mcwow.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Waygates (user, 2026-10-03): an arcane portal network linking a player's bases across both
 * continents, after Warcraft's mage portals and Titan waygates. A crafted block, named when placed,
 * in the open world only (Eastern Kingdoms and Kalimdor, not dungeon regions). Using one attunes
 * you to it and opens the destinations you are attuned to; travelling is WoW's teleport
 * (CMSG_CC_WAYGATE, VMaNGOS checks open world and, for another player's waygate, that its owner has
 * you as a WoW friend), and the placement brings Steve along. A waygate is Private or shared with
 * the owner's WoW Friends. The network lives in the Minecraft world (co-op shares it).
 */
public final class McwowWaygates {
    public static final String ID = "waygate";
    /** Open-world dimensions: Eastern Kingdoms, Kalimdor. */
    private static final String[] OPEN_WORLD = {"mcwow:map_0", "mcwow:map_1"};
    /** Instance regions start a whole region (47 * 512 blocks) out; the open world is well inside. */
    private static final int OPEN_WORLD_REACH = 12000;
    public static Block BLOCK;
    /** The arrival sound: WoW's own teleport (Sound\\Spells\\Teleport.wav, user's pick), streamed
     *  from the user's install like the music discs (McwowMusicFiles). */
    public static final String TELEPORT_SOUND_KEY = "waygate_teleport";
    public static SoundEvent TELEPORT_SOUND;

    private McwowWaygates() {
    }

    // ---- the network -------------------------------------------------------------------------

    /** One waygate: where, its name, its owner (WoW guid for the server's friend check), sharing. */
    public record Gate(UUID id, String name, Identifier dim, BlockPos pos, float yaw, long ownerGuid,
                       String ownerName, UUID ownerMc, boolean shared) {
        static final Codec<Gate> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(Gate::id),
                Codec.STRING.fieldOf("name").forGetter(Gate::name),
                Identifier.CODEC.fieldOf("dim").forGetter(Gate::dim),
                BlockPos.CODEC.fieldOf("pos").forGetter(Gate::pos),
                Codec.FLOAT.fieldOf("yaw").forGetter(Gate::yaw),
                Codec.LONG.fieldOf("owner_guid").forGetter(Gate::ownerGuid),
                Codec.STRING.fieldOf("owner_name").forGetter(Gate::ownerName),
                UUIDUtil.STRING_CODEC.fieldOf("owner_mc").forGetter(Gate::ownerMc),
                Codec.BOOL.fieldOf("shared").forGetter(Gate::shared)).apply(i, Gate::new));

        Gate with(String name, long guid, String ownerName, boolean shared) {
            return new Gate(id, name, dim, pos, yaw, guid, ownerName, ownerMc, shared);
        }
    }

    public static final class Network extends SavedData {
        final List<Gate> gates = new ArrayList<>();
        /** Minecraft player UUID (string) -> the waygates they are attuned to. */
        final Map<String, List<UUID>> attuned = new HashMap<>();

        static final Codec<Network> CODEC = RecordCodecBuilder.create(i -> i.group(
                Gate.CODEC.listOf().fieldOf("gates").forGetter(n -> n.gates),
                Codec.unboundedMap(Codec.STRING, UUIDUtil.STRING_CODEC.listOf()).fieldOf("attuned")
                        .forGetter(n -> n.attuned)).apply(i, (g, a) -> {
                            Network n = new Network();
                            n.gates.addAll(g);
                            a.forEach((k, v) -> n.attuned.put(k, new ArrayList<>(v)));
                            return n;
                        }));
        static final SavedDataType<Network> TYPE = new SavedDataType<>(
                Identifier.fromNamespaceAndPath("mcwow", "waygates"), Network::new, CODEC, null);

        Gate at(Identifier dim, BlockPos pos) {
            for (Gate g : gates) {
                if (g.dim().equals(dim) && g.pos().equals(pos)) return g;
            }
            return null;
        }

        void replace(Gate old, Gate now) {
            gates.set(gates.indexOf(old), now);
            setDirty();
        }

        void attune(Player p, Gate g) {
            List<UUID> mine = attuned.computeIfAbsent(p.getUUID().toString(), k -> new ArrayList<>());
            if (!mine.contains(g.id())) {
                mine.add(g.id());
                setDirty();
            }
        }
    }

    static Network network(ServerLevel level) {
        return level.getServer().getDataStorage().computeIfAbsent(Network.TYPE);
    }

    static boolean openWorld(Level level, BlockPos pos) {
        String dim = level.dimension().identifier().toString();
        boolean open = false;
        for (String d : OPEN_WORLD) open |= d.equals(dim);
        return open && Math.abs(pos.getX()) < OPEN_WORLD_REACH && Math.abs(pos.getZ()) < OPEN_WORLD_REACH;
    }

    // ---- network messages --------------------------------------------------------------------

    /** Server -> placer: name the waygate just placed at pos. */
    public record NamePrompt(BlockPos pos) implements CustomPacketPayload {
        public static final Type<NamePrompt> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "waygate_name_prompt"));
        static final StreamCodec<RegistryFriendlyByteBuf, NamePrompt> CODEC =
                StreamCodec.composite(BlockPos.STREAM_CODEC, NamePrompt::pos, NamePrompt::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Placer -> server: the name, and the placer's WoW guid (whose friends a shared gate admits). */
    public record Name(BlockPos pos, String name, long wowGuid) implements CustomPacketPayload {
        public static final Type<Name> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "waygate_name"));
        static final StreamCodec<RegistryFriendlyByteBuf, Name> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Name::pos, ByteBufCodecs.STRING_UTF8, Name::name,
                ByteBufCodecs.VAR_LONG, Name::wowGuid, Name::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A destination in the menu. */
    public record Entry(UUID id, String name, Identifier dim, BlockPos pos, float yaw, long ownerGuid,
                        String ownerName, boolean mine, boolean shared) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.of((buf, e) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, e.id());
            buf.writeUtf(e.name());
            buf.writeUtf(e.dim().toString());
            buf.writeBlockPos(e.pos());
            buf.writeFloat(e.yaw());
            buf.writeLong(e.ownerGuid());
            buf.writeUtf(e.ownerName());
            buf.writeBoolean(e.mine());
            buf.writeBoolean(e.shared());
        }, buf -> new Entry(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(), Identifier.parse(buf.readUtf()),
                buf.readBlockPos(), buf.readFloat(), buf.readLong(), buf.readUtf(), buf.readBoolean(), buf.readBoolean()));
    }

    /** Server -> user: the waygate used (`here`) and the attuned destinations. */
    public record Menu(UUID here, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Menu> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "waygate_menu"));
        static final StreamCodec<RegistryFriendlyByteBuf, Menu> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Menu::here, Entry.CODEC.apply(ByteBufCodecs.list()), Menu::entries, Menu::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Owner -> server: share a waygate with WoW friends, or make it private. */
    public record SetShared(UUID id, boolean shared) implements CustomPacketPayload {
        public static final Type<SetShared> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "waygate_shared"));
        static final StreamCodec<RegistryFriendlyByteBuf, SetShared> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, SetShared::id, ByteBufCodecs.BOOL, SetShared::shared, SetShared::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- the block ---------------------------------------------------------------------------

    public static final class WaygateBlock extends Block {
        public WaygateBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            if (!openWorld(ctx.getLevel(), ctx.getClickedPos())) {
                if (ctx.getPlayer() instanceof ServerPlayer p) {
                    p.sendSystemMessage(Component.literal("Waygates can only be raised in the open world of Azeroth."));
                }
                return null;
            }
            return super.getStateForPlacement(ctx);
        }

        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            if (!(level instanceof ServerLevel server) || !(placer instanceof ServerPlayer p)) return;
            Network n = network(server);
            Gate old = n.at(level.dimension().identifier(), pos);
            if (old != null) n.gates.remove(old);
            // The traveller arrives facing away from the gate, toward where its maker stood.
            Gate g = new Gate(UUID.randomUUID(), "Waygate", level.dimension().identifier(), pos.immutable(),
                    p.getYRot() + 180.0F, 0L, p.getGameProfile().name(), p.getUUID(), false);
            n.gates.add(g);
            n.attune(p, g);
            n.setDirty();
            ServerPlayNetworking.send(p, new NamePrompt(pos.immutable()));
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            if (level instanceof ServerLevel server) {
                Network n = network(server);
                Gate g = n.at(level.dimension().identifier(), pos);
                if (g != null) {
                    n.gates.remove(g);
                    n.setDirty();
                }
            }
            return super.playerWillDestroy(level, pos, state, player);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                   BlockHitResult hit) {
            if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer p)) {
                return InteractionResult.SUCCESS;
            }
            Network n = network(server);
            Gate here = n.at(level.dimension().identifier(), pos);
            if (here == null) {
                // A waygate from before the network knew it: adopt it for whoever uses it first.
                here = new Gate(UUID.randomUUID(), "Waygate", level.dimension().identifier(), pos.immutable(),
                        p.getYRot() + 180.0F, 0L, p.getGameProfile().name(), p.getUUID(), false);
                n.gates.add(here);
                n.setDirty();
            }
            boolean mine = here.ownerMc().equals(p.getUUID());
            if (!mine && !here.shared()) {
                p.sendSystemMessage(Component.literal("This waygate is attuned to " + here.ownerName() + " alone."));
                return InteractionResult.SUCCESS;
            }
            n.attune(p, here);
            ServerPlayNetworking.send(p, menu(server, n, p, here));
            return InteractionResult.SUCCESS;
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            // The arcane swirl of a mage's portal.
            for (int i = 0; i < 3; i++) {
                level.addParticle(ParticleTypes.PORTAL, pos.getX() + random.nextDouble(), pos.getY() + 0.5 + random.nextDouble(),
                        pos.getZ() + random.nextDouble(), (random.nextDouble() - 0.5) * 2.0, -random.nextDouble(),
                        (random.nextDouble() - 0.5) * 2.0);
            }
        }
    }

    /** The user's destinations: attuned, still standing (where loaded), theirs or shared with them. */
    static Menu menu(ServerLevel any, Network n, ServerPlayer p, Gate here) {
        List<UUID> mine = n.attuned.getOrDefault(p.getUUID().toString(), List.of());
        n.gates.removeIf(g -> {
            ServerLevel lvl = any.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, g.dim()));
            return lvl != null && lvl.isLoaded(g.pos()) && !lvl.getBlockState(g.pos()).is(BLOCK);
        });
        List<Entry> out = new ArrayList<>();
        for (Gate g : n.gates) {
            boolean own = g.ownerMc().equals(p.getUUID());
            if (!mine.contains(g.id()) || (!own && !g.shared())) continue;
            // Never named (its owner closed the name screen): no WoW guid, so the server couldn't check
            // the friend list - only its owner may travel there.
            if (!own && g.ownerGuid() == 0L) continue;
            out.add(new Entry(g.id(), g.name(), g.dim(), g.pos(), g.yaw(), g.ownerGuid(), g.ownerName(), own, g.shared()));
        }
        return new Menu(here.id(), out);
    }

    public static void register() {
        Identifier sound = Identifier.fromNamespaceAndPath("mcwow", "waygate.teleport");
        TELEPORT_SOUND = Registry.register(BuiltInRegistries.SOUND_EVENT, sound, SoundEvent.createVariableRangeEvent(sound));
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("mcwow", ID));
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, blockKey, new WaygateBlock(BlockBehaviour.Properties.of()
                .setId(blockKey).strength(25.0F, 1200.0F).requiresCorrectToolForDrops().lightLevel(s -> 10)
                .sound(SoundType.LODESTONE)));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("mcwow", ID));
        Item item = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BlockItem(BLOCK, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("functional_blocks"))).register(out -> out.accept(item));

        PayloadTypeRegistry.clientboundPlay().register(NamePrompt.TYPE, NamePrompt.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Menu.TYPE, Menu.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Name.TYPE, Name.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetShared.TYPE, SetShared.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Name.TYPE, (msg, ctx) -> {
            ServerPlayer p = ctx.player();
            if (!(p.level() instanceof ServerLevel server)) return;
            Network n = network(server);
            Gate g = n.at(server.dimension().identifier(), msg.pos());
            if (g == null || !g.ownerMc().equals(p.getUUID())) return;
            String name = msg.name().strip();
            if (name.isEmpty()) name = "Waygate";
            if (name.length() > 32) name = name.substring(0, 32);
            n.replace(g, g.with(name, msg.wowGuid(), p.getGameProfile().name(), g.shared()));
            p.sendSystemMessage(Component.literal("Waygate \"" + name + "\" attuned."));
        });
        ServerPlayNetworking.registerGlobalReceiver(SetShared.TYPE, (msg, ctx) -> {
            ServerPlayer p = ctx.player();
            if (!(p.level() instanceof ServerLevel server)) return;
            Network n = network(server);
            for (Gate g : List.copyOf(n.gates)) {
                if (g.id().equals(msg.id()) && g.ownerMc().equals(p.getUUID())) {
                    n.replace(g, g.with(g.name(), g.ownerGuid(), g.ownerName(), msg.shared()));
                }
            }
        });
    }
}
