package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translates the unit tests of Rust {@code fitschan/wcs_read.rs} ({@code mod tests}), which build
 * FitsStores directly, and adds tests of the pieces of C's {@code FsetFromStore}, {@code WcsMapFrm}
 * and {@code WcsOthers} the read is made of.
 */
class FitsWcsReadTest {

    /** A minimal two-dimensional celestial FitsStore. */
    private static FitsStore celestial2dStore() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.crpix[0] = 128.0;
        store.crpix[1] = 128.0;
        store.crval[0] = 180.0;
        store.crval[1] = 45.0;
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.radesys = "ICRS";
        return store;
    }

    /** Rust's test helper {@code decode_wcs}: the primary description of {@code store} alone. */
    private static FrameSet decodeWcs(FitsStore store) {
        return FitsWcsRead.decodeWcsInner(
                store,
                store.naxis,
                false,
                false,
                new FitsCardList(),
                ' ',
                new FitsChan(),
                new FitsWarnings(),
                new ArrayList<>());
    }

    @Test
    void decode2dCelestial() {
        FrameSet fs = decodeWcs(celestial2dStore());
        assertEquals(2, fs.getNframe());
        assertEquals(2, fs.getFrame(FrameSet.AST__BASE).getNaxes());
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    @Test
    void decodeCrpixMapsToCrval() {
        // FITS-WCS paper I: the reference pixel CRPIX maps to the reference value CRVAL.
        FrameSet fs = decodeWcs(celestial2dStore());
        double[][] out = fs.tran2(1, new double[] {128.0}, new double[] {128.0}, true);
        assertEquals(Math.toRadians(180.0), out[0][0], 1e-4);
        assertEquals(Math.toRadians(45.0), out[1][0], 1e-4);
    }

    /**
     * With TabOK above zero C's {@code TabMapping} needs the name of the table holding a {@code
     * -TAB} axis's coordinates, PS{@code i}_0, and reports AST__NOFTS without it.
     */
    @Test
    void tabAxisNeedsItsTableName() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "WAVE-TAB";
        FitsChan fc = new FitsChan();
        fc.setI("TabOK", 1);
        AstException e = assertThrows(
                AstException.class,
                () -> FitsWcsRead.decodeWcsInner(
                        store, 1, false, false, new FitsCardList(), ' ', fc, new FitsWarnings(), new ArrayList<>()));
        assertEquals(AstException.AST__NOFTS, e.getStatus());
        assertEquals("astRead(FitsChan): No value can be found for PS1_0 .", e.getMsgText());
    }

    /**
     * With TabOK above zero and the table named, C's {@code GetNamedTable} looks for the table in
     * the FitsChan, which holds none, and reports AST__NOTAB with the extension version and level
     * of PV{@code i}_1 and PV{@code i}_2 (default 1).
     */
    @Test
    void tabAxisNeedsItsTable() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "WAVE-TAB";
        store.ps[0][0] = "WCS-TAB";
        store.pv[0][1] = 3.0;
        FitsChan fc = new FitsChan();
        fc.setI("TabOK", 1);
        AstException e = assertThrows(
                AstException.class,
                () -> FitsWcsRead.decodeWcsInner(
                        store, 1, false, false, new FitsCardList(), ' ', fc, new FitsWarnings(), new ArrayList<>()));
        assertEquals(AstException.AST__NOTAB, e.getStatus());
        assertEquals(
                "astRead(FitsChan): Failed to read FITS binary table from extension 'WCS-TAB' (extver=3,"
                        + " extlevel=1).",
                e.getMsgText());
    }

    /**
     * The reads of {@code -TAB} headers in the probe {@code probes/lutmap_probe.c}: with TabOK set
     * and no tables C's read fails, with the status and message the probe prints, for a spectral
     * axis, an axis of another kind, and an alternate description (IgnoreBadAlt being unset); with
     * TabOK unset an axis of another kind is read as linear.
     */
    @Test
    void tabHeadersReadAsC() {
        Map<String, String> probe = ProbeOutput.load("lutmap_probe");
        String[] common = {
            "NAXIS   = 1", "NAXIS1  = 10", "CRPIX1  = 1", "CDELT1  = 1.0E6", "CTYPE1  = 'WAVE-TAB'", "CRVAL1  = 1.4E9"
        };
        String[][] h1 = {common, {"PS1_0   = 'WCS-TAB'"}};
        String[] other = {
            "NAXIS   = 2",
            "NAXIS1  = 10",
            "NAXIS2  = 10",
            "CRPIX1  = 1",
            "CRPIX2  = 1",
            "CDELT1  = 1.0",
            "CDELT2  = 1.0",
            "CTYPE1  = 'XPOS'",
            "CTYPE2  = 'YPOS-TAB'",
            "CRVAL1  = 0.0",
            "CRVAL2  = 0.0",
            "PS2_0   = 'TABLE2'",
            "PV2_1   = 3",
            "PV2_2   = 2"
        };
        String[] alternate = {
            "NAXIS   = 1",
            "NAXIS1  = 10",
            "CRPIX1  = 1",
            "CDELT1  = 1.0",
            "CTYPE1  = 'XPOS'",
            "CRVAL1  = 0.0",
            "CRPIX1A = 1",
            "CDELT1A = 1.0E6",
            "CTYPE1A = 'WAVE-TAB'",
            "CRVAL1A = 1.4E9",
            "PS1_0A  = 'WCS-TAB'"
        };
        assertEquals(probe.get("tab_spectral"), tabRead(concat(h1), 1));
        assertEquals(probe.get("tab_no_extname"), tabRead(common, 1));
        assertEquals(probe.get("tab_other_extver"), tabRead(other, 1));
        assertEquals(probe.get("tab_other_tabok0"), tabRead(other, 0));
        assertEquals(probe.get("tab_alternate"), tabRead(alternate, 1));
    }

    private static String[] concat(String[][] parts) {
        List<String> all = new ArrayList<>();
        for (String[] p : parts) {
            all.addAll(Arrays.asList(p));
        }
        return all.toArray(new String[0]);
    }

    /** What the probe's {@code tabread} prints for a read of {@code cards} with TabOK {@code tabOk}. */
    private static String tabRead(String[] cards, int tabOk) {
        FitsChan fc = FitsHeaders.chan(cards);
        if (tabOk != 0) {
            fc.setI("TabOK", tabOk);
        }
        AstObject obj;
        try {
            obj = fc.read();
        } catch (AstException e) {
            return "!  AST: Error in routine tabread at line 75 in file lutmap_probe.c.\n!  " + e.getMsgText()
                    + "\nread status " + e.getStatus() + "\n";
        }
        return "nframe " + ((FrameSet) obj).getNframe() + "\n";
    }

    @Test
    void decode1dSpectral() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "FREQ";
        store.crpix[0] = 1.0;
        store.crval[0] = 1.4e9;
        store.cdelt[0] = 1.0e6;
        FrameSet fs = decodeWcs(store);
        assertEquals(2, fs.getNframe());
        assertEquals(1, fs.getFrame(FrameSet.AST__BASE).getNaxes());
        assertEquals(1, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
        // CRPIX maps to CRVAL.
        assertEquals(1.4e9, FitsHeaders.tran(fs, new double[] {1.0}, true)[0], 1.0);
    }

    @Test
    void decode3dMixed() {
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.ctype[2] = "FREQ";
        store.crpix[0] = 128.0;
        store.crpix[1] = 128.0;
        store.crpix[2] = 1.0;
        store.crval[0] = 180.0;
        store.crval[1] = 45.0;
        store.crval[2] = 1.4e9;
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.cdelt[2] = 1.0e6;
        FrameSet fs = decodeWcs(store);
        assertEquals(2, fs.getNframe());
        assertEquals(3, fs.getFrame(FrameSet.AST__BASE).getNaxes());
        assertEquals(3, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    @Test
    void buildPixelToWorldHandlesNonAdjacentCelestialAxes() {
        // FITS axes DEC, WAVE, RA: the celestial pair is separated by the spectral axis.
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "DEC--TAN";
        store.ctype[1] = "WAVE    ";
        store.ctype[2] = "RA---TAN";
        store.crval[0] = 22.0;
        store.crval[1] = 5.0e-7;
        store.crval[2] = 83.6;
        store.crpix[0] = 50.5;
        store.crpix[1] = 25.5;
        store.crpix[2] = 50.5;
        store.cdelt[0] = 2.78e-4;
        store.cdelt[1] = 1.0e-10;
        store.cdelt[2] = -2.78e-4;
        store.cunit[1] = "m";
        FrameSet fs = decodeWcs(store);
        assertEquals(3, fs.getFrame(FrameSet.AST__BASE).getNaxes());
        assertEquals(3, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
        // At the reference pixel, axis 1 is the declination and axis 3 the right ascension.
        double[] out = FitsHeaders.tran(fs, new double[] {50.5, 25.5, 50.5}, true);
        assertEquals(22.0, Math.toDegrees(out[0]), 1e-9);
        assertEquals(5.0e-7, out[1], 1e-20);
        assertEquals(83.6, Math.toDegrees(out[2]), 1e-9);
    }

    @Test
    void logAxisNeedsMathMap() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "X-LOG";
        store.crval[0] = 2.0;
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> decodeWcs(store));
        assertTrue(e.getMessage().endsWith(" needs MathMap"), e.getMessage());
    }

    @Test
    void useCtypeCommentsComparesWithTheCommentsOfEarlierOtherAxesByCount() {
        // fitschan.c WcsOthers (~39262-39290): comms is indexed by axis, but compared at the
        // indices 0 to nother-2. With "other" axes 1 and 2 and the same comment on both, the
        // second is compared with comms[0], which is never filled, so the repeat is missed.
        FitsStore store = new FitsStore(3);
        store.ctypeCom[1] = "Same";
        store.ctypeCom[2] = "Same";
        assertTrue(FitsWcsRead.useCtypeComments(store, new int[] {1, 2}));
        // With the "other" axes first the indices agree and the repeat is found.
        FitsStore first = new FitsStore(3);
        first.ctypeCom[0] = "Same";
        first.ctypeCom[1] = "Same";
        assertFalse(FitsWcsRead.useCtypeComments(first, new int[] {0, 1}));
        // A single "other" axis takes its CTYPE (fitschan.c: if( nother == 1 ) usecom = 0).
        assertFalse(FitsWcsRead.useCtypeComments(first, new int[] {0}));
        // A blank comment stops their use.
        first.ctypeCom[1] = "  ";
        assertFalse(FitsWcsRead.useCtypeComments(first, new int[] {0, 1}));
    }

    @Test
    void ctypeDomainJoinsTheOtherCtypesWithoutLog() {
        // fitschan.c WcsOthers: the CTYPE (default AXIS<n>) with any "-LOG" removed, joined by
        // '-', a blank CTYPE contributing nothing.
        FitsStore store = new FitsStore(4);
        store.ctype[0] = "X-LOG";
        store.ctype[2] = "   ";
        store.ctype[3] = "TIME";
        assertEquals("X-AXIS2-TIME", FitsWcsRead.ctypeDomain(store, new int[] {0, 1, 2, 3}));
        assertNull(FitsWcsRead.ctypeDomain(store, new int[] {2}));
    }

    @Test
    void linearFrameTakesSymbolsLabelsUnitsAndWcsname() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "X";
        store.ctype[1] = "Y";
        store.cname[1] = "Height";
        store.cunit[0] = "m";
        Frame frame = FitsWcsRead.buildWorld(store, null, ' ', new FitsWarnings()).frame;
        assertEquals("X", frame.getSymbol(1));
        assertEquals("X", frame.getLabel(1));
        assertEquals("Height", frame.getLabel(2));
        assertEquals("m", frame.getUnit(1));
        assertEquals("X-Y", frame.getDomain());
        store.wcsname = "Plane";
        frame = FitsWcsRead.buildWorld(store, null, ' ', new FitsWarnings()).frame;
        assertEquals("PLANE", frame.getDomain());
        assertEquals("Plane coordinates", frame.getTitle());
    }

    @Test
    void joinPixelAxesGivesExtraWcsAxesZero() {
        // fitschan.c AddFrame: extra WCS axes take the constant 0.0 (the default CRPIX).
        Mapping unit = new UnitMap(3);
        Mapping joined = FitsWcsRead.joinPixelAxes(2, unit);
        assertEquals(2, joined.getNin());
        assertEquals(3, joined.getNout());
        double[][] out = joined.tranP(1, 2, new double[][] {{5.0}, {6.0}}, true, 3);
        assertEquals(Arrays.asList(5.0, 6.0, 0.0), Arrays.asList(out[0][0], out[1][0], out[2][0]));
        assertSame(unit, FitsWcsRead.joinPixelAxes(3, unit));
    }
}
