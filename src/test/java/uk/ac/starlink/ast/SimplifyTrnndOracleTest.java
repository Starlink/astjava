package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * A simplification that meets an undefined transformation leaves the Mapping unsimplified, as C's
 * {@code astSimplify} does (mapping.c {@code astSimplify_} clears {@code AST__TRNND} and returns
 * the Mapping it was given). Translation of the MatrixMap cases of Rust {@code
 * tests/test_mapping.rs} that compare with {@code tests/simplify_trnnd_oracle/}: the expected dumps
 * are what the C library writes, copied from {@code singular_matrix_swap.ast} and {@code
 * singular_matrix_swap_after_zoom.ast} at Rust commit 10f61689f19a5f1eacc7e653c7eab9b03809939f.
 */
class SimplifyTrnndOracleTest {

    /** A 2x2 MatrixMap whose matrix is singular, so it has no inverse. */
    private static MatrixMap singularMatrix() {
        return new MatrixMap(2, 2, new double[][] {{1.0, 1.0}, {1.0, 1.0}});
    }

    /** A WinMap with non-zero shifts and scales other than one. */
    private static WinMap shiftingWinmap() {
        return new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, new double[] {1.0, 2.0}, new double[] {3.0, 5.0});
    }

    /**
     * Swapping the MatrixMap past the WinMap needs the inverse the matrix lacks (matrixmap.c {@code
     * MatWin}), so the simplification ends there and returns the Mapping unsimplified.
     */
    @Test
    void aSwapPastASingularMatrixLeavesTheMappingUnsimplified() {
        CmpMap map = new CmpMap(singularMatrix(), shiftingWinmap(), true);
        String want = ChannelText.text(
                " Begin CmpMap \t# Compound Mapping",
                "    Nin = 2 \t# Number of input coordinates",
                "    Inv = 0 \t# Inverse transformation not defined",
                " IsA Mapping \t# Mapping between coordinate systems",
                "    MapA = \t# First component Mapping",
                "       Begin MatrixMap \t# Matrix transformation",
                "          Nin = 2 \t# Number of input coordinates",
                "       IsA Mapping \t# Mapping between coordinate systems",
                "          M0 = 1 \t# Forward matrix value",
                "          M1 = 1 \t# Forward matrix value",
                "          M2 = 1 \t# Forward matrix value",
                "          M3 = 1 \t# Forward matrix value",
                "          Form = \"Full\" \t# Matrix storage form",
                "       End MatrixMap",
                "    MapB = \t# Second component Mapping",
                "       Begin WinMap \t# Map one window on to another",
                "          Nin = 2 \t# Number of input coordinates",
                "       IsA Mapping \t# Mapping between coordinate systems",
                "          Sft1 = 1 \t# Shift for axis 1",
                "          Scl1 = 2 \t# Scale factor for axis 1",
                "          Sft2 = 2 \t# Shift for axis 2",
                "          Scl2 = 3 \t# Scale factor for axis 2",
                "       End WinMap",
                " End CmpMap");
        assertEquals(want, ChannelText.dump(map.simplify()));
    }

    /**
     * The same failed swap after a merge that succeeded: the whole simplification is abandoned, so
     * the MatrixMap the ZoomMap merged into is not kept.
     */
    @Test
    void aFailedSwapAbandonsTheMergesBeforeIt() {
        CmpMap inner = new CmpMap(singularMatrix(), shiftingWinmap(), true);
        CmpMap map = new CmpMap(new ZoomMap(2, 3.0), inner, true);
        String want = ChannelText.text(
                " Begin CmpMap \t# Compound Mapping",
                "    Nin = 2 \t# Number of input coordinates",
                "    Inv = 0 \t# Inverse transformation not defined",
                " IsA Mapping \t# Mapping between coordinate systems",
                "    MapA = \t# First component Mapping",
                "       Begin ZoomMap \t# Zoom about the origin",
                "          Nin = 2 \t# Number of input coordinates",
                "       IsA Mapping \t# Mapping between coordinate systems",
                "          Zoom = 3 \t# Zoom factor",
                "       End ZoomMap",
                "    MapB = \t# Second component Mapping",
                "       Begin CmpMap \t# Compound Mapping",
                "          Nin = 2 \t# Number of input coordinates",
                "          Inv = 0 \t# Inverse transformation not defined",
                "       IsA Mapping \t# Mapping between coordinate systems",
                "          MapA = \t# First component Mapping",
                "             Begin MatrixMap \t# Matrix transformation",
                "                Nin = 2 \t# Number of input coordinates",
                "             IsA Mapping \t# Mapping between coordinate systems",
                "                M0 = 1 \t# Forward matrix value",
                "                M1 = 1 \t# Forward matrix value",
                "                M2 = 1 \t# Forward matrix value",
                "                M3 = 1 \t# Forward matrix value",
                "                Form = \"Full\" \t# Matrix storage form",
                "             End MatrixMap",
                "          MapB = \t# Second component Mapping",
                "             Begin WinMap \t# Map one window on to another",
                "                Nin = 2 \t# Number of input coordinates",
                "             IsA Mapping \t# Mapping between coordinate systems",
                "                Sft1 = 1 \t# Shift for axis 1",
                "                Scl1 = 2 \t# Scale factor for axis 1",
                "                Sft2 = 2 \t# Shift for axis 2",
                "                Scl2 = 3 \t# Scale factor for axis 2",
                "             End WinMap",
                "       End CmpMap",
                " End CmpMap");
        assertEquals(want, ChannelText.dump(map.simplify()));
    }
}
