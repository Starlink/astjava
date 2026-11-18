package uk.ac.starlink.ast;

/**
 * The linear-fit helpers with which a FitsChan write finds the CRPIX and CD values of a grid to
 * intermediate world coordinate Mapping: C's {@code FindBasisVectors}, {@code MakeBasisVectors},
 * {@code FitLine}, {@code FitOK} and {@code SplitMat} (fitschan.c).
 *
 * <p>Arrays of points are indexed {@code [axis][point]}, as the coordinate arrays of C's
 * PointSets are.
 *
 * <p>Ported from Rust {@code fitschan/wcs_decompose.rs}, following fitschan.c.
 */
final class FitsWcsDecompose {

    private static final double AST__BAD = AstObject.AST__BAD;

    /** Half the number of points FitLine fits, C's {@code NPO2}. */
    private static final int NPO2 = 50;

    /** The number of points FitLine fits, C's {@code NP}. */
    private static final int NP = 2 * NPO2 + 1;

    private FitsWcsDecompose() {}

    /**
     * C's {@code MakeBasisVectors}: puts in {@code ptrg} the grid position {@code g0} followed by
     * the {@code nin} positions one unit from it along each grid axis, and in {@code ptrw} those
     * positions transformed into intermediate world coordinates by {@code map}.
     *
     * @param ptrg receives the grid positions, {@code [nin][nin + 1]}
     * @param ptrw receives the transformed positions, {@code [nout][nin + 1]}
     * @return whether every transformed value is good
     */
    static boolean makeBasisVectors(Mapping map, int nin, int nout, double[] g0, double[][] ptrg, double[][] ptrw) {
        for (int i = 0; i < nin; i++) {
            double[] c = ptrg[i];
            for (int ii = 0; ii < nin + 1; ii++) {
                c[ii] = g0[i];
            }
            c[i + 1] += 1.0;
        }
        double[][] out = map.transform(ptrg, nin + 1, true);
        for (int j = 0; j < nout; j++) {
            System.arraycopy(out[j], 0, ptrw[j], 0, nin + 1);
        }
        boolean ret = true;
        for (int j = 0; j < nout; j++) {
            for (int ii = 0; ii < nin + 1; ii++) {
                if (ptrw[j][ii] == AST__BAD) {
                    ret = false;
                    break;
                }
            }
        }
        return ret;
    }

    /**
     * C's {@code FindBasisVectors}: finds a grid position at which {@code map} gives good
     * intermediate world coordinates, as do the positions one unit from it along each grid axis,
     * trying the grid centre (when every dimension is known), then (1, 1, ...), then positions
     * moving out along the diagonal and along each axis, doubling the distance each time up to
     * the largest dimension, or 10240 when none is known.
     *
     * @param dim the grid dimensions, {@code AST__BAD} where unknown, or null if none is known
     * @param ptrg receives the grid positions, {@code [nin][nin + 1]}, as {@link #makeBasisVectors}
     *     gives them for the position found
     * @param ptrw receives the transformed positions, {@code [nout][nin + 1]}
     * @return whether a position was found
     */
    static boolean findBasisVectors(Mapping map, int nin, int nout, double[] dim, double[][] ptrg, double[][] ptrw) {
        double[] g0 = new double[nin];

        // The grid centre, if known. A dimension that is not known stops the search for the
        // largest dimension, which keeps the largest of those before it.
        double ddlim = 0.0;
        boolean ret = false;
        if (dim != null) {
            ret = true;
            for (int i = 0; i < nin; i++) {
                if (dim[i] != AST__BAD) {
                    g0[i] = 0.5 * (1 + dim[i]);
                    if (dim[i] > ddlim) {
                        ddlim = dim[i];
                    }
                } else {
                    ret = false;
                    break;
                }
            }
        }
        if (ret) {
            ret = makeBasisVectors(map, nin, nout, g0, ptrg, ptrw);
        }

        // Grid position (1, 1, ...).
        if (!ret) {
            for (int i = 0; i < nin; i++) {
                g0[i] = 1.0;
            }
            ret = makeBasisVectors(map, nin, nout, g0, ptrg, ptrw);
        }

        // Positions moving out from (1, 1, ...), first across the middle of the grid, then along
        // each grid axis in turn.
        double dd = 10.0;
        if (ddlim == 0.0) {
            ddlim = 10240.0;
        }
        while (!ret && dd <= ddlim) {
            for (int i = 0; i < nin; i++) {
                if (dim != null && dim[i] != AST__BAD) {
                    g0[i] = dd * dim[i] / ddlim;
                } else {
                    g0[i] = dd;
                }
            }
            ret = makeBasisVectors(map, nin, nout, g0, ptrg, ptrw);
            for (int ii = 0; !ret && ii < nin; ii++) {
                for (int i = 0; i < nin; i++) {
                    g0[i] = 1.0;
                }
                g0[ii] = dd;
                ret = makeBasisVectors(map, nin, nout, g0, ptrg, ptrw);
            }
            dd *= 2.0;
        }
        return ret;
    }

    /**
     * C's {@code FitOK}: whether the root mean square difference between the good pairs of actual
     * and estimated values is below {@code tol}; false if there is no good pair.
     */
    static boolean fitOK(int n, double[] act, double[] est, double tol) {
        double s1 = 0.0;
        double s2 = 0.0;
        for (int i = 0; i < n; i++) {
            if (act[i] != AST__BAD && est[i] != AST__BAD) {
                double diff = act[i] - est[i];
                s1 += diff * diff;
                s2 += 1.0;
            }
        }
        boolean ret = false;
        if (s2 > 0.0) {
            double mserr = s1 / s2;
            if (mserr < tol * tol) {
                ret = true;
            }
        }
        return ret;
    }

    /**
     * C's {@code FitLine}: transforms 101 points along the line through {@code g0} with direction
     * {@code g}, spread over the axis length {@code dim} (or 1 apart when it is unknown), and fits
     * a straight line to each output by least squares.
     *
     * @param g a unit vector in the input space
     * @param g0 the central position of the line in the input space
     * @param w0 the output position corresponding to {@code g0}
     * @param dim the length of the pixel axis, or {@code AST__BAD} if unknown
     * @param tol the largest root mean square residual allowed on each output
     * @return the output vector corresponding to a unit step along {@code g}, or null if the
     *     Mapping is not linear along the line, {@code dim} is zero, or {@code w0} has a bad value
     */
    static double[] fitLine(Mapping map, double[] g, double[] g0, double[] w0, double dim, double[] tol) {
        if (dim == 0.0) {
            return null;
        }
        int nin = map.getNin();
        int nout = map.getNout();
        if (nout < nin) {
            return null;
        }
        for (int j = 0; j < nout; j++) {
            if (w0[j] == AST__BAD) {
                return null;
            }
        }
        double gap = (dim != AST__BAD) ? dim / NP : 1.0;

        double[][] ptr1 = new double[nin][NP];
        for (int j = 0; j < nin; j++) {
            double[] pax = ptr1[j];
            double dax = g[j] * gap;
            int k = 0;
            for (int i = -NPO2; i <= NPO2; i++) {
                pax[k++] = g0[j] + dax * i;
            }
        }
        double[][] ptr2 = map.transform(ptr1, NP, true);

        double[] ret = new double[nout];
        double[] offset = new double[nout];
        for (int j = 0; j < nout; j++) {
            double[] pax = ptr2[j];
            double sdw = 0.0;
            double sw = 0.0;
            double sd = 0.0;
            double sd2 = 0.0;
            int n = 0;
            double wmax = -Double.MAX_VALUE;
            double wmin = Double.MAX_VALUE;
            int k = 0;
            for (int i = -NPO2; i <= NPO2; i++) {
                double v = pax[k++];
                if (v != AST__BAD) {
                    sdw += i * v;
                    sw += v;
                    sd += i;
                    sd2 += i * i;
                    n++;
                    if (v > wmax) {
                        wmax = v;
                    }
                    if (v < wmin) {
                        wmin = v;
                    }
                }
            }
            double denom = sd2 * n - sd * sd;
            if (n > NP / 4 && denom != 0.0) {
                if (wmax > wmin) {
                    ret[j] = (sdw * n - sw * sd) / denom;
                } else {
                    ret[j] = 0.0;
                }
                offset[j] = (sw * sd2 - sdw * sd) / denom;
            } else {
                return null;
            }
        }

        // The values the fit gives are put in the no longer needed first input row.
        for (int j = 0; j < nout; j++) {
            double[] pax = ptr1[0];
            int k = 0;
            for (int i = -NPO2; i <= NPO2; i++) {
                pax[k++] = i * ret[j] + offset[j];
            }
            if (fitOK(NP, ptr2[j], ptr1[0], tol[j])) {
                ret[j] /= gap;
            } else {
                return null;
            }
        }
        return ret;
    }

    /**
     * C's {@code SplitMat}: factorises the CD matrix {@code matrix}, {@code naxis} by {@code
     * naxis} in row order, into CDELT values and a PC matrix, so that each row of the PC matrix is
     * a unit vector with a positive diagonal element; PC elements below 1e-7 in magnitude are
     * set to zero.
     *
     * @param matrix the CD matrix, replaced by the PC matrix
     * @param cdelt receives the CDELT values
     * @return false if a bad value is found, the matrix and CDELT values then holding what the
     *     rows before it gave
     */
    static boolean splitMat(int naxis, double[] matrix, double[] cdelt) {
        boolean ok = true;
        for (int i = 0; i < naxis; i++) {
            int row = i * naxis;
            boolean dineg = matrix[row + i] < 0.0;
            double s2 = 0.0;
            for (int j = 0; j < naxis; j++) {
                double a = matrix[row + j];
                if (a == AST__BAD) {
                    ok = false;
                    break;
                }
                s2 += a * a;
            }
            if (!ok) {
                break;
            }
            double cdlt = Math.sqrt(Math.max(0.0, s2));
            if (dineg) {
                cdlt = -cdlt;
            }
            cdelt[i] = cdlt;
            for (int j = 0; j < naxis; j++) {
                if (cdlt != 0.0) {
                    matrix[row + j] /= cdlt;
                    if (Math.abs(matrix[row + j]) < 1.0e-7) {
                        matrix[row + j] = 0.0;
                    }
                } else {
                    matrix[row + j] = 0.0;
                }
            }
        }
        return ok;
    }
}
