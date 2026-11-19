package uk.ac.starlink.ast;

/**
 * Masking a grid of data with a Region: region.c {@code Mask<X>}, which a Region's {@link
 * Region#maskData} uses unless its class has its own, and the checks every class's {@code
 * Mask<X>} makes of its arguments.
 *
 * <p>The array and bound checks are those of {@link Resample}: a bound array with fewer values
 * than {@code ndim}, or a data array with fewer elements than the grid has pixels, is {@code
 * AST__DIMIN}, which C cannot check, and a longer one is accepted with its leading elements used.
 *
 * <p>Ported from C {@code region.c} ({@code Mask<X>}), with Rust {@code regions/masking.rs}.
 */
final class RegionMask {

    private RegionMask() {}

    /** The prefix of the messages of {@code method}, such as {@code astMaskD}, on {@code reg}. */
    static String prefix(Region reg, String method) {
        return method + "(" + reg.astClass() + "): ";
    }

    /**
     * The Region in grid coordinates, after the checks of {@code Mask<X>}: {@code reg} itself when
     * {@code map} is null, and otherwise {@code reg} mapped by it into a Frame with Domain GRID
     * (the protected {@code astMapRegion}).
     *
     * @throws AstException with status {@code AST__NGDIN} if {@code map} does not have an input
     *     for each axis of {@code reg} and an output for each grid dimension, or, with no {@code
     *     map}, {@code ndim} is not the number of axes of {@code reg}; {@code AST__DIMIN} if a bound
     *     array has fewer than {@code ndim} values; or {@code AST__GBDIN} if a lower bound is above
     *     its upper bound
     */
    static Region gridRegion(Region reg, Mapping map, int ndim, int[] lbnd, int[] ubnd, String method) {
        String prefix = prefix(reg, method);
        String cls = reg.astClass();
        int numAxes = reg.getNaxes();
        if (map != null) {
            int nin = map.getNin();
            int nout = map.getNout();
            if (numAxes != nin) {
                throw Ast.err(
                        AstException.AST__NGDIN,
                        prefix + "Bad number of mapping inputs (" + nin + ").\nThe " + cls + " given requires "
                                + numAxes + " coordinate value" + (numAxes == 1 ? "" : "s")
                                + " to specify a position.");
            }
            if (ndim != nout) {
                throw Ast.err(
                        AstException.AST__NGDIN,
                        prefix + "Bad number of mapping outputs (" + nout + ").\nThe pixel grid requires " + ndim
                                + " coordinate value" + (ndim == 1 ? "" : "s") + " to specify a position.");
            }
        } else if (ndim != numAxes || ndim < 1) {
            String msg = prefix + "Bad number of input grid dimensions (" + ndim + ").";
            if (ndim != numAxes) {
                msg += "\nThe " + cls + " given requires " + numAxes + " coordinate value" + (numAxes == 1 ? "" : "s")
                        + " to specify an input position.";
            }
            throw Ast.err(AstException.AST__NGDIN, msg);
        }
        Resample.checkBounds(prefix, "lower bounds", lbnd, ndim);
        Resample.checkBounds(prefix, "upper bounds", ubnd, ndim);
        Region used;
        if (map != null) {
            Frame gridFrame = new Frame(ndim);
            gridFrame.setDomain("GRID");
            used = reg.mapRegionInto(map, gridFrame);
        } else {
            used = reg;
        }
        for (int idim = 0; idim < ndim; idim++) {
            if (lbnd[idim] > ubnd[idim]) {
                throw Ast.err(
                        AstException.AST__GBDIN,
                        prefix + "Lower bound of input grid (" + lbnd[idim] + ") exceeds corresponding upper bound ("
                                + ubnd[idim] + ").\nError in input dimension " + (idim + 1) + ".");
            }
        }
        return used;
    }

    /** The number of pixels in the grid, whose bounds {@link #gridRegion} has checked. */
    static long gridSize(int ndim, int[] lbnd, int[] ubnd) {
        long npix = 1;
        for (int idim = 0; idim < ndim; idim++) {
            npix *= (long) ubnd[idim] - lbnd[idim] + 1;
        }
        return npix;
    }

    /**
     * C's {@code Mask<X>}: sets to the bad value of {@code in} every pixel of the grid whose centre
     * is inside {@code reg} (if {@code inside}) or not inside it, returning the number set.
     *
     * <p>Pixels outside the bounding box of the Region in grid coordinates, widened by two pixels,
     * are all on one side of it, so they are set together when that is the side being masked.
     * Within the box the data are resampled through the Region by nearest neighbour, with the
     * value to set as the bad value: the Region leaves a point inside it alone and makes any other
     * bad, so it is negated in place for the resampling when the inside is masked, as C does, which
     * leaves its Negated attribute set; with no Mapping, that Region is {@code reg} itself.
     *
     * @param method C's name of the function, such as {@code astMaskD}, for error messages
     * @throws AstException with the statuses of {@link #gridRegion}, or {@code AST__DIMIN} if
     *     {@code in} has fewer elements than the grid has pixels
     */
    static int mask(
            Region reg, Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, PixelArray in, String method) {
        Region used = gridRegion(reg, map, ndim, lbnd, ubnd, method);
        long npix = gridSize(ndim, lbnd, ubnd);
        Resample.checkLength(prefix(reg, method), "data", in, npix);

        // The bounding box in grid coordinates as pixel bounds, two pixels wider each way and kept
        // within the grid. C converts each bound with (int)round and widens it in int arithmetic.
        double[][] bounds = used.getRegionBounds();
        int[] lbndg = new int[ndim];
        int[] ubndg = new int[ndim];
        long npixg = 1;
        for (int idim = 0; idim < ndim; idim++) {
            double lb = bounds[0][idim];
            double ub = bounds[1][idim];
            if (lb != AstObject.AST__BAD && ub != AstObject.AST__BAD) {
                lbndg[idim] = Math.max(lbnd[idim], (int) FitsMakeIntWorld.roundHalfAway(lb) - 2);
                ubndg[idim] = Math.min(ubnd[idim], (int) FitsMakeIntWorld.roundHalfAway(ub) + 2);
            } else {
                lbndg[idim] = lbnd[idim];
                ubndg[idim] = ubnd[idim];
            }
            if (npixg >= 0) {
                npixg *= (long) ubndg[idim] - lbndg[idim] + 1;
            }
        }

        // The outside of the box is masked when it is outside the Region and the outside is
        // masked, or (the Region being negated) inside it and the inside is masked.
        boolean fillOutside = inside == used.getNegated();
        if (npixg <= 0) {
            if (fillOutside) {
                for (int ipix = 0; ipix < npix; ipix++) {
                    in.setBad(ipix);
                }
                return (int) npix;
            }
            return 0;
        }

        // Resample into an array holding the value everywhere when the outside of the box is to
        // be set too, and otherwise into the data array itself: within the box each pixel reads
        // only itself.
        PixelArray out;
        long result;
        if (fillOutside) {
            out = in.filled((int) npix);
            result = npix - npixg;
        } else {
            out = in;
            result = 0;
        }
        if (inside) {
            used.negate();
        }
        try {
            result += Resample.resample(
                    used,
                    ndim,
                    lbnd,
                    ubnd,
                    in,
                    null,
                    Mapping.NEAREST_INTERPOLATOR,
                    new ResampleFlags(),
                    0.0,
                    100,
                    ndim,
                    lbnd,
                    ubnd,
                    lbndg,
                    ubndg,
                    out,
                    null,
                    "astResample" + method.substring("astMask".length()));
        } finally {
            if (inside) {
                used.negate();
            }
        }
        if (fillOutside) {
            System.arraycopy(out.array(), 0, in.array(), 0, (int) npix);
        }
        return (int) result;
    }
}
