package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_ident.rs}. Its tests write and read KeyMaps, ZoomMaps,
 * Frames and FrameSets, and are translated in the tasks that port those classes (Channel and
 * KeyMap, Mapping, Frame, FrameSet); this class starts with Ident on the Object itself.
 */
class IdentTest {

    @Test
    void aBlankIdentIsSet() {
        AstObject o = new PlainObject();
        assertFalse(o.test("Ident"));
        assertEquals("", o.getIdent());
        o.setIdent("");
        assertTrue(o.test("Ident"));
        assertEquals("", o.getIdent());
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
