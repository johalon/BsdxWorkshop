package bsdx.workshop.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Decides whether a directory may receive the exported Update3.pac.
 * Rules: never the game folder (or anything inside it, or any folder that contains BaldrSky*.exe or Dat.pac);
 * must be inside the PROJECT folder unless {@link Settings#allowOutsideProject} is explicitly enabled.
 */
public final class ExportGuard {
    private ExportGuard() {}

    static Path canon(Path p) {
        Path a = p.toAbsolutePath().normalize();
        // resolve symlinks for the existing part of the path
        Path existing = a;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return a;
        try {
            Path real = existing.toRealPath();
            return real.resolve(existing.relativize(a)).normalize();
        } catch (IOException e) {
            return a;
        }
    }

    static boolean isInside(Path child, Path parent) {
        String c = child.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String p = parent.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return c.equals(p) || c.startsWith(p + "/");
    }

    static boolean looksLikeGameFolder(Path d) {
        if (d == null || !Files.isDirectory(d)) return false;
        try (var s = Files.list(d)) {
            return s.anyMatch(x -> {
                String n = x.getFileName().toString().toLowerCase(Locale.ROOT);
                return (n.startsWith("baldrsky") && n.endsWith(".exe")) || n.equals("dat.pac") || n.equals("system.pac");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /** @return null if OK, otherwise a Japanese reason. */
    public static String check(Path exportDir, Settings s) {
        if (exportDir == null || exportDir.toString().isBlank()) return "書き出し先が指定されていません。";
        Path d = canon(exportDir);
        if (s.gameDir != null && !s.gameDir.isBlank()) {
            Path g = canon(Path.of(s.gameDir));
            if (isInside(d, g)) return "書き出し先がゲームフォルダ（" + g + "）の中です。ゲームフォルダへは書き込みません。";
        }
        for (Path x = d; x != null; x = x.getParent())
            if (looksLikeGameFolder(x)) return "書き出し先（またはその親 " + x + "）にゲーム本体（BaldrSky*.exe / Dat.pac / System.pac）があります。ゲームフォルダへは書き込みません。";
        if (!s.allowOutsideProject) {
            if (s.projectRoot == null || s.projectRoot.isBlank()) return "PROJECT フォルダが設定されていません。";
            Path r = canon(Path.of(s.projectRoot));
            if (!isInside(d, r)) return "書き出し先が PROJECT フォルダ（" + r + "）の外です。\n設定で「PROJECT フォルダ外への書き出しを許可」を明示的に有効にしない限り書き出せません。";
        }
        return null;
    }
}
