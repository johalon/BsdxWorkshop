package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** tools/waz_fields.json (notes/05) loaded for schema-driven forms. */
public final class Fields {
    public static final class Field {
        public final String key, nameJa, type, semantics, confidence, unit;
        public final boolean editable;
        /** v4: user-experience explanation (waz_terms_ux.json) and its confidence (確定/推定/不明); "" if none. */
        public String ux = "", uxConf = "";
        Field(JsonNode n) {
            key = n.path("key").asText();
            nameJa = n.path("name_ja").asText(key);
            type = n.path("type").asText("int32");
            semantics = n.path("semantics").asText("");
            confidence = n.path("confidence").asText("UNKNOWN");
            unit = n.path("unit").asText("");
            editable = n.path("editable").asBoolean(true);
        }
        public boolean isScalarKey() { return key.matches("[A-Za-z0-9_]+"); }
        public boolean confirmed() { return "CONFIRMED".equals(confidence); }
        public boolean likely() { return "LIKELY".equals(confidence); }
        public boolean unknown() { return !confirmed() && !likely(); }
        /** "0=直値, 1=前の値±" -> {0:"直値",1:"前の値±"} (best effort; empty if no pattern). */
        public LinkedHashMap<Integer, String> enumLabels() {
            if (type.contains("bitmask")) return new LinkedHashMap<>();
            Legend l = legend();
            if (l.special || l.values.size() < 2) return new LinkedHashMap<>();
            if (!l.guessed) return l.values;
            LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
            l.values.forEach((k, v) -> m.put(k, v + "（推定）"));
            return m;
        }
        private Legend legend;
        /** v3: meaning of each value (enum / bool / bitmask / special values) for the inline legend. */
        public Legend legend() { if (legend == null) legend = Legend.of(type, semantics, confidence); return legend; }
    }

    /**
     * v3: value meanings parsed from the dictionary text, shown inline next to the editor.
     * guessed = the dictionary marks the field LIKELY/UNKNOWN, or the numbers were inferred from the order of a list.
     */
    public static final class Legend {
        public final LinkedHashMap<Integer, String> values = new LinkedHashMap<>();
        /** values are bit numbers (bitmask). */
        public boolean bits;
        /** only some special values are named (plain int fields such as "-1=なし"). */
        public boolean special;
        public boolean guessed;
        /** extra remark (e.g. "各ビットの意味は未解析"). */
        public String note = "";

        public boolean isEmpty() { return values.isEmpty() && note.isEmpty(); }

        static Legend of(String type, String sem, String confidence) {
            Legend l = new Legend();
            sem = sem == null ? "" : sem.trim();
            l.guessed = !"CONFIRMED".equals(confidence);
            boolean enumT = type.contains("enum"), bitT = type.contains("bitmask"), boolT = type.contains("bool");
            if (bitT) {
                l.bits = true;
                Matcher m = BIT.matcher(sem);
                while (m.find()) l.values.putIfAbsent(Integer.parseInt(m.group(1)), m.group(2).trim());
                if (l.values.isEmpty() && sem.contains("/") && !sem.matches(".*\\d.*")) {
                    String[] a = sem.split("/");
                    for (int i = 0; i < a.length; i++) if (!a[i].isBlank()) l.values.put(i, a[i].trim());
                    l.guessed = true;
                    l.note = "ビット番号は並び順からの推定";
                }
                if (l.values.isEmpty()) l.note = sem.contains("未解析") ? sem : "各ビットの意味は未解析" + (sem.isEmpty() ? "" : "（" + sem + "）");
                return l;
            }
            if (boolT) {
                Matcher m = Pattern.compile("1\\s*=\\s*([^,，、;；]+)").matcher(sem);
                l.values.put(0, "OFF");
                l.values.put(1, m.find() ? m.group(1).trim() : "ON");
                return l;
            }
            LinkedHashMap<Integer, String> eq = parseEnum(sem, 1);
            if (enumT) {
                if (eq.size() >= 2) { l.values.putAll(eq); return l; }
                LinkedHashMap<Integer, String> ws = new LinkedHashMap<>();
                Matcher m = WS_ENUM.matcher(sem);
                while (m.find()) ws.putIfAbsent(Integer.parseInt(m.group(1)), m.group(2).trim());
                if (ws.size() >= 2) { l.values.putAll(ws); return l; }
                if (sem.contains("/") && !sem.matches(".*\\d.*")) {
                    String[] a = sem.split("/");
                    for (int i = 0; i < a.length; i++) if (!a[i].isBlank()) l.values.put(i, a[i].trim());
                    l.guessed = true;
                    l.note = "番号は並び順からの推定";
                    return l;
                }
                l.note = "値の意味は未解析" + (sem.isEmpty() ? "" : "（値の範囲: " + sem + "）");
                return l;
            }
            // plain number with named special values ("-1=なし"); examples ("例 0=立ち") are not legends
            if (!eq.isEmpty() && !sem.contains("例")) { l.values.putAll(eq); l.special = true; }
            return l;
        }

        /** "0=直値 / 1=前の値±" or "bit0=敵 / bit1=味方". */
        public String legendText() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, String> e : values.entrySet()) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(bits ? "bit" + e.getKey() + "(" + (1L << e.getKey()) + ")" : String.valueOf(e.getKey())).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        /** Meaning of the current value v ("敵 + 自分", "直値", "未定義の値"), or "" when nothing is known. */
        public String describe(long v) {
            if (bits) {
                if (values.isEmpty()) return "";
                if (v == 0) return "なし（0）";
                List<String> on = new ArrayList<>();
                for (int b = 0; b < 32; b++) if ((v >> b & 1) != 0) on.add(values.containsKey(b) ? values.get(b) : "bit" + b + "(意味不明)");
                return String.join(" + ", on);
            }
            String n = values.get((int) v);
            if (n != null) return n;
            return special ? "" : values.isEmpty() ? "" : "一覧に無い値";
        }
    }

    public static final class Sub {
        public final int index; public final String nameJa, cls, semantics, confidence;
        public String ux = "", uxConf = "";
        Sub(JsonNode n) {
            index = n.path("index").asInt();
            nameJa = n.path("name_ja").asText("#" + index);
            cls = n.path("cls").asText("");
            semantics = n.path("semantics").asText("");
            confidence = n.path("confidence").asText("UNKNOWN");
        }
    }

    public static final class ClassSpec {
        public final String name, summary;
        public final List<Field> fields = new ArrayList<>();
        public final Map<Integer, Sub> subs = new TreeMap<>();
        ClassSpec(String name, JsonNode n) {
            this.name = name;
            summary = n.path("summary").asText("");
            for (JsonNode f : n.path("fields")) fields.add(new Field(f));
            for (JsonNode s : n.path("subobjects")) { Sub x = new Sub(s); subs.put(x.index, x); }
        }
        public Field field(String key) { for (Field f : fields) if (f.key.equals(key)) return f; return null; }
    }

    public static final class Slot {
        public final int slot, typeId; public final String nameJa, cls, semantics, confidence, mainValue;
        public String ux = "", uxConf = "";
        Slot(JsonNode n) {
            slot = n.path("slot").asInt();
            typeId = n.path("typeId").asInt(-1);
            nameJa = n.path("name_ja").asText("slot" + slot);
            cls = n.path("cls").asText("");
            semantics = n.path("semantics").asText("");
            confidence = n.path("confidence").asText("UNKNOWN");
            mainValue = n.path("main_value").asText("");
        }
    }

    public final Map<Integer, Slot> slots = new TreeMap<>();
    public final Map<String, ClassSpec> classes = new LinkedHashMap<>();
    public final Map<Integer, String> classByType = new HashMap<>();
    public final List<Field> mekWeapon = new ArrayList<>();
    public final String format;

    public Fields(JsonNode root) {
        format = root.path("format").asText("");
        for (JsonNode s : root.path("slots")) { Slot x = new Slot(s); slots.put(x.slot, x); }
        Iterator<Map.Entry<String, JsonNode>> it = root.path("classes").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            ClassSpec c = new ClassSpec(e.getKey(), e.getValue());
            classes.put(e.getKey(), c);
            for (JsonNode t : e.getValue().path("type_ids")) classByType.put(t.asInt(), e.getKey());
        }
        for (JsonNode f : root.path("mek_weapon")) mekWeapon.add(new Field(f));
    }

    public static Fields load(ObjectMapper m) throws IOException {
        Fields f;
        String ext = System.getProperty("bsdx.fields");
        if (ext != null && Files.isRegularFile(Path.of(ext))) f = new Fields(m.readTree(Files.readAllBytes(Path.of(ext))));
        else try (InputStream in = Fields.class.getResourceAsStream("/bsdx/workshop/waz_fields.json")) {
            if (in == null) throw new IOException("waz_fields.json resource missing");
            f = new Fields(m.readTree(in));
        }
        // v4: user-experience glossary (tools/waz_terms_ux.json, notes/41). Optional: the tool works without it.
        String terms = System.getProperty("bsdx.terms");
        try {
            if (terms != null && Files.isRegularFile(Path.of(terms))) f.applyUx(m.readTree(Files.readAllBytes(Path.of(terms))));
            else try (InputStream in = Fields.class.getResourceAsStream("/bsdx/workshop/waz_terms_ux.json")) {
                if (in != null) f.applyUx(m.readTree(in));
            }
        } catch (IOException e) {
            System.err.println("waz_terms_ux.json: " + e.getMessage());
        }
        return f;
    }

    /** v4: attaches the UX texts (slots / fields "Class.key" / subs "Class#index" / weapon) to the dictionary entries. */
    public void applyUx(JsonNode root) {
        for (Slot s : slots.values()) {
            JsonNode n = root.path("slots").path(String.valueOf(s.slot));
            s.ux = n.path("ux").asText(""); s.uxConf = n.path("conf").asText("");
        }
        for (ClassSpec c : classes.values()) {
            for (Field f : c.fields) {
                JsonNode n = root.path("fields").path(c.name + "." + f.key);
                f.ux = n.path("ux").asText(""); f.uxConf = n.path("conf").asText("");
            }
            for (Sub s : c.subs.values()) {
                JsonNode n = root.path("subs").path(c.name + "#" + s.index);
                if (n.isMissingNode()) n = slotByName(root, s.nameJa);   // e.g. 発射のサブ「ﾍﾞｸﾄﾙ：速度」 = slot 18 の説明
                s.ux = n.path("ux").asText(""); s.uxConf = n.path("conf").asText("");
            }
        }
        for (Field f : mekWeapon) {
            JsonNode n = root.path("weapon").path(f.key);
            f.ux = n.path("ux").asText(""); f.uxConf = n.path("conf").asText("");
        }
    }

    private JsonNode slotByName(JsonNode root, String nameJa) {
        for (Slot s : slots.values())
            if (s.nameJa.equals(nameJa)) {
                JsonNode n = root.path("slots").path(String.valueOf(s.slot));
                if (!n.isMissingNode()) return n;
            }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    /** v4: tooltip text = 体感 (UX) first, then the technical note. Plain text, "\n" = line break. */
    public static String tip(String ux, String uxConf, String tech) {
        StringBuilder sb = new StringBuilder();
        if (ux != null && !ux.isEmpty()) sb.append("【体感】").append(ux).append(uxConf == null || uxConf.isEmpty() ? "" : "（" + uxConf + "）");
        if (tech != null && !tech.isBlank()) { if (sb.length() > 0) sb.append("\n\n"); sb.append("【技術メモ】").append(tech.trim()); }
        return sb.toString();
    }

    /** v4: tooltip for a slot ([n] 名前 + 体感 + 技術メモ). */
    public String slotTip(int slot) {
        Slot s = slots.get(slot);
        if (s == null) return "[" + slot + "]";
        return "[" + slot + "] " + s.nameJa + "\n" + tip(s.ux, s.uxConf, s.semantics);
    }

    public ClassSpec classOf(int typeId) {
        String n = classByType.get(typeId);
        return n == null ? null : classes.get(n);
    }

    public String slotName(int slot) {
        Slot s = slots.get(slot);
        return s == null ? "slot" + slot : s.nameJa;
    }

    private static final Pattern BIT = Pattern.compile("bit\\s*(\\d+)\\s*[=:：]\\s*([^,，、/;；()（）]+)");
    /** "0通常 1塗りつぶし 3フィルム" (number directly followed by the name, whitespace separated). */
    private static final Pattern WS_ENUM = Pattern.compile("(?<![A-Za-z0-9+\\-])(-?\\d+)\\s?([^\\s\\d,，、/=:：()（）][^\\s,，、/]*)");
    private static final Pattern ENUM = Pattern.compile("(?<![A-Za-z0-9+])(-?\\d+)\\s*[=:：]\\s*([^,，、/;；()（）\\s][^,，、/;；]*)");

    static LinkedHashMap<Integer, String> parseEnum(String s) { return parseEnum(s, 2); }

    private static int count(String s, char a, char b) { int n = 0; for (char c : s.toCharArray()) if (c == a || c == b) n++; return n; }

    static LinkedHashMap<Integer, String> parseEnum(String s, int min) {
        LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
        if (s == null) return m;
        Matcher mt = ENUM.matcher(s);
        while (mt.find()) {
            try {
                String label = mt.group(2).trim();
                int dot = label.indexOf('。');
                if (dot > 0) label = label.substring(0, dot);
                while ((label.endsWith(")") || label.endsWith("）")) && count(label, '(', '（') < count(label, ')', '）')) label = label.substring(0, label.length() - 1).trim();
                m.putIfAbsent(Integer.parseInt(mt.group(1)), label);
            } catch (NumberFormatException ignored) {}
        }
        return m.size() >= min ? m : new LinkedHashMap<>();
    }
}
