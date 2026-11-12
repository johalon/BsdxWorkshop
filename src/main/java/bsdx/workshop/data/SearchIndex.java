package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Name search over mechs, weapons (all mek) and skills (all registered waz). Loaded (edited) docs are read live. */
public final class SearchIndex {
    public static final class Hit {
        public final String kind, text; public final int mechId, weapon, waz, skill;
        Hit(String kind, String text, int mechId, int weapon, int waz, int skill) {
            this.kind = kind; this.text = text; this.mechId = mechId; this.weapon = weapon; this.waz = waz; this.skill = skill;
        }
        @Override public String toString() { return "[" + kind + "] " + text; }
    }

    private final Project p;
    private final Map<Integer, List<String>> weaponNames = new ConcurrentHashMap<>();
    private volatile boolean built;

    public SearchIndex(Project p) { this.p = p; }

    /** Parses every mek / waz once (a few seconds); safe to call from a background thread. */
    public void build() {
        for (Project.Mech m : p.mechs) {
            if (m.mekFile == null) continue;
            try {
                if (p.docIfLoaded(m.mekFile) == null) {
                    JsonNode t = p.codec.parseToTree(m.mekFile, p.fs.read(m.mekFile));
                    weaponNames.put(m.id, names(t));
                }
            } catch (Exception e) { p.log.add("search: " + m.mekFile + ": " + e); }
        }
        for (int w = 0; w < p.wazaList().size(); w++) if (p.wazFile(w) != null) p.skillNames(w);
        built = true;
    }

    public boolean isBuilt() { return built; }

    static List<String> names(JsonNode mek) {
        List<String> l = new ArrayList<>();
        JsonNode wm = mek.path("mekWeaponInfoMap");
        for (int i = 0; i < wm.size(); i++) l.add(wm.path("" + i).path("weaponName").asText());
        return l;
    }

    public List<Hit> search(String q, int max) {
        List<Hit> out = new ArrayList<>();
        String n = q.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return out;
        for (Project.Mech m : p.mechs) {
            if (m.mekFile == null) continue;
            if ((m.mekName + " " + m.pilot + " " + m.name + " " + m.mekFile).toLowerCase(Locale.ROOT).contains(n))
                out.add(new Hit("機体", m.id + " " + m.mekName + " / " + m.pilot + "（" + m.mekFile + "）", m.id, -1, m.wazIdx, -1));
        }
        for (Project.Mech m : p.mechs) {
            if (m.mekFile == null) continue;
            FileDoc d = p.docIfLoaded(m.mekFile);
            List<String> ws = d != null ? names(d.tree) : weaponNames.get(m.id);
            if (ws == null) continue;
            for (int i = 0; i < ws.size() && out.size() < max; i++)
                if (ws.get(i).toLowerCase(Locale.ROOT).contains(n)) out.add(new Hit("武装", ws.get(i) + "　— " + m.mekName + " 武装#" + i, m.id, i, m.wazIdx, -1));
        }
        for (int w = 0; w < p.wazaList().size() && out.size() < max; w++) {
            String f = p.wazFile(w);
            if (f == null) continue;
            List<String> sk = p.skillNames(w);
            if (sk == null) continue;
            Project.Mech owner = p.mechOfWaz(w);
            for (int s = 0; s < sk.size() && out.size() < max; s++)
                if (sk.get(s).toLowerCase(Locale.ROOT).contains(n)) out.add(new Hit("技", sk.get(s) + "　— " + f + " 技" + s + (owner == null ? "（共通）" : "（" + owner.mekName + "）"), owner == null ? -1 : owner.id, -1, w, s));
        }
        return out;
    }
}
