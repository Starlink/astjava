package uk.ac.starlink.ast.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The C probe tables converted by {@link CasesToJson}, read from {@code src/test/resources/oracle/}. */
public final class ProbeData {

    private ProbeData() {}

    /**
     * The constants of {@code oracle/<name>.json}.
     *
     * @throws IllegalStateException if there is no such file
     */
    public static JsonNode load(String name) {
        String res = "/oracle/" + name + ".json";
        try (InputStream in = ProbeData.class.getResourceAsStream(res)) {
            if (in == null) {
                throw new IllegalStateException("No probe data " + res);
            }
            return new ObjectMapper().readTree(in).get("consts");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A number from probe data, with the string encodings {@code "NaN"}, {@code "Infinity"},
     * {@code "-Infinity"} and {@code "BAD"} (C's {@code AST__BAD}) reversed.
     *
     * @throws IllegalArgumentException if the node is not a number or one of those strings
     */
    public static double toDouble(JsonNode n) {
        if (n.isNumber()) {
            return n.doubleValue();
        }
        if (n.isTextual()) {
            switch (n.textValue()) {
                case "NaN":
                    return Double.NaN;
                case "Infinity":
                    return Double.POSITIVE_INFINITY;
                case "-Infinity":
                    return Double.NEGATIVE_INFINITY;
                case "BAD":
                    return CValues.BAD;
                default:
                    break;
            }
        }
        throw new IllegalArgumentException("Not a probe-data number: " + n);
    }
}
