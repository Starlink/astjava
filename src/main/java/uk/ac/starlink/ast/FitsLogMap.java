package uk.ac.starlink.ast;

/**
 * The MathMap C's {@code LogAxis} (fitschan.c) makes to test an axis for the {@code -LOG}
 * algorithm: forward {@code logs=log(s)}, inverse {@code s=exp(logs)}, with SimpFI and SimpIF set.
 * It computes what that MathMap computes, as mathmap.c's evaluator does it: the logarithm of a
 * value that is not positive is bad, and so is an exponential that overflows.
 *
 * <p>A MathMap merges in simplification only with another MathMap (mathmap.c {@code MapMerge}). The
 * Mappings this one meets come from FrameSets of this library, which hold no MathMap, so it never
 * merges with anything; nor does C override any other Mapping method for MathMap but {@code
 * Equal}, which nothing in {@code LogAxis} reaches.
 */
final class FitsLogMap extends Mapping {

    /** Constructs the Mapping, one input and one output, defined in both directions. */
    FitsLogMap() {
        super(1, 1, true, true);
    }

    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean fwd = effectiveForward(forward);
        double[] a = in[0];
        double[] o = out[0];
        for (int p = 0; p < npoint; p++) {
            double x = a[p];
            if (isBad(x)) {
                o[p] = AST__BAD;
            } else if (fwd) {
                // mathmap.c OP_LOG: ( x > 0.0 ) ? log( x ) : AST__BAD.
                o[p] = x > 0.0 ? Math.log(x) : AST__BAD;
            } else {
                // mathmap.c OP_EXP through CATCH_MATHS_OVERFLOW: an overflow, which only a finite
                // argument reaches here, is AST__BAD.
                double r = Math.exp(x);
                o[p] = Double.isInfinite(r) ? AST__BAD : r;
            }
        }
    }
}
