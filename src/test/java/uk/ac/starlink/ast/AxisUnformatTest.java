package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_axis_unformat.rs}: reading a plain Frame axis value from
 * text, checked against C ({@code oracle/axis_unformat.json}, from {@code
 * tools/oracle_fixtures/axis_unformat_probe.c}, captured on macOS). C's {@code AxisUnformat}
 * (axis.c) reads the value with {@code sscanf("%lf %n")}, which takes the longest prefix {@code
 * strtod} accepts and counts the white space around it, and otherwise accepts the {@code <bad>}
 * token.
 */
class AxisUnformatTest {

    /** Equal values, telling -0 from 0 and treating every NaN as equal. */
    private static boolean same(double a, double b) {
        return (Double.isNaN(a) && Double.isNaN(b)) || Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    @Test
    void unformatMatchesTheCLibrary() {
        Frame f = new Frame(1);
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("axis_unformat").get("CASES")) {
            String text = c.get(0).asText();
            int nread = c.get(1).asInt();
            double value = ProbeData.toDouble(c.get(2));
            double[] got = {AstObject.AST__BAD};
            int n = f.unformat(1, text, got);
            if (n != nread || !same(got[0], value)) {
                failures.add(
                        "\"" + text + "\": got (" + got[0] + ", " + n + "), C gives (" + value + ", " + nread + ")");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
