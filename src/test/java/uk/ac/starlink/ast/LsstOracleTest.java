package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_lsst_oracle.rs}: the transformations of the LSST FrameSet
 * ({@code wcsconv/inputs/lsst.ast}) against C ({@code oracle/lsst.json}, from {@code
 * tools/oracle_fixtures/lsst_probe.c}): the forward transformation of ten pixel positions, which
 * C gives with the same bits through the FrameSet as read and through its simplified form, and the
 * inverse transformation of those outputs.
 *
 * <p>The inverse runs PolyMap's iterative inverse, which stops once a step is below the Mapping's
 * TolInv (1e-7 pixel), so a last-bit difference in a sine, cosine or arctangent moves where it
 * stops. The Rust test allows the inverse to differ from C by up to 4.5e-10 pixel off the platform
 * the C values were captured on; Java's {@link Math} is not that platform's libm, so the inverse is
 * compared within that bound. Everything else is compared within libm rounding.
 *
 * <p>{@code lsst_simplified_inverse_matches_c} compares with the inverse through C's simplified
 * FrameSet, whose iterative inverse stops elsewhere, and is deferred as needing simplification.
 */
class LsstOracleTest {

    private static final JsonNode PROBE = ProbeData.load("lsst");

    private static final int NPTS = PROBE.get("NPTS").asInt();

    /** The most the inverse may differ from C (the Rust test's bound off the capture platform). */
    private static final double INVERSE_ATOL = 4.5e-10;

    /** The FrameSet read from {@code lsst.ast}. */
    static FrameSet loadLsst() {
        return assertInstanceOf(FrameSet.class, ChannelText.read(Fixtures.read("wcsconv/inputs/lsst.ast")));
    }

    /** The probe's pixel positions (100 + i*0.01, 200 + i*0.005), axis by axis. */
    private static double[][] inputPoints() {
        double[][] in = new double[2][NPTS];
        for (int i = 0; i < NPTS; i++) {
            in[0][i] = 100.0 + i * 0.01;
            in[1][i] = 200.0 + i * 0.005;
        }
        return in;
    }

    /** The probe's pairs named {@code name}, axis by axis. */
    private static double[][] pairs(String name) {
        JsonNode arr = PROBE.get(name);
        assertEquals(NPTS, arr.size());
        double[][] out = new double[2][NPTS];
        for (int i = 0; i < NPTS; i++) {
            out[0][i] = ProbeData.toDouble(arr.get(i).get(0));
            out[1][i] = ProbeData.toDouble(arr.get(i).get(1));
        }
        return out;
    }

    private static void assertMatchesC(double[][] got, double[][] want, double atol, String what) {
        boolean ok = true;
        for (int axis = 0; axis < 2; axis++) {
            for (int i = 0; i < NPTS; i++) {
                double g = got[axis][i];
                double w = want[axis][i];
                ok &= CValues.cValueEq(g, w) || Math.abs(g - w) <= atol;
            }
        }
        assertTrue(ok, what + ": got " + Arrays.deepToString(got) + ", C gives " + Arrays.deepToString(want));
    }

    @Test
    void lsstOriginalForwardMatchesC() {
        double[][] out = loadLsst().transform(inputPoints(), NPTS, true);
        assertMatchesC(out, pairs("C_FORWARD"), 0.0, "original forward");
    }

    @Test
    void lsstSimplifiedForwardMatchesC() {
        double[][] out = loadLsst().simplify().transform(inputPoints(), NPTS, true);
        assertMatchesC(out, pairs("C_FORWARD"), 0.0, "simplified forward");
    }

    @Test
    void lsstOriginalInverseMatchesC() {
        double[][] inv = loadLsst().transform(pairs("C_FORWARD"), NPTS, false);
        assertMatchesC(inv, pairs("C_INVERSE_OF_FORWARD"), INVERSE_ATOL, "original inverse");
    }

    @Test
    void lsstInverseRoundTrip() {
        Mapping mapping = loadLsst();
        double[][] pts = inputPoints();
        double[][] fwd = mapping.transform(pts, NPTS, true);
        double[][] inv = mapping.transform(fwd, NPTS, false);
        for (int i = 0; i < NPTS; i++) {
            for (int axis = 0; axis < 2; axis++) {
                double orig = pts[axis][i];
                double back = inv[axis][i];
                assertTrue(
                        Math.abs(back - orig) < 1e-6,
                        "pt " + i + " axis " + axis + " round trip: orig=" + orig + ", recovered=" + back);
            }
        }
    }
}
