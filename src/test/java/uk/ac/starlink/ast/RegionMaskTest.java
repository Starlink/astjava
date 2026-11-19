package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.CompoundProbe.check;
import static uk.ac.starlink.ast.CompoundProbe.circle2;
import static uk.ac.starlink.ast.CompoundProbe.interval1;
import static uk.ac.starlink.ast.CompoundProbe.maskcount;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_masking.rs}: {@link Region#mask} of a Circle inside and
 * outside, with a UnitMap and a ZoomMap, of {@code int} data, of a three-dimensional Prism, and its
 * errors; the counts are checked against {@code probes/region_compound_probe.c}.
 *
 * <p>The pixel centres are integers, and every one is either on a Circle's boundary to the last bit
 * (a distance of exactly 1 or 2, computed from integers) or at least 0.05 from it, so the counts do
 * not depend on rounding.
 */
class RegionMaskTest {

    @Test
    void maskOutsideCircle2d() {
        Circle c = circle2(3.0, 3.0, 2.0);
        double[] data = new double[25];
        java.util.Arrays.fill(data, 1.0);
        int count = c.maskD(new UnitMap(2), false, 2, new int[] {1, 1}, new int[] {5, 5}, data, 0.0);
        assertEquals(12, count);
        assertEquals(1.0, data[(3 - 1) * 5 + (3 - 1)]);
        assertEquals(0.0, data[0]);
        check(
                "mask_outside_circle",
                maskcount(circle2(3.0, 3.0, 2.0), new UnitMap(2), false, new int[] {1, 1}, new int[] {5, 5}, 0.0));
    }

    @Test
    void maskIntegerData() {
        Circle c = circle2(2.0, 2.0, 1.0);
        int[] data = new int[9];
        int count = c.maskI(new UnitMap(2), true, 2, new int[] {1, 1}, new int[] {3, 3}, data, -999);
        assertEquals(1, count);
        assertEquals(-999, data[4]);
        assertEquals(0, data[0]);
        check(
                "mask_integer_circle",
                maskcount(circle2(2.0, 2.0, 1.0), new UnitMap(2), true, new int[] {1, 1}, new int[] {3, 3}, -999.0));
    }

    @Test
    void maskWithZoomMapping() {
        Circle c = circle2(2.5, 2.5, 1.5);
        double[] data = new double[100];
        int count = c.maskD(new ZoomMap(2, 2.0), true, 2, new int[] {1, 1}, new int[] {10, 10}, data, -1.0);
        assertEquals(-1.0, data[(5 - 1) * 10 + (5 - 1)]);
        assertEquals(0.0, data[0]);
        assertTrue(count > 0);
        check(
                "mask_zoom_circle",
                maskcount(
                        circle2(2.5, 2.5, 1.5), new ZoomMap(2, 2.0), true, new int[] {1, 1}, new int[] {10, 10}, -1.0));
    }

    @Test
    void mask3dPrism() {
        Prism p = new Prism(circle2(3.0, 3.0, 1.0), interval1(2.0, 4.0));
        double[] data = new double[125];
        int count = p.maskD(new UnitMap(3), true, 3, new int[] {1, 1, 1}, new int[] {5, 5, 5}, data, -1.0);
        assertEquals(15, count);
        assertEquals(-1.0, data[(3 - 1) * 25 + (3 - 1) * 5 + (3 - 1)]);
        assertEquals(0.0, data[(1 - 1) * 25 + (3 - 1) * 5 + (3 - 1)]);
        assertEquals(0.0, data[(3 - 1) * 25 + (1 - 1) * 5 + (1 - 1)]);
        check(
                "mask_prism",
                maskcount(
                        new Prism(circle2(3.0, 3.0, 1.0), interval1(2.0, 4.0)),
                        new UnitMap(3),
                        true,
                        new int[] {1, 1, 1},
                        new int[] {5, 5, 5},
                        -1.0));
    }

    @Test
    void maskMismatchedBoundsLengths() {
        Circle c = circle2(0.0, 0.0, 1.0);
        assertThrows(
                AstException.class,
                () -> c.maskD(new UnitMap(2), true, 3, new int[] {1, 1}, new int[] {5, 5, 5}, new double[10], 0.0));
    }

    @Test
    void maskWrongMappingNin() {
        Circle c = circle2(0.0, 0.0, 1.0);
        assertThrows(
                AstException.class,
                () -> c.maskD(new UnitMap(3), true, 2, new int[] {1, 1}, new int[] {5, 5}, new double[25], 0.0));
    }

    @Test
    void maskWrongDataLength() {
        Circle c = circle2(0.0, 0.0, 1.0);
        assertThrows(
                AstException.class,
                () -> c.maskD(new UnitMap(2), true, 2, new int[] {1, 1}, new int[] {5, 5}, new double[10], 0.0));
    }

    @Test
    void maskUbndLessThanLbnd() {
        Circle c = circle2(0.0, 0.0, 1.0);
        assertThrows(
                AstException.class,
                () -> c.maskD(new UnitMap(2), true, 2, new int[] {5, 1}, new int[] {1, 5}, new double[1], 0.0));
    }
}
