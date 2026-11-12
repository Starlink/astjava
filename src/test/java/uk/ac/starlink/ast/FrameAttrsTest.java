package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frame_attrs.rs}: every attribute of a plain Frame, checked
 * against C ({@code oracle/frame.json}, from {@code tools/oracle_fixtures/frame_attrs_probe.c},
 * which sets each attribute on a 2-axis C Frame with {@code astSet}, reads every attribute back
 * with {@code astGetC}, {@code astGetI} or {@code astGetD} and {@code astTest}, and dumps the
 * Frame). The settings are applied here as C applies them, through {@link AstObject#set}, and the
 * values read back as C reads them, through {@link AstObject#getC} and its relatives.
 *
 * <p>ObsLat and ObsLon are read and written as text by a SkyAxis, as C's frame.c does: a value
 * such as {@code 10:20:30} is read as degrees (skyaxis.c {@code AxisUnformat}), and the value is
 * formatted as {@code N} or {@code S} (ObsLat) or {@code E} or {@code W} (ObsLon) followed by a
 * {@code dms.2} sexagesimal angle. Both the text and the dump are compared exactly with C's. The
 * Rust test sets them in radians and compares the angle C formats to 0.01 arc-second; the text
 * forms here are what C itself reads and writes.
 */
class FrameAttrsTest {

    private static final JsonNode CONSTS = ProbeData.load("frame");

    /** The Frame a probe setting describes, made as the probe makes it. */
    static Frame frame(String setting) {
        boolean permute = setting.startsWith("PERM:");
        String attrs = permute ? setting.substring(5) : setting;
        Frame f = new Frame(2);
        int start = 0;
        while (start <= attrs.length()) {
            int comma = attrs.indexOf(',', start);
            int end = comma < 0 ? attrs.length() : comma;
            String s = attrs.substring(start, end).trim();
            if (!s.isEmpty()) {
                f.set(s);
            }
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        if (permute) {
            f.permAxes(new int[] {2, 1});
        }
        return f;
    }

    private static String key(String attr, int axis) {
        return axis == 0 ? attr : attr + "(" + axis + ")";
    }

    /** Whether the Frame's value of {@code attr} is the value C gives, {@code expected}. */
    private static boolean matches(Frame f, String attr, int axis, JsonNode expected) {
        String variant = expected.get("variant").asText();
        JsonNode value = expected.get("value");
        String k = key(attr, axis);
        switch (variant) {
            case "S":
                return f.getC(k).equals(value.asText());
            case "I":
                return f.getI(k) == value.asInt();
            case "D":
                return f.getD(k) == ProbeData.toDouble(value);
            default:
                throw new IllegalStateException("variant " + variant);
        }
    }

    /** The value the Frame gives for {@code attr}, for messages and comparisons. */
    private static String value(Frame f, String attr, int axis) {
        return f.getC(key(attr, axis));
    }

    @Test
    void everyAttributeMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        JsonNode cases = CONSTS.get("VALUE_CASES");
        for (JsonNode c : cases) {
            String setting = c.get(0).asText();
            String attr = c.get(1).asText();
            int axis = c.get(2).asInt();
            Frame f = frame(setting);
            if (!matches(f, attr, axis, c.get(3))) {
                failures.add("\"" + setting + "\" " + attr + "(" + axis + "): got " + value(f, attr, axis)
                        + ", C gives " + c.get(3));
            }
            if (!c.get(4).isNull()) {
                boolean got = f.test(key(attr, axis));
                if (got != c.get(4).asBoolean()) {
                    failures.add("\"" + setting + "\" test " + attr + "(" + axis + "): got " + got);
                }
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ:\n" + String.join("\n", failures));
    }

    /**
     * The dump written is C's, and reading C's dump back gives a Frame that writes it again and
     * reports the same attributes.
     */
    @Test
    void dumpMatchesTheCLibrary() {
        for (JsonNode d : CONSTS.get("DUMP_CASES")) {
            String setting = d.get(0).asText();
            String expected = d.get(1).asText();
            Frame f = frame(setting);
            assertEquals(expected, ChannelText.dump(f), "dump for \"" + setting + "\"");
            Frame read = assertInstanceOf(Frame.class, ChannelText.read(expected));
            assertEquals(expected, ChannelText.dump(read), "round trip of C's dump for \"" + setting + "\"");
            for (JsonNode c : CONSTS.get("VALUE_CASES")) {
                if (c.get(0).asText().equals(setting)) {
                    String attr = c.get(1).asText();
                    int axis = c.get(2).asInt();
                    assertEquals(
                            value(f, attr, axis),
                            value(read, attr, axis),
                            attr + "(" + axis + ") after reading the dump for \"" + setting + "\"");
                }
            }
        }
    }

    /** Clearing every attribute returns the Frame to its defaults. */
    @Test
    void clearingRestoresEveryDefault() {
        String full = null;
        String defaultDump = null;
        for (JsonNode d : CONSTS.get("DUMP_CASES")) {
            String s = d.get(0).asText();
            if (s.startsWith("Title=T,")) {
                full = s;
            } else if (" ".equals(s)) {
                defaultDump = d.get(1).asText();
            }
        }
        Frame f = frame(full);
        f.clear("Title, Domain, Epoch, Digits, MatchEnd, MaxAxes, MinAxes, Permute, PreserveAxes");
        f.clear("ObsLat, ObsLon, ObsAlt, Dtai, Dut1");
        for (int axis = 1; axis <= 2; axis++) {
            for (String a :
                    new String[] {"Digits", "Direction", "Format", "Label", "Symbol", "Unit", "Top", "Bottom"}) {
                f.clear(a + "(" + axis + ")");
            }
        }
        assertEquals(defaultDump, ChannelText.dump(f));
    }
}
