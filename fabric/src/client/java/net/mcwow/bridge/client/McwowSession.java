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
 *  - <data dir>/minecraft.status says "menu" or "world", rewritten every 2 s and removed on closing:
 *    the launcher's Play and tools/play.sh start WoW only once Minecraft is in its world (user,
 *    2026-10-05: WoW opening over a Minecraft in its world is the clean picture).
 * Off with "minimizeWindow": false / "linkedQuit": false in config/mcwow.json, or
 * CLASSICCRAFT_MINIMIZE=0 / CLASSICCRAFT_LINKED_QUIT=0.
 */
public final class McwowSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    /** How long WoW must be gone before Minecraft's window comes back (loading hitches are shorter). */
    private static final long RESTORE_AFTER_NANOS = 5_000_000_000L;

    private static boolean minimizeOn, linkedQuitOn;
    private static boolean minimized;
    /** Ticks until the minimize is checked (the window manager answers later), and tries left. */
    private static int checkIn, triesLeft;
    private static long wowLastSeen;
    private static boolean wowSeenRunning;
    private static boolean closing;
    /** A world was loaded last tick while WoW showed Minecraft (input bridge on). */
    private static boolean inLinkedWorld;
    private static int statusIn;

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
            try {
                java.nio.file.Files.deleteIfExists(statusFile());
            } catch (java.io.IOException ignored) {
            }
        });
    }

    private static void minimize(Minecraft mc, String why) {
        triesLeft--;
        checkIn = 20;
        long window = mc.getWindow().handle();
        boolean ok = SDLVideo.SDL_MinimizeWindow(window);
        SDLVideo.SDL_SyncWindow(window);
        LOGGER.info("mcwow-bridge: {} - minimizing Minecraft's window ({})", why,
                ok ? "requested" : "SDL: " + org.lwjgl.sdl.SDLError.SDL_GetError());
    }

    private static java.nio.file.Path statusFile() {
        return net.mcwow.bridge.McwowLinks.dataDir().resolve("minecraft.status");
    }

    /** "world" once a world is loaded and Steve is in it, else "menu"; refreshed every 2 s. */
    private static void writeStatus(Minecraft mc) {
        if (--statusIn > 0) return;
        statusIn = 40;
        try {
            java.nio.file.Path f = statusFile();
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Path tmp = f.resolveSibling("minecraft.status.tmp");
            java.nio.file.Files.writeString(tmp, mc.level != null && mc.player != null ? "world\n" : "menu\n");
            java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException | RuntimeException e) {
            // Only a convenience for the launcher; nothing else reads it.
        }
    }

    private static void tick(Minecraft mc) {
        writeStatus(mc);
        long now = System.nanoTime();
        boolean linked = McwowOverlayLink.linked();
        if (linked) wowLastSeen = now;

        if (minimizeOn) {
            boolean shown = linked && McwowOverlayLink.inputActive() && mc.level != null && mc.player != null;
            if (shown && !minimized) {
                minimized = true;
                triesLeft = 3;
                minimize(mc, "WoW shows Minecraft");
            } else if (minimized && checkIn > 0 && --checkIn == 0) {
                long flags = SDLVideo.SDL_GetWindowFlags(mc.getWindow().handle());
                if ((flags & SDLVideo.SDL_WINDOW_MINIMIZED) != 0) {
                    LOGGER.info("mcwow-bridge: Minecraft's window is minimized");
                } else if (triesLeft > 0) {
                    minimize(mc, "still not minimized (window flags 0x" + Long.toHexString(flags) + ")");
                } else {
                    LOGGER.warn("mcwow-bridge: the window manager didn't minimize Minecraft's window (flags 0x{})",
                            Long.toHexString(flags));
                }
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
