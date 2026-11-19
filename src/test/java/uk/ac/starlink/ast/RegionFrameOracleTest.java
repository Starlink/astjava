package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_frame_oracle.rs}: the Frame methods a Region
 * forwards to the current Frame of its FrameSet (region.c {@code Offset2}, {@code Resolve}, {@code
 * AxDistance}, {@code AxOffset}, {@code SystemString}, {@code PickAxes}, and {@code SubFrame}
 * through {@code astConvert}), for a Circle in an ICRS SkyFrame, where a Cartesian answer differs
 * from the spherical one; and the defining points of a mapped Box ({@code oracle/region_frame.json},
 * from {@code tools/oracle_fixtures/region_frame_probe.c}).
 *
 * <p>The spherical values pass through {@code sin}, {@code cos}, {@code atan2} and {@code acos},
 * each within 1 ulp of the true value in {@code java.lang.Math} and in the C library, so the two
 * may differ by 2 ulp in each, about {@code 4.4e-16} relative; the offset and resolution chain at
 * most four of them through unit vectors and their cross and dot products, whose conditioning at
 * these points (well away from the poles, angles of order 1 radian) amplifies by less than 10.
 * That bounds the difference by about {@code 4 * 10 * 4.4e-16 * 2.4 = 4e-14}, values being at
 * most 2.4, so {@link #SKY_TOL} is {@code 1e-12}. The axis distance and offset normalise by a
 * whole turn and are compared exactly.
 */
class RegionFrameOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_frame");

    /** The tolerance for spherical values (see the class description). */
    private static final double SKY_TOL = 1e-12;

    private static double[] doubles(String key) {
        JsonNode n = DATA.get(key);
        double[] out = new double[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(n.get(i));
        }
        return out;
    }

    private static Circle circle() {
        SkyFrame sf = new SkyFrame();
        sf.setSystem("ICRS");
        return new Circle(sf, doubles("CENTRE"), DATA.get("RADIUS").doubleValue(), null);
    }

    @Test
    void offset2AndResolveAreSpherical() {
        Circle c = circle();
        double[] point = new double[2];
        double angle = c.offset2(doubles("CENTRE"), 1.0, 0.5, point);
        assertArrayEquals(doubles("OFFSET2"), new double[] {point[0], point[1], angle}, SKY_TOL);
        double[] p4 = new double[2];
        double[] d = c.resolve(doubles("CENTRE"), new double[] {1.5, 1.2}, new double[] {1.2, 1.3}, p4);
        assertArrayEquals(doubles("RESOLVE"), new double[] {p4[0], p4[1], d[0], d[1]}, SKY_TOL);
    }

    @Test
    void axisDistanceAndOffsetWrapLongitude() {
        Circle c = circle();
        assertArrayEquals(
                doubles("AX_DISTANCE_OFFSET"), new double[] {c.axDistance(1, 0.1, 6.2), c.axOffset(1, 6.2, 0.2)});
    }

    @Test
    void systemAndPickedAxesComeFromTheSkyFrame() {
        Circle c = circle();
        assertEquals(DATA.get("SYSTEM").textValue(), c.getSystem());
        Frame picked = c.pickAxes(2, new int[] {2, 1}, null);
        assertEquals(DATA.get("PICKED").get(0).textValue(), picked.astClass());
        assertInstanceOf(Circle.class, picked);
        assertEquals(DATA.get("PICKED").get(1).textValue(), picked.getSystem());
    }

    @Test
    void conversionsToAndFromTheRegion() {
        Circle c = circle();
        SkyFrame gal = new SkyFrame();
        gal.setSystem("Galactic");
        boolean toRegion = gal.convert(c, "") != null;
        boolean fromRegion = c.convert(gal, "") != null;
        assertEquals(DATA.get("CONVERTS").get(0).booleanValue(), toRegion);
        assertEquals(DATA.get("CONVERTS").get(1).booleanValue(), fromRegion);
        assertFalse(c.isUnitFrame());
    }

    @Test
    void regionPointsAreInTheCurrentFrame() {
        Box b = new Box(new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null);
        Region mapped = b.mapRegion(new ZoomMap(2, 2.0), new Frame(2));
        double[][] pts = mapped.getRegionPoints();
        assertArrayEquals(doubles("MAPPED_BOX_POINTS"), new double[] {pts[0][0], pts[0][1], pts[1][0], pts[1][1]});
    }
}
