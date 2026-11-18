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
 * System and Unit. A conversion between a SpecFluxFrame and a FluxFrame or SpecFrame that do not
 * match directly (a FluxFrame in another flux System, say) is made as in C, which casts the
 * CmpFrame its match gives into a SpecFluxFrame and converts between SpecFluxFrames.
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
     * FluxFrames are equal it is a UnitMap. Otherwise it is the simplified series of five
     * Mappings: a PermMap giving the flux value, the spectral value and a copy of the spectral
     * value; the conversion of the target's values to its standardised Frames ({@link
     * #makeMap2}); the conversion between the standardised Frames, with the copy converted between
     * the original SpecFrames ({@link #makeMap3}); the inverse of the result's {@link #makeMap2};
     * and the inverse of the first PermMap. There is no Mapping if any of the three conversions
     * cannot be made.
     */
    private static Mapping makeSFMapping(SpecFluxFrame target, SpecFluxFrame result) {
        boolean same = target.getSpecFrame(false).astEqual(result.getSpecFrame(false))
                && target.getFluxFrame(false).astEqual(result.getFluxFrame(false));
        if (same) {
            return new UnitMap(2);
        }
        PermMap map1 = PermMap.fromInternal(2, new int[] {2, 0}, 3, new int[] {1, 0, 0}, null);
        Mapping map2 = target.makeMap2();
        Mapping map3 = map2 != null ? makeMap3(target, result) : null;
        Mapping map4 = null;
        if (map3 != null) {
            map4 = result.makeMap2();
            if (map4 != null) {
                map4.invert();
            }
        }
        if (map4 == null) {
            return null;
        }
        Mapping map5 = (Mapping) map1.copy();
        map5.invert();
        Mapping tmap1 = new CmpMap(map1, map2, true);
        Mapping tmap2 = new CmpMap(tmap1, map3, true);
        Mapping tmap3 = new CmpMap(tmap2, map4, true);
        Mapping tmap4 = new CmpMap(tmap3, map5, true);
        return tmap4.simplify();
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
        Mapping tmap = new CmpMap(ax1Map, ax2Map, false);
        return new CmpMap(tmap, new UnitMap(1), false);
    }

    /**
     * The conversion of flux and spectral values between the standardised Frames of {@code target}
     * and {@code result}, in parallel with the conversion of the third value, a spectral value,
     * between their original SpecFrames, or null if either cannot be made (C's {@code MakeMap3}).
     * The first is a TranMap whose forward transformation is {@link #makeMapF}'s and whose inverse
     * is {@link #makeMapI}'s.
     */
    private static Mapping makeMap3(SpecFluxFrame target, SpecFluxFrame result) {
        // C makes the standardised FluxFrames as well, though MakeMapF and MakeMapI do not use them.
        target.getFluxFrame(true);
        SpecFrame sf1 = target.getSpecFrame(true);
        result.getFluxFrame(true);
        SpecFrame sf2 = result.getSpecFrame(true);
        Mapping fmap = makeMapF(sf1, sf2);
        Mapping imap = makeMapI(sf1, sf2);
        Mapping mapa = fmap != null && imap != null ? new TranMap(fmap, imap) : null;
        FrameSet fs = target.getSpecFrame(false).convert(result.getSpecFrame(false), "");
        Mapping mapb = fs != null ? fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT) : null;
        return mapa != null && mapb != null ? new CmpMap(mapa, mapb, false) : null;
    }

    /**
     * The forward part of the conversion between standardised Frames (C's {@code MakeMapF}): a
     * Mapping from (v1, x1) to (v2, x2), the flux value and spectral value in the standardised
     * Frames, which divides the flux value by the absolute rate of change of {@code x2} with
     * respect to {@code x1} (a RateMap of the conversion between the SpecFrames), or null if
     * {@code x1} does not convert to {@code x2}.
     */
    private static Mapping makeMapF(SpecFrame x1, SpecFrame x2) {
        FrameSet fs = x1.convert(x2, "");
        if (fs == null) {
            return null;
        }
        Mapping m = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        RateMap rate = new RateMap(m, 0, 0, true);
        MathMap div = new MathMap(2, 1, new String[] {"out=in0/abs(in1)"}, new String[] {"in0", "in1"});
        UnitMap unit = new UnitMap(1);
        PermMap perm = PermMap.fromInternal(2, new int[] {0, 2}, 3, new int[] {0, 1, 1}, null);
        // (v1, x1) to (v1, dx2/dx1), to v2; with x1 to x2 beside it; after the PermMap giving
        // (v1, x1, x1) from (v1, x1).
        CmpMap cmap1 = new CmpMap(unit, rate, false);
        CmpMap cmap2 = new CmpMap(cmap1, div, true);
        CmpMap cmap3 = new CmpMap(cmap2, m, false);
        return new CmpMap(perm, cmap3, true);
    }

    /**
     * The inverse part of the conversion between standardised Frames (C's {@code MakeMapI}): a
     * Mapping whose inverse transformation goes from (v2, x2) to (v1, x1), multiplying the flux
     * value by the absolute rate of change of {@code x2} with respect to {@code x1}, or null if
     * {@code x1} does not convert to {@code x2}. The RateMap shares the conversion between the
     * SpecFrames, which is then inverted: the RateMap uses the conversion as it was when the
     * RateMap was made.
     */
    private static Mapping makeMapI(SpecFrame x1, SpecFrame x2) {
        FrameSet fs = x1.convert(x2, "");
        if (fs == null) {
            return null;
        }
        Mapping m = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        RateMap rate = new RateMap(m, 0, 0, true);
        m.invert();
        MathMap mult = new MathMap(2, 1, new String[] {"out=in0*abs(in1)"}, new String[] {"in0", "in1"});
        UnitMap unit = new UnitMap(1);
        PermMap perm = PermMap.fromInternal(2, new int[] {0, 2}, 3, new int[] {0, 1, 1}, null);
        // (v2, x1) to (v2, dx2/dx1), to v1; with x1 copied beside it; after the PermMap giving
        // (v2, x1, x1) from (v2, x1); after (v2, x2) to (v2, x1).
        CmpMap cmap1 = new CmpMap(unit, rate, false);
        CmpMap cmap2 = new CmpMap(cmap1, mult, true);
        CmpMap cmap3 = new CmpMap(cmap2, unit, false);
        CmpMap cmap4 = new CmpMap(perm, cmap3, true);
        CmpMap cmap5 = new CmpMap(unit, m, false);
        CmpMap ret = new CmpMap(cmap5, cmap4, true);
        ret.invert();
        return ret;
    }
}
