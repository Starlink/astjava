package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Deferred;
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
 * {@code error-set-readonly}) run here; {@code excluded_cases_are_in_the_c_table} has no
 * counterpart, since none of those is excluded.
 *
 * <p>A case naming a write encoding ({@code write_enc}) is checked by C writing the FrameSet read to
 * that encoding, which must refuse it, and to FITS-WCS, which must accept it; {@code
 * FitsChan.write} is out of scope, so those cases are run up to the read and the writes are left
 * out. A case whose read stops at something out of scope ({@code UnsupportedOperationException})
 * cannot be compared with C, and is in {@link #EXCLUDED} with the reason measured from the
 * exception; {@code port_gap_cases_still_differ} becomes {@code excludedCasesStillDiffer}, which
 * checks every excluded case still differs from C, for that reason where it is measured.
 */
class FitsChanErrorsTest {

    /**
     * Cases that do not match C, each with why: {@code needs <class>} where the read stops at an
     * out-of-scope class, otherwise the gap the case exposes.
     */
    private static final Map<String, String> EXCLUDED = excludedCases();

    private static Map<String, String> excludedCases() {
        Map<String, String> m = new LinkedHashMap<>();
        // A spectral CTYPE is reached after the celestial axes are decoded (C WcsMapFrm calls
        // WcsSpectral after WcsCelestial), so these reads stop at the SpecFrame before C's error
        // or warning is checked.
        for (String name : Arrays.asList(
                "distortion-sip-on-axis3",
                "noctype-missing",
                "aips-reject-wave",
                "aips++-reject-wave",
                "aips-reject-topocent",
                "aips++-reject-topocent",
                "error-unknown-spectral-algorithm")) {
            m.put(name, "needs SpecFrame");
        }
        return Collections.unmodifiableMap(m);
    }

    /** The number of cases run: every case of C's table but those excluded. */
    private static final int RUNNABLE = 27;

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
        AstException readError = null;
        if (!c.get("skip_read").asBoolean()) {
            try {
                fc.read();
            } catch (AstException e) {
                readError = e;
            } catch (UnsupportedOperationException e) {
                String reason = Deferred.unavailableReason(e.getMessage());
                return "read " + (reason == null ? "unavailable" : reason) + ": " + e.getMessage();
            }
        }
        if (expect != null) {
            if (readError == null) {
                return "expected status " + expect + " but there was no error";
            }
            return expect == readError.getStatus()
                    ? null
                    : "expected status " + expect + ", got " + readError.getMessage();
        }
        // C clears an error from the read of any other case: a warning case checks the warnings
        // whatever the read did.
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
            return null;
        }
        // A write_enc case goes on in C to write the FrameSet read (testfitschanerrors.c
        // test_bad_header); FitsChan.write is out of scope, so that is left out.
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
            if (EXCLUDED.containsKey(c.get("name").asText())) {
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

    /**
     * Translation of Rust {@code port_gap_cases_still_differ}: every excluded case is a row of C's
     * table and still differs from C, a {@code needs} case because its read stops at that class, so
     * that the list shrinks when a gap closes.
     */
    @Test
    void excludedCasesStillDiffer() {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode c : ProbeData.load("fitschanerrors").get("CASES")) {
            byName.put(c.get("name").asText(), c);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : EXCLUDED.entrySet()) {
            JsonNode c = byName.get(e.getKey());
            if (c == null) {
                problems.add(e.getKey() + " is not a row of C's table");
                continue;
            }
            String err = check(c);
            if (err == null) {
                problems.add(e.getKey() + " now matches C; remove it from EXCLUDED. Reason on record: " + e.getValue());
            } else if (e.getValue().startsWith("needs ") && !err.startsWith("read " + e.getValue() + ":")) {
                problems.add(e.getKey() + " is excluded as \"" + e.getValue() + "\" but gives: " + err);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
