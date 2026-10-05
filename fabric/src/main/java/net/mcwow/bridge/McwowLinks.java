package net.mcwow.bridge;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Where the bridge's shared memory and the per-user data live, per platform (2026-10-04, Windows
 * groundwork). Mirrors benilla's classiccraft crate `link.rs`; keep both in step.
 * <ul>
 * <li>Shared memory: on Linux a file in /dev/shm (RAM-backed), elsewhere on Unix one in /tmp; on
 * Windows a named, pagefile-backed mapping {@code Local\<name>} (a file there would be written back
 * to disk all the time). {@code CLASSICCRAFT_SHM_DIR} overrides the Unix directory.</li>
 * <li>Data (music, dances, the weapon resource pack): {@code CLASSICCRAFT_DATA_DIR}, else
 * {@code %APPDATA%\classiccraft} on Windows, {@code $XDG_DATA_HOME/classiccraft} or
 * {@code ~/.local/share/classiccraft} elsewhere.</li>
 * </ul>
 */
public final class McwowLinks {
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private McwowLinks() {
    }

    public static Path dataDir() {
        String d = System.getenv("CLASSICCRAFT_DATA_DIR");
        if (d != null && !d.isEmpty()) return Path.of(d);
        if (WINDOWS) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) return Path.of(appData, "classiccraft");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isEmpty()) return Path.of(xdg, "classiccraft");
        return Path.of(System.getProperty("user.home"), ".local", "share", "classiccraft");
    }

    private static Path shmDir() {
        String d = System.getenv("CLASSICCRAFT_SHM_DIR");
        if (d != null && !d.isEmpty()) return Path.of(d);
        return Path.of(System.getProperty("os.name", "").toLowerCase().startsWith("linux") ? "/dev/shm" : "/tmp");
    }

    /** A small plain file next to the links (tuning knobs): the shm directory, the data directory on Windows. */
    public static Path tuningFile(String name) {
        return (WINDOWS ? dataDir() : shmDir()).resolve(name);
    }

    /** How a link is shown in logs: its file path, or its mapping name on Windows. */
    public static String describe(String name) {
        return WINDOWS ? "Local\\" + name : shmDir().resolve(name).toString();
    }

    /**
     * Maps the link {@code name} (e.g. classiccraft_v1.shm) read-write for the life of the game.
     * {@code create}: make it if missing, at least {@code size} bytes. Without it, null while the link
     * is missing or smaller than {@code size} (its creator hasn't set it up yet).
     */
    public static MemorySegment map(String name, long size, boolean create) throws IOException {
        return WINDOWS ? Win.map(name, size, create) : mapFile(shmDir().resolve(name), size, create);
    }

    private static MemorySegment mapFile(Path p, long size, boolean create) throws IOException {
        if (!create && !Files.exists(p)) return null;
        try (FileChannel ch = create
                ? FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                : FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (!create && ch.size() < size) return null;
            return ch.map(FileChannel.MapMode.READ_WRITE, 0, size, Arena.global()); // grows the file if needed
        }
    }

    /** kernel32's named file mappings through the FFM API. */
    private static final class Win {
        private static final int PAGE_READWRITE = 0x04;
        private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
        private static final long MBI_SIZE = 48, MBI_REGION_SIZE = 24; // MEMORY_BASIC_INFORMATION, x64
        private static final MethodHandle CREATE, OPEN, MAP_VIEW, QUERY;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            ValueLayout.OfInt i = ValueLayout.JAVA_INT;
            CREATE = linker.downcallHandle(k32.find("CreateFileMappingW").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, i, i, i, ValueLayout.ADDRESS));
            OPEN = linker.downcallHandle(k32.find("OpenFileMappingW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, i, i, ValueLayout.ADDRESS));
            MAP_VIEW = linker.downcallHandle(k32.find("MapViewOfFile").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, i, i, i, ValueLayout.JAVA_LONG));
            QUERY = linker.downcallHandle(k32.find("VirtualQuery").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        }

        static MemorySegment map(String name, long size, boolean create) throws IOException {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment wide = a.allocateFrom(ValueLayout.JAVA_BYTE,
                        ("Local\\" + name + "\0").getBytes(StandardCharsets.UTF_16LE));
                // CreateFileMappingW opens an existing mapping of that name (at its own size).
                MemorySegment h = create
                        ? (MemorySegment) CREATE.invokeExact(MemorySegment.ofAddress(-1L), MemorySegment.NULL,
                                PAGE_READWRITE, (int) (size >>> 32), (int) size, wide)
                        : (MemorySegment) OPEN.invokeExact(FILE_MAP_ALL_ACCESS, 0, wide);
                if (h.address() == 0) {
                    if (create) throw new IOException("CreateFileMappingW failed for " + name);
                    return null; // not there yet
                }
                MemorySegment view = (MemorySegment) MAP_VIEW.invokeExact(h, FILE_MAP_ALL_ACCESS, 0, 0, 0L);
                if (view.address() == 0) throw new IOException("MapViewOfFile failed for " + name);
                MemorySegment info = a.allocate(MBI_SIZE, 8);
                long got = (long) QUERY.invokeExact(view, info, MBI_SIZE);
                long region = got == 0 ? 0 : info.get(ValueLayout.JAVA_LONG, MBI_REGION_SIZE);
                if (region < size) return null; // the handle and view stay: harmless, retried later
                return view.reinterpret(size);
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException(t);
            }
        }
    }
}
