package net.mcwow.bridge.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mcwow.bridge.McwowDanceNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * WoW's race dances on Minecraft's own player (user, 2026-10-03): "/dance" as our WoW character's
 * race and gender, "/dance tauren", "/dance human f", "/dance stop"; moving or jumping ends it. The
 * moves come from the user's own WoW install: benilla extracts each race's dance into Steve's six
 * parts (benilla classiccraft crate dances.rs: ~/.local/share/classiccraft/dances/<race>_<sex>.ccd,
 * and "self" for our own race and gender). A dance goes through the server (McwowDanceNet), so
 * everyone who sees the dancer plays it from their own files.
 */
public final class McwowDance {
    /** ChrRaces ids by name, aliases included. */
    private static final Map<String, Integer> RACES = Map.ofEntries(
            Map.entry("human", 1), Map.entry("orc", 2), Map.entry("dwarf", 3), Map.entry("nightelf", 4),
            Map.entry("elf", 4), Map.entry("undead", 5), Map.entry("forsaken", 5), Map.entry("scourge", 5),
            Map.entry("tauren", 6), Map.entry("gnome", 7), Map.entry("troll", 8));
    private static final String[] SUGGESTED = {"human", "orc", "dwarf", "nightelf", "undead", "tauren", "gnome", "troll", "stop"};
    private static final int FLOATS = 17;
    /** Steve's hip, the point the root turns about (model pixels under the root's origin, the neck). */
    private static final Vector3f HIP = new Vector3f(0.0F, 12.0F, 0.0F);

    /** One variation of a dance: WoW's weight and replay range, frames of FLOATS values at fps. */
    private record Variation(int weight, int minReplay, int maxReplay, float[] frames, int count, float fps) {
        float seconds() {
            return this.count / this.fps;
        }
    }

    /** A race's dance: its variations (format in benilla's dances.rs, version 2). */
    private record Clip(Variation[] variations, int totalWeight) {
    }

    /**
     * Who dances: the clip, the entity's age (ticks) when it began, and the walk through its
     * variations - WoW picks one by weight, plays it a rolled number of times, picks again. Seeded
     * by the server, so every viewer walks the same order.
     */
    private static final class Active {
        final Clip clip;
        final float startAge;
        final java.util.Random rng;
        Variation current;
        float segmentStart, segmentLength;

        Active(Clip clip, float startAge, int seed) {
            this.clip = clip;
            this.startAge = startAge;
            this.rng = new java.util.Random(seed);
            this.next(0.0F);
        }

        /** The next variation from `at` seconds: weighted pick, replays min + rand * (max - min). */
        void next(float at) {
            int roll = this.rng.nextInt(this.clip.totalWeight());
            Variation pick = this.clip.variations()[0];
            for (Variation v : this.clip.variations()) {
                if (roll < v.weight()) {
                    pick = v;
                    break;
                }
                roll -= v.weight();
            }
            int span = pick.maxReplay() - pick.minReplay();
            int plays = Math.max(1, pick.minReplay() + (span > 0 ? this.rng.nextInt(span) : 0));
            this.current = pick;
            this.segmentStart = at;
            this.segmentLength = plays * pick.seconds();
        }

        /** The current variation's frame (fractional) at `t` seconds into the dance. */
        float frameAt(float t) {
            while (t >= this.segmentStart + this.segmentLength) {
                this.next(this.segmentStart + this.segmentLength);
            }
            float in = (t - this.segmentStart) % this.current.seconds();
            return in * this.current.fps();
        }
    }

    private static final Map<Integer, Clip> CLIPS = new HashMap<>(); // by race * 2 + sex
    private static final Map<Integer, Active> DANCING = new ConcurrentHashMap<>();
    private static Vec3 lastPos;
    /** Our WoW character's stand state this tick (McwowActors.readStandState). */
    private static int standState;
    /** How far a sitting Steve sinks (model pixels): his hip comes down to where his feet were. */
    private static final float SIT_DROP = 12.0F;

    private McwowDance() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("dance")
                        .executes(c -> command(c, null, null))
                        .then(ClientCommands.argument("race", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (String s : SUGGESTED) {
                                        if (s.startsWith(b.getRemainingLowerCase())) b.suggest(s);
                                    }
                                    return b.buildFuture();
                                })
                                .executes(c -> command(c, StringArgumentType.getString(c, "race"), null))
                                .then(ClientCommands.argument("gender", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            if ("m".startsWith(b.getRemainingLowerCase())) b.suggest("m");
                                            if ("f".startsWith(b.getRemainingLowerCase())) b.suggest("f");
                                            return b.buildFuture();
                                        })
                                        .executes(c -> command(c, StringArgumentType.getString(c, "race"),
                                                StringArgumentType.getString(c, "gender")))))));
        ClientPlayNetworking.registerGlobalReceiver(McwowDanceNet.Dance.TYPE, (dance, context) -> {
            if (dance.race() == 0) {
                DANCING.remove(dance.entity());
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Entity e = mc.level == null ? null : mc.level.getEntity(dance.entity());
            Clip clip = clip(dance.race(), dance.sex());
            if (e != null && clip != null) DANCING.put(dance.entity(), new Active(clip, e.tickCount, dance.seed()));
        });
    }

    private static Path dir() {
        return Path.of(System.getProperty("user.home"), ".local", "share", "classiccraft", "dances");
    }

    private static int command(CommandContext<FabricClientCommandSource> c, String race, String gender) {
        if (race != null && race.equalsIgnoreCase("stop")) {
            ClientPlayNetworking.send(new McwowDanceNet.Dance(0, 0, 0, 0));
            return 1;
        }
        int[] own = self();
        int raceId;
        if (race == null) {
            if (own == null) {
                c.getSource().sendError(Component.literal("Your WoW character's race isn't known yet (benilla in world?)."));
                return 0;
            }
            raceId = own[0];
        } else {
            Integer id = RACES.get(race.toLowerCase(Locale.ROOT));
            if (id == null) {
                c.getSource().sendError(Component.literal("Unknown race \"" + race
                        + "\": human, orc, dwarf, nightelf, undead, tauren, gnome or troll."));
                return 0;
            }
            raceId = id;
        }
        int sex;
        if (gender == null) {
            sex = own != null ? own[1] : 0;
        } else {
            switch (gender.toLowerCase(Locale.ROOT)) {
                case "m", "male" -> sex = 0;
                case "f", "female" -> sex = 1;
                default -> {
                    c.getSource().sendError(Component.literal("Gender is m or f."));
                    return 0;
                }
            }
        }
        if (clip(raceId, sex) == null) {
            c.getSource().sendError(Component.literal("No dance file for that race yet: benilla writes them to "
                    + dir() + " on start."));
            return 0;
        }
        ClientPlayNetworking.send(new McwowDanceNet.Dance(0, raceId, sex, 0));
        c.getSource().sendFeedback(Component.literal("Dancing - move or jump to stop. Third person (F5) to watch."));
        return 1;
    }

    /** Our WoW character's race and sex, from benilla's "self" file. */
    private static int[] self() {
        try {
            String[] p = Files.readString(dir().resolve("self")).trim().split("\\s+");
            return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Clip clip(int race, int sex) {
        return CLIPS.computeIfAbsent(race * 2 + sex, k -> {
            try {
                ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(dir().resolve(race + "_" + sex + ".ccd")))
                        .order(ByteOrder.LITTLE_ENDIAN);
                if (b.getInt() != 0x4E444343 || b.getInt() != 2) return null; // "CCDN", version 2
                Variation[] variations = new Variation[b.getInt()];
                int total = 0;
                for (int v = 0; v < variations.length; v++) {
                    int weight = Math.max(1, b.getInt()), minReplay = b.getInt(), maxReplay = b.getInt();
                    int count = b.getInt();
                    float fps = b.getFloat();
                    float[] f = new float[count * FLOATS];
                    for (int i = 0; i < f.length; i++) f[i] = b.getFloat();
                    if (count <= 0) return null;
                    variations[v] = new Variation(weight, minReplay, maxReplay, f, count, fps);
                    total += weight;
                }
                return variations.length > 0 ? new Clip(variations, total) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
    }

    /** Our own dance ends as we move or leave the ground, as in WoW (not the deck carrying us). */
    static void tick(Minecraft mc) {
        standState = mc.player == null ? 0 : net.mcwow.bridge.combat.McwowActors.readStandState();
        LocalPlayer p = mc.player;
        if (p == null) {
            DANCING.clear();
            lastPos = null;
            return;
        }
        Vec3 pos = p.position();
        if (DANCING.containsKey(p.getId()) && lastPos != null) {
            Vec3 carried = McwowDeckRide.carried();
            double walked = Math.hypot(pos.x - lastPos.x - carried.x, pos.z - lastPos.z - carried.z);
            if (walked > 0.02 || !p.onGround() || p.isDeadOrDying()) {
                DANCING.remove(p.getId());
                ClientPlayNetworking.send(new McwowDanceNet.Dance(0, 0, 0, 0));
            }
        }
        lastPos = pos;
    }

    /** This frame's values for a dancing player, or null (not an avatar, or not dancing). */
    private static float[] pose(HumanoidRenderState s) {
        if (!(s instanceof AvatarRenderState state)) return null;
        Active a = DANCING.get(state.id);
        if (a == null) return null;
        float t;
        Variation c;
        synchronized (a) {
            t = a.frameAt(Math.max(0.0F, (state.ageInTicks - a.startAge) / 20.0F));
            c = a.current;
        }
        int i0 = (int) t % c.count(), i1 = (i0 + 1) % c.count();
        float f = t - (int) t;
        float[] v = new float[FLOATS];
        for (int k = 0; k < FLOATS; k++) {
            float x = c.frames()[i0 * FLOATS + k], y = c.frames()[i1 * FLOATS + k];
            // Angles take the short way round between frames.
            if (k >= 3) {
                float d = y - x;
                d -= (float) (Math.round(d / (2 * Math.PI)) * 2 * Math.PI);
                y = x + d;
            }
            v[k] = x + (y - x) * f;
        }
        return v;
    }

    /**
     * The dance's whole-body turn and shift on a model's root: what is drawn on its own model over
     * the player (the elytra, ElytraModelDanceMixin) follows the body (2026-10-03: the elytra stayed
     * where a standing body would be).
     */
    public static void applyRoot(ModelPart root, HumanoidRenderState state) {
        float[] v = pose(state);
        if (v != null) {
            turnRoot(root, v);
        } else if (sitting(state)) {
            root.y += SIT_DROP;
        }
    }

    /**
     * Whether this is our own player while our WoW character sits (2026-10-03: a chair or bench used
     * from the crosshair; the server sets the stand state and moves the body onto the seat, where
     * the placement puts Steve's feet). Ground sit (1) and the chair states (2, 4, 5, 6).
     */
    private static boolean sitting(HumanoidRenderState s) {
        if (!(s instanceof AvatarRenderState state)) return false;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || state.id != p.getId()) return false;
        return standState == 1 || standState == 2 || (standState >= 4 && standState <= 6);
    }

    /** Whether our own player sits now; the camera sinks with him (CameraSitMixin). */
    public static boolean selfSitting() {
        return standState == 1 || standState == 2 || (standState >= 4 && standState <= 6);
    }

    /** The sitting sink in blocks, for the camera. */
    public static double sitDropBlocks() {
        return SIT_DROP / 16.0;
    }

    /** Steve seated: legs forward as vanilla rides, arms resting forward, sunk onto the seat. */
    private static void sit(HumanoidModel<?> model) {
        model.root().y += SIT_DROP;
        model.rightLeg.xRot = -1.4137167F;
        model.rightLeg.yRot = 0.31415927F;
        model.rightLeg.zRot = 0.07853982F;
        model.leftLeg.xRot = -1.4137167F;
        model.leftLeg.yRot = -0.31415927F;
        model.leftLeg.zRot = -0.07853982F;
        model.rightArm.xRot += -0.62831855F;
        model.leftArm.xRot += -0.62831855F;
    }

    private static void turnRoot(ModelPart root, float[] v) {
        // The root turns about the hip, not its origin at the neck: shift it so the hip lands on
        // the dance's offset.
        Vector3f hip = new Quaternionf().rotationZYX(v[5], v[4], v[3]).transform(new Vector3f(HIP));
        root.x += v[0] + HIP.x - hip.x;
        root.y += v[1] + HIP.y - hip.y;
        root.z += v[2] + HIP.z - hip.z;
        root.xRot += v[3];
        root.yRot += v[4];
        root.zRot += v[5];
    }

    /**
     * Poses a dancing player's humanoid model after vanilla's own animation: the player model
     * (PlayerModelDanceMixin, its cape model too) and its armor (HumanoidModelDanceMixin).
     */
    public static void apply(HumanoidModel<?> model, HumanoidRenderState state) {
        float[] v = pose(state);
        if (v == null) {
            if (sitting(state)) sit(model);
            return;
        }
        turnRoot(model.root(), v);
        model.head.xRot = v[6];
        model.head.yRot = v[7];
        model.head.zRot = v[8];
        model.rightArm.xRot = v[9];
        model.rightArm.yRot = 0.0F;
        model.rightArm.zRot = v[10];
        model.leftArm.xRot = v[11];
        model.leftArm.yRot = 0.0F;
        model.leftArm.zRot = v[12];
        model.rightLeg.xRot = v[13];
        model.rightLeg.yRot = 0.0F;
        model.rightLeg.zRot = v[14];
        model.leftLeg.xRot = v[15];
        model.leftLeg.yRot = 0.0F;
        model.leftLeg.zRot = v[16];
    }
}
