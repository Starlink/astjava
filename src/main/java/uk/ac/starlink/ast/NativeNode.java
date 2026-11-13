package uk.ac.starlink.ast;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One Object's description in AST's native text format: its class name, then its items in order.
 * An item is a named value (an integer, a double, a string or a nested Object's description) that
 * is set or unset (written as a comment), or an {@code IsA} marker ending the items of one class.
 *
 * <p>Each class's {@code dump} method adds its items, as C's chained {@code Dump} functions write
 * them through {@code astWriteInt} and friends, and each class's loading constructor reads its own
 * items back, as C's {@code astLoad<Class>} functions read them through {@code astReadInt} and
 * friends. {@link NativeWriter} renders a description as text and {@link NativeReader} and {@link
 * NativeStream} parse text into one.
 *
 * <p>Ported from Rust {@code serial/node.rs}.
 */
final class NativeNode {

    /** The key of an {@code IsA} marker. */
    static final String ISA = "IsA";

    /** The kinds of value an item holds. */
    enum Kind {
        INT,
        DOUBLE,
        STRING,
        OBJECT
    }

    /** A shared flag recording whether a loader has read something. */
    static final class Flag {
        private boolean value;

        void set() {
            value = true;
        }

        boolean get() {
            return value;
        }
    }

    /** A typed value. Two values are equal when their kinds and payloads are equal. */
    // MODERN-JAVA(21): sealed interface with record variants and pattern-matching switch
    static final class Value {
        final Kind kind;
        final long intValue;
        final double doubleValue;
        final String stringValue;
        final NativeNode object;

        private Value(Kind kind, long i, double d, String s, NativeNode o) {
            this.kind = kind;
            this.intValue = i;
            this.doubleValue = d;
            this.stringValue = s;
            this.object = o;
        }

        static Value ofInt(long v) {
            return new Value(Kind.INT, v, 0.0, null, null);
        }

        static Value ofDouble(double v) {
            return new Value(Kind.DOUBLE, 0, v, null, null);
        }

        static Value ofString(String v) {
            return new Value(Kind.STRING, 0, 0.0, v, null);
        }

        static Value ofObject(NativeNode v) {
            return new Value(Kind.OBJECT, 0, 0.0, null, v);
        }

        @Override
        @SuppressWarnings("FloatingPointEquality") // Rust's f64 equality: 0.0 equals -0.0, NaN nothing
        public boolean equals(Object o) {
            if (!(o instanceof Value)) {
                return false;
            }
            Value v = (Value) o;
            if (v.kind != kind) {
                return false;
            }
            switch (kind) {
                case INT:
                    return v.intValue == intValue;
                case DOUBLE:
                    return v.doubleValue == doubleValue;
                case STRING:
                    return v.stringValue.equals(stringValue);
                case OBJECT:
                    return v.object.equals(object);
                default:
                    throw Ast.err(AstException.AST__INTER, "NativeNode: unknown value kind " + kind + ".");
            }
        }

        @Override
        public int hashCode() {
            switch (kind) {
                case INT:
                    return Long.hashCode(intValue);
                case DOUBLE:
                    return Double.hashCode(doubleValue == 0.0 ? 0.0 : doubleValue);
                case STRING:
                    return stringValue.hashCode();
                case OBJECT:
                    return object.hashCode();
                default:
                    throw Ast.err(AstException.AST__INTER, "NativeNode: unknown value kind " + kind + ".");
            }
        }

        @Override
        public String toString() {
            switch (kind) {
                case INT:
                    return "Int(" + intValue + ")";
                case DOUBLE:
                    return "Double(" + doubleValue + ")";
                case STRING:
                    return "String(" + stringValue + ")";
                case OBJECT:
                    return "Object(" + object + ")";
                default:
                    throw Ast.err(AstException.AST__INTER, "NativeNode: unknown value kind " + kind + ".");
            }
        }
    }

    /**
     * One item: a key, its value, whether the value is set (an unset value is written as a
     * comment), the comment text, whether a loader has read it, and whether an unset value is
     * helpful (written when Full is zero; channel.c {@code Use}). An {@code IsA} marker has the
     * key {@link #ISA} and the class name as a string value.
     */
    static final class Item {
        final String key;
        final Value value;
        final boolean set;
        final String comment;
        final boolean helpful;
        final Flag read = new Flag();

        /**
         * The value's text as C's reader holds it (channel.c {@code AstChannelValue}), with any
         * quotes removed, for an item read from a stream; null for one a dump made.
         */
        String text;

        Item(String key, Value value, boolean set, String comment, boolean helpful) {
            this.key = key;
            this.value = value;
            this.set = set;
            this.comment = comment;
            this.helpful = helpful;
        }

        boolean isIsA() {
            return ISA.equals(key);
        }

        /** Items compare by key, value, setting, comment and helpfulness; read flags take no part. */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Item)) {
                return false;
            }
            Item i = (Item) o;
            return i.key.equals(key)
                    && i.value.equals(value)
                    && i.set == set
                    && (i.comment == null ? comment == null : i.comment.equals(comment))
                    && i.helpful == helpful;
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + value.hashCode();
        }
    }

    /** The AST class name. */
    final String className;

    /** The comment on the {@code Begin} line, or null. */
    String classComment;

    /** The items, in order. */
    final List<Item> items = new ArrayList<>();

    /**
     * Whether the class writing this Object as part of its own sets the Channel's Full to -1
     * around it unless Full is 1, as C's Frame does for its Axes (frame.c {@code Dump}). Full
     * applies to the whole of the Object's description, however deep it goes.
     */
    boolean fullCapped;

    /** Whether a loader has looked anything up in this description, shared by its sections. */
    Flag accessed = new Flag();

    /** The class of the Channel reading this description, which C's read errors name. */
    String channelClass = "Channel";

    /**
     * For a description read from a stream, the reader's mark after the part of each class, where
     * C's {@code ReadClassData} has read to when that class's loader starts; null otherwise.
     */
    Map<String, Integer> partEnds;

    /**
     * For a description read from a stream, shared by it, its sections and the descriptions nested
     * in it: the mark after the part of the class whose loader last asked for it, which is how far
     * C has read if that loader fails; null otherwise.
     */
    Progress progress;

    /** How far C's reader has read while an Object read from a stream is loaded. */
    static final class Progress {
        /** The reader's mark, or -1 if no loader has asked for its part. */
        int mark = -1;
    }

    NativeNode(String className) {
        this.className = className;
    }

    NativeNode(String className, String classComment) {
        this.className = className;
        this.classComment = classComment;
    }

    /** The description of {@code obj}, as C's {@code astDump} writes it. */
    static NativeNode of(AstObject obj) {
        NativeNode node = new NativeNode(obj.astClass(), obj.dumpComment());
        obj.dump(node);
        return node;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Appends an item. */
    void add(String key, Value value, boolean set, String comment, boolean helpful) {
        items.add(new Item(key, value, set, comment, set || helpful));
    }

    /**
     * Adds a value as C's {@code astWriteInt}, {@code astWriteDouble}, {@code astWriteString}
     * and {@code astWriteObject} do: active if {@code set}, otherwise as a comment that is written
     * when Full is zero only if {@code helpful}, and always when Full is positive (channel.c
     * {@code Use}).
     */
    void write(String key, boolean set, boolean helpful, Value value, String comment) {
        add(key, value, set, comment, helpful);
    }

    void writeInt(String key, boolean set, boolean helpful, long value, String comment) {
        write(key, set, helpful, Value.ofInt(value), comment);
    }

    void writeDouble(String key, boolean set, boolean helpful, double value, String comment) {
        write(key, set, helpful, Value.ofDouble(value), comment);
    }

    void writeString(String key, boolean set, boolean helpful, String value, String comment) {
        write(key, set, helpful, Value.ofString(value), comment);
    }

    void writeObject(String key, boolean set, boolean helpful, NativeNode value, String comment) {
        write(key, set, helpful, Value.ofObject(value), comment);
    }

    /** Adds an {@code IsA} marker, as C's {@code astWriteIsA} does. */
    void writeIsA(String cls, String comment) {
        add(ISA, Value.ofString(cls), true, comment, true);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Whether two keys are equal ignoring the case of ASCII letters. */
    static boolean keyMatches(String itemKey, String key) {
        if (itemKey.length() != key.length()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (CScan.toLower(itemKey.charAt(i)) != CScan.toLower(key.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The value of the first item called {@code key}, ignoring ASCII case as C's reader does, or
     * null if there is none or its value is not set. Records the read.
     */
    Value get(String key) {
        accessed.set();
        for (Item item : items) {
            if (keyMatches(item.key, key)) {
                if (!item.set) {
                    return null;
                }
                item.read.set();
                return item.value;
            }
        }
        return null;
    }

    /** The value of the first item called {@code key}, set or not, or null. Records the read. */
    Value getAny(String key) {
        accessed.set();
        for (Item item : items) {
            if (keyMatches(item.key, key)) {
                item.read.set();
                return item.value;
            }
        }
        return null;
    }

    /** The first item called {@code key}, or null, without recording a read. */
    Item item(String key) {
        for (Item item : items) {
            if (keyMatches(item.key, key)) {
                return item;
            }
        }
        return null;
    }

    private static Long asLong(Value v) {
        if (v == null) {
            return null;
        }
        switch (v.kind) {
            case INT:
                return v.intValue;
            case DOUBLE:
                // Rust's `as i64`: truncation, saturating, NaN to zero; Java's (long) does the same.
                return (long) v.doubleValue;
            case STRING:
            case OBJECT:
                return null;
            default:
                throw Ast.err(AstException.AST__INTER, "NativeNode: unknown value kind " + v.kind + ".");
        }
    }

    /** The integer value of {@code key}, set or not, or null if there is no such number. */
    Long getIntAny(String key) {
        return asLong(getAny(key));
    }

    /**
     * The first item called {@code key}, ignoring ASCII case, or null if there is none or its value
     * is not set. Records the read.
     */
    private Item setItem(String key) {
        accessed.set();
        for (Item item : items) {
            if (keyMatches(item.key, key)) {
                if (!item.set) {
                    return null;
                }
                item.read.set();
                return item;
            }
        }
        return null;
    }

    /** The text C's reader holds for {@code item}'s value. */
    private static String cText(Item item) {
        if (item.text != null) {
            return item.text;
        }
        Value v = item.value;
        switch (v.kind) {
            case INT:
                return Long.toString(v.intValue);
            case DOUBLE:
                return CFormat.format("%.17g", v.doubleValue);
            case STRING:
                return v.stringValue;
            case OBJECT:
                return "<" + v.object.className + ">";
            default:
                throw Ast.err(AstException.AST__INTER, "NativeNode: unknown value kind " + v.kind + ".");
        }
    }

    /** The text C scans for the string value of {@code item}. */
    private static String scanText(Item item) {
        return item.text != null ? item.text : item.value.stringValue;
    }

    /** C's error for a value that cannot be read as {@code what} (channel.c ReadInt, ReadDouble). */
    private AstException notA(Item item, String what) {
        String name = item.key.toLowerCase(Locale.ROOT);
        if (item.value.kind == Kind.OBJECT) {
            return Ast.err(
                    AstException.AST__BADIN,
                    "astRead(" + channelClass + "): The Object \"" + name + " = <" + item.value.object.className
                            + ">\" cannot be read as " + what + ".");
        }
        return Ast.err(
                AstException.AST__BADIN,
                "astRead(" + channelClass + "): The value \"" + name + " = " + cText(item) + "\" cannot be read as "
                        + what + ".");
    }

    /**
     * C's {@code astReadInt} of an item (channel.c:3165-3180): its text read with {@code sscanf}'s
     * {@code " %d %n"}, which must take all of it, or AST__BADIN. A number beyond the range of an
     * {@code int} is AST__BADIN too: {@code sscanf}'s result for it is undefined in C, and the
     * pinned library on macOS keeps its low 32 bits, which can make a valid-looking count or
     * index; this check has no counterpart in C.
     */
    private int cInt(Item item) {
        Value v = item.value;
        Long n;
        switch (v.kind) {
            case INT:
                n = v.intValue;
                break;
            case DOUBLE:
                // A decimal point, an exponent, "<bad>" or an integer beyond a long, at which %d
                // stops before the end of the text or which it cannot hold.
                n = null;
                break;
            case STRING:
                n = CScan.scanLong(scanText(item));
                break;
            case OBJECT:
                throw notA(item, "an integer");
            default:
                throw Ast.err(AstException.AST__INTER, "NativeNode: unknown value kind " + v.kind + ".");
        }
        if (n == null || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) {
            throw notA(item, "an integer");
        }
        return (int) (long) n;
    }

    /**
     * C's {@code astReadInt64} of an item (channel.c {@code ReadInt64}): its text read with {@code
     * sscanf}'s {@code " %" SCNd64 " %n"}, which must take all of it, or AST__BADIN. A number
     * beyond the range of a {@code long}, for which {@code sscanf}'s result is undefined in C, is
     * AST__BADIN too; this check has no counterpart in C.
     */
    private long cInt64(Item item) {
        Value v = item.value;
        switch (v.kind) {
            case INT:
                return v.intValue;
            case DOUBLE:
                throw notA(item, "a 64 bit integer");
            case STRING:
                // CScan.scanLong checks the form of the text; the exact value then shows whether a
                // long holds it.
                String t = scanText(item);
                BigInteger exact = CScan.scanLong(t) == null ? null : exactInteger(t);
                if (exact == null || exact.bitLength() > 63) {
                    throw notA(item, "a 64 bit integer");
                }
                return exact.longValue();
            case OBJECT:
                throw notA(item, "a 64 bit integer");
            default:
                throw Ast.err(AstException.AST__INTER, "NativeNode: unknown value kind " + v.kind + ".");
        }
    }

    /** The integer {@code s} holds, with optional white space and sign, or null. */
    private static BigInteger exactInteger(String s) {
        String t = s.trim();
        if (t.startsWith("+")) {
            t = t.substring(1);
        }
        try {
            return new BigInteger(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * C's {@code astReadDouble} of an item (channel.c:3039-3075): {@code <bad>} is {@code AST__BAD};
     * otherwise its text read with {@code sscanf}'s {@code " %lf %n"}, which must take all of it,
     * and a value that is not finite, are AST__BADIN.
     */
    private double cDouble(Item item) {
        Value v = item.value;
        double d;
        switch (v.kind) {
            case DOUBLE:
                d = v.doubleValue;
                break;
            case INT:
                return v.intValue;
            case STRING:
                String t = scanText(item);
                if ("<bad>".equals(t.trim())) {
                    return AstObject.AST__BAD;
                }
                Double scanned = CScan.scanDouble(t);
                if (scanned == null) {
                    throw notA(item, "a double precision floating point number");
                }
                d = scanned;
                break;
            case OBJECT:
                throw notA(item, "a double precision floating point number");
            default:
                throw Ast.err(AstException.AST__INTER, "NativeNode: unknown value kind " + v.kind + ".");
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw Ast.err(
                    AstException.AST__BADIN,
                    "astRead(" + channelClass + "): Illegal double precision floating point value \"" + cText(item)
                            + "\" read for \"" + item.key.toLowerCase(Locale.ROOT) + "\".");
        }
        return d;
    }

    /**
     * The integer value of {@code key}, as C's {@code astReadInt} reads it, or null if it is absent
     * or unset.
     *
     * @throws AstException with status AST__BADIN if the value cannot be read as an integer
     */
    Long getIntIfSet(String key) {
        Item item = setItem(key);
        return item == null ? null : (long) cInt(item);
    }

    /**
     * The 64-bit integer value of {@code key}, as C's {@code astReadInt64} reads it, or {@code
     * dflt}.
     *
     * @throws AstException with status AST__BADIN if the value cannot be read as such an integer
     */
    long readLong(String key, long dflt) {
        Item item = setItem(key);
        return item == null ? dflt : cInt64(item);
    }

    /**
     * The integer value of {@code key}, as C's {@code astReadInt} reads it, or {@code dflt}.
     *
     * @throws AstException with status AST__BADIN if the value cannot be read as an integer
     */
    int readInt(String key, int dflt) {
        Item item = setItem(key);
        return item == null ? dflt : cInt(item);
    }

    /**
     * The double value of {@code key}, as C's {@code astReadDouble} reads it, or {@code dflt}.
     *
     * @throws AstException with status AST__BADIN if the value cannot be read as a finite double
     */
    double readDouble(String key, double dflt) {
        Item item = setItem(key);
        return item == null ? dflt : cDouble(item);
    }

    /**
     * The double value of {@code key}, as C's {@code astReadDouble} reads it, or null if it is
     * absent or unset.
     *
     * @throws AstException with status AST__BADIN if the value cannot be read as a finite double
     */
    Double getDoubleIfSet(String key) {
        Item item = setItem(key);
        return item == null ? null : cDouble(item);
    }

    /** The string value of {@code key}, or {@code dflt}. */
    String readString(String key, String dflt) {
        String v = getStringIfSet(key);
        return v == null ? dflt : v;
    }

    /** The string value of {@code key}, or null if it is absent, unset or not a string. */
    String getStringIfSet(String key) {
        Value v = get(key);
        return v != null && v.kind == Kind.STRING ? v.stringValue : null;
    }

    /** The nested Object description of {@code key}, or null. */
    NativeNode readObject(String key) {
        Value v = get(key);
        return v != null && v.kind == Kind.OBJECT ? v.object : null;
    }

    /**
     * Records that the loader has read the items called {@code key}, which C's loader reads but
     * whose values come from elsewhere, so that a Channel does not report them as unrecognised.
     */
    void markRead(String key) {
        accessed.set();
        for (Item item : items) {
            if (keyMatches(item.key, key)) {
                item.read.set();
            }
        }
    }

    /**
     * The part of this description that belongs to {@code cls}, as C's {@code astReadClassData}
     * gives it to that class's loader (channel.c {@code ReadClassData}): the items between the
     * previous {@code IsA} marker and {@code IsA cls}, or, for the Object's own class, those after
     * the last marker. A part that is not there is empty, as is the part of a class whose marker
     * was left out because nothing preceded it: those items belong to the class named by the next
     * marker. The items are shared with this description, so reads through the section are
     * recorded here.
     */
    NativeNode section(String cls) {
        NativeNode node = new NativeNode(className, classComment);
        node.accessed = accessed;
        node.channelClass = channelClass;
        node.progress = progress;
        if (progress != null && partEnds != null && partEnds.containsKey(cls)) {
            progress.mark = partEnds.get(cls);
        }
        List<Item> current = new ArrayList<>();
        for (Item item : items) {
            if (item.isIsA()) {
                if (item.value.kind == Kind.STRING && item.value.stringValue.equals(cls)) {
                    node.items.addAll(current);
                    return node;
                }
                current = new ArrayList<>();
            } else {
                current.add(item);
            }
        }
        if (className.equals(cls)) {
            node.items.addAll(current);
        }
        return node;
    }

    /** Descriptions compare by class, class comment, items and Full capping. */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NativeNode)) {
            return false;
        }
        NativeNode n = (NativeNode) o;
        return n.className.equals(className)
                && (n.classComment == null ? classComment == null : n.classComment.equals(classComment))
                && n.items.equals(items)
                && n.fullCapped == fullCapped;
    }

    @Override
    public int hashCode() {
        return className.hashCode() * 31 + items.size();
    }

    @Override
    public String toString() {
        return "NativeNode(" + className + ")";
    }
}
