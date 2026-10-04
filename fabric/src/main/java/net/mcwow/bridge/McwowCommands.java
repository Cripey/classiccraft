package net.mcwow.bridge;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Server commands (game masters): /mcwow digging on|off - digging in the WoW world (McwowTerrainFill). */
public final class McwowCommands {
    private McwowCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                Commands.literal("mcwow").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("digging")
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(() -> Component.literal("Digging in the WoW world is "
                                            + (McwowTerrainFill.digging() ? "on" : "off") + "."), false);
                                    return 1;
                                })
                                .then(Commands.literal("on").executes(ctx -> digging(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> digging(ctx.getSource(), false))))));
    }

    private static int digging(CommandSourceStack src, boolean on) {
        McwowTerrainFill.setDigging(src.getServer(), on);
        src.sendSuccess(() -> Component.literal(on
                ? "Digging in the WoW world is on: the ground under WoW's surface appears as you dig."
                : "Digging in the WoW world is off: filled ground near you is emptied as you pass."), true);
        return 1;
    }
}
