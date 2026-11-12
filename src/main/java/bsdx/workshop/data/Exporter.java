package bsdx.workshop.data;

import bsdx.workshop.pac.PacArchive;
import bsdx.workshop.pac.PacWriter;
import bsdx.workshop.vfs.GameFs;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Validation (generate -> re-parse -> compare, mek_check rules) and Update3.pac export
 * (= every file of the overlay + every changed file).
 */
public final class Exporter {
    public static final class Plan {
        public final List<FileDoc> changed = new ArrayList<>();
        public final Map<String, byte[]> generated = new LinkedHashMap<>();   // original-case name -> bytes
        public final List<String> report = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public final List<MekCheck.Violation> blocking = new ArrayList<>();
        public final List<MekCheck.Violation> info = new ArrayList<>();
        public boolean ok() { return errors.isEmpty() && blocking.isEmpty(); }
    }

    private final Project p;
    private final boolean spmCheck;

    public Exporter(Project p, boolean spmCheck) { this.p = p; this.spmCheck = spmCheck; }

    /** Validates all changed documents. Never writes anything. */
    public Plan prepare() {
        Plan plan = new Plan();
        plan.changed.addAll(p.dirtyDocs());
        if (plan.changed.isEmpty()) plan.report.add("変更されたファイルはありません（オーバーレイの内容だけを書き出します）。");
        Map<String, JsonNode> baseTrees = new HashMap<>();
        for (FileDoc d : plan.changed) {
            try {
                byte[] gen = p.codec.treeToBytes(d.tree, d.ext);
                JsonNode re = p.codec.parseToTree(d.name, gen);
                List<String> diffs = Json.diff(d.tree, re, 10);
                if (!diffs.isEmpty()) { plan.errors.add(d.name + ": 生成→再パースで値が一致しません: " + diffs); continue; }
                byte[] gen2 = p.codec.treeToBytes(re, d.ext);
                if (!Arrays.equals(gen, gen2)) { plan.errors.add(d.name + ": 再生成のバイト列が一致しません"); continue; }
                JsonNode base = p.codec.parseToTree(d.name, d.originalBytes);
                baseTrees.put(d.lower, base);
                List<String> changes = Json.diff(base, re, 10000);
                Set<String> expected = d.changes().keySet();
                List<String> unexpected = new ArrayList<>();
                for (String c : changes) {
                    String ptr = c.substring(0, c.indexOf(':'));
                    boolean ok = false;
                    for (String e : expected) if (ptr.equals(e) || ptr.startsWith(e + "/")) { ok = true; break; }
                    if (!ok) unexpected.add(c);
                }
                if (!unexpected.isEmpty()) { plan.errors.add(d.name + ": 編集していない値が変化しました: " + unexpected.subList(0, Math.min(5, unexpected.size()))); continue; }
                plan.generated.put(d.name, gen);
                plan.report.add(String.format("%s: 生成→再パース一致 OK（%d B → %d B、変更 %d 箇所）", d.name, d.originalBytes.length, gen.length, changes.size()));
            } catch (Exception e) {
                plan.errors.add(d.name + ": 生成に失敗: " + e);
            }
        }
        // mek_check rules: changed meks, mechs whose own waz changed, shared waz on their own
        MekCheck mc = new MekCheck(p, spmCheck);
        Set<Integer> mechIds = new TreeSet<>();
        List<FileDoc> sharedWaz = new ArrayList<>();
        for (FileDoc d : plan.changed) {
            if (d.ext.equals("mek")) {
                for (Project.Mech m : p.mechs) if (m.mekFile != null && m.mekFile.equalsIgnoreCase(d.name)) mechIds.add(m.id);
            } else if (d.ext.equals("waz")) {
                int wi = p.wazIndexOfFile(d.name);
                List<Project.Mech> users = p.mechsUsingWaz(wi);
                if (users.isEmpty()) sharedWaz.add(d); else users.forEach(m -> mechIds.add(m.id));
            }
        }
        for (int id : mechIds) {
            Project.Mech m = p.mechs.get(id);
            try {
                JsonNode[] now = trees(m, false, baseTrees), base = trees(m, true, baseTrees);
                List<MekCheck.Violation> vb = mc.checkMech(base[0], base[1]);
                List<MekCheck.Violation> vn = mc.checkMech(now[0], now[1]);
                List<MekCheck.Violation> nv = MekCheck.newViolations(vb, vn);
                int blk = 0;
                for (MekCheck.Violation v : nv) { if (v.blocking()) { plan.blocking.add(v); blk++; } else plan.info.add(v); }
                plan.report.add(String.format("mek_check %s（%s）: 新たな違反 %d（元からある違反 %d は対象外）", m.mekFile, m.mekName, blk, vb.size()));
            } catch (Exception e) {
                plan.errors.add("mek_check " + m.mekFile + " 失敗: " + e);
            }
        }
        for (FileDoc d : sharedWaz) {
            int wi = p.wazIndexOfFile(d.name);
            if (wi < 0) { plan.warnings.add(d.name + " は WazaGroup に登録されていません（チェック省略）"); continue; }
            try {
                List<MekCheck.Violation> vb = mc.checkSharedWaz(baseTrees.get(d.lower), wi);
                List<MekCheck.Violation> vn = mc.checkSharedWaz(d.tree, wi);
                List<MekCheck.Violation> nv = MekCheck.newViolations(vb, vn);
                int blk = 0;
                for (MekCheck.Violation v : nv) { if (v.blocking()) { plan.blocking.add(v); blk++; } else plan.info.add(v); }
                plan.report.add(String.format("共通 waz チェック %s: 新たな違反 %d（元からある違反 %d は対象外）", d.name, blk, vb.size()));
                RefIndex r = p.refIndex();
                if (r != null) {
                    Set<String> users = new TreeSet<>();
                    for (String ptr : d.changes().keySet()) {
                        String[] t = ptr.split("/");
                        if (t.length > 2 && t[1].equals("skillList")) {
                            int sk = Integer.parseInt(t[2]);
                            for (RefIndex.Ref ref : r.referrers(wi, sk)) {
                                Project.Mech u = p.mechOfWaz(ref.srcWaz);
                                users.add(u != null ? u.mekName + "(" + u.name + ")" : p.wazLabel(ref.srcWaz));
                            }
                        }
                    }
                    if (!users.isEmpty()) plan.warnings.add(d.name + " の変更した技は次からも使われています: " + String.join(", ", users));
                }
            } catch (Exception e) {
                plan.errors.add("共通 waz チェック " + d.name + " 失敗: " + e);
            }
        }
        GameFs.Layer ov = p.fs.getOverlay();
        for (GameFs.Layer l : p.fs.layers()) {
            if (l == ov) continue;
            String lab = l.label().toLowerCase(Locale.ROOT);
            if (lab.matches("update[3-7]\\.pac"))
                plan.warnings.add("ゲームフォルダに " + l.label() + " があります（インストール済みの MOD）。書き出す Update3.pac には含まれません。含めたい場合はそれをオーバーレイとして開いてください。");
        }
        return plan;
    }

    private JsonNode[] trees(Project.Mech m, boolean base, Map<String, JsonNode> baseTrees) throws IOException {
        JsonNode mek = tree(m.mekFile, base, baseTrees);
        String wf = p.wazFile(Json.i(mek.path("mekBasicInfo"), "wazFileSequence", -1));
        JsonNode waz = wf == null ? null : tree(wf, base, baseTrees);
        return new JsonNode[]{mek, waz};
    }

    private JsonNode tree(String file, boolean base, Map<String, JsonNode> baseTrees) throws IOException {
        FileDoc d = p.docIfLoaded(file);
        if (d != null && !base) return d.tree;
        if (d != null && baseTrees.containsKey(d.lower)) return baseTrees.get(d.lower);
        if (d != null) return p.codec.parseToTree(d.name, d.originalBytes);
        return p.codec.parseToTree(file, p.fs.read(file));
    }

    /** Items of the output pac: overlay files first (original order), changed files replace/append. */
    public List<PacWriter.Item> items(Plan plan) throws IOException {
        LinkedHashMap<String, PacWriter.Item> m = new LinkedHashMap<>();
        GameFs.Layer ov = p.fs.getOverlay();
        if (ov != null) for (String n : ov.names()) m.put(n.toLowerCase(Locale.ROOT), new PacWriter.Item(n, ov.read(n.toLowerCase(Locale.ROOT))));
        for (Map.Entry<String, byte[]> e : plan.generated.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            PacWriter.Item prev = m.get(k);
            m.put(k, new PacWriter.Item(prev != null ? prev.name : e.getKey(), e.getValue()));
        }
        return new ArrayList<>(m.values());
    }

    /** Writes Update3.pac (+ change log) into {@code dir} after the guard check; verifies by reading back. */
    public Path write(Plan plan, Path dir, Settings s, List<UndoStack.Edit> history) throws IOException {
        if (!plan.ok()) throw new IOException("検証エラーがあるため書き出しません");
        String why = ExportGuard.check(dir, s);
        if (why != null) throw new IOException(why);
        Files.createDirectories(dir);
        why = ExportGuard.check(dir, s);   // re-check after creation (symlinks)
        if (why != null) throw new IOException(why);
        Path pac = dir.resolve("Update3.pac");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        if (Files.exists(pac)) Files.move(pac, dir.resolve("Update3.pac.bak_" + stamp), StandardCopyOption.REPLACE_EXISTING);
        List<PacWriter.Item> items = items(plan);
        if (items.isEmpty()) throw new IOException("書き出すファイルがありません");
        PacWriter.write(pac, items);
        try (PacArchive a = new PacArchive(pac)) {
            if (a.entries().size() != items.size()) throw new IOException("書き出し後の検証に失敗（件数）");
            for (PacWriter.Item it : items) {
                byte[] back = a.read(it.name);
                if (back == null || !Arrays.equals(back, it.data)) throw new IOException("書き出し後の検証に失敗: " + it.name);
            }
        }
        List<String> log = new ArrayList<>();
        log.add("BsdxWorkshop export " + stamp);
        log.add("pac: " + pac + " (" + items.size() + " files)");
        GameFs.Layer ov = p.fs.getOverlay();
        log.add("overlay: " + (ov == null ? "(なし)" : ov.label() + " " + ov.path()));
        log.add("");
        log.add("[検証]");
        log.addAll(plan.report);
        for (String w : plan.warnings) log.add("注意: " + w);
        for (MekCheck.Violation v : plan.info) log.add("情報: " + v);
        log.add("");
        log.add("[変更した値]");
        for (FileDoc d : plan.changed) {
            log.add("== " + d.name + " (元: " + d.sourceLabel + ")");
            d.changes().forEach((k, v) -> log.add("  " + k + " : " + v));
        }
        if (history != null && !history.isEmpty()) {
            log.add("");
            log.add("[操作履歴]");
            for (UndoStack.Edit e : history) log.add("  " + e.label);
        }
        log.add("");
        log.add("[pac の中身]");
        for (PacWriter.Item it : items) log.add("  " + it.name + " " + it.data.length + (plan.generated.containsKey(it.name) ? "  *変更" : ""));
        Files.write(dir.resolve("Update3_changes.txt"), log, StandardCharsets.UTF_8);
        return pac;
    }
}
