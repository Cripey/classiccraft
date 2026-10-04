package net.mcwow.bridge.client;

import net.mcwow.bridge.McwowColumns;
import net.mcwow.bridge.McwowGeomStore;
import net.mcwow.bridge.McwowGroundState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft footsteps on WoW's ground (user, 2026-10-02): WoW's own steps for the player are muted
 * (benilla sound/footsteps.rs) and Steve plays Minecraft's, in the sound of the WoW surface under
 * him (benilla's TerrainType, as WoW's footsteps pick it: a building's floor indoors, the ground
 * texture outside). Vanilla plays a step only for a real block underfoot, and on WoW's ground that
 * is air or the hidden top block (its own sound, or nothing on a WMO floor); there vanilla's is
 * cancelled (EntityStepSoundMixin) and this plays at vanilla's cadence and volume. On blocks the
 * player placed or dug down to, vanilla's own steps play. Jumping off and landing on WoW's ground
 * sound the same way (vanilla's come from the block underfoot too, silent there).
 */
public final class McwowFootsteps {
    private static Vec3 last;
    private static float walked, nextStep = 1.0F;
    private static boolean wasOnGround, wasOnWow;


    private McwowFootsteps() {
    }

    /** Standing on WoW's ground: nothing real underfoot, or a still-hidden column top. */
    public static boolean onWowGround(LocalPlayer player) {
        if (!player.onGround() || !McwowGeomStore.appliesTo(player.level())) return false;
        BlockPos on = player.getOnPos();
        if (player.level().getBlockState(on).isAir()) return true;
        int top = McwowColumns.topOf(player.level(), on.getX(), on.getZ());
        return top == on.getY() && !McwowColumns.isOpen(player.level(), on.getX(), on.getZ());
    }

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !McwowGeomStore.appliesTo(mc.level)) {
            last = null;
            return;
        }
        Vec3 pos = p.position();
        boolean onGround = p.onGround(), onWow = onWowGround(p);
        boolean audible = !p.isInWater() && !p.isSpectator() && !p.isPassenger();
        if (last != null && audible) {
            if (wasOnGround && !onGround && wasOnWow && p.getDeltaMovement().y > 0.0) {
                step(mc, p); // took off
            } else if (!wasOnGround && onGround && onWow) {
                // Landed: the soft step; a damaging fall's sound is vanilla's (EntityOnPosMixin gives
                // it the hidden ground block), or it would sound twice.
                step(mc, p);
                nextStep = walked + 1.0F; // the landing is this stride's sound, not a second step
            }
        }
        wasOnGround = onGround;
        wasOnWow = onWow;
        if (last != null && audible && onWow) {
            // Vanilla's: moveDist grows by 0.6 per block walked, a step past each whole number.
            // Less what a transport deck carried him: riding is no walking (2026-10-02, the tram
            // played steps the whole ride).
            Vec3 carried = McwowDeckRide.carried();
            walked += (float) Math.hypot(pos.x - last.x - carried.x, pos.z - last.z - carried.z) * 0.6F;
            if (walked > nextStep) {
                nextStep = (int) walked + 1;
                step(mc, p);
            }
        }
        last = pos;
    }

    /** The surface's step sound, at vanilla's step volume. */
    private static void step(Minecraft mc, LocalPlayer p) {
        SoundType type = soundFor(McwowGroundState.wowTerrain, p);
        if (type == null) return;
        mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), type.getStepSound(), SoundSource.PLAYERS,
                type.getVolume() * 0.15F, type.getPitch(), false);
    }

    /** WoW's TerrainType.dbc (1.12) to Minecraft's block sounds; unknown: the hidden top block's. */
    private static SoundType soundFor(int terrain, LocalPlayer p) {
        return switch (terrain) {
            case 0 -> SoundType.GRAVEL; // Dirt (Minecraft's dirt block sounds like this)
            case 1 -> SoundType.METAL; // Metallic
            case 2 -> SoundType.STONE; // Stone
            case 3 -> SoundType.SNOW; // Snow
            case 4 -> SoundType.WOOD; // Wood
            case 5, 6, 9 -> SoundType.GRASS; // Grass, Leaves, DustyGrass
            case 7 -> SoundType.SAND; // Sand
            case 8 -> SoundType.MUD; // Soggy
            case 10 -> null; // None: silent, as in WoW
            case 11 -> SoundType.WOOL; // benilla: a carpet or rug floor (by its texture)
            default -> {
                var state = p.level().getBlockState(p.getOnPos());
                yield state.isAir() ? null : state.getSoundType();
            }
        };
    }
}
