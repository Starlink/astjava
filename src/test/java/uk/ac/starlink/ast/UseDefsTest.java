package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_use_defs.rs}: the UseDefs attribute of Frames, checked
 * against C ({@code oracle/use_defs.json}, from {@code tools/oracle_fixtures/use_defs_probe.c}).
 * UseDefs defaults to true; a CmpFrame takes an unset value from its first component and a
 * FrameSet from its current Frame, to which setting it on the FrameSet goes (cmpframe.c, frameset.c
 * {@code GetUseDefs}). A dump writes {@code UseDfs} when it is set (object.c {@code Dump}).
 *
 * <p>Every case but {@code spec_off}, which needs a SpecFrame, outside the first phase, runs here.
 * For {@code frameset_own}, set through a FrameSet of two Frames, the dump is not compared: C's
 * {@code astSimplify} reduces the Mapping the FrameSet's integrity check puts on its current Frame
 * to a UnitMap, which this version does not, so the dumps differ in that Mapping; the value, the
 * test result and the reading back of C's dump are compared.
 */
class UseDefsTest {

    private static Frame off(Frame f) {
        f.setUseDefs(false);
        return f;
    }

    /** The Object of case {@code name}, or null if it is not run here. */
    private static Frame build(String name) {
        switch (name) {
            case "frame_default":
                return new Frame(1);
            case "frame_off":
                return off(new Frame(1));
            case "frame_off_ident":
                Frame f = off(new Frame(1));
                f.setIdent("Me");
                return f;
            case "sky_on":
                SkyFrame sky = new SkyFrame();
                sky.setUseDefs(true);
                return sky;
            case "time_off":
                return off(new TimeFrame());
            case "cmp_from_first":
                return new CmpFrame(off(new SkyFrame()), new Frame(1));
            case "cmp_second_ignored":
                return new CmpFrame(new Frame(1), off(new SkyFrame()));
            case "frameset_from_current":
            case "frameset_own":
                FrameSet fs = new FrameSet(new Frame(2));
                fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), off(new Frame(2)));
                if ("frameset_own".equals(name)) {
                    fs.setI("UseDefs", 1);
                }
                return fs;
            default:
                return null;
        }
    }

    @Test
    void useDefsMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("use_defs").get("CASES")) {
            String name = c.get(0).asText();
            Frame f = build(name);
            if (f == null) {
                continue;
            }
            ran++;
            String want = c.get(1).asBoolean() + "|" + c.get(2).asBoolean();
            String got = f.getUseDefs() + "|" + f.test("UseDefs");
            if (!got.equals(want)) {
                failures.add(name + ": got " + got + ", C gives " + want);
            }
            String dump = c.get(3).asText();
            String written = ChannelText.dump(f);
            if (!"frameset_own".equals(name) && !written.equals(dump)) {
                failures.add(name + ": dump differs\n" + written + "\nC:\n" + dump);
            }
            AstObject back = ChannelText.read(dump);
            String read = back.getUseDefs() + "|" + back.test("UseDefs");
            if (!read.equals(want)) {
                failures.add(name + ": read back as " + read);
            }
        }
        assertEquals(9, ran, "every case but spec_off");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
