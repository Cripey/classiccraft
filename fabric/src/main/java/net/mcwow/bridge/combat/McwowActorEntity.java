package net.mcwow.bridge.combat;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * An invisible stand-in for one WoW creature (Phase 5, 2026-10-01) - port of chasmlol/SkyCraft's
 * SkyrimActorEntity. Minecraft's own combat (swords, crits, sweeps, enchantments, attack cooldown,
 * bows, tridents, eggs, snowballs) targets and hits it like any mob; what it receives is collected
 * into one hit per tick and forwarded to the real WoW creature by McwowCombat. Its own health never
 * drops; WoW owns its position (McwowCombat places it every tick, no physics).
 */
public class McwowActorEntity extends LivingEntity {
    public static final int HIT_PROJECTILE = 1, HIT_CRITICAL = 2, HIT_FIRE = 4, HIT_THROWN = 8;
    /** 2026-10-04: a damage-over-time tick, frost's slow; bits 8-10 the spell school (McwowSpells, WoW's SpellSchools). */
    public static final int HIT_PERIODIC = 16, HIT_SLOW = 32, SCHOOL_SHIFT = 8;

    private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(McwowActorEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(McwowActorEntity.class, EntityDataSerializers.FLOAT);
    /** The WoW creature's guid, for the client too (McwowAim: melee aims at what benilla shows). */
    private static final EntityDataAccessor<Long> GUID = SynchedEntityData.defineId(McwowActorEntity.class, EntityDataSerializers.LONG);

    private long guid;
    private int entry, wowLevel;
    /** WoW's CanAttack(player, creature): the player's own hits count only then (no hurting guards). */
    private boolean attackable;
    // This tick's hit, flushed by McwowCombat after all attacks of the tick have landed.
    private float pendingDamage;
    private int pendingFlags;
    /** The weapon's item level of this tick's hit (its gear stamp; 0: none - McwowCombat falls back). */
    private int pendingIlvl;
    private boolean hitThisTick;
    /** Damage over time from the player (fire, shadow, nature, Fire Aspect): ticks once a second. */
    private final java.util.List<Dot> dots = new java.util.ArrayList<>();
    /** Damage-over-time ticks due this tick: {damage, flags, item level}. */
    private final java.util.List<float[]> dotHits = new java.util.ArrayList<>();

    private static final class Dot {
        final net.mcwow.bridge.McwowSpells.School school;
        final float perSecond;
        final int ilvl;
        int seconds, tick;

        Dot(net.mcwow.bridge.McwowSpells.School school, float perSecond, int seconds, int ilvl) {
            this.school = school;
            this.perSecond = perSecond;
            this.seconds = seconds;
            this.ilvl = ilvl;
        }
    }
    /**
     * This tick's hits by Minecraft mobs (zombies, the player's tamed wolves...), by entity id
     * (classiccraft): each becomes a hit from that mob's proxy on the server, so the WoW creature
     * fights the mob, not the player.
     */
    private final java.util.Map<Integer, Float> mobHits = new java.util.HashMap<>();

    public McwowActorEntity(EntityType<? extends McwowActorEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = true;
        this.setInvisible(true);
        this.setSilent(true);
    }

    public long guid() { return level().isClientSide() ? this.entityData.get(GUID) : guid; }
    public int entry() { return entry; }
    public int wowLevel() { return wowLevel; }

    public void setWow(long guid, int entry, int level, boolean attackable) {
        this.guid = guid;
        this.entityData.set(GUID, guid);
        this.entry = entry;
        this.wowLevel = level;
        this.attackable = attackable;
    }

    public boolean attackable() { return attackable; }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(WIDTH, 0.6F);
        builder.define(HEIGHT, 1.8F);
        builder.define(GUID, 0L);
    }

    public void setSize(float width, float height) {
        if (Math.abs(this.entityData.get(WIDTH) - width) > 0.01F || Math.abs(this.entityData.get(HEIGHT) - height) > 0.01F) {
            this.entityData.set(WIDTH, width);
            this.entityData.set(HEIGHT, height);
            this.refreshDimensions();
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (WIDTH.equals(accessor) || HEIGHT.equals(accessor)) this.refreshDimensions();
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return EntityDimensions.scalable(this.entityData.get(WIDTH), this.entityData.get(HEIGHT));
    }

    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float dmg) {
        // Minecraft has applied everything (crit, sharpness, strength, cooldown, invulnerability
        // frames). Hand the result to WoW instead of lowering our own health.
        if (this.isInvulnerableTo(level, source)) return;
        Entity responsible = source.getEntity();
        if (!(responsible instanceof net.minecraft.world.entity.player.Player)) {
            // A mob (or a tamed animal) hit it: its own proxy hits the WoW creature. The
            // environment (lava, cacti) is not WoW's business.
            if (responsible instanceof LivingEntity mob && dmg > 0.0F) {
                this.mobHits.merge(mob.getId(), dmg, Float::sum);
                this.getCombatTracker().recordDamage(source, dmg);
            }
            return;
        }
        // The player's hit on a creature WoW won't let them attack (a friendly guard): nothing.
        if (!this.attackable) return;
        boolean thrown = source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
        if (dmg <= 0.0F && !thrown) return;
        if (!(source.getDirectEntity() instanceof Projectile)) dmg += McwowCreatureKinds.bonus(this.entry, source, level);
        this.pendingDamage += Math.max(0.0F, dmg);
        if (source.getDirectEntity() instanceof Projectile) this.pendingFlags |= HIT_PROJECTILE;
        if (thrown) this.pendingFlags |= HIT_THROWN; // egg / snowball: pulls aggro (user's choice)
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) this.pendingFlags |= HIT_FIRE;
        // The weapon behind the hit (2026-10-04): its item level sets the hit's, ranged too (a bow's
        // arrow names its bow). Unstamped: 0, McwowCombat falls back (melee 1, ranged the character).
        ItemStack weapon = source.getWeaponItem();
        net.mcwow.bridge.McwowGear.Gear gear = weapon != null ? net.mcwow.bridge.McwowGear.of(weapon) : null;
        if (gear != null && weapon.has(net.mcwow.bridge.McwowGear.GEAR) && net.mcwow.bridge.McwowGear.usable(weapon)) {
            this.pendingIlvl = Math.max(this.pendingIlvl, gear.ilvl());
        }
        // A spell school (McwowSpells): WoW's resistances apply; fire, shadow and nature leave a
        // damage over time, frost Chills.
        net.mcwow.bridge.McwowSpells.School school = net.mcwow.bridge.McwowSpells.schoolOf(source);
        if (school != null) {
            this.pendingFlags = (this.pendingFlags & ~(0x7 << SCHOOL_SHIFT)) | (school.wow << SCHOOL_SHIFT);
            if (school == net.mcwow.bridge.McwowSpells.School.FROST) this.pendingFlags |= HIT_SLOW;
            if (school.dot) addDot(school, dmg * net.mcwow.bridge.McwowSpells.DOT_SHARE / net.mcwow.bridge.McwowSpells.DOT_SECONDS,
                    net.mcwow.bridge.McwowSpells.DOT_SECONDS, this.pendingIlvl);
        }
        // Fire Aspect: vanilla's burn (1 a second, 4 s a level) as WoW fire damage over time.
        if (weapon != null && !(source.getDirectEntity() instanceof Projectile)) {
            int fa = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FIRE_ASPECT), weapon);
            if (fa > 0) addDot(net.mcwow.bridge.McwowSpells.School.FIRE, 1.0F, 4 * fa, this.pendingIlvl);
        }
        this.hitThisTick = true;
        this.getCombatTracker().recordDamage(source, dmg);
    }

    /** This tick's mob hits {entity id -> Minecraft damage} and clears them; empty if none. */
    public java.util.Map<Integer, Float> takeMobHits() {
        if (this.mobHits.isEmpty()) return java.util.Map.of();
        java.util.Map<Integer, Float> hits = new java.util.HashMap<>(this.mobHits);
        this.mobHits.clear();
        return hits;
    }

    /**
     * Fully visible to mob targeting despite being invisible (vanilla scales an invisible target's
     * detection range down to 7-10%, so monsters would never notice WoW creatures).
     */
    @Override
    public double getVisibilityPercent(ServerLevel level, @Nullable Entity lookingEntity) {
        return 1.0;
    }

    /** Player.crit() was called on us this tick. */
    public void markCritical() {
        this.pendingFlags |= HIT_CRITICAL;
    }

    /** This tick's hit {damage, flags, item level} and clears it; null if nothing hit us. */
    public float[] takeHit() {
        if (!this.hitThisTick) return null;
        float[] hit = {this.pendingDamage, Float.intBitsToFloat(this.pendingFlags), this.pendingIlvl};
        this.pendingDamage = 0.0F;
        this.pendingFlags = 0;
        this.pendingIlvl = 0;
        this.hitThisTick = false;
        return hit;
    }

    /**
     * Damage over time from the player: perSecond Minecraft damage each second for that many
     * seconds, as WoW damage of the school (one of a school at a time: a new one replaces it).
     */
    public void addDot(net.mcwow.bridge.McwowSpells.School school, float perSecond, int seconds, int ilvl) {
        if (perSecond <= 0 || seconds <= 0) return;
        this.dots.removeIf(d -> d.school == school);
        this.dots.add(new Dot(school, perSecond, seconds, ilvl));
        if (school == net.mcwow.bridge.McwowSpells.School.FIRE) this.setRemainingFireTicks(Math.max(this.getRemainingFireTicks(), seconds * 20));
    }

    /** This tick's damage-over-time ticks {damage, flags, item level}, cleared. */
    public java.util.List<float[]> takeDotHits() {
        if (this.dotHits.isEmpty()) return java.util.List.of();
        java.util.List<float[]> out = new java.util.ArrayList<>(this.dotHits);
        this.dotHits.clear();
        return out;
    }

    @Override
    public void knockback(double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect) {
        // WoW owns this creature's position.
    }

    @Override
    public void tick() {
        // Position and rotation come from WoW (McwowCombat); keep hurt timers and fire ticking.
        this.baseTick();
        this.setHealth(this.getMaxHealth());
        if (!this.level().isClientSide() && !this.dots.isEmpty()) {
            for (java.util.Iterator<Dot> it = this.dots.iterator(); it.hasNext(); ) {
                Dot d = it.next();
                if (++d.tick % 20 != 0) continue;
                this.dotHits.add(new float[] {d.perSecond,
                        Float.intBitsToFloat(HIT_PERIODIC | (d.school.wow << SCHOOL_SHIFT)), d.ilvl});
                if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                    sl.sendParticles(d.school.particle, this.getX(), this.getY() + this.getBbHeight() * 0.6, this.getZ(),
                            6, this.getBbWidth() * 0.3, this.getBbHeight() * 0.3, this.getBbWidth() * 0.3, 0.01);
                }
                if (--d.seconds <= 0) it.remove();
            }
            if (!this.attackable || this.isDeadOrDying()) this.dots.clear();
        }
    }

    @Override
    public boolean isPushable() { return false; }

    @Override
    protected void doPush(Entity entity) { }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity other) { return false; }

    @Override
    public boolean shouldShowName() { return false; }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    protected @Nullable SoundEvent getHurtSound(DamageSource source) { return null; } // WoW plays its own

    @Override
    protected @Nullable SoundEvent getDeathSound() { return null; }

    @Override
    public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
}
