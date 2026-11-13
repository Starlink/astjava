package uk.ac.starlink.ast;

/**
 * Utilities shared by the Mapping classes.
 *
 * <p>Ported from Rust {@code mappings/helpers/mod.rs}.
 */
final class MappingHelpers {

    /** C's {@code DBL_EPSILON}. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    private MappingHelpers() {}

    /**
     * Whether two values are equal to within a tolerance that scales with them, as C's {@code
     * astEQUALS(a, b, tol)} macro tests: {@code |a - b| <= max(tol * ((|a| + |b|) * DBL_EPSILON),
     * 1e-12)}. The operands are scaled by {@code DBL_EPSILON} before the multiplier is applied, so
     * that operands near the top of the double range do not make the tolerance infinite. Two bad
     * values are equal; a bad value and a good one are not.
     */
    static boolean astEquals(double a, double b, double tol) {
        if (a == AstObject.AST__BAD) {
            return b == AstObject.AST__BAD;
        }
        if (b == AstObject.AST__BAD) {
            return false;
        }
        double eps = Math.max(tol * ((Math.abs(a) + Math.abs(b)) * DBL_EPSILON), 1.0e-12);
        return Math.abs(a - b) <= eps;
    }

    /** {@link #astEquals} with AST's standard multiplier, 1e5, as C's {@code astEQUAL} macro. */
    static boolean astEqual(double a, double b) {
        return astEquals(a, b, 1.0e5);
    }
}
