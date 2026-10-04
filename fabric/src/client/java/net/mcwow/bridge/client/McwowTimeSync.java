package net.mcwow.bridge.client;

import java.time.LocalTime;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives Minecraft's time of day from WoW's, so Minecraft's own day/night lighting (blocks,
 * entities, the held item) matches the WoW scene it's composited over - chasmlol/SkyCraft does
 * the same from Skyrim's GameHour (docs/DESIGN.md "Lighting").
 *
 * Source: WoW 3.3.5's day/night follows the server's clock 1:1, and this project's AzerothCore
 * server runs on this same machine - confirmed live 2026-10-01: WoW's minimap clock read 3:42 /
 * 3:55 at local 15:43 / 15:55. So the local clock IS WoW's clock; no WoW memory/Lua read needed.
 * (If the server ever runs elsewhere or in another time zone, this needs a real WoW-side read.)
 *
 * Minecraft 26.x replaced the old dayTime with world clocks: same calls as /time set
 * (TimeCommand, checked via javap) - the overworld's default clock via its dimension type, set
 * through server.clockManager(). The clock is paused while linked so it doesn't run 72x faster
 * than WoW's between updates; unpaused again when WoW goes away.
 */
public final class McwowTimeSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static long lastUpdateNanos;
    private static boolean pausedByUs;
    private static int lastLoggedHour = -1;

    private McwowTimeSync() {
    }

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        boolean linked = McwowOverlayLink.linked();
        long now = System.nanoTime();
        if (linked && now - lastUpdateNanos < 1_000_000_000L) return;
        if (!linked && !pausedByUs) return;
        lastUpdateNanos = now;

        LocalTime t = LocalTime.now();
        // Minecraft: 0 = 06:00, 6000 = noon, 12000 = 18:00, 18000 = midnight; 1000 ticks per hour.
        long timeOfDay = ((t.getHour() - 6 + 24) % 24) * 1000L + t.getMinute() * 1000L / 60 + t.getSecond() * 1000L / 3600;
        int hour = t.getHour();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            var clock = level.dimensionTypeRegistration().value().defaultClock();
            if (clock.isEmpty()) return;
            var manager = server.clockManager();
            if (!linked) {
                manager.setPaused(clock.get(), false);
                pausedByUs = false;
                LOGGER.info("mcwow-bridge: time sync off (WoW gone) - Minecraft clock running again");
                return;
            }
            long total = manager.getInstance(clock.get()).totalTicks();
            long day = Math.floorDiv(total, 24000L); // keep the day count (moon phase)
            manager.setTotalTicks(clock.get(), day * 24000L + timeOfDay);
            if (!pausedByUs) {
                manager.setPaused(clock.get(), true);
                pausedByUs = true;
            }
            if (hour != lastLoggedHour) {
                lastLoggedHour = hour;
                LOGGER.info("mcwow-bridge: time sync - WoW {}:{} -> Minecraft time {}", hour,
                        String.format("%02d", t.getMinute()), timeOfDay);
            }
        });
    }
}
