package uk.ac.starlink.ast;

/**
 * A compound spectrum/flux Frame. A SpecFluxFrame combines a SpecFrame and a FluxFrame into a
 * single two-dimensional compound Frame. Such a Frame can, for instance, describe a plot of a
 * spectrum in which the first axis represents spectral position and the second axis represents
 * flux.
 *
 * <p>A SpecFluxFrame is a CmpFrame of the two, and behaves as one, except that its default Title
 * is the FluxFrame's Label, then {@code versus} and the SpecFrame's Label (as {@code Flux density
 * versus Frequency}), and that when it is aligned with another SpecFluxFrame the flux values are
 * converted as the spectral positions they are densities in change, as well as with the flux
 * System and Unit. Where C converts between SpecFluxFrames whose SpecFrames or FluxFrames differ
 * it builds Mappings this library does not have (a RateMap, a MathMap and a TranMap), and such a
 * conversion throws {@link UnsupportedOperationException}. So does a conversion between a
 * SpecFluxFrame and a FluxFrame or SpecFrame that do not match directly (a FluxFrame in another
 * flux System, say): C then casts the CmpFrame its match gives into a SpecFluxFrame and converts
 * between SpecFluxFrames. SpecFluxFrames with equal components, and those C finds no conversion
 * between, behave as in C.
 *
 * <p>Ported from C {@code specfluxframe.c}, with Rust {@code frames/specfluxframe.rs}.
 */
public class SpecFluxFrame extends CmpFrame {

    /**
     * Creates a SpecFluxFrame. The SpecFrame and FluxFrame are held, not copied, as by {@link
     * CmpFrame#CmpFrame}: later changes to them change the SpecFluxFrame.
     *
     * @param frame1 SpecFrame which will form the first axis in the new SpecFluxFrame
     * @param frame2 FluxFrame which will form the second axis in the new SpecFluxFrame
     */
    public SpecFluxFrame(SpecFrame frame1, FluxFrame frame2) {
        super(frame1, frame2);
    }

    /**
     * Creates a SpecFluxFrame of any two Frames, marking them shared if {@code share}, for {@link
     * CmpFrame#castCopy}: C's {@code astCastCopy} of a CmpFrame into a SpecFluxFrame keeps the
     * CmpFrame's components whatever their classes. Such a SpecFluxFrame is used only as the
     * template of a match against a SpecFluxFrame, which reads its axes and attributes, not its
     * SpecFrame and FluxFrame.
     */
    SpecFluxFrame(Frame frame1, Frame frame2, boolean share) {
        super(frame1, frame2, share);
    }

    /**
     * Loads a SpecFluxFrame from its description, as C's {@code astLoadSpecFluxFrame} does: as the
     * CmpFrame it is, with no values of its own.
     *
     * @param node the description
     * @throws AstException with status {@code AST__LDERR} if the first component is not a SpecFrame
     *     or the second not a FluxFrame
     */
    SpecFluxFrame(NativeNode node) {
        super(node);
        node.section("SpecFluxFrame");
        checkLoadedComponents(this);
    }

    /**
     * Checks that a loaded SpecFluxFrame holds a SpecFrame and a FluxFrame. C keeps components of
     * any class; here they must be the classes this class reads them as.
     *
     * @throws AstException with status {@code AST__LDERR} if they are not
     */
    private static void checkLoadedComponents(SpecFluxFrame sff) {
        Frame frame1 = sff.firstFrame();
        Frame frame2 = sff.secondFrame();
        if (!(frame1 instanceof SpecFrame) || !(frame2 instanceof FluxFrame)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadSpecFluxFrame(SpecFluxFrame): The components of a SpecFluxFrame are a "
                            + frame1.astClass() + " and a " + frame2.astClass()
                            + ", not a SpecFrame and a FluxFrame.");
        }
    }

    @Override
    String dumpComment() {
        return "Compound spectral/flux coordinate system description";
    }

    /**
     * Writes the description as the CmpFrame it is, followed by the SpecFluxFrame class's empty
     * part (specfluxframe.c {@code Dump} writes nothing).
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("CmpFrame", "Compound coordinate system description");
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    /**
     * The FluxFrame (C's {@code GetFluxFrame}): the SpecFluxFrame's own, or, if {@code std}, a
     * copy with its System set explicitly and its Unit cleared, so that it uses the default units
     * of its System.
     */
    private FluxFrame getFluxFrame(boolean std) {
        FluxFrame ff = (FluxFrame) secondFrame();
        if (!std) {
            return ff;
        }
        FluxFrame ret = (FluxFrame) ff.copy();
        ret.setSystemValue(ff.systemValue());
        ret.clearUnit(1);
        return ret;
    }

    /**
     * The SpecFrame (C's {@code GetSpecFrame}): the SpecFluxFrame's own, or, if {@code std}, a copy
     * with its System and Unit set to the spectral system and units the FluxFrame's values are a
     * density in (frequency in Hz or wavelength in Angstrom).
     */
    private SpecFrame getSpecFrame(boolean std) {
        SpecFrame sf = (SpecFrame) firstFrame();
        if (!std) {
            return sf;
        }
        FluxFrame ff = (FluxFrame) secondFrame();
        SpecFrame ret = (SpecFrame) sf.copy();
        ret.setSystemValue(ff.getDensitySystem());
        ret.setUnit(1, ff.getDensityUnit());
        return ret;
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Gets the Title: the one set, or by default the FluxFrame's Label, its first letter in upper
     * case, followed by {@code versus} and the SpecFrame's Label (C's {@code GetTitle}).
     *
     * @return the Title
     */
    // C formats the default into a 101-character buffer, which long Labels overflow; here the
    // whole Title is returned.
    @Override
    public String getTitle() {
        if (testTitle()) {
            return super.getTitle();
        }
        // C reads the Labels of the components as Frames, whatever their classes.
        String title = secondFrame().getLabel(1) + " versus " + firstFrame().getLabel(1);
        return CScan.toUpper(title.charAt(0)) + title.substring(1);
    }

    // ------------------------------------------------------------------
    // Match and SubFrame
    // ------------------------------------------------------------------

    /**
     * Matches this SpecFluxFrame, a template, against a target (C's {@code Match},
     * specfluxframe.c). A target that is not a SpecFluxFrame is matched as by a CmpFrame. Against
     * a SpecFluxFrame, the result has two axes, associated with the target's axes in the order
     * that puts the spectral and flux axes where the template has them (or, with PreserveAxes
     * set, in the target's order), which needs Permute set if the two have them in different
     * orders; the target's {@link #subFrame} then makes the result and the conversion.
     */
    @Override
    FrameFinder.MatchResult match(Frame target, boolean matchSub) {
        if (!(target instanceof SpecFluxFrame)) {
            return super.match(target, matchSub);
        }
        boolean swap1 = validateAxis(1, true, "astMatch") != 1;
        boolean swap2 = target.validateAxis(1, true, "astMatch") != 1;
        boolean swap = swap1 != swap2;
        if (swap && !getPermute()) {
            return null;
        }
        int[] templateAxes;
        int[] targetAxes;
        if (getPreserveAxes()) {
            templateAxes = new int[] {swap ? 1 : 0, swap ? 0 : 1};
            targetAxes = new int[] {0, 1};
        } else {
            templateAxes = new int[] {0, 1};
            targetAxes = new int[] {swap ? 1 : 0, swap ? 0 : 1};
        }
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        if (!target.subFrame(this, 2, targetAxes, templateAxes, map, result)) {
            return null;
        }
        return new FrameFinder.MatchResult(templateAxes, targetAxes, map[0], result[0]);
    }

    /**
     * Selects axes from this SpecFluxFrame, the target (C's {@code SubFrame}, specfluxframe.c).
     * With a SpecFluxFrame template and two axes selected, the result is a copy with its axes in
     * the order selected and the template's attributes overlaid, and the Mapping converts between
     * the two ({@link #makeSFMapping}), with PermMaps swapping the coordinates of either Frame
     * whose flux axis comes first. Any other selection is made as by a CmpFrame.
     */
    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        if (!(template instanceof SpecFluxFrame) || resultNaxes != 2) {
            return super.subFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
        }
        map[0] = null;
        result[0] = null;
        SpecFluxFrame res = (SpecFluxFrame) copy();
        res.permAxes0(new int[] {targetAxes[0], targetAxes[1]});
        template.overlay(templateAxes, res);
        Mapping m = makeSFMapping(this, res);
        if (m == null) {
            return false;
        }
        boolean targetSwap = validateAxis(1, true, "astSubFrame") != 1;
        boolean resultSwap = targetSwap != (targetAxes[0] != 0);
        PermMap permmap = null;
        if (targetSwap || resultSwap) {
            int[] perm = {1, 0};
            permmap = PermMap.fromInternal(2, perm, 2, perm, null);
        }
        if (targetSwap) {
            m = new CmpMap(permmap, m, true);
        }
        if (resultSwap) {
            m = new CmpMap(m, permmap, true);
        }
        map[0] = m;
        result[0] = res;
        return true;
    }

    // ------------------------------------------------------------------
    // Conversion between SpecFluxFrames
    // ------------------------------------------------------------------

    /**
     * The Mapping from {@code target} to {@code result}, ignoring their axis permutations, or null
     * if there is none (C's {@code MakeSFMapping}). If their SpecFrames are equal and their
     * FluxFrames are equal it is a UnitMap. Otherwise C makes a chain of Mappings whose middle
     * part, the conversion between the standardised Frames ({@link #makeMap3}), is a TranMap
     * holding a RateMap and MathMaps; it is made only if the target's values convert to the
     * standardised Frames ({@link #makeMap2}).
     *
     * @throws UnsupportedOperationException if the SpecFrames or FluxFrames are not equal and C
     *     would make that TranMap, or if comparing them needs a Mapping this library does not
     *     have
     */
    private static Mapping makeSFMapping(SpecFluxFrame target, SpecFluxFrame result) {
        boolean same = astEqual(target.getSpecFrame(false), result.getSpecFrame(false))
                && astEqual(target.getFluxFrame(false), result.getFluxFrame(false));
        if (same) {
            return new UnitMap(2);
        }
        if (target.makeMap2() != null) {
            makeMap3(target, result);
        }
        // makeMap3 throws where C would make its TranMap; otherwise C makes no conversion either.
        return null;
    }

    /**
     * Whether two Frames are equal, as C's {@code astEqual} finds (object.c {@code astEqual_} and
     * frame.c {@code Equal}): they are the same Frame, or of the same class with a conversion from
     * {@code that} to {@code frame} that simplifies to a UnitMap.
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    private static boolean astEqual(Frame frame, Frame that) {
        if (frame == that) {
            return true;
        }
        if (!frame.astClass().equals(that.astClass())) {
            return false;
        }
        FrameSet fs = that.convert(frame, "");
        return fs != null
                && fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify() instanceof UnitMap;
    }

    /**
     * The conversion of this SpecFluxFrame's flux value and spectral value to the standardised
     * FluxFrame and SpecFrame ({@link #getFluxFrame} and {@link #getSpecFrame}), with a third,
     * unchanged, value, as three Mappings in parallel, or null if either Frame does not convert
     * (C's {@code MakeMap2}).
     */
    private Mapping makeMap2() {
        FrameSet fs = getFluxFrame(false).convert(getFluxFrame(true), "");
        if (fs == null) {
            return null;
        }
        Mapping ax1Map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        fs = getSpecFrame(false).convert(getSpecFrame(true), "");
        if (fs == null) {
            return null;
        }
        Mapping ax2Map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping tmap = CmpMap.transientCmpMap(ax1Map, ax2Map, false);
        return CmpMap.transientCmpMap(tmap, new UnitMap(1), false);
    }

    /**
     * The conversion of flux and spectral values between the standardised Frames of {@code target}
     * and {@code result}, as far as this library can follow C's {@code MakeMap3}. C makes a TranMap
     * of the Mappings {@link #makeMapF} and {@link #makeMapI} give, each of which throws where C
     * would build its RateMap, and looks for the conversion between the SpecFrames themselves; if
     * neither standardised SpecFrame converts to the other, C makes no Mapping.
     *
     * @throws UnsupportedOperationException if the standardised SpecFrames convert
     */
    private static void makeMap3(SpecFluxFrame target, SpecFluxFrame result) {
        // C makes the standardised FluxFrames as well, though MakeMapF and MakeMapI do not use them.
        target.getFluxFrame(true);
        SpecFrame sf1 = target.getSpecFrame(true);
        result.getFluxFrame(true);
        SpecFrame sf2 = result.getSpecFrame(true);
        makeMapF(sf1, sf2);
        makeMapI(sf1, sf2);
        target.getSpecFrame(false).convert(result.getSpecFrame(false), "");
    }

    /**
     * The forward part of C's flux conversion ({@code MakeMapF}): if the standardised SpecFrame
     * {@code x1} converts to {@code x2}, C builds a RateMap of that conversion to scale the flux,
     * and a MathMap dividing by it, which this library does not have.
     *
     * @throws UnsupportedOperationException if {@code x1} converts to {@code x2}
     */
    private static void makeMapF(SpecFrame x1, SpecFrame x2) {
        if (x1.convert(x2, "") != null) {
            throw new UnsupportedOperationException(
                    "MakeMapF(SpecFluxFrame): the conversion of flux values between SpecFluxFrames needs RateMap");
        }
    }

    /**
     * The inverse part of C's flux conversion ({@code MakeMapI}): as {@link #makeMapF}, with a
     * MathMap multiplying by the rate.
     *
     * @throws UnsupportedOperationException if {@code x1} converts to {@code x2}
     */
    private static void makeMapI(SpecFrame x1, SpecFrame x2) {
        if (x1.convert(x2, "") != null) {
            throw new UnsupportedOperationException(
                    "MakeMapI(SpecFluxFrame): the conversion of flux values between SpecFluxFrames needs RateMap");
        }
    }
}
