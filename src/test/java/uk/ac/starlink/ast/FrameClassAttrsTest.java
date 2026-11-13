package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frame_class_attrs.rs}: the Frame-class attributes of each
 * Frame class checked against C ({@code oracle/frame_class.json}, from {@code
 * tools/oracle_fixtures/frame_class_attrs_probe.c}). The probe builds one object of each class,
 * applies each setting on its own with {@code astSet}, and reads every attribute back with {@code
 * astGetC}, {@code astGetI} or {@code astGetD} and its {@code astTest} result; for a FrameSet it
 * also reads the current Frame ("inner"). {@link #make} and {@link #mismatch} do the same.
 *
 * <p>The probe's SpecFrame, DSBSpecFrame, FluxFrame and SpecFluxFrame cases are skipped (out of
 * scope), as are its CmpFrame cases, whose CmpFrame joins a SkyFrame and a SpecFrame (needs
 * SpecFrame), and its Circle cases (Region, out of scope).
 */
class FrameClassAttrsTest {

    /** The probe's classes this library provides. */
    private static final List<String> IN_SCOPE = Arrays.asList("Frame", "SkyFrame", "TimeFrame", "FrameSet");

    /** The object the probe builds for {@code cls}. */
    private static Frame make(String cls) {
        switch (cls) {
            case "Frame":
                return new Frame(2);
            case "SkyFrame":
                return new SkyFrame();
            case "TimeFrame":
                return new TimeFrame();
            case "FrameSet":
                return new FrameSet(new SkyFrame());
            default:
                throw new IllegalArgumentException("unexpected class " + cls);
        }
    }

    /** The Frame whose attributes show the effect of a setting on {@code f}: a FrameSet's current Frame. */
    private static Frame inner(String cls, Frame f) {
        if ("FrameSet".equals(cls)) {
            return ((FrameSet) f).getFrame(FrameSet.AST__CURRENT);
        }
        return null;
    }

    /**
     * Whether the value of {@code attr} on {@code f} differs from C's {@code expected}, as a
     * description, or null if it matches: text and integers exactly, doubles exactly except the
     * Epoch, which the Rust test compares to 1e-9 as it goes through an MJD.
     */
    private static String mismatch(Frame f, String attr, JsonNode expected) {
        String variant = expected.get("variant").asText();
        JsonNode want = expected.get("value");
        if ("ActiveUnit".equals(attr)) {
            int got = f.getActiveUnit() ? 1 : 0;
            return got == want.asInt() ? null : "got " + got + ", C gives " + want.asInt();
        }
        switch (variant) {
            case "S": {
                String got = f.getC(attr);
                return got.equals(want.asText()) ? null : "got \"" + got + "\", C gives \"" + want.asText() + "\"";
            }
            case "D": {
                double got = f.getD(attr);
                double w = ProbeData.toDouble(want);
                boolean ok = "Epoch".equals(attr) ? Math.abs(w - got) < 1e-9 : got == w;
                return ok ? null : "got " + got + ", C gives " + w;
            }
            case "I": {
                int got = f.getI(attr);
                return got == want.asInt() ? null : "got " + got + ", C gives " + want.asInt();
            }
            default:
                throw new IllegalArgumentException("unexpected variant " + variant);
        }
    }

    @Test
    void everyClassMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        String lastKey = null;
        Frame self = null;
        Frame in = null;
        int run = 0;
        for (JsonNode c : ProbeData.load("frame_class").get("CASES")) {
            String cls = c.get(0).asText();
            if (!IN_SCOPE.contains(cls)) {
                continue;
            }
            String setting = c.get(1).asText();
            String whose = c.get(2).asText();
            String attr = c.get(3).asText();
            String what = cls + " \"" + setting + "\" " + whose + " " + attr;
            String key = cls + "|" + setting;
            if (!key.equals(lastKey)) {
                lastKey = key;
                self = make(cls);
                try {
                    self.set(setting);
                } catch (AstException e) {
                    failures.add(cls + " \"" + setting + "\": set failed: " + e.getMessage());
                }
                in = inner(cls, self);
            }
            run++;
            Frame target = "self".equals(whose) ? self : in;
            if (target == null) {
                failures.add(what + ": no inner Frame");
                continue;
            }
            try {
                String bad = mismatch(target, attr, c.get(4));
                if (bad != null) {
                    failures.add(what + ": " + bad);
                }
                JsonNode wantTest = c.get(5);
                if (!wantTest.isNull() && target.test(attr) != wantTest.asBoolean()) {
                    failures.add(what + ": test " + target.test(attr) + ", C gives " + wantTest.asBoolean());
                }
            } catch (AstException e) {
                failures.add(what + ": " + e.getMessage());
            }
        }
        assertEquals(4 * 256 + 256, run, "the in-scope cases the C probe recorded");
        assertTrue(
                failures.isEmpty(), failures.size() + " of " + run + " cases differ:\n" + String.join("\n", failures));
    }
}
