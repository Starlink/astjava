package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_skyframe_format.rs}: SkyFrame axis formatting, Units and
 * serialisation, checked against C ({@code oracle/skyframe.json}, from {@code
 * tools/oracle_fixtures/skyframe_format_probe.c}). Each case names an attribute setting string as
 * {@code astSet} takes it; {@link #apply} applies it through the Java API.
 */
class SkyFrameFormatTest {

    /** A SkyFrame with System {@code system} and the {@code astSet}-style settings {@code attrs}. */
    private static SkyFrame skyFrame(String system, String attrs) {
        if (!"ICRS".equals(system) && !"GALACTIC".equals(system)) {
            throw new IllegalArgumentException("unexpected System " + system);
        }
        SkyFrame sky = new SkyFrame();
        sky.setSystem(system);
        apply(sky, attrs);
        return sky;
    }

    private static void apply(SkyFrame sky, String attrs) {
        int start = 0;
        for (int i = 0; i <= attrs.length(); i++) {
            if (i < attrs.length() && attrs.charAt(i) != ',') {
                continue;
            }
            String setting = attrs.substring(start, i).trim();
            start = i + 1;
            if (setting.isEmpty()) {
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
                    sky.setSystem(value.toUpperCase(Locale.ROOT));
                    break;
                case "Digits":
                    if (axis == 0) {
                        sky.setDigits(Integer.parseInt(value));
                    } else {
                        sky.setDigits(axis, Integer.parseInt(value));
                    }
                    break;
                case "Format":
                    sky.setFormat(axis, value);
                    break;
                case "Unit":
                    sky.setUnit(axis, value);
                    break;
                case "AsTime":
                    sky.setAsTime(axis, "1".equals(value));
                    break;
                case "Label":
                    sky.setLabel(axis, value);
                    break;
                default:
                    throw new IllegalArgumentException("unsupported setting " + setting);
            }
        }
    }

    @Test
    void formatMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        JsonNode cases = ProbeData.load("skyframe").get("FORMAT_CASES");
        for (JsonNode c : cases) {
            String system = c.get(0).asText();
            String attrs = c.get(1).asText();
            int axis = c.get(2).asInt();
            double value = ProbeData.toDouble(c.get(3));
            String expected = c.get(4).asText();
            String got = skyFrame(system, attrs).format(axis, value);
            if (!got.equals(expected)) {
                failures.add(system + " \"" + attrs + "\" axis " + axis + " value " + value + ": got \"" + got
                        + "\", C gives \"" + expected + "\"");
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ:\n" + String.join("\n", failures));
    }

    @Test
    void formatUnitAndNormUnitMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("skyframe").get("UNIT_CASES")) {
            String system = c.get(0).asText();
            String attrs = c.get(1).asText();
            int axis = c.get(2).asInt();
            SkyFrame sky = skyFrame(system, attrs);
            String got = sky.getFormat(axis) + "|" + sky.getUnit(axis) + "|" + sky.getNormUnit(axis);
            String want =
                    c.get(3).asText() + "|" + c.get(4).asText() + "|" + c.get(5).asText();
            if (!got.equals(want)) {
                failures.add(system + " \"" + attrs + "\" axis " + axis + ": got " + got + ", C gives " + want);
            }
            assertEquals(sky.getUnit(axis), sky.getUnit(axis));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * The dump the port writes for each setting is the one C writes, and reading C's dump back
     * gives a SkyFrame that writes it again.
     */
    @Test
    void dumpMatchesTheCLibrary() {
        for (JsonNode c : ProbeData.load("skyframe").get("DUMP_CASES")) {
            String attrs = c.get(0).asText();
            String expected = c.get(1).asText();
            SkyFrame sky = new SkyFrame();
            apply(sky, attrs);
            assertEquals(expected, ChannelText.dump(sky), "dump for \"" + attrs + "\"");
            String rewritten = ChannelText.dump(ChannelText.read(expected));
            assertEquals(expected, rewritten, "round trip of C's dump for \"" + attrs + "\"");
        }
    }

    /** The attributes a C dump carries are the ones the SkyFrame reads back. */
    @Test
    void aReadDumpKeepsItsAttributes() {
        String dump = null;
        for (JsonNode c : ProbeData.load("skyframe").get("DUMP_CASES")) {
            if (c.get(0).asText().startsWith("Label(1)=RA")) {
                dump = c.get(1).asText();
            }
        }
        assertNotNull(dump, "the combined case");
        SkyFrame sky = assertInstanceOf(SkyFrame.class, ChannelText.read(dump));
        assertEquals("bhms", sky.getFormat(1));
        assertTrue(sky.testFormat(1));
        assertEquals("hh mm ss", sky.getUnit(1));
        assertTrue(sky.testUnit(1));
        assertEquals(8, sky.getDigits(1));
        assertTrue(sky.testDigits(1));
        assertTrue(sky.getAsTime(1));
        assertTrue(sky.testAsTime(1));
        assertFalse(sky.testFormat(2));
        assertEquals("hh mm ss", sky.getNormUnit(1));
    }

    /** Clearing an attribute returns the axis to the default the System and the remaining attributes give it. */
    @Test
    void clearingRestoresTheDefault() {
        SkyFrame sky = skyFrame("ICRS", "Format(1)=bhms,Unit(1)=deg,Digits(1)=9,AsTime(1)=0");
        sky.clearFormat(1);
        sky.clearUnit(1);
        sky.clearDigits(1);
        sky.clearAsTime(1);
        assertEquals("hms.1", sky.getFormat(1));
        assertEquals("hh:mm:ss.s", sky.getUnit(1));

        sky.setDigits(3);
        assertEquals("d", sky.getFormat(2));
        sky.clearDigits();
        assertEquals("dms", sky.getFormat(2));
    }

    /** Attributes follow the axis they were set on when the axes are permuted. */
    @Test
    void attributesFollowAPermutation() {
        SkyFrame sky = skyFrame("ICRS", "Format(1)=bhms,Digits(2)=9");
        sky.permAxes(new int[] {2, 1});
        assertEquals("bhms", sky.getFormat(2));
        assertEquals("hh mm ss", sky.getUnit(2));
        assertEquals("dms.2", sky.getFormat(1));
        assertEquals("3 49 11", sky.format(2, 1.0));
    }

    @Test
    void badAndNanFormatAsBad() {
        SkyFrame sky = skyFrame("ICRS", " ");
        assertEquals("<bad>", sky.format(1, CValues.BAD));
        assertEquals("<bad>", sky.format(1, Double.NaN));
        assertThrows(AstException.class, () -> sky.format(3, 0.0));
    }
}
