package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fixture_full_dump_oracle.rs}: every native fixture read
 * through a Channel and written with Full = 1, against C's text. With Full = 1 C writes every value
 * a class's Dump writes (channel.c {@code Use}): set values, and unset ones as comments, both those
 * it calls helpful and those it does not; every {@code IsA} marker; and a Frame's Axes in full
 * (frame.c {@code Dump}).
 *
 * <p>The comparison leaves out what describes the C process rather than the Object: C's Object part
 * also writes the unset {@code RefCnt} and {@code Nobj}, counts of pointers to the Object and of
 * Objects of its class, which this library does not have and the probe drops; and the unset {@code
 * Seed} of a MathMap is chosen at random when the MathMap is made, so both sides replace its value
 * with {@code <random>}.
 *
 * <p>The text C writes for all the fixtures runs to megabytes, so {@code
 * oracle/fixture_full_dump.json}, from {@code tools/oracle_fixtures/fixture_full_dump_probe.c},
 * holds the 64-bit FNV-1a hash of each fixture's text, which is compared exactly. A fixture either
 * matches or is listed, with the reason, in {@code deferred/full-dumps.txt}, and a listed fixture
 * must differ; with {@code -Dast.writeDeferred=true} the test rewrites that list instead (see
 * {@link Deferred}). Rust's exclusion of {@code serialisation/frame_in_map_slot.ast}, a CmpFrame in
 * a Mapping slot, has no counterpart: a Frame is a Mapping here, as in C.
 *
 * <p>{@code write_full_dumps}, an ignored test writing the text for comparison by hand, is
 * deferred.
 */
class FixtureFullDumpOracleTest {

    private static final String DEFERRED = "full-dumps";

    /** The number of fixtures the probe hashed. */
    private static final int NFIXTURES = 1014;

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    private static final long FNV_PRIME = 0x100000001b3L;

    /** The probe's cases: fixture path relative to the fixture root, and C's hash. */
    private static Map<String, BigInteger> cases() {
        Map<String, BigInteger> out = new LinkedHashMap<>();
        for (JsonNode c : ProbeData.load("fixture_full_dump").get("CASES")) {
            String file = c.get(0).asText();
            out.put(file.startsWith("./") ? file.substring(2) : file, c.get(1).bigIntegerValue());
        }
        return out;
    }

    /**
     * The Objects in a fixture written with Full = 1, one after another, each line followed by a
     * newline, or null if a read fails.
     */
    static String fullDump(String relpath) {
        Channel input = ChannelText.reader(Fixtures.read(relpath));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Channel out = new Channel(null, buf);
        out.setFull(1);
        try {
            for (int i = 0; i < 10; i++) {
                AstObject obj = input.read();
                if (obj == null) {
                    break;
                }
                out.write(obj);
            }
        } catch (AstException | IOException e) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : CValues.lines(new String(buf.toByteArray(), StandardCharsets.UTF_8))) {
            sb.append(normalise(line)).append('\n');
        }
        return sb.toString();
    }

    /** {@code line} with the value of a MathMap's unset Seed replaced by {@code <random>}. */
    static String normalise(String line) {
        String defaultSeed = " \t# Default random number seed used";
        int at = line.indexOf("Seed = ");
        if (at >= 0 && line.startsWith("#") && line.endsWith(defaultSeed)) {
            return line.substring(0, at) + "Seed = <random>" + defaultSeed;
        }
        return line;
    }

    /** The 64-bit FNV-1a hash of the UTF-8 bytes of {@code text}, as the probe computes it. */
    static BigInteger fnv1a(String text) {
        long h = FNV_OFFSET;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h = (h ^ (b & 0xff)) * FNV_PRIME;
        }
        return new BigInteger(Long.toUnsignedString(h));
    }

    /** Whether the fixture's Full = 1 text differs from C's. */
    private static boolean differs(String relpath, BigInteger hash) {
        String text = fullDump(relpath);
        return text == null || !fnv1a(text).equals(hash);
    }

    /** Rewrites the deferred list, and gives the differences no missing class explains. */
    private static List<String> writeDeferred(Map<String, BigInteger> cases) {
        Map<String, String> old = Deferred.load(DEFERRED);
        Map<String, String> entries = new LinkedHashMap<>();
        List<String> unexplained = new ArrayList<>();
        for (Map.Entry<String, BigInteger> c : cases.entrySet()) {
            if (differs(c.getKey(), c.getValue())) {
                String reason = Deferred.newReason(NativeClasses.needs(Fixtures.read(c.getKey())), old.get(c.getKey()));
                entries.put(c.getKey(), reason);
                if (Deferred.UNEXPLAINED.equals(reason)) {
                    unexplained.add(c.getKey());
                }
            }
        }
        Deferred.write(
                DEFERRED,
                Arrays.asList(
                        "Native fixtures whose Full = 1 dump differs from C's:",
                        "<path relative to ast_tester/fixtures> | <reason>",
                        "Rewritten by FixtureFullDumpOracleTest with -D" + Deferred.WRITE_PROPERTY + "=true."),
                entries);
        return unexplained;
    }

    @Test
    void fullDumpsMatchC() {
        Map<String, BigInteger> cases = cases();
        List<String> problems = new ArrayList<>();
        if (Deferred.writeRequested()) {
            problems.addAll(writeDeferred(cases));
        } else {
            Map<String, String> deferred = Deferred.load(DEFERRED);
            for (Map.Entry<String, BigInteger> c : cases.entrySet()) {
                String relpath = c.getKey();
                boolean differs = differs(relpath, c.getValue());
                String reason = deferred.get(relpath);
                if (reason == null) {
                    if (differs) {
                        problems.add(relpath + " differs from C");
                    }
                } else if (!differs) {
                    problems.add(relpath + " is deferred (" + reason + ") but matches C; remove it from " + DEFERRED
                            + ".txt");
                } else {
                    String p = Deferred.reasonProblem(reason, NativeClasses.needs(Fixtures.read(relpath)));
                    if (p != null) {
                        problems.add(relpath + " " + p);
                    }
                }
            }
        }
        if (cases.size() != NFIXTURES) {
            problems.add("the probe data holds " + cases.size() + " fixtures, not " + NFIXTURES);
        }
        assertTrue(problems.isEmpty(), problems.size() + " fixtures differ from C:\n" + String.join("\n", problems));
    }

    /** Every deferred entry names a fixture the probe hashed. */
    @Test
    void excludedFixturesStillDiffer() {
        Map<String, BigInteger> cases = cases();
        for (String key : Deferred.load(DEFERRED).keySet()) {
            assertNotNull(cases.get(key), DEFERRED + ".txt names " + key + ", which the probe did not hash");
        }
    }

    @Test
    void fnv1aIsThe64BitHash() {
        assertEquals(new BigInteger("cbf29ce484222325", 16), fnv1a(""));
        assertEquals(new BigInteger("af63dc4c8601ec8c", 16), fnv1a("a"));
    }
}
