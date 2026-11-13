package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_alt_wcs_read.rs}: a FITS-WCS header with an alternate axis
 * description reads into a FrameSet with a Frame for each.
 */
class AltWcsReadTest {

    private static final String FITS_WCS_MULTI_HEAD = String.join(
            "\n",
            "NAXIS   =                    2",
            "NAXIS1  =                  300",
            "NAXIS2  =                  300",
            "WCSAXES =                    2",
            "CTYPE1  = 'GLON-TAN'           / Galactic longitude",
            "CTYPE2  = 'GLAT-TAN'           / Galactic latitude",
            "CRVAL1  =              180.000",
            "CRVAL2  =                0.000",
            "CRPIX1  =              150.500",
            "CRPIX2  =              150.500",
            "CDELT1  =             -0.01000",
            "CDELT2  =              0.01000",
            "WCSAXESA=                    2",
            "CTYPE1A = 'RA---TAN'",
            "CTYPE2A = 'DEC--TAN'",
            "CRVAL1A =              266.405",
            "CRVAL2A =              -28.936",
            "CRPIX1A =              150.500",
            "CRPIX2A =              150.500",
            "CDELT1A =             -0.01000",
            "CDELT2A =              0.01000",
            "EQUINOXA=               2000.0",
            "RADESYSA= 'FK5     '",
            "END",
            "");

    @Test
    void fitsWcsMultiReadsIntoThreeFrameFrameset() {
        FrameSet fs = FitsHeaders.readFrameSet(FitsChan.fromHeader(FITS_WCS_MULTI_HEAD));
        assertEquals(3, fs.getNframe(), "the base Frame, the primary and alternate A");
        assertEquals("GRID", fs.getFrame(1).getDomain());
        // C's WcsMapFrm sets each Frame's Ident to the version character, a space for the
        // primary description (fitschan.c:38300-38302).
        Frame primary = fs.getFrame(2);
        assertEquals("SKY", primary.getDomain());
        assertEquals(" ", primary.getIdent());
        Frame alt = fs.getFrame(3);
        assertEquals("SKY", alt.getDomain());
        assertEquals("A", alt.getIdent());
    }
}
