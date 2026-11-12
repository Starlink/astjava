package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_sky_use_defs.rs}: what UseDefs=0 changes in a SkyFrame,
 * checked against C ({@code oracle/sky_use_defs.json}, from {@code
 * tools/oracle_fixtures/sky_use_defs_probe.c}). The default Title leaves out an Equinox or Epoch
 * that has not been set (skyframe.c {@code GetTitle}), and a conversion step that needs an
 * Equinox, Epoch or observer position that is not set is refused with AST__NOVAL, in the input
 * SkyFrame for the steps to the alignment system and in the output SkyFrame for the steps from it
 * (skyframe.c {@code VerifyMSMAttrs}).
 */
class SkyUseDefsTest {

    /** A SkyFrame with the probe's comma-separated settings. */
    private static SkyFrame sky(String settings) {
        SkyFrame f = new SkyFrame();
        int start = 0;
        for (int i = 0; i <= settings.length(); i++) {
            if (i < settings.length() && settings.charAt(i) != ',') {
                continue;
            }
            String s = settings.substring(start, i);
            start = i + 1;
            int eq = s.indexOf('=');
            String name = s.substring(0, eq);
            String value = s.substring(eq + 1);
            switch (name) {
                case "System":
                    f.setSystem(value);
                    break;
                case "UseDefs":
                    f.setUseDefs("1".equals(value));
                    break;
                case "Equinox":
                    if (!"J2010".equals(value)) {
                        throw new IllegalArgumentException("no Java set-up for Equinox=" + value);
                    }
                    f.setEquinox(2010.0);
                    break;
                case "Epoch":
                    f.setEpoch(Double.parseDouble(value));
                    break;
                case "ObsLon":
                    // In degrees, read as the probe's astSet reads it.
                    f.setObsLon(value);
                    break;
                case "ObsLat":
                    f.setObsLat(value);
                    break;
                default:
                    throw new IllegalArgumentException("no Java set-up for " + s);
            }
        }
        return f;
    }

    @Test
    void titlesMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("sky_use_defs").get("TITLES")) {
            String settings = c.get(0).asText();
            String want = c.get(1).asText();
            String got = sky(settings).getTitle();
            if (!got.equals(want)) {
                failures.add("[" + settings + "]: got \"" + got + "\", C gives \"" + want + "\"");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void conversionsMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        JsonNode cases = ProbeData.load("sky_use_defs").get("CONVERSIONS");
        for (JsonNode c : cases) {
            String target = c.get(0).asText();
            String template = c.get(1).asText();
            JsonNode want = c.get(2);
            String label = "[" + target + "] -> [" + template + "]";
            String got;
            boolean same;
            try {
                FrameSet fs = sky(target).findFrame(sky(template), "");
                if (fs == null) {
                    got = "Ok(None)";
                    same = "Ok".equals(want.get("variant").asText())
                            && want.get("value").isNull();
                } else {
                    Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                    double[][] out = map.tran2(1, new double[] {1.0}, new double[] {0.5}, true);
                    double[] g = {out[0][0], out[1][0]};
                    got = "Ok(" + Arrays.toString(g) + ")";
                    JsonNode w = want.get("value");
                    same = "Ok".equals(want.get("variant").asText())
                            && !w.isNull()
                            && CValues.cValuesEq(
                                    g, new double[] {ProbeData.toDouble(w.get(0)), ProbeData.toDouble(w.get(1))});
                }
            } catch (AstException e) {
                if (e.getStatus() != AstException.AST__NOVAL) {
                    throw new AssertionError(label + ": " + e.getMessage(), e);
                }
                got = "Err(Noval)";
                same = "Err".equals(want.get("variant").asText())
                        && "Noval".equals(want.get("value").asText());
            }
            if (!same) {
                failures.add(label + ": got " + got + ", C gives " + want);
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ from C:\n" + String.join("\n", failures));
    }

    /**
     * Picking axes from a SkyFrame with UseDefs=0 needs no Epoch or Equinox, and the result keeps
     * UseDefs=0, as in C (skyframe.c {@code SubFrame}, checked with {@code astPickAxes} and an FK5
     * SkyFrame).
     */
    @Test
    void pickingAxesIsExempt() {
        SkyFrame f = sky("System=FK5,UseDefs=0");
        Frame picked = f.pickAxes(2, new int[] {2, 1}, null);
        assertFalse(picked.getUseDefs());
        assertTrue(picked.test("UseDefs"));
    }
}
