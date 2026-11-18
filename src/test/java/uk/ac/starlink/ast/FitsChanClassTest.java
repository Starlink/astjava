package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_fitschan_class.rs}: the celestial projection of FrameSets
 * read from FITS-CLASS headers. The expected projections are those C gives reading the same
 * headers ({@code astGetC} of Projection on the SkyFrame {@code astFindFrame} finds in the current
 * Frame).
 */
class FitsChanClassTest {

    /**
     * A FITS-CLASS header whose celestial CTYPEs have no projection code; DELTAV and a VELO-xxx
     * keyword make the encoding guessed FITS-CLASS.
     */
    private static final String CLASS_BLANK_PROJECTION = String.join(
            "\n",
            "SIMPLE  =                    T",
            "BITPIX  =                  -32",
            "NAXIS   =                    3",
            "NAXIS1  =                 1024",
            "NAXIS2  =                    1",
            "NAXIS3  =                    1",
            "CTYPE1  = 'FREQ    '",
            "CTYPE2  = 'RA--    '",
            "CTYPE3  = 'DEC-    '",
            "CRVAL1  =                  0.0",
            "CRVAL2  =               40.738",
            "CRVAL3  =               62.933",
            "CRPIX1  =              512.500",
            "CRPIX2  =                  0.5",
            "CRPIX3  =                  0.5",
            "CDELT1  =              39062.5",
            "CDELT2  =              -0.0100",
            "CDELT3  =               0.0100",
            "EQUINOX =               2000.0",
            "RADESYS = 'FK5     '",
            "RESTFREQ=       1.102013543E11",
            "VELO-OBS=               2500.0",
            "DELTAV  =             -106.266",
            "");

    /** Galactic celestial axes with an explicit {@code -GLS} projection code. */
    private static final String EXPLICIT_GLS = String.join(
            "\n",
            "NAXIS   =                    2",
            "NAXIS1  =                  100",
            "NAXIS2  =                  100",
            "CTYPE1  = 'GLON-GLS'",
            "CTYPE2  = 'GLAT-GLS'",
            "CRVAL1  =                 10.0",
            "CRVAL2  =                 20.0",
            "CRPIX1  =                 50.5",
            "CRPIX2  =                 50.5",
            "CDELT1  =                -0.01",
            "CDELT2  =                 0.01",
            "");

    /** The Projection of the SkyFrame in the current Frame of the FrameSet {@code header} reads as. */
    private static String projection(String header) {
        FrameSet fs = FitsHeaders.readFrameSet(FitsChan.fromHeader(header));
        Frame cur = fs.getFrame(FrameSet.AST__CURRENT);
        SkyFrame template = new SkyFrame();
        template.setMaxAxes(100);
        FrameSet found = cur.findFrame(template, "");
        assertNotNull(found, "a SkyFrame in the current Frame");
        return found.getC("Projection");
    }

    @Test
    void blankClassProjectionKeepsTheGlsDescription() {
        // C's ClassTrans gives a blank projection code -GLS; SpecTrans translates -GLS to -SFL only
        // when the projection it took from the CTYPE values before ClassTrans ran was -GLS, so the
        // WcsMap is a GLS one, described as sinusoidal.
        assertEquals("sinusoidal", projection(CLASS_BLANK_PROJECTION));
    }

    @Test
    void explicitGlsProjectionBecomesSfl() {
        assertEquals("Sanson-Flamsteed", projection(EXPLICIT_GLS));
    }
}
