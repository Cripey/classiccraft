package net.mcwow.bridge;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * WoW's spell schools on Minecraft damage (2026-10-04, user: magic weapons, schools on our hits).
 * Each school is its own damage type (data/mcwow/damage_type/spell_*.json; armor doesn't cut it, as
 * Minecraft's magic). A hit of one on a WoW creature's stand-in goes to the server with the school
 * (McwowActorEntity, HIT flags bits 8-10 = WoW's SpellSchools), where WoW's resistances, absorbs and
 * immunities apply. What each does besides its hit:
 *  - fire burns (damage over time, the target shown on fire);
 *  - frost Chills (WoW's slow, cast by the server);
 *  - shadow and nature: damage over time;
 *  - holy and arcane: just the hit.
 */
public final class McwowSpells {
    public enum School {
        HOLY(1, ParticleTypes.END_ROD, 0xFFF2A0, false),
        FIRE(2, ParticleTypes.FLAME, 0xFF7A20, true),
        NATURE(3, ParticleTypes.HAPPY_VILLAGER, 0x60D040, true),
        FROST(4, ParticleTypes.SNOWFLAKE, 0x9AD8FF, false),
        SHADOW(5, ParticleTypes.SMOKE, 0x6A3A8A, true),
        ARCANE(6, ParticleTypes.WITCH, 0xD070FF, false);

        /** WoW's SpellSchools index (the server's school). */
        public final int wow;
        public final ParticleOptions particle;
        public final int color;
        /** Leaves a damage-over-time effect (DOT_SHARE of the hit over DOT_SECONDS). */
        public final boolean dot;

        School(int wow, ParticleOptions particle, int color, boolean dot) {
            this.wow = wow;
            this.particle = particle;
            this.color = color;
            this.dot = dot;
        }

        public ResourceKey<DamageType> damageType() {
            return ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("mcwow", "spell_" + name().toLowerCase()));
        }

        public String title() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }

        public static School byWow(int wow) {
            for (School s : values()) if (s.wow == wow) return s;
            return null;
        }
    }

    /** A school's damage over time: this share of the hit, spread over this many seconds. */
    public static final float DOT_SHARE = 0.5F;
    public static final int DOT_SECONDS = 4;

    private McwowSpells() {
    }

    /** The school of a damage source, or null for physical. */
    public static School schoolOf(DamageSource source) {
        for (School s : School.values()) {
            if (source.is(s.damageType())) return s;
        }
        return null;
    }
}
