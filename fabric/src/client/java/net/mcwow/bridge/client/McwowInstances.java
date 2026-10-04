package net.mcwow.bridge.client;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which region ("slot") of a WoW map's dimension Steve belongs in (protocol v2 step b, 2026-10-01).
 *
 * <p>User's choice: blocks are kept per INSTANCE - a dungeon run that resets starts empty.
 * <ul>
 *   <li>Open-world maps (Map.dbc instanceType 0): slot 0, always.</li>
 *   <li>Dungeons/raids (1, 2): AzerothCore binds the player to the instance on entry
 *       ({@code character_instance}, Map.cpp InstanceMap::AddPlayerToMap), so the instance id is
 *       looked up in the character DB by (character guid, map, difficulty). AzerothCore REUSES
 *       freed instance ids (MapMgr::GenerateInstanceId, lowest free), so the id is never the slot
 *       itself: slots are allocated monotonically and an entry is marked dead once its instance
 *       row disappears (reset/expired); entering a reused id then gets a fresh slot.
 *       Known limit: a reset AND reuse of the same id for the same map while Minecraft isn't
 *       running can't be noticed - that run would reopen the old slot.</li>
 *   <li>Battlegrounds/arenas (3, 4): not bound in the DB; a fresh slot per visit.</li>
 * </ul>
 * The slot table lives in the world save ({@code mcwow_instances.json}).
 */
public final class McwowInstances {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcwow-bridge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // AzerothCore reuses the LOWEST free instance id, so after a reset the next run very likely gets
    // the same id right away - the gap where the id is absent from `instance` (between the reset's
    // delete and the next entry's insert, at least a walk/loading screen) must be caught, hence a
    // fast poll of a tiny table.
    private static final long LIVE_POLL_MS = 1_000;

    private record Key(int guid, int map, int difficulty) {
    }

    /** Persistent slot table (JSON). */
    private static final class Table {
        int nextSlot = 1;
        List<Entry> entries = new ArrayList<>();
    }

    private static final class Entry {
        int map, difficulty, instanceId, slot;
        boolean dead;
        String created;
        long createdMillis;
    }

    private static final class Config {
        // classiccraft's VMaNGOS characters DB (MariaDB on 3307; mcwow's AzerothCore holds 3306).
        String jdbcUrl = "jdbc:mysql://127.0.0.1:3307/characters";
        String user = "mangos";
        String password = "mangos";
    }

    private static Map<Integer, Integer> instanceTypes;
    private static Config config;
    private static Table table;
    private static Path tableFile;
    private static MinecraftServer tableServer;

    // Resolver thread state.
    private static volatile Key wanted;
    private static volatile long wantedNotBefore; // System.currentTimeMillis()
    private static final ConcurrentHashMap<Key, Integer> RESOLVED = new ConcurrentHashMap<>();
    // Instance ids we hold slots for, and when a poll (its START time) last found each missing.
    // An entry is dead once its id was missing after the entry was created.
    private static final Set<Integer> TRACKED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Integer, Long> ABSENT_AT = new ConcurrentHashMap<>();
    private static Thread thread;
    private static volatile String lastDbError;

    // Battleground/arena: the slot handed out for the current visit.
    private static int bgMap = -1, bgSlot;

    private McwowInstances() {
    }

    /** Map.dbc InstanceType (0 open world, 1 dungeon, 2 raid, 3 BG, 4 arena); 0 if unknown. */
    public static int instanceType(int mapId) {
        if (instanceTypes == null) {
            instanceTypes = new HashMap<>();
            try (Reader r = new InputStreamReader(
                    McwowInstances.class.getResourceAsStream("/mcwow/maps.json"),
                    StandardCharsets.UTF_8)) {
                JsonObject o = GSON.fromJson(r, JsonObject.class);
                for (String k : o.keySet()) {
                    instanceTypes.put(Integer.parseInt(k),
                            o.getAsJsonObject(k).get("instanceType").getAsInt());
                }
            } catch (Exception e) {
                LOGGER.error("mcwow-bridge: could not read mcwow/maps.json", e);
            }
        }
        return instanceTypes.getOrDefault(mapId, 0);
    }

    /**
     * Slot for the current WoW state, or -1 while an instance id is still being looked up.
     * {@code newVisit} = this placement entered the map (map change), not a teleport within it.
     * Client thread only.
     */
    public static int slotFor(MinecraftServer server, int mapId, int luaDifficulty, long guid,
                              boolean newVisit) {
        int type = instanceType(mapId);
        if (type == 0) return 0;
        loadTable(server);
        if (type == 3 || type == 4) {
            if (newVisit || bgMap != mapId) {
                bgMap = mapId;
                bgSlot = allocate(mapId, 0, -1);
                LOGGER.info("mcwow-bridge: battleground/arena map {} -> fresh slot {}", mapId,
                        bgSlot);
            }
            return bgSlot;
        }
        startThread();
        // Lua GetInstanceDifficulty() is 1-based; AzerothCore's Difficulty enum is 0-based
        // (0 = 5N/10N, 1 = 5H/25N, 2 = 10H, 3 = 25H).
        Key key = new Key((int) guid, mapId, Math.max(0, luaDifficulty - 1));
        if (newVisit) {
            // Entering (again): the bind may now point at a different instance (reset, new run).
            // AzerothCore writes the bind row asynchronously on entry - give it a moment so the
            // previous run's row isn't read.
            RESOLVED.remove(key);
            wantedNotBefore = System.currentTimeMillis() + 1500;
        }
        Integer instanceId = RESOLVED.get(key);
        if (instanceId == null) {
            wanted = key;
            return -1;
        }
        for (Entry e : table.entries) {
            if (!e.dead && e.map == key.map() && e.difficulty == key.difficulty()
                    && e.instanceId == instanceId) {
                Long absent = ABSENT_AT.get(e.instanceId);
                if (absent != null && absent > e.createdMillis) {
                    e.dead = true; // instance gone since - this visit is a new run
                    saveTable();
                    continue;
                }
                return e.slot;
            }
        }
        int slot = allocate(mapId, key.difficulty(), instanceId);
        LOGGER.info("mcwow-bridge: instance {} (map {}, difficulty {}) -> new slot {}",
                instanceId, mapId, key.difficulty(), slot);
        return slot;
    }

    /** Last database problem, for the placement log. */
    public static String lastDbError() {
        return lastDbError;
    }

    private static int allocate(int map, int difficulty, int instanceId) {
        Entry e = new Entry();
        e.map = map;
        e.difficulty = difficulty;
        e.instanceId = instanceId;
        e.slot = table.nextSlot++;
        e.dead = instanceId < 0; // BG/arena slots are single-visit
        e.createdMillis = System.currentTimeMillis();
        e.created = java.time.Instant.ofEpochMilli(e.createdMillis).toString();
        table.entries.add(e);
        if (instanceId >= 0) TRACKED.add(instanceId);
        saveTable();
        return e.slot;
    }

    private static void loadTable(MinecraftServer server) {
        if (table != null && tableServer == server) return;
        tableServer = server;
        tableFile = server.getWorldPath(LevelResource.ROOT).resolve("mcwow_instances.json");
        table = new Table();
        if (Files.exists(tableFile)) {
            try (Reader r = Files.newBufferedReader(tableFile)) {
                Table t = GSON.fromJson(r, Table.class);
                if (t != null && t.entries != null) table = t;
            } catch (Exception e) {
                LOGGER.error("mcwow-bridge: could not read {} - starting a new slot table",
                        tableFile, e);
            }
        }
        RESOLVED.clear();
        TRACKED.clear();
        for (Entry e : table.entries) if (!e.dead && e.instanceId >= 0) TRACKED.add(e.instanceId);
        bgMap = -1;
        LOGGER.info("mcwow-bridge: slot table {} ({} entries, next slot {})", tableFile,
                table.entries.size(), table.nextSlot);
    }

    private static void saveTable() {
        if (tableFile == null) return;
        try {
            Path tmp = tableFile.resolveSibling("mcwow_instances.json.tmp");
            Files.writeString(tmp, GSON.toJson(table));
            Files.move(tmp, tableFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.error("mcwow-bridge: could not save {}", tableFile, e);
        }
    }

    private static Config config() {
        if (config == null) {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("mcwow.json");
            config = new Config();
            try {
                if (Files.exists(p)) {
                    try (Reader r = Files.newBufferedReader(p)) {
                        Config c = GSON.fromJson(r, Config.class);
                        if (c != null) config = c;
                    }
                } else {
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, GSON.toJson(config));
                }
            } catch (Exception e) {
                LOGGER.error("mcwow-bridge: config {} unreadable, using defaults", p, e);
            }
        }
        return config;
    }

    private static synchronized void startThread() {
        if (thread != null) return;
        Config c = config();
        thread = new Thread(() -> runResolver(c), "mcwow-instance-resolver");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runResolver(Config c) {
        long lastLivePoll = 0;
        Connection conn = null;
        while (true) {
            try {
                if (conn == null || !conn.isValid(2)) {
                    conn = DriverManager.getConnection(c.jdbcUrl, c.user, c.password);
                    LOGGER.info("mcwow-bridge: connected to {}", c.jdbcUrl);
                    lastDbError = null;
                }
                long now = System.currentTimeMillis();
                if (now - lastLivePoll > LIVE_POLL_MS && !TRACKED.isEmpty()) {
                    Set<Integer> ids = new HashSet<>();
                    try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM instance");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) ids.add(rs.getInt(1));
                    }
                    for (Integer id : TRACKED) {
                        if (!ids.contains(id) && ABSENT_AT.put(id, now) == null) {
                            LOGGER.info("mcwow-bridge: instance {} is gone (reset/expired)", id);
                        }
                    }
                    lastLivePoll = now;
                }
                Key k = wanted;
                if (k != null && !RESOLVED.containsKey(k) && now >= wantedNotBefore) {
                    Integer id = lookup(conn, k, true);
                    if (id == null) id = lookup(conn, k, false); // single bind, any difficulty
                    if (id != null) {
                        RESOLVED.put(k, id);
                        LOGGER.info("mcwow-bridge: guid {} map {} difficulty {} -> instance {}",
                                k.guid(), k.map(), k.difficulty(), id);
                    }
                }
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (!msg.equals(lastDbError)) LOGGER.warn("mcwow-bridge: instance DB - {}", msg);
                lastDbError = msg;
                try {
                    if (conn != null) conn.close();
                } catch (Exception ignored) {
                }
                conn = null;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static Integer lookup(Connection conn, Key k, boolean byDifficulty) throws Exception {
        String sql = "SELECT i.id FROM character_instance ci JOIN instance i ON ci.instance = i.id "
                + "WHERE ci.guid = ? AND i.map = ?" + (byDifficulty ? " AND i.difficulty = ?" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, k.guid());
            ps.setInt(2, k.map());
            if (byDifficulty) ps.setInt(3, k.difficulty());
            try (ResultSet rs = ps.executeQuery()) {
                Integer id = null;
                int n = 0;
                while (rs.next()) {
                    id = rs.getInt(1);
                    n++;
                }
                return n == 1 ? id : null;
            }
        }
    }
}
