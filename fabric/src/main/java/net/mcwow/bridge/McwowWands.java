package net.mcwow.bridge;

import java.util.EnumMap;
import java.util.Map;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Wands (2026-10-04, user: "wands are a more simple projectile weapon"; magic costs nothing for now).
 * One wand per spell school (mcwow:fire_wand ...), crafted from a school catalyst over a metal bar
 * over a stick (the bar sets its material, McwowGear.stampCrafted): right-click fires a bolt of the
 * school that flies straight (no gravity) about 30 blocks, then the wand rests WAND_COOLDOWN ticks
 * and loses a point of durability. A bolt hits for BOLT_SHARE of the material's sword damage, plus
 * 10% per cloth armor piece worn (spellPower; cloth is the caster's armor), at the wand's item level
 * on WoW creatures, as WoW damage of its school (McwowSpells: resistances, damage over time, frost's
 * Chill). Its look: the school's vanilla item as a sprite, with the school's particles.
 */
public final class McwowWands {
    /** A bolt's damage as a share of the material's sword hit. */
    public static final float BOLT_SHARE = 0.4F;
    /** Ticks between bolts (0.6 s). */
    public static final int WAND_COOLDOWN = 12;
    /** A bolt's speed (blocks per tick) and life (ticks): about 32 blocks. */
    private static final float BOLT_SPEED = 1.6F;
    private static final int BOLT_LIFE = 20;
    /** Spell power per cloth piece worn. */
    public static final float CLOTH_POWER = 0.10F;

    /** What a school's bolt looks like (a vanilla item sprite) and what its wand is crafted with. */
    public static final Map<McwowSpells.School, Item> LOOK = new EnumMap<>(McwowSpells.School.class);
    public static final Map<McwowSpells.School, Item> CATALYST = new EnumMap<>(McwowSpells.School.class);
    public static final Map<McwowSpells.School, Item> WANDS = new EnumMap<>(McwowSpells.School.class);

    static {
        LOOK.put(McwowSpells.School.HOLY, Items.GLOWSTONE_DUST);
        LOOK.put(McwowSpells.School.FIRE, Items.FIRE_CHARGE);
        LOOK.put(McwowSpells.School.NATURE, Items.SLIME_BALL);
        LOOK.put(McwowSpells.School.FROST, Items.SNOWBALL);
        LOOK.put(McwowSpells.School.SHADOW, Items.ENDER_PEARL);
        LOOK.put(McwowSpells.School.ARCANE, Items.AMETHYST_SHARD);
        CATALYST.put(McwowSpells.School.HOLY, Items.GLOWSTONE_DUST);
        CATALYST.put(McwowSpells.School.FIRE, Items.BLAZE_POWDER);
        CATALYST.put(McwowSpells.School.NATURE, Items.SLIME_BALL);
        CATALYST.put(McwowSpells.School.FROST, Items.SNOWBALL);
        CATALYST.put(McwowSpells.School.SHADOW, Items.INK_SAC);
        CATALYST.put(McwowSpells.School.ARCANE, Items.LAPIS_LAZULI);
    }

    public static final ResourceKey<EntityType<?>> BOLT_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("mcwow", "spell_bolt"));
    public static final EntityType<SpellBolt> BOLT = Registry.register(BuiltInRegistries.ENTITY_TYPE, BOLT_KEY,
            EntityType.Builder.<SpellBolt>of(SpellBolt::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .noSave()
                    .noSummon()
                    .noLootTable()
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build(BOLT_KEY));

    private McwowWands() {
    }

    public static void register() {
        for (McwowSpells.School school : McwowSpells.School.values()) {
            String name = school.name().toLowerCase() + "_wand";
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("mcwow", name));
            Item item = Registry.register(BuiltInRegistries.ITEM, key,
                    new WandItem(school, new Item.Properties().setId(key).stacksTo(1).durability(250)));
            WANDS.put(school, item);
            CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    Identifier.withDefaultNamespace("combat"))).register(out -> {
                        ItemStack s = new ItemStack(item);
                        stamp(s, McwowGear.material("copper"));
                        out.accept(s);
                    });
        }
    }

    /** A wand of a material: its name, item level, durability (its sword twin's) and repair bar. */
    public static void stamp(ItemStack stack, McwowGear.Material m) {
        if (m == null || !(stack.getItem() instanceof WandItem w)) return;
        Item twin = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(m.toolTwin() + "_sword"));
        Integer max = twin.components().get(DataComponents.MAX_DAMAGE);
        if (max != null) stack.set(DataComponents.MAX_DAMAGE, Math.max(1, Math.round(max * m.durability())));
        if (m.repair() != null) {
            stack.set(DataComponents.REPAIRABLE, new net.minecraft.world.item.enchantment.Repairable(
                    net.minecraft.core.HolderSet.direct(BuiltInRegistries.ITEM.getValue(Identifier.parse(m.repair())).builtInRegistryHolder())));
        }
        stack.set(DataComponents.ITEM_NAME, Component.literal(m.title() + " Wand of " + w.school.title()));
        stack.set(McwowGear.GEAR, new McwowGear.Gear(m.id(), m.ilvl(), m.req()));
    }

    /** A bolt's base damage: BOLT_SHARE of the wand material's sword hit (an unstamped wand: copper's). */
    public static float boltDamage(ItemStack wand) {
        McwowGear.Gear g = McwowGear.of(wand);
        McwowGear.Material m = McwowGear.material(g != null ? g.material() : "copper");
        if (m == null || m.toolTwin() == null) m = McwowGear.material("copper");
        Item twin = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(m.toolTwin() + "_sword"));
        double attack = twin.components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
        return (float) (attack * BOLT_SHARE);
    }

    /** The player's spell power: 1 + CLOTH_POWER per cloth armor piece worn that they can use. */
    public static float spellPower(LivingEntity e) {
        int cloth = 0;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = e.getItemBySlot(slot);
            if ("cloth".equals(McwowGear.armorClass(s)) && McwowGear.usable(s)) cloth++;
        }
        return 1.0F + CLOTH_POWER * cloth;
    }

    /** A wand: right-click fires a bolt of its school. */
    public static final class WandItem extends Item {
        public final McwowSpells.School school;

        public WandItem(McwowSpells.School school, Item.Properties props) {
            super(props);
            this.school = school;
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack wand = player.getItemInHand(hand);
            if (!McwowGear.usable(wand)) {
                if (!level.isClientSide()) player.sendOverlayMessage(Component.literal("Requires level " + McwowGear.of(wand).req())
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            if (level instanceof ServerLevel sl) {
                SpellBolt bolt = new SpellBolt(level, player, wand, school, boltDamage(wand) * spellPower(player));
                bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, BOLT_SPEED, 0.5F);
                sl.addFreshEntity(bolt);
                sl.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EVOKER_CAST_SPELL,
                        SoundSource.PLAYERS, 0.5F, 1.4F + level.getRandom().nextFloat() * 0.2F);
                wand.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
            }
            player.getCooldowns().addCooldown(wand, WAND_COOLDOWN);
            return InteractionResult.SUCCESS;
        }
    }

    /** A wand's bolt: straight flight, the school's sprite and particles, its damage on the first thing hit. */
    public static final class SpellBolt extends ThrowableItemProjectile {
        private static final EntityDataAccessor<Integer> SCHOOL = SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.INT);
        private float damage;
        private ItemStack weapon = ItemStack.EMPTY;

        public SpellBolt(EntityType<? extends SpellBolt> type, Level level) {
            super(type, level);
        }

        SpellBolt(Level level, LivingEntity owner, ItemStack wand, McwowSpells.School school, float damage) {
            super(BOLT, owner, level, new ItemStack(LOOK.get(school)));
            this.entityData.set(SCHOOL, school.wow);
            this.damage = damage;
            this.weapon = wand.copy();
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {
            super.defineSynchedData(builder);
            builder.define(SCHOOL, McwowSpells.School.ARCANE.wow);
        }

        public McwowSpells.School school() {
            McwowSpells.School s = McwowSpells.School.byWow(this.entityData.get(SCHOOL));
            return s != null ? s : McwowSpells.School.ARCANE;
        }

        @Override
        protected Item getDefaultItem() {
            return Items.AMETHYST_SHARD;
        }

        @Override
        protected double getDefaultGravity() {
            return 0.0;
        }

        /** The wand that fired it: its item level is the hit's (McwowActorEntity). */
        @Override
        public ItemStack getWeaponItem() {
            return this.weapon;
        }

        @Override
        public void tick() {
            super.tick();
            if (this.level().isClientSide()) {
                for (int i = 0; i < 2; i++) {
                    this.level().addParticle(school().particle, this.getX() + (this.random.nextDouble() - 0.5) * 0.2,
                            this.getY() + 0.15 + (this.random.nextDouble() - 0.5) * 0.2,
                            this.getZ() + (this.random.nextDouble() - 0.5) * 0.2, 0.0, 0.0, 0.0);
                }
            } else if (this.tickCount > BOLT_LIFE) {
                this.discard();
            }
        }

        @Override
        protected void onHitEntity(EntityHitResult hit) {
            super.onHitEntity(hit);
            if (!(this.level() instanceof ServerLevel sl)) return;
            Entity target = hit.getEntity();
            var type = sl.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(school().damageType());
            target.hurtServer(sl, new DamageSource(type, this, this.getOwner()), this.damage);
        }

        @Override
        protected void onHit(HitResult hit) {
            super.onHit(hit);
            if (this.level() instanceof ServerLevel sl) {
                sl.sendParticles(school().particle, this.getX(), this.getY(), this.getZ(), 12, 0.2, 0.2, 0.2, 0.05);
                this.discard();
            }
        }
    }
}
