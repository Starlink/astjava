package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/guess_encoding.rs}: the default Encoding
 * chosen from the cards (fitschan.c {@code GetEncoding}).
 */
class FitsGuessEncodingTest {

    private static FitsChan.Encoding guess(String header) {
        return FitsGuessEncoding.guess(FitsCardList.fromHeader(header));
    }

    @Test
    void detectFitswcsCdelt() {
        assertEquals(
                FitsChan.Encoding.FITS_WCS,
                guess("CTYPE1  = 'RA---TAN'           / Axis type\n"
                        + "CTYPE2  = 'DEC--TAN'           / Axis type\n"
                        + "CRVAL1  =              180.0   / Reference value\n"
                        + "CRVAL2  =              -45.0   / Reference value\n"
                        + "CRPIX1  =              512.0   / Reference pixel\n"
                        + "CRPIX2  =              512.0   / Reference pixel\n"
                        + "CDELT1  =           -0.00028   / Pixel scale\n"
                        + "CDELT2  =            0.00028   / Pixel scale\n"
                        + "RADESYS = 'FK5'                / Reference frame"));
    }

    @Test
    void detectFitswcsCdMatrix() {
        assertEquals(
                FitsChan.Encoding.FITS_WCS,
                guess("CTYPE1  = 'RA---TAN'           / Axis type\n"
                        + "CTYPE2  = 'DEC--TAN'           / Axis type\n"
                        + "CRVAL1  =              180.0   / Reference value\n"
                        + "CRVAL2  =              -45.0   / Reference value\n"
                        + "CRPIX1  =              512.0   / Reference pixel\n"
                        + "CRPIX2  =              512.0   / Reference pixel\n"
                        + "CD1_1   =           -0.00028   / CD matrix\n"
                        + "CD2_2   =            0.00028   / CD matrix\n"
                        + "RADESYS = 'FK5'                / Reference frame"));
    }

    @Test
    void detectIrafRadecsys() {
        assertEquals(
                FitsChan.Encoding.FITS_IRAF,
                guess("CTYPE1  = 'RA---TAN'           / Axis type\n"
                        + "CTYPE2  = 'DEC--TAN'           / Axis type\n"
                        + "CRVAL1  =              180.0   / Reference value\n"
                        + "CRVAL2  =              -45.0   / Reference value\n"
                        + "CRPIX1  =              512.0   / Reference pixel\n"
                        + "CRPIX2  =              512.0   / Reference pixel\n"
                        + "CD1_1   =           -0.00028   / CD matrix\n"
                        + "CD2_2   =            0.00028   / CD matrix\n"
                        + "RADECSYS= 'FK5'                / Old-style reference frame"));
    }

    @Test
    void detectPc3digit() {
        assertEquals(
                FitsChan.Encoding.FITS_PC,
                guess("CTYPE1  = 'RA---TAN'           / Axis type\n"
                        + "CRVAL1  =              180.0   / Reference value\n"
                        + "CRPIX1  =              512.0   / Reference pixel\n"
                        + "PC001001=                  1.0 / PC matrix element"));
    }

    @Test
    void detectAipsCrota() {
        assertEquals(
                FitsChan.Encoding.FITS_AIPS,
                guess("CTYPE1  = 'RA---TAN'           / Axis type\n"
                        + "CTYPE2  = 'DEC--TAN'           / Axis type\n"
                        + "CRVAL1  =              180.0   / Reference value\n"
                        + "CRVAL2  =              -45.0   / Reference value\n"
                        + "CRPIX1  =              512.0   / Reference pixel\n"
                        + "CRPIX2  =              512.0   / Reference pixel\n"
                        + "CDELT1  =           -0.00028   / Pixel scale\n"
                        + "CDELT2  =            0.00028   / Pixel scale\n"
                        + "CROTA2  =                0.0   / Rotation\n"
                        + "EPOCH   =             2000.0   / Epoch of observation"));
    }

    @Test
    void detectAipsSpectral() {
        assertEquals(
                FitsChan.Encoding.FITS_AIPS,
                guess("CTYPE1  = 'VELO-LSR'           / Axis type\n"
                        + "CRVAL1  =            10000.0   / Reference value\n"
                        + "CRPIX1  =                1.0   / Reference pixel\n"
                        + "CDELT1  =             1000.0   / Channel width"));
    }

    @Test
    void detectAipsppSpectralWithCd() {
        assertEquals(
                FitsChan.Encoding.FITS_AIPSPP,
                guess("CTYPE1  = 'VELO-LSR'           / Axis type\n"
                        + "CRVAL1  =            10000.0   / Reference value\n"
                        + "CRPIX1  =                1.0   / Reference pixel\n"
                        + "CD1_1   =             1000.0   / CD matrix"));
    }

    @Test
    void detectClass() {
        assertEquals(
                FitsChan.Encoding.FITS_CLASS,
                guess("CTYPE1  = 'RA---TAN'           / Axis type\n"
                        + "CRVAL1  =              180.0   / Reference value\n"
                        + "CRPIX1  =              512.0   / Reference pixel\n"
                        + "DELTAV  =             1000.0   / Velocity increment\n"
                        + "VELO-LSR=            10000.0   / Source velocity"));
    }

    @Test
    void detectDss() {
        assertEquals(
                FitsChan.Encoding.DSS,
                guess("PLTRAH  =                   12 / Plate center RA hours\n"
                        + "PLTRAM  =                   30 / Plate center RA minutes\n"
                        + "PLTRAS  =                 0.0  / Plate center RA seconds\n"
                        + "PLTDECSN= '+'                  / Plate center Dec sign"));
    }

    @Test
    void detectNative() {
        assertEquals(
                FitsChan.Encoding.NATIVE,
                guess("COMMENT  AST +++++++++++++++++++++++++++++++++ AST\n"
                        + "COMMENT  AST    Begin ZoomMap                   AST\n"
                        + "COMMENT  AST --------------------------------- AST"));
    }

    @Test
    void isAipsSpectralValid() {
        for (String s :
                new String[] {"VELO-LSR", "FREQ-OBS", "FELO-HEL", "VELO-TOP", "FREQ-GEO", "FELO-EAR", "VELO-LSD"}) {
            assertNotNull(FitsGuessEncoding.isAipsSpectral(s), s);
        }
        for (String s : new String[] {"VELO-XYZ", "RA---TAN", "VELO", "VELOCITY", "FREQ+LSR"}) {
            assertNull(FitsGuessEncoding.isAipsSpectral(s), s);
        }
    }

    @Test
    void fallbackToNative() {
        assertEquals(
                FitsChan.Encoding.NATIVE,
                guess("SIMPLE  =                    T / Standard FITS\n"
                        + "BITPIX  =                  -32 / Bits per pixel"));
    }

    @Test
    void keywordNativeDetectedAsNativeEncoding() {
        assertEquals(
                FitsChan.Encoding.NATIVE,
                guess("BEGAST_A= 'FrameSet'\nNFRAME_A=                    2\n" + "ENDAST_A= 'FrameSet'\nEND\n"));
    }
}
