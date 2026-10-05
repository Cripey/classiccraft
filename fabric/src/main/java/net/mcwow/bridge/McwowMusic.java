package net.mcwow.bridge;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;

/**
 * WoW music discs (user's pick, 2026-10-03): Tavern (Alliance), Sacred, the main theme and Thunder
 * Bluff, each a disc for Minecraft's jukebox. The songs (data/mcwow/jukebox_song), sounds.json and
 * item models (vanilla discs' looks) are the mod's; the audio is the user's own install's, extracted
 * by benilla (classiccraft crate music.rs) and streamed from <data dir>/music
 * (client McwowMusicFiles, SoundBufferLibraryMixin). In the creative Tools & Utilities tab.
 */
public final class McwowMusic {
    /** Disc keys, as in music.rs, the jukebox songs, sounds.json and the item ids. */
    public static final String[] DISCS = {"tavern_alliance", "sacred", "main_theme", "thunder_bluff"};
    private static final List<Item> ITEMS = new ArrayList<>();

    private McwowMusic() {
    }

    public static void register() {
        for (String key : DISCS) {
            Identifier sound = Identifier.fromNamespaceAndPath("mcwow", "music_disc." + key);
            Registry.register(BuiltInRegistries.SOUND_EVENT, sound, SoundEvent.createVariableRangeEvent(sound));
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM,
                    Identifier.fromNamespaceAndPath("mcwow", "music_disc_" + key));
            ResourceKey<JukeboxSong> song = ResourceKey.create(Registries.JUKEBOX_SONG,
                    Identifier.fromNamespaceAndPath("mcwow", key));
            ITEMS.add(Registry.register(BuiltInRegistries.ITEM, itemKey, new Item(new Item.Properties()
                    .setId(itemKey).stacksTo(1).rarity(Rarity.RARE).jukeboxPlayable(song))));
        }
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("tools_and_utilities"))).register(out -> ITEMS.forEach(out::accept));
    }
}
