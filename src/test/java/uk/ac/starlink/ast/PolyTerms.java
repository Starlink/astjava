package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * PolyMap coefficients in the form C's {@code astPolyMap} takes them: each term is its coefficient,
 * the 1-based output it adds to, and one power per input.
 */
final class PolyTerms {

    private PolyTerms() {}

    /** One term: {@code coeff}, the 1-based output {@code out}, and the power of each input. */
    static double[] term(double coeff, int out, int... powers) {
        double[] t = new double[2 + powers.length];
        t[0] = coeff;
        t[1] = out;
        System.arraycopy(Arrays.stream(powers).asDoubleStream().toArray(), 0, t, 2, powers.length);
        return t;
    }

    /** The terms one after another, as one coefficient array. */
    static double[] flat(double[]... terms) {
        int n = 0;
        for (double[] t : terms) {
            n += t.length;
        }
        double[] out = new double[n];
        int k = 0;
        for (double[] t : terms) {
            System.arraycopy(t, 0, out, k, t.length);
            k += t.length;
        }
        return out;
    }

    /** A PolyMap with the given forward terms and, if {@code inv} is not null, inverse terms. */
    static PolyMap poly(int nin, int nout, double[][] fwd, double[][] inv) {
        return new PolyMap(
                nin, nout, fwd.length, flat(fwd), inv == null ? 0 : inv.length, inv == null ? null : flat(inv));
    }

    /** A PolyMap with the given forward terms only. */
    static PolyMap poly(int nin, int nout, double[]... fwd) {
        return poly(nin, nout, fwd, null);
    }

    /** {@code n} coefficients of a C coefficient array, for a PolyMap with {@code nin} inputs. */
    static PolyMap cPoly(int nin, double[] cofs) {
        return new PolyMap(nin, nin, cofs.length / (2 + nin), cofs, 0, null);
    }
}
