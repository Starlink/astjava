package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_cmpregion_oracle.rs}: CmpRegion construction,
 * membership, mesh and dump against C (cmpregion.c {@code astInitCmpRegion}, {@code Transform},
 * {@code RegBaseMesh}, {@code Dump}, {@code Load}): an FK5 Circle ANDed with a Galactic Box, which
 * the constructor maps into FK5, and the XOR of two Boxes, which C makes from AND and OR and dumps
 * as the two Boxes XORed ({@code oracle/region_cmpregion.json}, from {@code
 * tools/oracle_fixtures/region_cmpregion_probe.c}).
 *
 * <p>The mesh of the sky AND is the Circle's mesh on the sphere kept where it is inside the Box,
 * mapped from Galactic to FK5, and the Box's mesh kept where it is inside the Circle: each point
 * comes from {@code SkyFrame.offset2} (whose error {@code RegionEllipseOracleTest} bounds by
 * {@code 4e-14}) and the rotation between the two systems ({@code sin}, {@code cos}, {@code atan2}
 * and {@code asin} of values of at most 1, at declinations near 0.5, a few units in the last place
 * more), so {@link #SKY_TOL} is {@code 1e-12}. Which points are kept, and so their number, would
 * change only for a point within such an error of the other Region's boundary, of which there is
 * none: {@link #meshPointsAreClearOfTheOtherBoundary}.
 */
class RegionCmpRegionOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_cmpregion");

    private static final double SKY_TOL = 1.0e-12;

    private static String membership(Region reg, double[] x, double[] y) {
        double[][] out = reg.transform(new double[][] {x.clone(), y.clone()}, x.length, true);
        StringBuilder sb = new StringBuilder();
        for (double v : out[0]) {
            sb.append(v == AstObject.AST__BAD ? '0' : '1');
        }
        return sb.toString();
    }

    private static Circle fk5Circle() {
        SkyFrame fk5 = new SkyFrame();
        fk5.setSystem("FK5");
        return new Circle(fk5, 1, new double[] {1.0, 0.5}, new double[] {0.1}, null);
    }

    private static Box galacticBox() {
        SkyFrame gal = new SkyFrame();
        gal.setSystem("Galactic");
        return new Box(gal, 0, new double[] {2.857, -0.346}, new double[] {3.057, -0.146}, null);
    }

    private static CmpRegion skyAnd() {
        return new CmpRegion(fk5Circle(), galacticBox(), CmpRegion.AST__AND);
    }

    private static CmpRegion xor() {
        Box a = new Box(new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {2.0, 2.0}, null);
        Box b = new Box(new Frame(2), 1, new double[] {1.0, 1.0}, new double[] {3.0, 3.0}, null);
        return new CmpRegion(a, b, 3);
    }

    @Test
    void secondRegionIsMappedIntoTheFirstFrame() {
        assertEquals(
                DATA.get("SKY_AND").textValue(),
                membership(skyAnd(), new double[] {1.0, 1.05, 1.3}, new double[] {0.5, 0.5, 0.5}));
    }

    @Test
    void meshUsesComponentCurrentFrames() {
        CmpRegion and = skyAnd();
        double[][] m = and.regMesh();
        // Normalised as C's astGetRegionMesh gives the mesh.
        double[][] mesh = and.normalPoints(m, m[0].length, true);
        JsonNode want = DATA.get("SKY_AND_MESH");
        int n = mesh[0].length;
        assertEquals(want.size(), 2 * n, "number of values");
        for (int i = 0; i < n; i++) {
            assertEquals(ProbeData.toDouble(want.get(2 * i)), mesh[0][i], SKY_TOL, "point " + i + " axis 1");
            assertEquals(ProbeData.toDouble(want.get(2 * i + 1)), mesh[1][i], SKY_TOL, "point " + i + " axis 2");
        }
    }

    /**
     * Each point of the Circle's mesh is at least 1e-6 from the Box's boundary, and each point of
     * the Box's mesh at least 1e-6 from the Circle's, in the Frame of the other.
     */
    @Test
    void meshPointsAreClearOfTheOtherBoundary() {
        CmpRegion and = skyAnd();
        Region circle = and.getRegion1();
        Region box = and.getRegion2();
        assertClear(circle.regMesh(), box);
        assertClear(box.regMesh(), circle);
    }

    /** No point of {@code mesh} changes membership of {@code reg} when moved by 1e-6 on an axis. */
    private static void assertClear(double[][] mesh, Region reg) {
        int n = mesh[0].length;
        double[][] base = reg.transform(mesh, n, true);
        for (int axis = 0; axis < 2; axis++) {
            for (double d : new double[] {-1.0e-6, 1.0e-6}) {
                double[][] moved = {mesh[0].clone(), mesh[1].clone()};
                for (int i = 0; i < n; i++) {
                    moved[axis][i] += d;
                }
                double[][] out = reg.transform(moved, n, true);
                for (int i = 0; i < n; i++) {
                    assertEquals(
                            base[0][i] == AstObject.AST__BAD,
                            out[0][i] == AstObject.AST__BAD,
                            "mesh point " + i + " moved on axis " + axis);
                }
            }
        }
    }

    @Test
    void xorIsMadeDumpedAndReadAsCDoes() {
        double[] x = {0.5, 1.5, 2.5, 4.0};
        CmpRegion cmp = xor();
        assertEquals(3, cmp.cmpRegionList(new java.util.ArrayList<>()));
        assertEquals(DATA.get("XOR").textValue(), membership(cmp, x, x));
        assertEquals(DATA.get("XOR_DUMP").textValue(), ChannelText.dump(cmp));
        Region back = (Region) ChannelText.read(DATA.get("XOR_DUMP").textValue());
        assertEquals(DATA.get("XOR").textValue(), membership(back, x, x));
    }
}
