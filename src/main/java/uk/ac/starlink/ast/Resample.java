package uk.ac.starlink.ast;

import java.util.Arrays;
import uk.ac.starlink.ast.internal.pal.Dmat;

/**
 * Resampling a grid of data through a Mapping, as C's {@code astResample<X>} does.
 *
 * <p>The centre of each pixel of the output region is taken back through the Mapping's inverse to
 * a position in the input grid, where the input is interpolated. To save transforming every
 * pixel, the region is divided recursively along its longest axis until the inverse is linear
 * within the tolerance over a section, or the section is too small to be worth a linear fit. Each
 * section is then resampled in blocks of at most {@link #MXPIX} output pixels, whose input
 * positions come from the fit or from the Mapping.
 *
 * <p>C inverts the Mapping in place while it fits its inverse; this class fits the inverse
 * transformation directly, leaving the Mapping's Invert attribute alone. As in C, simplifying the
 * Mapping for a region of more than 1024 pixels can set the Mapping's own IsSimple flag. A Mapping
 * is used by one thread at a time, as every AST object is; each thread copies the FrameSet it
 * resamples through.
 *
 * <p>Ported from Rust {@code mappings/resample/mod.rs} and C {@code mapping.c} ({@code
 * Resample<X>}, {@code ResampleAdaptively}, {@code ResampleWithBlocking}, {@code ResampleSection},
 * {@code ConserveFlux<X>} and {@code MatrixDet}).
 */
final class Resample {

    /** The most output pixels resampled at once (C's {@code mxpix} in {@code ResampleWithBlocking}). */
    private static final long MXPIX = 2 * 1024;

    /** The simplified Mapping, whose inverse takes output positions to input ones. */
    private final Mapping map;

    /** C's name of the resampling function, for error messages. */
    private final String method;

    /** The class of the Mapping given, for error messages (C's {@code unsimplified_mapping}). */
    private final String cls;

    private final long[] lbndIn;
    private final long[] ubndIn;
    private final PixelArray in;

    /** The input variances, or null when variances are not resampled. */
    private final PixelArray inVar;

    private final Mapping.Interpolator interp;

    /** The flags as C's integer. */
    private final int flags;

    private final double tol;
    private final int maxpix;
    private final long[] lbndOut;
    private final long[] ubndOut;
    private final PixelArray out;

    /** The output variances, or null when variances are not resampled. */
    private final PixelArray outVar;

    private Resample(
            Mapping map,
            String method,
            String cls,
            long[] lbndIn,
            long[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            Mapping.Interpolator interp,
            int flags,
            double tol,
            int maxpix,
            long[] lbndOut,
            long[] ubndOut,
            PixelArray out,
            PixelArray outVar) {
        this.map = map;
        this.method = method;
        this.cls = cls;
        this.lbndIn = lbndIn;
        this.ubndIn = ubndIn;
        this.in = in;
        this.inVar = inVar;
        this.interp = interp;
        this.flags = flags;
        this.tol = tol;
        this.maxpix = maxpix;
        this.lbndOut = lbndOut;
        this.ubndOut = ubndOut;
        this.out = out;
        this.outVar = outVar;
    }

    /**
     * C's {@code Resample<X>}: resamples {@code in} (and {@code inVar}) on the input grid into the
     * region from {@code lbnd} to {@code ubnd} of {@code out} (and {@code outVar}) on the output
     * grid, through the inverse of {@code map}.
     *
     * <p>Besides C's checks, a data or variance array must have at least one element per pixel of
     * its grid, and each bound array at least as many elements as its grid has dimensions, which C
     * cannot check; these give {@code AST__DIMIN}, with the messages of Rust's {@code resample}. A
     * longer array is accepted and its leading elements used, as in C and jniast.
     *
     * @param method C's name of the function, such as {@code astResampleD}, for error messages
     * @return the number of output pixels (and variances) given the bad value
     */
    static int resample(
            Mapping map,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            Mapping.Interpolator interp,
            ResampleFlags flags,
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
        String cls = map.astClass();
        String prefix = method + "(" + cls + "): ";
        int iflags = flags.getFlagsInt();
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
        checkBounds(prefix, "input lower bounds", lbndIn, ndimIn);
        checkBounds(prefix, "input upper bounds", ubndIn, ndimIn);
        checkBounds(prefix, "output lower bounds", lbndOut, ndimOut);
        checkBounds(prefix, "output upper bounds", ubndOut, ndimOut);
        checkBounds(prefix, "output region lower bounds", lbnd, ndimOut);
        checkBounds(prefix, "output region upper bounds", ubnd, ndimOut);

        long npixIn = gridSize(prefix, lbndIn, ubndIn, ndimIn, "input");
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
        long npixOut = gridSize(prefix, lbndOut, ubndOut, ndimOut, "output");

        // The output region must lie within the output grid. The region is no larger than the
        // grid, so its number of pixels cannot exceed what gridSize has allowed.
        long npix = 1;
        for (int idim = 0; idim < ndimOut; idim++) {
            String detail;
            if (lbnd[idim] > ubnd[idim]) {
                detail = "Lower bound of output region (" + lbnd[idim] + ") exceeds corresponding upper bound ("
                        + ubnd[idim] + ").";
            } else if (lbnd[idim] < lbndOut[idim]) {
                detail = "Lower bound of output region (" + lbnd[idim]
                        + ") is less than corresponding bound of output grid (" + lbndOut[idim] + ").";
            } else if (ubnd[idim] > ubndOut[idim]) {
                detail = "Upper bound of output region (" + ubnd[idim]
                        + ") exceeds corresponding bound of output grid (" + ubndOut[idim] + ").";
            } else {
                npix *= (long) ubnd[idim] - lbnd[idim] + 1;
                continue;
            }
            throw Ast.err(AstException.AST__GBDIN, prefix + detail + "\nError in output dimension " + (idim + 1) + ".");
        }

        if ((iflags & ResampleFlags.AST__CONSERVEFLUX) != 0) {
            if (tol == 0.0) {
                throw Ast.err(
                        AstException.AST__CNFLX,
                        prefix + "Flux conservation was requested but cannot be performed because zero tolerance"
                                + " was also specified.");
            } else if (nin != nout) {
                throw Ast.err(
                        AstException.AST__CNFLX,
                        prefix + "Flux conservation was requested but cannot be performed because the Mapping has"
                                + " different numbers of inputs and outputs.");
            }
        }

        checkLength(prefix, "input data", in, npixIn);
        checkLength(prefix, "input variance", inVar, npixIn);
        checkLength(prefix, "output data", out, npixOut);
        checkLength(prefix, "output variance", outVar, npixOut);

        // Simplify only when there are enough pixels to make it worthwhile; otherwise share the
        // Mapping, as C clones it.
        Mapping simple = npix > 1024 ? map.simplify() : map;
        if (!simple.getTranInverse()) {
            throw Ast.err(
                    AstException.AST__TRNND,
                    prefix + "An inverse coordinate transformation is not defined by the " + cls + " supplied.");
        }

        boolean usevar = inVar != null && outVar != null;
        Resample r = new Resample(
                simple,
                method,
                cls,
                longs(lbndIn, ndimIn),
                longs(ubndIn, ndimIn),
                in,
                usevar ? inVar : null,
                interp,
                iflags,
                tol,
                maxpix,
                longs(lbndOut, ndimOut),
                longs(ubndOut, ndimOut),
                out,
                usevar ? outVar : null);
        return r.adaptively(longs(lbnd, ndimOut), longs(ubnd, ndimOut));
    }

    /** The first {@code n} values of {@code a}, widened so that no difference of bounds overflows. */
    private static long[] longs(int[] a, int n) {
        return Arrays.stream(a, 0, n).asLongStream().toArray();
    }

    /** Rust's check that a bound array has a value for each dimension. */
    private static void checkBounds(String prefix, String name, int[] a, int n) {
        if (a.length < n) {
            throw Ast.err(
                    AstException.AST__DIMIN, prefix + a.length + " " + name + " supplied for " + n + " dimensions.");
        }
    }

    /** Rust's check that a data or variance array, if given, has an element for each pixel of its grid. */
    private static void checkLength(String prefix, String what, PixelArray a, long want) {
        if (a != null && a.length() < want) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    prefix + "The " + what + " array has " + a.length() + " elements but its grid has " + want + ".");
        }
    }

    /**
     * C's check of the bounds of the input or output grid of {@code Resample<X>}, returning the
     * number of pixels in the grid. A grid with more pixels than a Java array holds gives {@code
     * AST__EXSPIX}, where C's limit is its {@code AstDim}.
     */
    private static long gridSize(String prefix, int[] lbnd, int[] ubnd, int ndim, String what) {
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
            // C's message for the output grid ends with two full stops.
            throw Ast.err(
                    AstException.AST__EXSPIX,
                    prefix + "Supplied " + what + " array contains too many pixels (" + CFormat.format("%g", mpix) + ")"
                            + ("input".equals(what) ? "." : ".."));
        }
        return (long) mpix;
    }

    /**
     * C's {@code ResampleAdaptively}: resamples the output section from {@code lbnd} to {@code
     * ubnd}, dividing it in two along its longest axis while the inverse is not linear enough over
     * it.
     */
    private int adaptively(long[] lbnd, long[] ubnd) {
        int ndimOut = lbnd.length;
        long npix = 1;
        long mxdim = 0;
        int dimx = 1;
        long nvertex = 1;
        for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
            long dim = ubnd[coordOut] - lbnd[coordOut] + 1;
            npix *= dim;
            if (dim > mxdim) {
                mxdim = dim;
                dimx = coordOut;
            }
            nvertex *= 2;
        }
        // The number of points astLinearApprox needs; a section with fewer than four times as many
        // pixels is not worth fitting.
        long npoint = 1 + 4L * ndimOut + 2 * nvertex;
        boolean toosmall = npix < 4 * npoint;
        boolean toobig = maxpix < mxdim;

        double[] linearFit = null;
        boolean divide;
        if (toosmall || tol == 0.0) {
            divide = false;
        } else if (toobig) {
            divide = true;
        } else {
            // Fit over the edges of the pixels rather than their centres, so that an axis spanned
            // by one pixel still has distinct bounds.
            double[] flbnd = new double[ndimOut];
            double[] fubnd = new double[ndimOut];
            for (int i = 0; i < ndimOut; i++) {
                flbnd[i] = lbnd[i] - 0.5;
                fubnd[i] = ubnd[i] + 0.5;
            }
            linearFit = LinearApprox.linearApprox(map, flbnd, fubnd, tol, false);
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
            return withBlocking(linearFit, lbnd, ubnd);
        }
        long[] lo = lbnd.clone();
        long[] hi = ubnd.clone();
        hi[dimx] = (long) Math.floor(0.5 * (lbnd[dimx] + ubnd[dimx]));
        int result = adaptively(lo, hi);
        lo[dimx] = hi[dimx] + 1;
        hi[dimx] = ubnd[dimx];
        if (lo[dimx] <= hi[dimx]) {
            result += adaptively(lo, hi);
        }
        return result;
    }

    /**
     * C's {@code ResampleWithBlocking}: resamples the section from {@code lbnd} to {@code ubnd} in
     * blocks of at most {@link #MXPIX} output pixels, shaped like the section.
     */
    private int withBlocking(double[] linearFit, long[] lbnd, long[] ubnd) {
        int ndimOut = lbnd.length;
        // Limit the longest dimension of a block, by a binary chop, until a block holds few enough
        // pixels; every dimension of a block is at least 2.
        long npix = 1;
        long mxdimBlock = 0;
        for (int idim = 0; idim < ndimOut; idim++) {
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
                for (int idim = 0; idim < ndimOut; idim++) {
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
        long[] dimBlock = new long[ndimOut];
        long[] lbndBlock = lbnd.clone();
        long[] ubndBlock = new long[ndimOut];
        for (int idim = 0; idim < ndimOut; idim++) {
            long dim = ubnd[idim] - lbnd[idim] + 1;
            dimBlock[idim] = Math.min(dim, mxdimBlock);
            ubndBlock[idim] = Math.min(lbnd[idim] + dimBlock[idim] - 1, ubnd[idim]);
        }

        // The factor that conserves flux: the ratio of output to input pixel sizes, from the
        // gradients of the fit.
        double factor;
        if ((flags & ResampleFlags.AST__CONSERVEFLUX) != 0 && linearFit != null) {
            int ndimIn = lbndIn.length;
            double[] grad = new double[ndimIn * ndimOut];
            System.arraycopy(linearFit, ndimIn, grad, 0, grad.length);
            factor = Math.abs(matrixDet(ndimIn, ndimOut, grad));
        } else {
            factor = 1.0;
        }

        int result = 0;
        boolean done = false;
        while (!done) {
            result += section(linearFit, lbndBlock, ubndBlock, factor);
            // Step the block along the least significant axis that has room, returning the axes
            // below it to their start; all blocks are done when the most significant axis returns.
            done = true;
            for (int idim = 0; idim < ndimOut; idim++) {
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
        return result;
    }

    /**
     * C's {@code ResampleSection}: the input position of each output pixel of the block from
     * {@code lbnd} to {@code ubnd}, from the linear fit if there is one and otherwise from the
     * Mapping's inverse, and the values interpolated there, scaled by {@code factor} when
     * conserving flux.
     */
    private int section(double[] linearFit, long[] lbnd, long[] ubnd, double factor) {
        boolean conserve = (flags & ResampleFlags.AST__CONSERVEFLUX) != 0;
        // Flux conservation needs bad values to tell which output pixels were assigned.
        if ((flags & ResampleFlags.AST__NOBAD) != 0 && conserve) {
            throw Ast.err(
                    AstException.AST__BADFLG,
                    "astResample: Cannot use the AST__NOBAD and AST__CONSERVEFLUX flags together (programming"
                            + " error).");
        }
        int ndimOut = lbnd.length;
        int ndimIn = lbndIn.length;
        int npoint = 1;
        for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
            npoint *= (int) (ubnd[coordOut] - lbnd[coordOut] + 1);
        }
        int[] offset = new int[npoint];
        long[] stride = new long[ndimOut];
        long s = 1;
        for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
            stride[coordOut] = s;
            s *= ubndOut[coordOut] - lbndOut[coordOut] + 1;
        }

        double[][] ptrIn;
        int point = 0;
        if (linearFit != null) {
            int g = ndimIn;
            ptrIn = new double[ndimIn][npoint];
            if (ndimIn == 1 && ndimOut == 1) {
                for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                    ptrIn[0][point] = linearFit[0] + linearFit[g] * ix;
                    offset[point++] = (int) (ix - lbndOut[0]);
                }
            } else if (ndimIn == 2 && ndimOut == 2) {
                for (long iy = lbnd[1]; iy <= ubnd[1]; iy++) {
                    double x1 = linearFit[0] + linearFit[g + 1] * iy;
                    double y1 = linearFit[1] + linearFit[g + 3] * iy;
                    long off1 = stride[1] * (iy - lbndOut[1]) - lbndOut[0];
                    for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                        ptrIn[0][point] = x1 + linearFit[g] * ix;
                        ptrIn[1][point] = y1 + linearFit[g + 2] * ix;
                        offset[point++] = (int) (off1 + ix);
                    }
                }
            } else {
                // Accumulate partial sums of the matrix product for the more significant output
                // axes, recomputing only those whose index has changed since the previous pixel.
                double[] accum = new double[ndimIn * ndimOut];
                long[] dim = lbnd.clone();
                long off = 0;
                for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
                    off += stride[coordOut] * (dim[coordOut] - lbndOut[coordOut]);
                }
                for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                    accum[(coordIn + 1) * ndimOut - 1] = linearFit[coordIn];
                }
                int coordOut = ndimOut - 1;
                boolean done = false;
                while (!done) {
                    for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                        int i1 = coordIn * ndimOut;
                        for (int idim = coordOut; idim >= 1; idim--) {
                            int i2 = i1 + idim;
                            accum[i2 - 1] = accum[i2] + dim[idim] * linearFit[g + i2];
                        }
                        ptrIn[coordIn][point] = accum[i1] + dim[0] * linearFit[g + i1];
                    }
                    offset[point++] = (int) off;
                    coordOut = 0;
                    done = true;
                    while (coordOut < ndimOut) {
                        if (dim[coordOut] < ubnd[coordOut]) {
                            dim[coordOut]++;
                            off += stride[coordOut];
                            done = false;
                            break;
                        }
                        dim[coordOut] = lbnd[coordOut];
                        off -= stride[coordOut] * (ubnd[coordOut] - lbnd[coordOut]);
                        coordOut++;
                    }
                }
            }
        } else {
            // Flux can be conserved only where a linear fit gives the ratio of pixel sizes.
            if (conserve) {
                throw Ast.err(
                        AstException.AST__CNFLX,
                        "astResampleSection(" + map.astClass() + "): Flux conservation was requested but cannot be"
                                + " performed because either the Mapping is too non-linear, or the requested"
                                + " tolerance is too small.");
            }
            double[][] ptrOut = new double[ndimOut][npoint];
            if (ndimOut == 1 && ndimIn == 1) {
                for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                    ptrOut[0][point] = ix;
                    offset[point++] = (int) (ix - lbndOut[0]);
                }
            } else if (ndimOut == 2 && ndimIn == 2) {
                for (long iy = lbnd[1]; iy <= ubnd[1]; iy++) {
                    long off1 = stride[1] * (iy - lbndOut[1]) - lbndOut[0];
                    for (long ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                        ptrOut[0][point] = ix;
                        ptrOut[1][point] = iy;
                        offset[point++] = (int) (off1 + ix);
                    }
                }
            } else {
                long[] dim = lbnd.clone();
                long off = 0;
                for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
                    off += stride[coordOut] * (dim[coordOut] - lbndOut[coordOut]);
                }
                boolean done = false;
                while (!done) {
                    for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
                        ptrOut[coordOut][point] = dim[coordOut];
                    }
                    offset[point++] = (int) off;
                    done = true;
                    for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
                        if (dim[coordOut] < ubnd[coordOut]) {
                            dim[coordOut]++;
                            off += stride[coordOut];
                            done = false;
                            break;
                        }
                        dim[coordOut] = lbnd[coordOut];
                        off -= stride[coordOut] * (ubnd[coordOut] - lbnd[coordOut]);
                    }
                }
            }
            ptrIn = map.transform(ptrOut, npoint, false);
        }

        int result = ResampleInterpolators.interpolate(
                interp, method, cls, lbndIn, ubndIn, in, inVar, npoint, offset, ptrIn, flags, out, outVar);

        if (conserve) {
            conserveFlux(factor, npoint, offset);
        }
        return result;
    }

    /**
     * C's {@code ConserveFlux<X>}: multiplies each output value of the block that is not bad by
     * {@code factor}, and each such variance by its square, converting the product to the type as
     * C's {@code *=} does.
     */
    private void conserveFlux(double factor, int npoint, int[] offset) {
        for (int point = 0; point < npoint; point++) {
            int offOut = offset[point];
            if (!out.isBad(offOut)) {
                out.set(offOut, out.get(offOut) * factor);
            }
        }
        if (outVar != null) {
            double f2 = factor * factor;
            for (int point = 0; point < npoint; point++) {
                int offOut = offset[point];
                if (!outVar.isBad(offOut)) {
                    outVar.set(offOut, outVar.get(offOut) * f2);
                }
            }
        }
    }

    /**
     * C's {@code MatrixDet}: the determinant of the {@code nrow} by {@code ncol} matrix {@code
     * matrix} (row by row) once the rows and columns holding only zeros or bad values are removed,
     * or {@code AST__BAD} if what remains is not square.
     */
    static double matrixDet(int nrow, int ncol, double[] matrix) {
        boolean[] userow = new boolean[nrow];
        boolean[] usecol = new boolean[ncol];
        for (int irow = 0; irow < nrow; irow++) {
            for (int icol = 0; icol < ncol; icol++) {
                double m = matrix[irow * ncol + icol];
                if (m != AstObject.AST__BAD && m != 0.0) {
                    usecol[icol] = true;
                    userow[irow] = true;
                }
            }
        }
        int nrowuse = 0;
        for (boolean u : userow) {
            if (u) {
                nrowuse++;
            }
        }
        int ncoluse = 0;
        for (boolean u : usecol) {
            if (u) {
                ncoluse++;
            }
        }
        if (ncoluse != nrowuse) {
            return AstObject.AST__BAD;
        }
        int ndim = ncoluse;
        double[] sqmat = new double[ndim * ndim];
        int k = 0;
        for (int irow = 0; irow < nrow; irow++) {
            if (userow[irow]) {
                for (int icol = 0; icol < ncol; icol++) {
                    if (usecol[icol]) {
                        sqmat[k++] = matrix[irow * ncol + icol];
                    }
                }
            }
        }
        if (ndim == 0) {
            // C allocates nothing for an empty matrix, so leaves the result bad.
            return AstObject.AST__BAD;
        } else if (ndim == 1) {
            return sqmat[0];
        } else if (ndim == 2) {
            return sqmat[0] * sqmat[3] - sqmat[1] * sqmat[2];
        } else {
            double[] y = new double[ndim];
            Arrays.fill(y, 1.0);
            return Dmat.dmat(ndim, sqmat, y);
        }
    }
}
