package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_match_axes.rs}: {@code matchAxes}, which finds for each
 * axis of one Frame the corresponding axis of another, checked against C ({@code
 * oracle/match_axes.json}, from {@code tools/oracle_fixtures/match_axes_probe.c}, which calls
 * {@code astMatchAxes( frm1, frm2, axes )} for every pair of a list of Frames).
 *
 * <p>The probe's Frames are numbered as its {@code make} function builds them. The cases whose
 * Frames this version can build run here: the SkyFrame (0), the SkyFrame with its axes swapped
 * (1), the one-axis plain Frame (3), the FrameSet whose current Frame is a SkyFrame (8) and the
 * TimeFrame (12). The CmpFrames (4, 5, 7) are added in Task 20. The cases using
 * Frames that hold a SpecFrame (2, 6, 9, 11) or a Circle (10) are outside this version.
 *
 * <p>{@code frm1.matchAxes(frm2)} is called, as the probe calls {@code astMatchAxes}, so that a
 * FrameSet {@code frm1} gives its current Frame as C's FrameSet {@code MatchAxes} does.
 *
 * <p>{@code plainFramesOfDifferentSizes} has no Rust counterpart: it checks what frame.c {@code
 * MatchAxesX} gives for plain Frames of different sizes, and that it restores the attributes of
 * {@code frm1} it changes while matching.
 */
class MatchAxesTest {

    /** Frame {@code i} of the probe's list, or null if this version cannot build it. */
    private static Frame frame(int i) {
        switch (i) {
            case 0:
                return new SkyFrame();
            case 1:
                SkyFrame swapped = new SkyFrame();
                swapped.permAxes(new int[] {2, 1});
                return swapped;
            case 3:
                return new Frame(1);
            case 8:
                FrameSet fs = new FrameSet(new Frame(2));
                fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), new SkyFrame());
                return fs;
            case 12:
                return new TimeFrame();
            default:
                return null;
        }
    }

    @Test
    void matchAxesMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("match_axes").get("CASES")) {
            int i = c.get(0).asInt();
            int j = c.get(1).asInt();
            Frame frm1 = frame(i);
            Frame frm2 = frame(j);
            if (frm1 == null || frm2 == null) {
                continue;
            }
            ran++;
            JsonNode want = c.get(2);
            int[] expected = new int[want.size()];
            for (int k = 0; k < expected.length; k++) {
                expected[k] = want.get(k).asInt();
            }
            int[] got = frm1.matchAxes(frm2);
            if (!Arrays.equals(got, expected)) {
                failures.add("frm1 " + i + " frm2 " + j + ": got " + Arrays.toString(got) + ", C gives "
                        + Arrays.toString(expected));
            }
        }
        assertEquals(25, ran, "the cases with Frames this version builds");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // frame.c MatchAxesX: frm1 is the template, with PreserveAxes set, MaxAxes 10000 and MinAxes
    // 1, so the result has the axes of each primary Frame of frm2, and template axis i goes with
    // result axis i (or none beyond the template's axes). Its PreserveAxes, MaxAxes and MinAxes
    // are put back afterwards.
    @Test
    void plainFramesOfDifferentSizes() {
        Frame two = new Frame(2);
        Frame three = new Frame(3);
        three.setMinAxes(3);
        assertArrayEquals(new int[] {1, 2, 0}, two.matchAxes(three));
        assertArrayEquals(new int[] {1, 2}, three.matchAxes(two));
        assertFalse(three.testPreserveAxes());
        assertFalse(three.testMaxAxes());
        assertTrue(three.testMinAxes());
        assertEquals(3, three.getMinAxes());
        assertFalse(two.testPreserveAxes());
        assertFalse(two.testMinAxes());
    }

    // frameset.c MatchAxes and MatchAxesX: a FrameSet takes part through its current Frame.
    @Test
    void aFramesetUsesItsCurrentFrame() {
        FrameSet fs = new FrameSet(new Frame(3));
        fs.addFrame(1, new PermMap(3, null, 2, null, null), new Frame(2));
        assertArrayEquals(new int[] {1, 2, 0}, fs.matchAxes(new Frame(3)));
        assertArrayEquals(new int[] {1, 2}, new Frame(3).matchAxes(fs));
    }
}
