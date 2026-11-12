package bsdx.workshop.data;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v5: tells whether an item name is the game's own wording. The Shift-JIS strings of the user's BaldrSky.exe are read
 * once (nothing is copied or shipped); a name that does not occur there was coined by this tool / project and is marked
 * in the editor. Without the exe nothing is marked.
 */
public final class ExeTerms {
    private ExeTerms() {}
    private static volatile String text;
    private static final Map<String, Boolean> cache = new ConcurrentHashMap<>();
    public static volatile String source = "";

    public static boolean loaded() { return text != null; }

    /** Loads BaldrSky.exe (any case) from the game folder; returns false when it is not there. */
    public static synchronized boolean load(Path gameDir) {
        text = null; cache.clear(); source = "";
        if (gameDir == null) return false;
        try (var s = Files.list(gameDir)) {
            Path exe = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals("baldrsky.exe")).findFirst().orElse(null);
            if (exe == null) return false;
            byte[] b = Files.readAllBytes(exe);
            Charset sj = Charset.forName("MS932");
            StringBuilder sb = new StringBuilder(b.length / 4);
            int st = 0;
            for (int i = 0; i <= b.length; i++) {
                if (i == b.length || b[i] == 0) {
                    if (i - st >= 2) {
                        boolean wide = false;
                        for (int k = st; k < i; k++) if ((b[k] & 0xff) >= 0x80) { wide = true; break; }
                        if (wide) sb.append(new String(b, st, i - st, sj)).append('\n');
                    }
                    st = i + 1;
                }
            }
            text = sb.toString();
            source = exe.toString();
            return true;
        } catch (Exception e) { text = null; return false; }
    }

    /** The term part of a label: text before a trailing explanation in （）/()/[] and without spaces. */
    public static String term(String name) {
        String t = name == null ? "" : name.trim();
        int cut = t.length();
        for (String open : new String[]{"（", "(", "["}) { int i = t.indexOf(open); if (i > 0) cut = Math.min(cut, i); }
        return t.substring(0, cut).trim();
    }

    /** True when the game's exe has this name (term part); also true when the exe is not loaded (nothing to compare). */
    public static boolean isGameTerm(String name) {
        String tx = text;
        if (tx == null) return true;
        String t = term(name);
        if (t.isEmpty() || t.chars().allMatch(c -> c < 0x80)) return true;     // raw names like int1 / hitCount are not terms
        return cache.computeIfAbsent(t, k -> tx.contains(k));
    }
}
