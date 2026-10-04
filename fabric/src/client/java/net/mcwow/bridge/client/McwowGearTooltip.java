package net.mcwow.bridge.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.mcwow.bridge.McwowGear;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Gear tooltips (2026-10-03), WoW style under the name: "Item Level 18", "Requires Level 14" (red
 * while the character is below it).
 */
public final class McwowGearTooltip {
    private McwowGearTooltip() {
    }

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            McwowGear.Gear g = McwowGear.of(stack);
            if (g == null || lines.isEmpty()) return;
            int level = McwowGear.wowLevel();
            lines.add(1, Component.literal("Item Level " + g.ilvl()).withStyle(ChatFormatting.YELLOW));
            lines.add(2, Component.literal("Requires Level " + g.req())
                    .withStyle(level > 0 && level < g.req() ? ChatFormatting.RED : ChatFormatting.WHITE));
        });
    }
}
