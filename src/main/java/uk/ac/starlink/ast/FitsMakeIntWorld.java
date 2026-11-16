package uk.ac.starlink.ast;

import java.util.Arrays;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * C's {@code MakeIntWorld} and {@code NearestPix} (fitschan.c): the CRPIX, PC, CDELT and WCSAXES
 * values of a FitsChan write, from the Mapping from grid to intermediate world coordinates.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/make_int_world.rs}, following fitschan.c.
 */
final class FitsMakeIntWorld {

    private static final double AST__BAD = AstObject.AST__BAD;

    /** Degrees to radians, C's {@code AST__DD2R}. */
    private static final double AST__DD2R = 0.017453292519943295;

    /** C's {@code DBL_EPSILON}. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    private FitsMakeIntWorld() {}

    /**
     * Stores in {@code store} the CRPIX, PC and CDELT values (and WCSAXES, when there are fewer
     * grid than WCS axes) describing {@code cmap}, which must be linear with an optional shift of
     * origin. A Mapping with more outputs than inputs gets degenerate grid axes, whose unit
     * vectors are chosen orthogonal to the other grid axes and scaled to look natural; the CRVAL
     * values, which the caller stores first, may be changed for them. When {@code sipok} is true
     * and {@code fr} holds a celestial axis, C first describes any distortion with SIP coefficients
     * through {@code SIPIntWorld}.
     *
     * <p>C stores CDELT values even when it fails, from memory it never sets; nothing is stored
     * here when the description fails.
     *
     * @param cmap the grid to intermediate world coordinate Mapping, with at least as many
     *     outputs as inputs
     * @param fr the final WCS Frame
     * @param wperm for each axis of {@code fr}, the zero-based index of the FITS-WCS axis that
     *     describes it
     * @param s the axis description character: a space for the primary description, else
     *     {@code 'A'} to {@code 'Z'}
     * @param store the store, which must hold as many axes as {@code cmap} has outputs
     * @param dim the grid dimensions, {@code AST__BAD} where unknown, one per input of {@code
     *     cmap}
     * @param fitstol the largest departure from linearity allowed on any axis, as a fraction of a
     *     grid pixel
     * @param sipok whether distortion may be described with SIP coefficients
     * @param method the calling method, for messages
     * @param cls the class of the object being written, for messages
     * @return whether the values were found
     * @throws UnsupportedOperationException if {@code sipok} is true and {@code fr} holds a
     *     celestial axis, which needs {@code SIPIntWorld}
     */
    // C compares the pointers astPrimaryFrame returns: whether two axes are in the same SkyFrame.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    static boolean makeIntWorld(
            Mapping cmap,
            Frame fr,
            int[] wperm,
            char s,
            FitsStore store,
            double[] dim,
            double fitstol,
            boolean sipok,
            String method,
            String cls) {
        int nin = cmap.getNin();
        int nout = cmap.getNout();
        if (nout < nin) {
            return false;
        }
        Mapping map = cmap.simplify();

        // The Mapping output of the longitude axis of any SkyFrame.
        int lonax = -1;
        int[] paxis = new int[1];
        for (int i = 0; i < nout; i++) {
            Frame pfrm = fr.primaryFrame(i + 1, paxis);
            if (pfrm instanceof SkyFrame && paxis[0] == 1) {
                lonax = i;
            }
        }
        if (sipok && lonax != -1) {
            throw new UnsupportedOperationException(
                    method + "(" + cls + "): describing the celestial axes of axis description '" + s
                            + "' with SIP coefficients needs FitsSipIntWorld");
        }

        int nwcs = fr.getNaxes();

        double[] g = new double[nin];
        double[] g0 = new double[nin];
        double[] w0 = new double[nout];
        double[] tol = new double[nout];
        double[][] partmat = new double[nout][];
        boolean[] lin = new boolean[nout];
        int[] pperm = new int[nout];
        int[] skycol = new int[nout];
        double[] cdmat = new double[nout * nout];
        double[] cdelt = new double[nout];
        double[][] ptrg = new double[nin][nin + 1];
        double[][] ptrw = new double[nout][nin + 1];

        // A root grid position with good IWC coordinates, those coordinates, and a tolerance on
        // each IWC axis: a fraction fitstol of the largest change caused by moving 1 pixel along
        // any grid axis, or, if that is zero, a very small fraction of the axis value.
        boolean ret = FitsWcsDecompose.findBasisVectors(map, nin, nout, dim, ptrg, ptrw);
        for (int j = 0; j < nin; j++) {
            g0[j] = ptrg[j][0];
        }
        for (int j = 0; j < nout; j++) {
            w0[j] = ptrw[j][0];
            tol[j] = 0.0;
            for (int i = 0; i < nin; i++) {
                double err = Math.abs(ptrw[j][i + 1] - w0[j]);
                if (err > tol[j]) {
                    tol[j] = err;
                }
            }
            tol[j] *= fitstol;
            if (tol[j] == 0.0) {
                tol[j] = w0[j] * DBL_EPSILON * 1.0e5;
            }
            if (tol[j] == 0.0) {
                tol[j] = Math.sqrt(Double.MIN_NORMAL);
            }
        }

        // The CD matrix: column i is the IWC vector of a unit vector along grid axis i.
        Arrays.fill(cdmat, AST__BAD);
        for (int i = 0; i < nin && ret; i++) {
            Arrays.fill(g, 0.0);
            g[i] = 1.0;
            partmat[i] = FitsWcsDecompose.fitLine(map, g, g0, w0, dim[i], tol);
            if (partmat[i] == null) {
                ret = false;
            }
        }

        // Columns for the degenerate grid axes, orthogonal to the others.
        double[][] fullmat = null;
        if (ret) {
            fullmat = FitsOrth.orthVectorSet(nout, nin, partmat);
            if (fullmat == null) {
                ret = false;
            }
        }
        if (!ret) {
            return false;
        }

        FitsInvertable.makeInvertable(fullmat, nout, dim);

        // The Mapping output of each FITS axis (the inverse of wperm); the columns and diagonal
        // elements of the first pair of celestial axes; and whether the Mapping from IWC to
        // world coordinates is linear on each axis, as it is taken to be for a plain Frame.
        Frame sfrm = null;
        double skydiag0 = AST__BAD;
        double skydiag1 = AST__BAD;
        int skycol0 = -1;
        int skycol1 = -1;
        for (int i = 0; i < nout; i++) {
            pperm[wperm[i]] = i;
            Frame pfrm = fr.primaryFrame(i + 1, paxis);
            if (isASkyFrame(pfrm)) {
                skycol[wperm[i]] = paxis[0];
                lin[i] = false;
                if (sfrm == null) {
                    sfrm = pfrm;
                    skycol0 = wperm[i];
                    skydiag0 = fullmat[skycol0][i];
                } else if (sfrm == pfrm) {
                    skycol1 = wperm[i];
                    skydiag1 = fullmat[skycol1][i];
                }
            } else {
                skycol[wperm[i]] = 0;
                lin[i] = "Frame".equals(pfrm.astClass());
            }
        }

        // CRPIX: w0 = fullmat * (g0 - crpix), solved for y = g0 - crpix, with g0 zero on the
        // degenerate axes. C ignores palDmat's singularity flag.
        double[] mat = new double[nout * nout];
        double[] y = w0.clone();
        int m = 0;
        for (int i = 0; i < nout; i++) {
            for (int j = 0; j < nout; j++) {
                mat[m++] = fullmat[j][i];
            }
        }
        Pal.dmat(nout, mat, y);

        for (int j = 0; j < nout; j++) {
            double[] colvec = fullmat[j];

            // A degenerate axis is at grid value 1, where w0 has it at 0.
            double crp;
            if (j < nin) {
                crp = g0[j] - y[j];
            } else {
                crp = 1.0 - y[j];
            }

            if (j < nin || crp == 0.0) {
                for (int i = 0; i < nout; i++) {
                    cdmat[wperm[i] * nout + j] = colvec[i];
                }
                store.crpix[j] = crp;
            } else {
                // The unit vector of a degenerate axis has length 1; scale it to look natural.
                // First, a celestial pair is given equal scales, longitude increasing east.
                double k = AST__BAD;
                if (skydiag0 != AST__BAD && skydiag1 != AST__BAD) {
                    if (skycol0 == j && skycol1 < nin) {
                        if (skydiag0 != 0.0) {
                            k = -skydiag1 / skydiag0;
                        }
                    } else if (skycol1 == j && skycol0 < nin && skydiag1 != 0.0) {
                        k = -skydiag0 / skydiag1;
                    }
                }

                // Next, the element with the largest diagonally opposite element is made its
                // negative.
                if (k == AST__BAD) {
                    double mxcv = 0.0;
                    int ii = pperm[j];
                    for (int i = 0; i < nout; i++) {
                        int jj = wperm[i];
                        if (jj < nin) {
                            double cv = fullmat[jj][ii];
                            if (!MappingHelpers.astEqual(colvec[i], 0.0) && Math.abs(cv) > mxcv) {
                                mxcv = Math.abs(cv);
                                k = -cv / colvec[i];
                            }
                        }
                    }
                }

                // Next, a diagonal element of 1 degree on a latitude axis, -1 degree on a
                // longitude axis, and 1 on any other axis.
                if (k == AST__BAD && colvec[pperm[j]] != 0.0) {
                    if (skycol[j] != 0) {
                        k = AST__DD2R / colvec[pperm[j]];
                        if (skycol[j] == 1) {
                            k = -k;
                        }
                    } else {
                        k = 1.0 / colvec[pperm[j]];
                    }
                }

                if (k == AST__BAD) {
                    k = 1.0;
                }
                for (int i = 0; i < nout; i++) {
                    cdmat[wperm[i] * nout + j] = k * colvec[i];
                }
                crp = 1.0 + (crp - 1.0) / k;
                store.crpix[j] = crp;
            }
        }

        // A degenerate axis that alone feeds a linear world axis, and feeds no other, has its
        // reference point moved to grid value 1.
        for (int i = 0; i < nout; i++) {
            if (lin[i]) {
                int jax = -1;
                for (int j = 0; j < nout; j++) {
                    if (!MappingHelpers.astEqual(fullmat[j][i], 0.0)) {
                        if (jax == -1) {
                            jax = j;
                        } else {
                            jax = -1;
                            break;
                        }
                    }
                }
                if (jax >= nin) {
                    for (int ii = 0; ii < nout; ii++) {
                        if (ii != i && !MappingHelpers.astEqual(fullmat[jax][ii], 0.0)) {
                            jax = -1;
                            break;
                        }
                    }
                    if (jax != -1) {
                        double crv = item(store.crval[wperm[i]]);
                        double cd = cdmat[wperm[i] * nout + jax];
                        double crp = item(store.crpix[jax]);
                        if (crv != AST__BAD && crp != AST__BAD && cd != AST__BAD) {
                            store.crpix[jax] = 1.0;
                            store.crval[wperm[i]] = cd * (1.0 - crp) + crv;
                        }
                    }
                }
            }
        }

        if (nin < nwcs) {
            store.wcsaxes = nwcs;
        }

        // PC and CDELT from the CD matrix; a unit diagonal and zero off-diagonal PC element is
        // not stored.
        FitsWcsDecompose.splitMat(nout, cdmat, cdelt);
        int c = 0;
        for (int i = 0; i < nout; i++) {
            for (int j = 0; j < nout; j++) {
                double val = cdmat[c++];
                if (i == j) {
                    if (MappingHelpers.astEqual(val, 1.0)) {
                        val = AST__BAD;
                    }
                } else {
                    if (MappingHelpers.astEqual(val, 0.0)) {
                        val = AST__BAD;
                    }
                }
                if (val != AST__BAD) {
                    store.pc[i][j] = val;
                }
            }
            store.cdelt[i] = cdelt[i];
        }
        return true;
    }

    /**
     * C's {@code IsASkyFrame} macro in fitschan.c: a SkyFrame whose Domain is {@code SKY}.
     */
    private static boolean isASkyFrame(Frame frame) {
        return frame instanceof SkyFrame && "SKY".equals(frame.getDomain());
    }

    /** A store value as C's {@code GetItem} gives it: {@code AST__BAD} when it is not set. */
    private static double item(Double v) {
        return v == null ? AST__BAD : v;
    }

    /**
     * C's {@code NearestPix}: the value of output {@code axis} of {@code map} at the grid position
     * nearest to where it is {@code val}. The value is transformed back with the other outputs
     * bad, each good grid value rounded to the nearest integer, and the result transformed
     * forward. A Mapping with no inverse is first split, the output split off it being used if
     * the split succeeds.
     *
     * @param map the Mapping, which is inverted and inverted back to split it, as C does
     * @param axis the zero-based output index
     * @return the value at the nearest grid position, or {@code val} if it is bad, the Mapping
     *     has no inverse, or the value found is bad
     */
    static double nearestPix(Mapping map, double val, int axis) {
        double result = val;
        if (result == AST__BAD) {
            return result;
        }
        Mapping tmap;
        int tax = axis;
        if (!map.getTranInverse()) {
            MapSplit split;
            map.invert();
            try {
                split = map.mapSplit(new int[] {axis});
            } finally {
                map.invert();
            }
            if (split != null) {
                tmap = split.map;
                tmap.invert();
                tax = 0;
            } else {
                tmap = map;
            }
        } else {
            tmap = map;
        }

        if (tmap.getTranInverse()) {
            int nin = tmap.getNin();
            int nout = tmap.getNout();
            double[][] ptr2 = new double[nout][1];
            for (int i = 0; i < nout; i++) {
                ptr2[i][0] = AST__BAD;
            }
            ptr2[tax][0] = val;
            double[][] ptr1 = tmap.transform(ptr2, 1, false);
            for (int i = 0; i < nin; i++) {
                if (ptr1[i][0] != AST__BAD) {
                    ptr1[i][0] = roundHalfAway(ptr1[i][0]);
                }
            }
            ptr2 = tmap.transform(ptr1, 1, true);
            if (ptr2[tax][0] != AST__BAD) {
                result = ptr2[tax][0];
            }
        }
        return result;
    }

    /** C's {@code round}: to the nearest integer, halfway cases away from zero. */
    private static double roundHalfAway(double x) {
        double a = Math.abs(x);
        double fl = Math.floor(a);
        return Math.copySign(a - fl >= 0.5 ? fl + 1.0 : fl, x);
    }
}
