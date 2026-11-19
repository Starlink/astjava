package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.function.Executable;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * The operations of a case of {@code probes/frame_line_probe.out}: each line of the form {@code
 * <op> <inputs> : <results>}, where the results are C's values or, after an error, {@code status
 * <n>} with the error's message lines before it. A {@code grid} line is followed by one line per
 * point, which become its rows.
 */
final class FrameProbe {

    private static final Map<String, String> CASES = ProbeOutput.load("frame_line_probe");

    /** One operation of a case. */
    static final class Op {

        /** The line as the probe wrote it, for failure messages. */
        final String line;

        /** The operation's name. */
        final String name;

        /** The words between the name and the colon. */
        final List<String> args;

        /** The words after the colon, or null if the operation failed. */
        final List<String> results;

        /** The message lines of the error, if the operation failed. */
        final List<String> error;

        /** The status of the error, if the operation failed. */
        final int status;

        /** The points that follow a {@code grid} line, each a row of values. */
        final List<double[]> rows = new ArrayList<>();

        Op(String line, List<String> words, List<String> results, List<String> error, int status) {
            this.line = line;
            this.name = words.get(0);
            this.args = words.subList(1, words.size());
            this.results = results;
            this.error = error;
            this.status = status;
        }

        /** Input {@code i} as a number. */
        double arg(int i) {
            return Double.parseDouble(args.get(i));
        }

        /** Inputs {@code from} to {@code to} (exclusive) as numbers. */
        double[] argRange(int from, int to) {
            double[] v = new double[to - from];
            for (int i = from; i < to; i++) {
                v[i - from] = arg(i);
            }
            return v;
        }

        /** Result {@code i} as a number. */
        double result(int i) {
            return Double.parseDouble(results.get(i));
        }

        /** Results {@code from} to {@code to} (exclusive) as numbers. */
        double[] resultRange(int from, int to) {
            double[] v = new double[to - from];
            for (int i = from; i < to; i++) {
                v[i - from] = result(i);
            }
            return v;
        }

        boolean failed() {
            return results == null;
        }

        /**
         * Checks that {@code action} fails as C did: with C's status and message lines.
         */
        void assertFails(Executable action) {
            AstException e = assertThrows(AstException.class, action, line);
            assertEquals(status, e.getStatus(), line);
            assertEquals(String.join("\n", error), ProbeOutput.trimLines(e.getMsgText()), line);
        }
    }

    private FrameProbe() {}

    /** The operations of case {@code name}. */
    static List<Op> ops(String name) {
        String text = CASES.get(name);
        assertNotNull(text, "No probe case " + name);
        List<Op> ops = new ArrayList<>();
        List<String> error = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("!! ")) {
                continue;
            }
            if (line.startsWith("!  ")) {
                error.add(line.substring(3));
            } else if (line.startsWith(" ")) {
                Op last = ops.get(ops.size() - 1);
                last.rows.add(numbers(line.trim()));
            } else {
                int colon = line.indexOf(" :");
                if (colon < 0) {
                    fail("Probe line without a colon: " + line);
                }
                List<String> words = words(line.substring(0, colon));
                String rest = line.substring(colon + 2).trim();
                if (rest.startsWith("status ")) {
                    int status = Integer.parseInt(rest.substring(7).trim());
                    ops.add(new Op(line, words, null, new ArrayList<>(error), status));
                } else {
                    ops.add(new Op(line, words, words(rest), Collections.emptyList(), 0));
                }
                error.clear();
            }
        }
        return ops;
    }

    private static List<String> words(String s) {
        List<String> w = new ArrayList<>();
        for (String t : s.trim().split("\\s+", -1)) {
            if (!t.isEmpty()) {
                w.add(t);
            }
        }
        return w;
    }

    private static double[] numbers(String s) {
        List<String> w = words(s);
        double[] v = new double[w.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = Double.parseDouble(w.get(i));
        }
        return v;
    }

    /** Asserts that each of {@code got} equals {@code want} within {@code tol}. */
    static void assertClose(double[] want, double[] got, double tol, String what) {
        assertEquals(want.length, got.length, what);
        for (int i = 0; i < want.length; i++) {
            if (Double.isNaN(got[i]) || Math.abs(want[i] - got[i]) > tol) {
                fail(what + ": value " + i + " is " + got[i] + ", C gives " + want[i] + " (tolerance " + tol + ")");
            }
        }
    }
}
