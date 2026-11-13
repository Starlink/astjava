package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_cmp_match_end.rs}: the MatchEnd of a CmpFrame template and
 * of its components, checked against C ({@code oracle/cmp_match_end.json}, from {@code
 * tools/oracle_fixtures/cmp_match_end_probe.c}). C's CmpFrame {@code PartMatch} gives an unset
 * component the template's MatchEnd only while matching it, then lays the result out with each
 * component's own MatchEnd (cmpframe.c {@code PartMatch}).
 */
class CmpMatchEndTest {

    /** Applies the probe's comma-separated MinAxes, MaxAxes, MatchEnd and PreserveAxes settings. */
    private static void apply(Frame f, String settings) {
        int start = 0;
        while (start <= settings.length()) {
            int comma = settings.indexOf(',', start);
            int end = comma < 0 ? settings.length() : comma;
            String s = settings.substring(start, end);
            int eq = s.indexOf('=');
            String name = s.substring(0, eq);
            String value = s.substring(eq + 1);
            switch (name) {
                case "MaxAxes":
                    f.setMaxAxes(Integer.parseInt(value));
                    break;
                case "MinAxes":
                    f.setMinAxes(Integer.parseInt(value));
                    break;
                case "MatchEnd":
                    f.setMatchEnd("1".equals(value));
                    break;
                case "PreserveAxes":
                    f.setPreserveAxes("1".equals(value));
                    break;
                default:
                    throw new IllegalArgumentException("no set-up for " + s);
            }
            start = end + 1;
        }
    }

    /**
     * The labels of the result axes and their values for the input (1, 2, 3, 4), as {@code
     * "A=1.0 D=4.0"}, or null if nothing is found.
     */
    private static String run(String cmpSettings, String c1Settings) {
        Frame target = new Frame(4);
        String[] labels = {"A", "B", "C", "D"};
        for (int i = 0; i < labels.length; i++) {
            target.setLabel(i + 1, labels[i]);
        }
        Frame c1 = new Frame(1);
        apply(c1, c1Settings);
        CmpFrame t = new CmpFrame(c1, new Frame(1));
        apply(t, cmpSettings);
        FrameSet fs = target.findFrame(t, "");
        if (fs == null) {
            return null;
        }
        Frame result = fs.getFrame(FrameSet.AST__CURRENT);
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        double[][] out = MappingTestData.transformRows(map, true, new double[] {1.0, 2.0, 3.0, 4.0});
        double[] p = MappingTestData.point(out, 0);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= result.getNaxes(); i++) {
            if (i > 1) {
                sb.append(' ');
            }
            sb.append(result.getLabel(i)).append('=').append(p[i - 1]);
        }
        return sb.toString();
    }

    private static String want(JsonNode w) {
        if (w.isNull()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(w.get(i).get(0).asText())
                    .append('=')
                    .append(ProbeData.toDouble(w.get(i).get(1)));
        }
        return sb.toString();
    }

    @Test
    void matchEndLayoutMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("cmp_match_end").get("CASES")) {
            String cmpSettings = c.get(0).asText();
            String c1Settings = c.get(1).asText();
            String got = run(cmpSettings, c1Settings);
            String want = want(c.get(2));
            boolean same = got == null ? want == null : got.equals(want);
            if (!same) {
                failures.add("[" + cmpSettings + " | " + c1Settings + "]: got " + got + ", C gives " + want);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
