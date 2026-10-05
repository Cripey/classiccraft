package net.mcwow.bridge.client.mixin;

import net.mcwow.bridge.client.McwowSession;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "Save and Quit Game" instead of "Save and Quit to Title" while WoW shows Minecraft (2026-10-05,
 * user): leaving the world then closes the whole game (McwowSession), as the title screen can't be
 * reached from WoW's window.
 */
@Mixin(CommonComponents.class)
public abstract class QuitButtonLabelMixin {
    @Inject(method = "disconnectButtonLabel", at = @At("HEAD"), cancellable = true)
    private static void mcwow$quitGame(boolean local, CallbackInfoReturnable<Component> cir) {
        if (local && McwowSession.leavingQuits()) cir.setReturnValue(Component.literal("Save and Quit Game"));
    }
}
