package net.mcwow.bridge;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * /dance over Minecraft's own networking (2026-10-03): a player's dance goes to the server, which
 * passes it to everyone who sees that player and back to the dancer, so co-op friends see it with
 * their own extracted dances (McwowDance; nothing of the client's data travels, only who dances what).
 */
public final class McwowDanceNet {
    private McwowDanceNet() {
    }

    /**
     * A dance: the dancer's entity id (unused going up), ChrRaces id (0 = stop), sex (0 m, 1 f), and
     * the seed of its variation rolls (the server picks it), so every viewer sees the same order.
     */
    public record Dance(int entity, int race, int sex, int seed) implements CustomPacketPayload {
        public static final Type<Dance> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "dance"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Dance> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Dance::entity, ByteBufCodecs.VAR_INT, Dance::race,
                ByteBufCodecs.VAR_INT, Dance::sex, ByteBufCodecs.INT, Dance::seed, Dance::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Dance.TYPE, Dance.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Dance.TYPE, Dance.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Dance.TYPE, (dance, context) -> {
            ServerPlayer player = context.player();
            Dance out = new Dance(player.getId(), dance.race(), dance.sex(), player.getRandom().nextInt());
            for (ServerPlayer viewer : PlayerLookup.tracking(player)) {
                if (viewer != player) ServerPlayNetworking.send(viewer, out);
            }
            ServerPlayNetworking.send(player, out);
        });
    }
}
