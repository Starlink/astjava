package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_rate.rs}: {@code rate} for each basic Mapping class and for
 * a compound Mapping, after C's {@code ast_tester/testrate.c}, with its error numbers in the
 * assertion messages. The tolerance of 1e-6 is the program's own, for the rates C compares to it.
 *
 * <p>{@code tran_map} needs a TranMap, which is outside the first phase. As in C, the unit
 * MatrixMap is made in its unit form. The Rust test builds the CmpMap's components in the
 * orientation they had when C composed them; here, as in C, the components are inverted after the
 * CmpMap is made, which, as the CmpMap records each component's Invert value when it is made,
 * does not change the CmpMap.
 */
class RateTest {

    /** The position C evaluates the 2-D rates at. */
    private static final double[] AT = {10.0, 1.2e6};

    /** The WinMap C uses twice: (0,0)-(1,1) onto (0.5,0.5)-(2.5,2.5). */
    private static WinMap winMap() {
        return new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, new double[] {0.5, 0.5}, new double[] {2.5, 2.5});
    }

    /** The full MatrixMap C uses twice. */
    private static MatrixMap fullMatrix() {
        return new MatrixMap(2, 2, new double[][] {{1.2, 1.6}, {-1.6, 2.2}});
    }

    private static void close(double rate, double want, int err) {
        assertTrue(Math.abs(rate - want) <= 1.0e-6, "Error " + err + ": rate " + rate + ", want " + want);
    }

    /** Rates of the inverse of {@link #fullMatrix}, Errors 15-18 and 51-54. */
    private static void assertInverseFullMatrixRates(Mapping m, int[] errs) {
        close(m.rate(AT, 1, 1), 0.423076923, errs[0]);
        close(m.rate(AT, 2, 2), 0.230769231, errs[1]);
        close(m.rate(AT, 1, 2), -0.307692308, errs[2]);
        close(m.rate(AT, 2, 1), 0.307692308, errs[3]);
    }

    /**
     * The compound Mapping of the C program's last block (and of {@code testratemap.c}): a parallel
     * ShiftMap and full MatrixMap, followed by the inverse of a PermMap followed by an inverted
     * ZoomMap, built as C builds it.
     */
    static CmpMap compoundMap() {
        Mapping c1 = new ShiftMap(new double[] {-1.0, 1.0});
        Mapping c2 = new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {-2.0, 3.0}});
        CmpMap c3 = new CmpMap(c1, c2, false);
        int[] perm = {3, 4, 1, 2};
        Mapping p = new PermMap(4, perm, 4, perm, new double[] {0.0});
        ZoomMap z = new ZoomMap(4, 0.25);
        z.invert();
        CmpMap c4 = new CmpMap(p, z, true);
        c4.invert();
        CmpMap result = new CmpMap(c3, c4, true);
        // C inverts these after composing them, which does not change the CmpMaps.
        z.invert();
        c3.invert();
        c4.invert();
        return result;
    }

    /**
     * The rates of {@link #compoundMap} at (1,2,3,4), as {output axis, input axis, value, exact},
     * where exact is 0 for the rates C compares to 1e-6 rather than exactly. C's error numbers run
     * on from 55.
     */
    static final double[][] COMPOUND_FORWARD = {
        {1, 1, 0.0, 1}, {1, 2, 0.0, 1}, {1, 3, 0.25, 0}, {1, 4, 0.5, 1},
        {3, 1, 0.25, 1}, {3, 2, 0.0, 1}, {3, 3, 0.0, 1}, {3, 4, 0.0, 1}
    };

    /** As {@link #COMPOUND_FORWARD}, for the inverted Mapping; error numbers from 63. */
    static final double[][] COMPOUND_INVERSE = {
        {1, 1, 0.0, 1}, {1, 2, 0.0, 1}, {1, 3, 4.0, 1}, {1, 4, 0.0, 1},
        {3, 1, 12.0 / 7.0, 0}, {3, 2, -8.0 / 7.0, 0}, {3, 3, 0.0, 1}, {3, 4, 0.0, 1}
    };

    @Test
    void unitMap() {
        UnitMap m = new UnitMap(2);
        assertEquals(1.0, m.rate(AT, 1, 1), "Error 1");
        assertEquals(0.0, m.rate(AT, 2, 1), "Error 2");
        m.invert();
        assertEquals(1.0, m.rate(AT, 1, 1), "Error 202");
    }

    @Test
    void zoomMap() {
        ZoomMap m = new ZoomMap(2, 2.0);
        assertEquals(2.0, m.rate(AT, 1, 1), "Error 3");
        assertEquals(0.0, m.rate(AT, 2, 1), "Error 4");
        m.invert();
        assertEquals(0.5, m.rate(AT, 1, 1), "Error 402");
    }

    @Test
    void unitMatrixMap() {
        MatrixMap m = new MatrixMap(2, 2);
        assertEquals(1.0, m.rate(AT, 1, 1), "Error 5");
        assertEquals(0.0, m.rate(AT, 2, 1), "Error 6");
        m.invert();
        assertEquals(1.0, m.rate(AT, 1, 1), "Error 602");
        assertEquals(0.0, m.rate(AT, 1, 2), "Error 603");
    }

    @Test
    void diagonalMatrixMap() {
        MatrixMap m = new MatrixMap(2, 2, new double[] {-2.0, 1.5});
        assertEquals(-2.0, m.rate(AT, 1, 1), "Error 7");
        assertEquals(1.5, m.rate(AT, 2, 2), "Error 8");
        assertEquals(0.0, m.rate(AT, 1, 2), "Error 9");
        assertEquals(0.0, m.rate(AT, 2, 1), "Error 10");
        m.invert();
        assertEquals(-0.5, m.rate(AT, 1, 1), "Error 1002");
    }

    @Test
    void fullMatrixMap() {
        MatrixMap m = fullMatrix();
        assertEquals(1.2, m.rate(AT, 1, 1), "Error 11");
        assertEquals(2.2, m.rate(AT, 2, 2), "Error 12");
        assertEquals(1.6, m.rate(AT, 1, 2), "Error 13");
        assertEquals(-1.6, m.rate(AT, 2, 1), "Error 14");
        m.invert();
        assertInverseFullMatrixRates(m, new int[] {15, 16, 17, 18});
    }

    @Test
    void shiftMap() {
        ShiftMap m = new ShiftMap(new double[] {-1.2, 1.2});
        assertEquals(1.0, m.rate(AT, 1, 1), "Error 20");
        assertEquals(0.0, m.rate(AT, 2, 1), "Error 21");
        m.invert();
        assertEquals(1.0, m.rate(AT, 1, 1), "Error 23");
    }

    @Test
    void winMapRates() {
        WinMap m = winMap();
        assertEquals(2.0, m.rate(AT, 1, 1), "Error 24");
        assertEquals(0.0, m.rate(AT, 2, 1), "Error 25");
        assertEquals(2.0, m.rate(AT, 2, 2), "Error 26");
        assertEquals(0.0, m.rate(AT, 1, 2), "Error 27");
        m.invert();
        assertEquals(0.5, m.rate(AT, 1, 1), "Error 29");
        assertEquals(0.0, m.rate(AT, 2, 1), "Error 30");
        assertEquals(0.5, m.rate(AT, 2, 2), "Error 31");
        assertEquals(0.0, m.rate(AT, 1, 2), "Error 32");
    }

    /** An asymmetric PermMap: the forward direction swaps the axes and the inverse does not. */
    @Test
    void permMap() {
        PermMap m = new PermMap(2, new int[] {1, 2}, 2, new int[] {2, 1}, null);
        assertEquals(0.0, m.rate(AT, 1, 1), "Error 34");
        assertEquals(1.0, m.rate(AT, 2, 1), "Error 35");
        assertEquals(0.0, m.rate(AT, 2, 2), "Error 37");
        assertEquals(1.0, m.rate(AT, 1, 2), "Error 38");
        m.invert();
        assertEquals(1.0, m.rate(AT, 1, 1), "Error 40");
        assertEquals(0.0, m.rate(AT, 2, 1), "Error 41");
        assertEquals(1.0, m.rate(AT, 2, 2), "Error 43");
        assertEquals(0.0, m.rate(AT, 1, 2), "Error 44");
    }

    @Test
    void cmpMap() {
        double[] at = {1.0, 2.0, 3.0, 4.0};
        Mapping m = compoundMap();
        double[][][] tables = {COMPOUND_FORWARD, COMPOUND_INVERSE};
        int[] firstErr = {55, 63};
        for (int t = 0; t < 2; t++) {
            double[][] table = tables[t];
            for (int k = 0; k < table.length; k++) {
                int ax1 = (int) table[k][0];
                int ax2 = (int) table[k][1];
                double want = table[k][2];
                double r = m.rate(at, ax1, ax2);
                int err = firstErr[t] + k;
                if (table[k][3] != 0.0) {
                    assertEquals(want, r, "Error " + err + ": rate(" + ax1 + "," + ax2 + ")");
                } else {
                    close(r, want, err);
                }
            }
            m.invert();
        }
    }
}
