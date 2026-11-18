package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A sequence of conversions between standard spectral coordinate systems.
 *
 * <p>A SpecMap is a specialised form of Mapping which can be used to represent a sequence of
 * conversions between standard spectral coordinate systems: frequency, wavelength and various forms
 * of velocity, and different standards of rest. When first created it performs a unit (null)
 * Mapping. {@link #specAdd} then appends coordinate conversion steps, so that multi-step
 * conversions between a variety of spectral coordinate systems can be assembled out of a set of
 * building blocks.
 *
 * <p>The conversions between standards of rest need the source position as an RA and Dec. It can
 * be given as arguments of those conversions, in which case the SpecMap is 1-dimensional,
 * transforming only the spectral axis values, and always uses the same source position. That may
 * not describe a 3-D spectral cube accurately, since changes of spatial position can change the
 * Doppler shift between standards of rest significantly. For that case a 3-dimensional SpecMap
 * takes the source RA and Dec from its second and third coordinates, which it copies unchanged from
 * input to output, and modifies the first (the spectral axis) appropriately.
 *
 * <p>For details of the individual coordinate conversions available, see {@link #specAdd}.
 *
 * <p>Ported from Rust {@code mappings/specmap.rs} and C {@code specmap.c}.
 */
public class SpecMap extends Mapping {

    /** The speed of light in metres per second, C's {@code AST__C}. */
    static final double AST__C = 2.99792458E8;

    /** Planck's constant in Joule seconds, C's {@code AST__H}. */
    static final double AST__H = 6.6260755E-34;

    /** The astronomical unit in metres that specmap.c converts au/s to m/s with. */
    private static final double AU = 149.597870E9;

    /**
     * The conversions a SpecMap can apply: C's conversion codes with their values. Each has its
     * string (its name), the comment Dump writes for it, its number of arguments, and the
     * descriptions of the values it stores, which are its arguments followed, for a change of rest
     * frame, by the frequency correction factor worked out from them (specmap.c {@code
     * CvtString}).
     */
    enum Cvt {
        FRTOVL(1, "Convert frequency to rel. velocity", 1, "Rest frequency (Hz)"),
        VLTOFR(2, "Convert rel. velocity to frequency", 1, "Rest frequency (Hz)"),
        ENTOFR(3, "Convert energy to frequency", 0),
        FRTOEN(4, "Convert frequency to energy", 0),
        WNTOFR(5, "Convert wave number to frequency", 0),
        FRTOWN(6, "Convert frequency to wave number", 0),
        WVTOFR(7, "Convert wavelength (vacuum) to frequency", 0),
        FRTOWV(8, "Convert frequency to wavelength (vacuum)", 0),
        AWTOFR(9, "Convert wavelength (air) to frequency", 0),
        FRTOAW(10, "Convert frequency to wavelength (air)", 0),
        VRTOVL(11, "Convert radio to rel. velocity", 0),
        VLTOVR(12, "Convert relativistic to radio velocity", 0),
        VOTOVL(13, "Convert optical to rel. velocity", 0),
        VLTOVO(14, "Convert relativistic to optical velocity", 0),
        ZOTOVL(15, "Convert redshift to rel. velocity", 0),
        VLTOZO(16, "Convert rel. velocity to redshift", 0),
        BTTOVL(17, "Convert beta factor to rel. velocity", 0),
        VLTOBT(18, "Convert rel. velocity to beta factor", 0),
        USF2HL(19, "Convert from user-defined to heliocentric frequency", 3, Desc.USER),
        HLF2US(20, "Convert from heliocentric to user-defined frequency", 3, Desc.USER),
        TPF2HL(21, "Convert from Topocentric to heliocentric frequency", 6, Desc.TOPO),
        HLF2TP(22, "Convert from Heliocentric to topocentric frequency", 6, Desc.TOPO),
        GEF2HL(23, "Convert from Geocentric to heliocentric frequency", 3, Desc.EPOCH),
        HLF2GE(24, "Convert from Heliocentric to geocentric frequency", 3, Desc.EPOCH),
        BYF2HL(25, "Convert from Barycentric to heliocentric frequency", 3, Desc.EPOCH),
        HLF2BY(26, "Convert from Heliocentric to barycentric frequency", 3, Desc.EPOCH),
        LKF2HL(27, "Convert from LSRK to heliocentric frequency", 2, Desc.POSITION),
        HLF2LK(28, "Convert from Heliocentric to LSRK frequency", 2, Desc.POSITION),
        LDF2HL(29, "Convert from LSRD to heliocentric frequency", 2, Desc.POSITION),
        HLF2LD(30, "Convert from Heliocentric to LSRD frequency", 2, Desc.POSITION),
        LGF2HL(31, "Convert from Local group to heliocentric frequency", 2, Desc.POSITION),
        HLF2LG(32, "Convert from Heliocentric to local group frequency", 2, Desc.POSITION),
        GLF2HL(33, "Convert from Galactic to heliocentric frequency", 2, Desc.POSITION),
        HLF2GL(34, "Convert from Heliocentric to galactic frequency", 2, Desc.POSITION);

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

        /** Whether the conversion is a change of rest frame, which {@code FrameChange} applies. */
        boolean isFrameChange() {
            return cCode >= USF2HL.cCode;
        }

        /**
         * The conversion that undoes this one, as the {@code SWAP_CODES} and {@code SWAP_CODES2}
         * macros of specmap.c {@code MapMerge} exchange them: each odd code with the even code
         * after it.
         */
        Cvt inverse() {
            int code = cCode % 2 == 1 ? cCode + 1 : cCode - 1;
            for (Cvt c : values()) {
                if (c.cCode == code) {
                    return c;
                }
            }
            throw Ast.err(
                    AstException.AST__INTER,
                    "SpecMap: conversion " + this + " has no inverse (internal AST programming error).");
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

    /** The argument descriptions the changes of rest frame share (specmap.c {@code CvtString}). */
    private static final class Desc {
        static final String RA = "RA of source (FK5 J2000, radians)";
        static final String DEC = "DEC of source (FK5 J2000, radians)";
        static final String EPOCH_MJD = "UT1 epoch of observaton (Modified Julian Date)";
        static final String FCORR = "Frequency correction factor";

        static final String[] USER = {"Velocity offset (m/s)", RA, DEC, FCORR};
        static final String[] TOPO = {
            "Longitude (positive eastwards, radians)",
            "Latitude (geodetic, radians)",
            "Altitude (geodetic, metres)",
            EPOCH_MJD,
            RA,
            DEC,
            FCORR
        };
        static final String[] EPOCH = {EPOCH_MJD, RA, DEC, FCORR};
        static final String[] POSITION = {RA, DEC, FCORR};

        private Desc() {}
    }

    /** One conversion of the sequence: C's {@code cvttype} and {@code cvtargs}. */
    private static final class Step {
        final Cvt cvt;

        /**
         * The arguments followed, for a change of rest frame, by the frequency correction factor,
         * {@link #AST__BAD} until a 1-dimensional transformation works it out; {@code szargs} in
         * all.
         */
        final double[] args;

        Step(Cvt cvt, double[] args) {
            this.cvt = cvt;
            this.args = args;
        }
    }

    /**
     * The parameters that define a reference frame, with intermediate results cached while one
     * conversion is applied (specmap.c {@code FrameDef}). A cached value is {@link #AST__BAD}, or
     * null, until it is worked out.
     */
    private static final class FrameDef {
        /** The observer's geodetic altitude (m). */
        double obsalt = AST__BAD;

        /** The observer's geodetic latitude (radians). */
        double obslat = AST__BAD;

        /** The observer's longitude (radians, positive east). */
        double obslon = AST__BAD;

        /** The epoch of observation (UT1 MJD). */
        double epoch = AST__BAD;

        /** The Dec of the reference point (FK5 J2000). */
        double refdec = AST__BAD;

        /** The RA of the reference point (FK5 J2000). */
        double refra = AST__BAD;

        /** The heliocentric velocity of the user-defined system (m/s). */
        double veluser = AST__BAD;

        /** The local apparent sidereal time. */
        double last = AST__BAD;

        /** The mean to apparent parameters. */
        double[] amprms;

        /** The user-defined velocity as an FK5 J2000 vector. */
        double[] vuser;

        /** The Earth-Sun velocity. */
        double[] dvh;

        /** The barycentre-Sun velocity. */
        double[] dvb;
    }

    /** The conversions, in the order they were added. */
    private List<Step> steps = new ArrayList<>();

    /**
     * Creates a SpecMap with no conversions, which performs a unit Mapping.
     *
     * <p>The nature and units of the values supplied to the SpecMap's first input (its spectral
     * input) must suit the first conversion step it applies, and those it gives are set by the
     * last: if the first step is "FRTOVL" the input is a frequency in Hz, and if the last is
     * "VLTOVO" the output is an optical velocity in metres per second. See {@link #specAdd} for
     * the units each conversion expects and gives.
     *
     * @param nin the number of inputs to the Mapping, which is also the number of outputs: 1, or 3
     *     for a SpecMap whose second and third axes give the RA and Dec (FK5 J2000) of the source.
     *     In either case the first input and output are the spectral axis. In a 3-dimensional
     *     SpecMap the position replaces the RA and DEC arguments of the conversions between
     *     standards of rest listed for {@link #specAdd}
     * @param flags reserved for future use; should be zero, and has no effect
     * @throws AstException with status {@code AST__BADNI} if {@code nin} is neither 1 nor 3
     */
    public SpecMap(int nin, int flags) {
        super(checkNin(nin), nin, true, true);
    }

    /** C's check in {@code astInitSpecMap}: {@code nin} must be 1 or 3. */
    private static int checkNin(int nin) {
        if (nin != 1 && nin != 3) {
            // C's message says "1 or 2"; the check is for 1 or 3.
            throw Ast.err(
                    AstException.AST__BADNI,
                    "astInitSpecMap(SpecMap): Supplied number of SpecMap axes (" + nin
                            + ") is illegal; it should be 1 or 2. ");
        }
        return nin;
    }

    /**
     * Loads a SpecMap from its description, as C's {@code astLoadSpecMap} does: the number of
     * conversions, then each conversion's string and every value stored for it, the frequency
     * correction factor included, as written, a missing value being {@link #AST__BAD}.
     *
     * @throws AstException with status {@code AST__BADIN} if a conversion's string is missing or
     *     names no conversion
     */
    SpecMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("SpecMap");
        int ncvt = Math.max(0, part.readInt("Nspec", 0));
        for (int icvt = 0; icvt < ncvt; icvt++) {
            String key = "Spec" + (icvt + 1);
            String sval = part.readString(key, null);
            if (sval == null) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astRead(Channel): A spectral coordinate conversion type is missing from the input SpecMap"
                                + " data.");
            }
            Cvt cvt = Cvt.fromCode(sval);
            if (cvt == null) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astRead(Channel): Invalid spectral conversion type \"" + sval + "\" in SpecMap data.");
            }
            double[] args = new double[cvt.szargs()];
            for (int iarg = 0; iarg < args.length; iarg++) {
                args[iarg] = part.readDouble(key + argLetter(iarg), AST__BAD);
            }
            steps.add(new Step(cvt, args));
        }
    }

    /** The letter that follows a conversion's key for argument {@code i}, C's {@code ALPHABET}. */
    private static char argLetter(int i) {
        return (char) ('a' + i);
    }

    /** Completes a copy as C's {@code Copy} (specmap.c) does, with its own conversions. */
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
        return "Conversion between spectral coordinate systems";
    }

    /**
     * Writes the number of conversions, set when there are any, then each conversion's string and
     * every stored value that is not bad, the frequency correction factor included, as C's {@code
     * Dump} (specmap.c) does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        int ncvt = steps.size();
        node.writeInt("Nspec", ncvt != 0, false, ncvt, "Number of conversion steps");
        for (int icvt = 0; icvt < ncvt; icvt++) {
            Step s = steps.get(icvt);
            String key = "Spec" + (icvt + 1);
            node.writeString(key, true, true, s.cvt.name(), s.cvt.comment);
            for (int iarg = 0; iarg < s.cvt.szargs(); iarg++) {
                if (s.args[iarg] != AST__BAD) {
                    node.writeDouble(key + argLetter(iarg), true, true, s.args[iarg], s.cvt.argdesc(iarg));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Adding conversions
    // ------------------------------------------------------------------

    /**
     * Adds one of the standard spectral coordinate system conversions listed below to an existing
     * SpecMap.
     *
     * <p>When a SpecMap is first created, it simply performs a unit (null) Mapping. By using this
     * method repeatedly, one or more coordinate conversion steps may then be added, which the
     * SpecMap will perform in sequence. This allows multi-step conversions between a variety of
     * spectral coordinate systems to be assembled out of the building blocks provided by this
     * class.
     *
     * <p>Normally, if a SpecMap's Invert attribute is zero (the default), then its forward
     * transformation is performed by carrying out each of the individual coordinate conversions
     * specified by this method in the order given (the most recently added conversion applied
     * last). This order is reversed if the SpecMap's Invert attribute is non-zero (or if the
     * inverse transformation is requested by any other means) and each individual coordinate
     * conversion is also replaced by its own inverse. This process inverts the overall effect of
     * the SpecMap. In this case, the first conversion to be applied would be the inverse of the one
     * most recently added.
     *
     * <p>When assembling a multi-stage conversion, it can sometimes be difficult to determine the
     * most economical conversion path. For example, when converting between reference frames,
     * converting first to the heliographic reference frame as an intermediate stage is often
     * sensible in formulating the problem, but may introduce unnecessary extra conversion steps. A
     * solution to this is to include all the steps which are (logically) necessary, but then to
     * simplify the resulting SpecMap. The simplification process will eliminate any steps which
     * turn out not to be needed. This method does not check that the sequence of coordinate
     * conversions added to a SpecMap is physically meaningful.
     *
     * <p>The conversions (case is not significant), with the arguments each takes in the order they
     * are given, are:
     *
     * <ul>
     *   <li>{@code "FRTOVL"} (RF): convert frequency to relativistic velocity;
     *   <li>{@code "VLTOFR"} (RF): convert relativistic velocity to frequency;
     *   <li>{@code "ENTOFR"}: convert energy to frequency;
     *   <li>{@code "FRTOEN"}: convert frequency to energy;
     *   <li>{@code "WNTOFR"}: convert wave number to frequency;
     *   <li>{@code "FRTOWN"}: convert frequency to wave number;
     *   <li>{@code "WVTOFR"}: convert wavelength (vacuum) to frequency;
     *   <li>{@code "FRTOWV"}: convert frequency to wavelength (vacuum);
     *   <li>{@code "AWTOFR"}: convert wavelength (air) to frequency;
     *   <li>{@code "FRTOAW"}: convert frequency to wavelength (air);
     *   <li>{@code "VRTOVL"}: convert radio to relativistic velocity;
     *   <li>{@code "VLTOVR"}: convert relativistic to radio velocity;
     *   <li>{@code "VOTOVL"}: convert optical to relativistic velocity;
     *   <li>{@code "VLTOVO"}: convert relativistic to optical velocity;
     *   <li>{@code "ZOTOVL"}: convert redshift to relativistic velocity;
     *   <li>{@code "VLTOZO"}: convert relativistic velocity to redshift;
     *   <li>{@code "BTTOVL"}: convert beta factor to relativistic velocity;
     *   <li>{@code "VLTOBT"}: convert relativistic velocity to beta factor;
     *   <li>{@code "USF2HL"} (VOFF, RA, DEC): convert frequency from a user-defined reference frame
     *       to heliocentric;
     *   <li>{@code "HLF2US"} (VOFF, RA, DEC): convert frequency from heliocentric reference frame
     *       to user-defined;
     *   <li>{@code "TPF2HL"} (OBSLON, OBSLAT, OBSALT, EPOCH, RA, DEC): convert frequency from
     *       topocentric reference frame to heliocentric;
     *   <li>{@code "HLF2TP"} (OBSLON, OBSLAT, OBSALT, EPOCH, RA, DEC): convert frequency from
     *       heliocentric reference frame to topocentric;
     *   <li>{@code "GEF2HL"} (EPOCH, RA, DEC): convert frequency from geocentric reference frame to
     *       heliocentric;
     *   <li>{@code "HLF2GE"} (EPOCH, RA, DEC): convert frequency from heliocentric reference frame
     *       to geocentric;
     *   <li>{@code "BYF2HL"} (EPOCH, RA, DEC): convert frequency from barycentric reference frame
     *       to heliocentric;
     *   <li>{@code "HLF2BY"} (EPOCH, RA, DEC): convert frequency from heliocentric reference frame
     *       to barycentric;
     *   <li>{@code "LKF2HL"} (RA, DEC): convert frequency from kinematic LSR reference frame to
     *       heliocentric;
     *   <li>{@code "HLF2LK"} (RA, DEC): convert frequency from heliocentric reference frame to
     *       kinematic LSR;
     *   <li>{@code "LDF2HL"} (RA, DEC): convert frequency from dynamical LSR reference frame to
     *       heliocentric;
     *   <li>{@code "HLF2LD"} (RA, DEC): convert frequency from heliocentric reference frame to
     *       dynamical LSR;
     *   <li>{@code "LGF2HL"} (RA, DEC): convert frequency from local group reference frame to
     *       heliocentric;
     *   <li>{@code "HLF2LG"} (RA, DEC): convert frequency from heliocentric reference frame to
     *       local group;
     *   <li>{@code "GLF2HL"} (RA, DEC): convert frequency from galactic reference frame to
     *       heliocentric;
     *   <li>{@code "HLF2GL"} (RA, DEC): convert frequency from heliocentric reference frame to
     *       galactic.
     * </ul>
     *
     * <p>The units for the values processed by the above conversions are: all velocities, metres
     * per second (positive if the source recedes from the observer); frequency, Hertz; all
     * wavelengths, metres; energy, Joules; wave number, cycles per metre.
     *
     * <p>The arguments are: RF, the rest frequency (Hz); OBSALT, the geodetic altitude of the
     * observer (IAU 1975, metres); OBSLAT, the geodetic latitude of the observer (IAU 1975,
     * radians); OBSLON, the longitude of the observer (radians, positive eastwards); EPOCH, the
     * epoch of observation (UT1 expressed as a Modified Julian Date); RA, the Right Ascension of the
     * source (radians, FK5 J2000); DEC, the Declination of the source (radians, FK5 J2000); VOFF,
     * the velocity of the user-defined reference frame, towards the position given by RA and DEC,
     * measured in the heliocentric reference frame.
     *
     * <p>If the SpecMap is 3-dimensional, source positions are provided by the values supplied to
     * inputs 2 and 3 of the SpecMap (which are simply copied to outputs 2 and 3). Usable values are
     * still required for the RA and DEC arguments in order to define the "user-defined" reference
     * frame used by USF2HL and HLF2US. However, {@link #AST__BAD} can be supplied for RA and DEC if
     * the user-defined reference frame is not required.
     *
     * @param cvt the conversion, as one of the strings above (case is not significant)
     * @param args its arguments, in the order given above; may be null if it takes none
     * @throws AstException with status {@code AST__SPCIN} if {@code cvt} names no conversion, or
     *     {@code AST__TIMIN} if {@code args} does not hold exactly the conversion's number of
     *     arguments
     */
    public void specAdd(String cvt, double[] args) {
        Cvt type = Cvt.fromCode(cvt);
        if (type == null) {
            throw Ast.err(
                    AstException.AST__SPCIN,
                    "astAddSpec(" + astClass() + "): Invalid SpecMap spectral coordinate conversion type \"" + cvt
                            + "\".");
        }
        addSpecCvt(type, args == null ? 0 : args.length, args);
    }

    /**
     * C's {@code AddSpecCvt}: appends conversion {@code type} with its {@code narg} arguments,
     * followed by {@link #AST__BAD} in each slot for a value worked out from them later.
     *
     * @throws AstException with status {@code AST__TIMIN} if {@code narg} is not the conversion's
     *     number of arguments
     */
    private void addSpecCvt(Cvt type, int narg, double[] args) {
        if (narg != type.nargs) {
            throw Ast.err(
                    AstException.AST__TIMIN,
                    "AddSpecCvt(" + astClass() + "): Invalid no. of arguments for spectral coordinate conversion type "
                            + type.cCode + " - " + narg + " supplied, " + type.nargs + " required.");
        }
        double[] stored = new double[type.szargs()];
        for (int i = 0; i < stored.length; i++) {
            stored[i] = i < type.nargs ? args[i] : AST__BAD;
        }
        steps.add(new Step(type, stored));
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * specmap.c {@code Equal}: {@code that} is a SpecMap with the same numbers of inputs and
     * outputs and the same Invert attribute, whose conversions are those of this SpecMap, in the
     * same order, with the same arguments to within {@code astEQUAL}: the first {@code nargs} of
     * each, not the frequency correction factor. SpecMaps with different Invert attributes are never equal.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof SpecMap)) {
            return false;
        }
        SpecMap other = (SpecMap) that;
        if (other.getNin() != getNin() || other.getNout() != getNout() || getInvert() != other.getInvert()) {
            return false;
        }
        if (steps.size() != other.steps.size()) {
            return false;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step s1 = steps.get(i);
            Step s2 = other.steps.get(i);
            if (s1.cvt != s2.cvt) {
                return false;
            }
            for (int j = 0; j < s1.cvt.nargs; j++) {
                if (!MappingHelpers.astEqual(s1.args[j], s2.args[j])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * specmap.c {@code MapMerge}. In series, or alone in the list, the SpecMap and the SpecMaps
     * with the same number of axes adjacent to it are condensed into one Mapping: their conversions
     * are gathered in the order they are applied, each SpecMap's in reverse and each conversion
     * exchanged for its inverse where the list applies the SpecMap inverted (a change of rest
     * frame having its frequency correction factor reciprocated), and the gathered list is then
     * simplified, pass after pass until a pass removes nothing, by removing adjacent pairs of
     * mutually inverse conversions whose arguments are equal. If that removed a Mapping or a
     * conversion, or the nominated SpecMap is applied inverted, the run is replaced by a UnitMap,
     * when no conversion is left, or else by a new SpecMap of the conversions left, applied
     * forward, which has none of the frequency correction factors.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        if (!series && list.size() != 1) {
            return -1;
        }
        int nin = list.map(where).getNin();
        int nstep0 = ((SpecMap) list.map(where)).steps.size();
        int imap1 = where;
        while (imap1 - 1 >= 0 && isSpecMap(list.map(imap1 - 1), nin)) {
            nstep0 += ((SpecMap) list.map(imap1 - 1)).steps.size();
            imap1--;
        }
        int imap2 = where;
        while (imap2 + 1 < list.size() && isSpecMap(list.map(imap2 + 1), nin)) {
            nstep0 += ((SpecMap) list.map(imap2 + 1)).steps.size();
            imap2++;
        }

        List<Step> gathered = new ArrayList<>(nstep0);
        for (int imap = imap1; imap <= imap2; imap++) {
            SpecMap specmap = (SpecMap) list.map(imap);
            boolean invert = list.invert(imap);
            int ncvt = specmap.steps.size();
            for (int k = 0; k < ncvt; k++) {
                Step step = specmap.steps.get(invert ? ncvt - 1 - k : k);
                double[] args = step.args.clone();
                Cvt cvt = step.cvt;
                if (invert) {
                    cvt = cvt.inverse();
                    if (cvt.isFrameChange()) {
                        // SWAP_CODES2: the frequency correction factor is reciprocated.
                        int jarg = cvt.nargs;
                        double tmp = args[jarg];
                        args[jarg] = tmp != AST__BAD && tmp != 0.0 ? 1.0 / tmp : AST__BAD;
                    }
                }
                gathered.add(new Step(cvt, args));
            }
        }

        boolean done = false;
        while (!done) {
            int nstep1 = gathered.size();
            List<Step> kept = new ArrayList<>(nstep1);
            int istep = 0;
            while (istep < nstep1) {
                boolean keep = true;
                if (istep < nstep1 - 1 && cancels(gathered.get(istep), gathered.get(istep + 1))) {
                    istep++;
                    keep = false;
                }
                if (keep) {
                    kept.add(gathered.get(istep));
                }
                istep++;
            }
            done = kept.size() >= nstep1;
            gathered = kept;
        }

        int nstep = gathered.size();
        int ngone = imap2 - imap1;
        boolean unit = nstep == 0;
        boolean simpler = ngone != 0 || nstep < nstep0 || unit || list.invert(where);
        if (!simpler) {
            return -1;
        }
        Mapping replacement;
        if (unit) {
            replacement = new UnitMap(list.map(where).getNin());
        } else {
            SpecMap sm = new SpecMap(nin, 0);
            for (Step step : gathered) {
                sm.addSpecCvt(step.cvt, step.cvt.nargs, step.args);
            }
            replacement = sm;
        }
        list.replaceRangeWith(imap1, imap2, replacement);
        return imap1;
    }

    /** Whether {@code map} is a SpecMap with {@code nin} axes, which specmap.c {@code MapMerge} merges. */
    private static boolean isSpecMap(Mapping map, int nin) {
        return map.getClass() == SpecMap.class && map.getNin() == nin;
    }

    /**
     * Whether {@code s1} followed by {@code s2} is one of the pairs specmap.c {@code MapMerge}
     * removes: a conversion and its inverse, in either order, with the arguments the user supplied
     * (none, one, two, three or six of them) equal to within {@code astEQUAL}. A frequency
     * correction factor, which the user does not supply, is not compared.
     */
    private static boolean cancels(Step s1, Step s2) {
        if (s1.cvt.inverse() != s2.cvt) {
            return false;
        }
        for (int i = 0; i < s1.cvt.nargs; i++) {
            if (!MappingHelpers.astEqual(s1.args[i], s2.args[i])) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * Applies the conversions as C's {@code Transform} (specmap.c) does: the output starts as a
     * copy of the input, a value that is not finite becoming {@link #AST__BAD}, and each conversion
     * in turn (the inverse of each in reverse order for the inverse transformation) updates the
     * spectral values in place. A 3-dimensional SpecMap copies its second and third axes, which
     * give each point's source position for the changes of rest frame.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean fwd = effectiveForward(forward);
        boolean map3d = in.length == 3;
        double[] spec = copyAxis(in[0], npoint, out[0]);
        double[] alpha = map3d ? copyAxis(in[1], npoint, out[1]) : null;
        double[] beta = map3d ? copyAxis(in[2], npoint, out[2]) : null;
        int ncvt = steps.size();
        for (int k = 0; k < ncvt; k++) {
            Step s = steps.get(fwd ? k : ncvt - 1 - k);
            if (!frameChange(s, npoint, alpha, beta, spec, fwd)) {
                systemChange(s.cvt, npoint, spec, s.args, fwd);
            }
        }
    }

    /** Copies the first {@code npoint} values of {@code src} to {@code dst}, each bad value as {@link #AST__BAD}. */
    private static double[] copyAxis(double[] src, int npoint, double[] dst) {
        for (int i = 0; i < npoint; i++) {
            dst[i] = isBad(src[i]) ? AST__BAD : src[i];
        }
        return dst;
    }

    /**
     * C's {@code SystemChange}: changes the {@code np} spectral values in {@code values} between two
     * spectral systems with conversion {@code cvt}, or its inverse if {@code forward} is false,
     * which is applied as the forward direction of the inverse conversion.
     */
    private static void systemChange(Cvt cvt, int np, double[] values, double[] args, boolean forward) {
        Cvt c = forward ? cvt : cvt.inverse();
        // MODERN-JAVA(14): switch with arrow labels
        switch (c) {
            case FRTOVL:
                if (args[0] != AST__BAD) {
                    double temp = args[0] * args[0];
                    for (int i = 0; i < np; i++) {
                        double v = values[i];
                        if (v != AST__BAD) {
                            double f2 = v * v;
                            double d = temp + f2;
                            values[i] = d > 0.0 ? finite(AST__C * ((temp - f2) / d)) : AST__BAD;
                        }
                    }
                } else {
                    setBad(values, np);
                }
                break;
            case VLTOFR:
                if (args[0] != AST__BAD) {
                    double temp = args[0];
                    for (int i = 0; i < np; i++) {
                        double v = values[i];
                        if (v != AST__BAD) {
                            double d = AST__C + v;
                            if (d != 0.0) {
                                d = (AST__C - v) / d;
                                values[i] = d >= 0.0 ? finite(temp * Math.sqrt(d)) : AST__BAD;
                            } else {
                                values[i] = AST__BAD;
                            }
                        }
                    }
                } else {
                    setBad(values, np);
                }
                break;
            case ENTOFR:
                for (int i = 0; i < np; i++) {
                    if (values[i] != AST__BAD) {
                        values[i] /= AST__H;
                    }
                }
                break;
            case FRTOEN:
                for (int i = 0; i < np; i++) {
                    if (values[i] != AST__BAD) {
                        values[i] *= AST__H;
                    }
                }
                break;
            case WNTOFR:
            case BTTOVL:
                for (int i = 0; i < np; i++) {
                    if (values[i] != AST__BAD) {
                        values[i] *= AST__C;
                    }
                }
                break;
            case FRTOWN:
            case VLTOBT:
                for (int i = 0; i < np; i++) {
                    if (values[i] != AST__BAD) {
                        values[i] /= AST__C;
                    }
                }
                break;
            case WVTOFR:
            case FRTOWV:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    values[i] = v != AST__BAD && v != 0.0 ? finite(AST__C / v) : AST__BAD;
                }
                break;
            case AWTOFR:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    values[i] = v != AST__BAD && v != 0.0 ? finite(AST__C / (v * refrac(v))) : AST__BAD;
                }
                break;
            case FRTOAW:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    if (v != AST__BAD && v != 0.0) {
                        // The vacuum wavelength. Refrac needs the wavelength in air, which is first
                        // taken to be the vacuum wavelength; each estimate of the air wavelength
                        // gives a better refractive index for the next.
                        double temp = AST__C / v;
                        double w = temp;
                        for (int iter = 0; iter < 3; iter++) {
                            w = temp / refrac(w);
                            if (!Double.isFinite(w)) {
                                w = AST__BAD;
                                break;
                            }
                        }
                        values[i] = w;
                    } else {
                        values[i] = AST__BAD;
                    }
                }
                break;
            case VRTOVL:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    if (v != AST__BAD) {
                        double temp = 1.0 - v / AST__C;
                        temp *= temp;
                        values[i] = finite(AST__C * (1.0 - temp) / (1.0 + temp));
                    }
                }
                break;
            case VLTOVR:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    if (v != AST__BAD) {
                        double temp = AST__C + v;
                        if (temp != 0.0) {
                            temp = (AST__C - v) / temp;
                            values[i] = temp >= 0.0 ? finite(AST__C * (1.0 - Math.sqrt(temp))) : AST__BAD;
                        } else {
                            values[i] = AST__BAD;
                        }
                    }
                }
                break;
            case VOTOVL:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    if (v != AST__BAD) {
                        double temp = 1.0 + v / AST__C;
                        temp *= temp;
                        values[i] = finite(AST__C * (temp - 1.0) / (temp + 1.0));
                    }
                }
                break;
            case VLTOVO:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    if (v != AST__BAD) {
                        double temp = AST__C - v;
                        if (temp != 0.0) {
                            temp = (AST__C + v) / temp;
                            values[i] = temp >= 0.0 ? finite(AST__C * (Math.sqrt(temp) - 1.0)) : AST__BAD;
                        } else {
                            values[i] = AST__BAD;
                        }
                    }
                }
                break;
            case ZOTOVL:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    if (v != AST__BAD) {
                        double temp = 1.0 + v;
                        temp *= temp;
                        values[i] = finite(AST__C * (temp - 1.0) / (temp + 1.0));
                    }
                }
                break;
            case VLTOZO:
                for (int i = 0; i < np; i++) {
                    double v = values[i];
                    if (v != AST__BAD) {
                        double temp = AST__C - v;
                        if (temp != 0.0) {
                            temp = (AST__C + v) / temp;
                            values[i] = temp >= 0.0 ? finite(Math.sqrt(temp) - 1.0) : AST__BAD;
                        } else {
                            values[i] = AST__BAD;
                        }
                    }
                }
                break;
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        "astTransform(SpecMap): Corrupt SpecMap contains invalid spectral coordinate conversion " + c
                                + " (internal AST programming error).");
        }
    }

    /** {@code v}, or {@link #AST__BAD} if it is not finite (C's {@code astISFINITE} check). */
    private static double finite(double v) {
        return Double.isFinite(v) ? v : AST__BAD;
    }

    /** Sets the first {@code np} values to {@link #AST__BAD}. */
    private static void setBad(double[] values, int np) {
        for (int i = 0; i < np; i++) {
            values[i] = AST__BAD;
        }
    }

    /**
     * C's {@code Refrac}: the refractive index of dry air at standard temperature and pressure, at
     * wavelength {@code wavelen} in metres, by the formula of FITS-WCS paper III (Greisen et al).
     * The wavelength should be the air wavelength, but the vacuum wavelength makes no significant
     * difference.
     */
    private static double refrac(double wavelen) {
        if (wavelen == 0.0) {
            return 1.0;
        }
        // The squared wave number in units of (per um)**2.
        double w2 = 1.0E-12 / (wavelen * wavelen);
        return 1.0 + 1.0E-6 * (287.6155 + 1.62887 * w2 + 0.01360 * w2 * w2);
    }

    /**
     * C's {@code FrameChange}: applies to the {@code np} frequencies in {@code freq} the Doppler
     * shift of the change of rest frame {@code s}, in direction {@code forward}.
     *
     * <p>With {@code ra} null (a 1-dimensional SpecMap) every frequency is observed at the source
     * position in the conversion's arguments, and the frequency correction factor is worked out
     * once and kept in the conversion's last argument slot; an unphysical velocity leaves it bad and
     * makes every frequency bad. Otherwise each frequency is observed at its own position in {@code
     * ra} and {@code dec}, and the inverse negates the velocity correction.
     *
     * @return false, changing nothing, if {@code s} is not a change of rest frame
     */
    private static boolean frameChange(Step s, int np, double[] ra, double[] dec, double[] freq, boolean forward) {
        if (!s.cvt.isFrameChange()) {
            return false;
        }
        double[] args = s.args;
        FrameDef def = new FrameDef();
        int sign;
        int ifcorr;
        // MODERN-JAVA(14): switch with arrow labels
        switch (s.cvt) {
            case USF2HL:
            case HLF2US:
                def.veluser = args[0];
                def.refra = args[1];
                def.refdec = args[2];
                ifcorr = 3;
                break;
            case TPF2HL:
            case HLF2TP:
                def.obslon = args[0];
                def.obslat = args[1];
                def.obsalt = args[2];
                def.epoch = args[3];
                def.refra = args[4];
                def.refdec = args[5];
                ifcorr = 6;
                break;
            case GEF2HL:
            case HLF2GE:
            case BYF2HL:
            case HLF2BY:
                def.epoch = args[0];
                def.refra = args[1];
                def.refdec = args[2];
                ifcorr = 3;
                break;
            case LKF2HL:
            case HLF2LK:
            case LDF2HL:
            case HLF2LD:
            case LGF2HL:
            case HLF2LG:
            case GLF2HL:
            case HLF2GL:
                def.refra = args[0];
                def.refdec = args[1];
                ifcorr = 2;
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "FrameChange(SpecMap): unexpected conversion " + s.cvt + ".");
        }
        // The conversions to heliocentric have odd codes.
        sign = s.cvt.cCode % 2 == 1 ? -1 : 1;

        if (ra == null) {
            if (args[ifcorr] == AST__BAD) {
                // The component of the velocity of the output system away from the source, as
                // measured in the input system. If it is positive the output frequency is lower
                // than the input frequency.
                double v = sign * velocity(s.cvt, def.refra, def.refdec, def);
                if (v < AST__C && v > -AST__C) {
                    args[ifcorr] = Math.sqrt((AST__C - v) / (AST__C + v));
                }
            }
            double fcorr = args[ifcorr];
            if (fcorr != AST__BAD && fcorr != 0.0) {
                double factor = forward ? fcorr : 1.0 / fcorr;
                for (int i = 0; i < np; i++) {
                    if (freq[i] != AST__BAD) {
                        freq[i] *= factor;
                    }
                }
            } else {
                setBad(freq, np);
            }
        } else {
            if (!forward) {
                sign = -sign;
            }
            for (int i = 0; i < np; i++) {
                if (ra[i] == AST__BAD || dec[i] == AST__BAD || freq[i] == AST__BAD) {
                    freq[i] = AST__BAD;
                } else {
                    double v = sign * velocity(s.cvt, ra[i], dec[i], def);
                    if (v < AST__C && v > -AST__C) {
                        freq[i] *= Math.sqrt((AST__C - v) / (AST__C + v));
                    } else {
                        freq[i] = AST__BAD;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The component away from the source at {@code ra}, {@code dec} (FK5 J2000, radians) of the
     * velocity of the rest frame that change of rest frame {@code cvt} goes to or from, in m/s,
     * measured within the heliocentric frame of rest: C's {@code cvtFunc} for the conversion.
     */
    private static double velocity(Cvt cvt, double ra, double dec, FrameDef def) {
        // MODERN-JAVA(14): switch expression
        switch (cvt) {
            case USF2HL:
            case HLF2US:
                return userVel(ra, dec, def);
            case TPF2HL:
            case HLF2TP:
                return topoVel(ra, dec, def);
            case GEF2HL:
            case HLF2GE:
                return geoVel(ra, dec, def);
            case BYF2HL:
            case HLF2BY:
                return baryVel(ra, dec, def);
            case LKF2HL:
            case HLF2LK:
                // The component away from the source of the velocity of the Sun relative to the
                // kinematic LSR, negated: the velocity of the LSR away from the source.
                return -1000.0 * Pal.rvlsrk(ra, dec);
            case LDF2HL:
            case HLF2LD:
                return -1000.0 * Pal.rvlsrd(ra, dec);
            case LGF2HL:
            case HLF2LG:
                return -1000.0 * Pal.rvlg(ra, dec);
            case GLF2HL:
            case HLF2GL:
                // The velocity of the Sun relative to the dynamical LSR, plus that of the
                // dynamical LSR relative to the galactic centre.
                return -1000.0 * (Pal.rvlsrd(ra, dec) + Pal.rvgalc(ra, dec));
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        "astTransform(SpecMap): conversion " + cvt
                                + " is not a change of rest frame (internal AST programming error).");
        }
    }

    /** C's {@code BaryVel}: the velocity of the Earth-Sun barycentre away from the source. */
    private static double baryVel(double ra, double dec, FrameDef def) {
        double[] v = Pal.dcs2c(ra, dec);
        if (def.dvb == null) {
            double[][] e = Pal.evp(def.epoch, 2000.0);
            double[] dvb = e[0];
            double[] dvh = e[2];
            // The barycentric velocity of the Earth becomes the heliocentric velocity of the
            // barycentre.
            def.dvb = new double[] {dvh[0] - dvb[0], dvh[1] - dvb[1], dvh[2] - dvb[2]};
        }
        return -Pal.dvdv(v, def.dvb) * AU;
    }

    /** C's {@code GeoVel}: the velocity of the Earth away from the source. */
    private static double geoVel(double ra, double dec, FrameDef def) {
        double[] v = Pal.dcs2c(ra, dec);
        if (def.dvh == null) {
            def.dvh = Pal.evp(def.epoch, 2000.0)[2];
        }
        return -Pal.dvdv(v, def.dvh) * AU;
    }

    /**
     * C's {@code TopoVel}: the velocity of the observer away from the source, the observer's
     * velocity relative to the centre of the Earth plus the Earth's relative to the Sun.
     */
    private static double topoVel(double ra, double dec, FrameDef def) {
        if (def.amprms == null) {
            def.amprms = Pal.mappa(2000.0, def.epoch);
        }
        // The source's apparent RA and Dec.
        double[] app = Pal.mapqkz(ra, dec, def.amprms);
        if (def.last == AST__BAD) {
            def.last = Pal.gmst(def.epoch) + Pal.eqeqx(def.epoch) + def.obslon;
        }
        double vobs = 1000.0 * rverot(def.obslat, def.obsalt, app[0], app[1], def.last);
        double vearth = geoVel(ra, dec, def);
        return vobs + vearth;
    }

    /**
     * C's {@code Rverot}: the component in direction ({@code ra}, {@code da}) (geocentric apparent,
     * radians) of the Earth-rotation velocity of an observer at geodetic latitude {@code phi} and
     * height {@code h} (m), at local apparent sidereal time {@code st}, in km/s, positive when the
     * observer recedes from that direction. PAL's {@code palPvobs} gives au/s with ERFA's au, which
     * converts it back.
     */
    private static double rverot(double phi, double h, double ra, double da, double st) {
        double[] v = Pal.dcs2c(ra, da);
        double[] pv = Pal.pvobs(phi, h, st);
        return -Pal.dvdv(v, new double[] {pv[3], pv[4], pv[5]}) * (Pal.ERFA_DAU / 1000.0);
    }

    /**
     * C's {@code UserVel}: the component away from the source of the velocity of the user-defined
     * rest frame, whose direction is the reference RA and Dec and whose magnitude, positive when the
     * source moves away from the observer, is the user velocity, in the heliocentric rest frame.
     */
    private static double userVel(double ra, double dec, FrameDef def) {
        if (def.vuser == null) {
            def.vuser = new double[] {
                def.veluser * Math.cos(def.refra) * Math.cos(def.refdec),
                def.veluser * Math.sin(def.refra) * Math.cos(def.refdec),
                def.veluser * Math.sin(def.refdec)
            };
        }
        double[] vb = Pal.dcs2c(ra, dec);
        // Negated: the user velocity is positive when the source moves away from the observer.
        return -Pal.dvdv(def.vuser, vb);
    }

    /**
     * The rate of change of the output with respect to the input, as C's {@code Rate} (specmap.c)
     * finds it: one for a SpecMap with no conversions; for a 1-dimensional SpecMap whose only
     * conversion is between frequency and wavelength, the derivative -c/x^2, which holds in both
     * directions, so the Invert attribute is not consulted; and otherwise the numerical estimate of
     * the Mapping class.
     */
    @Override
    double rateAt(double[] at, int ax1, int ax2) {
        if (steps.isEmpty()) {
            return 1.0;
        }
        Cvt cvt = steps.get(0).cvt;
        if (getNin() != 1 || steps.size() != 1 || (cvt != Cvt.WVTOFR && cvt != Cvt.FRTOWV)) {
            return super.rateAt(at, ax1, ax2);
        }
        return at[0] != AST__BAD ? -AST__C / (at[0] * at[0]) : AST__BAD;
    }
}
