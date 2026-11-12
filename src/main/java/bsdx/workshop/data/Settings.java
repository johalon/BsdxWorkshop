package bsdx.workshop.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/**
 * Tool settings. Stored next to the jar (BsdxWorkshop.settings.properties) so that the tool writes nothing
 * outside the PROJECT folder it is installed in.
 */
public final class Settings {
    /** The folder the tool is installed in: the jar's folder, or its parent when the jar sits in "app" (release zip layout). */
    public static final String DEFAULT_PROJECT = installDir().toString();
    /** No default game folder: the user picks it once (ファイル → ゲームフォルダを開く) and it is remembered. */
    public static final String DEFAULT_GAME = "";

    public String gameDir = DEFAULT_GAME;
    public String projectRoot = DEFAULT_PROJECT;
    public String exportDir = installDir().resolve("out").toString();

    static Path installDir() {
        try {
            Path jar = Path.of(Settings.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            if (dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("app") && dir.getParent() != null) dir = dir.getParent();
            return dir;
        } catch (Exception e) {
            return Path.of("").toAbsolutePath();
        }
    }
    public String overlay = "";
    /** Explicit opt-in to write outside projectRoot (the game folder is refused regardless). */
    public boolean allowOutsideProject = false;
    /** Mount Update3..7.pac found in the game folder (an installed mod) as part of the base. */
    public boolean mountGameUpdate3to7 = true;
    public boolean spmCheck = true;
    /** Most recent first, at most 8 each. */
    public final java.util.List<String> recentOverlays = new java.util.ArrayList<>(), recentGameDirs = new java.util.ArrayList<>();
    /** Window bounds "x,y,w,h" and maximized flag; last selected mech id. */
    public String windowBounds = "";
    public boolean maximized = false;
    public int lastMech = -1;

    private final Path file;

    public Settings(Path file) { this.file = file; }

    public static Path defaultFile() {
        String override = System.getProperty("bsdx.settings");
        if (override != null) return Path.of(override);
        try {
            Path jar = Path.of(Settings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            return dir.resolve("BsdxWorkshop.settings.properties");
        } catch (Exception e) {
            return Path.of("BsdxWorkshop.settings.properties");
        }
    }

    public static Settings load(Path f) {
        Settings s = new Settings(f);
        if (f != null && Files.isRegularFile(f)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
                s.gameDir = p.getProperty("gameDir", s.gameDir);
                s.projectRoot = p.getProperty("projectRoot", s.projectRoot);
                s.exportDir = p.getProperty("exportDir", s.exportDir);
                s.overlay = p.getProperty("overlay", s.overlay);
                s.allowOutsideProject = Boolean.parseBoolean(p.getProperty("allowOutsideProject", "false"));
                s.mountGameUpdate3to7 = Boolean.parseBoolean(p.getProperty("mountGameUpdate3to7", "true"));
                s.spmCheck = Boolean.parseBoolean(p.getProperty("spmCheck", "true"));
                for (String x : p.getProperty("recentOverlays", "").split("\\|")) if (!x.isBlank()) s.recentOverlays.add(x);
                for (String x : p.getProperty("recentGameDirs", "").split("\\|")) if (!x.isBlank()) s.recentGameDirs.add(x);
                s.windowBounds = p.getProperty("windowBounds", "");
                s.maximized = Boolean.parseBoolean(p.getProperty("maximized", "false"));
                try { s.lastMech = Integer.parseInt(p.getProperty("lastMech", "-1")); } catch (NumberFormatException ignored) {}
            } catch (IOException ignored) {}
        }
        return s;
    }

    public void save() throws IOException {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("gameDir", gameDir);
        p.setProperty("projectRoot", projectRoot);
        p.setProperty("exportDir", exportDir);
        p.setProperty("overlay", overlay == null ? "" : overlay);
        p.setProperty("allowOutsideProject", Boolean.toString(allowOutsideProject));
        p.setProperty("mountGameUpdate3to7", Boolean.toString(mountGameUpdate3to7));
        p.setProperty("spmCheck", Boolean.toString(spmCheck));
        p.setProperty("recentOverlays", String.join("|", recentOverlays));
        p.setProperty("recentGameDirs", String.join("|", recentGameDirs));
        p.setProperty("windowBounds", windowBounds == null ? "" : windowBounds);
        p.setProperty("maximized", Boolean.toString(maximized));
        p.setProperty("lastMech", Integer.toString(lastMech));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "BsdxWorkshop settings");
        }
    }

    public Path file() { return file; }

    /** Moves {@code v} to the front of a recent list (max 8). */
    public static void remember(java.util.List<String> l, String v) {
        if (v == null || v.isBlank()) return;
        l.removeIf(x -> x.equalsIgnoreCase(v));
        l.add(0, v);
        while (l.size() > 8) l.remove(l.size() - 1);
    }
}
