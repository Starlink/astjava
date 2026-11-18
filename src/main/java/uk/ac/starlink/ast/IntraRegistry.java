package uk.ac.starlink.ast;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tables of transformation functions by name, C's table of functions registered with {@code
 * astIntraReg} (intramap.c {@code tran_data}, filled by {@code IntraReg}), from which an IntraMap
 * read takes its Transformer.
 *
 * <p>There are two. A registry object holds the functions registered with one Channel ({@link
 * Channel#registerIntra}), which the Channel lends to the IntraMap loader through a thread-local
 * slot only while it reads ({@link #enter} and {@link #restore}), since the loader of an IntraMap
 * nested in another Object sees only its own description. The process-wide table, like C's, holds
 * the function of every IntraMap made in this process, as jniast's {@code construct} registers it,
 * but holds each Transformer weakly, so that an entry lapses when no IntraMap uses its
 * Transformer. A read looks in the Channel's registry first and then in the process-wide table.
 */
final class IntraRegistry {

    /** C's {@code AST__ANY}: a function that accepts or produces any number of coordinates. */
    static final int AST__ANY = -66;

    /** C's {@code AST__NOFWD}: the function has no forward transformation. */
    static final int NOFWD = 1;

    /** C's {@code AST__NOINV}: the function has no inverse transformation. */
    static final int NOINV = 2;

    /** C's {@code AST__SIMPFI}: forward then inverse may be simplified away. */
    static final int SIMPFI = 4;

    /** C's {@code AST__SIMPIF}: inverse then forward may be simplified away. */
    static final int SIMPIF = 8;

    /** The registry of the Channel reading on this thread, while it reads. */
    private static final ThreadLocal<IntraRegistry> READING = new ThreadLocal<>();

    /**
     * The process-wide table of the functions of the IntraMaps made in this process, by name: C's
     * {@code tran_data} (intramap.c), which {@code astIntraReg} fills for the whole process.
     */
    // Mutable static state, as intramap.c keeps its table of registered functions (tran_data) in
    // static memory for the whole process. PMD's MutableStaticState rule does not flag a final
    // field, so it needs no suppression.
    private static final ConcurrentMap<String, Made> MADE = new ConcurrentHashMap<>();

    /** The references of {@link #MADE} whose Transformers have been collected. */
    private static final ReferenceQueue<Transformer> CLEARED = new ReferenceQueue<>();

    /**
     * An entry of the process-wide table: a registered function with its Transformer held weakly.
     */
    private static final class Made extends WeakReference<Transformer> {
        final String name;
        final int nin;
        final int nout;
        final int flags;
        final String purpose;
        final String author;
        final String contact;

        Made(Entry e) {
            super(e.trans, CLEARED);
            name = e.name;
            nin = e.nin;
            nout = e.nout;
            flags = e.flags;
            purpose = e.purpose;
            author = e.author;
            contact = e.contact;
        }

        /** The entry, with the Transformer {@code held}. */
        Entry entry(Transformer held) {
            return new Entry(name, nin, nout, held, flags, purpose, author, contact);
        }
    }

    /** A registered function, as C's {@code AstIntraMapTranData} holds it. */
    static final class Entry {
        final String name;
        final int nin;
        final int nout;
        final Transformer trans;
        final int flags;
        final String purpose;
        final String author;
        final String contact;

        /**
         * The entry for {@code trans}, its flags and strings read from it in the order jniast's
         * {@code construct} reads them.
         */
        Entry(String name, int nin, int nout, Transformer trans) {
            this.name = name;
            this.nin = nin;
            this.nout = nout;
            this.trans = trans;
            this.flags = flagsOf(trans);
            this.purpose = text(trans.getPurpose());
            this.contact = text(trans.getContact());
            this.author = text(trans.getAuthor());
        }

        Entry(
                String name,
                int nin,
                int nout,
                Transformer trans,
                int flags,
                String purpose,
                String author,
                String contact) {
            this.name = name;
            this.nin = nin;
            this.nout = nout;
            this.trans = trans;
            this.flags = flags;
            this.purpose = purpose;
            this.author = author;
            this.contact = contact;
        }

        /** This entry under the name {@code newName}. */
        Entry renamed(String newName) {
            return new Entry(newName, nin, nout, trans, flags, purpose, author, contact);
        }

        /** Whether this entry registers {@code t}. */
        // The same Transformer, not an equal one.
        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
        boolean sameTransformer(Transformer t) {
            return trans == t;
        }

        /** Whether this entry and {@code o} register the same function, as C's IntraReg compares them. */
        // C compares the function pointers: the same Transformer, not an equal one.
        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
        boolean sameAs(Entry o) {
            return nin == o.nin
                    && nout == o.nout
                    && trans == o.trans
                    && flags == o.flags
                    && purpose.equals(o.purpose)
                    && author.equals(o.author)
                    && contact.equals(o.contact);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** A copy of this registry, holding the same registrations. */
    IntraRegistry copy() {
        IntraRegistry out = new IntraRegistry();
        out.entries.addAll(entries);
        return out;
    }

    /**
     * The flags C's {@code astIntraReg} is given for {@code trans}, as jniast makes them from the
     * Transformer (jni/IntraMap.c {@code construct}).
     */
    static int flagsOf(Transformer trans) {
        int flags = 0;
        if (!trans.hasForward()) {
            flags |= NOFWD;
        }
        if (!trans.hasInverse()) {
            flags |= NOINV;
        }
        if (trans.simpFI()) {
            flags |= SIMPFI;
        }
        if (trans.simpIF()) {
            flags |= SIMPIF;
        }
        return flags;
    }

    /** A description string of a Transformer, empty when it gives null, as jniast passes it. */
    static String text(String s) {
        return s == null ? "" : s;
    }

    /**
     * {@code name} with all white space removed, as intramap.c {@code CleanName} makes it.
     *
     * @throws AstException with status {@code AST__ITFNI} if the name is entirely blank
     */
    static String cleanName(String name, String caller) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!CScan.isCSpace(c)) {
                sb.append(c);
            }
        }
        if (sb.length() == 0) {
            throw Ast.err(AstException.AST__ITFNI, caller + ": Invalid blank transformation function name given.");
        }
        return sb.toString();
    }

    /**
     * Registers {@code trans} under {@code name}, as intramap.c {@code IntraReg} does. White space
     * in the name is removed; the name is case-sensitive. Registering the same function again with
     * the same details has no effect.
     *
     * @param nin the number of input coordinates, or {@link #AST__ANY}
     * @param nout the number of output coordinates, or {@link #AST__ANY}
     * @throws AstException with status {@code AST__ITFNI} if the name is blank, {@code AST__BADNI}
     *     or {@code AST__BADNO} if a number of coordinates is negative and not {@link #AST__ANY},
     *     or {@code AST__MRITF} if the name is registered with different details
     */
    void register(String name, int nin, int nout, Transformer trans) {
        Objects.requireNonNull(trans, "trans");
        String clname = cleanName(name, "astIntraReg");
        if (nin < 0 && nin != AST__ANY) {
            throw Ast.err(
                    AstException.AST__BADNI,
                    "astIntraReg(" + clname + "): Bad number of input coordinates (" + nin
                            + ").\nThis number should be zero or more (or AST__ANY).");
        } else if (nout < 0 && nout != AST__ANY) {
            throw Ast.err(
                    AstException.AST__BADNO,
                    "astIntraReg(" + clname + "): Bad number of output coordinates (" + nout
                            + ").\nThis number should be zero or more (or AST__ANY).");
        }
        Entry entry = new Entry(clname, nin, nout, trans);
        Entry found = find(clname);
        if (found == null) {
            entries.add(entry);
        } else if (!found.sameAs(entry)) {
            throw Ast.err(
                    AstException.AST__MRITF,
                    "astIntraReg: Invalid attempt to register the transformation function name \"" + clname
                            + "\" multiple times.");
        }
    }

    /** The function registered under exactly {@code name}, or null. */
    Entry find(String name) {
        for (Entry e : entries) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Makes {@code registry} the one the IntraMap loader consults on this thread, as a Channel
     * does for the duration of a read.
     *
     * @param registry the registry, or null for none
     * @return the registry it replaces, to be given to {@link #restore}
     */
    static IntraRegistry enter(IntraRegistry registry) {
        IntraRegistry previous = READING.get();
        READING.set(registry);
        return previous;
    }

    /** Restores the registry {@link #enter} replaced. */
    static void restore(IntraRegistry previous) {
        if (previous == null) {
            READING.remove();
        } else {
            READING.set(previous);
        }
    }

    /**
     * Registers {@code trans}, the Transformer of an IntraMap being made, in the process-wide table,
     * as jniast's {@code construct} registers it with {@code astIntraReg}, under {@code name} or,
     * if that is held by another Transformer still in use, under the first of {@code name#1},
     * {@code name#2}, ... that is not. A name is taken if it is free, held by a Transformer no
     * longer in use, or held by {@code trans}; held by {@code trans} with other details (numbers
     * of coordinates, flags or strings), it is AST__MRITF, as C's {@code IntraReg} reports a
     * function registered again with different details.
     *
     * <p>The names come from identity hash codes, which two Transformers in use may share; C's
     * names are chosen by its callers, and its {@code IntraReg} refuses a name held by another
     * function. A new name is chosen here instead, so that making an IntraMap does not fail
     * because of a shared hash code.
     *
     * @return the entry for {@code trans}, with the name it is registered under
     * @throws AstException with status {@code AST__MRITF} if {@code trans} is registered under the
     *     name with different details
     */
    static Entry registerMade(String name, int nin, int nout, Transformer trans) {
        purge();
        Entry first = new Entry(name, nin, nout, trans);
        for (int k = 0; ; k++) {
            Entry entry = k == 0 ? first : first.renamed(name + "#" + k);
            boolean[] other = new boolean[1];
            MADE.compute(entry.name, (key, old) -> {
                Transformer held = old == null ? null : old.get();
                if (held == null) {
                    return new Made(entry);
                }
                if (!old.entry(held).sameTransformer(trans)) {
                    other[0] = true;
                    return old;
                }
                if (!old.entry(held).sameAs(entry)) {
                    throw Ast.err(
                            AstException.AST__MRITF,
                            "astIntraReg: Invalid attempt to register the transformation function name \"" + entry.name
                                    + "\" multiple times.");
                }
                return old;
            });
            if (!other[0]) {
                return entry;
            }
        }
    }

    /** Removes the entries of the process-wide table whose Transformers have been collected. */
    private static void purge() {
        for (Reference<? extends Transformer> r = CLEARED.poll(); r != null; r = CLEARED.poll()) {
            Made m = (Made) r;
            MADE.remove(m.name, m);
        }
    }

    /**
     * The function registered under {@code name} with the Channel reading on this thread, or else
     * in the process-wide table by an IntraMap still in use, or null.
     */
    static Entry lookup(String name) {
        IntraRegistry reg = READING.get();
        Entry e = reg == null ? null : reg.find(name);
        if (e != null) {
            return e;
        }
        Made m = MADE.get(name);
        Transformer held = m == null ? null : m.get();
        return held == null ? null : m.entry(held);
    }
}
