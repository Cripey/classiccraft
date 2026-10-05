package net.mcwow.bridge.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One game, not two windows (launcher phase 2, 2026-10-05):
 *  - Minecraft's window is minimized once WoW shows Minecraft (WoW reads the overlay, its input
 *    bridge is on, a world is loaded) - everything Minecraft draws is in WoW's window then. It comes
 *    back when WoW has been gone for a few seconds (closed or crashed), so Minecraft is never left
 *    running out of sight. Minimized, not hidden: WindowMixin keeps Minecraft rendering at full
 *    rate while "iconified" (SkyCraft's own way), a path hiding hasn't been tried on.
 *  - Closing together: WoW closing (its state word goes to 2 after it was seen at 1) saves and
 *    leaves the world, then closes Minecraft; Minecraft closing sets MC_QUIT, and WoW closes
 *    (benilla overlay.rs linked_quit).
 *  - Leaving the world while WoW shows Minecraft closes the game (user, 2026-10-05): the title
 *    screen can't be reached from WoW's window, and plain WoW behind it was a dead end. The pause
 *    menu's button says so (QuitButtonLabelMixin).
 * Off with "minimizeWindow": false / "linkedQuit": false in config/mcwow.json, or
 * CLASSICCRAFT_MINIMIZE=0 / CLASSICCRAFT_LINKED_QUIT=0.
 */
public final class McwowSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    /** How long WoW must be gone before Minecraft's window comes back (loading hitches are shorter). */
    private static final long RESTORE_AFTER_NANOS = 5_000_000_000L;

    private static boolean minimizeOn, linkedQuitOn;
    private static boolean minimized;
    private static long wowLastSeen;
    private static boolean wowSeenRunning;
    private static boolean closing;
    /** A world was loaded last tick while WoW showed Minecraft (input bridge on). */
    private static boolean inLinkedWorld;

    private McwowSession() {
    }

    /** Leaving the world now closes the whole game. */
    public static boolean leavingQuits() {
        return linkedQuitOn && McwowOverlayLink.linked();
    }

    static void register() {
        minimizeOn = McwowClientConfig.flag("minimizeWindow", "CLASSICCRAFT_MINIMIZE");
        linkedQuitOn = McwowClientConfig.flag("linkedQuit", "CLASSICCRAFT_LINKED_QUIT");
        ClientTickEvents.END_CLIENT_TICK.register(McwowSession::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (linkedQuitOn) McwowOverlayLink.markQuitting();
        });
    }

    private static void tick(Minecraft mc) {
        long now = System.nanoTime();
        boolean linked = McwowOverlayLink.linked();
        if (linked) wowLastSeen = now;

        if (minimizeOn) {
            boolean shown = linked && McwowOverlayLink.inputActive() && mc.level != null && mc.player != null;
            if (shown && !minimized) {
                minimized = true;
                SDLVideo.SDL_MinimizeWindow(mc.getWindow().handle());
                LOGGER.info("mcwow-bridge: WoW shows Minecraft - Minecraft's window minimized");
            } else if (minimized && !linked && now - wowLastSeen > RESTORE_AFTER_NANOS) {
                minimized = false;
                SDLVideo.SDL_RestoreWindow(mc.getWindow().handle());
                LOGGER.info("mcwow-bridge: WoW is gone - Minecraft's window restored");
            }
        }

        if (!linkedQuitOn) return;
        if (mc.level != null) {
            inLinkedWorld = linked;
        } else if (inLinkedWorld && !closing) {
            // Save and Quit (the world is saved by now): close the game; WoW follows (MC_QUIT).
            inLinkedWorld = false;
            closing = true;
            LOGGER.info("mcwow-bridge: left the world while WoW showed it - closing the game");
        }
        int wow = McwowOverlayLink.wowState();
        if (wow == McwowOverlayLink.WOW_RUNNING) wowSeenRunning = true;
        if (closing) {
            // The world is saved and left (or was never open): now close.
            if (mc.level == null) mc.stop();
            return;
        }
        if (wowSeenRunning && wow == McwowOverlayLink.WOW_QUIT) {
            closing = true;
            LOGGER.info("mcwow-bridge: WoW closed - saving and closing Minecraft");
            // As the pause menu's Save and Quit.
            if (mc.level != null) mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
    }
}
