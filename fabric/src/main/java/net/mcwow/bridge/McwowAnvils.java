package net.mcwow.bridge;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import org.slf4j.LoggerFactory;

/**
 * WoW's anvils as Minecraft anvils (user, 2026-10-04): right-clicking one (client McwowInteract:
 * a WoW "Anvil" object - a spell focus, which WoW gives no cursor - or a placed anvil doodad in a
 * smithy) opens Minecraft's anvil screen. It has no block behind it (ContainerLevelAccess.NULL), so
 * it never wears out; the XP cost is gated as everywhere (the XP bar is the WoW level).
 */
public final class McwowAnvils {
    private McwowAnvils() {
    }

    /** Client -> server: open an anvil screen at a WoW anvil (its name, for the log). */
    public record OpenAnvil(String name) implements CustomPacketPayload {
        public static final Type<OpenAnvil> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "open_anvil"));
        static final StreamCodec<RegistryFriendlyByteBuf, OpenAnvil> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenAnvil::name, OpenAnvil::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Whether a WoW object's or doodad's name is an anvil ("Anvil", ".../PassiveDoodads/Anvil/Anvil.mdx"). */
    public static boolean isAnvil(String name) {
        String n = name.toLowerCase();
        return n.equals("anvil") || (n.contains("anvil") && (n.endsWith(".mdx") || n.endsWith(".m2")));
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(OpenAnvil.TYPE, OpenAnvil.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OpenAnvil.TYPE, (msg, ctx) -> {
            var p = ctx.player();
            p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new AnvilMenu(id, inv, ContainerLevelAccess.NULL),
                    Component.translatable("container.repair")));
            LoggerFactory.getLogger("mcwow-bridge").info("mcwow-bridge: WoW anvil ({}) used by {}", msg.name(),
                    p.getGameProfile().name());
        });
    }
}
