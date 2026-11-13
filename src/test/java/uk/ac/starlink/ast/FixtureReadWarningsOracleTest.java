package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Deferred;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.NativeClasses;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fixture_read_warnings_oracle.rs}: every native fixture
 * read through a Channel, as C reads it: the status of each read and the warnings it leaves for
 * items no class loader reads (channel.c {@code ClearValues}). C reads every fixture written by the
 * C library without a warning, so a warning here is an item a loader leaves unread that C's reads.
 * The fixtures C cannot read (card headers, and IntraMaps with no registered transformation) must
 * fail in the same way.
 *
 * <p>{@code oracle/fixture_read_warnings.json} comes from {@code
 * tools/oracle_fixtures/fixture_read_warnings_probe.c}, and lists only the fixtures C gives a
 * warning or an error for.
 *
 * <p>A fixture that holds a class the library cannot load is listed, with the reason, in {@code
 * deferred/read-warnings.txt}, and a listed fixture must still differ from C; with {@code
 * -Dast.writeDeferred=true} the test rewrites that list instead (see {@link Deferred}). Rust's
 * {@code EXCLUDED} has no counterpart: {@code frame_in_map_slot.ast}, a CmpFrame in a Mapping slot,
 * loads as in C, since a Frame is a Mapping here.
 */
class FixtureReadWarningsOracleTest {

    private static final String DEFERRED = "read-warnings";

    /** The number of native fixtures. */
    private static final int NFIXTURES = 1170;

    /** The native fixtures, as {@code ./}-prefixed paths relative to the fixture root, sorted. */
    private static List<String> fixtures() {
        Path root = Fixtures.root();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".map") || n.endsWith(".simp") || n.endsWith(".ast") || n.endsWith(".native");
                    })
                    .map(p -> "./" + root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** C's reads of each fixture the probe lists, those that fail or warn, as {@code [status, warnings]}. */
    private static Map<String, List<List<Object>>> cReads() {
        Map<String, List<List<Object>>> out = new HashMap<>();
        for (JsonNode c : ProbeData.load("fixture_read_warnings").get("CASES")) {
            List<List<Object>> reads = new ArrayList<>();
            for (JsonNode r : c.get(1)) {
                int status = r.get(0).asInt();
                List<String> warnings = new ArrayList<>();
                for (JsonNode w : r.get(1)) {
                    warnings.add(w.asText());
                }
                if (status != 0 || !warnings.isEmpty()) {
                    reads.add(Arrays.asList(status, warnings));
                }
            }
            out.put(c.get(0).asText(), reads);
        }
        return out;
    }

    /** Each read of {@code fixture}, up to the end or an error, listing only reads that fail or warn. */
    private static List<List<Object>> reads(String fixture) {
        Channel ch = ChannelText.reader(Fixtures.read(fixture.substring(2)));
        List<List<Object>> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int status = 0;
            boolean done;
            try {
                done = ch.read() == null;
            } catch (AstException e) {
                status = e.getStatus();
                done = true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            List<String> warnings = ch.warningTexts();
            if (status != 0 || !warnings.isEmpty()) {
                out.add(Arrays.asList(status, warnings));
            }
            if (done) {
                break;
            }
        }
        return out;
    }

    /** What differs from C for {@code fixture}, or null. */
    private static String difference(String fixture, Map<String, List<List<Object>>> c) {
        List<List<Object>> want = c.getOrDefault(fixture, new ArrayList<>());
        List<List<Object>> got = reads(fixture);
        return got.equals(want) ? null : "got " + got + ", C " + want;
    }

    @Test
    void readsMatchC() {
        List<String> files = fixtures();
        Map<String, List<List<Object>>> c = cReads();
        Map<String, String> old = Deferred.load(DEFERRED);
        List<String> problems = new ArrayList<>();
        if (Deferred.writeRequested()) {
            Map<String, String> entries = new LinkedHashMap<>();
            for (String file : files) {
                String diff = difference(file, c);
                if (diff != null) {
                    String reason =
                            Deferred.newReason(NativeClasses.needs(Fixtures.read(file.substring(2))), old.get(file));
                    entries.put(file, reason);
                    if (Deferred.UNEXPLAINED.equals(reason)) {
                        problems.add(file + ": " + diff);
                    }
                }
            }
            Deferred.write(
                    DEFERRED,
                    Arrays.asList(
                            "Native fixtures whose reads through a Channel differ from C's:",
                            "./<path relative to ast_tester/fixtures> | <reason>",
                            "Rewritten by FixtureReadWarningsOracleTest with -D" + Deferred.WRITE_PROPERTY + "=true."),
                    entries);
        } else {
            for (String file : files) {
                String diff = difference(file, c);
                String reason = old.get(file);
                if (reason == null) {
                    if (diff != null) {
                        problems.add(file + ": " + diff);
                    }
                } else if (diff == null) {
                    problems.add(file + " is deferred (" + reason + ") but reads as C does; remove it from " + DEFERRED
                            + ".txt");
                } else {
                    String p = Deferred.reasonProblem(reason, NativeClasses.needs(Fixtures.read(file.substring(2))));
                    if (p != null) {
                        problems.add(file + " " + p + " (" + diff + ")");
                    }
                }
            }
            for (String key : old.keySet()) {
                if (!files.contains(key)) {
                    problems.add(DEFERRED + ".txt names " + key + ", which is not a native fixture");
                }
            }
        }
        if (files.size() != NFIXTURES) {
            problems.add("found " + files.size() + " fixtures, not " + NFIXTURES);
        }
        assertTrue(problems.isEmpty(), problems.size() + " problems:\n" + String.join("\n", problems));
    }
}
