package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_polygon_oracle.rs}: Polygon boundary meshes against
 * C (polygon.c {@code RegBaseMesh}), for a quadrilateral in an ICRS SkyFrame, whose mesh follows
 * great circles, and a Cartesian square and triangle ({@code oracle/region_polygon.json}, from
 * {@code tools/oracle_fixtures/region_polygon_probe.c}).
 *
 * <p>The meshes in the plain Frame come from arithmetic on the vertices and are compared exactly.
 * On the sky each mesh point is placed by {@code astOffset} along a great circle: the {@code sin}
 * and {@code cos} of the coordinates of the two vertices, a cross product normalised by a square
 * root, the {@code sin} and {@code cos} of the distance along the edge, and {@code atan2} and
 * {@code asin} back to longitude and latitude, about ten functions each within 1 ulp of the true
 * value in {@code java.lang.Math} and in the C library, so 2 ulp apart, on values of at most 1;
 * the vertices lie at latitudes 0.2 to 0.8, where the conversion back to longitude amplifies an
 * error by less than {@code 1 / cos 0.8 < 2}, and the products by less than 10. That bounds the
 * difference by about {@code 10 * 10 * 4.4e-16 = 4.4e-14}, so {@link #SKY_TOL} is {@code 1e-12}.
 *
 * <p>The number of mesh points on each edge is {@code 1 + (int)(length * MeshSize / perimeter)},
 * the lengths found on the sky with {@code acos} or {@code atan2}; {@link
 * #edgePointCountsAreClearOfIntegers} shows that no such product on the sky is within {@code 1e-6}
 * of an integer, so the counts cannot differ.
 */
class RegionPolygonOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_polygon");

    /** The tolerance for a mesh on the sky (see the class description). */
    private static final double SKY_TOL = 1e-12;

    private static double[] doubles(JsonNode n) {
        double[] out = new double[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(n.get(i));
        }
        return out;
    }

    private static Frame frame(String name) {
        if ("sky".equals(name)) {
            SkyFrame sf = new SkyFrame();
            sf.setSystem("ICRS");
            return sf;
        }
        return new Frame(2);
    }

    private static Polygon polygon(String name, double[] xy) {
        int nv = xy.length / 2;
        double[] x = new double[nv];
        double[] y = new double[nv];
        System.arraycopy(xy, 0, x, 0, nv);
        System.arraycopy(xy, nv, y, 0, nv);
        return new Polygon(frame(name), nv, x, y, null);
    }

    @Test
    void meshesMatchTheCLibrary() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Polygon poly = polygon(name, doubles(c.get(1)));
            double[] want = doubles(c.get(2));
            double[][] mesh = poly.regMesh();
            int np = mesh[0].length;
            assertEquals(want.length, 2 * np, name + ": number of values");
            double tol = "sky".equals(name) ? SKY_TOL : 0.0;
            for (int i = 0; i < np; i++) {
                assertEquals(want[2 * i], mesh[0][i], tol, name + ": point " + i + " x");
                assertEquals(want[2 * i + 1], mesh[1][i], tol, name + ": point " + i + " y");
            }
            run++;
        }
        assertEquals(3, run);
    }

    @Test
    void edgePointCountsAreClearOfIntegers() {
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            double[] xy = doubles(c.get(1));
            int nv = xy.length / 2;
            Frame f = frame(name);
            double[] lens = new double[nv];
            double total = 0.0;
            for (int iv = 0; iv < nv; iv++) {
                int prev = (iv + nv - 1) % nv;
                lens[iv] = f.distance(new double[] {xy[prev], xy[nv + prev]}, new double[] {xy[iv], xy[nv + iv]});
                total += Math.abs(lens[iv]);
            }
            double mp = polygon(name, xy).getMeshSize() / total;
            for (double len : lens) {
                double v = len * mp;
                double frac = Math.abs(v - Math.rint(v));
                // In the plain Frame the product comes from correctly rounded arithmetic, the same
                // on every platform, and may be a whole number.
                assertTrue(frac > 1e-6 || !"sky".equals(name), name + ": " + v);
            }
        }
    }
}
