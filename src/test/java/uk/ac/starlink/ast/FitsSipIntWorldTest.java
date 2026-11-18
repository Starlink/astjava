package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static uk.ac.starlink.ast.PolyTerms.flat;
import static uk.ac.starlink.ast.PolyTerms.term;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/sip_int_world.rs} (C {@code
 * SIPIntWorld}, fitschan.c). The expected values were captured from C by the Rust repository's
 * {@code tools/oracle_fixtures/sip_int_world.c} (fixture SIP_2D), which runs C's function on the
 * synthetic chain built here with every image dimension unknown. The oracle's copy of C's
 * function fits the Mapping after the polynomial over a box current C no longer uses, which
 * changes the CD values it gives in the last bits only, so they are compared to a relative 1e-12;
 * the other values do not depend on the box and are compared exactly.
 */
class FitsSipIntWorldTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final double TOL = 0.001;

    /** The SIP coefficients of {@code sip.head} with the identity terms, in C's layout. */
    private static final double[] SIP_FWD = flat(
            term(1.0, 1, 1, 0),
            term(1.0, 2, 0, 1),
            term(2.9656e-06, 1, 0, 2),
            term(3.7746e-09, 1, 0, 3),
            term(2.1886e-05, 1, 1, 1),
            term(-1.6847e-07, 1, 1, 2),
            term(-2.3863e-05, 1, 2, 0),
            term(-8.561e-09, 1, 2, 1),
            term(-1.4172e-07, 1, 3, 0),
            term(2.31e-05, 2, 0, 2),
            term(-1.6168e-07, 2, 0, 3),
            term(-2.4386e-05, 2, 1, 1),
            term(-5.7813e-09, 2, 1, 2),
            term(2.1197e-06, 2, 2, 0),
            term(-1.6583e-07, 2, 2, 1),
            term(-2.0249e-08, 2, 3, 0));

    /** The inverse SIP coefficients of {@code sip.head} with the identity terms. */
    private static final double[] SIP_INV = flat(
            term(1.0, 1, 1, 0),
            term(1.0, 2, 0, 1),
            term(-6.4275e-07, 1, 0, 1),
            term(-2.9425e-06, 1, 0, 2),
            term(-3.582e-09, 1, 0, 3),
            term(-1.4897e-05, 1, 1, 0),
            term(-2.225e-05, 1, 1, 1),
            term(1.7195e-07, 1, 1, 2),
            term(2.4146e-05, 1, 2, 0),
            term(6.709e-09, 1, 2, 1),
            term(1.4492e-07, 1, 3, 0),
            term(-1.6588e-05, 2, 0, 1),
            term(-2.3424e-05, 2, 0, 2),
            term(1.651e-07, 2, 0, 3),
            term(-2.6783e-06, 2, 1, 0),
            term(2.4753e-05, 2, 1, 1),
            term(3.8917e-09, 2, 1, 2),
            term(-2.151e-06, 2, 2, 0),
            term(1.7e-07, 2, 2, 1),
            term(2.0482e-08, 2, 3, 0));

    private static final double[] CD = {
        0.000249756880272355, 0.000230177809743655, 0.000230428519265417, -0.000249965770576587
    };

    /** C's SIP_A: every coefficient C stores that is not bad, as (i, j, value). */
    private static final double[][] SIP_A = {
        {0, 2, 2.9656e-06},
        {0, 3, 3.7746e-09},
        {1, 1, 2.1886e-05},
        {1, 2, -1.6847e-07},
        {2, 0, -2.3863e-05},
        {2, 1, -8.561e-09},
        {3, 0, -1.4172e-07}
    };

    private static final double[][] SIP_B = {
        {0, 2, 2.31e-05},
        {0, 3, -1.6168e-07},
        {1, 1, -2.4386e-05},
        {1, 2, -5.7813e-09},
        {2, 0, 2.1197e-06},
        {2, 1, -1.6583e-07},
        {3, 0, -2.0249e-08}
    };

    private static final double[][] SIP_AP = {
        {0, 1, -6.4275e-07},
        {0, 2, -2.9425e-06},
        {0, 3, -3.582e-09},
        {1, 0, -1.4897000000013705e-05},
        {1, 1, -2.225e-05},
        {1, 2, 1.7195e-07},
        {2, 0, 2.4146e-05},
        {2, 1, 6.709e-09},
        {3, 0, 1.4492e-07}
    };

    private static final double[][] SIP_BP = {
        {0, 1, -1.658800000003957e-05},
        {0, 2, -2.3424e-05},
        {0, 3, 1.651e-07},
        {1, 0, -2.6783e-06},
        {1, 1, 2.4753e-05},
        {1, 2, 3.8917e-09},
        {2, 0, -2.151e-06},
        {2, 1, 1.7e-07},
        {3, 0, 2.0482e-08}
    };

    /** C's SAMPLE_FORWARD: grid positions and the IWC the returned Mapping gives for them. */
    private static final double[][][] SAMPLE_FORWARD = {
        {{1.0, 1.0}, {-0.060951705632033275, 0.002481230916518591}},
        {{64.0, 64.0}, {-0.030715820161024643, 0.0012503840839148805}},
        {{128.0, 128.0}, {0.0, 0.0}},
        {{200.0, 200.0}, {0.034555297681152716, -0.0014066820944042407}},
        {{256.0, 256.0}, {0.061431640322049286, -0.002500768167829761}}
    };

    /** The synthetic pixel to IWC chain: ShiftMap(-128, -128), the SIP PolyMap, the CD matrix. */
    private static Mapping sip2dChain() {
        ShiftMap sm = new ShiftMap(new double[] {-128.0, -128.0});
        PolyMap pm = new PolyMap(2, 2, SIP_FWD.length / 4, SIP_FWD, SIP_INV.length / 4, SIP_INV);
        MatrixMap mm = new MatrixMap(2, 2, MatrixMap.FULL, CD);
        return new CmpMap(new CmpMap(sm, pm, true), mm, true);
    }

    private static FitsStore storeWithCtypes(int naxis) {
        FitsStore store = new FitsStore(naxis);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        return store;
    }

    /** Asserts that a store table holds C's values at the listed elements and is bad elsewhere. */
    private static void assertTable(double[][] want, double[][] table, String label) {
        int n = 0;
        for (double[] row : table) {
            for (double v : row) {
                if (v != BAD) {
                    n++;
                }
            }
        }
        assertEquals(want.length, n, label + ": number of good coefficients");
        for (double[] w : want) {
            int i = (int) w[0];
            int j = (int) w[1];
            assertEquals(w[2], table[i][j], label + "[" + i + "][" + j + "]");
        }
    }

    private static void assertSip2dTables(FitsStore store) {
        assertTable(SIP_A, store.sipA, "SIP_A");
        assertTable(SIP_B, store.sipB, "SIP_B");
        assertTable(SIP_AP, store.sipAp, "SIP_AP");
        assertTable(SIP_BP, store.sipBp, "SIP_BP");
    }

    /** C's SIP_2D fixture. */
    @Test
    void sip2dExtractsOracleValues() {
        FitsStore store = storeWithCtypes(2);
        int[] inaxes = new int[2];
        double[] crpix = new double[2];
        double[] cd = new double[4];
        Mapping result = FitsSipIntWorld.sipIntWorld(
                sip2dChain(),
                TOL,
                0,
                1,
                ' ',
                store,
                new double[] {BAD, BAD},
                inaxes,
                crpix,
                cd,
                "astWrite",
                "FitsChan");
        assertNotNull(result);
        assertArrayEquals(new int[] {0, 1}, inaxes);
        assertArrayEquals(new double[] {128.0, 128.0}, crpix);
        for (int k = 0; k < 4; k++) {
            assertEquals(CD[k], cd[k], 1e-12 * Math.abs(CD[k]), "cd[" + k + "]");
        }
        assertSip2dTables(store);
        assertEquals("RA---TAN-SIP", store.ctype[0]);
        assertEquals("DEC--TAN-SIP", store.ctype[1]);

        for (double[][] sample : SAMPLE_FORWARD) {
            double[][] out = result.tran2(1, new double[] {sample[0][0]}, new double[] {sample[0][1]}, true);
            assertEquals(sample[1][0], out[0][0], "x at " + sample[0][0]);
            assertEquals(sample[1][1], out[1][0], "y at " + sample[0][1]);
        }
    }

    /** The same chain without its PolyMap: there is no SIP distortion, and the store is untouched. */
    @Test
    void nonSipReturnsNull() {
        ShiftMap sm = new ShiftMap(new double[] {-128.0, -128.0});
        MatrixMap mm = new MatrixMap(2, 2, MatrixMap.FULL, CD);
        FitsStore store = storeWithCtypes(2);
        Mapping result = FitsSipIntWorld.sipIntWorld(
                new CmpMap(sm, mm, true),
                TOL,
                0,
                1,
                ' ',
                store,
                new double[] {256, 256},
                new int[2],
                new double[2],
                new double[4],
                "astWrite",
                "FitsChan");
        assertNull(result);
        assertEquals(0, store.sipA.length);
        assertEquals("RA---TAN", store.ctype[0]);
    }

    /**
     * The SIP_2D chain in parallel with a one-axis UnitMap, which takes C's branch for Mappings with
     * more than two outputs. C's code gives the celestial values of SIP_2D, and a returned Mapping
     * that passes the third axis through.
     */
    @Test
    void sip3dKeepsTheOtherAxis() {
        Mapping chain = new CmpMap(sip2dChain(), new UnitMap(1), false);
        FitsStore store = storeWithCtypes(3);
        int[] inaxes = new int[2];
        double[] crpix = new double[2];
        double[] cd = new double[4];
        Mapping result = FitsSipIntWorld.sipIntWorld(
                chain, TOL, 0, 1, ' ', store, new double[] {BAD, BAD, BAD}, inaxes, crpix, cd, "astWrite", "FitsChan");
        assertNotNull(result);
        assertEquals(3, result.getNin());
        assertEquals(3, result.getNout());
        assertArrayEquals(new int[] {0, 1}, inaxes);
        assertArrayEquals(new double[] {128.0, 128.0}, crpix);
        assertSip2dTables(store);
        for (double[][] sample : SAMPLE_FORWARD) {
            double[][] out = result.transform(new double[][] {{sample[0][0]}, {sample[0][1]}, {1.0}}, 1, true);
            assertEquals(sample[1][0], out[0][0]);
            assertEquals(sample[1][1], out[1][0]);
            assertEquals(1.0, out[2][0]);
        }
    }

    /**
     * C copies a CTYPE into a buffer and writes {@code -SIP} at its ninth character, so a value of
     * fewer than eight characters keeps its terminating null and is stored unchanged, and a longer
     * one is cut to eight characters.
     */
    @Test
    void sipSuffixFollowsCsBuffer() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "RA";
        store.ctype[1] = "DEC--TAN-XYZ";
        assertNotNull(FitsSipIntWorld.sipIntWorld(
                sip2dChain(),
                TOL,
                0,
                1,
                ' ',
                store,
                new double[] {BAD, BAD},
                new int[2],
                new double[2],
                new double[4],
                "astWrite",
                "FitsChan"));
        assertEquals("RA", store.ctype[0]);
        assertEquals("DEC--TAN-SIP", store.ctype[1]);
    }
}
