package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/sky_sys.rs} (C {@code
 * SkySys} and {@code IsSkyOff}, fitschan.c), with the values C's code gives where they differ from
 * Rust's, and tests of branches the Rust tests do not reach, their expectations read from C's
 * code.
 */
class FitsSkySysTest {

    private static SkyFrame sky(String system) {
        SkyFrame sky = new SkyFrame();
        sky.setSystem(system);
        return sky;
    }

    /** C's {@code SkySys} for axes 0 and 1 of a two-axis store, with an empty FitsChan. */
    private static boolean skySys(SkyFrame sky, int wcstype, int proj, FitsStore store, char s, int isoff) {
        return skySys(new FitsChan(), sky, wcstype, proj, store, s, isoff);
    }

    private static boolean skySys(
            FitsChan fc, SkyFrame sky, int wcstype, int proj, FitsStore store, char s, int isoff) {
        return FitsSkySys.skySys(fc, sky, wcstype, proj, store, 0, 1, s, isoff, "astWrite", "FitsChan");
    }

    @Test
    void fk5SkySysWritesCtypeRadesysEquinox() {
        FitsStore s = new FitsStore(2);
        s.cunit[0] = "deg";
        s.cunit[1] = "deg";
        assertTrue(skySys(sky("FK5"), 1, WcsMap.AST__TAN, s, ' ', 0));
        assertEquals("RA---TAN", s.ctype[0]);
        assertEquals("DEC--TAN", s.ctype[1]);
        assertEquals("FK5", s.radesys);
        // The default equinox, J2000.0, as palEpj gives it.
        assertEquals(Pal.epj(51544.5), s.equinox);
        assertEquals(2000.0, s.equinox, 1e-12);
        assertNull(s.cunit[0]);
        assertNull(s.cunit[1]);
    }

    @Test
    void fk4SkySysUsesBesselianEquinox() {
        FitsStore s = new FitsStore(2);
        skySys(sky("FK4"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals(1950.0, s.equinox, 1e-9);
        assertEquals("FK4", s.radesys);
    }

    @Test
    void icrsNoEquinox() {
        FitsStore s = new FitsStore(2);
        skySys(sky("ICRS"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertNull(s.equinox);
        assertEquals("ICRS", s.radesys);
        assertEquals("RA---TAN", s.ctype[0]);
        assertEquals("DEC--TAN", s.ctype[1]);
    }

    @Test
    void galacticUsesGlonGlatNoEquinoxNoRadesys() {
        FitsStore s = new FitsStore(2);
        skySys(sky("GALACTIC"), 1, WcsMap.AST__SIN, s, ' ', 0);
        assertEquals("GLON-SIN", s.ctype[0]);
        assertEquals("GLAT-SIN", s.ctype[1]);
        assertNull(s.radesys);
        assertNull(s.equinox);
    }

    @Test
    void eclipticUsesElonElatJulianEquinox() {
        FitsStore s = new FitsStore(2);
        skySys(sky("ECLIPTIC"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("ELON-TAN", s.ctype[0]);
        assertEquals("ELAT-TAN", s.ctype[1]);
        assertEquals(2000.0, s.equinox);
    }

    @Test
    void supergalacticSlatSlon() {
        FitsStore s = new FitsStore(2);
        skySys(sky("SUPERGALACTIC"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("SLON-TAN", s.ctype[0]);
        assertEquals("SLAT-TAN", s.ctype[1]);
    }

    @Test
    void azelWritesAzElPrefixes() {
        FitsStore s = new FitsStore(2);
        skySys(sky("AZEL"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("AZ---TAN", s.ctype[0]);
        assertEquals("EL---TAN", s.ctype[1]);
    }

    @Test
    void isoffPositiveWritesOflnOflt() {
        SkyFrame sky = sky("FK5");
        sky.setSkyRefIs("Origin");
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 1);
        assertEquals("OFLN-TAN", s.ctype[0]);
        assertEquals("OFLT-TAN", s.ctype[1]);
    }

    /**
     * The CTYPE comments of offset coordinates: each axis Symbol without its first character
     * (the "D" of an offset Symbol) and the formatted SkyRef.
     */
    @Test
    void isoffPositiveWritesCtypeCommentOffsetFrom() {
        SkyFrame sky = sky("FK5");
        sky.setSkyRefIs("Origin");
        sky.setSkyRef(1, 0.5);
        sky.setSkyRef(2, -0.25);
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 1);
        assertTrue(s.ctypeCom[0].startsWith("RA offset from "), s.ctypeCom[0]);
        assertTrue(s.ctypeCom[1].startsWith("Dec offset from "), s.ctypeCom[1]);
        assertEquals("RA offset from " + sky.getC("SkyRef"), s.ctypeCom[0]);
    }

    @Test
    void isoffNegativeWritesSkyrefisSkyrefSkyrefp() {
        SkyFrame sky = sky("FK5");
        sky.setSkyRefIs("Pole");
        sky.setSkyRef(1, 0.5);
        sky.setSkyRef(2, -0.25);
        sky.setSkyRefP(1, 1.0);
        sky.setSkyRefP(2, 0.5);
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', -1);
        assertEquals("POLE", s.skyrefis);
        assertEquals(0.5, s.skyref[0]);
        assertEquals(-0.25, s.skyref[1]);
        assertEquals(1.0, s.skyrefp[0]);
        assertEquals(0.5, s.skyrefp[1]);
        // Only a positive isoff gives the offset CTYPE.
        assertEquals("RA---TAN", s.ctype[0]);
    }

    @Test
    void isoffNegative2WritesOrigin() {
        SkyFrame sky = sky("FK5");
        sky.setSkyRefIs("Origin");
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', -2);
        assertEquals("ORIGIN", s.skyrefis);
        assertNull(s.skyref[0]);
        assertNull(s.skyrefp[0]);
    }

    @Test
    void isoffNegative3WritesIgnored() {
        FitsStore s = new FitsStore(2);
        skySys(sky("FK5"), 1, WcsMap.AST__TAN, s, ' ', -3);
        assertEquals("IGNORED", s.skyrefis);
    }

    @Test
    void isoffZeroDoesNotEmitSkyrefMetadata() {
        SkyFrame sky = sky("FK5");
        sky.setSkyRef(1, 1.0);
        sky.setSkyRef(2, 1.0);
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        assertNull(s.skyrefis);
        assertNull(s.skyref[0]);
        assertNull(s.skyref[1]);
    }

    /** The observer's geocentric position, by eraGd2gc on the WGS84 ellipsoid. */
    @Test
    void obsgeoEmittedWhenObserverPositionSet() {
        SkyFrame sky = sky("FK5");
        double lat = Math.toRadians(51.4778);
        sky.setObsLonValue(0.0);
        sky.setObsLatValue(lat);
        sky.setObsAlt(0.0);
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        double[] xyz = Pal.gd2gc(0.0, lat, 0.0);
        assertEquals(xyz[0], s.obsgeox);
        assertEquals(xyz[1], s.obsgeoy);
        assertEquals(xyz[2], s.obsgeoz);
        double r = Math.sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2]);
        assertEquals(6_371_000.0, r, 50_000.0);
        assertEquals(0.0, s.obsgeoy, 1e-3);
    }

    @Test
    void obsgeoSkippedForNonPrimaryVersion() {
        SkyFrame sky = sky("FK5");
        sky.setObsLonValue(0.0);
        sky.setObsLatValue(0.0);
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, 'A', 0);
        assertNull(s.obsgeox);
    }

    @Test
    void obsgeoSkippedWhenObserverUnset() {
        FitsStore s = new FitsStore(2);
        skySys(sky("FK5"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertNull(s.obsgeox);
    }

    /**
     * Rust stores a Domain other than SKY as WCSNAME; C's {@code SkySys} begins with the {@code
     * IsASkyFrame} test, which needs the Domain SKY, so it stores nothing and returns 0.
     */
    @Test
    void nonSkyDomainIsNotDescribed() {
        SkyFrame sky = sky("FK5");
        sky.setDomain("MY-DOMAIN");
        FitsStore s = new FitsStore(2);
        assertFalse(skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0));
        assertNull(s.wcsname);
        assertNull(s.ctype[0]);
        assertNull(s.radesys);
    }

    /** A Domain set, which can only be SKY, is stored as WCSNAME (C's {@code astTestDomain}). */
    @Test
    void wcsnameEmittedWhenDomainSet() {
        SkyFrame sky = sky("FK5");
        sky.setDomain("SKY");
        FitsStore s = new FitsStore(2);
        assertTrue(skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0));
        assertEquals("SKY", s.wcsname);
    }

    @Test
    void wcsnameSkippedForDefaultDomain() {
        FitsStore s = new FitsStore(2);
        skySys(sky("FK5"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertNull(s.wcsname);
    }

    @Test
    void axisLabelEmitsCtypeCommentAndCname() {
        SkyFrame sky = sky("FK5");
        sky.setLabel(1, "MyRA");
        sky.setLabel(2, "MyDec");
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("MyRA", s.ctypeCom[0]);
        assertEquals("MyRA", s.cname[0]);
        assertEquals("MyDec", s.ctypeCom[1]);
        assertEquals("MyDec", s.cname[1]);
    }

    @Test
    void axisLabelUnsetLeavesCtypeComDefault() {
        FitsStore s = new FitsStore(2);
        skySys(sky("FK5"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertNull(s.ctypeCom[0]);
        assertNull(s.ctypeCom[1]);
        assertNull(s.cname[0]);
    }

    @Test
    void unknownSystemSymbolXxlnXxltPassesThrough() {
        SkyFrame sky = sky("UNKNOWN");
        sky.setSymbol(1, "QQLN");
        sky.setSymbol(2, "QQLT");
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("QQLN-TAN", s.ctype[0]);
        assertEquals("QQLT-TAN", s.ctype[1]);
    }

    @Test
    void unknownSystemSymbolXlonXlatPassesThrough() {
        SkyFrame sky = sky("UNKNOWN");
        sky.setSymbol(1, "ZLON");
        sky.setSymbol(2, "ZLAT");
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("ZLON-TAN", s.ctype[0]);
        assertEquals("ZLAT-TAN", s.ctype[1]);
    }

    /** MJD-OBS is the Epoch converted from TDB to UTC, as C's {@code TDBConv} gives it. */
    @Test
    void epochConvertsTdbToUtcForMjdobs() {
        SkyFrame sky = sky("FK5");
        double tdb = 60000.0;
        sky.setEpochMjd(tdb);
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        assertNotNull(s.mjdobs);
        assertEquals(FitsEpoch.tdbConv(tdb, TimeFrame.AST__UTC, true), s.mjdobs);
        double deltaS = (tdb - s.mjdobs) * 86_400.0;
        assertTrue(deltaS > 60.0 && deltaS < 80.0, "TDB-UTC " + deltaS);
        assertNull(s.timesys);
    }

    @Test
    void unknownSystemNonConformingFallsBackToUnlnUnlt() {
        SkyFrame sky = sky("UNKNOWN");
        sky.setSymbol(1, "WEIRD");
        sky.setSymbol(2, "ALSOWEIRD");
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("UNLN-TAN", s.ctype[0]);
        assertEquals("UNLT-TAN", s.ctype[1]);
    }

    @Test
    void wcstypeZeroUsesTabSuffix() {
        FitsStore s = new FitsStore(2);
        skySys(sky("FK5"), 0, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("RA---TAB", s.ctype[0]);
        assertEquals("DEC--TAB", s.ctype[1]);
    }

    /** A two-Frame FrameSet: a base Frame and a SkyFrame with SkyRefIs {@code skyRefIs}. */
    private static FrameSet skyOnlyFrameSet(String skyRefIs) {
        SkyFrame sky = sky("FK5");
        sky.setSkyRefIs(skyRefIs);
        FrameSet fs = new FrameSet(new Frame(2));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), sky);
        return fs;
    }

    @Test
    void isSkyOffOriginReturnsOne() {
        assertEquals(1, FitsSkySys.isSkyOff(skyOnlyFrameSet("Origin"), 2));
    }

    @Test
    void isSkyOffPoleReturnsOne() {
        assertEquals(1, FitsSkySys.isSkyOff(skyOnlyFrameSet("Pole"), 2));
    }

    @Test
    void isSkyOffIgnoredReturnsZero() {
        assertEquals(0, FitsSkySys.isSkyOff(skyOnlyFrameSet("Ignored"), 2));
    }

    @Test
    void isSkyOffBasicFrameReturnsZero() {
        assertEquals(0, FitsSkySys.isSkyOff(skyOnlyFrameSet("Origin"), 1));
    }

    // ------------------------------------------------------------------
    // Branches read from C's code
    // ------------------------------------------------------------------

    /** A CmpFrame holding an offset SkyFrame is offset: its SkyRefIs is read from the SkyFrame. */
    @Test
    void isSkyOffSeesASkyFrameInACmpFrame() {
        SkyFrame sky = sky("FK5");
        sky.setSkyRefIs("Pole");
        FrameSet fs = new FrameSet(new Frame(3));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(3), new CmpFrame(new Frame(1), sky));
        assertEquals(1, FitsSkySys.isSkyOff(fs, FrameSet.AST__CURRENT));
    }

    /** J2000 is none of the systems C names, so it is described as an unknown system. */
    @Test
    void j2000IsAnUnknownSystem() {
        FitsStore s = new FitsStore(2);
        skySys(sky("J2000"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("UNLN-TAN", s.ctype[0]);
        assertEquals("UNLT-TAN", s.ctype[1]);
        assertNull(s.radesys);
        assertNull(s.equinox);
    }

    @Test
    void fk4NoEAndGapptAndHelioecliptic() {
        FitsStore s = new FitsStore(2);
        skySys(sky("FK4-NO-E"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("FK4-NO-E", s.radesys);
        assertEquals(1950.0, s.equinox, 1e-9);

        s = new FitsStore(2);
        skySys(sky("GAPPT"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("GAPPT", s.radesys);
        assertEquals("RA---TAN", s.ctype[0]);
        assertNull(s.equinox);

        s = new FitsStore(2);
        skySys(sky("HELIOECLIPTIC"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("HLON-TAN", s.ctype[0]);
        assertEquals("HLAT-TAN", s.ctype[1]);
        assertNull(s.equinox);
    }

    /** An equinox that is not stored overwrites one already in the store, as C's SetItem of AST__BAD. */
    @Test
    void anUnstoredEquinoxClearsTheStore() {
        FitsStore s = new FitsStore(2);
        s.equinox = 1950.0;
        skySys(sky("ICRS"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertNull(s.equinox);
    }

    /** FITS has one MJD-OBS, which an alternate description does not store. */
    @Test
    void mjdobsOnlyForThePrimaryDescription() {
        SkyFrame sky = sky("FK5");
        sky.setEpochMjd(60000.0);
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, 'A', 0);
        assertNull(s.mjdobs);
    }

    /**
     * The longitude and latitude axes are those of the SkyFrame, whatever its axis order: with
     * latitude first, the latitude axis's Label is still stored for the latitude FITS axis.
     */
    @Test
    void permutedSkyFrameAxes() {
        SkyFrame sky = sky("FK5");
        sky.permAxes(new int[] {2, 1});
        sky.setLabel(1, "First");
        FitsStore s = new FitsStore(2);
        skySys(sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("First", s.cname[1]);
        assertNull(s.cname[0]);
    }

    /**
     * Under a TIMESYS card naming another time scale, MJD-OBS is in that scale and TIMESYS is
     * stored, its padding removed; the card is found even when a read has used it.
     */
    @Test
    void timesysCardGivesTheTimeScale() {
        SkyFrame sky = sky("FK5");
        double tdb = 60000.0;
        sky.setEpochMjd(tdb);
        FitsChan fc = new FitsChan();
        fc.putFits("TIMESYS = 'TAI     '", false);
        fc.cards().cards().get(0).flags.consumption = FitsCard.Consumption.USED;
        FitsStore s = new FitsStore(2);
        skySys(fc, sky, 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("TAI", s.timesys);
        assertEquals(FitsEpoch.tdbConv(tdb, TimeFrame.AST__TAI, true), s.mjdobs);
    }

    /** A TIMESYS of UTC is the default, and is not stored. */
    @Test
    void utcTimesysIsNotStored() {
        FitsChan fc = new FitsChan();
        fc.putFits("TIMESYS = 'UTC'", false);
        FitsStore s = new FitsStore(2);
        skySys(fc, sky("FK5"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertNull(s.timesys);
    }

    /** An unsupported TIMESYS raises C's {@code badval} warning, as an ASTWARN card. */
    @Test
    void unsupportedTimesysWarns() {
        FitsChan fc = new FitsChan();
        fc.setWarnings("BadVal");
        fc.putFits("TIMESYS = 'XYZ'", false);
        FitsStore s = new FitsStore(2);
        skySys(fc, sky("FK5"), 1, WcsMap.AST__TAN, s, ' ', 0);
        assertEquals("XYZ", s.timesys);
        fc.clear("Card");
        assertNotNull(fc.findFits("ASTWARN", false));
    }
}
