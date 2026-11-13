package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.OracleSection;

/**
 * Translation of Rust {@code tests/test_fitschan_wcs.rs}: FrameSets read from FITS-WCS headers and
 * their transformations.
 *
 * <p>The values the Rust test copies from {@code fixtures/oracle/headers.oracle} are read from that
 * file here, with the Rust test's tolerances.
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code read_spectral_header}, {@code
 * axes_swap_non_adjacent_celestial_axes} and {@code crota_3d_forward_matches_oracle_inverse_undefined}
 * (spectral axes, SpecFrame; {@code crota-3d.head} is also read with the FITS-AIPS encoding), {@code
 * jan_class_no_naxis_keyword} and {@code class_noproj_builds_sfl} (the FITS-CLASS encoding), {@code
 * serpens_dut1_is_seconds} and {@code serpens_swapped_axis_transform} ({@code serpens.head} is read
 * with the FITS-IRAF encoding), and {@code carlin_off_car_header_keeps_the_spherical_projection},
 * which checks the class of the Mapping C simplified. {@code
 * carlin_car_header_yields_a_single_winmap} is translated without its check that the simplified
 * Mapping is a WinMap.
 */
class FitsChanWcsTest {

    private static final double D2R = Math.PI / 180.0;

    /** Minimal 2-D TAN header. */
    private static final String MINIMAL_TAN_HEADER = String.join(
            "\n",
            "NAXIS   =                    2 / Number of axes",
            "CRPIX1  =              128.000 / Reference pixel",
            "CRPIX2  =              128.000 / Reference pixel",
            "CRVAL1  =          202.4820000 / Reference RA",
            "CRVAL2  =           47.1750000 / Reference Dec",
            "CDELT1  =       -0.00027778000 / Degrees per pixel",
            "CDELT2  =        0.00027778000 / Degrees per pixel",
            "CTYPE1  = 'RA---TAN'           / RA with TAN projection",
            "CTYPE2  = 'DEC--TAN'           / Dec with TAN projection",
            "RADESYS = 'ICRS    '           / Reference frame",
            "EQUINOX =               2000.0 / Equinox",
            "");

    /** An angle in degrees normalised into [0, 360). */
    private static double normaliseRa(double deg) {
        double r = deg % 360.0;
        return r < 0.0 ? r + 360.0 : r;
    }

    private static FitsChan wcsChan(String text) {
        FitsChan fc = FitsChan.fromHeader(text);
        fc.setEncoding("FITS-WCS");
        return fc;
    }

    private static String fixture(String name) {
        return Fixtures.read("wcsconv/inputs/" + name);
    }

    /** The FrameSet read from the wcsconv fixture {@code name} with the encoding guessed. */
    private static FrameSet readFixture(String name) {
        return FitsHeaders.readFrameSet(FitsChan.fromHeader(fixture(name)));
    }

    @Test
    void readMinimalTanHeader() {
        FrameSet fs = FitsHeaders.readFrameSet(wcsChan(MINIMAL_TAN_HEADER));
        assertEquals(2, fs.getNframe(), "pixel and sky Frames");
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    @Test
    void readSipHeaderFrameset() {
        FrameSet fs = FitsHeaders.readFrameSet(wcsChan(fixture("sip.head")));
        assertEquals(2, fs.getNframe());
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
        // sip.head has RADESYS = 'ICRS', for which C's WcsSkyFrame makes the SkyFrame with
        // "System=ICRS" (fitschan.c:39796).
        assertEquals("ICRS", fs.getFrame(FrameSet.AST__CURRENT).getSystem());
    }

    @Test
    void transformAtReferencePixel() {
        FrameSet fs = FitsHeaders.readFrameSet(wcsChan(MINIMAL_TAN_HEADER));
        // CRPIX (GRID coordinates) maps to CRVAL; the SkyFrame axes are in radians.
        double[] out = FitsHeaders.tran(fs, new double[] {128.0, 128.0}, true);
        double ra = normaliseRa(Math.toDegrees(out[0]));
        double dec = Math.toDegrees(out[1]);
        assertTrue(Math.abs(ra - 202.482) < 0.01, "RA at CRPIX: " + ra);
        assertTrue(Math.abs(dec - 47.175) < 0.01, "Dec at CRPIX: " + dec);
    }

    @Test
    void inverseTransformRoundTrip() {
        FrameSet fs = FitsHeaders.readFrameSet(wcsChan(MINIMAL_TAN_HEADER));
        double[] sky = FitsHeaders.tran(fs, new double[] {100.0, 150.0}, true);
        double[] pix = FitsHeaders.tran(fs, sky, false);
        assertTrue(Math.abs(pix[0] - 100.0) < 1e-6, "x: " + pix[0]);
        assertTrue(Math.abs(pix[1] - 150.0) < 1e-6, "y: " + pix[1]);
    }

    @Test
    void sipHeaderTransformAtCrpix() {
        FrameSet fs = FitsHeaders.readFrameSet(wcsChan(fixture("sip.head")));
        // The SIP distortion is zero at the reference pixel, so CRPIX maps to CRVAL.
        double[] out = FitsHeaders.tran(fs, new double[] {128.0, 128.0}, true);
        double ra = normaliseRa(Math.toDegrees(out[0]));
        double dec = Math.toDegrees(out[1]);
        assertTrue(Math.abs(ra - 202.4823) < 0.01, "RA at CRPIX: " + ra);
        assertTrue(Math.abs(dec - 47.1751) < 0.01, "Dec at CRPIX: " + dec);
    }

    @Test
    void car3NaxisInferredFromCtypeIndices() {
        // car3.head has no NAXIS, NAXISn or WCSAXES: the axis count comes from the CRPIX indices.
        assertEquals(2, readFixture("car3.head").getNin());
    }

    @Test
    void sipReadDefaultRefitsInverseViaPolytran() {
        // With SipReplace at its default, C replaces the inverse by an astPolyTran fit over three
        // times the image area (fitschan.c:29857-29862), which on this header is good to about
        // 6.5e-3 pixel.
        FrameSet fs = FitsHeaders.readFrameSet(wcsChan(fixture("sip.head")));
        double[] xs = {20.0, 128.0, 230.0};
        double[] ys = {40.0, 128.0, 200.0};
        for (int i = 0; i < 3; i++) {
            double[] back = FitsHeaders.tran(fs, FitsHeaders.tran(fs, new double[] {xs[i], ys[i]}, true), false);
            assertTrue(
                    Math.abs(back[0] - xs[i]) < 0.02 && Math.abs(back[1] - ys[i]) < 0.02,
                    "round trip " + i + ": (" + back[0] + ", " + back[1] + ")");
        }
    }

    @Test
    void sipReadSipreplaceOffKeepsSuppliedInverse() {
        // With SipReplace off, the AP/BP inverse sip.head supplies is used as it is; it is good to
        // better than a pixel near the reference point.
        FitsChan fc = wcsChan(fixture("sip.head"));
        fc.set("SipReplace=0");
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        double[] back = FitsHeaders.tran(fs, FitsHeaders.tran(fs, new double[] {128.0, 128.0}, true), false);
        assertTrue(
                Math.abs(back[0] - 128.0) < 1.0 && Math.abs(back[1] - 128.0) < 1.0,
                "round trip: (" + back[0] + ", " + back[1] + ")");
    }

    @Test
    void skyrefAltReadsVersionedSref() {
        FrameSet fs = readFixture("skyref-alt.head");
        Frame alt = fs.getFrame(3);
        assertInstanceOf(SkyFrame.class, alt, "frame 3 is a " + alt.astClass());
        SkyFrame sky = (SkyFrame) alt;
        // C's WcsSkyFrame gives the SREF1A and SREF2A values to astSetSkyRef as they are
        // (fitschan.c:39881-39884); C's own dump of this FrameSet (wcsconv/expected/skyref-alt.native)
        // has SREF1_B = 236.592, SREF2_B = 50.669 and SREFIS_B = 'Origin'.
        assertTrue(Math.abs(sky.getSkyRef(1) - 236.592) < 1e-9, "SkyRef(1) " + sky.getSkyRef(1));
        assertTrue(Math.abs(sky.getSkyRef(2) - 50.669) < 1e-9, "SkyRef(2) " + sky.getSkyRef(2));
        assertEquals("Origin", sky.getSkyRefIs());
    }

    /** Checks the forward rows {@code rows} (from 1) of the headers.oracle section of {@code name}. */
    private static void checkForwardRows(String name, int[] rows, double tol) {
        FrameSet fs = readFixture(name);
        OracleSection s = FitsHeaders.headersOracle("wcsconv/inputs/" + name, true);
        for (int row : rows) {
            double[] got = FitsHeaders.tran(fs, FitsHeaders.oracleIn(s, row), true);
            double[] want = FitsHeaders.oracleOut(s, row);
            for (int k = 0; k < want.length; k++) {
                assertTrue(
                        Math.abs(got[k] - want[k]) < tol,
                        name + " forward row " + row + " axis " + (k + 1) + ": got " + got[k] + ", C gives " + want[k]);
            }
        }
    }

    /** Checks the inverse rows {@code rows} (from 1) of the headers.oracle section of {@code name}. */
    private static void checkInverseRows(String name, int[] rows, double tol) {
        FrameSet fs = readFixture(name);
        OracleSection s = FitsHeaders.headersOracle("wcsconv/inputs/" + name, false);
        for (int row : rows) {
            double[] got = FitsHeaders.tran(fs, FitsHeaders.oracleIn(s, row), false);
            double[] want = FitsHeaders.oracleOut(s, row);
            for (int k = 0; k < want.length; k++) {
                assertTrue(
                        Math.abs(got[k] - want[k]) < tol,
                        name + " inverse row " + row + " axis " + (k + 1) + ": got " + got[k] + ", C gives " + want[k]);
            }
        }
    }

    @Test
    void glsLonpoleDefaultMatchesC() {
        // GLS (SFL) has theta0 = 0, so the LONPOLE default WcsNatPole chooses is 0, not 180.
        checkForwardRows("gls.head", new int[] {1, 4}, 1e-9);
    }

    @Test
    void unknownCelBuildsUnknownSkyframe() {
        FrameSet fs = readFixture("unknown-cel.head");
        Frame cur = fs.getFrame(FrameSet.AST__CURRENT);
        assertInstanceOf(SkyFrame.class, cur, "current Frame is a " + cur.astClass());
        assertEquals("Unknown", cur.getSystem());
        checkForwardRows("unknown-cel.head", new int[] {1, 4}, 1e-9);
    }

    @Test
    void unknownCel2BuildsUnknownSkyframe() {
        FrameSet fs = readFixture("unknown-cel2.head");
        Frame cur = fs.getFrame(FrameSet.AST__CURRENT);
        assertInstanceOf(SkyFrame.class, cur, "current Frame is a " + cur.astClass());
        assertEquals("Unknown", cur.getSystem());
        checkForwardRows("unknown-cel2.head", new int[] {1, 4}, 1e-9);
    }

    @Test
    void tanPolytanPromotesToTpn() {
        // PV2_1 and PV2_2 on the latitude axis make C's GetUsedPolyTan choose the distorted TAN
        // (TPN); plain TAN would not give these values.
        checkForwardRows("tan-polytan.head", new int[] {1, 2, 5, 6}, 1e-9);
    }

    @Test
    void car4CdMatrixWrapsCrpixForCar() {
        // C's SpecTrans turns the CD matrix into PC and CDELT before its MSX CAR block
        // (fitschan.c:32473-32475) rewrites CRPIX to bring the native longitude into the CAR domain.
        checkForwardRows("car4.head", new int[] {1, 2}, 1e-12);
        checkInverseRows("car4.head", new int[] {1, 2}, 1e-6);
    }

    /** The FrameSet read from {@code car1.head} with CarLin as given. */
    private static FrameSet readCar1(boolean carLin) {
        FitsChan fc = wcsChan(fixture("car1.head"));
        fc.setCarLin(carLin);
        return FitsHeaders.readFrameSet(fc);
    }

    @Test
    void carlinCarHeaderYieldsASingleWinmap() {
        // With CarLin set, C's WcsCelestial builds a WcsMap of type AST__WCSBAD and a WinMap
        // shifting by CRVAL instead of calling WcsNative (CarLin is read at fitschan.c:36146), so the
        // pixel-to-sky Mapping is linear: CRVAL in radians at CRPIX = (1, 1), moving by CDELT in
        // radians (-0.125 and 0.125 degrees in car1.head) per pixel. C simplifies it to a single
        // WinMap, which is not checked here.
        Mapping map = readCar1(true).getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        double[] sky = FitsHeaders.tran(map, new double[] {1.0, 1.0}, true);
        assertTrue(Math.abs(sky[0] - 185.0 * D2R) < 1e-12, "lon at CRPIX " + sky[0]);
        assertTrue(Math.abs(sky[1] - -35.0 * D2R) < 1e-12, "lat at CRPIX " + sky[1]);
        double[] sky2 = FitsHeaders.tran(map, new double[] {2.0, 2.0}, true);
        assertTrue(Math.abs(sky2[0] - sky[0] - -0.125 * D2R) < 1e-15, "lon step " + (sky2[0] - sky[0]));
        assertTrue(Math.abs(sky2[1] - sky[1] - 0.125 * D2R) < 1e-15, "lat step " + (sky2[1] - sky[1]));
    }

    @Test
    void carlinKeepsTheCartesianProjectionDescription() {
        // C gives WcsSkyFrame the real projection (AST__CAR), not the AST__WCSBAD that the WcsMap
        // is made with, and sets Projection from it (fitschan.c:39849).
        Frame cur = readCar1(true).getFrame(FrameSet.AST__CURRENT);
        assertInstanceOf(SkyFrame.class, cur, "current Frame is a " + cur.astClass());
        assertEquals("Cartesian", ((SkyFrame) cur).getProjection());
    }
}
