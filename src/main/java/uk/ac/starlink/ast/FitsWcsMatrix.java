package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mapping from pixel coordinates to intermediate world coordinates (IWC) of a FITS-WCS axis
 * description, as FITS-WCS Papers I and IV describe it:
 *
 * <pre>
 * pixel -&gt; [mapd1] -&gt; subtract CRPIX -&gt; [mapd2] -&gt; PC matrix -&gt; [mapd3] -&gt; CDELT scaling -&gt; [mapd4] -&gt; IWC
 * </pre>
 *
 * <p>The four optional {@code mapd} stages are the Paper IV distortion corrections C's {@code
 * DistortMaps} finds from the distortion codes of the CTYPE values. This is C's {@code
 * WcsIntWorld} (fitschan.c:38028).
 *
 * <p>Ported from Rust {@code fitschan/wcs_matrix.rs}.
 */
final class FitsWcsMatrix {

    private FitsWcsMatrix() {}

    /** The method C's errors and warnings name. */
    private static final String METHOD = "astRead(FitsChan)";

    /** The {@code zpx} warning text (fitschan.c:42139-42143). */
    private static final String ZPX_WARNING = "This FITS header includes, or was derived from, a ZPX projection which"
            + " requires unsupported IRAF-specific corrections. The WCS information may therefore be incorrect.";

    /**
     * The Mapping from pixel coordinates to IWC: C's {@code WcsIntWorld} (fitschan.c:38028). The
     * chain is {@code [mapd1] -> WcsShift -> [mapd2] -> WcsPCMatrix -> [mapd3] -> WcsCDeltMatrix ->
     * [mapd4]}, the {@code mapd} stages coming from {@link #distortMaps}.
     *
     * <p>{@code store} is changed: any distortion code is removed from its CTYPE values, so that
     * later steps see a plain FITS-WCS projection code. {@code cards} is the FitsChan's own card
     * list, which holds the IRAF {@code WAT} keywords. Warnings are added to {@code warnings}, and
     * any move of C's current card that decides where later warnings go is added to {@code
     * cursors}, as {@code {index of the first warning after the move, n}} with n -1 for
     * end-of-file.
     *
     * <p>C's {@code WcsIntWorld} adds a {@code badmat} warning when the PC matrix has no inverse,
     * but {@code WcsPCMatrix} has already reported that as an error, so C's {@code Warn}, which does
     * nothing once an error has been reported, never adds it; here the error is thrown.
     *
     * @throws AstException with status {@code AST__BDFTS} if the PC matrix cannot be inverted
     */
    static Mapping buildPixelToIwc(FitsStore store, FitsCardList cards, FitsWarnings warnings, List<int[]> cursors) {
        int naxes = store.naxis;
        Mapping[] dist = distortMaps(store, cards, naxes, warnings, cursors);
        Mapping result = dist[0];
        result = append(result, buildCrpixShift(store));
        if (dist[1] != null) {
            result = append(result, dist[1]);
        }
        result = append(result, buildPcMatrix(store));
        if (dist[2] != null) {
            result = append(result, dist[2]);
        }
        result = append(result, buildCdeltMatrix(store));
        if (dist[3] != null) {
            result = append(result, dist[3]);
        }
        return result;
    }

    /**
     * {@code next} in series after {@code acc}, or {@code next} when {@code acc} is null: C's
     * {@code if( ret ) ret = astCmpMap( ret, map1, 1, "" ); else ret = map1;}.
     */
    private static Mapping append(Mapping acc, Mapping next) {
        return acc == null ? next : new CmpMap(acc, next, true);
    }

    /**
     * C's {@code DistortMaps} (fitschan.c:8915): the Mappings that apply the Paper IV distortion
     * codes of the CTYPE values, at the four points of the chain C's {@code WcsIntWorld} splices
     * them into. Every distortion code found, recognised or not, is removed from the CTYPE values
     * in {@code store}.
     *
     * <p>{@code -SIP} (Spitzer) distortion of the first two axes was found, and removed from their
     * CTYPE values, when the keywords were read ({@link FitsWcsKeywords#extractSip}); the store
     * records it. A {@code -SIP} code on any other axis, more than two {@code -ZPX} axes, and any
     * other distortion code each raise one {@code distortion} warning.
     *
     * @return the four Mappings, {@code mapd1} to {@code mapd4}, each null when absent
     */
    private static Mapping[] distortMaps(
            FitsStore store, FitsCardList cards, int naxes, FitsWarnings warnings, List<int[]> cursors) {
        Mapping[] ret = new Mapping[4];

        // Spitzer distortion is applied to the translated pixel coordinates.
        if (store.hasSip()) {
            ret[1] = buildSipMapping(store);
        }

        // "-SIP" on an axis beyond the first two is not supported; C warns once and removes it.
        boolean warned = false;
        for (int i = 2; i < naxes; i++) {
            String ct = store.ctype[i];
            if (ct == null) {
                continue;
            }
            int nc = FitsCard.chrLen(ct) - 4;
            if (nc >= 0 && "-SIP".equals(ct.substring(nc))) {
                if (!warned) {
                    warned = true;
                    warnings.push(new FitsWarnings.Warning(
                            "distortion",
                            "The \"-SIP\" distortion code can only be used on axes 1 and 2, but was found in keyword"
                                    + " CTYPE" + (i + 1) + " (='" + ct + "'). The distortion will be ignored."));
                }
                store.ctype[i] = ct.substring(0, nc);
            }
        }

        // "-ZPX": IRAF distortion. SpecTrans has rewritten "-ZPX" as "-ZPN-ZPX", so the ZPN
        // projection is read in the normal way and only the distortion code is handled here.
        int nfound = 0;
        int[] foundAxes = new int[2];
        for (int i = 0; i < naxes; i++) {
            String ct = store.ctype[i];
            String[] parts = ct == null ? null : split433(ct);
            if (parts != null && "ZPX".equals(parts[2])) {
                if (nfound < 2) {
                    foundAxes[nfound] = i;
                }
                nfound++;
                store.ctype[i] = truncateAt(ct, 8);
            }
        }
        if (nfound > 2) {
            // The truncated CTYPE values already select the plain ZPN projection.
            warnings.push(new FitsWarnings.Warning(
                    "distortion",
                    "More than two axes were found with the \"-ZPX\" projection code. A ZPN projection will be"
                            + " used instead."));
        } else if (nfound == 2) {
            // ZPX distortion is applied to the translated, rotated and scaled IWC.
            Mapping m = zpxMapping(cards, naxes, foundAxes, warnings, cursors);
            if (m != null) {
                ret[3] = ret[3] == null ? m : new CmpMap(ret[3], m, true);
            }
        }

        // There are no other supported distortion codes. Remove any that remain, warning once.
        warned = false;
        for (int i = 0; i < naxes; i++) {
            String ct = store.ctype[i];
            String[] parts = ct == null ? null : split433(ct);
            if (parts == null) {
                continue;
            }
            if (!warned) {
                warned = true;
                warnings.push(new FitsWarnings.Warning(
                        "distortion",
                        "The header contains CTYPE values (e.g. CTYPE" + (i + 1) + " = '" + ct
                                + "') which include a distortion code \"-" + parts[2]
                                + "\". AST currently ignores this distortion. The code has been removed from the"
                                + " CTYPE values."));
            }
            store.ctype[i] = truncateAt(ct, 8);
        }
        return ret;
    }

    /**
     * A CTYPE value of the Paper IV "4-3-3" form split into its axis type, projection code and
     * distortion code: C's {@code 3 == astSscanf( ctype, "%4s-%3s-%3s", type, code, dist )}
     * (fitschan.c:9099). Each {@code %s} skips white space and reads from one character up to its
     * width of other characters, and each {@code -} must follow directly.
     *
     * @return the three fields, or null if the value does not have the form
     */
    private static String[] split433(String ctype) {
        String[] out = new String[3];
        int[] widths = {4, 3, 3};
        int at = 0;
        for (int k = 0; k < 3; k++) {
            if (k > 0) {
                if (at >= ctype.length() || ctype.charAt(at) != '-') {
                    return null;
                }
                at++;
            }
            while (at < ctype.length() && CScan.isCSpace(ctype.charAt(at))) {
                at++;
            }
            int start = at;
            while (at < ctype.length() && at - start < widths[k] && !CScan.isCSpace(ctype.charAt(at))) {
                at++;
            }
            if (at == start) {
                return null;
            }
            out[k] = ctype.substring(start, at);
        }
        return out;
    }

    /** C's {@code ctype[ n ] = 0}: the value ended at character {@code n}, if it is that long. */
    private static String truncateAt(String ct, int n) {
        return ct.length() > n ? ct.substring(0, n) : ct;
    }

    /**
     * C's {@code ZPXMapping} (fitschan.c:42010): the Mapping applying the {@code lngcor} and {@code
     * latcor} corrections the IRAF {@code WAT} keywords describe for the two {@code -ZPX} axes
     * {@code zpxaxes} (numbered from 0). The ZPN projection itself is read in the normal way; this
     * is only the polynomial correction on top of it, an inverted TPN WcsMap with FITSProj and
     * TPNTan cleared, or a UnitMap when neither axis has a correction.
     *
     * @return the Mapping, or null when the header uses IRAF features AST cannot express, having
     *     raised the {@code zpx} warning
     */
    private static Mapping zpxMapping(
            FitsCardList cards, int naxes, int[] zpxaxes, FitsWarnings warnings, List<int[]> cursors) {
        FitsSpecTrans.WatCoeffs[] coeffs = new FitsSpecTrans.WatCoeffs[2];
        boolean ok = true;

        // C's ConcatWAT searches for each WAT card from the start and ends on a search that fails,
        // which leaves the FitsChan at end-of-file, where the warnings raised from here on go.
        cursors.add(new int[] {warnings.size(), -1});

        for (int i = 0; i < 2; i++) {
            String watstr = FitsSpecTrans.concatWat(cards, zpxaxes[i] + 1);
            coeffs[i] = FitsSpecTrans.watCoeffs(watstr, i);
            ok = coeffs[i].ok;
            if (!ok) {
                break;
            }
        }
        if (!ok) {
            warnings.push(new FitsWarnings.Warning("zpx", ZPX_WARNING));
            return null;
        }
        if (coeffs[0].m.length == 0 && coeffs[1].m.length == 0) {
            return new UnitMap(naxes);
        }
        // The WcsMap corrects the ZPN WcsMap made later, so FITSProj is cleared to keep a write
        // from taking it for the header's projection, and TPNTan is cleared so that only the
        // polynomial part of the TPN projection is applied.
        WcsMap wcs = new WcsMap(naxes, WcsMap.AST__TPN, zpxaxes[0] + 1, zpxaxes[1] + 1);
        wcs.setInvert(true);
        wcs.setFITSProj(false);
        wcs.setTPNTan(false);
        for (int i = 0; i < 2; i++) {
            for (int k = 0; k < coeffs[i].m.length; k++) {
                wcs.setPV(zpxaxes[i], coeffs[i].m[k], coeffs[i].value[k]);
            }
        }
        return wcs;
    }

    /**
     * The WinMap subtracting CRPIX from pixel coordinates: C's {@code WcsShift}
     * (fitschan.c:39446), which maps the corners 0 and 1 of each axis to {@code -crpix} and {@code
     * 1 - crpix}, a missing CRPIX being 0.
     */
    static WinMap buildCrpixShift(FitsStore store) {
        int naxes = store.naxis;
        double[] c1In = new double[naxes];
        double[] c2In = new double[naxes];
        double[] c1Out = new double[naxes];
        double[] c2Out = new double[naxes];
        for (int j = 0; j < naxes; j++) {
            double crpix = store.crpix[j] != null ? store.crpix[j] : 0.0;
            c2In[j] = 1.0;
            c1Out[j] = -crpix;
            c2Out[j] = 1.0 - crpix;
        }
        return new WinMap(naxes, c1In, c2In, c1Out, c2Out);
    }

    /**
     * The MatrixMap of the PC matrix: C's {@code WcsPCMatrix} (fitschan.c:38417). Missing diagonal
     * elements are 1 and missing off-diagonal ones 0. A CD matrix in the header never reaches here:
     * {@code SpecTrans} has turned it into a PC matrix and a CDELT for each row.
     *
     * @throws AstException with status {@code AST__BDFTS} if the matrix cannot be inverted
     */
    private static MatrixMap buildPcMatrix(FitsStore store) {
        int naxes = store.naxis;
        double[] mat = new double[naxes * naxes];
        for (int i = 0; i < naxes; i++) {
            for (int j = 0; j < naxes; j++) {
                Double v = store.pc[i][j];
                mat[i * naxes + j] = v != null ? v : (i == j ? 1.0 : 0.0);
            }
        }
        MatrixMap mm = new MatrixMap(naxes, naxes, MatrixMap.FULL, mat);
        if (!mm.getTranInverse()) {
            throw Ast.err(
                    AstException.AST__BDFTS,
                    METHOD + ": Unusable rotation matrix (PC or CD) found in the FITS-WCS header - the matrix"
                            + " cannot be inverted.");
        }
        return mm;
    }

    /**
     * The diagonal MatrixMap of the CDELT values: C's {@code WcsCDeltMatrix} (fitschan.c:35889).
     * Missing values are 1. C reports an error if the MatrixMap has no inverse, but a diagonal
     * MatrixMap always has one, a zero value giving {@code AST__BAD} on its axis
     * (matrixmap.c:1531-1555), so a zero CDELT is not an error.
     */
    private static MatrixMap buildCdeltMatrix(FitsStore store) {
        int naxes = store.naxis;
        double[] diag = new double[naxes];
        for (int i = 0; i < naxes; i++) {
            diag[i] = store.cdelt[i] != null ? store.cdelt[i] : 1.0;
        }
        MatrixMap mm = new MatrixMap(naxes, naxes, MatrixMap.DIAGONAL, diag);
        if (!mm.getTranInverse()) {
            throw Ast.err(
                    AstException.AST__BDFTS,
                    METHOD + ": Unusable CDELT values found in the FITS-WCS header - one or more values are zero.");
        }
        return mm;
    }

    /**
     * The Mapping of SIP distortion: C's {@code SIPMapping} (fitschan.c:29597). In pixel offsets
     * from CRPIX, {@code U = u + sum(A_p_q u^p v^q)} and {@code V = v + sum(B_p_q u^p v^q)}, the
     * inverse likewise from the AP and BP coefficients. With no coefficients in either direction
     * the distortion is a UnitMap over every axis. Otherwise it is a PolyMap of the first two axes,
     * in parallel with a UnitMap of any others, as C's {@code AddUnitMaps} makes it.
     *
     * <p>With SipReplace set (the default) the inverse transformation SIP headers supply, often
     * inaccurate, or the missing transformation, is replaced by a polynomial fitted to samples of
     * the other one over three times the image size ({@code -dim} to {@code 2 dim}, or -1000 to
     * 2000 when NAXIS is unknown), C's {@code astPolyTran( pmap, ncoeff_f == 0, 0.0001, 0.01, 7,
     * lbnd, ubnd )}. If the fit fails to reach 0.01 pixel the PolyMap uses its iterative inverse,
     * with NiterInverse 6 and TolInverse 1.0E-8.
     */
    private static Mapping buildSipMapping(FitsStore store) {
        int naxes = store.naxis;
        List<double[]> forward = buildSipTerms(store.sipA, store.sipB);
        List<double[]> inverse = buildSipTerms(store.sipAp, store.sipBp);
        if (forward.isEmpty() && inverse.isEmpty()) {
            return new UnitMap(naxes);
        }
        PolyMap pmap = new PolyMap(2, 2, forward.size(), flatten(forward), inverse.size(), flatten(inverse));
        if (store.sipReplace) {
            double[] dim = {dimOf(store, 0), dimOf(store, 1)};
            double[] lbnd = {
                dim[0] != AstObject.AST__BAD ? -dim[0] : -1000.0, dim[1] != AstObject.AST__BAD ? -dim[1] : -1000.0
            };
            double[] ubnd = {
                dim[0] != AstObject.AST__BAD ? 2 * dim[0] : 2000.0, dim[1] != AstObject.AST__BAD ? 2 * dim[1] : 2000.0
            };
            PolyMap fitted = pmap.polyTran(forward.isEmpty(), 0.0001, 0.01, 7, lbnd, ubnd);
            if (fitted != null) {
                pmap = fitted;
            } else {
                pmap.setIterInverse(true);
                pmap.setNiterInverse(6);
                pmap.setTolInverse(1.0e-8);
            }
        }
        return addUnitMaps(pmap, naxes);
    }

    /** The NAXIS value of axis {@code i}, or {@code AST__BAD} if unknown, C's {@code dim[ i ]}. */
    private static double dimOf(FitsStore store, int i) {
        Double d = i < store.dims.length ? store.dims[i] : null;
        return d != null ? d : AstObject.AST__BAD;
    }

    /**
     * C's {@code AddUnitMaps( map, 0, naxes )} (fitschan.c:3197): {@code map}, of the first axes, in
     * parallel with a UnitMap of the axes above it up to {@code naxes}.
     */
    private static Mapping addUnitMaps(Mapping map, int naxes) {
        int nmap = map.getNin();
        return nmap < naxes ? new CmpMap(map, new UnitMap(naxes - nmap), false) : map;
    }

    private static double[] flatten(List<double[]> terms) {
        double[] out = new double[4 * terms.size()];
        for (int k = 0; k < terms.size(); k++) {
            System.arraycopy(terms.get(k), 0, out, 4 * k, 4);
        }
        return out;
    }

    /**
     * C's {@code GetMaxI} (fitschan.c:13610) for a SIP table: the highest first index with a row,
     * or -1 when there is none.
     */
    private static int sipMaxI(double[][] table) {
        int ret = -1;
        for (int i = 0; i < table.length; i++) {
            if (table[i].length > 0) {
                ret = i;
            }
        }
        return ret;
    }

    /**
     * C's {@code GetMaxJM} (fitschan.c:13397) for a SIP table: the highest second index holding a
     * value other than {@code AST__BAD} in any row, or -1 when there is none.
     */
    private static int sipMaxJm(double[][] table) {
        int ret = -1;
        for (double[] row : table) {
            int jm = row.length - 1;
            while (jm >= 0 && row[jm] == AstObject.AST__BAD) {
                jm--;
            }
            ret = Math.max(ret, jm);
        }
        return ret;
    }

    /**
     * The coefficient descriptions of one direction of SIP distortion: one of the two
     * coefficient-gathering loops of C's {@code SIPMapping} (fitschan.c:29684-29836), for the
     * tables of the first and second axes. Each description is {@code {coefficient, axis (from 1),
     * power of the first coordinate, power of the second}}, as {@code astPolyMap} takes them.
     *
     * <p>For each axis the powers swept run to the largest indices present ({@code GetMaxI} and
     * {@code GetMaxJM}); an absent coefficient is zero. The polynomial gives a correction to the
     * coordinate, whereas the Mapping is a direct transformation, so the term of the axis itself is
     * increased by one. Terms whose coefficient is zero are left out, and when the tables hold no
     * value at all the transformation is undefined and no terms are returned.
     */
    private static List<double[]> buildSipTerms(double[][] table0, double[][] table1) {
        List<double[]> terms = new ArrayList<>();
        boolean def = false;
        double[][][] tables = {table0, table1};
        for (int iax = 0; iax < 2; iax++) {
            double[][] table = tables[iax];
            int pmax = sipMaxI(table);
            int qmax = sipMaxJm(table);
            for (int p = 0; p <= pmax; p++) {
                for (int q = 0; q <= qmax; q++) {
                    double cof = q < table[p].length ? table[p][q] : AstObject.AST__BAD;
                    if (cof == AstObject.AST__BAD) {
                        cof = 0.0;
                    } else {
                        def = true;
                    }
                    if (p == 1 - iax && q == iax) {
                        cof += 1.0;
                    }
                    if (cof != 0.0) {
                        terms.add(new double[] {cof, iax + 1, p, q});
                    }
                }
            }
        }
        if (!def) {
            terms.clear();
        }
        return terms;
    }
}
