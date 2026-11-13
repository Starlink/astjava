package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * The numerical estimate of a Mapping's rate of change, which C's base-class {@code Rate}
 * (mapping.c) makes for the classes that do not know their rates exactly.
 *
 * <p>The Mapping is evaluated at evenly spaced points over an interval about the position, and
 * the spread of the gradients between neighbouring points measures how far the mean gradient over
 * the interval can be trusted. Intervals larger and smaller by factors of four are tried in turn,
 * and the mean gradient of the interval with the smallest spread is returned.
 *
 * <p>Ported from Rust {@code mappings/rate.rs} and C {@code mapping.c} ({@code Rate}, {@code
 * FindGradient} and {@code RateFun}).
 */
final class Rate {

    /** The number of sub-intervals each interval is divided into (C's {@code RATE_ORDER}). */
    private static final int RATE_ORDER = 8;

    /** The most interval sizes tried larger, and smaller, than the first (C's {@code NN}). */
    private static final int NN = 50;

    private Rate() {}

    /**
     * C's base-class {@code Rate}: the rate of change of output {@code ax1} with respect to input
     * {@code ax2} at {@code at}, both indices zero-based.
     *
     * @return the rate, or {@link AstObject#AST__BAD} if it cannot be found
     * @throws AstException with status {@code AST__AXIIN} for an index out of range, or {@code
     *     AST__NODEF} if the forward transformation is not defined
     */
    static double numericRate(Mapping map, double[] at, int ax1, int ax2) {
        String cls = map.astClass();
        int nin = map.getNin();
        int nout = map.getNout();
        if (ax1 < 0 || ax1 >= nout) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    "astRate(" + cls + "): The supplied Mapping output index (" + (ax1 + 1)
                            + ") is invalid; it should be in the range 1 to " + nout + ".");
        }
        if (ax2 < 0 || ax2 >= nin) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    "astRate(" + cls + "): The supplied Mapping input index (" + (ax2 + 1)
                            + ") is invalid; it should be in the range 1 to " + nin + ".");
        }
        if (!map.getTranForward()) {
            throw Ast.err(
                    AstException.AST__NODEF,
                    "astRate(" + cls + "): The supplied Mapping does not have a defined forward transformation.");
        }
        double x0 = at[ax2];
        if (Mapping.isBad(x0)) {
            return AstObject.AST__BAD;
        }

        // The first interval is a small fraction of |x0|, or the same small absolute step when x0
        // is zero, grown tenfold until a gradient can be found.
        double eps = Math.ulp(1.0);
        double h0 = x0 != 0.0 ? eps * 1.0e9 * Math.abs(x0) : eps * 1.0e9;
        double[] found = {AstObject.AST__BAD, 0.0};
        while (Mapping.isBad(found[0]) && h0 < 1.0e-10 * Double.MAX_VALUE) {
            h0 *= 10.0;
            found = findGradient(map, at, ax1, ax2, x0, h0);
        }
        if (Mapping.isBad(found[0])) {
            return AstObject.AST__BAD;
        }
        double range0 = found[1];

        // The gradient spreads (C's "y") and mean gradients (C's "z") for each interval size, in
        // order of size, the first interval at index NN.
        double[] ranges = new double[2 * NN + 1];
        double[] means = new double[2 * NN + 1];
        ranges[NN] = range0;
        means[NN] = found[0];
        double minRange = range0;
        int iret = NN;
        int itop = NN;

        // Larger intervals. An interval whose gradient cannot be found is skipped.
        double h = h0;
        for (int iin = NN + 1; iin <= 2 * NN; iin++) {
            h *= 4.0;
            double[] g = findGradient(map, at, ax1, ax2, x0, h);
            if (Mapping.isBad(g[0])) {
                continue;
            }
            itop++;
            means[itop] = g[0];
            ranges[itop] = g[1];
            double range = g[1];
            if (range < minRange) {
                minRange = range;
                iret = itop;
            } else if (range > minRange) {
                break;
            } else if (range == 0.0 && ranges[itop - 1] == 0.0) {
                // A zero spread confirmed by the previous interval: the Mapping is linear here.
                iret = itop;
                break;
            }
            if (range > 100.0 * range0) {
                break;
            }
        }
        range0 = minRange;

        // Smaller intervals, in the same way.
        int ibot = NN;
        h = h0;
        for (int iin = NN - 1; iin >= 0; iin--) {
            h /= 4.0;
            double[] g = findGradient(map, at, ax1, ax2, x0, h);
            if (Mapping.isBad(g[0])) {
                continue;
            }
            ibot--;
            means[ibot] = g[0];
            ranges[ibot] = g[1];
            double range = g[1];
            if (range < minRange) {
                minRange = range;
                iret = ibot;
            } else if (range > minRange) {
                break;
            } else if (range == 0.0 && ranges[ibot + 1] == 0.0) {
                iret = ibot;
                break;
            }
            if (range > 100.0 * range0) {
                break;
            }
        }

        double ret = AstObject.AST__BAD;
        if (minRange == 0.0) {
            // A zero spread is believed only if an adjacent interval also has one; otherwise the
            // smallest non-zero spread is used.
            if ((iret > ibot && ranges[iret - 1] == 0.0) || (iret < itop && ranges[iret + 1] == 0.0)) {
                ret = means[iret];
            } else {
                double minrange = 0.0;
                for (int i = ibot; i <= itop; i++) {
                    if (ranges[i] > 0.0 && (minrange == 0.0 || ranges[i] < minrange)) {
                        minrange = ranges[i];
                        ret = means[i];
                    }
                }
            }
        } else {
            ret = means[iret];
        }
        return ret;
    }

    /**
     * C's {@code FindGradient}: the mean gradient over the interval {@code h} centred on {@code
     * x0}, from its first to its last good point, and the spread (largest minus smallest) of the
     * gradients over its sub-intervals.
     *
     * @return the mean gradient, bad if fewer than two sub-intervals are usable, and the spread
     */
    private static double[] findGradient(Mapping map, double[] at, int ax1, int ax2, double x0, double h) {
        int npts = RATE_ORDER + 1;
        int i0 = RATE_ORDER / 2;
        double dh = h / RATE_ORDER;
        double[] x = new double[npts];
        for (int i = 0; i < npts; i++) {
            x[i] = x0 + (i - i0) * dh;
        }
        double[] y = rateFun(map, at, ax1, ax2, x);

        double gmax = AstObject.AST__BAD;
        double gmin = AstObject.AST__BAD;
        int ngood = 0;
        double x1 = 0.0;
        double y1 = AstObject.AST__BAD;
        double x2 = 0.0;
        double y2 = AstObject.AST__BAD;
        for (int i = 0; i < RATE_ORDER; i++) {
            if (!Mapping.isBad(y[i + 1]) && !Mapping.isBad(y[i]) && x[i + 1] != x[i]) {
                ngood++;
                double g = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
                if (ngood == 1) {
                    gmax = g;
                    gmin = g;
                } else if (g < gmin) {
                    gmin = g;
                } else if (g > gmax) {
                    gmax = g;
                }
                if (Mapping.isBad(y1)) {
                    y1 = y[i];
                    x1 = x[i];
                }
                y2 = y[i + 1];
                x2 = x[i + 1];
            }
        }
        if (ngood > 1) {
            return new double[] {(y2 - y1) / (x2 - x1), gmax - gmin};
        }
        return new double[] {AstObject.AST__BAD, 0.0};
    }

    /**
     * C's {@code RateFun}: output {@code ax1} of the forward transformation at each of the values
     * {@code x} of input {@code ax2}, the other inputs being as in {@code at}.
     */
    private static double[] rateFun(Mapping map, double[] at, int ax1, int ax2, double[] x) {
        int npts = x.length;
        int nin = map.getNin();
        double[][] in = new double[nin][npts];
        for (int ax = 0; ax < nin; ax++) {
            if (ax == ax2) {
                System.arraycopy(x, 0, in[ax], 0, npts);
            } else {
                Arrays.fill(in[ax], at[ax]);
            }
        }
        return map.transform(in, npts, true)[ax1];
    }
}
