package uk.ac.starlink.ast.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import uk.ac.starlink.ast.RegisteredClasses;

/** The classes a native dump uses that the library cannot load yet. */
public final class NativeClasses {

    private NativeClasses() {}

    /**
     * The names on the {@code Begin <Class>} lines of {@code dumpText} that the library has no
     * loading constructor for, sorted and without repeats.
     */
    public static List<String> missing(String dumpText) {
        SortedSet<String> out = new TreeSet<>();
        for (String name : begun(dumpText)) {
            if (!RegisteredClasses.isRegistered(name)) {
                out.add(name);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * The reason a dump cannot be loaded for want of classes: {@code needs <A>, <B>} naming the
     * classes {@link #missing} gives, or null if there are none.
     */
    public static String needs(String dumpText) {
        List<String> m = missing(dumpText);
        return m.isEmpty() ? null : "needs " + String.join(", ", m);
    }

    /** The class names on the {@code Begin} lines of {@code dumpText}, in order. */
    static List<String> begun(String dumpText) {
        List<String> out = new ArrayList<>();
        for (String line : CValues.lines(dumpText)) {
            String[] toks = CValues.splitWhitespace(line);
            if (toks.length >= 2 && "Begin".equals(toks[0])) {
                out.add(toks[1]);
            }
        }
        return out;
    }
}
