package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The marking of the cards a FITS-WCS read consumes, and the axis descriptions it reads: the card
 * sweep of C's {@code WcsFcRead} (fitschan.c:36696).
 *
 * <p>{@code WcsFcRead} walks every card in the FitsChan and matches its keyword against a fixed
 * list of templates. A card whose keyword matches, and whose value converts to the type the
 * matching branch asks for, is passed to {@code MarkCard}, which flags it as read so that it is
 * left out of later output. A value that does not convert raises a {@code badval} warning, unless
 * the translations {@code SpecTrans} made hold the keyword.
 *
 * <p>Because C sweeps the cards rather than looking keywords up, every copy of a repeated keyword
 * is marked. The values themselves are extracted into a {@link FitsStore} by {@link
 * FitsWcsKeywords}.
 *
 * <p>Ported from Rust {@code fitschan/wcs_mark.rs}.
 */
final class FitsWcsMark {

    private FitsWcsMark() {}

    /** The type a template's branch converts its card's value to. */
    private enum Want {
        FLOAT,
        STRING
    }

    /** A keyword template whose cards {@code WcsFcRead} consumes. */
    private static final class Template {
        /** The template, in the syntax of C's {@code Match} (see {@link FitsMatch}). */
        final String pattern;

        /** The type the branch converts the value to. */
        final Want want;

        /**
         * Whether a card with no value is accepted: set only for VELOSYS, which C reads only to
         * remove it from the header.
         */
        final boolean undef;

        Template(String pattern, Want want, boolean undef) {
            this.pattern = pattern;
            this.want = want;
            this.undef = undef;
        }
    }

    private static Template f(String pattern) {
        return new Template(pattern, Want.FLOAT, false);
    }

    private static Template s(String pattern) {
        return new Template(pattern, Want.STRING, false);
    }

    private static Template fUndef(String pattern) {
        return new Template(pattern, Want.FLOAT, true);
    }

    /**
     * Every template whose branch in C's {@code WcsFcRead} leaves {@code mark} at 1, in source order
     * (fitschan.c:36800-37352).
     *
     * <p>The branches that clear {@code mark} are left out: DUT1, MJD-OBS, MJD-AVG, OBSGEO-X,
     * OBSGEO-Y, OBSGEO-Z, TIMESYS and DTAI give observation details that do not depend on the
     * Mapping from pixel to world coordinates, so C reads them but leaves them in the header (DTAI
     * is read only when TELESCOP names JCMT or UKIRT, and is never marked). None of those names
     * matches a template below, so leaving them out keeps C's if/else-if precedence.
     *
     * <p>CD{@code i_j}, RADECSYS, CROTA{@code i}, PROJP{@code m}, EPOCH, LONGPOLE and DATE-OBS have
     * no {@code WcsFcRead} branch: they reach the store through the translations of {@code
     * SpecTrans}, which marks the first matching card only.
     */
    private static final List<Template> TEMPLATES = Collections.unmodifiableList(Arrays.asList(
            f("CRVAL%d"),
            f("CRVAL%d%1c"),
            f("CRPIX%d"),
            f("CRPIX%d%1c"),
            f("CDELT%d"),
            f("CDELT%d%1c"),
            s("CTYPE%d"),
            s("CTYPE%d%1c"),
            s("CNAME%d"),
            s("CNAME%d%1c"),
            s("CUNIT%d"),
            s("CUNIT%d%1c"),
            f("PC%d_%d"),
            f("PC%d_%d%1c"),
            f("PV%d_%d"),
            f("PV%d_%d%1c"),
            s("PS%d_%d"),
            s("PS%d_%d%1c"),
            s("RADESYS"),
            s("RADESYS%1c"),
            f("EQUINOX"),
            f("EQUINOX%1c"),
            f("LATPOLE"),
            f("LATPOLE%1c"),
            f("LONPOLE"),
            f("LONPOLE%1c"),
            f("WCSAXES"),
            f("WCSAXES%1c"),
            s("WCSNAME"),
            s("WCSNAME%1c"),
            s("SPECSYS"),
            s("SPECSYS%1c"),
            s("SSYSSRC"),
            s("SSYSSRC%1c"),
            f("ZSOURCE"),
            f("ZSOURCE%1c"),
            fUndef("VELOSYS"),
            fUndef("VELOSYS%1c"),
            f("RESTFRQ"),
            f("RESTFRQ%1c"),
            f("RESTWAV"),
            f("RESTWAV%1c"),
            f("IMAGFREQ"),
            f("SREF%d"),
            f("SREF%d%1c"),
            f("SREFP%d"),
            f("SREFP%d%1c"),
            s("SREFIS"),
            s("SREFIS%1c"),
            f("AXREF%d"),
            f("AXREF%d%1c"),
            f("A_%d_%d"),
            f("A_%d_%d%1c"),
            f("B_%d_%d"),
            f("B_%d_%d%1c"),
            f("AP_%d_%d"),
            f("AP_%d_%d%1c"),
            f("BP_%d_%d"),
            f("BP_%d_%d%1c")));

    /** The number of axis descriptions: the primary one and {@code A} to {@code Z}. */
    private static final int VERSIONS = 27;

    /** The outcome of the conversion C's {@code CnvValue} makes before marking a card. */
    private enum Convert {
        /** The value converts; the card is marked. */
        OK,
        /** The value does not convert; C leaves the card unmarked. */
        NO,
        /**
         * The card has no value and the branch does not accept that, which C reports as {@code
         * AST__FUNDEF} (fitschan.c:7795-7800).
         */
        UNDEFINED
    }

    /** The first template {@code keyword} matches, as C's else-if chain tries them, or null. */
    private static Template templateFor(String keyword) {
        for (Template t : TEMPLATES) {
            if (FitsMatch.matchKeyword(keyword, t.pattern, 0) != null) {
                return t;
            }
        }
        return null;
    }

    /** Whether {@code keyword} is one {@code WcsFcRead} reads and marks. */
    static boolean isWcsKeyword(String keyword) {
        return templateFor(keyword) != null;
    }

    /**
     * Whether {@code value} converts to {@code want}: C's {@code CnvValue}, which hands the card to
     * {@code CnvType} (fitschan.c:7582) unless an undefined value is acceptable.
     */
    private static Convert convert(FitsCard.Value value, Want want, boolean undef) {
        if (value.kind == FitsCard.Kind.UNDEF) {
            return undef ? Convert.OK : Convert.UNDEFINED;
        }
        FitsCardValue.Want w = want == Want.FLOAT ? FitsCardValue.Want.FLOAT : FitsCardValue.Want.STRING;
        // The rounding only shapes the text a floating value converts to.
        return FitsCardValue.cnvType(value, w, 0, "") != null ? Convert.OK : Convert.NO;
    }

    /** The axis descriptions a FITS-WCS read builds Frames for, and its number of pixel axes. */
    static final class WcsVersions {
        /**
         * The number of pixel axes: the highest CRPIX index of the primary axis description or any
         * alternate one up to the highest with a CRVAL, as C's {@code FitsToStore} takes it.
         */
        final int npix;

        /**
         * Whether the primary axis description has a CRPIX, without which C's {@code
         * MakeFitsFrameSet} adds no Frame for it.
         */
        final boolean primary;

        /**
         * The letters of the alternate axis descriptions {@code MakeFitsFrameSet} adds Frames for:
         * each letter up to the highest with a CRVAL that has a CRVAL, CRPIX or CTYPE for an axis no
         * higher than {@link #npix}.
         */
        final List<Character> alternates;

        WcsVersions(int npix, boolean primary, List<Character> alternates) {
            this.npix = npix;
            this.primary = primary;
            this.alternates = alternates;
        }
    }

    /**
     * The axis descriptions and pixel axes a read of {@code cards} gives, from the CRVAL, CRPIX and
     * CTYPE values C's {@code WcsFcRead} stores: a card counts if the sweep would store it, its
     * value converting to the type its template asks for; cards already consumed are skipped. The
     * highest version with a CRVAL is C's {@code GetMaxS} (fitschan.c:13701).
     */
    static WcsVersions wcsVersions(FitsCardList cards) {
        int[] crpixMax = new int[VERSIONS];
        List<List<Integer>> crvalAxes = new ArrayList<>();
        List<List<Integer>> crpixAxes = new ArrayList<>();
        List<List<Integer>> ctypeAxes = new ArrayList<>();
        for (int si = 0; si < VERSIONS; si++) {
            crvalAxes.add(new ArrayList<>());
            crpixAxes.add(new ArrayList<>());
            ctypeAxes.add(new ArrayList<>());
        }
        for (FitsCard card : cards.cards()) {
            if (card.flags.consumption == FitsCard.Consumption.USED) {
                continue;
            }
            Template tmpl = templateFor(card.keyword);
            if (tmpl == null) {
                continue;
            }
            List<List<Integer>> item;
            if (tmpl.pattern.startsWith("CRVAL")) {
                item = crvalAxes;
            } else if (tmpl.pattern.startsWith("CRPIX")) {
                item = crpixAxes;
            } else if (tmpl.pattern.startsWith("CTYPE")) {
                item = ctypeAxes;
            } else {
                continue;
            }
            if (convert(card.value, tmpl.want, tmpl.undef) != Convert.OK) {
                continue;
            }
            List<Integer> fields = FitsMatch.matchKeyword(card.keyword, tmpl.pattern, 1);
            if (fields == null || fields.size() != 1) {
                continue;
            }
            int axis = fields.get(0);
            if (axis <= 0) {
                continue;
            }
            int si = 0;
            if (tmpl.pattern.endsWith("%1c")) {
                char c = card.keyword.charAt(card.keyword.length() - 1);
                if (c >= 'A' && c <= 'Z') {
                    si = c - 'A' + 1;
                } else if (c >= 'a' && c <= 'z') {
                    si = c - 'a' + 1;
                } else {
                    continue;
                }
            }
            item.get(si).add(axis);
            if (tmpl.pattern.startsWith("CRPIX")) {
                crpixMax[si] = Math.max(crpixMax[si], axis);
            }
        }
        int smax = 0;
        for (int si = VERSIONS - 1; si >= 1; si--) {
            if (!crvalAxes.get(si).isEmpty()) {
                smax = si;
                break;
            }
        }
        int npix = 0;
        for (int si = 0; si <= smax; si++) {
            npix = Math.max(npix, crpixMax[si]);
        }
        List<Character> alternates = new ArrayList<>();
        for (int si = 1; si <= smax; si++) {
            if (anyAtMost(crvalAxes.get(si), npix)
                    || anyAtMost(crpixAxes.get(si), npix)
                    || anyAtMost(ctypeAxes.get(si), npix)) {
                alternates.add((char) ('A' + si - 1));
            }
        }
        return new WcsVersions(npix, crpixMax[0] > 0, alternates);
    }

    private static boolean anyAtMost(List<Integer> axes, int n) {
        for (int a : axes) {
            if (a <= n) {
                return true;
            }
        }
        return false;
    }

    /**
     * The outcome of {@link #markWcsCards}: the warnings raised, each with the index of the card it
     * goes in front of, and the error that ended the sweep.
     */
    static final class SweepResult {
        /** The warnings, each an array {@code {Integer cardIndex, FitsWarnings.Warning}}. */
        final List<Object[]> warnings;

        /** The error that ended the sweep, or null when it succeeded. */
        final AstException error;

        SweepResult(List<Object[]> warnings, AstException error) {
            this.warnings = warnings;
            this.error = error;
        }
    }

    /**
     * Flags every card whose keyword names a FITS-WCS item as provisionally consumed, so that a
     * successful read leaves it out of output: the marking of C's {@code WcsFcRead}
     * (fitschan.c:37354-37392).
     *
     * <p>Cards already consumed are skipped, as C's {@code astClearCard} and {@code MoveCard} skip
     * cards marked as used. Retained cards are visited but not marked, as in C's {@code MarkCard}.
     * A value that does not convert raises a {@code badval} warning in front of its card, unless
     * {@code translated} holds the keyword (C's {@code HasCard( fc2, ... )}).
     *
     * @param translated the keywords of the cards the translations hold
     * @param policy the Strict and Warnings attributes that make a warning an error
     * @return the warnings and, if the sweep ended early, the error: {@code AST__FUNDEF} when a
     *     card that must have a value has none, or the error {@code policy} makes of a warning
     */
    static SweepResult markWcsCards(FitsCardList cards, List<String> translated, FitsWarnings policy) {
        List<Object[]> warnings = new ArrayList<>();
        List<FitsCard> list = cards.cards();
        for (int index = 0; index < list.size(); index++) {
            FitsCard card = list.get(index);
            if (card.flags.consumption == FitsCard.Consumption.USED) {
                continue;
            }
            Template tmpl = templateFor(card.keyword);
            if (tmpl == null) {
                continue;
            }
            Convert conv = convert(card.value, tmpl.want, tmpl.undef);
            // MODERN-JAVA(21): switch expression over Convert
            switch (conv) {
                case OK:
                    if (!card.flags.retained) {
                        card.flags.consumption = FitsCard.Consumption.PROVISIONAL;
                    }
                    break;
                case NO:
                    if (!translated.contains(card.keyword)) {
                        FitsWarnings.Warning warning = new FitsWarnings.Warning(
                                "badval",
                                "The original FITS header contained a value for keyword " + card.keyword
                                        + " which could not be converted to a "
                                        + (tmpl.want == Want.FLOAT ? "floating point number" : "character string")
                                        + ".");
                        // With Strict in force the warning ends the sweep as an error, as C's Warn
                        // does.
                        try {
                            policy.check(warning);
                        } catch (AstException e) {
                            return new SweepResult(warnings, e);
                        }
                        warnings.add(new Object[] {index, warning});
                    }
                    break;
                case UNDEFINED:
                    return new SweepResult(
                            warnings,
                            Ast.err(
                                    AstException.AST__FUNDEF,
                                    "The FITS keyword '" + card.keyword + "' has an undefined value."));
                default:
                    throw Ast.err(AstException.AST__INTER, "markWcsCards: unexpected conversion outcome " + conv);
            }
        }
        return new SweepResult(warnings, null);
    }
}
