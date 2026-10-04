package net.mcwow.bridge.client;

import net.mcwow.bridge.combat.McwowActorEntity;
import net.mcwow.bridge.combat.McwowActors;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Melee hits what you see (hitboxes, 2026-10-04): a WoW creature's stand-in is an upright box,
 * while benilla picks the creature under the crosshair against its posed, rendered mesh (target::
 * hover). When the attack button is pressed with benilla's crosshair on a creature WoW lets us
 * attack (focus kind 1) within Minecraft's reach, measured to the model's surface, and nothing of
 * Minecraft's is nearer, the swing goes to that creature's stand-in - a giant's leg, a raptor's
 * tail. Projectiles and mobs still hit the stand-in's box (sized to the visible model).
 */
public final class McwowAim {
    private static final int ATTACK = 1;

    private McwowAim() {
    }

    /** Before Minecraft.startAttack: point its hit result at the creature benilla shows. */
    public static void retarget(Minecraft mc) {
        var player = mc.player;
        McwowActors.Focus f = McwowInteract.focus();
        if (player == null || mc.level == null || f == null || f.kind() != ATTACK || f.guid() == 0) return;
        if (f.distance() > player.entityInteractionRange()) return;
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult e && e.getEntity() instanceof McwowActorEntity a && a.guid() == f.guid()) return;
        if (!McwowInteract.wowNearer(mc, f)) return; // a Minecraft mob or block in front takes it
        McwowActorEntity standIn = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof McwowActorEntity a && a.guid() == f.guid()) {
                standIn = a;
                break;
            }
        }
        if (standIn == null) return;
        Vec3 at = player.getEyePosition().add(player.getViewVector(1.0F).scale(f.distance()));
        mc.hitResult = new EntityHitResult(standIn, at);
    }
}
