package net.mcwow.bridge.client;

import java.util.Comparator;
import java.util.List;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Straight into the game (launcher phase 1, 2026-10-05): the first time the title screen shows, the
 * world played last is opened, or - with no world yet - one named "classiccraft" is made on the
 * Void preset (what players used to set up by hand: Superflat > Customize > Presets > The Void).
 * Saves (2026-10-05, user: a save = a WoW character + its own Minecraft world): with "world" set in
 * config/mcwow.json (the launcher's chosen save, tools/minecraft.sh <Character>), exactly that world
 * opens, created on the Void preset under that name the first time.
 * Back at the title screen later (Save and Quit) nothing happens. Off with "autoWorld": false in
 * config/mcwow.json or CLASSICCRAFT_AUTO_WORLD=0.
 */
public final class McwowAutoWorld {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final String NEW_WORLD = "classiccraft";
    private static boolean done;

    private McwowAutoWorld() {
    }

    static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (done || !(screen instanceof TitleScreen)) return;
            done = true;
            if (!enabled()) {
                LOGGER.info("mcwow-bridge: auto world off");
                return;
            }
            // Not from inside the title screen's init: the next frame.
            client.execute(() -> open(client, screen));
        });
    }

    private static boolean enabled() {
        return McwowClientConfig.flag("autoWorld", "CLASSICCRAFT_AUTO_WORLD");
    }

    private static void open(Minecraft mc, Screen title) {
        try {
            mc.getLevelSource().loadLevelSummaries(mc.getLevelSource().findLevelCandidates())
                    .thenAcceptAsync(worlds -> pick(mc, title, worlds), mc);
        } catch (Exception e) {
            LOGGER.error("mcwow-bridge: auto world: could not list the worlds", e);
        }
    }

    private static void pick(Minecraft mc, Screen title, List<LevelSummary> worlds) {
        if (mc.gui.screen() != title) return; // the player went somewhere already
        String save = McwowClientConfig.text("world");
        if (save != null) {
            LevelSummary own = worlds.stream().filter(s -> s.getLevelId().equalsIgnoreCase(save)).findFirst().orElse(null);
            if (own == null) {
                create(mc, title, save);
            } else if (own.primaryActionActive() && !own.isLocked() && !own.requiresManualConversion()) {
                LOGGER.info("mcwow-bridge: auto world: opening the save's world {}", own.getLevelId());
                mc.createWorldOpenFlows().openWorld(own.getLevelId(), () -> mc.gui.setScreen(title));
            } else {
                LOGGER.warn("mcwow-bridge: auto world: {} can't be opened as it is - staying on the title screen", save);
            }
            return;
        }
        if (worlds.isEmpty()) {
            create(mc, title, NEW_WORLD);
            return;
        }
        LevelSummary last = worlds.stream()
                .filter(s -> s.primaryActionActive() && !s.isLocked() && !s.requiresManualConversion())
                .max(Comparator.comparingLong(LevelSummary::getLastPlayed))
                .orElse(null);
        if (last == null) {
            LOGGER.warn("mcwow-bridge: auto world: no world can be opened as it is - staying on the title screen");
            return;
        }
        LOGGER.info("mcwow-bridge: auto world: opening {}", last.getLevelId());
        mc.createWorldOpenFlows().openWorld(last.getLevelId(), () -> mc.gui.setScreen(title));
    }

    private static void create(Minecraft mc, Screen title, String name) {
        LOGGER.info("mcwow-bridge: auto world: creating \"{}\" (The Void)", name);
        LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL,
                LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, WorldOptions.defaultWithRandomSeed(),
                registries -> {
                    WorldDimensions flat = registries.lookupOrThrow(Registries.WORLD_PRESET)
                            .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
                    var voidPreset = registries.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                            .getOrThrow(FlatLevelGeneratorPresets.THE_VOID).value();
                    return flat.replaceOverworldGenerator(registries, new FlatLevelSource(voidPreset.settings()));
                }, title);
    }
}
