package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The conversion between the celestial coordinate systems of two SkyFrames: C's {@code
 * MakeSkyMapping}, with the checks of {@code VerifyMSMAttrs} and {@code TestSlaUnit} it makes
 * (skyframe.c).
 *
 * <p>The conversion goes from the target's System to FK5 J2000, then to the alignment System,
 * then back to FK5 J2000 and on to the result's System, each step an {@link SlaMap} conversion
 * with the Equinox, Epoch and observer of the SkyFrame it belongs to; steps that are not needed
 * are left out. The SlaMap is wrapped between the inverse of the target's offset Mapping and the
 * result's offset Mapping ({@link SkyFrame#skyOffsetMap}).
 *
 * <p>Ported from C {@code skyframe.c}, with Rust {@code frames/sky_convert.rs}.
 */
final class SkyConvert {

    /** C's {@code AST__DR2D}: degrees per radian. */
    private static final double AST__DR2D = 57.29577951308232;

    /** The latitudes of the test points of {@code TestSlaUnit}. */
    private static final double[] TEST_LAT = {
        0.0, 0.0, 0.0, 0.0, 0.8, 0.8, 0.8, 0.8, -0.8, -0.8, -0.8, -0.8, 1.570796, -1.570796
    };

    /** The longitudes of the test points of {@code TestSlaUnit}. */
    private static final double[] TEST_LON = {
        0.0, 1.57, 3.14, 4.71, 0.8, 2.37, 3.94, 5.51, 0.8, 2.37, 3.94, 5.51, 0.0, 0.0
    };

    private SkyConvert() {}

    /** Whether a System is referred to an equinox (C's {@code EQREF}). */
    private static boolean eqref(int system) {
        return system == SkyFrame.AST__FK4
                || system == SkyFrame.AST__FK4_NO_E
                || system == SkyFrame.AST__FK5
                || system == SkyFrame.AST__ECLIPTIC;
    }

    /** The SlaMap being built, with C's {@code TRANSFORM_n} macros. */
    private static final class Steps {
        final SlaMap slamap = new SlaMap();

        void add(String cvt, double... args) {
            slamap.add(cvt, args);
        }
    }

    /**
     * The Mapping from the coordinates of {@code target} to those of {@code result}, aligning in
     * {@code alignSys} (C's {@code MakeSkyMapping}), or null if there is none, which is when a
     * step needs a conversion to or from the Unknown System. The LAST and diurnal aberration of
     * both SkyFrames are found, and cached in them, whatever the Systems.
     *
     * @throws AstException with status {@code AST__NOVAL} if a step needs an attribute that is not
     *     set in a SkyFrame whose UseDefs is false
     */
    static Mapping makeSkyMapping(SkyFrame target, SkyFrame result, int alignSys) {
        double resultEpoch = result.getEpochMjd();
        double targetEpoch = target.getEpochMjd();
        double resultEquinox = result.getEquinoxMjd();
        double targetEquinox = target.getEquinoxMjd();
        int resultSystem = result.systemValue();
        int targetSystem = target.systemValue();
        if (!eqref(resultSystem)) {
            resultEquinox = targetEquinox;
        }
        if (!eqref(targetSystem)) {
            targetEquinox = resultEquinox;
        }
        if (targetSystem == SkyFrame.AST__UNKNOWN && resultSystem == SkyFrame.AST__UNKNOWN) {
            return new UnitMap(2);
        }
        boolean step1 = true;
        boolean step2 = true;
        boolean step3 = true;
        boolean step4 = true;
        if (targetSystem == alignSys) {
            step1 = false;
            step2 = false;
        }
        if (resultSystem == alignSys) {
            step3 = false;
            step4 = false;
        }
        if (step2 && step3 && (alignSys == SkyFrame.AST__FK5 || resultEpoch == targetEpoch)) {
            step2 = false;
            step3 = false;
        }
        if (resultSystem == targetSystem && resultEpoch == targetEpoch && resultEquinox == targetEquinox) {
            step1 = false;
            step2 = false;
            step3 = false;
            step4 = false;
        }
        Steps s = new Steps();
        boolean match = true;

        int system = targetSystem;
        double equinox = targetEquinox;
        double epoch = targetEpoch;
        double last = target.getLast();
        double diurab = target.getDiurab();
        double lat = target.getObsLatValue();
        double equinoxB = 0.0;
        double equinoxJ = 0.0;
        double epochB = 0.0;
        double epochJ = 0.0;
        if (step1) {
            equinoxB = Pal.epb(equinox);
            equinoxJ = Pal.epj(equinox);
            epochB = Pal.epb(epoch);
            epochJ = Pal.epj(epoch);
            match = toFk5(
                    s, target, result, 1, system, equinox, equinoxB, equinoxJ, epoch, epochB, epochJ, lat, last,
                    diurab);
        }
        if (match && step2) {
            match = fromFk5(
                    s, target, result, 1, alignSys, equinox, equinoxB, equinoxJ, epoch, epochB, epochJ, lat, last,
                    diurab);
        }

        system = resultSystem;
        equinox = resultEquinox;
        epoch = resultEpoch;
        diurab = result.getDiurab();
        last = result.getLast();
        lat = result.getObsLatValue();
        equinoxB = Pal.epb(equinox);
        equinoxJ = Pal.epj(equinox);
        epochB = Pal.epb(epoch);
        epochJ = Pal.epj(epoch);
        if (match && step3) {
            match = toFk5(
                    s, target, result, 3, alignSys, equinox, equinoxB, equinoxJ, epoch, epochB, epochJ, lat, last,
                    diurab);
        }
        if (match && step4) {
            match = fromFk5(
                    s, target, result, 3, system, equinox, equinoxB, equinoxJ, epoch, epochB, epochJ, lat, last,
                    diurab);
        }

        boolean isunit = testSlaUnit(target, result, s.slamap);
        Mapping omap = target.skyOffsetMap();
        omap.invert();
        Mapping tmap = isunit ? omap : new CmpMap(omap, s.slamap, true);
        Mapping omap2 = result.skyOffsetMap();
        Mapping map = new CmpMap(tmap, omap2, true).simplify();
        return match ? map : null;
    }

    /**
     * Adds the conversion from {@code system} to FK5 J2000 (C's step 1, and step 3 for the
     * alignment System), checking the attributes it needs with {@code which} as {@code
     * VerifyMSMAttrs} does.
     *
     * @return false if {@code system} is Unknown
     */
    private static boolean toFk5(
            Steps s,
            SkyFrame target,
            SkyFrame result,
            int which,
            int system,
            double equinox,
            double equinoxB,
            double equinoxJ,
            double epoch,
            double epochB,
            double epochJ,
            double lat,
            double last,
            double diurab) {
        switch (system) {
            case SkyFrame.AST__FK4:
                verifyMsmAttrs(target, result, which, "Equinox Epoch");
                if (equinoxB != 1950.0) {
                    s.add("SUBET", equinoxB);
                    s.add("PREBN", equinoxB, 1950.0);
                    s.add("ADDET", 1950.0);
                }
                s.add("FK45Z", epochB);
                return true;
            case SkyFrame.AST__FK4_NO_E:
                verifyMsmAttrs(target, result, which, "Equinox Epoch");
                if (equinoxB != 1950.0) {
                    s.add("PREBN", equinoxB, 1950.0);
                }
                s.add("ADDET", 1950.0);
                s.add("FK45Z", epochB);
                return true;
            case SkyFrame.AST__FK5:
                verifyMsmAttrs(target, result, which, "Equinox");
                if (equinoxJ != 2000.0) {
                    s.add("PREC", equinoxJ, 2000.0);
                }
                return true;
            case SkyFrame.AST__J2000:
                verifyMsmAttrs(target, result, which, "Epoch");
                s.add("J2000H");
                s.add("HFK5Z", epochJ);
                return true;
            case SkyFrame.AST__GAPPT:
                verifyMsmAttrs(target, result, which, "Epoch");
                s.add("AMP", epoch, 2000.0);
                return true;
            case SkyFrame.AST__ECLIPTIC:
                verifyMsmAttrs(target, result, which, "Equinox");
                s.add("ECLEQ", equinox);
                return true;
            case SkyFrame.AST__HELIOECLIPTIC:
                verifyMsmAttrs(target, result, which, "Epoch");
                s.add("HEEQ", epoch);
                return true;
            case SkyFrame.AST__GALACTIC:
                s.add("GALEQ");
                return true;
            case SkyFrame.AST__ICRS:
                verifyMsmAttrs(target, result, which, "Epoch");
                s.add("HFK5Z", epochJ);
                return true;
            case SkyFrame.AST__SUPERGALACTIC:
                s.add("SUPGAL");
                s.add("GALEQ");
                return true;
            case SkyFrame.AST__AZEL:
                verifyMsmAttrs(target, result, which, "ObsLon ObsLat Epoch");
                s.add("H2E", lat, diurab);
                s.add("H2R", last);
                s.add("AMP", epoch, 2000.0);
                return true;
            case SkyFrame.AST__UNKNOWN:
                return false;
            default:
                // C adds no step for a code that is not a celestial System.
                return true;
        }
    }

    /**
     * Adds the conversion from FK5 J2000 to {@code system} (C's step 4, and step 2 for the
     * alignment System), checking the attributes it needs with {@code which} as {@code
     * VerifyMSMAttrs} does.
     *
     * @return false if {@code system} is Unknown
     */
    private static boolean fromFk5(
            Steps s,
            SkyFrame target,
            SkyFrame result,
            int which,
            int system,
            double equinox,
            double equinoxB,
            double equinoxJ,
            double epoch,
            double epochB,
            double epochJ,
            double lat,
            double last,
            double diurab) {
        switch (system) {
            case SkyFrame.AST__FK4:
                verifyMsmAttrs(target, result, which, "Equinox Epoch");
                s.add("FK54Z", epochB);
                if (equinoxB != 1950.0) {
                    s.add("SUBET", 1950.0);
                    s.add("PREBN", 1950.0, equinoxB);
                    s.add("ADDET", equinoxB);
                }
                return true;
            case SkyFrame.AST__FK4_NO_E:
                verifyMsmAttrs(target, result, which, "Equinox Epoch");
                s.add("FK54Z", epochB);
                s.add("SUBET", 1950.0);
                if (equinoxB != 1950.0) {
                    s.add("PREBN", 1950.0, equinoxB);
                }
                return true;
            case SkyFrame.AST__FK5:
                verifyMsmAttrs(target, result, which, "Equinox");
                if (equinoxJ != 2000.0) {
                    s.add("PREC", 2000.0, equinoxJ);
                }
                return true;
            case SkyFrame.AST__J2000:
                verifyMsmAttrs(target, result, which, "Epoch");
                s.add("FK5HZ", epochJ);
                s.add("HJ2000");
                return true;
            case SkyFrame.AST__GAPPT:
                verifyMsmAttrs(target, result, which, "Epoch");
                s.add("MAP", 2000.0, epoch);
                return true;
            case SkyFrame.AST__ECLIPTIC:
                verifyMsmAttrs(target, result, which, "Equinox");
                s.add("EQECL", equinox);
                return true;
            case SkyFrame.AST__HELIOECLIPTIC:
                verifyMsmAttrs(target, result, which, "Epoch");
                s.add("EQHE", epoch);
                return true;
            case SkyFrame.AST__GALACTIC:
                s.add("EQGAL");
                return true;
            case SkyFrame.AST__ICRS:
                verifyMsmAttrs(target, result, which, "Epoch");
                s.add("FK5HZ", epochJ);
                return true;
            case SkyFrame.AST__SUPERGALACTIC:
                s.add("EQGAL");
                s.add("GALSUP");
                return true;
            case SkyFrame.AST__AZEL:
                verifyMsmAttrs(target, result, which, "ObsLon ObsLat Epoch");
                s.add("MAP", 2000.0, epoch);
                s.add("R2H", last);
                s.add("E2H", lat, diurab);
                return true;
            case SkyFrame.AST__UNKNOWN:
                return false;
            default:
                // C adds no step for a code that is not a celestial System.
                return true;
        }
    }

    /**
     * Whether a conversion shifts no test position by as much as the smaller SkyTol of the two
     * SkyFrames (C's {@code TestSlaUnit}); an SlaMap with no conversions always does.
     */
    static boolean testSlaUnit(SkyFrame sf1, SkyFrame sf2, SlaMap slamap) {
        if (slamap.isEmpty()) {
            return true;
        }
        double tol = sf1.getSkyTol();
        if (sf2 != null) {
            tol = Math.min(tol, sf2.getSkyTol());
        }
        if (tol > 0.0) {
            int n = TEST_LON.length;
            double[][] out = slamap.tran2(n, TEST_LON, TEST_LAT, true);
            double maxshift = 0.0;
            for (int i = 0; i < n; i++) {
                double shift = Pal.dsep(TEST_LON[i], TEST_LAT[i], out[0][i], out[1][i]);
                if (shift > maxshift) {
                    maxshift = shift;
                }
            }
            return maxshift * AST__DR2D * 3600 < tol;
        }
        return false;
    }

    /**
     * Checks that the attributes a conversion step needs are set where UseDefs is false (C's
     * {@code VerifyMSMAttrs}): in the target for {@code which} below 3, and in the result for
     * {@code which} above 1. Before reporting a missing one, the Titles of both are cleared.
     *
     * @throws AstException with status {@code AST__NOVAL} if one is not set
     */
    static void verifyMsmAttrs(SkyFrame target, SkyFrame result, int which, String attrs) {
        boolean usedef1 = target.getUseDefs();
        boolean usedef2 = result.getUseDefs();
        if (usedef1 && usedef2) {
            return;
        }
        int i = 0;
        int n = attrs.length();
        while (i < n) {
            while (i < n && CScan.isCSpace(attrs.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !CScan.isCSpace(attrs.charAt(i))) {
                i++;
            }
            if (i == start) {
                break;
            }
            String a = attrs.substring(start, i);
            boolean set1;
            boolean set2;
            String desc;
            switch (a) {
                case "Equinox":
                    set1 = target.testEquinox();
                    set2 = result.testEquinox();
                    desc = "reference equinox";
                    break;
                case "Dtai":
                    set1 = target.testDtai();
                    set2 = result.testDtai();
                    desc = "TAI-UTC correction";
                    break;
                case "Dut1":
                    set1 = target.testDut1();
                    set2 = result.testDut1();
                    desc = "UT1-UTC correction";
                    break;
                case "Epoch":
                    set1 = target.testEpoch();
                    set2 = result.testEpoch();
                    desc = "epoch of observation";
                    break;
                case "ObsLon":
                    set1 = target.testObsLon();
                    set2 = result.testObsLon();
                    desc = "longitude of observer";
                    break;
                case "ObsLat":
                    set1 = target.testObsLat();
                    set2 = result.testObsLat();
                    desc = "latitude of observer";
                    break;
                case "ObsAlt":
                    set1 = target.testObsAlt();
                    set2 = result.testObsAlt();
                    desc = "altitude of observer";
                    break;
                default:
                    throw Ast.err(
                            AstException.AST__INTER,
                            "VerifyMSMAttrs(SkyFrame): Unknown attribute name \"" + a
                                    + "\" supplied (AST internal programming error).");
            }
            if (!usedef1 && !set1 && which < 3) {
                throw noValue(target, result, target, a, desc, "input");
            }
            if (!usedef2 && !set2 && which > 1) {
                throw noValue(target, result, result, a, desc, "output");
            }
        }
    }

    private static AstException noValue(
            SkyFrame target, SkyFrame result, SkyFrame which, String attr, String desc, String io) {
        target.clearTitle();
        result.clearTitle();
        return Ast.err(
                AstException.AST__NOVAL,
                "astMatch(" + which.astClass() + "): Cannot convert celestial coordinates from "
                        + target.getC("Title") + " to " + result.getC("Title") + ".\nNo value has been set for the \""
                        + attr + "\" attribute (" + desc + ") in the " + io + " " + which.astClass() + ".");
    }
}
