package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Deferred;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.NativeClasses;

/**
 * Translation of the native-corpus part of Rust {@code tests/test_serialisation.rs}: every
 * native-encoded fixture C wrote is read through a Channel and written again, and the text must
 * reproduce the fixture, numbers within {@value #NUMERIC_TOLERANCE_ULPS} units in the last place.
 * Reading a dump and writing it back is the identity whatever the dump holds, so any difference is
 * a fault in reading or writing.
 *
 * <p>The corpus is C's: {@code simplify/*.map} and {@code *.simp}, {@code wcsconv/framesets/*.ast},
 * and every {@code *.ast} under {@code serialisation/} and {@code programs/} (the set upstream's
 * {@code roundtrip_*} tests cover). A fixture either round-trips or is listed, with the reason, in
 * {@code deferred/serialisation.txt}, and a listed fixture must fail; with {@code
 * -Dast.writeDeferred=true} the tests rewrite that list instead (see {@link Deferred}).
 *
 * <p>Rust's {@code ROUND_TRIP_EXCLUDED} has no counterpart: {@code frame_in_map_slot.ast}, a
 * CmpFrame in a Mapping slot, loads as in C, since a Frame is a Mapping here, and the MocChan
 * fixtures are deferred with the other fixtures holding classes out of scope. The other tests of
 * {@code test_serialisation.rs}, which round-trip Objects made in the test, are translated in
 * {@link SerialisationTest}.
 */
class SerialisationCorpusTest {

    /**
     * The largest distance in units in the last place between two numbers written differently;
     * the C and Rust tests use the same budget for libm differences between platforms.
     */
    private static final long NUMERIC_TOLERANCE_ULPS = 4;

    private static final String DEFERRED = "serialisation";

    /** The fixtures of one sweep, relative to the fixture root, sorted. */
    private static List<String> fixtures(Path dir, boolean recurse, String... exts) {
        Path root = Fixtures.root();
        assertTrue(Files.isDirectory(dir), dir + " is missing; the corpus is committed, so this is a broken checkout");
        try (Stream<Path> s = recurse ? Files.walk(dir) : Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> hasExt(p, exts))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean hasExt(Path p, String... exts) {
        String name = p.getFileName().toString();
        for (String ext : exts) {
            if (name.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    static List<String> simplifyFixtures() {
        return fixtures(Fixtures.root().resolve("simplify"), false, "map", "simp");
    }

    static List<String> framesetFixtures() {
        return fixtures(Fixtures.root().resolve("wcsconv/framesets"), false, "ast");
    }

    static List<String> serialisationFixtures() {
        List<String> out = new ArrayList<>(fixtures(Fixtures.root().resolve("serialisation"), true, "ast"));
        out.addAll(fixtures(Fixtures.root().resolve("programs"), true, "ast"));
        Collections.sort(out);
        return out;
    }

    /**
     * Drops the leading run of comment lines, as upstream's round-trip driver does ({@code
     * cmake/run_roundtrip_test.cmake}): a fixture may start with a header describing it, which is
     * not part of the Object.
     */
    static String stripLeadingComments(String text) {
        List<String> lines = CValues.lines(text);
        int i = 0;
        while (i < lines.size() && lines.get(i).startsWith("#")) {
            i++;
        }
        String rest = String.join("\n", lines.subList(i, lines.size()));
        return text.endsWith("\n") ? rest + "\n" : rest;
    }

    /**
     * Reads the fixture at {@code relpath}, writes it again, and gives what differs, or null if the
     * text reproduces the fixture.
     */
    static String roundTripFailure(String relpath) {
        String text = Fixtures.read(relpath);
        String output;
        try {
            output = ChannelText.dump(ChannelText.read(text));
        } catch (AstException e) {
            return "read: " + e.getMessage();
        }
        String want = stripLeadingComments(text);
        String got = stripLeadingComments(output);
        if (CValues.nativeStringsEquivalent(got, want, NUMERIC_TOLERANCE_ULPS)) {
            return null;
        }
        List<String> g = CValues.lines(got);
        List<String> w = CValues.lines(want);
        for (int i = 0; i < Math.min(g.size(), w.size()); i++) {
            if (!CValues.nativeStringsEquivalent(g.get(i), w.get(i), NUMERIC_TOLERANCE_ULPS)) {
                return "line " + (i + 1) + " differs:\n  got:  \"" + g.get(i) + "\"\n  want: \"" + w.get(i) + "\"";
            }
        }
        return "line count differs: got " + g.size() + ", want " + w.size();
    }

    /** Rewrites the deferred list from every fixture of the three sweeps, and gives the unexplained failures. */
    private static List<String> writeDeferred() {
        Map<String, String> old = Deferred.load(DEFERRED);
        Map<String, String> entries = new LinkedHashMap<>();
        List<String> unexplained = new ArrayList<>();
        List<String> all = new ArrayList<>(simplifyFixtures());
        all.addAll(framesetFixtures());
        all.addAll(serialisationFixtures());
        for (String relpath : all) {
            String failure = roundTripFailure(relpath);
            if (failure != null) {
                String reason = Deferred.newReason(NativeClasses.needs(Fixtures.read(relpath)), old.get(relpath));
                entries.put(relpath, reason);
                if (Deferred.UNEXPLAINED.equals(reason)) {
                    unexplained.add(relpath + ": " + failure);
                }
            }
        }
        Deferred.write(
                DEFERRED,
                Arrays.asList(
                        "Native fixtures that do not round-trip through a Channel:",
                        "<path relative to ast_tester/fixtures> | <reason>",
                        "Rewritten by SerialisationCorpusTest with -D" + Deferred.WRITE_PROPERTY + "=true."),
                entries);
        return unexplained;
    }

    /**
     * One sweep: each fixture round-trips or is deferred, a deferred fixture fails with a current
     * reason, and the sweep holds {@code nfixtures} fixtures. Gives what is wrong, or with {@code
     * -Dast.writeDeferred=true} rewrites the deferred list and gives the failures it could not
     * explain.
     */
    private static List<String> sweep(List<String> relpaths, int nfixtures) {
        if (Deferred.writeRequested()) {
            return writeDeferred();
        }
        Map<String, String> deferred = Deferred.load(DEFERRED);
        List<String> problems = new ArrayList<>();
        for (String relpath : relpaths) {
            String failure = roundTripFailure(relpath);
            String reason = deferred.get(relpath);
            if (reason == null) {
                if (failure != null) {
                    problems.add(relpath + ": " + failure);
                }
            } else if (failure == null) {
                problems.add(
                        relpath + " is deferred (" + reason + ") but round-trips; remove it from " + DEFERRED + ".txt");
            } else {
                String p = Deferred.reasonProblem(reason, NativeClasses.needs(Fixtures.read(relpath)));
                if (p != null) {
                    problems.add(relpath + " " + p + " (" + failure + ")");
                }
            }
        }
        if (relpaths.size() != nfixtures) {
            problems.add("the sweep read " + relpaths.size() + " fixtures, not " + nfixtures);
        }
        return problems;
    }

    @Test
    void nativeByteExactRoundTripSimplifyFixtures() {
        List<String> problems = sweep(simplifyFixtures(), 880);
        assertTrue(problems.isEmpty(), "simplify_fixtures:\n" + String.join("\n", problems));
    }

    @Test
    void nativeByteExactRoundTripFramesetFixtures() {
        List<String> problems = sweep(framesetFixtures(), 139);
        assertTrue(problems.isEmpty(), "frameset_fixtures:\n" + String.join("\n", problems));
    }

    @Test
    void nativeByteExactRoundTripSerialisationFixtures() {
        List<String> problems = sweep(serialisationFixtures(), 34);
        assertTrue(problems.isEmpty(), "serialisation + programs:\n" + String.join("\n", problems));
    }

    /** Every deferred entry names a fixture of one of the sweeps. */
    @Test
    void roundTripExclusionsStillFail() {
        List<String> all = new ArrayList<>(simplifyFixtures());
        all.addAll(framesetFixtures());
        all.addAll(serialisationFixtures());
        for (String key : Deferred.load(DEFERRED).keySet()) {
            assertTrue(all.contains(key), DEFERRED + ".txt names " + key + ", which no sweep reads");
        }
    }

    @Test
    void stripsOnlyTheLeadingComments() {
        assertEquals("Begin X\n# kept\nEnd X\n", stripLeadingComments("# a\n# b\nBegin X\n# kept\nEnd X\n"));
        assertEquals("Begin X", stripLeadingComments("Begin X"));
    }
}
