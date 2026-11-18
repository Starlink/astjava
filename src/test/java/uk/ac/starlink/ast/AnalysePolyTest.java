package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.PolyTerms.flat;
import static uk.ac.starlink.ast.PolyTerms.term;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code mappings/helpers/analyse_poly.rs} (C {@code
 * AnalysePoly}, fitschan.c, and the {@code astMergeShift} it uses, polymap.c). The expected values
 * were captured from C by the Rust repository's {@code tools/oracle_fixtures/analyse_poly.c}, which
 * gives the coefficients of the middle PolyMap in the order {@code astPolyCoeffs} returns them.
 */
class AnalysePolyTest {

    /** A 2-input, 2-output PolyMap with IterInverse set, from coefficients in C's layout. */
    private static PolyMap polyWithIterInverse(double... coeffs) {
        PolyMap pm = new PolyMap(2, 2, coeffs.length / 4, coeffs, 0, null);
        pm.setIterInverse(true);
        return pm;
    }

    /** The shift a ShiftMap applies, from transforming the origin as the C oracle does. */
    private static double[] shift(Mapping map1) {
        double[][] out = map1.tran2(1, new double[] {0.0}, new double[] {0.0}, true);
        return new double[] {out[0][0], out[1][0]};
    }

    /** The matrix (a, b, c, d) of a MatrixMap, from transforming unit vectors as the C oracle does. */
    private static double[] matrix(Mapping map3) {
        double[][] out = map3.tran2(3, new double[] {0.0, 1.0, 0.0}, new double[] {0.0, 0.0, 1.0}, true);
        return new double[] {out[0][1], out[0][2], out[1][1], out[1][2]};
    }

    private static Mapping[] analyse(PolyMap pm) {
        Mapping[] maps = new Mapping[3];
        assertTrue(AnalysePoly.analysePoly(pm, maps));
        return maps;
    }

    /** C's ANALYSE_POLY_SIP_SHAPE fixture: no shift, no linear part beyond the unit matrix. */
    @Test
    void sipShapePolyMap() {
        PolyMap pm = polyWithIterInverse(1.0, 1, 1, 0, 0.001, 1, 1, 1, 1.0, 2, 0, 1, 0.002, 2, 2, 0);
        Mapping[] maps = analyse(pm);
        assertArrayEquals(new double[] {0.0, 0.0}, shift(maps[0]));
        assertInstanceOf(UnitMap.class, maps[2]);
        assertArrayEquals(
                new double[] {1.0, 1, 1, 0, 0.001, 1, 1, 1, 1.0, 2, 0, 1, 0.002, 2, 2, 0},
                ((PolyMap) maps[1]).polyCoeffs(true));
    }

    /**
     * C's ANALYSE_POLY_WITH_CONSTANT fixture: the constant terms are taken out as a shift found
     * with the iterative inverse, and the linear terms the shift leaves as a matrix.
     */
    @Test
    void polyMapWithConstantsExtractsShift() {
        PolyMap pm =
                polyWithIterInverse(5.0, 1, 0, 0, 1.0, 1, 1, 0, 1.0, 1, 1, 1, 7.0, 2, 0, 0, 1.0, 2, 0, 1, 1.0, 2, 2, 0);
        Mapping[] maps = analyse(pm);
        double[] shift = shift(maps[0]);
        assertEquals(-3.712254771232298, shift[0]);
        assertEquals(6.496117295794907, shift[1]);
        assertInstanceOf(MatrixMap.class, maps[2]);
        double[] mat = matrix(maps[2]);
        assertArrayEquals(new double[] {-5.496117295794907, 3.712254771232298, 7.424509542464596, 1.0}, mat);
        double[] coeffs = ((PolyMap) maps[1]).polyCoeffs(true);
        double[] wantCoeffs = flat(
                term(1.0, 1, 1, 0),
                term(-0.030250057622327978, 1, 1, 1),
                term(0.11229592073853897, 1, 2, 0),
                term(1.0, 2, 0, 1),
                term(0.22459184147707795, 2, 1, 1),
                term(0.16625786489686933, 2, 2, 0));
        assertArrayEquals(wantCoeffs, coeffs);
    }

    /** C's ANALYSE_POLY_ROTATION_SCALE fixture: a linear part with rotation and scaling. */
    @Test
    void polyMapWithLinearExtractsMatrix() {
        PolyMap pm = polyWithIterInverse(
                2.0, 1, 1, 0, 3.0, 1, 0, 1, 0.01, 1, 1, 1, -1.0, 2, 1, 0, 4.0, 2, 0, 1, 0.02, 2, 2, 0);
        Mapping[] maps = analyse(pm);
        double[] shift = shift(maps[0]);
        assertEquals(0.0, shift[0]);
        assertEquals(0.0, shift[1]);
        assertInstanceOf(MatrixMap.class, maps[2]);
        assertArrayEquals(new double[] {2.0, 3.0, -1.0, 4.0}, matrix(maps[2]));
        double[] coeffs = ((PolyMap) maps[1]).polyCoeffs(true);
        double[] wantCoeffs = flat(
                term(1.0, 1, 1, 0),
                term(0.0036363636363636364, 1, 1, 1),
                term(-0.005454545454545454, 1, 2, 0),
                term(1.0, 2, 0, 1),
                term(0.0009090909090909091, 2, 1, 1),
                term(0.0036363636363636364, 2, 2, 0));
        assertArrayEquals(wantCoeffs, coeffs);
    }

    /** C's ANALYSE_POLY_SINGULAR fixture: a singular linear part cannot be analysed. */
    @Test
    void singularReturnsFalse() {
        PolyMap pm = polyWithIterInverse(
                1.0, 1, 1, 0, 1.0, 1, 0, 1, 0.01, 1, 2, 0, 1.0, 2, 1, 0, 1.0, 2, 0, 1, 0.01, 2, 2, 0);
        Mapping[] maps = new Mapping[3];
        assertFalse(AnalysePoly.analysePoly(pm, maps));
        assertNull(maps[0]);
        assertNull(maps[1]);
        assertNull(maps[2]);
    }

    /** A PolyMap whose inverse is not iterative is not analysed (fitschan.c AnalysePoly). */
    @Test
    void noIterInverseReturnsFalse() {
        PolyMap pm = new PolyMap(2, 2, 3, new double[] {1.0, 1, 1, 0, 1.0, 2, 0, 1, 0.001, 1, 1, 1}, 0, null);
        pm.setIterInverse(false);
        Mapping[] maps = new Mapping[3];
        assertFalse(AnalysePoly.analysePoly(pm, maps));
        assertNull(maps[1]);
    }

    /** The supplied PolyMap's Invert attribute is cleared while it is analysed, then set again. */
    @Test
    void invertedPolyMapIsRestored() {
        PolyMap pm = polyWithIterInverse(1.0, 1, 1, 0, 0.001, 1, 1, 1, 1.0, 2, 0, 1, 0.002, 2, 2, 0);
        pm.invert();
        analyse(pm);
        assertTrue(pm.getInvert());
    }

    /**
     * {@code astMergeShift} with zero shifts applied first and {@code force} set keeps every term
     * but the constant, which it sets to zero and the simplification then drops (polymap.c
     * MergeShift).
     */
    @Test
    void mergeShiftZeroDropsConstants() {
        PolyMap pm = new PolyMap(2, 2, 3, new double[] {1.0, 1, 2, 1, 2.0, 1, 0, 0, 3.0, 2, 0, 1}, 0, null);
        PolyMap merged = pm.mergeShift(new ShiftMap(new double[] {0.0, 0.0}), true, true);
        assertArrayEquals(new double[] {1.0, 1, 2, 1, 3.0, 2, 0, 1}, merged.polyCoeffs(true));
    }

    /**
     * {@code astMergeShift} with a shift applied first expands each term about the shifted inputs:
     * {@code (x0 + 2)^2} is {@code x0^2 + 4*x0 + 4}, the terms coming in the order C's {@code
     * ExpandTerm} gives them.
     */
    @Test
    void mergeShiftExpandsAShiftedTerm() {
        PolyMap pm = new PolyMap(1, 1, 1, new double[] {1.0, 1, 2}, 0, null);
        PolyMap merged = pm.mergeShift(new ShiftMap(new double[] {2.0}), true, false);
        assertArrayEquals(new double[] {1.0, 1, 2, 4.0, 1, 1, 4.0, 1, 0}, merged.polyCoeffs(true));
    }

    /**
     * {@code astMergeShift} with a shift applied after the PolyMap adds it to the first constant
     * term of each output, or appends a constant term to an output without one.
     */
    @Test
    void mergeShiftAfterAddsToTheConstants() {
        PolyMap pm =
                new PolyMap(2, 2, 4, new double[] {1.0, 1, 1, 0, 5.0, 1, 0, 0, 1.0, 2, 0, 1, 0.5, 2, 2, 0}, 0, null);
        PolyMap merged = pm.mergeShift(new ShiftMap(new double[] {1.0, 2.0}), false, false);
        assertArrayEquals(
                new double[] {1.0, 1, 1, 0, 6.0, 1, 0, 0, 1.0, 2, 0, 1, 0.5, 2, 2, 0, 2.0, 2, 0, 0},
                merged.polyCoeffs(true));
    }
}
