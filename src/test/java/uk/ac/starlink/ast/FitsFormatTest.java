package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Translation of the unit tests of Rust {@code fitschan/format.rs}. */
class FitsFormatTest {

    /** The value as {@code encodeFloat} formats it, without its leading blanks. */
    private static String sig(double value, int digits) {
        return stripLeading(FitsFormat.encodeFloat(value, digits, 10));
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return s.substring(i);
    }

    @Test
    void formatZero() {
        String s = FitsFormat.encodeFloat(0.0, 8, 10);
        assertEquals("0.0", s.trim());
        assertEquals(20, s.length());
    }

    @Test
    void formatIntegerLike() {
        assertEquals("1.0", sig(1.0, 8));
    }

    @Test
    void formatSimpleFloat() {
        assertTrue(sig(3.14159265, 8).startsWith("3.141592"));
    }

    @Test
    void formatLargeNumber() {
        String s = sig(1.23456789e+15, 8);
        assertTrue(s.contains("E"));
        assertTrue(s.startsWith("1.2345679"));
    }

    @Test
    void formatSmallNumber() {
        String s = sig(1.5e-10, 8);
        assertTrue(s.contains("E"));
        assertTrue(s.startsWith("1.5E"));
    }

    @Test
    void formatNegative() {
        assertTrue(sig(-273.15, 6).startsWith("-273.15"));
    }

    @Test
    void formatFitsWidth() {
        String s = FitsFormat.encodeFloat(42.0, 8, 10);
        assertEquals(20, s.length());
        assertTrue(s.startsWith(" "));
        assertEquals("42.0", s.trim());
    }

    @Test
    void formatPreservesSignificantFigures() {
        double val = 266.41666666666663;
        double reparsed = Double.parseDouble(sig(val, 15));
        assertTrue(Math.abs((val - reparsed) / val) < 1e-14);
    }

    @Test
    void formatNoTrailingZeros() {
        assertEquals("1.5", sig(1.5, 8));
        assertEquals("100.0", sig(100.0, 8));
    }

    @Test
    void formatBoundaryCases() {
        assertTrue(sig(0.0001, 4).contains("0.0001"));
        assertTrue(sig(0.00001, 4).contains("E"));
    }

    @Test
    void formatNegativeSpecialCase() {
        assertEquals("-8.3332205E-4", sig(-0.00083332205, 8));
        assertEquals("-2.778E-4", sig(-0.0002778, 4));
        assertEquals("0.00083332205", sig(0.00083332205, 8));
    }

    @Test
    void formatExplicitExponentSign() {
        assertEquals("2.3635893E+8", sig(2.3635893e8, 8));
        assertTrue(sig(1.5e10, 8).contains("E+"));
    }

    @Test
    void formatRoundTrip() {
        double[] values = {1.0, -1.0, 3.14159265358979, 2.99792458e8, 6.626e-34, 1e-4, 1e15, -0.001};
        for (double val : values) {
            double reparsed = Double.parseDouble(sig(val, 15));
            double relErr = val == 0.0 ? Math.abs(reparsed) : Math.abs((val - reparsed) / val);
            assertTrue(relErr < 1e-14, "round-trip failed for " + val + ": got " + reparsed);
        }
    }

    @Test
    void roundFstringCollapsesLongNinesWithLowProtection() {
        assertEquals("1.0", stripLeading(FitsFormat.encodeFloat(0.99998719, 8, 1)));
    }

    @Test
    void roundFstringPreservesValueWithDefaultProtection() {
        assertEquals("0.99998719", stripLeading(FitsFormat.encodeFloat(0.99998719, 8, 10)));
    }

    @Test
    void roundFstringShortRunsUnchanged() {
        assertEquals("0.99987", stripLeading(FitsFormat.encodeFloat(0.99987, 5, 1)));
    }

    @Test
    void roundFstringCarryPropagationToIntegerDigit() {
        assertEquals("100.0", stripLeading(FitsFormat.encodeFloat(99.99996, 7, 1)));
    }

    @Test
    void nonFiniteValuesBecomeZeroAsInC() {
        // C's CheckZero passes "INF" and "NAN" to RoundFString, which finds no non-zero digit and
        // writes the simplest zero (fitschan.c:6222, fitschan.c:26656); EncodeFloat then adds the
        // decimal point.
        assertEquals("                 0.0", FitsFormat.encodeFloat(Double.NaN, 8, 10));
        assertEquals("                 0.0", FitsFormat.encodeFloat(Double.POSITIVE_INFINITY, 8, 10));
    }

    @Test
    void aValueTooWideGivesNull() {
        assertNull(FitsFormat.encodeFloatIn(1.2345678901234567e100, 17, 20, 22, 30));
    }
}
