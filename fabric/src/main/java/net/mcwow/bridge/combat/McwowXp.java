package net.mcwow.bridge.combat;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.mcwow.bridge.McwowGeomStore;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kill XP as Minecraft XP orbs (leveling, 2026-10-02; user's design: the WoW server keeps the
 * character's level, its kill XP only counts once Steve picks the orbs up). The server holds each
 * kill's XP and announces it (McwowActors.XpDrop); here it becomes a few real XP orbs at the corpse,
 * sharing the WoW XP between them on top of a vanilla-sized value of their own (Minecraft's
 * enchanting XP, a separate pool). Picking one up claims its share (ExperienceOrbMixin -> CLAIMS ->
 * REN_XP_CLAIM -> CMSG_CC_XP_CLAIM). The share lives in a session-only attachment: after a reload an
 * orb is a plain vanilla one, as the server has forgotten the drop by then.
 *
 * <p>Minecraft's own XP bar is the WoW level (user, 2026-10-02): mirrorLevel sets it from the WoW
 * character every tick, so vanilla XP (ore, smelting, mobs, bottles) gives nothing and an enchanting
 * table or anvil, gated by it, can't lower it.
 */
public final class McwowXp {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final float S = 1.4667f;

    /** An orb's {drop id, WoW XP share}; not saved. */
    public static final AttachmentType<int[]> PAYLOAD = AttachmentRegistry.<int[]>builder()
            .buildAndRegister(Identifier.fromNamespaceAndPath("mcwow", "wow_xp"));

    /** A picked-up orb's claim, for the client to send (server thread -> render thread). */
    public record Claim(int dropId, int xp) {
    }

    public static final ConcurrentLinkedQueue<Claim> CLAIMS = new ConcurrentLinkedQueue<>();

    /** Orbs per kill: a normal creature, an elite or rarer one. */
    private static final int ORBS_NORMAL = 3, ORBS_ELITE = 5;

    private McwowXp() {
    }

    public static void init() {
        // Touch the class so the attachment type registers at startup, not on the first kill.
    }

    /**
     * Steve's Minecraft level and bar, set to the WoW character's (PLAYER_XP / NEXT_LEVEL_XP). The
     * total is only a change marker for Minecraft's XP packet (sent when it differs from the last).
     */
    static void mirrorLevel(net.minecraft.server.level.ServerPlayer player, McwowActors.Me me) {
        if (me.level() <= 0) return;
        float progress = me.nextXp() > 0 ? Math.clamp((float) me.xp() / me.nextXp(), 0.0F, 0.999F) : 0.0F;
        int total = me.level() * 1_000_000 + Math.floorMod(me.xp(), 1_000_000);
        player.experienceLevel = me.level();
        player.experienceProgress = progress;
        player.totalExperience = total;
    }

    static void dropOrbs(ServerLevel level, List<McwowActors.XpDrop> drops) {
        for (McwowActors.XpDrop d : drops) {
            double x = d.y() / S + McwowGeomStore.regionOffsetX;
            double y = d.z() / S + 0.3;
            double z = d.x() / S + McwowGeomStore.regionOffsetZ;
            // A grey kill gives no XP and drops no orbs (user, 2026-10-02).
            if (d.xp() <= 0 || !level.isLoaded(BlockPos.containing(x, y, z))) continue;
            boolean elite = d.rank() > 0;
            int orbs = elite ? ORBS_ELITE : ORBS_NORMAL;
            for (int k = 0; k < orbs; ++k) {
                // A vanilla orb's own value, as a vanilla mob's: 1 each, 2 off an elite.
                ExperienceOrb orb = new ExperienceOrb(level, x, y, z, elite ? 2 : 1);
                // The WoW XP split evenly, the remainder on the first orb.
                int share = d.xp() / orbs + (k == 0 ? d.xp() % orbs : 0);
                if (share > 0) orb.setAttached(PAYLOAD, new int[] { d.dropId(), share });
                level.addFreshEntity(orb);
            }
            LOGGER.info("mcwow-bridge: kill XP drop {}: {} WoW XP in {} orbs (creature level {}, rank {})",
                    d.dropId(), d.xp(), orbs, d.level(), d.rank());
        }
    }
}
