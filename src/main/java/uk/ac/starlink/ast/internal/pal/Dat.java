package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Jd2cal;

/**
 * TAI minus UTC at a UTC Modified Julian Date: {@code palDat}.
 *
 * <p>Ported from Rust {@code pal/dat.rs}, itself ported from PAL's {@code palDat} ({@code
 * pal/palDat.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with permission:
 * Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory, Tim Jenness
 * and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Dat {

    private Dat() {}

    /**
     * TAI minus UTC, in seconds, at a UTC date given as an MJD.
     *
     * <p>A date that ERFA cannot convert to a calendar date, or a calendar date it has no
     * leap-second value for, gives 0.0. PAL ignores both statuses and returns ERFA's {@code
     * deltat}, which {@code eraDat} sets to 0.0 before any check and leaves there on every error,
     * so those cases are defined. Where {@code eraJd2cal} fails it writes none of the year, month,
     * day and fraction, and PAL passes whatever was in them to {@code eraDat}, so C's result there
     * depends on the stack; 0.0 is what {@code eraDat} gives for the year, month and day fields
     * {@code eraJd2cal} leaves zero, and it is what this returns.
     */
    public static double dat(double dju) {
        Jd2cal.Result cal = Jd2cal.jd2cal(Pal.PAL__MJD0, dju);
        if (cal == null) {
            return 0.0;
        }
        uk.ac.starlink.ast.internal.erfa.Dat.Result r =
                uk.ac.starlink.ast.internal.erfa.Dat.dat(cal.iy, cal.im, cal.id, cal.fd);
        return r.error == null ? r.delat : 0.0;
    }
}
