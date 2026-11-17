package uk.ac.starlink.ast.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The output of a C probe run against the pinned C library, committed under {@code
 * src/test/resources/probes/} beside the probe's source. The output is a sequence of cases, each
 * starting with a line {@code == <name>}; the lines that follow, up to the next such line, are the
 * case's text. Trailing white space is removed from every line, so that the committed file and
 * the text a test makes compare alike however the file's hooks treat it.
 */
public final class ProbeOutput {

    private ProbeOutput() {}

    /**
     * The cases of {@code probes/<name>.out}, in order, each mapped to its text, every line ending
     * with a newline.
     *
     * @throws IllegalStateException if there is no such file, or a case name appears twice
     */
    public static Map<String, String> load(String name) {
        String res = "/probes/" + name + ".out";
        String text;
        try (InputStream in = ProbeOutput.class.getResourceAsStream(res)) {
            if (in == null) {
                throw new IllegalStateException("No probe output " + res);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> cases = new LinkedHashMap<>();
        String current = null;
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        // The piece after the newline that ends the file is not a line.
        int nline = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        for (int i = 0; i < nline; i++) {
            String line = lines[i];
            if (line.startsWith("== ")) {
                if (current != null) {
                    put(cases, current, sb.toString());
                }
                current = line.substring(3);
                sb.setLength(0);
            } else if (current != null) {
                sb.append(trimEnd(line)).append('\n');
            }
        }
        if (current != null) {
            put(cases, current, sb.toString());
        }
        return cases;
    }

    private static void put(Map<String, String> cases, String name, String body) {
        if (cases.put(name, body) != null) {
            throw new IllegalStateException("Probe case " + name + " appears twice");
        }
    }

    /** {@code text} with the trailing white space of every line removed. */
    public static String trimLines(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(trimEnd(lines[i]));
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String trimEnd(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
