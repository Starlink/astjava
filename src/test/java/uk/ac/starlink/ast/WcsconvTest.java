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

/**
 * Translation of Rust {@code tests/test_wcsconv.rs}: the rows of {@code fixtures/wcsconv/cases.txt},
 * each of which C's {@code ast_tester/wcsconverter.c} runs by reading an input (a FITS header
 * through a FitsChan whose attributes are set first, or a native dump) and writing the Object read
 * with an encoding, compared with C's output.
 *
 * <p>Every row is run or listed, with its reason, in {@code deferred/wcsconv.txt}, and the list is
 * checked both ways ({@code everyRowMatchesCOrIsListed}, for Rust's generated per-row tests, and
 * {@code listedRowsStillFail}, for {@code native_read_excluded_entries_still_fail}): a listed row
 * must still give the reason listed, and a row not listed must match C. Run with {@code
 * -Dast.writeDeferred=true} to rewrite the list from what the rows give.
 *
 * <ul>
 *   <li>A row whose output is written with a FITS encoding or DSS needs {@code FitsChan.write} and
 *       cannot run at all; there are {@value #WRITE_ROWS} of them, each listed as {@value #WRITE}.
 *   <li>A {@code native} row's output is the cards {@code FitsChan.write} gives with the NATIVE
 *       encoding, so no {@code native} row can be compared with C's output. Its input is still read
 *       as C reads it: a read that stops at an out-of-scope class or encoding gives that reason
 *       ({@code needs <Class>} or {@code encoding <name>}), and a read that fails otherwise fails the
 *       test, since C reads every input. A FrameSet read from FITS-WCS cards is listed as {@value
 *       #SIMPLIFY}, since C's output holds it with the Mapping C simplified ({@code WcsMapFrm}), which
 *       this port does not; any other is listed as {@value #WRITE}. Reading C's output back instead
 *       does not give C's Object: the cards hold numbers rounded to FitsDigits (the {@code timj}
 *       Epoch is 2002.3822 in them, 2002.3821786502001 in the Object).
 *   <li>An {@code ast} row's output is the Object's native text, compared with C's as the Rust test
 *       compares it (numbers to 4 ULP, or to {@code astEQUAL} for an {@code astequal} row). One that
 *       differs only in the FrameSet's Mappings is listed as {@value #SIMPLIFY}; the texts without
 *       the Mappings must match.
 * </ul>
 */
class WcsconvTest {

    /** The reason of a row whose output needs {@code FitsChan.write}. */
    static final String WRITE = "out-of-scope method FitsChan.write";

    /** The reason of a row whose output holds a FrameSet whose Mapping C simplified. */
    static final String SIMPLIFY = "needs simplification";

    /** The number of rows whose output is written with a FITS encoding or DSS. */
    static final int WRITE_ROWS = 85;

    /** One row of the manifest. */
    private static final class Row {
        final String name;
        final String input;
        final String expected;
        final String encoding;
        final String attrs;
        final boolean astEqual;

        Row(List<String> f) {
            name = f.get(0);
            input = f.get(1);
            expected = f.get(2);
            encoding = f.get(3);
            attrs = f.size() > 4 ? f.get(4) : "";
            String mode = f.size() > 5 ? f.get(5).toLowerCase(java.util.Locale.ROOT) : "";
            // MODERN-JAVA(21): switch expression
            switch (mode) {
                case "":
                case "yes":
                    // Upstream makes no string comparison for "yes"; no row has it, and it is
                    // compared as strictly as a blank one.
                    astEqual = false;
                    break;
                case "astequal":
                    astEqual = true;
                    break;
                default:
                    throw new IllegalArgumentException("wcsconv/cases.txt: unknown compare mode " + mode);
            }
        }

        boolean needsWrite() {
            return !"native".equalsIgnoreCase(encoding) && !"ast".equalsIgnoreCase(encoding);
        }
    }

    /** The rows of {@code wcsconv/cases.txt}, fields separated by '|'. */
    private static List<Row> manifest() {
        List<Row> rows = new ArrayList<>();
        for (String line : CValues.lines(Fixtures.read("wcsconv/cases.txt"))) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            int start = 0;
            for (int i = 0; i <= t.length(); i++) {
                if (i == t.length() || t.charAt(i) == '|') {
                    fields.add(t.substring(start, i).trim());
                    start = i + 1;
                }
            }
            if (fields.size() >= 4) {
                rows.add(new Row(fields));
            }
        }
        return rows;
    }

    /** The Object read from a row's input, and the encoding it was read with (null for a native dump). */
    private static final class Input {
        final AstObject obj;
        final String encoding;

        Input(AstObject obj, String encoding) {
            this.obj = obj;
            this.encoding = encoding;
        }
    }

    /**
     * The Object {@code wcsconverter.c} reads from the input of {@code row}: a FITS header through a
     * FitsChan with the row's attributes set first and CDMatrix set to its value, or, if that gives
     * nothing, the file read as a native dump.
     */
    private static Input readInput(Row row) {
        String text = Fixtures.read(row.input);
        if (row.input.endsWith(".ast")) {
            return new Input(ChannelText.read(text), null);
        }
        FitsChan fc = new FitsChan();
        if (!row.attrs.isEmpty()) {
            fc.set(row.attrs);
        }
        for (String line : CValues.lines(text)) {
            fc.putFits(line, false);
        }
        if (!fc.test("CDMatrix")) {
            fc.setCDMatrix(fc.getCDMatrix());
        }
        fc.clear("Card");
        String encoding = fc.getEncoding();
        AstObject obj = fc.read();
        return obj != null ? new Input(obj, encoding) : new Input(ChannelText.read(text), null);
    }

    /**
     * The class an AstException from reading a native description names as one the library cannot
     * load ({@code ObjectRegistry}: "unknown AST class: X"), or null.
     */
    private static String unknownClass(AstException e) {
        String marker = "unknown AST class: ";
        String msg = String.valueOf(e.getMessage());
        int i = msg.indexOf(marker);
        if (i < 0) {
            return null;
        }
        int start = i + marker.length();
        int end = start;
        while (end < msg.length() && Character.isJavaIdentifierPart(msg.charAt(end))) {
            end++;
        }
        return msg.substring(start, end);
    }

    private static boolean equivalent(Row row, String got, String want) {
        return row.astEqual ? CValues.nativeStringsAstEqual(got, want) : CValues.nativeStringsEquivalent(got, want, 4);
    }

    /**
     * What {@code row} gives: null if its output matches C's, otherwise the reason it cannot.
     *
     * @throws AssertionError if the read fails where C's succeeds, or an {@code ast} row's output
     *     differs from C's other than in the FrameSet's Mappings
     */
    private static String outcome(Row row) {
        boolean ast = "ast".equalsIgnoreCase(row.encoding);
        if (row.needsWrite() || (!ast && row.input.endsWith(".ast"))) {
            return WRITE;
        }
        Input in;
        try {
            in = readInput(row);
        } catch (UnsupportedOperationException e) {
            return FitsHeaders.outOfScope(e);
        } catch (AstException e) {
            String cls = unknownClass(e);
            if (cls != null) {
                return "needs " + cls;
            }
            throw new AssertionError("C reads " + row.input + ", but here the read fails: " + e.getMessage(), e);
        }
        if (!ast) {
            return "FITS-WCS".equals(in.encoding) ? SIMPLIFY : WRITE;
        }
        String got = ChannelText.dump(in.obj);
        String want = Fixtures.read(row.expected);
        if (equivalent(row, got, want)) {
            return null;
        }
        String gotFrames = FitsHeaders.withoutMappings(got);
        String wantFrames = FitsHeaders.withoutMappings(want);
        if (!equivalent(row, gotFrames, wantFrames)) {
            throw new AssertionError(
                    "the texts without Mappings differ:\n--- Java\n" + gotFrames + "--- C\n" + wantFrames);
        }
        return SIMPLIFY;
    }

    /** {@link #outcome}, with any failure given as a string starting "FAIL". */
    private static String measure(Row row) {
        try {
            return outcome(row);
        } catch (AssertionError | AstException e) {
            return "FAIL " + e.getMessage();
        }
    }

    @Test
    void everyRowMatchesCOrIsListed() {
        Map<String, String> listed = Deferred.load("wcsconv");
        Map<String, String> measured = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Row row : manifest()) {
            names.add(row.name);
            String got = measure(row);
            String reason = listed.get(row.name);
            if (got != null && got.startsWith("FAIL ")) {
                failed.add(row.name + ": " + got.substring(5));
            } else if (got == null && reason != null) {
                failed.add(row.name + " is listed as \"" + reason + "\" but matches C; remove it");
            } else if (got != null && !got.equals(reason)) {
                failed.add(row.name + " gives \"" + got + "\" but is listed as \"" + reason + "\"");
            }
            if (got != null && !got.startsWith("FAIL ")) {
                measured.put(row.name, got);
            }
        }
        for (String name : listed.keySet()) {
            if (!names.contains(name)) {
                failed.add(name + " is listed but is not a row of wcsconv/cases.txt");
            }
        }
        if (Deferred.writeRequested()) {
            Deferred.write(
                    "wcsconv",
                    Arrays.asList(
                            "Rows of fixtures/wcsconv/cases.txt that do not match C:",
                            "<row name> | <reason>",
                            "Rewritten by WcsconvTest with -Dast.writeDeferred=true."),
                    measured);
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }

    @Test
    void listedRowsStillFail() {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Row row : manifest()) {
            rows.put(row.name, row);
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> e : Deferred.load("wcsconv").entrySet()) {
            Row row = rows.get(e.getKey());
            if (row == null) {
                stale.add(e.getKey() + ": not a row of wcsconv/cases.txt");
            } else if (!Fixtures.root().resolve(row.input).toFile().isFile()) {
                stale.add(e.getKey() + ": its input " + row.input + " is missing");
            } else if (measure(row) == null) {
                stale.add(e.getKey() + ": matches C, though listed as \"" + e.getValue() + "\"");
            }
        }
        assertTrue(stale.isEmpty(), String.join("\n", stale));
    }

    @Test
    void writeRowsCannotRunAndArePinned() {
        Map<String, String> listed = Deferred.load("wcsconv");
        int n = 0;
        for (Row row : manifest()) {
            if (row.needsWrite()) {
                n++;
                assertEquals(WRITE, listed.get(row.name), row.name);
            }
        }
        assertEquals(WRITE_ROWS, n, "rows whose output needs FitsChan.write");
    }
}
