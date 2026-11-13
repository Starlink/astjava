package uk.ac.starlink.ast;

/**
 * The library's package-private NaN protocol, opened to the benchmarks in {@code
 * uk.ac.starlink.ast.bench}. This class is part of the benchmark project only, not of the library.
 */
public final class BenchAccess {

    private BenchAccess() {}

    /**
     * Transforms {@code npoint} points of {@code in} by the NaN protocol with its boundary work in
     * caller-owned buffers, as {@code nan_edges} in the Rust port's {@code ast-bench/src/run.rs}
     * does: bad and non-finite inputs become NaN in {@code nanIn}, the Mapping's {@code
     * transformIntoNan} writes {@code out}, and non-finite outputs become {@link AstObject#AST__BAD}.
     * This is what an entry point using the protocol costs per call once its buffers exist.
     *
     * @param map the Mapping
     * @param in the input points, {@code [ncoordIn][npoint or more]}
     * @param npoint the number of points
     * @param forward true for the forward transformation, false for the inverse
     * @param nanIn a buffer for the inputs with bad values as NaN, shaped as {@code in}
     * @param out the output points, {@code [ncoordOut][npoint or more]}
     */
    public static void nanEdges(
            Mapping map, double[][] in, int npoint, boolean forward, double[][] nanIn, double[][] out) {
        for (int c = 0; c < in.length; c++) {
            double[] src = in[c];
            double[] dst = nanIn[c];
            for (int p = 0; p < npoint; p++) {
                double v = src[p];
                dst[p] = (v == AstObject.AST__BAD || !Double.isFinite(v)) ? Double.NaN : v;
            }
        }
        map.transformIntoNan(nanIn, npoint, forward, out);
        for (double[] axis : out) {
            for (int p = 0; p < npoint; p++) {
                if (!Double.isFinite(axis[p])) {
                    axis[p] = AstObject.AST__BAD;
                }
            }
        }
    }
}
