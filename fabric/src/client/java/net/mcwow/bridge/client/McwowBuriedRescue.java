package net.mcwow.bridge.client;

import net.mcwow.bridge.McwowColumns;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local player caught under WoW's ground, in a column's gap over its hidden top block
 * (2026-10-02: after dismounting a horse - vanilla's dismount spot is found on real blocks, the
 * hidden top block - or a clip through a slope on an elytra) is set back on it
 * (McwowColumns.buriedSurface). The client owns the player's position, so this runs here.
 */
public final class McwowBuriedRescue {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static int ticks;

    private McwowBuriedRescue() {
    }

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || (++ticks & 1) != 0 || p.isPassenger() || p.isSpectator() || p.noPhysics
                || !McwowGeomStore.appliesTo(mc.level)) {
            return;
        }
        double ground = McwowColumns.buriedSurface(p);
        if (Double.isNaN(ground)) return;
        LOGGER.info("mcwow-bridge: player buried at {} - set on WoW's ground at y {}", p.position(),
                String.format("%.2f", ground));
        p.setPos(p.getX(), ground + 0.01, p.getZ());
        p.setDeltaMovement(p.getDeltaMovement().multiply(1.0, 0.0, 1.0));
        p.resetFallDistance();
    }
}
