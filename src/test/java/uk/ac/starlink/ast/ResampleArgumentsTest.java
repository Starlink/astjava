package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The arguments of {@code resample} and {@code resampleX} that C cannot check: a data or variance
 * array shorter than its grid and a bound array shorter than its number of dimensions give {@code
 * AST__DIMIN}, while a longer array is accepted and its leading elements used, as in C and jniast; an output region outside the output grid gives C's {@code
 * AST__GBDIN}; and the generic {@code resample} given arrays of different types, a bad value of
 * another {@code Number} type or data that is not a primitive array gives jniast's {@code
 * IllegalArgumentException}. No such call reaches an {@code ArrayIndexOutOfBoundsException} or a
 * {@code ClassCastException}. Nor does a Mapping whose user-supplied transformation gives NaN for
 * some positions: an IntraMap turns them into AST__BAD, so the result is that of positions that
 * are bad. This test has no Rust counterpart; the messages of the length checks are Rust's {@code
 * resample}'s.
 */
class ResampleArgumentsTest {

    private static final String JNIAST_MESSAGE =
            "in, in_var, out, out_var must be all arrays of the same primitive type, and badval a matching Number type";

    private static final int[] L = {1, 1};
    private static final int[] U = {4, 5};

    /** Checks the status and message text of {@code e}, which jniast follows with the status name. */
    private static void assertError(AstException e, int status, String text) {
        assertEquals(status, e.getStatus(), e.getMessage());
        assertEquals(text + " (" + e.getStatusName() + ")", e.getMessage());
    }

    private static AstException dimin(double[] in, double[] inVar, double[] out, double[] outVar) {
        return assertThrows(
                AstException.class,
                () -> new UnitMap(2)
                        .resampleD(
                                2,
                                L,
                                U,
                                in,
                                inVar,
                                Mapping.LINEAR_INTERPOLATOR,
                                new ResampleFlags(),
                                0.0,
                                100,
                                -1.0,
                                2,
                                L,
                                U,
                                L,
                                U,
                                out,
                                outVar));
    }

    @Test
    void dataAndVarianceArraysMustCoverTheirGrids() {
        AstException e = dimin(new double[19], null, new double[20], null);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astResampleD(UnitMap): The input data array has 19 elements but its grid has 20.");

        e = dimin(new double[20], new double[17], new double[20], new double[20]);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astResampleD(UnitMap): The input variance array has 17 elements but its grid has 20.");

        e = dimin(new double[20], null, new double[3], null);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astResampleD(UnitMap): The output data array has 3 elements but its grid has 20.");

        e = dimin(new double[20], new double[20], new double[20], new double[2]);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astResampleD(UnitMap): The output variance array has 2 elements but its grid has 20.");
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
        double[] out = new double[22];
        double[] outVar = new double[25];
        Arrays.fill(out, -7.0);
        Arrays.fill(outVar, -7.0);
        int nbad = new UnitMap(2)
                .resampleD(
                        2,
                        L,
                        U,
                        in,
                        inVar,
                        Mapping.NEAREST_INTERPOLATOR,
                        new ResampleFlags(),
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
        assertEquals(0, nbad);
        for (int k = 0; k < 20; k++) {
            assertEquals(in[k], out[k], "value " + k);
            assertEquals(inVar[k], outVar[k], "variance " + k);
        }
        for (int k = 20; k < out.length; k++) {
            assertEquals(-7.0, out[k], "value beyond the grid " + k);
        }
        for (int k = 20; k < outVar.length; k++) {
            assertEquals(-7.0, outVar[k], "variance beyond the grid " + k);
        }
    }

    @Test
    void boundArraysMustCoverTheirDimensions() {
        AstException e = assertThrows(
                AstException.class,
                () -> new UnitMap(2)
                        .resampleD(
                                2,
                                L,
                                new int[] {4},
                                new double[20],
                                null,
                                Mapping.NEAREST_INTERPOLATOR,
                                new ResampleFlags(),
                                0.0,
                                100,
                                -1.0,
                                2,
                                L,
                                U,
                                L,
                                U,
                                new double[20],
                                null));
        assertError(
                e, AstException.AST__DIMIN, "astResampleD(UnitMap): 1 input upper bounds supplied for 2 dimensions.");

        e = assertThrows(
                AstException.class,
                () -> new UnitMap(2)
                        .resampleD(
                                2,
                                L,
                                U,
                                new double[20],
                                null,
                                Mapping.NEAREST_INTERPOLATOR,
                                new ResampleFlags(),
                                0.0,
                                100,
                                -1.0,
                                2,
                                L,
                                U,
                                new int[] {1},
                                U,
                                new double[20],
                                null));
        assertError(
                e,
                AstException.AST__DIMIN,
                "astResampleD(UnitMap): 1 output region lower bounds supplied for 2 dimensions.");
    }

    @Test
    void outputRegionMustLieInTheOutputGrid() {
        AstException e = assertThrows(
                AstException.class,
                () -> new UnitMap(2)
                        .resampleD(
                                2,
                                L,
                                U,
                                new double[20],
                                null,
                                Mapping.NEAREST_INTERPOLATOR,
                                new ResampleFlags(),
                                0.0,
                                100,
                                -1.0,
                                2,
                                L,
                                U,
                                L,
                                new int[] {4, 6},
                                new double[20],
                                null));
        assertError(
                e,
                AstException.AST__GBDIN,
                "astResampleD(UnitMap): Upper bound of output region (6) exceeds corresponding bound of output grid"
                        + " (5).\nError in output dimension 2.");

        e = assertThrows(
                AstException.class,
                () -> new UnitMap(2)
                        .resampleD(
                                2,
                                L,
                                U,
                                new double[20],
                                null,
                                Mapping.NEAREST_INTERPOLATOR,
                                new ResampleFlags(),
                                0.0,
                                100,
                                -1.0,
                                2,
                                L,
                                U,
                                new int[] {0, 1},
                                U,
                                new double[20],
                                null));
        assertError(
                e,
                AstException.AST__GBDIN,
                "astResampleD(UnitMap): Lower bound of output region (0) is less than corresponding bound of output"
                        + " grid (1).\nError in output dimension 1.");
    }

    private static IllegalArgumentException generic(Object in, Object inVar, Number badval, Object out, Object outVar) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> new UnitMap(2)
                        .resample(
                                2,
                                L,
                                U,
                                in,
                                inVar,
                                Mapping.NEAREST_INTERPOLATOR,
                                new ResampleFlags(),
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
    void genericResampleNeedsMatchingTypes() {
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
                generic(new short[20], null, (short) -1, new short[20], new byte[20])
                        .getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic("not an array", null, -1.0, new double[20], null).getMessage());
        assertEquals(
                JNIAST_MESSAGE,
                generic(new char[20], null, -1.0, new char[20], null).getMessage());
    }

    @Test
    void genericResampleChoosesTheTypeOfTheData() {
        int[] in = new int[20];
        for (int k = 0; k < 20; k++) {
            in[k] = k * 3;
        }
        int[] out = new int[20];
        int nbad = new UnitMap(2)
                .resample(
                        2,
                        L,
                        U,
                        in,
                        null,
                        Mapping.NEAREST_INTERPOLATOR,
                        new ResampleFlags(),
                        0.0,
                        100,
                        -1,
                        2,
                        L,
                        U,
                        L,
                        U,
                        out,
                        null);
        assertEquals(0, nbad);
        for (int k = 0; k < 20; k++) {
            assertEquals(k * 3, out[k], "pixel " + k);
        }
    }

    /**
     * A 1-D transformation that shifts by a quarter of a pixel (forward +0.25, inverse -0.25) and
     * gives a set value instead for positions beyond 5.
     */
    static final class ShiftUpToFive extends Transformer1 {
        private final double beyond;

        ShiftUpToFive(double beyond) {
            this.beyond = beyond;
        }

        @Override
        public double[] tran1(int npoint, double[] xin, boolean forward) {
            double[] out = new double[npoint];
            for (int i = 0; i < npoint; i++) {
                double x = xin[i];
                out[i] = x > 5.0 ? beyond : forward ? x + 0.25 : x - 0.25;
            }
            return out;
        }

        @Override
        public String getPurpose() {
            return "Shift by a quarter of a pixel up to 5";
        }

        @Override
        public String getAuthor() {
            return "astjava tests";
        }

        @Override
        public String getContact() {
            return "astjava";
        }
    }

    /** The values and variances, then the bad count, of {@code resampleD} through {@code map}. */
    private static double[][] resampleWith(Mapping map, Mapping.Interpolator interp) {
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
        Arrays.fill(out, 55.0);
        Arrays.fill(outVar, 55.0);
        int nbad = map.resampleD(
                1,
                lbnd,
                ubnd,
                in,
                inVar,
                interp,
                new ResampleFlags(),
                0.0,
                100,
                -1.0,
                1,
                lbnd,
                ubnd,
                lbnd,
                ubnd,
                out,
                outVar);
        return new double[][] {out, outVar, {nbad}};
    }

    /**
     * Resampling through an IntraMap whose Transformer gives NaN beyond 5, on a grid whose lower
     * bound is 1, gives what it gives when the Transformer gives AST__BAD there, for the nearest,
     * linear, a kernel and the block averaging schemes.
     */
    @Test
    void nanPositionsAreBadPositions() {
        Mapping nanMap = new IntraMap(new ShiftUpToFive(Double.NaN));
        Mapping badMap = new IntraMap(new ShiftUpToFive(AstObject.AST__BAD));
        Mapping.Interpolator[] interps = {
            Mapping.NEAREST_INTERPOLATOR,
            Mapping.LINEAR_INTERPOLATOR,
            Mapping.Interpolator.sincSinc(2, 2.0),
            Mapping.Interpolator.blockAve(1)
        };
        for (Mapping.Interpolator interp : interps) {
            double[][] want = resampleWith(badMap, interp);
            double[][] got = resampleWith(nanMap, interp);
            assertEquals(5.0, want[2][0], "bad count");
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(want[i], got[i]);
            }
        }
    }
}
