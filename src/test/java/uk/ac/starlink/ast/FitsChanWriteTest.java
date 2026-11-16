package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Deferred;
import uk.ac.starlink.ast.testsupport.OracleSection;

/**
 * Writing FrameSets as FITS-WCS through {@link FitsChan#write}: what a failed write leaves, where
 * the cards of a successful one go, that the cards read back transform as the FrameSet written,
 * also for FITS-PC, FITS-IRAF, FITS-AIPS and FITS-AIPS++, and that writes in several threads
 * agree. The cards expected come from C probes of the pinned library (fitschan.c {@code Write},
 * {@code FitsFromStore}, {@code WcsFromStore}, {@code PCFromStore}, {@code IRAFFromStore}, {@code
 * AIPSFromStore}, {@code AIPSPPFromStore}).
 */
class FitsChanWriteTest {

    /** The cards {@code fc} holds, consumed or not, as {@code findFits} lists them, and its current card. */
    private static String state(FitsChan fc) {
        int card = fc.getCard();
        String cards = String.join("\n", FitsHeaders.foundCards(fc));
        fc.setCard(card);
        return "card " + card + "\n" + cards;
    }

    /** A 1-D FrameSet whose world axis is {@code c0 + c1 p + c2 p^2} of the pixel axis. */
    private static FrameSet quadratic(double c0, double c1, double c2) {
        PolyMap pm = new PolyMap(1, 1, 3, new double[] {c0, 1, 0, c1, 1, 1, c2, 1, 2}, 0, new double[0]);
        pm.set("IterInverse=1");
        Frame pixel = new Frame(1);
        pixel.setDomain("GRID");
        Frame wcs = new Frame(1);
        wcs.setDomain("WORLD");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, pm, wcs);
        return fs;
    }

    /** The FrameSet a TAN header with 100 by 100 pixels reads as, with {@code extra} cards. */
    private static FrameSet tan(String... extra) {
        List<String> cards = new ArrayList<>(Arrays.asList(
                "NAXIS   = 2",
                "NAXIS1  = 100",
                "NAXIS2  = 100",
                "CRPIX1  = 50",
                "CRPIX2  = 50",
                "CRVAL1  = 10",
                "CRVAL2  = 20",
                "CDELT1  = -0.001",
                "CDELT2  = 0.001",
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "RADESYS = 'ICRS'"));
        cards.addAll(Arrays.asList(extra));
        return FitsHeaders.readFrameSet(FitsHeaders.chan(cards.toArray(new String[0])));
    }

    /** The cards a FITS-WCS write of {@link #tan(String...)} with no extra cards adds (C probe). */
    private static final List<String> TAN_CARDS = Arrays.asList(
            "WCSAXES =                    2 / Number of WCS axes                             ",
            "CRPIX1  =                 50.0 / Reference pixel on axis 1                      ",
            "CRPIX2  =                 50.0 / Reference pixel on axis 2                      ",
            "CRVAL1  =                 10.0 / Value at ref. pixel on axis 1                  ",
            "CRVAL2  =                 20.0 / Value at ref. pixel on axis 2                  ",
            "CTYPE1  = 'RA---TAN'           / Type of co-ordinate on axis 1                  ", // codespell:ignore
            "CTYPE2  = 'DEC--TAN'           / Type of co-ordinate on axis 2                  ", // codespell:ignore
            "CDELT1  =               -0.001 / Pixel size on axis 1                           ",
            "CDELT2  =                0.001 / Pixel size on axis 2                           ",
            "RADESYS = 'ICRS    '           / Reference frame for RA/DEC values              ");

    private static String card(String keyword, String value) {
        return String.format("%-8s= %20s", keyword, value) + String.format("%50s", "");
    }

    @Test
    void aFailedWriteLeavesTheCardsAsTheyWere() {
        // C probe: a non-linear axis whose CRVAL is zero cannot be described, so write returns 0,
        // and the cards and the current card are as they were.
        FitsChan fc = FitsHeaders.chan(
                "NAXIS   = 1", "NAXIS1  = 100", "CRVAL1  = 3.0 / a WCS card", "DDD     = 4", "EEE     = 5");
        fc.setEncoding("FITS-WCS");
        fc.setCard(3);
        String before = state(fc);
        assertEquals(0, fc.write(quadratic(-25, 0, 0.01)));
        assertEquals(before, state(fc));
        assertEquals(3, fc.getCard());
        assertEquals(5, fc.getNcard());
    }

    @Test
    void strictWriteFailsAndLeavesTheCards() {
        // A FrameSet with an MJD-OBS written to a FitsChan holding TIMESYS = 'ET' raises a badval
        // warning, which Strict makes an error (C probe, FitsChanAstwarnTest).
        FrameSet fs = tan("MJD-OBS =                55000");
        FitsChan fc = FitsHeaders.chan("AAA     = 1", "TIMESYS = 'ET'", "CCC     = 3", "DDD     = 4", "EEE     = 5");
        fc.setEncoding("FITS-WCS");
        fc.set("Strict=1,Warnings=badval");
        fc.setCard(3);
        String before = state(fc);
        AstException e = assertThrows(AstException.class, () -> fc.write(fs));
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertEquals(before, state(fc));
        assertEquals(3, fc.getCard());
    }

    /**
     * C's {@code FitsFromStore} puts the cards after the last card {@code FindWcs} recognises as
     * describing WCS, whichever card is current, or at the end if there is none; the write then
     * leaves the FitsChan at end-of-file (C probe).
     */
    @Test
    void cardsAreWrittenBeforeTheCurrentCard() {
        String a = card("EPOCH", "2000.0");
        String b = card("BBB", "2");
        String c = card("CCC", "3");
        List<String> want = new ArrayList<>();
        want.add(a);
        want.addAll(TAN_CARDS);
        want.add(b);
        want.add(c);
        for (int current : new int[] {2, 3}) {
            FitsChan fc = FitsHeaders.chan(a, b, c);
            fc.setEncoding("FITS-WCS");
            fc.setCard(current);
            assertEquals(1, fc.write(tan()));
            assertEquals(14, fc.getCard(), "end-of-file");
            assertEquals(want, FitsHeaders.foundCards(fc), "card " + current + " current");
        }

        // With no card describing WCS the cards go at the end.
        String aaa = card("AAA", "1");
        FitsChan fc = FitsHeaders.chan(aaa, b, c);
        fc.setEncoding("FITS-WCS");
        fc.setCard(2);
        assertEquals(1, fc.write(tan()));
        List<String> atEnd = new ArrayList<>(Arrays.asList(aaa, b, c));
        atEnd.addAll(TAN_CARDS);
        assertEquals(atEnd, FitsHeaders.foundCards(fc));
    }

    /**
     * Written into the FitsChan it was read from, a FrameSet's new cards go in front of the card
     * after the last card describing WCS though the read consumed that card (here WCSAXES): C's
     * {@code GetCDMatrix} saves and restores the current card itself, not its index among the
     * cards not consumed (Rust c-library-quirks.md, upstream 35f3a372; C probe {@code p6.c}).
     */
    @Test
    void cardsGoInFrontOfAConsumedCurrentCard() {
        FitsChan fc = FitsHeaders.chan(
                "NAXIS   = 2",
                "NAXIS1  = 100",
                "NAXIS2  = 100",
                "CRPIX1  = 50",
                "CRPIX2  = 50",
                "CRVAL1  = 10",
                "CRVAL2  = 20",
                "CDELT1  = -0.001",
                "CDELT2  = 0.001",
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "RADESYS = 'ICRS'",
                "WCSAXES = 2",
                "AAA     = 1");
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        fs.set("Epoch=MJD 55000");
        fc.setEncoding("FITS-WCS");
        fc.clear("Card");
        assertEquals(1, fc.write(fs));
        List<String> want =
                new ArrayList<>(Arrays.asList(card("NAXIS", "2"), card("NAXIS1", "100"), card("NAXIS2", "100")));
        for (String t : TAN_CARDS.subList(1, 5)) {
            want.add(t);
        }
        want.add(TAN_CARDS.get(7));
        want.add(TAN_CARDS.get(8));
        want.add(TAN_CARDS.get(5));
        want.add(TAN_CARDS.get(6));
        want.add(TAN_CARDS.get(9));
        want.add("MJD-OBS =   54999.999233975963 / Modified Julian Date of observation            ");
        want.add("DATE-OBS= '2009-06-17T23:58:53.816' / Date of observation                       ");
        want.add(card("AAA", "1"));
        assertTrue(
                FitsHeaders.cardsMatchC(FitsHeaders.foundCards(fc), want),
                String.join("\n", FitsHeaders.foundCards(fc)));
    }

    /** The outcome of a header whose write returns 0. */
    private static final String WRITE_FAILS = "the write returns 0";

    /** The outcome of a header whose cards read back do not transform as C's read of the header. */
    private static final String DIFFERS = "the cards read back transform differently";

    /**
     * The headers that do not round-trip, with their outcome, which C's gives too (C probes {@code
     * p3.c} for FITS-WCS and {@code rt.c} for the other encodings, beside the task reports): C's
     * write returns 0 (for FITS-IRAF, a description with a LONPOLE or projection parameters, or a
     * TNX or ZPX projection read as TPN or ZPN), or C's cards read back give values where C's read
     * of the header gives BAD (the CAR headers whose longitude range the projection bounds, among
     * them the FITS-AIPS header cmap_3years_GP_D2) or other values (scp, at a point 1.3E-6 radians
     * from the pole, where longitude changes fast), or C's write reaches {@code LogAxis}, which
     * makes a MathMap, for the description of absolute coordinates of an offset SkyFrame whose
     * Domain is not {@code SKY}.
     */
    private static final Map<String, String> NOT_ROUND_TRIPPED = notRoundTripped();

    private static Map<String, String> notRoundTripped() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String h : Arrays.asList(
                "programs/joye_car_headers/CHIPASS_Equ.head",
                "wcsconv/inputs/car6.head",
                "wcsconv/inputs/tsc.head",
                "wcsconv/inputs/iraf-longpole.head",
                "wcsconv/inputs/projp-nopv0.head",
                "wcsconv/inputs/projp-pv.head",
                "wcsconv/inputs/projp-pv0only.head",
                "wcsconv/inputs/projp.head",
                "wcsconv/inputs/tnx-cheb.head",
                "wcsconv/inputs/tnx.head",
                "wcsconv/inputs/zpx.head")) {
            m.put(h, WRITE_FAILS);
        }
        for (String h : Arrays.asList(
                "programs/joye_car_headers/car1.head",
                "programs/joye_car_headers/car2.head",
                "programs/joye_car_headers/cmap_3years_GP_D2.head",
                "wcsconv/inputs/car1.head",
                "wcsconv/inputs/car2.head",
                "wcsconv/inputs/scp.head")) {
            m.put(h, DIFFERS);
        }
        for (String h : Arrays.asList(
                "wcsconv/inputs/offset-sky-pole.head",
                "wcsconv/inputs/offset-sky.head",
                "wcsconv/inputs/offset-sref.head",
                "wcsconv/inputs/skyref-alt.head",
                "wcsconv/inputs/skyref.head")) {
            m.put(h, "needs MathMap");
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * What a header gives when its FrameSet is written with {@code encoding} into a new FitsChan
     * and read back: null if the FrameSet read back transforms the inputs of the header's forward
     * section to the outputs C recorded, within the section's tolerances, and otherwise {@link
     * #WRITE_FAILS}, {@link #DIFFERS} or the class the write needs.
     */
    private static String roundTrip(
            OracleSection s,
            FrameSet fs,
            String encoding,
            TransformOracleTest.Overrides overrides,
            Map<String, TransformOracleTest.ExcusedRows> excused) {
        FitsChan out = new FitsChan();
        out.setEncoding(encoding);
        try {
            if (out.write(fs) != 1) {
                return WRITE_FAILS;
            }
        } catch (UnsupportedOperationException e) {
            return FitsHeaders.outOfScope(e);
        }
        out.clear("Card");
        FrameSet back = FitsHeaders.readFrameSet(out);
        double[][] live = back.tranP(s.npoint(), s.nin, s.inputs(), true, s.nout);
        List<TransformOracleTest.AxisMiss> misses = new ArrayList<>();
        if (!overrides.goldenOff(s.path, true)) {
            misses = TransformOracleTest.compareGolden(
                    TransformOracleTest.CheckKind.GOLDEN_FWD,
                    live,
                    s.outputs(),
                    TransformOracleTest.angularFlags(back, s),
                    overrides.goldenSkipRows(s.path, true),
                    s.rtol,
                    s.atol);
        }
        TransformOracleTest.ExcusedRows listed = excused.get(TransformOracleTest.sectionKey(s.path, true));
        if (listed != null) {
            misses = TransformOracleTest.excuseRows(misses, listed.rows).misses;
        }
        return misses.isEmpty() ? null : DIFFERS;
    }

    /** The encodings whose headers {@link #everyInScopeHeaderRoundTrips} writes and reads back. */
    private static final Set<String> ROUND_TRIP_ENCODINGS =
            new LinkedHashSet<>(Arrays.asList("FITS-WCS", "FITS-PC", "FITS-IRAF", "FITS-AIPS", "FITS-AIPS++"));

    /**
     * Every FITS-WCS, FITS-PC, FITS-IRAF, FITS-AIPS and FITS-AIPS++ header of {@code headers.oracle}
     * whose sections match C here, read, written with its own encoding into a new FitsChan and read back, transforms the
     * inputs of its forward section to the outputs C recorded, within the section's tolerances,
     * unless it is in {@link #NOT_ROUND_TRIPPED} with the outcome it gives, which is checked.
     */
    @Test
    void everyInScopeHeaderRoundTrips() {
        Map<String, String> deferredHeaders = Deferred.load("headers");
        TransformOracleTest.Overrides overrides = TransformOracleTest.committedOverrides();
        Map<String, TransformOracleTest.ExcusedRows> excused = TransformOracleTest.committedOracleRows();
        Set<String> seen = new LinkedHashSet<>();
        List<String> failed = new ArrayList<>();
        int roundTripped = 0;
        for (OracleSection s : TransformOracleTest.parseOracle("headers")) {
            if (!s.forward || !s.path.endsWith(".head") || deferredHeaders.containsKey(s.path) || !seen.add(s.path)) {
                continue;
            }
            FitsChan in = FitsHeaders.fixture(s.path);
            String encoding = in.getEncoding();
            if (!ROUND_TRIP_ENCODINGS.contains(encoding)) {
                continue;
            }
            String got = roundTrip(s, FitsHeaders.readFrameSet(in), encoding, overrides, excused);
            String want = NOT_ROUND_TRIPPED.get(s.path);
            if (got == null && want == null) {
                roundTripped++;
            } else if (got == null || !got.equals(want)) {
                failed.add(s.path + " gives \"" + got + "\", listed as \"" + want + "\"");
            }
        }
        for (String h : NOT_ROUND_TRIPPED.keySet()) {
            if (!seen.contains(h)) {
                failed.add(h + " is listed but is not an in-scope header of headers.oracle of those encodings");
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
        assertTrue(roundTripped > 0, "headers round-tripped");
    }

    // MODERN-JAVA(19): ExecutorService is AutoCloseable; try-with-resources would replace shutdown.
    @SuppressWarnings("PMD.CloseResource") // shut down in the finally block, as Java 8 allows
    @Test
    void threadsWriteCopiesIdentically() throws Exception {
        FitsChan in = FitsHeaders.fixture("wcsconv/inputs/sip.head");
        FrameSet fs = FitsHeaders.readFrameSet(in);
        int nthread = 4;
        ExecutorService pool = Executors.newFixedThreadPool(nthread);
        try {
            List<Future<List<String>>> results = new ArrayList<>();
            for (int i = 0; i < nthread; i++) {
                FrameSet copy = (FrameSet) fs.copy();
                Callable<List<String>> task = () -> {
                    FitsChan out = new FitsChan();
                    out.setEncoding("FITS-WCS");
                    assertEquals(1, out.write(copy));
                    return FitsHeaders.foundCards(out);
                };
                results.add(pool.submit(task));
            }
            List<String> first = results.get(0).get();
            assertNotNull(first);
            assertTrue(first.size() > 10, "cards written");
            for (Future<List<String>> r : results) {
                assertEquals(first, r.get());
            }
        } finally {
            pool.shutdown();
        }
    }
}
