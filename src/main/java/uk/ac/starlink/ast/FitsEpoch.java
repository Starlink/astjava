package uk.ac.starlink.ast;

/**
 * The date of observation a foreign read gives its Frames: C's {@code ChooseEpoch}, {@code
 * TimeSysToAst} and {@code TDBConv} (fitschan.c:6294, 35046, 34422).
 *
 * <p>Ported from Rust {@code fitschan/epoch.rs}.
 */
final class FitsEpoch {

    private FitsEpoch() {}

    /**
     * C's {@code TimeSysToAst} (fitschan.c:35046): the time scale a TIMESYS value names, as a
     * {@link TimeFrame} {@code AST__} code; UTC when there is none. {@code UT}, {@code ET} and a
     * value AST does not support raise {@code badval} warnings.
     *
     * @throws AstException with status {@code AST__BADIN} if Strict is in force and {@code
     *     warnings} reports {@code badval}
     */
    static int timeSysToAst(String timesys, FitsWarnings warnings) {
        if (timesys == null) {
            return TimeFrame.AST__UTC;
        }
        // MODERN-JAVA(21): switch expression
        switch (timesys) {
            case "UTC":
                return TimeFrame.AST__UTC;
            case "UT":
                warnings.push(new FitsWarnings.Warning(
                        "badval",
                        "The original FITS header contained a value of UT for keyword TIMESYS which is being"
                                + " interpreted as UTC."));
                return TimeFrame.AST__UTC;
            case "TAI":
            case "IAT":
                return TimeFrame.AST__TAI;
            case "ET":
                warnings.push(new FitsWarnings.Warning(
                        "badval",
                        "The original FITS header contained a value of ET for keyword TIMESYS. TT will be used"
                                + " instead."));
                return TimeFrame.AST__TT;
            case "TT":
            case "TDT":
                return TimeFrame.AST__TT;
            case "TDB":
                return TimeFrame.AST__TDB;
            case "TCG":
                return TimeFrame.AST__TCG;
            case "TCB":
                return TimeFrame.AST__TCB;
            default:
                warnings.push(new FitsWarnings.Warning(
                        "badval",
                        "The original FITS header contained a value of " + timesys + " for keyword TIMESYS. AST"
                                + " does not support this timescale so UTC will be used instead."));
                return TimeFrame.AST__UTC;
        }
    }

    /**
     * C's {@code TDBConv} (fitschan.c:34422): the MJD {@code mjd} converted from {@code timescale}
     * to TDB, or with {@code fromTdb} from TDB to {@code timescale}, through the conversion {@code
     * astConvert} finds from an MJD TimeFrame in TDB to one in {@code timescale}; {@code AST__BAD}
     * gives {@code AST__BAD}.
     *
     * @param timescale a {@link TimeFrame} time scale code, such as {@link TimeFrame#AST__TAI}
     */
    static double tdbConv(double mjd, int timescale, boolean fromTdb) {
        return TimeFrame.tdbConv(mjd, timescale, fromTdb);
    }

    /**
     * C's {@code ChooseEpoch} (fitschan.c:6294): the TDB MJD of the observation, from the date
     * {@link FitsWcsKeywords#readEpochIntoStore} stores (MJD-AVG, then MJD-OBS, to which {@code
     * SpecTrans} translates DATE-OBS when there is no MJD-OBS), in the time scale TIMESYS names. The
     * time scale is found, and any warning about it raised, whether or not there is a date.
     *
     * @return the date, or null when there is none
     * @throws AstException with status {@code AST__BADIN} if a TIMESYS warning is an error under
     *     Strict
     */
    static Double chooseEpoch(FitsStore store, FitsWarnings warnings) {
        int timescale = timeSysToAst(store.timesys, warnings);
        return store.mjdobs == null ? null : tdbConv(store.mjdobs, timescale, false);
    }
}
