package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * The lines {@code probes/region_compound_probe.c} writes, made here from the Java classes so that
 * a case's text can be compared with C's whole (with {@link RegionProbeText#check(Map, String,
 * String)}). Each method gives the line the probe's function of the same name writes; values are
 * written as C's {@code %.17g} writes them.
 */
final class CompoundProbe {

    /** The cases of {@code probes/region_compound_probe.c}. */
    static final Map<String, String> PROBE = ProbeOutput.load("region_compound_probe");

    static final double BAD = AstObject.AST__BAD;

    private CompoundProbe() {}

    /** Checks a case of the probe exactly. */
    static void check(String name, String actual) {
        RegionProbeText.check(PROBE, name, actual);
    }

    /** Checks a case of the probe, numbers to within {@code tol} of C's. */
    static void checkClose(String name, String actual, double tol) {
        RegionProbeText.checkClose(PROBE, name, actual, tol);
    }

    static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    static Frame frame(int naxes) {
        return new Frame(naxes);
    }

    /** A Box of {@code centre.length} axes given by its centre and a corner. */
    static Box boxc(double[] centre, double[] corner) {
        return new Box(new Frame(centre.length), 0, centre, corner, null);
    }

    static Box box2(double x1, double y1, double x2, double y2) {
        return new Box(new Frame(2), 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    static Box box3(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Box(new Frame(3), 1, new double[] {x1, y1, z1}, new double[] {x2, y2, z2}, null);
    }

    static Circle circle2(double cx, double cy, double r) {
        return new Circle(new Frame(2), 1, new double[] {cx, cy}, new double[] {r}, null);
    }

    static Interval interval1(double lo, double hi) {
        return new Interval(new Frame(1), new double[] {lo}, new double[] {hi}, null);
    }

    static <T extends Region> T negatedCopy(T r) {
        @SuppressWarnings("unchecked")
        T n = (T) r.copy();
        n.negate();
        return n;
    }

    static CmpRegion cmp(Region a, Region b, int oper) {
        return new CmpRegion(a, b, oper);
    }

    /** The probe's Prism of the Rust tests: the Circle of radius 5 at the origin and the Interval 2 to 8. */
    static Prism cylinder() {
        return new Prism(circle2(0.0, 0.0, 5.0), interval1(2.0, 8.0));
    }

    /** The values of {@code np} points given point by point, as {@code [nc][np]}. */
    static double[][] columns(int nc, double... pts) {
        int np = pts.length / nc;
        double[][] in = new double[nc][np];
        for (int i = 0; i < np; i++) {
            for (int j = 0; j < nc; j++) {
                in[j][i] = pts[i * nc + j];
            }
        }
        return in;
    }

    static String tran(Region r, int nc, double... pts) {
        return RegionProbeText.tran(r, nc, pts);
    }

    /** The values the Region gives the points, point by point. */
    static String tranValues(Region r, int nc, boolean forward, double... pts) {
        double[][] in = columns(nc, pts);
        int np = in[0].length;
        double[][] out = r.transform(in, np, forward);
        StringBuilder sb = new StringBuilder("values");
        for (int i = 0; i < np; i++) {
            for (int j = 0; j < nc; j++) {
                sb.append(' ').append(g(out[j][i]));
            }
        }
        return sb.append('\n').toString();
    }

    static String bounds(Region r) {
        return RegionProbeText.bounds(r);
    }

    static String bounded(Region r) {
        return RegionProbeText.bounded(r);
    }

    static String negated(Region r) {
        return "negated " + (r.getNegated() ? 1 : 0) + "\n";
    }

    static String naxes(Frame f) {
        return "naxes " + f.getNaxes() + "\n";
    }

    static String fill(Region r) {
        return "fill " + g(r.getFillFactor()) + "\n";
    }

    static String unc(Region r, boolean def) {
        return RegionProbeText.unc(r, def);
    }

    static String points(Region r) {
        double[][] p = r.getRegionPoints();
        return "points " + (p.length == 0 ? 0 : p[0].length) + "\n";
    }

    /** The mesh ({@code surface}) or grid line of the points given, {@code [nc][np]}. */
    static String meshText(boolean surface, double[][] m) {
        int n = m[0].length;
        StringBuilder sb = new StringBuilder(surface ? "mesh " : "grid ").append(n);
        for (double[] axis : m) {
            for (int i = 0; i < n; i++) {
                sb.append(' ').append(g(axis[i]));
            }
        }
        return sb.append('\n').toString();
    }

    /**
     * The boundary mesh ({@code surface}) or the grid inside the Region, in its current Frame,
     * normalised as C's {@code astGetRegionMesh} gives it.
     */
    static String mesh(Region r, boolean surface) {
        double[][] m = surface ? r.regMesh() : r.regGrid();
        return meshText(surface, r.normalPoints(m, m[0].length, true));
    }

    static String overlap(Region a, Region b) {
        return RegionProbeText.overlap(a, b);
    }

    static String mask(Region r, boolean inside) {
        return RegionProbeText.mask(r, null, inside, new int[] {0, 0}, new int[] {5, 4});
    }

    /** The count {@link Region#maskD} returns for a grid of zeros. */
    static String maskcount(Region r, Mapping map, boolean inside, int[] lbnd, int[] ubnd, double val) {
        int n = 1;
        for (int i = 0; i < lbnd.length; i++) {
            n *= ubnd[i] - lbnd[i] + 1;
        }
        int count = r.maskD(map, inside, lbnd.length, lbnd, ubnd, new double[n], val);
        return "maskcount " + count + "\n";
    }

    static String pins(Region r, double[][] pts, Region u) {
        return CircleTest.pins(r, pts, u);
    }

    /** The Mappings of a parallel, then a series, decomposition ({@code astMapList}). */
    static String maplist(Mapping m) {
        List<Mapping> maps = new ArrayList<>();
        List<Boolean> inverts = new ArrayList<>();
        m.mapList(false, false, maps, inverts);
        List<Mapping> smaps = new ArrayList<>();
        m.mapList(true, false, smaps, new ArrayList<>());
        StringBuilder sb =
                new StringBuilder("maplist ").append(maps.size()).append(' ').append(smaps.size());
        for (Mapping map : maps) {
            sb.append(' ').append(map.astClass());
        }
        return sb.append('\n').toString();
    }

    static String decompose(Mapping m) {
        boolean[] series = new boolean[1];
        Mapping[] parts = m.decompose(series, new boolean[2]);
        return "decompose " + (series[0] ? 1 : 0) + " " + parts[0].astClass() + " "
                + (parts.length > 1 ? parts[1].astClass() : "none") + "\n";
    }

    static String pick(Frame f, int... axes) {
        Frame p = f.pickAxes(axes.length, axes, null);
        String s = "pick " + p.astClass() + " " + p.getNaxes() + "\n";
        if (p instanceof Region) {
            s += bounds((Region) p);
        }
        return s;
    }

    static String klass(AstObject o) {
        return "class " + o.astClass() + "\n";
    }
}
