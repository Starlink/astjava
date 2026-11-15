package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of a KeyMap: the data type, a scalar or vector value, the comment, and the two age
 * counters C keeps in {@code struct AstMapEntry}.
 *
 * <p>The values are held as Java objects of the type's class: {@code Integer} for {@code
 * AST__INTTYPE} and {@code AST__BYTETYPE} (0 to 255), {@code Short} for {@code AST__SINTTYPE},
 * {@code Long} for {@code AST__KINTTYPE}, {@code Float}, {@code Double}, {@code String}, and an
 * {@link AstObject} or null for {@code AST__OBJECTTYPE}. An undefined entry holds no values.
 *
 * <p>Ported from Rust {@code keymap/entry.rs}.
 */
final class KeyMapEntry {

    /** The data type, one of the {@code AST__<X>TYPE} codes. */
    final int type;

    /**
     * C's {@code nel}: 0 for a scalar, otherwise the length of the vector. For an undefined entry
     * this is the length it was given.
     */
    int nel;

    /** The values: one for a scalar, {@code nel} for a vector, none for an undefined entry. */
    final List<Object> values;

    /** The comment, or null. */
    String comment;

    /** The value of the KeyMap's member counter when a value was last stored under the key. */
    long member;

    /** The value of the KeyMap's member counter when the key was first stored. */
    long keymember;

    private KeyMapEntry(int type, int nel, List<Object> values, String comment) {
        this.type = type;
        this.nel = nel;
        this.values = values;
        this.comment = comment;
    }

    /** A scalar entry. */
    static KeyMapEntry scalar(int type, Object value, String comment) {
        List<Object> v = new ArrayList<>(1);
        v.add(value);
        return new KeyMapEntry(type, 0, v, comment);
    }

    /** A vector entry. */
    static KeyMapEntry vector(int type, List<Object> values, String comment) {
        return new KeyMapEntry(type, values.size(), new ArrayList<>(values), comment);
    }

    /** An undefined entry. */
    static KeyMapEntry undefined(String comment) {
        return new KeyMapEntry(KeyMap.AST__UNDEFTYPE, 0, new ArrayList<>(), comment);
    }

    /** Whether the entry has a value. */
    boolean defined() {
        return type != KeyMap.AST__UNDEFTYPE;
    }

    /** The number of values: 1 for a scalar, the vector length otherwise. */
    int len() {
        return defined() ? values.size() : nel;
    }

    /** Whether this is a vector entry of length zero, which may not be stored. */
    boolean isEmptyVector() {
        return defined() && nel == 0 && values.isEmpty();
    }

    /** Makes a scalar a vector of length 1. */
    void promoteToVector() {
        if (defined() && nel == 0) {
            nel = values.size();
        }
    }

    /** Sets element {@code index} of a vector, appending when it is the length of the vector. */
    void setElement(int index, Object value) {
        if (index == values.size()) {
            values.add(value);
            nel = values.size();
        } else {
            values.set(index, value);
        }
    }

    /**
     * A copy of this entry, as C's {@code CopyMapEntry} makes it: the Objects it holds are copied
     * ({@code astCopy}), not shared.
     */
    KeyMapEntry copy() {
        List<Object> v = new ArrayList<>(values.size());
        for (Object o : values) {
            v.add(o instanceof AstObject ? ((AstObject) o).copy() : o);
        }
        KeyMapEntry e = new KeyMapEntry(type, nel, v, comment);
        e.member = member;
        e.keymember = keymember;
        return e;
    }

    /** The single KeyMap held by an Object entry of one element, or null. */
    KeyMap singleKeyMap() {
        if (type == KeyMap.AST__OBJECTTYPE && values.size() == 1 && values.get(0) instanceof KeyMap) {
            return (KeyMap) values.get(0);
        }
        return null;
    }

    /** The comment on a dump's {@code Typ} item for {@code type} (keymap.c {@code DumpEntry}). */
    static String typeComment(int type) {
        switch (type) {
            case KeyMap.AST__STRINGTYPE:
                return "Item data type (string)";
            case KeyMap.AST__OBJECTTYPE:
                return "Item data type (AST Object)";
            case KeyMap.AST__INTTYPE:
                return "Item data type (int)";
            case KeyMap.AST__SINTTYPE:
                return "Item data type (short int)";
            case KeyMap.AST__KINTTYPE:
                return "Item data type (64 bit int)";
            case KeyMap.AST__BYTETYPE:
                return "Item data type (unsigned byte)";
            case KeyMap.AST__DOUBLETYPE:
                return "Item data type (double)";
            case KeyMap.AST__FLOATTYPE:
                return "Item data type (float)";
            case KeyMap.AST__UNDEFTYPE:
                return "Item data type (undefined)";
            default:
                throw Ast.err(AstException.AST__INTER, "KeyMap: no entry has the data type " + type + ".");
        }
    }

    /** Whether {@code code} is a data type a KeyMap entry can have. */
    static boolean isEntryType(int code) {
        switch (code) {
            case KeyMap.AST__INTTYPE:
            case KeyMap.AST__DOUBLETYPE:
            case KeyMap.AST__STRINGTYPE:
            case KeyMap.AST__OBJECTTYPE:
            case KeyMap.AST__FLOATTYPE:
            case KeyMap.AST__SINTTYPE:
            case KeyMap.AST__UNDEFTYPE:
            case KeyMap.AST__BYTETYPE:
            case KeyMap.AST__KINTTYPE:
                return true;
            default:
                return false;
        }
    }
}
