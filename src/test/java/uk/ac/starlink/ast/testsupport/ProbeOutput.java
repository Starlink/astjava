package uk.ac.starlink.ast.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
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
     * with a newline. Where there is no such file the output is the string {@code output} of
     * {@code probes/<name>.json}, the form an output takes when it holds words of C's, such as
     * attribute names, that the spelling check of text files would reject.
     *
     * @throws IllegalStateException if there is neither file or both, or a case name appears twice
     */
    public static Map<String, String> load(String name) {
        String text = text(name, resource("/probes/" + name + ".out"), resource("/probes/" + name + ".json"));
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

    /**
     * The output of probe {@code name}, from the text of its {@code .out} file or of its {@code
     * .json} file, whichever is given (the other null). Both are an error: a probe whose output
     * moved from one form to the other leaves a copy of the old one among the compiled test
     * resources until the build is cleaned.
     *
     * @throws IllegalStateException if neither or both are given
     */
    static String text(String name, String out, String json) {
        if (out != null && json != null) {
            throw new IllegalStateException("Probe output /probes/" + name + " has both a .out and a .json file; one "
                    + "is stale (mvn clean removes a copy left in target/test-classes)");
        }
        if (out != null) {
            return out;
        }
        if (json == null) {
            throw new IllegalStateException("No probe output /probes/" + name + ".out or .json");
        }
        try {
            return new ObjectMapper().readTree(json).get("output").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text of the resource {@code res}, or null if there is none. */
    private static String resource(String res) {
        try (InputStream in = ProbeOutput.class.getResourceAsStream(res)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
