package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The arguments of {@code resample} and {@code resampleX} that C cannot check: a data or variance
 * array whose length does not match its grid and a bound array shorter than its number of
 * dimensions give {@code AST__DIMIN}; an output region outside the output grid gives C's {@code
 * AST__GBDIN}; and the generic {@code resample} given arrays of different types, a bad value of
 * another {@code Number} type or data that is not a primitive array gives jniast's {@code
 * IllegalArgumentException}. No such call reaches an {@code ArrayIndexOutOfBoundsException} or a
 * {@code ClassCastException}. This test has no Rust counterpart; the messages of the length checks
 * are Rust's {@code resample}'s.
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
    void dataAndVarianceLengthsMustMatchTheirGrids() {
        AstException e = dimin(new double[19], null, new double[20], null);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astResampleD(UnitMap): The input data array has 19 elements but its grid has 20.");

        e = dimin(new double[20], new double[21], new double[20], new double[20]);
        assertError(
                e,
                AstException.AST__DIMIN,
                "astResampleD(UnitMap): The input variance array has 21 elements but its grid has 20.");

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
}
