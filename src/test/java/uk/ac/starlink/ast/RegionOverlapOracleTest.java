package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_overlap_oracle.rs}: {@link Region#overlap} codes
 * against C for Region pairs that exercise the per-class dispatch of region.c {@code Overlap} and
 * {@code OverlapX}, each pair in both orders ({@code oracle/region_overlap.json}, from {@code
 * tools/oracle_fixtures/region_overlap_probe.c}).
 *
 * <p>The cases of a Prism wait for that class; the number of cases run is asserted, so that the
 * task that adds it raises it.
 */
class RegionOverlapOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_overlap");

    /** The number of cases whose classes are present. */
    private static final int CASES_RUN = 6;

    private static Region boxed(String domain, double[] lbnd, double[] ubnd) {
        Frame frame = new Frame(2);
        if (!domain.isEmpty()) {
            frame.setDomain(domain);
        }
        return new Box(frame, 1, lbnd, ubnd, null);
    }

    /** The probe's Interval x >= 0. */
    private static Region halfPlane() {
        double bad = AstObject.AST__BAD;
        return new Interval(new Frame(2), new double[] {0.0, bad}, new double[] {bad, bad}, null);
    }

    private static Region nullRegion(boolean negated) {
        NullRegion nr = new NullRegion(new Frame(2), null);
        if (negated) {
            nr.negate();
        }
        return nr;
    }

    /** The pair a case builds, or null if its classes are not yet present. */
    private static Region[] pair(String name) {
        switch (name) {
            case "different_domains":
                return new Region[] {
                    boxed("AAA", new double[] {0.0, 0.0}, new double[] {1.0, 1.0}),
                    boxed("BBB", new double[] {0.0, 0.0}, new double[] {1.0, 1.0})
                };
            case "box_beside_half_plane":
                return new Region[] {boxed("", new double[] {-10.0, -1.0}, new double[] {-9.0, 1.0}), halfPlane()};
            case "box_across_half_plane_edge":
                return new Region[] {boxed("", new double[] {-1.0, -1.0}, new double[] {1.0, 1.0}), halfPlane()};
            case "box_inside_half_plane":
                return new Region[] {boxed("", new double[] {5.0, -1.0}, new double[] {6.0, 1.0}), halfPlane()};
            case "box_and_null_region":
                return new Region[] {boxed("", new double[] {0.0, 0.0}, new double[] {1.0, 1.0}), nullRegion(false)};
            case "box_and_negated_null_region":
                return new Region[] {boxed("", new double[] {0.0, 0.0}, new double[] {1.0, 1.0}), nullRegion(true)};
            case "prism_in_box":
            case "prism_in_rotated_box":
            case "prism_across_box":
            case "negated_prism_and_box":
                return null;
            default:
                throw new AssertionError("no construction for case " + name);
        }
    }

    @Test
    void overlapCodesMatchTheCLibrary() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Region[] ab = pair(name);
            if (ab == null) {
                continue;
            }
            run++;
            assertEquals(c.get(1).intValue(), ab[0].overlap(ab[1]), name + ": a, b");
            assertEquals(c.get(2).intValue(), ab[1].overlap(ab[0]), name + ": b, a");
        }
        assertEquals(CASES_RUN, run);
    }
}
