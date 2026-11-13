package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Deferred;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.NativeClasses;
import uk.ac.starlink.ast.testsupport.OracleFile;
import uk.ac.starlink.ast.testsupport.OracleSection;

/**
 * Translation of Rust {@code tests/test_transform_oracle.rs}, a port of C's {@code
 * check_transform_oracle} ({@code ast_tester/check_transform_oracle.c}): each fixture named by the
 * C transform oracles ({@code fixtures/oracle/{simplify_fixtures,headers,framesets}.oracle}) is
 * loaded, the recorded inputs are transformed again, and the outputs are compared with C's, with
 * the tolerances the oracle header states and the per-fixture overrides of {@code
 * fixtures/oracle/transform_oracle_overrides.txt}. On an angular output axis a difference of a
 * whole turn is accepted. An inverse section of a FITS header or a FrameSet dump is also checked to
 * recover the inputs of the forward section before it, and the forward section of a {@code .simp}
 * fixture (C's simplification of the {@code .map} of the same stem) is checked to agree with the
 * {@code .map}'s where C's recorded values agree; simplification leaves transform values alone, so
 * this holds although {@link Mapping#simplify} here returns the Mapping unchanged.
 *
 * <p>The three gates, {@code head_sections_match_oracle}, {@code slamap_sections_match_oracle}
 * and {@code simplify_sections_match_oracle}, divide the sections as Rust's do. Every section
 * either matches C or belongs to a fixture in the deferred list of its oracle ({@code
 * deferred/{simplify,headers,framesets}.txt}), and a deferred fixture must fail. With {@code
 * -Dast.writeDeferred=true} each gate rewrites those lists instead; see {@link Deferred}. Here a
 * Frame is a Mapping, as in C, so Rust's list of untransformable fixtures has no counterpart: a
 * bare Frame's sections are compared like any other.
 *
 * <p>{@code bare_frame_is_untransformable_and_says_why} becomes {@code
 * bareFrameTransformsAsTheIdentity}, a check that a bare Frame transforms as the identity, as in
 * C.
 *
 * <p>A {@code .head} fixture is read as C's {@code oracle_load_mapping} ({@code
 * ast_tester/transform_oracle_util.c}) reads it: each line is stored with {@code putFits}, the
 * first card is made current, and the FitsChan is read; the sections are then transformed by the
 * FrameSet's base-to-current Mapping, and its base and current Frames say which axes are angular.
 * Why a {@code .head} fixture cannot be read is measured, not listed by hand: when the read throws
 * {@code UnsupportedOperationException}, the reason is the text of its message from {@code needs}
 * on (such as {@code needs SpecFrame}), or {@code encoding <name>} for an encoding whose read is not
 * available; a failed read of the NATIVE encoding needs the classes its {@code BEGAST} cards name
 * that are not ported. Any other read that gives no FrameSet, or throws {@code AstException}, is a
 * failure the gate reports unless a measured cause is written for it by hand.
 *
 * <p>{@code loads_head_frameset_with_frames} and {@code skyframe_longitude_is_cyclic_pixel_is_not}
 * read {@code wcsconv/inputs/galactic-tan.head} rather than Rust's {@code cobe.head}, which is
 * guessed to be FITS-AIPS, an encoding whose read is out of scope.
 *
 * <p>Single rows may be excused by {@code deferred/oracle-rows.txt}, one line per section, {@code
 * <fixture> <forward|inverse> <rows> | <measured cause>}, for rows that differ from C only because
 * C's oracle was computed with a multiply and an add fused (BAD on one side and finite on the
 * other, beside a singularity). A listed row that matches C, or an entry naming no section or a
 * row beyond the section, fails the gate.
 */
class TransformOracleTest {

    /** The oracle files, by their name without {@code .oracle}. */
    private static final List<String> ORACLES = Arrays.asList("simplify_fixtures", "headers", "framesets");

    private static final double TWO_PI = Math.PI * 2.0;

    /**
     * Rows where C's own recorded inverse does not recover the recorded forward input, keyed by
     * fixture; {@link #compareRtrip} excuses exactly these, and only after the live result has
     * failed both the direct and the aliased comparison. {@code
     * referenceRtripGapsAreExactlyTheRecordedSet} checks this against the oracle files alone.
     *
     * <p>tnx-cheb has no inverse: its TNX Chebyshev distortion builds a ChebyMap, which cannot
     * represent its own Jacobian, so sky to pixel is BAD for every row (both relpaths name the same
     * object). tsc row 10 is a 360-degree alias: the image is wider than the quad-cube period.
     * total_hi row 3 is an all-sky CAR header whose corner pixel does not recover.
     */
    private static final Map<String, List<Integer>> RTRIP_REFERENCE_GAPS_LICENSED = rtripGaps();

    private static Map<String, List<Integer>> rtripGaps() {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            all.add(i);
        }
        Map<String, List<Integer>> m = new TreeMap<>();
        m.put("wcsconv/inputs/tnx-cheb.head", all);
        m.put("wcsconv/framesets/tnx-cheb-head.ast", all);
        m.put("wcsconv/inputs/tsc.head", Collections.singletonList(10));
        m.put("wcsconv/framesets/tsc-head.ast", Collections.singletonList(10));
        m.put("programs/joye_car_headers/total_hi.head", Collections.singletonList(3));
        return Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------
    // Comparison primitives
    // ------------------------------------------------------------------

    /**
     * C's {@code oracle_within_tol}: {@code |got-ref| <= atol + rtol*|ref|}. BAD matches only BAD;
     * NaN never matches.
     */
    static boolean withinTol(double got, double reference, double rtol, double atol) {
        boolean gotBad = got == CValues.BAD;
        boolean refBad = reference == CValues.BAD;
        if (gotBad || refBad) {
            return gotBad && refBad;
        }
        if (Double.isNaN(got) || Double.isNaN(reference)) {
            return false;
        }
        return Math.abs(got - reference) <= atol + rtol * Math.abs(reference);
    }

    /**
     * C's {@code oracle_within_tol_wrap}: as {@link #withinTol}, but also accepting a difference of
     * one whole turn, as a longitude may come out as 0 or 2&pi;, or as &pi; or -&pi;.
     */
    static boolean withinTolWrap(double got, double reference, double rtol, double atol) {
        if (withinTol(got, reference, rtol, atol)) {
            return true;
        }
        if (got == CValues.BAD || reference == CValues.BAD || Double.isNaN(got) || Double.isNaN(reference)) {
            return false;
        }
        return Math.abs(Math.abs(got - reference) - TWO_PI) <= atol + rtol * Math.abs(reference);
    }

    /** C's {@code axis_match}: wrap-aware on angular axes, plain otherwise. */
    static boolean axisMatch(double got, double reference, double rtol, double atol, boolean angular) {
        return angular ? withinTolWrap(got, reference, rtol, atol) : withinTol(got, reference, rtol, atol);
    }

    private static double relDiff(double got, double reference) {
        double d = Math.abs(got - reference);
        return reference != 0.0 ? d / Math.abs(reference) : d;
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A fixture loaded as a Mapping, with the base and current Frames of the FrameSet it came from
     * (both null for a bare Mapping), or why it could not be.
     */
    static final class Loaded {
        final Mapping map;
        final Frame base;
        final Frame current;
        final String failure;

        Loaded(Mapping map, String failure) {
            this(map, null, null, failure);
        }

        Loaded(Mapping map, Frame base, Frame current, String failure) {
            this.map = map;
            this.base = base;
            this.current = current;
            this.failure = failure;
        }
    }

    /**
     * The fixture an oracle relpath names, as C's {@code oracle_load_mapping} loads it: a {@code
     * .head} read through a FitsChan, giving its FrameSet's base-to-current Mapping; anything else
     * read through a Channel, a FrameSet being used whole. The relpath names exactly one file under
     * the fixture root.
     */
    static Loaded loadFixture(String relpath) {
        Path path = resolveFixture(relpath);
        if (path == null) {
            return new Loaded(null, "fixture not found: " + relpath);
        }
        if (relpath.endsWith(".head")) {
            HeadRead h = readHead(relpath);
            if (h.frameSet == null) {
                return new Loaded(null, h.failure);
            }
            FrameSet fs = h.frameSet;
            return new Loaded(
                    fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT),
                    fs.getFrame(FrameSet.AST__BASE),
                    fs.getFrame(FrameSet.AST__CURRENT),
                    null);
        }
        AstObject obj;
        try {
            obj = ChannelText.read(Fixtures.read(relpath));
        } catch (AstException e) {
            return new Loaded(null, "read " + relpath + ": " + e.getMessage());
        }
        if (obj instanceof FrameSet) {
            FrameSet fs = (FrameSet) obj;
            return new Loaded(fs, fs.getFrame(FrameSet.AST__BASE), fs.getFrame(FrameSet.AST__CURRENT), null);
        }
        if (!(obj instanceof Mapping)) {
            return new Loaded(null, "not a Mapping: " + obj.astClass());
        }
        return new Loaded((Mapping) obj, null);
    }

    /**
     * What reading a {@code .head} fixture gave: a FrameSet, or why there is none and, when the
     * read stopped at something not available, the measured reason.
     */
    static final class HeadRead {
        final FrameSet frameSet;
        final String needs;
        final String failure;

        HeadRead(FrameSet frameSet, String needs, String failure) {
            this.frameSet = frameSet;
            this.needs = needs;
            this.failure = failure;
        }
    }

    /**
     * Reads a {@code .head} fixture as {@code oracle_load_mapping} does: each line, without its
     * line ending, is stored with {@code astPutFits} without overwriting, the first card is made
     * current, and the FitsChan is read. The measured reason is {@link Deferred#unavailableReason}
     * of an {@code UnsupportedOperationException} the read throws, or, when a read of the NATIVE
     * encoding fails, the classes its {@code BEGAST} cards name that are not ported. Every line of the fixtures is shorter than the 256-byte
     * buffer C reads them with, so each is one card.
     */
    static HeadRead readHead(String relpath) {
        FitsChan fc = new FitsChan();
        AstObject obj;
        try {
            for (String line : CValues.lines(Fixtures.read(relpath))) {
                int n = line.length();
                while (n > 0 && (line.charAt(n - 1) == '\n' || line.charAt(n - 1) == '\r')) {
                    n--;
                }
                fc.putFits(line.substring(0, n), false);
            }
            fc.clear("Card");
            obj = fc.read();
        } catch (UnsupportedOperationException e) {
            return new HeadRead(
                    null, Deferred.unavailableReason(e.getMessage()), "read " + relpath + ": " + e.getMessage());
        } catch (AstException e) {
            // A header of the NATIVE encoding fails to read when it holds a class the library has
            // no loading constructor for.
            String needs =
                    "NATIVE".equals(fc.getEncoding()) ? NativeClasses.needsInFitsHeader(Fixtures.read(relpath)) : null;
            return new HeadRead(null, needs, "read " + relpath + ": " + e.getMessage());
        }
        if (obj == null) {
            return new HeadRead(null, null, "FitsChan read returned null: " + relpath);
        }
        if (!(obj instanceof FrameSet)) {
            return new HeadRead(null, null, "FitsChan read gave a " + obj.astClass() + ", not a FrameSet: " + relpath);
        }
        return new HeadRead((FrameSet) obj, null, null);
    }

    /** The file a relpath names, or null if there is none. */
    static Path resolveFixture(String relpath) {
        Path p = Fixtures.root().resolve(relpath);
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * The reason a fixture cannot be loaded for want of classes, or null if it uses none: for a
     * {@code .head} the reason its read measures (see {@link #readHead}), for a native dump the
     * classes it names that are not ported.
     */
    static String needs(String relpath) {
        if (relpath.endsWith(".head")) {
            return readHead(relpath).needs;
        }
        return NativeClasses.needs(Fixtures.read(relpath));
    }

    /**
     * Which output axes of a section are angular: the cyclic axes of the Frame the section's output
     * is in, the current Frame for a forward section and the base Frame for an inverse one. A bare
     * Mapping has no Frame to ask, so every axis is, as in C.
     */
    static boolean[] angularFlags(Loaded loaded, OracleSection s) {
        return angularFlags(s.forward ? loaded.current : loaded.base, s.nout);
    }

    /** {@link #angularFlags(Loaded, OracleSection)} for a Mapping loaded from a native dump. */
    static boolean[] angularFlags(Mapping map, OracleSection s) {
        if (map instanceof FrameSet) {
            FrameSet fs = (FrameSet) map;
            return angularFlags(fs.getFrame(s.forward ? FrameSet.AST__CURRENT : FrameSet.AST__BASE), s.nout);
        }
        return angularFlags((Frame) null, s.nout);
    }

    private static boolean[] angularFlags(Frame frame, int nout) {
        boolean[] flags = new boolean[nout];
        if (frame == null) {
            Arrays.fill(flags, true);
            return flags;
        }
        for (int axis = 0; axis < nout; axis++) {
            flags[axis] = axisIsCyclic(frame, axis);
        }
        return flags;
    }

    /**
     * Whether an axis of a Frame, numbered from 0, is cyclic: whether the Frame normalises a value
     * and the value one turn on from it to the same value.
     */
    static boolean axisIsCyclic(Frame frame, int axis) {
        int naxes = frame.getNaxes();
        if (axis >= naxes) {
            return false;
        }
        double[] p = new double[naxes];
        double[] q = new double[naxes];
        Arrays.fill(p, 0.1);
        Arrays.fill(q, 0.1);
        q[axis] += TWO_PI;
        try {
            frame.norm(p);
            frame.norm(q);
        } catch (AstException e) {
            return false;
        }
        return Math.abs(p[axis] - q[axis]) < 1e-6;
    }

    /** A transform that could not be made: a changed arity or an error. */
    static final class TransformFailure extends Exception {
        private static final long serialVersionUID = 1L;

        TransformFailure(String message) {
            super(message);
        }

        TransformFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The recorded inputs {@code inputs} of section {@code s} transformed by {@code map}. */
    static double[][] transformSection(Mapping map, OracleSection s, double[][] inputs) throws TransformFailure {
        int needNin = s.forward ? s.nin : s.nout;
        int needNout = s.forward ? s.nout : s.nin;
        if (map.getNin() != needNin || map.getNout() != needNout) {
            throw new TransformFailure("arity: fixture nin/nout " + needNin + "/" + needNout + " != mapping "
                    + map.getNin() + "/" + map.getNout());
        }
        try {
            return map.tranP(inputs[0].length, s.nin, inputs, s.forward, s.nout);
        } catch (AstException e) {
            throw new TransformFailure("transform: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Overrides
    // ------------------------------------------------------------------

    /** A round-trip override: off, or tolerances (null for the oracle's default). */
    static final class RtripRule {
        final boolean off;
        final Double rtol;
        final Double atol;

        RtripRule(boolean off, Double rtol, Double atol) {
            this.off = off;
            this.rtol = rtol;
            this.atol = atol;
        }
    }

    /** A golden override: directions switched off and rows left uncompared. */
    static final class GoldenRule {
        boolean fwdOff;
        boolean invOff;
        long fwdRows;
        long invRows;
    }

    /** The parsed {@code transform_oracle_overrides.txt}; round-trip and golden rules are separate. */
    static final class Overrides {
        final Map<String, RtripRule> rtripRules = new LinkedHashMap<>();
        final Map<String, GoldenRule> goldenRules = new LinkedHashMap<>();

        /** The round-trip tolerances {@code {rtol, atol}}, or null if the check is off. */
        double[] rtrip(String relpath, double defRtol, double defAtol) {
            RtripRule r = rtripRules.get(relpath);
            if (r == null) {
                return new double[] {defRtol, defAtol};
            }
            if (r.off) {
                return null;
            }
            return new double[] {r.rtol == null ? defRtol : r.rtol, r.atol == null ? defAtol : r.atol};
        }

        boolean goldenOff(String relpath, boolean forward) {
            GoldenRule g = goldenRules.get(relpath);
            return g != null && (forward ? g.fwdOff : g.invOff);
        }

        long goldenSkipRows(String relpath, boolean forward) {
            GoldenRule g = goldenRules.get(relpath);
            if (g == null) {
                return 0L;
            }
            return forward ? g.fwdRows : g.invRows;
        }
    }

    /**
     * A comma-separated list of 0-based row indices as a bitmap. Rows at or beyond 64 are ignored;
     * a token that is not a number ends the list.
     */
    static long parseRowList(String list) {
        long rows = 0L;
        int i = 0;
        while (i < list.length()) {
            int j = i;
            while (j < list.length() && list.charAt(j) != ',' && list.charAt(j) != ' ') {
                j++;
            }
            String tok = list.substring(i, j).trim();
            i = j + 1;
            if (tok.isEmpty()) {
                continue;
            }
            long v;
            try {
                v = Long.parseLong(tok);
            } catch (NumberFormatException e) {
                break;
            }
            if (v < 0) {
                break;
            }
            if (v < 64) {
                rows |= 1L << v;
            }
        }
        return rows;
    }

    private static Double parseOrNull(String s) {
        if (s == null) {
            return null;
        }
        return CValues.parseRustFloat(s);
    }

    static Overrides parseOverrides(String text) {
        Overrides o = new Overrides();
        for (String line : CValues.lines(text)) {
            if (line.startsWith("#") || CValues.isBlank(line)) {
                continue;
            }
            String[] toks = CValues.splitWhitespace(line);
            if (toks.length < 2) {
                continue;
            }
            String rel = toks[0];
            String what = toks[1];
            String third = toks.length > 2 ? toks[2] : null;
            if (what.startsWith("golden")) {
                GoldenRule entry = o.goldenRules.computeIfAbsent(rel, k -> new GoldenRule());
                String kind = what.substring("golden".length());
                if ("-fwd-rows".equals(kind)) {
                    entry.fwdRows |= parseRowList(third == null ? "" : third);
                } else if ("-inv-rows".equals(kind)) {
                    entry.invRows |= parseRowList(third == null ? "" : third);
                } else {
                    entry.fwdOff |= kind.isEmpty() || "-fwd".equals(kind);
                    entry.invOff |= kind.isEmpty() || "-inv".equals(kind);
                }
            } else if ("off".equals(what)) {
                o.rtripRules.put(rel, new RtripRule(true, null, null));
            } else {
                o.rtripRules.put(rel, new RtripRule(false, parseOrNull(what), parseOrNull(third)));
            }
        }
        return o;
    }

    static Overrides committedOverrides() {
        return parseOverrides(Fixtures.read("oracle/transform_oracle_overrides.txt"));
    }

    // ------------------------------------------------------------------
    // Excused rows
    // ------------------------------------------------------------------

    /** The name of the row-level exclusion list in {@code deferred/}. */
    static final String ORACLE_ROWS = "oracle-rows";

    /** The rows of one section that {@code deferred/oracle-rows.txt} excuses, and why. */
    static final class ExcusedRows {
        final String relpath;
        final boolean forward;
        final SortedSet<Integer> rows;
        final String cause;

        ExcusedRows(String relpath, boolean forward, SortedSet<Integer> rows, String cause) {
            this.relpath = relpath;
            this.forward = forward;
            this.rows = Collections.unmodifiableSortedSet(rows);
            this.cause = cause;
        }
    }

    /** The key of a section in a map of {@link ExcusedRows}. */
    static String sectionKey(String relpath, boolean forward) {
        return relpath + (forward ? " forward" : " inverse");
    }

    /**
     * Parses the text of {@code deferred/oracle-rows.txt}: lines {@code <fixture> <forward|inverse>
     * <rows> | <cause>}, the rows numbered from 0 and separated by commas, with {@code #} comments
     * and blank lines ignored. The result is keyed by {@link #sectionKey}.
     *
     * @throws IllegalArgumentException for a malformed line, or a section listed twice
     */
    static Map<String, ExcusedRows> parseOracleRows(String text, String what) {
        return oracleRowsFrom(Deferred.parse(text, what), what);
    }

    /** The committed {@code deferred/oracle-rows.txt}. */
    static Map<String, ExcusedRows> committedOracleRows() {
        return oracleRowsFrom(Deferred.load(ORACLE_ROWS), "deferred/" + ORACLE_ROWS + ".txt");
    }

    private static Map<String, ExcusedRows> oracleRowsFrom(Map<String, String> entries, String what) {
        Map<String, ExcusedRows> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String[] toks = CValues.splitWhitespace(e.getKey());
            if (toks.length != 3) {
                throw new IllegalArgumentException(
                        what + ": expected \"<fixture> <forward|inverse> <rows> | <cause>\": " + e.getKey());
            }
            boolean forward;
            if ("forward".equals(toks[1])) {
                forward = true;
            } else if ("inverse".equals(toks[1])) {
                forward = false;
            } else {
                throw new IllegalArgumentException(what + ": direction must be forward or inverse: " + e.getKey());
            }
            SortedSet<Integer> rows = new TreeSet<>();
            String list = toks[2];
            int i = 0;
            while (i <= list.length()) {
                int j = list.indexOf(',', i);
                if (j < 0) {
                    j = list.length();
                }
                String tok = list.substring(i, j);
                int row;
                try {
                    row = Integer.parseInt(tok);
                } catch (NumberFormatException ex) {
                    throw (IllegalArgumentException) new IllegalArgumentException(
                                    what + ": rows must be numbers from 0, separated by commas: " + e.getKey())
                            .initCause(ex);
                }
                if (row < 0 || tok.startsWith("+") || !rows.add(row)) {
                    throw new IllegalArgumentException(
                            what + ": rows must be distinct numbers from 0, separated by commas: " + e.getKey());
                }
                i = j + 1;
            }
            String key = sectionKey(toks[0], forward);
            if (out.put(key, new ExcusedRows(toks[0], forward, rows, e.getValue())) != null) {
                throw new IllegalArgumentException(what + ": " + key + " listed twice");
            }
        }
        return out;
    }

    /** The misses of a section left once listed rows are excused, and the listed rows that match C. */
    static final class Excusal {
        final List<AxisMiss> misses;
        final SortedSet<Integer> matching;

        Excusal(List<AxisMiss> misses, SortedSet<Integer> matching) {
            this.misses = misses;
            this.matching = matching;
        }
    }

    /**
     * Excuses every miss on a listed row, whichever check found it; a listed row with no miss
     * matches C.
     */
    static Excusal excuseRows(List<AxisMiss> misses, Set<Integer> listed) {
        List<AxisMiss> kept = new ArrayList<>();
        Set<Integer> missed = new HashSet<>();
        for (AxisMiss m : misses) {
            if (listed.contains(m.row)) {
                missed.add(m.row);
            } else {
                kept.add(m);
            }
        }
        SortedSet<Integer> matching = new TreeSet<>(listed);
        matching.removeAll(missed);
        return new Excusal(kept, matching);
    }

    /**
     * What is wrong with the entries of {@code listed} that {@code inGate} covers, given the
     * number of rows of each section the gate compared (keyed by {@link #sectionKey}): an entry
     * naming no such section, or a row beyond its last.
     */
    static List<String> excusedRowEntryProblems(
            Map<String, ExcusedRows> listed, Map<String, Integer> npoints, Predicate<String> inGate) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, ExcusedRows> e : listed.entrySet()) {
            ExcusedRows x = e.getValue();
            if (!inGate.test(x.relpath)) {
                continue;
            }
            Integer npoint = npoints.get(e.getKey());
            if (npoint == null) {
                problems.add(ORACLE_ROWS + ".txt names " + e.getKey() + ", which is not an oracle section");
            } else if (x.rows.last() >= npoint) {
                problems.add(ORACLE_ROWS + ".txt names row " + x.rows.last() + " of " + e.getKey() + ", which has "
                        + npoint + " rows");
            }
        }
        return problems;
    }

    /** The problem a section with listed rows that match C gives, or null if it has none. */
    static String matchingRowsProblem(SectionReport r) {
        if (r.matchingListedRows == null || r.matchingListedRows.isEmpty()) {
            return null;
        }
        return sectionKey(r.relpath(), r.section.forward) + " rows " + r.matchingListedRows + " are listed in "
                + ORACLE_ROWS + ".txt but match C; remove them";
    }

    // ------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------

    enum CheckKind {
        GOLDEN_FWD("golden-fwd"),
        GOLDEN_INV("golden-inv"),
        EQUIVALENCE("equivalence"),
        ROUND_TRIP("round-trip");

        final String label;

        CheckKind(String label) {
            this.label = label;
        }
    }

    /** One output value that does not match. */
    static final class AxisMiss {
        final CheckKind check;
        final int row;
        final int axis;
        final double reference;
        final double got;
        final double rel;

        AxisMiss(CheckKind check, int row, int axis, double reference, double got) {
            this.check = check;
            this.row = row;
            this.axis = axis;
            this.reference = reference;
            this.got = got;
            this.rel = relDiff(got, reference);
        }

        boolean bad() {
            return got == CValues.BAD || reference == CValues.BAD;
        }
    }

    /** Golden check: live outputs against the recorded ones, axis by axis. */
    static List<AxisMiss> compareGolden(
            CheckKind check,
            double[][] live,
            double[][] recorded,
            boolean[] angular,
            long skipRows,
            double rtol,
            double atol) {
        List<AxisMiss> misses = new ArrayList<>();
        int npoint = recorded.length == 0 ? 0 : recorded[0].length;
        for (int row = 0; row < npoint; row++) {
            if (row < 64 && ((skipRows >>> row) & 1L) == 1L) {
                continue;
            }
            for (int axis = 0; axis < recorded.length; axis++) {
                double got = live[axis][row];
                double reference = recorded[axis][row];
                if (!axisMatch(got, reference, rtol, atol, angular[axis])) {
                    misses.add(new AxisMiss(check, row, axis, reference, got));
                }
            }
        }
        return misses;
    }

    /**
     * Equivalence check: the live {@code .map} outputs against the live {@code .simp} outputs, only
     * where C's recorded values for the two agree (where they do not, the point was outside the
     * domain the two share).
     */
    static List<AxisMiss> compareEquiv(
            double[][] mapLive,
            double[][] simpLive,
            double[][] mapRef,
            double[][] simpRef,
            boolean[] angular,
            double rtol,
            double atol) {
        List<AxisMiss> misses = new ArrayList<>();
        int npoint = mapRef.length == 0 ? 0 : mapRef[0].length;
        for (int row = 0; row < npoint; row++) {
            for (int axis = 0; axis < mapRef.length; axis++) {
                if (!axisMatch(mapRef[axis][row], simpRef[axis][row], rtol, atol, angular[axis])) {
                    continue;
                }
                double a = mapLive[axis][row];
                double b = simpLive[axis][row];
                if (!axisMatch(a, b, rtol, atol, angular[axis])) {
                    misses.add(new AxisMiss(CheckKind.EQUIVALENCE, row, axis, b, a));
                }
            }
        }
        return misses;
    }

    /**
     * Whether C's own recorded inverse recovers the recorded forward input at {@code row}, decided
     * from the recorded data alone.
     */
    static boolean referenceRoundTrips(OracleSection fwd, double[][] invRef, int row, double rtol, double atol) {
        double[][] in = fwd.inputs();
        for (int a = 0; a < fwd.nin; a++) {
            if (!withinTol(invRef[a][row], in[a][row], rtol, atol)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Round-trip check: the inverse of the forward outputs recovers the forward inputs, skipping
     * points whose forward output was BAD, accepting a different point that the forward
     * transformation takes to the same output (an alias), and excusing, last, a row C's own
     * recorded inverse does not recover either.
     */
    static List<AxisMiss> compareRtrip(
            Mapping map, OracleSection fwd, double[][] invLive, double[][] invRef, double rtol, double atol) {
        List<AxisMiss> misses = new ArrayList<>();
        int npoint = fwd.npoint();
        double[][] fwdIn = fwd.inputs();
        double[][] fwdOut = fwd.outputs();
        double[][] sky2;
        try {
            sky2 = map.tranP(npoint, fwd.nin, invLive, true, fwd.nout);
        } catch (AstException e) {
            sky2 = null;
        }
        for (int row = 0; row < npoint; row++) {
            boolean fbad = false;
            for (int b = 0; b < fwd.nout; b++) {
                fbad |= fwdOut[b][row] == CValues.BAD;
            }
            if (fbad) {
                continue;
            }
            boolean pixelOk = true;
            for (int a = 0; a < fwd.nin; a++) {
                pixelOk &= withinTol(invLive[a][row], fwdIn[a][row], rtol, atol);
            }
            if (pixelOk) {
                continue;
            }
            if (sky2 != null) {
                boolean alias = true;
                for (int b = 0; b < fwd.nout; b++) {
                    alias &= withinTolWrap(sky2[b][row], fwdOut[b][row], fwd.rtol, fwd.atol);
                }
                if (alias) {
                    continue;
                }
            }
            if (!referenceRoundTrips(fwd, invRef, row, rtol, atol)) {
                continue;
            }
            for (int a = 0; a < fwd.nin; a++) {
                if (!withinTol(invLive[a][row], fwdIn[a][row], rtol, atol)) {
                    misses.add(new AxisMiss(CheckKind.ROUND_TRIP, row, a, fwdIn[a][row], invLive[a][row]));
                }
            }
        }
        return misses;
    }

    // ------------------------------------------------------------------
    // Driver
    // ------------------------------------------------------------------

    enum Outcome {
        PASS,
        SKIPPED_BY_OVERRIDE,
        LOAD_FAIL,
        ARITY,
        MISMATCH;

        boolean fails() {
            return this == LOAD_FAIL || this == ARITY || this == MISMATCH;
        }
    }

    /** What happened to one section. */
    static final class SectionReport {
        final String oracle;
        final OracleSection section;
        Outcome outcome;
        String detail = "";
        List<AxisMiss> misses = Collections.emptyList();

        /** The round-trip check has a forward section to pair with, and is not switched off. */
        boolean rtripPaired;

        /** The round-trip check ran. */
        boolean rtrip;

        /** The {@code .map} section the equivalence check pairs with, or null. */
        String equivPartner;

        /** The equivalence check ran. */
        boolean equiv;

        /**
         * The rows {@code deferred/oracle-rows.txt} lists for this section that match C, or null if
         * it lists none or the section was not compared.
         */
        SortedSet<Integer> matchingListedRows;

        SectionReport(String oracle, OracleSection section) {
            this.oracle = oracle;
            this.section = section;
        }

        String relpath() {
            return section.path;
        }

        String describe() {
            String dir = section.forward ? "fwd" : "inv";
            if (outcome != Outcome.MISMATCH) {
                return relpath() + " " + dir + ": " + outcome + " " + detail;
            }
            AxisMiss worst = misses.get(0);
            for (AxisMiss m : misses) {
                if (m.rel > worst.rel) {
                    worst = m;
                }
            }
            return relpath() + " " + dir + ": mismatch:" + worst.check.label + " nmiss=" + misses.size() + " row="
                    + worst.row + " axis=" + worst.axis + " ref=" + worst.reference + " got=" + worst.got + " rel="
                    + worst.rel + (worst.bad() ? " (BAD)" : "");
        }
    }

    private static boolean hasExt(String relpath, String ext) {
        int dot = relpath.lastIndexOf('.');
        return dot >= 0 && relpath.substring(dot).equals(ext);
    }

    private static String pathStem(String relpath) {
        int dot = relpath.lastIndexOf('.');
        return dot < 0 ? relpath : relpath.substring(0, dot);
    }

    static List<OracleSection> parseOracle(String oracle) {
        return OracleFile.parse(Fixtures.root().resolve("oracle/" + oracle + ".oracle"));
    }

    /**
     * Runs every section of one oracle file, recording an outcome for each. The pairing of sections
     * follows the order C's generator writes them in.
     */
    static List<SectionReport> runOracle(String oracle, Overrides overrides, Map<String, ExcusedRows> excused) {
        List<SectionReport> reports = new ArrayList<>();
        OracleSection prevFwdAny = null;
        OracleSection prevMapAny = null;
        OracleSection prevFwd = null;
        OracleSection prevMap = null;
        double[][] prevMapLive = null;
        for (OracleSection s : parseOracle(oracle)) {
            SectionReport r = new SectionReport(oracle, s);
            reports.add(r);
            boolean grid = hasExt(s.path, ".head") || hasExt(s.path, ".ast");
            double[] rtripTol = overrides.rtrip(s.path, s.rtripRtol, s.rtripAtol);
            r.rtripPaired = !s.forward && grid && rtripTol != null && pairsAsRtrip(prevFwdAny, s);
            if (s.forward && hasExt(s.path, ".simp") && prevMapAny != null && pairsAsEquiv(prevMapAny, s)) {
                r.equivPartner = prevMapAny.path;
            }
            if (s.forward) {
                prevFwdAny = s;
                if (hasExt(s.path, ".map")) {
                    prevMapAny = s;
                }
            }

            Loaded loaded = loadFixture(s.path);
            if (loaded.map == null) {
                r.outcome = Outcome.LOAD_FAIL;
                r.detail = loaded.failure;
                continue;
            }
            Mapping map = loaded.map;
            double[][] live;
            try {
                live = transformSection(map, s, s.inputs());
            } catch (TransformFailure e) {
                r.outcome = Outcome.ARITY;
                r.detail = e.getMessage();
                continue;
            }
            boolean[] angular = angularFlags(loaded, s);
            List<AxisMiss> misses = new ArrayList<>();
            boolean skippedByOverride = false;
            if (overrides.goldenOff(s.path, s.forward)) {
                skippedByOverride = true;
            } else {
                misses.addAll(compareGolden(
                        s.forward ? CheckKind.GOLDEN_FWD : CheckKind.GOLDEN_INV,
                        live,
                        s.outputs(),
                        angular,
                        overrides.goldenSkipRows(s.path, s.forward),
                        s.rtol,
                        s.atol));
            }
            if (!s.forward && grid && rtripTol != null && prevFwd != null && pairsAsRtrip(prevFwd, s)) {
                misses.addAll(compareRtrip(map, prevFwd, live, s.outputs(), rtripTol[0], rtripTol[1]));
                r.rtrip = true;
            }
            if (s.forward && hasExt(s.path, ".simp") && prevMap != null && pairsAsEquiv(prevMap, s)) {
                misses.addAll(compareEquiv(
                        prevMapLive, live, prevMap.outputs(), s.outputs(), angular, s.equivRtol, s.equivAtol));
                r.equiv = true;
            }
            if (s.forward) {
                prevFwd = s;
                if (hasExt(s.path, ".map")) {
                    prevMap = s;
                    prevMapLive = live;
                }
            }
            ExcusedRows listed = excused.get(sectionKey(s.path, s.forward));
            if (listed != null) {
                Excusal x = excuseRows(misses, listed.rows);
                misses = x.misses;
                r.matchingListedRows = x.matching;
            }
            if (!misses.isEmpty()) {
                r.outcome = Outcome.MISMATCH;
                r.misses = misses;
            } else if (skippedByOverride) {
                r.outcome = Outcome.SKIPPED_BY_OVERRIDE;
                r.detail = s.forward ? "golden-fwd" : "golden-inv";
            } else {
                r.outcome = Outcome.PASS;
            }
        }
        return reports;
    }

    private static boolean pairsAsRtrip(OracleSection fwd, OracleSection inv) {
        return fwd != null && fwd.path.equals(inv.path) && fwd.nin == inv.nout && fwd.npoint() == inv.npoint();
    }

    private static boolean pairsAsEquiv(OracleSection map, OracleSection simp) {
        return map.nout == simp.nout
                && map.npoint() == simp.npoint()
                && pathStem(map.path).equals(pathStem(simp.path));
    }

    /** The deferred list of an oracle file. */
    static String deferredName(String oracle) {
        return "simplify_fixtures".equals(oracle) ? "simplify" : oracle;
    }

    static boolean isHeadSection(String relpath) {
        return relpath.endsWith(".head");
    }

    static boolean isSlamapSection(String relpath) {
        int slash = relpath.lastIndexOf('/');
        return relpath.substring(slash + 1).contains("sla");
    }

    static boolean isSimplifySection(String relpath) {
        return !isHeadSection(relpath) && !isSlamapSection(relpath);
    }

    /** The fixtures of an oracle's reports, each with its sections, in oracle order. */
    private static Map<String, List<SectionReport>> byFixture(List<SectionReport> reports) {
        Map<String, List<SectionReport>> out = new LinkedHashMap<>();
        for (SectionReport r : reports) {
            out.computeIfAbsent(r.relpath(), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private static boolean anyFails(List<SectionReport> sections) {
        for (SectionReport r : sections) {
            if (r.outcome.fails()) {
                return true;
            }
        }
        return false;
    }

    /** Rewrites the deferred list of one oracle from its reports, and gives the unexplained failures. */
    private static List<String> writeDeferred(String oracle, List<SectionReport> reports) {
        Map<String, String> old = Deferred.load(deferredName(oracle));
        Map<String, String> entries = new LinkedHashMap<>();
        for (Map.Entry<String, List<SectionReport>> e : byFixture(reports).entrySet()) {
            if (anyFails(e.getValue())) {
                entries.put(e.getKey(), Deferred.newReason(needs(e.getKey()), old.get(e.getKey())));
            }
        }
        Deferred.write(
                deferredName(oracle),
                Arrays.asList(
                        "Fixtures of fixtures/oracle/" + oracle + ".oracle whose sections do not all match C:",
                        "<path relative to ast_tester/fixtures> | <reason>",
                        "Rewritten by TransformOracleTest with -D" + Deferred.WRITE_PROPERTY + "=true."),
                entries);
        List<String> unexplained = new ArrayList<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (Deferred.UNEXPLAINED.equals(e.getValue())) {
                unexplained.add(
                        e.getKey() + ": " + firstFailure(byFixture(reports).get(e.getKey())));
            }
        }
        return unexplained;
    }

    private static String firstFailure(List<SectionReport> sections) {
        for (SectionReport r : sections) {
            if (r.outcome.fails()) {
                return r.describe();
            }
        }
        return "passes";
    }

    /**
     * One gate: the sections of the named oracles for which {@code inGate} holds either match C,
     * apart from the rows {@code deferred/oracle-rows.txt} excuses (each of which must still differ),
     * or belong to a deferred fixture, which must fail and give a current reason; the gate covers
     * {@code nsections} sections, and the round-trip and equivalence checks pair {@code nrtrip}
     * and {@code nequiv} of them. Gives what is wrong, or with {@code -Dast.writeDeferred=true}
     * rewrites the deferred lists and gives the failures it could not explain.
     */
    private static List<String> gate(
            List<String> oracles, Predicate<String> inGate, int nsections, int nrtrip, int nequiv) {
        Overrides ov = committedOverrides();
        Map<String, ExcusedRows> excused = committedOracleRows();
        List<String> problems = new ArrayList<>();
        Map<String, Integer> npoints = new LinkedHashMap<>();
        int sections = 0;
        int rtripPaired = 0;
        int equivPaired = 0;
        for (String oracle : oracles) {
            List<SectionReport> all = runOracle(oracle, ov, excused);
            for (SectionReport r : all) {
                if (inGate.test(r.relpath())) {
                    npoints.put(sectionKey(r.relpath(), r.section.forward), r.section.npoint());
                    String p = matchingRowsProblem(r);
                    if (p != null) {
                        problems.add(p);
                    }
                }
            }
            if (Deferred.writeRequested()) {
                problems.addAll(writeDeferred(oracle, all));
                continue;
            }
            Map<String, String> deferred = Deferred.load(deferredName(oracle));
            for (Map.Entry<String, List<SectionReport>> e : byFixture(all).entrySet()) {
                String relpath = e.getKey();
                if (!inGate.test(relpath)) {
                    continue;
                }
                List<SectionReport> secs = e.getValue();
                String reason = deferred.get(relpath);
                boolean fails = anyFails(secs);
                for (SectionReport r : secs) {
                    sections++;
                    rtripPaired += r.rtripPaired ? 1 : 0;
                    equivPaired += r.equivPartner != null ? 1 : 0;
                    if (reason == null && r.outcome.fails()) {
                        problems.add(r.describe());
                    }
                    if (reason == null && r.rtripPaired && !r.rtrip) {
                        problems.add(relpath + ": round-trip check did not run");
                    }
                    if (reason == null && r.equivPartner != null && !r.equiv && !deferred.containsKey(r.equivPartner)) {
                        problems.add(relpath + ": equivalence check against " + r.equivPartner + " did not run");
                    }
                }
                if (reason != null) {
                    if (!fails) {
                        problems.add(relpath + " is deferred (" + reason + ") but matches C; remove it from "
                                + deferredName(oracle) + ".txt");
                    } else {
                        String p = Deferred.reasonProblem(reason, needs(relpath));
                        if (p != null) {
                            problems.add(relpath + " " + p + " (" + firstFailure(secs) + ")");
                        }
                    }
                }
            }
        }
        problems.addAll(excusedRowEntryProblems(excused, npoints, inGate));
        if (Deferred.writeRequested()) {
            return problems;
        }
        if (sections != nsections) {
            problems.add("the gate compared " + sections + " sections, not " + nsections
                    + "; fixtures may have been renamed, removed or added");
        }
        if (rtripPaired != nrtrip || equivPaired != nequiv) {
            problems.add("the gate paired " + rtripPaired + " sections for the round trip and " + equivPaired
                    + " for equivalence, not " + nrtrip + " and " + nequiv
                    + "; a regenerated oracle that reorders sections stops the checks silently");
        }
        return problems;
    }

    // ------------------------------------------------------------------
    // resolve_tests
    // ------------------------------------------------------------------

    @Test
    void resolvesEveryOracleRelpath() {
        List<String> missing = new ArrayList<>();
        for (String oracle : ORACLES) {
            for (OracleSection s : parseOracle(oracle)) {
                if (resolveFixture(s.path) == null && !missing.contains(s.path)) {
                    missing.add(s.path);
                }
            }
        }
        assertTrue(missing.isEmpty(), "unresolved fixtures: " + missing);
    }

    /** {@code serialisation/degen1.ast} is the round-tripped form of {@code wcsconv/inputs/degen1.ast}. */
    @Test
    void duplicateBasenamesResolveToTheirOwnFiles() {
        Path a = resolveFixture("wcsconv/inputs/degen1.ast");
        Path b = resolveFixture("serialisation/degen1.ast");
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a, b);
        assertNotEquals(Fixtures.read("wcsconv/inputs/degen1.ast"), Fixtures.read("serialisation/degen1.ast"));
    }

    @Test
    void bareBasenameDoesNotResolve() {
        assertNull(resolveFixture("degen1.ast"));
        assertNull(resolveFixture("brad.map"));
    }

    // ------------------------------------------------------------------
    // parse_tests
    // ------------------------------------------------------------------

    private static final String TOL_LINE =
            "# tol: rtol=1e-07 atol=1e-07  equiv_rtol=1e-09 equiv_atol=1e-09  rtrip_rtol=1e-06 rtrip_atol=0.05\n";

    @Test
    void parsesHeaderBadTokenAndDirection() {
        String text = "# header\n" + TOL_LINE + "\n[foo.map  nin=2 nout=1 dir=forward]\n 1 2  3\n 4 5  BAD\n\n"
                + "[bar.head  nin=1 nout=1 dir=inverse]\n 7  8\n";
        List<OracleSection> secs = OracleFile.parseText(text, "test");
        assertEquals(2, secs.size());
        OracleSection s0 = secs.get(0);
        assertEquals("foo.map", s0.path);
        assertEquals(2, s0.nin);
        assertEquals(1, s0.nout);
        assertTrue(s0.forward);
        assertEquals(2, s0.npoint());
        assertEquals(1.0, s0.inputs()[0][0]);
        assertEquals(5.0, s0.inputs()[1][1]);
        assertEquals(3.0, s0.outputs()[0][0]);
        assertEquals(CValues.BAD, s0.outputs()[0][1]);
        assertFalse(secs.get(1).forward);
    }

    @Test
    void malformedRowIsHardError() {
        String text = TOL_LINE + "[foo.map  nin=2 nout=1 dir=forward]\n  1 2\n";
        assertThrows(IllegalArgumentException.class, () -> OracleFile.parseText(text, "test"));
    }

    @Test
    void parsesAllThreeCommittedOracles() {
        int total = 0;
        for (String oracle : ORACLES) {
            total += parseOracle(oracle).size();
        }
        assertEquals(2261, total);
    }

    // ------------------------------------------------------------------
    // primitive_tests
    // ------------------------------------------------------------------

    /** The golden tolerances every committed oracle states. */
    private static final double GOLDEN_RTOL = 1e-7;

    private static final double GOLDEN_ATOL = 1e-7;

    @Test
    void committedOraclesStateTheGoldenTolerances() {
        for (String oracle : ORACLES) {
            OracleSection s = parseOracle(oracle).get(0);
            assertEquals(GOLDEN_RTOL, s.rtol, oracle);
            assertEquals(GOLDEN_ATOL, s.atol, oracle);
        }
    }

    @Test
    void withinTolMatchesCSemantics() {
        assertTrue(withinTol(1.0, 1.0, 0.0, 0.0));
        assertTrue(withinTol(1.0 + 4e-16, 1.0, GOLDEN_RTOL, GOLDEN_ATOL));
        assertFalse(withinTol(1.0001, 1.0, GOLDEN_RTOL, GOLDEN_ATOL));
        assertTrue(withinTol(5e-13, 0.0, GOLDEN_RTOL, GOLDEN_ATOL));
        assertTrue(withinTol(CValues.BAD, CValues.BAD, GOLDEN_RTOL, GOLDEN_ATOL));
        assertFalse(withinTol(CValues.BAD, 1.0, GOLDEN_RTOL, GOLDEN_ATOL));
        assertFalse(withinTol(1.0, CValues.BAD, GOLDEN_RTOL, GOLDEN_ATOL));
        assertFalse(withinTol(Double.NaN, 1.0, 1e9, 1e9));
    }

    @Test
    void withinTolWrapAcceptsFullTurnAndCiPairs() {
        assertTrue(withinTolWrap(1.0, 1.0, GOLDEN_RTOL, GOLDEN_ATOL));
        assertTrue(withinTolWrap(TWO_PI, 0.0, GOLDEN_RTOL, GOLDEN_ATOL));
        assertTrue(withinTolWrap(0.0, TWO_PI, GOLDEN_RTOL, GOLDEN_ATOL));
        assertTrue(withinTolWrap(Math.PI, -Math.PI, GOLDEN_RTOL, GOLDEN_ATOL));
        assertFalse(withinTolWrap(1.0, 2.0, GOLDEN_RTOL, GOLDEN_ATOL));
        assertFalse(withinTolWrap(CValues.BAD, 0.0, GOLDEN_RTOL, GOLDEN_ATOL));
        // Pairs of an arm64 reference and an x86-64 result seen in C's CI.
        assertTrue(withinTolWrap(5.0999352672935785e-17, 6.283185307179586, GOLDEN_RTOL, GOLDEN_ATOL));
        assertTrue(withinTolWrap(1.9478449064398693e-20, 6.283185307179586, GOLDEN_RTOL, GOLDEN_ATOL));
        assertTrue(withinTolWrap(3.141592653589793, -3.141592653589793, GOLDEN_RTOL, GOLDEN_ATOL));
        assertTrue(withinTolWrap(1.0, 1.0000000047018207, GOLDEN_RTOL, GOLDEN_ATOL));
    }

    // ------------------------------------------------------------------
    // loader_tests
    // ------------------------------------------------------------------

    @Test
    void loadsNativeMapping() {
        Loaded l = loadFixture("simplify/matrix_diagonal_to_zoom.map");
        assertNull(l.failure);
        assertEquals(2, l.map.getNin());
        assertNotEquals("FrameSet", l.map.astClass(), "a bare Mapping has no Frame");
    }

    /**
     * A celestial header the FitsChan reads as FITS-WCS, used instead of Rust's {@code
     * wcsconv/inputs/cobe.head}, which the FitsChan guesses to be FITS-AIPS, an encoding whose read
     * is out of scope.
     */
    private static final String CELESTIAL_HEAD = "wcsconv/inputs/galactic-tan.head";

    /**
     * A {@code .head} fixture reads as a FrameSet, whose base-to-current Mapping is transformed and
     * whose base and current Frames are kept for the angular axes.
     */
    @Test
    void loadsHeadFramesetWithFrames() {
        Loaded l = loadFixture(CELESTIAL_HEAD);
        assertNull(l.failure);
        assertEquals(2, l.map.getNin());
        assertNotNull(l.current, "forward -> current frame");
        assertNotNull(l.base, "inverse -> base frame");
    }

    /**
     * Translation of Rust {@code bare_frame_is_untransformable_and_says_why}. A bare Frame is a
     * Mapping that transforms as the identity in both directions (frame.c {@code Transform}), so
     * C's oracle sections for one record each output equal to its input and a loaded bare Frame
     * reproduces them. Rust's fixture, {@code wcsconv/inputs/specflux.ast}, holds a SpecFrame; this
     * uses the bare CmpFrame {@code wcsconv/framesets/timeplot-head.ast}, of a TimeFrame and a
     * Frame, whose sections C writes the same way.
     */
    @Test
    void bareFrameTransformsAsTheIdentity() throws TransformFailure {
        String relpath = "wcsconv/framesets/timeplot-head.ast";
        Loaded l = loadFixture(relpath);
        assertNull(l.failure);
        assertEquals("CmpFrame", l.map.astClass());
        int nsections = 0;
        for (OracleSection s : parseOracle("framesets")) {
            if (!s.path.equals(relpath)) {
                continue;
            }
            nsections++;
            assertEquals(2, s.nin);
            assertEquals(2, s.nout);
            double[][] in = s.inputs();
            double[][] out = s.outputs();
            assertTrue(Arrays.deepEquals(in, out), s + ": C records the identity");
            assertTrue(Arrays.deepEquals(in, transformSection(l.map, s, in)), s.toString());
        }
        assertEquals(2, nsections, "a forward and an inverse section");
    }

    // ------------------------------------------------------------------
    // override_tests
    // ------------------------------------------------------------------

    @Test
    void parsesIndependentRtripAndGoldenLines() {
        String text = "# comment\n"
                + "rtoff.head off\n"
                + "loose.head 2e-3 4e-2\n"
                + "noisy.map golden-inv off\n"
                + "both.map golden off\n"
                + "fwdonly.map golden-fwd off\n"
                + "rows.map golden-inv-rows 2,3\n"
                + "rows.map golden-fwd-rows 0\n";
        Overrides o = parseOverrides(text);
        assertNull(o.rtrip("rtoff.head", 1e-6, 0.05));
        assertTrue(Arrays.equals(new double[] {2e-3, 4e-2}, o.rtrip("loose.head", 1e-6, 0.05)));
        // A fixture with only a golden line keeps the default round trip.
        assertTrue(Arrays.equals(new double[] {1e-6, 0.05}, o.rtrip("noisy.map", 1e-6, 0.05)));
        assertTrue(!o.goldenOff("rtoff.head", true) && !o.goldenOff("rtoff.head", false));
        assertTrue(!o.goldenOff("noisy.map", true) && o.goldenOff("noisy.map", false));
        assertTrue(o.goldenOff("both.map", true) && o.goldenOff("both.map", false));
        assertTrue(o.goldenOff("fwdonly.map", true) && !o.goldenOff("fwdonly.map", false));
        // A row list narrows the skip instead of switching off the direction.
        assertTrue(!o.goldenOff("rows.map", true) && !o.goldenOff("rows.map", false));
        assertEquals(0b1100L, o.goldenSkipRows("rows.map", false));
        assertEquals(0b1L, o.goldenSkipRows("rows.map", true));
        assertEquals(0L, o.goldenSkipRows("noisy.map", true));
    }

    @Test
    void parsesCommittedOverridesFile() {
        Overrides o = committedOverrides();
        // An allowlist, so that a new override cannot appear unnoticed.
        List<String> rtripOff = new ArrayList<>();
        for (Map.Entry<String, RtripRule> e : o.rtripRules.entrySet()) {
            assertTrue(e.getValue().off, "only disabled round trips, no loosened tolerance: " + e.getKey());
            rtripOff.add(e.getKey());
        }
        Collections.sort(rtripOff);
        assertEquals(Arrays.asList("wcsconv/framesets/tnx-cheb-head.ast", "wcsconv/inputs/tnx-cheb.head"), rtripOff);

        // neg_unitnormmap_inv_fwd_diffcentre's inverse at the all-zero sample (rows 2 and 3) is BAD
        // or a direction made of rounding noise, depending on whether a multiply and an add are
        // fused, so only those rows are skipped.
        String unm = "simplify/neg_unitnormmap_inv_fwd_diffcentre.map";
        assertEquals(Collections.singletonList(unm), new ArrayList<>(o.goldenRules.keySet()));
        assertTrue(!o.goldenOff(unm, false) && !o.goldenOff(unm, true));
        assertEquals(0b1100L, o.goldenSkipRows(unm, false));
        assertEquals(0L, o.goldenSkipRows(unm, true));

        Overrides probe = parseOverrides("a.head off\nb.map golden-inv off\nc.map golden-fwd-rows 0,5,63\n");
        assertNull(probe.rtrip("a.head", 1e-6, 0.05));
        assertTrue(probe.goldenOff("b.map", false));
        assertFalse(probe.goldenOff("c.map", true));
        assertEquals(1L | 1L << 5 | 1L << 63, probe.goldenSkipRows("c.map", true));
        assertEquals(0L, probe.goldenSkipRows("c.map", false));
    }

    @Test
    void parsesRowListsAsC() {
        assertEquals(0L, parseRowList(""));
        assertEquals(1L | 1L << 63, parseRowList("0,63"));
        assertEquals(0L, parseRowList("64,-1"));
    }

    // ------------------------------------------------------------------
    // Excused rows
    // ------------------------------------------------------------------

    @Test
    void parsesOracleRowLines() {
        String text = "# comment\n\n"
                + "wcsconv/inputs/a.head inverse 3,0,12 | BAD in C, finite here, beside the pole\n"
                + "simplify/b.map  forward  5 | finite in C, BAD here\n";
        Map<String, ExcusedRows> rows = parseOracleRows(text, "test");
        assertEquals(
                Arrays.asList("wcsconv/inputs/a.head inverse", "simplify/b.map forward"),
                new ArrayList<>(rows.keySet()));
        ExcusedRows a = rows.get(sectionKey("wcsconv/inputs/a.head", false));
        assertEquals("wcsconv/inputs/a.head", a.relpath);
        assertFalse(a.forward);
        assertEquals(Arrays.asList(0, 3, 12), new ArrayList<>(a.rows));
        assertEquals("BAD in C, finite here, beside the pole", a.cause);
        ExcusedRows b = rows.get(sectionKey("simplify/b.map", true));
        assertTrue(b.forward);
        assertEquals(Collections.singletonList(5), new ArrayList<>(b.rows));
        assertTrue(parseOracleRows("# only a comment\n", "test").isEmpty());
    }

    @Test
    void malformedOracleRowLinesAreHardErrors() {
        List<String> bad = Arrays.asList(
                "a.head inverse 3",
                "a.head inverse 3 |",
                "a.head 3 | cause",
                "a.head backward 3 | cause",
                "a.head inverse 3 4 | cause",
                "a.head inverse -1 | cause",
                "a.head inverse +1 | cause",
                "a.head inverse 1,,2 | cause",
                "a.head inverse 1, | cause",
                "a.head inverse x | cause",
                "a.head inverse 1,1 | cause",
                "a.head inverse 1 | cause\na.head inverse 2 | cause");
        for (String line : bad) {
            assertThrows(IllegalArgumentException.class, () -> parseOracleRows(line + "\n", "test"), line);
        }
        // The two directions of one fixture are two sections.
        assertEquals(
                2,
                parseOracleRows("a.head forward 1 | c\na.head inverse 1 | c\n", "test")
                        .size());
    }

    @Test
    void parsesCommittedOracleRows() {
        for (ExcusedRows x : committedOracleRows().values()) {
            assertFalse(x.rows.isEmpty(), x.relpath);
        }
    }

    @Test
    void listedRowsAreExcusedAndThoseMatchingCReported() {
        List<AxisMiss> misses = Arrays.asList(
                new AxisMiss(CheckKind.GOLDEN_INV, 1, 0, 1.0, 2.0),
                new AxisMiss(CheckKind.GOLDEN_INV, 2, 0, CValues.BAD, 2.0),
                new AxisMiss(CheckKind.ROUND_TRIP, 2, 1, 1.0, 3.0));
        Excusal x = excuseRows(misses, new TreeSet<>(Arrays.asList(2, 5)));
        assertEquals(1, x.misses.size());
        assertEquals(1, x.misses.get(0).row);
        assertEquals(Collections.singletonList(5), new ArrayList<>(x.matching));

        Excusal all = excuseRows(misses, new TreeSet<>(Arrays.asList(1, 2)));
        assertTrue(all.misses.isEmpty());
        assertTrue(all.matching.isEmpty());

        OracleSection s = OracleFile.parseText(TOL_LINE + "[x.head nin=1 nout=1 dir=inverse]\n 1 1\n", "test")
                .get(0);
        SectionReport r = new SectionReport("headers", s);
        assertNull(matchingRowsProblem(r), "no rows listed");
        r.matchingListedRows = all.matching;
        assertNull(matchingRowsProblem(r), "every listed row still differs");
        r.matchingListedRows = x.matching;
        String p = matchingRowsProblem(r);
        assertNotNull(p);
        assertTrue(p.contains("x.head inverse") && p.contains("[5]"), p);
    }

    @Test
    void oracleRowEntriesMustNameComparedSections() {
        Map<String, ExcusedRows> listed = parseOracleRows(
                "a.head inverse 0,3 | c\nb.head forward 1 | c\nc.head forward 4 | c\nd.map forward 9 | c\n", "test");
        Map<String, Integer> npoints = new LinkedHashMap<>();
        npoints.put(sectionKey("a.head", false), 4);
        npoints.put(sectionKey("b.head", true), 2);
        npoints.put(sectionKey("c.head", true), 4);
        npoints.put(sectionKey("c.head", false), 8);
        List<String> problems = excusedRowEntryProblems(listed, npoints, TransformOracleTest::isHeadSection);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("row 4 of c.head forward"), problems.get(0));
        npoints.remove(sectionKey("b.head", true));
        problems = excusedRowEntryProblems(listed, npoints, TransformOracleTest::isHeadSection);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("b.head forward, which is not an oracle section"), problems.get(0));
        // d.map belongs to another gate.
        assertEquals(
                1,
                excusedRowEntryProblems(listed, npoints, TransformOracleTest::isSimplifySection)
                        .size());
    }

    // ------------------------------------------------------------------
    // transform_tests
    // ------------------------------------------------------------------

    @Test
    void bareMappingAllAxesAngular() {
        Loaded l = loadFixture("simplify/matrix_diagonal_to_zoom.map");
        OracleSection s = OracleFile.parseText(TOL_LINE + "[x.map nin=2 nout=2 dir=forward]\n", "test")
                .get(0);
        boolean[] flags = angularFlags(l.map, s);
        assertTrue(flags[0] && flags[1]);
        assertEquals(2, flags.length);
    }

    /** A FrameSet's plain Frames do not wrap their axes, so none of its output axes is angular. */
    @Test
    void framesetOfPlainFramesHasNoAngularAxes() {
        FrameSet fs = new FrameSet(new Frame(2));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), new Frame(2));
        for (String dir : new String[] {"forward", "inverse"}) {
            OracleSection s = OracleFile.parseText(TOL_LINE + "[x.ast nin=2 nout=2 dir=" + dir + "]\n", "test")
                    .get(0);
            boolean[] flags = angularFlags(fs, s);
            assertEquals(2, flags.length);
            assertFalse(flags[0] || flags[1], dir);
        }
    }

    /**
     * The longitude axis of a {@code .head} fixture's celestial current Frame is angular; the GRID
     * axes of its base Frame, a plain Frame that normalises nothing, are not.
     */
    @Test
    void skyframeLongitudeIsCyclicPixelIsNot() {
        Loaded l = loadFixture(CELESTIAL_HEAD);
        assertNull(l.failure);
        boolean[] fwd = angularFlags(
                l,
                OracleFile.parseText(TOL_LINE + "[galactic-tan.head nin=2 nout=2 dir=forward]\n", "test")
                        .get(0));
        assertTrue(fwd[0] || fwd[1], "at least one axis cyclic (sky longitude)");
        boolean[] inv = angularFlags(
                l,
                OracleFile.parseText(TOL_LINE + "[galactic-tan.head nin=2 nout=2 dir=inverse]\n", "test")
                        .get(0));
        assertFalse(inv[0] || inv[1], "pixel axes are not cyclic");
    }

    @Test
    void transformReproducesOracleShape() throws TransformFailure {
        Loaded l = loadFixture("simplify/matrix_diagonal_to_zoom.map");
        OracleSection s = OracleFile.parseText(
                        TOL_LINE + "[x.map nin=2 nout=2 dir=forward]\n 1 3 0 0\n 2 4 0 0\n", "test")
                .get(0);
        double[][] out = transformSection(l.map, s, s.inputs());
        assertEquals(2, out.length);
        assertEquals(2, out[0].length);
    }

    // ------------------------------------------------------------------
    // check_tests
    // ------------------------------------------------------------------

    @Test
    void goldenFlagsOutOfToleranceAxis() {
        double[][] live = {{1.0, 2.0}};
        double[][] recorded = {{1.0, 2.5}};
        List<AxisMiss> misses = compareGolden(
                CheckKind.GOLDEN_FWD, live, recorded, new boolean[] {false}, 0L, GOLDEN_RTOL, GOLDEN_ATOL);
        assertEquals(1, misses.size());
        assertEquals(1, misses.get(0).row);
        assertEquals(0, misses.get(0).axis);
    }

    @Test
    void equivSkipsPointsWhereRecordedDisagree() {
        // Row 0 disagrees in the record (outside the shared domain) and live: skipped. Row 1 agrees
        // in the record but not live: flagged.
        double[][] mapRef = {{10.0, 1.0}};
        double[][] simpRef = {{20.0, 1.0}};
        double[][] mapLive = {{10.0, 1.0}};
        double[][] simpLive = {{20.0, 9.0}};
        List<AxisMiss> misses = compareEquiv(mapLive, simpLive, mapRef, simpRef, new boolean[] {false}, 1e-9, 1e-9);
        assertEquals(1, misses.size());
        assertEquals(1, misses.get(0).row);
    }

    @Test
    void rtripExcusesOnlyRowsTheReferenceAlsoFails() {
        // A 1-axis identity, so a recovered point forward-maps to itself.
        UnitMap map = new UnitMap(1);
        OracleSection fwd = OracleFile.parseText(
                        TOL_LINE + "[synthetic.head nin=1 nout=1 dir=forward]\n 10 10\n 20 20\n 30 30\n 40 40\n",
                        "test")
                .get(0);
        // Row 0: live recovers (pass). Row 1: live wrong, reference recovers (miss). Row 2: live
        // wrong, reference wrong too (excused). Row 3: live recovers, reference wrong (pass).
        double[][] invLive = {{10.0, 99.0, 99.0, 40.0}};
        double[][] invRef = {{10.0, 20.0, 77.0, 55.0}};
        List<AxisMiss> misses = compareRtrip(map, fwd, invLive, invRef, fwd.rtripRtol, fwd.rtripAtol);
        assertEquals(1, misses.size(), "only row 1 may be reported");
        assertEquals(1, misses.get(0).row);
    }

    // ------------------------------------------------------------------
    // driver_tests
    // ------------------------------------------------------------------

    @Test
    void runsFramesetsOracleWithoutPanicking() {
        assertEquals(
                353,
                runOracle("framesets", committedOverrides(), committedOracleRows())
                        .size());
    }

    // ------------------------------------------------------------------
    // Gates
    // ------------------------------------------------------------------

    /**
     * SlaMap sections: those of the simplify corpus whose file name contains {@code sla}. C pairs
     * none of them for the round trip and 31 for equivalence.
     */
    @Test
    void slamapSectionsMatchOracle() {
        List<String> problems =
                gate(Collections.singletonList("simplify_fixtures"), TransformOracleTest::isSlamapSection, 136, 0, 31);
        assertTrue(problems.isEmpty(), "SlaMap gate:\n" + String.join("\n", problems));
    }

    /** {@code .head} sections, all in {@code headers.oracle}. */
    @Test
    void headSectionsMatchOracle() {
        List<String> problems =
                gate(Collections.singletonList("headers"), TransformOracleTest::isHeadSection, 277, 137, 0);
        assertTrue(problems.isEmpty(), "head gate:\n" + String.join("\n", problems));
    }

    /**
     * Every other section: the rest of the simplify corpus and every native dump. Rust's gate
     * leaves out the 24 sections of the 12 fixtures its port cannot transform (11 bare Frames, and
     * a FrameSet with a Frame in a Mapping slot); here a Frame is a Mapping, as in C, so the gate
     * covers 1848 sections, and the round trip pairs the 12 inverse sections of those fixtures
     * besides Rust's 162.
     */
    @Test
    void simplifySectionsMatchOracle() {
        List<String> problems = gate(
                Arrays.asList("simplify_fixtures", "framesets"),
                TransformOracleTest::isSimplifySection,
                1848,
                RTRIP_SIMPLIFY,
                304);
        assertTrue(problems.isEmpty(), "simplify gate:\n" + String.join("\n", problems));
    }

    private static final int RTRIP_SIMPLIFY = 174;

    @Test
    void everyOracleSectionIsGated() {
        List<String> ungated = new ArrayList<>();
        int total = 0;
        for (String oracle : ORACLES) {
            for (OracleSection s : parseOracle(oracle)) {
                total++;
                if (!isHeadSection(s.path) && !isSlamapSection(s.path) && !isSimplifySection(s.path)) {
                    ungated.add(s.path);
                }
            }
        }
        assertTrue(ungated.isEmpty(), "sections no gate covers: " + ungated);
        assertEquals(2261, total);
    }

    /**
     * A fixture deferred for want of classes still cannot be loaded, so that it is noticed when
     * its classes are ported.
     */
    @Test
    void deferredFixturesStillFailToLoad() {
        List<String> nowLoading = new ArrayList<>();
        for (String oracle : ORACLES) {
            for (Map.Entry<String, String> e :
                    Deferred.load(deferredName(oracle)).entrySet()) {
                boolean measured =
                        e.getValue().startsWith("needs ") || e.getValue().startsWith("encoding ");
                if (measured && loadFixture(e.getKey()).map != null) {
                    nowLoading.add(e.getKey() + " (" + e.getValue() + ")");
                }
            }
        }
        assertTrue(nowLoading.isEmpty(), "these now load; rerun with -Dast.writeDeferred=true: " + nowLoading);
    }

    /** Every deferred list names fixtures of its own oracle, so a stale entry cannot sit unnoticed. */
    @Test
    void deferredListsNameOracleFixtures() {
        for (String oracle : ORACLES) {
            List<String> present = new ArrayList<>();
            for (OracleSection s : parseOracle(oracle)) {
                present.add(s.path);
            }
            for (String key : Deferred.load(deferredName(oracle)).keySet()) {
                assertTrue(present.contains(key), deferredName(oracle) + ".txt names " + key + ", not in " + oracle);
            }
        }
    }

    /**
     * The rows where C's own recorded inverse does not recover the forward input are exactly
     * {@link #RTRIP_REFERENCE_GAPS_LICENSED}, from the oracle files alone.
     */
    @Test
    void referenceRtripGapsAreExactlyTheRecordedSet() {
        Map<String, List<Integer>> found = new TreeMap<>();
        for (String oracle : ORACLES) {
            OracleSection prevFwd = null;
            for (OracleSection s : parseOracle(oracle)) {
                boolean grid = hasExt(s.path, ".head") || hasExt(s.path, ".ast");
                if (!s.forward && grid && pairsAsRtrip(prevFwd, s)) {
                    double[][] fwdOut = prevFwd.outputs();
                    double[][] invRef = s.outputs();
                    for (int row = 0; row < prevFwd.npoint(); row++) {
                        boolean fbad = false;
                        for (double[] col : fwdOut) {
                            fbad |= col[row] == CValues.BAD;
                        }
                        if (!fbad && !referenceRoundTrips(prevFwd, invRef, row, s.rtripRtol, s.rtripAtol)) {
                            found.computeIfAbsent(s.path, k -> new ArrayList<>())
                                    .add(row);
                        }
                    }
                }
                if (s.forward) {
                    prevFwd = s;
                }
            }
        }
        assertEquals(RTRIP_REFERENCE_GAPS_LICENSED, found);
    }
}
