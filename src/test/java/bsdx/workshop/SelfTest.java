package bsdx.workshop;

import bsdx.workshop.data.*;
import bsdx.workshop.pac.Huffman;
import bsdx.workshop.pac.PacArchive;
import bsdx.workshop.pac.PacWriter;
import bsdx.workshop.vfs.GameFs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Headless integration tests (no JUnit available in the cloud). Run: build_gui.sh test
 * Data: staged pacs (-Dstage), python-extracted files (-Dextracted), a mod pac for the overlay test (-Doverlay).
 */
public class SelfTest {
    static final Path STAGE = Path.of(System.getProperty("stage", "game"));
    static final Path EXTRACTED = Path.of(System.getProperty("extracted", "/home/claude/proj/work/extracted"));
    static final Path OVERLAY = Path.of(System.getProperty("overlay", "/home/claude/proj/mods/phase3b_tooru/Update3.pac"));
    static final Path WORK = Path.of(System.getProperty("work", "/home/claude/proj/work/gui_test"));
    static int pass, fail;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String what) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; failures.add(what); System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) throws Exception {
        Set<String> only = new HashSet<>(Arrays.asList(args));
        long t0 = System.currentTimeMillis();
        Files.createDirectories(WORK);
        if (only.isEmpty() || only.contains("huffman")) testHuffman();
        if (only.isEmpty() || only.contains("pac")) testPacs();
        Codec codec = new Codec();
        Fields fields = Fields.load(codec.mapper);
        check(fields.slots.size() == 72 && fields.classes.size() >= 30, "waz_fields.json loaded: slots=" + fields.slots.size() + " classes=" + fields.classes.size());
        if (only.isEmpty() || only.contains("load")) testLoadAll(codec, fields);
        if (only.isEmpty() || only.contains("export")) testKouExport(codec, fields);
        if (only.isEmpty() || only.contains("overlay")) testOverlayExport(codec, fields);
        if (only.isEmpty() || only.contains("guard")) testGuard();
        if (only.isEmpty() || only.contains("check")) testMekCheck(codec, fields);
        if (only.isEmpty() || only.contains("struct")) StructTest.run(codec, fields, STAGE);
        if (only.isEmpty() || only.contains("audio")) AudioTest.run(codec, fields, STAGE, OVERLAY);
        if (only.isEmpty() || only.contains("ui")) UiSmokeTest.run(codec, fields, STAGE, OVERLAY);
        if (only.isEmpty() || only.contains("timeline")) TimelineTest.run(codec, fields, STAGE, OVERLAY);
        System.out.printf("%nSelfTest: %d passed, %d failed (%.1fs)%n", pass + UiSmokeTest.pass, fail + UiSmokeTest.fail, (System.currentTimeMillis() - t0) / 1000.0);
        failures.addAll(UiSmokeTest.failures);
        for (String f : failures) System.out.println("  FAILED: " + f);
        System.exit(fail + UiSmokeTest.fail == 0 ? 0 : 1);
    }

    static void testHuffman() {
        System.out.println("[huffman]");
        Random r = new Random(1);
        boolean ok = true;
        for (int n : new int[]{0, 1, 2, 7, 100, 5000, 76 * 411}) {
            byte[] d = new byte[n];
            for (int i = 0; i < n; i++) d[i] = (byte) (n % 3 == 0 ? r.nextInt(256) : r.nextInt(5) * 7);
            byte[] back = Huffman.decode(Huffman.encode(d), n);
            ok &= Arrays.equals(d, back);
        }
        byte[] same = new byte[300];
        Arrays.fill(same, (byte) 42);
        ok &= Arrays.equals(same, Huffman.decode(Huffman.encode(same), 300));
        check(ok, "huffman encode/decode round trip (random, single-symbol)");
    }

    static void testPacs() throws IOException {
        System.out.println("[pac read/write round trip on staged pacs]");
        List<Path> pacs = new ArrayList<>();
        try (Stream<Path> s = Files.list(STAGE)) { s.filter(p -> p.toString().toLowerCase().endsWith(".pac")).sorted().forEach(pacs::add); }
        check(!pacs.isEmpty(), "staged pacs found: " + pacs.size());
        for (Path p : pacs) {
            String base = p.getFileName().toString().replaceAll("(?i)\\.pac$", "");
            Path ex = EXTRACTED.resolve(base);
            List<PacWriter.Item> items = new ArrayList<>();
            int same = 0, cmp = 0;
            long sz = 0;
            try (PacArchive a = new PacArchive(p)) {
                for (PacArchive.Entry e : a.entries()) {
                    byte[] d = a.read(e);
                    if (d.length != e.usize) throw new IOException("size mismatch " + e.name);
                    items.add(new PacWriter.Item(e.name, d));
                    sz += d.length;
                    Path f = ex.resolve(e.name);
                    if (Files.isRegularFile(f)) { cmp++; if (Arrays.equals(Files.readAllBytes(f), d)) same++; }
                }
                check(cmp == 0 || same == cmp, base + ": " + a.entries().size() + " entries read; identical to python-extracted files " + same + "/" + cmp);
                Path out = WORK.resolve(base + "_rt.pac");
                PacWriter.write(out, items);
                try (PacArchive b = new PacArchive(out)) {
                    boolean ok = b.entries().size() == items.size();
                    for (int i = 0; ok && i < items.size(); i++) {
                        ok = b.entries().get(i).name.equals(items.get(i).name) && Arrays.equals(b.read(b.entries().get(i)), items.get(i).data);
                    }
                    // header+body identical to the original (only the Huffman index may differ by tie breaks)
                    PacArchive.Entry last = a.entries().get(a.entries().size() - 1);
                    long bodyEnd = last.offset + last.psize;
                    boolean body = bodyEqual(p, out, bodyEnd);
                    check(ok, base + ": rewrite -> re-read all " + items.size() + " entries equal (" + sz / 1024 + " KiB)");
                    check(body, base + ": rewritten header+data section byte-identical to the original");
                }
                Files.deleteIfExists(out);
            }
        }
    }

    static boolean bodyEqual(Path a, Path b, long n) throws IOException {
        try (RandomAccessFile x = new RandomAccessFile(a.toFile(), "r"); RandomAccessFile y = new RandomAccessFile(b.toFile(), "r")) {
            byte[] bx = new byte[1 << 20], by = new byte[1 << 20];
            long pos = 4;   // byte 3 is a garbage byte in originals
            x.seek(4); y.seek(4);
            while (pos < n) {
                int k = (int) Math.min(bx.length, n - pos);
                x.readFully(bx, 0, k); y.readFully(by, 0, k);
                if (!Arrays.equals(bx, 0, k, by, 0, k)) return false;
                pos += k;
            }
            return true;
        }
    }

    static Project open(Codec codec, Fields fields, Path overlay) throws IOException {
        return Loader.open(codec, fields, STAGE, true, overlay, null);
    }

    static void testLoadAll(Codec codec, Fields fields) throws IOException {
        System.out.println("[load all mek + waz through the VFS, JSON round trip]");
        Project p = open(codec, fields, null);
        check(p.mechs.size() == 103, "MekaGroup: " + p.mechs.size() + " mechs; Kou=" + p.mechs.get(0).mekName + "/" + p.mechs.get(0).pilot);
        int mek = 0, waz = 0, bad = 0;
        long t = System.currentTimeMillis();
        for (String ext : new String[]{"mek", "waz"}) {
            for (String name : p.fs.list(ext).values()) {
                byte[] b = p.fs.read(name);
                JsonNode tree = codec.parseToTree(name, b);
                byte[] g = codec.treeToBytes(tree, ext);
                if (!Arrays.equals(b, g)) { bad++; System.out.println("    mismatch " + name); }
                if (ext.equals("mek")) mek++; else waz++;
            }
        }
        check(bad == 0 && mek >= 104 && waz >= 110, String.format("parse->JSON->generate byte-identical: mek %d, waz %d, mismatches %d (%.1fs)", mek, waz, bad, (System.currentTimeMillis() - t) / 1000.0));
        int withMek = 0;
        for (Project.Mech m : p.mechs) if (m.mekFile != null && p.wazFile(m.wazIdx) != null) withMek++;
        check(withMek >= 100, "mechs with mek + registered waz: " + withMek);
        RefIndex r = p.buildRefIndex();
        check(r.wazScanned >= 110 && r.errors.isEmpty(), "RefIndex: waz scanned " + r.wazScanned + ", refs " + r.refCount + ", " + r.millis + "ms, errors " + r.errors.size());
        // Kou's rifle bullet should be referenced from Kou.waz
        JsonNode kouWaz = p.doc(p.wazFile(p.mechs.get(0).wazIdx)).tree;
        int effRefs = 0;
        for (int si = 0; si < kouWaz.path("skillList").size(); si++)
            for (WazNav.Ev e : WazNav.events(kouWaz, si, -1))
                if (e.typeId() == 19) {
                    String sp = WazNav.subPtr(kouWaz, e.ptr, 3);
                    if (sp != null) {
                        JsonNode ws = kouWaz.at(sp);
                        if (!r.referrers(ws.path("wazFileNo").asInt(), ws.path("wazSequenceNo").asInt()).isEmpty()) effRefs++;
                    }
                }
        check(effRefs > 0, "Kou projectile events resolvable to referrer lists: " + effRefs);
        p.fs.close();
    }

    static Path exportDir(String name) throws IOException {
        Path root = WORK.resolve("PROJECT");
        Path d = root.resolve("mods").resolve(name);
        if (Files.exists(d)) try (Stream<Path> s = Files.walk(d)) { s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
        return d;
    }

    static Settings testSettings() {
        Settings s = new Settings(null);
        s.gameDir = STAGE.toString();
        s.projectRoot = WORK.resolve("PROJECT").toString();
        return s;
    }

    static void testKouExport(Codec codec, Fields fields) throws IOException {
        System.out.println("[edit Kou damage -> export -> verify]");
        Project p = open(codec, fields, null);
        Project.Mech kou = p.mechs.get(0);
        FileDoc mek = p.doc(kou.mekFile);
        JsonNode w0 = mek.tree.path("mekWeaponInfoMap").path("0");
        int si = w0.path("wazSequence").asInt();
        FileDoc waz = p.doc(p.wazFile(kou.wazIdx));
        WazNav.Ev hit = WazNav.firstHit(waz.tree, si);
        String dp = WazNav.damagePtr(waz.tree, hit.ptr);
        int before = waz.tree.at(dp).asInt();
        check(before == 50, "Kou weapon 0 「" + w0.path("weaponName").asText() + "」 skill " + si + " damage = " + before + " (" + dp + ")");
        p.undo.set(waz, dp, IntNode.valueOf(123), "威力 50→123");
        check(waz.isDirty() && waz.tree.at(dp).asInt() == 123, "edit applied, doc dirty");
        p.undo.undo();
        check(!waz.isDirty() && waz.tree.at(dp).asInt() == 50, "undo restores 50, doc clean");
        p.undo.redo();
        check(waz.tree.at(dp).asInt() == 123, "redo re-applies 123");
        Exporter ex = new Exporter(p, true);
        Exporter.Plan plan = ex.prepare();
        plan.report.forEach(x -> System.out.println("    " + x));
        plan.warnings.forEach(x -> System.out.println("    注意: " + x));
        check(plan.ok() && plan.changed.size() == 1, "validation OK (errors " + plan.errors + ", blocking " + plan.blocking + ")");
        Path dir = exportDir("gui_out_kou");
        Path pac = ex.write(plan, dir, testSettings(), p.undo.history());
        try (PacArchive a = new PacArchive(pac)) {
            check(a.entries().size() == 1 && a.entries().get(0).name.equalsIgnoreCase("Kou.waz"), "Update3.pac contains only Kou.waz: " + a.entries());
            byte[] nb = a.read(a.entries().get(0));
            byte[] ob = waz.originalBytes;
            int diffBytes = 0, first = -1, last = -1;
            for (int i = 0; i < Math.min(nb.length, ob.length); i++) if (nb[i] != ob[i]) { diffBytes++; if (first < 0) first = i; last = i; }
            check(nb.length == ob.length && diffBytes >= 1 && last - first < 4, "byte diff vs original Kou.waz: same length, " + diffBytes + " byte(s) differ within one int at 0x" + Integer.toHexString(first));
            JsonNode re = codec.parseToTree("Kou.waz", nb);
            JsonNode orig = codec.parseToTree("Kou.waz", ob);
            List<String> d = Json.diff(orig, re, 50);
            check(d.size() == 1 && d.get(0).startsWith(dp + ":") && re.at(dp).asInt() == 123, "re-parse: exactly one changed value " + d);
            // the vanilla Kou.waz is still what the VFS serves (game pacs untouched)
            check(Arrays.equals(p.fs.read("Kou.waz"), ob), "game pacs unchanged (read-only)");
        }
        check(Files.isRegularFile(dir.resolve("Update3_changes.txt")), "change log written");
        // python interop: tools/nexaspac.py can read our pac
        try {
            Process pr = new ProcessBuilder("python3", "/home/claude/proj/tools/nexaspac.py", "list", pac.toString()).redirectErrorStream(true).start();
            String outp = new String(pr.getInputStream().readAllBytes());
            check(pr.waitFor() == 0 && outp.contains("Kou.waz"), "tools/nexaspac.py lists the exported pac");
        } catch (Exception e) {
            System.out.println("    (python check skipped: " + e + ")");
        }
        p.fs.close();
    }

    static void testOverlayExport(Codec codec, Fields fields) throws IOException {
        System.out.println("[overlay (mod pac) + edit -> export keeps overlay files]");
        if (!Files.isRegularFile(OVERLAY)) { System.out.println("  (skip: no overlay " + OVERLAY + ")"); return; }
        Project p = open(codec, fields, OVERLAY);
        Map<String, byte[]> ov = new LinkedHashMap<>();
        try (PacArchive a = new PacArchive(OVERLAY)) { for (PacArchive.Entry e : a.entries()) ov.put(e.name, a.read(e)); }
        Project.Mech m = null;
        for (Project.Mech x : p.mechs) if (x.mekFile != null && p.fs.layerOf(x.mekFile) == p.fs.getOverlay()) { m = x; break; }
        check(m != null, "overlay mech found: " + (m == null ? "-" : m.id + " " + m.mekFile + " " + m.mekName + "/" + m.pilot));
        if (m == null) return;
        FileDoc mek = p.doc(m.mekFile);
        FileDoc waz = p.doc(p.wazFile(m.wazIdx));
        check(waz.sourceLabel.startsWith("オーバーレイ"), "waz comes from overlay: " + waz.name + " (" + waz.sourceLabel + ")");
        // edit damage of the first weapon with a hit, plus mek durability
        String dp = null;
        for (Iterator<JsonNode> it = mek.tree.path("mekWeaponInfoMap").elements(); it.hasNext() && dp == null; ) {
            JsonNode w = it.next();
            WazNav.Ev h = WazNav.firstHit(waz.tree, w.path("wazSequence").asInt());
            if (h != null) dp = WazNav.damagePtr(waz.tree, h.ptr);
        }
        check(dp != null, "damage pointer in overlay waz: " + dp);
        int v = waz.tree.at(dp).asInt();
        p.undo.set(waz, dp, IntNode.valueOf(v + 11), "威力");
        p.undo.set(mek, "/mekBasicInfo/baseHealth", IntNode.valueOf(mek.tree.at("/mekBasicInfo/baseHealth").asInt() + 1), "耐久");
        Exporter ex = new Exporter(p, true);
        Exporter.Plan plan = ex.prepare();
        plan.report.forEach(x -> System.out.println("    " + x));
        check(plan.ok() && plan.changed.size() == 2, "validation OK for 2 changed files (errors " + plan.errors + ", blocking " + plan.blocking + ")");
        Path pac = ex.write(plan, exportDir("gui_out_overlay"), testSettings(), p.undo.history());
        try (PacArchive a = new PacArchive(pac)) {
            int same = 0, changed = 0;
            for (PacArchive.Entry e : a.entries()) {
                byte[] d = a.read(e);
                if (Arrays.equals(d, ov.get(e.name))) same++; else changed++;
            }
            check(a.entries().size() == ov.size() && changed == 2 && same == ov.size() - 2,
                    "pac = overlay files (" + ov.size() + "): unchanged " + same + ", changed " + changed);
            JsonNode re = codec.parseToTree(waz.name, a.read(waz.name));
            List<String> d = Json.diff(codec.parseToTree(waz.name, ov.get(waz.name)), re, 10);
            check(d.size() == 1 && d.get(0).startsWith(dp), "overlay waz diff limited to the edit: " + d);
        }
        p.fs.close();
    }

    static void testGuard() {
        System.out.println("[export path guard]");
        Settings s = testSettings();
        check(ExportGuard.check(STAGE, s) != null, "refuses the game folder: " + ExportGuard.check(STAGE, s).split("\n")[0]);
        check(ExportGuard.check(STAGE.resolve("sub"), s) != null, "refuses a folder inside the game folder");
        check(ExportGuard.check(Path.of("/tmp/elsewhere"), s) != null, "refuses outside PROJECT by default");
        check(ExportGuard.check(Path.of(s.projectRoot, "mods", "gui_out"), s) == null, "accepts PROJECT/mods/gui_out");
        s.allowOutsideProject = true;
        check(ExportGuard.check(Path.of("/tmp/elsewhere"), s) == null, "accepts outside PROJECT only with explicit setting");
        check(ExportGuard.check(STAGE, s) != null, "game folder refused even with the setting");
    }

    static void testMekCheck(Codec codec, Fields fields) throws IOException {
        System.out.println("[mek_check port]");
        Project p = open(codec, fields, null);
        MekCheck mc = new MekCheck(p, false);
        int mechs = 0;
        Map<String, Integer> codes = new TreeMap<>();
        for (Project.Mech m : p.mechs) {
            if (m.mekFile == null || p.wazFile(m.wazIdx) == null) continue;
            JsonNode mek = codec.parseToTree(m.mekFile, p.fs.read(m.mekFile));
            String wf = p.wazFile(m.wazIdx);
            JsonNode waz = codec.parseToTree(wf, p.fs.read(wf));
            for (MekCheck.Violation v : mc.checkMech(mek, waz)) codes.merge(v.code, 1, Integer::sum);
            mechs++;
        }
        System.out.println("    vanilla violations by code over " + mechs + " mechs: " + codes);
        check(mechs >= 100 && !codes.containsKey("A1") && !codes.containsKey("M1"), "vanilla mechs: no A1/M1 violations");
        // inject the 蒼-type crash: AI attack weapon index out of range -> export must be blocked
        Project.Mech kou = p.mechs.get(0);
        FileDoc mek = p.doc(kou.mekFile);
        String ptr = null;
        JsonNode ai = mek.tree.path("mekAiInfoList");
        outer:
        for (int a = 0; a < ai.size(); a++) {
            JsonNode ev = ai.get(a).path("cpuEventList");
            for (int j = 0; j < ev.size(); j++) if (ev.get(j).path("type").asInt() == 2) { ptr = "/mekAiInfoList/" + a + "/cpuEventList/" + j + "/mekWeaponInfoMapNo"; break outer; }
        }
        p.undo.set(mek, ptr, IntNode.valueOf(999), "AI weapon 999");
        Exporter.Plan plan = new Exporter(p, false).prepare();
        check(!plan.ok() && plan.blocking.stream().anyMatch(v -> v.code.equals("A1")), "AI weapon index 999 blocks export: " + plan.blocking);
        p.undo.undo();
        // SE out of range in waz -> R2 blocks
        FileDoc waz = p.doc(p.wazFile(kou.wazIdx));
        String sePtr = null;
        for (WazNav.Ev e : WazNav.events(waz.tree, 12, -1)) if (e.typeId() == 23) { sePtr = e.ptr + "/byteDataList/0"; break; }
        int[] se = Json.bytesToInts(waz.tree.at(sePtr), 4);
        se[1] = 9999;
        p.undo.set(waz, sePtr, Json.intsToBytes(se), "SE");
        plan = new Exporter(p, false).prepare();
        check(!plan.ok() && plan.blocking.stream().anyMatch(v -> v.code.equals("R2")), "SE item 9999 blocks export: " + plan.blocking);
        p.undo.undo();
        plan = new Exporter(p, false).prepare();
        check(plan.ok() && plan.changed.isEmpty(), "after undo nothing to export, no violations");
        p.fs.close();
    }
}
