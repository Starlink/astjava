package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frame_axis_attrs.rs}: the per-axis attributes (Label,
 * Symbol, Unit, Format, Digits, Direction, Top and Bottom) checked against C ({@code
 * oracle/frame_axis.json}, from {@code tools/oracle_fixtures/frame_axis_attrs_probe.c}, which sets
 * one attribute on the first or last axis of an object of each class and reads every attribute of
 * every axis back).
 *
 * <p>The cases for a plain Frame, a SkyFrame, a TimeFrame, a FrameSet holding a SkyFrame (with
 * those of its current Frame, "inner1") and PlainCmpFrame, a CmpFrame of a one-axis and a two-axis
 * plain Frame (with those of its components, "inner1" and "inner2"), run here. The probe's
 * CmpFrame holds a SpecFrame, and its cases, like those of SpecFrame, DSBSpecFrame, FluxFrame,
 * SpecFluxFrame and Circle, are outside the first phase.
 *
 * <p>Digits of an axis is set, read and tested through the attribute string ({@code
 * "Digits(n)"}), as the probe does: for a CmpFrame that goes to the component holding the axis
 * (cmpframe.c {@code GetAttrib}).
 */
class FrameAxisAttrsTest {

    /** The classes whose cases run. */
    private static final List<String> CLASSES =
            Arrays.asList("Frame", "SkyFrame", "TimeFrame", "FrameSet", "PlainCmpFrame");

    private static SkyFrame icrs() {
        SkyFrame f = new SkyFrame();
        f.setSystem("ICRS");
        return f;
    }

    private static Frame make(String cls) {
        switch (cls) {
            case "Frame":
                return new Frame(2);
            case "SkyFrame":
                return icrs();
            case "TimeFrame":
                return new TimeFrame();
            case "FrameSet":
                return new FrameSet(icrs());
            case "PlainCmpFrame":
                return new CmpFrame(new Frame(1), new Frame(2));
            default:
                throw new IllegalArgumentException("unexpected class " + cls);
        }
    }

    /** The Frame whose attributes {@code whose} names: {@code f} itself or one it holds. */
    private static Frame target(String cls, Frame f, String whose) {
        if ("self".equals(whose)) {
            return f;
        }
        if ("FrameSet".equals(cls) && "inner1".equals(whose)) {
            return ((FrameSet) f).getFrame(FrameSet.AST__CURRENT);
        }
        if ("PlainCmpFrame".equals(cls) && "inner1".equals(whose)) {
            return ((CmpFrame) f).decompose()[0];
        }
        if ("PlainCmpFrame".equals(cls) && "inner2".equals(whose)) {
            return ((CmpFrame) f).decompose()[1];
        }
        throw new IllegalStateException("a " + cls + " has no Frame " + whose);
    }

    /** The axis of {@code "Label(2)"}. */
    private static int axisOf(String attr) {
        int open = attr.indexOf('(');
        return Integer.parseInt(attr.substring(open + 1, attr.length() - 1));
    }

    private static String nameOf(String attr) {
        return attr.substring(0, attr.indexOf('('));
    }

    private static void apply(Frame f, String setting) {
        int eq = setting.indexOf('=');
        if (eq < 0) {
            return;
        }
        String attr = setting.substring(0, eq);
        String value = setting.substring(eq + 1);
        if ("Digits".equals(attr)) {
            f.setDigits(Integer.parseInt(value));
            return;
        }
        int axis = axisOf(attr);
        switch (nameOf(attr)) {
            case "Label":
                f.setLabel(axis, value);
                break;
            case "Symbol":
                f.setSymbol(axis, value);
                break;
            case "Unit":
                f.setUnit(axis, value);
                break;
            case "Format":
                f.setFormat(axis, value);
                break;
            case "Digits":
                f.set(setting);
                break;
            case "Direction":
                f.setDirection(axis, !"0".equals(value));
                break;
            case "Top":
                f.setTop(axis, Double.parseDouble(value));
                break;
            case "Bottom":
                f.setBottom(axis, Double.parseDouble(value));
                break;
            default:
                throw new IllegalArgumentException("unexpected setting " + setting);
        }
    }

    /** The value (as C's probe prints it) and test result of {@code attr} on {@code f}. */
    private static String value(Frame f, String attr) {
        int axis = axisOf(attr);
        switch (nameOf(attr)) {
            case "Label":
                return "S:" + f.getLabel(axis) + "|" + f.testLabel(axis);
            case "Symbol":
                return "S:" + f.getSymbol(axis) + "|" + f.testSymbol(axis);
            case "Unit":
                return "S:" + f.getUnit(axis) + "|" + f.testUnit(axis);
            case "Format":
                return "S:" + f.getFormat(axis) + "|" + f.testFormat(axis);
            case "Digits":
                return "I:" + f.getI(attr) + "|" + f.test(attr);
            case "Direction":
                return "I:" + (f.getDirection(axis) ? 1 : 0) + "|" + f.testDirection(axis);
            case "Top":
                return "D:" + f.getTop(axis) + "|" + f.testTop(axis);
            case "Bottom":
                return "D:" + f.getBottom(axis) + "|" + f.testBottom(axis);
            default:
                throw new IllegalArgumentException("unexpected attribute " + attr);
        }
    }

    private static String expected(JsonNode v, boolean test) {
        String variant = v.get("variant").asText();
        JsonNode value = v.get("value");
        String text;
        switch (variant) {
            case "S":
                text = value.asText();
                break;
            case "I":
                text = Integer.toString(value.asInt());
                break;
            case "D":
                text = Double.toString(ProbeData.toDouble(value));
                break;
            default:
                throw new IllegalStateException("variant " + variant);
        }
        return variant + ":" + text + "|" + test;
    }

    @Test
    void everyClassMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("frame_axis").get("CASES")) {
            String cls = c.get(0).asText();
            if (!CLASSES.contains(cls)) {
                continue;
            }
            String setting = c.get(1).asText();
            String whose = c.get(2).asText();
            String attr = c.get(3).asText();
            Frame f = make(cls);
            apply(f, setting);
            String got = value(target(cls, f, whose), attr);
            String want = expected(c.get(4), c.get(5).asBoolean());
            run++;
            if (!got.equals(want)) {
                failures.add(cls + " \"" + setting + "\" " + whose + " " + attr + ": got " + got + ", C gives " + want);
            }
        }
        assertEquals(
                2096, run, "the Frame, SkyFrame, TimeFrame, FrameSet and PlainCmpFrame cases the C probe recorded");
        assertTrue(failures.isEmpty(), failures.size() + " cases differ:\n" + String.join("\n", failures));
    }

    /** An axis index out of range is an error, as C's {@code astValidateAxis} makes it. */
    @Test
    void anAxisOutOfRangeIsAnError() {
        for (String cls : CLASSES) {
            Frame f = make(cls);
            int n = f.getNaxes();
            assertEquals(
                    AstException.AST__AXIIN,
                    assertThrows(AstException.class, () -> f.getLabel(0)).getStatus(),
                    cls);
            assertEquals(
                    AstException.AST__AXIIN,
                    assertThrows(AstException.class, () -> f.getLabel(n + 1)).getStatus(),
                    cls);
            assertEquals(
                    AstException.AST__AXIIN,
                    assertThrows(AstException.class, () -> f.setTop(n + 1, 1.0)).getStatus(),
                    cls);
        }
    }
}
