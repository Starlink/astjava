package uk.ac.starlink.ast;

/**
 * The default Encoding of a FitsChan, chosen from the keywords of the cards a read has not
 * consumed, as C's {@code GetEncoding} (fitschan.c) chooses it.
 *
 * <p>Ported from Rust {@code fitschan/guess_encoding.rs}.
 */
final class FitsGuessEncoding {

    private FitsGuessEncoding() {}

    private static boolean kf(FitsCardList cards, String template) {
        return cards.keyFields(template, 0).n > 0;
    }

    /** Whether there is a RADECSYS, PROJPi or CmVALi keyword without its FITS-WCS equivalent. */
    private static boolean oldStyle(FitsCardList cards) {
        return (kf(cards, "RADECSYS") && !kf(cards, "RADESYS"))
                || (kf(cards, "PROJP%d") && !kf(cards, "PV%d_%d"))
                || kf(cards, "C%1dVAL%d");
    }

    /**
     * The encoding C's {@code GetEncoding} chooses when none is set, in the order of the code
     * (fitschan.c:12803-12890), which differs from that of the attribute's documentation.
     */
    static FitsChan.Encoding guess(FitsCardList cards) {
        boolean haswcs = kf(cards, "CTYPE%d%0c") || kf(cards, "CRPIX%d%0c") || kf(cards, "CRVAL%d%0c");
        boolean hascd = kf(cards, "CD%1d_%1d%0c");
        boolean haspc = kf(cards, "PC%1d_%1d%0c");
        if (kf(cards, "BEGAST%2f")) {
            return FitsChan.Encoding.NATIVE;
        } else if (haswcs && looksLikeClass(cards)) {
            return FitsChan.Encoding.FITS_CLASS;
        } else if (haswcs && hasAipsSpecAxis(cards)) {
            return hascd || kf(cards, "PROJP%d") || kf(cards, "LONPOLE") || kf(cards, "LATPOLE")
                    ? FitsChan.Encoding.FITS_AIPSPP
                    : FitsChan.Encoding.FITS_AIPS;
        } else if (kf(cards, "PLTRAH")) {
            return FitsChan.Encoding.DSS;
        } else if (haswcs && kf(cards, "PC%3d%3d")) {
            return FitsChan.Encoding.FITS_PC;
        } else if (haswcs && kf(cards, "CD%3d%3d")) {
            return FitsChan.Encoding.FITS_IRAF;
        } else if (haswcs && hascd) {
            return oldStyle(cards) ? FitsChan.Encoding.FITS_IRAF : FitsChan.Encoding.FITS_WCS;
        } else if (haswcs && !haspc && !hascd && oldStyle(cards)) {
            return FitsChan.Encoding.FITS_PC;
        } else if (haswcs && kf(cards, "CROTA%d")) {
            return FitsChan.Encoding.FITS_AIPS;
        } else if (haswcs && kf(cards, "CRVAL%d%0c")) {
            return FitsChan.Encoding.FITS_WCS;
        } else {
            return FitsChan.Encoding.NATIVE;
        }
    }

    /** C's {@code LooksLikeClass}: a DELTAV keyword and a velocity keyword. */
    private static boolean looksLikeClass(FitsCardList cards) {
        return kf(cards, "DELTAV")
                && (kf(cards, "VLSR")
                        || kf(cards, "VELO-OBS")
                        || kf(cards, "VELO-HEL")
                        || kf(cards, "VELO-EAR")
                        || kf(cards, "VELO-LSR"));
    }

    /** C's {@code HasAIPSSpecAxis}: whether a single-digit CTYPE holds an AIPS spectral axis type. */
    private static boolean hasAipsSpecAxis(FitsCardList cards) {
        FitsCardList.KeyFields k = cards.keyFields("CTYPE%1d", 1);
        if (k.n == 0) {
            return false;
        }
        for (int j = k.lo[0]; j <= k.hi[0]; j++) {
            FitsCard card = cards.firstVisible("CTYPE" + j);
            if (card == null) {
                continue;
            }
            FitsCardValue.Got got;
            try {
                got = FitsCardValue.cnvType(card.value, FitsCardValue.Want.STRING, 10, "");
            } catch (AstException e) {
                continue;
            }
            if (got != null && isAipsSpectral(got.s) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(String s, int from, String prefix) {
        return s.startsWith(prefix, from);
    }

    /**
     * C's {@code IsAIPSSpectral}: for a CTYPE of 8 characters (ignoring trailing spaces) of the form
     * {@code AAAA-BBB} AIPS uses for a spectral axis, the FITS-WCS CTYPE and SPECSYS it stands for;
     * otherwise null.
     */
    static String[] isAipsSpectral(String ctype) {
        int n = ctype.length();
        while (n > 0 && ctype.charAt(n - 1) == ' ') {
            n--;
        }
        if (n != 8) {
            return null;
        }
        String wctype;
        if (ctype.startsWith("FREQ")) {
            wctype = "FREQ    ";
        } else if (ctype.startsWith("VELO")) {
            wctype = "VRAD    ";
        } else if (ctype.startsWith("FELO")) {
            wctype = "VOPT-F2W";
        } else if (ctype.startsWith("WAVELENG")) {
            wctype = "WAVE    ";
        } else {
            return null;
        }
        String wspecsys;
        if (startsWith(ctype, 4, "-LSR") || startsWith(ctype, 4, "LSRK")) {
            wspecsys = "LSRK";
        } else if (startsWith(ctype, 4, "-LSD")) {
            wspecsys = "LSRD";
        } else if (startsWith(ctype, 4, "-HEL")) {
            wspecsys = "BARYCENT";
        } else if (startsWith(ctype, 4, "-EAR") || startsWith(ctype, 4, "-GEO")) {
            wspecsys = "GEOCENTR";
        } else if (startsWith(ctype, 4, "-OBS") || startsWith(ctype, 4, "-TOP")) {
            wspecsys = "TOPOCENT";
        } else {
            return null;
        }
        return new String[] {wctype, wspecsys};
    }
}
