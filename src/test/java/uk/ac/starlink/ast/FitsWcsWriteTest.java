package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link FitsWcsWrite}, C's {@code WcsFromStore}, and the comment rules of C's {@code SetValue}
 * and {@code SetFits} it writes its cards with ({@link FitsCardList#setValueAsWriter}). The cards
 * expected are those a C probe of the pinned library wrote ({@code p1.c} beside the task report),
 * or are worked from {@code WcsFromStore}'s code where a store is written directly.
 */
class FitsWcsWriteTest {

    /** A card as C lists it: 80 characters, blank-padded. */
    private static String pad(String card) {
        StringBuilder b = new StringBuilder(card);
        while (b.length() < 80) {
            b.append(' ');
        }
        return b.toString();
    }

    private static List<String> padded(String... cards) {
        List<String> out = new ArrayList<>();
        for (String c : cards) {
            out.add(pad(c));
        }
        return out;
    }

    /** The probe's FrameSet: {@code n} GRID axes shifted by 5, 6 and 7 to a Frame with {@code attrs}. */
    private static FrameSet plain(int n, String attrs) {
        Frame grid = new Frame(n);
        grid.setDomain("GRID");
        Frame wcs = new Frame(n);
        wcs.set(attrs);
        FrameSet fs = new FrameSet(grid);
        fs.addFrame(FrameSet.AST__BASE, new ShiftMap(Arrays.copyOf(new double[] {5, 6, 7}, n)), wcs);
        return fs;
    }

    /** The cards of {@code fs} written FITS-WCS into a FitsChan holding {@code cards}. */
    private static List<String> written(FrameSet fs, String... cards) {
        FitsChan fc = FitsHeaders.chan(cards);
        fc.setEncoding("FITS-WCS");
        assertEquals(1, fc.write(fs));
        return FitsHeaders.foundCards(fc);
    }

    /**
     * A card replaced keeps its comment when C's {@code Similar} finds the values alike, and
     * {@code Similar} does not compare the last character that is not blank (C-library quirk).
     */
    @Test
    void similarIgnoresTheLastCharacter() {
        List<String> rest = padded(
                "WCSAXES =                    2 / Number of WCS axes",
                "CRPIX1  =                  1.0 / Reference pixel on axis 1",
                "CRPIX2  =                  1.0 / Reference pixel on axis 2",
                "CRVAL1  =                  6.0 / Value at ref. pixel on axis 1",
                "CRVAL2  =                  7.0 / Value at ref. pixel on axis 2",
                "CTYPE1  = 'ABCD1   '           / Type of co-ordinate on axis 1", // codespell:ignore
                "CTYPE2  = 'ABCD2   '           / Type of co-ordinate on axis 2", // codespell:ignore
                "CDELT1  =                  1.0 / Pixel size on axis 1",
                "CDELT2  =                  1.0 / Pixel size on axis 2");
        List<String> want = new ArrayList<>();
        want.add(pad("WCSNAME = 'ABCD    '           / old comment"));
        want.addAll(rest);
        assertEquals(want, written(plain(2, "Domain=ABCD"), "WCSNAME = 'ABCX'  / old comment"));
        want.set(0, pad("WCSNAME = 'ABCD    '           / Reference name for the coord. frame"));
        assertEquals(want, written(plain(2, "Domain=ABCD"), "WCSNAME = 'ABXD'  / old comment"));

        assertTrue(FitsCardList.similar("RA---TAN", "ra---tab"));
        assertTrue(FitsCardList.similar("  A  B ", "A B"));
        assertFalse(FitsCardList.similar("ABCD", "ABC"));
        assertFalse(FitsCardList.similar("ABCD", ""));
        assertTrue(FitsCardList.similar("  ", ""));
    }

    /** For one axis, WCSNAME is left out when {@code Similar} finds it like CTYPE1. */
    @Test
    void oneAxisWcsnameLeftOutWhenLikeCtype() {
        assertEquals(
                padded(
                        "WCSAXES =                    1 / Number of WCS axes",
                        "CRPIX1  =                  1.0 / Reference pixel on axis 1",
                        "CRVAL1  =                  6.0 / Value at ref. pixel on axis 1",
                        "CTYPE1  = 'ABCD    '           / Type of co-ordinate on axis 1", // codespell:ignore
                        "CDELT1  =                  1.0 / Pixel size on axis 1"),
                written(plain(1, "Domain=ABCX,Symbol(1)=ABCD")));
        assertEquals(
                padded(
                        "WCSAXES =                    1 / Number of WCS axes",
                        "WCSNAME = 'ABXD    '           / Reference name for the coord. frame",
                        "CRPIX1  =                  1.0 / Reference pixel on axis 1",
                        "CRVAL1  =                  6.0 / Value at ref. pixel on axis 1",
                        "CTYPE1  = 'ABCD    '           / Type of co-ordinate on axis 1", // codespell:ignore
                        "CDELT1  =                  1.0 / Pixel size on axis 1"),
                written(plain(1, "Domain=ABXD,Symbol(1)=ABCD")));
    }

    /**
     * A card replaced keeps its comment when its value, converted to the type of the new value as
     * C's {@code CnvValue} converts it, is the same; a value that cannot be converted does not.
     * The new cards go after the last card describing WCS.
     */
    @Test
    void aReplacedCardKeepsItsCommentWhenTheConvertedValueIsTheSame() {
        assertEquals(
                padded(
                        "CRPIX1  =                  1.0 / old int comment",
                        "CRVAL1  =                  6.0 / old crval comment",
                        "CDELT1  =                  1.0 / Pixel size on axis 1",
                        "WCSAXES =                    1 / Number of WCS axes",
                        "WCSNAME = 'ABCD    '           / Reference name for the coord. frame",
                        "CTYPE1  = 'ABCD1   '           / Type of co-ordinate on axis 1"), // codespell:ignore
                written(
                        plain(1, "Domain=ABCD"),
                        "CRPIX1  = 1 / old int comment",
                        "CRVAL1  = 6 / old crval comment",
                        "CDELT1  = 'X' / old string comment"));
    }

    /** A store with the keywords common to every axis type, for two axes. */
    private static FitsStore store() {
        FitsStore st = new FitsStore(2);
        st.crpix[0] = 100.0;
        st.crpix[1] = 100.0;
        st.crval[0] = 180.0;
        st.crval[1] = 0.0;
        st.ctype[0] = "RA---TAN";
        st.ctype[1] = "DEC--TAN";
        st.cdelt[0] = -0.001;
        st.cdelt[1] = 0.001;
        return st;
    }

    private static List<String> keywords(FitsChan fc) {
        List<String> out = new ArrayList<>();
        for (String card : FitsHeaders.foundCards(fc)) {
            out.add(card.substring(0, 8).trim());
        }
        return out;
    }

    /**
     * The AST extension keywords and the observation-wide keywords, in C's order: the alternate
     * description has the version letter on each keyword but IMAGFREQ, and no OBSGEO or date of
     * observation. (Rust {@code primary_plane_emits_ast_extension_keywords} and {@code
     * alt_plane_emits_suffixed_versions_and_no_obsgeo}, whose IMAGFREQ follows C here.)
     */
    @Test
    void extensionKeywordsAndVersionLetters() {
        FitsPlaneStores stores = new FitsPlaneStores(2);
        for (char s : new char[] {' ', 'A'}) {
            FitsStore st = stores.version(s, 2);
            FitsStore src = store();
            st.crpix = src.crpix;
            st.crval = src.crval;
            st.ctype = src.ctype;
            st.cdelt = src.cdelt;
            st.wcsname = "MYDOMAIN";
            st.obsgeox = 1234567.0;
            st.obsgeoy = 2345678.0;
            st.obsgeoz = 3456789.0;
            st.ssyssrc = "LSRK";
            st.zsource = 0.025;
            st.imagfreq = 2.3e11;
            st.skyrefis = "ORIGIN";
            st.skyref[0] = 1.5;
            st.skyref[1] = 0.25;
            st.skyrefp[0] = 1.6;
            st.skyrefp[1] = 0.5;
            st.mjdobs = 51544.5;
        }
        FitsChan fc = new FitsChan();
        assertTrue(fromStore(fc, stores, "astWrite", "FrameSet"));
        assertEquals(
                Arrays.asList(
                        "WCSAXES",
                        "WCSNAME",
                        "CRPIX1",
                        "CRPIX2",
                        "CRVAL1",
                        "CRVAL2",
                        "CTYPE1",
                        "CTYPE2",
                        "CDELT1",
                        "CDELT2",
                        "SREFIS",
                        "SREF1",
                        "SREF2",
                        "SREFP1",
                        "SREFP2",
                        "MJD-OBS",
                        "DATE-OBS",
                        "SSYSSRC",
                        "ZSOURCE",
                        "IMAGFREQ",
                        "OBSGEO-X",
                        "OBSGEO-Y",
                        "OBSGEO-Z",
                        "WCSAXESA",
                        "WCSNAMEA",
                        "CRPIX1A",
                        "CRPIX2A",
                        "CRVAL1A",
                        "CRVAL2A",
                        "CTYPE1A",
                        "CTYPE2A",
                        "CDELT1A",
                        "CDELT2A",
                        "SREFISA",
                        "SREF1A",
                        "SREF2A",
                        "SREFP1A",
                        "SREFP2A",
                        "SSYSSRCA",
                        "ZSOURCEA"),
                keywords(fc));
        assertEquals("2000-01-01T12:00:00.000", fc.getFitsS("DATE-OBS"));
    }

    /**
     * A version without CRPIX1 is not written, and the versions after it still are; one lacking
     * a CTYPE on an axis is removed whole; and none is written if the primary one is not.
     */
    @Test
    void incompleteVersionsAreNotWritten() {
        FitsPlaneStores stores = new FitsPlaneStores(2);
        stores.version(' ', 2);
        copyInto(stores.get(' '), store());
        FitsStore a = stores.version('A', 2);
        copyInto(a, store());
        a.crpix[0] = null;
        FitsStore b = stores.version('B', 2);
        copyInto(b, store());
        b.ctype[1] = null;
        FitsStore c = stores.version('C', 2);
        copyInto(c, store());
        FitsChan fc = new FitsChan();
        assertTrue(fromStore(fc, stores, "astWrite", "FrameSet"));
        List<String> kw = keywords(fc);
        assertTrue(kw.contains("CRPIX1C"), kw.toString());
        for (String k : kw) {
            assertFalse(k.endsWith("A") || k.endsWith("B"), k);
        }

        stores.get(' ').crval[0] = null;
        FitsChan none = new FitsChan();
        assertFalse(fromStore(none, stores, "astWrite", "FrameSet"));
        assertEquals(0, none.getNcard());
    }

    /**
     * {@link FitsWcsWrite#wcsFromStore} with the cards it adds marked new, as C's {@code Write}
     * marks them while it runs; the marks are then cleared.
     */
    private static boolean fromStore(FitsChan fc, FitsPlaneStores stores, String method, String cls) {
        fc.cards().beginMarkingNew();
        try {
            return FitsWcsWrite.wcsFromStore(fc, stores, method, cls);
        } finally {
            fc.cards().fixNew(FitsCardList.NewScope.WRITE, false);
            fc.cards().endMarkingNew();
        }
    }

    private static void copyInto(FitsStore st, FitsStore src) {
        st.crpix = src.crpix;
        st.crval = src.crval;
        st.ctype = src.ctype;
        st.cdelt = src.cdelt;
    }

    /**
     * With CDMatrix the CD matrix is written, each PC row times its CDELT and zero elements left
     * out; without it, CDELT and the PC elements other than the unit matrix's. A TPN projection is
     * written TAN, its parameters (and every other axis's) as QV.
     */
    @Test
    void matrixFormsAndTpn() {
        FitsPlaneStores stores = new FitsPlaneStores(2);
        FitsStore st = stores.version(' ', 2);
        copyInto(st, store());
        st.ctype[0] = "RA---TPN";
        st.ctype[1] = "DEC--TPN";
        st.pc[0][0] = 1.0;
        st.pc[0][1] = 0.5;
        st.pc[1][0] = 0.0;
        st.pv[1][1] = 0.25;
        FitsChan fc = new FitsChan();
        fc.setCDMatrix(true);
        assertTrue(fromStore(fc, stores, "astWrite", "FrameSet"));
        assertEquals(
                Arrays.asList(
                        "WCSAXES", "CRPIX1", "CRPIX2", "CRVAL1", "CRVAL2", "CTYPE1", "CTYPE2", "CD1_1", "CD1_2",
                        "CD2_2", "QV2_1"),
                keywords(fc));
        assertEquals("RA---TAN", fc.getFitsS("CTYPE1"));
        assertEquals(-0.0005, fc.getFitsF("CD1_2"), 0.0);

        FitsChan pc = new FitsChan();
        pc.setCDMatrix(false);
        assertTrue(fromStore(pc, stores, "astWrite", "FrameSet"));
        assertEquals(
                Arrays.asList(
                        "WCSAXES", "CRPIX1", "CRPIX2", "CRVAL1", "CRVAL2", "CTYPE1", "CTYPE2", "CDELT1", "CDELT2",
                        "PC1_2", "QV2_1"),
                keywords(pc));
    }

    /**
     * The SIP coefficients, in order of the powers, each table followed by its order, the highest
     * sum of the powers; the coefficients of an alternate version carry its letter, but the order
     * keyword does not (C-library quirk).
     */
    @Test
    void sipCoefficientsAndOrders() {
        FitsPlaneStores stores = new FitsPlaneStores(2);
        FitsStore st = stores.version(' ', 2);
        copyInto(st, store());
        FitsStore a = stores.version('A', 2);
        copyInto(a, store());
        a.sipA = FitsStore.setItem(a.sipA, 0, 2, 1e-6);
        a.sipA = FitsStore.setItem(a.sipA, 2, 1, 2e-7);
        a.sipBp = FitsStore.setItem(a.sipBp, 1, 0, 3e-5);
        FitsChan fc = new FitsChan();
        assertTrue(fromStore(fc, stores, "astWrite", "FrameSet"));
        List<String> kw = keywords(fc);
        int i = kw.indexOf("A_0_2A");
        assertEquals(Arrays.asList("A_0_2A", "A_2_1A", "A_ORDER", "BP_1_0A", "BP_ORDER"), kw.subList(i, i + 5));
        assertEquals(3, fc.getFitsI("A_ORDER"));
        assertEquals(1, fc.getFitsI("BP_ORDER"));
    }
}
