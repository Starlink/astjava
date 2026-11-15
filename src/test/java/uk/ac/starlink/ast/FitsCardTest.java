package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/card.rs}: parsing and formatting single
 * header cards (fitschan.c {@code Split} and {@code FormatCard}).
 */
class FitsCardTest {

    private static void assertParse(String card, String kw, FitsCard.Value value, String comment) {
        FitsCard c = FitsCard.parse(card);
        assertEquals(kw, c.keyword, "keyword");
        assertEquals(value, c.value, "value of " + kw);
        assertEquals(comment, c.comment, "comment of " + kw);
    }

    private static FitsCard card(String kw, FitsCard.Value value, String comment) {
        return new FitsCard(kw, value, comment);
    }

    @Test
    void parseInteger() {
        assertParse(
                "NAXIS   =                    2 / Number of axes", "NAXIS", FitsCard.Value.ofInt(2), "Number of axes");
    }

    @Test
    void parseNegativeInteger() {
        assertParse(
                "NEGVAL  =                  -42 / Negative value",
                "NEGVAL",
                FitsCard.Value.ofInt(-42),
                "Negative value");
    }

    @Test
    void parseLargeInteger() {
        FitsCard c = FitsCard.parse("BIGINT  =       9999999999999 / Large int");
        assertEquals("BIGINT", c.keyword);
        assertEquals(FitsCard.Value.ofKInt(9_999_999_999_999L), c.value);
    }

    @Test
    void parseFloat() {
        assertParse(
                "CRVAL1  =     266.416666666667 / Reference value",
                "CRVAL1",
                FitsCard.Value.ofFloat(266.416666666667),
                "Reference value");
    }

    @Test
    void parseScientificNotation() {
        assertParse(
                "SMALLF  =            1.5E-10 / Tiny float", "SMALLF", FitsCard.Value.ofFloat(1.5e-10), "Tiny float");
    }

    @Test
    void parseDExponent() {
        assertParse("DEXP    =            1.5D-10 / D exponent", "DEXP", FitsCard.Value.ofFloat(1.5e-10), "D exponent");
    }

    @Test
    void parseString() {
        assertParse(
                "CTYPE1  = 'RA---TAN'           / Axis type",
                "CTYPE1",
                FitsCard.Value.ofString("RA---TAN"),
                "Axis type");
    }

    @Test
    void parseStringWithEscapedQuotes() {
        assertParse(
                "COMMENT1= 'It''s a test'       / Escaped quote",
                "COMMENT1",
                FitsCard.Value.ofString("It's a test"),
                "Escaped quote");
    }

    @Test
    void parseStringValueKeepsPaddingOnLongValues() {
        // C strips trailing spaces only when astGetFitsS returns a value of 8 characters or fewer.
        FitsCard c = FitsCard.parse("RA      = '07:21:44.00       ' / RA of observation (hr)                        ");
        assertEquals(FitsCard.Value.ofString("07:21:44.00       "), c.value);
    }

    @Test
    void parseStringValueKeepsPaddingOnShortValues() {
        // C's Split stores the value as written.
        FitsCard c = FitsCard.parse("CTYPE1  = 'RA--    '           / Axis type                                     ");
        assertEquals(FitsCard.Value.ofString("RA--    "), c.value);
    }

    @Test
    void parseLongString() {
        FitsCard c = FitsCard.parse("LONGSTR = 'This is a long string value that fills up most of the card'          ");
        assertEquals("LONGSTR", c.keyword);
        assertEquals(FitsCard.Value.ofString("This is a long string value that fills up most of the card"), c.value);
    }

    @Test
    void parseLogicalTrue() {
        assertParse(
                "SIMPLE  =                    T / Fits standard",
                "SIMPLE",
                FitsCard.Value.ofLogical(true),
                "Fits standard");
    }

    @Test
    void parseLogicalFalse() {
        FitsCard c = FitsCard.parse("EXTEND  =                    F / No extensions");
        assertEquals("EXTEND", c.keyword);
        assertEquals(FitsCard.Value.ofLogical(false), c.value);
    }

    @Test
    void parseCommentCard() {
        FitsCard c = FitsCard.parse("COMMENT This is a test comment");
        assertEquals("COMMENT", c.keyword);
        assertEquals(FitsCard.Kind.COMMENT, c.value.kind);
        assertTrue(c.value.s.contains("This is a test comment"));
    }

    @Test
    void parseHistoryCard() {
        FitsCard c = FitsCard.parse("HISTORY Created by AST library");
        assertEquals("HISTORY", c.keyword);
        assertEquals(FitsCard.Kind.COMMENT, c.value.kind);
    }

    @Test
    void parseBlankKeyword() {
        FitsCard c = FitsCard.parse("        This is a blank keyword comment card");
        assertEquals("", c.keyword);
        assertEquals(FitsCard.Kind.COMMENT, c.value.kind);
    }

    @Test
    void parseEndCard() {
        FitsCard c = FitsCard.parse("END");
        assertEquals("END", c.keyword);
        assertEquals(FitsCard.Kind.COMMENT, c.value.kind);
    }

    @Test
    void parseUndefinedValue() {
        assertParse(
                "UNDEF   =                      / Undefined value", "UNDEF", FitsCard.Value.UNDEF, "Undefined value");
    }

    @Test
    void parseContinueCard() {
        assertParse(
                "CONTINUE  'continued value'    / More text",
                "CONTINUE",
                FitsCard.Value.ofContinue("continued value"),
                "More text");
    }

    @Test
    void parseShortCard() {
        FitsCard c = FitsCard.parse("NAXIS   =                    3");
        assertEquals("NAXIS", c.keyword);
        assertEquals(FitsCard.Value.ofInt(3), c.value);
    }

    @Test
    void parseComplexFloat() {
        FitsCard c = FitsCard.parse("CMPLXF  =                  1.5                 2.5 / Complex float");
        assertEquals("CMPLXF", c.keyword);
        assertEquals(FitsCard.Value.ofComplexFloat(1.5, 2.5), c.value);
    }

    @Test
    void parseComplexInt() {
        FitsCard c = FitsCard.parse("CMPLXI  =                    3                    4 / Complex int");
        assertEquals("CMPLXI", c.keyword);
        assertEquals(FitsCard.Value.ofComplexInt(3, 4), c.value);
    }

    @Test
    void parenthesisedComplexIsAnIllegalValue() {
        FitsCard.Split s = FitsCard.split("CMPLXF  = (1.5, 2.5)          / Complex float");
        assertTrue(s.badValue);
        assertEquals("CMPLXF", s.card.keyword);
        assertEquals(FitsCard.Value.ofComment(""), s.card.value);
        assertNull(s.card.comment);
    }

    @Test
    void formatInteger() {
        String f = card("NAXIS", FitsCard.Value.ofInt(2), "Number of axes").format();
        assertEquals(80, f.length());
        assertTrue(f.startsWith("NAXIS   = "));
        assertTrue(f.contains("2"));
        assertTrue(f.contains("/ Number of axes"));
    }

    @Test
    void formatString() {
        String f =
                card("CTYPE1", FitsCard.Value.ofString("RA---TAN"), "Axis type").format();
        assertEquals(80, f.length());
        assertTrue(f.contains("'RA---TAN'"));
        assertTrue(f.contains("/ Axis type"));
    }

    @Test
    void formatLogical() {
        String f =
                card("SIMPLE", FitsCard.Value.ofLogical(true), "Fits standard").format();
        assertEquals(80, f.length());
        assertEquals('T', f.charAt(29));
    }

    @Test
    void formatFloat() {
        String f = card("CRVAL1", FitsCard.Value.ofFloat(266.416666666667), "Reference value")
                .format();
        assertEquals(80, f.length());
        assertTrue(f.contains("/ Reference value"));
    }

    /**
     * A value too wide for the 20-column field starts in column 11 (fitschan.c:9979-9987); the
     * card is from C's {@code tidy-pole.native}.
     */
    @Test
    void formatFloatOverwideExponentStaysLeftOfTheField() {
        String f = card("SCL1_A", FitsCard.Value.ofFloat(-1.7453292519943296E-4), "Scale factor for axis 1")
                .formatWithDigits(17);
        assertEquals("SCL1_A  = -1.7453292519943296E-4 / Scale factor for axis 1", stripTrailing(f));
    }

    @Test
    void formatFloatNarrowExponentStaysRightJustified() {
        String f = card("SFT1_C", FitsCard.Value.ofFloat(-7.67E-9), null).formatWithDigits(17);
        assertEquals("SFT1_C  =             -7.67E-9", stripTrailing(f));
        assertEquals(30, stripTrailing(f).length());
    }

    @Test
    void formatUndefined() {
        String f = card("UNDEF", FitsCard.Value.UNDEF, "No value").format();
        assertEquals(80, f.length());
        assertTrue(f.startsWith("UNDEF   = "));
        assertTrue(f.contains("/ No value"));
    }

    @Test
    void formatCommentCard() {
        String f = card("COMMENT", FitsCard.Value.ofComment(" This is a test"), null)
                .format();
        assertEquals(80, f.length());
        assertTrue(f.startsWith("COMMENT  This is a test"));
    }

    @Test
    void roundtripInteger() {
        FitsCard c = FitsCard.parse("CRPIX1  =                128.0 / Reference pixel");
        String f = c.format();
        assertEquals(80, f.length());
        FitsCard back = FitsCard.parse(f);
        assertEquals(c.keyword, back.keyword);
        assertEquals(FitsCard.Kind.FLOAT, c.value.kind);
        assertEquals(FitsCard.Kind.FLOAT, back.value.kind);
        assertTrue(Math.abs(c.value.d - back.value.d) < 1e-10);
    }

    @Test
    void roundtripStringWithQuotes() {
        String f = card("TEST", FitsCard.Value.ofString("It's OK"), "Quoted").format();
        assertEquals(80, f.length());
        assertEquals(FitsCard.Value.ofString("It's OK"), FitsCard.parse(f).value);
    }

    @Test
    void roundtripLogical() {
        String f = card("SIMPLE", FitsCard.Value.ofLogical(true), "Standard").format();
        assertEquals(FitsCard.Value.ofLogical(true), FitsCard.parse(f).value);
    }

    @Test
    void roundtripComplexFloat() {
        String f = card("CMPLX", FitsCard.Value.ofComplexFloat(1.5, -2.5), null).format();
        assertEquals(80, f.length());
    }

    @Test
    void formatContinueCard() {
        String f = card("CONTINUE", FitsCard.Value.ofContinue("long string value"), "continuation")
                .format();
        assertEquals(80, f.length());
        assertTrue(f.startsWith("CONTINUE"));
        assertTrue(f.contains("'long string value"));
    }

    @Test
    void parseNoComment() {
        FitsCard c = FitsCard.parse("NAXIS   =                    4");
        assertEquals("NAXIS", c.keyword);
        assertEquals(FitsCard.Value.ofInt(4), c.value);
        assertNull(c.comment);
    }

    @Test
    void parseStringWithSlashInside() {
        assertParse(
                "PATH    = '/usr/local/bin'     / The path",
                "PATH",
                FitsCard.Value.ofString("/usr/local/bin"),
                "The path");
    }

    @Test
    void parseZeroFloat() {
        assertParse("ZEROF   =                  0.0 / Zero float", "ZEROF", FitsCard.Value.ofFloat(0.0), "Zero float");
    }

    @Test
    void parseNegativeFloat() {
        FitsCard c = FitsCard.parse("NEGF    =    -1.23456789012345 / Negative float");
        assertEquals("NEGF", c.keyword);
        assertEquals(FitsCard.Kind.FLOAT, c.value.kind);
        assertTrue(Math.abs(c.value.d - -1.23456789012345) < 1e-14);
    }

    @Test
    void checkFitsNameLegal() {
        assertNull(FitsCard.checkFitsName("CRVAL1  "));
        assertNull(FitsCard.checkFitsName("MJD-OBS "));
        assertNull(FitsCard.checkFitsName("WCSAXES "));
        assertNull(FitsCard.checkFitsName("OBS_LON "));
        assertNull(FitsCard.checkFitsName("        "));
        // An '=' before column 9 is an error in C, not a warning.
        assertNull(FitsCard.checkFitsName("A=B     "));
    }

    @Test
    void checkFitsNameSpaceIsNotReported() {
        // CheckFitsName (fitschan.c:6072-6095) has no branch for a space.
        assertNull(FitsCard.checkFitsName("FITS hea"));
        assertNull(FitsCard.checkFitsName("A B     "));
    }

    @Test
    void checkFitsNameIllegalCharPreservesCase() {
        assertEquals(
                "The FITS keyword name ('sys.') contains an illegal character ('.').", FitsCard.checkFitsName("sys."));
    }

    @Test
    void wrapWarningTextBreaksOnWordBoundary() {
        String text = "The FITS keyword name ('sys.') contains an illegal character ('.').";
        assertEquals(
                Arrays.asList("The FITS keyword name ('sys.') contains an illegal character ", "('.')."),
                FitsCard.wrapWarningText(text));
    }

    private static String stripTrailing(String s) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == ' ') {
            n--;
        }
        return s.substring(0, n);
    }
}
