package net.mcwow.bridge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays WoW-captured input (mcwow.dll's InputBridge, src/mcwow.cpp) into Minecraft's own input
 * handlers, as if the Minecraft window had focus. Port of chasmlol/SkyCraft's InputBridge (same
 * Minecraft version): keeps a virtual keyboard so InputConstants.isKeyDown() works, plus
 * IN_LOOK - raw relative mouse motion fed to MouseHandler.onMove's delta arguments, which
 * Minecraft adds straight into its own grabbed-mouse look accumulator (checked via javap).
 */
public final class McwowInputBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final int IN_KEY = 1, IN_MOUSE_BUTTON = 2, IN_SCROLL = 3, IN_CURSOR = 4,
            IN_TEXT = 5, IN_RELEASE_ALL = 6, IN_LOOK = 9;

    private static final boolean[] KEYS = new boolean[512];
    private static final boolean[] BUTTONS = new boolean[8];
    private static double cursorX, cursorY;
    private static double lookX, lookY; // virtual, unbounded position for grabbed-mode motion
    private static int modifiers;
    private static boolean wasActive;
    private static int logged;

    private McwowInputBridge() {
    }

    /**
     * True for as long as WoW is linked - Minecraft then acts focused, uses only the virtual
     * keyboard below and never touches the real mouse, even in WoW UI mode (grave key) when no
     * input is being forwarded. Fixed 2026-10-01: tying this to inputActive made switching to WoW
     * UI mode look like a focus loss, which opened Minecraft's pause menu over WoW. Same scope
     * as SkyCraft's own tookOver(): once linked, Minecraft never reads its own window's input.
     */
    public static boolean tookOver() {
        return McwowOverlayLink.linked();
    }

    public static boolean isKeyDown(int scancode) {
        return scancode >= 0 && scancode < KEYS.length && KEYS[scancode];
    }

    /** Start of every Minecraft tick loop iteration (render thread). */
    public static void beginFrame(Minecraft minecraft) {
        boolean active = McwowOverlayLink.inputActive(); // forwarding (Minecraft mode), not just linked
        if (active != wasActive) {
            wasActive = active;
            LOGGER.info("mcwow-bridge: input bridge {}", active ? "ON (WoW drives Minecraft)" : "OFF");
            if (!active) releaseAll();
        }
        if (!active) {
            McwowOverlayLink.drainInput((t, c, a, b, d) -> { }); // discard; nothing should act on it
            return;
        }
        McwowOverlayLink.drainInput((type, code, a, b, c) -> dispatch(minecraft, type, code, a, b, c));
    }

    private static void dispatch(Minecraft minecraft, int type, int code, int a, int b, int c) {
        long handle = minecraft.getWindow().handle();
        if (logged < 10 && type != IN_LOOK && type != IN_CURSOR) {
            logged++;
            LOGGER.info("mcwow-bridge: input type={} code={} a={}", type, code, a);
        }
        switch (type) {
            case IN_KEY -> key(minecraft, handle, code, a != 0);
            case IN_MOUSE_BUTTON -> {
                if (code > 0 && code < BUTTONS.length) BUTTONS[code] = a != 0;
                minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(code, modifiers), a != 0 ? 1 : 0);
            }
            case IN_SCROLL -> minecraft.mouseHandler.onScroll(handle, 0.0, a / 120.0);
            case IN_CURSOR -> {
                double dx = a - cursorX, dy = b - cursorY;
                cursorX = a;
                cursorY = b;
                minecraft.mouseHandler.onMove(handle, a, b, dx, dy);
            }
            case IN_LOOK -> {
                lookX += a;
                lookY += b;
                minecraft.mouseHandler.onMove(handle, lookX, lookY, a, b);
            }
            case IN_TEXT -> {
                if (minecraft.gui.screen() != null) {
                    minecraft.keyboardHandler.textInput(handle, new String(Character.toChars(a)));
                }
            }
            case IN_RELEASE_ALL -> releaseAll();
            default -> {
            }
        }
    }

    private static void key(Minecraft minecraft, long handle, int scancode, boolean down) {
        if (scancode <= 0 || scancode >= KEYS.length) return;
        boolean wasDown = KEYS[scancode];
        KEYS[scancode] = down;
        updateModifiers();
        int action = down ? (wasDown ? -1 : 1) : 0; // -1 = repeat
        int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true);
        minecraft.keyboardHandler.keyPress(handle, action, new KeyEvent(scancode, keycode, modifiers));
    }

    private static void updateModifiers() {
        int m = 0;
        if (KEYS[225]) m |= 0x0001; // SDL_KMOD_LSHIFT
        if (KEYS[229]) m |= 0x0002; // SDL_KMOD_RSHIFT
        if (KEYS[224]) m |= 0x0040; // SDL_KMOD_LCTRL
        if (KEYS[228]) m |= 0x0080; // SDL_KMOD_RCTRL
        if (KEYS[226]) m |= 0x0100; // SDL_KMOD_LALT
        if (KEYS[230]) m |= 0x0200; // SDL_KMOD_RALT
        modifiers = m;
    }

    /** Lift every key and button we think is held (WoW lost focus, bridge turned off, ...). */
    public static void releaseAll() {
        Minecraft minecraft = Minecraft.getInstance();
        long handle = minecraft.getWindow().handle();
        for (int sc = 0; sc < KEYS.length; sc++) {
            if (KEYS[sc]) {
                KEYS[sc] = false;
                updateModifiers();
                minecraft.keyboardHandler.keyPress(handle, 0,
                        new KeyEvent(sc, SDLKeyboard.SDL_GetKeyFromScancode(sc, (short) 0, true), modifiers));
            }
        }
        for (int button = 1; button < BUTTONS.length; button++) {
            if (BUTTONS[button]) {
                BUTTONS[button] = false;
                minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(button, 0), 0);
            }
        }
    }
}
