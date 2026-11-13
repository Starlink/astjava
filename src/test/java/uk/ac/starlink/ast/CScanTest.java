package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** C's strtod and sscanf conversions, with the values the C standard defines. */
class CScanTest {

    private static double strtod(String s) {
        return CScan.strtod(s, 0).value;
    }

    private static int strtodEnd(String s) {
        return CScan.strtod(s, 0).end;
    }

    @Test
    void decimalConstants() {
        assertEquals(1.5, strtod("1.5"), 0.0);
        assertEquals(-0.5, strtod("-.5"), 0.0);
        assertEquals(2.0, strtod("+2."), 0.0);
        assertEquals(1e10, strtod("1e10"), 0.0);
        assertEquals(1.0, strtod("1e+x"), 0.0);
        assertEquals(1, strtodEnd("1e+x"));
        assertEquals(3, strtodEnd("1.5abc"));
        assertNull(CScan.strtod(".", 0));
        assertNull(CScan.strtod("e5", 0));
        assertNull(CScan.strtod(" 1", 0));
    }

    @Test
    void specialValues() {
        assertEquals(Double.POSITIVE_INFINITY, strtod("inf"), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, strtod("-Infinity"), 0.0);
        assertEquals(8, strtodEnd("infinity"));
        assertEquals(3, strtodEnd("infinx"));
        assertTrue(Double.isNaN(strtod("NaN")));
        assertEquals(8, strtodEnd("nan(abc)x"));
        assertEquals(3, strtodEnd("nan(ab"));
    }

    @Test
    void hexadecimalConstants() {
        assertEquals(255.0, strtod("0xff"), 0.0);
        assertEquals(1.5, strtod("0x1.8"), 0.0);
        assertEquals(12.0, strtod("0x1.8p3"), 0.0);
        assertEquals(0.75, strtod("0X3p-2"), 0.0);
        // "0x" with no hexadecimal digit is the number 0.
        assertEquals(0.0, strtod("0x"), 0.0);
        assertEquals(1, strtodEnd("0xg"));
        // A binary exponent marker with no digits is not part of the number.
        assertEquals(3, strtodEnd("0x1p"));
    }

    @Test
    void scanningAWholeValue() {
        assertEquals(1.5, CScan.scanDouble("  1.5  "), 0.0);
        assertNull(CScan.scanDouble("1.5x"));
        assertNull(CScan.scanDouble(""));
        assertEquals(-12, (int) CScan.scanInt(" -12 "));
        assertNull(CScan.scanInt("1.5"));
        assertNull(CScan.scanInt("0x10"));
        assertNull(CScan.scanInt("- 1"));
        assertEquals(123456789012L, (long) CScan.scanLong("123456789012"));
        assertEquals(0.1f, CScan.scanFloat("0.1"), 0.0f);
    }
}
