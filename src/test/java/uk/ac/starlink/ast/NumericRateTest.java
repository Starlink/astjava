package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_numeric_rate.rs}: rates that are known exactly, found
 * through {@code rate} at positions that test how the interval about the position is chosen. The
 * tolerances are the Rust tests' own, on exact values.
 *
 * <p>The tests on a WcsMap ({@code numeric_rate_wcsmap_nonlinear}, {@code
 * numeric_rate_wcsmap_near_pole} and {@code numeric_rate_consistency}) are translated in Task 11;
 * {@code numeric_rate_tan_at_zero} needs a MathMap, and the {@code numeric_rate_chebymap_*} tests a
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
}
