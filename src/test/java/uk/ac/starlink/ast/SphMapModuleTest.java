package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * The unit tests of Rust {@code src/mappings/sphmap.rs}, and the attribute strings of C's
 * {@code sphmap.c} ({@code GetAttrib}, {@code SetAttrib}, {@code TestAttrib}, {@code
 * ClearAttrib}) and its {@code astMAKE_SET1}/{@code astMAKE_CLEAR1} attributes. Rust's {@code
 * counts_only_the_calls_c_makes} tests its call counters ({@code Rust-only API}).
 */
class SphMapModuleTest {

    private static final double TOL = 1.0e-10;
    private static final double BAD = AstObject.AST__BAD;

    private static void assertClose(double a, double b) {
        assertTrue(Math.abs(a - b) < TOL, "expected " + a + " ~= " + b);
    }

    private static double[] fwd(SphMap m, double... v) {
        return point(transformRows(m, true, v), 0);
    }

    private static double[] inv(SphMap m, double... v) {
        return point(transformRows(m, false, v), 0);
    }

    @Test
    void forwardXAxis() {
        double[] out = fwd(new SphMap(), 1, 0, 0);
        assertClose(out[0], 0.0);
        assertClose(out[1], 0.0);
    }

    @Test
    void forwardYAxis() {
        double[] out = fwd(new SphMap(), 0, 1, 0);
        assertClose(out[0], Math.PI / 2);
        assertClose(out[1], 0.0);
    }

    @Test
    void forwardNorthPole() {
        double[] out = fwd(new SphMap(), 0, 0, 1);
        assertClose(out[0], 0.0);
        assertClose(out[1], Math.PI / 2);
    }

    @Test
    void forwardSouthPole() {
        double[] out = fwd(new SphMap(), 0, 0, -1);
        assertClose(out[0], 0.0);
        assertClose(out[1], -Math.PI / 2);
    }

    @Test
    void forwardPoleCustomPolarLong() {
        SphMap m = new SphMap();
        m.setPolarLong(1.5);
        double[] out = fwd(m, 0, 0, 1);
        assertClose(out[0], 1.5);
        assertClose(out[1], Math.PI / 2);
    }

    @Test
    void inverseOrigin() {
        double[] out = inv(new SphMap(), 0, 0);
        assertClose(out[0], 1.0);
        assertClose(out[1], 0.0);
        assertClose(out[2], 0.0);
    }

    @Test
    void inversePiHalf() {
        double[] out = inv(new SphMap(), Math.PI / 2, 0);
        assertClose(out[0], 0.0);
        assertClose(out[1], 1.0);
        assertClose(out[2], 0.0);
    }

    @Test
    void roundTripForwardInverse() {
        SphMap m = new SphMap();
        double[] sph = fwd(m, 0.6, 0.8, 0.0);
        double[] back = inv(m, sph);
        assertClose(back[0], 0.6);
        assertClose(back[1], 0.8);
        assertClose(back[2], 0.0);
    }

    @Test
    void nonUnitInputNormalized() {
        double[] out = fwd(new SphMap(), 2, 0, 0);
        assertClose(out[0], 0.0);
        assertClose(out[1], 0.0);
    }

    @Test
    void badPropagationForward() {
        double[] out = fwd(new SphMap(), BAD, 1.0, 0.0);
        assertEquals(BAD, out[0]);
        assertEquals(BAD, out[1]);
    }

    @Test
    void badPropagationInverse() {
        double[] out = inv(new SphMap(), 0.5, BAD);
        assertEquals(BAD, out[0]);
        assertEquals(BAD, out[1]);
        assertEquals(BAD, out[2]);
    }

    @Test
    void dimensionsNotInverted() {
        SphMap m = new SphMap();
        assertEquals(3, m.getNin());
        assertEquals(2, m.getNout());
        assertFalse(m.getInvert());
    }

    @Test
    void dimensionsInverted() {
        SphMap m = new SphMap();
        m.invert();
        assertEquals(2, m.getNin());
        assertEquals(3, m.getNout());
        assertTrue(m.getInvert());
    }

    @Test
    void unitRadiusAttribute() {
        SphMap m = new SphMap();
        assertFalse(m.getUnitRadius());
        m.setUnitRadius(true);
        assertTrue(m.getUnitRadius());
    }

    @Test
    void forwardNegativeX() {
        double[] out = fwd(new SphMap(), -1, 0, 0);
        assertClose(out[0], Math.PI);
        assertClose(out[1], 0.0);
    }

    @Test
    void zeroVectorReturnsOrigin() {
        // A zero vector is not at a pole (z is also 0), so it goes to palDcc2s, which gives (0, 0).
        double[] out = fwd(new SphMap(), 0, 0, 0);
        assertClose(out[0], 0.0);
        assertClose(out[1], 0.0);
    }

    /** C's GetAttrib formats UnitRadius with %d and PolarLong with %.15g (sphmap.c). */
    @Test
    void attributeStringsFollowC() {
        SphMap m = new SphMap();
        assertEquals("0", m.getC("UnitRadius"));
        assertEquals("0", m.getC("PolarLong"));
        assertFalse(m.test("PolarLong"));
        m.set("UnitRadius=1, PolarLong=0.25");
        assertEquals("1", m.getC("unitradius"));
        assertEquals("0.25", m.getC(" PolarLong "));
        assertTrue(m.test("UnitRadius"));
        assertTrue(m.test("PolarLong"));
        m.clear("UnitRadius");
        m.clear("PolarLong");
        assertFalse(m.test("UnitRadius"));
        assertFalse(m.test("PolarLong"));
        // A value C's sscanf cannot read goes to the parent class, which rejects the name.
        AstException e = assertThrows(AstException.class, () -> m.set("PolarLong=abc"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
    }

    /** UnitRadius and PolarLong are astMAKE_SET1/CLEAR1 attributes (sphmap.c:1394-1441). */
    @Test
    void aSharedSphMapRefusesChanges() {
        SphMap m = new SphMap();
        new CmpMap(m, new SphMap(), false);
        assertEquals(
                AstException.AST__IMMUT,
                assertThrows(AstException.class, () -> m.setUnitRadius(true)).getStatus());
        assertEquals(
                AstException.AST__IMMUT,
                assertThrows(AstException.class, () -> m.setPolarLong(1.0)).getStatus());
        assertEquals(
                AstException.AST__IMMUT,
                assertThrows(AstException.class, () -> m.clear("PolarLong")).getStatus());
        SphMap copy = (SphMap) m.copy();
        copy.setPolarLong(1.0);
        assertEquals(1.0, copy.getPolarLong());
    }
}
