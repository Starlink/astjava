package uk.ac.starlink.ast;

/**
 * The sub-pixel interpolation schemes of resampling: each fills the output pixels of one block
 * from their positions in the input grid, and returns the number of output values (and variances)
 * it set bad.
 *
 * <p>A position outside the input grid, including one on its upper edge, or a bad coordinate
 * gives a bad output pixel; a position on a pixel boundary belongs to the pixel with the larger
 * index. Variances are interpolated only when both {@code inVar} and {@code outVar} are given; a
 * variance is bad when it equals the bad value (with {@code AST__USEBAD}) or is negative.
 *
 * <p>C writes each scheme as eight loops, one for each combination of {@code AST__USEBAD}, {@code
 * AST__NOBAD} and the presence of variances, so that the compiler can drop the tests a case does
 * not need. Each scheme here makes those tests in one loop, with C's arithmetic in C's order.
 *
 * <p>Ported from Rust {@code mappings/resample/interp.rs} and C {@code mapping.c} ({@code
 * InterpolateNearest<X>} and {@code InterpolateLinear<X>}, and the scheme dispatch of {@code
 * ResampleSection}).
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
            PixelArray out,
            PixelArray outVar) {
        switch (interp.scheme) {
            case Mapping.AST__NEAREST:
                return nearest(lbndIn, ubndIn, in, inVar, npoint, offset, coords, flags, out, outVar);
            case Mapping.AST__LINEAR:
                return linear(lbndIn, ubndIn, in, inVar, npoint, offset, coords, flags, out, outVar);
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        method + "(" + cls + "): Unexpected sub-pixel interpolation scheme (" + interp.scheme + ").");
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
}
