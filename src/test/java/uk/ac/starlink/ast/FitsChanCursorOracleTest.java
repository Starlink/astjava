package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Deferred;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_cursor_oracle.rs}: the card functions against C
 * (how {@code putFits}, {@code putCards}, {@code findFits}, {@code delFits}, {@code emptyFits}, the
 * typed getters and setters, the source, the sink, SourceFile and SinkFile use and move the current
 * card, and the attributes Card, CardName, CardType, CardComm, Ncard and Nkey).
 *
 * <p>Each case of {@code oracle/fitschan_cursor.json} (from {@code
 * tools/oracle_fixtures/fitschan_cursor_probe.c}) is a script of operations C ran, with the
 * transcript C printed: the result of each operation and then the state. The same script is run
 * here, through the same string attribute interface the probe uses, and the transcripts compared.
 *
 * <p>Cases whose scripts read from the FitsChan ({@code R}, {@code RN} and {@code PW}) run when
 * their reads need only the NATIVE and FITS-WCS encodings. A read that reaches another foreign
 * encoding (FITS-PC, FITS-IRAF, FITS-AIPS, FITS-AIPS++, FITS-CLASS or DSS), whose read is out of
 * scope, throws {@code UnsupportedOperationException}; the number of such cases is pinned by {@link
 * #WAITING_FOR_ANOTHER_ENCODING}. A read that stops at another out-of-scope class (such as {@code
 * needs SpecFrame}) must be in {@link #EXCLUDED} with that reason, measured from the exception.
 * Cases whose scripts write to the
 * FitsChan ({@code W}, {@code WF}, {@code WFT}, {@code WC}, {@code WCF}, {@code RW}, {@code RCIW}
 * and {@code RSW}) need {@code FitsChan.write}, which is out of scope, as do the Rust tests {@code
 * excluded_cases_still_differ} (whose list holds a writing case) and {@code
 * native_full_1_matches_c_but_for_process_counts} (listed in {@code deferred/rust-tests.txt}).
 */
class FitsChanCursorOracleTest {

    /** The operations that write to the FitsChan. */
    private static final Set<String> WRITES =
            new HashSet<>(Arrays.asList("W", "WF", "WFT", "WC", "WCF", "RW", "RCIW", "RSW"));

    /** The operations that only read from the FitsChan. */
    private static final Set<String> READS = new HashSet<>(Arrays.asList("R", "RN", "PW"));

    /**
     * The number of cases run here: those that write no Object, are not excluded, and whose reads
     * need only the NATIVE and FITS-WCS encodings.
     */
    private static final int RUNNABLE = 143;

    /** The number of cases whose reads reach the read of an encoding other than NATIVE and FITS-WCS. */
    private static final int WAITING_FOR_ANOTHER_ENCODING = 5;

    /**
     * Cases read differently from C, with the reason; {@link #excludedReadCasesStillDiffer} fails
     * once one matches, or, for a {@code needs} reason, once the read no longer stops for want of
     * that class.
     */
    private static final Map<String, String> EXCLUDED = excludedCases();

    private static Map<String, String> excludedCases() {
        Map<String, String> m = new LinkedHashMap<>();
        // Headers with a spectral axis, whose FITS-WCS read builds a SpecFrame.
        for (String name : Arrays.asList(
                "dist_sip_axis3",
                "dist_zpx_three",
                "sao_three_ctypes",
                "iwc_on_spectral",
                "iwc_off_spectral",
                "iwc_on_spectral_cunit")) {
            m.put(name, "needs SpecFrame");
        }
        // Their transcripts show the FrameSet read, whose pixel to world Mapping C simplifies
        // (fitschan.c WcsMapFrm returns astSimplify of it).
        for (String name : Arrays.asList("iwc_off", "iwc_on", "iwc_on_alt", "iwc_on_linear", "iwc_on_cunit")) {
            m.put(name, "needs simplification");
        }
        return Collections.unmodifiableMap(m);
    }

    @TempDir
    Path tmp;

    /** The probe's source: the cards of {@code NEW|c1~c2~#~c3}, {@code #} ending a batch. */
    private static final class ScriptSource implements Iterator<String> {
        private String rest;

        ScriptSource(String text) {
            rest = text;
        }

        @Override
        public boolean hasNext() {
            if (rest.isEmpty()) {
                return false;
            }
            if (rest.charAt(0) == '#') {
                rest = rest.startsWith("#~") ? rest.substring(2) : rest.substring(1);
                return false;
            }
            return true;
        }

        @Override
        public String next() {
            if (rest.isEmpty() || rest.charAt(0) == '#') {
                throw new NoSuchElementException();
            }
            int tilde = rest.indexOf('~');
            String card = tilde < 0 ? rest : rest.substring(0, tilde);
            rest = tilde < 0 ? "" : rest.substring(tilde + 1);
            return card;
        }
    }

    /** A FitsChan whose sink prints each card as {@code |sink card}, as the probe's does. */
    private static final class LogFitsChan extends FitsChan {
        final List<String> log = new ArrayList<>();

        LogFitsChan(Iterator<String> source) {
            super(source);
        }

        @Override
        protected void sink(String line) {
            log.add("|sink " + line + "\n");
        }
    }

    private static String codeName(AstException e) {
        int s = e.getStatus();
        if (s == AstException.AST__BDFTS) {
            return "Bdfts";
        } else if (s == AstException.AST__FTCNV) {
            return "Ftcnv";
        } else if (s == AstException.AST__FUNDEF) {
            return "Fundef";
        } else if (s == AstException.AST__BDFMT) {
            return "Bdfmt";
        } else if (s == AstException.AST__ATTIN) {
            return "Attin";
        } else if (s == AstException.AST__RDERR) {
            return "Rderr";
        } else if (s == AstException.AST__WRERR) {
            return "Wrerr";
        } else {
            return "Other";
        }
    }

    private static String g17(double v) {
        return CFormat.format("%.17g", v);
    }

    private Path tmpPath(String name) {
        return tmp.resolve(name);
    }

    private static void state(FitsChan fc, StringBuilder out) {
        int icard = fc.getI("Card");
        out.append("card=")
                .append(icard)
                .append(" ncard=")
                .append(fc.getI("Ncard"))
                .append(" nkey=")
                .append(fc.getI("Nkey"))
                .append(" type=")
                .append(fc.getI("CardType"))
                .append(" name=")
                .append(fc.getC("CardName"))
                .append(" comm=[")
                .append(fc.getC("CardComm"))
                .append("]\n");
        fc.clear("Card");
        try {
            String card;
            while ((card = fc.findFits("%f", true)) != null) {
                out.append('|').append(card).append('\n');
            }
        } catch (AstException e) {
            out.append("|err ").append(codeName(e)).append('\n');
        }
        fc.setI("Card", icard);
    }

    private static String found(Object v, String text) {
        return v == null ? "= nf\n" : "= " + text + "\n";
    }

    /** One operation of a script: its result, as the probe prints it. */
    private String op(FitsChan fc, String op) throws IOException {
        List<String> f = fields(op);
        String a1 = arg(f, 1);
        String a2 = arg(f, 2);
        String a3 = arg(f, 3);
        String a4 = arg(f, 4);
        boolean ow = "1".equals(a1);
        String name = "-".equals(a1) ? null : a1;
        switch (f.get(0)) {
            case "P":
                fc.putFits(a2, ow);
                return "";
            case "PC": {
                StringBuilder cards = new StringBuilder();
                List<String> parts = splitOn(op.substring(3), '~');
                for (int i = 0; i < parts.size(); i++) {
                    cards.append(parts.get(i));
                    if (i + 1 < parts.size()) {
                        while (cards.length() % 80 != 0) {
                            cards.append(' ');
                        }
                    }
                }
                fc.putCards(cards.toString());
                return "";
            }
            case "SC":
                fc.setI("Card", Integer.parseInt(a1));
                return "";
            case "CC":
                fc.clear("Card");
                return "";
            case "TC":
                return "= " + (fc.test("Card") ? 1 : 0) + "\n";
            case "F": {
                String card = fc.findFits(a2, ow);
                return card == null ? "= nf\n" : "= found [" + card + "]\n";
            }
            case "D":
                fc.delFits();
                return "";
            case "E":
                fc.emptyFits();
                return "";
            case "GI": {
                Integer v = fc.getFitsI(name);
                return found(v, String.valueOf(v));
            }
            case "GK": {
                Long v = fc.getFitsK(name);
                return found(v, String.valueOf(v));
            }
            case "GF": {
                Double v = fc.getFitsF(name);
                return found(v, v == null ? "" : g17(v));
            }
            case "GS": {
                String v = fc.getFitsS(name);
                return found(v, "[" + v + "]");
            }
            case "GCN": {
                String v = fc.getFitsCN(name);
                return found(v, "[" + v + "]");
            }
            case "GL": {
                Boolean v = fc.getFitsL(name);
                return found(v, v != null && v ? "1" : "0");
            }
            case "GCF": {
                double[] v = fc.getFitsCF(name);
                return found(v, v == null ? "" : g17(v[0]) + " " + g17(v[1]));
            }
            case "GCI": {
                int[] v = fc.getFitsCI(name);
                return found(v, v == null ? "" : v[0] + " " + v[1]);
            }
            case "SI":
                fc.setFits(a2, Integer.parseInt(a3), a4, ow);
                return "";
            case "SK":
                fc.setFitsK(a2, Long.parseLong(a3), a4, ow);
                return "";
            case "SCN":
                fc.setFitsContinue(a2, a3, a4, ow);
                return "";
            case "SCF":
                fc.setFits(a2, Double.parseDouble(a3), Double.parseDouble(a4), arg(f, 5), ow);
                return "";
            case "SCI":
                fc.setFits(a2, Integer.parseInt(a3), Integer.parseInt(a4), arg(f, 5), ow);
                return "";
            case "SF":
                fc.setFits(a2, Double.parseDouble(a3), a4, ow);
                return "";
            case "SS":
                fc.setFits(a2, a3, a4, ow);
                return "";
            case "SL":
                fc.setFits(a2, Integer.parseInt(a3) != 0, a4, ow);
                return "";
            case "SU":
                fc.setFitsU(a2, a3, ow);
                return "";
            case "CM":
                fc.setFitsCM(a2, ow);
                return "";
            case "ENC":
                fc.setC("Encoding", a1);
                return "";
            case "CL":
                fc.setI("Clean", Integer.parseInt(a1));
                return "";
            case "RF":
                fc.retainFits();
                return "";
            case "TF": {
                Boolean has = fc.testFitsThere(name);
                return "= " + (has == null ? "nf" : has ? "1" : "0") + "\n";
            }
            case "AI":
                return "= " + fc.getI(a1) + " " + (fc.test(a1) ? 1 : 0) + "\n";
            case "AD":
                return "= " + g17(fc.getD(a1)) + " " + (fc.test(a1) ? 1 : 0) + "\n";
            case "AC":
                return "= [" + fc.getC(a1) + "] " + ("AllWarnings".equals(a1) ? 0 : fc.test(a1) ? 1 : 0) + "\n";
            case "ASC":
                fc.setC(a1, a2);
                return "";
            case "WN": {
                KeyMap km = fc.warnings();
                int n = km == null ? 0 : km.mapSize();
                StringBuilder sb = new StringBuilder("= ").append(n).append('\n');
                for (int i = 1; i <= n; i++) {
                    sb.append('|').append(km.mapGet0C("Warning_" + i)).append('\n');
                }
                return sb.toString();
            }
            case "ASI":
                fc.setI(a1, Integer.parseInt(a2));
                return "";
            case "ASD":
                fc.setD(a1, Double.parseDouble(a2));
                return "";
            case "AX":
                fc.clear(a1);
                return "";
            case "FD":
                fc.setI("FitsDigits", Integer.parseInt(a1));
                return "";
            case "QCD":
                return "= " + fc.getI("CDMatrix") + " " + (fc.test("CDMatrix") ? 1 : 0) + "\n";
            case "SCD":
                fc.setI("CDMatrix", Integer.parseInt(a1));
                return "";
            case "XCD":
                fc.clear("CDMatrix");
                return "";
            case "QE":
                return "= " + fc.getC("Encoding") + "\n";
            case "RDF":
                fc.readFits();
                return "";
            case "WRF":
                fc.writeFits();
                return "";
            case "MKF":
                Files.write(
                        tmpPath(a1),
                        op.substring(5 + a1.length()).replace('~', '\n').getBytes(StandardCharsets.UTF_8));
                return "";
            case "SRF":
                fc.setC("SourceFile", tmpPath(a1).toString());
                return "";
            case "SKF":
                fc.setC("SinkFile", tmpPath(a1).toString());
                return "";
            case "CATF":
                return catFile(a1);
            case "CPN":
                return "= " + fc.copy().getI("Ncard") + "\n";
            case "PW":
                fc.purgeWCS();
                return "";
            case "R": {
                AstObject obj = fc.read();
                if (obj == null) {
                    return "= null\n";
                }
                String cls = obj.astClass();
                boolean named =
                        "FrameSet".equals(cls) || "ZoomMap".equals(cls) || "Frame".equals(cls) || "CmpMap".equals(cls);
                return "= " + (named ? cls : "another class") + "\n";
            }
            case "RN": {
                AstObject obj = fc.read();
                if (obj == null) {
                    return "= null\n";
                }
                StringBuilder sb = new StringBuilder("= ")
                        .append("FrameSet".equals(obj.astClass()) ? "FrameSet" : "another class")
                        .append('\n');
                for (String line : CValues.lines(ChannelText.dump(obj))) {
                    sb.append('|').append(line).append('\n');
                }
                return sb.toString();
            }
            default:
                throw new IllegalArgumentException("unknown op " + op);
        }
    }

    private String catFile(String name) throws IOException {
        Path p = tmpPath(name);
        if (!Files.exists(p)) {
            return "|nofile\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : CValues.lines(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))) {
            sb.append("|file ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> fields(String op) {
        return splitOn(op, '|');
    }

    /** The parts of {@code s} between occurrences of {@code sep}, empty ones included. */
    private static List<String> splitOn(String s, char sep) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int at;
        while ((at = s.indexOf(sep, start)) >= 0) {
            out.add(s.substring(start, at));
            start = at + 1;
        }
        out.add(s.substring(start));
        return out;
    }

    private static String arg(List<String> f, int i) {
        return i < f.size() ? f.get(i) : "";
    }

    /** The transcript this library gives for a case's operations. */
    private String transcript(List<String> ops) throws IOException {
        StringBuilder out = new StringBuilder();
        FitsChan fc;
        LogFitsChan logged = null;
        int first = 0;
        if (ops.get(0).startsWith("NEW|")) {
            logged = new LogFitsChan(new ScriptSource(ops.get(0).substring(4)));
            fc = logged;
            first = 1;
        } else {
            fc = new FitsChan();
        }
        String sinkName = null;
        for (String op : ops.subList(first, ops.size())) {
            out.append("> ").append(op).append('\n');
            if (op.startsWith("SKF|")) {
                sinkName = op.substring(4);
            }
            String res;
            try {
                res = op(fc, op);
            } catch (AstException e) {
                res = "= err " + codeName(e) + "\n";
            }
            if (logged != null) {
                out.append(String.join("", logged.log));
                logged.log.clear();
            }
            out.append(res);
            state(fc, out);
        }
        out.append("> annul\n");
        try {
            fc.close();
        } catch (AstException e) {
            out.append("= err ").append(codeName(e)).append('\n');
        }
        if (logged != null) {
            out.append(String.join("", logged.log));
        }
        if (sinkName != null) {
            out.append(catFile(sinkName));
            Files.deleteIfExists(tmpPath(sinkName));
        }
        return out.toString();
    }

    private static List<String> ops(JsonNode c) {
        List<String> ops = new ArrayList<>();
        for (JsonNode o : c.get(1)) {
            ops.add(o.asText());
        }
        return ops;
    }

    private static boolean uses(List<String> ops, Set<String> codes) {
        for (String op : ops) {
            int bar = op.indexOf('|');
            if (codes.contains(bar < 0 ? op : op.substring(0, bar))) {
                return true;
            }
        }
        return false;
    }

    /** The case called {@code name}. */
    private static JsonNode findCase(String name) {
        for (JsonNode c : ProbeData.load("fitschan_cursor").get("CASES")) {
            if (name.equals(c.get(0).asText())) {
                return c;
            }
        }
        throw new IllegalArgumentException("no case " + name);
    }

    @Test
    void excludedReadCasesStillDiffer() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : EXCLUDED.entrySet()) {
            JsonNode c = findCase(e.getKey());
            boolean needs = e.getValue().startsWith("needs ") && !"needs simplification".equals(e.getValue());
            String got;
            try {
                got = transcript(ops(c));
            } catch (UnsupportedOperationException ex) {
                String reason = Deferred.unavailableReason(ex.getMessage());
                if (!e.getValue().equals(reason)) {
                    problems.add(e.getKey() + " is excluded as \"" + e.getValue() + "\" but its read gives: "
                            + ex.getMessage());
                }
                continue;
            }
            if (needs) {
                problems.add(e.getKey() + " is excluded as \"" + e.getValue() + "\" but its read no longer stops;"
                        + " compare it with C and remove it from EXCLUDED");
            } else if (c.get(2).asText().equals(got)) {
                problems.add(e.getKey() + " now matches C; remove it from EXCLUDED (" + e.getValue() + ")");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void cardFunctionsMatchTheCLibrary() throws IOException {
        List<String> failed = new ArrayList<>();
        List<String> waitingCases = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("fitschan_cursor").get("CASES")) {
            List<String> ops = ops(c);
            String name = c.get(0).asText();
            if (uses(ops, WRITES) || EXCLUDED.containsKey(name)) {
                continue;
            }
            String want = c.get(2).asText();
            String got;
            try {
                got = transcript(ops);
            } catch (UnsupportedOperationException e) {
                assertTrue(uses(ops, READS), name + ": " + e.getMessage());
                String reason = Deferred.unavailableReason(e.getMessage());
                if (reason != null && reason.startsWith("encoding ") && !"encoding FITS-WCS".equals(reason)) {
                    waitingCases.add(name + " (" + reason + ")");
                } else {
                    failed.add(name + ": the read stops (" + e.getMessage() + "); list it in EXCLUDED with \"" + reason
                            + "\"");
                }
                continue;
            }
            run++;
            if (!got.equals(want)) {
                List<String> g = CValues.lines(got);
                List<String> w = CValues.lines(want);
                int i = 0;
                while (i < g.size() && i < w.size() && g.get(i).equals(w.get(i))) {
                    i++;
                }
                failed.add(name + ": first difference at line " + (i + 1) + "\n--- java\n" + context(g, i) + "\n--- C\n"
                        + context(w, i));
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n\n", failed));
        assertEquals(RUNNABLE, run, "cases run");
        assertEquals(
                WAITING_FOR_ANOTHER_ENCODING,
                waitingCases.size(),
                "cases waiting for the read of another encoding: " + waitingCases);
    }

    private static String context(List<String> lines, int at) {
        return String.join("\n", lines.subList(Math.max(0, at - 3), Math.min(lines.size(), at + 3)));
    }
}
