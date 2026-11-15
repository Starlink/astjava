package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Translation of the unit tests in Rust {@code src/keymap/mod.rs}. */
class KeyMapModuleTest {

    /**
     * keymap.c {@code MapPutElem<X>} changes an element of an existing vector in place, which does
     * not count as a put; a scalar is first re-put as a vector through {@code astMapPut1<X>}, which
     * counts once.
     */
    @Test
    void putElemCountsOnlyAPromotion() {
        KeyMap km = new KeyMap();
        km.mapPut1I("V", new int[] {1, 2}, null);
        long before = km.memberCount();
        km.mapPutElemI("V", 0, 5);
        km.mapPutElemI("V", 2, 6);
        assertEquals(before, km.memberCount());

        km.mapPut0I("S", 1, null);
        before = km.memberCount();
        km.mapPutElemI("S", 0, 5);
        assertEquals(before + 1, km.memberCount());
        km.mapPutElemI("S", 1, 6);
        assertEquals(before + 1, km.memberCount());
    }

    @Test
    void attributeDefaultsMatchTheCGetMacros() {
        KeyMap km = new KeyMap();
        // keymap.c, the astMAKE_GET block.
        assertTrue(km.getKeyCase());
        assertFalse(km.getKeyError());
        assertFalse(km.getMapLocked());
        assertSame(KeyMapSort.NONE, km.getSortBy());
        // MIN_TABLE_SIZE * MAX_ENTRIES_PER_TABLE_ENTRY; the C prose claims 300.
        assertEquals(160, km.getSizeGuess());

        assertFalse(km.test("KeyCase"));
        assertFalse(km.test("KeyError"));
        assertFalse(km.test("MapLocked"));
        assertFalse(km.test("SortBy"));
        assertFalse(km.test("SizeGuess"));
    }

    @Test
    void settingAndClearingTracksTheTestFlag() {
        KeyMap km = new KeyMap();
        km.setKeyError(true);
        assertTrue(km.getKeyError());
        assertTrue(km.test("KeyError"));
        km.clearKeyError();
        assertFalse(km.getKeyError());
        assertFalse(km.test("KeyError"));

        km.setSizeGuess(1000);
        assertEquals(1000, km.getSizeGuess());
        assertTrue(km.test("SizeGuess"));
        km.clearSizeGuess();
        assertEquals(160, km.getSizeGuess());
    }

    @Test
    void sizeGuessIsFrozenOnceTheMapIsNotEmpty() {
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, null);

        AstException e = assertThrows(AstException.class, () -> km.setSizeGuess(1000));
        assertEquals(AstException.AST__NOWRT, e.getStatus());
        assertTrue(
                e.getMessage().contains("Illegal attempt to change the SizeGuess attribute of a non-empty KeyMap."),
                e.getMessage());
        // The failed set left the attribute alone.
        assertFalse(km.test("SizeGuess"));

        e = assertThrows(AstException.class, km::clearSizeGuess);
        assertEquals(AstException.AST__NOWRT, e.getStatus());
        assertTrue(
                e.getMessage().contains("Illegal attempt to clear the SizeGuess attribute of a non-empty KeyMap."),
                e.getMessage());
    }

    @Test
    void keyCaseIsFrozenOnlyWhenTheValueWouldChange() {
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, null);

        // C's SetKeyCase checks for emptiness only when the value would change.
        assertTrue(km.getKeyCase());
        km.setKeyCase(true);
        assertTrue(km.test("KeyCase"));

        AstException e = assertThrows(AstException.class, () -> km.setKeyCase(false));
        assertEquals(AstException.AST__NOWRT, e.getStatus());
        assertTrue(
                e.getMessage().contains("Illegal attempt to change the KeyCase attribute of a non-empty KeyMap."),
                e.getMessage());
        assertTrue(km.getKeyCase());

        // Clearing is allowed: the stored value already matches the default, so ClearKeyCase
        // never checks for emptiness.
        km.clearKeyCase();
        assertFalse(km.test("KeyCase"));
        assertTrue(km.getKeyCase());
    }

    private static void checkLockedAndKeyError(KeyMap km, int depth) {
        assertTrue(km.getKeyError(), "depth " + depth);
        assertTrue(km.getMapLocked(), "depth " + depth);
        for (String key : km.sortedKeys()) {
            if (km.mapType(key) == KeyMap.AST__OBJECTTYPE && km.mapGet0A(key) instanceof KeyMap) {
                checkLockedAndKeyError((KeyMap) km.mapGet0A(key), depth + 1);
            }
        }
    }

    @Test
    void keyErrorAndMapLockedRecurseIntoNestedKeyMaps() {
        KeyMap inner = new KeyMap();
        inner.mapPut0I("X", 1, null);
        KeyMap middle = new KeyMap();
        middle.mapPut0A("INNER", inner, null);
        KeyMap outer = new KeyMap();
        outer.mapPut0A("MIDDLE", middle, null);

        outer.setKeyError(true);
        outer.setMapLocked(true);

        checkLockedAndKeyError(outer, 0);
    }

    @Test
    void sortByAndSizeGuessDoNotRecurse() {
        KeyMap inner = new KeyMap();
        inner.mapPut0I("X", 1, null);
        KeyMap outer = new KeyMap();
        outer.mapPut0A("INNER", inner, null);

        outer.setSortBy(KeyMapSort.KEY_UP);
        KeyMap nested = (KeyMap) outer.mapGet0A("INNER");
        assertSame(KeyMapSort.NONE, nested.getSortBy());
        assertFalse(nested.test("SortBy"));
    }
}
