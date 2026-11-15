package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.OracleFile;
import uk.ac.starlink.ast.testsupport.OracleSection;

/** Helpers for the tests that read FITS headers through a FitsChan. */
final class FitsHeaders {

    private FitsHeaders() {}

    /** A FitsChan holding {@code cards}, each stored by {@code putFits}, rewound to its first card. */
    static FitsChan chan(String... cards) {
        FitsChan fc = new FitsChan();
        for (String card : cards) {
            fc.putFits(card, false);
        }
        fc.clear("Card");
        return fc;
    }

    /**
     * A FitsChan holding each line of {@code text} as a card, rewound, as C's oracle generators and
     * {@code wcsconverter.c} make one: each line, without its line ending, is given to {@code
     * astPutFits}.
     */
    static FitsChan chanFromLines(String text) {
        FitsChan fc = new FitsChan();
        for (String line : CValues.lines(text)) {
            fc.putFits(line, false);
        }
        fc.clear("Card");
        return fc;
    }

    /** {@link #chanFromLines} of the fixture at {@code rel}, relative to {@link Fixtures#root}. */
    static FitsChan fixture(String rel) {
        return chanFromLines(Fixtures.read(rel));
    }

    /** The Object {@code fc} reads, which must be a FrameSet. */
    static FrameSet readFrameSet(FitsChan fc) {
        AstObject obj = fc.read();
        assertNotNull(obj, "the read gave no Object");
        assertInstanceOf(FrameSet.class, obj, "the read gave a " + obj.astClass() + ", not a FrameSet");
        return (FrameSet) obj;
    }

    /** {@code map} applied to the one point {@code in}, forward or inverse. */
    static double[] tran(Mapping map, double[] in, boolean forward) {
        int nout = forward ? map.getNout() : map.getNin();
        double[][] cols = new double[in.length][1];
        for (int i = 0; i < in.length; i++) {
            cols[i][0] = in[i];
        }
        double[][] out = map.tranP(1, in.length, cols, forward, nout);
        double[] point = new double[nout];
        for (int i = 0; i < nout; i++) {
            point[i] = out[i][0];
        }
        return point;
    }

    /** The lines of the cards {@code fc} holds that a read has not consumed, END included. */
    static List<String> headerLines(FitsChan fc) {
        return CValues.lines(fc.toHeader());
    }

    /** The keyword of each card of {@link #headerLines} that is not blank, in order. */
    static List<String> keywords(FitsChan fc) {
        List<String> out = new ArrayList<>();
        for (String line : headerLines(fc)) {
            if (CValues.isBlank(line)) {
                continue;
            }
            String kw = line.length() > 8 ? line.substring(0, 8) : line;
            out.add(kw.trim());
        }
        return out;
    }

    /**
     * The string value of each {@code ASTWARN} card, in order: the text between the first pair of
     * quotes, with the trailing spaces C's wrapping gives.
     */
    static List<String> astwarnValues(FitsChan fc) {
        List<String> out = new ArrayList<>();
        for (String line : headerLines(fc)) {
            if (!line.startsWith("ASTWARN")) {
                continue;
            }
            int start = line.indexOf('\'') + 1;
            int end = line.indexOf('\'', start);
            out.add(line.substring(start, end));
        }
        return out;
    }

    /**
     * The section of {@code fixtures/oracle/headers.oracle} for the fixture {@code rel} in one
     * direction.
     */
    static OracleSection headersOracle(String rel, boolean forward) {
        for (OracleSection s : OracleFile.parse(Fixtures.root().resolve("oracle/headers.oracle"))) {
            if (s.path.equals(rel) && s.forward == forward) {
                return s;
            }
        }
        throw new IllegalArgumentException("headers.oracle has no " + (forward ? "forward" : "inverse") + " " + rel);
    }

    /** The inputs of row {@code row} (from 1) of {@code s}. */
    static double[] oracleIn(OracleSection s, int row) {
        double[] in = new double[s.nin];
        System.arraycopy(s.rows[row - 1], 0, in, 0, s.nin);
        return in;
    }

    /** The outputs of row {@code row} (from 1) of {@code s}. */
    static double[] oracleOut(OracleSection s, int row) {
        double[] out = new double[s.nout];
        System.arraycopy(s.rows[row - 1], s.nin, out, 0, s.nout);
        return out;
    }

    /**
     * The reason a read stopped for want of an out-of-scope class or encoding: {@code needs <Class>}
     * from the end of the message of {@code e}, or {@code encoding <name>} for an encoding whose
     * reading is not available; the whole message otherwise.
     */
    static String outOfScope(UnsupportedOperationException e) {
        String msg = String.valueOf(e.getMessage());
        int needs = msg.lastIndexOf("needs ");
        if (needs >= 0) {
            return msg.substring(needs);
        }
        String head = "reading the ";
        String tail = " encoding is not available";
        int h = msg.indexOf(head);
        if (h >= 0 && msg.endsWith(tail)) {
            return "encoding " + msg.substring(h + head.length(), msg.length() - tail.length());
        }
        return msg;
    }

    /** The cards of a probe case, a JSON array of strings. */
    static String[] cards(JsonNode node) {
        String[] out = new String[node.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = node.get(i).asText();
        }
        return out;
    }

    /**
     * A native dump without the Mappings of its top-level FrameSet: each {@code Map<n> =} line and
     * the Object written after it, up to its {@code End} line, is left out.
     */
    static String withoutMappings(String dump) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = CValues.lines(dump);
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (isMapLine(line) && i + 1 < lines.size()) {
                String begin = lines.get(i + 1);
                int indent = 0;
                while (indent < begin.length() && begin.charAt(indent) == ' ') {
                    indent++;
                }
                String rest = begin.substring(indent);
                int tab = rest.indexOf(' ', "Begin ".length());
                String cls = rest.substring("Begin ".length(), tab < 0 ? rest.length() : tab);
                String end = begin.substring(0, indent) + "End " + cls;
                i += 2;
                while (i < lines.size() && !lines.get(i).equals(end)) {
                    i++;
                }
                i++;
                continue;
            }
            sb.append(line).append('\n');
            i++;
        }
        return sb.toString();
    }

    /** Whether {@code line} is the {@code Map<n> =} line of a top-level FrameSet's dump. */
    private static boolean isMapLine(String line) {
        if (!line.startsWith("    Map")) {
            return false;
        }
        int i = "    Map".length();
        int digits = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        return i > digits && line.startsWith(" =", i);
    }
}
