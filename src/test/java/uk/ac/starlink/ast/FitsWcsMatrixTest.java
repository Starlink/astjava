package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/wcs_matrix.rs}: the Mapping from pixel
 * coordinates to intermediate world coordinates C's {@code WcsIntWorld} (fitschan.c:38028) builds.
 * The expected values are worked out from the keyword values. Further tests cover the errors C's
 * {@code WcsPCMatrix} and {@code WcsCDeltMatrix} raise and the distortion codes {@code
 * DistortMaps} removes.
 */
class FitsWcsMatrixTest {

    private static final double BAD = AstObject.AST__BAD;

    private static FitsStore simpleCdeltStore() {
        FitsStore store = new FitsStore(2);
        store.crpix[0] = 128.0;
        store.crpix[1] = 128.0;
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        return store;
    }

    /**
     * The store a rotated and skewed CD matrix gives once SpecTrans has split it into a PC matrix
     * with unit rows and a CDELT for each row (fitschan.c:31775-31804).
     */
    private static FitsStore cdMatrixStore() {
        double[][] cd = {{0.000250, 0.000230}, {0.000230, -0.000250}};
        FitsStore store = new FitsStore(2);
        store.crpix[0] = 128.0;
        store.crpix[1] = 128.0;
        for (int j = 0; j < 2; j++) {
            double rowsum2 = 0.0;
            for (double v : cd[j]) {
                rowsum2 += v * v;
            }
            double diag = cd[j][j] >= 0.0 ? 1.0 : -1.0;
            double cdeltj = Math.sqrt(rowsum2) * diag;
            store.cdelt[j] = cdeltj;
            for (int i = 0; i < 2; i++) {
                store.pc[j][i] = cd[j][i] / cdeltj;
            }
        }
        return store;
    }

    private static Mapping pixelToIwc(FitsStore store) {
        return FitsWcsMatrix.buildPixelToIwc(store, new FitsCardList(), new FitsWarnings(), new ArrayList<>());
    }

    private static double[] point(Mapping map, double x, double y) {
        double[][] out = map.tran2(1, new double[] {x}, new double[] {y}, true);
        return new double[] {out[0][0], out[1][0]};
    }

    @Test
    void crpixShiftMapsReferenceToZero() {
        double[] r = point(FitsWcsMatrix.buildCrpixShift(simpleCdeltStore()), 128.0, 128.0);
        assertEquals(0.0, r[0], 1e-10);
        assertEquals(0.0, r[1], 1e-10);
    }

    @Test
    void crpixShiftArbitraryPoint() {
        double[] r = point(FitsWcsMatrix.buildCrpixShift(simpleCdeltStore()), 129.0, 130.0);
        assertEquals(1.0, r[0], 1e-10);
        assertEquals(2.0, r[1], 1e-10);
    }

    @Test
    void cdeltOnlyProducesDiagonal() {
        double[] r = point(pixelToIwc(simpleCdeltStore()), 128.0, 128.0);
        assertEquals(0.0, r[0], 1e-10);
        assertEquals(0.0, r[1], 1e-10);
    }

    @Test
    void cdeltOffsetPixel() {
        double[] r = point(pixelToIwc(simpleCdeltStore()), 129.0, 129.0);
        assertEquals(-0.001, r[0], 1e-12);
        assertEquals(0.001, r[1], 1e-12);
    }

    @Test
    void cdMatrixReferenceToZero() {
        double[] r = point(pixelToIwc(cdMatrixStore()), 128.0, 128.0);
        assertEquals(0.0, r[0], 1e-10);
        assertEquals(0.0, r[1], 1e-10);
    }

    @Test
    void cdMatrixOffsetPixel() {
        // Offset (1, 0), so IWC = CD * (1, 0) = (CD1_1, CD2_1).
        double[] r = point(pixelToIwc(cdMatrixStore()), 129.0, 128.0);
        assertEquals(0.000250, r[0], 1e-12);
        assertEquals(0.000230, r[1], 1e-12);
    }

    @Test
    void ninNoutCorrect() {
        Mapping m = pixelToIwc(simpleCdeltStore());
        assertEquals(2, m.getNin());
        assertEquals(2, m.getNout());
    }

    @Test
    void pcMatrixWithRotation() {
        FitsStore store = new FitsStore(2);
        store.crpix[0] = 1.0;
        store.crpix[1] = 1.0;
        store.cdelt[0] = 1.0;
        store.cdelt[1] = 1.0;
        double cos45 = Math.sqrt(0.5);
        double sin45 = cos45;
        store.pc[0][0] = cos45;
        store.pc[0][1] = -sin45;
        store.pc[1][0] = sin45;
        store.pc[1][1] = cos45;
        double[] r = point(pixelToIwc(store), 2.0, 1.0);
        assertEquals(cos45, r[0], 1e-10);
        assertEquals(sin45, r[1], 1e-10);
    }

    @Test
    void identityWithDefaults() {
        // CRPIX defaults to 0, CDELT to 1 and PC to the unit matrix.
        double[] r = point(pixelToIwc(new FitsStore(2)), 5.0, 7.0);
        assertEquals(5.0, r[0], 1e-10);
        assertEquals(7.0, r[1], 1e-10);
    }

    @Test
    void sipDistortionForward() {
        FitsStore store = new FitsStore(2);
        store.crpix[0] = 0.0;
        store.crpix[1] = 0.0;
        store.cdelt[0] = 1.0;
        store.cdelt[1] = 1.0;
        store.sip = true;
        store.sipA = new double[3][3];
        for (double[] row : store.sipA) {
            Arrays.fill(row, BAD);
        }
        store.sipA[2][0] = 1e-5;
        store.sipB = new double[][] {{BAD, 0.0}};
        // U = u + A_2_0 u^2 = 100 + 1e-5 * 10000 = 100.1, V = v.
        double[] r = point(pixelToIwc(store), 100.0, 0.0);
        assertEquals(100.1, r[0], 1e-8);
        assertEquals(0.0, r[1], 1e-10);
    }

    /**
     * With SipReplace set, C's SIPMapping replaces the missing inverse by a polynomial fitted to the
     * forward transformation to within 0.01 pixel (fitschan.c:29849-29870), so the inverse undoes
     * the forward transformation to that accuracy.
     */
    @Test
    void sipInverseIsFittedToTheForwardTransformation() {
        FitsStore store = new FitsStore(2);
        store.sip = true;
        store.sipA = new double[][] {{BAD, BAD, 2.0e-6}, {BAD}, {1.0e-5}};
        store.sipB = new double[][] {{BAD, BAD, 3.0e-6}};
        store.dims[0] = 100.0;
        store.dims[1] = 200.0;
        Mapping m = pixelToIwc(store);
        double[] fwd = point(m, 50.0, 120.0);
        double[][] back = m.tran2(1, new double[] {fwd[0]}, new double[] {fwd[1]}, false);
        assertEquals(50.0, back[0][0], 0.01);
        assertEquals(120.0, back[1][0], 0.01);
    }

    /** C's WcsPCMatrix reports a PC matrix with no inverse as an error (fitschan.c:38502-38505). */
    @Test
    void aSingularPcMatrixIsAnError() {
        FitsStore store = new FitsStore(2);
        store.pc[0][0] = 1.0;
        store.pc[0][1] = 1.0;
        store.pc[1][0] = 1.0;
        store.pc[1][1] = 1.0;
        AstException e = assertThrows(AstException.class, () -> pixelToIwc(store));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(e.getMessage().startsWith("astRead(FitsChan): Unusable rotation matrix (PC or CD)"));
    }

    /**
     * C's WcsCDeltMatrix reports an error when its MatrixMap has no inverse (fitschan.c:35972-35975),
     * but a diagonal MatrixMap always has one, a zero element giving AST__BAD
     * (matrixmap.c:1531-1555), so a zero CDELT is not an error and the inverse is bad on that axis.
     */
    @Test
    void aZeroCdeltIsNotAnError() {
        FitsStore store = new FitsStore(2);
        store.cdelt[1] = 0.0;
        Mapping m = pixelToIwc(store);
        double[][] back = m.tran2(1, new double[] {1.0}, new double[] {0.0}, false);
        assertEquals(1.0, back[0][0]);
        assertEquals(BAD, back[1][0]);
    }

    /**
     * DistortMaps removes a {@code -SIP} code beyond the second axis and any other distortion code,
     * with one {@code distortion} warning for each kind (fitschan.c:9061-9076, 9139-9170).
     */
    @Test
    void unsupportedDistortionCodesAreRemoved() {
        FitsStore store = new FitsStore(4);
        store.ctype[0] = "RA---TAN-ABC";
        store.ctype[1] = "DEC--TAN-ABC";
        store.ctype[2] = "FREQ-SIP";
        store.ctype[3] = "VRAD-SIP";
        FitsWarnings warnings = new FitsWarnings();
        List<int[]> cursors = new ArrayList<>();
        Mapping m = FitsWcsMatrix.buildPixelToIwc(store, new FitsCardList(), warnings, cursors);
        assertEquals(4, m.getNin());
        assertEquals("RA---TAN", store.ctype[0]);
        assertEquals("DEC--TAN", store.ctype[1]);
        assertEquals("FREQ", store.ctype[2]);
        assertEquals("VRAD", store.ctype[3]);
        assertEquals(2, warnings.size());
        assertEquals("distortion", warnings.list().get(0).condition);
        assertEquals(
                "The \"-SIP\" distortion code can only be used on axes 1 and 2, but was found in keyword CTYPE3"
                        + " (='FREQ-SIP'). The distortion will be ignored.",
                warnings.list().get(0).text);
        assertEquals(
                "The header contains CTYPE values (e.g. CTYPE1 = 'RA---TAN-ABC') which include a distortion"
                        + " code \"-ABC\". AST currently ignores this distortion. The code has been removed from"
                        + " the CTYPE values.",
                warnings.list().get(1).text);
        assertTrue(cursors.isEmpty());
    }

    /** More than two {@code -ZPX} axes give a warning and the plain ZPN projection (fitschan.c:9108-9112). */
    @Test
    void moreThanTwoZpxAxesFallBackToZpn() {
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "RA---ZPN-ZPX";
        store.ctype[1] = "DEC--ZPN-ZPX";
        store.ctype[2] = "GLON-ZPN-ZPX";
        FitsWarnings warnings = new FitsWarnings();
        FitsWcsMatrix.buildPixelToIwc(store, new FitsCardList(), warnings, new ArrayList<>());
        assertEquals("RA---ZPN", store.ctype[0]);
        assertEquals("GLON-ZPN", store.ctype[2]);
        assertEquals(1, warnings.size());
        assertEquals("distortion", warnings.list().get(0).condition);
    }
}
