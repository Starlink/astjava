package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * zoommap_blank}, {@code keymap}, {@code channel}, {@code zoommap_copy}, {@code
 * zoommap_simplified}, {@code cmpmap}, {@code cmpmap_copy}, {@code frame}, {@code skyframe},
 * {@code frameset}, {@code frameset_frame_id}, {@code frameset_part_ids}, {@code cmpframe} and {@code fitschan}. {@code simplify} returns the
 * ZoomMap itself, as C's {@code astSimplify} returns a Mapping it cannot simplify, recording it as
 * simplified, and a CmpMap shares its components, as C's does, so the Rust test's exclusions for
 * copies do not arise for them. {@code cmpmap_simplified} is C's simplification of two ZoomMaps
 * into one, which this version does not make: it is in {@link #EXCLUDED}, and {@code
 * excluded_cases_still_differ} fails when it matches. {@code
 * cmpmap_unsimplifiable} needs a LutMap, and the remaining cases other classes outside the first
 * phase.
 */
class ObjectIdOracleTest {

    /** The cases {@link #dumpsMatchC} runs. */
    private static final List<String> CASES = Arrays.asList(
            "zoommap",
            "zoommap_blank",
            "keymap",
            "channel",
            "zoommap_copy",
            "zoommap_simplified",
            "cmpmap",
            "cmpmap_copy",
            "frame",
            "skyframe",
            "frameset",
            "frameset_frame_id",
            "frameset_part_ids",
            "cmpframe",
            "fitschan");

    /** The cases whose dump differs from C's, with the reason. */
    private static final String[][] EXCLUDED = {
        {"cmpmap_simplified", "needs simplification: C merges the two ZoomMaps into one"},
    };

    /** A series CmpMap of two ZoomMaps, with IDs. */
    private static CmpMap cmpmap() {
        CmpMap m = new CmpMap(zoom(2.0, "a"), zoom(3.0, "b"), true);
        m.setID("c");
        return m;
    }

    /** A 1-dimensional ZoomMap of factor {@code factor} with ID {@code id}. */
    private static ZoomMap zoom(double factor, String id) {
        ZoomMap m = new ZoomMap(1, factor);
        m.setID(id);
        return m;
    }

    /** A Frame with {@code naxes} axes and ID {@code id}, if not null. */
    private static Frame frame(int naxes, String id) {
        Frame f = new Frame(naxes);
        if (id != null) {
            f.setID(id);
        }
        return f;
    }

    /**
     * A FrameSet with ID "fs" made from a Frame with ID {@code base}, to which a Frame with ID
     * {@code added} is added through a ZoomMap with ID {@code map}; null for no ID. C's {@code
     * astFrameSet} holds the first Frame, keeping its ID, and {@code astAddFrame} copies the Frame
     * and Mapping it is given, dropping theirs.
     */
    private static FrameSet frameset(String base, String map, String added) {
        FrameSet fs = new FrameSet(frame(1, base));
        fs.setID("fs");
        ZoomMap m = new ZoomMap(1, 2.0);
        if (map != null) {
            m.setID(map);
        }
        fs.addFrame(FrameSet.AST__BASE, m, frame(1, added));
        return fs;
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
            case "cmpmap":
                return cmpmap();
            case "cmpmap_copy":
                return cmpmap().copy();
            case "cmpmap_simplified":
                return cmpmap().simplify();
            case "frame":
                Frame f = new Frame(2);
                f.setID("fid");
                return f;
            case "skyframe":
                SkyFrame sky = new SkyFrame();
                sky.setSystem("ICRS");
                sky.clearSystem();
                sky.setID("sky");
                return sky;
            case "frameset":
                return frameset(null, null, null);
            case "frameset_frame_id":
                FrameSet fs = new FrameSet(frame(1, "base"));
                fs.setID("fs");
                return fs;
            case "frameset_part_ids":
                return frameset("base", "map", "added");
            case "cmpframe":
                CmpFrame cf = new CmpFrame(frame(1, "a"), frame(1, "b"));
                cf.setID("c");
                return cf;
            case "fitschan":
                FitsChan fc = new FitsChan();
                fc.setID("fc");
                return fc;
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
    void excludedCasesStillDiffer() {
        for (String[] excluded : EXCLUDED) {
            JsonNode found = null;
            for (JsonNode c : ProbeData.load("object_id").get("DUMPS")) {
                if (c.get(0).asText().equals(excluded[0])) {
                    found = c;
                }
            }
            assertNotNull(found, "no case " + excluded[0]);
            assertNotNull(
                    difference(excluded[0], found.get(1), found.get(2)),
                    excluded[0] + " now matches C; remove it from EXCLUDED (" + excluded[1] + ")");
        }
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
