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
 * Translation of Rust {@code tests/test_picked_skyaxis.rs}: a Frame made by picking axes out of a
 * SkyFrame, checked against C ({@code oracle/picked_skyaxis.json}, from {@code
 * tools/oracle_fixtures/picked_skyaxis_probe.c}). C's SubFrame attaches the picked SkyAxis objects
 * to a plain Frame, after setting on them the attributes whose defaults the SkyFrame class
 * supplies, so the picked axes keep formatting, unformatting, normalising and dumping as a SkyAxis
 * does.
 */
class PickedSkyAxisTest {

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
                case "NegLon":
                    f.setNegLon(!"0".equals(value));
                    break;
                case "Digits":
                    if (axis == 0) {
                        f.setDigits(Integer.parseInt(value));
                    } else {
                        f.setDigits(axis, Integer.parseInt(value));
                    }
                    break;
                case "Format":
                    f.setFormat(axis, value);
                    break;
                case "AsTime":
                    f.setAsTime(axis, !"0".equals(value));
                    break;
                case "Unit":
                    f.setUnit(axis, value);
                    break;
                case "Label":
                    f.setLabel(axis, value);
                    break;
                case "Symbol":
                    f.setSymbol(axis, value);
                    break;
                case "SkyRef":
                    f.setSkyRef(axis, Double.parseDouble(value));
                    break;
                case "SkyRefIs":
                    f.setSkyRefIs(value);
                    break;
                default:
                    throw new IllegalArgumentException("unexpected setting " + name);
            }
        }
        return f;
    }

    private static Frame picked(String settings, String pick) {
        List<String> parts = splitOn(pick, ',');
        int[] axes = new int[parts.size()];
        for (int i = 0; i < axes.length; i++) {
            axes[i] = Integer.parseInt(parts.get(i));
        }
        return skyFrame(settings).pickAxes(axes.length, axes, null);
    }

    /**
     * The answer to query {@code q} as the probe writes it: {@code S:} text, {@code I:} an integer,
     * or {@code D:} a double, the double being returned in {@code dbl[0]}.
     */
    private static String query(Frame f, String q, int axis, boolean second, double[] dbl) {
        if (q.startsWith("Format:")) {
            return "S:" + f.format(axis, Double.parseDouble(q.substring("Format:".length())));
        }
        if (q.startsWith("AxOffset:")) {
            String[] args = twoArgs(q.substring("AxOffset:".length()));
            dbl[0] = f.axOffset(axis, Double.parseDouble(args[0]), Double.parseDouble(args[1]));
            return "D";
        }
        if (q.startsWith("AxDistance:")) {
            String[] args = twoArgs(q.substring("AxDistance:".length()));
            dbl[0] = f.axDistance(axis, Double.parseDouble(args[0]), Double.parseDouble(args[1]));
            return "D";
        }
        if (q.startsWith("Unformat:")) {
            double[] value = new double[1];
            int n = f.unformat(axis, q.substring("Unformat:".length()), value);
            if (second) {
                return "I:" + n;
            }
            // C reports a value of 0 when nothing is read.
            dbl[0] = n == 0 ? 0.0 : value[0];
            return "D";
        }
        switch (q) {
            case "Domain":
                return "S:" + f.getDomain();
            case "Title":
                return "S:" + f.getTitle();
            case "Label":
                return "S:" + f.getLabel(axis);
            case "Symbol":
                return "S:" + f.getSymbol(axis);
            case "Unit":
                return "S:" + f.getUnit(axis);
            case "Format":
                return "S:" + f.getFormat(axis);
            case "Digits":
                return "I:" + f.getDigits(axis);
            case "Direction":
                return "I:" + (f.getDirection(axis) ? 1 : 0);
            case "Top":
                dbl[0] = f.getTop(axis);
                return "D";
            case "Bottom":
                dbl[0] = f.getBottom(axis);
                return "D";
            case "NormUnit":
                return "S:" + f.getNormUnit(axis);
            default:
                throw new IllegalArgumentException("unexpected query " + q);
        }
    }

    private static String[] twoArgs(String s) {
        int comma = s.indexOf(',');
        return new String[] {s.substring(0, comma), s.substring(comma + 1)};
    }

    @Test
    void everyValueMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        JsonNode cases = ProbeData.load("picked_skyaxis").get("CASES");
        String lastKey = null;
        Frame f = null;
        String previous = null;
        for (JsonNode c : cases) {
            String setting = c.get(0).asText();
            String pick = c.get(1).asText();
            String q = c.get(2).asText();
            int axis = c.get(3).asInt();
            JsonNode expected = c.get(4);
            String key = setting + "|" + pick;
            if (!key.equals(lastKey)) {
                lastKey = key;
                f = picked(setting, pick);
            }
            // An Unformat query has two rows, the value and then the count.
            String row = key + "|" + q + "|" + axis;
            boolean second = row.equals(previous);
            previous = row;
            double[] dbl = new double[1];
            String got = query(f, q, axis, second, dbl);
            String variant = expected.get("variant").asText();
            boolean same;
            if ("D".equals(got)) {
                same = "D".equals(variant) && CValues.cValueEq(dbl[0], ProbeData.toDouble(expected.get("value")));
                got = "D:" + dbl[0];
            } else {
                same = got.equals(variant + ":" + expected.get("value").asText());
            }
            if (!same) {
                failures.add("\"" + setting + "\" pick " + pick + " " + q + "(" + axis + "): got " + got + ", C gives "
                        + expected);
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ:\n" + String.join("\n", failures));
    }

    /** The picked Frame dumps as C's does, and C's dump reads back to a Frame that writes it again. */
    @Test
    void dumpMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        JsonNode cases = ProbeData.load("picked_skyaxis").get("DUMP_CASES");
        for (JsonNode c : cases) {
            String setting = c.get(0).asText();
            String pick = c.get(1).asText();
            String expected = c.get(2).asText();
            String written = ChannelText.dump(picked(setting, pick));
            if (!written.equals(expected)) {
                failures.add(
                        "dump for \"" + setting + "\" pick " + pick + ":\n--- port\n" + written + "--- C\n" + expected);
                continue;
            }
            String rewritten = ChannelText.dump(ChannelText.read(expected));
            if (!rewritten.equals(expected)) {
                failures.add("round trip of C's dump for \"" + setting + "\" pick " + pick + ":\n--- port\n" + rewritten
                        + "--- C\n" + expected);
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " dumps differ:\n" + String.join("\n", failures));
    }

    /**
     * A plain Frame normalises each value through its Axis object (frame.c {@code Norm}), so a
     * SkyAxis picked out of a SkyFrame wraps its angle: into 0..2pi for longitude and -pi..pi for
     * latitude (skyaxis.c {@code AxisNorm}). The values are C's.
     */
    @Test
    void pickedSkyAxesNormaliseAsCDoes() {
        SkyFrame sky = new SkyFrame();
        Frame ra = sky.pickAxes(1, new int[] {1}, null);
        double[] v = {7.0};
        ra.norm(v);
        assertEquals(0.7168146928204138, v[0]);

        Frame dec = sky.pickAxes(1, new int[] {2}, null);
        v = new double[] {4.0};
        dec.norm(v);
        assertEquals(-2.2831853071795862, v[0]);

        Frame plain = new Frame(1);
        v = new double[] {7.0};
        plain.norm(v);
        assertEquals(7.0, v[0]);
    }
}
