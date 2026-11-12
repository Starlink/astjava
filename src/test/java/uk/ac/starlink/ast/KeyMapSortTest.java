package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Translation of the unit tests in Rust {@code src/keymap/sort.rs}. */
class KeyMapSortTest {

    private static KeyMapEntry entryWith(long member, long keymember) {
        KeyMapEntry e = KeyMapEntry.scalar(KeyMap.AST__INTTYPE, 0, null);
        e.member = member;
        e.keymember = keymember;
        return e;
    }

    @Test
    void codesAndStringsMatchC() {
        // keymap.c SORTBY_* codes and SortByString.
        Object[][] expected = {
            {KeyMapSort.NONE, 0, "None"},
            {KeyMapSort.AGE_UP, 1, "AgeUp"},
            {KeyMapSort.AGE_DOWN, 2, "AgeDown"},
            {KeyMapSort.KEY_UP, 3, "KeyUp"},
            {KeyMapSort.KEY_DOWN, 4, "KeyDown"},
            {KeyMapSort.KEY_AGE_UP, 5, "KeyAgeUp"},
            {KeyMapSort.KEY_AGE_DOWN, 6, "KeyAgeDown"},
        };
        for (Object[] row : expected) {
            KeyMapSort s = (KeyMapSort) row[0];
            int code = (Integer) row[1];
            String text = (String) row[2];
            assertEquals(code, s.code(), text);
            assertSame(s, KeyMapSort.fromCode(code), text);
            assertEquals(text, s.text());
            assertSame(s, KeyMapSort.parse(text, "astRead"));
        }
        assertNull(KeyMapSort.fromCode(7));
    }

    @Test
    void parseFoldsCaseAndTrailingSpace() {
        // astChrMatch folds case and tolerates trailing white space.
        assertSame(KeyMapSort.KEY_UP, KeyMapSort.parse("keyup", "astRead"));
        assertSame(KeyMapSort.KEY_AGE_DOWN, KeyMapSort.parse("KEYAGEDOWN  ", "astRead"));
        AstException lead = assertThrows(AstException.class, () -> KeyMapSort.parse("  none", "astRead"));
        assertEquals(AstException.AST__INTER, lead.getStatus());

        AstException e = assertThrows(AstException.class, () -> KeyMapSort.parse("Sideways", "astRead"));
        assertEquals(AstException.AST__INTER, e.getStatus());
        assertTrue(
                e.getMessage().contains("astRead(KeyMap): Illegal SortBy value Sideways encountered."), e.getMessage());
    }

    @Test
    void ageUpPutsTheNewestFirst() {
        // CompareEntries returns 1 when first->member < second->member.
        KeyMapEntry older = entryWith(1, 1);
        KeyMapEntry newer = entryWith(9, 9);
        assertTrue(KeyMapSort.AGE_UP.compare("A", older, "B", newer) > 0);
        assertTrue(KeyMapSort.AGE_DOWN.compare("A", older, "B", newer) < 0);
    }

    @Test
    void keyAgeUsesTheKeyCounterNotTheValueCounter() {
        // A re-put advances member but not keymember.
        KeyMapEntry a = entryWith(100, 1);
        KeyMapEntry b = entryWith(2, 9);
        assertTrue(KeyMapSort.KEY_AGE_UP.compare("A", a, "B", b) > 0);
        assertTrue(KeyMapSort.AGE_UP.compare("A", a, "B", b) < 0);
    }

    @Test
    void keyUpAndKeyDownAreKeyCmpEitherWayRound() {
        KeyMapEntry a = entryWith(1, 1);
        KeyMapEntry b = entryWith(2, 2);
        assertTrue(KeyMapSort.KEY_UP.compare("ALPHA", a, "BETA", b) < 0);
        assertTrue(KeyMapSort.KEY_DOWN.compare("ALPHA", a, "BETA", b) > 0);
        // Trailing spaces are ignored, as KeyCmp does.
        assertEquals(0, KeyMapSort.KEY_UP.compare("ALPHA  ", a, "ALPHA", b));
    }

    @Test
    void noneOrdersAlphabeticallyUnlikeC() {
        // C leaves SORTBY_NONE in hash table order; this port orders it by KeyCmp.
        KeyMapEntry a = entryWith(9, 9);
        KeyMapEntry b = entryWith(1, 1);
        assertTrue(KeyMapSort.NONE.compare("ALPHA", a, "BETA", b) < 0);
    }
}
