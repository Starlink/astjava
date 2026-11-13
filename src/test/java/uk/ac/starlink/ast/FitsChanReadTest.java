package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_fitschan_read.rs}: the Frame and Axis attributes a
 * FITS-WCS read sets, each test isolating one decision of C's {@code WcsSkyFrame} or {@code
 * WcsOthers}.
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code log_suffix_is_stripped_from_the_domain_and_symbol}
 * (a {@code -LOG} axis, which C maps with a MathMap), {@code dsb_centre_prefers_axref_over_crval}
 * (a DSBSpecFrame) and {@code unset_system_still_leaves_system_unset} (a constructor of the Rust
 * port's own).
 */
class FitsChanReadTest {

    /** A 2-axis Galactic header carrying EQUINOX and no observation date. */
    private static final String GALACTIC_EQUINOX_ONLY = String.join(
            "\n",
            "NAXIS   =                    2",
            "NAXIS1  =                  100",
            "NAXIS2  =                  100",
            "CTYPE1  = 'GLON-TAN'",
            "CTYPE2  = 'GLAT-TAN'",
            "CRVAL1  =                 10.0",
            "CRVAL2  =                 20.0",
            "CRPIX1  =                 50.5",
            "CRPIX2  =                 50.5",
            "CDELT1  =                -0.01",
            "CDELT2  =                 0.01",
            "EQUINOX =               2000.0",
            "");

    /** A plain 2-axis equatorial header followed by {@code extra}. */
    private static String equatorialHeader(String extra) {
        return String.join(
                        "\n",
                        "NAXIS   =                    2",
                        "NAXIS1  =                  100",
                        "NAXIS2  =                  100",
                        "CTYPE1  = 'RA---TAN'",
                        "CTYPE2  = 'DEC--TAN'",
                        "CRVAL1  =                 10.0",
                        "CRVAL2  =                 20.0",
                        "CRPIX1  =                 50.5",
                        "CRPIX2  =                 50.5",
                        "CDELT1  =                -0.01",
                        "CDELT2  =                 0.01",
                        "")
                + extra;
    }

    /** A 1-axis linear header followed by {@code ctype}. */
    private static String oneAxisHeader(String ctype) {
        return String.join(
                        "\n",
                        "NAXIS   =                    1",
                        "NAXIS1  =                  100",
                        "CRVAL1  =                  1.0",
                        "CRPIX1  =                  1.0",
                        "CDELT1  =                  0.1",
                        "")
                + ctype;
    }

    private static Frame currentFrame(String header) {
        return FitsHeaders.readFrameSet(FitsChan.fromHeader(header)).getFrame(FrameSet.AST__CURRENT);
    }

    private static SkyFrame readSkyFrame(String header) {
        Frame f = currentFrame(header);
        assertInstanceOf(SkyFrame.class, f, "current Frame is a " + f.astClass());
        return (SkyFrame) f;
    }

    @Test
    void galacticHeaderTakesItsEpochFromEquinox() {
        // C's WcsSkyFrame converts EQUINOX to an MJD whatever the system (fitschan.c:39750-39759)
        // and uses it for Epoch when there is no observation date (fitschan.c:39763-39765, 39852),
        // but sets Equinox only for equatorial and ecliptic systems (fitschan.c:39856-39857).
        SkyFrame sky = readSkyFrame(GALACTIC_EQUINOX_ONLY);
        assertEquals("GALACTIC", sky.getSystem());
        assertTrue(sky.test("Epoch"), "Epoch is set from EQUINOX");
        assertTrue(Math.abs(sky.getEpoch() - 2000.0) < 1e-9, "Epoch " + sky.getEpoch());
        assertFalse(sky.test("Equinox"), "a Galactic SkyFrame has no Equinox set");
    }

    @Test
    void cnameSuppliesSkyframeAxisLabels() {
        // C sets the Labels of SkyFrame axes 1 and 2 from the CNAME of the longitude and latitude
        // axes (fitschan.c:39859-39863).
        SkyFrame sky = readSkyFrame(equatorialHeader("CNAME1  = 'Right Ascension'\nCNAME2  = 'Declination'\n"));
        assertTrue(sky.test("Label(1)"));
        assertTrue(sky.test("Label(2)"));
        assertEquals("Right Ascension", sky.getLabel(1));
        assertEquals("Declination", sky.getLabel(2));
    }

    @Test
    void absentCnameLeavesSkyframeLabelsAtTheirDefaults() {
        SkyFrame sky = readSkyFrame(equatorialHeader(""));
        assertFalse(sky.test("Label(1)"));
        assertFalse(sky.test("Label(2)"));
        assertEquals("Right ascension", sky.getLabel(1));
        assertEquals("Declination", sky.getLabel(2));
    }

    @Test
    void fitsReadSetsSystemEvenWhenTheValueIsTheIcrsDefault() {
        // C makes the SkyFrame with an explicit System, "System=ICRS" for an ICRS header
        // (fitschan.c:39796), so System is set.
        SkyFrame sky = readSkyFrame(equatorialHeader("RADESYS = 'ICRS    '\n"));
        assertEquals("ICRS", sky.getSystem());
        assertTrue(sky.test("System"), "System is set on a SkyFrame read from FITS");
    }

    @Test
    void fitsReadSetsSystemForANonDefaultValueToo() {
        SkyFrame sky = readSkyFrame(equatorialHeader("RADESYS = 'FK5     '\nEQUINOX =               2000.0\n"));
        assertEquals("FK5", sky.getSystem());
        assertTrue(sky.test("System"));
    }

    @Test
    void unclassifiedAxisDomainComesFromCtype() {
        // C's WcsOthers builds the Domain of the axes no other function claims from their CTYPE
        // values, joined with "-" (fitschan.c:39370-39382).
        Frame f = currentFrame(oneAxisHeader("CTYPE1  = 'XXXX-WAV'\n"));
        assertEquals("XXXX-WAV", f.getDomain());
        assertTrue(f.test("Domain"), "Domain is set");
    }

    @Test
    void absentCtypeFallsBackToTheAxisNumber() {
        // An absent CTYPE is replaced by "AXIS<n>" (fitschan.c:39345-39349).
        Frame f = currentFrame(oneAxisHeader(""));
        assertEquals("AXIS1", f.getDomain());
        assertTrue(f.test("Domain"));
    }

    @Test
    void blankCtypeLeavesDomainUnsetAndNeverLeaksTheSentinel() {
        // A blank CTYPE is present, so "AXIS<n>" does not apply, but astChrLen of it is zero, so
        // nothing is added to the Domain (fitschan.c:39371) and C clears it (fitschan.c:39427).
        Frame f = currentFrame(oneAxisHeader("CTYPE1  = '        '\n"));
        assertFalse(f.test("Domain"));
        assertEquals("", f.getDomain());
        assertNotEquals("AST_FITSCHAN", f.getDomain());
    }

    @Test
    void twoUnclassifiedAxesJoinTheirCtypesWithADash() {
        String header = String.join(
                "\n",
                "NAXIS   =                    2",
                "NAXIS1  =                  100",
                "NAXIS2  =                   10",
                "CTYPE1  = 'AAAA    '",
                "CTYPE2  = 'BBBB    '",
                "CRVAL1  =                  1.0",
                "CRVAL2  =                  2.0",
                "CRPIX1  =                  1.0",
                "CRPIX2  =                  1.0",
                "CDELT1  =                  0.1",
                "CDELT2  =                  0.2",
                "");
        assertEquals("AAAA-BBBB", currentFrame(header).getDomain());
    }
}
