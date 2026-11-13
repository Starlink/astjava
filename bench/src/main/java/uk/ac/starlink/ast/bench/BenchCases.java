package uk.ac.starlink.ast.bench;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import uk.ac.starlink.ast.AstObject;
import uk.ac.starlink.ast.Channel;
import uk.ac.starlink.ast.FitsChan;
import uk.ac.starlink.ast.Mapping;

/**
 * The benchmark cases: sections of the transform oracles named by the Rust port's {@code ast-bench}
 * set files, their fixtures loaded as {@code ast-bench} loads them, and the points to transform
 * chosen from the sections' rows as {@code ast-bench} chooses them.
 *
 * <p>A case is named {@code <oracle>:<relpath>:<dir>}: {@code <oracle>} is {@code bench} for the
 * oracle of {@code ast-bench}'s own fixtures or the name of a core oracle without {@code .oracle},
 * {@code <relpath>} is the fixture's path under its oracle's fixture root, and {@code <dir>} is
 * {@code forward} or {@code inverse}.
 *
 * <p>The files are read from the {@code reference} submodules of the project: the system property
 * {@code ast.reference} names that directory, and by default it is {@code reference} or {@code
 * ../reference} relative to the working directory, whichever exists.
 *
 * <p>Run as a program, it lists every section of the sets {@code framesets} and {@code bench},
 * each with its case name if the fixture loads and the reason if it does not.
 */
public final class BenchCases {

    /** The bad-value flag of the oracles' {@code BAD} token. */
    private static final double BAD = AstObject.AST__BAD;

    /**
     * The largest point count timed only on rows that are good in every input and output, so that a
     * short run does not time the early exit of bad points ({@code SMALL_N_MAX} in {@code
     * ast-bench/src/points.rs}).
     */
    static final int SMALL_N_MAX = 100;

    /** The sets whose sections are benchmarked, as named in {@code ast-bench/sets}. */
    private static final String[] SETS = {"framesets", "bench"};

    private BenchCases() {}

    /** One {@code [relpath nin= nout= dir=]} section of an oracle. */
    static final class Section {
        final String relpath;
        final int nin;
        final int nout;
        final boolean forward;
        /** {@code inputs[axis][row]}. */
        final double[][] inputs;
        /** {@code outputs[axis][row]}. */
        final double[][] outputs;

        Section(String relpath, int nin, int nout, boolean forward, double[][] inputs, double[][] outputs) {
            this.relpath = relpath;
            this.nin = nin;
            this.nout = nout;
            this.forward = forward;
            this.inputs = inputs;
            this.outputs = outputs;
        }

        int npoint() {
            return inputs.length == 0 ? 0 : inputs[0].length;
        }

        String dir() {
            return forward ? "forward" : "inverse";
        }
    }

    /** A loaded case: its section, and the object to transform. */
    static final class Case {
        final Section section;
        final Mapping mapping;

        Case(Section section, Mapping mapping) {
            this.section = section;
            this.mapping = mapping;
        }
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /** The directory holding the reference submodules. */
    static Path reference() {
        String prop = System.getProperty("ast.reference");
        if (prop != null) {
            return Paths.get(prop);
        }
        for (String candidate : new String[] {"reference", "../reference"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p.resolve("starlink-ast-rs"))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "cannot find the reference submodules; run from the project root or set -Dast.reference");
    }

    /** The directory of {@code ast-bench}'s set files. */
    static Path setsDir() {
        return reference().resolve(Paths.get("starlink-ast-rs", "crates", "ast-bench", "sets"));
    }

    /** The fixture root of oracle {@code oracle}. */
    static Path fixtureRoot(String oracle) {
        if ("bench".equals(oracle)) {
            return reference().resolve(Paths.get("starlink-ast-rs", "crates", "ast-bench", "fixtures"));
        }
        return reference().resolve(Paths.get("starlink-ast", "ast_tester", "fixtures"));
    }

    /** The oracle file of {@code oracle}. */
    static Path oracleFile(String oracle) {
        if ("bench".equals(oracle)) {
            return fixtureRoot(oracle).resolve("bench.oracle");
        }
        return fixtureRoot(oracle).resolve("oracle").resolve(oracle + ".oracle");
    }

    static String readText(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // Oracle files
    // ------------------------------------------------------------------

    /** The lines of {@code text}, without their line endings. */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int n = text.length();
        while (start < n) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = n;
            }
            int stop = end;
            if (stop > start && text.charAt(stop - 1) == '\r') {
                stop--;
            }
            out.add(text.substring(start, stop));
            start = end + 1;
        }
        return out;
    }

    /** The white-space separated tokens of {@code line}. */
    static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i > start) {
                out.add(line.substring(start, i));
            }
        }
        return out;
    }

    /** An oracle value: the {@code BAD} token is {@link AstObject#AST__BAD}, anything else a number. */
    static double parseValue(String tok) {
        if ("BAD".equals(tok)) {
            return BAD;
        }
        return Double.parseDouble(tok);
    }

    /**
     * The sections of an oracle file. A section header is {@code [<relpath> nin=N nout=M
     * dir=forward|inverse]}, and each data row holds {@code nin} inputs then {@code nout} outputs;
     * lines starting {@code #} and blank lines are skipped. A malformed row is an error.
     */
    static List<Section> parseOracle(String text) {
        List<Section> sections = new ArrayList<>();
        String relpath = null;
        int nin = 0;
        int nout = 0;
        boolean forward = true;
        List<double[]> rows = new ArrayList<>();
        for (String line : lines(text)) {
            if (line.startsWith("#") || tokens(line).isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                if (relpath != null) {
                    sections.add(section(relpath, nin, nout, forward, rows));
                }
                String inside = line.endsWith("]") ? line.substring(1, line.length() - 1) : line.substring(1);
                List<String> toks = tokens(inside);
                if (toks.isEmpty()) {
                    throw new IllegalArgumentException("empty section header");
                }
                relpath = toks.get(0);
                nin = -1;
                nout = -1;
                forward = true;
                for (String tok : toks.subList(1, toks.size())) {
                    if (tok.startsWith("nin=")) {
                        nin = Integer.parseInt(tok.substring(4));
                    } else if (tok.startsWith("nout=")) {
                        nout = Integer.parseInt(tok.substring(5));
                    } else if (tok.startsWith("dir=")) {
                        forward = !"inverse".equals(tok.substring(4));
                    }
                }
                if (nin < 0 || nout < 0) {
                    throw new IllegalArgumentException("bad section header: " + line);
                }
                rows = new ArrayList<>();
                continue;
            }
            if (relpath == null) {
                throw new IllegalArgumentException("data row before any section header");
            }
            List<String> toks = tokens(line);
            if (toks.size() != nin + nout) {
                throw new IllegalArgumentException("malformed data row in [" + relpath + "]: got " + toks.size()
                        + " of " + (nin + nout) + " columns");
            }
            double[] row = new double[toks.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = parseValue(toks.get(i));
            }
            rows.add(row);
        }
        if (relpath != null) {
            sections.add(section(relpath, nin, nout, forward, rows));
        }
        return sections;
    }

    private static Section section(String relpath, int nin, int nout, boolean forward, List<double[]> rows) {
        double[][] inputs = new double[nin][rows.size()];
        double[][] outputs = new double[nout][rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            double[] row = rows.get(r);
            for (int a = 0; a < nin; a++) {
                inputs[a][r] = row[a];
            }
            for (int a = 0; a < nout; a++) {
                outputs[a][r] = row[nin + a];
            }
        }
        return new Section(relpath, nin, nout, forward, inputs, outputs);
    }

    // ------------------------------------------------------------------
    // Sets and cases
    // ------------------------------------------------------------------

    /**
     * The case names of a set file, in its order: lines {@code <oracle> <relpath> # reason}, each
     * giving every section its oracle has for the relpath.
     */
    static List<String> setCases(String set) {
        Map<String, List<Section>> oracles = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (String raw : lines(readText(setsDir().resolve(set + ".txt")))) {
            int hash = raw.indexOf('#');
            List<String> toks = tokens(hash >= 0 ? raw.substring(0, hash) : raw);
            if (toks.isEmpty()) {
                continue;
            }
            if (toks.size() < 2) {
                throw new IllegalArgumentException(set + ": bad line " + raw);
            }
            String oracle = toks.get(0);
            String relpath = toks.get(1);
            List<Section> sections = oracles.get(oracle);
            if (sections == null) {
                sections = parseOracle(readText(oracleFile(oracle)));
                oracles.put(oracle, sections);
            }
            boolean found = false;
            for (Section s : sections) {
                if (s.relpath.equals(relpath)) {
                    out.add(oracle + ":" + relpath + ":" + s.dir());
                    found = true;
                }
            }
            if (!found) {
                throw new IllegalArgumentException(set + ": " + relpath + " is not in the " + oracle + " oracle");
            }
        }
        return out;
    }

    /** The section a case name names. */
    static Section section(String name) {
        String[] parts = splitName(name);
        for (Section s : parseOracle(readText(oracleFile(parts[0])))) {
            if (s.relpath.equals(parts[1]) && s.dir().equals(parts[2])) {
                return s;
            }
        }
        throw new IllegalArgumentException("no section " + name);
    }

    private static String[] splitName(String name) {
        int first = name.indexOf(':');
        int last = name.lastIndexOf(':');
        if (first < 0 || last == first) {
            throw new IllegalArgumentException("a case name is <oracle>:<relpath>:<dir>, not " + name);
        }
        return new String[] {name.substring(0, first), name.substring(first + 1, last), name.substring(last + 1)};
    }

    /** The case a case name names, with its fixture loaded. */
    static Case load(String name) {
        Section s = section(name);
        return new Case(s, loadFixture(splitName(name)[0], s.relpath));
    }

    /**
     * Loads a fixture as {@code ast-bench} does ({@code ast-bench/src/load.rs}): a {@code .head} is
     * read through a FitsChan, each line stored as a card and the first card made current, as C's
     * {@code oracle_load_mapping} does; anything else is read through a Channel. The object read
     * (a FrameSet whole) is the Mapping timed.
     */
    static Mapping loadFixture(String oracle, String relpath) {
        String text = readText(fixtureRoot(oracle).resolve(relpath));
        AstObject obj;
        if (relpath.endsWith(".head")) {
            FitsChan fc = new FitsChan();
            for (String line : lines(text)) {
                fc.putFits(line, false);
            }
            fc.clear("Card");
            obj = fc.read();
        } else {
            try {
                obj = new Channel(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), null).read();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (obj == null) {
            throw new IllegalArgumentException("no object read from " + relpath);
        }
        if (!(obj instanceof Mapping)) {
            throw new IllegalArgumentException(
                    relpath + " holds a " + obj.getClass().getSimpleName() + ", not a Mapping");
        }
        return (Mapping) obj;
    }

    // ------------------------------------------------------------------
    // Points
    // ------------------------------------------------------------------

    /**
     * The rows of an {@code n}-point run, repeating the candidate rows in order ({@code select_rows}
     * in {@code ast-bench/src/points.rs}). Up to {@link #SMALL_N_MAX} points the candidates are the
     * rows good in every input and output; above it, every row.
     */
    static int[] selectRows(Section s, int n) {
        List<Integer> candidates = new ArrayList<>();
        for (int r = 0; r < s.npoint(); r++) {
            boolean good = true;
            if (n <= SMALL_N_MAX) {
                for (double[] axis : s.inputs) {
                    good &= axis[r] != BAD;
                }
                for (double[] axis : s.outputs) {
                    good &= axis[r] != BAD;
                }
            }
            if (good) {
                candidates.add(r);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(s.relpath + " dir=" + s.dir() + ": no non-BAD row to time at N=" + n);
        }
        int[] rows = new int[n];
        for (int i = 0; i < n; i++) {
            rows[i] = candidates.get(i % candidates.size());
        }
        return rows;
    }

    /** The selected rows' inputs, one array per axis ({@code rows_to_columns}). */
    static double[][] columns(Section s, int[] rows) {
        double[][] out = new double[s.nin][rows.length];
        for (int a = 0; a < s.nin; a++) {
            for (int i = 0; i < rows.length; i++) {
                out[a][i] = s.inputs[a][rows[i]];
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    /**
     * Prints every section of the benchmarked sets: the case name of each whose fixture loads, as
     * {@code TransformBench}'s {@code fixture} parameter takes it, and, after them, each that does
     * not with the reason.
     *
     * @param args not used
     */
    public static void main(String[] args) {
        List<String> loads = new ArrayList<>();
        List<String> fails = new ArrayList<>();
        for (String set : SETS) {
            for (String name : setCases(set)) {
                try {
                    load(name);
                    loads.add(name);
                } catch (RuntimeException e) {
                    String msg = String.valueOf(e.getMessage());
                    int nl = msg.indexOf('\n');
                    fails.add(name + " | " + e.getClass().getSimpleName() + ": "
                            + (nl >= 0 ? msg.substring(0, nl) : msg));
                }
            }
        }
        Collections.sort(fails);
        System.out.println("# Cases whose fixtures load (" + loads.size() + ")");
        for (String name : loads) {
            System.out.println(name);
        }
        System.out.println("# Cases whose fixtures do not load (" + fails.size() + ")");
        for (String line : fails) {
            System.out.println(line);
        }
    }
}
