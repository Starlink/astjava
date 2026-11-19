package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_stc_oracle.rs}: StcResourceProfile against C (stc.c,
 * stcresourceprofile.c), from {@code oracle/region_stc.json} ({@code
 * tools/oracle_fixtures/region_stc_probe.c}): the dumps of a plain, a negated and a sky Stc whose
 * AstroCoords Error Region is given in another SkyFrame, of an Stc made from an Stc whose
 * base->current Mapping is a ZoomMap, and of that mapped Stc simplified; two test points
 * transformed by each and whether it is bounded; its overlap with a Circle each way round; the
 * AstroCoords element {@code astGetStcCoord} gives; and the errors for an AstroCoords index out of
 * range and an unknown key. C's probe transforms the points before it writes the dump, as {@link
 * #stcMatchesC} does, since the transformation leaves Negated set on the encapsulated Region.
 *
 * <p>Only the cases of the StcResourceProfile class are run ({@link #CASES_RUN}); the others, and
 * {@code full_dumps_match_c} and {@code c_dumps_read_back_as_c_reads_them}, need the other Stc
 * classes. {@code error_ellipse_licence_is_still_needed} checks a licence of the Rust port's own
 * and is not translated.
 *
 * <p>The values in a plain Frame are exact. In the SkyFrame of the {@code sky} case, the second
 * defining point of each Circle comes from {@code SkyFrame.offset2}, whose difference from C's
 * {@code astOffset2} {@code RegionEllipseOracleTest} bounds by {@code 3e-14} for a centre at
 * latitude 0.8 and an offset of 0.2 radians; the chain here is the same with smaller offsets, so
 * the numbers in that dump are compared to within {@link #SKY_TOL}, {@code 1e-12}.
 *
 * <p>The AstroCoords Error Region of the {@code sky} case, and so {@code SKY_COORD}, is a Galactic
 * Circle of radius {@code R = 1e-4} that the Stc re-expresses in ICRS as the Ellipse that best fits
 * its mapped boundary mesh of {@code N = 200} points (ellipse.c {@code astBestEllipse}, {@link
 * Ellipse#bestEllipse}; {@code ERROR_ELLIPSE_LICENSED} in the Rust test). The Ellipse's three defining
 * points are its centre and the ends of its primary and secondary axes. The centre is the Circle's
 * centre converted from Galactic to ICRS (the sines and cosines of its coordinates, a product of
 * rotation matrices, then {@code atan2} and {@code asin}), a few ulps of values below 2, and is
 * compared within {@link #SKY_TOL}.
 *
 * <p>The ends of the axes are not determined by the values. The conversion is a rotation, so the
 * exact mesh is a circle about the centre and its points' distances from the centre differ only by
 * rounding. The fit takes the direction of the primary axis from the vertex of the parabola through
 * the farthest mesh point and its neighbours, so a difference of a few ulps in the mesh moves that
 * direction anywhere within a mesh step around the centre: this machine's {@code java.lang.Math}
 * puts the axis ends about {@code 2e-5} away from C's, as the Rust test finds of other platforms. No
 * tolerance on them short of the radius follows, and one of the radius tests nothing, so {@link
 * #withoutEllipseAxisEnds} leaves out their values, as the Rust test leaves out the Ellipse's values
 * off the platform its C values were taken on, as phase 7 compares an Ellipse fitted to a Circle,
 * and as {@code docs/rust-c-differences.md} records. The two half-lengths, which the direction does
 * not decide, are compared instead ({@link #assertHalfLengths}), within bounds worked out from
 * {@code bestEllipse}'s arithmetic as follows.
 *
 * <p>Each platform's mesh coordinates are within {@link #DELTA}, {@code 1e-14}, of their exact
 * values: {@code SkyFrame.offset2} with an offset of {@code 1e-4} on values below 2, then the
 * conversion above, each step a few ulps (below {@code 4.4e-16} each) of well-conditioned values,
 * about 20 ulps in all. A distance of {@code 1e-4} from the centre is then within {@link #EPS_D},
 * {@code 3 DELTA}, of the exact {@code R} (both points move, by up to {@code sqrt(2) DELTA} each,
 * and the distance's own rounding is below {@code 1e-16}); the components {@code px} and {@code py}
 * of a mesh point along and across the primary axis are within {@link #ETA}, {@code 4 DELTA} (as the
 * distance, and the axis end the components are resolved against moves the direction by {@code
 * DELTA / R} radians, which moves {@code py} by up to {@code DELTA} more).
 *
 * <p>Primary half-length {@code mx}: it is the largest distance {@code r2}, or the vertex of the
 * parabola through {@code r2} and its neighbours {@code r1} and {@code r3}, at equal steps of angle
 * (the mesh is uniform), whose height above {@code r2} is {@code (a - b)^2 / (8 (a + b)) <= (a + b) /
 * 8} with {@code a = r2 - r1}, {@code b = r2 - r3}. The distances are all {@code R} within {@code
 * EPS_D}, so {@code a + b <= 4 EPS_D} and each platform's {@code mx} is within {@link #DMX}, {@code
 * 1.5 EPS_D}, of {@code R}. The dumped axis end is {@code astOffset2} of the centre by {@code mx},
 * whose distance the test measures again within {@code EPS_D}. So the two platforms' primary
 * half-lengths agree within {@code 2 DMX + 2 EPS_D = 1.5e-13}.
 *
 * <p>Secondary half-length {@code mn}: the mean of {@code T_i / w_i} weighted by {@code w_i = |py_i|},
 * with {@code T_i = mx py_i^2 / sqrt(mx^2 - px_i^2)}. In exact arithmetic it is {@code R} plus a
 * systematic term from the sphere: by the spherical Pythagoras theorem {@code px^2 + py^2 = d^2 +
 * px^2 py^2 / 3} to leading order, so {@code T_i / w_i = mx (1 + px_i^2 / 6)} and {@code mn - mx = mx
 * sum(px_i^2 |py_i|) / (6 sum |py_i|)}, about {@code R^3 / 18 = 5.6e-14}, the offset seen between
 * each platform's two half-lengths. That term depends on the direction only through how the mesh
 * samples the circle; {@link #curvature} gives it for each platform's direction and the bound takes
 * their difference ({@code 1.3e-17} here). Rounding moves each platform's {@code mn} from the exact
 * value at its own direction by, to first order, {@code DMX} through the factor {@code mx}, {@code 3
 * N mx ETA / sum|py|} through {@code py^2} and the weights, and {@code mx R (DMX + ETA) sum(1/|py|) /
 * sum|py|} through {@code mx^2 - px^2}, which is about {@code py^2} and moves by up to {@code 2 R (DMX
 * + ETA)}. That last term is large for a mesh point close to the axis; {@link #roundingBound} computes
 * it for each platform's direction, requires the relative change of {@code mx^2 - px^2} to be at
 * most 0.1 at every point, where the first-order term is within a factor 1.2 of the true change of
 * {@code 1/sqrt}, and takes 1.2 times it. The dumped axis end is measured again within {@code EPS_D}
 * on each side. The two platforms' secondary half-lengths agree within the sum, about {@code 4e-12}
 * here, a part in {@code 2.5e7} of the radius; they differ by {@code 6.4e-15} on this machine.
 */
class RegionStcOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_stc");

    /** The tolerance on the numbers of a dump in a SkyFrame (see the class description). */
    private static final double SKY_TOL = 1.0e-12;

    /** The cases of {@code CASES} run here: those of the StcResourceProfile class. */
    private static final List<String> CASES_RUN =
            Arrays.asList("plain", "negated", "sky", "mapped", "from_mapped", "mapped_simplified");

    /** The radius of the Error Circle, {@code R} in the class description. */
    private static final double ERROR_RADIUS = 1.0e-4;

    /** How far each platform's mesh coordinates may be from exact (see the class description). */
    private static final double DELTA = 1.0e-14;

    /** How far a distance from the centre may be from exact (see the class description). */
    private static final double EPS_D = 3.0 * DELTA;

    /** How far a component along or across the axis may be from exact (see the class description). */
    private static final double ETA = 4.0 * DELTA;

    /** How far the primary half-length may be from the radius (see the class description). */
    private static final double DMX = 1.5 * EPS_D;

    /** The cases whose dump holds the fitted Error Ellipse (see the class description). */
    private static final List<String> ERROR_ELLIPSE_CASES = Arrays.asList("sky", "catalog_entry", "sky_coord");

    private static final double[] C0 = {0.0, 0.0};

    private static Circle circle(Frame frame, double[] centre, double radius) {
        return new Circle(frame, 1, centre, new double[] {radius}, null);
    }

    private static StcResourceProfile plain() {
        return new StcResourceProfile(circle(new Frame(2), C0, 1.0), (AstroCoords[]) null);
    }

    private static SkyFrame sky(String system) {
        SkyFrame sf = new SkyFrame();
        if (system != null) {
            sf.setSystem(system);
        }
        return sf;
    }

    private static KeyMap skyCoord() {
        KeyMap coord = new KeyMap();
        coord.mapPut1C(Stc.AST__STCNAME, new String[] {"RA", "Dec"}, null);
        coord.mapPut0A(Stc.AST__STCERROR, circle(sky("Galactic"), new double[] {2.0, -0.3}, 0.0001), null);
        return coord;
    }

    private static Circle skyCircle() {
        return circle(sky(null), new double[] {1.0, 0.5}, 0.01);
    }

    private static StcResourceProfile skyStc() {
        return new StcResourceProfile(skyCircle(), new KeyMap[] {skyCoord()});
    }

    private static Region mapped() {
        Frame zoomed = new Frame(2);
        zoomed.setDomain("ZOOMED");
        return plain().mapRegionInto(new ZoomMap(2, 2.0), zoomed);
    }

    /** The Stc each case names, built as the probe builds it. */
    private static Region built(String name) {
        switch (name) {
            case "plain":
                return plain();
            case "negated":
                // C negates a copy (astCopy) of the plain Stc.
                Region neg = (Region) plain().copy();
                neg.negate();
                return neg;
            case "sky":
                return skyStc();
            case "mapped":
                return mapped();
            case "from_mapped":
                return new StcResourceProfile(mapped(), (AstroCoords[]) null);
            case "mapped_simplified":
                return (Region) mapped().simplify();
            default:
                throw new IllegalArgumentException("no case " + name);
        }
    }

    /**
     * {@code dump} without the values of the PointSets inside Ellipses other than the first point,
     * the centre.
     */
    static String withoutEllipseAxisEnds(String dump) {
        StringBuilder sb = new StringBuilder();
        boolean inEllipse = false;
        for (String l : CValues.lines(dump)) {
            String t = l.trim();
            if (t.startsWith("Begin Ellipse")) {
                inEllipse = true;
            } else if (t.startsWith("End Ellipse")) {
                inEllipse = false;
            }
            if (!(inEllipse && isValue(t) && !t.startsWith("X1 =") && !t.startsWith("X2 ="))) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** Whether {@code t}, a trimmed line, is a value {@code X<n> = ...} of a PointSet. */
    private static boolean isValue(String t) {
        return t.startsWith("X") && t.contains(" = ");
    }

    /** The defining points of each Ellipse in {@code dump}: centre, then the two axis ends. */
    private static List<double[][]> ellipsePoints(String dump) {
        List<double[][]> out = new ArrayList<>();
        List<Double> vals = null;
        for (String l : CValues.lines(dump)) {
            String t = l.trim();
            if (t.startsWith("Begin Ellipse")) {
                vals = new ArrayList<>();
            } else if (t.startsWith("End Ellipse")) {
                assertEquals(6, vals.size(), "values of an Ellipse");
                double[][] p = new double[3][2];
                for (int i = 0; i < 6; i++) {
                    p[i / 2][i % 2] = vals.get(i);
                }
                out.add(p);
                vals = null;
            } else if (vals != null && isValue(t)) {
                vals.add(Double.parseDouble(CValues.splitWhitespace(t)[2]));
            }
        }
        return out;
    }

    /** The boundary mesh of the Error Circle in ICRS, from which the Ellipse is fitted. */
    private static double[][] errorMesh() {
        Circle err = circle(sky("Galactic"), new double[] {2.0, -0.3}, ERROR_RADIUS);
        FrameSet fs = err.convert(sky(null), "");
        return err.mapRegionInto(
                        fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT), fs.getFrame(FrameSet.AST__CURRENT))
                .regMesh();
    }

    /**
     * The components of the mesh points along and across the primary axis of an Ellipse with
     * defining points {@code p}, as {@code bestEllipse} resolves them.
     */
    private static double[][] resolved(SkyFrame icrs, double[][] mesh, double[][] p) {
        return icrs.resolvePoints(p[0], p[1], mesh, mesh[0].length);
    }

    /** {@code sum(px^2 |py|) / sum|py|}, the sampling of the systematic term (see the class description). */
    private static double curvature(double[][] r) {
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < r[0].length; i++) {
            double py = Math.abs(r[1][i]);
            num += r[0][i] * r[0][i] * py;
            den += py;
        }
        return num / den;
    }

    /**
     * The first-order bound on how far rounding moves one platform's secondary half-length from
     * its exact value at its own direction (see the class description).
     */
    private static double roundingBound(String name, double[][] r, double mx) {
        int n = r[0].length;
        double sumAbs = 0.0;
        double sumInv = 0.0;
        double minAbs = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double py = Math.abs(r[1][i]);
            sumAbs += py;
            sumInv += 1.0 / py;
            minAbs = Math.min(minAbs, py);
        }
        double change = 2.0 * ERROR_RADIUS * (DMX + ETA);
        assertTrue(
                change / (minAbs * minAbs) <= 0.1,
                name + ": a mesh point is too close to the axis for the first-order bound (|py| = " + minAbs + ")");
        return DMX + mx * (3.0 * n * ETA + 1.2 * ERROR_RADIUS * (DMX + ETA) * sumInv) / sumAbs;
    }

    /** Compares the half-lengths of the Ellipses in two dumps (see the class description). */
    private static void assertHalfLengths(String name, String got, String want) {
        List<double[][]> g = ellipsePoints(got);
        List<double[][]> w = ellipsePoints(want);
        assertFalse(w.isEmpty(), name + ": C's dump holds an Ellipse");
        assertEquals(w.size(), g.size(), name + ": Ellipses");
        SkyFrame icrs = new SkyFrame();
        double[][] mesh = errorMesh();
        for (int i = 0; i < w.size(); i++) {
            double[][] pw = w.get(i);
            double[][] pg = g.get(i);
            double mx = icrs.distance(pw[0], pw[1]);
            assertEquals(
                    mx, icrs.distance(pg[0], pg[1]), 2.0 * DMX + 2.0 * EPS_D, name + ": Ellipse " + i + " primary");
            double[][] rw = resolved(icrs, mesh, pw);
            double[][] rg = resolved(icrs, mesh, pg);
            double tol = roundingBound(name + " (C)", rw, mx)
                    + roundingBound(name, rg, mx)
                    + mx * Math.abs(curvature(rw) - curvature(rg)) / 6.0
                    + 2.0 * EPS_D;
            assertEquals(
                    icrs.distance(pw[0], pw[2]),
                    icrs.distance(pg[0], pg[2]),
                    tol,
                    name + ": Ellipse " + i + " secondary");
        }
    }

    /** Compares the dump of case {@code name} with C's, as the class description says. */
    private static void assertDump(String name, String got, String want) {
        String g = got;
        String w = want;
        if (ERROR_ELLIPSE_CASES.contains(name)) {
            assertHalfLengths(name, got, want);
            g = withoutEllipseAxisEnds(g);
            w = withoutEllipseAxisEnds(w);
        }
        if (name.startsWith("sky")) {
            assertTrue(CValues.nativeStringsWithin(g, w, SKY_TOL), name + ": dump\n--- got\n" + g + "--- C\n" + w);
        } else {
            assertEquals(w, g, name + ": dump");
        }
    }

    @Test
    void stcMatchesC() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            if (!CASES_RUN.contains(name)) {
                continue;
            }
            run++;
            Region stc = built(name);
            JsonNode points = c.get(3);
            int n = points.size();
            double[][] in = new double[2][n];
            for (int i = 0; i < n; i++) {
                in[0][i] = ProbeData.toDouble(points.get(i).get(0));
                in[1][i] = ProbeData.toDouble(points.get(i).get(1));
            }
            double[][] out = stc.transform(in, n, true);
            for (int i = 0; i < n; i++) {
                double[] want = {
                    ProbeData.toDouble(points.get(i).get(2)),
                    ProbeData.toDouble(points.get(i).get(3))
                };
                CValues.assertCValues(new double[] {out[0][i], out[1][i]}, want, name + ": point " + i);
            }
            assertDump(name, ChannelText.dump(stc), c.get(1).textValue());
            assertEquals(c.get(4).booleanValue(), stc.getBounded(), name + ": bounded");
        }
        assertEquals(CASES_RUN.size(), run, "cases run");
    }

    @Test
    void overlapWithACircleMatchesC() {
        StcResourceProfile stc = plain();
        Circle big = circle(new Frame(2), C0, 2.0);
        assertEquals(DATA.get("OVERLAP_STC_CIRCLE").intValue(), stc.overlap(big));
        assertEquals(DATA.get("OVERLAP_CIRCLE_STC").intValue(), big.overlap(stc));
    }

    @Test
    void astroCoordsMatchC() {
        StcResourceProfile stc = skyStc();
        assertEquals(DATA.get("SKY_NCOORD").intValue(), stc.getStcNCoord());
        KeyMap coord = stc.stcCoord(1);
        assertDump("sky_coord", ChannelText.dump(coord), DATA.get("SKY_COORD").textValue());
        assertEquals("Circle", stc.getC("RegionClass"));

        AstException e = assertThrows(AstException.class, () -> stc.stcCoord(2));
        assertEquals(DATA.get("COORD_INDEX").intValue(), e.getStatus());
        assertEquals(
                "astGetStcCoord(StcResourceProfile): Supplied AstroCoords index (2) is invalid.",
                e.getMsgText().split("\n", -1)[0]);
    }

    @Test
    void unknownAstroCoordsKeyIsStckey() {
        KeyMap bad = new KeyMap();
        bad.mapPut0A("Colour", circle(sky("Galactic"), new double[] {2.0, -0.3}, 0.0001), null);
        AstException e =
                assertThrows(AstException.class, () -> new StcResourceProfile(skyCircle(), new KeyMap[] {bad}));
        assertEquals(AstException.AST__STCKEY, e.getStatus());
        assertEquals(DATA.get("COORD_KEY").intValue(), e.getStatus());
        assertEquals(
                "astInitStc(StcResourceProfile): Unknown key \"Colour\" supplied in an AstroCoords list.",
                e.getMsgText());
    }
}
