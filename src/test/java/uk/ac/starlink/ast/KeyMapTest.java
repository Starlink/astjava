package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_keymap.rs}, itself a port of {@code
 * ast_tester/testkeymap.c}.
 *
 * <p>As in C, a KeyMap holds the Object it is given and a read returns that same Object, while a
 * copy of the KeyMap, {@code mapCopy} and {@code mapCopyEntry} copy the Objects they hold. The
 * blocks of testkeymap.c that change a nested KeyMap through the caller's own reference are
 * therefore translated as C writes them.
 *
 * <p>The tests built on the seven scalar entries of testkeymap.c, which store a SkyFrame, are
 * translated with SkyFrame (Task 18): {@code scalar_puts_and_types}, {@code
 * scalar_cross_type_reads} and {@code a_scalar_reads_as_a_length_one_vector}. Those that also
 * store a SpecFrame, which is outside the first phase, are on the deferred list.
 */
class KeyMapTest {

    private static AstException assertAst(int status, Runnable r) {
        AstException e = assertThrows(AstException.class, r::run);
        assertEquals(status, e.getStatus(), e.getMessage());
        return e;
    }

    private static void assertContains(String want, AstException e) {
        assertTrue(e.getMessage().contains(want), e.getMessage());
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    /** The nested KeyMap stored under {@code key}: the Object stored, not a copy. */
    private static KeyMap nestedKeyMap(KeyMap km, String key) {
        AstObject obj = km.mapGet0A(key);
        assertInstanceOf(KeyMap.class, obj, "expected a nested KeyMap at " + key);
        return (KeyMap) obj;
    }

    /** The keys read back in {@code mapKey} order. */
    private static List<String> readKeys(KeyMap km) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < km.mapSize(); i++) {
            out.add(km.mapKey(i));
        }
        return out;
    }

    @Test
    void scalarPutAndGetRoundTripEveryType() {
        KeyMap km = new KeyMap();
        km.mapPut0I("INT", -42, null);
        km.mapPut0S("SINT", (short) -7, null);
        km.mapPut0K("KINT", -10L * Integer.MAX_VALUE, null);
        km.mapPut0B("BYTE", 200, null);
        km.mapPut0F("FLOAT", 1.5f, null);
        km.mapPut0D("DOUBLE", -0.25, null);
        km.mapPut0C("STRING", "Hello", null);

        assertEquals(7, km.mapSize());
        assertEquals(-42, km.mapGet0I("INT"));
        assertEquals((short) -7, km.mapGet0S("SINT"));
        assertEquals(-10L * Integer.MAX_VALUE, km.mapGet0K("KINT"));
        assertEquals(200, km.mapGet0B("BYTE"));
        assertEquals(1.5f, km.mapGet0F("FLOAT"));
        assertEquals(-0.25, km.mapGet0D("DOUBLE"));
        assertEquals("Hello", km.mapGet0C("STRING"));
    }

    @Test
    void getConvertsBetweenTypes() {
        KeyMap km = new KeyMap();
        km.mapPut0D("D", 2.5, null);
        // ConvertValue rounds to the nearest integer, halves away from zero.
        assertEquals(3, km.mapGet0I("D"));
        assertEquals("2.5", km.mapGet0C("D"));

        km.mapPut0C("S", "42", null);
        assertEquals(42, km.mapGet0I("S"));
        assertEquals(42.0, km.mapGet0D("S"));

        km.mapPut0C("JUNK", "hello", null);
        AstException e = assertAst(AstException.AST__MPGER, () -> km.mapGet0I("JUNK"));
        assertContains("The value of KeyMap key \"JUNK\" cannot be read using the requested data type.", e);
    }

    @Test
    void aMissingKeyIsNotAnErrorUnlessKeyErrorIsSet() {
        KeyMap km = new KeyMap();
        assertNull(km.mapGet0I("NOPE"));

        km.setKeyError(true);
        AstException e = assertAst(AstException.AST__MPKER, () -> km.mapGet0I("NOPE"));
        assertContains("No value was found for NOPE in the supplied KeyMap.", e);
    }

    @Test
    void keysIgnoreTrailingSpacesAndHonourKeyCase() {
        KeyMap km = new KeyMap();
        km.mapPut0I("FRED", 1, null);
        // KeyCmp ignores trailing spaces, so the padded key is the same key.
        assertEquals(1, km.mapGet0I("FRED   "));
        assertEquals(1, km.mapSize());
        // Case matters by default.
        assertNull(km.mapGet0I("fred"));

        // testkeymap.c testcasesens: with KeyCase 0 the key is stored in upper case, so a
        // differently cased lookup succeeds, but a leading space still makes another key.
        KeyMap ci = new KeyMap();
        ci.setKeyCase(false);
        ci.mapPut0I("FREDS", 1, null);
        assertEquals(1, ci.mapGet0I("freds"));
        assertEquals(1, ci.mapGet0I("fReDs"));
        assertNull(ci.mapGet0I(" fReDs"));
    }

    @Test
    void rePuttingAKeyReplacesTheValueAndKeepsTheSize() {
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, "first comment");
        km.mapPut0C("A", "second", null);
        assertEquals(1, km.mapSize());
        assertEquals("second", km.mapGet0C("A"));
    }

    @Test
    void mapLockedBlocksNewKeysButNotReplacements() {
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, null);
        km.setMapLocked(true);

        // Replacing an existing key is still allowed.
        km.mapPut0I("A", 2, null);
        assertEquals(2, km.mapGet0I("A"));

        AstException e = assertAst(AstException.AST__BADKEY, () -> km.mapPut0I("B", 1, null));
        assertContains("astMapPut0I(KeyMap): Failed to add item \"B\" to a KeyMap: \"B\" is not a known item.", e);
        assertEquals(1, km.mapSize());
    }

    @Test
    void vectorPutAndGetRoundTrip() {
        KeyMap km = new KeyMap();
        km.mapPut1I("VI", new int[] {1, 2, 3}, null);
        km.mapPut1D("VD", new double[] {1.5, -2.5}, "two doubles");
        km.mapPut1C("VC", new String[] {"Position", "Position", "Time", "Energy"}, null);

        assertArrayEquals(new int[] {1, 2, 3}, km.mapGet1I("VI"));
        assertArrayEquals(new double[] {1.5, -2.5}, km.mapGet1D("VD"));
        assertArrayEquals(new String[] {"Position", "Position", "Time", "Energy"}, km.mapGet1C("VC"));

        assertEquals(3, km.mapLength("VI"));
        assertEquals(4, km.mapLength("VC"));
        // A scalar has length 1 through astMapLength.
        km.mapPut0I("S", 1, null);
        assertEquals(1, km.mapLength("S"));
        // A missing key has length 0.
        assertEquals(0, km.mapLength("NOPE"));
    }

    @Test
    void mapLengthHonoursKeyCase() {
        // astMapLength (keymap.c) converts the key with ConvertKey before the table lookup.
        KeyMap km = new KeyMap();
        km.setKeyCase(false);
        km.mapPut1I("FREDS", new int[] {1, 2, 3}, null);
        assertEquals(3, km.mapLength("fReDs"));
    }

    @Test
    void get1ConvertsEachElement() {
        KeyMap km = new KeyMap();
        km.mapPut1C("V", new String[] {"1", "2.6", "3"}, null);
        assertArrayEquals(new int[] {1, 3, 3}, km.mapGet1I("V"));

        // One element that cannot be converted fails the whole read, naming the element with a
        // one-based number.
        km.mapPut1C("BAD", new String[] {"1", "nope"}, null);
        AstException e = assertAst(AstException.AST__MPGER, () -> km.mapGet1I("BAD"));
        assertContains("element 2 of KeyMap key \"BAD\"", e);
    }

    @Test
    void get1ReadsAScalarAsALengthOneVector() {
        KeyMap km = new KeyMap();
        km.mapPut0I("S", 7, null);
        assertArrayEquals(new int[] {7}, km.mapGet1I("S"));
    }

    @Test
    void get0OnAVectorReturnsTheFirstElement() {
        // MAKE_MAPGET0 (keymap.c) takes the entry's storage base whether the entry is a scalar or
        // a vector, so a scalar read of a vector reads its first element.
        KeyMap km = new KeyMap();
        km.mapPut1I("V", new int[] {10, 20, 30}, null);
        assertEquals(10, km.mapGet0I("V"));

        km.mapPut1C("C", new String[] {"first", "second"}, null);
        assertEquals("first", km.mapGet0C("C"));
    }

    @Test
    void putElemCreatesAndAppends() {
        KeyMap km = new KeyMap();

        // Absent key: creates a vector of length 1.
        km.mapPutElemI("A", 0, 10);
        assertEquals(1, km.mapLength("A"));
        assertArrayEquals(new int[] {10}, km.mapGet1I("A"));

        // In-range index: overwrites.
        km.mapPutElemI("A", 0, 11);
        assertArrayEquals(new int[] {11}, km.mapGet1I("A"));

        // Out-of-range index: appends at the end, whatever the index was.
        km.mapPutElemI("A", 99, 12);
        assertArrayEquals(new int[] {11, 12}, km.mapGet1I("A"));

        // A scalar becomes a vector.
        km.mapPut0I("B", 1, null);
        km.mapPutElemI("B", 1, 2);
        assertArrayEquals(new int[] {1, 2}, km.mapGet1I("B"));
    }

    @Test
    void putElemCommentRetentionDiffersOnCreateVsPromote() {
        // MAKE_MAPPUTELEM (keymap.c): the branch that creates an entry calls astMapPut1<X> with a
        // NULL comment, so an undefined entry's comment is dropped; the branch that makes a scalar
        // a vector passes the entry's own comment, which survives.
        KeyMap km = new KeyMap();
        km.mapPutU("CREATED", "dropped on create");
        km.mapPutElemI("CREATED", 0, 1);
        assertNull(km.mapComment("CREATED"));

        km.mapPut0I("PROMOTED", 1, "kept on promote");
        km.mapPutElemI("PROMOTED", 1, 2);
        assertEquals("kept on promote", km.mapComment("PROMOTED"));
    }

    @Test
    void putElemOnALockedMapReportsTheCreatePathName() {
        // The create branch of MAKE_MAPPUTELEM calls astMapPut1<X>, whose MapLocked check names
        // astMapPut1I, not astMapPutElemI.
        KeyMap km = new KeyMap();
        km.setMapLocked(true);

        String want = "astMapPut1I(KeyMap): Failed to add item \"NEW\" to a KeyMap: \"NEW\" is not a known item.";
        AstException e = assertAst(AstException.AST__BADKEY, () -> km.mapPut1I("NEW", new int[] {1}, null));
        assertContains(want, e);

        e = assertAst(AstException.AST__BADKEY, () -> km.mapPutElemI("NEW", 0, 1));
        assertContains(want, e);
    }

    @Test
    void putElemConvertsOrReportsMpper() {
        KeyMap km = new KeyMap();
        km.mapPut1I("V", new int[] {1, 2}, null);
        // The value is converted to the entry's own type.
        km.mapPutElemD("V", 0, 2.6);
        assertArrayEquals(new int[] {3, 2}, km.mapGet1I("V"));

        km.mapPut1A("OBJ", new AstObject[] {new KeyMap()}, null);
        AstException e = assertAst(AstException.AST__MPPER, () -> km.mapPutElemI("OBJ", 0, 1));
        assertContains("The supplied value cannot be converted to the data type of KeyMap key \"OBJ\".", e);
    }

    @Test
    void getElemRejectsAnOutOfRangeIndex() {
        KeyMap km = new KeyMap();
        km.mapPut1I("V", new int[] {1, 2, 3}, null);
        assertEquals(1, km.mapGetElemI("V", 0));
        assertEquals(3, km.mapGetElemI("V", 2));

        // Unlike a store, a read does not accept an index outside the vector.
        AstException e = assertAst(AstException.AST__MPVIN, () -> km.mapGetElemI("V", 3));
        assertContains(
                "astMapGetElem<X>(KeyMap): Illegal zero-based vector index 3 supplied for KeyMap entry 'V' - the "
                        + "vector has 3 elements.",
                e);
    }

    /**
     * The out-of-range message is the C library's text for each method: the numeric methods share
     * one naming {@code astMapGetElem<X>}, and the string and Object methods each have their own,
     * whose upper limit is one more than the vector length (C-oracle probe).
     */
    @Test
    void getElemOutOfRangeMessagesMatchTheCLibrary() {
        KeyMap km = new KeyMap();
        km.mapPut1I("V", new int[] {1, 2, 3}, null);
        km.mapPut1A("O", new AstObject[] {new KeyMap(), new KeyMap(), new KeyMap()}, null);

        AstException e = assertAst(AstException.AST__MPVIN, () -> km.mapGetElemD("V", 3));
        assertContains("astMapGetElem<X>(KeyMap): Illegal zero-based vector index 3", e);

        e = assertAst(AstException.AST__MPVIN, () -> km.mapGetElemC("V", 3));
        assertContains(
                "astMapGetElemC(KeyMap): Illegal vector index 4 supplied for KeyMap entry 'V' - should be in the "
                        + "range 1 to 4.",
                e);

        e = assertAst(AstException.AST__MPVIN, () -> km.mapGetElemA("O", 3, new AstObject[1]));
        assertContains(
                "astMapGetElemA(KeyMap): Illegal vector index 4 supplied for KeyMap entry 'O' - should be in the "
                        + "range 1 to 4.",
                e);
    }

    @Test
    void getElemConversionFailureNamesTheOneBasedElement() {
        // MAKE_MAPGETELEM's AST__MPGER message (keymap.c) uses elem + 1, unlike its AST__MPVIN
        // message, which uses the zero-based elem.
        KeyMap km = new KeyMap();
        km.mapPut1C("V", new String[] {"1", "nope", "3"}, null);
        assertEquals(1, km.mapGetElemI("V", 0));

        AstException e = assertAst(AstException.AST__MPGER, () -> km.mapGetElemI("V", 1));
        assertContains(
                "astMapGetElemI(KeyMap): The value of element 2 of KeyMap key \"V\" cannot be read using the "
                        + "requested data type.",
                e);
    }

    @Test
    void mapLenCReportsTheLongestElement() {
        // testkeymap.c asserts 4, 0 and 5 for these three cases.
        KeyMap km = new KeyMap();
        km.mapPut0I("I", 1000, null);
        assertEquals(4, km.mapLenC("I"));

        // A missing key has no elements, so no length.
        assertEquals(0, km.mapLenC("NOPE"));

        km.mapPut1C("V", new String[] {"Hello", "", "Hello"}, null);
        assertEquals(5, km.mapLenC("V"));
    }

    @Test
    void mapLenCHonoursKeyCase() {
        // astMapLenC (keymap.c) converts the key with ConvertKey before the table lookup.
        KeyMap km = new KeyMap();
        km.setKeyCase(false);
        km.mapPut0I("FREDS", 1000, null);
        assertEquals(4, km.mapLenC("fReDs"));
    }

    @Test
    void mapGetCJoinsWithTheCFixedStride() {
        // testkeymap.c: the elements "Hello", " " and "  Hello" give a longest length of 7, and
        // C's join walks each fixed-size slot as a null-terminated string.
        KeyMap km = new KeyMap();
        km.mapPut1C("V", new String[] {"Hello", " ", "  Hello"}, null);
        assertEquals(7, km.mapLenC("V"));
        String got = km.mapGetC("V");
        assertEquals("(Hello, ,  Hello)", got);
        assertEquals(17, got.length());

        // An empty element's slot holds only null bytes, which read back as an empty string.
        km.mapPut1C("W", new String[] {"Hello", "", "Hello"}, null);
        assertEquals(5, km.mapLenC("W"));
        assertEquals("(Hello,,Hello)", km.mapGetC("W"));

        // A length-1 entry has no parentheses: C passes it to astMapGet0C.
        km.mapPut0C("S", "Hello", null);
        assertEquals("Hello", km.mapGetC("S"));

        // A missing key gives nothing.
        assertNull(km.mapGetC("NOPE"));
    }

    @Test
    void putUCreatesAnUndefinedEntry() {
        KeyMap km = new KeyMap();
        km.mapPutU("U", "no value yet");

        // The key is there, but has no value.
        assertTrue(km.mapHasKey("U"));
        assertFalse(km.mapDefined("U"));
        assertEquals(KeyMap.AST__UNDEFTYPE, km.mapType("U"));
        assertEquals(1, km.mapSize());
        assertEquals("no value yet", km.mapComment("U"));

        // Reading it gives no value, not an error, even with KeyError set.
        km.setKeyError(true);
        assertNull(km.mapGet0I("U"));

        // Putting a value over it defines it.
        km.mapPut0I("U", 3, null);
        assertTrue(km.mapDefined("U"));
        assertEquals(3, km.mapGet0I("U"));
    }

    @Test
    void mapTypeAndHasKeyOnAMissingKey() {
        KeyMap km = new KeyMap();
        // Neither reports an error, and neither consults KeyError.
        assertEquals(KeyMap.AST__BADTYPE, km.mapType("NOPE"));
        assertFalse(km.mapHasKey("NOPE"));
        assertFalse(km.mapDefined("NOPE"));
    }

    @Test
    void mapDefinedOnAMissingKeyConsultsKeyError() {
        // astMapDefined's KeyError branch is taken for an absent key, but not for one that is
        // present with no value (keymap.c).
        KeyMap km = new KeyMap();
        km.setKeyError(true);

        AstException e = assertAst(AstException.AST__MPKER, () -> km.mapDefined("NOPE"));
        assertContains("No value was found for NOPE in the supplied KeyMap.", e);

        km.mapPutU("U", null);
        assertFalse(km.mapDefined("U"));
    }

    @Test
    void mapDefinedMissingKeyErrorUsesTheCaseFoldedKey() {
        // astMapDefined's AST__MPKER message quotes the key as ConvertKey gives it.
        KeyMap km = new KeyMap();
        km.setKeyCase(false);
        km.setKeyError(true);

        AstException e = assertAst(AstException.AST__MPKER, () -> km.mapDefined("nope"));
        assertContains("No value was found for NOPE in the supplied KeyMap.", e);
    }

    @Test
    void mapRemoveIsSilentOnAMissingKey() {
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, null);
        km.mapRemove("NOPE");
        assertEquals(1, km.mapSize());
        km.mapRemove("A");
        assertEquals(0, km.mapSize());
        assertFalse(km.mapHasKey("A"));
    }

    @Test
    void mapRenameMovesTheValueAndTheComment() {
        KeyMap km = new KeyMap();
        km.mapPut0I("OLD", 42, "a comment");
        km.mapRename("OLD", "NEW");

        assertFalse(km.mapHasKey("OLD"));
        assertEquals(42, km.mapGet0I("NEW"));
        assertEquals("a comment", km.mapComment("NEW"));
        assertEquals(1, km.mapSize());

        // Renaming a key to itself does nothing at all.
        km.mapRename("NEW", "NEW");
        assertEquals(42, km.mapGet0I("NEW"));

        // Renaming a missing key does nothing, and is not an error.
        km.mapRename("GONE", "SOMETHING");
        assertEquals(1, km.mapSize());

        // Renaming onto an occupied key replaces it.
        km.mapPut0I("OTHER", 7, null);
        km.mapRename("NEW", "OTHER");
        assertEquals(1, km.mapSize());
        assertEquals(42, km.mapGet0I("OTHER"));
    }

    @Test
    void mapRenameRespectsMapLocked() {
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, null);
        km.mapPut0I("B", 2, null);
        km.setMapLocked(true);

        // Renaming onto an existing key is allowed: no new key appears.
        km.mapRename("A", "B");
        assertEquals(1, km.mapGet0I("B"));

        // Renaming onto a new key is not.
        AstException e = assertAst(AstException.AST__BADKEY, () -> km.mapRename("B", "C"));
        assertContains("Failed to rename item \"B\" in a KeyMap to \"C\": \"C\" is not a known item.", e);

        // A refused rename leaves the KeyMap unchanged, as C reports the error before removing
        // anything (upstream 89d0edfa).
        assertTrue(km.mapHasKey("B"));
        assertEquals(1, km.mapGet0I("B"));
        assertEquals(1, km.mapSize());
    }

    @Test
    void mapRenameLockedErrorUsesTheCaseFoldedKeys() {
        // astMapRename's AST__BADKEY message gives the keys as ConvertKey converts them, folded
        // but with their trailing spaces (C-oracle build).
        KeyMap km = new KeyMap();
        km.setKeyCase(false);
        km.mapPut0I("a", 1, null);
        km.setMapLocked(true);

        AstException e = assertAst(AstException.AST__BADKEY, () -> km.mapRename("a", "new key  "));
        assertContains(
                "Failed to rename item \"A\" in a KeyMap to \"NEW KEY  \": \"NEW KEY  \" is not a known item.", e);

        // The refused rename left the KeyMap unchanged.
        assertTrue(km.mapHasKey("a"));
        assertEquals(1, km.mapGet0I("a"));
    }

    @Test
    void mapKeyWalksTheEntriesInOrder() {
        KeyMap km = new KeyMap();
        // Put in an order that is not alphabetical.
        km.mapPut0I("Size", 1, null);
        km.mapPut0I("Error", 2, null);
        km.mapPut0I("Name", 3, null);
        km.mapPut0I("Resolution", 4, null);

        assertEquals(4, km.mapSize());
        // KeyCmp order; KeyCase defaults to case sensitive, so the keys keep their case.
        assertEquals(Arrays.asList("Error", "Name", "Resolution", "Size"), readKeys(km));

        // An index out of range is an error.
        AstException e = assertAst(AstException.AST__MPIND, () -> km.mapKey(4));
        assertContains("Cannot find element 4 (zero-based) of the KeyMap.", e);
    }

    @Test
    void mapKeyOnAnEmptyKeymapIsOutOfRange() {
        // GetKey (keymap.c) finds no entry at index 0 of an empty KeyMap.
        KeyMap km = new KeyMap();
        AstException e = assertAst(AstException.AST__MPIND, () -> km.mapKey(0));
        assertContains("Cannot find element 0 (zero-based) of the KeyMap.", e);
    }

    @Test
    void mapIterateOnAnEmptyKeymapReturnsNone() {
        // astMapIterate (keymap.c) has nothing to walk.
        KeyMap km = new KeyMap();
        assertNull(km.mapIterate(true));
        assertNull(km.mapIterate(false));
    }

    @Test
    void mapIterateVisitsEveryKeyOnceAndThenStops() {
        KeyMap km = new KeyMap();
        for (String k : new String[] {"Size", "Error", "Name"}) {
            km.mapPut0C(k, "x", null);
        }

        List<String> seen = new ArrayList<>();
        String key = km.mapIterate(true);
        while (key != null) {
            seen.add(key);
            // A runaway iteration would hang the test, so it is bounded.
            assertTrue(seen.size() <= 3, "iteration did not terminate: " + seen);
            key = km.mapIterate(false);
        }
        assertEquals(Arrays.asList("Error", "Name", "Size"), seen);

        // Once the walk is over the position is reset, so a new walk works.
        assertEquals("Error", km.mapIterate(false));
    }

    @Test
    void mapIterateTerminatesWithSortBySet() {
        KeyMap km = new KeyMap();
        for (String k : new String[] {"A", "B", "C"}) {
            km.mapPut0I(k, 1, null);
        }
        km.setSortBy(KeyMapSort.KEY_UP);

        int count = 0;
        String key = km.mapIterate(true);
        while (key != null) {
            count++;
            assertTrue(count <= 3, "iteration did not terminate");
            key = km.mapIterate(false);
        }
        assertEquals(3, count);
    }

    @Test
    void sortByReordersTheReadOut() {
        KeyMap km = new KeyMap();
        // Put oldest first, not alphabetically.
        km.mapPut0I("Zulu", 1, null);
        km.mapPut0I("Alpha", 2, null);
        km.mapPut0I("Mike", 3, null);
        // Putting the oldest key again advances its value age but not its key age.
        km.mapPut0I("Zulu", 9, null);

        km.setSortBy(KeyMapSort.KEY_UP);
        assertEquals(Arrays.asList("Alpha", "Mike", "Zulu"), readKeys(km));

        km.setSortBy(KeyMapSort.KEY_DOWN);
        assertEquals(Arrays.asList("Zulu", "Mike", "Alpha"), readKeys(km));

        // AgeUp is newest first, and Zulu's second put made it the newest value.
        km.setSortBy(KeyMapSort.AGE_UP);
        assertEquals(Arrays.asList("Zulu", "Mike", "Alpha"), readKeys(km));

        km.setSortBy(KeyMapSort.AGE_DOWN);
        assertEquals(Arrays.asList("Alpha", "Mike", "Zulu"), readKeys(km));

        // KeyAgeUp ignores the second put, so Zulu is still the oldest key and sorts last.
        km.setSortBy(KeyMapSort.KEY_AGE_UP);
        assertEquals(Arrays.asList("Mike", "Alpha", "Zulu"), readKeys(km));

        km.setSortBy(KeyMapSort.KEY_AGE_DOWN);
        assertEquals(Arrays.asList("Zulu", "Alpha", "Mike"), readKeys(km));

        // Clearing returns to the alphabetical default.
        km.clearSortBy();
        assertEquals(Arrays.asList("Alpha", "Mike", "Zulu"), readKeys(km));
    }

    @Test
    void mapCopyAddsAndReplaces() {
        KeyMap src = new KeyMap();
        src.mapPut0I("NEW", 1, "from source");
        src.mapPut0I("BOTH", 2, null);

        KeyMap dest = new KeyMap();
        dest.mapPut0C("BOTH", "old", null);
        dest.mapPut0I("ONLYDEST", 3, null);

        dest.mapCopy(src);

        assertEquals(3, dest.mapSize());
        assertEquals(1, dest.mapGet0I("NEW"));
        assertEquals("from source", dest.mapComment("NEW"));
        // The replaced entry takes the source's type as well as its value.
        assertEquals(KeyMap.AST__INTTYPE, dest.mapType("BOTH"));
        assertEquals(2, dest.mapGet0I("BOTH"));
        assertEquals(3, dest.mapGet0I("ONLYDEST"));
    }

    @Test
    void mapCopyKeepsTheDestinationKeyAgeForAReplacedKey() {
        KeyMap dest = new KeyMap();
        dest.mapPut0I("FIRST", 1, null);
        dest.mapPut0I("SECOND", 2, null);

        KeyMap src = new KeyMap();
        src.mapPut0I("FIRST", 9, null);

        dest.mapCopy(src);

        // FIRST is still the older key, so KeyAgeDown puts it first.
        dest.setSortBy(KeyMapSort.KEY_AGE_DOWN);
        assertEquals(Arrays.asList("FIRST", "SECOND"), readKeys(dest));
    }

    @Test
    void mapCopyRefusesANewKeyInALockedDestination() {
        KeyMap src = new KeyMap();
        src.mapPut0I("EXISTS", 1, null);
        src.mapPut0I("FRESH", 2, null);

        KeyMap dest = new KeyMap();
        dest.mapPut0I("EXISTS", 0, null);
        dest.setMapLocked(true);

        AstException e = assertAst(AstException.AST__BADKEY, () -> dest.mapCopy(src));
        assertContains("Failed to copy item \"FRESH\": \"FRESH\" is not a known item.", e);
    }

    @Test
    void mapCopyMergesNestedKeyMapsInsteadOfReplacingThem() {
        KeyMap innerSrc = new KeyMap();
        innerSrc.mapPut0I("FROMSRC", 1, null);
        KeyMap src = new KeyMap();
        src.mapPut0A("NESTED", innerSrc, null);

        KeyMap innerDest = new KeyMap();
        innerDest.mapPut0I("FROMDEST", 2, null);
        KeyMap dest = new KeyMap();
        dest.mapPut0A("NESTED", innerDest, null);

        dest.mapCopy(src);

        // The nested KeyMap was merged, so it holds both keys; it is the destination's own.
        KeyMap nested = nestedKeyMap(dest, "NESTED");
        assertSame(innerDest, nested);
        assertEquals(2, nested.mapSize());
        assertEquals(1, nested.mapGet0I("FROMSRC"));
        assertEquals(2, nested.mapGet0I("FROMDEST"));
    }

    @Test
    void mapCopyReplacesANestedKeyMapWhenTheOtherSideIsNotOne() {
        // A destination entry that is not an Object is replaced, not merged.
        KeyMap inner = new KeyMap();
        inner.mapPut0I("A", 1, null);
        KeyMap src = new KeyMap();
        src.mapPut0A("K", inner, null);

        KeyMap dest = new KeyMap();
        dest.mapPut0I("K", 7, null);
        dest.mapCopy(src);

        assertEquals(KeyMap.AST__OBJECTTYPE, dest.mapType("K"));
    }

    @Test
    void mapCopyEntryCopiesOneKeyAndHonoursMerge() {
        KeyMap src = new KeyMap();
        src.mapPut0I("WANTED", 1, null);
        src.mapPut0I("UNWANTED", 2, null);

        KeyMap dest = new KeyMap();
        dest.mapCopyEntry("WANTED", src, false);
        assertEquals(1, dest.mapSize());
        assertEquals(1, dest.mapGet0I("WANTED"));

        // A key the source does not hold is ignored.
        dest.mapCopyEntry("MISSING", src, false);
        assertEquals(1, dest.mapSize());

        // Without merge, a nested KeyMap is replaced rather than merged.
        KeyMap innerSrc = new KeyMap();
        innerSrc.mapPut0I("FROMSRC", 1, null);
        KeyMap src2 = new KeyMap();
        src2.mapPut0A("N", innerSrc, null);

        KeyMap innerDest = new KeyMap();
        innerDest.mapPut0I("FROMDEST", 2, null);
        KeyMap dest2 = new KeyMap();
        dest2.mapPut0A("N", innerDest, null);

        dest2.mapCopyEntry("N", src2, false);
        KeyMap nested = nestedKeyMap(dest2, "N");
        assertEquals(1, nested.mapSize());
        assertTrue(nested.mapHasKey("FROMSRC"));
        assertFalse(nested.mapHasKey("FROMDEST"));
    }

    @Test
    void mapCopyMergesThroughALengthOneVector() {
        // C's merge accepts nel 0 or 1, so a vector of one KeyMap merges through its element 0.
        KeyMap innerSrc = new KeyMap();
        innerSrc.mapPut0I("FROMSRC", 1, null);
        KeyMap src = new KeyMap();
        src.mapPut1A("N", new AstObject[] {innerSrc}, null);

        KeyMap innerDest = new KeyMap();
        innerDest.mapPut0I("FROMDEST", 2, null);
        KeyMap dest = new KeyMap();
        dest.mapPut1A("N", new AstObject[] {innerDest}, null);

        dest.mapCopy(src);

        AstObject[] nested = dest.mapGet1A("N");
        assertEquals(1, nested.length);
        assertInstanceOf(KeyMap.class, nested[0], "expected a nested KeyMap");
        assertEquals(2, ((KeyMap) nested[0]).mapSize());
    }

    @Test
    void mapCopyEntryLockedDestinationErrorKeepsTrailingSpaces() {
        // C's astMapCopyEntry quotes the key ConvertKey gives, before its trailing spaces are
        // removed, in the AST__BADKEY message.
        KeyMap src = new KeyMap();
        src.mapPut0I("FRESH", 1, null);

        KeyMap dest = new KeyMap();
        dest.setMapLocked(true);

        AstException e = assertAst(AstException.AST__BADKEY, () -> dest.mapCopyEntry("FRESH   ", src, false));
        assertContains("Failed to copy item \"FRESH   \": \"FRESH   \" is not a known item.", e);
    }

    // The tests below follow ast_tester/testkeymap.c in its own order: its two static helper
    // functions first, then main.

    /**
     * Writes {@code km}, reads it back and compares entry by entry, as {@code checkdump}
     * (testkeymap.c) does: sizes, then for each key the type and either the class of the Object
     * or the value as a string.
     */
    private static void checkDump(KeyMap km) {
        AstObject back = ChannelText.read(ChannelText.dump(km));
        assertInstanceOf(KeyMap.class, back, "round trip did not give a KeyMap");
        KeyMap result = (KeyMap) back;

        // checkDump 1.
        assertEquals(km.mapSize(), result.mapSize());
        for (int i = 0; i < result.mapSize(); i++) {
            String key = result.mapKey(i);
            int ty = result.mapType(key);
            // checkDump 4.
            assertEquals(km.mapType(key), ty, "key " + key);
            if (ty == KeyMap.AST__OBJECTTYPE) {
                // checkDump 5 / 6 / 7.
                AstObject a = result.mapGet0A(key);
                AstObject b = km.mapGet0A(key);
                assertEquals(b == null ? null : b.getClass(), a == null ? null : a.getClass(), "key " + key);
            } else {
                // checkDump 8 / 9 / 10.
                assertEquals(km.mapGet0C(key), result.mapGet0C(key), "key " + key);
            }
        }
    }

    @Test
    void caseInsensitiveKeys() {
        // testcasesens (testkeymap.c).
        KeyMap map = new KeyMap();
        map.setKeyCase(false);
        map.mapPut0I("Freds", 1999, "com 1");

        // Error case 1.
        assertTrue(map.mapHasKey("fReDs"));
        // Error case 2: the stored key is in upper case.
        assertEquals("FREDS", map.mapKey(0));
        // Error case 3 / 4 / 4b: an int reads as the string "1999".
        String sval = map.mapGet0C("freds");
        assertEquals("1999", sval);
        assertEquals(4, sval.length());

        // Error case 5: setting KeyCase to the value it has is allowed however full the KeyMap
        // is; clearing it on a non-empty KeyMap would change the value, so is AST__NOWRT.
        map.setKeyCase(false);
        assertAst(AstException.AST__NOWRT, map::clearKeyCase);

        // Error case 6 / 7.
        assertEquals(1, map.mapSize());
        map.mapRemove("freDs");
        assertEquals(0, map.mapSize());

        // Error case 8: on the empty KeyMap the clear succeeds, and with KeyCase at its default a
        // leading space and the case both matter.
        map.clearKeyCase();
        map.mapPut0I("Freds", 1999, "com 1");
        assertFalse(map.mapHasKey(" fReDs"));
    }

    /** The five keys testsorting (testkeymap.c) puts, in put order. */
    private static final List<String> SORT_KEYS =
            Collections.unmodifiableList(Arrays.asList("ABC", "zzzzzzzzzzz", "this_is_a_key", "HE-HE", "A"));

    /** A KeyMap holding {@link #SORT_KEYS} put in order with the values 1 to 5. */
    private static KeyMap sortFixture(KeyMapSort sortBy) {
        KeyMap km = new KeyMap();
        km.setSortBy(sortBy);
        for (int i = 0; i < SORT_KEYS.size(); i++) {
            km.mapPut0I(SORT_KEYS.get(i), i + 1, " ");
        }
        return km;
    }

    private static List<String> reversed(List<String> keys) {
        List<String> r = new ArrayList<>(keys);
        Collections.reverse(r);
        return r;
    }

    @Test
    void sortAgeDownAndUp() {
        // testsorting, first block (testkeymap.c).
        KeyMap km = sortFixture(KeyMapSort.AGE_DOWN);

        // Error Sort 1: AgeDown gives put order.
        assertEquals(SORT_KEYS, readKeys(km));

        // Error Sort 2: AgeUp reverses it.
        km.setSortBy(KeyMapSort.AGE_UP);
        assertEquals(reversed(SORT_KEYS), readKeys(km));

        // Error Sort 2b / 2c: putting an existing key again makes it the newest value, so under
        // AgeDown it moves to the end.
        km.mapPut0I(SORT_KEYS.get(0), 10, " ");
        km.setSortBy(KeyMapSort.AGE_DOWN);
        assertEquals(
                Arrays.asList(SORT_KEYS.get(1), SORT_KEYS.get(2), SORT_KEYS.get(3), SORT_KEYS.get(4), SORT_KEYS.get(0)),
                readKeys(km));
    }

    @Test
    void sortKeyAge() {
        // testsorting, second block (testkeymap.c).
        KeyMap km = sortFixture(KeyMapSort.KEY_AGE_DOWN);

        // Error Sort 0.
        assertEquals(SORT_KEYS, readKeys(km));

        // Error Sort -1.
        km.setSortBy(KeyMapSort.KEY_AGE_UP);
        assertEquals(reversed(SORT_KEYS), readKeys(km));

        // Error Sort -2: putting an existing key again does not change its key age.
        km.mapPut0I(SORT_KEYS.get(0), 10, " ");
        km.setSortBy(KeyMapSort.KEY_AGE_DOWN);
        assertEquals(SORT_KEYS, readKeys(km));
    }

    @Test
    void sortKeyAlphabetical() {
        // testsorting, third block (testkeymap.c).
        List<String> skeys = Arrays.asList("A", "ABC", "HE-HE", "this_is_a_key", "zzzzzzzzzzz");

        KeyMap km = sortFixture(KeyMapSort.KEY_UP);

        // Error Sort 3: KeyCmp compares bytes, so upper-case letters sort before lower-case ones.
        assertEquals(skeys, readKeys(km));

        // Error Sort 4.
        km.setSortBy(KeyMapSort.KEY_DOWN);
        assertEquals(reversed(skeys), readKeys(km));
    }

    @Test
    void fiveHundredEntries() {
        // main (testkeymap.c): 200 int keys, 299 int64 keys and one int64 key holding a value no
        // int could.
        KeyMap map = new KeyMap();
        for (int i = 1; i <= 200; i++) {
            map.mapPut0I("Fred" + i, i, " ");
        }
        for (int i = 201; i <= 499; i++) {
            map.mapPut0K("Fred" + i, i, " ");
        }
        map.mapPut0K("Fred500", Integer.MAX_VALUE * 100L, " ");

        // Error d1.
        assertEquals(500, map.mapSize());
        // Error d2 / d3.
        assertEquals(KeyMap.AST__INTTYPE, map.mapType("Fred123"));
        assertEquals(KeyMap.AST__KINTTYPE, map.mapType("Fred234"));
        // Error d4 / d5.
        assertEquals("489", map.mapGet0C("Fred489"));
        // Error d6 / d7.
        assertEquals((long) Integer.MAX_VALUE, map.mapGet0K("Fred500") / 100);

        // checkDump 2.
        checkDump(map);
    }

    @Test
    void putElemGrowsTheVector() {
        // main (testkeymap.c).
        KeyMap map = new KeyMap();
        map.mapPut1I("Fredi", new int[] {1, 2}, "com 1");

        // Error GETELEM_1 / GETELEM_2: an index in range overwrites.
        map.mapPutElemI("Fredi", 0, -1);
        assertEquals(-1, map.mapGetElemI("Fredi", 0));

        // Error GETELEM_3 / GETELEM_4: index 9 is past the length 2, so the value lands at index 2.
        map.mapPutElemI("Fredi", 9, -2);
        assertEquals(-2, map.mapGetElemI("Fredi", 2));

        // Error GETELEM_5 / GETELEM_6: index -1 appends (keymap.c MAKE_MAPPUTELEM).
        map.mapPutElemI("Fredi", -1, -3);
        assertEquals(-3, map.mapGetElemI("Fredi", 3));

        // Error GETELEM_7.
        assertEquals(4, map.mapLength("Fredi"));

        // Error GETELEM_8: an element store under an absent key creates a vector of length 1.
        KeyMap map2 = new KeyMap();
        map2.mapPutElemA("A A", 0, map);
        assertEquals(1, map2.mapLength("A A"));

        // Error GETELEM_9 / GETELEM_10 / GETELEM_11: the nested KeyMap holds the four elements.
        AstObject[] aval = new AstObject[1];
        assertTrue(map2.mapGetElemA("A A", 0, aval));
        assertInstanceOf(KeyMap.class, aval[0], "expected a nested KeyMap");
        assertEquals(-3, ((KeyMap) aval[0]).mapGetElemI("Fredi", 3));

        // Error GETELEM_12: storing a KeyMap in itself is AST__KYCIR (keymap.c CheckCircle).
        assertAst(AstException.AST__KYCIR, () -> map2.mapPutElemA("A A", 0, map2));
    }

    @Test
    void mapLockedRejectsNewKeys() {
        // main (testkeymap.c). As in C, km2 is the KeyMap stored in map, so changes through either
        // reference are changes to the one KeyMap.
        KeyMap map = new KeyMap();
        map.mapPut0C(" B", "Hello", " ");

        // Error GETELEM_12B: a new key in a locked KeyMap is AST__BADKEY.
        map.setMapLocked(true);
        assertAst(AstException.AST__BADKEY, () -> map.mapPut0C(" BZZ", "Bye Bye", " "));
        map.clearMapLocked();

        // Unlocked, the same put succeeds.
        map.mapPut0C(" BZZ", "Bye Bye", " ");

        KeyMap km2 = new KeyMap();
        km2.mapPut0C(" BZZ", "Bye Bye", " ");
        map.mapPut0A(" BZY", km2, " ");
        assertSame(km2, nestedKeyMap(map, " BZY"));

        // Error GETELEM_12C: MapLocked set on the outer KeyMap is set in every nested KeyMap, where
        // it refuses a new key but not a replacement.
        map.setMapLocked(true);
        map.mapPut0C(" BZZ", "You Bye", " ");

        assertTrue(km2.getMapLocked());
        km2.mapPut0C(" BZZ", "You Bye", " ");
        assertAst(AstException.AST__BADKEY, () -> km2.mapPut0C(" BZA", "No Bye", " "));

        // Clearing it on the outer KeyMap clears the nested one too.
        map.clearMapLocked();
        assertFalse(km2.getMapLocked());
        km2.mapPut0C(" BZA", "No Bye", " ");
    }

    @Test
    void keyErrorIsInheritedByNestedMaps() {
        // main (testkeymap.c), with the nested KeyMap reached through the reference the caller
        // stored, as in C.
        KeyMap map = new KeyMap();
        KeyMap km2 = new KeyMap();
        map.mapPut0A(" BZY", km2, " ");

        // Error GETELEM_12D.
        assertFalse(nestedKeyMap(map, " BZY").getKeyError());

        // Error GETELEM_12E.
        map.setKeyError(true);
        assertTrue(km2.getKeyError());

        // Error GETELEM_12F: with KeyError set, a missing key in the nested KeyMap is AST__MPKER.
        assertAst(AstException.AST__MPKER, () -> km2.mapGet0C("FRED"));

        map.clearKeyError();
        assertFalse(km2.getKeyError());
        assertNull(km2.mapGet0C("FRED"));
    }

    @Test
    void putElemCAppends() {
        // main (testkeymap.c). KeyCmp ignores trailing spaces, so " B " and " B" are one key, but
        // the leading space matters in both.
        KeyMap map = new KeyMap();
        map.mapPut0C(" B", "Hello", " ");

        map.mapPutElemC(" B ", 2, "YES YES");

        // Error GETELEM_13: the scalar became a vector, and index 2, past its length, appended at
        // index 1.
        assertEquals(2, map.mapLength(" B"));
        // Error GETELEM_14 / GETELEM_15.
        assertEquals("YES YES", map.mapGetElemC(" B ", 1));
    }

    /** The KeyMap holding the three "a" keys testkeymap.c puts in map1; a3 is int64 or int. */
    private static KeyMap mapCopySourceA(boolean a3IsInt64) {
        KeyMap map1 = new KeyMap();
        map1.mapPut0I("a1", 1, " ");
        map1.mapPut0I("a2", 2, " ");
        if (a3IsInt64) {
            map1.mapPut0K("a3", 3, " ");
        } else {
            map1.mapPut0I("a3", 3, " ");
        }
        return map1;
    }

    /** The mapcopy destination: two scalars and a nested KeyMap. */
    private static KeyMap mapCopyDest(boolean a3IsInt64) {
        KeyMap map = new KeyMap();
        map.mapPut0C("aa1", "Yes", " ");
        map.mapPut0I("aa2", 2, " ");
        map.mapPut0A("aa3", mapCopySourceA(a3IsInt64), " ");
        return map;
    }

    /** The nested KeyMap the mapcopy source holds. */
    private static KeyMap mapCopyNestedB() {
        KeyMap map2 = new KeyMap();
        map2.mapPut0I("b1", 10, " ");
        map2.mapPut0I("b2", 20, " ");
        map2.mapPut0I("b3", 30, " ");
        return map2;
    }

    @Test
    void mapCopyMergesNestedKeyMaps() {
        // main (testkeymap.c). The source's nested KeyMap is under "bb3", which the destination
        // does not have, so it is added whole.
        KeyMap map = mapCopyDest(true);

        KeyMap map3 = new KeyMap();
        map3.mapPut0C("bb1", "No", " ");
        map3.mapPut0I("aa2", 20, " ");
        map3.mapPut0A("bb3", mapCopyNestedB(), " ");

        map.mapCopy(map3);

        // Error MAPCOPY_0.
        assertEquals(5, map.mapSize());

        // Error MAPCOPY_1 / MAPCOPY_2: a key only the destination had survives.
        assertEquals("Yes", map.mapGet0C("aa1"));
        // Error MAPCOPY_3 / MAPCOPY_4: a key both had takes the source's value.
        assertEquals(20, map.mapGet0I("aa2"));
        // Error MAPCOPY_5 / MAPCOPY_6.
        assertInstanceOf(KeyMap.class, map.mapGet0A("aa3"));
        // Error MAPCOPY_13 / MAPCOPY_14.
        assertEquals("No", map.mapGet0C("bb1"));
        // Error MAPCOPY_15 / MAPCOPY_16.
        assertInstanceOf(KeyMap.class, map.mapGet0A("bb3"));
    }

    @Test
    void mapCopyReplacesANestedKeyMap() {
        // main (testkeymap.c). Every source key is in the destination; the scalars are replaced,
        // but the KeyMaps under the shared key "aa3" are merged (keymap.c MapCopy).
        KeyMap map = mapCopyDest(false);

        KeyMap map3 = new KeyMap();
        map3.mapPut0I("aa1", 0, " ");
        map3.mapPut0I("aa2", 20, " ");
        map3.mapPut0A("aa3", mapCopyNestedB(), " ");

        map.mapCopy(map3);

        // Error MAPCOPY_23.
        assertEquals(3, map.mapSize());

        // Error MAPCOPY_24 / MAPCOPY_25: the string entry was replaced by an int one.
        assertEquals(0, map.mapGet0I("aa1"));
        // Error MAPCOPY_26 / MAPCOPY_27.
        assertEquals(20, map.mapGet0I("aa2"));
        // Error MAPCOPY_28 / MAPCOPY_29: the nested KeyMap holds both sides' keys.
        KeyMap nested = nestedKeyMap(map, "aa3");
        assertEquals(6, nested.mapSize());
        assertEquals(1, nested.mapGet0I("a1"));
        assertEquals(30, nested.mapGet0I("b3"));
    }

    @Test
    void mapCopyIntoALockedMapFails() {
        // main (testkeymap.c). What fails is the merge of the nested "aa3" KeyMaps: MapLocked is
        // set in nested KeyMaps too, and b1, b2 and b3 are new keys there.
        KeyMap map = mapCopyDest(false);

        KeyMap map3 = new KeyMap();
        map3.mapPut0I("aa1", 0, " ");
        map3.mapPut0I("aa2", 20, " ");
        map3.mapPut0A("aa3", mapCopyNestedB(), " ");

        map.setMapLocked(true);

        // Error MAPCOPY_42.
        assertAst(AstException.AST__BADKEY, () -> map.mapCopy(map3));
    }

    @Test
    void undefinedEntries() {
        // main (testkeymap.c).
        KeyMap map = new KeyMap();
        map.mapPutU("GG", "A comment");

        // Error UNDEF_0.
        assertFalse(map.mapDefined("GG"));
        // Error UNDEF_1 / UNDEF_1B / UNDEF_2 / UNDEF_3 / UNDEF_4: every read gives no value,
        // without an error.
        assertNull(map.mapGet0I("GG"));
        assertNull(map.mapGet0S("GG"));
        assertNull(map.mapGet0C("GG"));
        assertFalse(map.mapGet0A("GG", new AstObject[1]));
        assertNull(map.mapGet1I("GG"));
        // Error UNDEF_5: "gg" is a missing key, which with KeyError clear gives no value.
        assertNull(map.mapGetElemC("gg", 0));
        // Error UNDEF_6.
        assertTrue(map.mapHasKey("GG"));

        // Error UNDEF_7 / UNDEF_8.
        assertEquals(KeyMap.AST__UNDEFTYPE, map.mapType("GG"));
        assertEquals(1, map.mapSize());

        // Error UNDEF_9 / UNDEF_10: a put over the undefined entry defines it.
        map.mapPut0I("GG", 0, " ");
        assertEquals(0, map.mapGet0I("GG"));
    }

    @Test
    void getElemOnAnUndefinedEntryReturnsNoneNotError() {
        // astMapGetElem<X> (keymap.c) reports success for an undefined entry without writing a
        // value, which here is no value.
        KeyMap map = new KeyMap();
        map.mapPutU("GG", "A comment");

        assertNull(map.mapGetElemI("GG", 0));
        assertNull(map.mapGetElemC("GG", 0));
        assertFalse(map.mapGetElemA("GG", 0, new AstObject[1]));

        // The range check comes first, so it still applies to an undefined entry.
        assertAst(AstException.AST__MPVIN, () -> map.mapGetElemI("GG", 1));
    }

    @Test
    void renameRoundTrip() {
        // main (testkeymap.c).
        KeyMap map = new KeyMap();
        map.mapPut0I("GG", 0, " ");

        map.mapRename("GG", "GGNEW");
        // Error RENAME_1 / RENAME_2 / RENAME_3.
        assertFalse(map.mapHasKey("GG"));
        assertEquals(0, map.mapGet0I("GGNEW"));

        map.mapRename("GGNEW", "GG");
        // Error RENAME_4 / RENAME_5 / RENAME_6.
        assertFalse(map.mapHasKey("GGNEW"));
        assertEquals(0, map.mapGet0I("GG"));
    }

    @Test
    void mapCopyEntry() {
        // main (testkeymap.c), without merge.
        KeyMap map = mapCopyDest(false);

        KeyMap map3 = new KeyMap();
        map3.mapPut0C("bb1", "No", " ");
        map3.mapPut0I("aa2", 20, " ");
        map3.mapPut0A("aa3", mapCopyNestedB(), " ");

        map.mapCopyEntry("bb1", map3, false);
        // Error MAPCOPYENTRY_1: one key added, and only that one.
        assertEquals(4, map.mapSize());
        // Error MAPCOPYENTRY_2 / MAPCOPYENTRY_3.
        assertEquals("No", map.mapGet0C("bb1"));

        map.mapCopyEntry("aa3", map3, false);
        // Error MAPCOPYENTRY_4.
        KeyMap nested = nestedKeyMap(map, "aa3");
        // Error MAPCOPYENTRY_5 / MAPCOPYENTRY_6: the source's nested KeyMap is there in full.
        assertEquals(30, nested.mapGet0I("b3"));
        // Error MAPCOPYENTRY_7: without merge the destination's own nested KeyMap was replaced.
        assertNull(nested.mapGet0I("a1"));
    }

    @Test
    void putElemDAppendsFiveTimes() {
        // main (testkeymap.c): index -1 appends each time, and the first store creates the entry.
        KeyMap map = new KeyMap();
        for (double value : new double[] {1.0, 2.0, 3.0, 4.0, 15.0}) {
            map.mapPutElemD("HHH", -1, value);
        }
        // Error MAPPUTELEM_7.
        assertEquals(5, map.mapLength("HHH"));
        assertArrayEquals(new double[] {1.0, 2.0, 3.0, 4.0, 15.0}, map.mapGet1D("HHH"));
    }

    @Test
    void int64EdgeValues() {
        // main (testkeymap.c). The two values are outside the int range, so only an int64 entry
        // can hold them.
        KeyMap map = new KeyMap();
        map.mapPut0D("asa", 0.0, " ");
        map.mapPut0F("asar", 1.0f, " ");
        map.mapPut0I("dfdfd", 1, " ");

        long[] kvec = {-1L * Integer.MAX_VALUE, 10L * Integer.MIN_VALUE};
        map.mapPut1K("AAA", kvec, "com 1");
        // Index -1 appends; both values are converted to the entry's int64 type.
        map.mapPutElemI("AAA", -1, 1);
        map.mapPutElemK("AAA", -1, 1);
        // Error I8_1.
        assertEquals(4, map.mapLength("AAA"));

        map.mapPut0C("bb1", "No", " ");

        // Error I8_2 / I8_3.
        assertEquals(10L * Integer.MIN_VALUE, map.mapGetElemK("AAA", 1));

        // Error I8_4 / I8_5 / I8_6.
        assertArrayEquals(new long[] {-1L * Integer.MAX_VALUE, 10L * Integer.MIN_VALUE, 1, 1}, map.mapGet1K("AAA"));
    }

    // The tests below were added upstream with the KeyMap fixes of the f6762005 sync; each is
    // named for the C function in testkeymap.c it comes from.

    /** {@code testdumpstable}: a dump is the same across a write, read, write cycle, and whatever
     * the order the entries were added in. */
    @Test
    void dumpIsStableAgainstInsertionOrder() {
        String[] keys = {"DELTA", "EPSILON", "ETA", "ALPHA", "BETA", "GAMMA"};
        int[] order = {4, 2, 5, 0, 3, 1};

        KeyMap km1 = new KeyMap();
        for (int i = 0; i < keys.length; i++) {
            km1.mapPut0I(keys[i], i + 1, " ");
        }
        String dump1 = ChannelText.dump(km1);

        // Error dumpstable-2.
        String dump2 = ChannelText.dump(ChannelText.read(dump1));
        assertEquals(dump1, dump2, "dump changed on a write, read, write cycle");

        // Error dumpstable-3.
        KeyMap km2 = new KeyMap();
        for (int i : order) {
            km2.mapPut0I(keys[i], i + 1, " ");
        }
        assertEquals(dump1, ChannelText.dump(km2), "dump depends on the order the entries were added");
    }

    /** {@code testiterate}: iteration ends on a KeyMap with SortBy set. */
    @Test
    void iterationTerminatesWithSortbySet() {
        KeyMap km = new KeyMap();
        km.setSortBy(KeyMapSort.KEY_UP);
        km.mapPut0I("AAA", 1, " ");
        km.mapPut0I("BBB", 2, " ");
        km.mapPut0I("CCC", 3, " ");

        List<String> seen = new ArrayList<>();
        String key = km.mapIterate(true);
        while (key != null) {
            seen.add(key);
            if (seen.size() >= 100) {
                break;
            }
            key = km.mapIterate(false);
        }
        assertEquals(Arrays.asList("AAA", "BBB", "CCC"), seen, "iteration did not terminate");
    }

    /** {@code testrenamelocked}: a rename refused on a locked KeyMap leaves it unchanged. */
    @Test
    void aRefusedRenameKeepsTheEntry() {
        KeyMap km = new KeyMap();
        km.mapPut0I("KEEP", 42, " ");
        km.setMapLocked(true);

        // Error rename-locked-noerr: NEWKEY is not a key already.
        assertThrows(AstException.class, () -> km.mapRename("KEEP", "NEWKEY"));

        // Errors rename-locked-lost / -unreadable / -value / -newkey.
        assertTrue(km.mapHasKey("KEEP"));
        assertEquals(42, km.mapGet0I("KEEP"));
        assertFalse(km.mapHasKey("NEWKEY"));
    }

    /** {@code testconvrange}: a double outside a narrow integer's range saturates, and NaN gives
     * zero. */
    @Test
    void numericConversionSaturatesAndDefinesNan() {
        KeyMap km = new KeyMap();
        km.mapPut0D("BIG", 5_000_000_100.0, " ");
        km.mapPut0D("NEG", -5_000_000_100.0, " ");
        km.mapPut0D("NAN", Double.NaN, " ");

        // Errors range-int-max / range-int-min / range-short-max / range-byte-max.
        assertEquals(Integer.MAX_VALUE, km.mapGet0I("BIG"));
        assertEquals(Integer.MIN_VALUE, km.mapGet0I("NEG"));
        assertEquals(Short.MAX_VALUE, km.mapGet0S("BIG"));
        assertEquals(255, km.mapGet0B("BIG"));

        // Error range-byte-min: a negative value saturates at zero for an unsigned type.
        assertEquals(0, km.mapGet0B("NEG"));

        // Errors range-nan-int / -short / -int64 / -byte.
        assertEquals(0, km.mapGet0I("NAN"));
        assertEquals((short) 0, km.mapGet0S("NAN"));
        assertEquals(0L, km.mapGet0K("NAN"));
        assertEquals(0, km.mapGet0B("NAN"));
    }

    /** {@code testconvstring}: a numeric string too large for the type saturates, and a string
     * that is not a number is refused. */
    @Test
    void numericStringConversionSaturates() {
        KeyMap km = new KeyMap();
        km.mapPut0C("OVER", "9999999999", " ");
        km.mapPut0C("HUGE", "99999999999999999999999999999999999999", " ");
        km.mapPut0C("OK", "42", " ");
        km.mapPut0C("OKSPACE", " 42 ", " ");
        km.mapPut0C("BLANK", "   ", " ");
        km.mapPut0C("TRAIL", "12abc", " ");

        // Errors convstr-ok / convstr-okspace.
        assertEquals(42, km.mapGet0I("OK"));
        assertEquals(42, km.mapGet0I("OKSPACE"));

        // Errors convstr-blank / convstr-trail: not a number, so refused.
        assertThrows(AstException.class, () -> km.mapGet0I("BLANK"));
        assertThrows(AstException.class, () -> km.mapGet0I("TRAIL"));

        // Errors convstr-over / convstr-huge: the magnitude saturates.
        assertEquals(Integer.MAX_VALUE, km.mapGet0I("OVER"));
        assertEquals(Integer.MAX_VALUE, km.mapGet0I("HUGE"));
    }

    /** {@code testemptyvector}: a vector of length zero is refused, and the KeyMap still dumps. */
    @Test
    void aZeroLengthVectorIsRefused() {
        KeyMap km = new KeyMap();

        // Errors emptyvec-int-noerr / -str-noerr / -obj-noerr.
        assertAst(AstException.AST__NELIN, () -> km.mapPut1I("EMPTYI", new int[0], " "));
        assertAst(AstException.AST__NELIN, () -> km.mapPut1C("EMPTYC", new String[0], " "));
        assertAst(AstException.AST__NELIN, () -> km.mapPut1A("EMPTYA", new AstObject[0], " "));

        // Errors emptyvec-int-present / -str-present / -obj-present.
        assertFalse(km.mapHasKey("EMPTYI"));
        assertFalse(km.mapHasKey("EMPTYC"));
        assertFalse(km.mapHasKey("EMPTYA"));

        // Error emptyvec-dump.
        km.mapPut0I("REAL", 7, " ");
        ChannelText.dump(km);
    }

    /** {@code testundefelem}: the element reads give no value for an undefined entry, and an
     * index out of range is still a bad index. */
    @Test
    void elementAccessorsReportAnUndefinedEntry() {
        KeyMap km = new KeyMap();
        km.mapPutU("UNDEF", " ");

        // Error undef-get0.
        assertNull(km.mapGet0I("UNDEF"));

        // Errors undef-getelem-i / -c / -a.
        assertNull(km.mapGetElemI("UNDEF", 0));
        assertNull(km.mapGetElemC("UNDEF", 0));
        assertFalse(km.mapGetElemA("UNDEF", 0, new AstObject[1]));

        // Error undef-getelem-i-oob.
        assertAst(AstException.AST__MPVIN, () -> km.mapGetElemI("UNDEF", 5));
    }

    /** {@code testlockedroundtrip}: a locked KeyMap with entries survives a dump and reload. */
    @Test
    void aLockedKeymapSurvivesARoundTrip() {
        KeyMap km = new KeyMap();
        km.mapPut0I("AAA", 1, " ");
        km.mapPut0I("BBB", 2, " ");

        KeyMap nested = new KeyMap();
        nested.mapPut0I("INNER", 3, " ");
        km.mapPut0A("NEST", nested, " ");

        km.setMapLocked(true);
        String dump = ChannelText.dump(km);

        // Error locked-reload.
        AstObject reloaded = ChannelText.read(dump);
        assertInstanceOf(KeyMap.class, reloaded, "reload did not give a KeyMap");
        KeyMap km2 = (KeyMap) reloaded;

        // Errors locked-reload-aaa / -flag.
        assertEquals(1, km2.mapGet0I("AAA"));
        assertTrue(km2.getMapLocked(), "MapLocked was lost over the round trip");

        // Error locked-reload-nestflag.
        assertTrue(nestedKeyMap(km2, "NEST").getMapLocked(), "MapLocked did not reach the nested KeyMap");
    }

    /** {@code testcopyentrycase}: a copied key takes the destination's KeyCase, both to search the
     * destination and to be stored in it. */
    @Test
    void aCopiedKeyTakesTheDestinationsKeyCase() {
        KeyMap src = new KeyMap();
        src.setKeyCase(true);
        KeyMap dst = new KeyMap();
        dst.setKeyCase(false);

        src.mapPut0I("MixedKey", 11, " ");
        // The destination already holds the key in its own folded form.
        dst.mapPut0I("MIXEDKEY", 99, " ");

        dst.mapCopyEntry("MixedKey", src, false);

        // Error copyentry-dup: no second, differently cased copy of the key.
        assertEquals(1, dst.mapSize());
        assertEquals(11, dst.mapGet0I("MIXEDKEY"));
        assertEquals("MIXEDKEY", dst.mapKey(0));

        // The bulk copy agrees.
        KeyMap bulk = new KeyMap();
        bulk.setKeyCase(false);
        bulk.mapCopy(src);
        assertEquals("MIXEDKEY", bulk.mapKey(0));
        assertEquals(11, bulk.mapGet0I("MIXEDKEY"));
    }

    /**
     * With KeyCase 0 a key may have 200 characters, as {@code AST__MXKEYLEN} allows (C-oracle
     * probe: 200 is stored, 201 is {@code AST__BIGKEY}).
     */
    @Test
    void aCaseInsensitiveKeyMayHave200Characters() {
        KeyMap km = new KeyMap();
        km.setKeyCase(false);

        String longest = repeat('a', 200);
        km.mapPut0I(longest, 1, null);
        assertEquals(1, km.mapGet0I(longest));

        String tooLong = repeat('a', 201);
        AstException e = assertAst(AstException.AST__BIGKEY, () -> km.mapPut0I(tooLong, 2, null));
        assertContains("keys must be no more than 200 characters long", e);
    }

    /**
     * {@code astMapGetC} reports a key too long for a KeyMap with KeyCase 0 from the length query it
     * makes first (C-oracle probe).
     */
    @Test
    void mapGetCReportsAnOverLongKey() {
        KeyMap km = new KeyMap();
        km.setKeyCase(false);
        km.mapPut0I("A", 1, null);

        AstException e = assertAst(AstException.AST__BIGKEY, () -> km.mapGetC(repeat('a', 201)));
        assertContains("astMapLength(KeyMap): ", e);
    }

    /** {@code astMapLength} reports a key too long for a KeyMap with KeyCase 0 (C-oracle probe). */
    @Test
    void mapLengthReportsAnOverLongKey() {
        KeyMap km = new KeyMap();
        km.setKeyCase(false);
        km.mapPut0I("A", 1, null);

        assertEquals(0, km.mapLength(repeat('a', 200)));
        AstException e = assertAst(AstException.AST__BIGKEY, () -> km.mapLength(repeat('a', 201)));
        assertContains("astMapLength(KeyMap): ", e);
    }
}
