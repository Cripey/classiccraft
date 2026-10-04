package net.mcwow.bridge;

import java.util.List;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.phys.Vec3;

/**
 * Guns (2026-10-04, user: WoW-style rifles and blunderbusses, ammo from gunpowder and metal nuggets,
 * tiered; crafted and sold by engineering vendors). A gun is stamped with the metal it was crafted
 * from (item level, durability, repair bar - McwowGear.stampCrafted), fires the best ammo in the
 * inventory the character's level allows, and its reload is the cooldown after a shot:
 *  - rifle: one fast bullet for RIFLE_SHARE of the metal's sword hit, RIFLE_RELOAD ticks;
 *  - blunderbuss: PELLETS pellets in a spread, each PELLET_SHARE of it, short range, a kick back,
 *    BLUNDERBUSS_RELOAD ticks.
 * Ammo (WoW's): Light Shot (copper), Heavy Shot (iron), Solid Shot (steel), Mithril Gyro-Shot,
 * Thorium Shells - each a damage multiplier and a required level. Hits are physical projectile hits
 * at the gun's item level (its stamp; McwowActorEntity reads the bullet's weapon).
 */
public final class McwowGuns {
    // Tuned with the progression sim (2026-10-04): 1.5x / 0.4x / 48 ticks were well behind melee.
    public static final float RIFLE_SHARE = 2.2F, PELLET_SHARE = 0.6F;
    public static final int PELLETS = 6, RIFLE_RELOAD = 32, BLUNDERBUSS_RELOAD = 40;
    /** Shots per ammo craft (gunpowder + 2 nuggets) and per vendor lot. */
    public static final int AMMO_PER_CRAFT = 32, AMMO_PER_LOT = 64;
    private static final float RIFLE_SPEED = 4.0F, PELLET_SPEED = 2.2F;
    private static final int RIFLE_LIFE = 15, PELLET_LIFE = 6;

    /** Ammo tiers: item id, name, damage multiplier, required level, nugget. */
    public record Ammo(String id, String name, float multiplier, int req, String nugget) {
    }

    public static final List<Ammo> AMMO = List.of(
            new Ammo("light_shot", "Light Shot", 1.0F, 1, "minecraft:copper_nugget"),
            new Ammo("heavy_shot", "Heavy Shot", 1.1F, 15, "minecraft:iron_nugget"),
            new Ammo("solid_shot", "Solid Shot", 1.2F, 30, "mcwow:steel_nugget"),
            new Ammo("mithril_gyro_shot", "Mithril Gyro-Shot", 1.3F, 40, "mcwow:mithril_nugget"),
            new Ammo("thorium_shell", "Thorium Shells", 1.4F, 50, "mcwow:thorium_nugget"));
    /** Our nuggets (a bar is 9 of them). */
    public static final List<String> NUGGETS = List.of("steel", "mithril", "thorium");

    public static Item RIFLE, BLUNDERBUSS;
    /** A bullet's damage type (data/mcwow/damage_type/bullet.json): a projectile that bypasses the hit cooldown. */
    public static final ResourceKey<net.minecraft.world.damagesource.DamageType> BULLET_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("mcwow", "bullet"));

    public static final ResourceKey<EntityType<?>> BULLET_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("mcwow", "bullet"));
    public static final EntityType<Bullet> BULLET = Registry.register(BuiltInRegistries.ENTITY_TYPE, BULLET_KEY,
            EntityType.Builder.<Bullet>of(Bullet::new, MobCategory.MISC)
                    .sized(0.15F, 0.15F)
                    .noSave()
                    .noSummon()
                    .noLootTable()
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build(BULLET_KEY));

    private McwowGuns() {
    }

    private static Item item(String name, java.util.function.Function<Item.Properties, Item> make, Item.Properties props) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("mcwow", name));
        return Registry.register(BuiltInRegistries.ITEM, key, make.apply(props.setId(key)));
    }

    private static void tab(String tab, java.util.function.Supplier<ItemStack> stack) {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace(tab))).register(out -> out.accept(stack.get()));
    }

    public static void register() {
        RIFLE = item("rifle", p -> new GunItem(false, p), new Item.Properties().stacksTo(1).durability(250));
        BLUNDERBUSS = item("blunderbuss", p -> new GunItem(true, p), new Item.Properties().stacksTo(1).durability(250));
        for (Item gun : new Item[] {RIFLE, BLUNDERBUSS}) {
            tab("combat", () -> {
                ItemStack s = new ItemStack(gun);
                ((GunItem) gun).stamp(s, McwowGear.material("copper"));
                return s;
            });
        }
        for (Ammo a : AMMO) {
            Item ammo = item(a.id(), Item::new, new Item.Properties());
            tab("combat", () -> new ItemStack(ammo));
        }
        for (String n : NUGGETS) {
            Item nugget = item(n + "_nugget", Item::new, new Item.Properties());
            tab("ingredients", () -> new ItemStack(nugget));
        }
    }

    /** The ammo tier of an item, or null. */
    public static Ammo ammoOf(ItemStack s) {
        if (s.isEmpty()) return null;
        Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
        if (!id.getNamespace().equals("mcwow")) return null;
        for (Ammo a : AMMO) if (a.id().equals(id.getPath())) return a;
        return null;
    }

    /** The best ammo tier a character level allows. */
    static Ammo bestTierFor(int level) {
        Ammo best = AMMO.getFirst();
        for (Ammo a : AMMO) if (a.req() <= Math.max(1, level)) best = a;
        return best;
    }

    /** The best ammo stack the player may use (highest tier at or under their WoW level), or EMPTY. */
    static ItemStack bestAmmo(Player player) {
        int level = Math.max(1, McwowGear.wowLevel());
        ItemStack best = ItemStack.EMPTY;
        Ammo bestTier = null;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Ammo a = ammoOf(s);
            if (a != null && a.req() <= level && (bestTier == null || a.multiplier() > bestTier.multiplier())) {
                best = s;
                bestTier = a;
            }
        }
        return best;
    }

    /** The material's sword hit (Minecraft damage) for a stamped weapon (unstamped: copper). */
    public static float swordHit(ItemStack weapon) {
        McwowGear.Gear g = McwowGear.of(weapon);
        McwowGear.Material m = McwowGear.material(g != null ? g.material() : "copper");
        if (m == null || m.toolTwin() == null) m = McwowGear.material("copper");
        Item twin = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(m.toolTwin() + "_sword"));
        return (float) twin.components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
    }

    /** A rifle or blunderbuss. */
    public static final class GunItem extends Item implements McwowGear.Stampable {
        public final boolean blunderbuss;

        public GunItem(boolean blunderbuss, Item.Properties props) {
            super(props);
            this.blunderbuss = blunderbuss;
        }

        @Override
        public void stamp(ItemStack stack, McwowGear.Material m) {
            McwowGear.stampWeapon(stack, m, m.title() + (blunderbuss ? " Blunderbuss" : " Rifle"));
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack gun = player.getItemInHand(hand);
            if (!McwowGear.usable(gun)) {
                if (!level.isClientSide()) player.sendOverlayMessage(Component.literal("Requires level " + McwowGear.of(gun).req())
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            // Creative: the best ammo the character's level allows (not the top tier - 2026-10-04 test).
            ItemStack ammo = player.hasInfiniteMaterials() ? new ItemStack(BuiltInRegistries.ITEM.getValue(
                    Identifier.fromNamespaceAndPath("mcwow", bestTierFor(McwowGear.wowLevel()).id()))) : bestAmmo(player);
            Ammo tier = ammoOf(ammo);
            if (tier == null) {
                if (!level.isClientSide()) player.sendOverlayMessage(Component.literal("No ammo you can use (shot: gunpowder + nuggets)")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            if (level instanceof ServerLevel sl) {
                float base = swordHit(gun) * tier.multiplier();
                int shots = blunderbuss ? PELLETS : 1;
                for (int i = 0; i < shots; i++) {
                    Bullet b = new Bullet(level, player, gun, new ItemStack(ammo.getItem()),
                            base * (blunderbuss ? PELLET_SHARE : RIFLE_SHARE), blunderbuss ? PELLET_LIFE : RIFLE_LIFE);
                    b.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
                            blunderbuss ? PELLET_SPEED : RIFLE_SPEED, blunderbuss ? 9.0F : 0.4F);
                    sl.addFreshEntity(b);
                }
                Vec3 look = player.getLookAngle();
                Vec3 muzzle = player.getEyePosition().add(look.scale(0.8));
                sl.sendParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y - 0.1, muzzle.z, blunderbuss ? 14 : 6, 0.05, 0.05, 0.05, 0.03);
                sl.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y - 0.1, muzzle.z, blunderbuss ? 4 : 1, 0.02, 0.02, 0.02, 0.01);
                sl.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GENERIC_EXPLODE,
                        SoundSource.PLAYERS, blunderbuss ? 0.6F : 0.45F, blunderbuss ? 1.3F : 1.8F);
                if (!player.hasInfiniteMaterials()) ammo.shrink(1);
                gun.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
            }
            if (blunderbuss) {
                // The kick: pushed back along the look, a little up. The player's movement is the
                // client's, so the client's own push is the one that moves them.
                Vec3 look = player.getLookAngle();
                player.push(-look.x * 0.45, 0.12, -look.z * 0.45);
            }
            player.getCooldowns().addCooldown(gun, blunderbuss ? BLUNDERBUSS_RELOAD : RIFLE_RELOAD);
            return InteractionResult.SUCCESS;
        }
    }

    /** A bullet or pellet: fast, nearly flat, gone after its life; physical damage on the first thing hit. */
    public static final class Bullet extends ThrowableItemProjectile {
        private float damage;
        private int life = RIFLE_LIFE;
        private ItemStack weapon = ItemStack.EMPTY;

        public Bullet(EntityType<? extends Bullet> type, Level level) {
            super(type, level);
        }

        Bullet(Level level, LivingEntity owner, ItemStack gun, ItemStack look, float damage, int life) {
            super(BULLET, owner, level, look);
            this.damage = damage;
            this.life = life;
            this.weapon = gun.copy();
        }

        @Override
        protected Item getDefaultItem() {
            return Items.IRON_NUGGET;
        }

        @Override
        protected double getDefaultGravity() {
            return 0.005;
        }

        /** The gun that fired it: its item level is the hit's (McwowActorEntity). */
        @Override
        public ItemStack getWeaponItem() {
            return this.weapon;
        }

        @Override
        public void tick() {
            super.tick();
            if (this.level().isClientSide()) {
                this.level().addParticle(ParticleTypes.CRIT, this.getX(), this.getY(), this.getZ(), 0.0, 0.0, 0.0);
            } else if (this.tickCount > this.life) {
                this.discard();
            }
        }

        @Override
        protected void onHitEntity(EntityHitResult hit) {
            super.onHitEntity(hit);
            if (this.level() instanceof ServerLevel sl) {
                // Its own damage type, mcwow:bullet - a projectile that bypasses the hit cooldown, so
                // every pellet of a volley counts (2026-10-04 test: one blunderbuss pellet of six landed;
                // the cooldown is LivingEntity.damageCooldownTime in 26.3).
                var type = sl.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(BULLET_DAMAGE);
                hit.getEntity().hurtServer(sl, new net.minecraft.world.damagesource.DamageSource(type, this, this.getOwner()),
                        this.damage);
            }
        }

        @Override
        protected void onHit(HitResult hit) {
            super.onHit(hit);
            if (this.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(), 3, 0.05, 0.05, 0.05, 0.01);
                this.discard();
            }
        }
    }
}
