package net.mcwow.bridge.client;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * WoW GM commands from Minecraft's chat (user, 2026-10-03: ".tele stormwind" typed in Minecraft).
 * A chat line starting with "." never reaches Minecraft's server: it goes to benilla over the render
 * ring (REN_CHAT, McwowWorldExporter), which sends it as WoW's edit box would (a Say line, which the
 * server parses commands from). The server's system lines (the replies) come back over the actors
 * file's text ring and show in Minecraft's chat, WoW's colour and link codes stripped. NPC speech
 * (says, yells, emotes, whispers) comes the same way, coloured as in WoW.
 */
public final class McwowGmChat {
    /** Typed lines for benilla, flushed by McwowWorldExporter. */
    static final ConcurrentLinkedQueue<String> OUT = new ConcurrentLinkedQueue<>();
    /** WoW's inline codes: |cAARRGGBB colour, |r reset, |H...|h link, |h link end. */
    private static final Pattern WOW_CODES = Pattern.compile("\\|c[0-9a-fA-F]{8}|\\|r|\\|H[^|]*\\|h|\\|h");

    private McwowGmChat() {
    }

    static void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.startsWith(".")) return true;
            OUT.add(message);
            show(Component.literal(message).withStyle(ChatFormatting.GRAY));
            return false;
        });
    }

    /** The server's replies since last tick, into Minecraft's chat. */
    static void tick(Minecraft mc) {
        if (mc.player == null) return;
        for (String text : net.mcwow.bridge.combat.McwowActors.drainText()) {
            // NPC speech arrives coloured as WoW colours it (benilla external::npc_line, § codes);
            // the server's system lines are yellow, as in WoW.
            boolean coloured = text.startsWith("\u00a7");
            String colour = coloured ? text.substring(0, 2) : "";
            for (String line : WOW_CODES.matcher(text).replaceAll("").split("\n")) {
                if (line.isBlank()) continue;
                show(coloured ? Component.literal(line.startsWith("\u00a7") ? line : colour + line)
                        : Component.literal(line).withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    private static void show(Component c) {
        Minecraft.getInstance().gui.chatListener().handleSystemMessage(c, false);
    }
}
