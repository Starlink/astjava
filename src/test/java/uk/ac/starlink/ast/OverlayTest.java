package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_overlay.rs}: the attributes of the Frames that {@code
 * findFrame} and {@code pickAxes} give, checked against C ({@code oracle/overlay.json}, from
 * {@code tools/oracle_fixtures/overlay_probe.c}). The result of a SubFrame is overlaid first by the
 * target and then by the template, each through its own class's Overlay (frame.c {@code
 * SubFrame}).
 *
 * <p>The cases of {@code overlay_results_match_the_c_library} whose Frames this version builds run
 * here: {@code frame_pick}, the SkyFrame cases ({@code sky_target_frame_template}, {@code
 * sky_pick_one}) and the TimeFrame cases ({@code time_target_frame_template}, {@code
 * time_system_change}, {@code time_pick_padded}). The cases with a SpecFrame
 * or a FluxFrame (among them {@code cmp_template_plain_target}, whose CmpFrame holds a SpecFrame)
 * are outside this version.
 */
class OverlayTest {

    /** J1990 as a TDB Modified Julian Date. */
    private static final double J1990 = 47892.0;

    /** The current Frame of what {@code target.findFrame(template, "")} finds. */
    private static Frame found(Frame target, Frame template) {
        FrameSet fs = target.findFrame(template, "");
        assertNotNull(fs, "a match");
        return fs.getFrame(FrameSet.AST__CURRENT);
    }

    private static TimeFrame time(String system) {
        TimeFrame f = new TimeFrame();
        f.setSystem(system);
        return f;
    }

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    /** The Frame the probe's case gives, or null if this version cannot build its Frames. */
    private static Frame run(String name) {
        switch (name) {
            case "frame_pick": {
                Frame f = new Frame(2);
                f.setTitle("Picked");
                f.setDomain("PIX");
                f.setDigits(3);
                f.setEpochMjd(J1990);
                f.setLabel(1, "X");
                f.setLabel(2, "Y");
                f.setUnit(1, "m");
                return f.pickAxes(2, new int[] {2, 1}, null);
            }
            case "sky_target_frame_template": {
                Frame t = new Frame(2);
                t.setDomain("SKY");
                t.setTitle("Hello");
                t.setDigits(9);
                return found(sky("FK5"), t);
            }
            case "sky_pick_one": {
                SkyFrame s = sky("FK5");
                s.setEpochMjd(J1990);
                s.setTitle("Sky");
                s.setDigits(5);
                return s.pickAxes(1, new int[] {1}, null);
            }
            case "time_target_frame_template": {
                Frame t = new Frame(1);
                t.setDomain("TIME");
                t.setTitle("When");
                t.setLabel(1, "T");
                t.setDigits(11);
                return found(time("MJD"), t);
            }
            case "time_system_change": {
                TimeFrame f = time("MJD");
                f.setLabel(1, "Mine");
                f.setTitle("Mine");
                return found(f, time("JD"));
            }
            case "time_pick_padded":
                return time("JD").pickAxes(2, new int[] {1, 0}, null);
            default:
                return null;
        }
    }

    @Test
    void overlayResultsMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("overlay").get("CASES")) {
            String name = c.get(0).asText();
            Frame f = run(name);
            if (f == null) {
                continue;
            }
            ran++;
            JsonNode w = c.get(1);
            String got = f.getTitle() + "|" + f.getDomain() + "|" + f.getDigits() + "|" + f.testEpoch() + "|"
                    + f.getEpochMjd() + "|" + f.getLabel(1) + "|" + f.getUnit(1);
            String want =
                    w.get(0).asText() + "|" + w.get(1).asText() + "|" + w.get(2).asInt() + "|"
                            + w.get(3).asBoolean() + "|" + ProbeData.toDouble(w.get(4)) + "|"
                            + w.get(5).asText() + "|"
                            + w.get(6).asText();
            if (!got.equals(want)) {
                failures.add(name + ": got " + got + ", C gives " + want);
            }
        }
        assertEquals(6, ran, "the cases with Frames this version builds");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void pickAxesSelectionRulesMatchTheCLibrary() {
        Frame f = new Frame(2);
        AstException err = assertThrows(AstException.class, () -> f.pickAxes(2, new int[] {1, 1}, null));
        assertEquals(AstException.AST__SELIN, err.getStatus());

        Mapping[] map = new Mapping[1];
        f.pickAxes(2, new int[] {1, 2}, map);
        assertInstanceOf(UnitMap.class, map[0]);
        f.pickAxes(2, new int[] {2, 1}, map);
        assertInstanceOf(PermMap.class, map[0]);

        Frame picked = f.pickAxes(2, new int[] {1, 3}, map);
        assertEquals(2, picked.getNaxes());
        double[][] out = map[0].tran2(1, new double[] {5.0}, new double[] {6.0}, true);
        assertEquals(5.0, out[0][0]);
        assertEquals(AstObject.AST__BAD, out[1][0]);
    }
}
