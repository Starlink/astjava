package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_axis_count_oracle.rs}: how many pixel and WCS axes
 * a FITS-WCS read gives, against C. The pixel axes are counted from the highest CRPIX index in the
 * primary or any alternate axis description (C's {@code FitsToStore}), whatever NAXIS says; each
 * description has WCSAXES WCS axes, or as many as there are pixel axes; a description with another
 * number of axes is joined to the pixel Frame by a PermMap ({@code AddFrame}); and a header with no
 * CRPIX gives nothing ({@code MakeFitsFrameSet}).
 *
 * <p>For each header of {@code oracle/fitschan_axis_count.json} (from {@code
 * tools/oracle_fixtures/fitschan_axis_count_probe.c}) the test compares with C whether the read
 * gives a FrameSet, nothing or an error, Ncard after the read, the names of the cards left, the
 * number of axes of each Frame, and the current Frame's position for pixel (2, 3, 4, ...).
 *
 * <p>The cases in {@link #EXCLUDED} have a spectral axis, which needs SpecFrame; each must still
 * stop there.
 */
class FitsChanAxisCountOracleTest {

    /** The cases that cannot be read, with the reason the read gives. */
    private static final Map<String, String> EXCLUDED = exclusions();

    private static Map<String, String> exclusions() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String name : Arrays.asList(
                "three_ctypes",
                "wcsaxes3_npix2",
                "alt_fewer_axes",
                "spec_log_crval0",
                "spec_unknown_code",
                "spec_x2p_unknown_x",
                "spec_log")) {
            m.put(name, "needs SpecFrame");
        }
        return m;
    }

    /** What the port gives for {@code header}, in the form C's transcript is compared in. */
    private static String read(String[] header) {
        FitsChan fc = FitsHeaders.chan(header);
        String result;
        AstObject obj = null;
        try {
            obj = fc.read();
            result = obj == null ? "none" : "frameset";
        } catch (AstException e) {
            result = "error";
        }
        int ncard = fc.getNcard();
        fc.clear("Card");
        List<String> names = new ArrayList<>();
        String card;
        while ((card = fc.findFits("%f", true)) != null) {
            names.add(card.substring(0, Math.min(8, card.length())).trim());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(result).append(' ').append(ncard).append(' ').append(names);
        if (obj != null) {
            assertInstanceOf(FrameSet.class, obj, "the read gave a " + obj.astClass());
            FrameSet fs = (FrameSet) obj;
            int[] naxes = new int[fs.getNframe()];
            for (int i = 1; i <= naxes.length; i++) {
                naxes[i - 1] = fs.getFrame(i).getNaxes();
            }
            sb.append(' ').append(Arrays.toString(naxes));
        }
        return sb.toString();
    }

    /** The current Frame's position for pixel (2, 3, 4, ...) of the FrameSet {@code header} gives. */
    private static double[] point(String[] header) {
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.chan(header));
        double[] in = new double[fs.getNin()];
        for (int i = 0; i < in.length; i++) {
            in[i] = i + 2.0;
        }
        return FitsHeaders.tran(fs, in, true);
    }

    @Test
    void axisCountsMatchC() {
        List<String> failed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("fitschan_axis_count").get("CASES")) {
            String name = c.get(0).asText();
            seen.add(name);
            String[] header = FitsHeaders.cards(c.get(1));
            if (EXCLUDED.containsKey(name)) {
                String reason = EXCLUDED.get(name);
                try {
                    read(header);
                    failed.add(name + ": excluded (" + reason + ") but the read did not stop there");
                } catch (UnsupportedOperationException e) {
                    String got = FitsHeaders.outOfScope(e);
                    if (!reason.equals(got)) {
                        failed.add(name + ": excluded (" + reason + ") but the read stops with " + got);
                    }
                }
                continue;
            }
            run++;
            List<String> names = new ArrayList<>();
            for (JsonNode n : c.get(4)) {
                names.add(n.asText());
            }
            String result = c.get(2).asText();
            StringBuilder want = new StringBuilder();
            want.append(result).append(' ').append(c.get(3).asInt()).append(' ').append(names);
            double[] wantPoint = new double[c.get(6).size()];
            if ("frameset".equals(result)) {
                int[] naxes = new int[c.get(5).size()];
                for (int i = 0; i < naxes.length; i++) {
                    naxes[i] = c.get(5).get(i).asInt();
                }
                want.append(' ').append(Arrays.toString(naxes));
                for (int i = 0; i < wantPoint.length; i++) {
                    wantPoint[i] = ProbeData.toDouble(c.get(6).get(i));
                }
            }
            String got;
            try {
                got = read(header);
            } catch (UnsupportedOperationException e) {
                failed.add(name + ": the read stops with " + FitsHeaders.outOfScope(e));
                continue;
            }
            if (!got.equals(want.toString())) {
                failed.add(name + ":\n  Java " + got + "\n  C    " + want);
            } else if ("frameset".equals(result)) {
                double[] gotPoint = point(header);
                if (!CValues.cValuesEq(gotPoint, wantPoint)) {
                    failed.add(name + ": position " + Arrays.toString(gotPoint) + ", C gives "
                            + Arrays.toString(wantPoint));
                }
            }
        }
        assertTrue(seen.containsAll(EXCLUDED.keySet()), "every excluded case is in the C table");
        assertEquals(seen.size() - EXCLUDED.size(), run, "every case not excluded is run");
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }
}
