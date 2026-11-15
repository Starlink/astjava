package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_axis_format.rs}: formatting a plain Frame axis value with
 * a Format string, checked against C ({@code oracle/axis_format.json}, from {@code
 * tools/oracle_fixtures/axis_format_probe.c}). C's {@code AxisFormat} (axis.c) rewrites the Format
 * with {@code ParseAxisFormat}, removing the AST {@code &} log flag and giving a {@code .*}
 * precision the Digits value, then hands it to {@code sprintf} with the value, cast to {@code int}
 * for an integer conversion.
 */
class AxisFormatTest {

    @Test
    void formatsMatchTheCLibrary() {
        JsonNode consts = ProbeData.load("axis_format");
        Frame f = new Frame(1);
        f.setDigits(consts.get("DIGITS").asInt());
        List<String> failures = new ArrayList<>();
        JsonNode cases = consts.get("CASES");
        for (JsonNode c : cases) {
            String format = c.get(0).asText();
            double value = ProbeData.toDouble(c.get(1));
            String want = c.get(2).asText();
            f.setFormat(1, format);
            String got;
            try {
                got = f.format(1, value);
            } catch (AstException e) {
                got = "error " + e.getMessage();
            }
            if (!got.equals(want)) {
                failures.add("\"" + format + "\" of " + value + ": got \"" + got + "\", C gives \"" + want + "\"");
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ from C:\n" + String.join("\n", failures));
    }
}
