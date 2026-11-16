package uk.ac.starlink.ast;

/**
 * C's {@code AnalysePoly} (fitschan.c): the analysis of a 2-input, 2-output PolyMap into a shift
 * of origin, a polynomial meeting the requirements of the SIP convention, and a linear
 * transformation.
 *
 * <p>Ported from Rust {@code mappings/helpers/analyse_poly.rs}, following fitschan.c.
 */
final class AnalysePoly {

    private AnalysePoly() {}

    /**
     * Analyses {@code polymap} into three Mappings which, applied in series, are equivalent to it:
     * a ShiftMap holding any shift of origin; a PolyMap with no constant terms whose linear terms
     * are an unscaled copy of the corresponding input, as the SIP convention needs; and a UnitMap,
     * or a MatrixMap holding any scaling or rotation. Only a PolyMap with an iterative inverse can
     * be analysed.
     *
     * <p>The shift is found by transforming the origin with the iterative inverse. The PolyMap
     * with that shift merged in, {@code pm1}, has linear terms {@code a*x0 + b*x1} and {@code c*x0
     * + d*x1} on its two outputs. If they are the unit matrix, {@code pm1} itself is the middle
     * Mapping. Otherwise, if their determinant is not zero, the higher terms {@code f} and {@code
     * g} of the two outputs become {@code (d*f - b*g)/det} and {@code (a*g - c*f)/det}, each after
     * a unit linear term, and the PolyMap made from them is simplified. The Invert attribute of
     * {@code polymap} is cleared while it is analysed, and set again afterwards if it was set.
     *
     * @param polymap the PolyMap
     * @param maps an array of at least three elements, which receives the three Mappings, or nulls
     *     if the PolyMap cannot be analysed
     * @return whether the PolyMap was analysed
     * @throws AstException with status {@code AST__INTER} if {@code polymap} does not have two
     *     inputs and two outputs
     */
    static boolean analysePoly(PolyMap polymap, Mapping[] maps) {
        maps[0] = null;
        maps[1] = null;
        maps[2] = null;

        // A PolyMap whose inverse is defined by coefficients would need a merge of a MatrixMap
        // into it, which C does not have.
        if (!polymap.getIterInverse()) {
            return false;
        }

        if (polymap.getNin() != 2 || polymap.getNout() != 2) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "AnalysePoly(fitschan): AST internal error; the supplied PolyMap is not 2-in, 2-out (internal AST"
                            + " programming error).");
        }

        // With Invert cleared, the forward transformation is the one defined by coefficients.
        boolean inverted = polymap.getInvert();
        if (inverted) {
            polymap.clearInvert();
        }
        Mapping map1;
        Mapping map2 = null;
        Mapping map3 = null;
        try {
            // The input position the PolyMap takes to the origin; map1 shifts it to the origin.
            double[][] origin = polymap.tran2(1, new double[] {0.0}, new double[] {0.0}, false);
            double[] shift = {origin[0][0], origin[1][0]};
            shift[0] *= -1.0;
            shift[1] *= -1.0;
            ShiftMap shiftMap = new ShiftMap(shift);
            map1 = shiftMap;

            // pm1, with polymap equivalent to map1 followed by pm1, has no constant terms.
            shiftMap.invert();
            PolyMap pm1;
            try {
                pm1 = polymap.mergeShift(shiftMap, true, true);
            } finally {
                shiftMap.invert();
            }

            double[] coeffsF = pm1.polyCoeffs(true);
            int ncoeffF = coeffsF.length / 4;

            // The linear terms y0 = a*x0 + b*x1 + f(x0, x1) and y1 = c*x0 + d*x1 + g(x0, x1), and
            // the number of quadratic or higher terms.
            double a = 0.0;
            int aIco = -1;
            double b = 0.0;
            int bIco = -1;
            double c = 0.0;
            int cIco = -1;
            double d = 0.0;
            int dIco = -1;
            int nhigh = 0;
            for (int ico = 0; ico < ncoeffF; ico++) {
                int pv = 4 * ico;
                if (coeffsF[pv + 1] == 1) {
                    if (coeffsF[pv + 2] == 1 && coeffsF[pv + 3] == 0) {
                        a = coeffsF[pv];
                        aIco = ico;
                    } else if (coeffsF[pv + 2] == 0 && coeffsF[pv + 3] == 1) {
                        b = coeffsF[pv];
                        bIco = ico;
                    } else {
                        nhigh++;
                    }
                } else {
                    if (coeffsF[pv + 2] == 1 && coeffsF[pv + 3] == 0) {
                        c = coeffsF[pv];
                        cIco = ico;
                    } else if (coeffsF[pv + 2] == 0 && coeffsF[pv + 3] == 1) {
                        d = coeffsF[pv];
                        dIco = ico;
                    } else {
                        nhigh++;
                    }
                }
            }

            double det = a * d - b * c;

            if (a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0) {
                map3 = new UnitMap(2);
                map2 = pm1;
            } else if (det != 0.0) {
                map3 = new MatrixMap(2, 2, MatrixMap.FULL, new double[] {a, b, c, d});

                // w0 = 1*x0 + f'(x0, x1) and w1 = 1*x1 + g'(x0, x1), with f' = (d*f - b*g)/det
                // and g' = -(c*f - a*g)/det. C sets the output index of the next group before
                // testing whether a coefficient is used, past the end of its array after the
                // last group; it is set here only for a group that is used.
                double[] coeffsNew = new double[4 * (2 * nhigh + 2)];
                int pw = 0;
                coeffsNew[pw] = 1.0;
                coeffsNew[pw + 1] = 1;
                coeffsNew[pw + 2] = 1;
                coeffsNew[pw + 3] = 0;
                pw += 4;
                for (int ico = 0; ico < ncoeffF; ico++) {
                    int pv = 4 * ico;
                    if (coeffsF[pv] != 0.0 && ico != aIco && ico != bIco && ico != cIco && ico != dIco) {
                        coeffsNew[pw + 1] = 1;
                        if (coeffsF[pv + 1] == 1) {
                            coeffsNew[pw] = d * coeffsF[pv] / det;
                        } else {
                            coeffsNew[pw] = -b * coeffsF[pv] / det;
                        }
                        coeffsNew[pw + 2] = coeffsF[pv + 2];
                        coeffsNew[pw + 3] = coeffsF[pv + 3];
                        pw += 4;
                    }
                }

                coeffsNew[pw] = 1.0;
                coeffsNew[pw + 1] = 2;
                coeffsNew[pw + 2] = 0;
                coeffsNew[pw + 3] = 1;
                pw += 4;
                for (int ico = 0; ico < ncoeffF; ico++) {
                    int pv = 4 * ico;
                    if (coeffsF[pv] != 0.0 && ico != aIco && ico != bIco && ico != cIco && ico != dIco) {
                        coeffsNew[pw + 1] = 2;
                        if (coeffsF[pv + 1] == 1) {
                            coeffsNew[pw] = -c * coeffsF[pv] / det;
                        } else {
                            coeffsNew[pw] = a * coeffsF[pv] / det;
                        }
                        coeffsNew[pw + 2] = coeffsF[pv + 2];
                        coeffsNew[pw + 3] = coeffsF[pv + 3];
                        pw += 4;
                    }
                }

                // Simplifying amalgamates coefficients that refer to the same powers.
                PolyMap tmap = new PolyMap(2, 2, 2 * nhigh + 2, coeffsNew, 0, null);
                map2 = tmap.simplify();
                if (polymap.testIterInverse() || polymap.testNiterInverse() || polymap.testTolInverse()) {
                    ScalePolyInputs.copyIterInverse(polymap, asPolyMap(map2));
                }
            }
        } finally {
            if (inverted) {
                polymap.setInvert(true);
            }
        }

        if (map2 == null || map3 == null) {
            return false;
        }
        maps[0] = map1;
        maps[1] = map2;
        maps[2] = map3;
        return true;
    }

    /**
     * {@code map} as a PolyMap, for setting a PolyMap attribute on it; C's {@code astCheckPolyMap}
     * reports any other class as an invalid object.
     *
     * @throws AstException with status {@code AST__OBJIN} if {@code map} is not a PolyMap
     */
    private static PolyMap asPolyMap(Mapping map) {
        if (map instanceof PolyMap) {
            return (PolyMap) map;
        }
        throw Ast.err(
                AstException.AST__OBJIN, "Pointer to PolyMap required, but pointer to " + map.astClass() + " given.");
    }
}
