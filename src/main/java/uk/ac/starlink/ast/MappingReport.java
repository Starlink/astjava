package uk.ac.starlink.ast;

import java.io.PrintStream;

/**
 * Where a Mapping whose Report attribute is set writes the effect of each transformation made by
 * {@link Mapping#tran1}, {@link Mapping#tran2}, {@link Mapping#tranN} and {@link Mapping#tranP}.
 *
 * <p>C's {@code ReportPoints} (mapping.c) prints to standard output. Here the report goes to a
 * stream that can be replaced for each thread, standard output by default, so reporting takes no
 * lock and each thread reports to its own stream.
 *
 * <p>Ported from Rust {@code mappings/report.rs}.
 */
final class MappingReport {

    /** Formats one coordinate value of a report, given its 0-based coordinate index. */
    @FunctionalInterface
    interface ValueFormat {
        String format(int coord, double value);
    }

    /** This thread's report stream, or null for standard output. */
    private static final ThreadLocal<PrintStream> SINK = new ThreadLocal<>();

    private MappingReport() {}

    /**
     * Sends this thread's reports to {@code stream} instead of standard output.
     *
     * @return the stream that was receiving them, or null for standard output
     */
    static PrintStream setSink(PrintStream stream) {
        PrintStream old = SINK.get();
        SINK.set(stream);
        return old;
    }

    /**
     * Sends this thread's reports to standard output again.
     *
     * @return the stream that was receiving them, or null for standard output
     */
    static PrintStream resetSink() {
        PrintStream old = SINK.get();
        SINK.remove();
        return old;
    }

    /** Writes {@code text} to this thread's report stream. As with C's printf, failures are not reported. */
    // The stream is standard output or one the caller owns, and stays open.
    @SuppressWarnings("PMD.CloseResource")
    static void write(String text) {
        PrintStream sink = SINK.get();
        PrintStream out = sink == null ? System.out : sink;
        out.print(text);
        out.flush();
    }

    /**
     * One line per point, {@code (in, ...) --> (out, ...)}, each value written by {@code fmtIn} or
     * {@code fmtOut}, for the first {@code npoint} points of {@code in} and {@code out} (each
     * {@code [ncoord][npoint]}), as mapping.c {@code ReportPoints} writes them.
     */
    static String lines(double[][] in, double[][] out, int npoint, ValueFormat fmtIn, ValueFormat fmtOut) {
        StringBuilder text = new StringBuilder();
        for (int p = 0; p < npoint; p++) {
            text.append('(');
            side(text, in, p, fmtIn);
            text.append(") --> (");
            side(text, out, p, fmtOut);
            text.append(")\n");
        }
        return text.toString();
    }

    private static void side(StringBuilder text, double[][] coords, int point, ValueFormat fmt) {
        for (int c = 0; c < coords.length; c++) {
            if (c > 0) {
                text.append(", ");
            }
            text.append(fmt.format(c, coords[c][point]));
        }
    }

    /** A value as mapping.c {@code ReportPoints} writes it: {@code <bad>}, or {@code %.17g}. */
    static String plainValue(int coord, double value) {
        return value == AstObject.AST__BAD ? "<bad>" : CFormat.format("%.17g", value);
    }
}
