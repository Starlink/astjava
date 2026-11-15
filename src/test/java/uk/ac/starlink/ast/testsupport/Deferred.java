package uk.ac.starlink.ast.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deferred lists in {@code src/test/resources/deferred/}: one line per deferred item,
 * {@code <key> | <reason>}, with {@code #} comments and blank lines ignored.
 *
 * <p>The fixture gates write their lists themselves when the system property {@value
 * #WRITE_PROPERTY} is {@code true}. A written reason is measured: {@code needs <classes>}, naming
 * the classes the library cannot load yet, or {@code encoding <name>}, naming a FITS encoding whose
 * read is not available; a reason written by hand for a fixture that fails with every class
 * present is kept; any other failure is written as {@value #UNEXPLAINED}, which the gates reject
 * until a person replaces it with the measured cause.
 */
public final class Deferred {

    /** The system property that makes the gates rewrite their deferred lists. */
    public static final String WRITE_PROPERTY = "ast.writeDeferred";

    /** The reason written for a failure that missing classes do not explain. */
    public static final String UNEXPLAINED = "UNEXPLAINED";

    private static final String NEEDS = "needs ";

    private static final String ENCODING = "encoding ";

    /** The start of the message of a FitsChan read of an encoding that is not available. */
    private static final String UNAVAILABLE_PREFIX = "FitsChan: reading the ";

    /** The end of the message of a FitsChan read of an encoding that is not available. */
    private static final String UNAVAILABLE_SUFFIX = " encoding is not available";

    private Deferred() {}

    /** Whether the gates are to rewrite their deferred lists rather than check them. */
    public static boolean writeRequested() {
        return Boolean.getBoolean(WRITE_PROPERTY);
    }

    /** Whether a reason is measured rather than written by hand. */
    private static boolean isMeasured(String reason) {
        return reason.startsWith(NEEDS) || reason.startsWith(ENCODING);
    }

    /**
     * The measured reason the message of an {@code UnsupportedOperationException} gives: the text
     * from {@code needs} on (the library ends the message of an exception for an out-of-scope class
     * with {@code needs <class>}), or {@code encoding <name>} for a FitsChan read of an encoding
     * whose read is not available; null for any other message.
     */
    public static String unavailableReason(String message) {
        if (message == null) {
            return null;
        }
        int at = message.lastIndexOf(" " + NEEDS);
        if (at >= 0) {
            return message.substring(at + 1);
        }
        if (message.startsWith(UNAVAILABLE_PREFIX) && message.endsWith(UNAVAILABLE_SUFFIX)) {
            String name =
                    message.substring(UNAVAILABLE_PREFIX.length(), message.length() - UNAVAILABLE_SUFFIX.length());
            return name.isEmpty() || name.indexOf(' ') >= 0 ? null : ENCODING + name;
        }
        return null;
    }

    /**
     * The reason to write for an item that fails.
     *
     * @param needs the measured reason for the item ({@code needs <classes>} or {@code encoding
     *     <name>}), or null if nothing missing explains its failure
     * @param old the reason listed now, or null
     */
    public static String newReason(String needs, String old) {
        if (needs != null) {
            return needs;
        }
        if (old != null && !isMeasured(old) && !UNEXPLAINED.equals(old)) {
            return old;
        }
        return UNEXPLAINED;
    }

    /**
     * What is wrong with the listed reason of an item that fails, or null if nothing is: {@value
     * #UNEXPLAINED}, a measured reason other than the current one, or a hand-written reason for an
     * item that something missing explains.
     *
     * @param reason the listed reason
     * @param needs the measured reason for the item ({@code needs <classes>} or {@code encoding
     *     <name>}), or null if nothing missing explains its failure
     */
    public static String reasonProblem(String reason, String needs) {
        if (UNEXPLAINED.equals(reason)) {
            return "is UNEXPLAINED; find the cause in the C and Rust sources and write it in place";
        }
        if (needs != null && !needs.equals(reason)) {
            return "is listed as \"" + reason + "\" but " + needs + "; rerun with -D" + WRITE_PROPERTY + "=true";
        }
        if (needs == null && isMeasured(reason)) {
            return "is listed as \"" + reason + "\" but nothing it needs is missing, and it still fails";
        }
        return null;
    }

    /**
     * Writes {@code src/test/resources/deferred/<name>.txt} under the project root: the header
     * lines, each as a comment, then one line per entry.
     */
    public static void write(String name, List<String> header, Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        for (String h : header) {
            sb.append("# ").append(h).append('\n');
        }
        for (Map.Entry<String, String> e : entries.entrySet()) {
            sb.append(e.getKey()).append(" | ").append(e.getValue()).append('\n');
        }
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        Path path = Paths.get(base, "src", "test", "resources", "deferred", name + ".txt");
        try {
            Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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
