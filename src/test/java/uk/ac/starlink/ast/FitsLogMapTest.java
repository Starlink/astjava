package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link FitsLogMap} transforms as the MathMap {@code logs=log(s)}, {@code s=exp(logs)} of C's
 * {@code LogAxis} (fitschan.c) does. The values expected are those of that MathMap in the pinned C
 * library (C probe {@code mm.c} of the final review fixes), to within one unit in the last place,
 * by which {@link Math#log} and {@link Math#exp} may differ from the C library's.
 */
class FitsLogMapTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void forwardIsTheNaturalLogarithmAndBadForNoPositiveValue() {
        double[] in = {0.0, -1.0, BAD, 1.0, 1e308, Double.NaN, 1000.0, -1000.0, 709.8};
        double[] want = {BAD, BAD, BAD, 0.0, 709.1962086421661, BAD, 6.907755278982137, BAD, 6.564983240212396};
        double[] got = new FitsLogMap().tran1(in.length, in, true);
        for (int i = 0; i < in.length; i++) {
            assertEquals(want[i], got[i], Math.ulp(want[i]), "log(" + in[i] + ")");
        }
    }

    @Test
    void inverseIsTheExponentialAndBadOnOverflow() {
        // C's evaluator gives AST__BAD for an exponential that overflows, where the C library
        // reports the overflow in errno; the pinned build on macOS, whose libm does not set errno,
        // gives an infinity for 1000 and 709.8.
        double[] in = {0.0, -1.0, BAD, 1.0, 1e308, Double.NaN, 1000.0, -1000.0, 709.8};
        double[] want = {1.0, 0.36787944117144233, BAD, 2.718281828459045, BAD, BAD, BAD, 0.0, BAD};
        double[] got = new FitsLogMap().tran1(in.length, in, false);
        for (int i = 0; i < in.length; i++) {
            assertEquals(want[i], got[i], Math.ulp(want[i]), "exp(" + in[i] + ")");
        }
    }

    @Test
    void invertingSwapsTheDirections() {
        FitsLogMap m = new FitsLogMap();
        m.invert();
        assertEquals(2.718281828459045, m.tran1(1, new double[] {1.0}, true)[0], Math.ulp(2.718281828459045));
        assertEquals(6.907755278982137, m.tran1(1, new double[] {1000.0}, false)[0], Math.ulp(6.907755278982137));
    }

    @Test
    void neverMergesWithItsNeighbours() {
        // A MathMap merges only with another MathMap (mathmap.c MapMerge), and no FrameSet of this
        // library holds one, so the Mapping is left in place whatever its neighbours.
        FitsLogMap log = new FitsLogMap();
        Mapping s = new CmpMap(new ZoomMap(1, 2.0), new CmpMap(log, new UnitMap(1), true), true).simplify();
        assertInstanceOf(CmpMap.class, s);
        Mapping[] parts = s.decompose(null, null);
        assertInstanceOf(ZoomMap.class, parts[0]);
        assertInstanceOf(FitsLogMap.class, parts[1]);
        assertFalse(log.isLinear());
        assertTrue(log.getTranForward() && log.getTranInverse());
        assertEquals(1, log.getNin());
        assertEquals(1, log.getNout());
    }
}
