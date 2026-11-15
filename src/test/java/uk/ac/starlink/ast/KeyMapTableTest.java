package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Translation of the unit tests in Rust {@code src/keymap/table.rs}. */
class KeyMapTableTest {

    /**
     * keymap.c {@code HashFun}, restated from C rather than calling {@link KeyMapTable#hashFun}:
     * djb2 seeded at 5381, skipping spaces, each byte read as a signed C {@code char}.
     */
    private static long djb2(String s) {
        long h = 5381;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            if (b != ' ') {
                h = (h << 5) + h + b;
            }
        }
        return h;
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    @Test
    void hashFunIsDjb2SkippingSpaces() {
        long mask = KeyMapTable.MIN_TABLE_SIZE - 1;
        for (String key : new String[] {"A", "ABC", "HE-HE", "this_is_a_key", "zzzzzzzzzzz", "Size"}) {
            assertEquals((int) (djb2(key) & mask), KeyMapTable.hashFun(key, mask), key);
        }
        // Spaces anywhere are skipped, not just trailing ones.
        assertEquals(KeyMapTable.hashFun("A B  ", mask), KeyMapTable.hashFun("AB", mask));
        assertEquals((int) (5381L & mask), KeyMapTable.hashFun("", mask));
    }

    @Test
    void hashFunSignExtendsBytesAbove0x7f() {
        // "KéY" is UTF-8 bytes 4B C3 A9 59. C's HashFun reads each through a signed char,
        // giving the full hash 6384066965 (from C's HashFun body compiled verbatim); an unsigned
        // reading gives 6384354197, bucket 917 of 1024 rather than 405.
        String key = "KéY";
        assertEquals(405, KeyMapTable.hashFun(key, 1023));
        // At mask 15 the two readings agree.
        assertEquals(5, KeyMapTable.hashFun(key, 15));
    }

    @Test
    void keyCmpIgnoresTrailingSpaces() {
        assertEquals(0, KeyMapTable.keyCmp("FRED", "FRED"));
        assertEquals(0, KeyMapTable.keyCmp("FRED", "FRED   "));
        assertEquals(0, KeyMapTable.keyCmp("FRED   ", "FRED"));
        assertTrue(KeyMapTable.keyCmp("FRED", "FREDA") < 0);
        assertTrue(KeyMapTable.keyCmp("FREDA", "FRED") > 0);
        assertTrue(KeyMapTable.keyCmp("FRED", "FRED A") < 0);
        assertTrue(KeyMapTable.keyCmp("ABC", "ABD") < 0); // codespell:ignore abd
        // Leading and interior spaces are significant: ' ' sorts below every printing character.
        assertTrue(KeyMapTable.keyCmp(" FRED", "FRED") < 0);
    }

    @Test
    void keyCmpSignExtendsBytesAbove0x7f() {
        // C's KeyCmp compares differing bytes as signed chars: 0xC3 (-61) is below 0x7F. C's KeyCmp
        // body compiled verbatim returns -1 for these two byte strings.
        assertTrue(KeyMapTable.keyCmp("KéY", "K\u007fZ") < 0);
    }

    @Test
    void convertKeyUpperCasesOnlyWhenCaseInsensitive() {
        assertEquals("fReDs", KeyMapTable.convertKey("fReDs", true, "astMapPut0I"));
        assertEquals("FREDS", KeyMapTable.convertKey("fReDs", false, "astMapPut0I"));
        // KeyCase 1 skips the length check entirely, as C does.
        String longKey = repeat('x', 500);
        assertEquals(longKey, KeyMapTable.convertKey(longKey, true, "astMapPut0I"));
    }

    @Test
    void convertKeyRejectsOverLongKeysWhenCaseInsensitive() {
        // astChrLen strips trailing white space before the length test.
        String exact = repeat('x', KeyMapTable.MXKEYLEN);
        KeyMapTable.convertKey(exact, false, "astMapPut0I");
        KeyMapTable.convertKey(exact + "          ", false, "astMapPut0I");

        String tooLong = repeat('x', KeyMapTable.MXKEYLEN + 1);
        AstException e = assertThrows(AstException.class, () -> KeyMapTable.convertKey(tooLong, false, "astMapPut0I"));
        assertEquals(AstException.AST__BIGKEY, e.getStatus());
        assertTrue(e.getMessage().contains("keys must be no more than 200 characters long"), e.getMessage());
    }

    @Test
    void chrLenStripsTrailingWhitespaceAndNonPrinting() {
        assertEquals(4, KeyMapTable.chrLen("FRED"));
        assertEquals(4, KeyMapTable.chrLen("FRED   "));
        assertEquals(4, KeyMapTable.chrLen("FRED\t\n"));
        assertEquals(4, KeyMapTable.chrLen("FRED\0\0"));
        assertEquals(0, KeyMapTable.chrLen("   "));
        assertEquals(0, KeyMapTable.chrLen(""));
        // Interior white space is kept.
        assertEquals(3, KeyMapTable.chrLen("F R "));
    }

    @Test
    void newTableSizeRoundsUpToAPowerOfTwo() {
        assertEquals(16, KeyMapTable.newTableSize(0));
        assertEquals(16, KeyMapTable.newTableSize(1));
        assertEquals(16, KeyMapTable.newTableSize(16));
        assertEquals(32, KeyMapTable.newTableSize(17));
        assertEquals(32, KeyMapTable.newTableSize(32));
        assertEquals(64, KeyMapTable.newTableSize(33));
        assertEquals(1024, KeyMapTable.newTableSize(1000));
        assertEquals(1024, KeyMapTable.newTableSize(1024));
        assertEquals(262_144, KeyMapTable.newTableSize(200_000));
    }

    /** The default SizeGuess, which gives the smallest table. */
    private static final int DEFAULT_GUESS = KeyMapTable.MIN_TABLE_SIZE * KeyMapTable.MAX_ENTRIES_PER_TABLE_ENTRY;

    @Test
    void mapSzIsMinTableSizeForSmallMaps() {
        // Four keys in a 16-bucket table cannot put 11 in one bucket.
        assertEquals(16, KeyMapTable.mapSz(Arrays.asList("Size", "Name", "Resolution", "Error"), DEFAULT_GUESS));
    }

    @Test
    void mapSzHonoursSizeGuessLikeSetSizeGuess() {
        // SetSizeGuess divides by MAX_ENTRIES_PER_TABLE_ENTRY and NewTable clamps at
        // MIN_TABLE_SIZE, so any guess below 170 leaves the table at 16.
        List<String> none = Collections.emptyList();
        assertEquals(16, KeyMapTable.mapSz(none, 100));
        assertEquals(16, KeyMapTable.mapSz(none, 160));
        assertEquals(32, KeyMapTable.mapSz(none, 170));
        assertEquals(512, KeyMapTable.mapSz(none, 5000));
    }

    @Test
    void mapSzDoublesWhenABucketOverflows() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            keys.add("KEY_" + i);
        }
        int sz = KeyMapTable.mapSz(keys, DEFAULT_GUESS);
        assertTrue(sz > KeyMapTable.MIN_TABLE_SIZE, Integer.toString(sz));
        assertEquals(sz, Integer.highestOneBit(sz));
        List<String> reversed = new ArrayList<>(keys);
        Collections.reverse(reversed);
        assertEquals(sz, KeyMapTable.mapSz(reversed, DEFAULT_GUESS));
    }

    /** The first {@code n} keys {@code K<i>} that land in bucket 5 of a 16-bucket table. */
    private static List<String> collidingKeys(int n) {
        List<String> out = new ArrayList<>();
        for (long i = 0; out.size() < n; i++) {
            String key = "K" + i;
            if (KeyMapTable.hashFun(key, 15) == 5) {
                out.add(key);
            }
        }
        return out;
    }

    @Test
    void mapSzReplaysTheDoublingRuleForACollidingKeySet() {
        // Ten in one bucket is at the limit, so no doubling: the rule is "more than ten".
        assertEquals(16, KeyMapTable.mapSz(collidingKeys(10), DEFAULT_GUESS));
        // The eleventh forces a doubling; the exact sizes are pinned against the C oracle in
        // KeyMapSerialTest.
        assertTrue(KeyMapTable.mapSz(collidingKeys(11), DEFAULT_GUESS) > 16);
        int prev = 16;
        for (int n : new int[] {11, 40, 300}) {
            int sz = KeyMapTable.mapSz(collidingKeys(n), DEFAULT_GUESS);
            assertEquals(sz, Integer.highestOneBit(sz), "n=" + n);
            assertTrue(sz >= prev, "n=" + n + " gave " + sz + ", down from " + prev);
            prev = sz;
        }
        List<String> mixed = collidingKeys(40);
        for (int i = 0; i < 40; i++) {
            mixed.add("SPREAD_" + i);
        }
        assertTrue(KeyMapTable.mapSz(mixed, DEFAULT_GUESS) >= KeyMapTable.mapSz(collidingKeys(40), DEFAULT_GUESS));
    }
}
