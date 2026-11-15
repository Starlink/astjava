package uk.ac.starlink.ast;

/**
 * The bounds of one output coordinate of a Mapping over a box of input coordinates, as C's {@code
 * astMapBox} finds them.
 *
 * <p>An initial estimate comes from a fixed set of special points (the corners, the centre of
 * each face, the centroid, the origin if it lies in the box, and a point just inside each corner).
 * It is refined by a global search: simplex searches for local extremes started from
 * pseudo-random positions, in a region that shrinks towards where good values are found, until
 * the same extremes keep being found. The pseudo-random sequence and its seed are C's, so the
 * search follows C's.
 *
 * <p>Ported from Rust {@code mappings/map_box.rs} and C {@code mapping.c} ({@code MapBox}, {@code
 * SpecialBounds}, {@code GlobalBounds}, {@code LocalMaximum}, {@code UphillSimplex}, {@code
 * NewVertex}, {@code MapFunction} and {@code Random}).
 */
final class MapBox {

    /** The Mapping being searched, C's {@code MapData}. */
    private final Mapping mapping;

    /** The lower bound of the box on each input, at most the upper one. */
    private final double[] lbnd;

    /** The upper bound of the box on each input. */
    private final double[] ubnd;

    /** The number of inputs of the transformation used. */
    private final int nin;

    /** The number of outputs of the transformation used. */
    private final int nout;

    /** The output whose bounds are wanted, zero-based. */
    private final int coord;

    /** Whether the forward transformation is used. */
    private final boolean forward;

    /** Whether the function is negated, so that a maximum search finds a minimum. */
    private boolean negate;

    private MapBox(Mapping mapping, double[] lbnd, double[] ubnd, int nin, int nout, int coord, boolean forward) {
        this.mapping = mapping;
        this.lbnd = lbnd;
        this.ubnd = ubnd;
        this.nin = nin;
        this.nout = nout;
        this.coord = coord;
        this.forward = forward;
    }

    /**
     * C's {@code MapBox}, with {@code coordOut} zero-based: the bounds of an output over the box
     * from {@code lbndIn} to {@code ubndIn}.
     *
     * @return the lower and upper bounds
     * @throws AstException with status {@code AST__BADCI} if {@code coordOut} is not an output,
     *     {@code AST__DIMIN} if an array is too short, or {@code AST__MBBNF} if no bounds are found
     */
    static double[] mapBox(
            Mapping map, double[] lbndIn, double[] ubndIn, boolean forward, int coordOut, double[] xl, double[] xu) {
        String cls = map.astClass();
        int nin = forward ? map.getNin() : map.getNout();
        int nout = forward ? map.getNout() : map.getNin();
        if (coordOut < 0 || coordOut >= nout) {
            throw Ast.err(
                    AstException.AST__BADCI,
                    "astMapBox(" + cls + "): Output coordinate index (" + (coordOut + 1)
                            + ") invalid - it should be in the range 1 to " + nout + ".");
        }
        map.checkDim("astMapBox", "lbnd_in", lbndIn.length, nin);
        map.checkDim("astMapBox", "ubnd_in", ubndIn.length, nin);
        if (xl != null) {
            map.checkDim("astMapBox", "xl", xl.length, nin);
        }
        if (xu != null) {
            map.checkDim("astMapBox", "xu", xu.length, nin);
        }

        // The bounds, the right way round.
        double[] lo = new double[nin];
        double[] hi = new double[nin];
        double[] xL = new double[nin];
        double[] xU = new double[nin];
        for (int c = 0; c < nin; c++) {
            xL[c] = AstObject.AST__BAD;
            xU[c] = AstObject.AST__BAD;
            lo[c] = lbndIn[c] < ubndIn[c] ? lbndIn[c] : ubndIn[c];
            hi[c] = ubndIn[c] > lbndIn[c] ? ubndIn[c] : lbndIn[c];
        }
        // The Mapping may be evaluated many times, so it is simplified first.
        MapBox data = new MapBox(map.simplify(), lo, hi, nin, nout, coordOut, forward);
        double[] bounds = {AstObject.AST__BAD, AstObject.AST__BAD};
        try {
            boolean refine = nin > 12 || data.specialBounds(bounds, xL, xU);
            if (refine) {
                data.globalBounds(bounds, xL, xU);
            }
        } catch (AstException e) {
            throw Ast.withContext(e, "Unable to find a bounding box for a " + cls + ".");
        }
        if (xl != null) {
            System.arraycopy(xL, 0, xl, 0, nin);
        }
        if (xu != null) {
            System.arraycopy(xU, 0, xu, 0, nin);
        }
        return bounds;
    }

    /**
     * C's {@code Random}: the "minimal standard" pseudo-random generator, giving a value in [0, 1]
     * and advancing {@code seed[0]}.
     */
    private static double random(long[] seed) {
        long i = seed[0] / 127773;
        seed[0] = (seed[0] - i * 127773) * 16807 - i * 2836;
        if (seed[0] < 0) {
            seed[0] += 2147483647;
        }
        return (seed[0] - 1) / 2147483646.0;
    }

    /**
     * C's {@code MapFunction}: output {@link #coord} at {@code in}, negated if {@link #negate}, or
     * bad if {@code in} is outside the box or any output is bad. {@code ncall[0]} counts the
     * evaluations.
     */
    private double mapFunction(double[] in, int[] ncall) {
        double[][] pt = new double[nin][1];
        for (int c = 0; c < nin; c++) {
            if (in[c] < lbnd[c] || in[c] > ubnd[c]) {
                return AstObject.AST__BAD;
            }
            pt[c][0] = in[c];
        }
        double[][] out = mapping.transform(pt, 1, forward);
        ncall[0]++;
        for (int c = 0; c < nout; c++) {
            if (out[c][0] == AstObject.AST__BAD) {
                return AstObject.AST__BAD;
            }
        }
        double result = out[coord][0];
        return negate ? -result : result;
    }

    /**
     * C's {@code NewVertex}: moves vertex {@code lo} of the simplex {@code x} through the centre of
     * the opposite face, scaling its distance from it by {@code scale}, and replaces it if the
     * function is greater there.
     *
     * @return the function value at the new position
     */
    private double newVertex(int lo, double scale, double[] x, double[] f, int[] ncall, double[] xnew) {
        int ncoord = nin;
        int nvertex = ncoord + 1;
        for (int c = 0; c < ncoord; c++) {
            double xface = 0.0;
            for (int vertex = 0; vertex < nvertex; vertex++) {
                if (vertex != lo) {
                    xface += x[vertex * ncoord + c] / (nvertex - 1);
                }
            }
            xnew[c] = xface + (x[lo * ncoord + c] - xface) * scale;
        }
        double fnew = mapFunction(xnew, ncall);
        if (fnew != AstObject.AST__BAD && fnew > f[lo]) {
            System.arraycopy(xnew, 0, x, lo * ncoord, ncoord);
            f[lo] = fnew;
        }
        return fnew;
    }

    /**
     * Moves every vertex but {@code hi} of the simplex a third of the way towards it, or onto it
     * where rounding stops it moving closer, and evaluates the function there.
     */
    private void contractToward(int hi, int vertex, double[] x, double[] f, int[] ncall) {
        int ncoord = nin;
        for (int c = 0; c < ncoord; c++) {
            double offset = x[vertex * ncoord + c] - x[hi * ncoord + c];
            x[vertex * ncoord + c] = x[hi * ncoord + c] + offset / 3.0;
            if (Math.abs(x[vertex * ncoord + c] - x[hi * ncoord + c]) >= Math.abs(offset)) {
                x[vertex * ncoord + c] = x[hi * ncoord + c];
            }
        }
        double[] v = new double[ncoord];
        System.arraycopy(x, vertex * ncoord, v, 0, ncoord);
        f[vertex] = mapFunction(v, ncall);
        if (f[vertex] == AstObject.AST__BAD) {
            f[vertex] = -Double.MAX_VALUE;
        }
    }

    /**
     * C's {@code UphillSimplex}: a simplex search for a local maximum of the function, starting at
     * {@code xmax} with steps {@code dx}, which receives the position found. Where a step leads
     * outside the region where the function is good, the worst vertex is drawn towards the best
     * rather than the simplex contracting about a face.
     *
     * @param err receives the range of function values over the final simplex
     * @return the maximum found, or bad if none
     */
    private double uphillSimplex(double acc, int maxcall, double[] dx, double[] xmax, double[] err, int[] ncall) {
        final double factor = 3.0;
        err[0] = Double.MAX_VALUE;
        ncall[0] = 0;
        int ncoord = nin;
        int nvertex = ncoord + 1;
        double[] f = new double[nvertex];
        double[] x = new double[ncoord * nvertex];
        double[] xnew = new double[ncoord];
        double[] v = new double[ncoord];
        for (int vertex = 0; vertex < nvertex; vertex++) {
            for (int c = 0; c < ncoord; c++) {
                double tmp = xmax[c];
                if (c == vertex - 1) {
                    tmp += dx[c];
                }
                x[vertex * ncoord + c] = tmp;
            }
            System.arraycopy(x, vertex * ncoord, v, 0, ncoord);
            f[vertex] = mapFunction(v, ncall);
            if (f[vertex] == AstObject.AST__BAD) {
                f[vertex] = -Double.MAX_VALUE;
            }
        }
        int ncalla = nvertex;
        double result;
        while (true) {
            // The lowest, next lowest and highest vertices; of equal vertices the first is taken
            // as the highest, so that the search is stable.
            int lo = f[0] < f[1] ? 0 : 1;
            int nextlo = 1 - lo;
            int hi = 0;
            for (int vertex = 0; vertex < nvertex; vertex++) {
                if (f[vertex] <= f[lo]) {
                    nextlo = lo;
                    lo = vertex;
                } else if (f[vertex] <= f[nextlo] && vertex != lo) {
                    nextlo = vertex;
                }
                if (f[vertex] > f[hi]) {
                    hi = vertex;
                }
            }
            double range = f[hi] == -Double.MAX_VALUE || f[lo] == -Double.MAX_VALUE ? Double.MAX_VALUE : f[hi] - f[lo];
            if (range <= Math.abs(acc) || ncall[0] >= maxcall || ncalla >= 3 * maxcall) {
                System.arraycopy(x, hi * ncoord, xmax, 0, ncoord);
                result = f[hi] == -Double.MAX_VALUE ? AstObject.AST__BAD : f[hi];
                err[0] = range;
                break;
            }
            double fnew = newVertex(lo, -1.0, x, f, ncall, xnew);
            ncalla++;
            if (fnew == AstObject.AST__BAD) {
                contractToward(hi, lo, x, f, ncall);
                ncalla++;
            } else if (fnew >= f[hi]) {
                newVertex(lo, factor, x, f, ncall, xnew);
                ncalla++;
            } else if (fnew <= f[nextlo]) {
                double fsave = f[lo];
                fnew = newVertex(lo, 1.0 / factor, x, f, ncall, xnew);
                ncalla++;
                if (fnew <= fsave) {
                    for (int vertex = 0; vertex < nvertex; vertex++) {
                        if (vertex != hi) {
                            contractToward(hi, vertex, x, f, ncall);
                            ncalla++;
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * C's {@code LocalMaximum}: a local maximum found by repeated simplex searches, each starting
     * where the last finished with steps a thousand times smaller, until the maximum stops
     * improving by more than {@code acc}. {@code x} gives the start and receives the position.
     */
    private double localMaximum(double acc, double fractIn, double[] x) {
        final int maxcall = 1500;
        final int maxiter = 5;
        double result = AstObject.AST__BAD;
        double fract = fractIn;
        double[] err = {0.0};
        int[] ncall = {0};
        double[] dx = new double[nin];
        for (int iter = 0; iter < maxiter; iter++) {
            // Step away from the nearer bound on each axis.
            for (int c = 0; c < nin; c++) {
                double middle = 0.5 * (lbnd[c] + ubnd[c]);
                dx[c] = fract * (ubnd[c] - lbnd[c]);
                if (x[c] > middle) {
                    dx[c] = -dx[c];
                }
            }
            double maximum = uphillSimplex(acc, maxcall, dx, x, err, ncall);
            if (result == AstObject.AST__BAD) {
                result = maximum;
            } else if (maximum >= result) {
                boolean done = maximum - result <= acc && err[0] <= acc;
                result = maximum;
                if (done) {
                    break;
                }
            }
            fract /= 1000.0;
        }
        return result;
    }

    /**
     * C's {@code SpecialBounds}: the bounds at the special points, in {@code bounds}, with the
     * positions where they are reached. If no point is good on every output, the bounds come from
     * points good on the wanted output alone.
     *
     * @return whether the bounds should be refined by the global search: false only when they come
     *     from points good on the wanted output alone
     */
    private boolean specialBounds(double[] bounds, double[] xl, double[] xu) {
        int ncoord = nin;
        int ncorner = 1;
        for (int c = 0; c < ncoord; c++) {
            ncorner *= 2;
        }
        int npoint = 2 * ncorner + 2 * ncoord + 1;
        boolean origin = true;
        for (int c = 0; c < ncoord; c++) {
            if (lbnd[c] > 0.0 || ubnd[c] < 0.0) {
                origin = false;
                break;
            }
        }
        if (origin) {
            npoint++;
        }
        double slbnd = bounds[0];
        double subnd = bounds[1];
        double[] secXl = new double[ncoord];
        double[] secXu = new double[ncoord];

        double[][] in = new double[ncoord][npoint];
        // The corners, from a binary counter of which bound each axis takes.
        boolean[] limit = new boolean[ncoord];
        int point = 0;
        boolean done = false;
        while (!done) {
            for (int c = 0; c < ncoord; c++) {
                in[c][point] = limit[c] ? ubnd[c] : lbnd[c];
            }
            point++;
            done = true;
            for (int c = 0; c < ncoord; c++) {
                if (!limit[c]) {
                    limit[c] = true;
                    done = false;
                    break;
                }
                limit[c] = false;
            }
        }
        for (int face = 0; face < 2 * ncoord; face++) {
            for (int c = 0; c < ncoord; c++) {
                in[c][point] = 0.5 * (lbnd[c] + ubnd[c]);
            }
            in[face / 2][point] = face % 2 != 0 ? lbnd[face / 2] : ubnd[face / 2];
            point++;
        }
        for (int c = 0; c < ncoord; c++) {
            in[c][point] = 0.5 * (lbnd[c] + ubnd[c]);
        }
        int icen = point++;
        for (int ic = 0; ic < ncorner; ic++) {
            for (int c = 0; c < ncoord; c++) {
                in[c][point] = 0.999 * in[c][ic] + 0.001 * in[c][icen];
            }
            point++;
        }
        // The origin, if it is in the box, is left at zero.

        double[][] out = mapping.transform(in, npoint, forward);
        for (point = 0; point < npoint; point++) {
            boolean bad = false;
            for (int c = 0; c < nout; c++) {
                if (out[c][point] == AstObject.AST__BAD) {
                    bad = true;
                    break;
                }
            }
            double f = out[coord][point];
            if (!bad) {
                if (bounds[0] == AstObject.AST__BAD || f < bounds[0]) {
                    bounds[0] = f;
                    copyPoint(in, point, xl);
                }
                if (bounds[1] == AstObject.AST__BAD || f > bounds[1]) {
                    bounds[1] = f;
                    copyPoint(in, point, xu);
                }
            } else if (f != AstObject.AST__BAD) {
                if (slbnd == AstObject.AST__BAD || f < slbnd) {
                    slbnd = f;
                    copyPoint(in, point, secXl);
                }
                if (subnd == AstObject.AST__BAD || f > subnd) {
                    subnd = f;
                    copyPoint(in, point, secXu);
                }
            }
        }
        boolean result = true;
        if (bounds[0] == AstObject.AST__BAD && bounds[1] == AstObject.AST__BAD) {
            bounds[0] = slbnd;
            bounds[1] = subnd;
            System.arraycopy(secXu, 0, xu, 0, ncoord);
            System.arraycopy(secXl, 0, xl, 0, ncoord);
            result = slbnd == AstObject.AST__BAD || subnd == AstObject.AST__BAD;
        }
        return result;
    }

    private static void copyPoint(double[][] in, int point, double[] x) {
        for (int c = 0; c < x.length; c++) {
            x[c] = in[c][point];
        }
    }

    /**
     * C's {@code GlobalBounds}: refines the bounds by searching for local minima and maxima from
     * pseudo-random starting positions until the extremes stop changing, then polishes them.
     *
     * @throws AstException with status {@code AST__MBBNF} if either bound remains unknown
     */
    private void globalBounds(double[] bounds, double[] xl, double[] xu) {
        final double defaultAcc = 3.0e-5;
        final int maxiter = 10000;
        final int minsame = 5;
        final int nbatch = 32;
        int ncoord = nin;
        double[] activeHi = new double[ncoord];
        double[] activeLo = new double[ncoord];
        double[] sampleHi = new double[ncoord];
        double[] sampleLo = new double[ncoord];
        double[] sampleWidth = new double[ncoord];
        double[] x = new double[ncoord];
        // A volume twice that of the region where good values have been found is sampled.
        double oversize = Math.pow(2.0, 1.0 / ncoord);
        for (int c = 0; c < ncoord; c++) {
            activeHi[c] = -Double.MAX_VALUE;
            activeLo[c] = Double.MAX_VALUE;
            sampleWidth[c] = (ubnd[c] - lbnd[c]) * oversize;
            sampleLo[c] = lbnd[c];
            sampleHi[c] = ubnd[c];
        }
        double[][] in = new double[ncoord][nbatch];
        double[][] out = new double[nout][nbatch];
        long[] seed = {1776655449L};
        Sampler sampler = new Sampler(in, out, activeLo, activeHi, sampleLo, sampleHi, sampleWidth, oversize, seed);
        sampler.fill();

        boolean doneMax = false;
        boolean doneMin = false;
        int nmax = 0;
        int nmin = 0;
        int nsameMax = 0;
        int nsameMin = 0;
        // As in C, one flag records whether the starting position is bad: the search for a minimum
        // resets it, and the search for a maximum keeps the value it was left with.
        boolean bad = false;
        for (int iter = 0; iter < maxiter; iter++) {
            double acc = defaultAcc;
            double lb = bounds[0];
            double ub = bounds[1];
            if (lb != AstObject.AST__BAD && ub != AstObject.AST__BAD) {
                acc = Math.abs(ub - lb) * defaultAcc;
            } else if (lb != AstObject.AST__BAD) {
                if (Math.abs(lb) > 1.0) {
                    acc = Math.abs(lb) * defaultAcc;
                }
            } else if (ub != AstObject.AST__BAD && Math.abs(ub) > 1.0) {
                acc = Math.abs(ub) * defaultAcc;
            }

            if (!doneMin) {
                bad = false;
                if (iter == 0 && bounds[0] != AstObject.AST__BAD) {
                    System.arraycopy(xl, 0, x, 0, ncoord);
                } else if (bounds[0] == AstObject.AST__BAD && bounds[1] != AstObject.AST__BAD) {
                    System.arraycopy(xu, 0, x, 0, ncoord);
                } else {
                    bad = sampler.next(x);
                }
                if (!bad) {
                    sampler.extendActive(x);
                    negate = true;
                    double newMin = localMaximum(acc, 0.01, x);
                    if (newMin != AstObject.AST__BAD) {
                        newMin = -newMin;
                        sampler.extendActive(x);
                        nmin++;
                        if (bounds[0] == AstObject.AST__BAD) {
                            nsameMin = 1;
                            bounds[0] = newMin;
                            System.arraycopy(x, 0, xl, 0, ncoord);
                        } else if (newMin < bounds[0]) {
                            nsameMin = bounds[0] - newMin > acc ? 1 : nsameMin + 1;
                            bounds[0] = newMin;
                            System.arraycopy(x, 0, xl, 0, ncoord);
                        } else {
                            nsameMin++;
                        }
                        if (nsameMin >= minsame && nsameMin >= Math.round((double) (0.3f * nmin))) {
                            doneMin = true;
                        }
                    }
                }
            }

            if (!doneMax) {
                if (iter == 0 && bounds[1] != AstObject.AST__BAD) {
                    System.arraycopy(xu, 0, x, 0, ncoord);
                } else if (bounds[1] == AstObject.AST__BAD && bounds[0] != AstObject.AST__BAD) {
                    System.arraycopy(xl, 0, x, 0, ncoord);
                } else {
                    bad = sampler.next(x);
                }
                if (!bad) {
                    sampler.extendActive(x);
                    negate = false;
                    double newMax = localMaximum(acc, 0.01, x);
                    if (newMax != AstObject.AST__BAD) {
                        sampler.extendActive(x);
                        nmax++;
                        if (bounds[1] == AstObject.AST__BAD) {
                            nsameMax = 1;
                            bounds[1] = newMax;
                            System.arraycopy(x, 0, xu, 0, ncoord);
                        } else if (newMax > bounds[1]) {
                            nsameMax = newMax - bounds[1] > acc ? 1 : nsameMax + 1;
                            bounds[1] = newMax;
                            System.arraycopy(x, 0, xu, 0, ncoord);
                        } else {
                            nsameMax++;
                        }
                        if (nsameMax >= minsame && nsameMax >= Math.round((double) (0.3f * nmax))) {
                            doneMax = true;
                        }
                    }
                }
            }
            if (doneMin && doneMax) {
                break;
            }
        }

        // Polish the extremes to machine precision.
        double sqrtEps = Math.sqrt(Math.ulp(1.0));
        if (bounds[0] != AstObject.AST__BAD) {
            negate = true;
            bounds[0] = localMaximum(0.0, sqrtEps, xl);
            if (bounds[0] != AstObject.AST__BAD) {
                bounds[0] = -bounds[0];
            }
        }
        if (bounds[1] != AstObject.AST__BAD) {
            negate = false;
            bounds[1] = localMaximum(0.0, sqrtEps, xu);
        }
        if (bounds[0] == AstObject.AST__BAD || bounds[1] == AstObject.AST__BAD) {
            throw Ast.err(
                    AstException.AST__MBBNF,
                    "astMapBox(" + mapping.astClass() + "): No valid output coordinates (after " + 2 * maxiter
                            + " test points).");
        }
    }

    /**
     * The buffer of pseudo-random positions {@code GlobalBounds} starts its searches from (C's
     * {@code FILL_POSITION_BUFFER}), drawn from a sampling region that follows the active region,
     * where good values have been found.
     */
    private final class Sampler {
        private final double[][] in;
        private final double[][] out;
        private final double[] activeLo;
        private final double[] activeHi;
        private final double[] sampleLo;
        private final double[] sampleHi;
        private final double[] sampleWidth;
        private final double oversize;
        private final long[] seed;
        private int batch;

        Sampler(
                double[][] in,
                double[][] out,
                double[] activeLo,
                double[] activeHi,
                double[] sampleLo,
                double[] sampleHi,
                double[] sampleWidth,
                double oversize,
                long[] seed) {
            this.in = in;
            this.out = out;
            this.activeLo = activeLo;
            this.activeHi = activeHi;
            this.sampleLo = sampleLo;
            this.sampleHi = sampleHi;
            this.sampleWidth = sampleWidth;
            this.oversize = oversize;
            this.seed = seed;
        }

        /**
         * Refills the buffer: on each axis where good values have been found, the sampling width
         * shrinks slowly towards twice the active region (by volume), or grows at once to it, and
         * is centred on it within the box; positions are then drawn uniformly and transformed.
         */
        void fill() {
            int ncoord = nin;
            int nbatch = in[0].length;
            for (int c = 0; c < ncoord; c++) {
                if (activeHi[c] >= activeLo[c]) {
                    double activeWidth = activeHi[c] - activeLo[c];
                    if (activeWidth * oversize < sampleWidth[c]) {
                        sampleWidth[c] /= Math.pow(oversize, 0.25);
                    } else if (activeWidth * oversize > sampleWidth[c]) {
                        sampleWidth[c] = activeWidth * oversize;
                    }
                    sampleLo[c] = (activeLo[c] + activeHi[c] - sampleWidth[c]) * 0.5;
                    sampleHi[c] = (activeLo[c] + activeHi[c] + sampleWidth[c]) * 0.5;
                    if (sampleLo[c] < lbnd[c]) {
                        sampleLo[c] = lbnd[c];
                    }
                    if (sampleHi[c] > ubnd[c]) {
                        sampleHi[c] = ubnd[c];
                    }
                }
            }
            for (int b = 0; b < nbatch; b++) {
                for (int c = 0; c < ncoord; c++) {
                    double r = random(seed);
                    in[c][b] = sampleLo[c] * r + sampleHi[c] * (1.0 - r);
                }
            }
            mapping.transformInto(in, nbatch, forward, out);
            batch = 0;
        }

        /**
         * Takes the next position from the buffer, refilling it if it is empty, into {@code x}
         * if every output is good there.
         *
         * @return whether the position is bad
         */
        boolean next(double[] x) {
            if (batch >= in[0].length) {
                fill();
            }
            boolean bad = false;
            for (double[] axis : out) {
                if (axis[batch] == AstObject.AST__BAD) {
                    bad = true;
                    break;
                }
            }
            if (!bad) {
                for (int c = 0; c < x.length; c++) {
                    x[c] = in[c][batch];
                }
            }
            batch++;
            return bad;
        }

        /** Extends the active region to include {@code x}. */
        void extendActive(double[] x) {
            for (int c = 0; c < x.length; c++) {
                if (x[c] < activeLo[c]) {
                    activeLo[c] = x[c];
                }
                if (x[c] > activeHi[c]) {
                    activeHi[c] = x[c];
                }
            }
        }
    }
}
