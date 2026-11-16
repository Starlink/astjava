package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_spec_origin_sor.rs}: a SpecOrigin and AlignSpecOffset
 * through a change of StdOfRest, checked against C ({@code oracle/spec_origin_sor.json}, from
 * {@code tools/oracle_fixtures/spec_origin_sor_probe.c}). Setting StdOfRest re-expresses a set
 * SpecOrigin in the new rest frame with a conversion from the Frame to a copy of it, AlignSpecOffset
 * included, and fails with {@code AST__ATSER} when the result is bad (specframe.c {@code
 * OriginStdOfRest}). With AlignSpecOffset set on both Frames, the result's zero point is the target
 * origin transformed through the conversion; in a wavelength System that has no value when no
 * SpecOrigin is set, and the result's own origin is used instead (specframe.c {@code
 * MakeSpecMapping}).
 *
 * <p>The probe's DSBSpecFrames are in the lower sideband with AlignSideBand set and an IF of 4
 * GHz, and their DSBCentre is the value converted.
 */
class SpecOriginSorOracleTest {

    /**
     * The probe's Frame: a SpecFrame, or a DSBSpecFrame with its DSBCentre, with its settings,
     * reference position and SpecOrigin.
     */
    private static SpecFrame build(String cls, String system, boolean offset, JsonNode origin, double value) {
        SpecFrame f;
        if ("DSBSpecFrame".equals(cls)) {
            f = new DSBSpecFrame();
            f.set(system + ",StdOfRest=LSRK,Epoch=2005.0,IF=4,SideBand=LSB,AlignSideBand=1");
        } else if ("SpecFrame".equals(cls)) {
            f = new SpecFrame();
            f.set(system + ",StdOfRest=LSRK,Epoch=2005.0");
        } else {
            throw new IllegalArgumentException("unexpected class " + cls);
        }
        f.setRefPos(null, 2.6, 0.35);
        if (f instanceof DSBSpecFrame) {
            f.setD("DSBCentre", value);
        }
        f.setI("AlignSpecOffset", offset ? 1 : 0);
        if (!origin.isNull()) {
            f.setD("SpecOrigin", ProbeData.toDouble(origin));
        }
        return f;
    }

    /** StdOfRest=HELIO set on a copy, giving its SpecOrigin, "None" or the error. */
    private static String setHelio(SpecFrame f) {
        SpecFrame g = (SpecFrame) f.copy();
        try {
            g.set("StdOfRest=HELIO");
        } catch (AstException e) {
            return e.getStatus() == AstException.AST__ATSER ? "Err:Atser" : "Err:" + e.getMessage();
        }
        return g.test("SpecOrigin") ? Double.toString(g.getD("SpecOrigin")) : "None";
    }

    /** The value converted to a copy whose StdOfRest is HELIO, "None" or the error. */
    private static String convert(SpecFrame f, JsonNode origin, double value) {
        try {
            SpecFrame h = (SpecFrame) f.copy();
            h.clear("SpecOrigin");
            h.set("StdOfRest=HELIO");
            if (!origin.isNull()) {
                h.setD("SpecOrigin", ProbeData.toDouble(origin));
            }
            FrameSet fs = f.convert(h, "");
            if (fs == null) {
                return "None";
            }
            return Double.toString(fs.tran1(1, new double[] {value}, true)[0]);
        } catch (AstException e) {
            return e.getStatus() == AstException.AST__ATSER ? "Err:Atser" : "Err:" + e.getMessage();
        }
    }

    /** The probe's result as this test writes it. */
    private static String want(JsonNode w) {
        if ("Err".equals(w.get("variant").asText())) {
            return "Err:" + w.get("value").asText();
        }
        JsonNode v = w.get("value");
        return v == null || v.isNull() ? "None" : Double.toString(ProbeData.toDouble(v));
    }

    private static boolean same(String got, String want) {
        if (got.equals(want)) {
            return true;
        }
        if (got.startsWith("Err") || want.startsWith("Err") || "None".equals(got) || "None".equals(want)) {
            return false;
        }
        return CValues.cValueEq(Double.parseDouble(got), Double.parseDouble(want));
    }

    @Test
    void originThroughRestFrameChangeMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("spec_origin_sor").get("CASES")) {
            ran++;
            String cls = c.get(0).asText();
            String system = c.get(1).asText();
            boolean offset = c.get(2).asBoolean();
            JsonNode origin = c.get(3);
            double value = ProbeData.toDouble(c.get(4));
            SpecFrame f = build(cls, system, offset, origin, value);
            String gotSet = setHelio(f);
            String gotConv = convert(f, origin, value);
            String wantSet = want(c.get(5));
            String wantConv = want(c.get(6));
            if (!same(gotSet, wantSet) || !same(gotConv, wantConv)) {
                failures.add(cls + " [" + system + "] offset=" + offset + " origin=" + origin + ": got (" + gotSet
                        + ", " + gotConv + "), C gives (" + wantSet + ", " + wantConv + ")");
            }
        }
        assertEquals(16, ran, "the cases the C probe recorded");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
