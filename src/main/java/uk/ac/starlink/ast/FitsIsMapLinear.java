package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * C's {@code IsMapLinear} (fitschan.c): whether an output of a Mapping is a linear function of its
 * inputs over a box.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/is_map_linear.rs}, following fitschan.c.
 */
final class FitsIsMapLinear {

    private static final double AST__BAD = AstObject.AST__BAD;

    /** The number of positions fitted, C's {@code NP}. */
    private static final int NP = 20;

    private FitsIsMapLinear() {}

    /**
     * Whether output {@code coordOut} of {@code smap} is linear in its inputs over the box from
     * {@code lbndIn} to {@code ubndIn}: {@code NP} positions evenly spread over the range of the
     * output (from C's {@code MapBox}), the other outputs held at their values where it is lowest,
     * are transformed back to the inputs, and a straight line fitted to each input that varies
     * must reproduce it to within 1e-7 of its range. The fit is made to values offset by the first
     * of each. When a single input feeds the output, the Mapping split off for it is used.
     *
     * <p>As C does, the search for the largest input value starts from {@code DBL_MIN}, the smallest
     * positive normal value, so for inputs that are all negative it is {@code DBL_MIN}.
     *
     * @param smap the Mapping, which is inverted and inverted back to split it, as C does
     * @param coordOut the output to test, zero-based
     * @param coordIn null, or an array whose first element receives the zero-based index of the
     *     input that alone feeds the output, or -1 if there is none
     */
    static boolean isMapLinear(Mapping smap, double[] lbndIn, double[] ubndIn, int coordOut, int[] coordIn) {
        if (coordIn != null) {
            coordIn[0] = -1;
        }

        // Split off the output, inverting the Mapping so that other outputs whose inverse is not
        // defined do not stop the split.
        MapSplit split;
        smap.invert();
        try {
            split = smap.mapSplit(new int[] {coordOut});
        } finally {
            smap.invert();
        }
        Mapping map;
        double[] lbnd = lbndIn;
        double[] ubnd = ubndIn;
        int cout = coordOut;
        if (split != null && split.map.getNout() == 1) {
            int ins0 = split.outputs[0];
            if (coordIn != null) {
                coordIn[0] = ins0;
            }
            map = split.map;
            map.invert();
            lbnd = Arrays.copyOfRange(lbndIn, ins0, lbndIn.length);
            ubnd = Arrays.copyOfRange(ubndIn, ins0, ubndIn.length);
            cout = 0;
        } else {
            map = smap;
        }

        boolean ret = false;
        if (map.getTranForward() && map.getTranInverse()) {
            int nin = map.getNin();
            int nout = map.getNout();
            double[] xl = new double[nin];

            // The bounds of the output, and the input position of its lowest value. A box that
            // cannot be found leaves the Mapping non-linear, C clearing the error.
            boolean boxok;
            double lbndOut = 0.0;
            double ubndOut = 0.0;
            try {
                double[] bounds = MapBox.mapBox(map, lbnd, ubnd, true, cout, xl, null);
                lbndOut = bounds[0];
                ubndOut = bounds[1];
                boxok = true;
                if (MappingHelpers.astEqual(lbndOut, ubndOut)) {
                    double m = 0.5 * (lbndOut + ubndOut);
                    if (Math.abs(m) > 1.0e-15) {
                        lbndOut = 0.9 * m;
                        ubndOut = 1.1 * m;
                    } else {
                        lbndOut = -1.0;
                        ubndOut = 1.0;
                    }
                }
            } catch (AstException e) {
                boxok = false;
            }

            if (boxok) {
                double[][] ptr1 = new double[nin][NP];
                for (int i = 0; i < nin; i++) {
                    Arrays.fill(ptr1[i], AST__BAD);
                    ptr1[i][0] = xl[i];
                }
                double[][] ptr2 = map.transform(ptr1, NP, true);

                // NP positions spread along the output from its lowest value, the other outputs
                // held at their values there.
                double delta = (ubndOut - lbndOut) / (NP - 1);
                for (int i = 0; i < nout; i++) {
                    double[] p = ptr2[i];
                    if (i == cout) {
                        for (int j = 0; j < NP; j++) {
                            p[j] = lbndOut + j * delta;
                        }
                    } else {
                        double p0 = p[0];
                        Arrays.fill(p, p0);
                    }
                }
                ptr1 = map.transform(ptr2, NP, false);

                ret = true;
                double[] s = ptr2[cout];
                double soff = s[0];
                for (int i = 0; i < nin; i++) {
                    double[] p = ptr1[i];
                    double poff = p[0];
                    double sp = 0.0;
                    double ss = 0.0;
                    double sps = 0.0;
                    double sn = 0.0;
                    double ss2 = 0.0;
                    double inLbnd = Double.MAX_VALUE;
                    double inUbnd = Double.MIN_NORMAL;
                    for (int j = 0; j < NP; j++) {
                        double sv = s[j];
                        double pv = p[j];
                        if (pv != AST__BAD && sv != AST__BAD) {
                            if (pv < inLbnd) {
                                inLbnd = pv;
                            }
                            if (pv > inUbnd) {
                                inUbnd = pv;
                            }
                            sv -= soff;
                            pv -= poff;
                            sp += pv;
                            ss += sv;
                            sps += pv * sv;
                            sn += 1.0;
                            ss2 += sv * sv;
                        } else {
                            sn = 0.0;
                            break;
                        }
                    }

                    // Inputs independent of the output are ignored.
                    if (!MappingHelpers.astEqual(inLbnd, inUbnd)) {
                        double d = ss * ss - sn * ss2;
                        if (sn > 0.0 && d != 0.0) {
                            double m = (sp * ss - sps * sn) / d;
                            double c = (sps * ss - sp * ss2) / d;
                            for (int j = 0; j < NP; j++) {
                                p[j] -= poff + m * (s[j] - soff) + c;
                            }

                            // A second fit, to the residuals, which the first sometimes leaves
                            // with a gradient.
                            sp = 0.0;
                            sps = 0.0;
                            for (int j = 0; j < NP; j++) {
                                double pv = p[j];
                                sp += pv;
                                sps += pv * (s[j] - soff);
                            }
                            m = (sp * ss - sps * sn) / d;
                            c = (sps * ss - sp * ss2) / d;
                            for (int j = 0; j < NP; j++) {
                                p[j] -= m * (s[j] - soff) + c;
                            }

                            double tol = 1.0e-7 * (inUbnd - inLbnd);
                            for (int j = 0; j < NP; j++) {
                                if (Math.abs(p[j]) > tol) {
                                    ret = false;
                                    break;
                                }
                            }
                        } else {
                            ret = false;
                        }
                    }
                    if (!ret) {
                        break;
                    }
                }
            }
        }
        return ret;
    }
}
