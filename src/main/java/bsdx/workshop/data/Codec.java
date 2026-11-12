package bsdx.workshop.data;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giga.nexas.dto.bsdx.Bsdx;
import com.giga.nexas.dto.bsdx.BsdxGenerator;
import com.giga.nexas.dto.bsdx.BsdxParser;
import com.giga.nexas.service.BsdxBinService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Bridge to the NeXAS_DX data layer (our patched build). Uses exactly the pipeline that was verified byte-exact
 * for all 105 mek / 112 waz (notes/01 §1.2): parser -> hutool JSONUtil.toJsonStr -> (edit JSON) -> Jackson -> generator.
 * Game strings are windows-31j.
 */
public final class Codec {
    public static final String CHARSET = "windows-31j";
    private static final Map<String, String> CLS = Map.of(
            "mek", "com.giga.nexas.dto.bsdx.mek.Mek",
            "waz", "com.giga.nexas.dto.bsdx.waz.Waz",
            "spm", "com.giga.nexas.dto.bsdx.spm.Spm",
            "grp", "com.giga.nexas.dto.bsdx.grp.Grp",
            "bin", "com.giga.nexas.dto.bsdx.bin.Bin",
            "dat", "com.giga.nexas.dto.bsdx.dat.Dat");

    private final Map<String, BsdxParser<?>> parsers;
    private final Map<String, BsdxGenerator<?>> generators;
    public final ObjectMapper mapper;
    private final Path tmpDir;

    public Codec() {
        BsdxBinService svc = new BsdxBinService();
        parsers = svc.getParserMap();
        generators = svc.getGeneratorMap();
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            tmpDir = Files.createTempDirectory("bsdxworkshop");
            tmpDir.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static String base(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(0, i);
    }

    public Bsdx parse(String fileName, byte[] data) {
        String ext = ext(fileName);
        BsdxParser<?> p = parsers.get(ext);
        if (p == null) throw new IllegalArgumentException("unsupported file type: " + fileName);
        Bsdx b = p.parse(data, base(fileName), CHARSET);
        b.setExtensionName(ext);
        return b;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized byte[] generate(Bsdx obj) throws IOException {
        String ext = obj.getExtensionName().toLowerCase(Locale.ROOT);
        BsdxGenerator gen = generators.get(ext);
        if (gen == null) throw new IllegalArgumentException("no generator for " + ext);
        Path f = tmpDir.resolve("gen." + ext);
        try {
            gen.generate(f.toString(), obj, CHARSET);
            return Files.readAllBytes(f);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    public String toJson(Bsdx obj) { return JSONUtil.toJsonStr(obj); }

    public ObjectNode toTree(Bsdx obj) throws IOException {
        return (ObjectNode) mapper.readTree(toJson(obj));
    }

    public ObjectNode parseToTree(String fileName, byte[] data) throws IOException {
        return toTree(parse(fileName, data));
    }

    public Bsdx fromTree(JsonNode tree, String ext) throws IOException {
        try {
            Class<?> c = Class.forName(CLS.get(ext));
            Bsdx b = (Bsdx) mapper.treeToValue(tree, c);
            b.setExtensionName(ext);
            return b;
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
    }

    /** JSON tree -> binary. */
    public byte[] treeToBytes(JsonNode tree, String ext) throws IOException {
        return generate(fromTree(tree, ext));
    }
}
