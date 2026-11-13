package uk.ac.starlink.ast;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The parts of C's chained bucket table that the KeyMap still needs: the hash function, the
 * trailing-space-insensitive key comparison, key normalisation, and the arithmetic that gives the
 * {@code MapSz} item of a dump. Keys are compared and hashed as C sees them, byte by byte in their
 * UTF-8 encoding.
 *
 * <p>Ported from Rust {@code keymap/table.rs}, which ports keymap.c.
 */
final class KeyMapTable {

    /** The smallest hash table C ever allocates. */
    static final int MIN_TABLE_SIZE = 16;

    /** C doubles the table when a single bucket holds more than this many entries. */
    static final int MAX_ENTRIES_PER_TABLE_ENTRY = 10;

    /** {@code AST__MXKEYLEN}: the longest key accepted when KeyCase is 0. */
    static final int MXKEYLEN = 200;

    private KeyMapTable() {}

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * C's {@code astChrLen}: the length in bytes of a string without its trailing white space and
     * non-printing characters.
     */
    static int chrLen(String s) {
        byte[] b = bytes(s);
        int n = b.length;
        while (n > 0 && (b[n - 1] < 0x21 || b[n - 1] > 0x7e)) {
            n--;
        }
        return n;
    }

    /**
     * The bucket index for a key (keymap.c {@code HashFun}): djb2 ({@code hash * 33 + c}) seeded
     * at 5381, in an unsigned 64-bit register, masked by {@code bitmask}. Spaces are skipped, so
     * trailing spaces padding an F77 character variable are ignored. Each byte is read as a C
     * {@code char}, which is signed, so a byte at or above 0x80 adds its sign-extended value.
     */
    static int hashFun(String key, long bitmask) {
        long hash = 5381;
        for (byte c : bytes(key)) {
            if (c != ' ') {
                hash = (hash << 5) + hash + c;
            }
        }
        return (int) (hash & bitmask);
    }

    /**
     * Compares two keys ignoring trailing spaces (keymap.c {@code KeyCmp}): negative, zero or
     * positive. The first differing bytes are compared as signed C {@code char}s, so a byte at or
     * above 0x80 orders below every ASCII character.
     */
    static int keyCmp(String key1, String key2) {
        byte[] b1 = bytes(key1);
        byte[] b2 = bytes(key2);
        int i = 0;
        while (i < b1.length && i < b2.length && b1[i] == b2[i]) {
            i++;
        }
        if (i < b1.length && i < b2.length) {
            return Integer.compare(b1[i], b2[i]);
        }
        if (i < b1.length) {
            return allSpaces(b1, i) ? 0 : 1;
        }
        if (i < b2.length) {
            return allSpaces(b2, i) ? 0 : -1;
        }
        return 0;
    }

    private static boolean allSpaces(byte[] b, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two keys as C's {@code strcmp} does, by unsigned bytes: the order in which a dump
     * lists the entries (keymap.c {@code DumpCmp}).
     */
    static int strcmp(String key1, String key2) {
        byte[] b1 = bytes(key1);
        byte[] b2 = bytes(key2);
        int n = Math.min(b1.length, b2.length);
        for (int i = 0; i < n; i++) {
            if (b1[i] != b2[i]) {
                return Integer.compare(b1[i] & 0xff, b2[i] & 0xff);
            }
        }
        return Integer.compare(b1.length, b2.length);
    }

    /**
     * Normalises a key given to a method named {@code method} (keymap.c {@code ConvertKey}): when
     * KeyCase is 0 its ASCII letters are put in upper case, and a key longer than {@link
     * #MXKEYLEN} is an error.
     *
     * @throws AstException with status {@code AST__BIGKEY} if the key is too long
     */
    static String convertKey(String key, boolean keyCase, String method) {
        if (keyCase) {
            return key;
        }
        if (chrLen(key) > MXKEYLEN) {
            throw Ast.err(
                    AstException.AST__BIGKEY,
                    method + "(KeyMap): Supplied key '" + key + "' is too long (keys must be no more than " + MXKEYLEN
                            + " characters long).");
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
        }
        return sb.toString();
    }

    /** A key without its trailing spaces (only spaces), as the put functions store it. */
    static String trimSpaces(String key) {
        int n = key.length();
        while (n > 0 && key.charAt(n - 1) == ' ') {
            n--;
        }
        return key.substring(0, n);
    }

    /**
     * The table size C's {@code NewTable} allocates for a requested size: {@link #MIN_TABLE_SIZE}
     * at most, otherwise rounded up to a power of two.
     */
    static int newTableSize(int size) {
        if (size <= MIN_TABLE_SIZE) {
            return MIN_TABLE_SIZE;
        }
        int p = Integer.highestOneBit(size);
        return p == size ? p : p << 1;
    }

    /**
     * The {@code MapSz} value to dump for a KeyMap holding {@code keys}, in dump order: the size
     * of the table a KeyMap with that SizeGuess would have after the keys were added in that order,
     * by {@code NewTable} and {@code AddTableEntry}'s doubling rule. C writes its table's current
     * size, which also depends on keys added and removed before; this depends on the keys alone.
     */
    static int mapSz(List<String> keys, int sizeGuess) {
        int mapsize = newTableSize(sizeGuess / MAX_ENTRIES_PER_TABLE_ENTRY);
        int[] counts = new int[mapsize];
        for (int placed = 0; placed < keys.size(); placed++) {
            int itab = hashFun(keys.get(placed), mapsize - 1);
            counts[itab]++;
            // DoubleTableSize doubles once and redistributes, without re-checking the new
            // distribution, so a single insert never doubles twice.
            if (counts[itab] > MAX_ENTRIES_PER_TABLE_ENTRY) {
                mapsize *= 2;
                counts = new int[mapsize];
                for (int k = 0; k <= placed; k++) {
                    counts[hashFun(keys.get(k), mapsize - 1)]++;
                }
            }
        }
        return mapsize;
    }
}
