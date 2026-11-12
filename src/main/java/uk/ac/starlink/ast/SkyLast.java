package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * The Local Apparent Sidereal Time for SkyFrame conversions, with C's tables of LAST values
 * already computed (skyframe.c {@code CalcLAST}, {@code GetCachedLAST} and {@code
 * SetCachedLAST}).
 *
 * <p>Every LAST computed exactly goes into a table, one for each observer position, DUT1 and
 * DTAI, and a later request at an epoch within 0.4 day of two tabulated epochs is answered by
 * linear interpolation between them; C puts the error at under 0.1 arc-second. A value served
 * from a table is brought into the range 0 to 2pi, while an exact one can be negative, since C
 * truncates the day with {@code (int)}.
 *
 * <p>C keeps the tables in the SkyFrame class, shared by every SkyFrame in the process behind a
 * lock. Here they are held per thread, so the conversion path takes no lock; within one thread
 * the results follow C's, including their dependence on what was converted before.
 *
 * <p>Ported from Rust {@code frames/sky_last.rs}.
 */
final class SkyLast {

    /** The LAST values computed for one observer, DUT1 and DTAI (C's {@code AstSkyLastTable}). */
    private static final class LastTable {
        final double obslat;
        final double obslon;
        final double obsalt;
        final double dut1;
        final double dtai;

        /** The epochs, increasing. */
        final List<Double> epoch = new ArrayList<>();

        /** The LAST at each epoch, each continuous with its neighbours rather than wrapped. */
        final List<Double> last = new ArrayList<>();

        LastTable(double obslon, double obslat, double obsalt, double dut1, double dtai) {
            this.obslat = obslat;
            this.obslon = obslon;
            this.obsalt = obsalt;
            this.dut1 = dut1;
            this.dtai = dtai;
        }

        /** Whether the table is for the given observer, within C's tolerances. */
        boolean isFor(double lon, double lat, double alt, double ut1, double tai) {
            return Math.abs(obslat - lat) < 2.0e-7
                    && Math.abs(obslon - lon) < 2.0e-7
                    && Math.abs(obsalt - alt) < 1.0
                    && Math.abs(dut1 - ut1) < 1.0e-5
                    && SkyFrame.equal(dtai, tai, 1.0e-5);
        }
    }

    /** The tables of the current thread. */
    private static final ThreadLocal<List<LastTable>> TABLES = ThreadLocal.withInitial(ArrayList::new);

    private SkyLast() {}

    /**
     * The LAST, in radians, at TDB MJD {@code epoch} for the given observer (C's {@code
     * CalcLAST}): from the table for the observer if it can give it, otherwise computed exactly
     * and added to the table.
     *
     * @param dtai TAI-UTC in seconds, or {@link AstObject#AST__BAD} to look it up
     */
    static double calcLast(double epoch, double obslon, double obslat, double obsalt, double dut1, double dtai) {
        double result = cachedLast(epoch, obslon, obslat, obsalt, dut1, dtai);
        if (result == AstObject.AST__BAD) {
            result = exactLast(epoch, obslon, obslat, obsalt, dut1, dtai);
            cacheLast(result, epoch, obslon, obslat, obsalt, dut1, dtai);
        }
        return result;
    }

    /**
     * The exact LAST: the Epoch converted from TDB to LAST, both as MJDs with an origin at the
     * integer part of the Epoch for accuracy. C converts between two TimeFrames; the Mapping their
     * conversion gives is this TimeMap (timeframe.c {@code MakeMap}, for frames that differ only in
     * their time scale), the observer's longitude negated as the TimeMap needs it.
     */
    private static double exactLast(
            double epoch, double obslon, double obslat, double obsalt, double dut1, double dtai) {
        double origin = (int) epoch;
        TimeMap map = new TimeMap();
        map.timeAdd("TDBTOTT", new double[] {origin, -obslon, obslat, obsalt, dtai});
        map.timeAdd("TTTOTAI", new double[] {origin});
        map.timeAdd("TAITOUTC", new double[] {origin, dtai});
        map.timeAdd("UTCTOUT", new double[] {dut1});
        map.timeAdd("UTTOGMST", new double[] {origin});
        map.timeAdd("GMSTTOLMST", new double[] {origin, -obslon, obslat});
        map.timeAdd("LMSTTOLAST", new double[] {origin, -obslon, obslat});
        double day = map.tran1(1, new double[] {epoch - origin}, true)[0];
        return (day - (int) day) * 2 * Math.PI;
    }

    /** The LAST at {@code epoch} from the table for the observer, or bad (C's {@code GetCachedLAST}). */
    private static double cachedLast(
            double epoch, double obslon, double obslat, double obsalt, double dut1, double dtai) {
        double result = AstObject.AST__BAD;
        LastTable table = findTable(obslon, obslat, obsalt, dut1, dtai);
        if (table != null) {
            List<Double> ep = table.epoch;
            List<Double> lp = table.last;
            int ilo = 0;
            int ihi = ep.size() - 1;
            while (ihi > ilo) {
                int itest = (ilo + ihi) / 2;
                if (ep.get(itest) >= epoch) {
                    ihi = itest;
                } else {
                    ilo = itest + 1;
                }
            }
            double close = 0.001 / 86400.0;
            double dep = ep.get(ilo) - epoch;
            if (ilo == 0 || dep <= 0.0) {
                if (Math.abs(dep) < close) {
                    result = lp.get(ilo);
                }
            } else if (ep.get(ilo) - ep.get(ilo - 1) < 0.4) {
                double e0 = ep.get(ilo - 1);
                double l0 = lp.get(ilo - 1);
                result = l0 + (epoch - e0) * (lp.get(ilo) - l0) / (ep.get(ilo) - e0);
            } else if (Math.abs(dep) < close) {
                result = lp.get(ilo);
            }
        }
        if (result != AstObject.AST__BAD) {
            while (result > 2 * Math.PI) {
                result -= 2 * Math.PI;
            }
            while (result < 0.0) {
                result += 2 * Math.PI;
            }
        }
        return result;
    }

    /**
     * Adds an exactly computed LAST to the table for the observer, creating the table if need be
     * (C's {@code SetCachedLAST}). The entry goes after every entry with an epoch no later than
     * its own, made continuous with the entry before it, or with the one after it when it is the
     * earliest.
     */
    private static void cacheLast(
            double last, double epoch, double obslon, double obslat, double obsalt, double dut1, double dtai) {
        LastTable table = findTable(obslon, obslat, obsalt, dut1, dtai);
        if (table == null) {
            table = new LastTable(obslon, obslat, obsalt, dut1, dtai);
            table.epoch.add(epoch);
            table.last.add(last);
            TABLES.get().add(table);
            return;
        }
        int at = table.epoch.size();
        while (at > 0 && table.epoch.get(at - 1) > epoch) {
            at--;
        }
        double ref = at > 0 ? table.last.get(at - 1) : table.last.get(0);
        double value = last;
        if (last > ref + Math.PI) {
            value = last - 2 * Math.PI;
        } else if (last < ref - Math.PI) {
            value = last + 2 * Math.PI;
        }
        table.epoch.add(at, epoch);
        table.last.add(at, value);
    }

    /** The first table for the given observer, or null. */
    private static LastTable findTable(double obslon, double obslat, double obsalt, double dut1, double dtai) {
        for (LastTable table : TABLES.get()) {
            if (table.isFor(obslon, obslat, obsalt, dut1, dtai)) {
                return table;
            }
        }
        return null;
    }
}
