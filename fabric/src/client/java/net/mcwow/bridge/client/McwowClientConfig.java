package net.mcwow.bridge.client;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/** On/off switches in config/mcwow.json (the launcher writes that file), each with an env override. */
final class McwowClientConfig {
    private McwowClientConfig() {
    }

    /** `key` in mcwow.json (default on); the environment variable `env` = "0" turns it off. */
    static boolean flag(String key, String env) {
        if ("0".equals(System.getenv(env))) return false;
        Path p = FabricLoader.getInstance().getConfigDir().resolve("mcwow.json");
        try (Reader r = Files.newBufferedReader(p)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            return !o.has(key) || o.get(key).getAsBoolean();
        } catch (Exception e) {
            return true;
        }
    }
}
