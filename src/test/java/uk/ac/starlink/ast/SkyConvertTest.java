package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_sky_convert.rs}: the Mapping {@code convert} finds between
 * two SkyFrames, checked against C for every pair of celestial coordinate systems ({@code
 * oracle/sky_convert.json}, from {@code tools/oracle_fixtures/sky_convert_probe.c}). The probe
 * calls {@code astConvert} between SkyFrames that differ in Epoch, Equinox and observer position,
 * with the destination's AlignSystem unset and set to each system that changes which steps {@code
 * MakeSkyMapping} (skyframe.c) includes, and transforms three points with the result.
 *
 * <p>Every case is compared with C to within libm rounding, except that a case may differ when its SlaMap
 * holds a pair of adjacent conversions that C's {@code astSimplify} cancels or merges (slamap.c
 * {@code MapMerge}): C transforms with the simplified SlaMap, whose results can differ by more than
 * libm rounding (an iterative FK4 conversion and its inverse, a longitude left unnormalised), and
 * this library does not simplify, though by no more than {@link #SIMPLIFICATION_BOUND}. The test fails if no such
 * case differs any more. The Rust test's list of cases licensed
 * to drift by its vectorised SlaMap kernels, and {@code fk_licences_name_existing_cases}, which
 * checks that list, have no counterpart here (the port has no such kernels).
 */
class SkyConvertTest {

    private static SkyFrame sky(String system, JsonNode props) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        f.setEpochMjd(props.get(0).asDouble());
        f.setEquinoxMjd(props.get(1).asDouble());
        f.setObsLatValue(props.get(2).asDouble());
        f.setObsLonValue(props.get(3).asDouble());
        return f;
    }

    /** The conversions of every SlaMap in {@code map}, in the order they are applied. */
    private static void slaSteps(Mapping map, List<String> codes, List<double[]> args) {
        if (map instanceof CmpMap) {
            CmpMap cmp = (CmpMap) map;
            slaSteps(cmp.getMap1(), codes, args);
            slaSteps(cmp.getMap2(), codes, args);
        } else if (map instanceof SlaMap) {
            SlaMap sla = (SlaMap) map;
            for (int i = 0; i < sla.ncvt(); i++) {
                codes.add(sla.cvtCode(i));
                args.add(sla.cvtArgs(i));
            }
        }
    }

    /** Whether {@code a} and {@code b} are mutually inverse conversions (slamap.c {@code MapMerge}). */
    private static boolean inversePair(String a, String b) {
        String[][] pairs = {
            {"SUBET", "ADDET"}, {"FK45Z", "FK54Z"}, {"HFK5Z", "FK5HZ"}, {"AMP", "MAP"}, {"ECLEQ", "EQECL"},
            {"H2E", "E2H"}, {"GALEQ", "EQGAL"}, {"GALSUP", "SUPGAL"}, {"EQHE", "HEEQ"}, {"J2000H", "HJ2000"},
            {"H2R", "R2H"}
        };
        for (String[] p : pairs) {
            if ((p[0].equals(a) && p[1].equals(b)) || (p[1].equals(a) && p[0].equals(b))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether C's SlaMap simplification (slamap.c {@code MapMerge}) changes the conversions of
     * {@code map}: a precession between equal equinoxes, two precessions in a row that chain, or
     * adjacent mutually inverse conversions whose arguments match (the AMP and MAP arguments
     * crossed, as their inverses take them).
     */
    private static boolean simplifiable(Mapping map) {
        List<String> codes = new ArrayList<>();
        List<double[]> args = new ArrayList<>();
        slaSteps(map, codes, args);
        for (int i = 0; i < codes.size(); i++) {
            String c = codes.get(i);
            double[] a = args.get(i);
            if (("PREBN".equals(c) || "PREC".equals(c)) && a[0] == a[1]) {
                return true;
            }
            if (i + 1 < codes.size()) {
                String c2 = codes.get(i + 1);
                double[] a2 = args.get(i + 1);
                if (c.equals(c2) && ("PREBN".equals(c) || "PREC".equals(c)) && a[1] == a2[0]) {
                    return true;
                }
                if (inversePair(c, c2)) {
                    if ("AMP".equals(c) || "MAP".equals(c)) {
                        if (a[0] == a2[1] && a[1] == a2[0]) {
                            return true;
                        }
                    } else if (Arrays.equals(a, a2)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The probe's points transformed from {@code fromSys} to {@code toSys}, or null if there is no conversion. */
    private static double[] convert(
            JsonNode data, int config, String fromSys, String toSys, String align, boolean[] simplifiable) {
        SkyFrame from = sky(fromSys, data.get("FROM_PROPS").get(config));
        SkyFrame to = sky(toSys, data.get("TO_PROPS").get(config));
        if (align != null) {
            to.setAlignSystem(align);
        }
        FrameSet fs = from.convert(to, "");
        if (fs == null) {
            return null;
        }
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        simplifiable[0] = simplifiable(map);
        JsonNode points = data.get("POINTS");
        int n = points.size();
        double[] xin = new double[n];
        double[] yin = new double[n];
        for (int i = 0; i < n; i++) {
            xin[i] = points.get(i).get(0).asDouble();
            yin[i] = points.get(i).get(1).asDouble();
        }
        double[][] out = map.tran2(n, xin, yin, true);
        double[] got = new double[2 * n];
        for (int i = 0; i < n; i++) {
            got[2 * i] = out[0][i];
            got[2 * i + 1] = out[1][i];
        }
        return got;
    }

    private static double[] flatten(JsonNode rows) {
        double[] v = new double[2 * rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            v[2 * i] = ProbeData.toDouble(rows.get(i).get(0));
            v[2 * i + 1] = ProbeData.toDouble(rows.get(i).get(1));
        }
        return v;
    }

    /**
     * The largest difference, in radians, allowed in a case whose SlaMap C simplifies: the
     * differences measured are at most 1.97e-8 rad (FK5 to FK5 in configurations 0 and 1, whose
     * SlaMap holds conversions C merges), with a median of 5.6e-11 rad; the bound leaves room of
     * more than a factor of two above the largest, so that a real fault in a pair C cancels or
     * merges is not excused.
     */
    private static final double SIMPLIFICATION_BOUND = 5.0e-8;

    /**
     * The largest difference between {@code got} and {@code want}, pairs of longitude and latitude
     * in radians, a longitude difference taken modulo 2 pi; infinite if one is bad and the other not.
     */
    private static double largestDifference(double[] got, double[] want) {
        double largest = 0.0;
        for (int i = 0; i < got.length; i++) {
            boolean gotBad = got[i] == CValues.BAD || Double.isNaN(got[i]);
            boolean wantBad = want[i] == CValues.BAD || Double.isNaN(want[i]);
            if (gotBad || wantBad) {
                if (gotBad != wantBad) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            double d = got[i] - want[i];
            if (i % 2 == 0) {
                d = Math.IEEEremainder(d, 2.0 * Math.PI);
            }
            largest = Math.max(largest, Math.abs(d));
        }
        return largest;
    }

    @Test
    void convertMatchesTheCLibrary() {
        JsonNode data = ProbeData.load("sky_convert");
        JsonNode cases = data.get("CASES");
        List<String> failures = new ArrayList<>();
        int excused = 0;
        double largest = 0.0;
        for (JsonNode c : cases) {
            int config = c.get(0).asInt();
            String fromSys = c.get(1).asText();
            String toSys = c.get(2).asText();
            String align = c.get(3).isNull() ? null : c.get(3).asText();
            JsonNode expected = c.get(4);
            String label = "config " + config + " " + fromSys + " -> " + toSys + " align " + align;
            boolean[] simp = new boolean[1];
            double[] got = convert(data, config, fromSys, toSys, align, simp);
            if (got == null && expected.isNull()) {
                continue;
            }
            if (expected.isNull()) {
                failures.add(label + ": converts, C finds no conversion");
            } else if (got == null) {
                failures.add(label + ": no conversion, C converts");
            } else {
                double[] want = flatten(expected);
                boolean same = CValues.cValuesEq(got, want);
                if (simp[0] && !same) {
                    excused++;
                    double d = largestDifference(got, want);
                    largest = Math.max(largest, d);
                    if (d > SIMPLIFICATION_BOUND || Double.isNaN(d)) {
                        failures.add(label + ": differs from C by " + d + " rad, beyond the "
                                + SIMPLIFICATION_BOUND + " rad a merged pair explains: got " + Arrays.toString(got)
                                + ", C gives " + Arrays.toString(want));
                    }
                } else if (!same) {
                    failures.add(label + ": got " + Arrays.toString(got) + ", C gives " + Arrays.toString(want));
                }
            }
        }
        assertTrue(excused > 0, "no case needs simplification any more (largest difference " + largest + ")");
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ from C:\n" + String.join("\n", failures));
    }
}
