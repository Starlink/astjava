package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_winmap.rs}.
 *
 * <p>The Rust tests build a WinMap from its shifts and scales; here the package-private
 * constructor {@link WinMap#WinMap(double[], double[])} does that, and the corner form is
 * jniast's public constructor. {@code winmap_validation_mismatched_lengths} and {@code
 * winmap_validation_empty} test a Rust-only constructor; here a corner array too short for the
 * number of axes is refused, and a WinMap with no axes cannot transform ({@code AST__BADWM}, as
 * winmap.c {@code Transform} reports).
 *
 * <p>{@code winmap_rate_diagonal}, {@code winmap_rate_off_diagonal} and {@code
 * winmap_rate_inverted} test {@code rate}, and are translated in Task 8. The merge and
 * simplification tests are deferred ({@code needs simplification}), apart from {@code
 * winmap_non_uniform_stays_winmap}, which holds of a Mapping that is not restructured.
 */
class WinMapTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void winmapDimensions() {
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0});
        assertEquals(2, m.getNin());
        assertEquals(2, m.getNout());
    }

    @Test
    void winmapForward() {
        // output = shift + scale * input
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0});
        double[][] out = transformRows(m, true, new double[] {1.0, 2.0});
        assertArrayEquals(new double[] {12.0, 26.0}, point(out, 0));
    }

    @Test
    void winmapInverse() {
        // inverse: (input - shift) / scale
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0});
        double[][] out = transformRows(m, false, new double[] {12.0, 26.0});
        assertArrayEquals(new double[] {1.0, 2.0}, point(out, 0));
    }

    @Test
    void winmapFromCorners() {
        // [0,0]->[10,10] maps to [100,200]->[200,400]
        WinMap m = new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {10.0, 10.0}, new double[] {100.0, 200.0}, new double[] {
                    200.0, 400.0
                });
        assertArrayEquals(new double[] {150.0, 300.0}, point(transformRows(m, true, new double[] {5.0, 5.0}), 0));
        assertArrayEquals(new double[] {100.0, 200.0}, point(transformRows(m, true, new double[] {0.0, 0.0}), 0));
        assertArrayEquals(new double[] {200.0, 400.0}, point(transformRows(m, true, new double[] {10.0, 10.0}), 0));
    }

    @Test
    void winmapFromCornersZeroRange() {
        // A zero input range makes the axis bad.
        WinMap m = new WinMap(
                2, new double[] {5.0, 0.0}, new double[] {5.0, 10.0}, new double[] {100.0, 200.0}, new double[] {
                    200.0, 400.0
                });
        double[] out = point(transformRows(m, true, new double[] {5.0, 5.0}), 0);
        assertEquals(BAD, out[0]);
        assertEquals(300.0, out[1]);
    }

    @Test
    void winmapZeroScaleInverse() {
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {0.0, 3.0});
        double[] out = point(transformRows(m, false, new double[] {12.0, 26.0}), 0);
        assertEquals(BAD, out[0]);
        assertEquals(2.0, out[1]);
    }

    @Test
    void winmapBadInputPropagation() {
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0});
        double[] out = point(transformRows(m, true, new double[] {BAD, 2.0}), 0);
        assertEquals(BAD, out[0]);
        assertEquals(26.0, out[1]);

        double[] outNan = point(transformRows(m, true, new double[] {Double.NaN, 2.0}), 0);
        assertEquals(BAD, outNan[0]);
        assertEquals(26.0, outNan[1]);
    }

    @Test
    void winmapBadShiftPropagation() {
        WinMap m = new WinMap(new double[] {BAD, 20.0}, new double[] {2.0, 3.0});
        double[] out = point(transformRows(m, true, new double[] {1.0, 2.0}), 0);
        assertEquals(BAD, out[0]);
        assertEquals(26.0, out[1]);
    }

    @Test
    void winmapInvertFlag() {
        WinMap m = new WinMap(new double[] {10.0}, new double[] {2.0});
        assertFalse(m.getInvert());
        m.invert();
        assertTrue(m.getInvert());
        // "forward" on the inverted map applies the inverse: (12 - 10) / 2 = 1
        assertArrayEquals(new double[] {1.0}, point(transformRows(m, true, new double[] {12.0}), 0));
    }

    @Test
    void winmapSerialisationRoundTrip() {
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0});
        Mapping m2 = (Mapping) ChannelText.read(ChannelText.dump(m));
        assertArrayEquals(
                point(transformRows(m, true, new double[] {1.0, 2.0}), 0),
                point(transformRows(m2, true, new double[] {1.0, 2.0}), 0));
    }

    @Test
    void winmapSerialisationTextContent() {
        String text = ChannelText.dump(new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0}));
        assertTrue(text.contains("Begin WinMap"));
        assertTrue(text.contains("Sft1"));
        assertTrue(text.contains("Sft2"));
        assertTrue(text.contains("Scl1"));
        assertTrue(text.contains("Scl2"));
        assertTrue(text.contains("End WinMap"));
    }

    @Test
    void winmapSerialisationDefaultOmission() {
        String text = ChannelText.dump(new WinMap(new double[] {0.0, 0.0}, new double[] {1.0, 1.0}));
        assertTrue(text.contains("Begin WinMap"));
        assertFalse(text.contains("Sft1"));
        assertFalse(text.contains("Sft2"));
        assertFalse(text.contains("Scl1"));
        assertFalse(text.contains("Scl2"));
    }

    @Test
    void winmapValidationMismatchedLengths() {
        AstException e = assertThrows(
                AstException.class,
                () -> new WinMap(
                        2, new double[] {1.0, 2.0}, new double[] {3.0}, new double[] {0.0, 0.0}, new double[] {1.0, 1.0
                        }));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
    }

    @Test
    void winmapValidationEmpty() {
        WinMap m = new WinMap(0, new double[0], new double[0], new double[0], new double[0]);
        AstException e = assertThrows(AstException.class, () -> m.transform(new double[0][], 1, true));
        assertEquals(AstException.AST__BADWM, e.getStatus());
    }

    @Test
    void winmapBadPerAxis() {
        WinMap wm = new WinMap(new double[] {1.0, 2.0, 3.0}, new double[] {2.0, 3.0, 4.0});
        assertArrayEquals(
                new double[] {11.0, BAD, 31.0}, point(transformRows(wm, true, new double[] {5.0, BAD, 7.0}), 0));

        // The inverse is -shift/scale + input/scale, with the coefficients inverted first
        // (winmap.c Transform), so axis 2 gives C's 5.9999999999999991 rather than 6.
        double[] inv = point(transformRows(wm, false, new double[] {BAD, 20.0, 31.0}), 0);
        assertEquals(BAD, inv[0]);
        assertEquals(5.999999999999999, inv[1]);
        assertEquals(7.0, inv[2]);
    }

    @Test
    void winmapNonUniformStaysWinmap() {
        WinMap wm = new WinMap(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        assertInstanceOf(WinMap.class, wm.simplify());
    }
}
