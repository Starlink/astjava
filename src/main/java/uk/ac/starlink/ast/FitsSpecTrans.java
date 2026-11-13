package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The standard FITS-WCS equivalents of non-standard WCS keywords, which a FitsChan read takes in
 * preference to the cards themselves (C's {@code SpecTrans}, fitschan.c:31348).
 *
 * <p>C writes the equivalents into a new FitsChan {@code ret}, reads keyword values from {@code
 * ret} in preference to the FitsChan {@code this} ({@code GetValue2}), and marks each card of
 * {@code this} it reads as used. Here the equivalents are written into a copy of the cards, which
 * then holds both, as C's reader sees them; the cards read are recorded as consumed with {@link
 * FitsCardList#recordConsumed}, and no card is removed except where C deletes one.
 *
 * <p>The translations only the FITS-PC, FITS-IRAF and FITS-CLASS encodings make (RADECSYS to
 * RADESYS, LONGPOLE to LONPOLE, PROJPi to PVi_m, CmVALi and its relatives to alternate axis
 * descriptions, CDjjjiii to PCi_j, and C's {@code ClassTrans}) are not available, since those
 * encodings are not read; asking for one of them throws {@link UnsupportedOperationException}. The
 * reads C makes of those keywords under every encoding, and the CmELTi translation C makes under
 * every encoding, are made.
 *
 * <p>Ported from Rust {@code fitschan/spec_trans.rs}, following the order and tests of C's {@code
 * SpecTrans}.
 */
final class FitsSpecTrans {

    /** C's {@code AST__DD2R}: radians per degree. */
    private static final double AST__DD2R = 0.017453292519943295;

    /** C's {@code AST__DR2D}: degrees per radian. */
    private static final double AST__DR2D = 57.29577951308232;

    /** The {@code tnx} warning (fitschan.c:32460). */
    private static final String TNX_WARNING = "This FITS header includes, or was derived from, a TNX projection which"
            + " requires unsupported IRAF-specific corrections. The WCS information may therefore be incorrect.";

    /** The number of SAO polynomial coefficients on each axis (C's {@code NC} in {@code SAOTrans}). */
    private static final int SAO_NC = 13;

    /** The PVi_m keywords {@code SAOTrans} writes, in its order (fitschan.c:27125-27227). */
    private static final String[] SAO_PV_KEYS = {
        "PV1_0", "PV1_1", "PV1_2", "PV1_4", "PV1_5", "PV1_6", "PV1_7", "PV1_8", "PV1_9", "PV1_10",
        "PV2_0", "PV2_1", "PV2_2", "PV2_4", "PV2_5", "PV2_6", "PV2_7", "PV2_8", "PV2_9", "PV2_10"
    };

    /**
     * For each of {@link #SAO_PV_KEYS}, the pairs of axis and coefficient index, from 0, of the SAO
     * coefficients summed to give it.
     */
    private static final int[][] SAO_PV_TERMS = {
        {0, 0}, {0, 1}, {0, 2}, {0, 3, 0, 10}, {0, 5}, {0, 4, 0, 10}, {0, 6, 0, 11}, {0, 8, 0, 12}, {0, 9, 0, 11},
        {0, 7, 0, 12}, {1, 0}, {1, 2}, {1, 1}, {1, 4, 1, 10}, {1, 5}, {1, 3, 1, 10}, {1, 7, 1, 12}, {1, 9, 1, 11},
        {1, 8, 1, 12}, {1, 6, 1, 11}
    };

    /**
     * The keywords whose scaling and rotation an SAO polynomial includes, which {@code SAOTrans}
     * marks as used (fitschan.c:27235-27246).
     */
    private static final String[] SAO_SUBSUMED = {
        "CD1_1", "CD1_2", "CD2_1", "CD2_2", "PC1_1", "PC1_2", "PC2_1", "PC2_2", "CDELT1", "CDELT2", "CROTA1", "CROTA2"
    };

    /** The number of coefficients a TNX correction surface of each order needs (C's {@code nab}). */
    private static final int[] NAB = {1, 3, 6, 10, 15, 21, 28, 36};

    private FitsSpecTrans() {}

    /** The FitsChan attributes the translations depend on. */
    static final class TransAttrs {
        /** CarLin: a {@code -CAR} projection is linear, so its CRPIX is not moved. */
        boolean carLin;

        /**
         * PolyTan: whether a {@code -TAN} projection with distortion terms is read as {@code -TPN};
         * negative to decide from the PV cards present.
         */
        int polyTan;

        /** The defaults: CarLin false and PolyTan -1. */
        TransAttrs() {
            carLin = false;
            polyTan = -1;
        }
    }

    /**
     * The warnings the translations raise, by where C's current card is when it raises them. Those
     * raised before the scan for {@code QV} cards in the primary axis description ({@code SAOTrans})
     * are written in front of the card the read started at, since C's {@code Read} does not move
     * the current card first; that scan leaves the current card at end-of-file, where the rest go.
     */
    static final class TransWarnings {
        /** Warnings written in front of the card the read started at. */
        final List<FitsWarnings.Warning> entry = new ArrayList<>();

        /** Warnings written at end-of-file. */
        final List<FitsWarnings.Warning> end = new ArrayList<>();
    }

    /**
     * The PVi_m values of a TPN projection equivalent to an IRAF TNX correction surface: {@code
     * m[k]} and {@code value[k]} are the index and value of each, and {@code ok} is false if the
     * surface uses features a TPN projection cannot express. No values with {@code ok} true means
     * there is no correction surface.
     */
    static final class WatCoeffs {
        final int[] m;
        final double[] value;
        final boolean ok;

        WatCoeffs(int[] m, double[] value, boolean ok) {
            this.m = m;
            this.value = value;
            this.ok = ok;
        }
    }

    /**
     * A copy of {@code cards} holding the standard equivalents of its non-standard WCS keywords as
     * well, which records the keywords the translations write ({@link
     * FitsCardList#takeWrittenNames}) and those they read ({@link FitsCardList#takeConsumedNames});
     * {@code cards} is unchanged. This is C's {@code SpecTrans} (fitschan.c:31348), with its {@code
     * ret} held in the copy.
     *
     * <p>{@code warnings} collects the warnings raised. C's {@code Warn} adds them to the FitsChan
     * read, not to {@code ret}, so the caller stores them on {@code cards}. With Strict in force
     * ({@code policy}), the first warning whose condition is reported, in the order C raises them
     * (those at the entry card first), is thrown as the error C reports; the translations change
     * only the copy, so stopping once they are done ends the read where C's ends.
     *
     * @throws UnsupportedOperationException for the FITS-PC, FITS-IRAF and FITS-CLASS encodings
     * @throws AstException where C's {@code SpecTrans} reports an error: a keyword it needs is
     *     absent or has no value, or an IRAF WAT string holds a word that is not a number
     */
    static FitsCardList specTrans(
            FitsCardList cards,
            FitsChan.Encoding encoding,
            TransAttrs attrs,
            TransWarnings warnings,
            FitsWarnings policy) {
        FitsCardList trans = cards.copy();
        trans.recordWrites();
        specTransInplace(trans, encoding, attrs, warnings);
        for (FitsWarnings.Warning w : warnings.entry) {
            policy.check(w);
        }
        for (FitsWarnings.Warning w : warnings.end) {
            policy.check(w);
        }
        return trans;
    }

    /**
     * The translations of {@link #specTrans}, written into {@code cards} itself. C's {@code this}
     * is {@code cards} as supplied, and its {@code ret} the keywords written here.
     *
     * @throws UnsupportedOperationException for the FITS-PC, FITS-IRAF and FITS-CLASS encodings
     * @throws AstException as {@link #specTrans} does
     */
    static void specTransInplace(
            FitsCardList cards, FitsChan.Encoding encoding, TransAttrs attrs, TransWarnings warnings) {
        switch (encoding) {
            case FITS_PC:
            case FITS_IRAF:
            case FITS_CLASS:
                throw new UnsupportedOperationException(
                        "FitsChan: reading the " + encoding.cName() + " encoding is not available");
            default:
                break;
        }
        Translator t = new Translator(cards, attrs, warnings);
        // The primary axis description, then 'A' to 'Z' (fitschan.c:31558-31559, 32615).
        t.version(' ');
        for (char s = 'A'; s <= 'Z'; s++) {
            t.version(s);
        }
        t.miniWcs();
    }

    /**
     * The concatenated values of the IRAF {@code WAT<axis>_nnn} keywords, {@code axis} counting
     * from 1, for {@code nnn} from 001 up to the first that is absent; empty if there are none
     * (C's {@code ConcatWAT}, fitschan.c:8236, which returns NULL then).
     */
    static String concatWat(FitsCardList cards, int axis) {
        StringBuilder out = new StringBuilder();
        for (int j = 1; ; j++) {
            String value = cards.getString(String.format(Locale.ROOT, "WAT%d_%03d", axis, j));
            if (value == null) {
                return out.toString();
            }
            out.append(value);
        }
    }

    /**
     * The PVi_m values of the TPN projection equivalent to the TNX correction surface in the
     * concatenated WAT string {@code watstr} of the longitude ({@code iaxis} 0) or latitude ({@code
     * iaxis} 1) axis (C's {@code WATCoeffs}, fitschan.c:35632).
     *
     * <p>The surface is the {@code "..."} of {@code cor = "..."}; without one there are no values
     * and {@code ok} is true. A surface is representable when its type is not 2 (legendre), its
     * orders on both axes agree and are at most 7, it has half cross-terms, and it has exactly the
     * coefficients its order needs. A first-order surface, whose single coefficient C adds the
     * linear term to beyond the end of its array, is not representable here.
     *
     * @throws AstException with status {@code AST__BDFTS} if a word of the surface is not a number
     */
    static WatCoeffs watCoeffs(String watstr, int iaxis) {
        String body = watstr == null ? null : correctionSurface(watstr);
        if (body == null) {
            return new WatCoeffs(new int[0], new double[0], true);
        }
        List<String> words = words(body);
        int nword = words.size();
        boolean ok = true;
        boolean cheb = false;
        int order = 0;
        int porder = -1;
        double[] coeff = null;
        double ximin = 0.0;
        double ximax = 0.0;
        double etamin = 0.0;
        double etamax = 0.0;
        int xiorder = 0;
        int etaorder = 0;
        for (int iword = 0; iword < nword && ok; iword++) {
            Double parsed = CScan.scanDouble(words.get(iword));
            if (parsed == null) {
                throw Ast.err(
                        AstException.AST__BDFTS,
                        "astRead(FitsChan): Failed to read a numerical value from sub-string \"" + words.get(iword)
                                + "\" found in an IRAF \"WAT...\" keyword.");
            }
            double dval = parsed;
            if (iword == 0) {
                // The surface type: 1 is chebyshev, 2 (legendre) cannot be handled, and anything
                // else is a simple polynomial.
                if (dval == 1.0) {
                    cheb = true;
                } else if (dval == 2.0) {
                    ok = false;
                }
            } else if (iword == 1) {
                order = (int) dval;
                porder = order - 1;
            } else if (iword == 2) {
                if (dval - 1 != porder || dval < 0 || dval > 7) {
                    ok = false;
                }
            } else if (iword == 3) {
                // Only half cross-terms (type 2) can be handled. The coefficients are held as if
                // full cross-terms had been supplied.
                if (dval != 2.0) {
                    ok = false;
                }
                coeff = new double[Math.max(order, 0) * Math.max(order, 0)];
            } else if (iword == 4) {
                ximin = dval;
            } else if (iword == 5) {
                ximax = dval;
            } else if (iword == 6) {
                etamin = dval;
            } else if (iword == 7) {
                etamax = dval;
            } else {
                // The coefficients, the power of xi increasing fastest; with half cross-terms the
                // highest power of xi falls from order to zero through the list. C writes past
                // the end of its array for a list longer than the order allows, which the count
                // check below then rejects; such coefficients are not stored here.
                if (etaorder < order) {
                    coeff[xiorder + order * etaorder] = dval;
                    if (++xiorder == order - etaorder) {
                        xiorder = 0;
                        etaorder++;
                    }
                }
            }
        }
        // C indexes nab[porder] for any porder other than -1; a porder past the table, which C
        // reaches only with ok already false, fails the check here.
        if (porder < 0 || porder >= NAB.length || nword != 8 + NAB[porder]) {
            ok = false;
        }
        if (!ok || coeff == null || coeff.length < 2) {
            return new WatCoeffs(new int[0], new double[0], false);
        }
        if (cheb) {
            coeff = cheb2poly(coeff, order, order, ximin, ximax, etamin, etamax);
        }
        // The polynomials are corrections added to xi and eta, so the linear term of the axis
        // being described increases by one.
        coeff[iaxis != 0 ? order : 1] += 1.0;
        List<int[]> ms = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        int pc = 0;
        for (etaorder = 0; etaorder < order; etaorder++) {
            for (xiorder = 0; xiorder < order; xiorder++, pc++) {
                // Coefficients with their default value (zero, or one for a linear term) are
                // left out.
                int mn = xiorder + etaorder;
                if (coeff[pc] != (mn == 1 ? 1.0 : 0.0)) {
                    int m = mn * (1 + mn) / 2 + mn / 2;
                    m += iaxis != 0 ? xiorder : etaorder;
                    ms.add(new int[] {m});
                    vals.add(coeff[pc]);
                }
            }
        }
        int[] mout = new int[ms.size()];
        double[] vout = new double[vals.size()];
        for (int k = 0; k < mout.length; k++) {
            mout[k] = ms.get(k)[0];
            vout[k] = vals.get(k);
        }
        return new WatCoeffs(mout, vout, true);
    }

    /**
     * The text matched by the parenthesised part of C's {@code astChrSplitRE( watstr, "cor *= *\"(.*)\"",
     * ... )} (fitschan.c:35728): the first place {@code cor} is followed by an equals sign and a
     * double quote, with optional spaces before each, and the greedy {@code .*} running to the last
     * double quote after it. Null if there is none.
     */
    private static String correctionSurface(String watstr) {
        int from = 0;
        while (true) {
            int cor = watstr.indexOf("cor", from);
            if (cor < 0) {
                return null;
            }
            int k = cor + 3;
            while (k < watstr.length() && watstr.charAt(k) == ' ') {
                k++;
            }
            if (k < watstr.length() && watstr.charAt(k) == '=') {
                k++;
                while (k < watstr.length() && watstr.charAt(k) == ' ') {
                    k++;
                }
                if (k < watstr.length() && watstr.charAt(k) == '"') {
                    int close = watstr.lastIndexOf('"');
                    if (close > k) {
                        return watstr.substring(k + 1, close);
                    }
                }
            }
            from = cor + 1;
        }
    }

    /** The words of {@code s} separated by white space (C's {@code astChrSplit}). */
    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && CScan.isCSpace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !CScan.isCSpace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                out.add(s.substring(start, i));
            }
        }
        return out;
    }

    /**
     * The coefficients of the 2-D polynomial in x and y equal to the 2-D Chebyshev polynomial with
     * coefficients {@code c} over the box {@code [xmin, xmax]} by {@code [ymin, ymax]} (C's {@code
     * Cheb2Poly}, fitschan.c:5905). Both arrays hold the coefficient of the i'th term in x and j'th
     * term in y at {@code i + j*nx}.
     */
    private static double[] cheb2poly(double[] c, int nx, int ny, double xmin, double xmax, double ymin, double ymax) {
        double[] d = new double[nx * ny];
        double[] work1 = new double[ny];
        double[] work2 = new double[ny];
        double[] work3 = new double[nx];
        double[] row = new double[nx];
        double[] out = new double[nx];
        // Each row is a Chebyshev polynomial in u, which becomes a polynomial in x.
        for (int j = 0; j < ny; j++) {
            System.arraycopy(c, j * nx, row, 0, nx);
            chpc1(row, work3, nx);
            shpc1(xmin, xmax, nx, work3, out);
            System.arraycopy(out, 0, d, j * nx, nx);
        }
        // Each column is then a Chebyshev polynomial in v, which becomes a polynomial in y.
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                work1[j] = d[i + j * nx];
            }
            chpc1(work1, work2, ny);
            shpc1(ymin, ymax, ny, work2, work1);
            for (int j = 0; j < ny; j++) {
                d[i + j * nx] = work1[j];
            }
        }
        return d;
    }

    /**
     * The coefficients {@code d} of the polynomial equal to the 1-D Chebyshev polynomial with the
     * {@code n} coefficients {@code c} (C's {@code Chpc1}, fitschan.c:6380), using the recurrence
     * {@code T{k+1}(x) = 2x T{k}(x) - T{k-1}(x)}. C sets the T1 term without checking {@code n}; a
     * single coefficient is here its own polynomial.
     */
    // The loop over w0 and w1 updates both together, which no array copy does.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private static void chpc1(double[] c, double[] d, int n) {
        for (int j = 0; j < n; j++) {
            d[j] = 0.0;
        }
        if (n == 1) {
            d[0] = c[0];
            return;
        }
        // w0 holds the coefficients of T{k-1} and w1 those of T{k}.
        int[] w0 = new int[n];
        int[] w1 = new int[n];
        w0[0] = 1;
        w1[1] = 1;
        d[0] = c[0];
        d[1] = c[1];
        for (int k = 1; k < n - 1; k++) {
            for (int j = n - 1; j > 0; j--) {
                int sv = w0[j];
                w0[j] = w1[j];
                w1[j] = 2 * w1[j - 1] - sv;
            }
            int sv = w0[0];
            w0[0] = w1[0];
            w1[0] = -sv;
            for (int j = 0; j <= k + 1; j++) {
                d[j] += c[k + 1] * w1[j];
            }
        }
    }

    /**
     * The coefficients {@code w} of the polynomial in x equal to the polynomial in u with the
     * {@code n} coefficients {@code d}, where u runs from -1 to +1 as x runs from {@code xmin} to
     * {@code xmax} (C's {@code Shpc1}, fitschan.c:28533).
     */
    private static void shpc1(double xmin, double xmax, int n, double[] d, double[] w) {
        double a = 2.0 / (xmax - xmin);
        double b = (xmin + xmax) / (xmin - xmax);
        for (int i = 0; i < n; i++) {
            w[i] = 0.0;
        }
        w[0] = d[n - 1];
        for (int i = 1; i < n; i++) {
            for (int j = i; j > 0; j--) {
                w[j] = b * w[j] + a * w[j - 1];
            }
            w[0] = d[n - i - 1] + b * w[0];
        }
    }

    /** C's {@code FormatKey( root, i, -1, s )}: {@code root}, the index, and {@code s} unless blank. */
    private static String key(String root, int i, char s) {
        return s == ' ' ? root + i : root + i + s;
    }

    /** C's {@code FormatKey( root, i, j, s )}: {@code root}, {@code i_j}, and {@code s} unless blank. */
    private static String key2(String root, int i, int j, char s) {
        return s == ' ' ? root + i + "_" + j : root + i + "_" + j + s;
    }

    /** A keyword or template for version {@code s}: {@code root} with {@code s} appended unless blank. */
    private static String versioned(String root, char s) {
        return s == ' ' ? root : root + s;
    }

    /** C's {@code Ustrcmp( a, b ) == 0}: equal ignoring case, trailing spaces included. */
    private static boolean ustrEq(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    /** C's {@code strncmp( s + from, prefix, strlen( prefix ) ) == 0}. */
    private static boolean at(String s, int from, String prefix) {
        return s.length() >= from + prefix.length() && s.startsWith(prefix, from);
    }

    /**
     * C's {@code strcpy( type + 4, suffix )} on a CTYPE value copied with {@code strncpy( type,
     * cval, 10 )}: the first four characters followed by {@code suffix}. A value shorter than four
     * characters keeps its terminating null, so is unchanged.
     */
    private static String withSuffix(String type, String suffix) {
        return type.length() < 4 ? type : type.substring(0, 4) + suffix;
    }

    /** The translations of one FitsChan, holding C's {@code this} and {@code ret}. */
    private static final class Translator {

        /** The cards, into which the translations are written. */
        private final FitsCardList cards;

        /** C's {@code this}: the cards as supplied. */
        private final FitsCardList src;

        /** The keywords C's {@code ret} holds, in upper case. */
        private final Set<String> ret = new HashSet<>();

        private final TransAttrs attrs;
        private final TransWarnings warnings;

        Translator(FitsCardList cards, TransAttrs attrs, TransWarnings warnings) {
            this.cards = cards;
            this.src = cards.copy();
            this.attrs = attrs;
            this.warnings = warnings;
        }

        // ------------------------------------------------------------------
        // C's GetValue, GetValue2 and SetValue
        // ------------------------------------------------------------------

        /**
         * The value of the first card of {@code list} with {@code keyword}, converted to {@code
         * want} as C's {@code CnvValue} converts it at the default FitsRounding; null if there is no
         * such card or it does not convert. With {@code report}, either is the error C's {@code
         * GetValue} reports.
         *
         * @throws AstException with status {@code AST__FUNDEF} if the card has no value
         */
        private static FitsCardValue.Got getValue(
                FitsCardList list, String keyword, FitsCardValue.Want want, boolean report) {
            FitsCard c = list.find(keyword);
            if (c == null) {
                if (report) {
                    throw Ast.err(
                            AstException.AST__BDFTS,
                            "astRead(FitsChan): Unable to find a value for FITS keyword \"" + keyword + "\".");
                }
                return null;
            }
            FitsCardValue.Got g = FitsCardValue.cnvType(c.value, want, 10, keyword);
            if (g == null && report) {
                throw Ast.err(
                        AstException.AST__FTCNV,
                        "astRead(FitsChan): Cannot convert FITS keyword '" + keyword + "' to " + want.cName() + ".");
            }
            return g;
        }

        /**
         * C's {@code GetValue2( ret, this, keyword, ... )} (fitschan.c:18515): the value from
         * {@code ret} if it holds the keyword and it converts, and otherwise from {@code this},
         * whose cards with the keyword are then consumed.
         */
        private FitsCardValue.Got getValue2(String keyword, FitsCardValue.Want want, boolean report) {
            String k = keyword.toUpperCase(Locale.ROOT);
            if (ret.contains(k)) {
                FitsCardValue.Got g = getValue(cards, k, want, false);
                if (g != null) {
                    return g;
                }
            }
            FitsCardValue.Got g = getValue(src, k, want, report);
            if (g != null) {
                cards.recordConsumed(k);
            }
            return g;
        }

        /** {@link #getValue2} of a floating value. */
        private Double getFloat2(String keyword, boolean report) {
            FitsCardValue.Got g = getValue2(keyword, FitsCardValue.Want.FLOAT, report);
            return g == null ? null : g.d;
        }

        /** {@link #getValue2} of a string. */
        private String getString2(String keyword) {
            FitsCardValue.Got g = getValue2(keyword, FitsCardValue.Want.STRING, false);
            return g == null ? null : g.s;
        }

        /**
         * C's {@code GetValue( this, keyword, AST__FLOAT, ..., mark )} with report clear: the value
         * from the cards as supplied, consuming them if {@code mark} is set.
         */
        private Double getFloatThis(String keyword, boolean mark) {
            FitsCardValue.Got g = getValue(src, keyword, FitsCardValue.Want.FLOAT, false);
            if (g == null) {
                return null;
            }
            if (mark) {
                cards.recordConsumed(keyword);
            }
            return g.d;
        }

        /** C's {@code SetValue( ret, keyword, ... )} of a floating value. */
        private void setFloat(String keyword, double value, String comment) {
            ret.add(keyword.toUpperCase(Locale.ROOT));
            cards.setFloat(keyword, value, comment);
        }

        /** C's {@code SetValue( ret, keyword, ... )} of a string. */
        private void setString(String keyword, String value, String comment) {
            ret.add(keyword.toUpperCase(Locale.ROOT));
            cards.setString(keyword, value, comment);
        }

        /**
         * C's {@code SetValue( ret, keyword, ... )} of a string with no comment, which leaves the
         * comment of the keyword in {@code this} as the one a reader sees ({@code SetItemC} ignores
         * a null comment): the value replaces the card's and keeps its comment.
         */
        private void replaceString(String keyword, String value) {
            FitsCard old = cards.find(keyword);
            String comment = old == null || old.comment == null ? "" : old.comment;
            setString(keyword, value, comment);
        }

        /**
         * Sets the CTYPE of axis {@code ax} (from 0) of version {@code s}. For an axis that was not
         * found ({@code ax} -1) C writes an unset value to CTYPE0; nothing is written here.
         */
        private void setCtype(int ax, char s, String value) {
            if (ax >= 0) {
                replaceString(key("CTYPE", ax + 1, s), value);
            }
        }

        /** C's {@code Warn( this, ... )}: a warning raised before the QV scan of the primary description. */
        private void warnAtEntry(String condition, String text) {
            warnings.entry.add(new FitsWarnings.Warning(condition, text));
        }

        /** C's {@code Warn( this, ... )}: a warning raised after a scan left the current card at end-of-file. */
        private void warnAtEnd(String condition, String text) {
            warnings.end.add(new FitsWarnings.Warning(condition, text));
        }

        /** Deletes the first card with {@code keyword} from {@code this} (C's {@code DeleteCard}). */
        private void deleteThis(String keyword) {
            src.delete(keyword);
            cards.delete(keyword);
        }

        // ------------------------------------------------------------------
        // One axis description (the body of C's loop, fitschan.c:31558-32616)
        // ------------------------------------------------------------------

        /** The celestial axes and projection of the axis description being translated. */
        private int axlon;

        private int axlat;

        /** C's {@code prj}: characters 5 to 8 of the CTYPE of the last celestial axis. */
        private String prj;

        /** C's {@code lontype} and {@code lattype}: the CTYPE values of the celestial axes. */
        private String lontype;

        private String lattype;

        /** Translates the keywords of axis description {@code s}, {@code ' '} for the primary one. */
        void version(char s) {
            axlon = -1;
            axlat = -1;
            prj = "";
            lontype = null;
            lattype = null;
            celestialAxes(s);
            if (s == ' ') {
                // RADECSYS and LONGPOLE are read under every encoding, and translated only for
                // FITS-PC and FITS-IRAF (fitschan.c:31656-31675).
                getString2("RADECSYS");
                getFloat2("LONGPOLE", false);
            }
            zeroCdelt(s);
            pcFamily(s);
            if (s == ' ') {
                projp();
                cmxxx();
                epochEquinox();
                dateObs();
                // C's ClassTrans runs here for FITS-CLASS, which is not read.
                if (ustrEq(prj, "-TAN") && saoTrans()) {
                    prj = "-TPN";
                    setCelestialSuffix(s, "-TPN");
                }
            }
            ncpGlsAtf(s);
            qv(s);
            polyTan(s);
            if (s == ' ') {
                if (ustrEq(prj, "-ZPX")) {
                    zpx();
                } else if (ustrEq(prj, "-TNX")) {
                    tnx();
                }
                if (ustrEq(prj, "-CAR") && !attrs.carLin) {
                    car();
                }
            }
            restfreq(s);
            aipsSpectral(s);
            cunit(s);
        }

        /** {@link #withSuffix} of a CTYPE that may be absent, as C's would be when an axis is. */
        private static String rewrite(String type, String suffix) {
            return type == null ? null : withSuffix(type, suffix);
        }

        /** Sets both celestial CTYPEs to their values with {@code suffix} (C's paired {@code SetValue} calls). */
        private void setCelestialSuffix(char s, String suffix) {
            lontype = rewrite(lontype, suffix);
            lattype = rewrite(lattype, suffix);
            setCtype(axlon, s, lontype);
            setCtype(axlat, s, lattype);
        }

        /**
         * Finds the celestial axes and projection from the CTYPE values, and translates the
         * spectral algorithm codes of early drafts of FITS-WCS paper III ({@code -WAV}, {@code -FRQ}
         * and {@code -VEL}) into the {@code -X2P} form (fitschan.c:31565-31652).
         */
        private void celestialAxes(char s) {
            FitsCardList.KeyFields kf = src.keyFields(versioned("CTYPE%d", s), 1);
            if (kf.n == 0) {
                return;
            }
            int naxis = kf.hi[0];
            for (int j = 0; j < naxis; j++) {
                String cval = getString2(key("CTYPE", j + 1, s));
                if (cval == null) {
                    continue;
                }
                int nc = cval.length();
                if (at(cval, 0, "RA--")
                        || at(cval, 0, "AZ--")
                        || (nc > 1 && at(cval, 1, "LON"))
                        || (nc > 2 && at(cval, 2, "LN"))) {
                    axlon = j;
                    prj = projection(cval);
                    lontype = cval.length() > 10 ? cval.substring(0, 10) : cval;
                } else if (at(cval, 0, "DEC-")
                        || at(cval, 0, "EL--")
                        || (nc > 1 && at(cval, 1, "LAT"))
                        || (nc > 2 && at(cval, 2, "LT"))) {
                    axlat = j;
                    prj = projection(cval);
                    lattype = cval.length() > 10 ? cval.substring(0, 10) : cval;
                } else {
                    earlySpectral(cval, key("CTYPE", j + 1, s));
                }
            }
        }

        /** C's {@code strncpy( prj, cval + 4, 4 )}: characters 5 to 8 of a CTYPE, as far as it goes. */
        private static String projection(String cval) {
            return cval.length() <= 4 ? "" : cval.substring(4, Math.min(cval.length(), 8));
        }

        /** The early paper III spectral translation of one CTYPE (fitschan.c:31602-31650). */
        private void earlySpectral(String cval, String keyword) {
            char x;
            if (at(cval, 4, "-WAV")) {
                x = 'W';
            } else if (at(cval, 4, "-FRQ")) {
                x = 'F';
            } else if (at(cval, 4, "-VEL")) {
                x = 'V';
            } else {
                return;
            }
            char p;
            if (at(cval, 0, "WAVE") || at(cval, 0, "VOPT") || at(cval, 0, "ZOPT")) {
                p = 'W';
            } else if (at(cval, 0, "FREQ") || at(cval, 0, "VRAD") || at(cval, 0, "ENER") || at(cval, 0, "WAVN")) {
                p = 'F';
            } else if (at(cval, 0, "VELO") || at(cval, 0, "BETA")) {
                p = 'V';
            } else {
                return;
            }
            String sprj;
            if (x == p) {
                sprj = cval.length() > 8 ? "----" : "    ";
            } else {
                sprj = "-" + x + "2" + p;
            }
            // Characters 5 to 8 are replaced; any after them are kept.
            replaceString(keyword, cval.substring(0, 4) + sprj + cval.substring(8));
        }

        /**
         * A zero CDELT becomes 1.0E-6 times the CDELT, which is still zero, and so 1.0, so that the
         * matrix stays invertible (fitschan.c:31678-31716). The comment in C says 1.0E-6 of the
         * CRVAL, but the code uses the CDELT.
         */
        private void zeroCdelt(char s) {
            FitsCardList.KeyFields kf = src.keyFields(versioned("CDELT%d", s), 1);
            if (kf.n == 0) {
                return;
            }
            for (int j = 0; j < kf.hi[0]; j++) {
                // An absent CDELT leaves C's value from the row before, which is not zero (or, for
                // the first row, uninitialised).
                Double cdeltj = getFloat2(key("CDELT", j + 1, s), false);
                if (cdeltj != null && cdeltj == 0.0) {
                    double dval = getFloat2(key("CDELT", j + 1, s), true);
                    double fixed = 1.0E-6 * dval;
                    if (fixed == 0.0) {
                        fixed = 1.0;
                    }
                    setFloat(key("CDELT", j + 1, s), fixed, "");
                }
            }
        }

        /**
         * The conversions that produce PCi_j keywords, made only if there are none
         * (fitschan.c:31718-31906): CDj_i, and then the 6-digit PCjjjiii or CROTAi. The legacy
         * CDjjjiii matrix is converted only for FITS-IRAF.
         */
        private void pcFamily(char s) {
            if (src.keyFields(versioned("PC%d_%d", s), 1).n > 0) {
                return;
            }
            boolean gotpcij = false;
            FitsCardList.KeyFields kf = src.keyFields(versioned("CD%d_%d", s), 1);
            if (kf.n > 0) {
                int naxis = kf.hi[0];
                for (int j = 0; j < naxis; j++) {
                    // The CDELT is the length of the row, signed so that the diagonal PC element
                    // is positive, so each row becomes a unit vector.
                    double rowsum2 = 0.0;
                    int diag = 1;
                    for (int i = 0; i < naxis; i++) {
                        Double dval = getFloat2(key2("CD", j + 1, i + 1, s), false);
                        if (dval != null) {
                            rowsum2 += dval * dval;
                            if (i == j) {
                                diag = dval >= 0.0 ? 1 : -1;
                            }
                        }
                    }
                    double cdelti = Math.sqrt(rowsum2) * diag;
                    setFloat(key("CDELT", j + 1, s), cdelti, "");
                    for (int i = 0; i < naxis; i++) {
                        // Every CD element defaults to zero, the diagonal included.
                        Double v = getFloat2(key2("CD", j + 1, i + 1, s), false);
                        double dval = v == null ? 0.0 : v;
                        if (cdelti != 0.0) {
                            dval /= cdelti;
                        }
                        setFloat(key2("PC", j + 1, i + 1, s), dval, "");
                        gotpcij = true;
                    }
                }
            }
            if (gotpcij) {
                return;
            }
            kf = src.keyFields(versioned("CDELT%d", s), 1);
            if (kf.n == 0) {
                return;
            }
            int naxis = kf.hi[0];
            // Both CROTA values are read, so neither reaches the output; the latitude one is read
            // second and so takes priority.
            Double crota = null;
            Double v = getFloat2(key("CROTA", axlon + 1, s), false);
            if (v != null) {
                crota = v;
            }
            v = getFloat2(key("CROTA", axlat + 1, s), false);
            if (v != null) {
                crota = v;
            }
            if (s == ' ' && src.keyFields("PC%3d%3d", 0).n > 0) {
                for (int j = 0; j < naxis; j++) {
                    for (int i = 0; i < naxis; i++) {
                        Double e = getFloat2(String.format(Locale.ROOT, "PC%03d%03d", j + 1, i + 1), false);
                        double dval;
                        if (e != null) {
                            dval = e;
                        } else {
                            dval = i == j ? 1.0 : 0.0;
                        }
                        setFloat(key2("PC", j + 1, i + 1, ' '), dval, "");
                    }
                }
            } else if (s == ' ' && axlat != -1 && axlon != -1 && crota != null) {
                double cosrota = Math.cos(crota * AST__DD2R);
                double sinrota = Math.sin(crota * AST__DD2R);
                double cdeltj = getFloat2(key("CDELT", axlat + 1, ' '), true);
                double cdelti = getFloat2(key("CDELT", axlon + 1, ' '), true);
                double lambda = cdeltj / cdelti;
                for (int i = 0; i < naxis; i++) {
                    double dval = (i == axlat || i == axlon) ? cosrota : 1.0;
                    setFloat(key2("PC", i + 1, i + 1, ' '), dval, "");
                }
                setFloat(key2("PC", axlat + 1, axlon + 1, ' '), sinrota / lambda, "");
                setFloat(key2("PC", axlon + 1, axlat + 1, ' '), -sinrota * lambda, "");
            }
        }

        /**
         * PROJPi keywords (fitschan.c:31910-31958). When there are PV values for the longitude axis
         * that look complete, C trusts them; otherwise it reads the PROJP values, which it
         * translates to PV values only for FITS-PC and FITS-IRAF.
         */
        private void projp() {
            FitsCardList.KeyFields kf = src.keyFields("PROJP%d", 1);
            if (kf.n == 0 || axlat == -1) {
                return;
            }
            boolean useProjp = true;
            FitsCardList.KeyFields pv = src.keyFields("PV%d_%d", 2);
            if (pv.n > 0) {
                useProjp = false;
                int lon = axlon + 1;
                if (pv.lo[0] <= lon && lon <= pv.hi[0]) {
                    boolean has0 = src.find(key2("PV", lon, 0, ' ')) != null;
                    if (src.find(key2("PV", lon, 1, ' ')) != null || src.find(key2("PV", lon, 2, ' ')) != null) {
                        if (!has0) {
                            useProjp = true;
                        }
                    } else if (has0) {
                        useProjp = true;
                    }
                }
            }
            if (useProjp) {
                for (int i = kf.lo[0]; i <= kf.hi[0]; i++) {
                    getFloat2(key("PROJP", i, ' '), false);
                }
            }
        }

        /**
         * CmVALi, CmPIXi, CmYPEi, CmNITi and CmELTi keywords (fitschan.c:31962-32100). C reads the
         * first four under every encoding but translates them only for FITS-PC and FITS-IRAF; it
         * translates CmELTi, and the PCjjjiii matrix with it, into CDELTis and PCj_is under every
         * encoding.
         */
        private void cmxxx() {
            readCmxxx("C%1dVAL%d", "VAL", FitsCardValue.Want.FLOAT);
            readCmxxx("C%1dPIX%d", "PIX", FitsCardValue.Want.FLOAT);
            readCmxxx("C%1dYPE%d", "YPE", FitsCardValue.Want.STRING);
            readCmxxx("C%1dNIT%d", "NIT", FitsCardValue.Want.STRING);
            FitsCardList.KeyFields kf = src.keyFields("C%1dELT%d", 2);
            if (kf.n == 0) {
                return;
            }
            int naxis = kf.hi[1];
            char ss = 'A';
            for (int m = kf.lo[0]; m <= kf.hi[0]; m++) {
                for (int j = 0; j < naxis; j++) {
                    double cdeltj = getFloat2("C" + m + "ELT" + (j + 1), true);
                    // A zero CDELT becomes one hundredth of the CRVAL, or 1.0 if that is zero.
                    if (cdeltj == 0.0) {
                        double dval = getFloat2(key("CRVAL", j + 1, ss), true);
                        cdeltj = 0.01 * dval;
                        if (cdeltj == 0.0) {
                            cdeltj = 1.0;
                        }
                    }
                    setFloat(key("CDELT", j + 1, ss), cdeltj, "");
                    for (int i = 0; i < naxis; i++) {
                        Double e = getFloat2(String.format(Locale.ROOT, "PC%03d%03d", j + 1, i + 1), false);
                        double dval;
                        if (e != null) {
                            dval = e;
                        } else {
                            dval = i == j ? 1.0 : 0.0;
                        }
                        setFloat(key2("PC", j + 1, i + 1, ss), dval, "");
                    }
                }
                ss++;
            }
        }

        /** Reads every {@code C<m><kind><i>} keyword the template's bounds cover, as C does. */
        private void readCmxxx(String template, String kind, FitsCardValue.Want want) {
            FitsCardList.KeyFields kf = src.keyFields(template, 2);
            if (kf.n == 0) {
                return;
            }
            for (int m = kf.lo[0]; m <= kf.hi[0]; m++) {
                for (int i = kf.lo[1]; i <= kf.hi[1]; i++) {
                    getValue2("C" + m + kind + i, want, false);
                }
            }
        }

        /**
         * EPOCH becomes EQUINOX when there is none, a string EQUINOX with a B or J prefix becomes
         * the Julian epoch, and an EQUINOX of zero becomes 1950 (fitschan.c:32102-32155).
         */
        private void epochEquinox() {
            Double epoch = getFloat2("EPOCH", false);
            if (epoch != null) {
                double dval = epoch == 0.0 ? 1950.0 : epoch;
                if (getString2("EQUINOX") == null) {
                    setFloat("EQUINOX", dval, "Reference equinox");
                }
            }
            String cval = getString2("EQUINOX");
            if (cval != null && !cval.isEmpty()) {
                char bj = cval.charAt(0);
                if (bj == 'B' || bj == 'J') {
                    // astSscanf( cval + 1, " %lf ", ... ) == 1: a number, with anything after it.
                    CScan.Num num = CScan.strtod(cval, skipSpace(cval, 1));
                    if (num != null) {
                        double dval = bj == 'B' ? Pal.epj(Pal.epb2d(num.value)) : num.value;
                        setFloat("EQUINOX", dval, commentOf("EQUINOX"));
                    }
                }
            }
            Double eq = getFloat2("EQUINOX", false);
            if (eq != null && eq == 0.0) {
                setFloat("EQUINOX", 1950.0, commentOf("EQUINOX"));
            }
        }

        /** The comment of the card with {@code keyword} that a write replaces, or empty. */
        private String commentOf(String keyword) {
            FitsCard c = cards.find(keyword);
            return c == null || c.comment == null ? "" : c.comment;
        }

        /**
         * DATE-OBS is read, so it does not reach the output, and gives MJD-OBS when there is none
         * (fitschan.c:32157-32184). MJD-OBS is read only as a test for its presence, so it is not
         * consumed.
         */
        private void dateObs() {
            String cval = getString2("DATE-OBS");
            if (cval == null) {
                return;
            }
            if (getValue(cards, "MJD-OBS", FitsCardValue.Want.FLOAT, false) != null) {
                return;
            }
            Double mjd = FitsDates.dateStrToMjd(cval);
            if (mjd != null) {
                setFloat("MJD-OBS", mjd, "Date of observation");
            }
        }

        /**
         * C's {@code SAOTrans} (fitschan.c:27020): translates the 13 polynomial coefficients per axis
         * ({@code COi_m}) by which SAO's platepos.c describes a distorted TAN projection into PVi_m
         * values, and replaces the CD, PC, CDELT and CROTA values, which the polynomial includes, by
         * unit values. Returns whether the header was such a one, which needs exactly two CTYPE
         * keywords and the first three coefficients on each axis; one with every coefficient but
         * the constant zero on an axis is warned of and not translated.
         */
        private boolean saoTrans() {
            if (src.keyFields("CTYPE%d", 0).n != 2) {
                return false;
            }
            double[][] co = new double[2][SAO_NC];
            boolean isSao = true;
            boolean ok = true;
            for (int i = 0; i < 2 && ok && isSao; i++) {
                ok = false;
                for (int m = 0; m < SAO_NC; m++) {
                    Double v = getFloatThis("CO" + (i + 1) + "_" + (m + 1), true);
                    if (v == null) {
                        if (m < 3) {
                            isSao = false;
                        }
                        break;
                    }
                    co[i][m] = v;
                    if (v != 0.0 && m > 0) {
                        ok = true;
                    }
                }
            }
            if (!isSao) {
                return false;
            }
            if (!ok) {
                warnAtEntry(
                        "badpv",
                        "This FITS header describes an SAO encoded distorted TAN projection, but all the distortion"
                                + " coefficients for at least one axis are zero.");
                return false;
            }
            for (int k = 0; k < SAO_PV_KEYS.length; k++) {
                int[] terms = SAO_PV_TERMS[k];
                double pv;
                if (terms.length == 2) {
                    pv = co[terms[0]][terms[1]];
                } else {
                    pv = 0.0;
                    pv += co[terms[0]][terms[1]];
                    pv += co[terms[2]][terms[3]];
                }
                setFloat(SAO_PV_KEYS[k], pv, "");
            }
            for (String k : SAO_SUBSUMED) {
                getFloatThis(k, true);
            }
            setFloat("PC1_1", 1.0, "");
            setFloat("PC2_2", 1.0, "");
            setFloat("CDELT1", 1.0, "");
            setFloat("CDELT2", 1.0, "");
            setFloat("PC1_2", 0.0, "");
            setFloat("PC2_1", 0.0, "");
            return true;
        }

        /**
         * The AIPS NCP projection becomes SIN with PV values giving the same projection, the CLASS
         * ATF projection becomes AIT, and the AIPS GLS projection becomes SFL
         * (fitschan.c:32212-32303).
         */
        private void ncpGlsAtf(char s) {
            if (ustrEq(prj, "-NCP")) {
                double dval = getFloat2(key("CRVAL", axlat + 1, s), true);
                double sinval = Math.sin(dval * AST__DD2R);
                if (sinval != 0.0) {
                    double cot = Math.cos(dval * AST__DD2R) / sinval;
                    setCelestialSuffix(s, "-SIN");
                    setFloat(key2("PV", axlat + 1, 2, s), cot, "");
                    setFloat(key2("PV", axlat + 1, 1, s), 0.0, "");
                }
            }
            if (ustrEq(prj, "-ATF")) {
                setCelestialSuffix(s, "-AIT");
            }
            if (ustrEq(prj, "-GLS")) {
                setCelestialSuffix(s, "-SFL");
                // C reads both CRVAL values to decide whether to shift the native latitude of the
                // fiducial point, a change it has disabled ("if( 0 && shifted )").
                getFloat2(key("CRVAL", axlon + 1, ' '), false);
                getFloat2(key("CRVAL", axlat + 1, ' '), false);
            }
        }

        /**
         * QVi_m keywords become PVi_m keywords, unless the PV keyword is already present; a TAN
         * projection with any QV keyword becomes TPN (fitschan.c:32306-32353).
         */
        private void qv(char s) {
            String template = versioned("QV%d_%d", s);
            List<FitsCard> found = new ArrayList<>();
            for (FitsCard c : src.cards()) {
                if (!c.isConsumed() && FitsMatch.matchKeyword(c.cName(), template, 0) != null) {
                    found.add(c);
                }
            }
            for (FitsCard c : found) {
                if (ustrEq(prj, "-TAN")) {
                    prj = "-TPN";
                    setCelestialSuffix(s, "-TPN");
                }
                cards.recordConsumed(c.keyword);
                String keyname = "P" + c.keyword.substring(1);
                if (getValue2(keyname, FitsCardValue.Want.FLOAT, false) == null) {
                    ret.add(keyname);
                    cards.setValue(keyname, c.value, c.cComment() == null ? "" : c.cComment());
                }
            }
        }

        /**
         * A TAN projection becomes TPN if C's {@code GetUsedPolyTan} says the distorted TAN
         * convention is in use, and a TPV projection always does (fitschan.c:32357-32372).
         */
        private void polyTan(char s) {
            if ((ustrEq(prj, "-TAN") && getUsedPolyTan(axlat + 1, axlon + 1, s)) || ustrEq(prj, "-TPV")) {
                prj = "-TPN";
                setCelestialSuffix(s, "-TPN");
            }
        }

        /**
         * C's {@code GetUsedPolyTan} (fitschan.c:14104): PolyTan if it is zero or more, and
         * otherwise whether the cards as supplied hold a PVi_m on the latitude axis, or one with m
         * above 4 on the longitude axis. If so, but every distortion coefficient (m from 1) of the
         * latitude axis, or else of the longitude axis, is zero, a warning is raised, the PV cards
         * of that axis are deleted, and the standard TAN convention is used.
         */
        private boolean getUsedPolyTan(int latax, int lonax, char s) {
            if (attrs.polyTan >= 0) {
                return attrs.polyTan != 0;
            }
            FitsCardList.KeyFields lat = src.keyFields(versioned("PV" + latax + "_%d", s), 1);
            FitsCardList.KeyFields lon = src.keyFields(versioned("PV" + lonax + "_%d", s), 1);
            if (!(lat.n > 0 || (lon.n > 0 && lon.hi[0] > 4))) {
                return false;
            }
            if (!anyDistortion(latax, lat.hi[0], s)) {
                warnAtEnd(
                        "badpv",
                        "This FITS header describes a distorted TAN projection, but all the distortion coefficients"
                                + " (the PVi_m headers) on the latitude axis are zero.");
                for (int m = 1; m <= lat.hi[0]; m++) {
                    deleteThis(key2("PV", latax, m, s));
                }
                return false;
            }
            if (!anyDistortion(lonax, lon.hi[0], s)) {
                warnAtEnd(
                        "badpv",
                        "This FITS header describes a distorted TAN projection, but all the distortion coefficients"
                                + " (the PVi_m headers) on the longitude axis are zero.");
                for (int m = 1; m <= lon.hi[0]; m++) {
                    deleteThis(key2("PV", lonax, m, s));
                }
                return false;
            }
            return true;
        }

        /**
         * Whether any of PV{@code ax}_1 to PV{@code ax}_{@code ubnd} is not zero, an absent one being
         * 1.0 for m = 1 and zero otherwise; read from the cards as supplied without consuming them.
         */
        private boolean anyDistortion(int ax, int ubnd, char s) {
            for (int m = 1; m <= ubnd; m++) {
                Double v = getFloatThis(key2("PV", ax, m, s), false);
                double pval = v != null ? v : (m == 1 ? 1.0 : 0.0);
                if (pval != 0.0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The IRAF ZPX projection becomes ZPN with the ZPX distortion code, and the {@code projpN=}
         * terms of the WAT strings of the longitude and then the latitude axis become PV values on
         * the latitude axis (fitschan.c:32376-32416).
         */
        private void zpx() {
            setCelestialSuffix(' ', "-ZPN-ZPX");
            for (int i = 0; i < 2; i++) {
                int iaxis = i != 0 ? axlat : axlon;
                String watmem = concatWat(src, iaxis + 1);
                if (watmem.isEmpty()) {
                    continue;
                }
                for (int iproj = 0; iproj < 10; iproj++) {
                    String tag = "projp" + iproj + "=";
                    int start = watmem.indexOf(tag);
                    if (start >= 0) {
                        // astSscanf( start, "projp%d=%lf", ... ): %lf skips white space first.
                        CScan.Num num = CScan.strtod(watmem, skipSpace(watmem, start + tag.length()));
                        if (num != null) {
                            setFloat(key2("PV", axlat + 1, iproj, ' '), num.value, "ZPN projection parameter");
                        }
                    }
                }
            }
        }

        /**
         * The IRAF TNX projection becomes TPN, with the PV values of the correction surfaces of the
         * WAT strings; a surface a TPN projection cannot express raises the {@code tnx} warning
         * (fitschan.c:32420-32470).
         */
        private void tnx() {
            setCelestialSuffix(' ', "-TPN");
            for (int i = 0; i < 2; i++) {
                int iaxis = i != 0 ? axlat : axlon;
                WatCoeffs c = watCoeffs(concatWat(src, iaxis + 1), i);
                if (c.ok) {
                    for (int k = 0; k < c.m.length; k++) {
                        setFloat(key2("PV", iaxis + 1, c.m[k], ' '), c.value[k], "TAN projection parameter");
                    }
                } else {
                    warnAtEnd("tnx", TNX_WARNING);
                }
            }
        }

        /**
         * A CAR projection has valid projection plane points only for native longitudes from -180
         * to +180 degrees, so CRPIX is moved by whole turns of longitude to bring the middle of the
         * image into that range (fitschan.c:32473-32503).
         */
        private void car() {
            double cdelti = getFloat2(key("CDELT", axlon + 1, ' '), true);
            Double crpix = getFloat2(key("CRPIX", axlon + 1, ' '), false);
            if (crpix == null) {
                return;
            }
            FitsCardValue.Got naxis = getValue(src, key("NAXIS", axlon + 1, ' '), FitsCardValue.Want.INT, false);
            int dim = naxis == null ? 0 : (int) naxis.i;
            if (cdelti != 0.0) {
                double offset = 0.5 * (dim + 1);
                double dval = offset + AST__DR2D * Pal.drange(AST__DD2R * (crpix - offset) * cdelti) / cdelti;
                setFloat(key("CRPIX", axlon + 1, ' '), dval, commentOf(key("CRPIX", axlon + 1, ' ')));
            }
        }

        /**
         * RESTFREQ becomes RESTFRQ when there is none, scaled to Hz if its comment says GHz or MHz
         * (fitschan.c:32505-32545).
         */
        private void restfreq(char s) {
            String keyname = versioned("RESTFREQ", s);
            Double v = getFloat2(keyname, false);
            if (v == null) {
                return;
            }
            double dval = v;
            FitsCard c = src.find(keyname);
            String comm = c == null ? null : c.cComment();
            if (comm != null) {
                if (comm.contains("GHz")) {
                    dval *= 1.0E9;
                    comm = "[Hz] Rest Frequency";
                } else if (comm.contains("MHz")) {
                    dval *= 1.0E6;
                    comm = "[Hz] Rest Frequency";
                }
            }
            String restfrq = versioned("RESTFRQ", s);
            if (getString2(restfrq) == null) {
                setFloat(restfrq, dval, comm == null ? "" : comm);
            }
        }

        /**
         * The first AIPS spectral CTYPE becomes its FITS-WCS paper III equivalent, setting SPECSYS,
         * and the first CTYPE of exactly {@code "LAMBDA  "} becomes WAVE (fitschan.c:32548-32575).
         * C sets SPECSYS, not the keyword of the axis description, for every description.
         */
        private void aipsSpectral(char s) {
            FitsCardList.KeyFields kf = src.keyFields(versioned("CTYPE%d", s), 1);
            if (kf.n == 0) {
                return;
            }
            for (int j = 0; j < kf.hi[0]; j++) {
                String keyname = key("CTYPE", j + 1, s);
                String cval = getString2(keyname);
                if (cval == null) {
                    continue;
                }
                String[] aips = FitsGuessEncoding.isAipsSpectral(cval);
                if (aips != null) {
                    replaceString(keyname, aips[0]);
                    setString("SPECSYS", aips[1], "");
                    return;
                } else if ("LAMBDA  ".equals(cval)) {
                    replaceString(keyname, "WAVE");
                    return;
                }
            }
        }

        /**
         * The CUNIT values "Hz", "Angstrom", "km/s" and "m/s" in any case, compared whole, trailing
         * spaces included, are given in the standard case (fitschan.c:32578-32610).
         */
        private void cunit(char s) {
            FitsCardList.KeyFields kf = src.keyFields(versioned("CUNIT%d", s), 1);
            if (kf.n == 0) {
                return;
            }
            for (int j = kf.lo[0] - 1; j <= kf.hi[0] - 1; j++) {
                String keyname = key("CUNIT", j + 1, s);
                String cval = getString2(keyname);
                if (cval == null || FitsCard.chrLen(cval) == 0) {
                    continue;
                }
                for (String unit : new String[] {"Hz", "Angstrom", "km/s", "m/s"}) {
                    if (ustrEq(cval, unit)) {
                        replaceString(keyname, unit);
                        break;
                    }
                }
            }
        }

        /**
         * Consumes the keywords of the IRAF mini-WCS system, IRAF's equivalent of the native
         * encoding, so that it does not conflict with the WCS read (fitschan.c:32618-32637).
         */
        void miniWcs() {
            for (FitsCard c : src.cards()) {
                if (c.isConsumed()) {
                    continue;
                }
                String name = c.cName();
                if (FitsMatch.matchKeyword(name, "WAT%d_%d", 0) != null
                        || FitsMatch.matchKeyword(name, "LTM%d_%d", 0) != null
                        || FitsMatch.matchKeyword(name, "LTV%d", 0) != null
                        || FitsMatch.matchKeyword(name, "WSV%d_LEN", 0) != null
                        || FitsMatch.matchKeyword(name, "WSV%d_%d", 0) != null) {
                    cards.recordConsumed(c.keyword);
                }
            }
        }
    }

    /** The index of the first character of {@code s} at or after {@code from} that is not white space. */
    private static int skipSpace(String s, int from) {
        int i = from;
        while (i < s.length() && CScan.isCSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
