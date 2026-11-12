package uk.ac.starlink.ast.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for the C transform oracles ({@code fixtures/oracle/*.oracle}), a port of the parser in
 * the Rust repository's {@code tests/test_transform_oracle.rs}.
 *
 * <p>Section headers look like {@code [<relpath>  nin=N nout=M dir=forward]}; data rows are
 * {@code nin} inputs then {@code nout} outputs, with {@code BAD} for C's bad value. A malformed row
 * is an error, so a truncated oracle cannot pass on reduced coverage.
 */
public final class OracleFile {

    private static final String[] TOL_KEYS = {"rtol", "atol", "equiv_rtol", "equiv_atol", "rtrip_rtol", "rtrip_atol"};

    private OracleFile() {}

    /** Parses the oracle file at {@code path}. */
    public static List<OracleSection> parse(Path path) {
        try {
            return parseText(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses oracle text.
     *
     * @param text the oracle text
     * @param what a name for the text, used in error messages
     * @throws IllegalArgumentException if the text is malformed
     */
    public static List<OracleSection> parseText(String text, String what) {
        List<OracleSection> out = new ArrayList<>();
        double[] tol = null;
        String path = null;
        int nin = 0;
        int nout = 0;
        boolean forward = true;
        List<double[]> rows = new ArrayList<>();
        int lineNo = 0;
        for (String line : CValues.lines(text)) {
            lineNo++;
            if (line.startsWith("# tol:")) {
                tol = parseTol(line.substring("# tol:".length()), what, lineNo);
                continue;
            }
            if (line.startsWith("#") || CValues.isBlank(line)) {
                continue;
            }
            if (line.startsWith("[")) {
                if (path != null) {
                    out.add(new OracleSection(path, nin, nout, forward, rows.toArray(new double[0][]), tol));
                }
                if (tol == null) {
                    throw new IllegalArgumentException(what + ":" + lineNo + ": section before the tolerance line");
                }
                String inside = line.substring(1);
                if (inside.endsWith("]")) {
                    inside = inside.substring(0, inside.length() - 1);
                }
                String[] toks = CValues.splitWhitespace(inside);
                path = toks[0];
                Integer nn = null;
                Integer no = null;
                forward = true;
                for (int i = 1; i < toks.length; i++) {
                    if (toks[i].startsWith("nin=")) {
                        nn = Integer.valueOf(toks[i].substring(4));
                    } else if (toks[i].startsWith("nout=")) {
                        no = Integer.valueOf(toks[i].substring(5));
                    } else if (toks[i].startsWith("dir=")) {
                        forward = !"inverse".equals(toks[i].substring(4));
                    }
                }
                if (nn == null || no == null) {
                    throw new IllegalArgumentException(what + ":" + lineNo + ": bad section header: " + line);
                }
                nin = nn;
                nout = no;
                rows = new ArrayList<>();
                continue;
            }
            if (path == null) {
                throw new IllegalArgumentException(what + ":" + lineNo + ": data row before any section header");
            }
            String[] toks = CValues.splitWhitespace(line);
            if (toks.length != nin + nout) {
                throw new IllegalArgumentException(what + ":" + lineNo + ": malformed data row in [" + path + "]: got "
                        + toks.length + " of " + (nin + nout) + " columns");
            }
            double[] row = new double[toks.length];
            for (int i = 0; i < toks.length; i++) {
                row[i] = parseDouble(toks[i], what, lineNo);
            }
            rows.add(row);
        }
        if (path != null) {
            out.add(new OracleSection(path, nin, nout, forward, rows.toArray(new double[0][]), tol));
        }
        return out;
    }

    private static double parseDouble(String tok, String what, int lineNo) {
        if ("BAD".equals(tok)) {
            return CValues.BAD;
        }
        Double v = CValues.parseRustFloat(tok);
        if (v == null) {
            throw new IllegalArgumentException(what + ":" + lineNo + ": unparsable token \"" + tok + "\"");
        }
        return v;
    }

    private static double[] parseTol(String spec, String what, int lineNo) {
        double[] tol = new double[TOL_KEYS.length];
        boolean[] seen = new boolean[TOL_KEYS.length];
        for (String tok : CValues.splitWhitespace(spec)) {
            int eq = tok.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(what + ":" + lineNo + ": bad tolerance \"" + tok + "\"");
            }
            String key = tok.substring(0, eq);
            for (int i = 0; i < TOL_KEYS.length; i++) {
                if (TOL_KEYS[i].equals(key)) {
                    tol[i] = parseDouble(tok.substring(eq + 1), what, lineNo);
                    seen[i] = true;
                }
            }
        }
        for (int i = 0; i < TOL_KEYS.length; i++) {
            if (!seen[i]) {
                throw new IllegalArgumentException(what + ":" + lineNo + ": tolerance line lacks " + TOL_KEYS[i]);
            }
        }
        return tol;
    }
}
