package uk.ac.starlink.ast;

/**
 * A 1-dimensional Mapping whose inverse transformation is not defined and whose forward
 * transformation doubles each value, standing in for the RateMap the Rust tests use (RateMap is
 * outside the first phase): C's ratemap.c creates its Mapping with the inverse undefined.
 */
final class NoInverseMap extends Mapping {

    NoInverseMap() {
        super(1, 1, true, false);
    }

    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        for (int p = 0; p < npoint; p++) {
            out[0][p] = isBad(in[0][p]) ? AST__BAD : 2.0 * in[0][p];
        }
    }
}
