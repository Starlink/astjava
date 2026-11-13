package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * Translation of the unit tests of Rust {@code fitschan/options.rs}: the settings a new FitsChan
 * takes from {@code FITSCHAN_OPTIONS}, applied as C's {@code astEnvSet} applies them through
 * {@code astSet}. The cases are those of a C probe setting the variable before {@code astFitsChan}.
 */
class FitsChanOptionsTest {

    /** A FitsChan with no setting but {@code settings}. */
    private static FitsChan with(String settings) {
        FitsChan fc = FitsChan.bare();
        FitsChanOptions.apply(fc, settings);
        return fc;
    }

    private static AstException fails(String settings) {
        return assertThrows(AstException.class, () -> with(settings));
    }

    @Test
    void valuesAreTakenAsWrittenAfterTheEqualsSign() {
        assertEquals(FitsChan.Encoding.FITS_WCS, with("Encoding=FITS-WCS").encoding());
        AstException e = fails(" Encoding = FITS-WCS");
        assertEquals(AstException.AST__BADAT, e.getStatus());
        assertTrue(
                e.getMessage()
                        .startsWith("astSet(FitsChan): Unknown encoding system ' FITS-WCS' requested for a FitsChan."),
                e.getMessage());
        e = fails("Encoding=FITS-WCS,");
        assertTrue(e.getMessage().contains("'FITS-WCS,'"), e.getMessage());
    }

    @Test
    void encodingNamesMayBeAbbreviatedAndUseUnderscores() {
        assertEquals(FitsChan.Encoding.FITS_PC, with("encoding=fits").encoding());
        FitsChan fc = with("Encoding=");
        assertTrue(fc.test("Encoding"));
        assertEquals(FitsChan.Encoding.NATIVE, fc.encoding());
        fc = with("Encoding=fits_wcs,FitsDigits=8");
        assertEquals(FitsChan.Encoding.FITS_WCS, fc.encoding());
        assertEquals(8, fc.getFitsDigits());
    }

    @Test
    void quotesProtectCommasAndAreRemoved() {
        assertEquals("badval", with("Warnings=\"badval\"").getWarnings());
        AstException e = fails("FitsDigits = 8 , Warnings=\"badval, x\"");
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertTrue(e.getMessage().contains("'badval,'"), e.getMessage());
        assertEquals("badval tnx", with("Warnings=badval tnx").getWarnings());
    }

    @Test
    void channelAttributesAndNumbers() {
        FitsChan fc = with("FitsTol=1e-3,strict=1");
        assertEquals(1e-3, fc.getFitsTol());
        assertTrue(fc.getStrict());
    }

    /** C probe: with {@code UseDefs=0}, the dump's Object part holds only {@code UseDfs = 0}. */
    @Test
    void useDefsIsKeptAndDumped() {
        String[] cDump = {
            " Begin FitsChan \t# I/O channels to FITS files",
            "    UseDfs = 0 \t# Default values cannot be used",
            " IsA Object \t# AST Object",
            "    Card = 0 \t# Index of current card",
            "#   Encod = \"NATIVE\" \t# Encoding system",
            "#   FAxOrd = \"<auto>\" \t# Order of WCS axes in new FITS headers",
            "#   FitsDg = 17 \t# No. of digits for floating point values",
            "#   FitsRn = 10 \t# No. of digits guarded from rounding",
            "#   AltAx = \"ALL\" \t# Creation of alternate axes",
            "#   DfB1950 = 1 \t# Default to FK4 B1950",
            "#   TabOK = 0 \t# Do not support -TAB CTYPE codes",
            "#   FrcTab = 0 \t# Only use -TAB if necessary",
            "#   IgBdAl = 0 \t# Abort on unreadable alternate axes",
            "#   CdMat = 0 \t# Use PC matrix",
            "#   CarLin = 0 \t# Use full FITS-WCS CAR projections",
            "#   SipRpl = 1 \t# Replace SIP inverse coefficients?",
            "#   FitsTol = 0.10000000000000001 \t# [pixel] Max allowed departure from linearity",
            "#   Iwc = 0 \t# Do not include an IWC Frame",
            "#   Warn = \"BadKeyName BadKeyValue Tnx Zpx BadPV BadCel BadMat BadCTYPE\" \t# Warnings to be reported",
            " End FitsChan",
        };
        String text = ChannelText.dump(with("UseDefs=0"));
        assertEquals(Arrays.asList(cDump), CValues.lines(text));
        assertEquals(Arrays.asList(cDump), CValues.lines(ChannelText.dump(ChannelText.read(text))));
    }

    @Test
    void badSettingsAreErrors() {
        AstException e = fails("FitsDigits=8, Bogus=1");
        assertEquals(AstException.AST__BADAT, e.getStatus());
        assertTrue(
                e.getMessage().startsWith("astSet: The attribute setting \"bogus=1\" is invalid for a FitsChan."),
                e.getMessage());
        e = fails("Ncard=3");
        assertEquals(AstException.AST__NOWRT, e.getStatus());
        assertTrue(
                e.getMessage().startsWith("astSet: The setting \"ncard=3\" is invalid for a FitsChan."),
                e.getMessage());
        assertEquals(AstException.AST__BADAT, fails("cdmatrix=1 x").getStatus());
    }
}
