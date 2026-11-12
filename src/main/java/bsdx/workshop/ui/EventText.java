package bsdx.workshop.ui;

import bsdx.workshop.data.Json;
import bsdx.workshop.data.Project;
import bsdx.workshop.data.WazNav;
import com.fasterxml.jackson.databind.JsonNode;
import com.giga.nexas.dto.bsdx.spm.Spm;

import java.util.ArrayList;
import java.util.List;

/** Human readable one-line summaries (port of the relevant parts of tools/waz_summary.py). */
public final class EventText {
    private EventText() {}

    public static String termText(Project p, JsonNode b) {
        if (b == null || !b.isObject()) return "";
        int g = b.path("int1").asInt(-1);
        List<String> out = new ArrayList<>();
        JsonNode vals = b.path("paramList");
        int vi = 0;
        boolean dead = false;
        for (JsonNode tokN : b.path("typeList")) {
            int tok = tokN.asInt();
            if (dead || g < 0 || g >= p.termList().size()) { out.add("#" + tok); dead = true; continue; }
            JsonNode items = p.termList().get(g).path("termItemList");
            if (tok < 0 || tok >= items.size()) { out.add(Json.s(p.termList().get(g), "termGroupCodeName", "") + "#" + tok); dead = true; continue; }
            JsonNode it = items.get(tok);
            String s = Json.s(it, "termItemName", "?");
            String code = Json.s(it, "termItemCodeName", "");
            int n = count(s, "$v") + count(code, "$v");
            int p2 = it.path("param2").asInt(-1);
            if (p2 == -1 || p2 == -2) n = Math.max(n, -p2);
            if (n > 0) {
                StringBuilder sb = new StringBuilder(s).append('(');
                for (int k = 0; k < n && vi < vals.size(); k++, vi++) sb.append(k > 0 ? "," : "").append(vals.get(vi).asInt());
                s = sb.append(')').toString();
            }
            out.add(s);
            if (p2 >= 0) g = p2; else dead = true;
        }
        if (vi < vals.size()) {
            StringBuilder sb = new StringBuilder("v=");
            for (int k = vi; k < vals.size(); k++) sb.append(k > vi ? "," : "").append(vals.get(k).asInt());
            out.add(sb.toString());
        }
        return String.join("→", out);
    }

    static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }

    public static String attackText(Project p, JsonNode term) {
        if (term == null) return "-";
        JsonNode b = term.path("bsdxInfoCollectionList").path(0);
        int t = b.path("typeList").path(0).asInt(0);
        int v = b.path("paramList").path(0).asInt(0);
        switch (t) {
            case 0: return "" + v;
            case 1: return "自HP" + v + "%";
            case 2: return "敵HP" + v + "%";
            default: return termText(p, b);
        }
    }

    static JsonNode sub(JsonNode o, int idx) {
        String k = Json.subListKey(o);
        if (k == null) return null;
        int i = Json.subIndex(o.get(k), idx);
        return i < 0 ? null : o.get(k).get(i).get("data");
    }

    static int val(JsonNode o) {
        if (o == null) return 0;
        return o.has("int5") ? o.get("int5").asInt() : o.path("int4").asInt();
    }

    public static String animName(Project p, int spmIdx, int anim, int pat) {
        String f = p.spmFile(spmIdx);
        Spm s = f == null ? null : p.spm(f);
        if (s == null || s.getAnimData() == null) return "anim" + anim + "#" + pat;
        if (anim < 0 || anim >= s.getAnimData().size()) return "anim?" + anim;
        Spm.SPMAnimData a = s.getAnimData().get(anim);
        String n = a.getAnimName() == null ? "" : a.getAnimName().replace("\r", "").replace("\n", "").trim();
        return n + "#" + pat + (pat >= (a.getNumPat() & 0xFFFF) ? "!範囲外" : "");
    }

    public static String noke(JsonNode hit) {
        JsonNode n = sub(hit, 17);
        if (n == null) return "-";
        int t = n.path("int1").asInt();
        if (t != 0) return t < 6 ? new String[]{"指定", "同:地→地", "同:空", "同:空→地", "同:ダウン", "なし"}[t] : "?";
        return "停" + n.path("int2").asInt() + " HB" + n.path("int3").asInt() + " ダ" + n.path("int7").asInt() + " 吹" + n.path("int10").asInt();
    }

    /** One-line summary of an event (tree labels / timeline). */
    public static String summary(Project p, JsonNode o, int selfSpm) {
        int ty = o.path("typeId").asInt(-1);
        switch (ty) {
            case 13: {
                int g = o.path("spmFileSequence").asInt();
                return animName(p, g == -1 ? selfSpm : g, o.path("actionGroupNumber").asInt(), o.path("actionNumber").asInt());
            }
            case 16: {
                String c = o.path("hitCount").asInt() == -1 || o.path("hitCount").asInt() == 9999 ? "∞" : "" + o.path("hitCount").asInt();
                return "威力" + attackText(p, sub(o, 6)) + "×" + c + " HS" + o.path("int15").asInt() + " のけぞり[" + noke(o) + "]";
            }
            case 19: {
                JsonNode ws = sub(o, 3);
                return ws == null ? "発射 ?" : "発射 " + wazSel(p, ws.path("wazFileNo").asInt(), ws.path("wazSequenceNo").asInt());
            }
            case 4: return wazSel(p, o.path("wazFileNo").asInt(), o.path("wazSequenceNo").asInt());
            case 23: {
                List<String> l = new ArrayList<>();
                for (JsonNode x : o.path("byteDataList")) { int[] v = Json.bytesToInts(x, 4); l.add(p.seLabel(v[0], v[1]) + (v[2] == 100 ? "" : "(" + v[2] + "%)")); }
                return String.join(" / ", l);
            }
            case 24: {
                List<String> l = new ArrayList<>();
                for (JsonNode x : o.path("byteDataList")) { int[] v = Json.bytesToInts(x, 3); l.add(p.voiceLabel(v[0], v[1]) + (v[2] == 100 ? "" : "(" + v[2] + "%)")); }
                return String.join(" / ", l);
            }
            case 0: case 1:
                return (o.path("int1").asInt() != 0 || o.path("int2").asInt() != 0 ? o.path("int2").asInt() + "⇒" : "") + o.path("int4").asInt();
            case 2: case 3:
                return o.path("int5").asInt() + "±" + o.path("int6").asInt();
            case 34: {
                List<String> c = new ArrayList<>();
                for (JsonNode b : o.path("bsdxInfoCollectionList1")) c.add(termText(p, b));
                return String.join(" & ", c) + " ⇒ " + termText(p, o.path("bsdxInfoCollectionList2").path(0));
            }
            case 21: return "熱チャージ消費=" + val(sub(o, 0)) + " 溜め=" + val(sub(o, 1));
            default:
                if (o.has("bsdxInfoCollectionList")) return termText(p, o.path("bsdxInfoCollectionList").path(0));
                StringBuilder sb = new StringBuilder();
                o.fields().forEachRemaining(e -> {
                    String k = e.getKey();
                    if (e.getValue().isNumber() && !k.equals("offset") && !k.equals("slotNum") && !k.equals("typeId") && !k.equals("startFrame") && !k.equals("endFrame") && sb.length() < 80)
                        sb.append(k).append('=').append(e.getValue().asText()).append(' ');
                });
                return sb.toString().trim();
        }
    }

    public static String wazSel(Project p, int f, int q) {
        String fn = p.wazFile(f);
        String name = "?";
        List<String> names = p.skillNames(f);
        if (names != null && q >= 0 && q < names.size()) name = names.get(q);
        return (fn == null ? "?" + f : fn.replaceAll("(?i)\\.waz$", "")) + ":" + q + "「" + name + "」";
    }

    /** "威力×ヒット数" list for a skill, following spawned bullets one level. */
    public static String hitsSummary(Project p, JsonNode waz, int si) {
        List<String> out = new ArrayList<>();
        for (WazNav.Ev e : WazNav.events(waz, si, -1)) {
            if (e.typeId() == 16 && e.slot == 41) {
                String c = e.node.path("hitCount").asInt() == -1 || e.node.path("hitCount").asInt() == 9999 ? "∞" : "" + e.node.path("hitCount").asInt();
                out.add(attackText(p, sub(e.node, 6)) + "×" + c);
            } else if (e.typeId() == 19) {
                JsonNode ws = sub(e.node, 3);
                if (ws != null) out.add("{" + wazSel(p, ws.path("wazFileNo").asInt(), ws.path("wazSequenceNo").asInt()) + "}");
            }
            if (out.size() > 6) { out.add("…"); break; }
        }
        return out.isEmpty() ? "-" : String.join(" ", out);
    }
}
