package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_fitschan_time.rs}: the DUT1 and DTAI values a FITS-WCS read
 * sets on the world Frame (C's {@code WcsMapFrm}), and the TELESCOP condition on reading DTAI at
 * all (C's {@code WcsFcRead} uses it only for JCMT and UKIRT).
 *
 * <p>The header has a spectral axis as well as the celestial pair, so the world Frame is a
 * CmpFrame: C sets the values on the SkyFrame in {@code WcsSkyFrame} and on the whole world Frame
 * in {@code WcsMapFrm}, and only a compound Frame tells the two apart. The NATIVE dump of the
 * FrameSet shows a CmpFrame's own value as a card just after its {@code BEGAST} card.
 */
class FitsChanTimeTest {

    /** The (FREQ, RA, Dec) header, with an optional TELESCOP card and DTAI value. */
    private static String header(String telescop, Double dtai) {
        StringBuilder text = new StringBuilder(String.join(
                "\n",
                "NAXIS1  =                  100 / Spectral axis",
                "NAXIS2  =                  100 / Spatial axis",
                "NAXIS3  =                  100 / Spatial axis",
                "CTYPE1  = 'FREQ    '           / Frequency axis",
                "CTYPE2  = 'RA---TAN'           / Right Ascension",
                "CTYPE3  = 'DEC--TAN'           / Declination",
                "CRVAL1  =           3.4500E+11 / Reference frequency (Hz)",
                "CRVAL2  =               83.633 / Reference RA (deg)",
                "CRVAL3  =               22.014 / Reference Dec (deg)",
                "CRPIX1  =                 50.0 / Reference pixel",
                "CRPIX2  =                 50.5 / Reference pixel",
                "CRPIX3  =                 50.5 / Reference pixel",
                "CDELT1  =           6.1035E+04 / Frequency increment (Hz)",
                "CDELT2  =           -0.0002778 / Degrees/pixel",
                "CDELT3  =            0.0002778 / Degrees/pixel",
                "CUNIT1  = 'Hz      '           / Frequency units",
                "CUNIT2  = 'deg     '           / Angular units",
                "CUNIT3  = 'deg     '           / Angular units",
                "RADESYS = 'FK5     '           / Reference frame",
                "EQUINOX =               2000.0 / Equinox",
                "SPECSYS = 'LSRK    '           / Spectral reference frame",
                "RESTFRQ =           3.4506E+11 / Rest frequency (Hz)",
                "LONPOLE =              180.000 / Native longitude of celestial pole",
                "LATPOLE =               22.014 / Native latitude of celestial pole",
                "DUT1    =             0.001    / UT1-UTC in days",
                ""));
        if (telescop != null) {
            text.append(String.format(Locale.ROOT, "TELESCOP= '%-8s'           / Telescope name\n", telescop));
        }
        if (dtai != null) {
            text.append(String.format(Locale.ROOT, "DTAI    =           %.3f    / TAI-UTC in seconds\n", dtai));
        }
        return text.toString();
    }

    private static FrameSet read(String text) {
        return FitsHeaders.readFrameSet(FitsChan.fromHeader(text));
    }

    /** The world Frame read from {@code text}, which is a CmpFrame. */
    private static Frame topFrame(String text) {
        Frame frame = read(text).getFrame(FrameSet.AST__CURRENT);
        assertInstanceOf(CmpFrame.class, frame);
        return frame;
    }

    /**
     * Whether the first card of the NATIVE dump of the FrameSet read from {@code text} whose
     * keyword starts with {@code prefix} lies just after the CmpFrame's {@code BEGAST} card, so
     * that it is the CmpFrame's own value.
     */
    private static boolean cmpFrameCarries(String text, String prefix) {
        FitsChan out = new FitsChan();
        out.setEncoding("NATIVE");
        out.write(read(text));
        List<String> lines = FitsHeaders.headerLines(out);
        int begast = -1;
        int first = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (begast < 0 && line.contains("'CmpFrame'")) {
                begast = i;
            }
            if (first < 0 && line.startsWith(prefix)) {
                first = i;
            }
        }
        assertTrue(begast >= 0, "the dump holds a CmpFrame: " + lines);
        assertTrue(first >= 0, "the dump holds a " + prefix + " card: " + lines);
        return first < begast + 5;
    }

    @Test
    void dtaiSetOnWorldFrameForJcmt() {
        String text = header("JCMT", 37.0);
        Frame frame = topFrame(text);
        assertTrue(frame.test("Dtai"), "Dtai is set for a JCMT header");
        assertEquals(37.0, frame.getDtai(), 1e-9);
        assertTrue(cmpFrameCarries(text, "DTAI_"), "the CmpFrame carries its own Dtai");
    }

    @Test
    void dtaiSetOnWorldFrameForUkirt() {
        Frame frame = topFrame(header("UKIRT", 37.0));
        assertTrue(frame.test("Dtai"), "Dtai is set for a UKIRT header");
        assertEquals(37.0, frame.getDtai(), 1e-9);
    }

    @Test
    void dtaiIgnoredForOtherTelescope() {
        assertFalse(topFrame(header("HST", 37.0)).test("Dtai"));
    }

    @Test
    void dtaiIgnoredWhenTelescopAbsent() {
        assertFalse(topFrame(header(null, 37.0)).test("Dtai"));
    }

    @Test
    void dut1SetOnWorldFrameRegardlessOfTelescop() {
        // DUT1 is given in days and set in seconds.
        String text = header(null, null);
        Frame frame = topFrame(text);
        assertTrue(frame.test("Dut1"), "Dut1 is set whatever the TELESCOP");
        assertEquals(0.001 * 86400.0, frame.getDut1(), 1e-6);
        assertTrue(cmpFrameCarries(text, "DUT1_"), "the CmpFrame carries its own Dut1");
    }
}
