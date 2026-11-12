package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_ident.rs}: Ident, the Object attribute every AST Object
 * has, written and read as C writes and reads it, in the Object part of a description whenever it
 * is set, even blank, and read only from that part. A KeyMap holds one too, as does each KeyMap
 * nested in it. The expected dumps and reads come from C ({@code oracle/ident.json}, from {@code
 * tools/oracle_fixtures/ident_probe.c}).
 *
 * <p>The Frame and FrameSet parts of {@code a_blank_ident_is_set_and_written} and {@code
 * a_blank_ident_reads_back_set} are translated in Tasks 15 (Frame) and 16 (FrameSet).
 * {@code aCopyKeepsIdent} checks the Object itself.
 */
class IdentTest {

    private static final JsonNode CASES = ProbeData.load("ident");

    private static String[] lines(JsonNode arr) {
        String[] out = new String[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).asText();
        }
        return out;
    }

    private static String text(String name) {
        return ChannelText.text(lines(CASES.get(name)));
    }

    @Test
    void keymapIdentsAreWrittenAsCWritesThem() {
        KeyMap inner = new KeyMap();
        inner.setIdent("inner map");
        inner.mapPut0I("x", 7, null);
        KeyMap km = new KeyMap();
        km.setIdent("outer map");
        km.mapPut0A("inner", inner, null);
        assertEquals(text("DUMP_IDENTS"), ChannelText.dump(km), "idents");

        km = new KeyMap();
        km.mapPut0I("a", 1, null);
        assertFalse(km.test("Ident"));
        assertEquals(text("DUMP_NO_IDENT"), ChannelText.dump(km), "no ident");
    }

    @Test
    void aBlankIdentIsSetAndWritten() {
        AstObject o = new PlainObject();
        assertFalse(o.test("Ident"));
        assertEquals("", o.getIdent());
        o.setIdent("");
        assertTrue(o.test("Ident"));
        assertEquals("", o.getIdent());

        KeyMap km = new KeyMap();
        km.setIdent("");
        assertTrue(km.test("Ident"));
        assertEquals(text("DUMP_BLANK_KEYMAP"), ChannelText.dump(km), "keymap");

        ZoomMap zoom = new ZoomMap(1, 2.0);
        zoom.setIdent("");
        assertTrue(zoom.test("Ident"));
        assertEquals(text("DUMP_BLANK_ZOOMMAP"), ChannelText.dump(zoom), "zoommap");
    }

    @Test
    void aBlankIdentReadsBackSet() {
        for (String dump : new String[] {"DUMP_BLANK_KEYMAP", "DUMP_BLANK_ZOOMMAP"}) {
            AstObject obj = ChannelText.read(text(dump));
            assertEquals("", obj.getIdent(), dump);
            assertTrue(obj.test("Ident"), dump);
        }
    }

    @Test
    void keymapIdentsAreReadAsCReadsThem() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : CASES.get("READS")) {
            String name = c.get(0).asText();
            AstObject obj = ChannelText.read(ChannelText.text(lines(c.get(1))));
            KeyMap km = assertInstanceOf(KeyMap.class, obj, name + ": not a KeyMap");
            String got = km.getIdent() + "|" + km.test("Ident");
            String want = c.get(2).get(0).asText() + "|" + c.get(2).get(1).asBoolean();
            AstObject inner = km.mapGet0A("inner");
            String gotInner = inner instanceof KeyMap ? inner.getIdent() + "|" + inner.test("Ident") : null;
            JsonNode wi = c.get(3);
            String wantInner =
                    wi.isNull() ? null : wi.get(0).asText() + "|" + wi.get(1).asBoolean();
            if (!got.equals(want) || (gotInner == null ? wantInner != null : !gotInner.equals(wantInner))) {
                failures.add(name + ": got " + got + " " + gotInner + ", C gives " + want + " " + wantInner);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void aCopyKeepsIdent() {
        AstObject o = new PlainObject();
        o.setIdent("ident");
        AstObject copy = o.copy();
        assertEquals("ident", copy.getIdent());
        assertTrue(copy.test("Ident"));
    }
}
