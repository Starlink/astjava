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
 * UseDefs defaults to true, and a dump writes {@code UseDfs} when it is set (object.c {@code
 * Dump}).
 *
 * <p>The plain Frame cases, {@code sky_on} and {@code time_off} run here; {@code cmp_from_first}, {@code cmp_second_ignored}, {@code frameset_from_current} and {@code
 * frameset_own} are added in Task 20; {@code spec_off} needs a SpecFrame, outside the first phase.
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
            if (!written.equals(dump)) {
                failures.add(name + ": dump differs\n" + written + "\nC:\n" + dump);
            }
            AstObject back = ChannelText.read(dump);
            String read = back.getUseDefs() + "|" + back.test("UseDefs");
            if (!read.equals(want)) {
                failures.add(name + ": read back as " + read);
            }
        }
        assertEquals(5, ran, "the plain Frame cases, sky_on and time_off");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
