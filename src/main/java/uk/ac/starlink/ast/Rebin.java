package uk.ac.starlink.ast;

import java.util.Objects;

/**
 * Rebinning a grid of data through a Mapping, as C's {@code astRebin<X>} does.
 *
 * <p>The centre of each input pixel of the region is taken through the Mapping's forward
 * transformation to a position in the output grid, and its value spread over the output pixels
 * around that position. To save transforming every pixel, the region is divided recursively along
 * its longest axis until the Mapping is linear within the tolerance over a section, or the section
 * is too small to be worth a linear fit. Each section is then rebinned in blocks of at most {@link
 * #MXPIX} input pixels, whose output positions come from the fit or from the Mapping, and spread
 * by {@link RebinSpreaders}.
 *
 * <p>C's drivers serve {@code astRebinSeq<X>} too, which jniast lacks and which is not ported:
 * where they test a flag only {@code astRebinSeq<X>} accepts, the test is left out, with a comment
 * naming the flag. As in C, simplifying the Mapping for a region of more than 1024 pixels can set
 * the Mapping's own IsSimple flag. A Mapping is used by one thread at a time, as every AST object
 * is; each thread copies the FrameSet it rebins through.
 *
 * <p>Ported from Rust {@code mappings/rebin/mod.rs} and C {@code mapping.c} ({@code Rebin<X>},
 * {@code RebinAdaptively}, {@code RebinWithBlocking} and {@code RebinSection}).
 */
final class Rebin {

    /** C's flag {@code AST__USEVAR}: output variances are formed from the input variances. */
    static final int AST__USEVAR = 16;

    /** The most input pixels rebinned at once (C's {@code mxpix} in {@code RebinWithBlocking}). */
    private static final long MXPIX = 2 * 1024;

    /** C's flags of {@code astRebinSeq<X>}, which {@code Rebin<X>} rejects (mapping.h). */
    private static final int AST__REBININIT = 128;

    private static final int AST__REBINEND = 256;
    private static final int AST__GENVAR = 512;
    private static final int AST__VARWGT = 1024;
    private static final int AST__DISVAR = 4096;
    private static final int AST__NONORM = 8192;
    private static final int AST__PARWGT = 16384;

    /**
     * The flags {@code Rebin<X>} rejects, in the order C tests them, and their names; any other
     * flag but {@code AST__USEBAD} and {@code AST__USEVAR} is "unknown".
     */
    private static final int[] ILLEGAL_FLAGS = {
        AST__REBININIT,
        AST__REBINEND,
        AST__GENVAR,
        AST__DISVAR,
        AST__VARWGT,
        AST__PARWGT,
        AST__NONORM,
        ResampleFlags.AST__CONSERVEFLUX
    };

    private static final String[] ILLEGAL_FLAG_NAMES = {
        "AST__REBININIT",
        "AST__REBINEND",
        "AST__GENVAR",
        "AST__DISVAR",
        "AST__VARWGT",
        "AST__PARWGT",
        "AST__NONORM",
        "AST__CONSERVEFLUX"
    };

    /** The simplified Mapping, whose forward transformation takes input positions to output ones. */
    private final Mapping map;

    private final long[] lbndIn;
    private final long[] ubndIn;
    private final PixelArray in;

    /** The input variances, or null. */
    private final PixelArray inVar;

    /** The spreading scheme (C's {@code spread}). */
    private final int scheme;

    /** The kernel of a kernel scheme, or null for nearest-neighbour and linear spreading. */
    private final ResampleKernels.Setup kernel;

    /** The flags as C's integer. */
    private final int flags;

    private final double tol;
    private final int maxpix;
    private final long[] lbndOut;
    private final long[] ubndOut;
    private final PixelArray out;

    /** The output variances, or null. */
    private final PixelArray outVar;

    /** The sum of the weights given to each output pixel, or null when {@code wlim} is not positive. */
    private final double[] work;

    /** What spreads each block in place of the scheme's spreading function, or null. */
    private final BlockSpreading spreading;

    /**
     * The spreading of a block of input pixels at their output positions: what C's {@code
     * RebinSection} does with the positions it has found.
     */
    @FunctionalInterface
    interface BlockSpreading {

        /**
         * Spreads the block.
         *
         * @param npoint the number of input pixels in the block
         * @param offset the offset of each input pixel in the input array
         * @param coords the output position of each input pixel, {@code [ndimOut][npoint]}
         */
        void spread(int npoint, int[] offset, double[][] coords);
    }

    private Rebin(
            Mapping map,
            long[] lbndIn,
            long[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            Mapping.Spreader spread,
            int flags,
            double tol,
            int maxpix,
            long[] lbndOut,
            long[] ubndOut,
            PixelArray out,
            PixelArray outVar,
            double[] work,
            BlockSpreading spreading,
            String prefix) {
        this.map = map;
        this.lbndIn = lbndIn;
        this.ubndIn = ubndIn;
        this.in = in;
        this.inVar = inVar;
        this.scheme = spread.scheme;
        this.flags = flags;
        this.tol = tol;
        this.maxpix = maxpix;
        this.lbndOut = lbndOut;
        this.ubndOut = ubndOut;
        this.out = out;
        this.outVar = outVar;
        this.work = work;
        this.spreading = spreading;
        // RebinSection's set-up of the scheme; C makes it for each section, always the same.
        switch (scheme) {
            case Mapping.AST__NEAREST:
            case Mapping.AST__LINEAR:
                this.kernel = null;
                break;
            case Mapping.AST__SINC:
            case Mapping.AST__SINCCOS:
            case Mapping.AST__SINCGAUSS:
            case Mapping.AST__GAUSS:
            case Mapping.AST__SINCSINC:
            case Mapping.AST__SOMB:
            case Mapping.AST__SOMBCOS:
                this.kernel = ResampleKernels.setup(scheme, spread.params);
                break;
            default:
                throw Ast.err(AstException.AST__INTER, prefix + "Unexpected pixel spreading scheme (" + scheme + ").");
        }
    }

    /**
     * C's {@code Rebin<X>}: rebins the region from {@code lbnd} to {@code ubnd} of {@code in} (and
     * {@code inVar}) on the input grid into {@code out} (and {@code outVar}) on the output grid,
     * through the forward transformation of {@code map}. The output arrays are set to zero first,
     * and each good input pixel is added into the output pixels around its position; when {@code
     * wlim} is positive, output pixels whose weights sum to less than it are then set bad. {@code
     * flags} holds C's {@code AST__USEBAD} and {@code AST__USEVAR}; variances are rebinned when
     * {@code AST__USEVAR} is set and both variance arrays are given, and otherwise {@code inVar}
     * is ignored and {@code outVar}, if given, is set to zero (and bad where the data are).
     *
     * <p>Before C's checks, a data or variance array must have at least one element per pixel of
     * its grid, and each bound array at least as many elements as its grid has dimensions, which C
     * cannot check; these give {@code AST__DIMIN}, with the messages of Rust's {@code rebin}, and
     * leave the output alone. A longer array is accepted and its leading elements used, as in C
     * and jniast. As in C, an error in the axis counts, bounds, tolerance or region sets the first
     * output value (and variance) to zero, and a later error the whole output.
     *
     * @param method C's name of the function, such as {@code astRebinD}, for error messages
     */
    static void rebin(
            Mapping map,
            double wlim,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            Mapping.Spreader spread,
            int flags,
            double tol,
            int maxpix,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            PixelArray out,
            PixelArray outVar,
            String method) {
        rebin(
                map, wlim, ndimIn, lbndIn, ubndIn, in, inVar, spread, flags, tol, maxpix, ndimOut, lbndOut, ubndOut,
                lbnd, ubnd, out, outVar, method, null);
    }

    /**
     * {@link #rebin} with each block spread by {@code spreading} instead of the scheme's spreading
     * function, unless it is null: the blocks and output positions are those the rebinning
     * spreads, which a test records.
     */
    static void rebin(
            Mapping map,
            double wlim,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            Mapping.Spreader spread,
            int flags,
            double tol,
            int maxpix,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            PixelArray out,
            PixelArray outVar,
            String method,
            BlockSpreading spreading) {
        // A null argument is an error before anything, the output included, is changed.
        Objects.requireNonNull(lbndIn, "lbndIn");
        Objects.requireNonNull(ubndIn, "ubndIn");
        Objects.requireNonNull(spread, "spread");
        Objects.requireNonNull(lbndOut, "lbndOut");
        Objects.requireNonNull(ubndOut, "ubndOut");
        Objects.requireNonNull(lbnd, "lbnd");
        Objects.requireNonNull(ubnd, "ubnd");
        String cls = map.astClass();
        String prefix = method + "(" + cls + "): ";

        // The checks C cannot make, which Rust's rebin and jniast make first.
        Resample.checkBounds(prefix, "input lower bounds", lbndIn, ndimIn);
        Resample.checkBounds(prefix, "input upper bounds", ubndIn, ndimIn);
        Resample.checkBounds(prefix, "output lower bounds", lbndOut, ndimOut);
        Resample.checkBounds(prefix, "output upper bounds", ubndOut, ndimOut);
        Resample.checkBounds(prefix, "input region lower bounds", lbnd, ndimIn);
        Resample.checkBounds(prefix, "input region upper bounds", ubnd, ndimIn);
        // A grid whose bounds are inconsistent has no size; that is C's error to report.
        long nIn = consistentSize(lbndIn, ubndIn, ndimIn);
        long nOut = consistentSize(lbndOut, ubndOut, ndimOut);
        if (nIn >= 0) {
            Resample.checkLength(prefix, "input data", in, nIn);
            Resample.checkLength(prefix, "input variance", inVar, nIn);
        }
        if (nOut >= 0) {
            Resample.checkLength(prefix, "output data", out, nOut);
            Resample.checkLength(prefix, "output variance", outVar, nOut);
        }

        // C reports an error in its arguments and carries on to zero the output, of one pixel when
        // the error came before it counted them.
        int npixOut;
        try {
            npixOut = validate(map, prefix, ndimIn, lbndIn, ubndIn, tol, maxpix, ndimOut, lbndOut, ubndOut, lbnd, ubnd);
        } catch (AstException e) {
            zero(out, outVar, 1);
            throw e;
        }
        zero(out, outVar, npixOut);

        long npix = 1;
        for (int idim = 0; idim < ndimIn; idim++) {
            npix *= (long) ubnd[idim] - lbnd[idim] + 1;
        }
        // Simplify only when there are enough pixels to make it worthwhile; otherwise share the
        // Mapping, as C clones it.
        Mapping simple = npix > 1024 ? map.simplify() : map;
        if (!simple.getTranForward()) {
            throw Ast.err(
                    AstException.AST__TRNND,
                    prefix + "An forward coordinate transformation is not defined by the " + cls + " supplied.");
        }
        for (int i = 0; i < ILLEGAL_FLAGS.length; i++) {
            if ((flags & ILLEGAL_FLAGS[i]) != 0) {
                throw illegalFlag(prefix, ILLEGAL_FLAG_NAMES[i]);
            }
        }
        if ((flags & ~(ResampleFlags.AST__USEBAD | AST__USEVAR)) != 0) {
            throw illegalFlag(prefix, "unknown");
        }

        double[] work = wlim > 0.0 ? new double[npixOut] : null;
        Rebin r = new Rebin(
                simple,
                Resample.longs(lbndIn, ndimIn),
                Resample.longs(ubndIn, ndimIn),
                in,
                inVar,
                spread,
                flags,
                tol,
                maxpix,
                Resample.longs(lbndOut, ndimOut),
                Resample.longs(ubndOut, ndimOut),
                out,
                outVar,
                work,
                spreading,
                prefix);
        // C's report of a flux conservation that could not be made needs AST__CONSERVEFLUX, which
        // Rebin<X> rejects.
        r.adaptively(Resample.longs(lbnd, ndimIn), Resample.longs(ubnd, ndimIn));

        // Output pixels whose weights sum to less than wlim are bad.
        if (work != null) {
            for (int ipix = 0; ipix < npixOut; ipix++) {
                if (Math.abs(work[ipix]) < wlim) {
                    out.setBad(ipix);
                    if (outVar != null) {
                        outVar.setBad(ipix);
                    }
                }
            }
        }
    }

    private static AstException illegalFlag(String prefix, String name) {
        return Ast.err(
                AstException.AST__BADFLG,
                prefix + "An illegal flag (" + name + ") was included in the 'flags' argument.");
    }

    /**
     * The number of pixels in the grid from {@code lbnd} to {@code ubnd}, or -1 if a lower bound
     * exceeds its upper bound or there are more pixels than a {@code long} holds.
     */
    private static long consistentSize(int[] lbnd, int[] ubnd, int ndim) {
        long n = 1;
        for (int idim = 0; idim < ndim; idim++) {
            long dim = (long) ubnd[idim] - lbnd[idim] + 1;
            if (dim <= 0 || n > Long.MAX_VALUE / dim) {
                return -1;
            }
            n *= dim;
        }
        return n;
    }

    /** Sets the first {@code n} values of {@code out} and {@code outVar} (if given) to zero, as far as they go. */
    private static void zero(PixelArray out, PixelArray outVar, int n) {
        for (int i = 0; i < Math.min(n, out.length()); i++) {
            out.set(i, 0.0);
        }
        if (outVar != null) {
            for (int i = 0; i < Math.min(n, outVar.length()); i++) {
                outVar.set(i, 0.0);
            }
        }
    }

    /**
     * {@code Rebin<X>}'s checks of its arguments, in C's order, each reporting as C does, returning
     * the number of output pixels.
     */
    private static int validate(
            Mapping map,
            String prefix,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            double tol,
            int maxpix,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd) {
        String cls = map.astClass();
        int nin = map.getNin();
        int nout = map.getNout();
        if (ndimIn != nin || ndimIn < 1) {
            String msg = prefix + "Bad number of input grid dimensions (" + ndimIn + ").";
            if (ndimIn != nin) {
                msg += "\nThe " + cls + " given requires " + nin + " coordinate value" + (nin == 1 ? "" : "s")
                        + " to specify an input position.";
            }
            throw Ast.err(AstException.AST__NGDIN, msg);
        }
        if (ndimOut != nout || ndimOut < 1) {
            String msg = prefix + "Bad number of output grid dimensions (" + ndimOut + ").";
            if (ndimOut != nout) {
                msg += "\nThe " + cls + " given generates " + (nout < ndimOut ? "only " : "") + nout
                        + " coordinate value" + (nout == 1 ? "" : "s") + " for each output position.";
            }
            throw Ast.err(AstException.AST__NGDIN, msg);
        }
        gridSize(prefix, lbndIn, ubndIn, ndimIn, "input");
        if (tol < 0.0) {
            throw Ast.err(
                    AstException.AST__PATIN,
                    prefix + "Invalid positional accuracy tolerance (" + CFormat.format("%.17g", tol)
                            + " pixel).\nThis value should not be less than zero.");
        }
        if (maxpix < 0) {
            throw Ast.err(
                    AstException.AST__SSPIN,
                    prefix + "Invalid initial scale size in pixels (" + maxpix
                            + ").\nThis value should not be less than zero.");
        }
        int npixOut = gridSize(prefix, lbndOut, ubndOut, ndimOut, "output");

        // The input region must lie within the input grid, so it has no more pixels than the
        // grid, which gridSize has allowed.
        for (int idim = 0; idim < ndimIn; idim++) {
            String detail;
            if (lbnd[idim] > ubnd[idim]) {
                detail = "Lower bound of input region (" + lbnd[idim] + ") exceeds corresponding upper bound ("
                        + ubnd[idim] + ").";
            } else if (lbnd[idim] < lbndIn[idim]) {
                detail = "Lower bound of input region (" + lbnd[idim]
                        + ") is less than corresponding bound of input grid (" + lbndIn[idim] + ").";
            } else if (ubnd[idim] > ubndIn[idim]) {
                detail = "Upper bound of input region (" + ubnd[idim] + ") exceeds corresponding bound of input grid ("
                        + ubndIn[idim] + ").";
            } else {
                continue;
            }
            throw Ast.err(AstException.AST__GBDIN, prefix + detail + "\nError in input dimension " + (idim + 1) + ".");
        }
        return npixOut;
    }

    /**
     * C's check of the bounds of the input or output grid of {@code Rebin<X>}, returning the number
     * of pixels in the grid. A grid with more pixels than a Java array holds gives {@code
     * AST__EXSPIX}, where C's limit is its {@code AstDim}.
     */
    private static int gridSize(String prefix, int[] lbnd, int[] ubnd, int ndim, String what) {
        double mpix = 1.0;
        for (int idim = 0; idim < ndim; idim++) {
            if (lbnd[idim] > ubnd[idim]) {
                throw Ast.err(
                        AstException.AST__GBDIN,
                        prefix + "Lower bound of " + what + " grid (" + lbnd[idim]
                                + ") exceeds corresponding upper bound (" + ubnd[idim] + ").\nError in " + what
                                + " dimension " + (idim + 1) + ".");
            }
            mpix *= (double) ubnd[idim] - lbnd[idim] + 1;
        }
        if (mpix > Integer.MAX_VALUE) {
            throw Ast.err(
                    AstException.AST__EXSPIX,
                    prefix + "Supplied " + what + " array contains too many pixels (" + CFormat.format("%g", mpix)
                            + ").");
        }
        return (int) mpix;
    }

    /**
     * C's {@code RebinAdaptively}: rebins the input section from {@code lbnd} to {@code ubnd},
     * dividing it in two along its longest axis while the Mapping is not linear enough over it.
     */
    private void adaptively(long[] lbnd, long[] ubnd) {
        int ndimIn = lbnd.length;
        long npix = 1;
        long mxdim = 0;
        int dimx = 1;
        long nvertex = 1;
        for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
            long dim = ubnd[coordIn] - lbnd[coordIn] + 1;
            npix *= dim;
            if (dim > mxdim) {
                mxdim = dim;
                dimx = coordIn;
            }
            nvertex *= 2;
        }
        // The number of points astLinearApprox needs; a section with fewer than four times as many
        // pixels is not worth fitting.
        long npoint = 1 + 4L * ndimIn + 2 * nvertex;
        boolean toosmall = npix < 4 * npoint;
        boolean toobig = maxpix < mxdim;

        // C always fits with AST__CONSERVEFLUX, which Rebin<X> rejects.
        double[] linearFit = null;
        boolean divide;
        if (toosmall || tol == 0.0) {
            divide = false;
        } else if (toobig) {
            divide = true;
        } else {
            // Fit over the edges of the pixels rather than their centres, so that an axis spanned
            // by one pixel still has distinct bounds.
            double[] flbnd = new double[ndimIn];
            double[] fubnd = new double[ndimIn];
            for (int i = 0; i < ndimIn; i++) {
                flbnd[i] = lbnd[i] - 0.5;
                fubnd[i] = ubnd[i] + 0.5;
            }
            linearFit = LinearApprox.linearApprox(map, flbnd, fubnd, tol, true);
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
            withBlocking(linearFit, lbnd, ubnd);
            return;
        }
        long[] lo = lbnd.clone();
        long[] hi = ubnd.clone();
        hi[dimx] = (long) Math.floor(0.5 * (lbnd[dimx] + ubnd[dimx]));
        adaptively(lo, hi);
        lo[dimx] = hi[dimx] + 1;
        hi[dimx] = ubnd[dimx];
        if (lo[dimx] <= hi[dimx]) {
            adaptively(lo, hi);
        }
    }

    /**
     * C's {@code RebinWithBlocking}: rebins the section from {@code lbnd} to {@code ubnd} in blocks
     * of at most {@link #MXPIX} input pixels, shaped like the section.
     */
    private void withBlocking(double[] linearFit, long[] lbnd, long[] ubnd) {
        int ndimIn = lbnd.length;
        // Limit the longest dimension of a block, by a binary chop, until a block holds few enough
        // pixels; every dimension of a block is at least 2.
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

        // C's flux conservation factor is 1 without AST__CONSERVEFLUX, which Rebin<X> rejects.
        boolean done = false;
        while (!done) {
            section(linearFit, lbndBlock, ubndBlock);
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
     * C's {@code RebinSection}: the output position of each input pixel of the block from {@code
     * lbnd} to {@code ubnd}, from the linear fit if there is one and otherwise from the Mapping's
     * forward transformation, and the spreading of its value there.
     */
    private void section(double[] linearFit, long[] lbnd, long[] ubnd) {
        int ndimIn = lbnd.length;
        int ndimOut = lbndOut.length;
        int npoint = 1;
        for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
            npoint *= (int) (ubnd[coordIn] - lbnd[coordIn] + 1);
        }
        int[] offset = new int[npoint];
        long[] stride = new long[ndimIn];
        long s = 1;
        for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
            stride[coordIn] = s;
            s *= ubndIn[coordIn] - lbndIn[coordIn] + 1;
        }

        double[][] ptrOut;
        int point = 0;
        if (linearFit != null) {
            // The fit's zero points, then its gradients row by row.
            int g = ndimOut;
            ptrOut = new double[ndimOut][npoint];
            if (ndimIn == 1 && ndimOut == 1) {
                // C steps along the row by adding the gradient.
                long off = lbnd[0] - lbndIn[0];
                double xx1 = linearFit[0] + linearFit[g] * lbnd[0];
                for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                    ptrOut[0][point] = xx1;
                    xx1 += linearFit[g];
                    offset[point++] = (int) off++;
                }
            } else if (ndimIn == 2 && ndimOut == 2) {
                // C steps along rows and columns by adding the gradients.
                double x1 = linearFit[0] + linearFit[g + 1] * (lbnd[1] - 1);
                double y1 = linearFit[1] + linearFit[g + 3] * (lbnd[1] - 1);
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
                        offset[point++] = (int) off++;
                    }
                }
            } else {
                // Accumulate partial sums of the matrix product for the more significant input
                // axes, recomputing only those whose index has changed since the previous pixel.
                double[] accum = new double[ndimIn * ndimOut];
                long[] dim = lbnd.clone();
                long off = 0;
                for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                    off += stride[coordIn] * (dim[coordIn] - lbndIn[coordIn]);
                }
                for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
                    accum[(coordOut + 1) * ndimIn - 1] = linearFit[coordOut];
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
                    offset[point++] = (int) off;
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
                    offset[point++] = (int) (ix - lbndIn[0]);
                }
            } else if (ndimIn == 2 && ndimOut == 2) {
                long off1 = stride[1] * (lbnd[1] - lbndIn[1] - 1) - lbndIn[0];
                for (long iy = lbnd[1]; iy <= ubnd[1]; iy++) {
                    off1 += stride[1];
                    long off2 = off1 + lbnd[0];
                    for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                        ptrIn[0][point] = ix;
                        ptrIn[1][point] = iy;
                        offset[point++] = (int) off2++;
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
                    offset[point++] = (int) off;
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
        if (spreading == null) {
            spreadBlock(npoint, offset, ptrOut);
        } else {
            spreading.spread(npoint, offset, ptrOut);
        }
    }

    /** {@code RebinSection}'s call of the spreading function for the scheme. */
    private void spreadBlock(int npoint, int[] offset, double[][] ptrOut) {
        switch (scheme) {
            case Mapping.AST__NEAREST:
                RebinSpreaders.nearest(lbndOut, ubndOut, in, inVar, npoint, offset, ptrOut, flags, out, outVar, work);
                break;
            case Mapping.AST__LINEAR:
                RebinSpreaders.linear(lbndOut, ubndOut, in, inVar, npoint, offset, ptrOut, flags, out, outVar, work);
                break;
            case Mapping.AST__SINC:
            case Mapping.AST__SINCCOS:
            case Mapping.AST__SINCGAUSS:
            case Mapping.AST__GAUSS:
            case Mapping.AST__SINCSINC:
            case Mapping.AST__SOMB:
            case Mapping.AST__SOMBCOS:
                RebinSpreaders.kernel1(
                        lbndOut,
                        ubndOut,
                        in,
                        inVar,
                        npoint,
                        offset,
                        ptrOut,
                        kernel.kernel,
                        kernel.neighb,
                        kernel.params,
                        flags,
                        out,
                        outVar,
                        work);
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "Unexpected pixel spreading scheme (" + scheme + ").");
        }
    }
}
