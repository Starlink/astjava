package uk.ac.starlink.ast;

/**
 * The orders in which a KeyMap returns its keys, chosen by its SortBy attribute.
 *
 * <p>The age orders measure a key's age in one of two ways: {@code AgeUp} and {@code AgeDown} use
 * the time a value was last stored under the key, so storing a new value under an existing key
 * makes it the newest; {@code KeyAgeUp} and {@code KeyAgeDown} use the time the key was first
 * stored, which storing a new value under it does not change. "Up" puts the newest entry first and
 * "Down" the oldest, as keymap.c {@code CompareEntries} does.
 *
 * <p>Ported from Rust {@code keymap/sort.rs} ({@code SortBy}, {@code compare_entries}), which ports
 * keymap.c {@code CompareEntries}, {@code SortByInt} and {@code SortByString}.
 */
enum KeyMapSort {
    /**
     * No order is asked for; the keys are returned in the order of {@link #KEY_UP}. C returns
     * them in the order of its hash table.
     */
    NONE("None", 0),
    /** The key with the most recently stored value first. */
    AGE_UP("AgeUp", 1),
    /** The key with the most recently stored value last. */
    AGE_DOWN("AgeDown", 2),
    /** Alphabetical order, comparing the keys byte by byte, with {@code A...} first. */
    KEY_UP("KeyUp", 3),
    /** Reverse alphabetical order, with {@code A...} last. */
    KEY_DOWN("KeyDown", 4),
    /** The most recently added key first. */
    KEY_AGE_UP("KeyAgeUp", 5),
    /** The most recently added key last. */
    KEY_AGE_DOWN("KeyAgeDown", 6);

    private final String label;

    private final int number;

    KeyMapSort(String label, int number) {
        this.label = label;
        this.number = number;
    }

    /** The integer code of this order, C's ({@code SORTBY_NONE} 0 to {@code SORTBY_KEYAGEDOWN} 6). */
    int code() {
        return number;
    }

    /** The order with integer code {@code code}, or null. */
    static KeyMapSort fromCode(int code) {
        for (KeyMapSort s : values()) {
            if (s.number == code) {
                return s;
            }
        }
        return null;
    }

    /** The name of this order, as a dump records it (keymap.c {@code SortByString}). */
    String text() {
        return label;
    }

    /**
     * The order named by {@code value}, ignoring case and trailing white space as C's {@code
     * astChrMatch} does (keymap.c {@code SortByInt}).
     *
     * @param method the name of the calling function, for the error message
     * @throws AstException with status {@code AST__INTER} if {@code value} names no order
     */
    static KeyMapSort parse(String value, String method) {
        KeyMapSort[] tryOrder = {NONE, AGE_UP, AGE_DOWN, KEY_AGE_UP, KEY_AGE_DOWN, KEY_UP, KEY_DOWN};
        for (KeyMapSort s : tryOrder) {
            if (AstObject.chrMatch(value, s.label)) {
                return s;
            }
        }
        throw Ast.err(AstException.AST__INTER, method + "(KeyMap): Illegal SortBy value " + value + " encountered.");
    }

    /**
     * Orders two entries under this order (keymap.c {@code CompareEntries}), except that {@link
     * #NONE} orders by key rather than leaving the pair unordered.
     */
    int compare(String keyA, KeyMapEntry a, String keyB, KeyMapEntry b) {
        switch (this) {
            case AGE_UP:
                return Long.compare(b.member, a.member);
            case AGE_DOWN:
                return Long.compare(a.member, b.member);
            case KEY_AGE_UP:
                return Long.compare(b.keymember, a.keymember);
            case KEY_AGE_DOWN:
                return Long.compare(a.keymember, b.keymember);
            case KEY_UP:
            case NONE:
                return KeyMapTable.keyCmp(keyA, keyB);
            case KEY_DOWN:
                return KeyMapTable.keyCmp(keyB, keyA);
            default:
                throw Ast.err(AstException.AST__INTER, "KeyMap: unknown SortBy value " + this + ".");
        }
    }
}
