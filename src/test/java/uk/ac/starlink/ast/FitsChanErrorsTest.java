package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_errors.rs}: FitsChan warnings and errors from
 * malformed headers, as C's {@code testfitschanerrors.c} {@code test_bad_header} checks them.
 * {@code oracle/fitschanerrors.json} is that program's table, from {@code
 * tools/oracle_fixtures/fitschanerrors_table.c}.
 *
 * <p>Each case sets the warning conditions, puts the cards one at a time and then {@code END},
 * rewinds, sets any attributes, reads the header, and then checks the expected error or warning.
 * The attributes are set through the string interface, as C's test sets them, so the cases the
 * Rust test leaves out for want of it ({@code error-bad-encoding}, {@code error-bad-altaxes} and
 * {@code error-set-readonly}) run here.
 *
 * <p>Left to Task 23: the cases that read the header (all but those that skip the read or fail
 * while setting an attribute), whose cards are all read with the FITS-WCS encoding, and {@code
 * port_gap_cases_still_differ}, whose case reads it.
 * {@code excluded_cases_are_in_the_c_table} has no counterpart, since no case is excluded.
 */
class FitsChanErrorsTest {

    /** The number of cases that neither read the header nor reach the read. */
    private static final int RUNNABLE = 6;

    private static String text(JsonNode c, String field) {
        JsonNode v = c.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** The status of an error code name, as the Rust table names it. */
    private static int status(JsonNode code) {
        String name = code.get("variant").asText();
        switch (name) {
            case "Rderr":
                return AstException.AST__RDERR;
            case "Badat":
                return AstException.AST__BADAT;
            case "Nowrt":
                return AstException.AST__NOWRT;
            case "Bdfts":
                return AstException.AST__BDFTS;
            default:
                throw new IllegalArgumentException("unknown error code " + name);
        }
    }

    /** C's {@code test_bad_header} up to the read; the first mismatch, or null. */
    private static String check(JsonNode c) {
        JsonNode code = c.get("expect_error");
        Integer expect = code == null || code.isNull() ? null : status(code);
        String warnCat = text(c, "warn_cat");
        FitsChan fc = new FitsChan();
        if (warnCat != null) {
            fc.setWarnings(warnCat);
        }
        try {
            for (String line : lines(c.get("cards").asText())) {
                int i = 0;
                while (i < line.length() && line.charAt(i) == ' ') {
                    i++;
                }
                if (i < line.length()) {
                    fc.putFits(line.substring(i), false);
                }
            }
            fc.putFits("END", false);
        } catch (AstException e) {
            return expect != null && expect == e.getStatus() ? null : "error during PutFits: " + e.getMessage();
        }
        fc.clear("Card");
        String attrs = text(c, "attrs");
        if (attrs != null) {
            try {
                fc.set(attrs);
            } catch (AstException e) {
                return expect != null && expect == e.getStatus()
                        ? null
                        : "error setting " + attrs + ": " + e.getMessage();
            }
        }
        if (!c.get("skip_read").asBoolean()) {
            throw new IllegalStateException(c.get("name").asText() + " reads the header");
        }
        if (expect != null) {
            return "expected status " + expect + " but there was no error";
        }
        if (warnCat != null) {
            KeyMap km = fc.warnings();
            if (km == null) {
                return "no warnings issued (expected '" + warnCat + "')";
            }
            String want = text(c, "expect_text");
            if (want != null) {
                boolean found = false;
                for (int i = 1; i <= km.mapSize(); i++) {
                    String v = km.mapGet0C("Warning_" + i);
                    found |= v != null && v.contains(want);
                }
                if (!found) {
                    return "warning text does not contain '" + want + "'";
                }
            }
        }
        return null;
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int nl;
        while ((nl = s.indexOf('\n', start)) >= 0) {
            out.add(s.substring(start, nl));
            start = nl + 1;
        }
        out.add(s.substring(start));
        return out;
    }

    @Test
    void badHeadersMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("fitschanerrors").get("CASES")) {
            boolean reads = !c.get("skip_read").asBoolean() && text(c, "attrs") == null;
            if (reads) {
                continue;
            }
            run++;
            String err = check(c);
            if (err != null) {
                failures.add(c.get("name").asText() + ": " + err);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(RUNNABLE, run, "cases run");
    }
}
