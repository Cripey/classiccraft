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
 * WoW's ore veins (2026-10-04) are mined the same way with a pickaxe and its herbs gathered
 * (McwowNodes), WoW's trees chopped (McwowTrees), its locked treasure chests pried open (McwowChests; unlocked ones open on right-click, McwowInteract).
 */
public final class McwowGather {
    /** Ticks to gather one object bare-handed (or with the wrong tool). */
    private static final int TICKS = 24;
    /** The fastest a gather gets (a good tool). */
    private static final int MIN_TICKS = 6;
    /** benilla's cursor kinds a gather works on: loot (3), use (4), open (10), and ore veins (mine, 11). */
    private static final int LOOT = 3, USE = 4, OPEN = 10, MINE = 11, HERBS = 12;
    /** benilla's focus kind for a placed doodad (its hull id and model path): trees (McwowTrees). */
    private static final int DOODAD = 16;
    /** Reach for a vein (blocks; WoW calls it unusable without the Mining skill, so its flag can't say). */
    private static final double VEIN_REACH = 5.0;
    /** Veins mined, for benilla (REN_HARVEST, flushed by McwowWorldExporter). */
    static final java.util.concurrent.ConcurrentLinkedQueue<Long> HARVESTS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static long target;
    private static int progress;
    private static boolean active;
    private static long gatheredAt;

    private McwowGather() {
    }

    /** A WoW game object (guid high 0xF110) a gather can work on, nearer than Minecraft's own target. */
    private static boolean gatherable(Minecraft mc, McwowActors.Focus f) {
        if (f != null && f.kind() == DOODAD && f.guid() != 0) {
            return net.mcwow.bridge.McwowTrees.forModel(f.name()) != null && McwowInteract.wowNearer(mc, f);
        }
        if (f == null || f.guid() == 0 || (f.guid() >>> 48) != 0xF110L) return false;
        var chest = net.mcwow.bridge.McwowChests.forName(f.name());
        if (chest != null) return chest.pick() > 0 && f.kind() > 1 && McwowInteract.wowNearer(mc, f); // unlocked: right-click
        if (f.kind() == HERBS && net.mcwow.bridge.McwowNodes.herbFor(f.name()) == null) return false;
        if (f.kind() != LOOT && f.kind() != USE && f.kind() != OPEN && f.kind() != MINE && f.kind() != HERBS) return false;
        return McwowInteract.wowNearer(mc, f);
    }

    /**
     * Whether the attack button belongs to a gather this tick (MinecraftAttackMixin): one running,
     * or the crosshair on something a gather takes - Minecraft handles a press before our tick
     * runs, so the first swing of a press went to the block behind (2026-10-04).
     */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return active || (mc.player != null && gatherable(mc, McwowInteract.focus()));
    }

    /** An unlocked treasure chest right-clicked (McwowInteract): the WoW server consumes it, then this server opens its chest screen. */
    static void openChest(Minecraft mc, McwowActors.Focus f) {
        HARVESTS.add(f.guid());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new net.mcwow.bridge.McwowNodes.Harvest(f.guid(), f.name()));
        gatheredAt = System.nanoTime();
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

    /** Whether the target is out of reach: WoW's own verdict, a vein by distance. */
    static boolean tooFar(McwowActors.Focus f) {
        // WoW greys veins and herbs out without its Mining / Herbalism skill: by distance instead.
        return f.kind() == MINE || f.kind() == HERBS || f.kind() == DOODAD || net.mcwow.bridge.McwowChests.forName(f.name()) != null
                ? f.distance() > VEIN_REACH : f.unable();
    }

    /**
     * A vein's or a tree's time: its blocks (ore, logs), each as long as Minecraft takes to break one
     * with what's in hand (a log needs no tool, an axe speeds it).
     */
    private static int blockTicks(net.minecraft.world.entity.player.Player player, BlockState block, int blocks) {
        float hardness = block.getDestroySpeed(player.level(), player.blockPosition());
        float perTick = player.getDestroySpeed(block) / hardness / 30.0F;
        int perBlock = perTick >= 1.0F ? 1 : (int) Math.ceil(1.0F / perTick);
        return Math.max(MIN_TICKS, perBlock * blocks);
    }

    /** Milliseconds until a chopped tree regrows (0: choppable). */
    static long choppedFor(net.minecraft.world.entity.player.Player player, long id) {
        Long until = net.mcwow.bridge.McwowTrees.CLIENT_CHOPPED.get(
                net.mcwow.bridge.McwowTrees.key(player.level().dimension().identifier(), id));
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    static void tick(Minecraft mc) {
        var player = mc.player;
        McwowActors.Focus f = McwowInteract.focus();
        boolean held = player != null && mc.gui.screen() == null && mc.options.keyAttack.isDown();
        net.mcwow.bridge.McwowNodes.Vein vein = f != null && f.kind() == MINE ? net.mcwow.bridge.McwowNodes.forName(f.name()) : null;
        net.mcwow.bridge.McwowTrees.Tree tree = f != null && f.kind() == DOODAD ? net.mcwow.bridge.McwowTrees.forModel(f.name()) : null;
        net.mcwow.bridge.McwowNodes.Herb herb = f != null && f.kind() == HERBS ? net.mcwow.bridge.McwowNodes.herbFor(f.name()) : null;
        net.mcwow.bridge.McwowChests.Kind chest = f != null && (f.guid() >>> 48) == 0xF110L
                ? net.mcwow.bridge.McwowChests.forName(f.name()) : null;
        BlockState pry = chest != null ? net.mcwow.bridge.McwowChests.pryBlock(chest.pick()) : null;
        if (held && pry != null && gatherable(mc, f) && !tooFar(f)) {
            // A locked chest (2026-10-04): no Lockpicking - pried open with a pickaxe good enough for the lock.
            var hand = player.getMainHandItem();
            String refuse = !hand.is(net.minecraft.tags.ItemTags.PICKAXES) ? "Locked - pry it open with a pickaxe"
                    : !hand.isCorrectToolForDrops(pry) ? "Locked - requires a better pickaxe"
                    : !net.mcwow.bridge.McwowGear.usable(hand) ? "Requires level " + net.mcwow.bridge.McwowGear.of(hand).req() : null;
            if (refuse != null) {
                mc.gui.chatListener().handleOverlay(Component.literal(refuse).withStyle(ChatFormatting.RED));
                active = true;
                progress = 0;
                target = 0;
                return;
            }
        }
        if (held && tree != null && gatherable(mc, f) && !tooFar(f)) {
            long left = choppedFor(player, f.guid());
            if (left > 0) {
                mc.gui.chatListener().handleOverlay(Component.literal(tree.name() + " is chopped - regrows in "
                        + Math.max(1, left / 60_000) + " min").withStyle(ChatFormatting.GRAY));
                active = true;
                progress = 0;
                target = 0;
                return;
            }
        }
        if (held && f != null && gatherable(mc, f) && f.kind() == MINE && !tooFar(f)) {
            // A vein (2026-10-04): a pickaxe that could mine its ore block, usable at our level.
            var hand = player.getMainHandItem();
            String refuse = vein == null ? "Can't mine " + f.name()
                    : !hand.isCorrectToolForDrops(vein.state()) ? "Requires a better pickaxe"
                    : !net.mcwow.bridge.McwowGear.usable(hand) ? "Requires level " + net.mcwow.bridge.McwowGear.of(hand).req() : null;
            if (refuse != null) {
                mc.gui.chatListener().handleOverlay(Component.literal(refuse).withStyle(ChatFormatting.RED));
                active = true; // still ours: no swing at the air behind it
                progress = 0;
                target = 0;
                return;
            }
        }
        if (!held || !gatherable(mc, f) || tooFar(f)) {
            if (held && f != null && gatherable(mc, f) && tooFar(f)) {
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
        BlockState look = vein != null ? vein.state() : tree != null ? tree.block().defaultBlockState()
                : herb != null ? Blocks.OAK_LEAVES.defaultBlockState() : lookalike(f.name());
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
        int ticks = vein != null ? blockTicks(player, look, vein.blocks()) : tree != null ? blockTicks(player, look, tree.logs())
                : pry != null ? blockTicks(player, Blocks.IRON_ORE.defaultBlockState(), net.mcwow.bridge.McwowChests.sizeFactor(chest.size()))
                : ticksFor(player, look);
        int bars = Math.clamp(progress * 10 / ticks, 0, 10);
        mc.gui.chatListener().handleOverlay(Component.literal(vein != null ? "Mining " + f.name() + " "
                        : pry != null ? "Prying open " + f.name() + " " : chest != null ? "Opening " + f.name() + " "
                        : tree != null ? "Chopping " + tree.name() + " " : "Gathering " + f.name() + " ")
                .append(Component.literal("|".repeat(bars)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("|".repeat(10 - bars)).withStyle(ChatFormatting.DARK_GRAY)));
        if (progress >= ticks) {
            if (tree != null) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        new net.mcwow.bridge.McwowTrees.Chop(f.guid(), f.name()));
                net.mcwow.bridge.McwowTrees.CLIENT_CHOPPED.put(net.mcwow.bridge.McwowTrees.key(
                        player.level().dimension().identifier(), f.guid()), System.currentTimeMillis() + net.mcwow.bridge.McwowTrees.REGROW_MILLIS);
            } else if (vein != null || herb != null || chest != null) {
                // The WoW server takes a use off the vein; this server drops the ore when it confirms.
                HARVESTS.add(f.guid());
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        new net.mcwow.bridge.McwowNodes.Harvest(f.guid(), f.name()));
            } else {
                McwowInteract.OUT.incrementAndGet(); // WoW's own use of it
            }
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
