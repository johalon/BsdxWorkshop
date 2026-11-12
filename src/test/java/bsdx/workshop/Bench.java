package bsdx.workshop;

import bsdx.workshop.data.Codec;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giga.nexas.dto.bsdx.Bsdx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Quick timing of the parse -> JSON tree -> generate pipeline: java -cp jar bsdx.workshop.Bench file.waz */
public class Bench {
    public static void main(String[] a) throws Exception {
        Codec c = new Codec();
        for (String f : a) {
            byte[] d = Files.readAllBytes(Path.of(f));
            for (int rep = 0; rep < 2; rep++) {
                long t0 = System.nanoTime();
                Bsdx b = c.parse(Path.of(f).getFileName().toString(), d);
                long t1 = System.nanoTime();
                ObjectNode tree = c.toTree(b);
                long t2 = System.nanoTime();
                byte[] g = c.treeToBytes(tree, Codec.ext(f));
                long t3 = System.nanoTime();
                System.out.printf("%s parse=%dms tree=%dms gen=%dms same=%s%n", f, (t1 - t0) / 1000000, (t2 - t1) / 1000000, (t3 - t2) / 1000000, Arrays.equals(d, g));
            }
        }
    }
}
