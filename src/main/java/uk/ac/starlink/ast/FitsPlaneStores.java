package uk.ac.starlink.ast;

/**
 * The axis descriptions C's {@code FsetToStore} makes from a FrameSet for a FITS write: one {@link
 * FitsStore} for each coordinate version letter, the primary description (a space) and the
 * alternates {@code A} to {@code Z}, as C's single {@code FitsStore} holds each item under the
 * letter of the description it belongs to. A writer takes the versions in letter order, as C's
 * writers loop over the letters.
 *
 * <p>A version's store holds whatever C's {@code AddVersion} stored under its letter, also when
 * the description could not be completed (an alternate whose pixel to intermediate world Mapping
 * is not linear has values from its axes but no CRPIX, which is how C's writers recognise it), and
 * when two descriptions are given the same letter, as C's {@code FsetToStore} can do, both are
 * stored in the one store, the second replacing the values both store.
 *
 * <p>Ported from Rust {@code fitschan/wcs_write/plane_stores.rs}, following fitschan.c {@code
 * FsetToStore}.
 */
final class FitsPlaneStores {

    /** C's {@code store->naxis}: the number of pixel axes, the base Frame's. */
    final int naxis;

    /** The store of each version, indexed by {@link #index}; null for a version with none. */
    private final FitsStore[] versions = new FitsStore[27];

    FitsPlaneStores(int naxis) {
        this.naxis = naxis;
    }

    /**
     * The index of version {@code s} in {@link #versions}: 0 for the primary description, 1 to 26
     * for {@code A} to {@code Z}.
     *
     * @throws AstException with status {@code AST__INTER} for any other character
     */
    private static int index(char s) {
        if (s == ' ') {
            return 0;
        }
        if (s >= 'A' && s <= 'Z') {
            return s - 'A' + 1;
        }
        throw Ast.err(
                AstException.AST__INTER,
                "FsetToStore(fitschan): AST internal error; coordinate version character '" + s + "' is invalid.");
    }

    /** The store of version {@code s}, or null if nothing has been stored for it. */
    FitsStore get(char s) {
        return versions[index(s)];
    }

    /**
     * C's {@code GetMaxS( &(store->crval) )}: the last version letter with a CRVAL value, or the
     * primary description's (a space) if no alternate has one.
     */
    char maxCrvalVersion() {
        for (char s = 'Z'; s >= 'A'; s--) {
            FitsStore st = get(s);
            if (st != null) {
                for (Double v : st.crval) {
                    if (v != null) {
                        return s;
                    }
                }
            }
        }
        return ' ';
    }

    /**
     * The store of version {@code s}, made empty if there is none, with room for at least {@code
     * naxes} axes.
     */
    FitsStore version(char s, int naxes) {
        int i = index(s);
        if (versions[i] == null) {
            versions[i] = new FitsStore(naxes);
        } else {
            versions[i].ensureAxes(naxes);
        }
        return versions[i];
    }

    /**
     * The version letters C's {@code FsetToStore} gives the Frames of a FrameSet: {@link #sid}
     * for each Frame, and the last letter handed out.
     */
    static final class Letters {
        /**
         * Indexed by Frame index (from 1; element 0 is unused): 0 for a Frame given no letter
         * (among them the base and current Frames), 1 for an IWC Frame, otherwise the letter.
         */
        final char[] sid;

        /**
         * The last letter the second pass handed out, or the character before {@code A} if it
         * handed out none; the descriptions of absolute coordinates added for offset SkyFrames
         * take the letters after it.
         */
        final char last;

        Letters(char[] sid, char last) {
            this.sid = sid;
            this.last = last;
        }
    }

    /**
     * C's assignment of version letters in {@code FsetToStore}: each Frame other than the base and
     * current Frames and the IWC Frames (those whose Domain begins {@code IWC}) takes its Ident if
     * that is a single upper case letter no earlier Frame has taken; then, unless AltAxes is
     * IDENT, each Frame still without one takes the next letter no Frame has, in Frame order.
     *
     * <p>When the letters run out C reads past the end of its table of letters; here a Frame
     * finding no free letter gets none, as C's test of the letter against {@code Z} then gives.
     *
     * @param altaxes C's AltAxes code, 0 ALL, 1 IDENT, 2 NONE
     */
    static Letters versionLetters(FrameSet fset, int ibase, int icurr, int altaxes) {
        int nfrm = fset.getNframe();
        char[] sid = new char[nfrm + 1];
        int[] frms = new int['Z' + 1];
        for (int ifrm = 1; ifrm <= nfrm; ifrm++) {
            sid[ifrm] = 0;
            if (ifrm != icurr && ifrm != ibase) {
                Frame frame = fset.getFrame(ifrm);
                if (AstObject.chrMatchN(frame.getDomain(), "IWC", 3)) {
                    sid[ifrm] = 1;
                } else {
                    String id = frame.getIdent();
                    if (id.length() == 1 && id.charAt(0) >= 'A' && id.charAt(0) <= 'Z' && frms[id.charAt(0)] == 0) {
                        frms[id.charAt(0)] = ifrm;
                        sid[ifrm] = id.charAt(0);
                    }
                }
            }
        }

        char s = 'A' - 1;
        if (altaxes != 1) {
            for (int ifrm = 1; ifrm <= nfrm; ifrm++) {
                if (ifrm != icurr && ifrm != ibase && sid[ifrm] == 0) {
                    do {
                        s++;
                    } while (s <= 'Z' && frms[s] != 0);
                    if (s <= 'Z') {
                        sid[ifrm] = s;
                        frms[s] = ifrm;
                    }
                }
            }
        }
        return new Letters(sid, s);
    }
}
