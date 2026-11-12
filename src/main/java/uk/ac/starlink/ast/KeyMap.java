package uk.ac.starlink.ast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A set of values, each stored under a string key.
 *
 * <p>A KeyMap takes a key and gives back the value stored under it. It is not a {@link
 * AstObject}-to-coordinate Mapping. Keys are strings in which trailing spaces are ignored, so
 * {@code "RA"} and {@code "RA  "} name the same entry. Keys are case sensitive unless the KeyCase
 * attribute is 0, in which case every key is converted to upper case before it is used.
 *
 * <p>Each value is a scalar or a vector of at least one element, of type {@code int}, {@code
 * double}, {@code String} or {@link AstObject} (and, through the KeyMap's other functions, {@code
 * short}, {@code long}, unsigned byte or {@code float}). The {@code mapPut0} methods store a scalar
 * and the {@code mapPut1} methods a vector; the {@code mapGet0} and {@code mapGet1} methods read
 * them. Storing a value under a key replaces whatever was stored there before, of whatever type.
 *
 * <p>A value can be read as any type, not only the one it was stored as. A floating-point value
 * read as an integer is rounded to the nearest integer, and any value outside the range of an
 * integer type is clamped to that type's limits. A number read as a string is formatted as a
 * decimal number. A string read as a number must hold nothing but the number and surrounding white
 * space; {@code "<bad>"} reads as {@link #AST__BAD}. An Object can be read only as an Object, and
 * no other value can be read as one. A conversion that is not possible raises {@code AST__MPGER}. A
 * vector read as a scalar gives its first element, and a scalar read as a vector gives a vector of
 * length 1. Reading a key that is not in the KeyMap gives null, or raises {@code AST__MPKER} when
 * the KeyError attribute is set.
 *
 * <p>An Object stored in a KeyMap is the Object given, not a copy, and reading it gives that same
 * Object. Being shared, an Object stored or read in this way can no longer have the attributes
 * changed that C makes immutable once cloned (such as a ZoomMap's Zoom). A copy of a KeyMap holds
 * copies of its Objects. A KeyMap cannot be stored in itself, or in a KeyMap it holds.
 *
 * <p>The attributes of a KeyMap are:
 *
 * <ul>
 *   <li>{@code KeyCase}: whether keys are case sensitive (default 1). It can be changed only while
 *       the KeyMap is empty. When it is 0 a key may have no more than 200 characters.
 *   <li>{@code KeyError}: whether reading a key that is not in the KeyMap is an error (default 0).
 *       Setting or clearing it also sets or clears it in every KeyMap the KeyMap holds.
 *   <li>{@code MapLocked}: whether new keys are refused (default 0), with {@code AST__BADKEY}.
 *       Setting or clearing it also sets or clears it in every KeyMap the KeyMap holds.
 *   <li>{@code SizeGuess}: the expected number of entries (default 160), which can be changed
 *       only while the KeyMap is empty.
 *   <li>{@code SortBy}: the order in which {@link #mapKey} gives the keys: {@code None} (the
 *       default, giving alphabetical order), {@code AgeUp}, {@code AgeDown}, {@code KeyUp}, {@code
 *       KeyDown}, {@code KeyAgeUp} or {@code KeyAgeDown}.
 * </ul>
 *
 * <p>Ported from Rust {@code keymap/mod.rs} and {@code keymap/serial.rs}, with the attribute
 * handling of C's keymap.c.
 */
public class KeyMap extends AstObject {

    /** The data type of a key that is not in a KeyMap. */
    public static final int AST__BADTYPE = 0;

    /** The data type of an {@code int} value. */
    public static final int AST__INTTYPE = 1;

    /** The data type of a {@code double} value. */
    public static final int AST__DOUBLETYPE = 2;

    /** The data type of a {@code String} value. */
    public static final int AST__STRINGTYPE = 3;

    /** The data type of an {@link AstObject} value. */
    public static final int AST__OBJECTTYPE = 4;

    /** The data type of a {@code float} value. */
    static final int AST__FLOATTYPE = 5;

    /** The data type of a {@code short} value. */
    static final int AST__SINTTYPE = 7;

    /** The data type of an entry with no value. */
    static final int AST__UNDEFTYPE = 8;

    /** The data type of an unsigned byte value. */
    static final int AST__BYTETYPE = 9;

    /** The data type of a {@code long} value. */
    static final int AST__KINTTYPE = 10;

    /** The default SizeGuess: the entries the smallest table holds before C would double it. */
    private static final int DEFAULT_SIZE_GUESS = KeyMapTable.MIN_TABLE_SIZE * KeyMapTable.MAX_ENTRIES_PER_TABLE_ENTRY;

    /** The entries, under their keys as stored: converted for KeyCase, trailing spaces removed. */
    private Map<String, KeyMapEntry> entries = new HashMap<>();

    /**
     * The source of both age counters, incremented twice when a new key is added and once when a
     * new value is stored under an existing key, as C's {@code AddTableEntry} does.
     */
    private long members;

    /** The SizeGuess attribute, or null if unset. */
    private Integer sizeGuess;

    /** The KeyCase attribute, or null if unset. */
    private Boolean keyCase;

    /** The KeyError attribute, or null if unset. */
    private Boolean keyError;

    /** The MapLocked attribute, or null if unset. */
    private Boolean mapLocked;

    /** The SortBy attribute, or null if unset. */
    private KeyMapSort sortBy;

    /** The position {@link #mapIterate} resumes from, in the order of {@link #sortedKeys}. */
    private int iterIndex;

    /** Creates an empty KeyMap with every attribute unset. */
    public KeyMap() {}

    /**
     * Constructs a KeyMap from its description, as C's {@code astLoadKeyMap} does: the attributes
     * are applied first, then each entry is stored with the put methods, then MapLocked, then the
     * member count.
     */
    // C's astLoadKeyMap stores the entries through the put functions, which read the attributes
    // through their virtual getters, as this does.
    @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
    KeyMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("KeyMap");
        // Each attribute is applied as its setter applies it to an empty KeyMap.
        Long v = part.getIntIfSet("SzGss");
        if (v != null) {
            sizeGuess = (int) (long) v;
        }
        String sv = part.getStringIfSet("SortBy");
        if (sv != null) {
            sortBy = KeyMapSort.parse(sv, "astRead");
        }
        v = part.getIntIfSet("KyCas");
        if (v != null) {
            keyCase = v != 0;
        }
        v = part.getIntIfSet("KyErr");
        if (v != null) {
            keyError = v != 0;
        }
        // MapSz records the size of C's hash table; the size is worked out from the keys when a
        // KeyMap is written.
        part.markRead("MapSz");
        for (int n = 1; ; n++) {
            // The loop stops at the first missing key, so a gap in the numbering ends the load.
            String key = part.getStringIfSet("Key" + n);
            if (key == null) {
                break;
            }
            loadEntry(part, n, key, part.getStringIfSet("Com" + n));
        }
        // MapLocked is applied after the entries, which every put above would otherwise refuse
        // (keymap.c, upstream f444583f).
        v = part.getIntIfSet("MpLck");
        if (v != null) {
            applyMapLocked(v != 0);
        }
        members = part.readLong("MemCnt", 0);
    }

    @Override
    String dumpComment() {
        return "Map of key/value pairs";
    }

    @Override
    void deepCopy() {
        super.deepCopy();
        Map<String, KeyMapEntry> copied = new HashMap<>();
        for (Map.Entry<String, KeyMapEntry> e : entries.entrySet()) {
            copied.put(e.getKey(), e.getValue().copy());
        }
        entries = copied;
    }

    // ------------------------------------------------------------------
    // Dump and load
    // ------------------------------------------------------------------

    /**
     * Writes the KeyMap's description, as keymap.c {@code Dump} does: its attributes, each active
     * when set and otherwise the default, none of them helpful; the size of the hash table; the
     * member count; and each entry in key order.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Object", "AST Object");
        node.writeInt("SzGss", sizeGuess != null, false, getSizeGuess(), "Guess at KeyMap size");
        node.writeString("SortBy", sortBy != null, false, getSortBy().text(), "Sorting scheme for keys");
        node.writeInt("KyCas", keyCase != null, false, getKeyCase() ? 1 : 0, "Are keys case sensitive?");
        // The misspelling is C's, kept so that dumps match; the split keeps the spelling checker quiet.
        String kyErr = "Report non-" + "existant keys?"; // codespell:ignore existant
        node.writeInt("KyErr", keyError != null, false, getKeyError() ? 1 : 0, kyErr);
        node.writeInt("MpLck", mapLocked != null, false, getMapLocked() ? 1 : 0, "Prevent addition of new entries?");
        List<String> keys = dumpKeys();
        node.writeInt("MapSz", true, true, KeyMapTable.mapSz(keys, getSizeGuess()), "Size of hash table");
        node.writeInt("MemCnt", true, true, members, "Total member count");
        for (int n = 0; n < keys.size(); n++) {
            dumpEntry(node, n + 1, keys.get(n), entries.get(keys.get(n)));
        }
    }

    /** The keys in the order a dump lists them: C's {@code strcmp} order (keymap.c {@code DumpCmp}). */
    List<String> dumpKeys() {
        List<String> keys = new ArrayList<>(entries.keySet());
        keys.sort(KeyMapTable::strcmp);
        return keys;
    }

    /** The {@code MapSz} value a dump writes. */
    int mapSzForDump() {
        return KeyMapTable.mapSz(dumpKeys(), getSizeGuess());
    }

    /** Writes the items of one entry, as keymap.c {@code DumpEntry} does. */
    private static void dumpEntry(NativeNode node, int n, String key, KeyMapEntry entry) {
        node.writeString("Key" + n, true, true, key, "Item name");
        // C tests both the pointer and the first character, so an empty comment is not written.
        if (entry.comment != null && !entry.comment.isEmpty()) {
            node.writeString("Com" + n, true, true, entry.comment, "Item comment");
        }
        node.writeInt("Typ" + n, true, true, entry.type, KeyMapEntry.typeComment(entry.type));
        int nel = entry.nel;
        if (nel > 0) {
            node.writeInt("Nel" + n, true, true, nel, "Vector length");
        }
        // The comment goes on the first element actually written, and an empty one on the rest;
        // a scalar always has "Item value".
        String com = "Item values";
        int count = Math.max(entry.len(), 1);
        for (int i = 0; i < count; i++) {
            String name = nel == 0 ? "Val" + n : "V" + n + "_" + (i + 1);
            String comment = nel == 0 ? "Item value" : com;
            if (pushElement(node, name, comment, entry, i) && nel > 0) {
                com = "";
            }
        }
    }

    /**
     * Writes element {@code i} of an entry, giving whether anything was written: an AST__BAD
     * double, a null Object and an undefined entry write nothing.
     */
    private static boolean pushElement(NativeNode node, String name, String comment, KeyMapEntry entry, int i) {
        if (!entry.defined()) {
            return false;
        }
        Object v = entry.values.get(i);
        switch (entry.type) {
            case AST__INTTYPE:
            case AST__BYTETYPE:
                node.writeInt(name, true, true, (Integer) v, comment);
                return true;
            case AST__SINTTYPE:
                node.writeInt(name, true, true, (Short) v, comment);
                return true;
            case AST__KINTTYPE:
                node.writeInt(name, true, true, (Long) v, comment);
                return true;
            case AST__DOUBLETYPE:
                if ((Double) v == AST__BAD) {
                    return false;
                }
                node.writeDouble(name, true, true, (Double) v, comment);
                return true;
            case AST__FLOATTYPE:
                // A float is written as a double, with no check for AST__BAD.
                node.writeDouble(name, true, true, (Float) v, comment);
                return true;
            case AST__STRINGTYPE:
                node.writeString(name, true, true, (String) v, comment);
                return true;
            case AST__OBJECTTYPE:
                if (v == null) {
                    return false;
                }
                node.writeObject(name, true, true, NativeNode.of((AstObject) v), comment);
                return true;
            default:
                throw Ast.err(AstException.AST__INTER, "KeyMap: no entry has the data type " + entry.type + ".");
        }
    }

    /** Reads one entry's items and stores the entry, as {@code astLoadKeyMap} does. */
    private void loadEntry(NativeNode part, int n, String key, String comment) {
        // astReadInt gives its default, AST__BADTYPE, both for an absent item and for one written
        // as a comment, and C tests the value, so an explicit 0 is "no data type" too.
        Long code = part.getIntIfSet("Typ" + n);
        int type = code == null ? AST__BADTYPE : (int) (long) code;
        if (type == AST__BADTYPE) {
            throw Ast.err(
                    AstException.AST__BDFTS, "astLoadKeyMap(KeyMap): No data type code found whilst reading a KeyMap.");
        }
        if (!KeyMapEntry.isEntryType(type) || code != type) {
            throw Ast.err(
                    AstException.AST__BDFTS,
                    "astLoadKeyMap(KeyMap): Unknown data type code (" + code
                            + ") encountered whilst reading a KeyMap.");
        }
        // C's read loop runs no times for a negative length, and reads no elements.
        int nel = Math.max(part.readInt("Nel" + n, 0), 0);
        List<String> names = new ArrayList<>();
        if (nel == 0) {
            names.add("Val" + n);
        } else {
            for (int i = 1; i <= nel; i++) {
                names.add("V" + n + "_" + i);
            }
        }
        List<Object> values = new ArrayList<>(names.size());
        for (String nm : names) {
            switch (type) {
                case AST__INTTYPE:
                    values.add((int) part.readLong(nm, 0));
                    break;
                case AST__SINTTYPE:
                    values.add((short) part.readLong(nm, 0));
                    break;
                case AST__KINTTYPE:
                    values.add(part.readLong(nm, 0));
                    break;
                case AST__BYTETYPE:
                    values.add((int) (part.readLong(nm, 0) & 0xff));
                    break;
                case AST__DOUBLETYPE:
                    values.add(part.readDouble(nm, AST__BAD));
                    break;
                case AST__FLOATTYPE:
                    values.add((float) part.readDouble(nm, 0.0));
                    break;
                case AST__STRINGTYPE:
                    values.add(part.readString(nm, ""));
                    break;
                case AST__OBJECTTYPE:
                    NativeNode obj = part.readObject(nm);
                    values.add(obj == null ? null : ObjectRegistry.load(obj));
                    break;
                case AST__UNDEFTYPE:
                    break;
                default:
                    throw Ast.err(AstException.AST__INTER, "KeyMap: no entry has the data type " + type + ".");
            }
        }
        if (type == AST__UNDEFTYPE) {
            putUndefined(key, comment);
        } else if (nel == 0) {
            putScalar(key, type, values.get(0), comment, "astMapPut0" + typeLetter(type));
        } else {
            putVector(key, type, values, comment, "astMapPut1" + typeLetter(type));
        }
    }

    /** The letter that names a data type in C's function names. */
    private static String typeLetter(int type) {
        switch (type) {
            case AST__INTTYPE:
                return "I";
            case AST__SINTTYPE:
                return "S";
            case AST__KINTTYPE:
                return "K";
            case AST__BYTETYPE:
                return "B";
            case AST__FLOATTYPE:
                return "F";
            case AST__DOUBLETYPE:
                return "D";
            case AST__STRINGTYPE:
                return "C";
            case AST__OBJECTTYPE:
                return "A";
            default:
                throw Ast.err(AstException.AST__INTER, "KeyMap: no entry has the data type " + type + ".");
        }
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /** The SizeGuess attribute: the number of entries the KeyMap is expected to hold (default 160). */
    int getSizeGuess() {
        return sizeGuess == null ? DEFAULT_SIZE_GUESS : sizeGuess;
    }

    /**
     * Sets SizeGuess.
     *
     * @throws AstException with status {@code AST__NOWRT} if the KeyMap holds any entries
     */
    void setSizeGuess(int value) {
        if (!entries.isEmpty()) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSetAttrib(KeyMap): Illegal attempt to change the SizeGuess attribute of a non-empty KeyMap.");
        }
        sizeGuess = value;
    }

    /** Clears SizeGuess, raising {@code AST__NOWRT} if the KeyMap holds any entries. */
    void clearSizeGuess() {
        if (!entries.isEmpty()) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astClearAttrib(KeyMap): Illegal attempt to clear the SizeGuess attribute of a non-empty KeyMap.");
        }
        sizeGuess = null;
    }

    /**
     * The KeyCase attribute: whether keys are case sensitive (default true). When false, the ASCII
     * letters of every key are put in upper case before it is used.
     */
    boolean getKeyCase() {
        return keyCase == null || keyCase;
    }

    /**
     * Sets KeyCase. Setting the value it already has is always allowed.
     *
     * @throws AstException with status {@code AST__NOWRT} if the KeyMap holds any entries and the
     *     value would change
     */
    void setKeyCase(boolean value) {
        if (value != getKeyCase() && !entries.isEmpty()) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSetAttrib(KeyMap): Illegal attempt to change the KeyCase attribute of a non-empty KeyMap.");
        }
        keyCase = value;
    }

    /** Clears KeyCase, raising {@code AST__NOWRT} if that would change it for a non-empty KeyMap. */
    void clearKeyCase() {
        boolean old = getKeyCase();
        Boolean saved = keyCase;
        keyCase = null;
        if (getKeyCase() != old && !entries.isEmpty()) {
            keyCase = saved;
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astClearAttrib(KeyMap): Illegal attempt to clear the KeyCase attribute of a non-empty KeyMap.");
        }
    }

    /** The KeyError attribute: whether reading a missing key is an error (default false). */
    boolean getKeyError() {
        return keyError != null && keyError;
    }

    /** Sets KeyError, here and in every KeyMap held in this one. */
    void setKeyError(boolean value) {
        keyError = value;
        for (KeyMap km : nestedKeyMaps()) {
            km.setKeyError(value);
        }
    }

    /** Clears KeyError, here and in every KeyMap held in this one. */
    void clearKeyError() {
        keyError = null;
        for (KeyMap km : nestedKeyMaps()) {
            km.clearKeyError();
        }
    }

    /** The MapLocked attribute: whether new keys are refused (default false). */
    boolean getMapLocked() {
        return mapLocked != null && mapLocked;
    }

    /** Sets MapLocked, here and in every KeyMap held in this one. */
    void setMapLocked(boolean value) {
        applyMapLocked(value);
    }

    private void applyMapLocked(boolean value) {
        mapLocked = value;
        for (KeyMap km : nestedKeyMaps()) {
            km.setMapLocked(value);
        }
    }

    /** Clears MapLocked, here and in every KeyMap held in this one. */
    void clearMapLocked() {
        mapLocked = null;
        for (KeyMap km : nestedKeyMaps()) {
            km.clearMapLocked();
        }
    }

    /** The SortBy attribute: the order in which keys are returned (default none). */
    KeyMapSort getSortBy() {
        return sortBy == null ? KeyMapSort.NONE : sortBy;
    }

    /** Sets SortBy. */
    void setSortBy(KeyMapSort value) {
        sortBy = value;
    }

    /** Clears SortBy. */
    void clearSortBy() {
        sortBy = null;
    }

    /** Every KeyMap held directly by this one, as a scalar or as a vector element. */
    private List<KeyMap> nestedKeyMaps() {
        List<KeyMap> out = new ArrayList<>();
        for (KeyMapEntry e : entries.values()) {
            if (e.type == AST__OBJECTTYPE) {
                for (Object o : e.values) {
                    if (o instanceof KeyMap) {
                        out.add((KeyMap) o);
                    }
                }
            }
        }
        return out;
    }

    @Override
    String getAttrib(String attrib) {
        switch (attrib) {
            case "sizeguess":
                return Integer.toString(getSizeGuess());
            case "keycase":
                return getKeyCase() ? "1" : "0";
            case "keyerror":
                return getKeyError() ? "1" : "0";
            case "maplocked":
                return getMapLocked() ? "1" : "0";
            case "sortby":
                return getSortBy().text();
            default:
                return super.getAttrib(attrib);
        }
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        if ("sizeguess".equals(attrib) && ival != null) {
            setSizeGuess(ival);
        } else if ("keycase".equals(attrib) && ival != null) {
            setKeyCase(ival != 0);
        } else if ("keyerror".equals(attrib) && ival != null) {
            setKeyError(ival != 0);
        } else if ("maplocked".equals(attrib) && ival != null) {
            setMapLocked(ival != 0);
        } else if ("sortby".equals(attrib) && isOneWord(value)) {
            // C's "sortby= %n%*s %n": one word, with any white space around it.
            int start = 0;
            while (CScan.isCSpace(value.charAt(start))) {
                start++;
            }
            setSortBy(KeyMapSort.parse(value.substring(start), "astSetAttrib"));
        } else {
            super.setAttrib(attrib, value);
        }
    }

    /** Whether {@code s} is one word with optional white space around it. */
    private static boolean isOneWord(String s) {
        int i = 0;
        while (i < s.length() && CScan.isCSpace(s.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < s.length() && !CScan.isCSpace(s.charAt(i))) {
            i++;
        }
        int end = i;
        while (i < s.length() && CScan.isCSpace(s.charAt(i))) {
            i++;
        }
        return end > start && i == s.length();
    }

    @Override
    boolean testAttrib(String attrib) {
        switch (attrib) {
            case "sizeguess":
                return sizeGuess != null;
            case "keycase":
                return keyCase != null;
            case "keyerror":
                return keyError != null;
            case "maplocked":
                return mapLocked != null;
            case "sortby":
                return sortBy != null;
            default:
                return super.testAttrib(attrib);
        }
    }

    @Override
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "sizeguess":
                clearSizeGuess();
                break;
            case "keyerror":
                clearKeyError();
                break;
            case "keycase":
                clearKeyCase();
                break;
            case "maplocked":
                clearMapLocked();
                break;
            case "sortby":
                clearSortBy();
                break;
            default:
                super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Storing
    // ------------------------------------------------------------------

    private String convertKey(String key, String method) {
        return KeyMapTable.convertKey(key, getKeyCase(), method);
    }

    private KeyMapEntry lookup(String convertedKey) {
        return entries.get(KeyMapTable.trimSpaces(convertedKey));
    }

    /**
     * C's {@code CheckCircle}: refuses to store a KeyMap that is this one, or that holds this one
     * at any depth.
     */
    // Identity, not equality, is what makes a KeyMap hold itself.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private void checkCircle(AstObject obj, String method) {
        if (!(obj instanceof KeyMap)) {
            return;
        }
        KeyMap keymap = (KeyMap) obj;
        if (keymap == this) {
            throw Ast.err(
                    AstException.AST__KYCIR,
                    method + "(KeyMap): Cannot add a KeyMap into another KeyMap because they are same KeyMap.");
        }
        for (String key : keymap.sortedKeys()) {
            KeyMapEntry e = keymap.entries.get(key);
            if (e.type != AST__OBJECTTYPE) {
                continue;
            }
            for (Object o : e.values) {
                if (o == this) {
                    throw Ast.err(
                            AstException.AST__KYCIR,
                            method + "(KeyMap): Cannot add a KeyMap into another KeyMap because the first KeyMap "
                                    + "contains the second KeyMap.");
                }
                if (o instanceof KeyMap) {
                    checkCircle((AstObject) o, method);
                }
            }
        }
    }

    /**
     * Stores {@code entry} under a converted key, as the put functions do: the stored key loses
     * its trailing spaces; an existing entry is replaced, keeping its key's age; a new key on a
     * locked KeyMap is refused.
     */
    private void insertEntry(String converted, KeyMapEntry entry, String method) {
        String key = KeyMapTable.trimSpaces(converted);
        KeyMapEntry previous = entries.remove(key);
        if (previous == null && getMapLocked()) {
            // C quotes the converted key, trailing spaces included.
            throw Ast.err(
                    AstException.AST__BADKEY,
                    method + "(KeyMap): Failed to add item \"" + converted + "\" to a KeyMap: \"" + converted
                            + "\" is not a known item.");
        }
        if (previous != null) {
            members++;
            entry.member = members;
            entry.keymember = previous.keymember;
            entries.put(key, entry);
        } else {
            insertNew(key, entry);
        }
    }

    /** Adds an entry under a new key, as C's {@code AddTableEntry} does. */
    private void insertNew(String key, KeyMapEntry entry) {
        members++;
        entry.member = members;
        members++;
        entry.keymember = members;
        entries.put(key, entry);
    }

    private void putScalar(String key, int type, Object value, String comment, String method) {
        if (type == AST__OBJECTTYPE) {
            checkCircle((AstObject) value, method);
        }
        insertEntry(convertKey(key, method), KeyMapEntry.scalar(type, value, comment), method);
        if (type == AST__OBJECTTYPE) {
            shared(value);
        }
    }

    /**
     * Marks an Object the KeyMap stores or returns as shared, since C clones it (keymap.c
     * {@code astMapPut0A}, {@code astMapPut1A}, {@code astMapPutElemA} and the conversion of an
     * entry to an Object).
     */
    private static Object shared(Object value) {
        if (value != null) {
            ((AstObject) value).markShared();
        }
        return value;
    }

    private void putVector(String key, int type, List<Object> values, String comment, String method) {
        if (values.isEmpty()) {
            throw Ast.err(
                    AstException.AST__NELIN,
                    method + "(KeyMap): Illegal vector length 0 supplied for KeyMap entry \"" + key
                            + "\" - must be at least one.");
        }
        if (type == AST__OBJECTTYPE) {
            for (Object o : values) {
                checkCircle((AstObject) o, method);
            }
        }
        insertEntry(convertKey(key, method), KeyMapEntry.vector(type, values, comment), method);
        if (type == AST__OBJECTTYPE) {
            for (Object o : values) {
                shared(o);
            }
        }
    }

    /**
     * Stores a {@code double} scalar under a key, replacing any value stored there before.
     *
     * @param key the key
     * @param value the value
     * @param comment a comment for the entry, or null
     * @throws AstException with status {@code AST__BADKEY} if the key is new and the KeyMap is
     *     locked, or {@code AST__BIGKEY} if KeyCase is 0 and the key has more than 200 characters
     */
    public void mapPut0D(String key, double value, String comment) {
        putScalar(key, AST__DOUBLETYPE, value, comment, "astMapPut0D");
    }

    /**
     * Stores an {@code int} scalar under a key, replacing any value stored there before.
     *
     * @param key the key
     * @param value the value
     * @param comment a comment for the entry, or null
     * @throws AstException as {@link #mapPut0D} does
     */
    public void mapPut0I(String key, int value, String comment) {
        putScalar(key, AST__INTTYPE, value, comment, "astMapPut0I");
    }

    /**
     * Stores a {@code String} scalar under a key, replacing any value stored there before.
     *
     * @param key the key
     * @param value the value
     * @param comment a comment for the entry, or null
     * @throws AstException as {@link #mapPut0D} does
     */
    public void mapPut0C(String key, String value, String comment) {
        putScalar(key, AST__STRINGTYPE, Objects.requireNonNull(value, "value"), comment, "astMapPut0C");
    }

    /**
     * Stores an Object under a key, replacing any value stored there before. The KeyMap holds the
     * Object given, not a copy.
     *
     * @param key the key
     * @param value the Object, or null
     * @param comment a comment for the entry, or null
     * @throws AstException as {@link #mapPut0D} does, or with status {@code AST__KYCIR} if {@code
     *     value} is this KeyMap or a KeyMap that holds it
     */
    public void mapPut0A(String key, AstObject value, String comment) {
        putScalar(key, AST__OBJECTTYPE, value, comment, "astMapPut0A");
    }

    /** Stores a {@code short} scalar ({@code astMapPut0S}). */
    void mapPut0S(String key, short value, String comment) {
        putScalar(key, AST__SINTTYPE, value, comment, "astMapPut0S");
    }

    /** Stores a {@code long} scalar ({@code astMapPut0K}). */
    void mapPut0K(String key, long value, String comment) {
        putScalar(key, AST__KINTTYPE, value, comment, "astMapPut0K");
    }

    /** Stores an unsigned byte scalar, the low 8 bits of {@code value} ({@code astMapPut0B}). */
    void mapPut0B(String key, int value, String comment) {
        putScalar(key, AST__BYTETYPE, value & 0xff, comment, "astMapPut0B");
    }

    /** Stores a {@code float} scalar ({@code astMapPut0F}). */
    void mapPut0F(String key, float value, String comment) {
        putScalar(key, AST__FLOATTYPE, value, comment, "astMapPut0F");
    }

    /** Stores a key with no value ({@code astMapPutU}); a replaced entry keeps its key's age. */
    void mapPutU(String key, String comment) {
        putUndefined(key, comment);
    }

    private void putUndefined(String key, String comment) {
        insertEntry(convertKey(key, "astMapPutU"), KeyMapEntry.undefined(comment), "astMapPutU");
    }

    /**
     * Stores a vector of {@code double} values under a key, replacing any value stored there
     * before.
     *
     * @param key the key
     * @param value the values, at least one
     * @param comment a comment for the entry, or null
     * @throws AstException as {@link #mapPut0D} does, or with status {@code AST__NELIN} if there
     *     are no values
     */
    public void mapPut1D(String key, double[] value, String comment) {
        List<Object> v = new ArrayList<>(value.length);
        for (double d : value) {
            v.add(d);
        }
        putVector(key, AST__DOUBLETYPE, v, comment, "astMapPut1D");
    }

    /**
     * Stores a vector of {@code int} values under a key, replacing any value stored there before.
     *
     * @param key the key
     * @param value the values, at least one
     * @param comment a comment for the entry, or null
     * @throws AstException as {@link #mapPut1D} does
     */
    public void mapPut1I(String key, int[] value, String comment) {
        List<Object> v = new ArrayList<>(value.length);
        for (int i : value) {
            v.add(i);
        }
        putVector(key, AST__INTTYPE, v, comment, "astMapPut1I");
    }

    /**
     * Stores a vector of {@code String} values under a key, replacing any value stored there
     * before.
     *
     * @param key the key
     * @param value the values, at least one
     * @param comment a comment for the entry, or null
     * @throws AstException as {@link #mapPut1D} does
     */
    public void mapPut1C(String key, String[] value, String comment) {
        List<Object> v = new ArrayList<>(value.length);
        for (String s : value) {
            v.add(Objects.requireNonNull(s, "value element"));
        }
        putVector(key, AST__STRINGTYPE, v, comment, "astMapPut1C");
    }

    /**
     * Stores a vector of Objects under a key, replacing any value stored there before. The KeyMap
     * holds the Objects given, not copies; an element may be null.
     *
     * @param key the key
     * @param value the Objects, at least one
     * @param comment a comment for the entry, or null
     * @throws AstException as {@link #mapPut1D} and {@link #mapPut0A} do
     */
    public void mapPut1A(String key, AstObject[] value, String comment) {
        putVector(key, AST__OBJECTTYPE, new ArrayList<>(Arrays.asList(value)), comment, "astMapPut1A");
    }

    /** Stores a vector of {@code short} values ({@code astMapPut1S}). */
    void mapPut1S(String key, short[] value, String comment) {
        List<Object> v = new ArrayList<>(value.length);
        for (short s : value) {
            v.add(s);
        }
        putVector(key, AST__SINTTYPE, v, comment, "astMapPut1S");
    }

    /** Stores a vector of {@code long} values ({@code astMapPut1K}). */
    void mapPut1K(String key, long[] value, String comment) {
        List<Object> v = new ArrayList<>(value.length);
        for (long l : value) {
            v.add(l);
        }
        putVector(key, AST__KINTTYPE, v, comment, "astMapPut1K");
    }

    /** Stores a vector of unsigned bytes, the low 8 bits of each value ({@code astMapPut1B}). */
    void mapPut1B(String key, int[] value, String comment) {
        List<Object> v = new ArrayList<>(value.length);
        for (int b : value) {
            v.add(b & 0xff);
        }
        putVector(key, AST__BYTETYPE, v, comment, "astMapPut1B");
    }

    /** Stores a vector of {@code float} values ({@code astMapPut1F}). */
    void mapPut1F(String key, float[] value, String comment) {
        List<Object> v = new ArrayList<>(value.length);
        for (float f : value) {
            v.add(f);
        }
        putVector(key, AST__FLOATTYPE, v, comment, "astMapPut1F");
    }

    // ------------------------------------------------------------------
    // Element stores
    // ------------------------------------------------------------------

    /**
     * C's {@code astMapPutElem<X>}: stores one element of a vector. An absent or undefined entry
     * is created as a vector of length 1, with no comment; a scalar becomes a vector of length 1,
     * keeping its comment, which counts as storing a value; an index outside the vector (negative,
     * or at or beyond its end) appends.
     */
    private void putElem(String key, int elem, KeyMapConvert.Raw value, String letter) {
        String method = "astMapPutElem" + letter;
        if (value.type == AST__OBJECTTYPE) {
            checkCircle((AstObject) value.value, method);
        }
        String converted = convertKey(key, method);
        String nkey = KeyMapTable.trimSpaces(converted);
        KeyMapEntry entry = entries.get(nkey);
        if (entry == null || !entry.defined()) {
            List<Object> one = new ArrayList<>(1);
            one.add(value.value);
            insertEntry(converted, KeyMapEntry.vector(value.type, one, null), "astMapPut1" + letter);
            if (value.type == AST__OBJECTTYPE) {
                shared(value.value);
            }
            return;
        }
        if (entry.nel == 0) {
            entry.promoteToVector();
            members++;
            entry.member = members;
        }
        KeyMapConvert.Raw conv = KeyMapConvert.convert(value, entry.type);
        if (conv == null) {
            throw Ast.err(
                    AstException.AST__MPPER,
                    method + "(KeyMap): The supplied value cannot be converted to the data type of KeyMap key \""
                            + converted + "\".");
        }
        int n = entry.len();
        entry.setElement(elem >= n || elem < 0 ? n : elem, conv.value);
        if (value.type == AST__OBJECTTYPE) {
            shared(value.value);
        }
    }

    /** Stores element {@code elem} of an {@code int} vector ({@code astMapPutElemI}). */
    void mapPutElemI(String key, int elem, int value) {
        putElem(key, elem, new KeyMapConvert.Raw(AST__INTTYPE, value), "I");
    }

    /** Stores element {@code elem} of a {@code short} vector ({@code astMapPutElemS}). */
    void mapPutElemS(String key, int elem, short value) {
        putElem(key, elem, new KeyMapConvert.Raw(AST__SINTTYPE, value), "S");
    }

    /** Stores element {@code elem} of a {@code long} vector ({@code astMapPutElemK}). */
    void mapPutElemK(String key, int elem, long value) {
        putElem(key, elem, new KeyMapConvert.Raw(AST__KINTTYPE, value), "K");
    }

    /** Stores element {@code elem} of an unsigned byte vector ({@code astMapPutElemB}). */
    void mapPutElemB(String key, int elem, int value) {
        putElem(key, elem, new KeyMapConvert.Raw(AST__BYTETYPE, value & 0xff), "B");
    }

    /** Stores element {@code elem} of a {@code float} vector ({@code astMapPutElemF}). */
    void mapPutElemF(String key, int elem, float value) {
        putElem(key, elem, new KeyMapConvert.Raw(AST__FLOATTYPE, value), "F");
    }

    /** Stores element {@code elem} of a {@code double} vector ({@code astMapPutElemD}). */
    void mapPutElemD(String key, int elem, double value) {
        putElem(key, elem, new KeyMapConvert.Raw(AST__DOUBLETYPE, value), "D");
    }

    /** Stores element {@code elem} of a {@code String} vector ({@code astMapPutElemC}). */
    void mapPutElemC(String key, int elem, String value) {
        putElem(key, elem, new KeyMapConvert.Raw(AST__STRINGTYPE, Objects.requireNonNull(value, "value")), "C");
    }

    /** Stores element {@code elem} of an Object vector ({@code astMapPutElemA}). */
    void mapPutElemA(String key, int elem, AstObject value) {
        putElem(key, elem, new KeyMapConvert.Raw(AST__OBJECTTYPE, value), "A");
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private AstException missing(String method, String nkey) {
        return Ast.err(
                AstException.AST__MPKER,
                method + "(KeyMap): No value was found for " + nkey + " in the supplied KeyMap.");
    }

    /** Whether the KeyMap holds a defined value for a scalar read, and its first element. */
    private KeyMapConvert.Raw getScalar(String key, int want, String method) {
        String nkey = convertKey(key, method);
        KeyMapEntry entry = lookup(nkey);
        if (entry == null) {
            if (getKeyError()) {
                throw missing(method, nkey);
            }
            return null;
        }
        // An undefined entry is present, not missing: it gives no value without consulting KeyError.
        KeyMapConvert.Raw raw = KeyMapConvert.elementAt(entry, 0);
        if (raw == null || (raw.type == AST__UNDEFTYPE && want != AST__UNDEFTYPE)) {
            return null;
        }
        KeyMapConvert.Raw v = KeyMapConvert.convert(raw, want);
        if (v == null) {
            throw Ast.err(
                    AstException.AST__MPGER,
                    method + "(KeyMap): The value of KeyMap key \"" + nkey
                            + "\" cannot be read using the requested data type.");
        }
        return v;
    }

    /**
     * Gets a scalar value as a {@code double}; a vector gives its first element.
     *
     * @param key the key
     * @return the value, or null if the key is not in the KeyMap or has no value
     * @throws AstException with status {@code AST__MPGER} if the value cannot be read as a {@code
     *     double}, or {@code AST__MPKER} if the key is not in the KeyMap and KeyError is set
     */
    public Double mapGet0D(String key) {
        KeyMapConvert.Raw v = getScalar(key, AST__DOUBLETYPE, "astMapGet0D");
        return v == null ? null : (Double) v.value;
    }

    /**
     * Gets a scalar value as an {@code int}; a vector gives its first element.
     *
     * @param key the key
     * @return the value, or null if the key is not in the KeyMap or has no value
     * @throws AstException as {@link #mapGet0D} does
     */
    public Integer mapGet0I(String key) {
        KeyMapConvert.Raw v = getScalar(key, AST__INTTYPE, "astMapGet0I");
        return v == null ? null : (Integer) v.value;
    }

    /**
     * Gets a scalar value as a {@code String}; a vector gives its first element.
     *
     * @param key the key
     * @return the value, or null if the key is not in the KeyMap or has no value
     * @throws AstException as {@link #mapGet0D} does
     */
    public String mapGet0C(String key) {
        KeyMapConvert.Raw v = getScalar(key, AST__STRINGTYPE, "astMapGet0C");
        return v == null ? null : (String) v.value;
    }

    /**
     * Gets a scalar Object; a vector gives its first element. This is the Object stored, not a
     * copy.
     *
     * @param key the key
     * @return the Object, or null if the key is not in the KeyMap, has no value, or holds null
     * @throws AstException as {@link #mapGet0D} does
     */
    public AstObject mapGet0A(String key) {
        KeyMapConvert.Raw v = getScalar(key, AST__OBJECTTYPE, "astMapGet0A");
        return v == null ? null : (AstObject) shared(v.value);
    }

    /** A scalar as a {@code short} ({@code astMapGet0S}), or null. */
    Short mapGet0S(String key) {
        KeyMapConvert.Raw v = getScalar(key, AST__SINTTYPE, "astMapGet0S");
        return v == null ? null : (Short) v.value;
    }

    /** A scalar as a {@code long} ({@code astMapGet0K}), or null. */
    Long mapGet0K(String key) {
        KeyMapConvert.Raw v = getScalar(key, AST__KINTTYPE, "astMapGet0K");
        return v == null ? null : (Long) v.value;
    }

    /** A scalar as an unsigned byte, 0 to 255 ({@code astMapGet0B}), or null. */
    Integer mapGet0B(String key) {
        KeyMapConvert.Raw v = getScalar(key, AST__BYTETYPE, "astMapGet0B");
        return v == null ? null : (Integer) v.value;
    }

    /** A scalar as a {@code float} ({@code astMapGet0F}), or null. */
    Float mapGet0F(String key) {
        KeyMapConvert.Raw v = getScalar(key, AST__FLOATTYPE, "astMapGet0F");
        return v == null ? null : (Float) v.value;
    }

    /**
     * Whether {@code key} holds a value that reads as an Object, and if so that Object (possibly
     * null) in {@code out[0]}: {@code astMapGet0A}'s returned flag and value.
     */
    boolean mapGet0A(String key, AstObject[] out) {
        KeyMapConvert.Raw v = getScalar(key, AST__OBJECTTYPE, "astMapGet0A");
        if (v == null) {
            return false;
        }
        out[0] = (AstObject) shared(v.value);
        return true;
    }

    /** The values of a key converted to {@code want}, a scalar giving one, or null. */
    private List<Object> getVector(String key, int want, String method) {
        String nkey = convertKey(key, method);
        KeyMapEntry entry = lookup(nkey);
        if (entry == null) {
            if (getKeyError()) {
                throw missing(method, nkey);
            }
            return null;
        }
        if (!entry.defined()) {
            return null;
        }
        int n = Math.max(entry.len(), 1);
        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            KeyMapConvert.Raw raw = KeyMapConvert.elementAt(entry, i);
            if (raw == null) {
                break;
            }
            KeyMapConvert.Raw v = KeyMapConvert.convert(raw, want);
            if (v == null) {
                throw Ast.err(
                        AstException.AST__MPGER,
                        method + "(KeyMap): The value of element " + (i + 1) + " of KeyMap key \"" + nkey
                                + "\" cannot be read using the requested data type.");
            }
            out.add(v.value);
        }
        return out;
    }

    /**
     * Gets a vector of values as {@code double}s; a scalar gives a vector of length 1.
     *
     * @param key the key
     * @return the values, or null if the key is not in the KeyMap or has no value
     * @throws AstException as {@link #mapGet0D} does, for any element
     */
    public double[] mapGet1D(String key) {
        List<Object> v = getVector(key, AST__DOUBLETYPE, "astMapGet1D");
        if (v == null) {
            return null;
        }
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Double) v.get(i);
        }
        return out;
    }

    /**
     * Gets a vector of values as {@code int}s; a scalar gives a vector of length 1.
     *
     * @param key the key
     * @return the values, or null if the key is not in the KeyMap or has no value
     * @throws AstException as {@link #mapGet0D} does, for any element
     */
    public int[] mapGet1I(String key) {
        List<Object> v = getVector(key, AST__INTTYPE, "astMapGet1I");
        if (v == null) {
            return null;
        }
        int[] out = new int[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Integer) v.get(i);
        }
        return out;
    }

    /**
     * Gets a vector of values as {@code String}s, each truncated to {@code sleng} characters, as
     * C's {@code astMapGet1C} truncates each to its buffer; a scalar gives a vector of length 1.
     *
     * @param key the key
     * @param sleng the greatest length of each string
     * @return the values, or null if the key is not in the KeyMap or has no value
     * @throws AstException as {@link #mapGet0D} does, for any element
     */
    public String[] mapGet1C(String key, int sleng) {
        String[] all = mapGet1C(key);
        if (all == null) {
            return null;
        }
        for (int i = 0; i < all.length; i++) {
            if (all[i].length() > sleng) {
                all[i] = all[i].substring(0, Math.max(sleng, 0));
            }
        }
        return all;
    }

    /** A vector as {@code String}s, untruncated ({@code astMapGet1C}), or null. */
    String[] mapGet1C(String key) {
        List<Object> v = getVector(key, AST__STRINGTYPE, "astMapGet1C");
        return v == null ? null : v.toArray(new String[0]);
    }

    /**
     * Gets a vector of Objects; a scalar gives a vector of length 1. These are the Objects stored,
     * not copies, and an element may be null.
     *
     * @param key the key
     * @return the Objects, or null if the key is not in the KeyMap or has no value
     * @throws AstException as {@link #mapGet0D} does, for any element
     */
    public AstObject[] mapGet1A(String key) {
        List<Object> v = getVector(key, AST__OBJECTTYPE, "astMapGet1A");
        if (v == null) {
            return null;
        }
        for (Object o : v) {
            shared(o);
        }
        return v.toArray(new AstObject[0]);
    }

    /** A vector as {@code short}s ({@code astMapGet1S}), or null. */
    short[] mapGet1S(String key) {
        List<Object> v = getVector(key, AST__SINTTYPE, "astMapGet1S");
        if (v == null) {
            return null;
        }
        short[] out = new short[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Short) v.get(i);
        }
        return out;
    }

    /** A vector as {@code long}s ({@code astMapGet1K}), or null. */
    long[] mapGet1K(String key) {
        List<Object> v = getVector(key, AST__KINTTYPE, "astMapGet1K");
        if (v == null) {
            return null;
        }
        long[] out = new long[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Long) v.get(i);
        }
        return out;
    }

    /** A vector as unsigned bytes, 0 to 255 ({@code astMapGet1B}), or null. */
    int[] mapGet1B(String key) {
        List<Object> v = getVector(key, AST__BYTETYPE, "astMapGet1B");
        if (v == null) {
            return null;
        }
        int[] out = new int[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Integer) v.get(i);
        }
        return out;
    }

    /** A vector as {@code float}s ({@code astMapGet1F}), or null. */
    float[] mapGet1F(String key) {
        List<Object> v = getVector(key, AST__FLOATTYPE, "astMapGet1F");
        if (v == null) {
            return null;
        }
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Float) v.get(i);
        }
        return out;
    }

    /**
     * C's {@code astMapGetElem<X>}: element {@code elem} of a key's value converted to {@code
     * want}, or null if the key is missing or has no value.
     */
    private KeyMapConvert.Raw getElem(String key, int elem, int want, String method) {
        String nkey = convertKey(key, method);
        KeyMapEntry entry = lookup(nkey);
        if (entry == null) {
            if (getKeyError()) {
                throw missing(method, nkey);
            }
            return null;
        }
        int n = Math.max(entry.len(), 1);
        if (elem >= n || elem < 0) {
            // The numeric functions share MAKE_MAPGETELEM's message, which names the function
            // with a literal "<X>"; MapGetElemC and astMapGetElemAId_ have their own, giving the
            // index one-based and an upper limit one more than the vector length.
            String message = want == AST__STRINGTYPE || want == AST__OBJECTTYPE
                    ? method + "(KeyMap): Illegal vector index " + (elem + 1) + " supplied for KeyMap entry '" + nkey
                            + "' - should be in the range 1 to " + (n + 1) + "."
                    : "astMapGetElem<X>(KeyMap): Illegal zero-based vector index " + elem
                            + " supplied for KeyMap entry '" + nkey + "' - the vector has " + n + " elements.";
            throw Ast.err(AstException.AST__MPVIN, message);
        }
        if (!entry.defined()) {
            return null;
        }
        KeyMapConvert.Raw raw = KeyMapConvert.elementAt(entry, elem);
        if (raw == null) {
            return null;
        }
        KeyMapConvert.Raw v = KeyMapConvert.convert(raw, want);
        if (v == null) {
            throw Ast.err(
                    AstException.AST__MPGER,
                    method + "(KeyMap): The value of element " + (elem + 1) + " of KeyMap key \"" + nkey
                            + "\" cannot be read using the requested data type.");
        }
        return v;
    }

    /** Element {@code elem} as an {@code int} ({@code astMapGetElemI}), or null. */
    Integer mapGetElemI(String key, int elem) {
        KeyMapConvert.Raw v = getElem(key, elem, AST__INTTYPE, "astMapGetElemI");
        return v == null ? null : (Integer) v.value;
    }

    /** Element {@code elem} as a {@code short} ({@code astMapGetElemS}), or null. */
    Short mapGetElemS(String key, int elem) {
        KeyMapConvert.Raw v = getElem(key, elem, AST__SINTTYPE, "astMapGetElemS");
        return v == null ? null : (Short) v.value;
    }

    /** Element {@code elem} as a {@code long} ({@code astMapGetElemK}), or null. */
    Long mapGetElemK(String key, int elem) {
        KeyMapConvert.Raw v = getElem(key, elem, AST__KINTTYPE, "astMapGetElemK");
        return v == null ? null : (Long) v.value;
    }

    /** Element {@code elem} as an unsigned byte ({@code astMapGetElemB}), or null. */
    Integer mapGetElemB(String key, int elem) {
        KeyMapConvert.Raw v = getElem(key, elem, AST__BYTETYPE, "astMapGetElemB");
        return v == null ? null : (Integer) v.value;
    }

    /** Element {@code elem} as a {@code float} ({@code astMapGetElemF}), or null. */
    Float mapGetElemF(String key, int elem) {
        KeyMapConvert.Raw v = getElem(key, elem, AST__FLOATTYPE, "astMapGetElemF");
        return v == null ? null : (Float) v.value;
    }

    /** Element {@code elem} as a {@code double} ({@code astMapGetElemD}), or null. */
    Double mapGetElemD(String key, int elem) {
        KeyMapConvert.Raw v = getElem(key, elem, AST__DOUBLETYPE, "astMapGetElemD");
        return v == null ? null : (Double) v.value;
    }

    /** Element {@code elem} as a {@code String} ({@code astMapGetElemC}), or null. */
    String mapGetElemC(String key, int elem) {
        KeyMapConvert.Raw v = getElem(key, elem, AST__STRINGTYPE, "astMapGetElemC");
        return v == null ? null : (String) v.value;
    }

    /**
     * Whether element {@code elem} reads as an Object, and if so that Object (possibly null) in
     * {@code out[0]} ({@code astMapGetElemA}).
     */
    boolean mapGetElemA(String key, int elem, AstObject[] out) {
        KeyMapConvert.Raw v = getElem(key, elem, AST__OBJECTTYPE, "astMapGetElemA");
        if (v == null) {
            return false;
        }
        out[0] = (AstObject) shared(v.value);
        return true;
    }

    // ------------------------------------------------------------------
    // Enquiries
    // ------------------------------------------------------------------

    /**
     * Returns the number of entries in this KeyMap, those with no value included.
     *
     * @return the number of entries
     */
    public int mapSize() {
        return entries.size();
    }

    /**
     * Returns the number of values stored under a key: 1 for a scalar, the vector length for a
     * vector, and 0 if the key is not in the KeyMap.
     *
     * @param key the key
     * @return the number of values
     * @throws AstException with status {@code AST__BIGKEY} if KeyCase is 0 and the key has more
     *     than 200 characters
     */
    public int mapLength(String key) {
        KeyMapEntry entry = lookup(convertKey(key, "astMapLength"));
        return entry == null ? 0 : Math.max(entry.len(), 1);
    }

    /**
     * Returns whether a key is in this KeyMap, with or without a value.
     *
     * @param key the key
     * @return true if the key is in the KeyMap
     * @throws AstException as {@link #mapLength} does
     */
    public boolean mapHasKey(String key) {
        return lookup(convertKey(key, "astMapHasKey")) != null;
    }

    /**
     * Returns the data type of the value stored under a key: one of the {@code AST__<X>TYPE}
     * constants, or {@link #AST__BADTYPE} if the key is not in the KeyMap.
     *
     * @param key the key
     * @return the data type
     * @throws AstException as {@link #mapLength} does
     */
    public int mapType(String key) {
        KeyMapEntry entry = lookup(convertKey(key, "astMapType"));
        return entry == null ? AST__BADTYPE : entry.type;
    }

    /**
     * Removes a key and its value from this KeyMap. A key that is not in the KeyMap is ignored.
     *
     * @param key the key
     * @throws AstException as {@link #mapLength} does
     */
    public void mapRemove(String key) {
        entries.remove(KeyMapTable.trimSpaces(convertKey(key, "astMapRemove")));
    }

    /**
     * The length of the longest value of a key formatted as a string ({@code astMapLenC}), or 0
     * if the key is missing or has no value.
     */
    int mapLenC(String key) {
        String nkey = convertKey(key, "astMapLenC");
        KeyMapEntry entry = lookup(nkey);
        if (entry == null || !entry.defined()) {
            return 0;
        }
        int result = 0;
        for (int i = 0; i < Math.max(entry.len(), 1); i++) {
            KeyMapConvert.Raw raw = KeyMapConvert.elementAt(entry, i);
            if (raw == null) {
                break;
            }
            KeyMapConvert.Raw s = KeyMapConvert.convert(raw, AST__STRINGTYPE);
            if (s != null) {
                // C's lengths are in bytes.
                result = Math.max(result, ((String) s.value).getBytes(StandardCharsets.UTF_8).length);
            }
        }
        return result;
    }

    /**
     * A key's value as one string ({@code astMapGetC}): a scalar formatted as a string, or a
     * vector's elements so formatted, joined by commas within parentheses; null if the key is
     * missing or has no value.
     */
    String mapGetC(String key) {
        int nval = mapLength(key);
        if (nval == 0) {
            return null;
        }
        if (nval == 1) {
            return mapGet0C(key);
        }
        String[] parts = mapGet1C(key);
        if (parts == null) {
            return null;
        }
        return "(" + String.join(",", parts) + ")";
    }

    /**
     * Whether a key has a value ({@code astMapDefined}): false for a key stored with no value and
     * for a missing key, which raises {@code AST__MPKER} when KeyError is set.
     */
    boolean mapDefined(String key) {
        String nkey = convertKey(key, "astMapDefined");
        KeyMapEntry entry = lookup(nkey);
        if (entry == null) {
            if (getKeyError()) {
                throw missing("astMapDefined", nkey);
            }
            return false;
        }
        return entry.defined();
    }

    /** The comment stored with a key, or null. C keeps it only for a dump. */
    String mapComment(String key) {
        KeyMapEntry entry;
        try {
            entry = lookup(convertKey(key, "map_comment"));
        } catch (AstException e) {
            return null;
        }
        return entry == null ? null : entry.comment;
    }

    /**
     * Renames a key ({@code astMapRename}). Nothing happens if the two keys are the same or the
     * old key is missing; the entry keeps the age of an entry it replaces.
     *
     * @throws AstException with status {@code AST__BADKEY} if the new key is not in a locked
     *     KeyMap, which is then unchanged
     */
    void mapRename(String oldKey, String newKey) {
        String oldConverted = convertKey(oldKey, "astMapRename");
        String newConverted = convertKey(newKey, "astMapRename");
        String old = KeyMapTable.trimSpaces(oldConverted);
        String nu = KeyMapTable.trimSpaces(newConverted);
        if (old.equals(nu)) {
            return;
        }
        KeyMapEntry entry = entries.get(old);
        if (entry == null) {
            return;
        }
        KeyMapEntry previous = entries.get(nu);
        if (previous == null && getMapLocked()) {
            throw Ast.err(
                    AstException.AST__BADKEY,
                    "astMapRename(KeyMap): Failed to rename item \"" + oldConverted + "\" in a KeyMap to \""
                            + newConverted + "\": \"" + newConverted + "\" is not a known item.");
        }
        entries.remove(old);
        entries.remove(nu);
        members++;
        entry.member = members;
        if (previous != null) {
            entry.keymember = previous.keymember;
        } else {
            members++;
            entry.keymember = members;
        }
        entries.put(nu, entry);
    }

    /**
     * Copies every entry of {@code source} into this KeyMap ({@code astMapCopy}), replacing
     * entries with the same keys; a KeyMap held as a single value under a key in both is merged
     * rather than replaced. The Objects copied are copies.
     *
     * @throws AstException with status {@code AST__BADKEY} if a key is new and this KeyMap is
     *     locked
     */
    void mapCopy(KeyMap source) {
        for (String key : source.sortedKeys()) {
            String outKey = KeyMapTable.trimSpaces(convertKey(key, "astMapCopy"));
            copyOne(key, outKey, outKey, source, true, "astMapCopy");
        }
    }

    /**
     * Copies the entry of {@code source} under {@code key} into this KeyMap ({@code
     * astMapCopyEntry}); a key missing from {@code source} is ignored. Each KeyMap's own KeyCase
     * converts the key. With {@code merge}, a KeyMap held as a single value under the key in both
     * is merged rather than replaced.
     */
    void mapCopyEntry(String key, KeyMap source, boolean merge) {
        String inKey = KeyMapTable.trimSpaces(KeyMapTable.convertKey(key, source.getKeyCase(), "astMapCopyEntry"));
        String outKey = convertKey(key, "astMapCopyEntry");
        if (!source.entries.containsKey(inKey)) {
            return;
        }
        copyOne(inKey, KeyMapTable.trimSpaces(outKey), outKey, source, merge, "astMapCopyEntry");
    }

    private void copyOne(String inKey, String outKey, String messageKey, KeyMap source, boolean merge, String method) {
        KeyMapEntry in = source.entries.get(inKey);
        KeyMapEntry out = entries.get(outKey);
        if (out == null) {
            if (getMapLocked()) {
                throw Ast.err(
                        AstException.AST__BADKEY,
                        method + "(KeyMap): Failed to copy item \"" + messageKey + "\": \"" + messageKey
                                + "\" is not a known item.");
            }
            insertNew(outKey, in.copy());
            return;
        }
        // Two KeyMaps held as single values merge rather than replace.
        KeyMap innerSrc = in.singleKeyMap();
        KeyMap innerDest = out.singleKeyMap();
        if (merge && innerSrc != null && innerDest != null) {
            innerDest.mapCopy(innerSrc);
            return;
        }
        KeyMapEntry copied = in.copy();
        members++;
        copied.member = members;
        copied.keymember = out.keymember;
        entries.put(outKey, copied);
    }

    /** The keys in the order SortBy gives. */
    List<String> sortedKeys() {
        KeyMapSort order = getSortBy();
        List<String> keys = new ArrayList<>(entries.keySet());
        keys.sort((a, b) -> order.compare(a, entries.get(a), b, entries.get(b)));
        return keys;
    }

    /**
     * Returns the key at a given index, in the order the SortBy attribute gives.
     *
     * @param index the index, from 0 to one less than {@link #mapSize}
     * @return the key
     * @throws AstException with status {@code AST__MPIND} if there is no key at that index
     */
    public String mapKey(int index) {
        List<String> keys = sortedKeys();
        if (index < 0 || index >= keys.size()) {
            throw Ast.err(
                    AstException.AST__MPIND,
                    "astMapKey(KeyMap): Cannot find element " + index + " (zero-based) of the KeyMap.");
        }
        return keys.get(index);
    }

    /**
     * The next key in the order SortBy gives ({@code astMapIterate}), starting again at the first
     * when {@code reset} is true, or null after the last.
     */
    String mapIterate(boolean reset) {
        if (reset) {
            iterIndex = 0;
        }
        List<String> keys = sortedKeys();
        if (iterIndex < keys.size()) {
            return keys.get(iterIndex++);
        }
        iterIndex = 0;
        return null;
    }

    /** The member counter, as a dump records it ({@code MemCnt}). */
    long memberCount() {
        return members;
    }
}
