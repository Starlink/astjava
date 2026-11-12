package uk.ac.starlink.ast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The base class of all AST objects.
 *
 * <p>An Object has a set of attributes, which can be read and written by name through the
 * string-based methods of this class ({@link #set}, {@link #getC} and the other typed forms,
 * {@link #test} and {@link #clear}) as well as through the named accessors of each class. An
 * attribute name may be given in any case and with embedded white space; an axis-indexed name has
 * the form {@code Label(1)}.
 *
 * <p>The attributes of the Object class itself are:
 *
 * <ul>
 *   <li>{@code Class} (read-only): the name of the class of the Object.
 *   <li>{@code ID}: a string identifying the Object, which a copy does not inherit.
 *   <li>{@code Ident}: a permanent string identifying the Object, which a copy inherits.
 *   <li>{@code Nobject} (read-only), {@code ObjSize} (read-only) and {@code RefCount}
 *       (read-only): diagnostic values (see {@link #getNobject}, {@link #getObjSize} and {@link
 *       #getRefCount}).
 *   <li>{@code UseDefs}: whether default values may be used for attributes that have not been
 *       set.
 * </ul>
 *
 * <p>Memory is managed by the garbage collector: there is no need to annul or delete an Object,
 * and {@link #copy} makes a deep copy.
 *
 * <p>An Object may be used from any thread, but not from two threads at once. A thread that needs
 * an Object another thread is using should take its own {@link #copy}.
 */
public class AstObject implements Cloneable {

    /** The bad value, which marks a coordinate value that is missing or undefined. */
    public static final double AST__BAD = -Double.MAX_VALUE;

    /** The value which, given to {@link #tune}, reads a tuning parameter without changing it. */
    public static final int AST__TUNULL = -99999;

    /** The value of an unset integer attribute that can only be 0 or 1. */
    static final int UNSET_FLAG = -1;

    /** The values of the tuning parameters ObjectCaching and MemoryCaching, for this thread. */
    private static final ThreadLocal<int[]> TUNING = ThreadLocal.withInitial(() -> new int[2]);

    /** The ID attribute, or null if it is not set. */
    private String id;

    /** The Ident attribute, or null if it is not set. */
    private String ident;

    /** The UseDefs attribute: 0, 1, or {@link #UNSET_FLAG}. */
    private int useDefs = UNSET_FLAG;

    /** Constructs an Object. */
    protected AstObject() {}

    /**
     * Constructs an Object from its description, reading the Object class's part of it as C's
     * {@code astLoadObject} (object.c) does: ID, Ident and UseDefs. The counts {@code RefCnt} and
     * {@code Nobj} are read and discarded.
     *
     * @param node the description
     */
    AstObject(NativeNode node) {
        NativeNode part = node.section("Object");
        id = part.getStringIfSet("ID");
        ident = part.getStringIfSet("Ident");
        Long u = part.getIntIfSet("UseDfs");
        useDefs = u == null ? UNSET_FLAG : (u != 0 ? 1 : 0);
        part.markRead("RefCnt");
        part.markRead("Nobj");
    }

    /**
     * Writes the Object class's part of a description, as C's {@code Dump} (object.c) does: ID and
     * Ident, active when set and otherwise written only if not blank, and UseDefs. C also writes
     * the unset counts {@code RefCnt} and {@code Nobj}, which have no meaning here and are not
     * written. Each class calls {@code super.dump(node)}, then writes the {@code IsA} marker of its
     * parent class with {@link NativeNode#writeIsA}, then its own items.
     *
     * @param node the description being written
     */
    void dump(NativeNode node) {
        String sval = getID();
        node.writeString("ID", id != null, !sval.isEmpty(), sval, "Object identification string");
        sval = getIdent();
        node.writeString("Ident", ident != null, !sval.isEmpty(), sval, "Permanent Object identification string");
        boolean ival = getUseDefs();
        node.writeInt(
                "UseDfs",
                useDefs != UNSET_FLAG,
                false,
                ival ? 1 : 0,
                ival ? "Default attribute values can be used" : "Default values cannot be used");
    }

    /** The comment on the {@code Begin} line of a description: what the class is. */
    String dumpComment() {
        return "AST Object";
    }

    // ------------------------------------------------------------------
    // Copying and identity
    // ------------------------------------------------------------------

    /**
     * Returns a deep copy of this Object: a new Object of the same class with copies of all its
     * attributes and of any Objects it holds. The ID attribute is not copied.
     *
     * @return the copy
     */
    public AstObject copy() {
        AstObject copy;
        try {
            copy = (AstObject) super.clone();
        } catch (CloneNotSupportedException e) {
            AstException err = Ast.err(AstException.AST__INTER, "astCopy(" + astClass() + "): cannot clone.");
            err.initCause(e);
            throw err;
        }
        copy.deepCopy();
        return copy;
    }

    /**
     * Completes a copy made by {@link #copy}, replacing the mutable fields that the copy shares
     * with its original, as C's per-class {@code Copy} functions do. Each class calls
     * {@code super.deepCopy()} first.
     */
    void deepCopy() {
        // C's Copy (object.c) does not copy the ID.
        id = null;
    }

    /**
     * Has no effect: memory is managed by the garbage collector. Present so that code written for
     * the JNI interface compiles.
     *
     * @deprecated there is nothing to annul
     */
    @Deprecated
    public void annul() {}

    /**
     * Has no effect: memory is managed by the garbage collector. Present so that code written for
     * the JNI interface compiles.
     *
     * @deprecated there is nothing to delete
     */
    @Deprecated
    public void delete() {}

    /**
     * Tests whether {@code obj} is this Object. Two different Objects may be {@code equals}, but
     * only an Object is the same object as itself.
     *
     * @param obj the object to test
     * @return true if {@code obj} is this Object
     */
    // Identity, not equality, is what this method tests.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    public boolean sameObject(Object obj) {
        return obj == this;
    }

    /**
     * Tests whether {@code obj} resembles this Object in all respects: whether it is of the same
     * class and is written to a Channel (with Full -1 and no comments) as the same text. This may
     * be expensive.
     *
     * @param obj the object to compare with this one
     * @return true if {@code obj} is an Object of the same class with the same description
     */
    @Override
    // The contract compares exact classes, as jniast's does.
    @SuppressWarnings("EqualsGetClass")
    public boolean equals(Object obj) {
        if (obj == null || !obj.getClass().equals(getClass())) {
            return false;
        }
        return compactText().equals(((AstObject) obj).compactText());
    }

    /**
     * Returns a hash code consistent with {@link #equals}: that of the text this Object is written
     * as by a Channel with Full -1 and no comments. This may be expensive.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return compactText().hashCode();
    }

    /** The text {@link #equals} compares. */
    private String compactText() {
        return Channel.text(this, false, -1);
    }

    /**
     * Returns the text a default Channel writes for this Object, which {@link #show} displays.
     *
     * @return the description of this Object in AST's native format
     */
    @Override
    public String toString() {
        return Channel.text(this, true, 0);
    }

    /**
     * Displays a description of this Object on standard output, as a default Channel writes it.
     * This is meant as an aid to debugging.
     */
    public void show() {
        try {
            new Channel(null, null).write(this);
        } catch (IOException e) {
            AstException err = Ast.err(AstException.AST__WRERR, "astShow(" + astClass() + "): " + e.getMessage());
            err.initCause(e);
            throw err;
        }
    }

    /**
     * Returns whether the library can be used from several threads. It always can, as long as no
     * Object is used by two threads at once.
     *
     * @return true
     */
    public static boolean isThreaded() {
        return true;
    }

    /**
     * Returns a string giving the version of AST this library follows.
     *
     * @return the version string
     */
    public static String reportVersions() {
        return "AST V9.5-0; pure Java; " + (isThreaded() ? "threaded" : "unthreaded");
    }

    /**
     * Sets or reads a tuning parameter. The parameters are {@code ObjectCaching} and
     * {@code MemoryCaching}, which control caches of the C library and have no effect here; their
     * values are kept for each thread, and default to zero.
     *
     * @param name the parameter name, in any case
     * @param value the new value, or {@link #AST__TUNULL} to leave the value unchanged
     * @return the value before this call
     * @throws AstException with status {@code AST__TUNAM} if the name is not a tuning parameter
     */
    public static int tune(String name, int value) {
        int index;
        if (chrMatch(name, "ObjectCaching")) {
            index = 0;
        } else if (chrMatch(name, "MemoryCaching")) {
            index = 1;
        } else {
            throw Ast.err(AstException.AST__TUNAM, "astTune: Unknown AST tuning parameter specified \"" + name + "\".");
        }
        int[] values = TUNING.get();
        int old = values[index];
        if (value != AST__TUNULL) {
            values[index] = value;
        }
        return old;
    }

    /**
     * C's {@code astChrMatch}: whether two strings are equal ignoring case and trailing white
     * space.
     */
    static boolean chrMatch(String a, String b) {
        int n = Math.max(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            boolean ea = i >= a.length();
            boolean eb = i >= b.length();
            boolean match = (ea && CScan.isCSpace(b.charAt(i)))
                    || (eb && CScan.isCSpace(a.charAt(i)))
                    || (!ea && !eb && CScan.toLower(a.charAt(i)) == CScan.toLower(b.charAt(i)));
            if (!match) {
                return false;
            }
        }
        return true;
    }

    /** The AST class name of this Object, the value of its Class attribute. */
    final String astClass() {
        Class<?> c = getClass();
        while (c.getSimpleName().isEmpty()) {
            c = c.getSuperclass();
        }
        return c.getSimpleName();
    }

    // ------------------------------------------------------------------
    // String-based attribute access
    // ------------------------------------------------------------------

    /** An attribute name as C normalises it: white space removed and letters in lower case. */
    static String normaliseName(String attrib) {
        StringBuilder sb = new StringBuilder(attrib.length());
        for (int i = 0; i < attrib.length(); i++) {
            char c = attrib.charAt(i);
            if (!CScan.isCSpace(c)) {
                sb.append(CScan.toLower(c));
            }
        }
        return sb.toString();
    }

    /** C's {@code Get} (object.c): the value of an attribute as a string, or null. */
    private String get(String attrib) {
        String name = normaliseName(attrib);
        if (name.isEmpty()) {
            throw Ast.err(AstException.AST__BADAT, "astGet(" + astClass() + "): A blank attribute name was given.");
        }
        return getAttrib(name);
    }

    /**
     * Gets an attribute value as a string.
     *
     * @param attrib the attribute name
     * @return the value
     * @throws AstException if the Object has no such attribute
     */
    public String getC(String attrib) {
        String value = get(attrib);
        return value == null ? "" : value;
    }

    private AstException atger(String code, String attrib, String value) {
        return Ast.err(
                AstException.AST__ATGER,
                "astGet" + code + "(" + astClass() + "): The attribute value \"" + attrib + "=" + value
                        + "\" cannot be read using the requested data type.");
    }

    /**
     * Gets an attribute value as a double.
     *
     * @param attrib the attribute name
     * @return the value
     * @throws AstException if the Object has no such attribute, or its value is not a number
     */
    public double getD(String attrib) {
        String str = getC(attrib);
        Double v = CScan.scanDouble(str);
        if (v == null) {
            throw atger("D", attrib, str);
        }
        return v;
    }

    /**
     * Gets an attribute value as a float.
     *
     * @param attrib the attribute name
     * @return the value
     * @throws AstException if the Object has no such attribute, or its value is not a number
     */
    public float getF(String attrib) {
        String str = getC(attrib);
        Float v = CScan.scanFloat(str);
        if (v == null) {
            throw atger("F", attrib, str);
        }
        return v;
    }

    /**
     * Gets an attribute value as an int.
     *
     * @param attrib the attribute name
     * @return the value
     * @throws AstException if the Object has no such attribute, or its value is not an integer
     */
    public int getI(String attrib) {
        String str = getC(attrib);
        Integer v = CScan.scanInt(str);
        if (v == null) {
            throw atger("I", attrib, str);
        }
        return v;
    }

    /**
     * Gets an attribute value as a long.
     *
     * @param attrib the attribute name
     * @return the value
     * @throws AstException if the Object has no such attribute, or its value is not an integer
     */
    public long getL(String attrib) {
        String str = getC(attrib);
        Long v = CScan.scanLong(str);
        if (v == null) {
            throw atger("L", attrib, str);
        }
        return v;
    }

    /**
     * Gets an attribute value as a boolean: true if its integer value is not zero.
     *
     * @param attrib the attribute name
     * @return the value
     * @throws AstException if the Object has no such attribute, or its value is not an integer
     */
    public boolean getB(String attrib) {
        return getI(attrib) != 0;
    }

    /** The attribute name with trailing white space removed, as C's {@code astChrLen} measures it. */
    private static String trimTrailing(String s) {
        int n = s.length();
        while (n > 0 && CScan.isCSpace(s.charAt(n - 1))) {
            n--;
        }
        return s.substring(0, n);
    }

    /**
     * Sets an attribute value from a string. A comma in the value is part of the value.
     *
     * @param attrib the attribute name
     * @param value the value
     * @throws AstException if the Object has no such writable attribute, or the value is invalid
     */
    public void setC(String attrib, String value) {
        // astSetC replaces commas in the value by '\r', which VSet turns back into commas, so they
        // cannot separate settings.
        vset(trimTrailing(attrib) + "=", value.replace(',', '\r'));
    }

    /**
     * Sets an attribute value from a double, formatted with 17 significant digits.
     *
     * @param attrib the attribute name
     * @param value the value
     * @throws AstException if the Object has no such writable attribute, or the value is invalid
     */
    public void setD(String attrib, double value) {
        vset(trimTrailing(attrib) + "=", CFormat.format("%.17g", value));
    }

    /**
     * Sets an attribute value from a float, formatted with 6 significant digits.
     *
     * @param attrib the attribute name
     * @param value the value
     * @throws AstException if the Object has no such writable attribute, or the value is invalid
     */
    public void setF(String attrib, float value) {
        vset(trimTrailing(attrib) + "=", CFormat.format("%.6g", (double) value));
    }

    /**
     * Sets an attribute value from an int.
     *
     * @param attrib the attribute name
     * @param value the value
     * @throws AstException if the Object has no such writable attribute, or the value is invalid
     */
    public void setI(String attrib, int value) {
        vset(trimTrailing(attrib) + "=", Integer.toString(value));
    }

    /**
     * Sets an attribute value from a long.
     *
     * @param attrib the attribute name
     * @param value the value
     * @throws AstException if the Object has no such writable attribute, or the value is invalid
     */
    public void setL(String attrib, long value) {
        vset(trimTrailing(attrib) + "=", Long.toString(value));
    }

    /**
     * Sets an attribute value from a boolean, as the integer 1 or 0.
     *
     * @param attrib the attribute name
     * @param value the value
     * @throws AstException if the Object has no such writable attribute, or the value is invalid
     */
    public void setB(String attrib, boolean value) {
        setI(attrib, value ? 1 : 0);
    }

    /**
     * Sets attribute values. The settings are a comma-separated list of {@code name=value}
     * assignments, such as {@code "Title=My Graph, Label(1)=X"}. If there is only one assignment,
     * any commas are part of its value. A value enclosed in double quotes may contain commas; the
     * quotes are removed. Names may be in any case and contain white space; values are used as
     * given.
     *
     * @param settings the settings
     * @throws AstException if an attribute does not exist or is read-only, or a value is invalid
     */
    public void set(String settings) {
        vset(settings, "");
    }

    /**
     * C's {@code VSet} (object.c) for a settings string {@code head} with {@code value} substituted
     * after it, as {@code astSet} substitutes the values of a format's conversions. Commas in
     * {@code head} separate assignments when it holds more than one '=' (outside quotes); commas
     * in the substituted value never do.
     */
    private void vset(String head, String value) {
        if (head.isEmpty() && value.isEmpty()) {
            return;
        }
        StringBuilder buff = new StringBuilder(head);
        int eq1 = head.indexOf('=');
        // The format string C examines is the head followed by a conversion specifier, which holds
        // no '=' or comma, so only the head's characters count.
        if (eq1 >= 0 && head.indexOf('=', eq1 + 1) >= 0) {
            boolean quoted = false;
            for (int i = 0; i < buff.length(); i++) {
                char c = buff.charAt(i);
                if (!quoted) {
                    if (c == ',') {
                        buff.setCharAt(i, '\n');
                    } else if (c == '"') {
                        quoted = true;
                    }
                } else if (c == '"') {
                    quoted = false;
                }
            }
        }
        buff.append(value);
        String text = buff.toString();
        int start = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            String setting = revise(text.substring(start, end));
            if (!setting.isEmpty()) {
                // With nothing after the '=', append a space so that "Title=" matches the
                // attribute's pattern in SetAttrib.
                applySetting(setting.endsWith("=") ? setting + " " : setting);
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
    }

    /**
     * Revises one assignment as C's {@code VSet} does: before the first '=', white space removed
     * and letters in lower case; after it, characters unchanged except that a '\r' becomes a
     * comma, and a value whose first non-blank character is a double quote loses that quote and
     * the last one.
     */
    private static String revise(String assign) {
        StringBuilder out = new StringBuilder(assign.length());
        boolean lo = true;
        int tq = -1;
        boolean quoted = false;
        for (int i = 0; i < assign.length(); i++) {
            char c = assign.charAt(i);
            if (c == '=') {
                lo = false;
            }
            if (!lo || !CScan.isCSpace(c)) {
                if (c == '\r') {
                    out.append(',');
                } else if (lo) {
                    out.append(CScan.toLower(c));
                } else {
                    out.append(c);
                    if (tq > 0 && !CScan.isCSpace(c)) {
                        if (c == '"') {
                            quoted = true;
                            out.setLength(out.length() - 1);
                        }
                        tq = 0;
                    }
                }
            }
            if (c == '=' && tq == -1) {
                tq = 1;
            }
        }
        if (quoted) {
            int j = out.length() - 1;
            while (j >= 0 && CScan.isCSpace(out.charAt(j))) {
                j--;
            }
            if (j >= 0 && out.charAt(j) == '"') {
                j--;
            }
            out.setLength(j + 1);
        }
        return out.toString();
    }

    /** Passes one revised {@code name=value} setting to {@link #setAttrib}. */
    private void applySetting(String setting) {
        int eq = setting.indexOf('=');
        if (eq < 0) {
            // No class's SetAttrib matches a setting without '=', so C reaches the Object class's
            // final error.
            throw badSetting(setting);
        }
        setAttrib(setting.substring(0, eq), setting.substring(eq + 1));
    }

    /**
     * Tests whether an attribute has been set.
     *
     * @param attrib the attribute name
     * @return true if the attribute has been set
     * @throws AstException if the Object has no such attribute
     */
    public boolean test(String attrib) {
        return testAttrib(normaliseName(attrib));
    }

    /**
     * Clears attribute values, restoring their defaults. The names are a comma-separated list.
     *
     * @param attrib the attribute names
     * @throws AstException if an attribute does not exist or is read-only
     */
    public void clear(String attrib) {
        if (attrib.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        int start = 0;
        while (true) {
            int comma = attrib.indexOf(',', start);
            names.add(attrib.substring(start, comma < 0 ? attrib.length() : comma));
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        for (String n : names) {
            String name = normaliseName(n);
            if (!name.isEmpty()) {
                clearAttrib(name);
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-class attribute hooks
    // ------------------------------------------------------------------

    /** Whether {@code name} is one of the Object class's read-only attributes. */
    private static boolean isReadOnly(String name) {
        return "class".equals(name) || "nobject".equals(name) || "objsize".equals(name) || "refcount".equals(name);
    }

    /** The error for a setting no class recognises (object.c {@code SetAttrib}). */
    final AstException badSetting(String setting) {
        return Ast.err(
                AstException.AST__BADAT,
                "astSet: The attribute setting \"" + setting + "\" is invalid for a " + astClass() + ".");
    }

    /**
     * Returns the value of an attribute as a string, as C's per-class {@code GetAttrib} does. Each
     * class handles its own attributes and passes any other name to its superclass.
     *
     * @param attrib the attribute name, in lower case without white space
     */
    String getAttrib(String attrib) {
        switch (attrib) {
            case "class":
                return astClass();
            case "id":
                return getID();
            case "ident":
                return getIdent();
            case "usedefs":
                return Integer.toString(getUseDefs() ? 1 : 0);
            case "nobject":
                return Integer.toString(getNobject());
            case "objsize":
                return Integer.toString(getObjSize());
            case "refcount":
                return Integer.toString(getRefCount());
            default:
                throw Ast.err(
                        AstException.AST__BADAT,
                        "astGet: The " + astClass() + " given does not have an attribute called \"" + attrib + "\".");
        }
    }

    /**
     * Sets an attribute from its value as a string, as C's per-class {@code SetAttrib} does. Each
     * class handles the settings it recognises and passes the rest to its superclass, including a
     * setting of one of its attributes whose value it cannot read.
     *
     * @param attrib the attribute name, in lower case without white space
     * @param value the value, as given after the '='; never empty
     */
    void setAttrib(String attrib, String value) {
        if ("id".equals(attrib)) {
            setID(value);
        } else if ("ident".equals(attrib)) {
            setIdent(value);
        } else if ("usedefs".equals(attrib) && CScan.scanInt(value) != null) {
            setUseDefs(CScan.scanInt(value) != 0);
        } else if (isReadOnly(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ". This is a read-only attribute.");
        } else {
            throw badSetting(attrib + "=" + value);
        }
    }

    /**
     * Returns whether an attribute has been set, as C's per-class {@code TestAttrib} does. A
     * read-only attribute is never set.
     *
     * @param attrib the attribute name, in lower case without white space
     */
    boolean testAttrib(String attrib) {
        switch (attrib) {
            case "id":
                return id != null;
            case "ident":
                return ident != null;
            case "usedefs":
                return useDefs != UNSET_FLAG;
            default:
                if (isReadOnly(attrib)) {
                    return false;
                }
                throw Ast.err(
                        AstException.AST__BADAT,
                        "astTest: The attribute name \"" + attrib + "\" is invalid for a " + astClass() + ".");
        }
    }

    /**
     * Clears an attribute, as C's per-class {@code ClearAttrib} does.
     *
     * @param attrib the attribute name, in lower case without white space
     */
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "id":
                id = null;
                break;
            case "ident":
                ident = null;
                break;
            case "usedefs":
                useDefs = UNSET_FLAG;
                break;
            default:
                if (isReadOnly(attrib)) {
                    throw Ast.err(
                            AstException.AST__NOWRT,
                            "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                                    + ". This is a read-only attribute.");
                }
                throw Ast.err(
                        AstException.AST__BADAT,
                        "astClear: The attribute name \"" + attrib + "\" is invalid for a " + astClass() + ".");
        }
    }

    // ------------------------------------------------------------------
    // Object attributes
    // ------------------------------------------------------------------

    /**
     * Gets the ID attribute: a string identifying this Object, blank unless set. A copy does not
     * inherit it.
     *
     * @return the ID
     */
    public String getID() {
        return id == null ? "" : id;
    }

    /**
     * Sets the ID attribute.
     *
     * @param id the ID
     */
    public void setID(String id) {
        this.id = id;
    }

    /**
     * Gets the Ident attribute: a permanent string identifying this Object, blank unless set. A
     * copy inherits it.
     *
     * @return the Ident
     */
    public String getIdent() {
        return ident == null ? "" : ident;
    }

    /**
     * Sets the Ident attribute.
     *
     * @param ident the Ident
     */
    public void setIdent(String ident) {
        this.ident = ident;
    }

    /** The UseDefs attribute: whether defaults may be used for unset attributes (default true). */
    boolean getUseDefs() {
        return useDefs == UNSET_FLAG || useDefs != 0;
    }

    /** Sets the UseDefs attribute. */
    void setUseDefs(boolean value) {
        useDefs = value ? 1 : 0;
    }

    /**
     * Gets the Nobject attribute. In C it counts the Objects of this class in existence; Objects
     * here are managed by the garbage collector and are not counted, so this returns 1, the value
     * for the only Object of its class.
     *
     * @return 1
     */
    public int getNobject() {
        return 1;
    }

    /**
     * Gets the ObjSize attribute. In C it is the memory in bytes used by the Object; the memory a
     * Java object uses cannot be measured, so this returns 0.
     *
     * @return 0
     */
    public int getObjSize() {
        return 0;
    }

    /**
     * Gets the RefCount attribute. In C it counts the pointers to the Object; here every reference
     * is an ordinary Java reference, so this returns 1.
     *
     * @return 1
     */
    public int getRefCount() {
        return 1;
    }
}
