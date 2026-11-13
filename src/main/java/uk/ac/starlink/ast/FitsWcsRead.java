package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * The FITS-WCS read of a FitsChan: the FrameSet its cards describe in the FITS-WCS encoding (C's
 * {@code FitsToStore} and {@code FsetFromStore} for that encoding).
 *
 * <p>The read translates the header ({@code SpecTrans}), sweeps the WCS cards as C's {@code
 * WcsFcRead} does, extracts every axis description into a {@link FitsStore} and makes from them a
 * FrameSet whose base Frame describes pixel coordinates and whose current Frame describes the
 * primary axis description, with a Frame for each alternate description. For each description the
 * Mapping from pixel to world coordinates is built as C's {@code WcsMapFrm} builds it: the pixel
 * to intermediate world coordinate Mapping, then one Mapping over every axis for each class of
 * axis (celestial, spectral, the others) and any {@code -TAB} look-ups, all in series.
 *
 * <p>The Frames and Mappings of spectral axes (SpecFrame), of {@code -TAB} axes when TabOK is
 * above zero (LutMap) and of non-spectral {@code -LOG} axes (MathMap) are not available: a header
 * using them makes the read throw {@link UnsupportedOperationException}.
 *
 * <p>Ported from Rust {@code fitschan/wcs_read.rs} and the FITS-WCS branch of {@code
 * fitschan/mod.rs}'s {@code read}.
 */
final class FitsWcsRead {

    /** Seconds per day, C's {@code SPD}. */
    private static final double SPD = 86400.0;

    /** The method C's read reports warnings and errors from. */
    private static final String METHOD = "astRead";

    private FitsWcsRead() {}

    // ------------------------------------------------------------------
    // The read (fitschan/mod.rs)
    // ------------------------------------------------------------------

    /**
     * The FrameSet the cards of {@code fc} describe in the FITS-WCS encoding, or null when they
     * describe none (C's {@code Read} for a foreign encoding, fitschan.c:26007, through {@code
     * FitsToStore} and {@code FsetFromStore}).
     *
     * <p>The cards the read uses are marked as provisionally used, and the warnings it raises are
     * stored as {@code ASTWARN} cards. {@link FitsChan#read} then makes the marked cards used, or,
     * when no FrameSet is made and Clean is not set, available again (C's {@code FixUsed}).
     *
     * @throws AstException as C's read reports it
     * @throws UnsupportedOperationException if the header has a spectral axis, a {@code -TAB} axis
     *     with TabOK above zero, or a non-spectral {@code -LOG} axis
     */
    static FrameSet read(FitsChan fc) {
        FitsCardList cards = fc.cards();
        boolean defB1950 = fc.getDefB1950();
        // SpecTrans works on a copy of the cards (C's temporary FitsChan), whose warnings are
        // stored on the FitsChan itself.
        int entry = cards.position();
        FitsSpecTrans.TransWarnings transWarnings = new FitsSpecTrans.TransWarnings();
        FitsCardList trans = FitsSpecTrans.specTrans(
                cards, FitsChan.Encoding.FITS_WCS, transAttrs(fc), transWarnings, fc.warnList(METHOD));
        // The cards SpecTrans used, which C marks before WcsFcRead runs.
        List<String> specNames = trans.takeConsumedNames();
        // C's WcsFcRead sweeps the cards before the FrameSet is made, and makes nothing when the
        // sweep fails.
        AstException swept = sweepForeign(fc, entry, transWarnings, trans);
        FitsWarnings readWarnings = fc.warnList(METHOD);
        List<int[]> cursors = new ArrayList<>();
        FrameSet decoded = null;
        AstException decodeError = null;
        if (swept == null) {
            try {
                decoded = decodeWcsStores(fc, trans, defB1950, cursors, readWarnings);
            } catch (AstException e) {
                decodeError = e;
            }
        }
        return finishForeignRead(fc, decoded, decodeError, swept, readWarnings, cursors, trans, specNames);
    }

    /** The attributes the header translations of a read depend on. */
    private static FitsSpecTrans.TransAttrs transAttrs(FitsChan fc) {
        FitsSpecTrans.TransAttrs attrs = new FitsSpecTrans.TransAttrs();
        attrs.carLin = fc.getCarLin();
        attrs.polyTan = fc.getPolyTan();
        return attrs;
    }

    /**
     * Reads the FrameSet a foreign encoding describes, as C reads it ({@code FitsToStore} and
     * {@code FsetFromStore}): extracts every axis description from {@code trans}, the header with
     * {@code SpecTrans}'s translations, and makes a FrameSet from them. {@code cursors} receives,
     * for each axis description read, the index in {@code warnings} of its first warning and its
     * number of WCS axes (see {@link #pushReadWarnings}).
     */
    private static FrameSet decodeWcsStores(
            FitsChan fc, FitsCardList trans, boolean defB1950, List<int[]> cursors, FitsWarnings warnings) {
        FitsWcsKeywords.WcsStores stores = FitsWcsKeywords.extractWcsKeywordsWithAlts(trans, fc.getFitsRounding());
        boolean sipReplace = fc.getSipReplace();
        boolean carLin = fc.getCarLin();
        if (stores.primary != null) {
            stores.primary.sipReplace = sipReplace;
            stores.primary.carLin = carLin;
        }
        for (FitsStore st : stores.alts) {
            st.sipReplace = sipReplace;
            st.carLin = carLin;
        }
        return decodeWcsWithAltsInner(
                stores, defB1950, fc.getIgnoreBadAlt() != 0, fc.getIwc(), fc.cards(), fc.getTabOK(), warnings, cursors);
    }

    /**
     * C's {@code WcsFcRead} sweep, after the warnings {@code SpecTrans} raised are stored: flags
     * every WCS card whose value converts as provisionally used, warning in front of each card it
     * cannot convert. All three steps are made; the first error, if any, is returned rather than
     * thrown. It is the error C's read fails with at a card with no value, or, with Strict in
     * force, at the first warning the Warnings attribute names.
     */
    private static AstException sweepForeign(
            FitsChan fc, int entry, FitsSpecTrans.TransWarnings transWarnings, FitsCardList trans) {
        List<String> translated = trans.takeWrittenNames();
        AstException transIssued = null;
        try {
            pushTransWarnings(fc, entry, transWarnings);
        } catch (AstException e) {
            transIssued = e;
        }
        FitsWarnings sweepPolicy = fc.warnList(METHOD);
        FitsWcsMark.SweepResult swept = FitsWcsMark.markWcsCards(fc.cards(), translated, sweepPolicy);
        AstException sweepIssued = null;
        try {
            pushSweepWarnings(fc, swept.warnings);
        } catch (AstException e) {
            sweepIssued = e;
        }
        if (transIssued != null) {
            return transIssued;
        }
        return swept.error != null ? swept.error : sweepIssued;
    }

    /**
     * The end of the read, mirroring what C's {@code Read} does before its {@code FixUsed}, once
     * {@link #sweepForeign} has given {@code swept} (null when it succeeded) and, when it
     * succeeded, the FrameSet has been made ({@code decoded}) or has failed ({@code
     * decodeError}).
     *
     * <p>The cards used are marked as provisionally used: those SpecTrans used ({@code
     * specNames}), and the other names the extraction recorded on {@code trans}, each marking
     * every matching card as C's {@code MarkAllCards} does; the cards the sweep marked are marked
     * already. When the sweep failed, the extraction's names are not marked, since C's read
     * stopped before it. {@link FitsChan#read} then calls {@code FixUsed}: the marks become uses
     * when a FrameSet is returned or Clean is set, and are removed otherwise, so that with a
     * failure and Clean unset no card is used.
     *
     * <p>The warnings raised before an error are kept, as C's {@code Warn} stores each as it is
     * raised, and they are stored before {@code FixUsed}, as in C. C's sweep runs before the
     * object is made, so when it fails only the header translations have warned, and its error
     * is the one reported. With no pixel axes C's {@code MakeFitsFrameSet} gives nothing, which
     * {@code Read} treats as a failure without an error.
     */
    private static FrameSet finishForeignRead(
            FitsChan fc,
            FrameSet decoded,
            AstException decodeError,
            AstException swept,
            FitsWarnings readWarnings,
            List<int[]> cursors,
            FitsCardList trans,
            List<String> specNames) {
        // A keyword WcsFcRead reads is consumed card by card by the sweep, only where its value
        // converts.
        List<String> extractNames = new ArrayList<>();
        for (String name : trans.takeConsumedNames()) {
            if (!FitsWcsMark.isWcsKeyword(name)) {
                extractNames.add(name);
            }
        }
        FitsCardList cards = fc.cards();
        for (String name : specNames) {
            cards.markAllCards(name);
        }
        if (swept != null) {
            throw swept;
        }
        for (String name : extractNames) {
            cards.markAllCards(name);
        }
        pushReadWarnings(fc, readWarnings, cursors);
        if (decodeError != null) {
            throw decodeError;
        }
        return decoded;
    }

    /**
     * Stores the {@code ASTWARN} blocks {@code SpecTrans} raised, those raised before its {@code
     * QV} scan in front of the card at {@code entry}, where the read started, and the rest after
     * the last card: the scan walks off the end of the list and C's {@code InsCard} appends when
     * there is no current card (fitschan.c:18892-18903).
     */
    private static void pushTransWarnings(FitsChan fc, int entry, FitsSpecTrans.TransWarnings warnings) {
        if (fc.getClean()) {
            return;
        }
        FitsCardList cards = fc.cards();
        if (!warnings.entry.isEmpty()) {
            cards.setPosition(entry);
            for (FitsWarnings.Warning w : warnings.entry) {
                fc.issueWarning(w, METHOD);
            }
        }
        if (!warnings.end.isEmpty()) {
            cards.setPosition(cards.len());
            for (FitsWarnings.Warning w : warnings.end) {
                fc.issueWarning(w, METHOD);
            }
        }
    }

    /**
     * Stores the {@code ASTWARN} blocks {@code WcsFcRead}'s sweep raised, each in front of the
     * card at its index, where C's cursor is when it warns. Each element of {@code warnings} is
     * {@code {Integer cardIndex, FitsWarnings.Warning}}.
     */
    private static void pushSweepWarnings(FitsChan fc, List<Object[]> warnings) {
        if (fc.getClean()) {
            return;
        }
        FitsCardList cards = fc.cards();
        int added = 0;
        for (Object[] item : warnings) {
            int index = (Integer) item[0];
            int before = cards.len();
            cards.setPosition(index + added);
            fc.issueWarning((FitsWarnings.Warning) item[1], METHOD);
            added += cards.len() - before;
        }
    }

    /**
     * Stores the {@code ASTWARN} blocks the decoding raised, each in front of the card current in
     * C when it was raised (see {@link #decodeWcsWithAltsInner} for {@code cursors}). C's {@code
     * DistortMaps} reads NAXIS1 to NAXIS{@code n} as each axis description is read, {@code n} its
     * number of WCS axes, leaving the cursor {@link #moveCursorToLastNaxis} reproduces; a cursor
     * of -1 is end-of-file.
     */
    private static void pushReadWarnings(FitsChan fc, FitsWarnings warnings, List<int[]> cursors) {
        if (fc.getClean()) {
            return;
        }
        FitsCardList cards = fc.cards();
        boolean haveCurrent = false;
        int current = 0;
        List<FitsWarnings.Warning> list = warnings.list();
        for (int index = 0; index < list.size(); index++) {
            int cursor = 0;
            for (int k = cursors.size() - 1; k >= 0; k--) {
                if (cursors.get(k)[0] <= index) {
                    cursor = cursors.get(k)[1];
                    break;
                }
            }
            if (!haveCurrent || current != cursor) {
                if (cursor >= 0) {
                    moveCursorToLastNaxis(cards, cursor);
                } else {
                    cards.setPosition(cards.len());
                }
                current = cursor;
                haveCurrent = true;
            }
            fc.issueWarning(list.get(index), METHOD);
        }
    }

    /**
     * Looks up NAXIS1 to NAXIS{@code naxes} on {@code cards} as C's {@code DistortMaps}'s opening
     * loop does (fitschan.c:8903-8907), from the end-of-file cursor {@code WcsFcRead}'s card loop
     * leaves. The values reach the FitsStore through the extraction; what this reproduces is the
     * cursor: {@code astGetFitsF} finds its card with {@code SearchCard}, which makes it current,
     * and does not reinstate the original card. The card left current, NAXIS{@code naxes}, or
     * end-of-file when there is none, is where C's {@code Warn} then inserts any {@code ASTWARN}
     * block.
     */
    private static void moveCursorToLastNaxis(FitsCardList cards, int naxes) {
        cards.setPosition(cards.len());
        for (int i = 1; i <= naxes; i++) {
            cards.searchCard("NAXIS" + i, "astGetFitsF");
        }
    }

    // ------------------------------------------------------------------
    // FrameSet from the axis descriptions (fitschan/wcs_read.rs)
    // ------------------------------------------------------------------

    /**
     * The Frame of an axis description and the Mapping to it from pixel coordinates, as C's
     * {@code WcsMapFrm} returns them. With the Iwc attribute set, {@link #frame} is a FrameSet
     * whose base Frame is the WCS Frame and whose current Frame describes intermediate world
     * coordinates, and {@link #map} goes from pixel to intermediate world coordinates.
     */
    private static final class MapFrm {
        /** The Mapping from pixel coordinates. */
        final Mapping map;

        /** The Frame (or FrameSet). */
        final Frame frame;

        MapFrm(Mapping map, Frame frame) {
            this.map = map;
            this.frame = frame;
        }
    }

    /**
     * Makes the FrameSet of the primary and alternate axis descriptions of a header, mirroring C's
     * {@code FsetFromStore} (fitschan.c:12094): the pixel Frame, Domain GRID, is the base Frame,
     * and a Frame is added for the primary axis description, if there is one, and for each
     * alternate description, each derived from the pixel Frame. The primary WCS Frame, or the
     * pixel Frame when there is no primary description, is current. Each WCS Frame's Ident is its
     * version character (a space for the primary description). C's {@code TidyOffsets} then
     * removes any Frames of sky offset coordinates that duplicate absolute ones.
     *
     * <p>With {@code ignoreBadAlt}, an alternate description that cannot be read is left out with
     * a {@code badalt} warning, as C's {@code AddFrame} clears the error {@code WcsMapFrm}
     * reports. Each warning raised is added to {@code warnings}. For each axis description read,
     * {@code cursors} receives {@code {index in warnings of its first warning, number of WCS
     * axes}}, which decide where C writes those warnings.
     *
     * @param cards the FitsChan's own cards, from which C reads RFVAL and NAXIS
     * @param tabOk the FitsChan's TabOK attribute
     * @return the FrameSet, or null with no pixel axes, when C's {@code FsetFromStore} makes
     *     nothing
     */
    static FrameSet decodeWcsWithAltsInner(
            FitsWcsKeywords.WcsStores stores,
            boolean defB1950,
            boolean ignoreBadAlt,
            boolean iwc,
            FitsCardList cards,
            int tabOk,
            FitsWarnings warnings,
            List<int[]> cursors) {
        int npix = stores.npix;
        if (npix == 0) {
            return null;
        }
        FrameSet ret = new FrameSet(pixelFrame(npix));
        int pixel = ret.getCurrent();
        if (stores.primary != null) {
            cursors.add(new int[] {warnings.size(), stores.primary.naxis});
            MapFrm mf = wcsMapFrm(stores.primary, defB1950, iwc, cards, ' ', tabOk, warnings, cursors);
            addFrame(ret, pixel, npix, mf);
        }
        int physical = ret.getCurrent();
        for (int k = 0; k < stores.alts.size(); k++) {
            char letter = stores.altLetters.get(k);
            FitsStore alt = stores.alts.get(k);
            cursors.add(new int[] {warnings.size(), alt.naxis});
            MapFrm mf;
            try {
                mf = wcsMapFrm(alt, defB1950, iwc, cards, letter, tabOk, warnings, cursors);
            } catch (AstException e) {
                if (!ignoreBadAlt) {
                    throw e;
                }
                warnings.push(new FitsWarnings.Warning(
                        "badalt", "Ignoring unusable FITS-WCS alternate axes label '" + letter + "'."));
                continue;
            }
            addFrame(ret, pixel, npix, mf);
        }
        ret.setBase(pixel);
        ret.setCurrent(physical);
        tidyOffsets(ret);
        return ret;
    }

    /**
     * The FrameSet of one axis description, {@code s} (a space for the primary description): the
     * pixel Frame, with {@code npix} axes, as base Frame, and the WCS Frame, current, as C's
     * {@code FsetFromStore} makes it for the primary description. See {@link
     * #decodeWcsWithAltsInner} for the other parameters.
     */
    static FrameSet decodeWcsInner(
            FitsStore store,
            int npix,
            boolean defB1950,
            boolean iwc,
            FitsCardList cards,
            char s,
            int tabOk,
            FitsWarnings warnings,
            List<int[]> cursors) {
        MapFrm mf = wcsMapFrm(store, defB1950, iwc, cards, s, tabOk, warnings, cursors);
        FrameSet fs = new FrameSet(pixelFrame(npix));
        addFrame(fs, 1, npix, mf);
        fs.setBase(1);
        return fs;
    }

    /**
     * The Mapping from pixel coordinates and the Frame of axis description {@code s}, C's {@code
     * WcsMapFrm} (fitschan.c:38172). The Mapping is {@code ((((map1 ; map2) ; map3) ; map4) ;
     * tabmap)}, simplified: {@code map1} from pixel to intermediate world coordinates ({@code
     * WcsIntWorld}), then a Mapping over every axis for the celestial axes ({@code
     * WcsCelestial}), the spectral axes ({@code WcsSpectral}) and the other axes ({@code
     * WcsOthers}), and the look-ups of any {@code -TAB} axes. The Frame's Ident is {@code s} and
     * it takes the DUT1 (in days) and DTAI of the description.
     *
     * <p>The store is copied first, as {@code DistortMaps} removes any distortion code from the
     * CTYPE values it holds, and every later step reads the shortened form.
     */
    private static MapFrm wcsMapFrm(
            FitsStore store0,
            boolean defB1950,
            boolean iwc,
            FitsCardList cards,
            char s,
            int tabOk,
            FitsWarnings warnings,
            List<int[]> cursors) {
        FitsStore store = store0.copy();
        tabMapping(store, tabOk);
        Mapping map1 = FitsWcsMatrix.buildPixelToIwc(store, cards, warnings, cursors);
        FitsWcsCelestial.CelestialResult celestial = FitsWcsCelestial.buildCelestialWithDefB1950(
                store, s, defB1950, readRfval(cards, store.naxis, s), warnings);
        buildSpectral(store);
        Frame frm = buildWorldFrame(store, celestial);
        String[] iwcUnits = iwc ? iwcAxisUnits(store, celestial) : null;
        Mapping ret = buildPixelToWorld(store, map1, celestial).simplify();
        frm.setIdent(String.valueOf(s));
        if (store.dut1 != null) {
            frm.setDut1(store.dut1 * SPD);
        }
        if (store.dtai != null) {
            frm.setDtai(store.dtai);
        }
        if (!iwc) {
            return new MapFrm(ret, frm);
        }
        // The IWC Frame and the FrameSet holding it with the WCS Frame as base Frame. C inverts
        // ret, makes the CmpMap, which records the inversion, and inverts ret back.
        int wcsaxes = store.wcsaxes != null ? store.wcsaxes : store.naxis;
        Frame iwcfrm = new Frame(wcsaxes);
        iwcfrm.setTitle("FITS Intermediate World Coordinates");
        iwcfrm.setDomain("IWC" + s);
        for (int i = 0; i < iwcUnits.length; i++) {
            if (iwcUnits[i] != null) {
                iwcfrm.setUnit(i + 1, iwcUnits[i]);
            }
        }
        FrameSet fs = new FrameSet(frm);
        ret.invert();
        Mapping map9 = new CmpMap(ret, map1, true);
        ret.invert();
        Mapping map10 = map9.simplify();
        fs.addFrame(FrameSet.AST__BASE, map10, iwcfrm);
        return new MapFrm(map1, fs);
    }

    /**
     * Reads RFVAL{@code i}{@code s} for each of the {@code naxis} axes off {@code cards}, unmarked,
     * as C's {@code WcsCelestial} reads it with {@code GetValue} (fitschan.c:36554-36557): C has
     * no FitsStore member for it and reads it from the FitsChan where it is used.
     */
    private static Double[] readRfval(FitsCardList cards, int naxis, char s) {
        Double[] rfval = new Double[naxis];
        for (int i = 0; i < naxis; i++) {
            rfval[i] = cards.getFloat(FitsWcsKeywords.keyFor("RFVAL", i + 1, s));
        }
        return rfval;
    }

    /**
     * C's {@code TabMapping} (fitschan.c:33898), which makes the look-up Mapping of every {@code
     * -TAB} axis when TabOK is above zero; with TabOK at most zero it makes none and the axes are
     * read as linear.
     *
     * @throws UnsupportedOperationException for a {@code -TAB} axis with TabOK above zero, whose
     *     look-up needs LutMap
     */
    private static void tabMapping(FitsStore store, int tabOk) {
        if (tabOk <= 0) {
            return;
        }
        for (int iaxis = 0; iaxis < store.naxis; iaxis++) {
            String ctype = store.ctype[iaxis];
            if (ctype != null && ctype.length() > 4 && ctype.startsWith("-TAB", 4)) {
                throw new UnsupportedOperationException(
                        "FitsChan: the -TAB axis " + (iaxis + 1) + " (CTYPE '" + ctype + "') needs LutMap");
            }
        }
    }

    /**
     * C's {@code WcsSpectral} (fitschan.c:39913), which describes each axis whose CTYPE names a
     * spectral system with a SpecFrame.
     *
     * @throws UnsupportedOperationException for the first such axis, since that needs SpecFrame
     */
    private static void buildSpectral(FitsStore store) {
        for (int axis = 0; axis < store.naxis; axis++) {
            String ctype = store.ctype[axis];
            if (ctype != null && FitsStore.isSpectralCtype(ctype)) {
                throw new UnsupportedOperationException(
                        "FitsChan: the spectral axis " + (axis + 1) + " (CTYPE '" + ctype + "') needs SpecFrame");
            }
        }
    }

    /**
     * The pixel Frame of a FITS-WCS read, with {@code npix} axes: Title "Pixel Coordinates",
     * Domain GRID and each axis labelled "Pixel axis n" (fitschan.c:12183-12187).
     */
    private static Frame pixelFrame(int npix) {
        Frame frame = new Frame(npix);
        frame.setTitle("Pixel Coordinates");
        frame.setDomain("GRID");
        for (int i = 0; i < npix; i++) {
            frame.setLabel(i + 1, "Pixel axis " + (i + 1));
        }
        return frame;
    }

    /**
     * Joins a Mapping whose inputs are an axis description's WCS axes to the {@code npix} pixel
     * axes, as C's {@code AddFrame} does (fitschan.c:2835-2862): a PermMap passes the axes they
     * share, gives extra WCS axes the value 0, the default CRPIX, and drops extra pixel axes.
     * {@code map} itself is returned when the numbers agree.
     */
    static Mapping joinPixelAxes(int npix, Mapping map) {
        int nwcs = map.getNin();
        if (nwcs == npix) {
            return map;
        }
        int[] inperm = new int[npix];
        for (int i = 0; i < npix; i++) {
            inperm[i] = i < nwcs ? i + 1 : -1;
        }
        int[] outperm = new int[nwcs];
        for (int i = 0; i < nwcs; i++) {
            outperm[i] = i < npix ? i + 1 : -1;
        }
        PermMap pmap = new PermMap(npix, inperm, nwcs, outperm, new double[] {0.0});
        return new CmpMap(pmap, map, true);
    }

    /**
     * Adds the Frame of an axis description, derived from the pixel Frame {@code pixel} of {@code
     * fset}, which has {@code npix} axes, and makes the WCS Frame current: C's {@code AddFrame}
     * (fitschan.c:2730) once {@code WcsMapFrm} has succeeded. With the Iwc attribute set the
     * Frame is a FrameSet, whose Frames are all added, the WCS Frame first.
     */
    private static void addFrame(FrameSet fset, int pixel, int npix, MapFrm mf) {
        Mapping mapping = joinPixelAxes(npix, mf.map);
        int nf = fset.getNframe();
        fset.addFrame(pixel, mapping, mf.frame);
        fset.setCurrent(nf + 1);
    }

    /**
     * The units C gives the axes of the IWC Frame: degrees for the celestial axes ({@code
     * WcsCelestial}, fitschan.c:36605-36606) and any CUNIT for the others ({@code WcsOthers},
     * fitschan.c:39350-39353); a null element leaves the Unit unset.
     */
    private static String[] iwcAxisUnits(FitsStore store, FitsWcsCelestial.CelestialResult celestial) {
        String[] units = new String[store.naxis];
        for (int i = 0; i < store.naxis; i++) {
            if (celestial != null && (celestial.lonAxis == i || celestial.latAxis == i)) {
                units[i] = "deg";
            } else {
                units[i] = store.cunit[i];
            }
        }
        return units;
    }

    /**
     * The pixel to world coordinate Mapping of an axis description, before simplification: as
     * C's {@code WcsMapFrm} builds it (fitschan.c:38335-38341), the following stages in series,
     * in their C shapes, each over every axis:
     *
     * <ol>
     *   <li>{@code map1}, pixel to IWC ({@code WcsIntWorld});
     *   <li>the celestial Mapping, or a UnitMap when there are no celestial axes ({@code
     *       WcsCelestial});
     *   <li>one UnitMap per axis in parallel ({@code WcsSpectral}, which makes a UnitMap for each
     *       axis that is not spectral, and no spectral axis reaches here);
     *   <li>a UnitMap when every axis is celestial, and otherwise one Mapping per axis in
     *       parallel: {@link #othersAxisMap} for an axis no other stage describes and a UnitMap for
     *       the others ({@code WcsOthers}).
     * </ol>
     *
     * <p>No {@code -TAB} look-up Mapping reaches here (see {@link #tabMapping}).
     */
    private static Mapping buildPixelToWorld(
            FitsStore store, Mapping map1, FitsWcsCelestial.CelestialResult celestial) {
        int naxis = store.naxis;
        boolean[] claimed = new boolean[naxis];
        if (celestial != null) {
            claimed[celestial.lonAxis] = true;
            claimed[celestial.latAxis] = true;
        }
        boolean otherAxis = false;
        for (boolean c : claimed) {
            if (!c) {
                otherAxis = true;
                break;
            }
        }
        Mapping map2 = celestial != null ? celestial.mapping : new UnitMap(naxis);
        Mapping map3 = null;
        for (int i = 0; i < naxis; i++) {
            Mapping part = new UnitMap(1);
            map3 = map3 == null ? part : new CmpMap(map3, part, false);
        }
        Mapping map4;
        if (otherAxis) {
            map4 = null;
            for (int i = 0; i < naxis; i++) {
                Mapping part = claimed[i] ? new UnitMap(1) : othersAxisMap(store, i);
                map4 = map4 == null ? part : new CmpMap(map4, part, false);
            }
        } else {
            map4 = new UnitMap(naxis);
        }
        Mapping map5 = new CmpMap(map1, map2, true);
        Mapping map6 = new CmpMap(map5, map3, true);
        return new CmpMap(map6, map4, true);
    }

    /**
     * The IWC to WCS Mapping C's {@code WcsOthers} makes for an axis no other stage describes
     * (fitschan.c:39355-39368): {@code LogWcs} for a CTYPE ending in {@code -LOG}, and otherwise
     * {@code LinearWcs}, a ShiftMap adding CRVAL, or a UnitMap when CRVAL is zero or absent. A
     * {@code -LOG} axis with a zero CRVAL has no logarithmic form, and {@code WcsOthers} gives it
     * a UnitMap.
     *
     * @throws UnsupportedOperationException for a {@code -LOG} axis with a non-zero CRVAL, whose
     *     Mapping needs MathMap
     */
    private static Mapping othersAxisMap(FitsStore store, int axis) {
        double crval = store.crval[axis] != null ? store.crval[axis] : 0.0;
        String ctype = store.ctype[axis] != null ? store.ctype[axis] : "AXIS" + (axis + 1);
        if (ctype.length() > 3 && ctype.endsWith("-LOG")) {
            if (crval != 0.0) {
                throw new UnsupportedOperationException(
                        "FitsChan: the logarithmic axis " + (axis + 1) + " (CTYPE '" + ctype + "') needs MathMap");
            }
            return new UnitMap(1);
        }
        return crval != 0.0 ? new ShiftMap(new double[] {crval}) : new UnitMap(1);
    }

    /**
     * The WCS Frame of an axis description, as C's {@code WcsMapFrm} makes it from a Frame with
     * an axis for each WCS axis, which {@code WcsCelestial} restructures to hold the SkyFrame of
     * any celestial axes and {@code WcsOthers} completes for the other axes.
     *
     * <ul>
     *   <li>With no celestial axes, a Frame whose axes carry the CTYPE, CNAME and CUNIT values,
     *       its Domain made of the CTYPE values, and any WCSNAME as Domain and in the Title.
     *   <li>With only celestial axes, the SkyFrame.
     *   <li>Otherwise a CmpFrame of the other axes, picked from such a Frame, and the SkyFrame,
     *       its axes permuted back to the FITS order; the WCSNAME names the CmpFrame.
     * </ul>
     *
     * <p>The SkyFrame takes the reference position as SkyRef (see {@link #wcsCelestialFrame}).
     */
    static Frame buildWorldFrame(FitsStore store, FitsWcsCelestial.CelestialResult celestial) {
        int naxis = store.naxis;
        boolean[] claimed = new boolean[naxis];
        if (celestial != null) {
            claimed[celestial.lonAxis] = true;
            claimed[celestial.latAxis] = true;
        }
        int nother = 0;
        for (boolean c : claimed) {
            if (!c) {
                nother++;
            }
        }
        int[] all = new int[naxis];
        int[] others = new int[nother];
        int j = 0;
        for (int i = 0; i < naxis; i++) {
            all[i] = i;
            if (!claimed[i]) {
                others[j++] = i;
            }
        }
        // The Frame of every axis, carrying what WcsOthers sets on the axes it describes; the
        // celestial stage replaces the axes it describes.
        Frame frame = seedLinearFrame(store, all, others);
        if (celestial != null) {
            frame = wcsCelestialFrame(frame, celestial, naxis);
        }
        // WcsOthers sets the WCSNAME only when it describes some axes.
        if (nother > 0) {
            applyWcsname(store, frame);
        }
        return frame;
    }

    /**
     * C's {@code WcsCelestial} from the point where it has made the SkyFrame (fitschan.c:36535-
     * 36686): the reference position is stored as the SkyFrame's SkyRef, with SkyRefIs Ignored,
     * when SkyRef is not set; the other axes of {@code frame} are picked into a Frame, which with
     * the SkyFrame makes a CmpFrame, or the SkyFrame alone is used when there are none; and the
     * axes are permuted to put longitude and latitude back at their FITS axes. A reference
     * position from AXREF is then stored whether or not SkyRef is set.
     *
     * <p>C makes the SkyFrame with longitude as its first axis and permutes it only when it is
     * the whole Frame. The SkyFrame the celestial stage gives has latitude first when latitude
     * comes first in the FITS axes, so its axes are permuted back first.
     *
     * <p>C stores the AXREF position, on axes 1 and 2 of the SkyFrame, after the permutation; so
     * when the SkyFrame is the whole Frame and latitude comes first, its latitude takes the
     * reference longitude and its longitude the reference latitude (fitschan.c:36683-36685).
     */
    private static Frame wcsCelestialFrame(Frame frame, FitsWcsCelestial.CelestialResult celestial, int naxes) {
        SkyFrame sfrm = celestial.frame;
        int axlon = celestial.lonAxis;
        int axlat = celestial.latAxis;
        int[] paxis = new int[1];
        sfrm.primaryFrame(1, paxis);
        if (paxis[0] != 1) {
            sfrm.permAxes(new int[] {2, 1});
        }
        double[] ref = celestial.refPos;
        if (ref != null
                && !celestial.refPosForced
                && !sfrm.testSkyRef(1)
                && ref[0] != AstObject.AST__BAD
                && ref[1] != AstObject.AST__BAD) {
            sfrm.setSkyRef(1, ref[0]);
            sfrm.setSkyRef(2, ref[1]);
            sfrm.setSkyRefIs("Ignored");
        }
        int[] axes = new int[naxes];
        int j = 0;
        for (int i = 0; i < naxes; i++) {
            if (i != axlat && i != axlon) {
                axes[j++] = i + 1;
            }
        }
        Frame result;
        if (j == 0) {
            result = sfrm;
        } else {
            Frame ofrm = frame.pickAxes(j, axes, null);
            result = new CmpFrame(ofrm, sfrm);
        }
        j = 0;
        for (int i = 0; i < naxes; i++) {
            if (i == axlat) {
                axes[i] = naxes;
            } else if (i == axlon) {
                axes[i] = naxes - 1;
            } else {
                axes[i] = ++j;
            }
        }
        result.permAxes(axes);
        if (ref != null && celestial.refPosForced) {
            sfrm.setSkyRef(1, ref[0]);
            sfrm.setSkyRef(2, ref[1]);
            sfrm.setSkyRefIs("Ignored");
        }
        return result;
    }

    /** C's {@code WcsOthers} ending: a WCSNAME keyword names the whole Frame, as its Domain and in its Title. */
    private static void applyWcsname(FitsStore store, Frame frame) {
        if (store.wcsname != null) {
            frame.setDomain(store.wcsname);
            frame.setTitle(store.wcsname + " coordinates");
        }
    }

    /**
     * The CTYPE value C's {@code WcsOthers} works from for one axis: the keyword value, {@code
     * AXIS<n>} when there is none, with any trailing {@code -LOG} removed (fitschan.c:39295-
     * 39315). C uses it for the axis Symbol, as the Label fallback, and in the Frame's Domain.
     */
    private static String othersAxisCtype(FitsStore store, int axis) {
        String ctype = store.ctype[axis] != null ? store.ctype[axis] : "AXIS" + (axis + 1);
        int len = ctype.length();
        if (len > 3 && ctype.endsWith("-LOG")) {
            return ctype.substring(0, len - 4);
        }
        return ctype;
    }

    /**
     * The Domain of the Frame holding the axes no class stage describes, C's {@code WcsOthers}
     * (fitschan.c:39316-39327, 39377-39383): those axes' CTYPE values, as {@link #othersAxisCtype}
     * gives them, joined by '-', each axis whose CTYPE keyword value is blank contributing
     * nothing; or null, so the Domain is cleared, when none contributes. As in C, the value is cut
     * to 499 characters after each join.
     */
    static String ctypeDomain(FitsStore store, int[] axes) {
        StringBuilder newdom = null;
        for (int axis : axes) {
            String raw = store.ctype[axis] != null ? store.ctype[axis] : "AXIS" + (axis + 1);
            if (FitsCard.chrLen(raw) == 0) {
                continue;
            }
            String buf2 = othersAxisCtype(store, axis);
            if (newdom == null) {
                newdom = new StringBuilder(buf2);
            } else {
                newdom.append('-').append(buf2);
            }
            if (newdom.length() > 499) {
                newdom.setLength(499);
            }
        }
        return newdom == null || FitsCard.chrLen(newdom.toString()) == 0 ? null : newdom.toString();
    }

    /**
     * Whether C's {@code WcsOthers} labels the axes no class stage describes ({@code others},
     * ascending) with the comments of their CTYPE cards (fitschan.c:39262-39290, 39297): only when
     * there is more than one such axis, each has a non-blank comment, and no comment repeats an
     * earlier one.
     *
     * <p>C keeps the comments in an array indexed by axis but compares each with the entries at
     * the indices of the earlier other axes counted from zero, which are the same axes only when
     * the other axes come first. The comparison here is C's.
     */
    static boolean useCtypeComments(FitsStore store, int[] others) {
        int naxes = store.ctypeCom.length;
        String[] comms = new String[naxes];
        boolean usecom = true;
        int nother = 0;
        for (int i = 0; i < naxes; i++) {
            if (!contains(others, i)) {
                continue;
            }
            nother++;
            String com = store.ctypeCom[i];
            if (com == null || FitsCard.chrLen(com) == 0) {
                usecom = false;
            } else {
                for (int j = 0; j < nother - 1; j++) {
                    if (comms[j] != null && comms[j].equals(com)) {
                        usecom = false;
                        break;
                    }
                }
            }
            if (usecom) {
                comms[i] = com;
            }
        }
        return usecom && nother > 1;
    }

    /**
     * A Frame over the FITS axes {@code axes} (from 0), each axis carrying what C's {@code
     * WcsOthers} sets (fitschan.c:39328-39353): its Symbol from {@link #othersAxisCtype}, its
     * Label from CNAME, else the CTYPE comment as {@link #useCtypeComments} decides, else the
     * Symbol, and its Unit from any CUNIT. {@code domainAxes}, the axes no class stage describes,
     * make the Domain ({@link #ctypeDomain}) and decide the use of the comments.
     */
    private static Frame seedLinearFrame(FitsStore store, int[] axes, int[] domainAxes) {
        boolean usecom = useCtypeComments(store, domainAxes);
        Frame frame = new Frame(axes.length);
        for (int k = 0; k < axes.length; k++) {
            int axis = axes[k];
            String ctype = othersAxisCtype(store, axis);
            frame.setSymbol(k + 1, ctype);
            String label = store.cname[axis];
            if (label == null && usecom && contains(domainAxes, axis)) {
                label = store.ctypeCom[axis];
            }
            frame.setLabel(k + 1, label != null ? label : ctype);
            if (store.cunit[axis] != null) {
                frame.setUnit(k + 1, store.cunit[axis]);
            }
        }
        String domain = ctypeDomain(store, domainAxes);
        if (domain != null) {
            frame.setDomain(domain);
        }
        return frame;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * C's {@code TidyOffsets} (fitschan.c:34898) on a FrameSet just read. A Frame whose SkyFrame
     * has Domain SKY_POLE or SKY_ORIGIN, as {@code WcsSkyFrame} marks one, is an absolute Frame
     * whose SkyRefIs is set, through the FrameSet, to Pole or Origin; one with Domain SKY is
     * absolute; one with Domain SKY_OFFSETS describes offsets. When there are both absolute and
     * offset Frames, the offset Frames are removed, and the absolute Frames marked SKY_POLE or
     * SKY_ORIGIN have their Ident and their SkyFrame's Domain cleared; if the current Frame is
     * removed, the first of those Frames reached, counting down, becomes current.
     */
    static void tidyOffsets(FrameSet fset) {
        int icurr = fset.getCurrent();
        boolean icurrIsOffset = false;
        boolean hasabs = false;
        boolean hasoff = false;
        int nfrm = fset.getNframe();
        int[] pax = new int[1];
        for (int ifrm = 1; ifrm <= nfrm; ifrm++) {
            String skyrefis = null;
            Frame frm = fset.getFrame(ifrm);
            int naxes = frm.getNaxes();
            for (int iax = 0; iax < naxes; iax++) {
                Frame pfrm = frm.primaryFrame(iax + 1, pax);
                if (pfrm instanceof SkyFrame) {
                    String dom = pfrm.getDomain();
                    if ("SKY_OFFSETS".equals(dom)) {
                        hasoff = true;
                        if (ifrm == icurr) {
                            icurrIsOffset = true;
                        }
                        break;
                    } else if ("SKY".equals(dom)) {
                        hasabs = true;
                        break;
                    } else if ("SKY_POLE".equals(dom)) {
                        hasabs = true;
                        skyrefis = "POLE";
                        break;
                    } else if ("SKY_ORIGIN".equals(dom)) {
                        hasabs = true;
                        skyrefis = "ORIGIN";
                        break;
                    }
                }
            }
            if (skyrefis != null) {
                fset.setCurrent(ifrm);
                fset.setC("SkyRefIs", skyrefis);
                fset.setCurrent(icurr);
            }
        }
        if (hasabs && hasoff) {
            for (int ifrm = nfrm; ifrm > 0; ifrm--) {
                boolean remove = false;
                Frame frm = fset.getFrame(ifrm);
                int naxes = frm.getNaxes();
                for (int iax = 0; iax < naxes; iax++) {
                    Frame pfrm = frm.primaryFrame(iax + 1, pax);
                    if (pfrm instanceof SkyFrame) {
                        String dom = pfrm.getDomain();
                        if ("SKY_OFFSETS".equals(dom)) {
                            remove = true;
                            break;
                        } else if ("SKY_POLE".equals(dom) || "SKY_ORIGIN".equals(dom)) {
                            frm.clear("Ident");
                            pfrm.clearDomain();
                            if (icurrIsOffset) {
                                fset.setCurrent(ifrm);
                                icurrIsOffset = false;
                            }
                            break;
                        }
                    }
                }
                if (remove) {
                    fset.removeFrame(ifrm);
                }
            }
        }
    }
}
