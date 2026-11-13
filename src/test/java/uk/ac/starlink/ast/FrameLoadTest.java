package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.NativeClasses;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frame_load.rs}: loading Frames from native dumps reads the
 * Frame-class attributes as C's {@code astLoadFrame} does, and the observer position under the
 * names older versions of AST wrote.
 *
 * <p>{@code oracle/frame_load.json} (from {@code tools/oracle_fixtures/frame_load_probe.c}) holds
 * hand-written descriptions whose per-axis items ({@code Lbl1}, {@code Fmt2}, ...) override the
 * Axis objects, which have no axes, or which name a System the class does not know, each with the
 * axis attributes C reads from it (through {@code astGetC}, {@code astGetI}, {@code astGetD} and
 * {@code astTest}) or the error it reports.
 *
 * <p>SpecFrame and FluxFrame are out of scope, so {@code frame_keys_survive_a_round_trip} and
 * {@code legacy_observer_keys_are_read} check their TimeFrame halves only, and the cases of {@link
 * #OUT_OF_SCOPE} are left out of {@code hand_written_descriptions_load_as_the_c_library_does}.
 */
class FrameLoadTest {

    /** Cases holding a class that is out of scope, with that class. */
    private static final Map<String, String> OUT_OF_SCOPE = outOfScope();

    private static Map<String, String> outOfScope() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("spec_bad_system", "SpecFrame");
        m.put("spec_bad_alsys", "SpecFrame");
        m.put("flux_bad_system", "FluxFrame");
        m.put("spec_axis_keys", "SpecFrame");
        m.put("flux_axis_keys", "FluxFrame");
        return Collections.unmodifiableMap(m);
    }

    /**
     * Hand-written descriptions loaded differently from C, each with what it would take to load it
     * as C does; {@code excludedDescriptionsStillDiffer} fails when one starts matching.
     */
    private static final Map<String, String> EXCLUDED = Collections.emptyMap();

    /** The axis attributes the probe prints, in its order. */
    private static final String[] AXIS_ATTRS = {
        "Label", "Symbol", "Unit", "Format", "Digits", "Direction", "Top", "Bottom"
    };

    private static void setFrameKeys(Frame f) {
        f.setDigits(10);
        f.setMaxAxes(3);
        f.setMinAxes(1);
        f.setMatchEnd(true);
        f.setPermute(false);
        f.setPreserveAxes(true);
    }

    private static void assertFrameKeys(Frame f) {
        assertEquals(10, f.getDigits());
        assertTrue(f.test("MaxAxes"));
        assertEquals(3, f.getMaxAxes());
        assertTrue(f.test("MinAxes"));
        assertEquals(1, f.getMinAxes());
        assertTrue(f.test("MatchEnd") && f.getMatchEnd());
        assertTrue(f.test("Permute") && !f.getPermute());
        assertTrue(f.test("PreserveAxes") && f.getPreserveAxes());
    }

    private static Frame roundTrip(Frame f) {
        Frame back = (Frame) ChannelText.read(ChannelText.dump(f));
        assertEquals(f.getNaxes(), back.getNaxes());
        return back;
    }

    /**
     * Digits, MinAxes, MaxAxes, MatchEnd, Permute and PreserveAxes survive a native round trip of
     * a TimeFrame.
     */
    @Test
    void frameKeysSurviveARoundTrip() {
        TimeFrame t = new TimeFrame();
        t.setSystem("MJD");
        setFrameKeys(t);
        assertFrameKeys(roundTrip(t));
    }

    /** ClLat and ClLon (timeframe.c) give the observer position when ObsLat and ObsLon are absent. */
    @Test
    void legacyObserverKeysAreRead() {
        String time = "Begin TimeFrame\n Naxes = 1\n Domain = \"TIME\"\n System = \"MJD\"\n "
                + "ClLat = -0.4\n ClLon = 2.5\nEnd TimeFrame\n";
        Frame t = (Frame) ChannelText.read(time);
        assertEquals(-0.4, t.getObsLatValue());
        assertEquals(2.5, t.getObsLonValue());
    }

    /** The probe's form of one axis attribute: {@code [value, set]}. */
    private static List<Object> axisAttr(Frame f, String name, int axis) {
        String key = name + "(" + axis + ")";
        Object value;
        if ("Label".equals(name) || "Symbol".equals(name) || "Unit".equals(name) || "Format".equals(name)) {
            value = f.getC(key);
        } else if ("Direction".equals(name)) {
            value = f.getI(key) != 0;
        } else if ("Digits".equals(name)) {
            value = f.getI(key);
        } else {
            value = f.getD(key);
        }
        List<Object> out = new ArrayList<>();
        out.add(value);
        out.add(f.test(key));
        return out;
    }

    /** C's form of one axis attribute from the probe data. */
    private static List<Object> wantAttr(JsonNode n, String name) {
        Object value;
        if ("Label".equals(name) || "Symbol".equals(name) || "Unit".equals(name) || "Format".equals(name)) {
            value = n.get(0).asText();
        } else if ("Direction".equals(name)) {
            value = n.get(0).asBoolean();
        } else if ("Digits".equals(name)) {
            value = n.get(0).asInt();
        } else {
            value = ProbeData.toDouble(n.get(0));
        }
        List<Object> out = new ArrayList<>();
        out.add(value);
        out.add(n.get(1).asBoolean());
        return out;
    }

    /** The axes the text loads to, one list of attributes per axis, or the error, as the probe gives them. */
    private static Object loadAxes(String text) {
        AstObject obj;
        try {
            obj = ChannelText.read(text);
        } catch (AstException e) {
            return e.getStatus() == AstException.AST__ATTIN ? "Attin" : "other: " + e.getMessage();
        }
        Frame f = (Frame) obj;
        List<List<List<Object>>> axes = new ArrayList<>();
        for (int axis = 1; axis <= f.getNaxes(); axis++) {
            List<List<Object>> attrs = new ArrayList<>();
            for (String name : AXIS_ATTRS) {
                attrs.add(axisAttr(f, name, axis));
            }
            axes.add(attrs);
        }
        return axes;
    }

    /** C's result for a case: the axes, or the error. */
    private static Object want(JsonNode result) {
        if ("Err".equals(result.get("variant").asText())) {
            return result.get("value").asText();
        }
        List<List<List<Object>>> axes = new ArrayList<>();
        for (JsonNode axis : result.get("value")) {
            List<List<Object>> attrs = new ArrayList<>();
            for (int i = 0; i < AXIS_ATTRS.length; i++) {
                attrs.add(wantAttr(axis.get(i), AXIS_ATTRS[i]));
            }
            axes.add(attrs);
        }
        return axes;
    }

    private static String text(JsonNode lines) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : lines) {
            out.add(l.asText());
        }
        return String.join("\n", out);
    }

    private static JsonNode cases() {
        return ProbeData.load("frame_load").get("CASES");
    }

    @Test
    void handWrittenDescriptionsLoadAsTheCLibraryDoes() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : cases()) {
            String name = c.get(0).asText();
            String text = text(c.get(1));
            String cls = OUT_OF_SCOPE.get(name);
            if (cls != null) {
                assertTrue(
                        NativeClasses.missing(text).contains(cls),
                        name + " is left out for want of " + cls + ", which is now loaded; run it");
                continue;
            }
            if (EXCLUDED.containsKey(name)) {
                continue;
            }
            run++;
            Object got = loadAxes(text);
            Object want = want(c.get(2));
            if (!want.equals(got)) {
                failures.add(name + ": got " + got + ", C gives " + want);
            }
        }
        assertEquals(12, run, "cases run");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** Every {@link #EXCLUDED} entry names a case that still differs from C. */
    @Test
    void excludedDescriptionsStillDiffer() {
        for (Map.Entry<String, String> e : EXCLUDED.entrySet()) {
            JsonNode found = null;
            for (JsonNode c : cases()) {
                if (c.get(0).asText().equals(e.getKey())) {
                    found = c;
                }
            }
            assertNotNull(found, e.getKey() + " is excluded but not a case");
            assertNotEquals(
                    want(found.get(2)),
                    loadAxes(text(found.get(1))),
                    e.getKey() + " now loads as C does; remove it from EXCLUDED (" + e.getValue() + ")");
        }
    }
}
