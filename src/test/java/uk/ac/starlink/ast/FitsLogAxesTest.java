package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * FITS-WCS {@code -LOG} axes, spectral and not, read and written as C reads and writes them
 * (fitschan.c {@code LogWcs} and {@code LogAxis}): each case of the probe {@code
 * probes/fitslog_probe.c} made here and its text compared with C's, the numbers within libm
 * rounding (the transformations go through {@code exp} and {@code log}).
 *
 * <p>A header is read, its FrameSet dumped and transformed, and written back in each FITS encoding
 * (C writes a {@code -LOG} axis that is not spectral with FITS-WCS, FITS-PC, FITS-IRAF, FITS-AIPS
 * and FITS-AIPS++, a spectral one only with the first three); each header written is read back
 * and transformed, so that the FrameSet read back transforms as the original. FrameSets whose world
 * axis is exponential in the pixel axis are written with FITS-WCS as {@code -LOG} axes.
 */
class FitsLogAxesTest {

    private static final Map<String, String> PROBE = ProbeOutput.load("fitslog_probe");

    private static final String[] ENCODINGS = {
        "FITS-WCS", "FITS-PC", "FITS-IRAF", "FITS-AIPS", "FITS-AIPS++", "FITS-CLASS"
    };

    private static final String[] NAXIS1 = {"NAXIS   = 1", "NAXIS1  = 100"};

    private static final String[] NAXIS1K = {"NAXIS   = 1", "NAXIS1  = 1024"};

    private static final String[] NAXIS2 = {"NAXIS   = 2", "NAXIS1  = 100", "NAXIS2  = 100"};

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    /** The probe's {@code trans}: pixel positions along the first axis, the others at 1. */
    private static void trans(List<String> out, FrameSet fs, boolean big) {
        double[] pix = {1.0, 50.0, 100.0, 512.0, 1024.0};
        int np = big ? 5 : 3;
        int nin = fs.getNin();
        double[][] in = new double[nin][np];
        for (int i = 0; i < np; i++) {
            in[0][i] = pix[i];
            for (int j = 1; j < nin; j++) {
                in[j][i] = 1.0;
            }
        }
        double[][] wcs = fs.transform(in, np, true);
        double[][] back = fs.transform(wcs, np, false);
        for (int i = 0; i < np; i++) {
            out.add("pix" + coords(in, i) + " -> wcs" + coords(wcs, i) + " -> pix" + coords(back, i));
        }
    }

    /** Point {@code i} of {@code cols}, each coordinate after a space. */
    private static String coords(double[][] cols, int i) {
        StringBuilder sb = new StringBuilder();
        for (double[] col : cols) {
            sb.append(' ').append(g(col[i]));
        }
        return sb.toString();
    }

    /** The probe's {@code writeback}. */
    private static void writeback(List<String> out, FrameSet fs, String enc, String[] naxis, boolean big) {
        out.add("-- write " + enc);
        FitsChan fc = new FitsChan();
        fc.setEncoding(enc);
        for (String card : naxis) {
            fc.putFits(card, false);
        }
        int n = fc.write(fs);
        out.add("wrote " + n);
        for (String card : FitsHeaders.foundCards(fc)) {
            out.add(ProbeOutput.trimLines(card));
        }
        if (n != 0) {
            fc.clear("Card");
            AstObject back = fc.read();
            out.add("-- read back " + (back != null ? back.astClass() : "nothing"));
            if (back != null) {
                FrameSet bfs = (FrameSet) back;
                out.add("domain " + bfs.getDomain());
                trans(out, bfs, big);
            }
        }
    }

    /** {@code lines}, each ending with a newline. */
    private static String text(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }

    /** The probe's {@code readhead}. */
    private static String readHead(String[] header, String[] naxis, boolean big) {
        List<String> out = new ArrayList<>();
        FitsChan fc = new FitsChan();
        for (String card : header) {
            fc.putFits(card, false);
        }
        fc.clear("Card");
        out.add("encoding " + fc.getEncoding());
        FrameSet fs = (FrameSet) fc.read();
        out.add("-- read");
        String dump = ProbeOutput.trimLines(ChannelText.dump(fs));
        out.add(dump.substring(0, dump.length() - 1));
        out.add("domain " + fs.getDomain());
        for (int i = 1; i <= fs.getNaxes(); i++) {
            out.add("symbol(" + i + ") " + fs.getSymbol(i));
        }
        trans(out, fs, big);
        for (String enc : ENCODINGS) {
            writeback(out, fs, enc, naxis, big);
        }
        return text(out);
    }

    private static String written(FrameSet fs, String[] naxis, boolean big) {
        List<String> out = new ArrayList<>();
        writeback(out, fs, "FITS-WCS", naxis, big);
        return text(out);
    }

    /**
     * The largest difference from C's of a pixel coordinate transformed back from world
     * coordinates. The inverse of a {@code -LOG} axis is {@code Sr log(S/Sr)}: with {@code Sr} near
     * 1.4e9, a difference of the last bit of the logarithm from C's libm is a difference near 3e-7
     * in the intermediate world coordinate, which a CDELT of 1e5 makes near 3e-12 pixels.
     */
    private static final double PIXEL_ATOL = 1e-11;

    /**
     * Whether {@code got} matches C's {@code want}: every line within libm rounding, but for the
     * pixel coordinates a {@code pix} line ends with, which need only be within {@link
     * #PIXEL_ATOL}.
     */
    private static boolean matchesC(String got, String want) {
        List<String> g = CValues.lines(got);
        List<String> w = CValues.lines(want);
        if (g.size() != w.size()) {
            return false;
        }
        for (int i = 0; i < g.size(); i++) {
            String gl = g.get(i);
            String wl = w.get(i);
            int gi = gl.lastIndexOf(" -> pix");
            int wi = wl.lastIndexOf(" -> pix");
            boolean ok;
            if (gl.startsWith("pix") && gi >= 0 && wi >= 0) {
                ok = CValues.nativeStringsWithinLibmRounding(gl.substring(0, gi), wl.substring(0, wi))
                        && CValues.nativeStringsWithin(gl.substring(gi), wl.substring(wi), PIXEL_ATOL);
            } else {
                ok = CValues.nativeStringsWithinLibmRounding(gl, wl);
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static void assertProbe(String name, String got) {
        String want = PROBE.get(name);
        if (want == null || !matchesC(got, want)) {
            assertEquals(want, got, name);
        }
    }

    /** The PolyMap of the Taylor series to degree 20 of {@code sr exp(slope p)}. */
    private static PolyMap exponential(double sr, double slope) {
        double[] coeffs = new double[21 * 3];
        double term = sr;
        for (int k = 0; k <= 20; k++) {
            coeffs[3 * k] = term;
            coeffs[3 * k + 1] = 1;
            coeffs[3 * k + 2] = k;
            term = term * slope / (k + 1);
        }
        PolyMap pm = new PolyMap(1, 1, 21, coeffs, 0, new double[0]);
        pm.set("IterInverse=1");
        return pm;
    }

    private static FrameSet frameSet(int nin, Mapping map, Frame wcs) {
        Frame pix = new Frame(nin);
        pix.setDomain("GRID");
        FrameSet fs = new FrameSet(pix);
        fs.addFrame(FrameSet.AST__BASE, map, wcs);
        return fs;
    }

    @Test
    void otherLogAxis() {
        assertProbe(
                "other_log",
                readHead(
                        new String[] {
                            "NAXIS   = 1",
                            "NAXIS1  = 100",
                            "CTYPE1  = 'ZZZZ-LOG'",
                            "CRPIX1  = 1",
                            "CRVAL1  = 10.0",
                            "CDELT1  = 0.1"
                        },
                        NAXIS1,
                        false));
    }

    @Test
    void otherLogAxisBesideALinearAxis() {
        assertProbe(
                "other_log_2d",
                readHead(
                        new String[] {
                            "NAXIS   = 2",
                            "NAXIS1  = 100",
                            "NAXIS2  = 100",
                            "CTYPE1  = 'XPOS'",
                            "CTYPE2  = 'YPOS-LOG'",
                            "CUNIT2  = 'V'",
                            "CRPIX1  = 1",
                            "CRPIX2  = 50",
                            "CRVAL1  = 3.0",
                            "CRVAL2  = 250.0",
                            "CDELT1  = 2.0",
                            "CDELT2  = 5.0"
                        },
                        NAXIS2,
                        false));
    }

    @Test
    void spectralLogAxis() {
        assertProbe(
                "freq_log",
                readHead(
                        new String[] {
                            "NAXIS   = 1",
                            "NAXIS1  = 1024",
                            "CTYPE1  = 'FREQ-LOG'",
                            "CUNIT1  = 'Hz'",
                            "CRPIX1  = 512",
                            "CRVAL1  = 1.4204E9",
                            "CDELT1  = 1.0E5",
                            "SPECSYS = 'BARYCENT'",
                            "RESTFRQ = 1.4204E9"
                        },
                        NAXIS1K,
                        true));
    }

    @Test
    void velocityLogAxis() {
        assertProbe(
                "vrad_log",
                readHead(
                        new String[] {
                            "NAXIS   = 1",
                            "NAXIS1  = 100",
                            "CTYPE1  = 'VRAD-LOG'",
                            "CUNIT1  = 'km/s'",
                            "CRPIX1  = 50",
                            "CRVAL1  = 1000.0",
                            "CDELT1  = 2.0",
                            "SPECSYS = 'LSRK'",
                            "RESTFRQ = 1.4204E9"
                        },
                        NAXIS1,
                        false));
    }

    /**
     * The fixture {@code wcsconv/inputs/freq-log.head}: its logarithmic increment is so small that
     * the axis is linear, and C writes it back as a linear FREQ axis in GHz.
     */
    @Test
    void freqLogFixtureIsWrittenAsLinear() {
        FitsChan fc = FitsHeaders.fixture("wcsconv/inputs/freq-log.head");
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        List<String> out = new ArrayList<>();
        trans(out, fs, true);
        writeback(out, fs, "FITS-WCS", NAXIS1K, true);
        assertProbe("freq_log_head", text(out));
    }

    @Test
    void exponentialAxisIsWrittenAsLog() {
        Frame world = new Frame(1);
        world.setDomain("WORLD");
        assertProbe("write_other_exp", written(frameSet(1, exponential(10.0, 0.01), world), NAXIS1, false));
    }

    @Test
    void exponentialFrequencyAxisIsWrittenAsLog() {
        SpecFrame sf = new SpecFrame();
        sf.set("System=FREQ");
        assertProbe("write_freq_exp", written(frameSet(1, exponential(1.4, 0.001), sf), NAXIS1, false));
    }

    @Test
    void mathMapFrequencyAxisIsWrittenAsLog() {
        MathMap mm = new MathMap(
                1, 1, new String[] {"s = 1.4204e9 * exp( p * 1.0e-4 )"}, new String[] {"p = 1.0e4 * log( s / 1.4204e9 )"
                });
        SpecFrame sf = new SpecFrame();
        sf.set("System=FREQ,Unit=Hz,StdOfRest=Bary,RestFreq=1.4204e9 Hz");
        assertProbe("write_mathmap_freq", written(frameSet(1, mm, sf), NAXIS1K, true));
    }

    @Test
    void logUnitFrequencyAxisIsWrittenAsLog() {
        SpecFrame sf = new SpecFrame();
        sf.set("System=FREQ,Unit=log(Hz)");
        CmpFrame cf = new CmpFrame(new Frame(1), sf);
        Mapping map = new CmpMap(
                new MatrixMap(2, 2, new double[] {1.0, 0.001}), new ShiftMap(new double[] {0.0, 8.95}), true);
        assertProbe("write_log_unit", written(frameSet(2, map, cf), NAXIS2, false));
    }
}
