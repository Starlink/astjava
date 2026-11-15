package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests of C {@code printf} emulation. The values are those C's {@code printf} gives, as the C
 * standard (C99 7.19.6.1) defines them; the tests of {@code AxisFormat} in Rust
 * {@code frames/axis_format.rs} are translated with Axis.
 */
class CFormatTest {

    @Test
    void defaultAxisFormatMatchesC() {
        assertEquals("0", CFormat.format("%1.7G", 0.0));
        assertEquals("1234.568", CFormat.format("%1.7G", 1234.5678));
        assertEquals("0.0001234568", CFormat.format("%1.7G", 0.00012345678));
        assertEquals("1.234568E+08", CFormat.format("%1.7G", 123456789.0));
        assertEquals("9999999", CFormat.format("%1.7G", 9.999999e6));
        assertEquals("1E-05", CFormat.format("%1.7G", 1e-5));
    }

    @Test
    void undefinedFormatsAreErrors() {
        for (String f : new String[] {"%g %g", "%*g", "%.*g %d", "%ld", "%s", "%Lg", "%a", "%", "%'g"}) {
            AstException e = assertThrows(AstException.class, () -> CFormat.format(f, 1.5), f);
            assertEquals(AstException.AST__FMTER, e.getStatus(), f);
        }
        assertThrows(AstException.class, () -> CFormat.format("%f", 1));
        assertThrows(AstException.class, () -> CFormat.format("%ld", 1));
        assertThrows(AstException.class, () -> CFormat.format("%05c", 65));
    }

    @Test
    void roundingUsesTheExactBinaryValue() {
        // 0.125 is exact, so the tie rounds to even; 0.1 is a little above 1/10.
        assertEquals("0.12", CFormat.format("%.2f", 0.125));
        assertEquals("0.38", CFormat.format("%.2f", 0.375));
        assertEquals("0.10000000000000000555", CFormat.format("%.20f", 0.1));
        assertEquals("0.10000000000000001", CFormat.format("%.17g", 0.1));
        assertEquals("2.5000000000000000e-01", CFormat.format("%.16e", 0.25));
        assertEquals("1e+100", CFormat.format("%g", 1e100));
        assertEquals("1.7976931348623157e+308", CFormat.format("%.17g", Double.MAX_VALUE));
        assertEquals("4.9406564584124654e-324", CFormat.format("%.17g", Double.MIN_VALUE));
    }

    @Test
    void floatingConversions() {
        assertEquals("0.000000e+00", CFormat.format("%e", 0.0));
        assertEquals("-0", CFormat.format("%g", -0.0));
        assertEquals("1.000000", CFormat.format("%f", 1.0));
        assertEquals("100000", CFormat.format("%g", 100000.0));
        assertEquals("1e+06", CFormat.format("%g", 1000000.0));
        assertEquals("0.0001", CFormat.format("%g", 0.0001));
        assertEquals("1e-05", CFormat.format("%g", 0.00001));
        assertEquals("1.00000", CFormat.format("%#g", 1.0));
        assertEquals("1.e+00", CFormat.format("%#.0e", 1.0));
        assertEquals("2.", CFormat.format("%#.0f", 2.0));
        assertEquals("2", CFormat.format("%.0f", 2.5));
        assertEquals("4", CFormat.format("%.0f", 3.5));
        assertEquals("-001.500", CFormat.format("%+08.3f", -1.5));
        assertEquals("+1.5", CFormat.format("%+g", 1.5));
        assertEquals(" 1.5", CFormat.format("% g", 1.5));
        assertEquals("1.5   |", CFormat.format("%-6g|", 1.5));
        assertEquals("   1.5", CFormat.format("%6g", 1.5));
        assertEquals("1E+10", CFormat.format("%G", 1e10));
        assertEquals("1", CFormat.format("%.0g", 1.0));
        assertEquals("1e+01", CFormat.format("%.0g", 9.5));
        assertEquals("10", CFormat.format("%.2g", 9.99));
        assertEquals("ab 1.5 %", CFormat.format("ab %g %%", 1.5));
        assertEquals("1.5", CFormat.format("%lg", 1.5));
    }

    @Test
    void nonFiniteValues() {
        assertEquals("nan", CFormat.format("%g", Double.NaN));
        assertEquals("NAN", CFormat.format("%G", Double.NaN));
        assertEquals("-inf", CFormat.format("%f", Double.NEGATIVE_INFINITY));
        assertEquals("  INF", CFormat.format("%05E", Double.POSITIVE_INFINITY));
    }

    @Test
    void integerConversions() {
        assertEquals("42", CFormat.format("%d", 42));
        assertEquals("-42", CFormat.format("%i", -42));
        assertEquals("007", CFormat.format("%.3d", 7));
        assertEquals("", CFormat.format("%.0d", 0));
        assertEquals("   -7", CFormat.format("%5d", -7));
        assertEquals("-0007", CFormat.format("%05d", -7));
        assertEquals("   07", CFormat.format("%05.2d", 7));
        assertEquals("7    |", CFormat.format("%-5d|", 7));
        assertEquals("4294967295", CFormat.format("%u", -1));
        assertEquals("ff", CFormat.format("%x", 255));
        assertEquals("0XFF", CFormat.format("%#X", 255));
        assertEquals("0", CFormat.format("%#x", 0));
        assertEquals("017", CFormat.format("%#o", 15));
        assertEquals("-2147483648", CFormat.format("%d", Integer.MIN_VALUE));
        assertEquals("  A", CFormat.format("%3c", 65));
    }
}
