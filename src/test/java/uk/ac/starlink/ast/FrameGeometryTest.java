package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Translation of Rust {@code tests/test_frame_geometry.rs}: the Cartesian geometry of a plain
 * Frame (frame.c {@code Offset2}, {@code Intersect}, {@code AxAngle}, {@code AxDistance}, {@code
 * AxOffset}, {@code Resolve} and {@code ResolvePoints}).
 *
 * <p>A point with fewer coordinates than the Frame has axes, which Rust rejects with a panic
 * naming the shortfall, raises {@code AST__DIMIN} here with the same words, for a CmpFrame as for
 * the other classes. {@code match_axes_finds_sky_in_cmp} needs a SpecFrame and is deferred.
 */
class FrameGeometryTest {

    private static final double BAD = AstObject.AST__BAD;

    private static double[] p(double... v) {
        return v;
    }

    private static void assertShortPoint(String expected, Executable call) {
        AstException e = assertThrows(AstException.class, call);
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    void offset2Cartesian() {
        Frame f = new Frame(2);
        // Angle 0 is the +axis 2 direction, so an offset of 5 moves the point by 5 on axis 2.
        double[] p2 = new double[2];
        double ret = f.offset2(p(1.0, 2.0), 0.0, 5.0, p2);
        assertTrue(Math.abs(p2[0] - 1.0) < 1e-12);
        assertTrue(Math.abs(p2[1] - 7.0) < 1e-12);
        assertTrue(Math.abs(ret) < 1e-12);
    }

    @Test
    void offset2BadInputs() {
        Frame f = new Frame(2);
        double[] p2 = new double[2];
        double ret = f.offset2(p(BAD, 2.0), 0.0, 5.0, p2);
        assertEquals(BAD, p2[0]);
        assertEquals(BAD, p2[1]);
        assertEquals(BAD, ret);
    }

    @Test
    void intersectCrossingLines() {
        Frame f = new Frame(2);
        double[] cross = f.intersect(p(0.0, 0.0), p(1.0, 1.0), p(0.0, 2.0), p(2.0, 0.0));
        assertTrue(Math.abs(cross[0] - 1.0) < 1e-12);
        assertTrue(Math.abs(cross[1] - 1.0) < 1e-12);
    }

    @Test
    void intersectParallelIsBad() {
        Frame f = new Frame(2);
        double[] cross = f.intersect(p(0.0, 0.0), p(1.0, 1.0), p(0.0, 1.0), p(1.0, 2.0));
        assertEquals(BAD, cross[0]);
        assertEquals(BAD, cross[1]);
    }

    @Test
    void axDistanceAndOffset() {
        Frame f = new Frame(2);
        assertTrue(Math.abs(f.axDistance(1, 3.0, 8.0) - 5.0) < 1e-12);
        assertEquals(BAD, f.axDistance(1, BAD, 8.0));
        assertTrue(Math.abs(f.axOffset(1, 3.0, 5.0) - 8.0) < 1e-12);
        assertEquals(BAD, f.axOffset(1, BAD, 5.0));
    }

    /**
     * The values C gives (astAxAngle at commit da9ddb39, which scales the nudge by the axis's own
     * coordinate): 0 for a line along +axis 1, pi/2 for axis 2, pi/4 for the third case.
     */
    @Test
    void axAngleCartesian() {
        Frame f = new Frame(2);
        double ang1 = f.axAngle(p(0.0, 0.0), p(1.0, 0.0), 1);
        assertNotEquals(BAD, ang1, "C returns a valid angle, not BAD");
        assertTrue(Math.abs(ang1) < 1e-6, "expected 0 rad, got " + ang1);
        double ang2 = f.axAngle(p(0.0, 0.0), p(1.0, 0.0), 2);
        assertTrue(Math.abs(ang2 - Math.PI / 2.0) < 1e-6);
        double ang3 = f.axAngle(p(0.0, 5.0), p(3.0, 2.0), 1);
        assertTrue(Math.abs(ang3 - Math.PI / 4.0) < 1e-6);
    }

    @Test
    void axNormIsNoopForCartesian() {
        Frame f = new Frame(2);
        double[] vals = {1.0, 2.0, 3.0};
        f.axNorm(1, 0, vals);
        assertArrayEquals(new double[] {1.0, 2.0, 3.0}, vals);
    }

    @Test
    void resolveParallelPerp() {
        Frame f = new Frame(2);
        // Basis (0,0)->(2,0); point3 = (1,3): parallel 1, perpendicular 3.
        double[] p4 = new double[2];
        double[] d = f.resolve(p(0.0, 0.0), p(2.0, 0.0), p(1.0, 3.0), p4);
        assertTrue(Math.abs(d[0] - 1.0) < 1e-12);
        assertTrue(Math.abs(d[1] - 3.0) < 1e-12);
        assertTrue(Math.abs(p4[0] - 1.0) < 1e-12);
        assertTrue(Math.abs(p4[1]) < 1e-12);
    }

    @Test
    void resolveZeroBasisIsBad() {
        Frame f = new Frame(2);
        double[] d = f.resolve(p(0.0, 0.0), p(0.0, 0.0), p(1.0, 3.0), new double[2]);
        assertEquals(BAD, d[0]);
        assertEquals(BAD, d[1]);
    }

    /** C's sign convention, {@code x1*y2 - x2*y1 < 0} negates (frame.c ResolvePoints). */
    @Test
    void resolvePointsSigned2d() {
        Frame f = new Frame(2);
        double[][] in = {{1.0, 1.0}, {2.0, -2.0}};
        double[][] out = f.resolvePoints(p(0.0, 0.0), p(1.0, 0.0), in, 2);
        assertTrue(Math.abs(out[0][0] - 1.0) < 1e-12);
        assertTrue(Math.abs(out[1][0] - 2.0) < 1e-12);
        assertTrue(Math.abs(out[1][1] + 2.0) < 1e-12);
    }

    @Test
    void isUnitFrameBaseTrue() {
        assertTrue(new Frame(2).isUnitFrame());
    }

    @Test
    void distancePanicsOnAShortPoint() {
        assertShortPoint("has 3 axes", () -> new Frame(3).distance(p(0.0, 0.0), p(3.0, 4.0)));
    }

    @Test
    void anglePanicsOnAShortPoint() {
        assertShortPoint("has 3 axes", () -> new Frame(3).angle(p(0.0, 1.0), p(0.0, 0.0), p(1.0, 0.0)));
    }

    @Test
    void offsetPanicsOnAShortPoint() {
        assertShortPoint("has 3 axes", () -> new Frame(3).offset(p(0.0, 0.0), p(3.0, 4.0), 1.0));
    }

    @Test
    void normPanicsOnAShortPoint() {
        assertShortPoint("has 3 axes", () -> new Frame(3).norm(p(0.0, 0.0)));
    }

    @Test
    void resolvePanicsOnAShortPoint() {
        assertShortPoint(
                "has 3 axes", () -> new Frame(3).resolve(p(0.0, 0.0), p(1.0, 0.0), p(3.0, 4.0), new double[3]));
    }

    @Test
    void axAnglePanicsOnAShortPoint() {
        assertShortPoint("has 3 axes", () -> new Frame(3).axAngle(p(0.0, 0.0), p(1.0, 0.0), 1));
    }

    @Test
    void offset2PanicsOnAShortPoint() {
        assertShortPoint("has 2 axes", () -> new Frame(2).offset2(p(1.0), 0.0, 1.0, new double[2]));
    }

    @Test
    void intersectPanicsOnAShortPoint() {
        assertShortPoint("has 2 axes", () -> new Frame(2).intersect(p(0.0, 0.0), p(2.0), p(0.0, 2.0), p(2.0, 0.0)));
    }

    @Test
    void resolvePointsPanicsOnAShortPoint() {
        assertShortPoint(
                "has 2 axes", () -> new Frame(2).resolvePoints(p(0.0), p(1.0, 0.0), new double[][] {{1.0}, {1.0}}, 1));
    }

    @Test
    void skyNormPanicsOnAShortPoint() {
        assertShortPoint("has 2 axes", () -> new SkyFrame().norm(p(0.0)));
    }

    @Test
    void skyDistancePanicsOnAShortPoint() {
        assertShortPoint("has 2 axes", () -> new SkyFrame().distance(p(0.0), p(0.0)));
    }

    /** A CmpFrame of a one-axis and a two-axis plain Frame. */
    private static CmpFrame cmpPlain() {
        return new CmpFrame(new Frame(1), new Frame(2));
    }

    /** Every axis is a plain axis, so this takes the Cartesian path. */
    @Test
    void cmpAnglePanicsOnAShortPoint() {
        assertShortPoint("has 3 axes", () -> cmpPlain().angle(p(0.0, 1.0), p(0.0, 0.0), p(1.0, 0.0)));
    }

    @Test
    void cmpDistancePanicsOnAShortPoint() {
        assertShortPoint("has 3 axes", () -> cmpPlain().distance(p(0.0, 0.0), p(3.0, 4.0)));
    }
}
