package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_numeric_rate.rs}: rates that are known exactly, found
 * through {@code rate} at positions that test how the interval about the position is chosen. The
 * tolerances are the Rust tests' own, on exact values.
 *
 * <p>{@code numericRateMatchesTheTanDerivatives} is not in the Rust file: it checks the rates of a
 * TAN WcsMap, which is nonlinear, against its derivatives, which they match to within 3e-8
 * relative. {@code numeric_rate_tan_at_zero} needs a MathMap, and the {@code numeric_rate_chebymap_*} tests a
 * ChebyMap, which are outside the first phase.
 */
class NumericRateTest {

    @Test
    void numericRateLinearMapping() {
        ZoomMap zoom = new ZoomMap(2, 3.0);
        assertEquals(3.0, zoom.rate(new double[] {1.0, 2.0}, 1, 1), 1e-6, "zoom rate d(out1)/d(in1)");
        assertEquals(0.0, zoom.rate(new double[] {1.0, 2.0}, 1, 2), 1e-6, "zoom rate d(out1)/d(in2)");
        assertEquals(3.0, zoom.rate(new double[] {1.0, 2.0}, 2, 2), 1e-6, "zoom rate d(out2)/d(in2)");
    }

    @Test
    void numericRateAtZero() {
        ZoomMap zoom = new ZoomMap(1, 5.0);
        assertEquals(5.0, zoom.rate(new double[] {0.0}, 1, 1), 1e-6, "zoom rate at origin");
    }

    @Test
    void numericRateLargePosition() {
        ZoomMap zoom = new ZoomMap(1, 2.0);
        assertEquals(2.0, zoom.rate(new double[] {1e10}, 1, 1), 1e-4, "zoom rate at large position");
    }

    @Test
    void numericRateSmallPosition() {
        ZoomMap zoom = new ZoomMap(1, 7.0);
        assertEquals(7.0, zoom.rate(new double[] {1e-15}, 1, 1), 1e-4, "zoom rate at small position");
    }

    /**
     * The numerical estimate itself, through a CmpMap, which C's base class differentiates as a
     * whole (cmpmap.c leaves its own {@code Rate} unregistered): a linear CmpMap gives its rate,
     * and a bad position gives a bad rate (mapping.c {@code Rate}).
     */
    @Test
    void aCompoundMappingIsDifferentiatedNumerically() {
        CmpMap m = new CmpMap(new ZoomMap(1, 3.0), new ShiftMap(new double[] {2.0}), true);
        assertEquals(3.0, m.rate(new double[] {4.0}, 1, 1), 1e-6);
        assertEquals(3.0, m.rate(new double[] {0.0}, 1, 1), 1e-6);
        assertEquals(AstObject.AST__BAD, m.rate(new double[] {AstObject.AST__BAD}, 1, 1));
    }

    @Test
    void numericRateWcsmapNonlinear() {
        WcsMap wcs = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        double rate = wcs.rate(new double[] {0.1, 0.1}, 1, 1);
        assertNotEquals(AstObject.AST__BAD, rate);
        assertTrue(Double.isFinite(rate), "rate should be finite, got " + rate);
        assertTrue(Math.abs(rate) > 0.01, "rate should be non-trivial, got " + rate);
    }

    @Test
    void numericRateWcsmapNearPole() {
        WcsMap wcs = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        double rate = wcs.rate(new double[] {0.01, 1.4}, 2, 2);
        assertNotEquals(AstObject.AST__BAD, rate);
        assertTrue(Double.isFinite(rate), "rate near pole should be finite, got " + rate);
        assertTrue(Math.abs(rate) > 0.1, "rate near pole should be non-trivial, got " + rate);
    }

    @Test
    void numericRateConsistency() {
        WcsMap wcs = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        double r1 = wcs.rate(new double[] {0.1, 0.1}, 1, 1);
        double r2 = wcs.rate(new double[] {0.1001, 0.1}, 1, 1);
        assertNotEquals(AstObject.AST__BAD, r1);
        assertNotEquals(AstObject.AST__BAD, r2);
        double relativeDiff = Math.abs(r1 - r2) / Math.max(Math.abs(r1), 1e-10);
        assertTrue(relativeDiff < 0.01, "r1=" + r1 + ", r2=" + r2);
    }

    /**
     * TAN takes native (phi, theta) to x = cot(theta) sin(phi), y = -cot(theta) cos(phi), in
     * radians; the numerical rates match these derivatives to within 3e-8 relative here.
     */
    @Test
    void numericRateMatchesTheTanDerivatives() {
        WcsMap wcs = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        for (double[] at : new double[][] {{0.01, 1.4}, {0.1, 0.1}, {-2.0, 0.7}}) {
            double phi = at[0];
            double theta = at[1];
            double cot = 1.0 / Math.tan(theta);
            double csc2 = 1.0 / (Math.sin(theta) * Math.sin(theta));
            double[][] want = {{cot * Math.cos(phi), -csc2 * Math.sin(phi)}, {cot * Math.sin(phi), csc2 * Math.cos(phi)}
            };
            for (int ax1 = 1; ax1 <= 2; ax1++) {
                for (int ax2 = 1; ax2 <= 2; ax2++) {
                    double got = wcs.rate(at, ax1, ax2);
                    double w = want[ax1 - 1][ax2 - 1];
                    assertTrue(
                            Math.abs(got - w) <= 1e-7 * Math.max(1.0, Math.abs(w)),
                            "rate(" + ax1 + "," + ax2 + ") at " + phi + "," + theta + ": " + got + ", derivative " + w);
                }
            }
        }
    }
}
