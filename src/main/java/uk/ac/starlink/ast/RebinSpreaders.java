package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * The spreading functions of rebinning, C's {@code SpreadNearest<X>}, {@code SpreadLinear<X>} and
 * {@code SpreadKernel1<X>}: each adds every good input pixel of a block into the output pixels
 * around its output position, weighted, with the weights into the work array when there is one
 * and, as the flags ask, its variance into the output variances.
 *
 * <p>C writes each function as eight loops, one for each combination of the flags it tests, so
 * that the compiler can drop the tests a case does not need; here one loop tests the flags. The
 * arithmetic, and its order, are C's, including each addition to an output pixel in the pixel
 * type ({@link PixelArray#add}).
 *
 * <p>The functions take only the flags {@code Rebin<X>} passes, {@code AST__USEBAD} and {@code
 * AST__USEVAR}. Where C tests a flag only {@code astRebinSeq<X>} accepts, which is not ported,
 * the test is left out, with a comment naming the flag; C's weight of every input pixel ({@code
 * conwgt}, which {@code AST__PARWGT} sets) is then 1, and its factor that conserves flux ({@code
 * infac}, which {@code AST__CONSERVEFLUX} sets) is 1, so that multiplying by either changes
 * nothing and is left out too.
 *
 * <p>Ported from Rust {@code mappings/rebin/spread.rs} and C {@code mapping.c}.
 */
final class RebinSpreaders {

    private RebinSpreaders() {}

    /** Whether variances are rebinned: C's {@code usevar}. */
    private static boolean useVar(int flags, PixelArray inVar, PixelArray outVar) {
        // C tests AST__GENVAR, which only astRebinSeq<X> accepts, before AST__USEVAR.
        return (flags & Rebin.AST__USEVAR) != 0 && inVar != null && outVar != null;
    }

    /** Whether the input pixel at {@code offIn} is bad, its value or, when variances are rebinned, its variance. */
    private static boolean inputBad(boolean usebad, boolean usevar, PixelArray in, PixelArray inVar, int offIn) {
        // With AST__VARWGT, which only astRebinSeq<X> accepts, C also takes a pixel whose
        // variance is not positive to be bad.
        if (!usebad) {
            return false;
        }
        boolean bad = in.isBad(offIn);
        if (usevar) {
            bad = bad || inVar.isBad(offIn);
        }
        return bad;
    }

    /** Whether the coordinate {@code x} lies outside {@code [xmin, xmax)} or is bad. */
    private static boolean outside(double x, double xmin, double xmax) {
        return x < xmin || x >= xmax || x == AstObject.AST__BAD;
    }

    /** The output grid's stride along each axis. */
    private static long[] strides(long[] lbndOut, long[] ubndOut) {
        long[] stride = new long[lbndOut.length];
        long s = 1;
        for (int idim = 0; idim < lbndOut.length; idim++) {
            stride[idim] = s;
            s *= ubndOut[idim] - lbndOut[idim] + 1;
        }
        return stride;
    }

    /**
     * Adds the input pixel at {@code offIn}, with weight {@code pixwt}, into the output pixel at
     * {@code offOut}, as each of C's spreading functions does: the weighted value converted to the
     * type, then added to the output pixel in the type; the weight added to the work array; and,
     * when variances are rebinned, the variance times the square of the weight, converted as C's
     * {@code CONV} does, added to the output variance in double and converted to the type.
     */
    private static void deposit(
            PixelArray in,
            PixelArray inVar,
            boolean usevar,
            int offIn,
            int offOut,
            double pixwt,
            PixelArray out,
            PixelArray outVar,
            double[] work) {
        double c = out.fromC(out.conv(pixwt * in.get(offIn)));
        out.add(offOut, c);
        if (work != null) {
            work[offOut] += pixwt;
        }
        if (usevar) {
            outVar.set(offOut, outVar.get(offOut) + outVar.conv(inVar.get(offIn) * pixwt * pixwt));
        }
        // With AST__GENVAR, which only astRebinSeq<X> accepts, C adds the square of the weighted
        // value to the output variance instead.
    }

    /**
     * C's {@code SpreadNearest<X>}: each input pixel is added into the output pixel containing its
     * position.
     */
    static void nearest(
            long[] lbndOut,
            long[] ubndOut,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            int flags,
            PixelArray out,
            PixelArray outVar,
            double[] work) {
        boolean usebad = (flags & ResampleFlags.AST__USEBAD) != 0;
        boolean usevar = useVar(flags, inVar, outVar);
        int ndim = lbndOut.length;
        long[] stride = strides(lbndOut, ubndOut);
        double[] xmin = new double[ndim];
        double[] xmax = new double[ndim];
        for (int idim = 0; idim < ndim; idim++) {
            xmin[idim] = lbndOut[idim] - 0.5;
            xmax[idim] = ubndOut[idim] + 0.5;
        }
        for (int point = 0; point < npoint; point++) {
            int offIn = offset[point];
            boolean bad = inputBad(usebad, usevar, in, inVar, offIn);
            long offOut = 0;
            if (ndim == 1) {
                double x = coords[0][point];
                bad = bad || outside(x, xmin[0], xmax[0]);
                if (!bad) {
                    offOut = (long) Math.floor(x + 0.5) - lbndOut[0];
                }
            } else if (ndim == 2) {
                double y = coords[1][point];
                bad = bad || outside(y, xmin[1], xmax[1]);
                if (!bad) {
                    double x = coords[0][point];
                    bad = outside(x, xmin[0], xmax[0]);
                    if (!bad) {
                        long ix = (long) Math.floor(x + 0.5) - lbndOut[0];
                        long iy = (long) Math.floor(y + 0.5) - lbndOut[1];
                        offOut = ix + stride[1] * iy;
                    }
                }
            } else if (!bad) {
                for (int idim = 0; idim < ndim; idim++) {
                    double xn = coords[idim][point];
                    bad = outside(xn, xmin[idim], xmax[idim]);
                    if (bad) {
                        break;
                    }
                    long ixn = (long) Math.floor(xn + 0.5) - lbndOut[idim];
                    offOut += ixn * stride[idim];
                }
            }
            if (!bad) {
                // With AST__VARWGT, which only astRebinSeq<X> accepts, C divides the weight by
                // the input variance.
                deposit(in, inVar, usevar, offIn, (int) offOut, 1.0, out, outVar, work);
            }
        }
    }

    /**
     * C's {@code SpreadLinear<X>}: each input pixel is shared between the output pixels around its
     * position along each axis, in proportion to their nearness.
     */
    static void linear(
            long[] lbndOut,
            long[] ubndOut,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            int flags,
            PixelArray out,
            PixelArray outVar,
            double[] work) {
        boolean usebad = (flags & ResampleFlags.AST__USEBAD) != 0;
        boolean usevar = useVar(flags, inVar, outVar);
        int ndim = lbndOut.length;
        long[] stride = strides(lbndOut, ubndOut);
        double[] xmin = new double[ndim];
        double[] xmax = new double[ndim];
        for (int idim = 0; idim < ndim; idim++) {
            xmin[idim] = lbndOut[idim] - 0.5;
            xmax[idim] = ubndOut[idim] + 0.5;
        }
        // Workspace for the n-dimensional path.
        long[] dim = new long[ndim];
        long[] lo = new long[ndim];
        long[] hi = new long[ndim];
        double[] fracLo = new double[ndim];
        double[] fracHi = new double[ndim];
        double[] wt = new double[ndim];
        double[] wtprod = new double[ndim];

        // With AST__VARWGT, which only astRebinSeq<X> accepts, C scales the fractions of each
        // input pixel by a weight from its variance.
        for (int point = 0; point < npoint; point++) {
            int offIn = offset[point];
            boolean bad = inputBad(usebad, usevar, in, inVar, offIn);
            if (ndim == 1) {
                double x = coords[0][point];
                bad = bad || outside(x, xmin[0], xmax[0]);
                if (!bad) {
                    long loX = (long) Math.floor(x);
                    long hiX = loX + 1;
                    double fracLoX = hiX - x;
                    double fracHiX = 1.0 - fracLoX;
                    long offLo = loX - lbndOut[0];
                    if (loX >= lbndOut[0]) {
                        deposit(in, inVar, usevar, offIn, (int) offLo, fracLoX, out, outVar, work);
                    }
                    if (hiX <= ubndOut[0]) {
                        deposit(in, inVar, usevar, offIn, (int) (offLo + 1), fracHiX, out, outVar, work);
                    }
                }
            } else if (ndim == 2) {
                long ystride = stride[1];
                double y = coords[1][point];
                bad = bad || outside(y, xmin[1], xmax[1]);
                if (!bad) {
                    double x = coords[0][point];
                    bad = outside(x, xmin[0], xmax[0]);
                    if (!bad) {
                        long loX = (long) Math.floor(x);
                        long hiX = loX + 1;
                        double fracLoX = hiX - x;
                        double fracHiX = 1.0 - fracLoX;
                        long loY = (long) Math.floor(y);
                        long hiY = loY + 1;
                        double fracLoY = hiY - y;
                        double fracHiY = 1.0 - fracLoY;
                        long offLo = loX - lbndOut[0] + ystride * (loY - lbndOut[1]);
                        if (loY >= lbndOut[1]) {
                            if (loX >= lbndOut[0]) {
                                deposit(in, inVar, usevar, offIn, (int) offLo, fracLoX * fracLoY, out, outVar, work);
                            }
                            if (hiX <= ubndOut[0]) {
                                deposit(
                                        in,
                                        inVar,
                                        usevar,
                                        offIn,
                                        (int) (offLo + 1),
                                        fracHiX * fracLoY,
                                        out,
                                        outVar,
                                        work);
                            }
                        }
                        if (hiY <= ubndOut[1]) {
                            if (loX >= lbndOut[0]) {
                                deposit(
                                        in,
                                        inVar,
                                        usevar,
                                        offIn,
                                        (int) (offLo + ystride),
                                        fracLoX * fracHiY,
                                        out,
                                        outVar,
                                        work);
                            }
                            if (hiX <= ubndOut[0]) {
                                deposit(
                                        in,
                                        inVar,
                                        usevar,
                                        offIn,
                                        (int) (offLo + ystride + 1),
                                        fracHiX * fracHiY,
                                        out,
                                        outVar,
                                        work);
                            }
                        }
                    }
                }
            } else if (!bad) {
                long offOut = 0;
                for (int idim = 0; idim < ndim; idim++) {
                    double xn = coords[idim][point];
                    bad = outside(xn, xmin[idim], xmax[idim]);
                    if (bad) {
                        break;
                    }
                    // The two pixels, usually adjacent, that share the value along this axis, kept
                    // within the output grid, and the fraction each gets.
                    long ixn = (long) Math.floor(xn);
                    lo[idim] = Math.max(ixn, lbndOut[idim]);
                    hi[idim] = Math.min(ixn + 1, ubndOut[idim]);
                    fracLo[idim] = 1.0 - Math.abs(xn - lo[idim]);
                    fracHi[idim] = 1.0 - Math.abs(xn - hi[idim]);
                    dim[idim] = lo[idim];
                    offOut += stride[idim] * (lo[idim] - lbndOut[idim]);
                    wt[idim] = fracLo[idim];
                }
                if (!bad) {
                    // Each pixel's weight is the product of its fractions along each axis, the
                    // products for the more significant axes kept in wtprod and recomputed only
                    // for the axes whose index has changed.
                    int idim = ndim - 1;
                    wtprod[idim] = 1.0;
                    boolean done = false;
                    while (!done) {
                        for (int ii = idim; ii >= 1; ii--) {
                            wtprod[ii - 1] = wtprod[ii] * wt[ii];
                        }
                        double pixwt = wtprod[0] * wt[0];
                        deposit(in, inVar, usevar, offIn, (int) offOut, pixwt, out, outVar, work);

                        // The first axis still at its lower pixel moves to its higher one, and
                        // the axes before it return to their lower ones; an axis whose two pixels
                        // are the same has only one.
                        idim = 0;
                        done = true;
                        while (idim < ndim) {
                            if (dim[idim] != hi[idim]) {
                                dim[idim] = hi[idim];
                                offOut += stride[idim];
                                wt[idim] = fracHi[idim];
                                done = false;
                                break;
                            }
                            if (dim[idim] != lo[idim]) {
                                dim[idim] = lo[idim];
                                offOut -= stride[idim];
                                wt[idim] = fracLo[idim];
                            }
                            idim++;
                        }
                    }
                }
            }
        }
    }

    /**
     * Fills {@code filter[lo..hi]} with the kernel at offsets {@code x0}, {@code x0 + 1}, ...,
     * {@code nb2} values in all, reflecting the values for the pixels off the edge of the output
     * grid back onto it, as C's {@code SpreadKernel1<X>} does when the kernel overlaps an edge, so
     * that the kernel's whole weight lands on the grid. The reflected part may itself overlap the
     * opposite edge, which C's {@code %} handles. The filter must hold zeros beforehand.
     *
     * @return the sum of the kernel values
     */
    private static double reflect(
            ResampleKernels.Kernel kernel, double[] params, double x0, int lo, int hi, int nb2, double[] filter) {
        int nw = 2 * (hi - lo + 1);
        double sum = 0.0;
        double xx = x0;
        for (int j = 0; j < nb2; j++) {
            double pixwt = kernel.value(xx, params);
            int jj = (j - lo) % nw + lo;
            if (jj < lo) {
                jj += nw;
            }
            if (jj > hi) {
                jj = 2 * hi - jj + 1;
            }
            filter[jj] += pixwt;
            sum += pixwt;
            xx += 1.0;
        }
        return sum;
    }

    /**
     * C's {@code SpreadKernel1<X>}: each input pixel is shared between the output pixels within
     * {@code neighb} pixels of its position along each axis, weighted by the product of the kernel
     * evaluated at their offsets along each axis, normalised to sum to one over the kernel's box.
     * Weight falling off the grid is reflected back onto it. The kernel values are kept and reused
     * while successive points have the same offsets.
     */
    static void kernel1(
            long[] lbndOut,
            long[] ubndOut,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            ResampleKernels.Kernel kernel,
            int neighb,
            double[] params,
            int flags,
            PixelArray out,
            PixelArray outVar,
            double[] work) {
        boolean usebad = (flags & ResampleFlags.AST__USEBAD) != 0;
        boolean usevar = useVar(flags, inVar, outVar);
        int ndim = lbndOut.length;
        int nb2 = 2 * neighb;
        // With AST__VARWGT, which only astRebinSeq<X> accepts, C divides each pixel's weight by
        // its variance; without it the weight is C's conwgt / sum, conwgt being 1.
        if (ndim == 1) {
            double[] filter = new double[nb2];
            double xxl = AstObject.AST__BAD;
            double sum = 0.0;
            for (int point = 0; point < npoint; point++) {
                int offIn = offset[point];
                boolean bad = inputBad(usebad, usevar, in, inVar, offIn);
                // The point's centre must fall within the output grid.
                double x = coords[0][point];
                long ix = (long) Math.floor(x + 0.5);
                if (ix < lbndOut[0] || ix > ubndOut[0]) {
                    bad = true;
                }
                if (bad || x == AstObject.AST__BAD) {
                    continue;
                }
                ix = (long) Math.floor(x) - neighb + 1;
                long loIx = Math.max(ix, lbndOut[0]);
                long hiIx = Math.min(ix + nb2 - 1, ubndOut[0]);
                if (loIx > hiIx) {
                    continue;
                }
                int loJx = (int) (loIx - ix);
                int hiJx = (int) (hiIx - ix);
                boolean offXedge = hiJx - loJx + 1 < nb2;
                double xx = ix - x;
                if (xx != xxl || offXedge) {
                    if (offXedge) {
                        xxl = AstObject.AST__BAD;
                        Arrays.fill(filter, 0.0);
                        sum = reflect(kernel, params, xx, loJx, hiJx, nb2, filter);
                    } else {
                        xxl = xx;
                        sum = 0.0;
                        for (int jx = 0; jx < nb2; jx++) {
                            double pixwt = kernel.value(xx, params);
                            filter[jx] = pixwt;
                            sum += pixwt;
                            xx += 1.0;
                        }
                    }
                    if (sum == 0.0) {
                        sum = 1.0;
                    }
                }
                double wgt = 1.0 / sum;
                long offOut = loIx - lbndOut[0];
                for (int jx = loJx; jx <= hiJx; jx++) {
                    deposit(in, inVar, usevar, offIn, (int) offOut++, wgt * filter[jx], out, outVar, work);
                }
            }
        } else if (ndim == 2) {
            long ystride = ubndOut[0] - lbndOut[0] + 1;
            double[] filter = new double[nb2 * nb2];
            double[] xfilter = new double[nb2];
            double xxl = AstObject.AST__BAD;
            double yyl = AstObject.AST__BAD;
            double sum = 0.0;
            for (int point = 0; point < npoint; point++) {
                int offIn = offset[point];
                boolean bad = inputBad(usebad, usevar, in, inVar, offIn);
                // The point's centre must fall within the output grid.
                double x = coords[0][point];
                long ix = (long) Math.floor(x + 0.5);
                if (ix < lbndOut[0] || ix > ubndOut[0]) {
                    bad = true;
                }
                if (bad || x == AstObject.AST__BAD) {
                    continue;
                }
                double y = coords[1][point];
                long iy = (long) Math.floor(y + 0.5);
                if (iy < lbndOut[1] || iy > ubndOut[1] || y == AstObject.AST__BAD) {
                    continue;
                }
                ix = (long) Math.floor(x) - neighb + 1;
                long loIx = Math.max(ix, lbndOut[0]);
                long hiIx = Math.min(ix + nb2 - 1, ubndOut[0]);
                iy = (long) Math.floor(y) - neighb + 1;
                long loIy = Math.max(iy, lbndOut[1]);
                long hiIy = Math.min(iy + nb2 - 1, ubndOut[1]);
                if (loIx > hiIx || loIy > hiIy) {
                    continue;
                }
                int loJx = (int) (loIx - ix);
                int hiJx = (int) (hiIx - ix);
                int loJy = (int) (loIy - iy);
                int hiJy = (int) (hiIy - iy);
                boolean offXedge = hiJx - loJx + 1 < nb2;
                boolean offYedge = hiJy - loJy + 1 < nb2;
                double yy = iy - y;
                double xx = ix - x;
                if (xx != xxl || yy != yyl || offXedge || offYedge) {
                    // The y kernel values fill each row of the filter.
                    if (offYedge) {
                        xxl = AstObject.AST__BAD;
                        yyl = AstObject.AST__BAD;
                        Arrays.fill(filter, 0.0);
                        reflectRows(kernel, params, yy, loJy, hiJy, nb2, filter);
                    } else {
                        xxl = xx;
                        yyl = yy;
                        for (int jy = 0; jy < nb2; jy++) {
                            double pixwt = kernel.value(yy, params);
                            Arrays.fill(filter, jy * nb2, (jy + 1) * nb2, pixwt);
                            yy += 1.0;
                        }
                    }
                    // Each column is then scaled by the x kernel value, and the filter summed
                    // column by column.
                    sum = 0.0;
                    if (offXedge) {
                        xxl = AstObject.AST__BAD;
                        Arrays.fill(xfilter, 0.0);
                        reflect(kernel, params, xx, loJx, hiJx, nb2, xfilter);
                        for (int jx = 0; jx < nb2; jx++) {
                            for (int jy = 0; jy < nb2; jy++) {
                                int k = jy * nb2 + jx;
                                filter[k] *= xfilter[jx];
                                sum += filter[k];
                            }
                        }
                    } else {
                        for (int jx = 0; jx < nb2; jx++) {
                            double pixwt = kernel.value(xx, params);
                            for (int jy = 0; jy < nb2; jy++) {
                                int k = jy * nb2 + jx;
                                filter[k] *= pixwt;
                                sum += filter[k];
                            }
                            xx += 1.0;
                        }
                    }
                    if (sum == 0.0) {
                        sum = 1.0;
                    }
                }
                double wgt = 1.0 / sum;
                long off1 = loIx - lbndOut[0] + ystride * (loIy - lbndOut[1]);
                for (int jy = loJy; jy <= hiJy; jy++) {
                    long offOut = off1;
                    for (int jx = loJx; jx <= hiJx; jx++) {
                        double pixwt = wgt * filter[jx + jy * nb2];
                        deposit(in, inVar, usevar, offIn, (int) offOut++, pixwt, out, outVar, work);
                    }
                    off1 += ystride;
                }
            }
        } else {
            kernelNd(
                    lbndOut, ubndOut, in, inVar, npoint, offset, coords, kernel, neighb, params, usebad, usevar, out,
                    outVar, work);
        }
    }

    /**
     * The reflection of {@link #reflect} for the y axis of the 2-dimensional filter: each kernel
     * value, reflected onto the row it lands in, is added to every element of that row. The
     * filter must hold zeros beforehand.
     */
    private static void reflectRows(
            ResampleKernels.Kernel kernel, double[] params, double y0, int lo, int hi, int nb2, double[] filter) {
        int nw = 2 * (hi - lo + 1);
        double yy = y0;
        for (int j = 0; j < nb2; j++) {
            double pixwt = kernel.value(yy, params);
            int jj = (j - lo) % nw + lo;
            if (jj < lo) {
                jj += nw;
            }
            if (jj > hi) {
                jj = 2 * hi - jj + 1;
            }
            for (int jx = 0; jx < nb2; jx++) {
                filter[jj * nb2 + jx] += pixwt;
            }
            yy += 1.0;
        }
    }

    /** The n-dimensional path of {@link #kernel1}. */
    private static void kernelNd(
            long[] lbndOut,
            long[] ubndOut,
            PixelArray in,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            ResampleKernels.Kernel kernel,
            int neighb,
            double[] params,
            boolean usebad,
            boolean usevar,
            PixelArray out,
            PixelArray outVar,
            double[] work) {
        int ndim = lbndOut.length;
        int nb2 = 2 * neighb;
        long[] stride = strides(lbndOut, ubndOut);
        // Each axis's kernel values are kept in kval, with the offset they were computed for in
        // xnl; sum is bad once any of them changes.
        double[][] kval = new double[ndim][nb2];
        double[] xnl = new double[ndim];
        Arrays.fill(xnl, AstObject.AST__BAD);
        long[] lo = new long[ndim];
        long[] hi = new long[ndim];
        int[] jlo = new int[ndim];
        int[] jhi = new int[ndim];
        int[] w = new int[ndim];
        double[] wtprod = new double[ndim];
        double sum = AstObject.AST__BAD;
        for (int point = 0; point < npoint; point++) {
            int offIn = offset[point];
            if (inputBad(usebad, usevar, in, inVar, offIn)) {
                continue;
            }
            boolean bad = false;
            long offOut = 0;
            for (int idim = 0; idim < ndim; idim++) {
                double xn = coords[idim][point];
                long ix = (long) Math.floor(xn + 0.5);
                if (ix < lbndOut[idim] || ix > ubndOut[idim] || xn == AstObject.AST__BAD) {
                    bad = true;
                    break;
                }
                ix = (long) Math.floor(xn) - neighb + 1;
                lo[idim] = Math.max(ix, lbndOut[idim]);
                hi[idim] = Math.min(ix + nb2 - 1, ubndOut[idim]);
                jlo[idim] = (int) (lo[idim] - ix);
                jhi[idim] = (int) (hi[idim] - ix);
                if (lo[idim] > hi[idim]) {
                    bad = true;
                    break;
                }
                offOut += stride[idim] * (lo[idim] - lbndOut[idim]);
                boolean offXedge = jhi[idim] - jlo[idim] + 1 < nb2;
                double xxn = ix - xn;
                if (xxn != xnl[idim] || offXedge) {
                    sum = AstObject.AST__BAD;
                    if (offXedge) {
                        xnl[idim] = AstObject.AST__BAD;
                        Arrays.fill(kval[idim], 0.0);
                        reflect(kernel, params, xxn, jlo[idim], jhi[idim], nb2, kval[idim]);
                    } else {
                        xnl[idim] = xxn;
                        for (int jxn = 0; jxn < nb2; jxn++) {
                            kval[idim][jxn] = kernel.value(xxn, params);
                            xxn += 1.0;
                        }
                    }
                }
            }
            if (bad) {
                continue;
            }

            // The sum of the kernel products over the whole box, formed as the weights are below.
            if (sum == AstObject.AST__BAD) {
                Arrays.fill(w, 0);
                int idim = ndim - 1;
                wtprod[idim] = 1.0;
                sum = 0.0;
                boolean done = false;
                while (!done) {
                    for (int ii = idim; ii >= 1; ii--) {
                        wtprod[ii - 1] = wtprod[ii] * kval[ii][w[ii]];
                    }
                    sum += wtprod[0] * kval[0][w[0]];
                    idim = 0;
                    done = true;
                    while (idim < ndim) {
                        if (w[idim] != nb2 - 1) {
                            w[idim]++;
                            done = false;
                            break;
                        }
                        w[idim] = 0;
                        idim++;
                    }
                }
                if (sum == 0.0) {
                    sum = 1.0;
                }
            }
            System.arraycopy(jlo, 0, w, 0, ndim);
            double wgt = 1.0 / sum;
            int idim = ndim - 1;
            wtprod[idim] = 1.0;
            boolean done = false;
            while (!done) {
                for (int ii = idim; ii >= 1; ii--) {
                    wtprod[ii - 1] = wtprod[ii] * kval[ii][w[ii]];
                }
                double pixwt = (wtprod[0] * kval[0][w[0]]) * wgt;
                deposit(in, inVar, usevar, offIn, (int) offOut, pixwt, out, outVar, work);
                idim = 0;
                done = true;
                while (idim < ndim) {
                    if (w[idim] != jhi[idim]) {
                        w[idim]++;
                        offOut += stride[idim];
                        done = false;
                        break;
                    }
                    w[idim] = jlo[idim];
                    offOut -= stride[idim] * (hi[idim] - lo[idim]);
                    idim++;
                }
            }
        }
    }
}
