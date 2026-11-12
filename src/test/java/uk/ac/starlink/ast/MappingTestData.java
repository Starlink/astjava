package uk.ac.starlink.ast;

/** Points for Mapping tests, converted between rows (one per point) and C's per-coordinate layout. */
final class MappingTestData {

    private MappingTestData() {}

    /** The points given one row per point, as {@code [ncoord][npoint]}. */
    static double[][] fromRows(double[]... rows) {
        int ncoord = rows.length == 0 ? 0 : rows[0].length;
        double[][] out = new double[ncoord][rows.length];
        for (int p = 0; p < rows.length; p++) {
            for (int c = 0; c < ncoord; c++) {
                out[c][p] = rows[p][c];
            }
        }
        return out;
    }

    /** Point {@code p} of points held as {@code [ncoord][npoint]}. */
    static double[] point(double[][] coords, int p) {
        double[] out = new double[coords.length];
        for (int c = 0; c < coords.length; c++) {
            out[c] = coords[c][p];
        }
        return out;
    }

    /** Transforms points given one row per point. */
    static double[][] transformRows(Mapping map, boolean forward, double[]... rows) {
        return map.transform(fromRows(rows), rows.length, forward);
    }

    /**
     * Whether {@code map} is a UnitMap, or a CmpMap whose components all are, which C's {@code
     * astSimplify} reduces to a UnitMap. Where C returns a simplified UnitMap, this library, which
     * does not simplify, returns such a chain.
     */
    static boolean isUnitChain(Mapping map) {
        if (map instanceof UnitMap) {
            return true;
        }
        if (map instanceof CmpMap) {
            CmpMap cmp = (CmpMap) map;
            return isUnitChain(cmp.getMap1()) && isUnitChain(cmp.getMap2());
        }
        return false;
    }
}
