package uk.ac.starlink.ast;

/**
 * The sub-pixel interpolation schemes of resampling: each fills the output pixels of one block
 * from their positions in the input grid, and returns the number of output values (and variances)
 * it set bad.
 *
 * <p>For every scheme but block averaging, a position outside the input grid, including one on its
 * upper edge, or a bad coordinate gives a bad output pixel, as does (with {@code AST__USEBAD}) a
 * bad input pixel containing the position; a position on a pixel boundary belongs to the pixel
 * with the larger index. Variances are interpolated only when both {@code inVar} and {@code
 * outVar} are given; a variance is bad when it equals the bad value (with {@code AST__USEBAD}) or
 * is negative.
 *
 * <p>C writes each scheme as eight loops, one for each combination of {@code AST__USEBAD}, {@code
 * AST__NOBAD} and the presence of variances, so that the compiler can drop the tests a case does
 * not need. Each scheme here makes those tests in one loop, with C's arithmetic in C's order.
 *
 * <p>A caller's kernel or interpolation function that throws an {@link Exception} ends the
 * resampling with an {@link AstException} whose status is {@code AST__UK1ER} or {@code
 * AST__UINER}, the statuses jniast's callbacks set, and whose cause is that exception; an {@link
 * Error} passes through unchanged. Either way the output holds what was set before the failure.
 *
 * <p>Ported from Rust {@code mappings/resample/interp.rs} and C {@code mapping.c} ({@code
 * InterpolateNearest<X>}, {@code InterpolateLinear<X>}, {@code InterpolateKernel1<X>}, {@code
 * InterpolateBlockAverage<X>}, and the scheme dispatch of {@code ResampleSection}), and jniast's
 * {@code jni/Mapping.c} ({@code fukern1} and {@code fuinterp<X>}).
 */
final class ResampleInterpolators {

    private ResampleInterpolators() {}

    /**
     * The interpolation of {@code ResampleSection}: the scheme of {@code interp} applied to the
     * {@code npoint} output pixels whose offsets in the output arrays are {@code offset} and whose
     * positions in the input grid are {@code coords[axis][point]}.
     *
     * @param method C's name of the resampling function, for error messages
     * @param cls the class of the Mapping given, for error messages
     * @param flags the flags as C's integer
     * @param flagsObject the flags object given to the resampling method, which a caller's
     *     interpolation function is given
     * @return the number of output values and variances set bad
     */
    static int interpolate(
            Mapping.Interpolator interp,
            String method,
            String cls,
            long[] lbndIn,
            long[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            int flags,
            ResampleFlags flagsObject,
            PixelArray out,
            PixelArray outVar) {
        switch (interp.scheme) {
            case Mapping.AST__NEAREST:
                return nearest(lbndIn, ubndIn, in, inVar, npoint, offset, coords, flags, out, outVar);
            case Mapping.AST__LINEAR:
                return linear(lbndIn, ubndIn, in, inVar, npoint, offset, coords, flags, out, outVar);
            case Mapping.AST__SINC:
            case Mapping.AST__SINCCOS:
            case Mapping.AST__SINCGAUSS:
            case Mapping.AST__SINCSINC:
            case Mapping.AST__SOMB:
            case Mapping.AST__SOMBCOS:
            case Mapping.AST__GAUSS: {
                ResampleKernels.Setup setup = ResampleKernels.setup(interp.scheme, interp.params);
                return kernel1(
                        lbndIn,
                        ubndIn,
                        in,
                        inVar,
                        npoint,
                        offset,
                        coords,
                        flags,
                        setup.kernel,
                        setup.neighb,
                        setup.params,
                        out,
                        outVar);
            }
            case Mapping.AST__UKERN1: {
                // C's MaxI( 1, (int) floor( params[ 0 ] + 0.5 ) ).
                int neighb = Math.max(1, (int) Math.floor(interp.params[0] + 0.5));
                ResampleKernels.Kernel kernel = callersKernel(interp.ukern1er, method, cls);
                return kernel1(
                        lbndIn,
                        ubndIn,
                        in,
                        inVar,
                        npoint,
                        offset,
                        coords,
                        flags,
                        kernel,
                        neighb,
                        interp.params,
                        out,
                        outVar);
            }
            case Mapping.AST__BLOCKAVE:
                return blockAverage(
                        lbndIn, ubndIn, in, inVar, npoint, offset, coords, flags, interp.params, out, outVar);
            case Mapping.AST__UINTERP:
                return callersInterpolation(
                        interp.uinterper,
                        method,
                        cls,
                        lbndIn,
                        ubndIn,
                        in,
                        inVar,
                        npoint,
                        offset,
                        coords,
                        flagsObject,
                        out,
                        outVar);
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        method + "(" + cls + "): Unexpected sub-pixel interpolation scheme (" + interp.scheme + ").");
        }
    }

    /**
     * The error with which a caller's function that threw {@code cause} ends the resampling: C's
     * message, with the status jniast's callback sets, and {@code cause} as its cause.
     */
    private static AstException callersError(int status, String message, Exception cause) {
        AstException err = Ast.err(status, message);
        err.initCause(cause);
        return err;
    }

    /**
     * The caller's kernel {@code calc} as a kernel, as jniast's {@code fukern1} calls it: with the
     * offset alone.
     */
    // Ukern1Calculator.ukern1 is declared to throw any Exception; each one is the kernel's failure,
    // and an Error passes through.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static ResampleKernels.Kernel callersKernel(Ukern1Calculator calc, String method, String cls) {
        return (offset, params) -> {
            try {
                return calc.ukern1(offset);
            } catch (Exception e) {
                throw callersError(
                        AstException.AST__UK1ER,
                        method + "(" + cls + "): Error signalled by user-supplied 1-d interpolation kernel.",
                        e);
            }
        };
    }

    /**
     * C's call of a caller's interpolation function, as jniast's {@code fuinterp<X>} makes it: the
     * {@code uinterp<X>} method of {@code calc} for the data's type, given the caller's own data,
     * variance and output arrays, new arrays of the input bounds and of the offsets, the positions,
     * the flags object and the bad value. Returns the number of bad pixels it returns.
     */
    // UinterpCalculator's methods are declared to throw any Exception; each one is the function's
    // failure, and an Error passes through.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static int callersInterpolation(
            UinterpCalculator calc,
            String method,
            String cls,
            long[] lbndIn,
            long[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            ResampleFlags flags,
            PixelArray out,
            PixelArray outVar) {
        int ndimIn = lbndIn.length;
        int[] lbnd = new int[ndimIn];
        int[] ubnd = new int[ndimIn];
        for (int i = 0; i < ndimIn; i++) {
            lbnd[i] = (int) lbndIn[i];
            ubnd[i] = (int) ubndIn[i];
        }
        try {
            return in.uinterp(calc, ndimIn, lbnd, ubnd, inVar, npoint, offset.clone(), coords, flags, out, outVar);
        } catch (Exception e) {
            throw callersError(
                    AstException.AST__UINER,
                    method + "(" + cls + "): Error signalled by user-supplied sub-pixel interpolation function.",
                    e);
        }
    }

    /** Whether a coordinate lies outside {@code [xmin, xmax)} or is bad. */
    private static boolean outside(double x, double xmin, double xmax) {
        return x < xmin || x >= xmax || x == AstObject.AST__BAD;
    }

    /**
     * C's {@code InterpolateNearest<X>}: each output pixel takes the value, and variance, of the
     * input pixel containing its position. C's 1-D, 2-D and general paths do the same integer
     * arithmetic, so the general path serves for all.
     */
    static int nearest(
            long[] lbndIn,
            long[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            int flags,
            PixelArray out,
            PixelArray outVar) {
        int ndimIn = lbndIn.length;
        boolean nobad = (flags & ResampleFlags.AST__NOBAD) != 0;
        boolean usebad = (flags & ResampleFlags.AST__USEBAD) != 0;
        boolean usevar = inVar != null && outVar != null;
        boolean signed = in.signed();
        long[] stride = new long[ndimIn];
        double[] xnMin = new double[ndimIn];
        double[] xnMax = new double[ndimIn];
        long s = 1;
        for (int idim = 0; idim < ndimIn; idim++) {
            stride[idim] = s;
            s *= ubndIn[idim] - lbndIn[idim] + 1;
            xnMin[idim] = lbndIn[idim] - 0.5;
            xnMax[idim] = ubndIn[idim] + 0.5;
        }

        int result = 0;
        for (int point = 0; point < npoint; point++) {
            boolean bad = false;
            long offIn = 0;
            for (int idim = 0; idim < ndimIn; idim++) {
                double xn = coords[idim][point];
                bad = outside(xn, xnMin[idim], xnMax[idim]);
                if (bad) {
                    break;
                }
                offIn += ((long) Math.floor(xn + 0.5) - lbndIn[idim]) * stride[idim];
            }
            if (usebad) {
                bad = bad || in.isBad((int) offIn);
            }

            int offOut = offset[point];
            if (bad) {
                if (!nobad) {
                    out.setBad(offOut);
                    if (usevar) {
                        outVar.setBad(offOut);
                    }
                }
                result++;
            } else {
                out.copy(offOut, in, (int) offIn);
                if (usevar) {
                    boolean badVar = usebad && inVar.isBad((int) offIn);
                    if (signed) {
                        badVar = badVar || inVar.get((int) offIn) < 0.0;
                    }
                    if ((signed || usebad) && badVar) {
                        if (!nobad) {
                            outVar.setBad(offOut);
                        }
                        result++;
                    } else {
                        outVar.copy(offOut, inVar, (int) offIn);
                    }
                }
            }
        }
        return result;
    }

    /**
     * The sums from which {@code InterpolateLinear<X>} forms an output value and variance, each
     * held in the type's sum type.
     */
    private static final class Sums {
        private final PixelArray in;
        private final PixelArray inVar;
        private final boolean usebad;
        private final boolean signed;
        double sum;
        double sumVar;
        double wtsum;
        boolean badVar;

        Sums(PixelArray in, PixelArray inVar, boolean usebad) {
            this.in = in;
            this.inVar = inVar;
            this.usebad = usebad;
            this.signed = in.signed();
        }

        /** Starts the sums for a new output pixel. */
        void reset() {
            sum = 0.0;
            wtsum = 0.0;
            if (inVar != null) {
                sumVar = 0.0;
                if (signed || usebad) {
                    badVar = false;
                }
            }
        }

        /**
         * C's {@code FORM_LINEAR_INTERPOLATION_SUM}: adds the input pixel at {@code off} with weight
         * {@code pixwt}, unless it is bad.
         */
        void add(long off, double pixwt) {
            int offIn = (int) off;
            if (!usebad || !in.isBad(offIn)) {
                sum = in.sum(sum + in.sum(in.sum(in.get(offIn)) * in.sum(pixwt)));
                wtsum = in.sum(wtsum + in.sum(pixwt));
                if (inVar != null && (!(signed || usebad) || !badVar)) {
                    double var = inVar.get(offIn);
                    if (usebad) {
                        badVar = inVar.isBad(offIn);
                    }
                    if (signed) {
                        badVar = badVar || var < 0.0;
                    }
                    if (!(signed || usebad) || !badVar) {
                        sumVar = in.sum(sumVar + in.sum(in.sum(pixwt * pixwt) * in.sum(var)));
                    }
                }
            }
        }
    }

    /**
     * C's {@code InterpolateLinear<X>}: each output value is the mean of the input pixels nearest
     * its position, two along each axis, weighted by their overlap with a pixel centred on the
     * position, and each variance the correspondingly weighted sum of their variances. An input
     * pixel outside the grid, or bad, contributes nothing; the output is bad when the input pixel
     * containing the position is bad. C's 1-D, 2-D and general paths form the weights differently,
     * so each is kept.
     */
    static int linear(
            long[] lbndIn,
            long[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            int flags,
            PixelArray out,
            PixelArray outVar) {
        int ndimIn = lbndIn.length;
        boolean nobad = (flags & ResampleFlags.AST__NOBAD) != 0;
        boolean usebad = (flags & ResampleFlags.AST__USEBAD) != 0;
        boolean usevar = inVar != null && outVar != null;
        boolean signed = in.signed();
        Sums sums = new Sums(in, usevar ? inVar : null, usebad);

        long[] stride = new long[ndimIn];
        double[] xnMin = new double[ndimIn];
        double[] xnMax = new double[ndimIn];
        long s = 1;
        for (int idim = 0; idim < ndimIn; idim++) {
            stride[idim] = s;
            s *= ubndIn[idim] - lbndIn[idim] + 1;
            xnMin[idim] = lbndIn[idim] - 0.5;
            xnMax[idim] = ubndIn[idim] + 0.5;
        }
        long[] dim = new long[ndimIn];
        long[] lo = new long[ndimIn];
        long[] hi = new long[ndimIn];
        double[] fracLo = new double[ndimIn];
        double[] fracHi = new double[ndimIn];
        double[] wt = new double[ndimIn];
        double[] wtprod = new double[ndimIn];

        int result = 0;
        for (int point = 0; point < npoint; point++) {
            boolean bad;
            if (ndimIn == 1) {
                double x = coords[0][point];
                bad = outside(x, xnMin[0], xnMax[0]);
                if (!bad) {
                    if (usebad) {
                        long pixel = (long) Math.floor(x + 0.5) - lbndIn[0];
                        bad = in.isBad((int) pixel);
                    }
                    if (!bad) {
                        long loX = (long) Math.floor(x);
                        long hiX = loX + 1;
                        double fracLoX = hiX - x;
                        double fracHiX = 1.0 - fracLoX;
                        long offLo = loX - lbndIn[0];
                        sums.reset();
                        if (loX >= lbndIn[0]) {
                            sums.add(offLo, fracLoX);
                        }
                        if (hiX <= ubndIn[0]) {
                            sums.add(offLo + 1, fracHiX);
                        }
                    }
                }
            } else if (ndimIn == 2) {
                long ystride = stride[1];
                double x = coords[0][point];
                bad = outside(x, xnMin[0], xnMax[0]);
                if (!bad) {
                    double y = coords[1][point];
                    bad = outside(y, xnMin[1], xnMax[1]);
                    if (!bad) {
                        if (usebad) {
                            long ix = (long) Math.floor(x + 0.5);
                            long iy = (long) Math.floor(y + 0.5);
                            long pixel = ix - lbndIn[0] + ystride * (iy - lbndIn[1]);
                            bad = in.isBad((int) pixel);
                        }
                        if (!bad) {
                            long loX = (long) Math.floor(x);
                            long hiX = loX + 1;
                            double fracLoX = hiX - x;
                            double fracHiX = 1.0 - fracLoX;
                            long loY = (long) Math.floor(y);
                            long hiY = loY + 1;
                            double fracLoY = hiY - y;
                            double fracHiY = 1.0 - fracLoY;
                            long offLo = loX - lbndIn[0] + ystride * (loY - lbndIn[1]);
                            sums.reset();
                            if (loY >= lbndIn[1]) {
                                if (loX >= lbndIn[0]) {
                                    sums.add(offLo, fracLoX * fracLoY);
                                }
                                if (hiX <= ubndIn[0]) {
                                    sums.add(offLo + 1, fracHiX * fracLoY);
                                }
                            }
                            if (hiY <= ubndIn[1]) {
                                if (loX >= lbndIn[0]) {
                                    sums.add(offLo + ystride, fracLoX * fracHiY);
                                }
                                if (hiX <= ubndIn[0]) {
                                    sums.add(offLo + ystride + 1, fracHiX * fracHiY);
                                }
                            }
                        }
                    }
                }
            } else {
                long offIn = 0;
                long pixel = 0;
                bad = false;
                for (int idim = 0; idim < ndimIn; idim++) {
                    double xn = coords[idim][point];
                    bad = outside(xn, xnMin[idim], xnMax[idim]);
                    if (bad) {
                        break;
                    }
                    if (usebad) {
                        pixel += stride[idim] * ((long) Math.floor(xn + 0.5) - lbndIn[idim]);
                    }
                    long ixn = (long) Math.floor(xn);
                    lo[idim] = Math.max(ixn, lbndIn[idim]);
                    hi[idim] = Math.min(ixn + 1, ubndIn[idim]);
                    fracLo[idim] = 1.0 - Math.abs(xn - lo[idim]);
                    fracHi[idim] = 1.0 - Math.abs(xn - hi[idim]);
                    dim[idim] = lo[idim];
                    offIn += stride[idim] * (lo[idim] - lbndIn[idim]);
                    wt[idim] = fracLo[idim];
                }
                if (usebad) {
                    bad = bad || in.isBad((int) pixel);
                }
                if (!bad) {
                    sums.reset();
                    int idim = ndimIn - 1;
                    wtprod[idim] = 1.0;
                    boolean done = false;
                    while (!done) {
                        // The weight products of the more significant axes, recomputed only for
                        // the axes below the most significant one whose index has changed.
                        for (int ii = idim; ii >= 1; ii--) {
                            wtprod[ii - 1] = wtprod[ii] * wt[ii];
                        }
                        sums.add(offIn, wtprod[0] * wt[0]);

                        // The first axis still at its lower index moves to its higher one, and the
                        // axes before it return to their lower ones; an axis whose two indices
                        // are the same takes only one. All are done when the last axis returns.
                        idim = 0;
                        while (true) {
                            if (dim[idim] != hi[idim]) {
                                dim[idim] = hi[idim];
                                offIn += stride[idim];
                                wt[idim] = fracHi[idim];
                                break;
                            }
                            if (dim[idim] != lo[idim]) {
                                dim[idim] = lo[idim];
                                offIn -= stride[idim];
                                wt[idim] = fracLo[idim];
                            }
                            idim++;
                            if (idim == ndimIn) {
                                done = true;
                                break;
                            }
                        }
                    }
                }
            }

            // C's CALC_AND_ASSIGN_OUTPUT.
            int offOut = offset[point];
            if (bad) {
                if (!nobad) {
                    out.setBad(offOut);
                    if (usevar) {
                        outVar.setBad(offOut);
                    }
                }
                result++;
            } else {
                out.setResampled(offOut, in.sum(sums.sum / sums.wtsum));
                if (usevar) {
                    if ((signed || usebad) && sums.badVar) {
                        if (!nobad) {
                            outVar.setBad(offOut);
                        }
                        result++;
                    } else {
                        outVar.setResampled(offOut, in.sum(sums.sumVar / in.sum(sums.wtsum * sums.wtsum)));
                    }
                }
            }
        }
        return result;
    }

    /**
     * C's {@code CALC_AND_ASSIGN_OUTPUT} as {@code InterpolateKernel1<X>} and {@code
     * InterpolateBlockAverage<X>} define it: the value and variance from the sums, each bad where
     * its weights sum to zero or it would overflow an integer type when rounded, assigned to the
     * output pixel at {@code offOut}. {@code bad} says whether the pixel is already bad, and {@code
     * badVar} whether its variance is. Returns the number of bad values assigned.
     */
    private static int calcAndAssign(
            boolean bad,
            boolean badVar,
            double sum,
            double sumVar,
            double wtsum,
            int offOut,
            boolean nobad,
            PixelArray in,
            PixelArray out,
            PixelArray outVar) {
        boolean usevar = outVar != null;
        double val = 0.0;
        double valVar = 0.0;
        if (!bad) {
            bad = wtsum == 0.0;
            if (!bad) {
                val = in.sum(sum / wtsum);
                bad = out.overflows(val);
            }
            if (usevar && bad) {
                badVar = true;
            }
        }
        if (usevar && !badVar) {
            double wtsumSq = in.sum(wtsum * wtsum);
            badVar = wtsumSq == 0.0;
            if (!badVar) {
                valVar = in.sum(sumVar / wtsumSq);
                badVar = out.overflows(valVar);
            }
        }

        int nbad = 0;
        if (bad) {
            if (!nobad) {
                out.setBad(offOut);
                if (usevar) {
                    outVar.setBad(offOut);
                }
            }
            nbad++;
        } else {
            out.setResampled(offOut, val);
            if (usevar) {
                if (badVar) {
                    if (!nobad) {
                        outVar.setBad(offOut);
                    }
                    nbad++;
                } else {
                    outVar.setResampled(offOut, valVar);
                }
            }
        }
        return nbad;
    }

    /**
     * C's {@code InterpolateKernel1<X>}: each output value is the sum of the input pixels within
     * {@code neighb} pixels of its position along each axis, weighted by the product of {@code
     * kernel} evaluated at their offsets along each axis, divided by the sum of those weights, and
     * each variance the sum of the input variances weighted by the squares of those weights,
     * divided by the square of their sum. {@code params} are passed to the kernel. The kernel is
     * evaluated as often and in the order C evaluates it, so that a caller's kernel sees C's
     * sequence of calls.
     */
    static int kernel1(
            long[] lbndIn,
            long[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            int flags,
            ResampleKernels.Kernel kernel,
            long neighb,
            double[] params,
            PixelArray out,
            PixelArray outVar) {
        int ndimIn = lbndIn.length;
        boolean nobad = (flags & ResampleFlags.AST__NOBAD) != 0;
        boolean usebad = (flags & ResampleFlags.AST__USEBAD) != 0;
        boolean usevar = inVar != null && outVar != null;
        Sums sums = new Sums(in, usevar ? inVar : null, usebad);

        long[] stride = new long[ndimIn];
        double[] xnMin = new double[ndimIn];
        double[] xnMax = new double[ndimIn];
        long s = 1;
        for (int idim = 0; idim < ndimIn; idim++) {
            stride[idim] = s;
            s *= ubndIn[idim] - lbndIn[idim] + 1;
            xnMin[idim] = lbndIn[idim] - 0.5;
            xnMax[idim] = ubndIn[idim] + 0.5;
        }
        // The kernel's values along each axis. C allocates 2 * neighb per axis; no more than the
        // axis's extent can be used.
        double[][] kval = new double[ndimIn][];
        for (int idim = 0; idim < ndimIn; idim++) {
            kval[idim] = new double[(int) Math.min(2 * neighb, ubndIn[idim] - lbndIn[idim] + 1)];
        }
        long[] lo = new long[ndimIn];
        long[] hi = new long[ndimIn];
        int[] wt = new int[ndimIn];
        double[] wtprod = new double[ndimIn];

        int result = 0;
        for (int point = 0; point < npoint; point++) {
            boolean bad;
            if (ndimIn == 1) {
                double x = coords[0][point];
                bad = outside(x, xnMin[0], xnMax[0]);
                if (!bad) {
                    if (usebad) {
                        long pixel = (long) Math.floor(x + 0.5) - lbndIn[0];
                        bad = in.isBad((int) pixel);
                    }
                    if (!bad) {
                        long ix = (long) Math.floor(x);
                        long loX = Math.max(ix - neighb + 1, lbndIn[0]);
                        long hiX = Math.min(ix + neighb, ubndIn[0]);
                        sums.reset();
                        long offIn = loX - lbndIn[0];
                        for (long jx = loX; jx <= hiX; jx++, offIn++) {
                            if (!usebad || !in.isBad((int) offIn)) {
                                sums.add(offIn, kernel.value(jx - x, params));
                            }
                        }
                    }
                }
            } else if (ndimIn == 2) {
                long ystride = stride[1];
                double x = coords[0][point];
                bad = outside(x, xnMin[0], xnMax[0]);
                if (!bad) {
                    double y = coords[1][point];
                    bad = outside(y, xnMin[1], xnMax[1]);
                    if (!bad) {
                        if (usebad) {
                            long ix = (long) Math.floor(x + 0.5);
                            long iy = (long) Math.floor(y + 0.5);
                            long pixel = ix - lbndIn[0] + ystride * (iy - lbndIn[1]);
                            bad = in.isBad((int) pixel);
                        }
                        if (!bad) {
                            long ix = (long) Math.floor(x);
                            long loX = Math.max(ix - neighb + 1, lbndIn[0]);
                            long hiX = Math.min(ix + neighb, ubndIn[0]);
                            long iy = (long) Math.floor(y);
                            long loY = Math.max(iy - neighb + 1, lbndIn[1]);
                            long hiY = Math.min(iy + neighb, ubndIn[1]);
                            for (long jx = loX; jx <= hiX; jx++) {
                                kval[0][(int) (jx - loX)] = kernel.value(jx - x, params);
                            }
                            sums.reset();
                            long off1 = loX - lbndIn[0] + ystride * (loY - lbndIn[1]);
                            for (long jy = loY; jy <= hiY; jy++, off1 += ystride) {
                                double wtY = kernel.value(jy - y, params);
                                long offIn = off1;
                                for (long jx = loX; jx <= hiX; jx++, offIn++) {
                                    if (!usebad || !in.isBad((int) offIn)) {
                                        sums.add(offIn, kval[0][(int) (jx - loX)] * wtY);
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                long pixel = 0;
                long offIn = 0;
                bad = false;
                for (int idim = 0; idim < ndimIn; idim++) {
                    double xn = coords[idim][point];
                    bad = outside(xn, xnMin[idim], xnMax[idim]);
                    if (bad) {
                        break;
                    }
                    if (usebad) {
                        pixel += stride[idim] * ((long) Math.floor(xn + 0.5) - lbndIn[idim]);
                    }
                    long ixn = (long) Math.floor(xn);
                    lo[idim] = Math.max(ixn - neighb + 1, lbndIn[idim]);
                    hi[idim] = Math.min(ixn + neighb, ubndIn[idim]);
                    offIn += stride[idim] * (lo[idim] - lbndIn[idim]);
                }
                if (usebad) {
                    bad = bad || in.isBad((int) pixel);
                }
                if (!bad) {
                    for (int idim = 0; idim < ndimIn; idim++) {
                        wt[idim] = 0;
                        double xn = coords[idim][point];
                        for (long ixn = lo[idim]; ixn <= hi[idim]; ixn++) {
                            kval[idim][(int) (ixn - lo[idim])] = kernel.value(ixn - xn, params);
                        }
                    }
                    sums.reset();
                    int idim = ndimIn - 1;
                    wtprod[idim] = 1.0;
                    boolean done = false;
                    while (!done) {
                        // Each pixel's weight is the product of the kernel's values along each
                        // axis. The products over the more significant axes are kept in wtprod
                        // and recomputed only from the most significant axis whose index changed.
                        for (int ii = idim; ii >= 1; ii--) {
                            wtprod[ii - 1] = wtprod[ii] * kval[ii][wt[ii]];
                        }
                        if (!usebad || !in.isBad((int) offIn)) {
                            sums.add(offIn, wtprod[0] * kval[0][wt[0]]);
                        }

                        // The first axis whose index has not reached its last value moves on, and
                        // the axes before it return to their first; all are done when the last
                        // axis returns.
                        idim = 0;
                        while (true) {
                            if (wt[idim] != hi[idim] - lo[idim]) {
                                wt[idim]++;
                                offIn += stride[idim];
                                break;
                            }
                            wt[idim] = 0;
                            offIn -= stride[idim] * (hi[idim] - lo[idim]);
                            idim++;
                            if (idim == ndimIn) {
                                done = true;
                                break;
                            }
                        }
                    }
                }
            }
            result += calcAndAssign(
                    bad,
                    sums.badVar,
                    sums.sum,
                    sums.sumVar,
                    sums.wtsum,
                    offset[point],
                    nobad,
                    in,
                    out,
                    usevar ? outVar : null);
        }
        return result;
    }

    /**
     * How far outside the input grid {@link #blockIndex} holds a pixel index. A block reaches less
     * than 2^31 pixels either side of its position, so a block whose position lies further than
     * this outside the grid misses it, as does the block of the index held here.
     */
    private static final long BLOCK_INDEX_LIMIT = 1L << 32;

    /**
     * The index of the pixel holding position {@code x} on an axis of the input grid from {@code
     * lbnd} to {@code ubnd}, as C's {@code (int) floor(x)}, but held within {@link
     * #BLOCK_INDEX_LIMIT} of the grid. The block it gives is then the same, empty when the
     * position lies so far outside, while the block's bounds stay well within a {@code long}.
     */
    private static long blockIndex(double x, long lbnd, long ubnd) {
        long ix = (long) Math.floor(x);
        return Math.max(lbnd - BLOCK_INDEX_LIMIT, Math.min(ix, ubnd + BLOCK_INDEX_LIMIT));
    }

    /**
     * C's {@code InterpolateBlockAverage<X>}: each output value is the mean of the good input
     * pixels in the block reaching {@code params[0]} pixels (rounded) either side of its position
     * along each axis, weighted by the reciprocals of their variances when variances are given;
     * the variance is the reciprocal of the sum of those weights.
     *
     * <p>As in C, only the general path tests for an empty block, and a position outside the input
     * grid is bad only if its block misses the grid. C means to leave out a pixel whose variance is
     * bad, but keeps one flag for the block: for a signed type without {@code AST__USEBAD} a
     * negative variance leaves out the rest of the block, and the output variance is bad when the
     * variance of the block's last good pixel was. Both are kept.
     */
    static int blockAverage(
            long[] lbndIn,
            long[] ubndIn,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            int flags,
            double[] params,
            PixelArray out,
            PixelArray outVar) {
        int ndimIn = lbndIn.length;
        boolean nobad = (flags & ResampleFlags.AST__NOBAD) != 0;
        boolean usebad = (flags & ResampleFlags.AST__USEBAD) != 0;
        boolean usevar = inVar != null && outVar != null;
        long neighb = (int) Math.floor(params[0] + 0.5);
        BlockSums sums = new BlockSums(in, usevar ? inVar : null, usebad);

        long[] stride = new long[ndimIn];
        long s = 1;
        for (int idim = 0; idim < ndimIn; idim++) {
            stride[idim] = s;
            s *= ubndIn[idim] - lbndIn[idim] + 1;
        }
        long[] lo = new long[ndimIn];
        long[] hi = new long[ndimIn];
        long[] ixm = new long[ndimIn];

        int result = 0;
        for (int point = 0; point < npoint; point++) {
            boolean bad;
            if (ndimIn == 1) {
                double x = coords[0][point];
                bad = x == AstObject.AST__BAD;
                if (!bad) {
                    long ix = blockIndex(x, lbndIn[0], ubndIn[0]);
                    long loX = Math.max(ix - neighb + 1, lbndIn[0]);
                    long hiX = Math.min(ix + neighb, ubndIn[0]);
                    sums.reset();
                    long offIn = loX - lbndIn[0];
                    for (long jx = loX; jx <= hiX; jx++, offIn++) {
                        sums.add(offIn);
                    }
                }
            } else if (ndimIn == 2) {
                long ystride = stride[1];
                double x = coords[0][point];
                bad = x == AstObject.AST__BAD;
                if (!bad) {
                    double y = coords[1][point];
                    bad = y == AstObject.AST__BAD;
                    if (!bad) {
                        long ix = blockIndex(x, lbndIn[0], ubndIn[0]);
                        long loX = Math.max(ix - neighb + 1, lbndIn[0]);
                        long hiX = Math.min(ix + neighb, ubndIn[0]);
                        long iy = blockIndex(y, lbndIn[1], ubndIn[1]);
                        long loY = Math.max(iy - neighb + 1, lbndIn[1]);
                        long hiY = Math.min(iy + neighb, ubndIn[1]);
                        sums.reset();
                        long off1 = loX - lbndIn[0] + ystride * (loY - lbndIn[1]);
                        for (long jy = loY; jy <= hiY; jy++, off1 += ystride) {
                            long offIn = off1;
                            for (long jx = loX; jx <= hiX; jx++, offIn++) {
                                sums.add(offIn);
                            }
                        }
                    }
                }
            } else {
                long offIn = 0;
                bad = false;
                for (int idim = 0; idim < ndimIn; idim++) {
                    double xn = coords[idim][point];
                    bad = xn == AstObject.AST__BAD;
                    if (bad) {
                        break;
                    }
                    long ixn = blockIndex(xn, lbndIn[idim], ubndIn[idim]);
                    lo[idim] = Math.max(ixn - neighb + 1, lbndIn[idim]);
                    hi[idim] = Math.min(ixn + neighb, ubndIn[idim]);
                    // A block with no extent along an axis holds no data.
                    bad = lo[idim] > hi[idim];
                    if (bad) {
                        break;
                    }
                    offIn += stride[idim] * (lo[idim] - lbndIn[idim]);
                    ixm[idim] = lo[idim];
                }
                if (!bad) {
                    sums.reset();
                    int idim;
                    do {
                        sums.add(offIn);
                        for (idim = 0; idim < ndimIn; idim++) {
                            if (ixm[idim] < hi[idim]) {
                                offIn += stride[idim];
                                ixm[idim]++;
                                break;
                            }
                            offIn -= stride[idim] * (hi[idim] - lo[idim]);
                            ixm[idim] = lo[idim];
                        }
                    } while (idim != ndimIn);
                }
            }
            result += calcAndAssign(
                    bad,
                    sums.badVar,
                    sums.sum,
                    sums.sumVar,
                    sums.wtsum,
                    offset[point],
                    nobad,
                    in,
                    out,
                    usevar ? outVar : null);
        }
        return result;
    }

    /**
     * The sums from which {@code InterpolateBlockAverage<X>} forms an output value and variance,
     * each held in the type's sum type, with C's one flag for a bad variance, which is reset only
     * when a block's sums are.
     */
    private static final class BlockSums {
        private final PixelArray in;
        private final PixelArray inVar;
        private final boolean usebad;
        private final boolean signed;
        double sum;
        double sumVar;
        double wtsum;
        boolean badVar;

        BlockSums(PixelArray in, PixelArray inVar, boolean usebad) {
            this.in = in;
            this.inVar = inVar;
            this.usebad = usebad;
            this.signed = in.signed();
        }

        /** Starts the sums for a new block. */
        void reset() {
            sum = 0.0;
            wtsum = 0.0;
            if (inVar != null) {
                sumVar = 0.0;
                badVar = false;
            }
        }

        /**
         * Adds the input pixel at {@code off}, unless it is bad: weighted by the reciprocal of its
         * variance when variances are given, unless {@code badVar} is set, and by one otherwise.
         */
        void add(long off) {
            int offIn = (int) off;
            if (!usebad || !in.isBad(offIn)) {
                if (inVar != null) {
                    double var = inVar.get(offIn);
                    if (usebad) {
                        badVar = inVar.isBad(offIn);
                    }
                    if (signed) {
                        badVar = badVar || var < 0.0;
                    }
                    if (!(signed || usebad) || !badVar) {
                        double pixwt = in.sum(1.0 / var);
                        sum = in.sum(sum + in.sum(pixwt * in.sum(in.get(offIn))));
                        wtsum = in.sum(wtsum + pixwt);
                        sumVar = in.sum(sumVar + pixwt);
                    }
                } else {
                    sum = in.sum(sum + in.sum(in.get(offIn)));
                    wtsum = in.sum(wtsum + 1.0);
                }
            }
        }
    }
}
