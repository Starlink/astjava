package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The arguments of {@code rebin} and {@code rebinX} that C cannot check: a data or variance array
 * shorter than its grid and a bound array shorter than its number of dimensions give {@code
 * AST__DIMIN}, before C's checks and without touching the output, while a longer array is
 * accepted and its leading elements used, as in C and jniast; an input region outside the input
 * grid gives C's {@code AST__GBDIN}; an output array too short for C's zeroing of its first value
 * after an error in the bounds is left alone; and the generic {@code rebin} given arrays of
 * different types, a type jniast cannot rebin, a bad value of another {@code Number} type or data
 * that is not a primitive array gives jniast's {@code IllegalArgumentException}. No such call
 * reaches an {@code ArrayIndexOutOfBoundsException} or a {@code ClassCastException}. Nor does a
 * Mapping whose user-supplied transformation gives NaN for some positions: an IntraMap turns them
 * into AST__BAD, so the result is that of positions that are bad. This test has no Rust
 * counterpart; the messages of the length checks are Rust's {@code rebin}'s.
 */
class RebinArgumentsTest {

    private static final String JNIAST_MESSAGE =
            "in, in_var, out and out_var must all be arrays of the same primitive type, and badval a matching Number"
                    + " type";

    private static final int[] L = {1, 1};
    private static final int[] U = {4, 5};

    /** Checks the status and message text of {@code e}, which jniast follows with the status name. */
    private static void assertError(AstException e, int status, String text) {
        assertEquals(status, e.getStatus(), e.getMessage());
        assertEquals(text + " (" + e.getStatusName() + ")", e.getMessage());
    }

    private static AstException rebinError(
            int[] lbndIn,
            int[] ubndIn,
            double[] in,
            double[] inVar,
            int[] lbnd,
            int[] ubnd,
            double[] out,
            double[] outVar) {
        return assertThrows(
                AstException.class,
                () -> new UnitMap(2)
                        .rebinD(
                                0.0,
                                2,
                                lbndIn,
                                ubndIn,
                                in,
                                inVar,
                                Mapping.LINEAR_SPREADER,
                                false,
                                0.0,
                                100,
                                -1.0,
                                2,
                                L,
                                U,
                                lbnd,
                                ubnd,
                                out,
                                outVar));
    }

    private static double[] sevens(int n) {
        double[] a = new double[n];
        Arrays.fill(a, 7.0);
        return a;
    }

    @Test
    void dataAndVarianceArraysMustCoverTheirGrids() {
        double[] out = sevens(20);
        AstException e = rebinError(L, U, new double[19], null, L, U, out, null);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astRebinD(UnitMap): The input data array has 19 elements but its grid has 20.");
        assertEquals(7.0, out[0], "the output is not touched");

        e = rebinError(L, U, new double[20], new double[17], L, U, new double[20], new double[20]);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astRebinD(UnitMap): The input variance array has 17 elements but its grid has 20.");

        e = rebinError(L, U, new double[20], null, L, U, new double[3], null);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astRebinD(UnitMap): The output data array has 3 elements but its grid has 20.");

        e = rebinError(L, U, new double[20], new double[20], L, U, new double[20], new double[2]);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astRebinD(UnitMap): The output variance array has 2 elements but its grid has 20.");
    }

    @Test
    void longerArraysAreAcceptedAndTheirLeadingElementsUsed() {
        double[] in = new double[23];
        double[] inVar = new double[21];
        for (int k = 0; k < in.length; k++) {
            in[k] = k + 1.0;
        }
        for (int k = 0; k < inVar.length; k++) {
            inVar[k] = 0.5 * k;
        }
        double[] out = sevens(22);
        double[] outVar = sevens(25);
        new UnitMap(2)
                .rebinD(
                        0.0,
                        2,
                        L,
                        U,
                        in,
                        inVar,
                        Mapping.NEAREST_SPREADER,
                        false,
                        0.0,
                        100,
                        -1.0,
                        2,
                        L,
                        U,
                        L,
                        U,
                        out,
                        outVar);
        for (int k = 0; k < 20; k++) {
            assertEquals(in[k], out[k], "value " + k);
            assertEquals(inVar[k], outVar[k], "variance " + k);
        }
        for (int k = 20; k < out.length; k++) {
            assertEquals(7.0, out[k], "value beyond the grid " + k);
        }
        for (int k = 20; k < outVar.length; k++) {
            assertEquals(7.0, outVar[k], "variance beyond the grid " + k);
        }
    }

    @Test
    void boundArraysMustCoverTheirDimensions() {
        AstException e = rebinError(L, new int[] {4}, new double[20], null, L, U, new double[20], null);
        assertError(e, AstException.AST__DIMIN, "astRebinD(UnitMap): 1 input upper bounds supplied for 2 dimensions.");

        e = rebinError(L, U, new double[20], null, new int[] {1}, U, new double[20], null);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astRebinD(UnitMap): 1 input region lower bounds supplied for 2 dimensions.");
    }

    @Test
    void inputRegionMustLieInTheInputGrid() {
        double[] out = sevens(20);
        AstException e = rebinError(L, U, new double[20], null, L, new int[] {4, 6}, out, null);
        assertError(
                e,
                AstException.AST__GBDIN,
                "astRebinD(UnitMap): Upper bound of input region (6) exceeds corresponding bound of input grid"
                        + " (5).\nError in input dimension 2.");
        // C zeroes the first output value after an error in its checks of the bounds.
        assertEquals(0.0, out[0], "first output value");
        assertEquals(7.0, out[1], "second output value");

        e = rebinError(L, U, new double[20], null, new int[] {0, 1}, U, new double[20], null);
        assertError(
                e,
                AstException.AST__GBDIN,
                "astRebinD(UnitMap): Lower bound of input region (0) is less than corresponding bound of input"
                        + " grid (1).\nError in input dimension 1.");
    }

    @Test
    void anEmptyOutputIsLeftAloneAfterAnErrorInItsBounds() {
        AstException e = assertThrows(
                AstException.class,
                () -> new UnitMap(2)
                        .rebinD(
                                0.0,
                                2,
                                L,
                                U,
                                new double[20],
                                null,
                                Mapping.LINEAR_SPREADER,
                                false,
                                0.0,
                                100,
                                -1.0,
                                2,
                                new int[] {5, 1},
                                U,
                                L,
                                U,
                                new double[0],
                                null));
        assertError(
                e,
                AstException.AST__GBDIN,
                "astRebinD(UnitMap): Lower bound of output grid (5) exceeds corresponding upper bound (4).\nError in"
                        + " output dimension 1.");
    }

    private static IllegalArgumentException generic(Object in, Object inVar, Number badval, Object out, Object outVar) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> new UnitMap(2)
                        .rebin(
                                0.0,
                                2,
                                L,
                                U,
                                in,
                                inVar,
                                Mapping.NEAREST_SPREADER,
                                false,
                                0.0,
                                100,
                                badval,
                                2,
                                L,
                                U,
                                L,
                                U,
                                out,
                                outVar));
    }

    @Test
    void genericRebinNeedsMatchingTypes() {
        assertEquals(
                JNIAST_MESSAGE,
                generic(new double[20], null, -1.0f, new double[20], null).getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic(new double[20], null, -1.0, new float[20], null).getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic(new int[20], new long[20], -1, new int[20], new int[20]).getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic(new float[20], new float[20], -1.0f, new float[20], new double[20])
                        .getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic(new long[20], null, -1L, new long[20], null).getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic(new short[20], null, (short) -1, new short[20], null).getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic(new byte[20], null, (byte) -1, new byte[20], null).getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic("not an array", null, -1.0, new double[20], null).getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic(new char[20], null, -1.0, new char[20], null).getMessage());
    }

    @Test
    void genericRebinChoosesTheTypeOfTheData() {
        int[] in = new int[20];
        for (int k = 0; k < 20; k++) {
            in[k] = k * 3;
        }
        int[] out = new int[20];
        new UnitMap(2)
                .rebin(0.0, 2, L, U, in, null, Mapping.NEAREST_SPREADER, false, 0.0, 100, -1, 2, L, U, L, U, out, null);
        for (int k = 0; k < 20; k++) {
            assertEquals(k * 3, out[k], "pixel " + k);
        }
    }

    /** The values and variances of {@code rebinD} through {@code map}. */
    private static double[][] rebinWith(Mapping map, Mapping.Spreader spread) {
        int[] lbnd = {1};
        int[] ubnd = {10};
        double[] in = new double[10];
        double[] inVar = new double[10];
        for (int k = 0; k < 10; k++) {
            in[k] = k + 1.0;
            inVar[k] = 0.5 * k + 0.25;
        }
        double[] out = new double[10];
        double[] outVar = new double[10];
        map.rebinD(
                0.0, 1, lbnd, ubnd, in, inVar, spread, false, 0.0, 100, -1.0, 1, lbnd, ubnd, lbnd, ubnd, out, outVar);
        return new double[][] {out, outVar};
    }

    /**
     * Rebinning through an IntraMap whose Transformer gives NaN beyond 5, on a grid whose lower
     * bound is 1, gives what it gives when the Transformer gives AST__BAD there, for the nearest
     * and linear schemes.
     */
    @Test
    void nanPositionsAreBadPositions() {
        Mapping nanMap = new IntraMap(new ResampleArgumentsTest.ShiftUpToFive(Double.NaN));
        Mapping badMap = new IntraMap(new ResampleArgumentsTest.ShiftUpToFive(AstObject.AST__BAD));
        Mapping.Spreader[] spreads = {Mapping.NEAREST_SPREADER, Mapping.LINEAR_SPREADER};
        for (Mapping.Spreader spread : spreads) {
            double[][] want = rebinWith(badMap, spread);
            double[][] got = rebinWith(nanMap, spread);
            assertFalse(
                    Arrays.equals(rebinWith(new ShiftMap(new double[] {0.25}), spread)[0], want[0]),
                    "the bad positions change the result");
            for (int i = 0; i < 2; i++) {
                assertArrayEquals(want[i], got[i]);
            }
        }
    }
}
