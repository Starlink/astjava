package uk.ac.starlink.ast;

/**
 * Units handling: the Mapping between two systems of units, the label of a quantity in new units,
 * the normalised form of a units string, and the name of a unit symbol.
 *
 * <p>A units string follows the FITS WCS paper I (Greisen and Calabretta): an expression combining
 * unit symbols, optionally with multiplier prefixes or preceded by a constant ("km", "1000 m"),
 * with the operators "*" (or "." or a space), "/", "**" (or "^", or an integer following a unit
 * symbol, as in "cm2"), the functions log, ln, sqrt and exp, parentheses, and the constants pi
 * and e. An unknown symbol is a unit in its own right, which converts only to itself.
 *
 * <p>Ported from Rust {@code units/mod.rs} and C {@code unit.c} ({@code astUnitMapper_}, {@code
 * astUnitNormaliser_}, {@code astNormUnit_} and {@code astUnitLabel_}).
 */
final class Units {

    private Units() {}

    /** A Mapping between two systems of units, and a label rewritten for the new units. */
    static final class LabelledMapping {
        /** The Mapping, with one input and one output. */
        final Mapping mapping;

        /** The rewritten label, or null where C gives none. */
        final String label;

        LabelledMapping(Mapping mapping, String label) {
            this.mapping = mapping;
            this.label = label;
        }
    }

    /**
     * The Mapping that converts values in the units {@code inUnit} to values in the units {@code
     * outUnit}, or null if they cannot be converted, as for "kg" and "m" (C {@code astUnitMapper}
     * with no label).
     *
     * @throws AstException with status {@code AST__BADUN} if a units string cannot be parsed
     * @throws UnsupportedOperationException if the conversion needs a MathMap, which is not
     *     available
     */
    static Mapping unitMapper(String inUnit, String outUnit) {
        LabelledMapping lm = UnitsMapper.unitMapper(inUnit, outUnit, null);
        return lm != null ? lm.mapping : null;
    }

    /**
     * The Mapping that converts values in the units {@code inUnit} to values in the units {@code
     * outUnit}, and the label {@code inLabel} of the quantity in the input units rewritten for the
     * output units, or null if the units cannot be converted (C {@code astUnitMapper}). For units
     * "Hz" and "sqrt(Hz)", the label "Frequency" becomes "sqrt( Frequency )"; a simple scaling
     * leaves a label unchanged. The label is unchanged when the units strings are identical or
     * both blank, and is null where C gives none.
     *
     * @throws AstException with status {@code AST__BADUN} if a units string or the label cannot
     *     be parsed
     * @throws UnsupportedOperationException if the conversion needs a MathMap, which is not
     *     available
     */
    static LabelledMapping unitMapperLabel(String inUnit, String outUnit, String inLabel) {
        return UnitsMapper.unitMapper(inUnit, outUnit, inLabel);
    }

    /**
     * Whether values in the units {@code inUnit} can be converted to the units {@code outUnit},
     * and the label {@code inLabel} rewritten for the output units, as {@link #unitMapperLabel}
     * gives them, but without making a Mapping from the conversion found, so that the result's
     * {@code mapping} may be null: C's
     * {@code NewUnit} (frame.c) uses only the label and whether there is a Mapping, so a conversion
     * that needs a MathMap is found too.
     *
     * @return the label, or null if the units cannot be converted
     * @throws AstException with status {@code AST__BADUN} if a units string or the label cannot
     *     be parsed
     */
    static LabelledMapping relabel(String inUnit, String outUnit, String inLabel) {
        return UnitsMapper.unitMapper(inUnit, outUnit, inLabel, false);
    }

    /**
     * The standard FITS-WCS form of the units string {@code in} (C {@code astUnitNormaliser}). A
     * string that is only a constant normalises to a blank string.
     *
     * @throws AstException with status {@code AST__BADUN} if the string cannot be parsed, which
     *     includes a blank string
     */
    static String unitNormaliser(String in) {
        UnitsTree.Node inTree = UnitsParser.createTree(in, false);
        if (inTree == null) {
            throw Ast.err(AstException.AST__BADUN, "astUnitNormaliser: Error parsing input units string '" + in + "'.");
        }
        UnitsSimplify.simplifyTree(inTree, true);
        // The tree was made with its constant coefficients inverted, so that "1000 m" means units
        // of 1000 metres; inverting them again formats such a constant as part of the units.
        UnitsSimplify.invertConstants(inTree);
        String result = UnitsToExpr.makeExp(inTree, UnitsToExpr.UNITS, true);
        // A result that is wholly a constant is blank.
        if (result != null && CScan.scanDouble(result) != null) {
            result = "";
        }
        return result;
    }

    /**
     * The normalised form of the units string {@code in}, or {@code in} itself if it cannot be
     * parsed as a units expression, as for a blank string or a description of axis values such as
     * "ddd:mm:ss" (C {@code astNormUnit}).
     */
    static String normUnit(String in) {
        try {
            return unitNormaliser(in);
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADUN) {
                throw e;
            }
            return in;
        }
    }

    /** The name of the known unit with the symbol {@code sym}, such as "Hertz" for "Hz", or null (C {@code astUnitLabel}). */
    static String unitLabel(String sym) {
        UnitsTable.KnownUnit unit = UnitsTable.knownUnit(sym);
        return unit != null ? unit.label : null;
    }
}
