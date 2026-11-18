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
 * <p>Every case is compared with C to within libm rounding. The Rust test's list of cases licensed
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

    /** The probe's points transformed from {@code fromSys} to {@code toSys}, or null if there is no conversion. */
    private static double[] convert(JsonNode data, int config, String fromSys, String toSys, String align) {
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
        for (JsonNode c : cases) {
            int config = c.get(0).asInt();
            String fromSys = c.get(1).asText();
            String toSys = c.get(2).asText();
            String align = c.get(3).isNull() ? null : c.get(3).asText();
            JsonNode expected = c.get(4);
            String label = "config " + config + " " + fromSys + " -> " + toSys + " align " + align;
            double[] got = convert(data, config, fromSys, toSys, align);
            if (got == null && expected.isNull()) {
                continue;
            }
            if (expected.isNull()) {
                failures.add(label + ": converts, C finds no conversion");
            } else if (got == null) {
                failures.add(label + ": no conversion, C converts");
            } else {
                double[] want = flatten(expected);
                if (!CValues.cValuesEq(got, want)) {
                    failures.add(label + ": differs from C by " + largestDifference(got, want) + " rad: got "
                            + Arrays.toString(got) + ", C gives " + Arrays.toString(want));
                }
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ from C:\n" + String.join("\n", failures));
    }
}
