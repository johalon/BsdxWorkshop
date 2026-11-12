package bsdx.workshop.data;

import bsdx.workshop.vfs.GameFs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giga.nexas.dto.bsdx.spm.Spm;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The loaded game state: read-only VFS (game pacs + optional overlay), registries (grp) and the editable
 * documents (mek / waz JSON trees) with one shared undo stack.
 */
public final class Project {
    public final Codec codec;
    public final Fields fields;
    public final GameFs fs;
    public final UndoStack undo = new UndoStack();
    public JsonNode mekaGroup, wazaGroup, spriteGroup, seGroup, batVoice, term;
    public final List<Mech> mechs = new ArrayList<>();
    private final Map<String, FileDoc> docs = new LinkedHashMap<>();
    private final Map<String, Optional<Spm>> spmCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<BufferedImage>> pngCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Optional<BufferedImage>> e) { return size() > 96; }
    });
    private volatile RefIndex refIndex;
    public final List<String> log = new ArrayList<>();

    /** One MekaGroup entry. */
    public static final class Mech {
        public int id; public String name, code; public boolean exists;
        public String mekFile;              // effective original-case file name or null
        public String mekName = "", pilot = "";
        public int wazIdx = -1, spmIdx = -1;
        public String origin = "";
        @Override public String toString() { return id + " " + name; }
    }

    public Project(Codec codec, Fields fields, GameFs fs) {
        this.codec = codec; this.fields = fields; this.fs = fs;
    }

    // ------------------------------------------------------------------ loading
    public JsonNode loadGrp(String name) throws IOException {
        byte[] b = fs.read(name);
        if (b == null) throw new IOException(name + " がどのレイヤにも見つかりません（Dat.pac が必要です）");
        return codec.parseToTree(name, b);
    }

    public void loadRegistries() throws IOException {
        mekaGroup = loadGrp("MekaGroup.grp");
        wazaGroup = doc("WazaGroup.grp").tree;   // editable: param (= skill count) follows skill insert / delete
        spriteGroup = loadGrp("SpriteGroup.grp");
        seGroup = loadGrp("SeGroup.grp");
        batVoice = loadGrp("BatVoice.grp");
        term = loadGrp("Term.grp");
        mechs.clear();
        ArrayNode ml = (ArrayNode) mekaGroup.get("mekaList");
        for (int i = 0; i < ml.size(); i++) {
            JsonNode e = ml.get(i);
            Mech m = new Mech();
            m.id = i;
            m.name = Json.s(e, "mekaName", "");
            m.code = Json.s(e, "mekaCodeName", "");
            m.exists = Json.i(e, "existFlag", 0) != 0;
            if (m.exists && !m.name.isEmpty() && fs.exists(m.name + ".mek")) {
                m.mekFile = fs.originalName(m.name + ".mek");
                GameFs.Layer l = fs.layerOf(m.mekFile);
                m.origin = l == null ? "" : l.label();
                try {
                    com.giga.nexas.dto.bsdx.mek.Mek mek = (com.giga.nexas.dto.bsdx.mek.Mek) codec.parse(m.mekFile, fs.read(m.mekFile));
                    var bi = mek.getMekBasicInfo();
                    m.mekName = nz(bi.getMekName());
                    m.pilot = nz(bi.getPilotNameKanji());
                    m.wazIdx = bi.getWazFileSequence() == null ? -1 : bi.getWazFileSequence();
                    m.spmIdx = bi.getSpmFileSequence() == null ? -1 : bi.getSpmFileSequence();
                } catch (Exception ex) {
                    log.add("mek 読み込み失敗 " + m.mekFile + ": " + ex);
                }
            }
            mechs.add(m);
        }
    }

    static String nz(String s) { return s == null ? "" : s; }

    /** Refreshes name/pilot columns from the (possibly edited) mek doc. */
    public void refreshMech(Mech m) {
        FileDoc d = m.mekFile == null ? null : docs.get(m.mekFile.toLowerCase(Locale.ROOT));
        if (d == null) return;
        JsonNode bi = d.tree.path("mekBasicInfo");
        m.mekName = Json.s(bi, "mekName", m.mekName);
        m.pilot = Json.s(bi, "pilotNameKanji", m.pilot);
    }

    public synchronized FileDoc doc(String fileName) throws IOException {
        String k = fileName.toLowerCase(Locale.ROOT);
        FileDoc d = docs.get(k);
        if (d != null) return d;
        byte[] b = fs.read(fileName);
        if (b == null) throw new IOException(fileName + " が見つかりません");
        GameFs.Layer l = fs.layerOf(fileName);
        d = new FileDoc(fs.originalName(fileName), l == null ? "?" : l.label(), b, codec.parseToTree(fileName, b));
        docs.put(k, d);
        return d;
    }

    public synchronized FileDoc docIfLoaded(String fileName) { return docs.get(fileName.toLowerCase(Locale.ROOT)); }
    public synchronized List<FileDoc> loadedDocs() { return new ArrayList<>(docs.values()); }
    public synchronized List<FileDoc> dirtyDocs() {
        List<FileDoc> l = new ArrayList<>();
        for (FileDoc d : docs.values()) if (d.isDirty()) l.add(d);
        return l;
    }

    // ------------------------------------------------------------------ registry helpers
    public ArrayNode wazaList() { return (ArrayNode) wazaGroup.get("wazaList"); }
    public ArrayNode spriteList() { return (ArrayNode) spriteGroup.get("spriteList"); }
    public ArrayNode seList() { return (ArrayNode) seGroup.get("seList"); }
    public ArrayNode voiceList() { return (ArrayNode) batVoice.get("voiceList"); }
    public ArrayNode termList() { return (ArrayNode) term.get("termList"); }

    public String wazFile(int idx) {
        ArrayNode w = wazaList();
        if (idx < 0 || idx >= w.size() || Json.i(w.get(idx), "existFlag", 0) == 0) return null;
        String n = Json.s(w.get(idx), "wazaDisplayName", null);
        return n == null ? null : fs.originalName(n + ".waz");
    }

    public String wazLabel(int idx) {
        ArrayNode w = wazaList();
        if (idx < 0 || idx >= w.size()) return "?" + idx;
        JsonNode e = w.get(idx);
        if (Json.i(e, "existFlag", 0) == 0) return idx + ": (空き)";
        return idx + ": " + Json.s(e, "wazaDisplayName", "?") + " 「" + Json.s(e, "wazaName", "") + "」";
    }

    public int wazParam(int idx) {
        ArrayNode w = wazaList();
        return idx < 0 || idx >= w.size() ? -1 : Json.i(w.get(idx), "param", -1);
    }

    public int wazIndexOfFile(String fileName) {
        String b = Codec.base(fileName);
        ArrayNode w = wazaList();
        for (int i = 0; i < w.size(); i++)
            if (Json.i(w.get(i), "existFlag", 0) != 0 && Json.s(w.get(i), "wazaDisplayName", "").equalsIgnoreCase(b)) return i;
        return -1;
    }

    public String spmFile(int idx) {
        ArrayNode s = spriteList();
        if (idx < 0 || idx >= s.size() || Json.i(s.get(idx), "existFlag", 0) == 0) return null;
        return Json.s(s.get(idx), "spriteFileName", null);
    }

    /** Mechs whose own waz is the given WazaGroup index. */
    public List<Mech> mechsUsingWaz(int wazIdx) {
        List<Mech> l = new ArrayList<>();
        for (Mech m : mechs) if (m.mekFile != null && m.wazIdx == wazIdx) l.add(m);
        return l;
    }

    public Mech mechOfWaz(int wazIdx) {
        List<Mech> l = mechsUsingWaz(wazIdx);
        return l.isEmpty() ? null : l.get(0);
    }

    public String seLabel(int cat, int item) {
        ArrayNode se = seList();
        if (cat < 0 || cat >= se.size()) return "?" + cat + "/" + item;
        JsonNode c = se.get(cat);
        JsonNode items = c.path("seItems");
        String cn = Json.s(c, "seTypeCodeName", "" + cat);
        if (item < 0 || item >= items.size()) return cn + "/?" + item;
        JsonNode it = items.get(item);
        if (Json.i(it, "existFlag", 0) == 0) return cn + "/(空き" + item + ")";
        return cn + "/" + Json.s(it, "seItemName", "?");
    }

    public String voiceLabel(int ch, int item) {
        ArrayNode v = voiceList();
        if (ch < 0 || ch >= v.size()) return "voice?" + ch + "/" + item;
        JsonNode c = v.get(ch);
        JsonNode vs = c.path("voices");
        String cn = Json.s(c, "characterName", "" + ch);
        if (item < 0 || item >= vs.size()) return cn + "/?" + item;
        return cn + "「" + Json.s(vs.get(item), "voice", "?") + "」";
    }

    // ------------------------------------------------------------------ sound files (SE / voice preview)
    /** Base file name (no extension) of an SE item, or null. */
    public String seBaseName(int cat, int item) {
        ArrayNode se = seList();
        if (cat < 0 || cat >= se.size()) return null;
        JsonNode items = se.get(cat).path("seItems");
        if (item < 0 || item >= items.size() || Json.i(items.get(item), "existFlag", 0) == 0) return null;
        return Json.s(items.get(item), "seFileName", null);
    }

    public String voiceBaseName(int ch, int item) {
        ArrayNode v = voiceList();
        if (ch < 0 || ch >= v.size()) return null;
        JsonNode vs = v.get(ch).path("voices");
        if (item < 0 || item >= vs.size() || Json.i(vs.get(item), "existFlag", 0) == 0) return null;
        return Json.s(vs.get(item), "voiceFileName", null);
    }

    /** Resolves a sound base name to a file in the VFS (.ogg, then .wav, then as-is); null if absent (Se.pac / Voice.pac not mounted). */
    public String soundFile(String base) {
        if (base == null || base.isEmpty()) return null;
        for (String n : new String[]{base + ".ogg", base + ".wav", base})
            if (fs.exists(n)) return fs.originalName(n);
        return null;
    }

    // ------------------------------------------------------------------ spm / png (read-only, for preview)
    public Spm spm(String fileName) {
        if (fileName == null) return null;
        return spmCache.computeIfAbsent(fileName.toLowerCase(Locale.ROOT), k -> {
            try {
                byte[] b = fs.read(fileName);
                if (b == null) return Optional.empty();
                return Optional.of((Spm) codec.parse(fileName, b));
            } catch (Exception e) {
                log.add("spm 読み込み失敗 " + fileName + ": " + e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    public BufferedImage png(String name) {
        if (name == null) return null;
        String k = name.toLowerCase(Locale.ROOT);
        Optional<BufferedImage> o = pngCache.get(k);
        if (o == null) {
            BufferedImage img = null;
            try {
                byte[] b = fs.read(name);
                if (b != null) img = ImageIO.read(new ByteArrayInputStream(b));
            } catch (Exception e) {
                log.add("png 読み込み失敗 " + name + ": " + e);
            }
            o = Optional.ofNullable(img);
            pngCache.put(k, o);
        }
        return o.orElse(null);
    }

    private final Map<Integer, List<String>> skillNameCache = new ConcurrentHashMap<>();

    /** Skill names of a registered waz (from the loaded doc if any, else a quick binary parse; cached). */
    public List<String> skillNames(int wazIdx) {
        String f = wazFile(wazIdx);
        if (f == null) return null;
        FileDoc d = docIfLoaded(f);
        if (d != null) {
            List<String> l = new ArrayList<>();
            JsonNode sl = d.tree.path("skillList");
            for (int i = 0; i < sl.size(); i++) l.add(WazNav.skillName(d.tree, i));
            return l;
        }
        return skillNameCache.computeIfAbsent(wazIdx, k -> {
            List<String> l = new ArrayList<>();
            try {
                com.giga.nexas.dto.bsdx.waz.Waz w = (com.giga.nexas.dto.bsdx.waz.Waz) codec.parse(f, fs.read(f));
                for (var s : w.getSkillList()) {
                    String n = s == null ? null : s.getSkillNameJapanese();
                    l.add(n == null ? "(空)" : n.isEmpty() ? "(無名)" : n);
                }
            } catch (Exception e) {
                log.add("waz 読み込み失敗 " + f + ": " + e);
            }
            return l;
        });
    }

    // ------------------------------------------------------------------ reference index
    public RefIndex refIndex() { return refIndex; }

    public RefIndex buildRefIndex() {
        RefIndex r = new RefIndex(this);
        r.build();
        synchronized (this) { for (FileDoc d : docs.values()) if (d.ext.equals("waz")) indexed.put(d.lower, d.version); }
        refIndex = r;
        return r;
    }

    private final Map<String, Integer> indexed = new HashMap<>();

    /** Re-scans every loaded waz whose tree changed since it was last indexed (after edits / undo / redo). */
    public synchronized void syncRefIndex() {
        RefIndex r = refIndex;
        if (r == null) return;
        for (FileDoc d : docs.values()) {
            if (!d.ext.equals("waz")) continue;
            Integer v = indexed.get(d.lower);
            if (v != null && v == d.version) continue;
            int wi = wazIndexOfFile(d.name);
            if (wi >= 0 && (v != null || d.version != 0)) r.update(wi, d.tree);
            indexed.put(d.lower, d.version);
        }
    }

    /** The weapon equipment table (Config.pac WeaponEquip.dat) as an editable doc, or null if absent. */
    public FileDoc weaponEquip() {
        try { return fs.exists("WeaponEquip.dat") ? doc("WeaponEquip.dat") : null; } catch (IOException e) { log.add("WeaponEquip.dat: " + e); return null; }
    }
}
