package uk.ac.starlink.ast;

/** Functions of the C library that {@code java.lang.Math} lacks or defines differently. */
final class CMath {

    private CMath() {}

    /**
     * C's {@code round}: to the nearest integer, halfway cases away from zero. The fraction is
     * found as the difference of the magnitude and its floor, which is exact, so a value just
     * below a half is not rounded up; infinities, NaN and signed zeros are returned as given.
     */
    static double roundHalfAway(double x) {
        double a = Math.abs(x);
        double fl = Math.floor(a);
        return Math.copySign(a - fl >= 0.5 ? fl + 1.0 : fl, x);
    }
}
