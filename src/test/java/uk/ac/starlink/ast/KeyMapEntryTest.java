package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Translation of the unit tests in Rust {@code src/keymap/entry.rs}. */
class KeyMapEntryTest {

    @Test
    void typeCodesMatchC() {
        // keymap.h: AST__BADTYPE 0 .. AST__KINTTYPE 10.
        assertEquals(0, KeyMap.AST__BADTYPE);
        assertEquals(1, KeyMap.AST__INTTYPE);
        assertEquals(2, KeyMap.AST__DOUBLETYPE);
        assertEquals(3, KeyMap.AST__STRINGTYPE);
        assertEquals(4, KeyMap.AST__OBJECTTYPE);
        assertEquals(5, KeyMap.AST__FLOATTYPE);
        assertEquals(7, KeyMap.AST__SINTTYPE);
        assertEquals(8, KeyMap.AST__UNDEFTYPE);
        assertEquals(9, KeyMap.AST__BYTETYPE);
        assertEquals(10, KeyMap.AST__KINTTYPE);

        // 0 is AST__BADTYPE and 6 is AST__POINTERTYPE: no entry has either.
        assertFalse(KeyMapEntry.isEntryType(0));
        assertFalse(KeyMapEntry.isEntryType(6));
        assertFalse(KeyMapEntry.isEntryType(11));
        for (int t : new int[] {1, 2, 3, 4, 5, 7, 8, 9, 10}) {
            assertTrue(KeyMapEntry.isEntryType(t), Integer.toString(t));
        }
    }

    @Test
    void typeCommentsMatchDumpentry() {
        // keymap.c DumpEntry, the Typ%d comments.
        assertEquals("Item data type (string)", KeyMapEntry.typeComment(KeyMap.AST__STRINGTYPE));
        assertEquals("Item data type (AST Object)", KeyMapEntry.typeComment(KeyMap.AST__OBJECTTYPE));
        assertEquals("Item data type (int)", KeyMapEntry.typeComment(KeyMap.AST__INTTYPE));
        assertEquals("Item data type (short int)", KeyMapEntry.typeComment(KeyMap.AST__SINTTYPE));
        assertEquals("Item data type (64 bit int)", KeyMapEntry.typeComment(KeyMap.AST__KINTTYPE));
        assertEquals("Item data type (unsigned byte)", KeyMapEntry.typeComment(KeyMap.AST__BYTETYPE));
        assertEquals("Item data type (double)", KeyMapEntry.typeComment(KeyMap.AST__DOUBLETYPE));
        assertEquals("Item data type (float)", KeyMapEntry.typeComment(KeyMap.AST__FLOATTYPE));
        assertEquals("Item data type (undefined)", KeyMapEntry.typeComment(KeyMap.AST__UNDEFTYPE));
    }

    @Test
    void scalarAndVectorNelDifferLikeC() {
        // C: nel == 0 marks a scalar; nel > 0 is a vector length.
        KeyMapEntry s = KeyMapEntry.scalar(KeyMap.AST__INTTYPE, 7, null);
        assertEquals(0, s.nel);
        assertEquals(1, s.len());
        assertTrue(s.defined());

        KeyMapEntry v = KeyMapEntry.vector(KeyMap.AST__INTTYPE, Arrays.<Object>asList(1, 2, 3), null);
        assertEquals(3, v.nel);
        assertEquals(3, v.len());

        KeyMapEntry u = KeyMapEntry.undefined(null);
        assertEquals(KeyMap.AST__UNDEFTYPE, u.type);
        assertFalse(u.defined());
    }
}
