package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_errors_oracle.rs}: the errors C's Region
 * constructors and setters report, and the values its loader repairs ({@code
 * oracle/region_errors.json}, from {@code tools/oracle_fixtures/region_errors_probe.c}). Each
 * expected code is the status C is left holding.
 *
 * <p>Only the cases built from Boxes alone run: BAD coordinates given to {@code astBox} (box.c
 * {@code astInitBox}) and a Box dump whose points have three values for its 2-D Frame (region.c
 * {@code Load}). The other cases need Circle, Polygon or Ellipse; the number of checks made is
 * asserted, so that the task that adds those classes raises it.
 */
class RegionErrorsOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_errors");

    /** The number of C values checked. */
    private static final int CHECKS = 5;

    private int checks;

    private void assertStatus(String key, Runnable r) {
        AstException e = assertThrows(AstException.class, r::run, key);
        assertEquals(DATA.get(key).intValue(), e.getStatus(), key);
        checks++;
    }

    private static final double BAD = AstObject.AST__BAD;

    private static Box centred(double[] c, double[] p) {
        return new Box(new Frame(2), 0, c, p, null);
    }

    private static Box bounds(double[] l, double[] u) {
        return new Box(new Frame(2), 1, l, u, null);
    }

    @Test
    void boxRejectsBadCoordinatesAndTheLoaderAWrongLength() {
        assertStatus("BOX_CENTRE", () -> centred(new double[] {BAD, 0.0}, new double[] {1.0, 1.0}));
        assertStatus("BOX_CORNER", () -> centred(new double[] {0.0, 0.0}, new double[] {1.0, BAD}));
        assertStatus("BOX_LOWER", () -> bounds(new double[] {BAD, 0.0}, new double[] {1.0, 1.0}));
        assertStatus("BOX_UPPER", () -> bounds(new double[] {0.0, 0.0}, new double[] {1.0, BAD}));

        AstException e = assertThrows(
                AstException.class,
                () -> ChannelText.read(DATA.get("LOAD_NCOORD_DUMP").textValue()));
        assertEquals(DATA.get("LOAD_NCOORD").intValue(), e.getStatus());
        assertEquals(
                "astLoadRegion(Box): Corrupt Box contains  incorrect number of coordinate values per point (3).",
                e.getMsgText().split("\n", -1)[0]);
        checks++;

        assertEquals(CHECKS, checks);
    }
}
