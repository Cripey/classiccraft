package net.mcwow.bridge;

import java.util.List;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quest rewards in Minecraft (2026-10-03, game design: quests reward Minecraft equivalents of their
 * WoW rewards). When a quest is turned in from Minecraft mode (benilla: DIALOG_QUEST_DONE, the
 * server's SMSG_QUESTGIVER_QUEST_COMPLETE), the client hands the granted rows here: each WoW item
 * becomes its Minecraft equivalent (McwowDialog.toMinecraft) and the money emeralds, at the same
 * rate as a kill's (McwowLoot.emeraldCopper of the character's level), at least one. The WoW items
 * themselves stay in the WoW bags; the quest XP is WoW's own.
 */
public final class McwowQuestRewards {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    private McwowQuestRewards() {
    }

    /** Client -> server: a quest turned in, with what WoW granted. */
    public record QuestDone(int questId, int money, int xp, String title, List<McwowDialog.WowItem> items)
            implements CustomPacketPayload {
        public static final Type<QuestDone> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcwow", "quest_done"));
        static final StreamCodec<RegistryFriendlyByteBuf, QuestDone> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, QuestDone::questId, ByteBufCodecs.VAR_INT, QuestDone::money,
                ByteBufCodecs.VAR_INT, QuestDone::xp, ByteBufCodecs.STRING_UTF8, QuestDone::title,
                McwowDialog.WowItem.LIST, QuestDone::items, QuestDone::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    static void grant(ServerPlayer p, QuestDone q) {
        StringBuilder log = new StringBuilder();
        for (McwowDialog.WowItem w : q.items()) {
            ItemStack s = McwowDialog.toMinecraft(w, p.level().registryAccess(), p.getRandom());
            if (s.isEmpty()) continue;
            log.append(' ').append(s.getCount()).append('x').append(s.getHoverName().getString());
            give(p, s);
        }
        if (q.money() > 0) {
            int level = Math.max(1, McwowGear.wowLevel());
            int emeralds = Math.max(1, (int) Math.round(q.money() / net.mcwow.bridge.combat.McwowLoot.emeraldCopper(level)));
            give(p, new ItemStack(Items.EMERALD, emeralds));
            log.append(' ').append(emeralds).append('x').append("emerald");
        }
        p.sendSystemMessage(Component.literal("Quest completed: " + q.title()).withStyle(ChatFormatting.YELLOW));
        LOGGER.info("mcwow-bridge: quest {} ({}) turned in: {} copper, {} XP ->{}", q.questId(), q.title(), q.money(),
                q.xp(), log.isEmpty() ? " nothing" : log);
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) {
            p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY() + 0.5, p.getZ(), s));
        }
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(QuestDone.TYPE, QuestDone.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(QuestDone.TYPE, (msg, ctx) -> grant(ctx.player(), msg));
    }
}
