package uk.ac.starlink.ast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A Channel's reader: one Object at a time from a source of text lines, as channel.c {@code
 * Read}, {@code ReadClassData}, {@code GetNextData}, {@code InputTextItem} and {@code ClearValues}
 * read it.
 *
 * <p>C builds an Object while it reads. Each class loader, from {@code Object} down to the
 * Object's own class, asks {@code ReadClassData} for its own part of the description: the items up
 * to the next {@code IsA} (or the {@code End}). Those items are a "values list" from which the
 * loader takes the ones it knows; when the next class asks, or the Object is finished, {@code
 * ClearValues} reports each item left as not recognised, a warning or, with Strict set, an error.
 * A nested Object is read, in full, when its item is met.
 *
 * <p>This reader works in two passes. The first is C's reading, line for line: it consumes exactly
 * the lines C consumes, raises the errors C raises while reading, and records each values list with
 * the number of lines read when it was cleared. The second loads the description built and then
 * reports the items no loader looked up, in the order C's {@code ClearValues} reports them: a
 * nested Object's reports when it is read, then each values list in turn, its items in the order of
 * C's hash table. With Strict set the first is an error, and the lines read after the values list
 * it was in are handed back to the source, where C stops reading.
 *
 * <p>Two things C does are not reproduced. When a read fails partway, C's loaders for the classes
 * before the failure have already taken their items, so the items they left are reported; this
 * reader, which loads only a whole description, reports only the items of values lists no loader
 * could see. And when a loader fails, C has read only up to that class's part, where this reader
 * has read the whole Object.
 *
 * <p>Ported from Rust {@code serial/native_stream.rs}.
 */
final class NativeStream {

    /** Gives the lines a Channel reads. */
    @FunctionalInterface
    interface LineSource {
        /** The next line, without its terminator, or null at the end of the text. */
        String next() throws IOException;
    }

    /** Loads a nested Object that no loader read, as C loads every nested Object it reads. */
    @FunctionalInterface
    interface Loader {
        void load(NativeNode node);
    }

    /**
     * The parent of each class C can load, whose loader each class's loader calls first ({@code
     * astLoad<Parent>} in each {@code astLoad<Class>}), or null for {@code Object} and for a class
     * C cannot load (loader.c {@code astGetLoader}). This is the parent {@code astMAKE_ISA} names,
     * except for ChebyMap, which chebymap.c declares a Mapping although its loader is PolyMap's
     * and its dumps hold an {@code IsA PolyMap}.
     */
    static String parentOf(String cls) {
        switch (cls) {
            case "Axis":
            case "Channel":
            case "KeyMap":
            case "Mapping":
            case "PointSet":
                return "Object";
            case "Box":
            case "Circle":
            case "CmpRegion":
            case "Ellipse":
            case "Interval":
            case "Moc":
            case "NullRegion":
            case "PointList":
            case "Polygon":
            case "Prism":
            case "Stc":
                return "Region";
            case "ChebyMap":
                return "PolyMap";
            case "CmpFrame":
            case "FluxFrame":
            case "FrameSet":
            case "Region":
            case "SkyFrame":
            case "SpecFrame":
            case "TimeFrame":
                return "Frame";
            case "CmpMap":
            case "DssMap":
            case "Frame":
            case "GrismMap":
            case "IntraMap":
            case "LutMap":
            case "MathMap":
            case "MatrixMap":
            case "NormMap":
            case "PcdMap":
            case "PermMap":
            case "PolyMap":
            case "RateMap":
            case "SelectorMap":
            case "ShiftMap":
            case "SlaMap":
            case "SpecMap":
            case "SphMap":
            case "SplineMap":
            case "SwitchMap":
            case "TimeMap":
            case "TranMap":
            case "UnitMap":
            case "UnitNormMap":
            case "WcsMap":
            case "WinMap":
            case "XphMap":
            case "ZoomMap":
                return "Mapping";
            case "DSBSpecFrame":
                return "SpecFrame";
            case "FitsChan":
            case "MocChan":
            case "StcsChan":
            case "XmlChan":
            case "YamlChan":
                return "Channel";
            case "FitsTable":
                return "Table";
            case "Plot":
                return "FrameSet";
            case "Plot3D":
                return "Plot";
            case "SkyAxis":
                return "Axis";
            case "SpecFluxFrame":
                return "CmpFrame";
            case "StcCatalogEntryLocation":
            case "StcObsDataLocation":
            case "StcResourceProfile":
            case "StcSearchLocation":
                return "Stc";
            case "Table":
                return "KeyMap";
            default:
                return null;
        }
    }

    /**
     * The classes whose loaders build an Object of class {@code cls}, in the order they read their
     * parts of its description ({@code Object} first), or null for a class C cannot load.
     */
    static List<String> classChain(String cls) {
        if (!"Object".equals(cls) && parentOf(cls) == null) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        for (String name = cls; name != null; name = parentOf(name)) {
            chain.add(name);
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * The bucket of C's values hash table that holds an item called {@code name} (channel.c {@code
     * HashFun}, the djb2 hash, over {@code HASHMAP_SIZE} 128 buckets).
     */
    static int bucket(String name) {
        long hash = 5381;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash << 5) + hash + (b & 0xff);
        }
        return (int) (hash & 127);
    }

    /**
     * C's {@code InputTextItem}: the line without any comment (text after the first {@code #}
     * outside quotes) or leading and trailing white space.
     */
    static String clean(String line) {
        boolean quoted = false;
        int end = line.length();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == '#' && !quoted) {
                end = i;
                break;
            }
        }
        int a = 0;
        while (a < end && CScan.isCSpace(line.charAt(a))) {
            a++;
        }
        while (end > a && CScan.isCSpace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(a, end);
    }

    /**
     * C's {@code Unquote}: removes the quotes of each quoted string, turning a doubled quote
     * within one into a single quote.
     */
    static String unquote(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean quoted = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '"') {
                out.append(c);
            } else if (!quoted) {
                quoted = true;
            } else if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                out.append('"');
                i++;
            } else {
                quoted = false;
            }
            i++;
        }
        return out.toString();
    }

    private static int skipSpace(String s, int i) {
        int j = i;
        while (j < s.length() && CScan.isCSpace(s.charAt(j))) {
            j++;
        }
        return j;
    }

    /**
     * Matches {@code " %n%*8[^ \t=]%n = %n"}: a name of one to eight characters other than a
     * space, tab or {@code =}, then {@code =}. Gives the end of the name and the start of what
     * follows the {@code =} and any white space after it, or null.
     */
    private static int[] matchNameEquals(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) != ' ' && s.charAt(i) != '\t' && s.charAt(i) != '=') {
            i++;
        }
        if (i == 0 || i > 8) {
            return null;
        }
        int nameEnd = i;
        i = skipSpace(s, i);
        if (i >= s.length() || s.charAt(i) != '=') {
            return null;
        }
        return new int[] {nameEnd, skipSpace(s, i + 1)};
    }

    /**
     * Matches {@code keyword} ({@code begin}, {@code isa} or {@code end}), ignoring case, followed
     * by one word and nothing else; gives the word, or null.
     */
    private static String matchKeyword(String line, String keyword) {
        int n = keyword.length();
        if (line.length() < n || !NativeNode.keyMatches(line.substring(0, n), keyword)) {
            return null;
        }
        int start = skipSpace(line, n);
        int end = start;
        while (end < line.length() && !CScan.isCSpace(line.charAt(end))) {
            end++;
        }
        return end > start && skipSpace(line, end) == line.length() ? line.substring(start, end) : null;
    }

    /** One data item, as C's {@code GetNextData} gives it. */
    private static final class Datum {
        /** The name, in lower case: {@code begin}, {@code isa}, {@code end} or an item's name. */
        final String name;
        /**
         * The class of a {@code begin}, {@code isa} or {@code end}, or an item's value with its
         * quotes removed; null for an item holding an Object.
         */
        final String val;
        /** The item's name as written. */
        final String key;
        /** The item's value as written, quotes included. */
        final String raw;
        /** The comment after the item. */
        final String comment;

        Datum(String name, String val, String key, String raw, String comment) {
            this.name = name;
            this.val = val;
            this.key = key;
            this.raw = raw;
            this.comment = comment;
        }
    }

    /**
     * An item of a values list: its index in the Object's description, its name in lower case,
     * and C's report of it if no loader reads it.
     */
    // MODERN-JAVA(21): record
    private static final class Value {
        final int index;
        final String name;
        final String msg;

        Value(int index, String name, String msg) {
            this.index = index;
            this.name = name;
            this.msg = msg;
        }
    }

    /**
     * What happened while an Object was read: a values list cleared (with the number of lines read
     * when it was), or an Object held by an item read.
     */
    // MODERN-JAVA(21): sealed interface with record variants
    private static final class Event {
        /** For a nested Object, the index of its item; -1 for a cleared values list. */
        final int item;
        /** For a nested Object, the events of its reading. */
        final List<Event> events;
        /** For a cleared values list, its items. */
        final List<Value> values;
        /** For a cleared values list, the number of lines read when it was cleared. */
        final int lines;

        private Event(int item, List<Event> events, List<Value> values, int lines) {
            this.item = item;
            this.events = events;
            this.values = values;
            this.lines = lines;
        }

        static Event nested(int item, List<Event> events) {
            return new Event(item, events, null, 0);
        }

        static Event clear(List<Value> values, int lines) {
            return new Event(-1, null, values, lines);
        }
    }

    /** An Object's description and what happened while it was read. */
    static final class ReadObject {
        final NativeNode node;
        private final List<Event> events;

        private ReadObject(NativeNode node, List<Event> events) {
            this.node = node;
            this.events = events;
        }
    }

    /** An error from reporting the unread items, with the number of lines C had read at it. */
    static final class Failure {
        final AstException error;
        final int lines;

        Failure(AstException error, int lines) {
            this.error = error;
            this.lines = lines;
        }
    }

    private final LineSource source;

    /** The class of the Channel, for messages. */
    private final String cls;

    /** Every line read, so that those after an error can be handed back. */
    final List<String> lines = new ArrayList<>();

    /**
     * C's {@code astreadclassdata_msg}: whether the failure of a nested Object has been reported.
     * It is shared by the nested reads of one Object, so it is a field.
     */
    @SuppressWarnings("PMD.SingularField")
    private boolean nestedMsg;

    private final boolean strict;

    private final int reportLevel;

    /** The warnings made, as C's {@code astAddWarning} stores them. */
    final List<String> warnings = new ArrayList<>();

    NativeStream(LineSource source, String cls, boolean strict, int reportLevel) {
        this.source = source;
        this.cls = cls;
        this.strict = strict;
        this.reportLevel = reportLevel;
    }

    private AstException error(int status, String message) {
        return Ast.err(status, "astRead(" + cls + "): " + message);
    }

    /**
     * C's {@code astAddWarning} at level 1: stores {@code msg} as a warning, or with Strict set
     * raises it as an error, if ReportLevel is at least 1.
     */
    private void report(String msg) {
        if (reportLevel < 1) {
            return;
        }
        if (strict) {
            throw error(AstException.AST__BADIN, msg);
        }
        warnings.add(msg);
    }

    /** Whether the item at an index of a description has not been read. */
    @FunctionalInterface
    private interface Unread {
        boolean test(int index);
    }

    /**
     * Reports each item of a values list that no loader read, in the order of C's hash table
     * ({@code ClearValues}).
     */
    private void reportUnread(List<Value> items, Unread unread) {
        List<Value> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(v -> bucket(v.name)));
        for (Value v : sorted) {
            if (unread.test(v.index)) {
                report(v.msg);
            }
        }
    }

    /**
     * Reports the items of {@code object} and the Objects in it that no loader read, in the order
     * C reports them, once the description has been loaded. {@code loader} loads a nested Object
     * that no loader read, as C loads every nested Object it reads.
     *
     * @return null, or the error with the number of lines C had read when it raised it
     */
    Failure reportObject(ReadObject object, Loader loader) {
        return reportEvents(object.node, object.events, loader);
    }

    private Failure reportEvents(NativeNode node, List<Event> events, Loader loader) {
        for (Event event : events) {
            if (event.item >= 0) {
                NativeNode child = node.items.get(event.item).value.object;
                if (!child.accessed.get()) {
                    try {
                        loader.load(child);
                    } catch (AstException e) {
                        return new Failure(e, lines.size());
                    }
                }
                Failure f = reportEvents(child, event.events, loader);
                if (f != null) {
                    return f;
                }
            } else {
                try {
                    reportUnread(event.values, i -> !node.items.get(i).read.get());
                } catch (AstException e) {
                    return new Failure(e, event.lines);
                }
            }
        }
        return null;
    }

    /**
     * C's {@code GetNextData}, adding to {@code defaults} the unset values met on the way, which C
     * ignores and this reader keeps in the description. Gives null at the end of the text.
     */
    private Datum nextDatum(boolean skip, List<NativeNode.Item> defaults) throws IOException {
        while (true) {
            String raw = source.next();
            if (raw == null) {
                return null;
            }
            lines.add(raw);
            String line = clean(raw);
            if (line.isEmpty()) {
                String t = NativeReader.trimStart(raw);
                if (t.startsWith("#")) {
                    NativeNode.Item item = NativeReader.parseCommentedDefault(t.substring(1));
                    if (item != null) {
                        defaults.add(item);
                    }
                }
                continue;
            }
            String comment = NativeReader.splitComment(raw)[1];
            if (!skip) {
                int[] m = matchNameEquals(line);
                if (m != null) {
                    String key = line.substring(0, m[0]);
                    String name = key.toLowerCase(Locale.ROOT);
                    String value = line.substring(m[1]);
                    return value.isEmpty()
                            ? new Datum(name, null, key, "", comment)
                            : new Datum(name, unquote(value), key, value, comment);
                }
            }
            String begin = matchKeyword(line, "begin");
            if (begin != null) {
                return new Datum("begin", begin, "", "", comment);
            }
            if (!skip) {
                for (String keyword : new String[] {"isa", "end"}) {
                    String c = matchKeyword(line, keyword);
                    if (c != null) {
                        return new Datum(keyword, c, "", "", comment);
                    }
                }
                String shown = line.length() > 50 ? line.substring(0, 50) + "..." : line;
                throw error(AstException.AST__BADIN, "Cannot interpret the input data: \"" + shown + "\".");
            }
        }
    }

    /**
     * C's {@code Read}: the next Object, or null at the end of the data. {@code top} is whether it
     * is a top-level Object, before which other data are skipped when {@code skip} is set.
     */
    ReadObject read(boolean top, boolean skip) throws IOException {
        Datum datum = nextDatum(top && skip, new ArrayList<>());
        if (datum == null) {
            if (top) {
                return null;
            }
            // channel.c:2597
            throw error(AstException.AST__EOCHN, "End of input encountered while trying to read an AST Object.");
        }
        if (!"begin".equals(datum.name)) {
            // channel.c:2605
            throw error(AstException.AST__BADIN, "Missing \"Begin\" when expecting an Object.");
        }
        String objClass = datum.val;
        List<String> chain = classChain(objClass);
        if (chain == null) {
            // loader.c:202, through astGetLoader at channel.c:2618.
            throw Ast.err(
                    AstException.AST__OCLUK,
                    "astGetLoader: Object of unknown class \"" + objClass + "\" cannot be loaded.");
        }
        NativeNode node = new NativeNode(objClass, datum.comment);
        try {
            return new ReadObject(node, readClasses(node, objClass, chain));
        } catch (AstException e) {
            if (top) {
                // channel.c:2672
                throw Ast.withContext(e, "Error while reading a " + objClass + " from a " + cls + ".");
            }
            throw e;
        }
    }

    /** The {@code ReadClassData} of each class in {@code chain}, then {@code Read}'s closing checks. */
    private List<Event> readClasses(NativeNode node, String objClass, List<String> chain) throws IOException {
        List<Event> events = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        String valuesClass = null;
        boolean valuesOk = false;
        boolean endOfObject = false;
        for (String want : chain) {
            if (valuesOk) {
                events.add(Event.clear(values, lines.size()));
                values = new ArrayList<>();
                valuesClass = null;
            }
            if (valuesClass != null) {
                valuesOk = valuesClass.equals(want);
            } else if (endOfObject) {
                // channel.c:2801
                throw Ast.withContext(
                        error(
                                AstException.AST__LDERR,
                                "Invalid attempt to read further " + want + " data following an end of " + objClass
                                        + "."),
                        "Perhaps the wrong class loader has been invoked?");
            } else {
                while (true) {
                    List<NativeNode.Item> defaults = new ArrayList<>();
                    Datum datum = nextDatum(false, defaults);
                    node.items.addAll(defaults);
                    if (datum == null) {
                        // channel.c:2824
                        throw error(
                                AstException.AST__EOCHN, "Unexpected end of input (missing end of " + objClass + ").");
                    }
                    if ("isa".equals(datum.name)) {
                        node.writeIsA(datum.val, datum.comment);
                        valuesOk = datum.val.equals(want);
                        valuesClass = datum.val;
                        break;
                    }
                    if ("end".equals(datum.name)) {
                        endOfObject = true;
                        if (!datum.val.equals(objClass)) {
                            // channel.c:2871
                            throw Ast.withContext(
                                    error(AstException.AST__BADIN, "Bad class structure in input data."),
                                    "End of " + datum.val + " read when expecting end of " + objClass + ".");
                        }
                        valuesOk = want.equals(datum.val);
                        valuesClass = datum.val;
                        break;
                    }
                    int index = node.items.size();
                    if (datum.val != null) {
                        String msg = "The value \"" + datum.name + " = " + datum.val
                                + "\" was not recognised as valid input.";
                        values.add(new Value(index, datum.name, msg));
                        node.items.add(new NativeNode.Item(
                                datum.key, NativeReader.parseScalarValue(datum.raw), true, datum.comment, true));
                    } else {
                        nestedMsg = false;
                        ReadObject nested;
                        try {
                            nested = read(false, false);
                        } catch (AstException e) {
                            if (nestedMsg) {
                                throw e;
                            }
                            nestedMsg = true;
                            throw Ast.withContext(e, "Failed to read the \"" + datum.name + "\" Object value.");
                        }
                        String msg = "The Object \"" + datum.name + " = <" + nested.node.className
                                + ">\" was not recognised as valid input.";
                        values.add(new Value(index, datum.name, msg));
                        events.add(Event.nested(index, nested.events));
                        node.writeObject(datum.key, true, true, nested.node, datum.comment);
                    }
                }
            }
        }
        // Read's own ClearValues, after the loader. A values list no loader could look in is
        // reported now, the read having failed.
        if (valuesClass != null && !valuesOk) {
            if (strict) {
                throw Ast.withContext(
                        error(AstException.AST__BADIN, "Invalid class structure in input data."),
                        "Class \"" + valuesClass + "\" is invalid or out of order within a " + objClass + ".");
            }
            reportUnread(values, i -> true);
        } else {
            events.add(Event.clear(values, lines.size()));
        }
        if (!endOfObject) {
            // channel.c:2663
            throw error(AstException.AST__BADIN, "Unexpected end of input (missing end of " + objClass + ").");
        }
        return events;
    }
}
