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
 * <p>Cases whose scripts read from the FitsChan ({@code R}, {@code RN}, {@code PW}, and {@code
 * RW}, {@code RCIW} and {@code RSW}, which write what they read back into it) run when their reads
 * and writes need only the classes of this library. A read or write that stops at an out-of-scope
 * class (such as {@code needs MathMap}) must be in {@link #EXCLUDED} with that reason, measured
 * from the exception. Cases whose scripts write an Object they make ({@code W},
 * {@code WF}, {@code WFT}, {@code WC} and {@code WCF}) run; {@code native_full_1}, whose Object
 * parts C writes with counts this library does not keep, is excluded and compared without them.
 */
class FitsChanCursorOracleTest {

    /** The operations that read from the FitsChan, and write what they read. */
    private static final Set<String> READS = new HashSet<>(Arrays.asList("R", "RN", "PW", "RW", "RCIW", "RSW"));

    /** The number of cases run here: those that are not excluded. */
    private static final int RUNNABLE = 257;

    /**
     * The start of the reason of a case whose transcript hangs on the last bit of an elementary
     * function of java.lang.Math, which is not C's libm and differs between platforms: such a case
     * is not reported when it matches C, since on another runtime it may not.
     */
    private static final String RUNTIME_MATH = "runtime Math:";

    /**
     * Cases read differently from C, with the reason; {@link #excludedReadCasesStillDiffer} fails
     * once one matches, unless its reason starts {@value #RUNTIME_MATH}, or, for a {@code needs}
     * reason, once the read no longer stops for want of that class.
     */
    private static final Map<String, String> EXCLUDED = excludedCases();

    private static Map<String, String> excludedCases() {
        Map<String, String> m = new LinkedHashMap<>();
        // The absolute description of the offset SkyFrame passes C's LogAxis test on its first
        // axis, so C builds the -LOG Mapping, which holds a MathMap; the description then fails
        // MakeIntWorld and is not written (lldb probe s2 of the final review fixes).
        m.put("alt_axes_alt_offset", "needs MathMap");
        // The Mapping C's FitLine fits for the offset coordinates, written without a projection,
        // is the same as this library's (probe s1, dumps equal); of the 101 points of each grid
        // axis, 81 and 67 differ, each first at a SphMap, SphMap inverse or TAN WcsMap inverse
        // given equal inputs (the components whose sines, cosines and arctangents the replays for
        // alt_axes_offset found 1 ULP from C's libm at equal arguments), and the slopes of the
        // differences (2e-18) are the differences of the fitted gradients.
        // runtimeMathCasesMatchCButForLicensedCards compares them.
        m.put("sky_offset_system_rw", RUNTIME_MATH + " SphMap and TAN WcsMap transforms 1 ULP from C's libm");
        // The Mapping C's FitLine fits for the primary description is the same as this library's
        // (probe a1), but its SphMaps and inverse TAN WcsMap take sines, cosines and arctangents
        // where java.lang.Math differs from C's libm by 1 ULP at the same arguments (probes a2 and
        // rep), so the fitted CDELT2 differs by 1 ULP. runtimeMathCasesMatchCButForLicensedCards
        // compares them.
        m.put("alt_axes_offset", RUNTIME_MATH + " SphMap and TAN WcsMap transforms 1 ULP from C's libm");
        m.put("alt_axes_offset_none", RUNTIME_MATH + " SphMap and TAN WcsMap transforms 1 ULP from C's libm");
        // Its transcript shows the FrameSet read. The rotation of the alternate description
        // (fitschan.c WcsNative, three astMtrRot calls) takes the sine of 2 deg - pi/2, for which
        // java.lang.Math gives -0x1.ffb0278bf0566p-1 and C's libm -0x1.ffb0278bf0567p-1 (measured
        // on aarch64, JDK 21 and macOS libm), so the forward matrix element M2 is
        // 0.9992386149554825 here and 0.99923861495548261 in C.
        m.put("iwc_on_alt", RUNTIME_MATH + " the sine of 2 deg - pi/2 differs from C's libm by 1 ULP");
        // With Full positive C also writes the unset RefCnt and Nobj of each Object part as comment
        // cards: counts of pointers to the Object and of Objects of its class, which this library
        // does not keep. nativeFull1MatchesCButForProcessCounts compares the rest.
        m.put("native_full_1", "RefCnt and Nobj are counts this library does not keep");
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
            case "W":
                return "= " + fc.write(new ZoomMap(2, 2.0)) + "\n";
            case "WF":
                return "= " + fc.write(new Frame(2)) + "\n";
            case "WFT": {
                Frame fr = new Frame(2);
                fr.setTitle(a1);
                return "= " + fc.write(fr) + "\n";
            }
            case "WC":
                return "= " + fc.write(new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 3.0), true)) + "\n";
            case "WCF":
                return "= " + fc.write(new CmpFrame(new Frame(1), new Frame(1))) + "\n";
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
            case "RW": {
                AstObject obj = fc.read();
                return obj == null ? "= null 0\n" : "= " + obj.astClass() + " " + fc.write(obj) + "\n";
            }
            case "RCIW": {
                AstObject obj = fc.read();
                if (obj == null) {
                    return "= null 0\n";
                }
                ((FrameSet) obj).getFrame(Integer.parseInt(a1)).clear("Ident");
                return "= " + obj.astClass() + " " + fc.write(obj) + "\n";
            }
            case "RSW": {
                AstObject obj = fc.read();
                fc.setC("Warnings", a1);
                return obj == null ? "= null 0\n" : "= " + obj.astClass() + " " + fc.write(obj) + "\n";
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
            boolean needs = e.getValue().startsWith("needs ");
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
            } else if (!e.getValue().startsWith(RUNTIME_MATH)
                    && c.get(2).asText().equals(got)) {
                problems.add(e.getKey() + " now matches C; remove it from EXCLUDED (" + e.getValue() + ")");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void cardFunctionsMatchTheCLibrary() throws IOException {
        List<String> failed = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("fitschan_cursor").get("CASES")) {
            List<String> ops = ops(c);
            String name = c.get(0).asText();
            if (EXCLUDED.containsKey(name)) {
                continue;
            }
            String want = c.get(2).asText();
            String got;
            try {
                got = transcript(ops);
            } catch (UnsupportedOperationException e) {
                assertTrue(uses(ops, READS), name + ": " + e.getMessage());
                String reason = Deferred.unavailableReason(e.getMessage());
                failed.add(name + ": the read stops (" + e.getMessage() + "); list it in EXCLUDED with \"" + reason
                        + "\"");
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
    }

    /**
     * The cards of each case excluded for {@value #RUNTIME_MATH} whose values may differ from C's
     * within libm rounding: a difference of at most {@link #LIBM_RTOL} relative to the value, or,
     * for an element of the PC matrix, whose diagonal elements are 1, relative to 1.
     */
    private static final Map<String, List<String>> RUNTIME_MATH_CARDS = runtimeMathCards();

    /** The relative difference of a value computed with java.lang.Math from C's libm value. */
    private static final double LIBM_RTOL = 1.0e-14;

    private static Map<String, List<String>> runtimeMathCards() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("alt_axes_offset", Arrays.asList("CDELT2", "CDELT2A"));
        m.put("alt_axes_offset_none", Arrays.asList("CDELT2"));
        m.put("sky_offset_system_rw", Arrays.asList("CDELT1", "CDELT2", "PC1_2", "PC2_1"));
        return Collections.unmodifiableMap(m);
    }

    /**
     * The cases whose cards C computes with elementary functions of its libm match C's transcript
     * line for line, but for the values of the named cards, which are within {@link #LIBM_RTOL}.
     */
    @Test
    void runtimeMathCasesMatchCButForLicensedCards() throws IOException {
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : RUNTIME_MATH_CARDS.entrySet()) {
            assertTrue(EXCLUDED.get(e.getKey()).startsWith(RUNTIME_MATH), e.getKey());
            JsonNode c = findCase(e.getKey());
            List<String> g = CValues.lines(transcript(ops(c)));
            List<String> w = CValues.lines(c.get(2).asText());
            if (g.size() != w.size()) {
                failed.add(e.getKey() + ": " + g.size() + " lines, C " + w.size());
                continue;
            }
            for (int i = 0; i < g.size(); i++) {
                if (!g.get(i).equals(w.get(i)) && !withinLibmRounding(g.get(i), w.get(i), e.getValue())) {
                    failed.add(e.getKey() + " line " + (i + 1) + ":\n" + g.get(i) + "\n" + w.get(i));
                }
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }

    /** Whether two transcript lines are the same card of {@code keys} with values within libm rounding. */
    private static boolean withinLibmRounding(String got, String want, List<String> keys) {
        if (!got.startsWith("|") || !want.startsWith("|") || got.length() < 9 || want.length() < 9) {
            return false;
        }
        String key = got.substring(1, 9).trim();
        if (!keys.contains(key) || !got.substring(0, 11).equals(want.substring(0, 11))) {
            return false;
        }
        int gs = got.indexOf(" /");
        int ws = want.indexOf(" /");
        // A value of another length moves the comment, so the padding after it differs.
        if (gs < 0
                || ws < 0
                || !got.substring(gs).trim().equals(want.substring(ws).trim())) {
            return false;
        }
        double g = Double.parseDouble(got.substring(11, gs).trim());
        double w = Double.parseDouble(want.substring(11, ws).trim());
        double scale = key.startsWith("PC") ? Math.max(Math.abs(w), 1.0) : Math.abs(w);
        return Math.abs(g - w) <= LIBM_RTOL * scale;
    }

    /**
     * Case {@code native_full_1} with C's {@code REFCNT} and {@code NOBJ} comment cards left out,
     * and the card counts that include them: everything else C writes with Full positive, every
     * unset value and every {@code IsA} marker, matches.
     */
    @Test
    void nativeFull1MatchesCButForProcessCounts() throws IOException {
        JsonNode c = findCase("native_full_1");
        String want = c.get(2).asText();
        int refcnt = 0;
        for (String line : CValues.lines(want)) {
            if (line.startsWith("|COMMENT REFCNT_")) {
                refcnt++;
            }
        }
        // The Frame and its two Axes each have an Object part.
        assertEquals(3, refcnt);
        assertEquals(withoutProcessCounts(want), withoutProcessCounts(transcript(ops(c))));
    }

    private static List<String> withoutProcessCounts(String transcript) {
        List<String> out = new ArrayList<>();
        for (String line : CValues.lines(transcript)) {
            if (!line.startsWith("card=")
                    && !line.startsWith("|COMMENT REFCNT_")
                    && !line.startsWith("|COMMENT NOBJ_")) {
                out.add(line);
            }
        }
        return out;
    }

    private static String context(List<String> lines, int at) {
        return String.join("\n", lines.subList(Math.max(0, at - 3), Math.min(lines.size(), at + 3)));
    }
}
