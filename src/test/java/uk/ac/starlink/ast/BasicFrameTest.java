package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_basicframe.rs}, the Rust form of C's {@code
 * ast_tester/testframe.c}: construction, per-axis attributes, Cartesian geometry, and formatting
 * and reading axis values.
 *
 * <p>{@code builder_zero_axes_rejected} asserts C's rule, under which only a negative number of
 * axes is an error (frame.c {@code astInitFrame}); Rust also rejects zero. {@code
 * snapshot_is_independent_clone} becomes a test of {@link AstObject#copy}. {@code
 * map_from_returns_unit} tests a Rust-only method and is deferred.
 */
class BasicFrameTest {

    private static final double BAD = AstObject.AST__BAD;

    // ---- Construction ----

    @Test
    void builderBasic() {
        Frame frame = new Frame(3);
        frame.setTitle("Test Frame");
        frame.setDomain("TEST");
        assertEquals(3, frame.getNaxes());
        assertEquals("Test Frame", frame.getTitle());
        assertEquals("TEST", frame.getDomain());
    }

    /** C rejects a negative number of axes and accepts none (frame.c:14981). */
    @Test
    void builderZeroAxesRejected() {
        AstException e = assertThrows(AstException.class, () -> new Frame(-1));
        assertEquals(AstException.AST__NAXIN, e.getStatus());
        assertEquals(0, new Frame(0).getNaxes());
    }

    @Test
    void builderWithLabels() {
        Frame frame = new Frame(2);
        frame.setLabel(1, "X");
        frame.setLabel(2, "Y");
        frame.setUnit(1, "m");
        frame.setUnit(2, "m");
        assertEquals("X", frame.getLabel(1));
        assertEquals("Y", frame.getLabel(2));
        assertEquals("m", frame.getUnit(1));
        assertEquals("m", frame.getUnit(2));
    }

    // ---- Attribute defaults ----

    @Test
    void defaultAttributes() {
        Frame frame = new Frame(2);
        // The defaults C's Frame reports (frame.c GetDefaultTitle, GetDefaultLabel,
        // GetDefaultSymbol; axis.c GetDefaultFormat).
        assertEquals("2-d coordinate system", frame.getTitle());
        assertEquals("", frame.getDomain());
        assertEquals(2000.0, frame.getEpoch());
        assertFalse(frame.testEpoch());
        assertEquals("Axis 1", frame.getLabel(1));
        assertEquals("", frame.getUnit(1));
        assertEquals("x1", frame.getSymbol(1));
        assertEquals("%1.7G", frame.getFormat(1));
    }

    // ---- Set / test / clear cycle ----

    @Test
    void setClearAttributes() {
        Frame frame = new Frame(2);
        assertFalse(frame.testLabel(1));
        assertEquals("Axis 1", frame.getLabel(1));

        frame.setLabel(1, "RA");
        assertTrue(frame.testLabel(1));
        assertEquals("RA", frame.getLabel(1));

        frame.clearLabel(1);
        assertFalse(frame.testLabel(1));
        assertEquals("Axis 1", frame.getLabel(1));
    }

    // ---- Axis validation ----

    @Test
    void axisValidation() {
        Frame frame = new Frame(2);
        AstException e = assertThrows(AstException.class, () -> frame.getLabel(0));
        assertEquals(AstException.AST__AXIIN, e.getStatus());
        e = assertThrows(AstException.class, () -> frame.getLabel(3));
        assertEquals(AstException.AST__AXIIN, e.getStatus());
    }

    // ---- Epoch attribute ----

    @Test
    void epochAttribute() {
        Frame frame = new Frame(1);
        assertFalse(frame.testEpoch());

        frame.setEpoch(1950.0);
        assertTrue(frame.testEpoch());
        assertEquals(1950.0, frame.getEpoch());
        // B1950.0 is MJD 33281.92345905 (palEpb2d); a year before 1984 is Besselian.
        assertTrue(Math.abs(frame.getEpochMjd() - 33281.92345905) < 1e-8);

        frame.clearEpoch();
        assertFalse(frame.testEpoch());
        assertEquals(2000.0, frame.getEpoch());
    }

    // ---- Euclidean distance ----

    @Test
    void distance2d() {
        Frame frame = new Frame(2);
        double d = frame.distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0});
        assertTrue(Math.abs(d - 5.0) < 1e-12);
    }

    @Test
    void distance3d() {
        Frame frame = new Frame(3);
        double d = frame.distance(new double[] {1.0, 2.0, 3.0}, new double[] {4.0, 6.0, 3.0});
        assertTrue(Math.abs(d - 5.0) < 1e-12);
    }

    @Test
    void distanceBadValue() {
        Frame frame = new Frame(2);
        assertEquals(BAD, frame.distance(new double[] {BAD, 0.0}, new double[] {3.0, 4.0}));
    }

    // ---- Angle ----

    /**
     * In two dimensions the angle is signed (frame.c:1302-1311): the positive sense rotates from
     * +Y towards +X, so going from A on the +X axis to C on the +Y axis is -pi/2.
     */
    @Test
    void angleRightAngle() {
        Frame frame = new Frame(2);
        double angle = frame.angle(new double[] {1.0, 0.0}, new double[] {0.0, 0.0}, new double[] {0.0, 1.0});
        assertTrue(Math.abs(angle + Math.PI / 2.0) < 1e-12, "got " + angle);
    }

    @Test
    void angleStraightLine() {
        Frame frame = new Frame(2);
        double angle = frame.angle(new double[] {-1.0, 0.0}, new double[] {0.0, 0.0}, new double[] {1.0, 0.0});
        assertTrue(Math.abs(angle - Math.PI) < 1e-12);
    }

    // ---- Offset ----

    @Test
    void offset2d() {
        Frame frame = new Frame(2);
        double[] result = frame.offset(new double[] {0.0, 0.0}, new double[] {3.0, 4.0}, 5.0);
        assertTrue(Math.abs(result[0] - 3.0) < 1e-12);
        assertTrue(Math.abs(result[1] - 4.0) < 1e-12);
    }

    @Test
    void offsetHalfDistance() {
        Frame frame = new Frame(2);
        double[] result = frame.offset(new double[] {0.0, 0.0}, new double[] {4.0, 0.0}, 2.0);
        assertTrue(Math.abs(result[0] - 2.0) < 1e-12);
        assertTrue(Math.abs(result[1]) < 1e-12);
    }

    // ---- Norm ----

    @Test
    void normIsNoop() {
        Frame frame = new Frame(2);
        double[] coords = {3.25, 2.75};
        frame.norm(coords);
        assertEquals(3.25, coords[0]);
        assertEquals(2.75, coords[1]);
    }

    // ---- Format ----

    @Test
    void formatDefault() {
        Frame frame = new Frame(1);
        assertTrue(frame.format(1, 3.14159).contains("3.14159"));
    }

    @Test
    void formatBadValue() {
        Frame frame = new Frame(1);
        assertEquals("<bad>", frame.format(1, BAD));
    }

    @Test
    void formatWithPrecision() {
        Frame frame = new Frame(1);
        frame.setFormat(1, "%.3f");
        assertEquals("3.142", frame.format(1, 3.14159));
    }

    /**
     * A Format with no '%' has nothing for sprintf to substitute, so C returns the literal string
     * and ignores the value (axis.c:794).
     */
    @Test
    void formatWithoutPercentReturnsLiteral() {
        Frame frame = new Frame(1);
        frame.setFormat(1, "3");
        assertEquals("3", frame.format(1, 3.14159));
    }

    @Test
    void formatUnsetUsesDefaultG7() {
        Frame frame = new Frame(1);
        assertEquals("3.14159", frame.format(1, 3.14159));
    }

    // ---- Unformat ----

    /** C's {@code sscanf("%lf %n")} counts the white space after the number (axis.c:1365). */
    @Test
    void unformatBasic() {
        Frame frame = new Frame(1);
        double[] value = new double[1];
        int consumed = frame.unformat(1, "3.14 rest", value);
        assertTrue(Math.abs(value[0] - 3.14) < 1e-12);
        assertEquals(5, consumed);
    }

    @Test
    void unformatWithLeadingWhitespace() {
        Frame frame = new Frame(1);
        double[] value = new double[1];
        int consumed = frame.unformat(1, "  42.0", value);
        assertTrue(Math.abs(value[0] - 42.0) < 1e-12);
        assertEquals(6, consumed);
    }

    /** C reads nothing, without error, from text that is not a value (axis.c:1378). */
    @Test
    void unformatInvalid() {
        Frame frame = new Frame(1);
        assertEquals(0, frame.unformat(1, "abc", new double[1]));
    }

    // ---- Copy ----

    @Test
    void snapshotIsIndependentClone() {
        Frame frame = new Frame(3);
        frame.setTitle("Original");
        Frame copy = (Frame) frame.copy();
        assertEquals(3, copy.getNaxes());
        copy.setTitle("Changed");
        assertEquals("Original", frame.getTitle());
    }

    // ---- Bottom / Top defaults ----

    @Test
    void bottomTopDefaults() {
        Frame frame = new Frame(2);
        assertEquals(-Double.MAX_VALUE, frame.getBottom(1));
        assertEquals(Double.MAX_VALUE, frame.getTop(1));
        assertEquals(-Double.MAX_VALUE, frame.getBottom(2));
        assertEquals(Double.MAX_VALUE, frame.getTop(2));
    }

    @Test
    void setIdentRoundTrip() {
        Frame f = new Frame(2);
        assertEquals("", f.getIdent());
        f.setIdent("A");
        assertEquals("A", f.getIdent());
        f.setIdent("");
        assertEquals("", f.getIdent());
    }
}
