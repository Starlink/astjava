package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_nulltran_oracle.rs}: NullRegion membership against
 * C (nullregion.c {@code Transform}): a negated NullRegion passes every point through as given,
 * bad axes and all, before and after mapping through a ZoomMap, as it inherits Frame's unit
 * Transform; one that is not negated makes every value bad ({@code oracle/region_nulltran.json},
 * from {@code tools/oracle_fixtures/region_nulltran_probe.c}). The values are compared exactly.
 *
 * <p>The Rust test takes a point to be in the Region when every value is left good; here it is
 * C's {@code astPointInRegion} (region.c {@code PointInRegion}), which looks at the first good
 * value, so that the point (bad, 3) is in a negated NullRegion, as the probe {@code
 * probes/region_null_interval_pointlist_probe.c} finds ({@code null_bad_input}).
 */
class RegionNullTranOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_nulltran");

    private static final double BAD = AstObject.AST__BAD;

    private static Region nullRegion(boolean negated) {
        NullRegion nr = new NullRegion(new Frame(2), null);
        if (negated) {
            nr.negate();
        }
        return nr;
    }

    @Test
    void transformMatchesTheCLibrary() {
        JsonNode pts = DATA.get("POINTS");
        int np = pts.size();
        double[][] in = new double[2][np];
        for (int i = 0; i < np; i++) {
            in[0][i] = ProbeData.toDouble(pts.get(i).get(0));
            in[1][i] = ProbeData.toDouble(pts.get(i).get(1));
        }
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Region region;
            switch (name) {
                case "negated":
                    region = nullRegion(true);
                    break;
                case "negated_zoomed":
                    region = nullRegion(true).mapRegionInto(new ZoomMap(2, 2.0), new Frame(2));
                    break;
                case "plain":
                    region = nullRegion(false);
                    break;
                default:
                    throw new AssertionError("no NullRegion for case " + name);
            }
            double[][] out = region.transform(in, np, true);
            double[] got = new double[2 * np];
            double[] want = new double[2 * np];
            for (int i = 0; i < np; i++) {
                got[2 * i] = out[0][i];
                got[2 * i + 1] = out[1][i];
            }
            for (int k = 0; k < 2 * np; k++) {
                want[k] = ProbeData.toDouble(c.get(1).get(k));
            }
            assertArrayEquals(want, got, name);
            // Membership is C's astPointInRegion: whether the first good value is left good.
            for (int i = 0; i < np; i++) {
                int first = in[0][i] != BAD ? 0 : 1;
                boolean inside = want[2 * i + first] != BAD;
                assertEquals(inside, region.pointInRegion(new double[] {in[0][i], in[1][i]}), name + " point " + i);
            }
        }
    }
}
