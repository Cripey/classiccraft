package net.mcwow.bridge.client;

import net.mcwow.bridge.combat.McwowActors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Gathering WoW's objects the Minecraft way (2026-10-03, user's pick of three: "harvest it like a
 * block"). Holding the attack button on a WoW game object (a cactus apple, a crate, a plant) - not
 * a creature, and not WoW's mining veins or herbs, which need WoW skills - works it like mining a
 * block: arm swings, the hit sound and particles of a block that looks like it, a progress bar on
 * the action bar, faster with the right tool (ticksFor); at the end WoW's own use runs (the
 * server skips WoW's Opening cast for us, Spell.cpp classiccraft hook) (McwowInteract's right-click, REN_INTERACT), the
 * loot goes straight to the quest log (benilla auto-loots in Minecraft mode) and McwowQuestLog
 * announces what came in. MinecraftAttackMixin keeps Minecraft's own attack out meanwhile.
 */
public final class McwowGather {
    /** Ticks to gather one object bare-handed (or with the wrong tool). */
    private static final int TICKS = 24;
    /** The fastest a gather gets (a good tool). */
    private static final int MIN_TICKS = 6;
    /** benilla's cursor kinds a gather works on: loot (3), use (4), open (10). */
    private static final int LOOT = 3, USE = 4, OPEN = 10;

    private static long target;
    private static int progress;
    private static boolean active;
    private static long gatheredAt;

    private McwowGather() {
    }

    /** A WoW game object (guid high 0xF110) a gather can work on, nearer than Minecraft's own target. */
    private static boolean gatherable(Minecraft mc, McwowActors.Focus f) {
        if (f == null || f.guid() == 0 || (f.guid() >>> 48) != 0xF110L) return false;
        if (f.kind() != LOOT && f.kind() != USE && f.kind() != OPEN) return false;
        return McwowInteract.wowNearer(mc, f);
    }

    /** Whether the attack button belongs to a gather this tick (MinecraftAttackMixin). */
    public static boolean active() {
        return active;
    }

    /** Just gathered something (McwowQuestLog announces loot for a while after). */
    static boolean recentlyGathered() {
        return System.nanoTime() - gatheredAt < 8_000_000_000L;
    }

    /** A block that looks like the object, for its particles and sounds. */
    private static BlockState lookalike(String name) {
        String n = name.toLowerCase();
        if (n.contains("cactus")) return Blocks.CACTUS.defaultBlockState();
        if (n.contains("ore") || n.contains("rock") || n.contains("stone") || n.contains("rubble")) {
            return Blocks.STONE.defaultBlockState();
        }
        if (n.contains("mushroom") || n.contains("shroom")) return Blocks.RED_MUSHROOM_BLOCK.defaultBlockState();
        if (n.contains("plant") || n.contains("flower") || n.contains("weed") || n.contains("grass") || n.contains("bloom")
                || n.contains("root") || n.contains("pumpkin") || n.contains("harvest") || n.contains("wheat") || n.contains("corn")) {
            return Blocks.OAK_LEAVES.defaultBlockState();
        }
        if (n.contains("egg") || n.contains("nest")) return Blocks.HAY_BLOCK.defaultBlockState();
        if (n.contains("barrel") || n.contains("crate") || n.contains("box") || n.contains("chest") || n.contains("cask")) {
            return Blocks.OAK_PLANKS.defaultBlockState();
        }
        return Blocks.OAK_PLANKS.defaultBlockState();
    }

    /**
     * The block whose right tool speeds this object up (user, 2026-10-03: timing like mining a
     * block): wooden things want an axe, plants (and cactus) a hoe or shears, rocks a pickaxe.
     */
    private static BlockState toolBlock(BlockState look) {
        if (look.is(Blocks.CACTUS) || look.is(Blocks.HAY_BLOCK) || look.is(Blocks.RED_MUSHROOM_BLOCK)) {
            return Blocks.OAK_LEAVES.defaultBlockState();
        }
        return look;
    }

    /** Ticks for this object with what's in hand: Minecraft's own mining speed of the tool on it. */
    private static int ticksFor(net.minecraft.world.entity.player.Player player, BlockState look) {
        float speed = player.getMainHandItem().getDestroySpeed(toolBlock(look));
        if (speed <= 1.0F) return TICKS;
        // wooden axe (2) -> 20 ticks, stone (4) -> 14, iron (6) -> 11, shears on leaves (15) -> 7
        return Math.max(MIN_TICKS, Math.round(TICKS * 2.0F / (speed + 1.0F) + 4));
    }

    static void tick(Minecraft mc) {
        var player = mc.player;
        McwowActors.Focus f = McwowInteract.focus();
        boolean held = player != null && mc.gui.screen() == null && mc.options.keyAttack.isDown();
        if (!held || !gatherable(mc, f) || f.unable()) {
            if (held && f != null && gatherable(mc, f) && f.unable()) {
                mc.gui.chatListener().handleOverlay(Component.literal(f.name() + " (too far)").withStyle(ChatFormatting.GRAY));
            }
            active = held && gatherable(mc, f); // too far: still ours, so Minecraft doesn't swing at the air behind it
            progress = 0;
            target = 0;
            return;
        }
        active = true;
        if (f.guid() != target) {
            target = f.guid();
            progress = 0;
        }
        progress++;
        if (progress <= 0) return; // the short pause after a gather: nothing to show yet
        Vec3 at = player.getEyePosition().add(player.getViewVector(1.0F).scale(Math.max(0.5, f.distance() - 0.2)));
        BlockState look = lookalike(f.name());
        if (progress % 4 == 1) {
            player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
            var sound = look.getSoundType();
            mc.level.playLocalSound(at.x, at.y, at.z, sound.getHitSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F, false);
            for (int k = 0; k < 4; k++) {
                mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, look), at.x + (Math.random() - 0.5) * 0.4,
                        at.y + (Math.random() - 0.5) * 0.4, at.z + (Math.random() - 0.5) * 0.4, 0.0, 0.0, 0.0);
            }
        }
        int ticks = ticksFor(player, look);
        int bars = Math.clamp(progress * 10 / ticks, 0, 10);
        mc.gui.chatListener().handleOverlay(Component.literal("Gathering " + f.name() + " ")
                .append(Component.literal("|".repeat(bars)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("|".repeat(10 - bars)).withStyle(ChatFormatting.DARK_GRAY)));
        if (progress >= ticks) {
            McwowInteract.OUT.incrementAndGet(); // WoW's own use of it
            var sound = look.getSoundType();
            mc.level.playLocalSound(at.x, at.y, at.z, sound.getBreakSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
            for (int k = 0; k < 16; k++) {
                mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, look), at.x + (Math.random() - 0.5) * 0.6,
                        at.y + (Math.random() - 0.5) * 0.6, at.z + (Math.random() - 0.5) * 0.6, 0.0, 0.05, 0.0);
            }
            gatheredAt = System.nanoTime();
            progress = -10; // a short pause before the next
        }
    }
}
