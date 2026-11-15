package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_cmpframe.rs}, the Rust form of C's {@code
 * ast_tester/testcmpframe.c}: the axes of a CmpFrame are those of its first component followed by
 * those of its second, and the per-axis methods, the geometry and the formatting and reading of
 * values go to the component that holds each axis.
 *
 * <p>{@code cmpframe_snapshot} becomes a test of {@link AstObject#copy}. The four {@code
 * norm_box_*} tests need {@code astNormBox}, which only Regions call, and {@code
 * norm_box_bounds_do_not_depend_on_component_order} needs a Circle; they are deferred.
 */
class CmpFrameTest {

    private static final double BAD = AstObject.AST__BAD;

    // ---- Axis concatenation ----

    @Test
    void cmpframeNaxes() {
        CmpFrame cmp = new CmpFrame(new Frame(2), new Frame(3));
        assertEquals(5, cmp.getNaxes());
        assertEquals(2, cmp.decompose()[0].getNaxes());
    }

    // ---- Format delegation ----

    @Test
    void cmpframeFormatDelegates() {
        CmpFrame cmp = new CmpFrame(new Frame(1), new Frame(1));
        // Both components are plain Frames, so the values are formatted with the default format.
        assertTrue(cmp.format(1, 3.14).contains("3.14"));
        assertTrue(cmp.format(2, 2.72).contains("2.72"));
    }

    // ---- Direction routing ----

    /**
     * C routes Direction for each axis to the component that holds it (cmpframe.c {@code
     * MAKE_GET(Direction)}); an axis out of range is an error, as C's {@code astValidateAxis} makes
     * it.
     */
    @Test
    void cmpframeDirectionRoutesToComponent() {
        Frame f1 = new Frame(2);
        f1.setDirection(1, false);
        CmpFrame cmp = new CmpFrame(f1, new Frame(1));
        assertFalse(cmp.getDirection(1));
        assertTrue(cmp.getDirection(2));
        assertTrue(cmp.getDirection(3));
        for (int axis : new int[] {0, 4}) {
            AstException e = assertThrows(AstException.class, () -> cmp.getDirection(axis));
            assertEquals(AstException.AST__AXIIN, e.getStatus(), "axis " + axis);
        }
    }

    // ---- Unformat delegation ----

    /**
     * Axis 3 of a 2+1 CmpFrame is axis 1 of its second component. C's {@code sscanf("%lf %n")}
     * counts the white space after the number, so "42.5 rest" uses 5 characters.
     */
    @Test
    void cmpframeUnformatDelegates() {
        CmpFrame cmp = new CmpFrame(new Frame(2), new Frame(1));
        double[] value = {BAD};
        int consumed = cmp.unformat(3, "42.5 rest", value);
        assertTrue(Math.abs(value[0] - 42.5) < 1e-12, "value " + value[0]);
        assertEquals(5, consumed);
    }

    // ---- Axis validation ----

    @Test
    void cmpframeAxisValidation() {
        CmpFrame cmp = new CmpFrame(new Frame(2), new Frame(1));
        assertEquals(3, cmp.getNaxes());
        for (int axis : new int[] {0, 4}) {
            AstException e = assertThrows(AstException.class, () -> cmp.format(axis, 1.0));
            assertEquals(AstException.AST__AXIIN, e.getStatus(), "axis " + axis);
        }
    }

    // ---- Distance ----

    @Test
    void cmpframeDistance() {
        CmpFrame cmp = new CmpFrame(new Frame(2), new Frame(1));
        // (0,0,0) to (1,2,2): sqrt(1+4+4) = 3.
        double d = cmp.distance(new double[] {0.0, 0.0, 0.0}, new double[] {1.0, 2.0, 2.0});
        assertTrue(Math.abs(d - 3.0) < 1e-12, "distance " + d);
    }

    @Test
    void cmpframeDistanceBadValue() {
        CmpFrame cmp = new CmpFrame(new Frame(1), new Frame(1));
        assertEquals(BAD, cmp.distance(new double[] {BAD, 0.0}, new double[] {1.0, 1.0}));
    }

    // ---- Offset ----

    @Test
    void cmpframeOffset() {
        CmpFrame cmp = new CmpFrame(new Frame(1), new Frame(1));
        // From (0,0) towards (3,4) by 5 is (3,4).
        double[] result = cmp.offset(new double[] {0.0, 0.0}, new double[] {3.0, 4.0}, 5.0);
        assertTrue(Math.abs(result[0] - 3.0) < 1e-12, "axis 1 " + result[0]);
        assertTrue(Math.abs(result[1] - 4.0) < 1e-12, "axis 2 " + result[1]);
    }

    // ---- Norm delegation ----

    @Test
    void cmpframeNormDelegates() {
        CmpFrame cmp = new CmpFrame(new Frame(2), new Frame(1));
        double[] coords = {1.0, 2.0, 3.0};
        cmp.norm(coords);
        // A plain Frame leaves its values as they are.
        assertArrayEquals(new double[] {1.0, 2.0, 3.0}, coords);
    }

    // ---- Copy ----

    @Test
    void cmpframeSnapshot() {
        CmpFrame cmp = new CmpFrame(new Frame(2), new Frame(3));
        AstObject copy = cmp.copy();
        assertEquals(5, assertInstanceOf(CmpFrame.class, copy).getNaxes());
    }

    // ---- Axis-permutation native round trip ----

    /**
     * A permuted CmpFrame writes its {@code Axp<n>} items and reads them back, keeping the external
     * axis order (cmpframe.c {@code Dump} and {@code astLoadCmpFrame}). Distinct labels show each
     * external axis still goes to the right component axis after the round trip.
     */
    @Test
    void cmpframeAxisPermutationRoundTrips() {
        Frame f1 = new Frame(2);
        f1.setLabel(1, "lon");
        f1.setLabel(2, "lat");
        Frame f2 = new Frame(1);
        f2.setLabel(1, "wave");
        CmpFrame cmp = new CmpFrame(f1, f2);

        // External axes (wave, lon, lat): external axis 1 is internal axis 3, 2 is 1, 3 is 2.
        cmp.permAxes(new int[] {3, 1, 2});
        String[] before = labels(cmp);
        assertArrayEquals(new String[] {"wave", "lon", "lat"}, before);

        String text = ChannelText.dump(cmp);
        assertTrue(text.contains("Axp1"), "a permuted CmpFrame writes Axp items:\n" + text);

        CmpFrame back = assertInstanceOf(CmpFrame.class, ChannelText.read(text));
        assertArrayEquals(before, labels(back), "the axis permutation survives the round trip");
    }

    private static String[] labels(Frame f) {
        String[] out = new String[f.getNaxes()];
        for (int axis = 1; axis <= out.length; axis++) {
            out[axis - 1] = f.getLabel(axis);
        }
        return out;
    }

    // ---- Angle ----

    /**
     * A CmpFrame whose components are all plain Frames uses the Frame angle, which is signed in two
     * dimensions (frame.c {@code Angle}): from A on +X to C on +Y is -pi/2.
     */
    @Test
    void cmpframeAngle() {
        CmpFrame cmp = new CmpFrame(new Frame(1), new Frame(1));
        double angle = cmp.angle(new double[] {1.0, 0.0}, new double[] {0.0, 0.0}, new double[] {0.0, 1.0});
        assertTrue(Math.abs(angle + Math.PI / 2.0) < 1e-12, "angle " + angle);
    }

    // ---- NormPoints ----

    /**
     * testcmpframe.c Errors 1-9: NormPoints on a CmpFrame whose axes are permuted (3, 1, 2),
     * holding a one-axis Frame and a SkyFrame whose own axes are swapped, so the CmpFrame's axes
     * are (RA, PX, Dec). With {@code contig} the longitudes are brought into one range (the two
     * near 2pi go below zero); without it every value is returned as given.
     */
    @Test
    void normPointsOnAPermutedCmpframeHoldingASwappedSkyframe() {
        SkyFrame sky = new SkyFrame();
        sky.permAxes(new int[] {2, 1});
        Frame plane = new Frame(1);
        plane.setDomain("FPLANE");
        CmpFrame cmp = new CmpFrame(plane, sky);
        cmp.permAxes(new int[] {3, 1, 2});

        double[] ra = {6.1, 6.1, 0.04, 0.04};
        double[] dec = {0.2, -0.2, -0.2, 0.2};
        double[] px = {-100.0, -10.0, 10.0, 100.0};
        double[][] pts = {ra.clone(), px.clone(), dec.clone()};

        double[][] normed = cmp.normPoints(pts, 4, true);
        for (int i = 0; i < 4; i++) {
            double wantRa = i < 2 ? ra[i] - 2.0 * Math.PI : ra[i];
            assertEquals(wantRa, normed[0][i], "Errors 1-4, point " + i);
            assertEquals(px[i], normed[1][i], "Error 5, point " + i);
            assertEquals(dec[i], normed[2][i], "Error 6, point " + i);
        }

        double[][] passed = cmp.normPoints(pts, 4, false);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(
                    new double[] {ra[i], px[i], dec[i]},
                    new double[] {passed[0][i], passed[1][i], passed[2][i]},
                    "Errors 7-9, point " + i);
        }
    }
}
