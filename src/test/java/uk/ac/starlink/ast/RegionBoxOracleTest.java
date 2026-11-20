package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_box_oracle.rs}: Box membership against C (box.c
 * {@code Cache} and {@code Transform}), for a Box in an ICRS SkyFrame whose corner wraps through
 * RA 0, which C sizes by the axis distance from centre to corner, and for a Box of zero width on
 * one axis, which C widens to the width of its uncertainty when Negated and Closed differ; the
 * sky Box's bounding box; and the boundary meshes (box.c {@code RegBaseMesh}) of a flat Box, one
 * whose axes have different units, a sky Box, a 3-D Box and a single-point Box ({@code
 * oracle/region_box.json}, from {@code tools/oracle_fixtures/region_box_probe.c}).
 *
 * <p>Every value is compared exactly. The membership and bounds of the sky Box use SkyAxis
 * distances and offsets of the centre from the corner, which are sums and differences normalised
 * by {@code fmod} ({@code %}) and by adding or subtracting a whole turn, all correctly rounded in
 * IEEE arithmetic and so the same on every platform; the bounds are the centre plus and minus that
 * distance. A boundary mesh is made of corners and sums of steps along the edges, also exact; for
 * the sky Box the number of points on each edge is the truncation of the edge's share of the
 * MeshSize, in proportion to its length, which {@code SkyFrame.distance} sums through {@code
 * java.lang.Math}. Those shares, 41.22, 56.95, 41.22 and 60.60, are at least 0.047 from an
 * integer, where a few ulp in each of the 49 distances summed along an edge change them by about
 * {@code 1e-13}.
 */
class RegionBoxOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_box");

    private static double[] doubles(JsonNode n) {
        double[] out = new double[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(n.get(i));
        }
        return out;
    }

    private static Region zeroWidth(boolean negated, boolean closed) {
        Box b = new Box(new Frame(2), 1, new double[] {1.0, 5.0}, new double[] {2.0, 5.0}, null);
        if (!closed) {
            b.setClosed(false);
        }
        if (negated) {
            b.negate();
        }
        return b;
    }

    private static SkyFrame icrs() {
        SkyFrame sf = new SkyFrame();
        sf.setSystem("ICRS");
        return sf;
    }

    private static Region skyBox() {
        return new Box(icrs(), 0, new double[] {6.27, 0.5}, new double[] {0.037, 0.52}, null);
    }

    private static String membership(Region reg, double[] x, double[] y) {
        double[][] out = reg.tran2(x.length, x, y, true);
        StringBuilder sb = new StringBuilder();
        for (double v : out[0]) {
            sb.append(v == AstObject.AST__BAD ? '0' : '1');
        }
        return sb.toString();
    }

    @Test
    void membershipMatchesTheCLibrary() {
        double[] x15 = {1.5, 1.5, 1.5, 1.5};
        double[] zeroY = doubles(DATA.get("ZERO_Y"));
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            String got;
            switch (name) {
                case "sky_box":
                    got = membership(skyBox(), doubles(DATA.get("SKY_X")), doubles(DATA.get("SKY_Y")));
                    break;
                case "zero_width":
                    got = membership(zeroWidth(false, true), x15, zeroY);
                    break;
                case "zero_width_negated_open":
                    got = membership(zeroWidth(true, false), x15, zeroY);
                    break;
                case "zero_width_negated_closed":
                    got = membership(zeroWidth(true, true), x15, zeroY);
                    break;
                default:
                    throw new AssertionError("no construction for case " + name);
            }
            assertEquals(c.get(1).textValue(), got, name);
        }
    }

    @Test
    void skyBoxBoundsMatchTheCLibrary() {
        double[][] b = skyBox().getRegionBounds();
        assertArrayEquals(doubles(DATA.get("SKY_BOUNDS")), new double[] {b[0][0], b[0][1], b[1][0], b[1][1]});
    }

    private static Region boxed(Frame f, double[] lbnd, double[] ubnd) {
        return new Box(f, 1, lbnd, ubnd, null);
    }

    private static Region meshBox(String name) {
        switch (name) {
            case "flat":
                return boxed(new Frame(2), new double[] {0.0, 0.0}, new double[] {3.0, 1.0});
            case "mixed_units": {
                Frame f = new Frame(2);
                f.setUnit(1, "Hz");
                f.setUnit(2, "m");
                return boxed(f, new double[] {0.0, 0.0}, new double[] {3.0, 1.0});
            }
            case "sky":
                return boxed(icrs(), new double[] {1.0, 0.2}, new double[] {1.3, 0.4});
            case "three_d":
                return boxed(new Frame(3), new double[] {0.0, 0.0, 0.0}, new double[] {1.0, 2.0, 3.0});
            case "single_point":
                return boxed(new Frame(2), new double[] {1.0, 3.0}, new double[] {1.0, 3.0});
            default:
                throw new AssertionError("no construction for case " + name);
        }
    }

    @Test
    void meshesMatchTheCLibrary() {
        for (JsonNode c : DATA.get("MESHES")) {
            String name = c.get(0).textValue();
            double[][] mesh = meshBox(name).regMesh();
            int np = mesh[0].length;
            double[] got = new double[np * mesh.length];
            int k = 0;
            for (int i = 0; i < np; i++) {
                for (double[] axis : mesh) {
                    got[k++] = axis[i];
                }
            }
            double[] want = doubles(c.get(1));
            assertEquals(want.length, got.length, name + ": number of values");
            assertArrayEquals(want, got, name);
        }
    }
}
