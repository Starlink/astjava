package uk.ac.starlink.ast.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The deferred lists in {@code src/test/resources/deferred/}: one line per deferred item,
 * {@code <key> | <reason>}, with {@code #} comments and blank lines ignored.
 */
public final class Deferred {

    private Deferred() {}

    /**
     * Loads {@code deferred/<name>.txt} from the test class path, keyed in file order.
     *
     * @throws IllegalStateException if there is no such list
     */
    public static Map<String, String> load(String name) {
        String res = "/deferred/" + name + ".txt";
        try (InputStream in = Deferred.class.getResourceAsStream(res)) {
            if (in == null) {
                throw new IllegalStateException("No deferred list " + res);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return parse(new String(buf.toByteArray(), StandardCharsets.UTF_8), res);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses deferred-list text.
     *
     * @throws IllegalArgumentException for a line without a reason, or a key listed twice
     */
    public static Map<String, String> parse(String text, String what) {
        Map<String, String> out = new LinkedHashMap<>();
        int lineNo = 0;
        for (String line : CValues.lines(text)) {
            lineNo++;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int bar = t.indexOf('|');
            String key = bar < 0 ? "" : t.substring(0, bar).trim();
            String reason = bar < 0 ? "" : t.substring(bar + 1).trim();
            if (key.isEmpty() || reason.isEmpty()) {
                throw new IllegalArgumentException(what + ":" + lineNo + ": expected \"<key> | <reason>\": " + line);
            }
            if (out.put(key, reason) != null) {
                throw new IllegalArgumentException(what + ":" + lineNo + ": " + key + " listed twice");
            }
        }
        return out;
    }
}
