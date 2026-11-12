package uk.ac.starlink.ast.internal.pal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.erfa.Cal2jd;
import uk.ac.starlink.ast.internal.erfa.D2tf;
import uk.ac.starlink.ast.internal.erfa.Jd2cal;
import uk.ac.starlink.ast.internal.erfa.Pn;
import uk.ac.starlink.ast.internal.erfa.Tf2d;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.KernelInputs;

/**
 * The tests of Rust {@code src/pal/golden_tests.rs}: every PAL routine AST uses, checked against
 * values captured from the C it was ported from ({@code src/test/resources/golden/README.md}).
 *
 * <p>The values were captured with macOS aarch64's libm, and the routines take their elementary
 * functions from {@link Math}, whose last bit can differ from it. The routines that call no
 * elementary function other than the square root ({@link #EXACT_ROOTS}) are compared bit for bit,
 * as the Rust test compares every routine; the others are compared with {@link
 * CValues#withinLibmRounding}. Two NaN outputs compare equal whatever their sign and payload.
 */
class PalGoldenTest {

    private static final double BAD = -Double.MAX_VALUE;

    /** The orders {@code deuler} is called with; a line holds an index into this list. */
    private static final String[] EULER_ORDERS = {
        "", "x", "zyx", "ZXZ", "xyzx", "abc", "z1y", "XYZ", "123", "321", "zy", "Zy3", "y?z", "zzz", "z\u0000y", "zyxz"
    };

    /** Every root the file must cover. */
    private static final String[] ROOTS = {
        "epj", "epj2d", "epb", "epb2d", "dcs2c", "dcc2s", "dmxv", "dimxv", "dmxm", "dvxv", "dvdv", "dvn", "dmat",
        "galeq", "eqgal", "galsup", "supgal", "fk45z", "fk54z", "fk5hz", "hfk5z", "prec", "prebn", "ecmat", "deuler",
        "addet", "subet", "mappa", "ampqk", "mapqkz", "drange", "dranrm", "dsep", "dbear", "evp", "geoc", "dav2m",
        "dat", "gmst", "eqeqx", "gd2gc", "gc2gd", "pvobs", "rvgalc", "rvlg", "rvlsrd", "rvlsrk", "djcl", "cldj",
        "dd2tf", "dtf2d"
    };

    /**
     * The roots whose arithmetic is IEEE operations, {@code fmod}, {@code floor}, {@code ceil} and
     * {@code sqrt} only, which Java computes exactly as C does, so their results must match bit
     * for bit.
     */
    private static final Set<String> EXACT_ROOTS = new HashSet<>(Arrays.asList(
            "epj", "epj2d", "epb", "epb2d", "dmxv", "dimxv", "dmxm", "dvxv", "dvdv", "dvn", "dmat", "drange", "dranrm",
            "dat", "djcl", "cldj", "dd2tf", "dtf2d"));

    private static double bits(String tok) {
        if ("nan".equals(tok)) {
            return Double.NaN;
        }
        return Double.longBitsToDouble(Long.parseUnsignedLong(tok, 16));
    }

    private static boolean sameBitsOrBothNan(double a, double b) {
        return (Double.isNaN(a) && Double.isNaN(b)) || Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static double[][] m3(double[] x, int off) {
        return new double[][] {
            {x[off], x[off + 1], x[off + 2]}, {x[off + 3], x[off + 4], x[off + 5]}, {x[off + 6], x[off + 7], x[off + 8]}
        };
    }

    private static double[] flat(double[][] m) {
        double[] out = new double[9];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(m[i], 0, out, 3 * i, 3);
        }
        return out;
    }

    private static double[] v3(double[] x, int off) {
        return Arrays.copyOfRange(x, off, off + 3);
    }

    /**
     * Runs the port's routine {@code root} on {@code x}, in the layout the file records: the
     * doubles of the inputs in argument order (an integer as its value, a matrix by rows) and the
     * outputs in the same way. A status is its C value, so a routine that fails gives only the
     * status where C leaves its outputs unwritten.
     */
    // MODERN-JAVA(21): switch expression on the root name
    private static double[] run(String root, double[] x) {
        switch (root) {
            case "epj":
                return new double[] {Pal.epj(x[0])};
            case "epj2d":
                return new double[] {Pal.epj2d(x[0])};
            case "epb":
                return new double[] {Pal.epb(x[0])};
            case "epb2d":
                return new double[] {Pal.epb2d(x[0])};
            case "dcs2c":
                return Pal.dcs2c(x[0], x[1]);
            case "dcc2s":
                return Pal.dcc2s(v3(x, 0));
            case "dmxv":
                return Pal.dmxv(m3(x, 0), v3(x, 9));
            case "dimxv":
                return Pal.dimxv(m3(x, 0), v3(x, 9));
            case "dmxm":
                return flat(Pal.dmxm(m3(x, 0), m3(x, 9)));
            case "dvxv":
                return Pal.dvxv(v3(x, 0), v3(x, 3));
            case "dvdv":
                return new double[] {Pal.dvdv(v3(x, 0), v3(x, 3))};
            case "dvn": {
                Pn.Result r = Pal.dvn(v3(x, 0));
                return new double[] {r.unit[0], r.unit[1], r.unit[2], r.modulus};
            }
            case "dmat": {
                int n = (int) x[0];
                double[] a = Arrays.copyOfRange(x, 1, 1 + n * n);
                double[] y = Arrays.copyOfRange(x, 1 + n * n, 1 + n * n + n);
                double d = Pal.dmatDet(n, a, y);
                double[] out = new double[2 + a.length + y.length];
                out[0] = d != 0.0 ? 1.0 : 0.0;
                out[1] = d;
                System.arraycopy(a, 0, out, 2, a.length);
                System.arraycopy(y, 0, out, 2 + a.length, y.length);
                return out;
            }
            case "galeq":
                return Pal.galeq(x[0], x[1]);
            case "eqgal":
                return Pal.eqgal(x[0], x[1]);
            case "galsup":
                return Pal.galsup(x[0], x[1]);
            case "supgal":
                return Pal.supgal(x[0], x[1]);
            case "fk45z":
                return Pal.fk45z(x[0], x[1], x[2]);
            case "fk54z":
                return Pal.fk54z(x[0], x[1], x[2]);
            case "fk5hz":
                return Pal.fk5hz(x[0], x[1], x[2]);
            case "hfk5z":
                return Pal.hfk5z(x[0], x[1], x[2]);
            case "prec":
                return flat(Pal.prec(x[0], x[1]));
            case "prebn":
                return flat(Pal.prebn(x[0], x[1]));
            case "ecmat":
                return flat(Pal.ecmat(x[0]));
            case "deuler":
                return flat(Pal.deuler(EULER_ORDERS[(int) x[0]], x[1], x[2], x[3]));
            case "addet":
                return Pal.addet(x[0], x[1], x[2]);
            case "subet":
                return Pal.subet(x[0], x[1], x[2]);
            case "mappa":
                return Pal.mappa(x[0], x[1]);
            case "ampqk":
                return Pal.ampqk(x[0], x[1], Arrays.copyOfRange(x, 2, 23));
            case "mapqkz":
                return Pal.mapqkz(x[0], x[1], Arrays.copyOfRange(x, 2, 23));
            case "drange":
                return new double[] {Pal.drange(x[0])};
            case "dranrm":
                return new double[] {Pal.dranrm(x[0])};
            case "dsep":
                return new double[] {Pal.dsep(x[0], x[1], x[2], x[3])};
            case "dbear":
                return new double[] {Pal.dbear(x[0], x[1], x[2], x[3])};
            case "evp": {
                double[][] e = Pal.evp(x[0], x[1]);
                double[] out = new double[12];
                for (int i = 0; i < 4; i++) {
                    System.arraycopy(e[i], 0, out, 3 * i, 3);
                }
                return out;
            }
            case "geoc":
                return Pal.geoc(x[0], x[1]);
            case "dav2m":
                return flat(Pal.dav2m(v3(x, 0)));
            case "dat":
                return new double[] {Pal.dat(x[0])};
            case "gmst":
                return new double[] {Pal.gmst(x[0])};
            case "eqeqx":
                return new double[] {Pal.eqeqx(x[0])};
            case "gd2gc": {
                double[] v = Pal.gd2gc(x[0], x[1], x[2]);
                return v == null ? new double[] {0.0} : new double[] {1.0, v[0], v[1], v[2]};
            }
            case "gc2gd": {
                double[] v = Pal.gc2gd(v3(x, 0));
                return v == null ? new double[] {0.0} : new double[] {1.0, v[0], v[1], v[2]};
            }
            case "pvobs":
                return Pal.pvobs(x[0], x[1], x[2]);
            case "rvgalc":
                return new double[] {Pal.rvgalc(x[0], x[1])};
            case "rvlg":
                return new double[] {Pal.rvlg(x[0], x[1])};
            case "rvlsrd":
                return new double[] {Pal.rvlsrd(x[0], x[1])};
            case "rvlsrk":
                return new double[] {Pal.rvlsrk(x[0], x[1])};
            case "djcl": {
                Jd2cal.Result r = Pal.djcl(x[0]);
                return r == null ? new double[] {-1.0} : new double[] {0.0, r.iy, r.im, r.id, r.fd};
            }
            case "cldj":
                return cldj(Pal.cldj((int) x[0], (int) x[1], (int) x[2]));
            case "dd2tf": {
                D2tf.Result r = Pal.dd2tf((int) x[0], x[1]);
                return new double[] {r.sign, r.ihmsf[0], r.ihmsf[1], r.ihmsf[2], r.ihmsf[3]};
            }
            case "dtf2d":
                return dtf2d(Pal.dtf2d((int) x[0], (int) x[1], x[2]));
            default:
                fail("unknown root " + root);
                return new double[0];
        }
    }

    private static double[] cldj(Cal2jd.Result r) {
        if (r.error == Cal2jd.Cal2jdError.BAD_YEAR) {
            return new double[] {-1.0};
        } else if (r.error == Cal2jd.Cal2jdError.BAD_MONTH) {
            return new double[] {-2.0};
        } else if (r.error != null) {
            fail("unexpected error " + r.error);
            return new double[0];
        } else if (r.warning == null) {
            return new double[] {0.0, r.djm};
        } else {
            return new double[] {-3.0, r.djm};
        }
    }

    // MODERN-JAVA(21): switch expression over the warning
    private static double[] dtf2d(Tf2d.Result r) {
        double status;
        if (r.warning == null) {
            status = 0.0;
        } else {
            switch (r.warning) {
                case HOUR:
                    status = 1.0;
                    break;
                case MINUTE:
                    status = 2.0;
                    break;
                case SECOND:
                    status = 3.0;
                    break;
                default:
                    fail("unexpected warning " + r.warning);
                    return new double[0];
            }
        }
        return new double[] {status, r.days};
    }

    /** The comparison counts for one root. */
    private static final class Tally {
        int calls;
        int differ;
        int beyondLibm;
    }

    private static String readGolden() {
        try (InputStream in = PalGoldenTest.class.getResourceAsStream("/golden/erfa_pal_roots.tsv")) {
            assertNotNull(in, "golden/erfa_pal_roots.tsv");
            byte[] buf = new byte[1 << 16];
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                bytes.write(buf, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void rootsMatchTheCapturedCValues() {
        Map<String, Tally> tally = new TreeMap<>();
        List<String> shown = new ArrayList<>();
        int count = 0;
        for (String line : CValues.lines(readGolden())) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] toks = CValues.splitWhitespace(line);
            int eq = Arrays.asList(toks).indexOf("=");
            assertTrue(eq > 0, line);
            String root = toks[0];
            double[] input = new double[eq - 1];
            for (int i = 1; i < eq; i++) {
                input[i - 1] = bits(toks[i]);
            }
            double[] want = new double[toks.length - eq - 1];
            for (int i = eq + 1; i < toks.length; i++) {
                want[i - eq - 1] = bits(toks[i]);
            }
            double[] got = run(root, input);
            Tally t = tally.computeIfAbsent(root, k -> new Tally());
            t.calls++;
            boolean exact = got.length == want.length;
            boolean close = exact;
            for (int i = 0; close && i < got.length; i++) {
                if (!sameBitsOrBothNan(got[i], want[i])) {
                    exact = false;
                    close = CValues.withinLibmRounding(got[i], want[i]);
                }
            }
            if (!exact) {
                t.differ++;
            }
            boolean ok = EXACT_ROOTS.contains(root) ? exact : close;
            if (!ok) {
                t.beyondLibm++;
                count++;
                if (shown.size() < 20) {
                    shown.add(root + Arrays.toString(input) + ": " + Arrays.toString(got) + ", expected "
                            + Arrays.toString(want));
                }
            }
        }
        for (String root : ROOTS) {
            assertTrue(tally.containsKey(root), root + " has no golden values");
        }
        assertEquals(ROOTS.length, tally.size());
        if (count != 0) {
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, Tally> e : tally.entrySet()) {
                Tally t = e.getValue();
                summary.append(e.getKey())
                        .append(": ")
                        .append(t.calls)
                        .append(" calls, ")
                        .append(t.differ)
                        .append(" differ, ")
                        .append(t.beyondLibm)
                        .append(" beyond the allowed difference\n");
            }
            fail(count + " results differ from the recorded ones; the first " + shown.size() + ":\n"
                    + String.join("\n", shown) + "\n" + summary);
        }
    }

    /**
     * The inputs C is undefined on, which the file leaves out: the port returns without failing
     * and gives the value its documentation states.
     */
    @Test
    void undefinedInputsGiveTheDocumentedValues() {
        // dat: a date jd2cal rejects or a NaN reaches eraDat as nothing it wrote, and gives 0.0.
        for (double d :
                new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, BAD, 1e300, -1e300}) {
            assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(Pal.dat(d)), String.valueOf(d));
            Pal.djcl(d);
        }
        // dd2tf of a NaN, infinite or huge fraction, or with more decimal places than an int holds:
        // the sign follows the days.
        for (int ndp = -2; ndp <= 10; ndp++) {
            for (double d : KernelInputs.specials()) {
                if (ndp < -9 || ndp > 9 || !Double.isFinite(d) || Math.abs(d) >= 1e7) {
                    assertEquals(d >= 0.0 ? '+' : '-', Pal.dd2tf(ndp, d).sign, ndp + " " + d);
                }
            }
        }
        // dtf2d of an hour or minute of Integer.MIN_VALUE warns for that field.
        for (double s : new double[] {0.0, 1.0, Double.NaN}) {
            assertEquals(Tf2d.Tf2dWarning.HOUR, Pal.dtf2d(Integer.MIN_VALUE, 0, s).warning);
            assertEquals(Tf2d.Tf2dWarning.MINUTE, Pal.dtf2d(0, Integer.MIN_VALUE, s).warning);
        }
    }
}
