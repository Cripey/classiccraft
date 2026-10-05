package net.mcwow.bridge.client;

import java.nio.file.Path;

import net.mcwow.bridge.McwowLinks;

/**
 * The WoW music discs' audio (McwowMusic): benilla extracts each track from the user's install and
 * writes the mono Ogg Vorbis Minecraft streams to {@code <data dir>/music/<key>.ogg} (classiccraft
 * crate music.rs; McwowLinks.dataDir). Mono, as vanilla discs are: Minecraft places only mono sounds
 * at the jukebox. Until it's there the disc plays its silent placeholder (SoundBufferLibraryMixin).
 */
public final class McwowMusicFiles {
    private McwowMusicFiles() {
    }

    public static Path dir() {
        return McwowLinks.dataDir().resolve("music");
    }
}
