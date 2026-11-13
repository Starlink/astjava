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
 * Translation of Rust {@code tests/test_spec_use_defs.rs}: what UseDefs=0 changes in a TimeFrame,
 * checked against C ({@code oracle/spec_use_defs.json}, from {@code
 * tools/oracle_fixtures/spec_use_defs_probe.c}). Picking the axis of a TimeFrame whose TimeScale
 * is not set is refused with {@code AST__NOVAL} when UseDefs is false (timeframe.c {@code
 * SubFrame} and {@code VerifyAttrs}); finding a TimeFrame in one is not.
 *
 * <p>The TimeFrame cases of {@code conversions_match_the_c_library} run here; its SpecFrame and
 * DSBSpecFrame cases, and {@code titles_match_the_c_library}, need a SpecFrame, outside the first
 * phase.
 */
class SpecUseDefsTest {

    /** The probe's {@code make}: a TimeFrame with the settings applied. */
    private static TimeFrame make(String settings) {
        TimeFrame f = new TimeFrame();
        if (!settings.isEmpty()) {
            f.set(settings);
        }
        return f;
    }

    /** The value 230 converted, "None" if there is no conversion, or "Err:Noval". */
    private static String convert(String target, String template) {
        TimeFrame t = make(target);
        Mapping map;
        try {
            if ("pick".equals(template)) {
                Mapping[] m = new Mapping[1];
                t.pickAxes(1, new int[] {1}, m);
                map = m[0];
            } else {
                FrameSet fs = t.findFrame(make(template), "");
                if (fs == null) {
                    return "None";
                }
                map = fs;
            }
        } catch (AstException e) {
            return e.getStatus() == AstException.AST__NOVAL ? "Err:Noval" : "Err:other";
        }
        return Double.toString(map.tran1(1, new double[] {230.0}, true)[0]);
    }

    @Test
    void conversionsMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("spec_use_defs").get("CONVERSIONS")) {
            if (!"TimeFrame".equals(c.get(0).asText())) {
                continue;
            }
            ran++;
            String target = c.get(1).asText();
            String template = c.get(2).asText();
            JsonNode w = c.get(3);
            String got = convert(target, template);
            boolean ok;
            String want;
            if ("Err".equals(w.get("variant").asText())) {
                want = "Err:" + w.get("value").asText();
                ok = got.equals(want);
            } else if (w.get("value") == null || w.get("value").isNull()) {
                want = "None";
                ok = got.equals(want);
            } else {
                double v = ProbeData.toDouble(w.get("value"));
                want = Double.toString(v);
                ok = !got.startsWith("Err") && !"None".equals(got) && CValues.cValueEq(Double.parseDouble(got), v);
            }
            if (!ok) {
                failures.add("[" + target + "] -> [" + template + "]: got " + got + ", C gives " + want);
            }
        }
        assertEquals(4, ran, "the TimeFrame cases");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
