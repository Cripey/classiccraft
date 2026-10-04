package net.mcwow.bridge.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mcwow.bridge.McwowDialog;
import net.mcwow.bridge.McwowGear;
import net.mcwow.bridge.McwowQuestRewards;
import net.mcwow.bridge.McwowVendors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WoW's NPC windows as Minecraft screens (2026-10-03), the foundation for quests, gossip and
 * vendors from Minecraft mode. benilla sends each open window as it changes (MSG_DIALOG on the
 * geometry ring, McwowGeomClient -> {@link #show}); this screen shows it - the NPC's text, its
 * options, a quest's objectives and its rewards as the Minecraft items they will be - and sends
 * the choice back (REN_DIALOG, McwowWorldExporter), which benilla queues as WoW's own Lua frames
 * would. A vendor opens Minecraft's trade screen with the vendor's Minecraft goods (McwowVendors);
 * a turned-in quest's rewards become Minecraft items and emeralds (McwowQuestRewards).
 */
public final class McwowDialogs {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    /** REN_DIALOG payloads for McwowWorldExporter: u64 npc, u8 kind, u8 action, u16 0, u32 arg. */
    public static final ConcurrentLinkedQueue<ByteBuffer> OUT = new ConcurrentLinkedQueue<>();

    /** The vendor whose trade screen we asked for / have open (0 none), and since when. */
    private static long vendorNpc;
    private static boolean vendorOpen;
    private static long vendorAskedNanos;
    /** The reward chosen on the last reward screen, and its quest. */
    private static McwowDialog.WowItem picked;
    private static int pickedQuest;
    /** The last quest's title, for the "Quest completed" line. */
    private static String lastQuestTitle = "";

    private McwowDialogs() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(McwowDialogs::tick);
    }

    static void send(long npc, int kind, int action, int arg) {
        OUT.add(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(npc).put((byte) kind)
                .put((byte) action).putShort((short) 0).putInt(arg).flip());
        LOGGER.info("mcwow-bridge: dialog choice kind {} action {} arg {} for {}", kind, action, arg, Long.toHexString(npc));
    }

    // ---- MSG_DIALOG -------------------------------------------------------------------------

    private static final class Reader {
        final ByteBuffer b;

        Reader(byte[] raw) {
            b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        }

        int i() { return b.getInt(); }

        String s() {
            int n = b.getInt();
            if (n < 0 || n > b.remaining()) throw new IllegalArgumentException("bad string length " + n);
            byte[] x = new byte[n];
            b.get(x);
            return new String(x, StandardCharsets.UTF_8);
        }

        List<McwowDialog.WowItem> items() {
            int n = i();
            List<McwowDialog.WowItem> out = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                out.add(new McwowDialog.WowItem(i(), i(), i(), i(), i(), i(), i(), i(), s()));
            }
            return out;
        }
    }

    /** A window from its MSG_DIALOG payload (layout: benilla classiccraft geom.rs MSG_DIALOG). */
    static McwowDialog.Window parse(byte[] raw) {
        try {
            Reader r = new Reader(raw);
            long npc = r.b.getLong();
            int kind = r.i(), quest = r.i();
            String name = r.s(), title = r.s(), text = r.s(), objectives = r.s();
            int n = r.i();
            List<McwowDialog.Option> options = new ArrayList<>();
            for (int k = 0; k < n; k++) options.add(new McwowDialog.Option(r.i(), r.s()));
            List<McwowDialog.WowItem> choices = r.items(), rewards = r.items(), required = r.items();
            int money = r.i();
            boolean completable = r.i() != 0;
            int xp = r.i();
            return new McwowDialog.Window(npc, kind, quest, name, title, text, objectives, options, choices, rewards,
                    required, money, completable, xp);
        } catch (RuntimeException e) {
            LOGGER.warn("mcwow-bridge: bad dialog message: {}", e.toString());
            return null;
        }
    }

    /** A window from benilla (geometry reader thread). */
    static void show(McwowDialog.Window w) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> apply(mc, w));
    }

    private static void apply(Minecraft mc, McwowDialog.Window w) {
        LOGGER.info("mcwow-bridge: dialog kind {} from {} ({})", w.kind(), w.npcName(), w.title());
        switch (w.kind()) {
            case McwowDialog.CLOSE -> {
                // Only that window's screen: a gossip closing must not close the popup it opened.
                if (mc.gui.screen() instanceof DialogScreen d && d.w.npc() == w.npc()) d.closeQuietly();
                if (vendorNpc != 0 && vendorNpc == w.npc()) {
                    if (vendorOpen && mc.player != null) mc.player.closeContainer();
                    vendorNpc = 0;
                    vendorOpen = false;
                }
            }
            case McwowDialog.VENDOR -> {
                if (mc.gui.screen() instanceof DialogScreen d) d.closeQuietly();
                vendorNpc = w.npc();
                vendorOpen = false;
                vendorAskedNanos = System.nanoTime();
                ClientPlayNetworking.send(new McwowVendors.OpenVendor(w.npcEntry(), w.npcName()));
            }
            case McwowDialog.QUEST_DONE -> {
                // 1.12's SMSG_QUESTGIVER_QUEST_COMPLETE lists only the fixed rewards, never the one
                // picked: it is remembered from the reward screen (2026-10-03, picks went missing).
                List<McwowDialog.WowItem> rewards = new ArrayList<>(w.rewards());
                if (picked != null && pickedQuest == w.questId()) rewards.add(picked);
                picked = null;
                ClientPlayNetworking.send(new McwowQuestRewards.QuestDone(w.questId(), w.money(), w.xp(),
                        lastQuestTitle.isEmpty() ? "quest " + w.questId() : lastQuestTitle, rewards));
            }
            default -> {
                if (!w.title().isEmpty()) lastQuestTitle = w.title();
                if (mc.gui.screen() instanceof DialogScreen d) d.replaced = true;
                mc.gui.setScreen(new DialogScreen(w));
            }
        }
    }

    /** A vendor's trade screen: closing it closes the WoW vendor (and a vendor that never opened). */
    private static void tick(Minecraft mc) {
        if (vendorNpc == 0) return;
        boolean merchant = mc.gui.screen() instanceof MerchantScreen;
        if (!vendorOpen && merchant) {
            vendorOpen = true;
        } else if ((vendorOpen && !merchant) || (!vendorOpen && System.nanoTime() - vendorAskedNanos > 5_000_000_000L)) {
            send(vendorNpc, McwowDialog.VENDOR, McwowDialog.ACT_CLOSE, 0);
            vendorNpc = 0;
            vendorOpen = false;
        }
    }

    // ---- the screen ---------------------------------------------------------------------------

    static final class DialogScreen extends Screen {
        private static final int W = 320, LINE = 10, ROW = 18;
        private final McwowDialog.Window w;
        /** Replaced by the next window or closed by WoW: no close goes back. */
        boolean replaced;
        private int chosen = -1;
        private List<FormattedCharSequence> lines = List.of();
        private final List<ItemStack> choiceStacks = new ArrayList<>(), rewardStacks = new ArrayList<>(),
                requiredStacks = new ArrayList<>();
        private int scroll, textTop, textBottom, left;
        private Button complete;

        DialogScreen(McwowDialog.Window w) {
            super(Component.literal(w.npcName().isEmpty() ? "" : w.npcName()));
            this.w = w;
        }

        void closeQuietly() {
            this.replaced = true;
            this.onClose();
        }

        private static ItemStack preview(McwowDialog.WowItem item) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return ItemStack.EMPTY;
            return McwowDialog.toMinecraft(item, mc.level.registryAccess(), null);
        }

        private String body() {
            StringBuilder sb = new StringBuilder(w.text());
            if (w.kind() == McwowDialog.QUEST_DETAIL && !w.objectives().isEmpty()) {
                sb.append("\n\n§6Quest Objectives§r\n").append(w.objectives());
            }
            return sb.toString().replace("\r", "");
        }

        @Override
        protected void init() {
            this.left = (this.width - W) / 2;
            int bottom = this.height - 8;
            List<Runnable> rows = new ArrayList<>();
            // Buttons from the bottom up: the panel's actions, then options / choices.
            int y = bottom - 20;
            switch (w.kind()) {
                case McwowDialog.QUEST_DETAIL -> {
                    addRenderableWidget(Button.builder(Component.literal("Accept"),
                            b -> act(McwowDialog.ACT_ACCEPT, 0)).bounds(left, y, W / 2 - 2, 20).build());
                    addRenderableWidget(Button.builder(Component.literal("Decline"),
                            b -> act(McwowDialog.ACT_DECLINE, 0)).bounds(left + W / 2 + 2, y, W / 2 - 2, 20).build());
                }
                case McwowDialog.QUEST_PROGRESS -> {
                    Button cont = Button.builder(Component.literal("Continue"),
                            b -> act(McwowDialog.ACT_CONTINUE, 0)).bounds(left, y, W / 2 - 2, 20).build();
                    cont.active = w.completable();
                    addRenderableWidget(cont);
                    addRenderableWidget(Button.builder(Component.literal("Cancel"),
                            b -> act(McwowDialog.ACT_DECLINE, 0)).bounds(left + W / 2 + 2, y, W / 2 - 2, 20).build());
                }
                case McwowDialog.QUEST_REWARD -> {
                    complete = Button.builder(Component.literal("Complete Quest"), b -> {
                                if (chosen >= 0 && chosen < w.choices().size()) {
                                    picked = w.choices().get(chosen);
                                    pickedQuest = w.questId();
                                }
                                act(McwowDialog.ACT_COMPLETE, Math.max(0, chosen));
                            }).bounds(left, y, W / 2 - 2, 20).build();
                    complete.active = w.choices().isEmpty() || chosen >= 0; // a pick survives the rebuild
                    addRenderableWidget(complete);
                    addRenderableWidget(Button.builder(Component.literal("Cancel"),
                            b -> act(McwowDialog.ACT_DECLINE, 0)).bounds(left + W / 2 + 2, y, W / 2 - 2, 20).build());
                }
                default -> addRenderableWidget(Button.builder(Component.literal("Goodbye"), b -> this.onClose())
                        .bounds(left, y, W, 20).build());
            }
            y -= 4;
            choiceStacks.clear();
            rewardStacks.clear();
            requiredStacks.clear();
            for (McwowDialog.WowItem it : w.choices()) choiceStacks.add(preview(it));
            for (McwowDialog.WowItem it : w.rewards()) rewardStacks.add(preview(it));
            // Required items are WoW's own (quest items, materials): shown by their WoW names.
            for (McwowDialog.WowItem it : w.required()) requiredStacks.add(ItemStack.EMPTY);
            // Choices (reward panel: pick one).
            if (w.kind() == McwowDialog.QUEST_REWARD) {
                for (int k = w.choices().size() - 1; k >= 0; k--) {
                    y -= 20;
                    int idx = k;
                    addRenderableWidget(Button.builder(choiceLabel(k), b -> {
                        chosen = idx;
                        if (complete != null) complete.active = true;
                        this.rebuildWidgets();
                    }).bounds(left + 20, y, W - 20, 18).build());
                }
            }
            // Options (gossip, greeting).
            for (int k = w.options().size() - 1; k >= 0; k--) {
                y -= 20;
                int idx = k;
                McwowDialog.Option o = w.options().get(k);
                addRenderableWidget(Button.builder(optionLabel(o), b -> act(McwowDialog.ACT_SELECT, idx))
                        .bounds(left, y, W, 18).build());
            }
            this.textBottom = y - 4 - rewardsHeight();
            this.textTop = 26;
            this.lines = this.font.split(Component.literal(body()), W - 8);
        }

        private Component choiceLabel(int k) {
            ItemStack s = choiceStacks.get(k);
            String name = s.isEmpty() ? w.choices().get(k).name() : s.getHoverName().getString();
            return Component.literal((k == chosen ? "> " : "") + name).withStyle(k == chosen ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
        }

        private static Component optionLabel(McwowDialog.Option o) {
            return switch (o.icon()) {
                case McwowDialog.ICON_AVAILABLE -> Component.literal("! " + o.label()).withStyle(ChatFormatting.YELLOW);
                case McwowDialog.ICON_ACTIVE -> Component.literal("? " + o.label()).withStyle(ChatFormatting.GRAY);
                case McwowDialog.ICON_VENDOR -> Component.literal("$ " + o.label()).withStyle(ChatFormatting.GREEN);
                default -> Component.literal(o.label());
            };
        }

        /** Rows under the text: rewards (detail, reward panels), required items (progress), money. */
        private int rewardsHeight() {
            int rows = 0;
            if (w.kind() == McwowDialog.QUEST_DETAIL) rows += shown(choiceStacks) == 0 ? 0 : 1 + shown(choiceStacks);
            if (w.kind() == McwowDialog.QUEST_DETAIL || w.kind() == McwowDialog.QUEST_REWARD) {
                rows += shown(rewardStacks) == 0 ? 0 : 1 + shown(rewardStacks);
                if (w.money() > 0) rows += 1;
            }
            if (w.kind() == McwowDialog.QUEST_PROGRESS) rows += w.required().isEmpty() ? 0 : 1 + w.required().size();
            return rows * ROW;
        }

        /** Rewards with a Minecraft form (the rest give nothing any more, so aren't listed). */
        private static int shown(List<ItemStack> stacks) {
            int n = 0;
            for (ItemStack s : stacks) if (!s.isEmpty()) n++;
            return n;
        }

        private void act(int action, int arg) {
            send(w.npc(), w.kind(), action, arg);
        }

        @Override
        public void onClose() {
            if (!replaced) {
                // ESC / Goodbye: the window closes on WoW's side too.
                send(w.npc(), w.kind(), McwowDialog.ACT_CLOSE, 0);
            }
            replaced = true;
            super.onClose();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double sx, double sy) {
            int visible = Math.max(1, (textBottom - textTop) / LINE);
            scroll = Math.clamp(scroll - (int) Math.signum(sy) * 3, 0, Math.max(0, lines.size() - visible));
            return true;
        }

        private int itemRows(GuiGraphicsExtractor g, String header, List<McwowDialog.WowItem> rows,
                             List<ItemStack> stacks, int y) {
            boolean required = stacks == requiredStacks;
            if (rows.isEmpty() || (!required && shown(stacks) == 0)) return y;
            g.text(this.font, Component.literal(header).withStyle(ChatFormatting.GOLD), left + 4, y + 5, 0xFFFFFFFF);
            y += ROW;
            for (int k = 0; k < rows.size(); k++) {
                ItemStack s = stacks.get(k);
                McwowDialog.WowItem it = rows.get(k);
                if (!required && s.isEmpty()) continue; // no Minecraft form: not a reward any more
                if (!s.isEmpty()) g.item(s, left + 4, y);
                String label = required ? it.name() + (it.count() > 1 ? " x" + it.count() : "")
                        : s.getHoverName().getString()
                        + (s.getCount() > 1 ? " x" + s.getCount() : "")
                        + (it.quality() >= 2 && !s.is(Items.ENCHANTED_BOOK) ? " (enchanted)" : "")
                        + (it.count() > 1 && s.getCount() == 1 ? " x" + it.count() : "");
                g.text(this.font, Component.literal(label), left + 24, y + 5, 0xFFFFFFFF);
                y += ROW;
            }
            return y;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            g.fill(left - 6, 4, left + W + 6, this.height - 4, 0xC0101010);
            super.extractRenderState(g, mouseX, mouseY, partial);
            String head = w.title().isEmpty() ? w.npcName() : w.title();
            g.centeredText(this.font, Component.literal(head).withStyle(ChatFormatting.BOLD), this.width / 2, 10, 0xFFFFD080);
            int y = textTop;
            for (int k = scroll; k < lines.size() && y + LINE <= textBottom; k++) {
                g.text(this.font, lines.get(k), left + 4, y, 0xFFE8E0D0);
                y += LINE;
            }
            if (lines.size() > (textBottom - textTop) / LINE) {
                g.text(this.font, "(scroll for more)", left + W - 90, textBottom - LINE + 1, 0xFF909090);
            }
            y = textBottom + 2;
            if (w.kind() == McwowDialog.QUEST_DETAIL) {
                y = itemRows(g, "You will be able to choose one of these rewards:", w.choices(), choiceStacks, y);
            }
            if (w.kind() == McwowDialog.QUEST_DETAIL || w.kind() == McwowDialog.QUEST_REWARD) {
                y = itemRows(g, "You will receive:", w.rewards(), rewardStacks, y);
                if (w.money() > 0) {
                    int level = Math.max(1, McwowGear.wowLevel());
                    int emeralds = Math.max(1, (int) Math.round(w.money()
                            / net.mcwow.bridge.combat.McwowLoot.emeraldCopper(level)));
                    g.item(new ItemStack(Items.EMERALD), left + 4, y);
                    g.text(this.font, Component.literal(emeralds + " emerald" + (emeralds > 1 ? "s" : "")), left + 24, y + 5, 0xFF80FF80);
                }
            }
            if (w.kind() == McwowDialog.QUEST_PROGRESS) {
                itemRows(g, "Required items:", w.required(), requiredStacks, y);
            }
        }
    }
}
