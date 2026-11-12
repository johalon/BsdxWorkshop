package bsdx.workshop.vfs;

import bsdx.workshop.pac.PacArchive;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Read-only virtual file system that mirrors BaldrSky.exe's pac priority (notes/02 §4.2): one flat,
 * case-insensitive name space, the FIRST layer that has a name wins.
 * <pre>
 *   [overlay (mod folder / Update3.pac)] > Update7 > ... > Update3 > Update2 > Update > MascotVoice > Voice > Stand
 *   > Face > Thumbnail > System > Script > Bgm > Visual > Movie > Se > Config > PlugIn01..08 > Bmp > Dat
 * </pre>
 * Game pacs are opened with RandomAccessFile mode "r" and never written.
 */
public final class GameFs implements Closeable {
    /** Mount order of BaldrSky.exe (main loader 0x71b880), highest priority first. */
    public static final List<String> GAME_PAC_ORDER;
    static {
        List<String> l = new ArrayList<>(List.of("Update7", "Update6", "Update5", "Update4", "Update3", "Update2", "Update",
                "MascotVoice", "Voice", "Stand", "Face", "Thumbnail", "System", "Script", "Bgm", "Visual", "Movie", "Se", "Config"));
        for (int i = 1; i <= 8; i++) l.add(String.format("PlugIn%02d", i));
        l.add("Bmp");
        l.add("Dat");
        GAME_PAC_ORDER = Collections.unmodifiableList(l);
    }

    public interface Layer extends Closeable {
        String label();
        /** Original-case file names. */
        Collection<String> names();
        boolean has(String lower);
        byte[] read(String lower) throws IOException;
        default String originalName(String lower) { return lower; }
        default Path path() { return null; }
    }

    public static final class PacLayer implements Layer {
        final PacArchive pac; final String label;
        public PacLayer(Path p, String label) throws IOException { pac = new PacArchive(p); this.label = label; }
        @Override public String label() { return label; }
        @Override public Collection<String> names() { List<String> l = new ArrayList<>(); pac.entries().forEach(e -> l.add(e.name)); return l; }
        @Override public boolean has(String lower) { return pac.find(lower) != null; }
        @Override public byte[] read(String lower) throws IOException { return pac.read(lower); }
        @Override public String originalName(String lower) { PacArchive.Entry e = pac.find(lower); return e == null ? lower : e.name; }
        @Override public Path path() { return pac.getPath(); }
        public PacArchive archive() { return pac; }
        @Override public void close() throws IOException { pac.close(); }
    }

    /** A mod folder (e.g. mods/phase3c_merged/files). Only top-level files count (pacs are flat). */
    public static final class DirLayer implements Layer {
        final Path dir; final String label; final Map<String, Path> files = new LinkedHashMap<>();
        public DirLayer(Path dir, String label) throws IOException {
            this.dir = dir; this.label = label;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile).sorted().forEach(p -> files.putIfAbsent(p.getFileName().toString().toLowerCase(Locale.ROOT), p));
            }
        }
        @Override public String label() { return label; }
        @Override public Collection<String> names() { List<String> l = new ArrayList<>(); files.values().forEach(p -> l.add(p.getFileName().toString())); return l; }
        @Override public boolean has(String lower) { return files.containsKey(lower); }
        @Override public byte[] read(String lower) throws IOException { Path p = files.get(lower); return p == null ? null : Files.readAllBytes(p); }
        @Override public String originalName(String lower) { Path p = files.get(lower); return p == null ? lower : p.getFileName().toString(); }
        @Override public Path path() { return dir; }
        @Override public void close() {}
    }

    private final List<Layer> layers = new ArrayList<>();   // highest priority first
    private Layer overlay;

    public static Layer openLayer(Path p, String label) throws IOException {
        return Files.isDirectory(p) ? new DirLayer(p, label) : new PacLayer(p, label);
    }

    /** Opens every known pac found in {@code gameDir} (missing ones are skipped like the exe does). */
    public void mountGameDir(Path gameDir, boolean includeUpdate3to7, List<String> log) throws IOException {
        for (String n : GAME_PAC_ORDER) {
            if (!includeUpdate3to7 && n.matches("Update[3-7]")) continue;
            Path p = findCaseInsensitive(gameDir, n + ".pac");
            if (p == null) continue;
            try {
                layers.add(new PacLayer(p, n + ".pac"));
                if (log != null) log.add("mount " + p.getFileName());
            } catch (IOException e) {
                if (log != null) log.add("SKIP " + p.getFileName() + ": " + e.getMessage());
            }
        }
    }

    /** Adds a pac or folder at the lowest priority (used for staged/extracted test data). */
    public void addBottom(Layer l) { layers.add(l); }

    public void setOverlay(Layer l) throws IOException {
        if (overlay != null) { layers.remove(overlay); overlay.close(); }
        overlay = l;
        if (l != null) layers.add(0, l);
    }

    public Layer getOverlay() { return overlay; }
    public List<Layer> layers() { return Collections.unmodifiableList(layers); }

    static Path findCaseInsensitive(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        if (Files.isRegularFile(p)) return p;
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(x -> x.getFileName().toString().equalsIgnoreCase(name) && Files.isRegularFile(x)).findFirst().orElse(null);
        }
    }

    public Layer layerOf(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        for (Layer l : layers) if (l.has(k)) return l;
        return null;
    }

    public boolean exists(String name) { return layerOf(name) != null; }

    public byte[] read(String name) throws IOException {
        Layer l = layerOf(name);
        return l == null ? null : l.read(name.toLowerCase(Locale.ROOT));
    }

    /** Effective original-case name of a file. */
    public String originalName(String name) {
        Layer l = layerOf(name);
        return l == null ? name : l.originalName(name.toLowerCase(Locale.ROOT));
    }

    /** All effective names (lower-case key -> original name) with a given extension. */
    public SortedMap<String, String> list(String ext) {
        SortedMap<String, String> m = new TreeMap<>();
        String suf = "." + ext.toLowerCase(Locale.ROOT);
        for (int i = layers.size() - 1; i >= 0; i--)
            for (String n : layers.get(i).names()) if (n.toLowerCase(Locale.ROOT).endsWith(suf)) m.put(n.toLowerCase(Locale.ROOT), n);
        return m;
    }

    @Override public void close() throws IOException {
        for (Layer l : layers) try { l.close(); } catch (IOException ignored) {}
        layers.clear();
        overlay = null;
    }
}
