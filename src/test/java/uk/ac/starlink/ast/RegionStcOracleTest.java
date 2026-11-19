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
 * Circle of radius {@code 1e-4} that the Stc re-expresses in ICRS as the Ellipse that best fits its
 * mapped boundary mesh (ellipse.c {@code astBestEllipse}; {@code ERROR_ELLIPSE_LICENSED} in the Rust
 * test). The Ellipse's three defining points are its centre and the ends of its two axes. The
 * centre is the Circle's centre mapped into ICRS, through the same chain of functions as the
 * Circle's own points, and is compared within {@link #SKY_TOL}. The ends of the axes are not
 * determined by the values: the fit takes the direction of the primary axis from the mesh point
 * farthest from the centre and a parabola through it and its neighbours, and on the mapped
 * Circle, all but circular, the distances of the mesh points from the centre differ by less than
 * {@code 1e-13}, so a difference of a few ulps in the mesh moves that direction anywhere around
 * the centre. No tolerance on those points short of the radius itself follows from the
 * computation (this machine's {@code java.lang.Math} puts them about {@code 2e-5} away from C's,
 * as the Rust test finds of other platforms), and one of the radius tests nothing, so {@link
 * #withoutEllipseAxisEnds} leaves out their values, as the Rust test leaves out the Ellipse's values
 * off the platform its C values were taken on, as phase 7 compares an Ellipse fitted to a Circle,
 * and as {@code docs/rust-c-differences.md} records. What the axis ends do determine, the two
 * half-lengths, is compared instead ({@link #assertHalfLengths}): each is the distance from the
 * centre of a point the fit places on or near the mesh, so it lies within the spread of the mesh
 * points' distances from the centre, which C's own fit shows by its two half-lengths, {@code 5.2e-14}
 * apart, beside rounding of order {@code 1e-17} in distances of {@code 1e-4}; {@link
 * #HALF_LENGTH_TOL} is twice that spread, {@code 1e-13}, a part in {@code 1e9} of the radius.
 */
class RegionStcOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_stc");

    /** The tolerance on the numbers of a dump in a SkyFrame (see the class description). */
    private static final double SKY_TOL = 1.0e-12;

    /** The cases of {@code CASES} run here: those of the StcResourceProfile class. */
    private static final List<String> CASES_RUN =
            Arrays.asList("plain", "negated", "sky", "mapped", "from_mapped", "mapped_simplified");

    /** The tolerance on the half-lengths of the fitted Error Ellipse (see the class description). */
    private static final double HALF_LENGTH_TOL = 1.0e-13;

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

    /** Compares the half-lengths of the Ellipses in two dumps (see the class description). */
    private static void assertHalfLengths(String name, String got, String want) {
        List<double[][]> g = ellipsePoints(got);
        List<double[][]> w = ellipsePoints(want);
        assertFalse(w.isEmpty(), name + ": C's dump holds an Ellipse");
        assertEquals(w.size(), g.size(), name + ": Ellipses");
        SkyFrame icrs = new SkyFrame();
        for (int i = 0; i < w.size(); i++) {
            for (int axis = 1; axis <= 2; axis++) {
                assertEquals(
                        icrs.distance(w.get(i)[0], w.get(i)[axis]),
                        icrs.distance(g.get(i)[0], g.get(i)[axis]),
                        HALF_LENGTH_TOL,
                        name + ": Ellipse " + i + " half-length " + axis);
            }
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
