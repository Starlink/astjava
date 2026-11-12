package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_frame_format.rs}: formatting with the default Format and
 * the Digits attribute, and C's {@code astUnformat} contract for white space, errors and the
 * {@code <bad>} token (frame.c {@code Format} and {@code Unformat}, axis.c {@code AxisFormat} and
 * {@code AxisUnformat}).
 */
class FrameFormatTest {

    @Test
    void formatDefaultIsG7() {
        Frame f = new Frame(1);
        assertEquals("1234.568", f.format(1, 1234.5678));
        assertEquals("1.234568E+08", f.format(1, 123456789.0));
    }

    @Test
    void formatBadIsBadToken() {
        assertEquals("<bad>", new Frame(1).format(1, AstObject.AST__BAD));
    }

    @Test
    void formatHonoursDigits() {
        Frame f = new Frame(1);
        f.setDigits(3);
        assertEquals("1.23E+03", f.format(1, 1234.5678));
    }

    @Test
    void formatExplicitFormatString() {
        Frame f = new Frame(1);
        f.setFormat(1, "%.2f");
        assertEquals("9.88", f.format(1, 9.87654));
    }

    /**
     * A Format without a precision uses printf's default of six digits, not Digits: C's {@code
     * ParseAxisFormat} hands it to {@code sprintf} unchanged.
     */
    @Test
    void formatPrecisionLessFloatDefaultsToSix() {
        Frame f = new Frame(1);
        f.setFormat(1, "%E");
        assertEquals("5.000000E+00", f.format(1, 5.0));
        f.setFormat(1, "%f");
        assertEquals("5.000000", f.format(1, 5.0));
        Frame g = new Frame(1);
        assertEquals("1234.568", g.format(1, 1234.5678));
        assertEquals("1.234568E+08", g.format(1, 123456789.0));
    }

    @Test
    void unformatReadsNumberAndTrailingWs() {
        Frame f = new Frame(1);
        double[] v = new double[1];
        assertEquals(6, f.unformat(1, "12.5  ", v));
        assertEquals(12.5, v[0]);
    }

    @Test
    void unformatFailureIsNreadZeroNoError() {
        assertEquals(0, new Frame(1).unformat(1, "xyz", new double[1]));
    }

    /**
     * C's {@code sscanf(" <bad> ", " < %*1[Bb] %*1[Aa] %*1[Dd] > %n", &nc)} counts the trailing
     * space, and white space is allowed before each token.
     */
    @Test
    void unformatRecognisesBadToken() {
        Frame f = new Frame(1);
        double[] v = new double[1];
        assertEquals(7, f.unformat(1, " <bad> ", v));
        assertEquals(AstObject.AST__BAD, v[0]);
        v[0] = 0.0;
        assertEquals(13, f.unformat(1, "  < b a d >  ", v));
        assertEquals(AstObject.AST__BAD, v[0]);
    }
}
