package uk.ac.starlink.ast.testsupport;

/**
 * One section of a C transform oracle: sampled inputs and the C library's outputs for one fixture
 * in one direction, with the tolerances stated in the oracle file's header.
 */
public final class OracleSection {

    /** The fixture path, relative to {@link Fixtures#root}. */
    public final String path;

    /** Number of input coordinates. */
    public final int nin;

    /** Number of output coordinates. */
    public final int nout;

    /** Whether the section records the forward transformation. */
    public final boolean forward;

    /** One row per point: {@code nin} inputs then {@code nout} outputs, BAD read as {@link CValues#BAD}. */
    public final double[][] rows;

    /** Tolerances of the live outputs against the recorded values. */
    public final double rtol;

    public final double atol;

    /** Tolerances of a {@code .map} fixture against its {@code .simp}. */
    public final double equivRtol;

    public final double equivAtol;

    /** Tolerances of the round trip of inverse after forward. */
    public final double rtripRtol;

    public final double rtripAtol;

    OracleSection(String path, int nin, int nout, boolean forward, double[][] rows, double[] tol) {
        this.path = path;
        this.nin = nin;
        this.nout = nout;
        this.forward = forward;
        this.rows = rows;
        this.rtol = tol[0];
        this.atol = tol[1];
        this.equivRtol = tol[2];
        this.equivAtol = tol[3];
        this.rtripRtol = tol[4];
        this.rtripAtol = tol[5];
    }

    /** Number of points. */
    public int npoint() {
        return rows.length;
    }

    /** The inputs as {@code [nin][npoint]}. */
    public double[][] inputs() {
        return columns(0, nin);
    }

    /** The outputs as {@code [nout][npoint]}. */
    public double[][] outputs() {
        return columns(nin, nout);
    }

    private double[][] columns(int first, int n) {
        double[][] out = new double[n][rows.length];
        for (int p = 0; p < rows.length; p++) {
            for (int c = 0; c < n; c++) {
                out[c][p] = rows[p][first + c];
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "[" + path + " nin=" + nin + " nout=" + nout + " dir=" + (forward ? "forward" : "inverse") + "]";
    }
}
