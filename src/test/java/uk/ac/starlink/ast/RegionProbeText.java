package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.function.Executable;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * The lines {@code probes/region_null_interval_pointlist_probe.c} writes, made here from the
 * Java classes so that a case's text can be compared with C's whole. Each method gives the line
 * or lines the probe's function of the same name writes; values are written as C's {@code %.17g}
 * writes them.
 */
final class RegionProbeText {

    /** The probe's cases. */
    static final Map<String, String> PROBE = ProbeOutput.load("region_null_interval_pointlist_probe");

    private RegionProbeText() {}

    /**
     * Checks a case: {@code actual} must be C's text for {@code name}, less the line C's error
     * system writes naming the probe's own source line ({@code !! AST: Error in routine ...}).
     */
    static void check(String name, String actual) {
        String want = PROBE.get(name);
        assertNotNull(want, "no probe case " + name);
        StringBuilder sb = new StringBuilder();
        for (String line : want.split("\n", -1)) {
            if (!line.isEmpty() && !line.startsWith("!! ")) {
                sb.append(line).append('\n');
            }
        }
        assertEquals(sb.toString(), actual, name);
    }

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    /**
     * For each point, given point by point with {@code naxes} values each, 1 if the Region leaves
     * it good and 0 if it makes it bad.
     */
    static String tran(Region r, int naxes, double... pts) {
        int np = pts.length / naxes;
        double[][] in = new double[naxes][np];
        for (int i = 0; i < np; i++) {
            for (int j = 0; j < naxes; j++) {
                in[j][i] = pts[i * naxes + j];
            }
        }
        double[][] out = r.transform(in, np, true);
        StringBuilder sb = new StringBuilder("tran");
        for (int i = 0; i < np; i++) {
            boolean good = true;
            for (int j = 0; j < naxes; j++) {
                if (out[j][i] == AstObject.AST__BAD) {
                    good = false;
                }
            }
            sb.append(good ? " 1" : " 0");
        }
        return sb.append('\n').toString();
    }

    /**
     * The values the Region gives each 2-D point, given point by point, and whether {@link
     * Region#pointInRegion} finds each inside.
     */
    static String tranValues(Region r, double... pts) {
        int np = pts.length / 2;
        double[][] in = new double[2][np];
        for (int i = 0; i < np; i++) {
            in[0][i] = pts[2 * i];
            in[1][i] = pts[2 * i + 1];
        }
        double[][] out = r.transform(in, np, true);
        StringBuilder sb = new StringBuilder("values");
        for (int i = 0; i < np; i++) {
            sb.append(' ').append(g(out[0][i])).append(' ').append(g(out[1][i]));
        }
        sb.append("\npir");
        for (int i = 0; i < np; i++) {
            sb.append(r.pointInRegion(new double[] {pts[2 * i], pts[2 * i + 1]}) ? " 1" : " 0");
        }
        return sb.append('\n').toString();
    }

    /** The lower then upper bound on each axis. */
    static String bounds(Region r) {
        double[][] b = r.getRegionBounds();
        StringBuilder sb = new StringBuilder("bounds");
        for (double v : b[0]) {
            sb.append(' ').append(g(v));
        }
        for (double v : b[1]) {
            sb.append(' ').append(g(v));
        }
        return sb.append('\n').toString();
    }

    /** The Bounded attribute. */
    static String bounded(Region r) {
        return "bounded " + (r.getBounded() ? 1 : 0) + "\n";
    }

    /** The class and Negated of the uncertainty, then its bounds, or "none". */
    static String unc(Region r, boolean def) {
        Region u = r.getUnc(def);
        if (u == null) {
            return "unc none\n";
        }
        return "unc " + u.astClass() + " " + (u.getNegated() ? 1 : 0) + "\n" + bounds(u);
    }

    /** The number of defining points, then each axis's values. */
    static String points(Region r) {
        double[][] pts = r.getRegionPoints();
        int n = pts.length == 0 ? 0 : pts[0].length;
        StringBuilder sb = new StringBuilder("points ").append(n);
        for (double[] axis : pts) {
            for (int i = 0; i < n; i++) {
                sb.append(' ').append(g(axis[i]));
            }
        }
        return sb.append('\n').toString();
    }

    /** {@code a.overlap(b)}, then {@code b.overlap(a)}. */
    static String overlap(Region a, Region b) {
        int ab = a.overlap(b);
        int ba = b.overlap(a);
        return "overlap " + ab + " " + ba + "\n";
    }

    /**
     * {@link Region#maskD} of a copy of {@code r0} with the value 9, on an array holding 0, 1, 2
     * in turn: the count, then the array.
     */
    static String mask(Region r0, Mapping map, boolean inside, int[] lbnd, int[] ubnd) {
        Region r = (Region) r0.copy();
        int n = 1;
        for (int i = 0; i < lbnd.length; i++) {
            n *= ubnd[i] - lbnd[i] + 1;
        }
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = i % 3;
        }
        int count = r.maskD(map, inside, lbnd.length, lbnd, ubnd, d, 9.0);
        StringBuilder sb = new StringBuilder("mask ").append(count);
        for (double v : d) {
            sb.append(' ').append(g(v));
        }
        return sb.append('\n').toString();
    }

    /** The message lines and status of the AstException {@code e} throws, as C reports them. */
    static String error(Executable e) {
        AstException ex = assertThrows(AstException.class, e);
        StringBuilder sb = new StringBuilder();
        for (String line : ex.getMsgText().split("\n", -1)) {
            sb.append("!  ").append(line).append('\n');
        }
        return sb.append("status ").append(ex.getStatus()).append('\n').toString();
    }
}
