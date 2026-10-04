package net.mcwow.bridge.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mcwow.bridge.McwowWaygates;
import net.mcwow.bridge.combat.McwowActors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Waygates on the client (McwowWaygates): naming a waygate just placed, and the destinations menu a
 * waygate opens. Travelling is WoW's teleport: the destination goes to benilla (REN_WAYGATE,
 * McwowWorldExporter), which asks the server; the placement then brings Steve along.
 */
public final class McwowWaygateClient {
    /** Travels for benilla, REN_WAYGATE's 28 bytes each. */
    static final ConcurrentLinkedQueue<ByteBuffer> OUT = new ConcurrentLinkedQueue<>();
    private static final float BLOCKS_TO_YARDS = McwowWorldPlacement.BLOCKS_TO_YARDS;
    /** When the last travel was asked for: the next placement within a minute is its arrival. */
    private static volatile long travelNanos;

    private McwowWaygateClient() {
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(McwowWaygates.NamePrompt.TYPE,
                (msg, ctx) -> ctx.client().gui.setScreen(new NameScreen(msg.pos())));
        ClientPlayNetworking.registerGlobalReceiver(McwowWaygates.Menu.TYPE,
                (msg, ctx) -> ctx.client().gui.setScreen(new MenuScreen(msg)));
    }

    /** Ask benilla to travel to a waygate: arrive 1.5 blocks in front of it, facing away. */
    static void travel(McwowWaygates.Entry e) {
        String dim = e.dim().getPath(); // map_<id>
        int map = Integer.parseInt(dim.substring(dim.indexOf('_') + 1));
        double yaw = Math.toRadians(e.yaw());
        // Minecraft's facing for yaw: (-sin, cos) in (x, z).
        double mx = e.pos().getX() + 0.5 - Math.sin(yaw) * 1.5;
        double mz = e.pos().getZ() + 0.5 + Math.cos(yaw) * 1.5;
        double my = e.pos().getY() + 0.1;
        // Fixed mapping (protocol/mcwow_protocol.h): WoW x = mc z, y = mc x, z = mc y (yards); facing = -yaw.
        ByteBuffer b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(map)
                .putFloat((float) (mz * BLOCKS_TO_YARDS)).putFloat((float) (mx * BLOCKS_TO_YARDS))
                .putFloat((float) (my * BLOCKS_TO_YARDS)).putFloat((float) -yaw)
                .putLong(e.ownerGuid());
        OUT.add(b.flip());
        travelNanos = System.nanoTime();
    }

    /** A placement finished (McwowWorldPlacement): after a waygate travel, WoW's teleport sound. */
    static void placed(Minecraft mc) {
        long t = travelNanos;
        if (t == 0L || System.nanoTime() - t > 60_000_000_000L) return;
        travelNanos = 0L;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(McwowWaygates.TELEPORT_SOUND, 1.0F));
    }

    /** Names a waygate just placed. */
    static final class NameScreen extends Screen {
        private final BlockPos pos;
        private EditBox name;

        NameScreen(BlockPos pos) {
            super(Component.literal("Name this waygate"));
            this.pos = pos;
        }

        @Override
        protected void init() {
            this.name = new EditBox(this.font, this.width / 2 - 100, this.height / 2 - 20, 200, 20, Component.literal("Name"));
            this.name.setMaxLength(32);
            this.name.setValue("Waygate");
            this.addRenderableWidget(this.name);
            this.setInitialFocus(this.name);
            this.addRenderableWidget(Button.builder(Component.literal("Attune"), b -> this.done())
                    .bounds(this.width / 2 - 100, this.height / 2 + 10, 200, 20).build());
        }

        private void done() {
            McwowActors.Me me = McwowActors.readMe();
            ClientPlayNetworking.send(new McwowWaygates.Name(this.pos, this.name.getValue(), me != null ? me.guid() : 0L));
            this.onClose();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            super.extractRenderState(g, mouseX, mouseY, partial);
            g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFD080FF);
        }
    }

    /** The destinations a waygate opens, and its sharing for its owner. */
    static final class MenuScreen extends Screen {
        private final McwowWaygates.Menu menu;

        MenuScreen(McwowWaygates.Menu menu) {
            super(Component.literal("Waygate"));
            this.menu = menu;
        }

        @Override
        protected void init() {
            int x = this.width / 2 - 120, y = 40;
            McwowWaygates.Entry here = null;
            for (McwowWaygates.Entry e : this.menu.entries()) {
                if (e.id().equals(this.menu.here())) here = e;
            }
            if (here != null && here.mine()) {
                McwowWaygates.Entry h = here;
                this.addRenderableWidget(Button.builder(Component.literal("Sharing: " + (h.shared() ? "WoW friends" : "Private")),
                        b -> {
                            ClientPlayNetworking.send(new McwowWaygates.SetShared(h.id(), !h.shared()));
                            this.onClose();
                        }).bounds(x, y, 240, 20).build());
                y += 30;
            }
            int shown = 0;
            for (McwowWaygates.Entry e : this.menu.entries()) {
                if (e.id().equals(this.menu.here())) continue;
                String where = e.dim().getPath().equals("map_0") ? "Eastern Kingdoms" : "Kalimdor";
                String label = e.name() + " - " + where + (e.mine() ? "" : " (" + e.ownerName() + ")");
                this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                    travel(e);
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal("The waygate hums... travelling to " + e.name() + "."));
                    this.onClose();
                }).bounds(x, y, 240, 20).build());
                y += 24;
                shown++;
            }
            if (shown == 0) {
                this.addRenderableWidget(Button.builder(Component.literal("No other waygates attuned yet"), b -> this.onClose())
                        .bounds(x, y, 240, 20).build());
                y += 24;
            }
            this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
                    .bounds(x, y + 6, 240, 20).build());
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            super.extractRenderState(g, mouseX, mouseY, partial);
            String name = "Waygate";
            for (McwowWaygates.Entry e : this.menu.entries()) {
                if (e.id().equals(this.menu.here())) name = e.name();
            }
            g.centeredText(this.font, Component.literal(name), this.width / 2, 20, 0xFFD080FF);
        }
    }
}
