package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Epj2jd;
import uk.ac.starlink.ast.internal.erfa.Epv00;
import uk.ac.starlink.ast.internal.erfa.Pmat06;
import uk.ac.starlink.ast.internal.erfa.Rxpv;

/**
 * Earth velocity and position: {@code palEvp}.
 *
 * <p>Ported from Rust {@code pal/evp.rs}, itself ported from PAL's {@code palEvp} ({@code
 * pal/palEvp.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with permission:
 * Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory, Tim Jenness
 * and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Evp {

    private Evp() {}

    /**
     * The Earth's barycentric velocity (au per second) and position (au), then its heliocentric
     * velocity and position, as {@code {dvb, dpb, dvh, dph}}, at the TDB date {@code date} (a
     * Modified Julian Date), with respect to the mean equator and equinox of Julian epoch {@code
     * deqx}, or to the BCRS when {@code deqx} is zero or negative.
     */
    public static double[][] evp(double date, double deqx) {
        // BCRS PV-vectors; the date-range warning is ignored.
        Epv00.Result e = Epv00.epv00(Pal.PAL__MJD0, date);
        double[][] pvh = e.pvh;
        double[][] pvb = e.pvb;

        // Was precession to another equinox requested?
        if (deqx > 0.0) {
            // Yes: compute precession matrix from J2000.0 to deqx.
            double[] d = Epj2jd.epj2jd(deqx);
            double[][] r = Pmat06.pmat06(d[0], d[1]);

            // Rotate the PV-vectors.
            pvh = Rxpv.rxpv(r, pvh);
            pvb = Rxpv.rxpv(r, pvb);
        }

        // Return the required vectors.
        double[] dvb = new double[3];
        double[] dpb = new double[3];
        double[] dvh = new double[3];
        double[] dph = new double[3];
        for (int i = 0; i < 3; i++) {
            dvh[i] = pvh[1][i] / Pal.PAL__SPD;
            dvb[i] = pvb[1][i] / Pal.PAL__SPD;
            dph[i] = pvh[0][i];
            dpb[i] = pvb[0][i];
        }
        return new double[][] {dvb, dpb, dvh, dph};
    }
}
