package net.mcwow.bridge.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.mcwow.bridge.McwowMusic;
import net.mcwow.bridge.McwowWaygates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The WoW music discs' audio (McwowMusic): benilla extracts each track from the user's install as
 * MP3 to ~/.local/share/classiccraft/music; here each becomes the Ogg Vorbis Minecraft streams, once,
 * with ffmpeg. Mono, as vanilla discs are: Minecraft places only mono sounds at the jukebox. Watches
 * for a while, since benilla may start after Minecraft.
 */
public final class McwowMusicFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");

    private McwowMusicFiles() {
    }

    public static Path dir() {
        return Path.of(System.getProperty("user.home"), ".local", "share", "classiccraft", "music");
    }

    static void start() {
        Thread t = new Thread(() -> {
            for (int round = 0; round < 120; round++) { // ten minutes
                boolean all = true;
                for (String key : McwowMusic.DISCS) all &= convert(key);
                all &= convert(McwowWaygates.TELEPORT_SOUND_KEY);
                if (all) return;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "mcwow-music");
        t.setDaemon(true);
        t.start();
    }

    /** Whether key's Ogg is there (made now if its MP3 is). */
    private static boolean convert(String key) {
        Path mp3 = dir().resolve(key + ".mp3"), ogg = dir().resolve(key + ".ogg");
        if (Files.exists(ogg)) return true;
        if (!Files.exists(mp3)) mp3 = dir().resolve(key + ".wav"); // sound effects come as WAV
        if (!Files.exists(mp3)) return false;
        Path tmp = dir().resolve(key + ".tmp.ogg");
        try {
            Process p = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-i", mp3.toString(), "-ac", "1",
                    "-c:a", "libvorbis", "-q:a", "5", tmp.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) {
                LOGGER.warn("mcwow-bridge: music {}: ffmpeg failed: {}", key, out.trim());
                return true; // don't retry a failing file every 5 s
            }
            Files.move(tmp, ogg, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("mcwow-bridge: music {} ready", key);
            return true;
        } catch (Exception e) {
            LOGGER.warn("mcwow-bridge: music {}: {} (is ffmpeg installed?)", key, e.toString());
            return true;
        }
    }
}
