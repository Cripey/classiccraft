package net.mcwow.bridge.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.mcwow.bridge.combat.McwowActorEntity;
import net.mcwow.bridge.combat.McwowActors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Interacting with WoW from Minecraft mode (2026-10-03; the foundation for quests, vendors, gossip
 * and objects). benilla picks what WoW thing is under Minecraft's crosshair with WoW's own pick and
 * publishes it (McwowActors.readFocus: what a right-click would do, its name, distance, whether in
 * reach); here it is shown as an actionbar hint, and a right-click on it - when it is nearer than
 * any Minecraft block - goes to benilla (REN_INTERACT, McwowWorldExporter), which runs WoW's own
 * right-click. Answers come back as Minecraft screens: a book or plaque in Minecraft's book screen
 * so far (MSG_BOOK).
 */
public final class McwowInteract {
    /** Right-clicks for benilla, flushed by McwowWorldExporter. */
    static final AtomicInteger OUT = new AtomicInteger();
    /** What a right-click does, by benilla's cursor kind (external::CROSSHAIR_KINDS); null: nothing. */
    private static final String[] VERBS = {
            null, null, "Talk to", "Loot", "Use", "Trade with", "Read", "Train with", "Fly with", "Skin", "Open",
            "Mine", "Gather", "Pick the lock of", "Repair at", null};
    /** Characters per Minecraft book page (14 lines of ~19). */
    private static final int PAGE_CHARS = 230;

    private static McwowActors.Focus focus;
    private static String shownHint;
    private static boolean useHeld;

    private McwowInteract() {
    }

    static void tick(Minecraft mc) {
        focus = mc.player == null ? null : McwowActors.readFocus();
        if (!mc.options.keyUse.isDown()) useHeld = false;
        String hint = null;
        if (mc.gui.screen() == null && usable(focus)) {
            boolean gather = (focus.guid() >>> 48) == 0xF110L && (focus.kind() == 3 || focus.kind() == 4 || focus.kind() == 10);
            hint = (gather ? "Hold attack: Gather " : "Right-click: " + VERBS[focus.kind()] + " ") + focus.name()
                    + (focus.unable() ? " (too far)" : "");
        }
        if (hint != null && !hint.equals(shownHint)) {
            mc.gui.chatListener().handleOverlay(Component.literal(hint).withStyle(
                    focus.unable() ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
        }
        shownHint = hint;
    }

    private static boolean usable(McwowActors.Focus f) {
        return f != null && f.kind() > 0 && f.kind() < VERBS.length && VERBS[f.kind()] != null && f.guid() != 0;
    }

    /**
     * Minecraft's right-click (MinecraftUseMixin): taken for WoW when its target is nearer than the
     * block or entity Minecraft would use (a WoW creature's own stand-in counts as WoW). Held down,
     * it acts once.
     */
    public static boolean tryUse(Minecraft mc) {
        if (mc.player == null || !usable(focus)) return false;
        if (!wowNearer(mc, focus)) return false; // a Minecraft block or entity in front
        if (!useHeld) {
            useHeld = true;
            OUT.incrementAndGet();
        }
        return true;
    }

    /** This tick's crosshair focus (McwowGather). */
    static McwowActors.Focus focus() {
        return focus;
    }

    /** The WoW target is nearer than the block or entity Minecraft would act on (a WoW creature's
     *  own stand-in counts as WoW). */
    static boolean wowNearer(Minecraft mc, McwowActors.Focus f) {
        HitResult hit = mc.hitResult;
        if (mc.player == null || f == null) return false;
        if (hit != null && hit.getType() != HitResult.Type.MISS
                && !(hit instanceof EntityHitResult e && e.getEntity() instanceof McwowActorEntity)) {
            double d = mc.player.getEyePosition().distanceTo(hit.getLocation());
            // A hit on WoW's own geometry (McwowTargeting: an AIR cell in front of a WoW surface) is
            // often the object's own collision, a little in front of where benilla's pick measures
            // it (2026-10-03: gathering only worked aiming beside the object): it hides the WoW
            // target only when clearly in front of it, a wall between.
            if (hit instanceof net.minecraft.world.phys.BlockHitResult b && mc.level != null
                    && mc.level.getBlockState(b.getBlockPos()).isAir()) {
                return d >= f.distance() - 1.0;
            }
            return d >= f.distance();
        }
        return true;
    }

    /** A book read from WoW (title, then pages), in Minecraft's book screen; any thread. */
    static void showBook(String[] book) {
        List<Component> pages = new ArrayList<>();
        for (int i = 1; i < book.length; i++) {
            List<String> parts = split(book[i].replace("\r", ""));
            for (int k = 0; k < parts.size(); k++) {
                if (i == 1 && k == 0) {
                    pages.add(Component.empty().append(Component.literal(book[0]).withStyle(ChatFormatting.BOLD))
                            .append("\n\n" + parts.get(k)));
                } else {
                    pages.add(Component.literal(parts.get(k)));
                }
            }
        }
        if (pages.isEmpty()) pages.add(Component.literal(book[0]).withStyle(ChatFormatting.BOLD));
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new BookViewScreen(new BookViewScreen.BookAccess(pages))));
    }

    /** A WoW page as Minecraft pages, cut at word boundaries; the first leaves room for the title. */
    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        int limit = PAGE_CHARS - 40;
        StringBuilder page = new StringBuilder();
        for (String word : text.split("(?<= )|(?<=\n)")) {
            if (page.length() + word.length() > limit && page.length() > 0) {
                out.add(page.toString().strip());
                page.setLength(0);
                limit = PAGE_CHARS;
            }
            page.append(word);
        }
        if (!page.toString().isBlank()) out.add(page.toString().strip());
        return out;
    }
}
