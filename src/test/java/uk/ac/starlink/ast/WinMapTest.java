package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Disabled;
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
 * <p>A WinMap's rate is C's {@code Rate} (winmap.c): the scale factor, or its reciprocal when
 * inverted, where the output is the input, and zero elsewhere. The Rust tests of the pairwise
 * merge ({@code try_merge}) offer the two Mappings here, as a list, to the WinMap's {@code
 * MapMerge} (winmap.c).
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

    @Test
    void winmapRateDiagonal() {
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0});
        assertEquals(2.0, m.rate(new double[] {1.0, 1.0}, 1, 1), 1e-10);
        assertEquals(3.0, m.rate(new double[] {1.0, 1.0}, 2, 2), 1e-10);
    }

    @Test
    void winmapRateOffDiagonal() {
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0});
        assertEquals(0.0, m.rate(new double[] {1.0, 1.0}, 1, 2));
        assertEquals(0.0, m.rate(new double[] {1.0, 1.0}, 2, 1));
    }

    @Test
    void winmapRateInverted() {
        WinMap m = new WinMap(new double[] {10.0, 20.0}, new double[] {2.0, 3.0});
        m.invert();
        assertEquals(0.5, m.rate(new double[] {1.0, 1.0}, 1, 1), 1e-10);
        assertEquals(1.0 / 3.0, m.rate(new double[] {1.0, 1.0}, 2, 2), 1e-10);
    }

    /**
     * An undefined axis has a bad scale factor, which C's {@code Rate} (winmap.c) gives as it is
     * and, inverted, as bad.
     */
    @Test
    void anUndefinedAxisHasABadRate() {
        WinMap m = new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {0.0, 1.0}, new double[] {0.0, 0.0}, new double[] {1.0, 1.0});
        assertEquals(AstObject.AST__BAD, m.rate(new double[] {1.0, 1.0}, 1, 1));
        assertEquals(1.0, m.rate(new double[] {1.0, 1.0}, 2, 2));
        m.invert();
        assertEquals(AstObject.AST__BAD, m.rate(new double[] {1.0, 1.0}, 1, 1));
    }

    /** The list of {@code w} and {@code other}, both applied forward, after {@code w}'s merge. */
    private static MergeList merged(WinMap w, Mapping other, boolean series) {
        MergeList list = new MergeList(Arrays.asList(w, other), Arrays.asList(false, false));
        w.mapMerge(list, 0, series);
        return list;
    }

    @Test
    void winmapParallelMerge() {
        WinMap w1 = new WinMap(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        WinMap w2 = new WinMap(new double[] {5.0}, new double[] {6.0});
        MergeList list = merged(w1, w2, false);
        assertEquals(1, list.size());
        Mapping m = list.map(0);
        assertEquals(3, m.getNin());
        assertEquals(3, m.getNout());
        double[][] out = transformRows(m, true, new double[] {1.0, 2.0, 3.0});
        assertArrayEquals(new double[] {1.0 + 3.0 * 1.0, 2.0 + 4.0 * 2.0, 5.0 + 6.0 * 3.0}, point(out, 0));
    }

    @Test
    void winmapParallelMergeWithZoommap() {
        WinMap w = new WinMap(new double[] {1.0}, new double[] {2.0});
        MergeList list = merged(w, new ZoomMap(2, 3.0), false);
        assertEquals(1, list.size());
        Mapping m = list.map(0);
        assertEquals(3, m.getNin());
        double[][] out = transformRows(m, true, new double[] {5.0, 7.0, 9.0});
        assertArrayEquals(new double[] {1.0 + 2.0 * 5.0, 3.0 * 7.0, 3.0 * 9.0}, point(out, 0));
    }

    @Test
    void winmapMergeWithDiagonalMatrix() {
        WinMap wm = new WinMap(new double[] {1.0, 1.0}, new double[] {2.0, 2.0});
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{3.0, 0.0}, {0.0, 4.0}});
        MergeList list = merged(wm, mm, true);
        assertEquals(1, list.size());
        double[][] expected = transformRows(new CmpMap(wm, mm, true), true, new double[] {5.0, 7.0});
        double[][] actual = transformRows(list.map(0), true, new double[] {5.0, 7.0});
        assertArrayEquals(point(expected, 0), point(actual, 0), 1e-10);
    }

    @Test
    void winmapNoMergeWithFullMatrix() {
        WinMap wm = new WinMap(new double[] {1.0, 1.0}, new double[] {2.0, 2.0});
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {3.0, 4.0}});
        MergeList list = new MergeList(Arrays.asList(wm, mm), Arrays.asList(false, false));
        assertEquals(-1, wm.mapMerge(list, 0, true));
        assertEquals(2, list.size());
        assertSame(wm, list.map(0));
        assertSame(mm, list.map(1));
    }

    @Test
    void winmapSimplifiesToShiftmap() {
        Mapping s = new WinMap(new double[] {3.0, 5.0}, new double[] {1.0, 1.0}).simplify();
        assertInstanceOf(ShiftMap.class, s);
        assertEquals(2, s.getNin());
    }

    @Test
    @Disabled("needs MatrixMap merge (a uniform diagonal MatrixMap becoming a ZoomMap): Task 4")
    void winmapSimplifiesToZoommap() {
        Mapping s = new WinMap(new double[] {0.0, 0.0}, new double[] {4.0, 4.0}).simplify();
        assertInstanceOf(ZoomMap.class, s);
    }

    @Test
    @Disabled("needs MatrixMap merge (a unit MatrixMap becoming a UnitMap): Task 4")
    void winmapSimplifiesToUnitmap() {
        Mapping s = new WinMap(new double[] {0.0, 0.0}, new double[] {1.0, 1.0}).simplify();
        assertInstanceOf(UnitMap.class, s);
    }

    /**
     * C winmap.c {@code WinWin}: a WinMap composed in series with its own inverse simplifies to
     * the exact identity, since the test for equal and opposite WinMaps compares the stored terms,
     * so never meets the drift {@code scale * (1 / scale)} would. The scales are not exactly
     * representable.
     */
    @Test
    @Disabled("needs MatrixMap merge (a unit MatrixMap becoming a UnitMap): Task 4")
    void winmapCancelsWithOwnInverseExactly() {
        WinMap w = new WinMap(new double[] {3.5, -2.0}, new double[] {0.999_999_999_977_888, 1.000_000_3});
        WinMap winv = (WinMap) w.copy();
        winv.invert();
        assertInstanceOf(UnitMap.class, new CmpMap(w, winv, true).simplify());
    }

    /** The leaf classes of a dump, CmpMaps left out, in order. */
    private static String[] leafClasses(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String l = line.trim();
            if (l.startsWith("Begin ")) {
                String rest = l.substring("Begin ".length());
                int end = rest.indexOf(' ');
                String cls = end < 0 ? rest : rest.substring(0, end);
                if (!"CmpMap".equals(cls)) {
                    out.add(cls);
                }
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * The FITS-WCS read path for a header with an off-diagonal CD matrix builds WinMap(-CRPIX, unit
     * scale), MatrixMap(CD), UnitMap, MatrixMap(deg2rad, diagonal), WcsMap(TAN) inverted, and C
     * simplifies that to a leading ShiftMap and one full MatrixMap carrying CD times pi/180. The
     * matrix values are the C oracle's.
     */
    @Test
    @Disabled("needs MatrixMap merge: Task 4")
    void offdiagonalCdChainFusesDeg2radIntoTheMatrixmap() {
        double[] crpix = {75.910_204, 60.359_184};
        WinMap win = new WinMap(new double[] {-crpix[0], -crpix[1]}, new double[] {1.0, 1.0});
        MatrixMap cd = new MatrixMap(
                2, 2, new double[][] {{-0.001_831_773_954, -8.541_702_226e-4}, {0.000_854_170_222_6, -0.001_831_773_954}
                });
        double d2r = Math.PI / 180.0;
        MatrixMap deg2rad = new MatrixMap(2, 2, new double[][] {{d2r, 0.0}, {0.0, d2r}});
        WcsMap wcs = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wcs.invert();
        CmpMap c1 = new CmpMap(win, cd, true);
        CmpMap c2 = new CmpMap(c1, new UnitMap(2), true);
        CmpMap c3 = new CmpMap(c2, deg2rad, true);
        CmpMap chain = new CmpMap(c3, wcs, true);

        String text = ChannelText.dump(assertInstanceOf(CmpMap.class, chain.simplify()));
        assertArrayEquals(new String[] {"ShiftMap", "MatrixMap", "WcsMap"}, leafClasses(text), text);
        for (String token : new String[] {
            "Sft1 = -75.910203999999993",
            "Sft2 = -60.359183999999999",
            "M0 = -3.1970486649575151e-05",
            "M1 = -1.4908082756862879e-05",
            "M2 = 1.4908082756862879e-05",
            "M3 = -3.1970486649575151e-05",
            "Form = \"Full\""
        }) {
            assertTrue(text.contains(token), "expected " + token + " in:\n" + text);
        }
    }

    /**
     * The FITS-WCS read path for the ZPX header ({@code fixtures/wcsconv/inputs/zpx.head}) builds
     * WinMap(-CRPIX, unit scale), MatrixMap(PC), MatrixMap(CDELT), UnitMap, MatrixMap(deg2rad,
     * diagonal), WcsMap(ZPN) inverted. C keeps the degrees to radians factor in a WinMap's scales
     * and leaves the product PC times CDELT in degrees, before the WinMap. The values are the C
     * oracle's.
     */
    @Test
    @Disabled("needs MatrixMap merge: Task 4")
    void nonuniformCdeltChainKeepsDeg2radInTheWinmap() {
        double[] crpix = {-319.077_517_318_151, 3_035.649_826_355_72};
        WinMap win = new WinMap(new double[] {-crpix[0], -crpix[1]}, new double[] {1.0, 1.0});
        MatrixMap pc = new MatrixMap(2, 2, new double[][] {
            {0.017_123_380_684_552_016, 0.999_853_384_168_865}, {-0.999_844_421_650_356_9, 0.017_638_948_230_076_08}
        });
        MatrixMap cdelt =
                new MatrixMap(2, 2, new double[][] {{-9.253_475_713_904_881e-5, 0.0}, {0.0, 9.255_325_538_402_174e-5}});
        double d2r = Math.PI / 180.0;
        MatrixMap deg2rad = new MatrixMap(2, 2, new double[][] {{d2r, 0.0}, {0.0, d2r}});
        WcsMap wcs = new WcsMap(2, WcsMap.AST__ZPN, 1, 2);
        wcs.invert();
        CmpMap c1 = new CmpMap(win, pc, true);
        CmpMap c2 = new CmpMap(c1, cdelt, true);
        CmpMap c3 = new CmpMap(c2, new UnitMap(2), true);
        CmpMap c4 = new CmpMap(c3, deg2rad, true);
        CmpMap chain = new CmpMap(c4, wcs, true);

        String text = ChannelText.dump(assertInstanceOf(CmpMap.class, chain.simplify()));
        assertArrayEquals(new String[] {"MatrixMap", "WinMap", "WcsMap"}, leafClasses(text), text);
        for (String token : new String[] {
            "M0 = -1.5845078730445003e-06",
            "M1 = -9.2521190078721999e-05",
            "M2 = -9.2538856101294999e-05",
            "M3 = 1.6325420802437699e-06",
            "Form = \"Full\"",
            "Sft1 = 0.0048931414519699694",
            "Scl1 = 0.017453292519943295",
            "Sft2 = -0.00060184004538763232",
            "Scl2 = 0.017453292519943295"
        }) {
            assertTrue(text.contains(token), "expected " + token + " in:\n" + text);
        }
    }

    /**
     * Merging a WinMap with an inverted ShiftMap of {@code +DBL_MAX}, whose shift is then the bad
     * value, gives a WinMap with bad terms, as C's {@code WinShift} does (checked against the C
     * library: the simplified Mapping is a WinMap of bad terms, and maps 0.0013 to {@code
     * AST__BAD}).
     */
    @Test
    void winmapMergeWithABadTermIsUndefined() {
        Mapping w = new CmpMap(new ZoomMap(1, 1e10), new ShiftMap(new double[] {Double.MAX_VALUE}), true).simplify();
        ShiftMap s = new ShiftMap(new double[] {Double.MAX_VALUE});
        s.invert();
        Mapping simplified = new CmpMap(w, s, true).simplify();
        assertInstanceOf(WinMap.class, simplified);
        String dump = ChannelText.dump(simplified);
        assertTrue(dump.contains("Sft1 = <bad>") && dump.contains("Scl1 = <bad>"), dump);
        assertEquals(BAD, simplified.tran1(1, new double[] {0.0013}, true)[0]);
    }
}
