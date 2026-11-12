package uk.ac.starlink.ast.internal.wcslib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * The test of Rust {@code src/wcslib/golden_tests.rs}: every projection checked against values
 * captured from the wcslib C it was ported from ({@code src/test/resources/golden/README.md}),
 * at points that include the poles, the multiples of 45 degrees, the edges of the quad-cube and
 * HEALPix facets, and points outside each projection's domain.
 *
 * <p>Statuses are compared exactly. The values were captured with macOS aarch64's libm and the
 * projections take their elementary functions from {@link Math}, whose last bit can differ from
 * it, so outputs are compared with {@link CValues#withinLibmRounding}; two NaN outputs compare
 * equal whatever their sign and payload. At the points of {@link #WIDER}, a formula amplifies a
 * one-ULP difference of a libm function far beyond that; each is listed with its measured size
 * and cause, and its tolerance is an upper bound on the difference. The sizes were measured with
 * the {@code Math} of one runtime (aarch64 HotSpot), which is not C's libm and whose last bits
 * differ between platforms and JIT compilers, so on another runtime a listed point may come within
 * libm rounding; that is not a failure, only a difference beyond the bound is.
 */
class WcslibGoldenTest {

    private static final double BAD = -Double.MAX_VALUE;

    /** A point compared with a wider tolerance, and why. */
    private static final class Wider {
        /** The largest difference allowed, relative to the larger of 1 and the C value; null to compare the status only. */
        final Double tol;

        /** The measured difference and its cause. */
        final String cause;

        Wider(Double tol, String cause) {
            this.tol = tol;
            this.cause = cause;
        }
    }

    /** The points compared with a wider tolerance, keyed as {@link #key} names them. */
    private static final Map<String, Wider> WIDER = new HashMap<>();

    private static void addWider(String key, Double tol, String cause) {
        WIDER.put(key, new Wider(tol, cause));
    }

    static {
        String nearSouthPole = "fwd(134.5290029874253, -89.85509321152901)";
        String sinNearPole = "Math.sin of the latitude is 1 ULP from Apple's libm and 1 - |sin| cancels to 3.2e-6";
        addWider("STG [-] " + nearSouthPole, 1e-10, "3.5e-11 relative: " + sinNearPole);
        addWider("HPX [2:1:3ff0000000000000,2:2:3ff0000000000000] " + nearSouthPole, 1e-10, "9e-12: " + sinNearPole);
        addWider("HPX [2:1:3ff0000000000000,2:2:4008000000000000] " + nearSouthPole, 1e-10, "1.7e-11: " + sinNearPole);
        addWider("HPX [2:1:3ff0000000000000,2:2:4014000000000000] " + nearSouthPole, 1e-10, "1.7e-11: " + sinNearPole);
        addWider("QSC [-] " + nearSouthPole, 1e-11, "2.2e-12: " + sinNearPole);
        addWider(
                "COE [2:1:c03e000000000000,2:2:404e000000000000] " + nearSouthPole,
                1e-11,
                "2.8e-12: " + sinNearPole + " in the radicand w4 - w5 sin(theta)");
        String nearNorthPole = "fwd(-131.0184683466427, 89.99995572455052)";
        addWider(
                "SZP [2:1:c008000000000000,2:2:4069000000000000,2:3:4046800000000000] " + nearNorthPole,
                1e-13,
                "1.1e-14 absolute: Math.sin of the latitude is 1 ULP from Apple's libm and 1 - sin, 4.4e-10,"
                        + " is scaled by 114");
        String molCause = "5.2e-14 absolute: Math.sin of 1.5707955540 is 1 ULP from Apple's libm, and x is"
                + " proportional to the cosine of an angle near pi/2";
        addWider("MOL [-] " + nearNorthPole, 1e-12, molCause);
        addWider("MOL [2:1:4008000000000000] " + nearNorthPole, 1e-12, molCause);
        String parPoint = "fwd(-81.72604780129319, 89.99999821174198)";
        String parCause = "1.8e-11 absolute: Math.sin of 0.52359876519 is 1 ULP from Apple's libm, and 1 - 4 s^2"
                + " cancels near s = 0.5";
        addWider("PAR [-] " + parPoint, 1e-10, parCause);
        addWider("PAR [2:1:4008000000000000] " + parPoint, 1e-10, parCause);
        addWider(
                "AIR [2:1:c05679999999999a] rev(2.176528807027965, -39.65533700189065)",
                1e-13,
                "2.8e-14 absolute: Math.log in the bisection is 1 ULP from Apple's libm, which moves the final"
                        + " cos(xi) by 1 ULP, and 90 - 2 acosd(cos xi) cancels to near zero");
        addWider(
                "COP [2:1:403e000000000000,2:2:404e000000000000] rev(-1.8252000837883278E-8, 49.61960057639072)",
                1e-6,
                "2.8e-7 relative in phi: Math.tan(pi/6) in the set-up is 1 ULP from Apple's libm, and the"
                        + " point is 1e-8 from the cone's apex, where w2 - y cancels");
        String cooApex = "3e-8 relative in phi: COO's set-up constant w0, a ratio of logarithms of tangents, is"
                + " 1 to 9 ULP from C's through 1-ULP differences of Math.tan, and the point is within 1e-6 of"
                + " the cone's apex";
        addWider(
                "COO [2:1:4046800000000000,2:2:4014000000000000] rev(7.434058429952755E-7, 57.004897957807785)",
                1e-7,
                cooApex);
        addWider(
                "COO [2:1:4046800000000000,2:2:4034000000000000] rev(4.84679979589505E-7, 52.636885357310106)",
                1e-7,
                cooApex);
        addWider(
                "COO [2:1:403e000000000000,2:2:403e000000000000] rev(6.227593640067684E-5, 81.5282416812931)",
                1e-9,
                "1e-10 relative in phi: " + cooApex.substring(cooApex.indexOf(':') + 2));
        addWider(
                "COO [2:1:c024000000000000,2:2:4014000000000000] rev(-303.75, -180.00000000000003)",
                1e-12,
                "3.6e-13 absolute in theta: COO's set-up constant w0 is a few ULP from C's (Math.tan and"
                        + " Math.log) and a power of the radius amplifies it");
        addWider(
                "COO [2:1:4046800000000000,2:2:4014000000000000] fwd(89.99999999999999, -15.000000000000002)",
                1e-13,
                "3.5e-14 absolute in y: COO's set-up constant w0 is a few ULP from C's (Math.tan and Math.log)");
        String cooHuge = "fwd(-1.7976931348623157E308, 0.0)";
        String hugeCause = "outputs not compared: the native longitude is -DBL_MAX, so w0 phi is near 1e308"
                + " degrees, and the few ULP by which w0 differs from C's change it by about 1e292 degrees,"
                + " giving an unrelated sine and cosine";
        for (String set : new String[] {
            "[2:1:c04e000000000000,2:2:4014000000000000]",
            "[2:1:c04e000000000000,2:2:4034000000000000]",
            "[2:1:c024000000000000,2:2:4014000000000000]",
            "[2:1:4046800000000000,2:2:4014000000000000]",
            "[2:1:c046800000000000,2:2:4034000000000000]"
        }) {
            addWider("COO " + set + " " + cooHuge, null, hugeCause);
        }
    }

    private static double bits(String tok) {
        if ("nan".equals(tok)) {
            return Double.NaN;
        }
        return Double.longBitsToDouble(Long.parseUnsignedLong(tok, 16));
    }

    private static String hex(double v) {
        return String.format("%016x", Double.doubleToRawLongBits(v));
    }

    private static String key(String set, boolean fwd, double u, double v) {
        return set + (fwd ? " fwd(" : " rev(") + u + ", " + v + ")";
    }

    private static boolean libmClose(double got, double want) {
        return (Double.isNaN(got) && Double.isNaN(want)) || CValues.withinLibmRounding(got, want);
    }

    private static double scaled(double got, double want) {
        double d = Math.abs(got - want) / Math.max(1.0, Math.abs(want));
        return Double.isNaN(d) ? Double.POSITIVE_INFINITY : d;
    }

    private static List<String> lines() {
        try (InputStream in = WcslibGoldenTest.class.getResourceAsStream("/golden/projections.tsv")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1 << 16];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            List<String> out = new ArrayList<>();
            for (String l : CValues.lines(new String(buf.toByteArray(), StandardCharsets.UTF_8))) {
                if (!l.isEmpty() && !l.startsWith("#")) {
                    out.add(l);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The comparison counts for one projection. */
    private static final class Tally {
        int forwardOk;
        int reverseOk;
    }

    /** The state of one run through the file. */
    private static final class Run {
        final List<String> failures = new ArrayList<>();
        final Set<String> widerSeen = new HashSet<>();
        final Map<String, Tally> tally = new TreeMap<>();
        final double[] out = new double[2];

        void check(Prj prj, String code, String set, boolean fwd, double u, double v, String[] t, int at) {
            int want = Integer.parseInt(t[at]);
            Prj.Status status = fwd ? prj.fwdRaw(u, v, out) : prj.revRaw(u, v, out);
            String what = key(set, fwd, u, v);
            if (status.code() != want) {
                failures.add(what + ": status " + status.code() + ", expected " + want);
                return;
            }
            if (want != 0) {
                return;
            }
            Tally tl = tally.computeIfAbsent(code, k -> new Tally());
            if (fwd) {
                tl.forwardOk++;
            } else {
                tl.reverseOk++;
            }
            double w0 = bits(t[at + 1]);
            double w1 = bits(t[at + 2]);
            boolean close = libmClose(out[0], w0) && libmClose(out[1], w1);
            Wider wider = WIDER.get(what);
            String values = ": " + out[0] + " " + out[1] + ", expected " + w0 + " " + w1;
            if (wider == null) {
                if (!close) {
                    failures.add(what + values);
                }
                return;
            }
            widerSeen.add(what);
            // A listed point within libm rounding passes: whether it needs its wider bound depends
            // on the runtime's Math (on x86-64 most of them do not).
            if (!close && wider.tol != null && Math.max(scaled(out[0], w0), scaled(out[1], w1)) > wider.tol) {
                failures.add(what + " is beyond its wider tolerance " + wider.tol + " (" + wider.cause + ")" + values);
            }
        }
    }

    @Test
    void projectionsMatchTheCapturedWcslibValues() {
        List<String> lines = lines();
        List<double[]> fwdInputs = new ArrayList<>();
        List<double[]> revInputs = new ArrayList<>();
        int k = 0;
        for (; k < lines.size(); k++) {
            String[] t = CValues.splitWhitespace(lines.get(k));
            if ("FI".equals(t[0])) {
                fwdInputs.add(new double[] {bits(t[1]), bits(t[2])});
            } else if ("RI".equals(t[0])) {
                revInputs.add(new double[] {bits(t[1]), bits(t[2])});
            } else {
                break;
            }
        }
        assertTrue(!fwdInputs.isEmpty() && !revInputs.isEmpty());

        Run run = new Run();
        while (k < lines.size()) {
            String[] t = CValues.splitWhitespace(lines.get(k++));
            assertEquals("S", t[0], "expected a set header");
            WcsProjection proj = WcsProjection.fromCode(t[1]);
            int stride = Integer.parseInt(t[2]);
            boolean tpnTan = "1".equals(t[3]);
            String set = t[1] + " [" + t[4] + "]";
            double[][] params = {new double[0], new double[0]};
            if (!"-".equals(t[4])) {
                for (String pv : t[4].split(",", -1)) {
                    String[] f = pv.split(":", -1);
                    int axis = "2".equals(f[0]) ? 0 : 1;
                    int m = Integer.parseInt(f[1]);
                    double[] p = params[axis];
                    if (m >= p.length) {
                        int old = p.length;
                        p = Arrays.copyOf(p, m + 1);
                        Arrays.fill(p, old, m + 1, BAD);
                        params[axis] = p;
                    }
                    p[m] = bits(f[2]);
                }
            }
            Prj prj = Prj.create(proj, params[0], params[1], tpnTan);

            String[] a = CValues.splitWhitespace(lines.get(k++));
            assertEquals("A", a[0], set);
            for (int axis = 0; axis < 2; axis++) {
                for (int m = 0; m <= 6; m++) {
                    String want = a[1 + axis * 7 + m];
                    double got = prj.getParam(axis, m);
                    assertTrue(
                            ("nan".equals(want) && Double.isNaN(got)) || want.equals(hex(got)),
                            set + ": parameter " + axis + "/" + m + " is " + hex(got) + ", expected " + want);
                }
            }

            String[] r = CValues.splitWhitespace(lines.get(k));
            if ("E".equals(r[0])) {
                // A failed set-up gives its status at the first point that reaches the projection.
                k++;
                assertEquals(
                        Integer.parseInt(r[1]), prj.fwdRaw(0.0, 0.0, run.out).code(), set + ": forward set-up");
                assertEquals(
                        Integer.parseInt(r[2]), prj.revRaw(0.0, 0.0, run.out).code(), set + ": reverse set-up");
                assertNotEquals(0, Integer.parseInt(r[1]), set + ": set-up");
                continue;
            }
            for (int i = 0; i < fwdInputs.size(); i += stride) {
                String[] x = CValues.splitWhitespace(lines.get(k++));
                assertEquals("F", x[0], set + ": layout");
                run.check(prj, t[1], set, true, fwdInputs.get(i)[0], fwdInputs.get(i)[1], x, 1);
            }
            for (int i = 0; i < revInputs.size(); i += stride) {
                String[] x = CValues.splitWhitespace(lines.get(k++));
                assertEquals("R", x[0], set + ": layout");
                run.check(prj, t[1], set, false, revInputs.get(i)[0], revInputs.get(i)[1], x, 1);
            }
            while (k < lines.size() && lines.get(k).startsWith("X ")) {
                String[] x = CValues.splitWhitespace(lines.get(k++));
                run.check(prj, t[1], set, false, bits(x[1]), bits(x[2]), x, 3);
            }
        }

        // Every projection but the deprecated aliases is covered, with points that project in
        // each direction.
        for (WcsProjection p : WcsProjection.values()) {
            if (p == WcsProjection.NCP || p == WcsProjection.GLS || p == WcsProjection.UNKNOWN) {
                continue;
            }
            Tally tl = run.tally.get(p.code());
            assertTrue(tl != null && tl.forwardOk > 0 && tl.reverseOk > 0, p.code() + " has no compared outputs");
        }
        Set<String> unseen = new HashSet<>(WIDER.keySet());
        unseen.removeAll(run.widerSeen);
        assertTrue(unseen.isEmpty(), "WIDER entries not in the file: " + unseen);
        assertTrue(
                run.failures.isEmpty(), run.failures.size() + " results differ:\n" + String.join("\n", run.failures));
    }
}
