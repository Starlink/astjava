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
 * <p>The named cases {@code dsb_*}, {@code spec_vrad_to_dsb} and {@code dsb_found_in_cube} convert
 * DSBSpecFrames, which a DSBSpecFrame template aligns as a SpecFrame unless both have AlignSideBand
 * set (dsbspecframe.c {@code Match} and {@code SubFrame}), the last in a (sky, DSBSpecFrame) cube.
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
     * The probe's DSBSpecFrame: the SideBand and AlignSideBand given to its constructor, then
     * FREQ in GHz, RestFreq 230.5 GHz, DSBCentre 230 GHz and IF 4 GHz.
     */
    private static DSBSpecFrame dsb(String sideBand, boolean align) {
        DSBSpecFrame f = new DSBSpecFrame();
        f.set("SideBand=" + sideBand + (align ? ",AlignSideBand=1" : ""));
        f.set("System=FREQ,Unit=GHz,RestFreq=230.5,DSBCentre=230.0,IF=4.0");
        return f;
    }

    /** The probe's DSBSpecFrame in the lower sideband with System VRAD. */
    private static DSBSpecFrame dsbVrad() {
        DSBSpecFrame v = dsb("LSB", false);
        v.set("System=VRAD");
        return v;
    }

    /** The probe's SpecFrame: VRAD with RestFreq 230.5 GHz. */
    private static SpecFrame specVrad() {
        SpecFrame s = new SpecFrame();
        s.set("System=VRAD,RestFreq=230.5");
        return s;
    }

    /**
     * The probe's {@code dsb_found_in_cube}: a DSBSpecFrame template in VRAD found in a (sky,
     * DSBSpecFrame) cube, and two cube positions transformed to it; null when it is not found.
     */
    private static double[] foundInCube() {
        CmpFrame cube = new CmpFrame(new SkyFrame(), dsb("LSB", false));
        DSBSpecFrame t = (DSBSpecFrame) dsbVrad().copy();
        t.set("MaxAxes=3");
        FrameSet fs = cube.findFrame(t, "");
        if (fs == null) {
            return null;
        }
        double[][] out = fs.tranP(2, 3, new double[][] {{1.0, 2.0}, {0.5, 0.6}, {230.0, 345.0}}, true, 1);
        return new double[] {out[0][0], out[0][1]};
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

    /** The probe's case {@code name}. */
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
                case "dsb_freq_to_vrad":
                    return Outcome.of(convert(dsb("LSB", false), dsbVrad(), freq));
                case "dsb_lsb_to_usb":
                    return Outcome.of(convert(dsb("LSB", false), dsb("USB", false), freq));
                case "dsb_lsb_to_usb_aligned":
                    return Outcome.of(convert(dsb("LSB", true), dsb("USB", true), freq));
                case "dsb_to_spec_vrad":
                    return Outcome.of(convert(dsb("LSB", false), specVrad(), freq));
                case "spec_vrad_to_dsb":
                    return Outcome.of(convert(specVrad(), dsb("LSB", false), pointsOf(consts, "VRAD")));
                case "dsb_found_in_cube":
                    return Outcome.of(foundInCube());
                default:
                    throw new IllegalArgumentException("no case " + name);
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
            ran++;
            Outcome want = Outcome.want(c.get(1));
            if (!got.matches(want)) {
                failures.add(name + ": got " + got.text + ", C gives " + want.text);
            }
        }
        assertEquals(208, ran, "the cases the C probe recorded");
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
