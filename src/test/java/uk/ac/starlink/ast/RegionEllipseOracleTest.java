package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_ellipse_oracle.rs}: Ellipse membership against C
 * (ellipse.c {@code Transform}, with its axes from {@code CalcPars}), for a three-point Ellipse
 * whose second point is not the end of its secondary axis, and an Ellipse in an ICRS SkyFrame over
 * a grid, where the components along and across the primary axis are measured on the sky; and
 * the dumps of Ellipses made with C's form-1 constructor ({@code oracle/region_ellipse.json}, from
 * {@code tools/oracle_fixtures/region_ellipse_probe.c}). Each Ellipse is read from C's dump.
 *
 * <p>Membership is compared exactly. {@link #noGridPointLiesNearTheSkyBoundary} shows that no
 * point of the sky grid lies within a relative {@code 1e-9} of the Ellipse's axes of the
 * boundary, far beyond the few ulps by which {@code java.lang.Math} and the C library may place
 * it.
 *
 * <p>The dumps hold the ends of the axes. In a plain Frame each is the centre plus the half-length
 * times the {@code sin} or {@code cos} of the angle, which may differ by 2 ulp between {@code
 * java.lang.Math} and the C library: about {@code 4.4e-16} of a product at most 2, added to a
 * centre at most 2, so {@link #TOL} is {@code 1e-14}. In the SkyFrame each comes from {@code
 * astOffset2}, which chains the {@code sin} and {@code cos} of the centre's coordinates and of the
 * offset, then {@code atan2} and {@code asin}: at most six such functions, each 2 ulp apart, through
 * unit vectors and their products, whose conditioning here (the centre at latitude 0.8, offsets of
 * 0.2 radians) amplifies by less than 10, on values at most 1.2: about {@code 6 * 10 * 4.4e-16 *
 * 1.2 = 3e-14}, as {@link RegionFrameOracleTest} finds for the same chain, so {@link #SKY_TOL} is
 * {@code 1e-12}.
 */
class RegionEllipseOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_ellipse");

    /** The tolerance for the values of a plain Frame's dump (see the class description). */
    private static final double TOL = 1e-14;

    /** The tolerance for the values of a SkyFrame's dump (see the class description). */
    private static final double SKY_TOL = 1e-12;

    private static double[] doubles(JsonNode n) {
        double[] out = new double[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(n.get(i));
        }
        return out;
    }

    /** The sky test grid: x = 0.7 + 0.01*i (i &lt; nx), y = 0.65 + 0.006*j (j &lt; ny), i varying fastest. */
    private static double[][] grid() {
        int nx = DATA.get("GRID").get(0).intValue();
        int ny = DATA.get("GRID").get(1).intValue();
        double[] gx = new double[nx * ny];
        double[] gy = new double[nx * ny];
        for (int k = 0; k < nx * ny; k++) {
            int i = k % nx;
            int j = k / nx;
            gx[k] = 0.7 + 0.01 * i;
            gy[k] = 0.65 + 0.006 * j;
        }
        return new double[][] {gx, gy};
    }

    private static String membership(Region reg, double[] x, double[] y) {
        double[][] out = reg.tran2(x.length, x, y, true);
        StringBuilder sb = new StringBuilder();
        for (double v : out[0]) {
            sb.append(v == AstObject.AST__BAD ? '0' : '1');
        }
        return sb.toString();
    }

    private static JsonNode find(String name) {
        for (JsonNode c : DATA.get("CASES")) {
            if (name.equals(c.get(0).textValue())) {
                return c;
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    void membershipMatchesTheCLibrary() {
        double[][] g = grid();
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Region reg = (Region) ChannelText.read(c.get(1).textValue());
            double[] x;
            double[] y;
            switch (name) {
                case "form0":
                    x = doubles(DATA.get("FORM0_X"));
                    y = doubles(DATA.get("FORM0_Y"));
                    break;
                case "sky":
                    x = g[0];
                    y = g[1];
                    break;
                case "form1":
                    x = doubles(DATA.get("FORM1_X"));
                    y = doubles(DATA.get("FORM1_Y"));
                    break;
                default:
                    throw new IllegalStateException("no test points for case " + name);
            }
            assertEquals(c.get(2).textValue(), membership(reg, x, y), name);
            run++;
        }
        assertEquals(3, run);
    }

    private static SkyFrame icrs() {
        SkyFrame sf = new SkyFrame();
        sf.setSystem("ICRS");
        return sf;
    }

    private static Ellipse form1(Frame f, double cx, double cy, double a, double b, double angle) {
        return new Ellipse(f, 1, new double[] {cx, cy}, new double[] {a, b}, new double[] {angle}, null);
    }

    @Test
    void noGridPointLiesNearTheSkyBoundary() {
        double[][] g = grid();
        double f = 1.0e-9;
        String small = membership(form1(icrs(), 1.0, 0.8, 0.2 * (1 - f), 0.1 * (1 - f), 0.3), g[0], g[1]);
        String large = membership(form1(icrs(), 1.0, 0.8, 0.2 * (1 + f), 0.1 * (1 + f), 0.3), g[0], g[1]);
        assertEquals(small, large);
    }

    @Test
    void constructorMatchesAstEllipseForm1() {
        RegionProbeText.checkCloseText(
                find("sky").get(1).textValue(),
                ChannelText.dump(form1(icrs(), 1.0, 0.8, 0.2, 0.1, 0.3)),
                SKY_TOL,
                "sky");
        RegionProbeText.checkCloseText(
                find("form1").get(1).textValue(),
                ChannelText.dump(form1(new Frame(2), 1.0, 2.0, 2.0, 1.0, 0.3)),
                TOL,
                "form1");
    }
}
