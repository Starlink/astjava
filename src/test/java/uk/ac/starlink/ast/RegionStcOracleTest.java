package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_stc_oracle.rs}: the Stc classes against C (stc.c,
 * stcresourceprofile.c, stcsearchlocation.c, stccatalogentrylocation.c, stcobsdatalocation.c), from
 * {@code oracle/region_stc.json} ({@code tools/oracle_fixtures/region_stc_probe.c}): the dumps of a
 * plain, a negated and a sky StcResourceProfile whose AstroCoords Error Region is given in another
 * SkyFrame, of an Stc made from an Stc whose base->current Mapping is a ZoomMap, of that mapped Stc
 * simplified, of an StcSearchLocation, of an StcCatalogEntryLocation with the sky AstroCoords, and
 * of an StcObsDataLocation without and with an observatory position; two test points transformed
 * by each and whether it is bounded; each dump written with Full = 1; each of C's dumps read back
 * and written again; the overlap of the plain Stc with a Circle each way round; the AstroCoords
 * element {@code astGetStcCoord} gives; and the errors for an AstroCoords index out of range and an
 * unknown key. C's probe transforms the points before it writes the dumps, as {@link #stcMatchesC}
 * and {@link #fullDumpsMatchC} do, since the transformation leaves Negated set on the encapsulated
 * Region. The probe gives the {@code obs_data} case its position with the protected {@code
 * astStcSetObs}, as {@link #built} does with {@link StcObsDataLocation#setObs}.
 *
 * <p>{@code error_ellipse_licence_is_still_needed} checks a licence of the Rust port's own and is
 * not translated.
 *
 * <p>The values in a plain Frame are exact. In the SkyFrame of the {@code sky} and {@code
 * catalog_entry} cases, the second defining point of each Circle comes from {@code
 * SkyFrame.offset2}, whose difference from C's {@code astOffset2} {@code RegionEllipseOracleTest}
 * bounds by {@code 3e-14} for a centre at latitude 0.8 and an offset of 0.2 radians; the chain here
 * is the same with smaller offsets, so the numbers in those dumps are compared to within {@link
 * #SKY_TOL}, {@code 1e-12}. A dump C wrote and Java reads back holds C's numbers, and is compared
 * exactly.
 *
 * <p>The AstroCoords Error Region of the {@code sky} and {@code catalog_entry} cases, and so {@code
 * SKY_COORD}, is a Galactic
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
 * <p>Secondary half-length {@code mn}: {@code smn / sw}, where {@code sw = sum w_i}, {@code smn = sum
 * T_i}, {@code w_i = |py_i|} and {@code T_i = mx py_i^2 / sqrt(mx^2 - px_i^2)}, over the points with
 * {@code mx^2 - px_i^2 > 0}. In exact arithmetic {@code mx = R}, and for a point on the circle the
 * spherical right triangle {@code cos R = cos px cos py} gives {@code R^2 - px^2 = py^2 (1 - px^2 / 3
 * + O(R^4))}, so {@code T*_i = R |py*_i| (1 + px*_i^2 / 6)} to a relative {@code O(R^4)}, and the
 * exact value at a direction {@code theta} is {@code mn*(theta) = R + R F(theta) / 6} with {@code F =
 * sum(px^2 |py|) / sum|py|}, about {@code R^2 / 3}: the offset of about {@code R^3 / 18 = 5.6e-14}
 * seen between each platform's two half-lengths. The two platforms' values are compared through
 * {@code mn_C - mn_J = (mn_C - mn*(theta_C)) + (mn*(theta_C) - mn*(theta_J)) + (mn*(theta_J) -
 * mn_J)}, each part bounded as follows.
 *
 * <ul>
 *   <li>C's own rounding, at C's direction, which is oracle data: to first order, {@code DMX}
 *       through the factor {@code mx}, {@code 3 N mx ETA / sw} through {@code py^2} and the weights,
 *       and {@code mx R (DMX + ETA) sum(1/|py|) / sw} through {@code mx^2 - px^2}, which is about
 *       {@code py^2} and moves by up to {@code 2 R (DMX + ETA)}; the last is 1.2 times its first-order
 *       value wherever that relative change is at most 0.1, as it is at every point by a factor of
 *       {@code 1.3e4} at C's direction ({@link #firstOrderBound}, which checks it).
 *   <li>The directions: {@code R |F(theta_C) - F(theta_J)| / 6}, from the mesh resolved at each
 *       ({@link #curvature}; {@code 1.3e-17} here; {@code ETA} moves each {@code F} by under {@code
 *       1e-16}, which {@code R / 6} makes {@code 2e-21}).
 *   <li>This platform's own rounding, at its own direction, which may lie as near a mesh point as the
 *       last bits of the mesh put it, so the first-order bound does not serve: it is bounded after
 *       the fact instead ({@link #platformBound}). The test repeats this platform's fit ({@link
 *       #refit}, calling the Circle's fit and the parts of {@code bestEllipse} that give the
 *       primary half-length and each {@code T_i}) and requires it to give the dumped axis ends bit
 *       for bit, so that its {@code T_i}, {@code w_i} and resolved components are the fit's own;
 *       then, since {@code |px*_i - px_i|} and {@code |py*_i - py_i|} are at most {@code ETA},
 *       {@code |smn - sum T*_i| <= sum |T_i - R |py_i| (1 + px_i^2 / 6)| + N R ETA (1 + R^2)} (a point
 *       the fit left out has {@code T_i = 0}) and {@code |sw - sum w*_i| <= N ETA + sum of |py_i| over
 *       those points}, whence {@code |mn_J - mn*(theta_J)| <= (|smn - sum T*| + R (1 + R^2) |sw - sum
 *       w*|) / sw}, with {@code R^5} added for the {@code O(R^4)} term. No term divides by a small
 *       {@code |py|}, and nothing about the platform's direction is assumed.
 * </ul>
 *
 * <p>Each dumped secondary axis end is measured again within {@code EPS_D}. The two platforms'
 * secondary half-lengths agree within the sum, about {@code 1.1e-12} on this machine, a part in
 * {@code 1e8} of the radius; they differ by {@code 6.4e-15}.
 */
class RegionStcOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_stc");

    /** The tolerance on the numbers of a dump in a SkyFrame (see the class description). */
    private static final double SKY_TOL = 1.0e-12;

    /** The number of cases in {@code CASES} and in {@code FULL_DUMPS}. */
    private static final int NCASES = 10;

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

    /** The cases whose dump is in a SkyFrame (see the class description). */
    private static final List<String> SKY_CASES = ERROR_ELLIPSE_CASES;

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

    private static Circle plainCircle() {
        return circle(new Frame(2), C0, 1.0);
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
            case "search":
                return new StcSearchLocation(plainCircle(), (AstroCoords[]) null);
            case "catalog_entry":
                return new StcCatalogEntryLocation(skyCircle(), new KeyMap[] {skyCoord()});
            case "obs_data_without_obs":
                return new StcObsDataLocation(plainCircle(), (AstroCoords[]) null);
            case "obs_data":
                StcObsDataLocation obsData = new StcObsDataLocation(plainCircle(), (AstroCoords[]) null);
                Frame geo = new Frame(3);
                geo.setDomain("GEO");
                obsData.setObs(new PointList(geo, 1, new double[][] {{1.0}, {2.0}, {3.0}}, null));
                return obsData;
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

    /**
     * The Error Circle mapped into ICRS, as the Stc maps it before simplifying it, which fits the
     * Ellipse.
     */
    private static Region mappedErrorCircle() {
        Circle err = circle(sky("Galactic"), new double[] {2.0, -0.3}, ERROR_RADIUS);
        FrameSet fs = err.convert(sky(null), "");
        return err.mapRegionInto(
                fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT), fs.getFrame(FrameSet.AST__CURRENT));
    }

    /** This platform's fit of an Ellipse to the Error Circle, with the values it computed on the way. */
    private static final class Fit {
        /** The Frame of the fit, the uncertainty's. */
        Frame frm;
        /** The centre. */
        double[] cen;
        /** The mesh, {@code [2][n]}. */
        double[][] mesh;
        /** The ends of the primary and secondary axes. */
        double[] pa;

        double[] pb;
        /** The primary half-length. */
        double mx;
        /** The components of the mesh points along and across the primary axis. */
        double[][] ps2;
        /** Each point's {@code T_i}, and {@code w_i}; zero for a point the fit leaves out. */
        double[] t;

        double[] w;
    }

    /**
     * This platform's Ellipse fit of the Error Circle, as {@code Circle.simplifyStep} makes it
     * ({@link Circle#fittedCircleOrEllipse}), with the mesh, centre and Frame it fits in, the
     * primary half-length ({@link Ellipse#bestPrimaryAxis}), and the resolved components, {@code
     * T_i} ({@link Ellipse#secondaryEstimate}) and {@code w_i} the fit used, which the bound needs.
     */
    private static Fit refit() {
        Region reg = mappedErrorCircle();
        Region nw = reg.parentSimplify();
        Fit f = new Fit();
        f.mesh = nw.regMesh();
        double[][] c = reg.regTransform(null, true);
        f.cen = new double[] {c[0][0], c[1][0]};
        f.frm = nw.getUncFrm(FrameSet.AST__CURRENT).regFrame();
        Ellipse fit = assertInstanceOf(Ellipse.class, Circle.fittedCircleOrEllipse(reg, nw), "the fit");
        double[][] pts = fit.getRegionPoints();
        f.pa = new double[] {pts[0][1], pts[1][1]};
        f.pb = new double[] {pts[0][2], pts[1][2]};
        f.mx = Ellipse.bestPrimaryAxis(f.mesh, f.cen, f.frm)[1];
        int np = f.mesh[0].length;
        f.ps2 = f.frm.resolvePoints(f.cen, f.pa, f.mesh, np);
        f.t = new double[np];
        f.w = new double[np];
        for (int ip = 0; ip < np; ip++) {
            double t = Ellipse.secondaryEstimate(f.mx, f.ps2[0][ip], f.ps2[1][ip]);
            if (t != AstObject.AST__BAD) {
                f.t[ip] = t;
                f.w[ip] = Math.abs(f.ps2[1][ip]);
            }
        }
        return f;
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
     * The first-order bound on how far rounding moves C's secondary half-length from its exact
     * value at C's direction, whose components are {@code r} (see the class description).
     */
    private static double firstOrderBound(double[][] r, double mx) {
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
        assertTrue(change / (minAbs * minAbs) <= 0.1, "C's axis is too close to a mesh point (|py| = " + minAbs + ")");
        return DMX + mx * (3.0 * n * ETA + 1.2 * ERROR_RADIUS * (DMX + ETA) * sumInv) / sumAbs;
    }

    /**
     * The bound, after the fact, on how far rounding moves this platform's secondary half-length
     * from its exact value at its own direction (see the class description).
     */
    private static double platformBound(Fit f) {
        double r = ERROR_RADIUS;
        int n = f.t.length;
        double sw = 0.0;
        double dt = 0.0;
        double dw = n * ETA;
        for (int i = 0; i < n; i++) {
            double px = f.ps2[0][i];
            double py = Math.abs(f.ps2[1][i]);
            sw += f.w[i];
            dt += Math.abs(f.t[i] - r * py * (1.0 + px * px / 6.0));
            if (f.w[i] == 0.0) {
                dw += py;
            }
        }
        dt += n * r * ETA * (1.0 + r * r);
        return (dt + r * (1.0 + r * r) * dw) / sw + Math.pow(r, 5);
    }

    /** Compares the half-lengths of the Ellipses in two dumps (see the class description). */
    private static void assertHalfLengths(String name, String got, String want) {
        List<double[][]> g = ellipsePoints(got);
        List<double[][]> w = ellipsePoints(want);
        assertFalse(w.isEmpty(), name + ": C's dump holds an Ellipse");
        assertEquals(w.size(), g.size(), name + ": Ellipses");
        Fit fit = refit();
        SkyFrame icrs = new SkyFrame();
        for (int i = 0; i < w.size(); i++) {
            double[][] pw = w.get(i);
            double[][] pg = g.get(i);
            assertArrayEquals(fit.cen, pg[0], name + ": the refit centre is the dumped one");
            assertArrayEquals(fit.pa, pg[1], name + ": the refit primary axis end is the dumped one");
            assertArrayEquals(fit.pb, pg[2], name + ": the refit secondary axis end is the dumped one");
            double mx = icrs.distance(pw[0], pw[1]);
            assertEquals(
                    mx, icrs.distance(pg[0], pg[1]), 2.0 * DMX + 2.0 * EPS_D, name + ": Ellipse " + i + " primary");
            double[][] rw = icrs.resolvePoints(pw[0], pw[1], fit.mesh, fit.mesh[0].length);
            double tol = firstOrderBound(rw, mx)
                    + ERROR_RADIUS * Math.abs(curvature(rw) - curvature(fit.ps2)) / 6.0
                    + platformBound(fit)
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
        if (SKY_CASES.contains(name)) {
            assertTrue(CValues.nativeStringsWithin(g, w, SKY_TOL), name + ": dump\n--- got\n" + g + "--- C\n" + w);
        } else {
            assertEquals(w, g, name + ": dump");
        }
    }

    /** The case of {@code CASES} called {@code name}. */
    private static JsonNode testCase(String name) {
        for (JsonNode c : DATA.get("CASES")) {
            if (c.get(0).textValue().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no case " + name);
    }

    /** Transforms the two test points of case {@code c} with {@code stc} and compares them with C's. */
    private static void assertPoints(String name, Region stc, JsonNode c) {
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
    }

    /** The text a Channel with Full = 1 writes for {@code obj}. */
    private static String fullDump(AstObject obj) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Channel ch = new Channel(null, out);
        ch.setFull(1);
        try {
            ch.write(obj);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void stcMatchesC() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            run++;
            Region stc = built(name);
            assertPoints(name, stc, c);
            assertDump(name, ChannelText.dump(stc), c.get(1).textValue());
            assertEquals(c.get(4).booleanValue(), stc.getBounded(), name + ": bounded");
        }
        assertEquals(NCASES, run, "cases run");
    }

    @Test
    void fullDumpsMatchC() {
        int run = 0;
        for (JsonNode c : DATA.get("FULL_DUMPS")) {
            String name = c.get(0).textValue();
            run++;
            Region stc = built(name);
            assertPoints(name, stc, testCase(name));
            assertDump(name, fullDump(stc), c.get(1).textValue());
        }
        assertEquals(NCASES, run, "cases run");
    }

    @Test
    void cDumpsReadBackAsCReadsThem() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            run++;
            Region region = (Region) ChannelText.read(c.get(1).textValue());
            assertEquals(c.get(2).textValue(), ChannelText.dump(region), name);
        }
        assertEquals(NCASES, run, "cases run");
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
