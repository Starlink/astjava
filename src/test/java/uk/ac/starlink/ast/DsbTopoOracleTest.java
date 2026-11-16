package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_dsb_topo.rs}: the DSBSpecFrame values that pass through
 * C's {@code TopoMap} (dsbspecframe.c), checked against C ({@code oracle/dsb_topo.json}, from
 * {@code tools/oracle_fixtures/dsb_topo_probe.c}). {@code TopoMap} overlays the DSBSpecFrame onto a
 * new SpecFrame, aligns it in the topocentric standard of rest and converts it to a copy set to
 * topocentric frequency in Hz, so the SpecOrigin and the alignment settings take part in the
 * conversions of the DSBCentre, the ImagFreq and a change of sideband.
 */
class DsbTopoOracleTest {

    /** The probe's per-case settings. */
    private static String settings(String name) {
        switch (name) {
            case "plain":
                return "";
            case "origin":
                return "SpecOrigin=229";
            case "align_bary":
                return "AlignStdOfRest=BARY";
            case "origin_align_offset":
                return "SpecOrigin=229,AlignSpecOffset=1";
            case "origin_align_bary":
                return "SpecOrigin=229,AlignStdOfRest=BARY";
            default:
                throw new IllegalArgumentException("no case " + name);
        }
    }

    /** The probe's upper sideband DSBSpecFrame with the case's settings and DSBCentre=230. */
    private static DSBSpecFrame usb(String name) {
        DSBSpecFrame f = new DSBSpecFrame();
        f.set("System=FREQ,Unit=GHz,StdOfRest=LSRK,Epoch=2005.0,IF=4,SideBand=USB,AlignSideBand=1");
        f.setRefPos(null, 2.6, 0.35);
        String s = settings(name);
        if (!s.isEmpty()) {
            f.set(s);
        }
        f.setD("DSBCentre", 230.0);
        return f;
    }

    /** The value, or null where it raises an error, as the probe's {@code opt} prints None. */
    private static double[] opt(DoubleSupplier v) {
        try {
            return new double[] {v.getAsDouble()};
        } catch (AstException e) {
            return null;
        }
    }

    /** The points converted from {@code usb} to a copy in the lower sideband, or null on an error. */
    private static double[] toLsb(DSBSpecFrame usb, double[] points) {
        try {
            DSBSpecFrame lsb = (DSBSpecFrame) usb.copy();
            lsb.set("SideBand=LSB");
            FrameSet fs = usb.convert(lsb, "");
            if (fs == null) {
                return null;
            }
            return fs.tran1(points.length, points, true);
        } catch (AstException e) {
            return null;
        }
    }

    private static double[] want(JsonNode v) {
        if (v.isNull()) {
            return null;
        }
        if (v.isArray()) {
            return new double[] {ProbeData.toDouble(v.get(0)), ProbeData.toDouble(v.get(1))};
        }
        return new double[] {ProbeData.toDouble(v)};
    }

    private static boolean same(double[] got, double[] want) {
        if (got == null || want == null) {
            return got == null && want == null;
        }
        return CValues.cValuesEq(got, want);
    }

    @Test
    void topocentricConversionsMatchTheCLibrary() {
        JsonNode consts = ProbeData.load("dsb_topo");
        JsonNode p = consts.get("POINTS");
        double[] points = {ProbeData.toDouble(p.get(0)), ProbeData.toDouble(p.get(1))};
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : consts.get("CASES")) {
            String name = c.get(0).asText();
            ran++;
            DSBSpecFrame f = usb(name);
            double[][] got = {opt(() -> f.getD("DSBCentre")), opt(() -> f.getD("ImagFreq")), toLsb(f, points)};
            for (int i = 0; i < 3; i++) {
                double[] wanted = want(c.get(i + 1));
                if (!same(got[i], wanted)) {
                    failures.add(name + " item " + i + ": got " + Arrays.toString(got[i]) + ", C gives "
                            + Arrays.toString(wanted));
                }
            }
        }
        assertEquals(5, ran, "the cases the C probe recorded");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
