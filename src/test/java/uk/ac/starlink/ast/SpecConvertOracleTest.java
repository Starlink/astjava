package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_spec_convert.rs}: the Mapping {@code convert} finds between
 * SpecFrames, checked against C ({@code oracle/spec_convert.json}, from {@code
 * tools/oracle_fixtures/spec_convert_probe.c}; specframe.c {@code Match}, {@code SubFrame} and
 * {@code MakeSpecMapping}). It converts between every pair of spectral systems in two
 * configurations: with no RestFreq, where C's default of 1.0E14 Hz relates velocities to
 * frequencies, and with different RestFreqs, standards of rest, an Epoch and a reference position
 * on the two sides. Two named cases cover the units check at the end of {@code MakeSpecMapping}.
 *
 * <p>The probe's named cases {@code dsb_*}, {@code spec_vrad_to_dsb} and {@code dsb_found_in_cube}
 * convert DSBSpecFrames and are left to the DSBSpecFrame tests.
 */
class SpecConvertOracleTest {

    /** RefRA of configuration 1 (spec_convert_probe.c {@code REF_RA}). */
    private static final double REF_RA = 1.0471975511965976;

    /** RefDec of configuration 1 (spec_convert_probe.c {@code REF_DEC}). */
    private static final double REF_DEC = 0.5235987755982988;

    /** The probe's {@code config( c, sys, is_to )}. */
    private static SpecFrame config(int c, String sys, boolean isTo) {
        SpecFrame f = new SpecFrame();
        f.set("System=" + sys);
        if (c == 1) {
            f.set("RestFreq=" + (isTo ? "345.5" : "230.5") + ",StdOfRest=" + (isTo ? "Bary" : "LSRK")
                    + ",Epoch=2010.0");
            f.setRefPos(null, REF_RA, REF_DEC);
        }
        return f;
    }

    /**
     * The two points converted from {@code from} to {@code to}, as the probe's {@code emit} does:
     * null when there is no conversion.
     *
     * @throws AstException when C reports an error
     */
    private static double[] convert(Frame from, Frame to, double[] points) {
        FrameSet fs = from.convert(to, "");
        if (fs == null) {
            return null;
        }
        return fs.tran1(2, points, true);
    }

    private static double[] pointsOf(JsonNode consts, String sys) {
        JsonNode systems = consts.get("SYSTEMS");
        for (int i = 0; i < systems.size(); i++) {
            if (systems.get(i).asText().equals(sys)) {
                JsonNode p = consts.get("POINTS").get(i);
                return new double[] {ProbeData.toDouble(p.get(0)), ProbeData.toDouble(p.get(1))};
            }
        }
        throw new IllegalArgumentException(sys);
    }

    /** The probe's case {@code name}, or null for a case left to the DSBSpecFrame tests. */
    private static Outcome run(JsonNode consts, String name) {
        try {
            if (name.startsWith("c")) {
                int colon1 = name.indexOf(':');
                int colon2 = name.indexOf(':', colon1 + 1);
                int c = Integer.parseInt(name.substring(1, colon1));
                String from = name.substring(colon1 + 1, colon2);
                String to = name.substring(colon2 + 1);
                return Outcome.of(convert(config(c, from, false), config(c, to, true), pointsOf(consts, from)));
            }
            double[] freq = pointsOf(consts, "FREQ");
            switch (name) {
                case "units_same_bad":
                case "units_different_bad": {
                    SpecFrame a = new SpecFrame();
                    a.set("System=FREQ");
                    a.setC("Unit", "Jy");
                    SpecFrame b = new SpecFrame();
                    b.set("System=FREQ");
                    b.setC("Unit", "units_same_bad".equals(name) ? "Jy" : "W");
                    return Outcome.of(convert(a, b, freq));
                }
                default:
                    return null;
            }
        } catch (AstException e) {
            return Outcome.error(e);
        }
    }

    /** A probe result: the two converted points, no conversion, or an error. */
    private static final class Outcome {
        final double[] values;
        final boolean failed;
        final String text;

        private Outcome(double[] values, boolean failed, String text) {
            this.values = values;
            this.failed = failed;
            this.text = text;
        }

        static Outcome of(double[] values) {
            return new Outcome(values, false, values == null ? "None" : Arrays.toString(values));
        }

        static Outcome error(AstException e) {
            return new Outcome(null, true, "Err(" + e.getMessage() + ")");
        }

        static Outcome want(JsonNode w) {
            if ("Err".equals(w.get("variant").asText())) {
                return new Outcome(null, true, "Err");
            }
            JsonNode v = w.get("value");
            if (v == null || v.isNull()) {
                return of(null);
            }
            return of(new double[] {ProbeData.toDouble(v.get(0)), ProbeData.toDouble(v.get(1))});
        }

        boolean matches(Outcome want) {
            if (failed || want.failed) {
                return failed == want.failed;
            }
            if (values == null || want.values == null) {
                return values == null && want.values == null;
            }
            return CValues.cValuesEq(values, want.values);
        }
    }

    @Test
    void convertMatchesTheCLibrary() {
        JsonNode consts = ProbeData.load("spec_convert");
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : consts.get("CASES")) {
            String name = c.get(0).asText();
            Outcome got = run(consts, name);
            if (got == null) {
                continue;
            }
            ran++;
            Outcome want = Outcome.want(c.get(1));
            if (!got.matches(want)) {
                failures.add(name + ": got " + got.text + ", C gives " + want.text);
            }
        }
        assertEquals(202, ran, "the SpecFrame cases");
        assertTrue(failures.isEmpty(), failures.size() + " cases differ from C:\n" + String.join("\n", failures));
    }

    /**
     * Two Source rest frames are the same when their SourceVRF and SourceSys are, whether set or
     * defaulted (specframe.c {@code EqualSor} uses the getters), so setting SourceVRF to its default
     * changes nothing.
     */
    @Test
    void sourceRestFramesCompareThroughTheirDefaults() {
        SpecFrame a = new SpecFrame();
        a.set("System=FREQ,StdOfRest=Source");
        SpecFrame b = (SpecFrame) a.copy();
        b.setC("SourceVRF", a.getC("SourceVRF"));
        assertTrue(b.testSourceVRF());
        FrameSet fs = a.convert(b, "");
        assertNotNull(fs);
        assertEquals(
                "UnitMap",
                fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)
                        .simplify()
                        .astClass());
    }
}
