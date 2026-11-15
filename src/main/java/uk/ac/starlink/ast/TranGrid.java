package uk.ac.starlink.ast;

/**
 * Transforming every position of a regular grid, as C's {@code astTranGrid} does.
 *
 * <p>The grid is divided recursively along its longest axis until either a section is too small
 * for a linear fit to be worth trying, or {@code astLinearApprox} finds the Mapping linear enough
 * over it. Each section is then transformed in blocks of at most {@link #MXPIX} positions, either
 * through the Mapping or by stepping the linear fit across the block. The positions given by a fit
 * are accumulated by repeated addition, starting afresh at each block, so the blocking and the
 * 1-D, 2-D and general paths are kept as C has them: each rounds differently.
 *
 * <p>Ported from Rust {@code mappings/tran_grid.rs} and C {@code mapping.c} ({@code TranGrid},
 * {@code TranGridAdaptively}, {@code TranGridWithBlocking} and {@code TranGridSection}).
 */
final class TranGrid {

    /** The most positions transformed at once (C's {@code mxpix} in {@code TranGridWithBlocking}). */
    private static final long MXPIX = 2 * 1024;

    /** The lower bounds of the whole grid, which every section indexes into. */
    private final long[] lbndIn;

    /** The upper bounds of the whole grid. */
    private final long[] ubndIn;

    /** The number of output coordinates. */
    private final int ncoordOut;

    /** The output coordinates, {@code [ncoordOut][npoint]}. */
    private final double[][] out;

    private TranGrid(long[] lbndIn, long[] ubndIn, int ncoordOut, double[][] out) {
        this.lbndIn = lbndIn;
        this.ubndIn = ubndIn;
        this.ncoordOut = ncoordOut;
        this.out = out;
    }

    /**
     * C's {@code TranGrid}, after jniast's check of the bound arrays: transforms the grid from
     * {@code lbnd} to {@code ubnd} through {@code map}.
     *
     * @return the output coordinates, one array per output coordinate
     */
    static double[][] tranGrid(
            Mapping map, int ncoordIn, int[] lbnd, int[] ubnd, double tol, int maxpix, boolean forward, int ncoordOut) {
        String cls = map.astClass();
        int ndim = Math.max(0, ncoordIn);
        map.checkDim("astTranGrid", "lbnd", lbnd.length, ndim);
        map.checkDim("astTranGrid", "ubnd", ubnd.length, ndim);
        long[] lo = new long[ndim];
        long[] hi = new long[ndim];
        long mpix = 1;
        for (int idim = 0; idim < ndim; idim++) {
            lo[idim] = lbnd[idim];
            hi[idim] = ubnd[idim];
            if (lo[idim] > hi[idim]) {
                throw Ast.err(
                        AstException.AST__GBDIN,
                        "astTranGrid(" + cls + "): Lower bound of input grid (" + lo[idim]
                                + ") exceeds corresponding upper bound (" + hi[idim] + ").\nError in input dimension "
                                + (idim + 1) + ".");
            }
            mpix *= hi[idim] - lo[idim] + 1;
        }
        // A Java array holds at most Integer.MAX_VALUE elements, where C's limit is its AstDim.
        if (mpix > Integer.MAX_VALUE || mpix < 0) {
            throw Ast.err(
                    AstException.AST__EXSPIX,
                    "astTranGrid(" + cls + "): Supplied grid contains too many points ("
                            + CFormat.format("%g", (double) mpix) + ").");
        }
        int npoint = (int) mpix;
        map.validateMapping(forward, npoint, ncoordIn, ncoordOut, "astTranGrid");
        if (tol < 0.0) {
            throw Ast.err(
                    AstException.AST__PATIN,
                    "astTranGrid(" + cls + "): Invalid positional accuracy tolerance (" + CFormat.format("%.17g", tol)
                            + " pixel).\nThis value should not be less than zero.");
        }
        if (maxpix < 0) {
            throw Ast.err(
                    AstException.AST__SSPIN,
                    "astTranGrid(" + cls + "): Invalid initial scale size in grid points (" + maxpix
                            + ").\nThis value should not be less than zero.");
        }

        // Simplify only when there are enough points to make it worthwhile.
        Mapping simple = map;
        if (npoint > 1024) {
            simple = map.simplify();
            if (forward ? !simple.getTranForward() : !simple.getTranInverse()) {
                throw Ast.err(
                        AstException.AST__TRNND,
                        "astTranGrid(" + cls + "): " + (forward ? "A forward" : "An inverse")
                                + " coordinate transformation is not defined by the " + cls + " supplied.");
            }
        }
        TranGrid grid = new TranGrid(lo, hi, ncoordOut, new double[ncoordOut][npoint]);
        // As C does, the Mapping is inverted while the inverse transformation is applied.
        if (!forward) {
            simple.invert();
        }
        try {
            grid.adaptively(simple, lo, hi, tol, maxpix);
        } finally {
            if (!forward) {
                simple.invert();
            }
        }
        return grid.out;
    }

    /**
     * C's {@code TranGridAdaptively}: transforms the section from {@code lbnd} to {@code ubnd},
     * dividing it in two along its longest axis while the Mapping is not linear enough over it.
     */
    private void adaptively(Mapping map, long[] lbnd, long[] ubnd, double tol, int maxpix) {
        int ncoordIn = lbnd.length;
        long npix = 1;
        long mxdim = 0;
        int dimx = 1;
        long nvertex = 1;
        for (int coordIn = 0; coordIn < ncoordIn; coordIn++) {
            long dim = ubnd[coordIn] - lbnd[coordIn] + 1;
            npix *= dim;
            if (dim > mxdim) {
                mxdim = dim;
                dimx = coordIn;
            }
            nvertex *= 2;
        }
        // The number of points astLinearApprox needs; a section with fewer than four times as many
        // positions is not worth fitting.
        long npoint = 1 + 4L * ncoordIn + 2 * nvertex;
        boolean toosmall = npix < 4 * npoint;
        boolean toobig = maxpix < mxdim;

        double[] linearFit = null;
        boolean divide;
        if (toosmall || tol == 0.0) {
            divide = false;
        } else if (toobig) {
            divide = true;
        } else {
            // Fit over the edges of the cells rather than their centres, so that an axis spanned by
            // one cell still has distinct bounds.
            double[] flbnd = new double[ncoordIn];
            double[] fubnd = new double[ncoordIn];
            for (int i = 0; i < ncoordIn; i++) {
                flbnd[i] = lbnd[i] - 0.5;
                fubnd[i] = ubnd[i] + 0.5;
            }
            linearFit = map.linearApprox(flbnd, fubnd, tol);
            if (linearFit != null) {
                for (double v : linearFit) {
                    if (v == AstObject.AST__BAD) {
                        linearFit = null;
                        break;
                    }
                }
            }
            divide = linearFit == null;
        }

        if (!divide) {
            withBlocking(map, linearFit, lbnd, ubnd);
        } else {
            long[] lo = lbnd.clone();
            long[] hi = ubnd.clone();
            hi[dimx] = (long) Math.floor(0.5 * (lbnd[dimx] + ubnd[dimx]));
            adaptively(map, lo, hi, tol, maxpix);
            lo[dimx] = hi[dimx] + 1;
            hi[dimx] = ubnd[dimx];
            if (lo[dimx] <= hi[dimx]) {
                adaptively(map, lo, hi, tol, maxpix);
            }
        }
    }

    /**
     * C's {@code TranGridWithBlocking}: transforms the section from {@code lbnd} to {@code ubnd}
     * in blocks of at most {@link #MXPIX} positions, shaped like the section.
     */
    private void withBlocking(Mapping map, double[] linearFit, long[] lbnd, long[] ubnd) {
        int ndimIn = lbnd.length;
        // Limit the longest dimension of a block, by a binary chop, until a block holds few enough
        // positions; every dimension of a block is at least 2.
        long npix = 1;
        long mxdimBlock = 0;
        for (int idim = 0; idim < ndimIn; idim++) {
            long dim = ubnd[idim] - lbnd[idim] + 1;
            npix *= dim;
            if (mxdimBlock < dim) {
                mxdimBlock = dim;
            }
        }
        if (npix > MXPIX) {
            long lolim = 1;
            long hilim = mxdimBlock;
            while (hilim - lolim > 1) {
                mxdimBlock = (hilim + lolim) / 2;
                npix = 1;
                for (int idim = 0; idim < ndimIn; idim++) {
                    long dim = ubnd[idim] - lbnd[idim] + 1;
                    npix *= Math.min(dim, mxdimBlock);
                }
                if (npix <= MXPIX) {
                    lolim = mxdimBlock;
                } else {
                    hilim = mxdimBlock;
                }
            }
            mxdimBlock = lolim;
        }
        if (mxdimBlock < 2) {
            mxdimBlock = 2;
        }
        long[] dimBlock = new long[ndimIn];
        long[] lbndBlock = lbnd.clone();
        long[] ubndBlock = new long[ndimIn];
        for (int idim = 0; idim < ndimIn; idim++) {
            long dim = ubnd[idim] - lbnd[idim] + 1;
            dimBlock[idim] = Math.min(dim, mxdimBlock);
            ubndBlock[idim] = Math.min(lbnd[idim] + dimBlock[idim] - 1, ubnd[idim]);
        }
        boolean done = false;
        while (!done) {
            section(map, linearFit, lbndBlock, ubndBlock);
            // Step the block along the least significant axis that has room, returning the axes
            // below it to their start; all blocks are done when the most significant axis returns.
            done = true;
            for (int idim = 0; idim < ndimIn; idim++) {
                if (ubndBlock[idim] < ubnd[idim]) {
                    lbndBlock[idim] = Math.min(lbndBlock[idim] + dimBlock[idim], ubnd[idim]);
                    ubndBlock[idim] = Math.min(lbndBlock[idim] + dimBlock[idim] - 1, ubnd[idim]);
                    done = false;
                    break;
                }
                lbndBlock[idim] = lbnd[idim];
                ubndBlock[idim] = Math.min(lbnd[idim] + dimBlock[idim] - 1, ubnd[idim]);
            }
        }
    }

    /**
     * C's {@code TranGridSection}: transforms the block from {@code lbnd} to {@code ubnd}, through
     * the linear fit if there is one and otherwise through the Mapping, and stores each result at
     * its position in the whole grid.
     */
    private void section(Mapping map, double[] linearFit, long[] lbnd, long[] ubnd) {
        int ndimIn = lbnd.length;
        int ndimOut = ncoordOut;
        int npoint = 1;
        for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
            npoint *= (int) (ubnd[coordIn] - lbnd[coordIn] + 1);
        }
        long[] offset = new long[npoint];
        long[] stride = new long[ndimIn];
        long s = 1;
        for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
            stride[coordIn] = s;
            s *= ubndIn[coordIn] - lbndIn[coordIn] + 1;
        }

        double[][] ptrOut;
        int point = 0;
        if (linearFit != null) {
            double[] zero = linearFit;
            int g = ndimOut;
            ptrOut = new double[ndimOut][npoint];
            if (ndimIn == 1 && ndimOut == 1) {
                long off = lbnd[0] - lbndIn[0];
                double xx1 = zero[0] + linearFit[g] * lbnd[0];
                for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                    ptrOut[0][point] = xx1;
                    xx1 += linearFit[g];
                    offset[point++] = off++;
                }
            } else if (ndimIn == 2 && ndimOut == 2) {
                double x1 = zero[0] + linearFit[g + 1] * (lbnd[1] - 1);
                double y1 = zero[1] + linearFit[g + 3] * (lbnd[1] - 1);
                long off1 = stride[1] * (lbnd[1] - lbndIn[1] - 1) - lbndIn[0];
                for (long iy = lbnd[1]; iy <= ubnd[1]; iy++) {
                    x1 += linearFit[g + 1];
                    y1 += linearFit[g + 3];
                    off1 += stride[1];
                    double xx1 = x1 + linearFit[g] * lbnd[0];
                    double yy1 = y1 + linearFit[g + 2] * lbnd[0];
                    long off = off1 + lbnd[0];
                    for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                        ptrOut[0][point] = xx1;
                        xx1 += linearFit[g];
                        ptrOut[1][point] = yy1;
                        yy1 += linearFit[g + 2];
                        offset[point++] = off++;
                    }
                }
            } else {
                // Accumulate partial sums of the matrix product for the more significant input
                // axes, recomputing only those whose index has changed since the previous position.
                double[] accum = new double[ndimIn * ndimOut];
                long[] dim = lbnd.clone();
                long off = 0;
                for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                    off += stride[coordIn] * (dim[coordIn] - lbndIn[coordIn]);
                }
                for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
                    accum[(coordOut + 1) * ndimIn - 1] = zero[coordOut];
                }
                int coordIn = ndimIn - 1;
                boolean done = false;
                while (!done) {
                    for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
                        int i1 = coordOut * ndimIn;
                        for (int idim = coordIn; idim >= 1; idim--) {
                            int i2 = i1 + idim;
                            accum[i2 - 1] = accum[i2] + dim[idim] * linearFit[g + i2];
                        }
                        ptrOut[coordOut][point] = accum[i1] + dim[0] * linearFit[g + i1];
                    }
                    offset[point++] = off;
                    coordIn = 0;
                    done = true;
                    while (coordIn < ndimIn) {
                        if (dim[coordIn] < ubnd[coordIn]) {
                            dim[coordIn]++;
                            off += stride[coordIn];
                            done = false;
                            break;
                        }
                        dim[coordIn] = lbnd[coordIn];
                        off -= stride[coordIn] * (ubnd[coordIn] - lbnd[coordIn]);
                        coordIn++;
                    }
                }
            }
        } else {
            double[][] ptrIn = new double[ndimIn][npoint];
            if (ndimIn == 1 && ndimOut == 1) {
                for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                    ptrIn[0][point] = ix;
                    offset[point++] = ix - lbndIn[0];
                }
            } else if (ndimIn == 2 && ndimOut == 2) {
                long off1 = stride[1] * (lbnd[1] - lbndIn[1] - 1) - lbndIn[0];
                for (long iy = lbnd[1]; iy <= ubnd[1]; iy++) {
                    off1 += stride[1];
                    long off2 = off1 + lbnd[0];
                    for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                        ptrIn[0][point] = ix;
                        ptrIn[1][point] = iy;
                        offset[point++] = off2++;
                    }
                }
            } else {
                long[] dim = lbnd.clone();
                long off = 0;
                for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                    off += stride[coordIn] * (dim[coordIn] - lbndIn[coordIn]);
                }
                boolean done = false;
                while (!done) {
                    for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                        ptrIn[coordIn][point] = dim[coordIn];
                    }
                    offset[point++] = off;
                    done = true;
                    for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                        if (dim[coordIn] < ubnd[coordIn]) {
                            dim[coordIn]++;
                            off += stride[coordIn];
                            done = false;
                            break;
                        }
                        dim[coordIn] = lbnd[coordIn];
                        off -= stride[coordIn] * (ubnd[coordIn] - lbnd[coordIn]);
                    }
                }
            }
            ptrOut = map.transform(ptrIn, npoint, true);
        }

        for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
            for (int p = 0; p < npoint; p++) {
                out[coordOut][(int) offset[p]] = ptrOut[coordOut][p];
            }
        }
    }
}
