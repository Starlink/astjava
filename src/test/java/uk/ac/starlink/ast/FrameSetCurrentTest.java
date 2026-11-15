package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frameset_current.rs}: a FrameSet's Current attribute is
 * set, cleared and written as C's is: unset until a Frame is added or Current is set, written
 * commented out while unset, cleared when the current Frame is removed, and renumbered when an
 * earlier Frame is. The steps and C's dumps are {@code oracle/frameset_current.json}, from {@code
 * tools/oracle_fixtures/frameset_current_probe.c}.
 *
 * <p>Removing the first of three Frames joined by UnitMaps leaves C with the UnitMap its
 * simplification makes of the two joined, which this version, not simplifying, writes as a
 * CmpMap: the dumps of those steps, in {@link #NEEDS_SIMPLIFICATION}, are not compared, and {@code
 * excludedStepsStillDiffer} fails when they match.
 */
class FrameSetCurrentTest {

    private static final JsonNode STEPS = ProbeData.load("frameset_current").get("STEPS");

    /** The steps whose dump needs simplification: C joins the two UnitMaps into one. */
    private static final List<String> NEEDS_SIMPLIFICATION = Arrays.asList("cleared_remove_first", "set_remove_first");

    private static Frame frame(int i) {
        Frame f = new Frame(1);
        f.setDomain("F" + i);
        return f;
    }

    /** A FrameSet of {@code n} one-axis Frames, as the probe builds it. */
    private static FrameSet frameset(int n) {
        FrameSet fs = new FrameSet(frame(1));
        for (int i = 2; i <= n; i++) {
            fs.addFrame(FrameSet.AST__BASE, new UnitMap(1), frame(i));
        }
        return fs;
    }

    private static String text(JsonNode lines) {
        String[] out = new String[lines.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = lines.get(i).asText();
        }
        return ChannelText.text(out);
    }

    /** The names of the probe's steps, and in {@code got} the FrameSet each makes. */
    private static List<String> runSteps(List<FrameSet> got) {
        List<String> names = new ArrayList<>();
        names.add("one_frame");
        got.add(frameset(1));

        FrameSet fs = frameset(3);
        names.add("three_frames");
        got.add((FrameSet) fs.copy());
        fs.clearCurrent();
        names.add("three_cleared");
        got.add((FrameSet) fs.copy());
        fs.removeFrame(1);
        names.add("cleared_remove_first");
        got.add(fs);

        fs = frameset(3);
        fs.removeFrame(1);
        names.add("set_remove_first");
        got.add((FrameSet) fs.copy());
        fs.removeFrame(2);
        names.add("set_remove_current");
        got.add(fs);

        fs = frameset(3);
        fs.setCurrent(1);
        fs.removeFrame(3);
        names.add("set_first_remove_last");
        got.add(fs);
        return names;
    }

    @Test
    void currentFollowsTheCLibrary() {
        List<FrameSet> got = new ArrayList<>();
        List<String> names = runSteps(got);
        List<String> want = new ArrayList<>();
        for (JsonNode s : STEPS) {
            want.add(s.get(0).asText());
        }
        assertEquals(want, names, "the test runs the probe's steps");

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            JsonNode step = STEPS.get(i);
            boolean set = step.get(1).asBoolean();
            FrameSet f = got.get(i);
            if (f.testCurrent() != set) {
                failures.add(names.get(i) + ": testCurrent is " + !set + ", C gives " + set);
            }
            String written = ChannelText.dump(f);
            String c = text(step.get(2));
            if (!NEEDS_SIMPLIFICATION.contains(names.get(i)) && !written.equals(c)) {
                failures.add(names.get(i) + ": dump differs\n" + written + "\nC:\n" + c);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void excludedStepsStillDiffer() {
        List<FrameSet> got = new ArrayList<>();
        List<String> names = runSteps(got);
        for (String name : NEEDS_SIMPLIFICATION) {
            int i = names.indexOf(name);
            assertTrue(i >= 0, name);
            assertNotEquals(
                    text(STEPS.get(i).get(2)),
                    ChannelText.dump(got.get(i)),
                    name + " now matches C: remove it from NEEDS_SIMPLIFICATION");
        }
    }

    /** Each dump reads back with Current set or unset as C wrote it, and writes out unchanged. */
    @Test
    void currentReadsBackAsWritten() {
        for (JsonNode step : STEPS) {
            String name = step.get(0).asText();
            String dump = text(step.get(2));
            FrameSet fs = assertInstanceOf(FrameSet.class, ChannelText.read(dump), name);
            assertEquals(step.get(1).asBoolean(), fs.testCurrent(), name);
            assertEquals(dump, ChannelText.dump(fs), name);
        }
    }
}
