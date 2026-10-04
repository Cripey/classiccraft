package net.mcwow.bridge.combat;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.slf4j.LoggerFactory;

/**
 * Smite and Bane of Arthropods against WoW creatures (2026-10-04, user). The stand-ins are one
 * Minecraft entity type, so vanilla's undead / arthropod tags can't tell them apart: this adds
 * vanilla's bonus (2.5 per level) by WoW creature entry - undead and arthropods from
 * resources mcwow/creature_kinds.json (tools/creature_kinds.py).
 */
public final class McwowCreatureKinds {
    private static final Set<Integer> UNDEAD = new HashSet<>(), ARTHROPOD = new HashSet<>();

    static {
        try (var in = McwowCreatureKinds.class.getResourceAsStream("/mcwow/creature_kinds.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("undead")) UNDEAD.add(e.getAsInt());
            for (JsonElement e : o.getAsJsonArray("arthropod")) ARTHROPOD.add(e.getAsInt());
        } catch (Exception ex) {
            LoggerFactory.getLogger("mcwow-bridge").warn("mcwow-bridge: cannot read creature kinds: {}", ex.toString());
        }
    }

    private McwowCreatureKinds() {
    }

    public static boolean undead(int entry) {
        return UNDEAD.contains(entry);
    }

    public static boolean arthropod(int entry) {
        return ARTHROPOD.contains(entry);
    }

    /** Extra Minecraft damage of a melee hit on this creature from the attacker's Smite or Bane. */
    public static float bonus(int entry, DamageSource source, ServerLevel level) {
        ResourceKey<Enchantment> key = undead(entry) ? Enchantments.SMITE : arthropod(entry) ? Enchantments.BANE_OF_ARTHROPODS : null;
        if (key == null) return 0.0F;
        ItemStack weapon = source.getWeaponItem();
        if ((weapon == null || weapon.isEmpty()) && source.getEntity() instanceof LivingEntity le) weapon = le.getMainHandItem();
        if (weapon == null || weapon.isEmpty()) return 0.0F;
        int lv = EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(key), weapon);
        return 2.5F * lv;
    }
}
