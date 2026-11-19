package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_equal_oracle.rs}: Region equality against C
 * (region.c {@code Equal}): the same class, defining points equal within pointset.c {@code
 * Equal}'s default accuracy, equal base and current Frames, an equal base to current Mapping, and
 * the same Negated and Closed values. A Region whose Unit was changed adaptively has the same
 * points and current Frame as a Box built in the new unit, but a different base Frame and Mapping,
 * so the two are not equal ({@code oracle/region_equal.json}, from {@code
 * tools/oracle_fixtures/region_equal_probe.c}).
 *
 * <p>The third column of each case, C's {@code astOverlap}, is not checked: {@link
 * Region#overlap} is not ported.
 */
class RegionEqualOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_equal");

    private static Frame frame(String unit1) {
        Frame f = new Frame(2);
        if (unit1 != null) {
            f.setUnit(1, unit1);
            f.setUnit(2, "m");
            f.setActiveUnit(true);
        }
        return f;
    }

    private static Region boxIn(Frame f, double x1, double y1) {
        return new Box(f, 1, new double[] {0.0, 0.0}, new double[] {x1, y1}, null);
    }

    private static Region[] pair(String name) {
        switch (name) {
            case "remapped_against_plain": {
                Region a = boxIn(frame("m"), 1.0, 1.0);
                a.setUnit(1, "km");
                return new Region[] {a, boxIn(frame("km"), 1.0, 1.0)};
            }
            case "points_within_accuracy":
                return new Region[] {boxIn(frame(null), 1.0, 1.0), boxIn(frame(null), 1.0 + 1.0e-15, 1.0)};
            case "points_beyond_accuracy":
                return new Region[] {boxIn(frame(null), 1.0, 1.0), boxIn(frame(null), 1.0 + 1.0e-9, 1.0)};
            case "same":
                return new Region[] {boxIn(frame(null), 1.0, 1.0), boxIn(frame(null), 1.0, 1.0)};
            case "negated": {
                Region a = boxIn(frame(null), 1.0, 1.0);
                a.negate();
                return new Region[] {a, boxIn(frame(null), 1.0, 1.0)};
            }
            default:
                throw new AssertionError("no construction for case " + name);
        }
    }

    @Test
    void equalityMatchesTheCLibrary() {
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Region[] ab = pair(name);
            assertEquals(c.get(1).booleanValue(), ab[0].equalTo(ab[1]), name + ": equal");
        }
    }
}
