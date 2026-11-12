package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.cminpack.Lmder;
import uk.ac.starlink.ast.internal.cminpack.Lmder1;

/**
 * The least-squares fitting behind {@link PolyMap#polyTran}: polymap.c's {@code
 * ReplaceTransformation}, {@code SamplePoly1D}, {@code SamplePoly2D}, {@code FitPoly1D} and {@code
 * FitPoly2D}, with the residual and Jacobian functions they give cminpack's {@code lmder1}.
 *
 * <p>The transformation to replace is fitted by sampling the other one on a regular grid of {@code
 * 2 * order} points per axis over the given box, scaling the sampled values of each space to unit
 * RMS, and minimising the sum of squared residuals between the grid positions and the polynomial
 * applied to the sampled values, raising the order from 2 (linear) until the target accuracy is met
 * or the maximum order is reached.
 *
 * <p>Ported from Rust {@code mappings/helpers/poly_tran.rs}, which minimises with a Rust crate;
 * here, as in C, the minimiser is cminpack's {@code lmder1} ({@link Lmder1}), called as {@code
 * polymap.c} calls it, so the fitted coefficients follow C's.
 */
final class PolyTran {

    private PolyTran() {}

    /** A fitted polynomial in the form {@link PolyMap#storeArrays} takes, and its achieved accuracy. */
    private static final class Fit {
        final int ncoeff;
        final double[] coeffs;
        final double racc;

        Fit(int ncoeff, double[] coeffs, double racc) {
            this.ncoeff = ncoeff;
            this.coeffs = coeffs;
            this.racc = racc;
        }
    }

    /**
     * C's {@code ReplaceTransformation}: replaces the forward ({@code forward}) or inverse
     * transformation of {@code map} as it stands with a fit to samples of the other one; see {@link
     * PolyMap#polyTran}.
     *
     * @return whether a fit at least as good as {@code maxacc} was made and stored
     */
    static boolean replaceTransformation(
            PolyMap map, boolean forward, double acc, double maxacc, int maxorder, double[] lbnd, double[] ubnd) {
        String cls = map.astClass();
        int ndim = map.getNin();
        if (map.getNout() != ndim) {
            throw Ast.err(
                    AstException.AST__BADNI,
                    "astPolyTran(" + cls + "): Supplied " + cls + " has different number of inputs (" + ndim
                            + ") and outputs (" + map.getNout() + ").");
        } else if (ndim > 2) {
            throw Ast.err(
                    AstException.AST__BADNI,
                    "astPolyTran(" + cls + "): Supplied " + cls + " has too many inputs and outputs (" + ndim
                            + ") - must be 1 or 2.");
        }
        if (forward != map.getInvert()) {
            if (!map.hasCoeffs(false)) {
                throw Ast.err(
                        AstException.AST__NODEF,
                        "astPolyTran(" + cls + "): Supplied " + cls + " has no inverse transformation.");
            }
        } else if (!map.hasCoeffs(true)) {
            throw Ast.err(
                    AstException.AST__NODEF,
                    "astPolyTran(" + cls + "): Supplied " + cls + " has no forward transformation.");
        }
        if (lbnd == null || ubnd == null) {
            throw Ast.err(AstException.AST__NODEF, "astPolyTran(" + cls + "): No upper and/or lower bounds supplied.");
        }
        int nbnd = Math.max(1, ndim);
        map.checkDim("astPolyTran", "lbnd", lbnd.length, nbnd);
        map.checkDim("astPolyTran", "ubnd", ubnd.length, nbnd);
        // C reports the lower bound as the upper and the upper as the lower.
        if (lbnd[0] >= ubnd[0]) {
            throw Ast.err(
                    AstException.AST__NODEF,
                    "astPolyTran(" + cls + "): Supplied upper bound for the first axis ("
                            + CFormat.format("%g", lbnd[0])
                            + ") is less than or equal to the supplied lower bound (" + CFormat.format("%g", ubnd[0])
                            + ").");
        } else if (ndim == 2 && lbnd[1] >= ubnd[1]) {
            throw Ast.err(
                    AstException.AST__NODEF,
                    "astPolyTran(" + cls + "): Supplied upper bound for the second axis ("
                            + CFormat.format("%g", lbnd[1]) + ") is less than or equal to the supplied lower bound ("
                            + CFormat.format("%g", ubnd[1]) + ").");
        }

        // The order is one more than the maximum power, so a quadratic has order 3.
        int maxord = Math.max(2, maxorder);
        for (int order = 2; order <= maxord; order++) {
            Fit fit;
            if (ndim == 2) {
                double[] scales = new double[4];
                double[][] table = samplePoly2D(map, !forward, lbnd, ubnd, 2 * order, scales);
                fit = fitPoly2D(map, forward, table[0].length, acc, order, table, scales);
            } else {
                double[] scales = new double[2];
                double[][] table = samplePoly1D(map, !forward, lbnd[0], ubnd[0], 2 * order, scales);
                fit = fitPoly1D(map, forward, table[0].length, acc, order, table, scales);
            }
            if (fit != null && (fit.racc < acc || (fit.racc < maxacc && order == maxord))) {
                map.storeArrays(forward, fit.ncoeff, fit.coeffs);
                return true;
            }
        }
        return false;
    }

    /** {@code map}'s transformation of the points, with C's {@code astTransform} replacing values that are not finite with bad. */
    private static double[][] transform(PolyMap map, double[][] in, int npoint, boolean forward) {
        double[][] out = map.transform(in, npoint, forward);
        for (double[] axis : out) {
            for (int p = 0; p < npoint; p++) {
                if (!Double.isFinite(axis[p])) {
                    axis[p] = Mapping.AST__BAD;
                }
            }
        }
        return out;
    }

    /** Divides each value of the columns by their joint RMS, which it returns. */
    private static double normalise(double[]... cols) {
        double sum = 0.0;
        int n = 0;
        for (double[] col : cols) {
            for (double v : col) {
                sum += v * v;
            }
            n += col.length;
        }
        double rms = Math.sqrt(sum / n);
        for (double[] col : cols) {
            for (int i = 0; i < col.length; i++) {
                col[i] /= rms;
            }
        }
        return rms;
    }

    /**
     * C's {@code SamplePoly1D}: the transformation of {@code map} in direction {@code forward} at
     * {@code npoint} regularly spaced positions from {@code lbnd} to {@code ubnd}, the last set to
     * {@code ubnd} so that rounding cannot take it beyond. Gives the table: column 0 the transformed
     * values and column 1 the positions, each divided by its RMS, which goes in {@code scales}.
     */
    private static double[][] samplePoly1D(
            PolyMap map, boolean forward, double lbnd, double ubnd, int npoint, double[] scales) {
        double delta0 = (ubnd - lbnd) / (npoint - 1);
        double[] grid = new double[npoint];
        double val0 = lbnd;
        for (int i = 0; i < npoint - 1; i++) {
            grid[i] = val0;
            val0 += delta0;
        }
        grid[npoint - 1] = ubnd;
        double[][] table = {transform(map, new double[][] {grid}, npoint, forward)[0], grid};
        scales[0] = normalise(table[0]);
        scales[1] = normalise(table[1]);
        return table;
    }

    /**
     * C's {@code SamplePoly2D}: the transformation of {@code map} in direction {@code forward} at an
     * {@code npoint} by {@code npoint} grid over the box, the first axis varying slowest and the
     * last position on each axis set to its upper bound. Gives the table: columns 0 and 1 the
     * transformed values and columns 2 and 3 the positions. Each pair of columns is divided by its
     * joint RMS, so that the scaling is the same on both axes, and the RMS goes in {@code scales}
     * for both.
     */
    // The loops fill and select as they go, which PMD mistakes for copies.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private static double[][] samplePoly2D(
            PolyMap map, boolean forward, double[] lbnd, double[] ubnd, int npoint, double[] scales) {
        int nsamp = npoint * npoint;
        double delta0 = (ubnd[0] - lbnd[0]) / (npoint - 1);
        double delta1 = (ubnd[1] - lbnd[1]) / (npoint - 1);
        double[] p0 = new double[nsamp];
        double[] p1 = new double[nsamp];
        int k = 0;
        double val0 = lbnd[0];
        for (int i = 0; i < npoint - 1; i++) {
            double val1 = lbnd[1];
            for (int j = 0; j < npoint - 1; j++) {
                p0[k] = val0;
                p1[k++] = val1;
                val1 += delta1;
            }
            p0[k] = val0;
            p1[k++] = ubnd[1];
            val0 += delta0;
        }
        double val1 = lbnd[1];
        for (int j = 0; j < npoint - 1; j++) {
            p0[k] = ubnd[0];
            p1[k++] = val1;
            val1 += delta1;
        }
        p0[k] = ubnd[0];
        p1[k] = ubnd[1];
        double[][] out = transform(map, new double[][] {p0, p1}, nsamp, forward);
        double[][] table = {out[0], out[1], p0, p1};
        scales[0] = normalise(table[0], table[1]);
        scales[1] = scales[0];
        scales[2] = normalise(table[2], table[3]);
        scales[3] = scales[2];
        return table;
    }

    /**
     * C's {@code FitPoly1D}: fits a least-squares polynomial y1 = P1(x1) of order {@code order} to
     * the {@code nsamp} positions of the table (x1 in column 0, y1 in column 1), starting from the
     * unit transformation of the scaled values, with {@code lmder1} at tolerance {@code
     * sqrt(DBL_EPSILON)}. Coefficients whose largest contribution at any sample is below {@code
     * acc} are dropped and the rest unscaled.
     *
     * @return the coefficients and achieved accuracy, or null if every coefficient is dropped
     */
    private static Fit fitPoly1D(
            PolyMap map, boolean forward, int nsamp, double acc, int order, double[][] table, double[] scales) {
        int ncof = order;
        double[] xp1 = map.fitPoly1DInit(forward, table, order, nsamp, scales);
        double[] y = table[1];
        double[] coeffs = new double[ncof];
        coeffs[1] = 1.0;
        boolean[] initJac = {true};
        Lmder.Function fcn = (m, n, x, fvec, fjac, ldfjac, iflag) -> {
            if (iflag == 1) {
                lmFunc1D(x, fvec, order, nsamp, xp1, y);
            } else {
                lmJacob1D(fjac, n, m, order, xp1, initJac);
            }
            return 0;
        };
        double[] work1 = new double[nsamp];
        int info = Lmder1.lmder1(
                fcn,
                nsamp,
                ncof,
                coeffs,
                work1,
                new double[ncof * nsamp],
                nsamp,
                Math.sqrt(Math.ulp(1.0)),
                new int[ncof]);
        checkInfo(info);

        // The achieved accuracy, from the scaled Y residuals at each sample.
        double tv = 0.0;
        for (double r : work1) {
            tv += r * r;
        }
        double racc = scales[1] * Math.sqrt(tv / nsamp);

        // Drop each coefficient whose largest contribution at any sample is below the required
        // accuracy, and unscale the rest from the table's scaled values.
        double f1 = scales[1];
        double f2 = 1.0;
        for (int w1 = 0; w1 < order; w1++) {
            double maxterm = 0.0;
            for (int k = 0; k < nsamp; k++) {
                double term = Math.abs(coeffs[w1] * xp1[w1 + k * order]);
                if (term > maxterm) {
                    maxterm = term;
                }
            }
            if (maxterm * f1 < acc) {
                coeffs[w1] = 0.0;
            } else {
                coeffs[w1] *= f1 / f2;
            }
            f2 *= scales[0];
        }

        int n = 0;
        double[] result = new double[ncof * 3];
        for (int w1 = 0; w1 < order; w1++) {
            if (coeffs[w1] != 0.0) {
                result[3 * n] = coeffs[w1];
                result[3 * n + 1] = 1;
                result[3 * n + 2] = w1;
                n++;
            }
        }
        return n == 0 ? null : new Fit(n, result, racc);
    }

    /**
     * C's {@code FitPoly2D}: fits a pair of least-squares polynomials y1 = P1(x1, x2), y2 = P2(x1,
     * x2) of order {@code order} to the {@code nsamp} positions of the table (x1, x2 in columns 0
     * and 1, y1, y2 in columns 2 and 3), cross terms with a total power of {@code order} or more
     * excluded, so that each has {@code order (order + 1) / 2} coefficients, ordered C00, C10, C01,
     * C20, C11, C02 and so on. It starts, as C does, from elements 1 and 5 of the coefficients set
     * to 1, which is the unit transformation of the scaled values for a linear fit, and minimises
     * with {@code lmder1} at tolerance {@code sqrt(DBL_EPSILON)}. Coefficients whose largest
     * contribution at any sample is below {@code acc} are dropped and the rest unscaled.
     *
     * @return the coefficients and achieved accuracy, or null if every coefficient is dropped
     */
    // The loops fill and select as they go, which PMD mistakes for copies.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private static Fit fitPoly2D(
            PolyMap map, boolean forward, int nsamp, double acc, int order, double[][] table, double[] scales) {
        int ncof = order * (order + 1) / 2;
        double[][] xp = map.fitPoly2DInit(forward, table, order, nsamp, scales);
        double[] xp1 = xp[0];
        double[] xp2 = xp[1];
        double[][] y = {table[2], table[3]};
        double[] coeffs = new double[2 * ncof];
        coeffs[1] = 1.0;
        coeffs[5] = 1.0;
        boolean[] initJac = {true};
        Lmder.Function fcn = (m, n, x, fvec, fjac, ldfjac, iflag) -> {
            if (iflag == 1) {
                lmFunc2D(x, fvec, order, nsamp, xp1, xp2, y);
            } else {
                lmJacob2D(fjac, n, m, order, nsamp, xp1, xp2, initJac);
            }
            return 0;
        };
        double[] work1 = new double[2 * nsamp];
        int info = Lmder1.lmder1(
                fcn,
                2 * nsamp,
                2 * ncof,
                coeffs,
                work1,
                new double[4 * ncof * nsamp],
                2 * nsamp,
                Math.sqrt(Math.ulp(1.0)),
                new int[2 * ncof]);
        checkInfo(info);

        // The achieved accuracy.
        double tv = 0.0;
        for (double r : work1) {
            tv += r * r;
        }
        double facc = 1.0 / (scales[2] * scales[2]) + 1.0 / (scales[3] * scales[3]);
        double racc = Math.sqrt(tv / (2 * nsamp * facc));

        // Drop each coefficient whose largest contribution at any sample is below the required
        // accuracy, and unscale the rest from the table's scaled values.
        int pc = 0;
        for (int iout = 0; iout < 2; iout++) {
            double f1 = scales[2 + iout];
            double f20 = 1.0;
            for (int w12 = 0; w12 < order; w12++) {
                double f3 = 1.0;
                double f2 = f20;
                for (int w2 = 0; w2 <= w12; w2++, pc++) {
                    int w1 = w12 - w2;
                    double maxterm = 0.0;
                    for (int k = 0; k < nsamp; k++) {
                        double term = Math.abs(coeffs[pc] * xp1[w1 + k * order] * xp2[w2 + k * order]);
                        if (term > maxterm) {
                            maxterm = term;
                        }
                    }
                    if (maxterm * f1 < acc) {
                        coeffs[pc] = 0.0;
                    } else {
                        coeffs[pc] *= f1 / (f2 * f3);
                    }
                    f2 /= scales[0];
                    f3 *= scales[1];
                }
                f20 *= scales[0];
            }
        }

        int n = 0;
        double[] result = new double[2 * ncof * 4];
        pc = 0;
        for (int iout = 0; iout < 2; iout++) {
            for (int w12 = 0; w12 < order; w12++) {
                for (int w2 = 0; w2 <= w12; w2++, pc++) {
                    if (coeffs[pc] != 0.0) {
                        result[4 * n] = coeffs[pc];
                        result[4 * n + 1] = iout + 1;
                        result[4 * n + 2] = w12 - w2;
                        result[4 * n + 3] = w2;
                        n++;
                    }
                }
            }
        }
        return n == 0 ? null : new Fit(n, result, racc);
    }

    /** C's error when {@code lmder1} reports improper input parameters. */
    private static void checkInfo(int info) {
        if (info == 0) {
            throw Ast.err(
                    AstException.AST__MNPCK,
                    "astPolyMap(PolyTran): Minpack error detected (possible programming error).");
        }
    }

    /**
     * C's {@code LMFunc1D}: the residual at each sample of the candidate polynomial with
     * coefficients {@code p} (C0, C1, C2 and so on), minus the sampled value {@code y}.
     */
    private static void lmFunc1D(double[] p, double[] hx, int order, int nsamp, double[] xp1, double[] y) {
        int px1 = 0;
        for (int k = 0; k < nsamp; k++) {
            double res = -y[k];
            for (int w1 = 0; w1 < order; w1++) {
                res += p[w1] * xp1[px1++];
            }
            hx[k] = res;
        }
    }

    /**
     * C's {@code LMFunc2D}: the residual at each sample of each candidate polynomial, all of P1's
     * then all of P2's, with coefficients {@code p} (P1's, then P2's, each ordered C00, C10, C01,
     * C20, C11, C02 and so on), minus the sampled values {@code y}.
     */
    private static void lmFunc2D(
            double[] p, double[] hx, int order, int nsamp, double[] xp1, double[] xp2, double[][] y) {
        int vr = 0;
        int vp0 = 0;
        for (int iout = 0; iout < 2; iout++) {
            double[] py = y[iout];
            int px10 = 0;
            int px20 = 0;
            for (int k = 0; k < nsamp; k++) {
                int vp = vp0;
                double res = -py[k];
                // w12 is the total power of x1 and x2; x1's power falls from w12 to zero as x2's
                // rises from zero to w12.
                for (int w12 = 0; w12 < order; w12++) {
                    int px1 = px10++;
                    int px2 = px20;
                    for (int w2 = 0; w2 <= w12; w2++) {
                        res += p[vp++] * xp1[px1--] * xp2[px2++];
                    }
                }
                px20 += order;
                hx[vr++] = res;
            }
            vp0 += order * (1 + order) / 2;
        }
    }

    /**
     * C's {@code LMJacob1D}: the Jacobian of the residuals with respect to the coefficients, which
     * for a polynomial does not depend on them, so C forms it on the first call only and returns
     * at once on later ones, leaving {@code jac} as it is. {@code lmder} factorises its Jacobian in
     * place, so on a later iteration it works from that factorisation rather than the Jacobian, as
     * C's does.
     */
    private static void lmJacob1D(double[] jac, int m, int n, int order, double[] xp1, boolean[] initJac) {
        if (initJac[0]) {
            initJac[0] = false;
            for (int k = 0; k < n; k++) {
                for (int w1 = 0; w1 < m; w1++) {
                    jac[k + w1 * n] = xp1[w1 + k * order];
                }
            }
        }
    }

    /** C's {@code LMJacob2D}: as {@link #lmJacob1D} for the pair of polynomials of {@link #lmFunc2D}. */
    private static void lmJacob2D(
            double[] jac, int m, int n, int order, int nsamp, double[] xp1, double[] xp2, boolean[] initJac) {
        if (initJac[0]) {
            initJac[0] = false;
            int ncof = order * (1 + order) / 2;
            for (int vr = 0; vr < n; vr++) {
                int iout = vr / nsamp;
                int k = vr - iout * nsamp;
                for (int vp = 0; vp < m; vp++) {
                    if (vp / ncof != iout) {
                        jac[vr + vp * n] = 0.0;
                    } else {
                        int w12 = (int) ((-1.0 + Math.sqrt(1.0 + 8.0 * (vp - iout * ncof))) / 2.0);
                        int w2 = vp - iout * ncof - w12 * (w12 + 1) / 2;
                        int w1 = w12 - w2;
                        jac[vr + vp * n] = xp1[w1 + k * order] * xp2[w2 + k * order];
                    }
                }
            }
        }
    }
}
