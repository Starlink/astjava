package uk.ac.starlink.ast;

/**
 * C's {@code ScalePolyInputs} (fitschan.c): a PolyMap that scales each of its inputs before
 * applying a supplied PolyMap.
 *
 * <p>Ported from Rust {@code mappings/helpers/scale_poly_inputs.rs}, following fitschan.c.
 */
final class ScalePolyInputs {

    private ScalePolyInputs() {}

    /**
     * A new PolyMap equivalent to {@code polymap} applied after each input {@code i} is multiplied
     * by {@code scales[i]}. Each coefficient of the forward transformation is multiplied by each
     * scale raised to the power of its input in the term; each coefficient of any inverse
     * transformation is multiplied by the reciprocal of the scale of the input it gives. An
     * inverted PolyMap gives a PolyMap made uninverted and then inverted. The IterInverse,
     * NiterInverse and TolInverse values set on {@code polymap} are set on the new PolyMap.
     *
     * @param polymap the PolyMap, which is not changed
     * @param scales the scale of each input of {@code polymap}
     * @return the new PolyMap
     * @throws AstException with status {@code AST__INTER} if a scale is zero or bad
     */
    static PolyMap scalePolyInputs(PolyMap polymap, double[] scales) {
        int nin = polymap.getNin();
        int nout = polymap.getNout();

        for (int iin = 0; iin < nin; iin++) {
            if (scales[iin] == 0.0 || scales[iin] == AstObject.AST__BAD) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "ScalePolyInputs(fitschan): AST internal programming error - Scale factor for input " + iin
                                + " is zero or bad.");
            }
        }

        // Each forward coefficient, (value, output, nin powers), times the product of the scales
        // raised to their powers.
        double[] coeffsF = polymap.polyCoeffs(true);
        int ncoeffF = coeffsF.length / (nin + 2);
        for (int ico = 0; ico < ncoeffF; ico++) {
            int pv = ico * (nin + 2);
            double cof = coeffsF[pv];
            for (int iin = 0; iin < nin; iin++) {
                cof *= Math.pow(scales[iin], coeffsF[pv + 2 + iin]);
            }
            coeffsF[pv] = cof;
        }

        // Each inverse coefficient, (value, input, nout powers), times the reciprocal of the scale
        // of the input it gives.
        double[] coeffsI = polymap.polyCoeffs(false);
        int ncoeffI = coeffsI.length / (nout + 2);
        for (int ico = 0; ico < ncoeffI; ico++) {
            int pv = ico * (nout + 2);
            int iin = (int) coeffsI[pv + 1] - 1;
            coeffsI[pv] *= 1.0 / scales[iin];
        }

        PolyMap result;
        if (polymap.getInvert()) {
            result = new PolyMap(nout, nin, ncoeffI, coeffsI, ncoeffF, coeffsF);
            result.invert();
        } else {
            result = new PolyMap(nin, nout, ncoeffF, coeffsF, ncoeffI, coeffsI);
        }
        copyIterInverse(polymap, result);
        return result;
    }

    /**
     * Sets on {@code to} each of the IterInverse, NiterInverse and TolInverse attributes that is
     * set on {@code from}, as C's {@code ScalePolyInputs} and {@code AnalysePoly} do.
     */
    static void copyIterInverse(PolyMap from, PolyMap to) {
        if (from.testIterInverse()) {
            to.setIterInverse(from.getIterInverse());
        }
        if (from.testNiterInverse()) {
            to.setNiterInverse(from.getNiterInverse());
        }
        if (from.testTolInverse()) {
            to.setTolInverse(from.getTolInverse());
        }
    }
}
