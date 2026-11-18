package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Deferred;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.NativeClasses;
import uk.ac.starlink.ast.testsupport.OracleFile;
import uk.ac.starlink.ast.testsupport.OracleSection;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_simplify_regression.rs::simplify_regression}: the rows of
 * {@code ast_tester/fixtures/simplify/cases.txt}, the manifest C's own simplify harness reads, each
 * naming a Mapping ({@code .map}) and C's simplification of it ({@code .simp}).
 *
 * <p>For each row the input is read and simplified, after reducing a FrameSet to its base-to-current
 * Mapping unless the reference is itself a FrameSet (the reference was then made by simplifying the
 * FrameSet). The simplified Mapping, read back from its native dump, must be equal to the
 * reference by {@code astEqual}, as C's harness finds it ({@code ast_tester/test_simplify.tap}
 * runs {@code ast_astequal} on every row). The simplified Mapping's native dump is compared with
 * the reference by the row's fourth field:
 *
 * <ul>
 *   <li>blank: numbers within 4 units in the last place, every other byte equal;
 *   <li>{@code astequal}: numbers within C's {@code astEQUAL};
 *   <li>{@code yes}: no comparison, unless the row names one file as both input and reference; such
 *       a row asserts that simplification leaves the Mapping as it is, so the dumps are compared with
 *       their {@code IsSimp} lines removed, and the result must carry an {@code IsSimp} card unless
 *       it is a Frame (C's {@code astSimplify} records every Mapping it returns as simplified, but a
 *       Frame does not write the record, frame.c:5590).
 * </ul>
 *
 * <p>Simplifying must leave the input as C leaves it: its dump, apart from the records of
 * simplification C also writes on it ({@code IsSimp}, {@code ReSimp}), and what it computes for the
 * inputs of its oracle sections, are unchanged. Where the probe {@code
 * probes/simplify_input_probe.c} records C's dump of a row's input after simplification, the
 * input's dump, apart from those records, must be that instead: C's comparisons of CmpMaps can
 * change the Invert attribute of a component shared with the input (row {@code brad}).
 *
 * <p>The simplified Mapping must also transform the inputs of the input file's sections of {@code
 * oracle/simplify_fixtures.oracle} to C's recorded outputs, within the file's tolerances, as the
 * transform oracle compares a bare Mapping, with the overrides the oracle's overrides file states.
 * Where the reference is another file with a section of the same direction, the inputs and outputs
 * are those C recorded for the reference, from its section with the same inputs if it has one: C's
 * simplification can change what a Mapping gives outside the domain its parts assume (SphMaps
 * cancelling on angles outside the ranges a SphMap gives or on vectors that are not of unit length,
 * a WcsMap and its inverse cancelling on points the projection cannot transform, a PolyMap and its
 * inverse cancelling on points where the iterative inverse does not converge), and the simplified
 * Mapping is to transform as C's simplified Mapping does.
 *
 * <p>Every row matches C or is listed with its reason in {@code deferred/simplify-cases.txt}, and the
 * list is checked both ways. Run with {@code -Dast.writeDeferred=true} to rewrite it.
 */
class SimplifyRegressionTest {

    /** The deferred list. */
    private static final String DEFERRED = "simplify-cases";

    /** The rows of the manifest. */
    private static final int MANIFEST_ROWS = 517;

    /** The manifest, relative to the fixtures. */
    private static final String MANIFEST = "simplify/cases.txt";

    /** One row of the manifest. */
    static final class Row {
        final String name;
        final String input;
        final String reference;
        final String compare;

        Row(String name, String input, String reference, String compare) {
            this.name = name;
            this.input = input;
            this.reference = reference;
            this.compare = compare;
        }
    }

    /** The rows of {@code cases.txt}, in order. */
    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (String line : CValues.lines(Fixtures.read(MANIFEST))) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] f = t.split("\\|", -1);
            if (f.length != 4) {
                throw new IllegalStateException("malformed manifest row: " + line);
            }
            rows.add(new Row(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim()));
        }
        return rows;
    }

    /** The oracle sections of each fixture, keyed by path. */
    private static Map<String, List<OracleSection>> oracleSections() {
        Map<String, List<OracleSection>> out = new LinkedHashMap<>();
        for (OracleSection s :
                OracleFile.parse(Fixtures.root().resolve("oracle").resolve("simplify_fixtures.oracle"))) {
            out.computeIfAbsent(s.path, k -> new ArrayList<>()).add(s);
        }
        return out;
    }

    /** The reason of a row whose Mappings are all in scope but whose simplification differs from C's. */
    static final String SIMPLIFY = "needs simplification";

    /**
     * C's dumps of rows' inputs after simplification, as {@code <row>/input_after} cases of the
     * probe {@code probes/simplify_input_probe.c}.
     */
    private static final Map<String, String> INPUT_AFTER = ProbeOutput.load("simplify_input_probe");

    /**
     * The reason a failing row is listed with: the out-of-scope classes its input or reference
     * names, or {@value #SIMPLIFY} if there are none, since the row then fails in a part of the
     * simplifier not yet ported.
     */
    static String reason(Row r) {
        String needs = NativeClasses.needs(Fixtures.read(r.input) + "\n" + Fixtures.read(r.reference));
        return needs == null ? SIMPLIFY : needs;
    }

    /** Whether a dump carries an {@code IsSimp} card on its outermost Object (indented by four spaces). */
    private static boolean hasTopLevelIsSimp(String dump) {
        for (String line : CValues.lines(dump)) {
            if (line.startsWith("    IsSimp") && !line.startsWith("     ")) {
                return true;
            }
        }
        return false;
    }

    /** What differs between the row's simplified input and C's, or null if nothing does. */
    static String failure(Row r, Map<String, List<OracleSection>> oracle, TransformOracleTest.Overrides overrides) {
        String refText = Fixtures.read(r.reference);
        Mapping simplified;
        Mapping input;
        String dump;
        boolean equal;
        try {
            AstObject inObj = ChannelText.read(Fixtures.read(r.input));
            AstObject refObj = ChannelText.read(refText);
            input = inputMapping(inObj, refObj);
            if (input == null) {
                return "input is a " + inObj.getClass().getSimpleName() + ", not a Mapping";
            }
            List<String> inputBefore = withoutRecords(ChannelText.dump(input));
            simplified = input.simplify();
            List<String> inputAfter = withoutRecords(ChannelText.dump(input));
            String cAfter = INPUT_AFTER.get(r.name + "/input_after");
            if (cAfter != null) {
                if (!withoutRecords(cAfter).equals(inputAfter)) {
                    return "simplify() left the input's dump other than C leaves it";
                }
            } else if (!sameButForClearedAlSimp(inputBefore, inputAfter)) {
                return "simplify() changed the input's dump";
            }
            // Both dumps are taken before anything is transformed, since a transformation can
            // store values that are written: a 1-dimensional SpecMap keeps each frequency
            // correction factor it works out, as C's does (specmap.c FrameChange). What the input
            // computed before it was simplified is found from a second reading of it.
            dump = ChannelText.dump(simplified);
            // C's harness compares the reference and the simplified Mapping, both read from their
            // dumps, with astEqual (ast_tester/test_simplify.tap, ast_astequal.c), the reference
            // first: a dump does not carry everything astEqual looks at, such as a base Frame
            // written only through Frm.
            equal = refObj.astEqual(ChannelText.read(dump));
            List<double[][]> transformsBefore = inputTransforms(
                    inputMapping(ChannelText.read(Fixtures.read(r.input)), refObj), oracle.get(r.input));
            List<double[][]> transformsAfter = inputTransforms(input, oracle.get(r.input));
            for (int i = 0; i < transformsBefore.size(); i++) {
                if (!Arrays.deepEquals(transformsBefore.get(i), transformsAfter.get(i))) {
                    return "simplify() changed what the input computes";
                }
            }
        } catch (AstException | UnsupportedOperationException e) {
            return "read or simplify: " + e.getMessage();
        }

        if (!equal) {
            return "astEqual finds the simplified Mapping, read from its dump, unequal to C's";
        }
        boolean sameFile = r.input.equals(r.reference);
        if (sameFile) {
            boolean frame = simplified instanceof Frame;
            boolean card = hasTopLevelIsSimp(dump);
            if (card && frame) {
                return "IsSimp card on a Frame (frame.c:5590)";
            }
            if (!card && !frame) {
                return "simplify() wrote no IsSimp card";
            }
        }
        String problem = null;
        if (sameFile || !"yes".equals(r.compare)) {
            String got = sameFile ? CValues.stripIsSimp(dump) : dump;
            String want = SerialisationCorpusTest.stripLeadingComments(refText);
            want = sameFile ? CValues.stripIsSimp(want) : want;
            if (sameFile && got.endsWith("\n") != want.endsWith("\n")) {
                got = got.endsWith("\n") ? got : got + "\n";
                want = want.endsWith("\n") ? want : want + "\n";
            }
            boolean matched = "astequal".equals(r.compare)
                    ? CValues.nativeStringsAstEqual(got, want)
                    : CValues.nativeStringsEquivalent(got, want, 4);
            if (!matched) {
                problem = "dump differs from C's: " + firstDifference(got, want);
            }
        }
        String transform = transformFailure(r, simplified, oracle, overrides);
        if (transform != null) {
            problem = problem == null ? transform : problem + "; " + transform;
        }
        return problem;
    }

    /**
     * The Mapping a row simplifies: the input itself, or a FrameSet's base-to-current Mapping when
     * the reference is not a FrameSet; null if the input is not a Mapping.
     */
    private static Mapping inputMapping(AstObject inObj, AstObject refObj) {
        if (inObj instanceof FrameSet && !(refObj instanceof FrameSet)) {
            return ((FrameSet) inObj).getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        } else if (inObj instanceof Mapping) {
            return (Mapping) inObj;
        } else {
            return null;
        }
    }

    /**
     * The lines of a dump without its records of simplification, {@code IsSimp} and {@code
     * ReSimp}, which C's {@code astSimplify} also writes on the Mapping it is given and on its
     * shared components.
     */
    private static List<String> withoutRecords(String dump) {
        List<String> out = new ArrayList<>();
        for (String line : CValues.lines(dump)) {
            String t = line.trim();
            if (!t.startsWith("IsSimp =") && !t.startsWith("ReSimp =")) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * Whether the dump {@code after} is the dump {@code before} with no change but {@code AlSimp}
     * lines removed: cmpmap.c {@code Simplify} clears AllowSimplify on each component it joins
     * into a new CmpMap, among them components it shares with the input (probe {@code
     * mathmap_probe.c}, case {@code simplify_input_after}), but sets it on none of them.
     */
    private static boolean sameButForClearedAlSimp(List<String> before, List<String> after) {
        int j = 0;
        for (String line : before) {
            if (j < after.size() && line.equals(after.get(j))) {
                j++;
            } else if (!line.trim().startsWith("AlSimp =")) {
                return false;
            }
        }
        return j == after.size();
    }

    /**
     * The outputs {@code map} gives for the inputs of each of {@code sections}, null for a section
     * it cannot transform.
     */
    private static List<double[][]> inputTransforms(Mapping map, List<OracleSection> sections) {
        List<double[][]> out = new ArrayList<>();
        if (sections == null) {
            return out;
        }
        for (OracleSection s : sections) {
            try {
                out.add(TransformOracleTest.transformSection(map, s, s.inputs()));
            } catch (TransformOracleTest.TransformFailure e) {
                out.add(null);
            }
        }
        return out;
    }

    /** The first line where two dumps differ. */
    private static String firstDifference(String got, String want) {
        List<String> g = CValues.lines(got);
        List<String> w = CValues.lines(want);
        for (int i = 0; i < Math.min(g.size(), w.size()); i++) {
            if (!CValues.nativeStringsEquivalent(g.get(i), w.get(i), 4)) {
                return "line " + (i + 1) + " got \"" + g.get(i).trim() + "\", want \""
                        + w.get(i).trim() + "\"";
            }
        }
        return "line count " + g.size() + ", want " + w.size();
    }

    /**
     * The section of the row's reference with the direction and inputs of the input file's section
     * {@code s}, failing that the reference's first section of that direction, or {@code s} itself
     * if the reference is the input file or has no section of that direction.
     */
    private static OracleSection referenceSection(Row r, OracleSection s, Map<String, List<OracleSection>> oracle) {
        if (r.reference.equals(r.input)) {
            return s;
        }
        OracleSection sameDirection = null;
        for (OracleSection ref : oracle.getOrDefault(r.reference, new ArrayList<>())) {
            if (ref.forward == s.forward) {
                if (Arrays.deepEquals(ref.inputs(), s.inputs())) {
                    return ref;
                }
                if (sameDirection == null) {
                    sameDirection = ref;
                }
            }
        }
        return sameDirection == null ? s : sameDirection;
    }

    /**
     * Where {@code simplified} fails to reproduce C's recorded outputs for the input file's oracle
     * sections, or those of the reference for the same inputs ({@link #referenceSection}), or null
     * if it reproduces them all.
     */
    private static String transformFailure(
            Row r,
            Mapping simplified,
            Map<String, List<OracleSection>> oracle,
            TransformOracleTest.Overrides overrides) {
        for (OracleSection in : oracle.getOrDefault(r.input, new ArrayList<>())) {
            OracleSection s = referenceSection(r, in, oracle);
            if (overrides.goldenOff(s.path, s.forward)) {
                continue;
            }
            long skip = overrides.goldenSkipRows(s.path, s.forward);
            double[][] live;
            try {
                live = TransformOracleTest.transformSection(simplified, s, s.inputs());
            } catch (TransformOracleTest.TransformFailure e) {
                return (s.forward ? "forward " : "inverse ") + e.getMessage();
            }
            boolean[] angular = TransformOracleTest.angularFlags(simplified, s);
            double[][] want = s.outputs();
            for (int c = 0; c < want.length; c++) {
                for (int p = 0; p < want[c].length; p++) {
                    if (p < 64 && (skip & (1L << p)) != 0) {
                        continue;
                    }
                    if (!TransformOracleTest.axisMatch(live[c][p], want[c][p], s.rtol, s.atol, angular[c])) {
                        return (s.forward ? "forward" : "inverse") + " row " + p + " axis " + c + ": got " + live[c][p]
                                + ", C " + want[c][p];
                    }
                }
            }
        }
        return null;
    }

    @Test
    void rowCountIsTheManifests() {
        assertEquals(MANIFEST_ROWS, rows().size());
    }

    @Test
    void everyRowMatchesCOrIsListed() {
        Map<String, List<OracleSection>> oracle = oracleSections();
        TransformOracleTest.Overrides overrides = TransformOracleTest.committedOverrides();
        if (Deferred.writeRequested()) {
            Map<String, String> entries = new LinkedHashMap<>();
            for (Row r : rows()) {
                if (failure(r, oracle, overrides) != null) {
                    entries.put(r.name, reason(r));
                }
            }
            Deferred.write(
                    DEFERRED,
                    Arrays.asList(
                            "Rows of fixtures/simplify/cases.txt whose simplification does not match C:",
                            "<row name> | <reason>",
                            "Rewritten by SimplifyRegressionTest with -D" + Deferred.WRITE_PROPERTY + "=true."),
                    entries);
            return;
        }
        Map<String, String> deferred = Deferred.load(DEFERRED);
        List<String> problems = new ArrayList<>();
        for (Row r : rows()) {
            if (deferred.containsKey(r.name)) {
                continue;
            }
            String f = failure(r, oracle, overrides);
            if (f != null) {
                problems.add(r.name + ": " + f);
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " rows differ from C:\n" + String.join("\n", problems));
    }

    @Test
    void listedRowsStillFail() {
        Map<String, List<OracleSection>> oracle = oracleSections();
        TransformOracleTest.Overrides overrides = TransformOracleTest.committedOverrides();
        Map<String, String> deferred = Deferred.load(DEFERRED);
        Map<String, Row> byName = new LinkedHashMap<>();
        for (Row r : rows()) {
            byName.put(r.name, r);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : deferred.entrySet()) {
            Row r = byName.get(e.getKey());
            if (r == null) {
                problems.add(e.getKey() + ": not a row of the manifest");
                continue;
            }
            if (failure(r, oracle, overrides) == null) {
                problems.add(r.name + ": matches C; remove it from the list");
                continue;
            }
            String want = reason(r);
            if (!want.equals(e.getValue())) {
                problems.add(r.name + ": is listed as \"" + e.getValue() + "\" but its reason is \"" + want
                        + "\"; rerun with -D" + Deferred.WRITE_PROPERTY + "=true");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
