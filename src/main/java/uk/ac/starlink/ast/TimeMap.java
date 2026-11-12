package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import uk.ac.starlink.ast.internal.erfa.Dat;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A sequence of conversions between standard time coordinate systems.
 *
 * <p>A TimeMap is a 1-dimensional Mapping. When created it performs a unit (null) Mapping. {@link
 * #timeAdd} then appends time coordinate conversions, such as MJD to Julian Date, UTC to TAI or TT
 * to TDB, so that multi-step conversions between a variety of time coordinate systems can be
 * assembled out of a set of building blocks.
 *
 * <p>For details of the individual coordinate conversions available, see {@link #timeAdd}.
 *
 * <p>Ported from Rust {@code mappings/timemap.rs} and C {@code timemap.c}.
 */
public class TimeMap extends Mapping {

    /** Seconds per day, C's {@code SPD}. */
    private static final double SPD = 86400.0;

    /** The rate of drift of TCG with respect to TT, C's {@code LG}. */
    private static final double LG = 6.969290134e-10;

    /** The rate of drift of TCB with respect to TDB, C's {@code LB}. */
    private static final double LB = 1.55051976772e-8;

    /** The TDB-TCB offset at 1977 January 1.0 TAI, in seconds, C's {@code P0}. */
    private static final double P0 = 6.55e-5;

    /** TT minus TAI, in seconds, C's {@code TTOFF}. */
    private static final double TTOFF = 32.184;

    /** 2 pi, C's {@code D2PI}. */
    private static final double D2PI = 2 * Math.PI;

    /** Degrees to radians, C's {@code D2R}. */
    private static final double D2R = Math.PI / 180.0;

    /** The astronomical unit in metres, C's {@code AST__AU}. */
    private static final double AU = 1.49597870e11;

    /** The UTC MJD at which each {@link Dat#LEAP_SECONDS} entry takes effect. */
    private static final double[] UTC_STARTS = utcStarts();

    /**
     * The TAI MJD at which each {@link Dat#LEAP_SECONDS} entry takes effect: the UTC instant plus
     * the offset in seconds, which before 1972 is the value at that instant, its drift included.
     */
    private static final double[] TAI_STARTS = taiStarts();

    /**
     * The conversions a TimeMap can apply: C's conversion codes with their values. Each has its
     * string (its name), the comment Dump writes for it, its number of arguments, and the
     * descriptions of the values it stores, which are its arguments followed by values derived from
     * them (timemap.c {@code CvtString}).
     */
    enum Cvt {
        MJDTOMJD(1, "Convert MJD between offsets", 2, "Input MJD offset", "Output MJD offset", "Combined offset"),
        MJDTOJD(2, "Convert MJD to JD", 2, "MJD offset", "JD offset", "Combined offset"),
        JDTOMJD(3, "Convert JD to MJD", 2, "JD offset", "MJD offset", "Combined offset"),
        MJDTOBEP(
                4,
                "Convert MJD to Besselian epoch",
                2,
                "MJD offset",
                "Besselian epoch offset",
                "Combined forward offset",
                "Combined inverse offset"),
        BEPTOMJD(
                5,
                "Convert Besselian epoch to MJD",
                2,
                "Besselian epoch offset",
                "MJD offset",
                "Combined forward offset",
                "Combined inverse offset"),
        MJDTOJEP(
                6,
                "Convert MJD to Julian epoch",
                2,
                "MJD offset",
                "Julian epoch offset",
                "Combined forward offset",
                "Combined inverse offset"),
        JEPTOMJD(
                7,
                "Convert Julian epoch to MJD",
                2,
                "Julian epoch offset",
                "MJD offset",
                "Combined forward offset",
                "Combined inverse offset"),
        TAITOUTC(8, "Convert TAI to UTC", 2, "MJD offset", "DTAI"),
        UTCTOTAI(9, "Convert UTC to TAI", 2, "MJD offset", "DTAI"),
        TTTOTAI(10, "Convert TT to TAI", 1, "MJD offset"),
        TAITOTT(11, "Convert TAI to TT", 1, "MJD offset"),
        TDBTOTT(
                12,
                "Convert TDB to TT",
                5,
                "MJD offset",
                "Observer longitude",
                "Observer latitude",
                "Observer altitude",
                "DTAI",
                "Distance from earth spin axis",
                "Distance north of equatorial plane"),
        TTTOTDB(
                13,
                "Convert TT to TDB",
                5,
                "MJD offset",
                "Observer longitude",
                "Observer latitude",
                "Observer altitude",
                "DTAI",
                "Distance from earth spin axis",
                "Distance north of equatorial plane"),
        TCGTOTT(14, "Convert TCG to TT", 1, "MJD offset", "TCG offset"),
        TTTOTCG(15, "Convert TT to TCG", 1, "MJD offset", "TCG offset"),
        TCBTOTDB(16, "Convert TCB to TDB", 1, "MJD offset", "TCB offset"),
        TDBTOTCB(17, "Convert TDB to TCB", 1, "MJD offset", "TCB offset"),
        UTTOGMST(18, "Convert UT to GMST", 1, "MJD offset"),
        GMSTTOUT(19, "Convert GMST to UT", 1, "MJD offset"),
        GMSTTOLMST(20, "Convert GMST to LMST", 3, "MJD offset", "Observer longitude", "Observer latitude"),
        LMSTTOGMST(21, "Convert LMST to GMST", 3, "MJD offset", "Observer longitude", "Observer latitude"),
        LASTTOLMST(22, "Convert LAST to LMST", 3, "MJD offset", "Observer longitude", "Observer latitude"),
        LMSTTOLAST(23, "Convert LMST to LAST", 3, "MJD offset", "Observer longitude", "Observer latitude"),
        UTTOUTC(24, "Convert UT1 to UTC", 1, "DUT1"),
        UTCTOUT(25, "Convert UTC to UT1", 1, "DUT1"),
        LTTOUTC(26, "Convert Local Time to UTC", 1, "LTOFF"),
        UTCTOLT(27, "Convert UTC to Local Time", 1, "LTOFF");

        /** C's integer code for the conversion. */
        final int cCode;

        /** The comment Dump writes beside the conversion. */
        final String comment;

        /** The number of arguments the conversion takes. */
        final int nargs;

        /** The descriptions of the stored values, one for each. */
        // The array is private and never modified.
        @SuppressWarnings("ImmutableEnumChecker")
        private final String[] argdescs;

        Cvt(int cCode, String comment, int nargs, String... argdesc) {
            this.cCode = cCode;
            this.comment = comment;
            this.nargs = nargs;
            this.argdescs = argdesc;
        }

        /** The number of values stored for the conversion, C's {@code szargs}. */
        int szargs() {
            return argdescs.length;
        }

        /** The description of stored value {@code i}. */
        String argdesc(int i) {
            return argdescs[i];
        }

        /**
         * For the TT and TDB conversions, the stored value written as each argument of a dump,
         * whose DTAI comes last there for backward compatibility; null for the others, which are
         * written in their stored order (timemap.c {@code CvtString}).
         */
        int[] order() {
            return this == TDBTOTT || this == TTTOTDB ? new int[] {0, 1, 2, 3, 5, 6, 4} : null;
        }

        /**
         * The conversion named {@code s}, matched as C's {@code CvtCode} matches it, with {@code
         * astChrMatch} (ignoring case and trailing white space), or null if there is none.
         */
        static Cvt fromCode(String s) {
            for (Cvt c : values()) {
                if (chrMatch(s, c.name())) {
                    return c;
                }
            }
            return null;
        }
    }

    /** One conversion of the sequence: C's {@code cvttype} and {@code cvtargs}. */
    private static final class Step {
        final Cvt cvt;

        /** The arguments followed by the values derived from them, {@code szargs} in all. */
        final double[] args;

        Step(Cvt cvt, double[] args) {
            this.cvt = cvt;
            this.args = args;
        }
    }

    /** The conversions, in the order they were added. */
    private List<Step> steps = new ArrayList<>();

    /** Creates a TimeMap with no conversions, which performs a unit Mapping. */
    public TimeMap() {
        super(1, 1, true, true);
    }

    /**
     * Creates a TimeMap with no conversions, which performs a unit Mapping.
     *
     * @param flags reserved for future use; should be zero, and has no effect
     */
    public TimeMap(int flags) {
        this();
    }

    /**
     * Loads a TimeMap from its description, as C's {@code astLoadTimeMap} does: the number of
     * conversions, then each conversion's string and every value stored for it, derived ones
     * included, as written, a missing value being {@link #AST__BAD}.
     *
     * @throws AstException with status {@code AST__BADIN} if a conversion's string is missing or
     *     names no conversion
     */
    TimeMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("TimeMap");
        int ncvt = Math.max(0, part.readInt("Ntime", 0));
        for (int icvt = 0; icvt < ncvt; icvt++) {
            String key = "Time" + (icvt + 1);
            String sval = part.readString(key, null);
            if (sval == null) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astRead(Channel): A time coordinate conversion type is missing from the input TimeMap"
                                + " data.");
            }
            Cvt cvt = Cvt.fromCode(sval);
            if (cvt == null) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astRead(Channel): Invalid time conversion type \"" + sval + "\" in TimeMap data.");
            }
            int[] order = cvt.order();
            double[] args = new double[cvt.szargs()];
            for (int iarg = 0; iarg < args.length; iarg++) {
                int jarg = order == null ? iarg : order[iarg];
                args[jarg] = part.readDouble(key + argLetter(iarg), AST__BAD);
            }
            steps.add(new Step(cvt, args));
        }
    }

    /** The letter that follows a conversion's key for argument {@code i}, C's {@code ALPHABET}. */
    private static char argLetter(int i) {
        return (char) ('a' + i);
    }

    /** Completes a copy as C's {@code Copy} (timemap.c) does, with its own conversions. */
    @Override
    void deepCopy() {
        super.deepCopy();
        List<Step> copied = new ArrayList<>(steps.size());
        for (Step s : steps) {
            copied.add(new Step(s.cvt, s.args.clone()));
        }
        steps = copied;
    }

    @Override
    String dumpComment() {
        return "Conversion between time coordinate systems";
    }

    /**
     * Writes the number of conversions, set when there are any, then each conversion's string and
     * every stored value that is not bad, derived ones included, as C's {@code Dump} (timemap.c)
     * does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        int ncvt = steps.size();
        node.writeInt("Ntime", ncvt != 0, false, ncvt, "Number of conversion steps");
        for (int icvt = 0; icvt < ncvt; icvt++) {
            Step s = steps.get(icvt);
            String key = "Time" + (icvt + 1);
            node.writeString(key, true, true, s.cvt.name(), s.cvt.comment);
            int[] order = s.cvt.order();
            for (int iarg = 0; iarg < s.cvt.szargs(); iarg++) {
                int jarg = order == null ? iarg : order[iarg];
                if (!isBad(s.args[jarg])) {
                    node.writeDouble(key + argLetter(iarg), true, true, s.args[jarg], s.cvt.argdesc(jarg));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Adding conversions
    // ------------------------------------------------------------------

    /**
     * Adds one of the standard time coordinate system conversions listed below to an existing
     * TimeMap.
     *
     * <p>When a TimeMap is first created, it simply performs a unit (null) Mapping. By using this
     * method repeatedly, a series of coordinate conversion steps may then be added. This allows
     * multi-step conversions between a variety of time coordinate systems to be assembled out of
     * the building blocks provided by this class.
     *
     * <p>The forward transformation of the TimeMap applies the conversions in the order they were
     * added, and its inverse transformation applies the inverse of each conversion in the reverse
     * order. No check is made that the sequence of conversions is physically meaningful. When
     * assembling a multi-step conversion, it is often simplest to include every step that is
     * logically needed and to simplify the TimeMap afterwards, which removes the steps that turn
     * out not to be needed.
     *
     * <p>The nature and units of the values the TimeMap transforms must suit the first conversion
     * it applies, and those it gives are set by the last: Julian epochs and their offsets are in
     * Julian years, Besselian epochs and their offsets in tropical years, and Modified Julian Dates
     * (MJD), Julian Dates (JD) and their offsets in days. The conversions, with the arguments each
     * takes in the order they are given, are:
     *
     * <ul>
     *   <li>{@code "MJDTOMJD"} (MJDOFF1, MJDOFF2): convert an MJD from one offset to another;
     *   <li>{@code "MJDTOJD"} (MJDOFF, JDOFF): convert an MJD to a JD;
     *   <li>{@code "JDTOMJD"} (JDOFF, MJDOFF): convert a JD to an MJD;
     *   <li>{@code "MJDTOBEP"} (MJDOFF, BEPOFF): convert an MJD to a Besselian epoch;
     *   <li>{@code "BEPTOMJD"} (BEPOFF, MJDOFF): convert a Besselian epoch to an MJD;
     *   <li>{@code "MJDTOJEP"} (MJDOFF, JEPOFF): convert an MJD to a Julian epoch;
     *   <li>{@code "JEPTOMJD"} (JEPOFF, MJDOFF): convert a Julian epoch to an MJD;
     *   <li>{@code "TAITOUTC"} (MJDOFF, DTAI): convert a TAI MJD to a UTC MJD;
     *   <li>{@code "UTCTOTAI"} (MJDOFF, DTAI): convert a UTC MJD to a TAI MJD;
     *   <li>{@code "TAITOTT"} (MJDOFF): convert a TAI MJD to a TT MJD;
     *   <li>{@code "TTTOTAI"} (MJDOFF): convert a TT MJD to a TAI MJD;
     *   <li>{@code "TTTOTDB"} (MJDOFF, OBSLON, OBSLAT, OBSALT, DTAI): convert a TT MJD to a TDB
     *       MJD;
     *   <li>{@code "TDBTOTT"} (MJDOFF, OBSLON, OBSLAT, OBSALT, DTAI): convert a TDB MJD to a TT
     *       MJD;
     *   <li>{@code "TTTOTCG"} (MJDOFF): convert a TT MJD to a TCG MJD;
     *   <li>{@code "TCGTOTT"} (MJDOFF): convert a TCG MJD to a TT MJD;
     *   <li>{@code "TDBTOTCB"} (MJDOFF): convert a TDB MJD to a TCB MJD;
     *   <li>{@code "TCBTOTDB"} (MJDOFF): convert a TCB MJD to a TDB MJD;
     *   <li>{@code "UTTOGMST"} (MJDOFF): convert a UT MJD to a GMST MJD;
     *   <li>{@code "GMSTTOUT"} (MJDOFF): convert a GMST MJD to a UT MJD;
     *   <li>{@code "GMSTTOLMST"} (MJDOFF, OBSLON, OBSLAT): convert a GMST MJD to an LMST MJD;
     *   <li>{@code "LMSTTOGMST"} (MJDOFF, OBSLON, OBSLAT): convert an LMST MJD to a GMST MJD;
     *   <li>{@code "LASTTOLMST"} (MJDOFF, OBSLON, OBSLAT): convert a LAST MJD to an LMST MJD;
     *   <li>{@code "LMSTTOLAST"} (MJDOFF, OBSLON, OBSLAT): convert an LMST MJD to a LAST MJD;
     *   <li>{@code "UTTOUTC"} (DUT1): convert a UT1 MJD to a UTC MJD;
     *   <li>{@code "UTCTOUT"} (DUT1): convert a UTC MJD to a UT1 MJD;
     *   <li>{@code "LTTOUTC"} (LTOFF): convert a Local Time MJD to a UTC MJD;
     *   <li>{@code "UTCTOLT"} (LTOFF): convert a UTC MJD to a Local Time MJD.
     * </ul>
     *
     * <p>The arguments are: MJDOFF, JDOFF, BEPOFF and JEPOFF, the zero-points of MJD, JD,
     * Besselian epoch and Julian epoch values, the values transformed being offsets from them;
     * OBSLON, the observer's longitude in radians (positive westwards); OBSLAT, the observer's
     * geodetic latitude (IAU 1975) in radians (positive northwards); OBSALT, the observer's geodetic
     * altitude (IAU 1975) in metres; DTAI, the value of TAI-UTC in seconds, or {@link #AST__BAD} to
     * use the built-in table of leap seconds; DUT1, the value of UT1-UTC in seconds; and LTOFF, the
     * offset between Local Time and UTC in hours, positive for time zones east of Greenwich.
     *
     * @param cvt the conversion, as one of the strings above (case is not significant)
     * @param args its arguments, in the order given above
     * @throws AstException with status {@code AST__TIMIN} if {@code cvt} names no conversion or
     *     {@code args} does not hold exactly the conversion's number of arguments
     */
    public void timeAdd(String cvt, double[] args) {
        Cvt type = Cvt.fromCode(cvt);
        if (type == null) {
            throw Ast.err(
                    AstException.AST__TIMIN,
                    "astAddTime(" + astClass() + "): Invalid TimeMap time coordinate conversion type \"" + cvt + "\".");
        }
        int narg = args == null ? 0 : args.length;
        if (narg != type.nargs) {
            throw Ast.err(
                    AstException.AST__TIMIN,
                    "AddTimeCvt(" + astClass() + "): Invalid no. of arguments for time coordinate conversion type "
                            + type.cCode + " - " + narg + " supplied, " + type.nargs + " required.");
        }
        double[] stored = new double[type.szargs()];
        for (int i = 0; i < stored.length; i++) {
            stored[i] = i < narg ? args[i] : AST__BAD;
        }
        addArgs(type, stored);
        steps.add(new Step(type, stored));
    }

    /** C's {@code AddArgs}: works out the values a conversion stores after its arguments. */
    private static void addArgs(Cvt cvt, double[] a) {
        // MODERN-JAVA(21): switch with arrow labels
        switch (cvt) {
            case MJDTOMJD:
                a[2] = a[0] - a[1];
                break;
            case MJDTOJD:
                a[2] = a[0] - a[1] + 2400000.5;
                break;
            case JDTOMJD:
                a[2] = a[0] - a[1] - 2400000.5;
                break;
            case MJDTOBEP:
                a[2] = Pal.epb(a[0]) - Pal.epb(0.0) - a[1];
                a[3] = Pal.epb2d(a[1]) - Pal.epb2d(0.0) - a[0];
                break;
            case BEPTOMJD:
                a[2] = Pal.epb2d(a[0]) - Pal.epb2d(0.0) - a[1];
                a[3] = Pal.epb(a[1]) - Pal.epb(0.0) - a[0];
                break;
            case MJDTOJEP:
                a[2] = Pal.epj(a[0]) - Pal.epj(0.0) - a[1];
                a[3] = Pal.epj2d(a[1]) - Pal.epj2d(0.0) - a[0];
                break;
            case JEPTOMJD:
                a[2] = Pal.epj2d(a[0]) - Pal.epj2d(0.0) - a[1];
                a[3] = Pal.epj(a[1]) - Pal.epj(0.0) - a[0];
                break;
            case TTTOTDB:
            case TDBTOTT: {
                // The observer's distances from the Earth's spin axis and north of its equatorial
                // plane, in km.
                double[] rz = Pal.geoc(a[2], a[3]);
                a[5] = 0.001 * rz[0] * AU;
                a[6] = 0.001 * rz[1] * AU;
                break;
            }
            case TDBTOTCB:
            case TCBTOTDB:
                a[1] = LB * (a[0] - (TTOFF / SPD) - 43144.0) + P0 / SPD;
                break;
            case TTTOTCG:
            case TCGTOTT:
                a[1] = LG * (a[0] - (TTOFF / SPD) - 43144.0);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * Applies the conversions as C's {@code Transform} (timemap.c) does: the output starts as a
     * copy of the input, a NaN becoming {@link #AST__BAD}, and each conversion in turn (the inverse
     * of each in reverse order for the inverse transformation) updates every good value in place.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean fwd = effectiveForward(forward);
        double[] time = out[0];
        double[] src = in[0];
        for (int i = 0; i < npoint; i++) {
            time[i] = isBad(src[i]) ? AST__BAD : src[i];
        }
        int ncvt = steps.size();
        for (int k = 0; k < ncvt; k++) {
            Step s = steps.get(fwd ? k : ncvt - 1 - k);
            for (int i = 0; i < npoint; i++) {
                if (!isBad(time[i])) {
                    time[i] = apply(s, fwd, time[i]);
                }
            }
        }
    }

    /** One value through one conversion, an arm of the conversion switch of C's {@code Transform}. */
    private static double apply(Step s, boolean fwd, double time) {
        double[] args = s.args;
        // MODERN-JAVA(21): switch expression over the conversions
        switch (s.cvt) {
            case MJDTOMJD:
            case MJDTOJD:
            case JDTOMJD:
                return fwd ? time + args[2] : time - args[2];
            case MJDTOBEP:
                return fwd ? Pal.epb(time) + args[2] : Pal.epb2d(time) + args[3];
            case BEPTOMJD:
                return fwd ? Pal.epb2d(time) + args[2] : Pal.epb(time) + args[3];
            case MJDTOJEP:
                return fwd ? Pal.epj(time) + args[2] : Pal.epj2d(time) + args[3];
            case JEPTOMJD:
                return fwd ? Pal.epj2d(time) + args[2] : Pal.epj(time) + args[3];
            case TAITOUTC:
                return fwd ? taiToUtc(time, args) : utcToTai(time, args);
            case UTCTOTAI:
                return fwd ? utcToTai(time, args) : taiToUtc(time, args);
            case TAITOTT:
                return fwd ? time + (TTOFF / SPD) : time - (TTOFF / SPD);
            case TTTOTAI:
                return fwd ? time - (TTOFF / SPD) : time + (TTOFF / SPD);
            case TTTOTDB:
                return fwd ? ttToTdb(time, args) : tdbToTt(time, args);
            case TDBTOTT:
                return fwd ? tdbToTt(time, args) : ttToTdb(time, args);
            case TTTOTCG:
                // C adds the correction as one term ("+="), so the sum is rounded once.
                return fwd ? time + (time * LG + args[1]) : (time - args[1]) / (1.0 + LG);
            case TCGTOTT:
                return fwd ? (time - args[1]) / (1.0 + LG) : time + (time * LG + args[1]);
            case TDBTOTCB:
                return fwd ? time + (time * LB + args[1]) : (time - args[1]) / (1.0 + LB);
            case TCBTOTDB:
                return fwd ? (time - args[1]) / (1.0 + LB) : time + (time * LB + args[1]);
            case UTTOGMST:
                return gmsta(time, args[0], fwd);
            case GMSTTOUT:
                return gmsta(time, args[0], !fwd);
            case GMSTTOLMST:
                return fwd ? time - args[1] / D2PI : time + args[1] / D2PI;
            case LMSTTOGMST:
                return fwd ? time + args[1] / D2PI : time - args[1] / D2PI;
            case UTTOUTC:
                return fwd ? time - args[0] / 86400.0 : time + args[0] / 86400.0;
            case UTCTOUT:
                return fwd ? time + args[0] / 86400.0 : time - args[0] / 86400.0;
            case LTTOUTC:
                return fwd ? time - args[0] / 24.0 : time + args[0] / 24.0;
            case UTCTOLT:
                return fwd ? time + args[0] / 24.0 : time - args[0] / 24.0;
            case LMSTTOLAST:
                return fwd ? time + equationOfEquinoxes(time, args) : time - equationOfEquinoxes(time, args);
            case LASTTOLMST:
                return fwd ? time - equationOfEquinoxes(time, args) : time + equationOfEquinoxes(time, args);
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        "astTransform(TimeMap): Corrupt TimeMap contains invalid time coordinate conversion " + s.cvt
                                + " (internal AST programming error).");
        }
    }

    /** TAI to UTC, using DTAI if it is given and the table of leap seconds otherwise. */
    private static double taiToUtc(double time, double[] args) {
        return time + (isBad(args[1]) ? astDat(time + args[0], false) : -args[1]) / SPD;
    }

    /** UTC to TAI, using DTAI if it is given and the table of leap seconds otherwise. */
    private static double utcToTai(double time, double[] args) {
        return time + (isBad(args[1]) ? astDat(time + args[0], true) : args[1]) / SPD;
    }

    /**
     * UTC approximated from a TT or TDB value, for estimating TDB-TT: UTC is taken to be a good
     * approximation to UT1, and TT to TDB, but leap seconds are allowed for (timemap.c {@code
     * Transform}).
     */
    private static double approxUtc(double tt, double[] args) {
        double tai = tt - (TTOFF / SPD);
        return tai + (isBad(args[4]) ? astDat(tai, false) : -args[4]) / SPD;
    }

    private static double ttToTdb(double time, double[] args) {
        double tt = time + args[0];
        return time + rcc(tt, approxUtc(tt, args), args[1], args[5], args[6]) / SPD;
    }

    private static double tdbToTt(double time, double[] args) {
        double tdb = time + args[0];
        return time - rcc(tdb, approxUtc(tdb, args), args[1], args[5], args[6]) / SPD;
    }

    /**
     * The equation of the equinoxes as a fraction of a day, at the TDB of an LMST value (timemap.c
     * {@code Transform}): the LMST is converted to UT1, which is taken to equal UTC, and 32 seconds
     * (TAI-UTC since 1999) and TT-TAI are added to give TT, which is taken to equal TDB. The error in
     * TDB, a few minutes at most, is insignificant here.
     */
    private static double equationOfEquinoxes(double time, double[] args) {
        double gmstx = time + args[1] / D2PI;
        double tdb = gmsta(gmstx, args[0], false) + args[0] + (32 + TTOFF) / SPD;
        return Pal.eqeqx(tdb) / D2PI;
    }

    /**
     * The rate of change of the output with respect to the input, as C's {@code Rate} (timemap.c)
     * finds it: the product of the slopes of the conversions, or the reciprocal for an inverted
     * TimeMap. The epoch conversions are linear. Heuristic, as in C: every other time-scale
     * conversion is taken to have a slope of one, which it has only nearly, except the GMST
     * conversions, which are not linear, and for which the rate is estimated numerically.
     */
    @Override
    double rateAt(double[] at, int ax1, int ax2) {
        double result = 1.0;
        for (Step s : steps) {
            // MODERN-JAVA(21): switch with arrow labels
            switch (s.cvt) {
                case MJDTOBEP:
                    result *= 1.0 / 365.242198781;
                    break;
                case BEPTOMJD:
                    result *= 365.242198781;
                    break;
                case MJDTOJEP:
                    result *= 1.0 / 365.25;
                    break;
                case JEPTOMJD:
                    result *= 365.25;
                    break;
                case UTTOGMST:
                case GMSTTOUT:
                    return super.rateAt(at, ax1, ax2);
                default:
                    break;
            }
        }
        return getInvert() ? 1.0 / result : result;
    }

    // ------------------------------------------------------------------
    // TAI-UTC
    // ------------------------------------------------------------------

    private static double[] utcStarts() {
        double[] t = new double[Dat.LEAP_SECONDS.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = Dat.LEAP_SECONDS.get(i)[0];
        }
        return t;
    }

    private static double[] taiStarts() {
        double[] t = new double[Dat.LEAP_SECONDS.size()];
        for (int i = 0; i < t.length; i++) {
            double start = Dat.LEAP_SECONDS.get(i)[0];
            double delat = Dat.LEAP_SECONDS.get(i)[1];
            if (i < Dat.NERA1) {
                double[] drift = Dat.DRIFT.get(i);
                t[i] = start + (delat + (start - drift[0]) * drift[1]) / SPD;
            } else {
                t[i] = start + delat / SPD;
            }
        }
        return t;
    }

    /**
     * C's {@code astDat}: TAI-UTC in seconds given UTC as an MJD ({@code forward} true), or UTC-TAI
     * in seconds given TAI as an MJD ({@code forward} false); {@link #AST__BAD} for a bad date.
     *
     * <p>C keeps its own table of the leap seconds ERFA's {@code eraDat} lists, as a chain of
     * comparisons; this reads ERFA's table, and every date from 1960 gives the value of that chain,
     * bit for bit. A date before 1960, or NaN, takes the first entry's segment, extended backwards,
     * as the chain's last branch does.
     */
    static double astDat(double mjd, boolean forward) {
        if (isBad(mjd)) {
            return AST__BAD;
        }
        double[] starts = forward ? UTC_STARTS : TAI_STARTS;
        // The last entry whose start is at or before mjd.
        int i = 0;
        for (int j = starts.length - 1; j >= 0; j--) {
            if (mjd >= starts[j]) {
                i = j;
                break;
            }
        }
        double delat = Dat.LEAP_SECONDS.get(i)[1];
        if (i < Dat.NERA1) {
            double[] drift = Dat.DRIFT.get(i);
            double reference = drift[0];
            double rate = drift[1];
            if (forward) {
                return delat + (mjd - reference) * rate;
            }
            return -(delat + (mjd - reference) * rate) / (1.0 + rate / SPD);
        }
        return forward ? delat : -delat;
    }

    // ------------------------------------------------------------------
    // GMST
    // ------------------------------------------------------------------

    /**
     * C's {@code Gmsta}: UT1 to GMST ({@code forward} true) or GMST to UT1, both as MJD offsets
     * from {@code off} (in days) rather than angles. GMST to UT1 inverts the series by
     * Newton-Raphson iteration, from a guess given by the inverse of a linear approximation, until
     * the guess stops changing or after 10 iterations.
     */
    private static double gmsta(double in, double off, boolean forward) {
        if (isBad(in) || isBad(off)) {
            return AST__BAD;
        }
        if (forward) {
            // Julian centuries since J2000.
            double t = (off + in - 51544.5) / 36525.0;
            return in + (24110.54841 + (8640184.812866 + (0.093104 - 6.2e-6 * t) * t) * t) / 86400.0;
        }
        double result = 0.996997348638869 * in + 154.49194372222 - 0.00300265136113098 * off;
        double utl = AST__BAD;
        int nit = 0;
        while (result != utl && nit++ < 10) {
            double t = (off + result - 51544.5) / 36525.0;
            double g = result + (24110.54841 + (8640184.812866 + (0.093104 - 6.2e-6 * t) * t) * t) / 86400.0;
            double dgdu = 1.0 + (8640184.812866 + (0.186208 - 12.4e-6 * t) * t) / (36525.0 * 86400.0);
            utl = result;
            result = result - (g - in) / dgdu;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // TDB-TT
    // ------------------------------------------------------------------

    /**
     * C's {@code Rcc}: the relativistic clock correction TDB-TT, in seconds, by the model of
     * Fairhead and Bretagnon (1990) with the topocentric terms of Moyer (1981) and Murray (1983)
     * and adjustments to use JPL planetary masses instead of IAU.
     *
     * @param tdb TDB as an MJD (TT will do)
     * @param ut1 UT1 as an MJD, of which only the fraction of a day matters
     * @param wl the observer's longitude, in radians, positive westwards
     * @param u the observer's distance from the Earth's spin axis, in km
     * @param v the observer's distance north of the Earth's equatorial plane, in km
     */
    private static double rcc(double tdb, double ut1, double wl, double u, double v) {
        double[][] fairhd = TimeMapFairhd.FAIRHD;

        // Time since J2000.0 in Julian millennia.
        double t = (tdb - 51544.5) / 365250;

        // Topocentric terms: local solar time in radians, then the fundamental arguments (Simon et
        // al. 1994), with the time in millennia combined with the degree/arcsecond factor.
        double tsol = (ut1 % 1.0) * D2PI - wl;
        double w = t / 3600.0;
        double elsun = ((280.46645683 + 1296027711.03429 * w) % 360.0) * D2R;
        double emsun = ((357.52910918 + 1295965810.481 * w) % 360.0) * D2R;
        double d = ((297.85019547 + 16029616012.090 * w) % 360.0) * D2R;
        double elj = ((34.35151874 + 109306899.89453 * w) % 360.0) * D2R;
        double els = ((50.07744430 + 44046398.47038 * w) % 360.0) * D2R;
        double wt = 0.00029e-10 * u * Math.sin(tsol + elsun - els)
                + 0.00100e-10 * u * Math.sin(tsol - 2 * emsun)
                + 0.00133e-10 * u * Math.sin(tsol - d)
                + 0.00133e-10 * u * Math.sin(tsol + elsun - elj)
                - 0.00229e-10 * u * Math.sin(tsol + 2 * elsun + emsun)
                - 0.0220e-10 * v * Math.cos(elsun + emsun)
                + 0.05312e-10 * u * Math.sin(tsol - emsun)
                - 0.13677e-10 * u * Math.sin(tsol + 2 * elsun)
                - 1.3184e-10 * v * Math.cos(elsun)
                + 3.17679e-10 * u * Math.sin(tsol);

        // The Fairhead model, summed from the smallest terms for each power of t.
        double w0 = series(fairhd, 473, 0, t);
        double w1 = series(fairhd, 678, 474, t);
        double w2 = series(fairhd, 763, 679, t);
        double w3 = series(fairhd, 783, 764, t);
        double w4 = series(fairhd, 786, 784, t);
        double wf = t * (t * (t * (t * w4 + w3) + w2) + w1) + w0;

        // Adjustments to use JPL planetary masses instead of IAU.
        double wj = 0.00065e-6 * Math.sin(6069.776754 * t + 4.021194)
                + 0.00033e-6 * Math.sin(213.299095 * t + 5.543132)
                + (-0.00196e-6 * Math.sin(6208.294251 * t + 5.696701))
                + (-0.00173e-6 * Math.sin(74.781599 * t + 2.435900))
                + 0.03638e-6 * t * t;

        return wt + wf + wj;
    }

    /** The sum of the terms {@code from} down to {@code to} of the Fairhead series, at time {@code t}. */
    private static double series(double[][] fairhd, int from, int to, double t) {
        double sum = 0;
        for (int i = from; i >= to; i--) {
            sum = sum + fairhd[i][0] * Math.sin(fairhd[i][1] * t + fairhd[i][2]);
        }
        return sum;
    }
}
