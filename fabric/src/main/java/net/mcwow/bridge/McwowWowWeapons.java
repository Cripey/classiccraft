package net.mcwow.bridge;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft weapons drawn with a WoW item's model (user, 2026-10-04: blocky 3D, specific weapons on
 * demand). Definitions in resources mcwow/wow_weapons.json (ours, no art); the models are built from
 * the player's own WoW install by tools/wow-weapons.sh (benilla classiccraft crate cc_weapon) into
 * the local resource pack client McwowLocalPack loads. A weapon is a vanilla item (its base) with
 * the WoW name, the model (item_model mcwow:wow/<key>), a rarity and a McwowGear stamp; where each
 * comes from in play is decided per weapon - for now the listed ones are in the creative Combat tab.
 */
public final class McwowWowWeapons {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    public record Weapon(String key, String name, int modelItem, Identifier base, boolean creative, Rarity rarity,
                         McwowGear.Gear gear) {
    }

    private static final List<Weapon> WEAPONS = new ArrayList<>();

    private McwowWowWeapons() {
    }

    /** A weapon as an item stack. */
    public static ItemStack stack(Weapon w) {
        ItemStack s = new ItemStack(BuiltInRegistries.ITEM.getValue(w.base()));
        s.set(DataComponents.ITEM_NAME, Component.literal(w.name()));
        s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("mcwow", "wow/" + w.key()));
        s.set(DataComponents.RARITY, w.rarity());
        if (w.gear() != null) s.set(McwowGear.GEAR, w.gear());
        return s;
    }

    public static void register() {
        try (var in = McwowWowWeapons.class.getResourceAsStream("/mcwow/wow_weapons.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("weapons")) {
                JsonObject o = e.getAsJsonObject();
                McwowGear.Gear gear = null;
                if (o.has("gear")) {
                    var g = o.getAsJsonArray("gear");
                    gear = new McwowGear.Gear(g.get(0).getAsString(), g.get(1).getAsInt(), g.get(2).getAsInt());
                }
                WEAPONS.add(new Weapon(o.get("key").getAsString(), o.get("name").getAsString(), o.get("model_item").getAsInt(),
                        Identifier.parse(o.get("base").getAsString()), o.has("creative") && o.get("creative").getAsBoolean(),
                        o.has("rarity") ? Rarity.valueOf(o.get("rarity").getAsString().toUpperCase()) : Rarity.COMMON, gear));
            }
        } catch (Exception ex) {
            LOGGER.warn("mcwow-bridge: cannot read wow_weapons.json: {}", ex.toString());
        }
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("combat"))).register(out -> {
                    for (Weapon w : WEAPONS) if (w.creative()) out.accept(stack(w));
                });
    }
}
