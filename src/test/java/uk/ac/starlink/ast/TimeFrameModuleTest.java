package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of the unit tests in Rust {@code frames/timeframe.rs}: setting and clearing the
 * System, matching, selecting axes and overlaying, and the conversion {@code currentTime} applies
 * to the clock, checked at the clock reading at which C's {@code astCurrentTime} gave the values
 * in {@code oracle/frame_api.json} ({@code tools/oracle_fixtures/frame_api_probe.c}).
 */
class TimeFrameModuleTest {

    private static TimeFrame frame(String system) {
        TimeFrame f = new TimeFrame();
        f.setSystem(system);
        return f;
    }

    /** A Frame built with an explicit System reports it set; clearing it gives the MJD default. */
    @Test
    void testSystemSetTrackingIndependentOfValue() {
        TimeFrame f = frame("MJD");
        assertTrue(f.testSystem());
        assertEquals("MJD", f.getSystem());
        f = frame("JD");
        f.clearSystem();
        assertFalse(f.testSystem());
        assertEquals("MJD", f.getSystem());
    }

    @Test
    void matchTemplateTimeframeSelf() {
        FrameFinder.MatchResult m = frame("JD").match(frame("JD"), false);
        assertNotNull(m, "a match");
        assertEquals(0, m.templateAxes[0]);
        assertEquals(0, m.targetAxes[0]);
    }

    @Test
    void matchTemplateTimeframeAgainstBasicframeFails() {
        assertNull(frame("JD").match(new Frame(1), false));
    }

    @Test
    void subFrameTimeframeToTimeframeIdentity() {
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        assertTrue(frame("JD").subFrame(frame("JD"), 1, new int[] {0}, new int[] {0}, map, result));
        assertEquals(1, map[0].getNin());
        assertEquals(1, map[0].getNout());
        assertEquals("JD", assertInstanceOf(TimeFrame.class, result[0]).getSystem());
    }

    @Test
    void overlayTemplateCopiesSetSystem() {
        TimeFrame template = frame("MJD");
        template.setSystem("JEPOCH");
        assertTrue(template.testSystem());
        TimeFrame result = frame("MJD");
        template.overlay(new int[] {0}, result);
        assertEquals("JEPOCH", result.getSystem());
    }

    @Test
    void overlayTemplateSkipsUnsetSystem() {
        TimeFrame template = frame("MJD");
        template.clearSystem();
        assertFalse(template.testSystem());
        TimeFrame result = frame("JD");
        template.overlay(new int[] {0}, result);
        assertEquals("JD", result.getSystem());
    }

    /** Picking the one axis of a TimeFrame gives a TimeFrame (timeframe.c {@code SubFrame}). */
    @Test
    void pickAxesSnapshotFullReturnsTimeframe() {
        Frame picked = frame("JD").pickAxes(1, new int[] {1}, null);
        assertEquals("JD", assertInstanceOf(TimeFrame.class, picked).getSystem());
    }

    /**
     * The conversion {@code currentTime} applies to the clock reading, in UTC seconds since 1970,
     * gives the values C's {@code astCurrentTime} gave at that reading; and the clock gives a time
     * after MJD 60000.
     */
    @Test
    void timeAtUnixSecondsFollowsAstcurrenttime() {
        JsonNode consts = ProbeData.load("frame_api");
        double systime = ProbeData.toDouble(consts.get("SYSTIME"));
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : consts.get("TIMES")) {
            String attrs = c.get(0).asText();
            TimeFrame tf = new TimeFrame();
            tf.set(attrs);
            double want = ProbeData.toDouble(c.get(1));
            double got = tf.currentTime(systime);
            ran++;
            if (!CValues.cValueEq(got, want)) {
                failures.add("\"" + attrs + "\": got " + got + ", C gives " + want);
            }
        }
        assertEquals(4, ran);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(new TimeFrame().currentTime() > 60000.0);
    }
}
