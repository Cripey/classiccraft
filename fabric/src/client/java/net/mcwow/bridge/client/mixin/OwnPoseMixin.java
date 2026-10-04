package net.mcwow.bridge.client.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client decides its own player's crouch and crawl in the WoW worlds (2026-10-04). The client
 * drives the player there and works the pose out every tick; the server works it out again from
 * its copy and sends it back, and Minecraft applied that over the client's own. Where the server's
 * check disagreed - Minecraft blocks hidden under WoW's ground that the client's own check passes,
 * and WoW's ground there is not even all real blocks - the server's crawl pose arrived for a tick:
 * the eye eased toward 0.4 and back, a camera dip while walking and mid-jump. Several fixes that
 * chased the blocks behind the server's verdict each missed a case; this removes the verdict.
 * Other poses from the server (sleeping, dying, gliding) still apply.
 */
@Mixin(ClientPacketListener.class)
public abstract class OwnPoseMixin {
    @WrapOperation(method = "handleSetEntityData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/syncher/SynchedEntityData;assignValues(Ljava/util/List;)V"))
    private void mcwow$ownPose(SynchedEntityData data, List<SynchedEntityData.DataValue<?>> values,
                               Operation<Void> original, ClientboundSetEntityDataPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && packet.id() == mc.player.getId() && McwowGeomStore.appliesTo(mc.player.level())
                && values.stream().anyMatch(OwnPoseMixin::forcedPose)) {
            values = values.stream().filter(v -> !forcedPose(v)).toList();
        }
        original.call(data, values);
    }

    private static boolean forcedPose(SynchedEntityData.DataValue<?> v) {
        return v.serializer() == EntityDataSerializers.POSE && (v.value() == Pose.CROUCHING || v.value() == Pose.SWIMMING);
    }
}
