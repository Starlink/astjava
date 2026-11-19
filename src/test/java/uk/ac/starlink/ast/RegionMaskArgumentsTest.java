package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The arguments of {@link Region#mask} and {@link Region#maskD} and the other types, which follow
 * the rules of {@link Mapping#resample}: a data array shorter than its grid, or a bound array
 * shorter than {@code ndim}, is {@code AST__DIMIN}, which C cannot check, and a longer one is
 * accepted with its leading elements used; C's checks of the dimensions, the Mapping and the bounds
 * come first where C makes them, with C's status and messages; a null argument is a {@code
 * NullPointerException} naming it before the array is touched; and the generic {@code mask} given
 * an array and a value of different types is jniast's {@code IllegalArgumentException}. No
 * argument gives an {@code ArrayIndexOutOfBoundsException}.
 */
class RegionMaskArgumentsTest {

    private static final int[] LB = {1, 1};
    private static final int[] UB = {6, 6};

    private static Region box() {
        return new Box(new Frame(2), 1, new double[] {2.0, 2.0}, new double[] {4.0, 4.0}, null);
    }

    private static double[] filled(int n, double v) {
        double[] d = new double[n];
        Arrays.fill(d, v);
        return d;
    }

    @Test
    void aShortDataArrayIsDimin() {
        double[] d = filled(35, 0.5);
        AstException e = assertThrows(AstException.class, () -> box().maskD(null, false, 2, LB, UB, d, 9.0));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertEquals("astMaskD(Box): The data array has 35 elements but its grid has 36.", e.getMsgText());
        assertArrayEquals(filled(35, 0.5), d);
    }

    @Test
    void aLongDataArrayIsAcceptedAndItsTailLeftAlone() {
        double[] exact = filled(36, 0.5);
        double[] longer = filled(40, 0.5);
        int n1 = box().maskD(null, false, 2, LB, UB, exact, 9.0);
        int n2 = box().maskD(null, false, 2, LB, UB, longer, 9.0);
        assertEquals(n1, n2);
        for (int i = 0; i < 36; i++) {
            assertEquals(exact[i], longer[i], "element " + i);
        }
        for (int i = 36; i < 40; i++) {
            assertEquals(0.5, longer[i], "element " + i);
        }
    }

    @Test
    void aGridTooLargeForAnArrayIsDimin() {
        double[] d = filled(36, 0.5);
        AstException e = assertThrows(
                AstException.class, () -> box().maskD(null, false, 2, LB, new int[] {100000, 100000}, d, 9.0));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertEquals("astMaskD(Box): The data array has 36 elements but its grid has 10000000000.", e.getMsgText());
        assertArrayEquals(filled(36, 0.5), d);
    }

    /** A mask of {@code reg} on a grid whose pixel count overflows a {@code long}. */
    private static void checkWrappingGrid(Region reg, int ndim, boolean inside) {
        int[] lb = new int[ndim];
        int[] ub = new int[ndim];
        Arrays.fill(lb, Integer.MIN_VALUE);
        Arrays.fill(ub, Integer.MAX_VALUE);
        double[] d = filled(4, 0.5);
        AstException e =
                assertThrows(AstException.class, () -> reg.maskD(null, inside, ndim, lb, ub, d, 9.0), "ndim " + ndim);
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        String grid = ndim == 2 ? "18446744073709551616" : "79228162514264337593543950336";
        assertEquals("astMaskD(Box): The data array has 4 elements but its grid has " + grid + ".", e.getMsgText());
        assertArrayEquals(filled(4, 0.5), d);
    }

    @Test
    void aGridWhosePixelCountOverflowsIsDimin() {
        Region outside2 = new Box(new Frame(2), 1, new double[] {1e10, 1e10}, new double[] {2e10, 2e10}, null);
        checkWrappingGrid(outside2, 2, false);
        checkWrappingGrid(box(), 2, false);
        checkWrappingGrid(box(), 2, true);
        Region outside3 =
                new Box(new Frame(3), 1, new double[] {1e10, 1e10, 1e10}, new double[] {2e10, 2e10, 2e10}, null);
        checkWrappingGrid(outside3, 3, false);
        Region inside3 = new Box(new Frame(3), 1, new double[] {2, 2, 2}, new double[] {4, 4, 4}, null);
        checkWrappingGrid(inside3, 3, true);
    }

    @Test
    void shortBoundArraysAreDimin() {
        double[] d = filled(36, 0.5);
        AstException e = assertThrows(AstException.class, () -> box().maskD(null, true, 2, new int[] {1}, UB, d, 9.0));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertEquals("astMaskD(Box): 1 lower bounds supplied for 2 dimensions.", e.getMsgText());
        e = assertThrows(AstException.class, () -> box().maskD(null, true, 2, LB, new int[] {6}, d, 9.0));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertEquals("astMaskD(Box): 1 upper bounds supplied for 2 dimensions.", e.getMsgText());
        assertArrayEquals(filled(36, 0.5), d);
    }

    @Test
    void longBoundArraysUseTheirLeadingValues() {
        double[] a = filled(36, 0.5);
        double[] b = filled(36, 0.5);
        int n1 = box().maskD(null, true, 2, LB, UB, a, 9.0);
        int n2 = box().maskD(null, true, 2, new int[] {1, 1, 7}, new int[] {6, 6, -3}, b, 9.0);
        assertEquals(n1, n2);
        assertArrayEquals(a, b);
    }

    @Test
    void cChecksComeBeforeTheDataLength() {
        double[] d = new double[0];
        AstException e = assertThrows(
                AstException.class, () -> box().maskD(null, true, 2, new int[] {4, 1}, new int[] {3, 6}, d, 9.0));
        assertEquals(AstException.AST__GBDIN, e.getStatus());
        e = assertThrows(AstException.class, () -> box().maskD(new UnitMap(2), true, 3, LB, UB, d, 9.0));
        assertEquals(AstException.AST__NGDIN, e.getStatus());
        assertEquals(
                "astMaskD(Box): Bad number of mapping outputs (2).\n"
                        + "The pixel grid requires 3 coordinate values to specify a position.",
                e.getMsgText());
        e = assertThrows(AstException.class, () -> box().maskD(null, true, -1, LB, UB, d, 9.0));
        assertEquals(AstException.AST__NGDIN, e.getStatus());
    }

    @Test
    void aNullArgumentIsNamedBeforeTheArrayIsTouched() {
        double[] d = filled(36, 0.5);
        NullPointerException e =
                assertThrows(NullPointerException.class, () -> box().maskD(null, false, 2, null, UB, d, 9.0));
        assertEquals("lbnd", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> box().maskD(null, false, 2, LB, null, d, 9.0));
        assertEquals("ubnd", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> box().maskD(null, false, 2, LB, UB, null, 9.0));
        assertEquals("in", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> box().mask(null, false, 2, LB, UB, null, 9.0));
        assertEquals("in", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> box().mask(null, false, 2, null, UB, d, 9.0));
        assertEquals("lbnd", e.getMessage());
        assertArrayEquals(filled(36, 0.5), d);
    }

    @Test
    void theGenericMaskRejectsMismatchedTypesAsJniastDoes() {
        double[] d = filled(36, 0.5);
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> box().mask(null, false, 2, LB, UB, d, 9.0f));
        assertEquals("Bad class class [D for map 'in' param", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> box().mask(null, false, 2, LB, UB, d, null));
        assertEquals("Bad class class [D for map 'in' param", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> box().mask(null, false, 2, LB, UB, "text", 9.0));
        assertEquals("Bad class class java.lang.String for map 'in' param", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> box().mask(null, false, 2, LB, UB, new char[36], 9.0));
        assertEquals("Bad class class [C for map 'in' param", e.getMessage());
        assertArrayEquals(filled(36, 0.5), d);
    }

    @Test
    void theGenericMaskDispatchesOnTheArrayType() {
        double[] d = filled(36, 0.5);
        double[] dd = filled(36, 0.5);
        assertEquals(box().maskD(null, true, 2, LB, UB, dd, 9.0), box().mask(null, true, 2, LB, UB, d, 9.0));
        assertArrayEquals(dd, d);
        float[] f = new float[36];
        float[] ff = new float[36];
        assertEquals(box().maskF(null, true, 2, LB, UB, ff, 9.0f), box().mask(null, true, 2, LB, UB, f, 9.0f));
        assertArrayEquals(ff, f);
        long[] l = new long[36];
        long[] ll = new long[36];
        assertEquals(box().maskL(null, false, 2, LB, UB, ll, 9L), box().mask(null, false, 2, LB, UB, l, 9L));
        assertArrayEquals(ll, l);
        int[] i = new int[36];
        int[] ii = new int[36];
        assertEquals(box().maskI(null, false, 2, LB, UB, ii, 9), box().mask(null, false, 2, LB, UB, i, 9));
        assertArrayEquals(ii, i);
        short[] s = new short[36];
        short[] ss = new short[36];
        assertEquals(
                box().maskS(null, true, 2, LB, UB, ss, (short) 9), box().mask(null, true, 2, LB, UB, s, (short) 9));
        assertArrayEquals(ss, s);
        byte[] b = new byte[36];
        byte[] bb = new byte[36];
        assertEquals(box().maskB(null, true, 2, LB, UB, bb, (byte) 9), box().mask(null, true, 2, LB, UB, b, (byte) 9));
        assertArrayEquals(bb, b);
    }
}
