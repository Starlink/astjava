package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_min_max_axes.rs}: MinAxes and MaxAxes, checked against C
 * ({@code oracle/min_max_axes.json}, from {@code tools/oracle_fixtures/min_max_axes_probe.c}). An
 * unset MinAxes defaults to Naxes but no more than a set MaxAxes, an unset MaxAxes to Naxes but no
 * less than a set MinAxes, and setting either moves a set value of the other to stay consistent
 * (frame.c {@code DefaultMinAxes}, {@code DefaultMaxAxes}, {@code ConsistentMinAxes} and {@code
 * ConsistentMaxAxes}). A CmpFrame defaults to 0 and 1000000 instead (cmpframe.c {@code
 * GetMinAxes}, {@code GetMaxAxes}).
 */
class MinMaxAxesTest {

    /** Applies {@code ;}-separated {@code MinAxes=n}, {@code MaxAxes=n}, {@code -MinAxes} or {@code -MaxAxes}. */
    private static void apply(Frame f, String settings) {
        int start = 0;
        while (start < settings.length()) {
            int semi = settings.indexOf(';', start);
            int end = semi < 0 ? settings.length() : semi;
            String tok = settings.substring(start, end);
            if ("-MinAxes".equals(tok)) {
                f.clearMinAxes();
            } else if ("-MaxAxes".equals(tok)) {
                f.clearMaxAxes();
            } else if (!tok.isEmpty()) {
                int eq = tok.indexOf('=');
                int n = Integer.parseInt(tok.substring(eq + 1));
                if ("MinAxes".equals(tok.substring(0, eq))) {
                    f.setMinAxes(n);
                } else {
                    f.setMaxAxes(n);
                }
            }
            start = end + 1;
        }
    }

    /** The Frame the probe names {@code cls}. */
    private static Frame make(String cls) {
        switch (cls) {
            case "Frame2":
                return new Frame(2);
            case "SkyFrame":
                return new SkyFrame();
            case "CmpFrame":
                return new CmpFrame(new Frame(1), new Frame(1));
            default:
                throw new IllegalArgumentException("unknown class " + cls);
        }
    }

    @Test
    void minMaxAxesMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("min_max_axes").get("AXES_CASES")) {
            String cls = c.get(0).asText();
            Frame f = make(cls);
            ran++;
            String settings = c.get(1).asText();
            apply(f, settings);
            String got = f.getMinAxes() + "|" + f.getMaxAxes() + "|" + f.testMinAxes() + "|" + f.testMaxAxes();
            String want = c.get(2).asInt() + "|" + c.get(3).asInt() + "|"
                    + c.get(4).asBoolean() + "|" + c.get(5).asBoolean();
            if (!got.equals(want)) {
                failures.add(cls + " [" + settings + "]: got " + got + ", C gives " + want);
            }
        }
        assertEquals(30, ran);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void findFrameMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("min_max_axes").get("FIND_CASES")) {
            ran++;
            String settings = c.get(0).asText();
            int naxes = c.get(1).asInt();
            boolean matched = c.get(2).asBoolean();
            Frame template = new Frame(2);
            apply(template, settings);
            boolean got = new Frame(naxes).findFrame(template, "") != null;
            if (got != matched) {
                failures.add("[" + settings + "] against " + naxes + " axes: got " + got + ", C gives " + matched);
            }
        }
        assertEquals(40, ran);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
