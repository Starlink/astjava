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
 * Translation of Rust {@code tests/test_skyframe_attrs.rs}: every attribute of a SkyFrame, checked
 * against C ({@code oracle/skyframe_attrs.json}, from {@code
 * tools/oracle_fixtures/skyframe_attrs_probe.c}). The probe applies each setting to a default
 * SkyFrame, reads every attribute back with its {@code astTest} result, and dumps the SkyFrame;
 * {@link #skyFrame} applies the same settings through the Java API.
 *
 * <p>The CmpFrame half of {@code own_axes_have_no_is_latitude} (a CmpFrame holding the SkyFrame
 * reports no Top on its latitude axis) is left to Task 20, which ports CmpFrame.
 */
class SkyFrameAttrsTest {

    /** A default SkyFrame with each {@code ;}-separated setting applied in turn, as the probe does. */
    private static SkyFrame skyFrame(String settings) {
        SkyFrame f = new SkyFrame();
        for (String setting : splitOn(settings, ';')) {
            if (setting.isEmpty()) {
                continue;
            }
            if ("PERM".equals(setting)) {
                f.permAxes(new int[] {2, 1});
                continue;
            }
            int eq = setting.indexOf('=');
            String name = setting.substring(0, eq);
            String value = setting.substring(eq + 1);
            int axis = 0;
            int paren = name.indexOf('(');
            if (paren >= 0) {
                axis = Integer.parseInt(name.substring(paren + 1, name.length() - 1));
                name = name.substring(0, paren);
            }
            switch (name) {
                case "System":
                    f.setSystem(value);
                    break;
                case "AlignSystem":
                    f.setAlignSystem(value);
                    break;
                case "Equinox":
                    f.setEquinox(Double.parseDouble(value));
                    break;
                case "Epoch":
                    f.setEpoch(Double.parseDouble(value));
                    break;
                case "NegLon":
                    f.setNegLon(!"0".equals(value));
                    break;
                case "Projection":
                    f.setProjection(value);
                    break;
                case "SkyRef":
                    f.setSkyRef(axis, Double.parseDouble(value));
                    break;
                case "SkyRefP":
                    f.setSkyRefP(axis, Double.parseDouble(value));
                    break;
                case "SkyRefIs":
                    f.setSkyRefIs(value);
                    break;
                case "SkyTol":
                    f.setSkyTol(Double.parseDouble(value));
                    break;
                case "AlignOffset":
                    f.setAlignOffset(!"0".equals(value));
                    break;
                case "AsTime":
                    f.setAsTime(axis, !"0".equals(value));
                    break;
                default:
                    throw new IllegalArgumentException("unexpected setting " + name);
            }
        }
        return f;
    }

    /** The pieces of {@code s} between occurrences of {@code sep}. */
    private static List<String> splitOn(String s, char sep) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == sep) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        return parts;
    }

    /** A value as the probe writes it: {@code S:} text, {@code I:} integer or {@code D:} double. */
    private static String str(String v) {
        return "S:" + v;
    }

    private static String flag(boolean v) {
        return "I:" + (v ? 1 : 0);
    }

    /** The value of {@code attr}, as a string or integer, or null for a double. */
    private static String value(SkyFrame f, String attr, int axis) {
        switch (attr) {
            case "System":
                return str(f.getSystem());
            case "AlignSystem":
                return str(f.getAlignSystem());
            case "NegLon":
                return flag(f.getNegLon());
            case "Projection":
                return str(f.getProjection());
            case "SkyRefIs":
                return str(f.getSkyRefIs());
            case "AlignOffset":
                return flag(f.getAlignOffset());
            case "AsTime":
                return flag(f.getAsTime(axis));
            case "IsLatAxis":
                return flag(f.getIsLatAxis(axis));
            case "IsLonAxis":
                return flag(f.getIsLonAxis(axis));
            case "LatAxis":
                return "I:" + f.getLatAxis();
            case "LonAxis":
                return "I:" + f.getLonAxis();
            case "Title":
                return str(f.getTitle());
            case "Label":
                return str(f.getLabel(axis));
            case "Symbol":
                return str(f.getSymbol(axis));
            case "Unit":
                return str(f.getUnit(axis));
            case "Format":
                return str(f.getFormat(axis));
            case "Direction":
                return flag(f.getDirection(axis));
            default:
                return null;
        }
    }

    private static double doubleValue(SkyFrame f, String attr, int axis) {
        switch (attr) {
            case "Equinox":
                return f.getEquinox();
            case "Epoch":
                return f.getEpoch();
            case "SkyRef":
                return f.getSkyRef(axis);
            case "SkyRefP":
                return f.getSkyRefP(axis);
            case "SkyTol":
                return f.getSkyTol();
            case "Top":
                return f.getTop(axis);
            case "Bottom":
                return f.getBottom(axis);
            default:
                throw new IllegalArgumentException("unexpected attribute " + attr);
        }
    }

    private static boolean testFlag(SkyFrame f, String attr, int axis) {
        switch (attr) {
            case "System":
                return f.testSystem();
            case "AlignSystem":
                return f.testAlignSystem();
            case "Equinox":
                return f.testEquinox();
            case "Epoch":
                return f.testEpoch();
            case "NegLon":
                return f.testNegLon();
            case "Projection":
                return f.testProjection();
            case "SkyRef":
                return f.testSkyRef(axis);
            case "SkyRefIs":
                return f.testSkyRefIs();
            case "SkyRefP":
                return f.testSkyRefP(axis);
            case "SkyTol":
                return f.testSkyTol();
            case "AlignOffset":
                return f.testAlignOffset();
            case "AsTime":
                return f.testAsTime(axis);
            case "Title":
                return f.testTitle();
            case "Label":
                return f.testLabel(axis);
            case "Symbol":
                return f.testSymbol(axis);
            case "Unit":
                return f.testUnit(axis);
            case "Format":
                return f.testFormat(axis);
            case "Direction":
                return f.testDirection(axis);
            case "Top":
                return f.testTop(axis);
            case "Bottom":
                return f.testBottom(axis);
            default:
                throw new IllegalArgumentException("unexpected attribute " + attr);
        }
    }

    /**
     * Whether the value of {@code attr} matches C's. Epoch and Equinox are compared to 1e-9 years,
     * since C and the port convert them from an MJD by the same formula but not necessarily with
     * the same rounding.
     */
    private static boolean matches(SkyFrame f, String attr, int axis, JsonNode expected) {
        String variant = expected.get("variant").asText();
        JsonNode want = expected.get("value");
        if ("D".equals(variant)) {
            String got = value(f, attr, axis);
            if (got != null) {
                return false;
            }
            double g = doubleValue(f, attr, axis);
            double w = ProbeData.toDouble(want);
            if ("Epoch".equals(attr) || "Equinox".equals(attr)) {
                return Math.abs(w - g) < 1.0e-9;
            }
            return CValues.cValueEq(g, w);
        }
        return (variant + ":" + want.asText()).equals(value(f, attr, axis));
    }

    private static String describe(SkyFrame f, String attr, int axis) {
        String v = value(f, attr, axis);
        return v != null ? v : "D:" + doubleValue(f, attr, axis);
    }

    @Test
    void everyAttributeMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        JsonNode cases = ProbeData.load("skyframe_attrs").get("VALUE_CASES");
        String lastSetting = null;
        SkyFrame f = null;
        for (JsonNode c : cases) {
            String setting = c.get(0).asText();
            String attr = c.get(1).asText();
            int axis = c.get(2).asInt();
            JsonNode expected = c.get(3);
            JsonNode expectedTest = c.get(4);
            if (!setting.equals(lastSetting)) {
                lastSetting = setting;
                f = skyFrame(setting);
            }
            boolean ok = matches(f, attr, axis, expected);
            String gotTest = "-";
            if (!expectedTest.isNull()) {
                boolean t = testFlag(f, attr, axis);
                gotTest = Boolean.toString(t);
                ok = ok && t == expectedTest.asBoolean();
            }
            if (!ok) {
                failures.add("\"" + setting + "\" " + attr + "(" + axis + "): got " + describe(f, attr, axis) + " test "
                        + gotTest + ", C gives " + expected + " test " + expectedTest);
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ:\n" + String.join("\n", failures));
    }

    /** The dump the port writes is C's, and reading C's dump back gives a SkyFrame that writes it again. */
    @Test
    void dumpMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        JsonNode cases = ProbeData.load("skyframe_attrs").get("DUMP_CASES");
        for (JsonNode c : cases) {
            String setting = c.get(0).asText();
            String expected = c.get(1).asText();
            String written = ChannelText.dump(skyFrame(setting));
            if (!written.equals(expected)) {
                failures.add("dump for \"" + setting + "\":\n--- port\n" + written + "--- C\n" + expected);
                continue;
            }
            String rewritten = ChannelText.dump(ChannelText.read(expected));
            if (!rewritten.equals(expected)) {
                failures.add("round trip of C's dump for \"" + setting + "\":\n--- port\n" + rewritten + "--- C\n"
                        + expected);
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " dumps differ:\n" + String.join("\n", failures));
    }

    /**
     * A SkyFrame's own SkyAxes have no IsLatitude set, so the Axis methods C reaches through {@code
     * astGetAxis} treat its latitude axis as a longitude: {@code astAxOffset} wraps -0.1 into [0,
     * 2pi] on either axis. The SkyFrame itself still gives its latitude a Top of pi/2. Recorded in
     * the Rust repository's {@code docs/issues/c-library-quirks.md}.
     */
    @Test
    void ownAxesHaveNoIsLatitude() {
        SkyFrame sky = new SkyFrame();
        for (int axis = 1; axis <= 2; axis++) {
            double v = sky.axOffset(axis, -0.1, 0.0);
            assertTrue(Math.abs(v - (2.0 * Math.PI - 0.1)) < 1.0e-12, "axis " + axis + ": " + v);
        }
        assertTrue(Math.abs(sky.axDistance(2, 0.1, 6.2) - (6.1 - 2.0 * Math.PI)) < 1.0e-12);
        assertEquals(Math.PI / 2.0, sky.getTop(2));
    }

    /** A Native SkyFrame whose System is one of C's alternative names, here EQUATORIAL for FK5, reads as C reads it. */
    @Test
    void nativeSystemAlternativeNameIsRead() {
        SkyFrame fk5 = new SkyFrame();
        fk5.setSystem("FK5");
        String dump = ChannelText.dump(fk5);
        assertTrue(dump.contains("System = \"FK5\""), dump);
        String text = dump.replace("System = \"FK5\"", "System = \"EQUATORIAL\"");
        Frame sky = (Frame) ChannelText.read(text);
        assertEquals("FK5", sky.getSystem());
    }
}
