package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_interval_oracle.rs}: Interval membership, Bounded
 * and boundary mesh against C (interval.c {@code Transform}, {@code GetBounded}, {@code
 * RegBaseMesh}): an Interval that is equivalent to a Box is tested, bounded and meshed as that
 * Box; one that is not tests each axis, separating equal limits by the width of its uncertainty;
 * and an Interval excluding every axis is not bounded ({@code oracle/region_interval.json}, from
 * {@code tools/oracle_fixtures/region_interval_probe.c}).
 *
 * <p>Every value is compared exactly: the memberships are flags, and the mesh is that of a Box
 * whose points are sums of exact steps.
 */
class RegionIntervalOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_interval");

    private static final double BAD = AstObject.AST__BAD;

    private static double[] doubles(JsonNode n) {
        double[] out = new double[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(n.get(i));
        }
        return out;
    }

    private static Region interval(Frame frame, double[] lbnd, double[] ubnd) {
        return new Interval(frame, lbnd, ubnd, null);
    }

    private static SkyFrame icrs() {
        SkyFrame sf = new SkyFrame();
        sf.setSystem("ICRS");
        return sf;
    }

    private static String membership(Region reg, double[] x, double[] y) {
        double[][] out = reg.tran2(x.length, x, y, true);
        StringBuilder sb = new StringBuilder();
        for (double v : out[0]) {
            sb.append(v == BAD ? '0' : '1');
        }
        return sb.toString();
    }

    @Test
    void membershipAndBoundedMatchTheCLibrary() {
        double[] x15 = {1.5, 1.5, 1.5, 1.5};
        double[] zeroY = doubles(DATA.get("ZERO_Y"));
        double[] excluded = doubles(DATA.get("EXCLUDED_XY"));
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Region reg;
            String got;
            switch (name) {
                case "bounded_slice":
                    reg = interval(new Frame(2), new double[] {1.0, 5.0}, new double[] {2.0, 5.0});
                    got = membership(reg, x15, zeroY);
                    break;
                case "unbounded_slice":
                    reg = interval(new Frame(2), new double[] {1.0, 5.0}, new double[] {BAD, 5.0});
                    got = membership(reg, x15, zeroY);
                    break;
                case "unbounded_slice_negated_open":
                    reg = interval(new Frame(2), new double[] {1.0, 5.0}, new double[] {BAD, 5.0});
                    reg.setClosed(false);
                    reg.negate();
                    got = membership(reg, x15, zeroY);
                    break;
                case "sky":
                    reg = interval(icrs(), new double[] {6.2, 0.1}, new double[] {6.4, 0.3});
                    got = membership(reg, doubles(DATA.get("SKY_X")), doubles(DATA.get("SKY_Y")));
                    break;
                case "all_axes_excluded":
                    reg = interval(new Frame(2), new double[] {2.0, 2.0}, new double[] {1.0, 1.0});
                    got = membership(reg, excluded, excluded);
                    break;
                default:
                    throw new AssertionError("no construction for case " + name);
            }
            assertEquals(c.get(1).textValue(), got, name + ": membership");
            assertEquals(c.get(2).booleanValue(), reg.getBounded(), name + ": Bounded");
        }
    }

    @Test
    void meshMatchesTheCLibrary() {
        Region reg = interval(new Frame(2), new double[] {0.0, 0.0}, new double[] {1.0, 1.0});
        double[][] mesh = reg.regMesh();
        int n = mesh[0].length;
        JsonNode want = DATA.get("MESH");
        assertEquals(want.get(0).intValue(), n);
        double[] ends = {mesh[0][0], mesh[1][0], mesh[0][n - 1], mesh[1][n - 1]};
        double[] wantEnds = doubles(want.get(1));
        for (int i = 0; i < 4; i++) {
            assertEquals(wantEnds[i], ends[i], "end value " + i);
        }
    }
}
