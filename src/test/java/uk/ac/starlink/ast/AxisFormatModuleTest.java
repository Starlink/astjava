package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The unit tests of Rust {@code src/frames/axis_format.rs}: axis.c's {@code ParseAxisFormat} and
 * {@code AxisFormat}. The printf layer itself is tested by {@link CFormatTest}.
 */
class AxisFormatModuleTest {

    private static String fmt(String f, int digits, double v) {
        return AxisFormat.formatValue("Axis", f, digits, v);
    }

    /** Values C's {@code printf("%1.7G", x)} gives. */
    @Test
    void defaultFormatMatchesC() {
        assertEquals("0", fmt("%1.7G", 7, 0.0));
        assertEquals("1234.568", fmt("%1.7G", 7, 1234.5678));
        assertEquals("0.0001234568", fmt("%1.7G", 7, 0.00012345678));
        assertEquals("1.234568E+08", fmt("%1.7G", 7, 123456789.0));
        assertEquals("9999999", fmt("%1.7G", 7, 9.999999e6));
        assertEquals("1E-05", fmt("%1.7G", 7, 1e-5));
    }

    @Test
    void parseRewritesAsCDoes() {
        AxisFormat.Parsed p = AxisFormat.parseAxisFormat("%.*G", 5);
        assertEquals("%.5G", p.fmt);
        assertTrue(!p.integ && !p.log);

        p = AxisFormat.parseAxisFormat("%+-&#8.2f  ", 7);
        assertEquals("%-#8.2f", p.fmt);
        assertTrue(p.log && p.sign && !p.integ);

        // Without '&' the Format is unchanged apart from trailing space.
        p = AxisFormat.parseAxisFormat("%+ d ", 7);
        assertEquals("%+ d", p.fmt);
        assertTrue(p.integ);
        assertFalse(p.log);
    }

    @Test
    void undefinedFormatsAreErrors() {
        for (String f : new String[] {"%g %g", "%*g", "%.*g %d", "%ld", "%s", "%Lg", "%a", "%"}) {
            AstException e = assertThrows(AstException.class, () -> fmt(f, 7, 1.5), f);
            assertEquals(AstException.AST__FMTER, e.getStatus(), f);
        }
    }

    /** The result is a C string, so it ends at the first zero byte, which {@code %c} writes for zero. */
    @Test
    void zeroByteEndsTheString() {
        assertEquals("ab", fmt("ab%cde", 7, 0.0));
        assertEquals("  ", fmt("%3c|", 7, 0.0));
    }
}
