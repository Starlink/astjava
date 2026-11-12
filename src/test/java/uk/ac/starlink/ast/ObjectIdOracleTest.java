package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_object_id_oracle.rs}: ID, the Object attribute every AST
 * Object has, against C's (object.c): written first in the Object part of a dump whenever it is
 * set, even blank, and read back. Each dump in {@code oracle/object_id.json} (from {@code
 * tools/oracle_fixtures/object_id_probe.c}) is made, compared with C's, read back and dumped again.
 *
 * <p>{@code dumps_match_c} runs here the cases whose classes are ported: {@code zoommap}, {@code
 * zoommap_blank}, {@code keymap}, {@code channel}, {@code zoommap_copy} and {@code
 * zoommap_simplified}. {@code simplify} returns the ZoomMap itself, as C's {@code astSimplify}
 * returns a Mapping it cannot simplify, recording it as simplified. The others are added by the
 * tasks that port their classes: {@code cmpmap}, {@code cmpmap_copy}, {@code cmpmap_simplified} and {@code
 * cmpmap_unsimplifiable} in Task 7; {@code frame} in Task 15; {@code frameset}, {@code
 * frameset_frame_id} and {@code frameset_part_ids} in Task 16; {@code skyframe} in Task 18; {@code
 * cmpframe} in Task 20; {@code fitschan} in Task 21. {@code excluded_cases_still_differ} is
 * translated with the first excluded case. The remaining cases need classes outside the first
 * phase.
 */
class ObjectIdOracleTest {

    /** The cases {@link #dumpsMatchC} runs. */
    private static final List<String> CASES =
            Arrays.asList("zoommap", "zoommap_blank", "keymap", "channel", "zoommap_copy", "zoommap_simplified");

    /** A 1-dimensional ZoomMap of factor {@code factor} with ID {@code id}. */
    private static ZoomMap zoom(double factor, String id) {
        ZoomMap m = new ZoomMap(1, factor);
        m.setID(id);
        return m;
    }

    /** The Object each case of the probe makes. */
    private static AstObject object(String name) {
        switch (name) {
            case "zoommap":
                ZoomMap z = zoom(2.0, "zid");
                z.setC("Ident", "zident");
                return z;
            case "zoommap_blank":
                return zoom(2.0, "");
            case "keymap":
                KeyMap km = new KeyMap();
                km.setID("km");
                km.mapPut0A("z", zoom(2.0, "inner"), null);
                return km;
            case "channel":
                Channel ch = new Channel(null, null);
                ch.setID("ch");
                return ch;
            case "zoommap_copy":
                return zoom(2.0, "orig").copy();
            case "zoommap_simplified":
                return zoom(2.0, "orig").simplify();
            default:
                throw new IllegalArgumentException("no case " + name);
        }
    }

    private static String[] lines(JsonNode arr) {
        String[] out = new String[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).asText();
        }
        return out;
    }

    /** The difference between case {@code name} and C's dumps, or null. */
    private static String difference(String name, JsonNode dump, JsonNode reread) {
        String text = ChannelText.dump(object(name));
        if (!text.equals(ChannelText.text(lines(dump)))) {
            return name + ": dump\n" + text;
        }
        String back = ChannelText.dump(ChannelText.read(text));
        return back.equals(ChannelText.text(lines(reread))) ? null : name + ": dump read back\n" + back;
    }

    @Test
    void dumpsMatchC() {
        List<String> failed = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("object_id").get("DUMPS")) {
            String name = c.get(0).asText();
            if (CASES.contains(name)) {
                run++;
                String d = difference(name, c.get(1), c.get(2));
                if (d != null) {
                    failed.add(d);
                }
            }
        }
        assertEquals(CASES.size(), run, "cases missing from object_id.json");
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }

    @Test
    void aCloneHasNoIdAndKeepsIdent() {
        AstObject m = new PlainObject();
        m.setID("id");
        m.setIdent("ident");
        AstObject copy = m.copy();
        assertNotSame(m, copy);
        assertSame(PlainObject.class, copy.getClass());
        assertFalse(copy.test("ID"));
        assertEquals("", copy.getID());
        assertEquals("ident", copy.getIdent());
        assertEquals("id", m.getID());
    }
}
