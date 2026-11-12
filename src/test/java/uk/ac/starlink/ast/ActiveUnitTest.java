package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_active_unit.rs}: ActiveUnit and the matching of Frames
 * (frame.c {@code Overlay}, {@code Match} and {@code SubFrame}). Rust's {@code
 * match_template(target, false)} is {@code match(target, false)}.
 */
class ActiveUnitTest {

    @Test
    void overlayTransfersActiveUnit() {
        Frame result = new Frame(1);
        Frame template = new Frame(1);
        template.setActiveUnit(true);
        template.overlay(new int[] {0}, result);
        assertTrue(result.getActiveUnit());
    }

    private static Frame frame(String... units) {
        Frame f = new Frame(units.length);
        for (int i = 0; i < units.length; i++) {
            f.setUnit(i + 1, units[i]);
        }
        return f;
    }

    private static Frame active(String... units) {
        Frame f = frame(units);
        f.setActiveUnit(true);
        return f;
    }

    @Test
    void activeUnitInsertsScaleForKmToM() {
        FrameFinder.MatchResult m = active("m").match(active("km"), false);
        assertNotNull(m);
        assertEquals(2000.0, m.map.tran1(1, new double[] {2.0}, true)[0], 1e-9);
    }

    @Test
    void activeUnitOffIgnoresUnits() {
        FrameFinder.MatchResult m = frame("m").match(frame("km"), false);
        assertNotNull(m);
        assertEquals(2.0, m.map.tran1(1, new double[] {2.0}, true)[0], 1e-12);
    }

    @Test
    void activeUnitMultiAxisScalesPerAxis() {
        FrameFinder.MatchResult m = active("m", "s").match(active("km", "ms"), false);
        assertNotNull(m);
        double[][] out = m.map.tran2(1, new double[] {2.0}, new double[] {2.0}, true);
        assertEquals(2000.0, out[0][0], 1e-9);
        assertEquals(0.002, out[1][0], 1e-12);
    }

    @Test
    void activeUnitAbortsWholeMatchWhenOneAxisUnmappable() {
        assertNull(active("m", "s").match(active("km", "m"), false));
    }
}
