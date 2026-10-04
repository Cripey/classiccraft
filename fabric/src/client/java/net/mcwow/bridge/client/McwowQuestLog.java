package net.mcwow.bridge.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.mcwow.bridge.McwowDialog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WoW's quest log in Minecraft (2026-10-03, user: quest items live in the quest log, not as
 * Minecraft items). benilla sends the log and the quest items in the WoW bags whenever they change
 * (MSG_QUESTLOG, McwowGeomClient -> {@link #update}); J (rebindable, "Quest Log") or /quests opens
 * it: the quests on the left, the chosen one's objectives with progress and its text on the right,
 * the quest items below with "Use" for the ones that do something (benilla uses the bag item), and
 * Abandon (pressed twice).
 */
public final class McwowQuestLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    public static final int KIND = 9; // benilla external_dialog::DIALOG_QUEST_LOG

    public record Quest(int id, int level, int complete, String title, List<Objective> objectives, String description,
                        String objectivesText, int rewardMoney) {
    }

    public record Objective(boolean finished, String text) {
    }

    public record Item(int bag, int slot, int id, int count, boolean usable, String name) {
    }

    private static volatile List<Quest> quests = List.of();
    private static volatile List<Item> items = List.of();
    private static KeyMapping key;
    private static boolean migrated;

    private McwowQuestLog() {
    }

    public static void init() {
        // 26.3 numbers keys as SDL scancodes: InputConstants, never a GLFW number (74, GLFW's J,
        // is Home here - the first build bound the log to Home).
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwow.quest_log",
                com.mojang.blaze3d.platform.InputConstants.KEY_J, KeyMapping.Category.GAMEPLAY));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!migrated) {
                migrated = true;
                // That first build saved Home in options.txt: move it to J once.
                if (key.saveString().equals("key.keyboard.home")) {
                    key.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD.getOrCreate(
                            com.mojang.blaze3d.platform.InputConstants.KEY_J));
                    KeyMapping.resetMapping();
                    mc.options.save();
                    LOGGER.info("mcwow-bridge: Quest Log key moved from Home to J");
                }
            }
            while (key.consumeClick()) {
                if (mc.gui.screen() == null) mc.gui.setScreen(new LogScreen());
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommands.literal("quests").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.gui.setScreen(new LogScreen()));
                    return 1;
                })));
    }

    // ---- MSG_QUESTLOG ---------------------------------------------------------------------------

    /** The log from its MSG_QUESTLOG payload (layout: benilla classiccraft geom.rs MSG_QUESTLOG). */
    static void update(byte[] raw) {
        try {
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            List<Quest> qs = new ArrayList<>();
            int n = b.getInt();
            for (int k = 0; k < n; k++) {
                int id = b.getInt(), level = b.getInt(), complete = b.getInt();
                String title = str(b);
                int no = b.getInt();
                List<Objective> objectives = new ArrayList<>();
                for (int o = 0; o < no; o++) objectives.add(new Objective(b.getInt() != 0, str(b)));
                qs.add(new Quest(id, level, complete, title, objectives, str(b), str(b), b.getInt()));
            }
            List<Item> is = new ArrayList<>();
            int ni = b.getInt();
            for (int k = 0; k < ni; k++) {
                is.add(new Item(b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getInt() != 0, str(b)));
            }
            List<Item> before = seen ? items : null;
            quests = qs;
            items = is;
            seen = true;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (before != null) announce(mc, before, is, qs);
                if (mc.gui.screen() instanceof LogScreen s) s.refresh();
            });
        } catch (RuntimeException e) {
            LOGGER.warn("mcwow-bridge: bad quest log message: {}", e.toString());
        }
    }

    /** A log has arrived before (the first one announces nothing). */
    private static boolean seen;

    private static int count(List<Item> list, int id) {
        int n = 0;
        for (Item it : list) if (it.id() == id) n += it.count();
        return n;
    }

    /**
     * Quest items that came in (a gather, a kill), the Minecraft way: the pickup sound and
     * "+1 Cactus Apple - Cactus Apple: 3/10" on the action bar.
     */
    private static void announce(Minecraft mc, List<Item> before, List<Item> now, List<Quest> qs) {
        java.util.Set<Integer> done = new java.util.HashSet<>();
        for (Item it : now) {
            if (!done.add(it.id())) continue;
            int gained = count(now, it.id()) - count(before, it.id());
            if (gained <= 0 || mc.player == null) continue;
            String progress = "";
            for (Quest q : qs) {
                for (Objective o : q.objectives()) {
                    if (o.text().startsWith(it.name())) progress = o.text();
                }
            }
            mc.gui.chatListener().handleOverlay(Component.literal("+" + gained + " " + it.name()).withStyle(ChatFormatting.YELLOW)
                    .append(progress.isEmpty() ? Component.empty()
                            : Component.literal("  " + progress).withStyle(ChatFormatting.WHITE)));
            mc.player.playSound(net.minecraft.sounds.SoundEvents.ITEM_PICKUP, 0.4F, 1.4F);
            LOGGER.info("mcwow-bridge: quest item +{} {} ({})", gained, it.name(), progress);
        }
    }

    private static String str(ByteBuffer b) {
        int n = b.getInt();
        if (n < 0 || n > b.remaining()) throw new IllegalArgumentException("bad string length " + n);
        byte[] x = new byte[n];
        b.get(x);
        return new String(x, StandardCharsets.UTF_8);
    }

    // ---- the screen -------------------------------------------------------------------------------

    static final class LogScreen extends Screen {
        private static final int LIST_W = 150, PANE_W = 250, GAP = 8, LINE = 10;
        private int selected = -1; // quest id
        private int abandonArmed = -1;
        private int left, top, scroll;
        private List<FormattedCharSequence> lines = List.of();

        LogScreen() {
            super(Component.literal("Quest Log"));
        }

        void refresh() {
            this.rebuildWidgets();
        }

        private Quest current() {
            for (Quest q : quests) if (q.id() == selected) return q;
            return quests.isEmpty() ? null : quests.getFirst();
        }

        @Override
        protected void init() {
            int w = LIST_W + GAP + PANE_W;
            left = (this.width - w) / 2;
            top = 24;
            Quest cur = current();
            if (cur != null) selected = cur.id();
            int y = top;
            for (Quest q : quests) {
                String state = q.complete() > 0 ? " (Complete)" : q.complete() < 0 ? " (Failed)" : "";
                Component label = Component.literal("[" + q.level() + "] " + q.title() + state).withStyle(
                        q.id() == selected ? ChatFormatting.YELLOW : q.complete() > 0 ? ChatFormatting.GREEN : ChatFormatting.WHITE);
                addRenderableWidget(Button.builder(label, b -> {
                    selected = q.id();
                    abandonArmed = -1;
                    scroll = 0;
                    this.rebuildWidgets();
                }).bounds(left, y, LIST_W, 16).build());
                y += 18;
            }
            if (quests.isEmpty()) y += 18;
            // The quest items, with Use where they do something.
            y += 14;
            int itemsTop = y;
            for (Item it : items) {
                if (it.usable()) {
                    addRenderableWidget(Button.builder(Component.literal("Use"), b -> {
                        McwowDialogs.send(0, KIND, McwowDialog.ACT_SELECT, (it.bag() << 8) | it.slot());
                        this.onClose();
                    }).bounds(left + LIST_W - 34, y, 34, 14).build());
                }
                y += 16;
            }
            this.itemsTop = itemsTop;
            // The chosen quest's pane.
            int px = left + LIST_W + GAP;
            if (cur != null) {
                boolean armed = abandonArmed == cur.id();
                addRenderableWidget(Button.builder(Component.literal(armed ? "Abandon - sure?" : "Abandon")
                        .withStyle(armed ? ChatFormatting.RED : ChatFormatting.WHITE), b -> {
                    if (abandonArmed == cur.id()) {
                        McwowDialogs.send(0, KIND, McwowDialog.ACT_DECLINE, cur.id());
                        abandonArmed = -1;
                    } else {
                        abandonArmed = cur.id();
                    }
                    this.rebuildWidgets();
                }).bounds(px, this.height - 28, PANE_W / 2 - 2, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
                    .bounds(px + PANE_W / 2 + 2, this.height - 28, PANE_W / 2 - 2, 20).build());
            StringBuilder sb = new StringBuilder();
            if (cur != null) {
                for (Objective o : cur.objectives()) {
                    sb.append(o.finished() ? "§a- " : "§f- ").append(o.text()).append(o.finished() ? " (Complete)" : "").append("§r\n");
                }
                if (!cur.objectivesText().isEmpty()) sb.append("\n").append(cur.objectivesText()).append("\n");
                if (!cur.description().isEmpty()) sb.append("\n§6Description§r\n").append(cur.description());
            } else {
                sb.append("No quests. Right-click a WoW quest giver (a yellow !) to get one.");
            }
            this.lines = this.font.split(Component.literal(sb.toString().replace("\r", "")), PANE_W - 8);
        }

        private int itemsTop;

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double sx, double sy) {
            int visible = Math.max(1, (this.height - 40 - (top + 14)) / LINE);
            scroll = Math.clamp(scroll - (int) Math.signum(sy) * 3, 0, Math.max(0, lines.size() - visible));
            return true;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            g.fill(left - 6, 4, left + LIST_W + GAP + PANE_W + 6, this.height - 4, 0xC0101010);
            super.extractRenderState(g, mouseX, mouseY, partial);
            g.centeredText(this.font, Component.literal("Quest Log").withStyle(ChatFormatting.BOLD), this.width / 2, 10, 0xFFFFD080);
            g.text(this.font, Component.literal("Quest Items").withStyle(ChatFormatting.GOLD), left, itemsTop - 12, 0xFFFFFFFF);
            int y = itemsTop;
            if (items.isEmpty()) g.text(this.font, "(none)", left + 4, y + 3, 0xFF909090);
            for (Item it : items) {
                // Cut to the room left of the Use button ("..." when cut; the whole name as a tooltip).
                String label = it.name() + (it.count() > 1 ? " x" + it.count() : "");
                int room = (it.usable() ? LIST_W - 38 : LIST_W) - 6;
                if (this.font.width(label) > room) {
                    if (mouseX >= left && mouseX < left + room && mouseY >= y && mouseY < y + 14) {
                        g.setTooltipForNextFrame(Component.literal(label), mouseX, mouseY);
                    }
                    label = this.font.plainSubstrByWidth(label, room - this.font.width("...")) + "...";
                }
                g.text(this.font, label, left + 4, y + 3, 0xFFE8E0D0);
                y += 16;
            }
            int px = left + LIST_W + GAP;
            Quest cur = current();
            if (cur != null) {
                g.text(this.font, Component.literal(cur.title()).withStyle(ChatFormatting.BOLD), px + 4, top, 0xFFFFD080);
            }
            y = top + 14;
            int bottom = this.height - 34;
            for (int k = scroll; k < lines.size() && y + LINE <= bottom; k++) {
                g.text(this.font, lines.get(k), px + 4, y, 0xFFE8E0D0);
                y += LINE;
            }
        }
    }
}
