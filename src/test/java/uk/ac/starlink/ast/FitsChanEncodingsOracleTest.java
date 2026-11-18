package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.FreshThread;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_encodings_oracle.rs}: what each FITS encoding
 * writes for a set of headers, against C's. Each header is read into a FrameSet, which is written
 * back to the same FitsChan with each encoding in turn, some cases first setting the FrameSet's
 * Epoch, or its SkyRef and SkyRefIs. Each encoding's writer writes nothing for a description it
 * cannot hold, and the write then leaves every card the FitsChan had, those the read consumed
 * included.
 *
 * <p>For each header and encoding, compared with C: the value the write returns, Ncard, the cards
 * not used, and the name of every card, from a native dump of the FitsChan. The cases are those of
 * {@code oracle/fitschan_encodings.json}, made by {@code
 * tools/oracle_fixtures/fitschan_encodings_probe.c}.
 *
 * <p>Every encoding's column runs. A write that stops for want of a class or an encoding this
 * library does not have would be in {@link #EXCLUDED}, by header and encoding, with the reason it
 * gives, measured, so the list is checked both ways; the header's other writes are compared. No
 * write stops so.
 */
class FitsChanEncodingsOracleTest {

    /**
     * Writes that stop for want of a class, keyed by header and encoding ({@code "<header>
     * <encoding>"}), with the reason they give.
     */
    private static final Map<String, String> EXCLUDED = excludedCases();

    private static Map<String, String> excludedCases() {
        return Collections.emptyMap();
    }

    /**
     * The keywords whose numbers depend on the runtime's elementary functions, by header, for the
     * FITS-WCS write. Setting SkyRef and SkyRefIs builds the same Mappings as C (the MatrixMaps
     * agree to the last bit), but the transforms through them differ from C's by 1 ULP, as
     * java.lang.Math's atan2 does from C's libm (atan2(0.3420201433256688, 0.9396926207859083) is
     * 0.34906585039886606 here and 0.349065850398866 in libm); {@code FitLine}'s least-squares
     * fit raises that to 1e-16 in the PC elements, small differences of terms of order 1 (C probe
     * {@code p5.c}, beside the task report). Rust licenses the same two writes
     * ({@code SPHMAP_DRIFT_LICENSED}).
     */
    private static final Map<String, Set<String>> RUNTIME_MATH = runtimeMath();

    private static Map<String, Set<String>> runtimeMath() {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("tan_offset_origin", new HashSet<>(Arrays.asList("PC1_2", "PC2_1")));
        m.put("tan_offset_pole", new HashSet<>(Arrays.asList("CRVAL2", "PC1_1", "PC1_2", "PC2_1", "PC2_2")));
        return Collections.unmodifiableMap(m);
    }

    /**
     * The DSS cards whose numbers depend on the runtime's elementary functions, by header, with
     * the absolute tolerance each is compared with. DSS writes the reference point as degrees (or
     * hours), minutes and seconds, so PLTRAS and PLTDECS hold the difference of CRVAL1 / 15 or
     * CRVAL2 from a whole number, times 3600: there, a difference of CRVAL in its last bits is
     * the number's leading digits. CRVAL is made as C makes it but for java.lang.Math's atan2,
     * which differs from C's libm by 1 ULP at identical arguments: in the forward SphMap ({@code
     * atan2(0.34202014332566877, 0.9396926207859083)}, 0x3fd657184ae74489 here and
     * 0x3fd657184ae74488 in libm, which is correctly rounded), and for the headers whose Epoch is
     * set, also in the FK5HZ step of the SlaMap that follows ({@code eraFk5hz}'s {@code eraC2s},
     * 0x3fc65716f92aed2c here and 0x3fc65716f92aed2b in libm); every other operation agrees to
     * the bit (C probes {@code p3.c}, {@code rep.c} and {@code at.c} of Task 9, beside the task
     * report). C's store then holds CRVAL (10, 20) one or two ULP from this library's, which the
     * FITS-WCS cards absorb. The tolerance is the difference 4 ULP of CRVAL (10, 20) makes, the
     * difference the cards of the wcsconv gate allow CRVAL itself: 4 ULP of 10 degrees times 240
     * seconds of time per degree, and 4 ULP of 20 degrees times 3600 arcseconds per degree.
     */
    private static final Map<String, Map<String, Double>> DSS_RUNTIME_MATH = dssRuntimeMath();

    private static Map<String, Map<String, Double>> dssRuntimeMath() {
        Map<String, Double> sexagesimal = new HashMap<>();
        sexagesimal.put("PLTRAS", 4 * Math.ulp(10.0) * 240.0);
        sexagesimal.put("PLTDECS", 4 * Math.ulp(20.0) * 3600.0);
        Map<String, Map<String, Double>> m = new HashMap<>();
        for (String name :
                Arrays.asList("tan_fk5", "tpn", "alt_pc", "alt_j", "tan_new_epoch", "tan_new_epoch_no_mjd", "cname")) {
            m.put(name, Collections.unmodifiableMap(sexagesimal));
        }
        return Collections.unmodifiableMap(m);
    }

    /** The outcome of one write, in the form C's probe prints it. */
    private static final class Written {
        final int ret;
        final int ncard;
        final List<String> cards;
        final List<String> names;

        Written(int ret, int ncard, List<String> cards, List<String> names) {
            this.ret = ret;
            this.ncard = ncard;
            this.cards = cards;
            this.names = names;
        }

        @Override
        public String toString() {
            return "wrote " + ret + ", Ncard " + ncard + "\n" + String.join("\n", cards) + "\nnames " + names;
        }
    }

    /** The FrameSet {@code header} reads as, after the case's change of Epoch or SkyRef. */
    private static AstObject read(FitsChan fc, List<String> header, String epoch) {
        for (String card : header) {
            fc.putFits(card, false);
        }
        fc.clear("Card");
        AstObject fs = fc.read();
        if (fs != null && epoch.startsWith("SKYREF ")) {
            // SkyRef(1), SkyRef(2) and SkyRefIs, each set through the FrameSet, one at a time.
            String[] words = CValues.splitWhitespace(epoch.substring(7));
            fs.setD("SkyRef(1)", Double.parseDouble(words[0]));
            fs.setD("SkyRef(2)", Double.parseDouble(words[1]));
            fs.setC("SkyRefIs", words[2]);
        } else if (fs != null && !epoch.isEmpty()) {
            fs.setC("Epoch", epoch);
        }
        return fs;
    }

    /** The header written with {@code enc} into the FitsChan it was read from. */
    private static Written written(List<String> header, String epoch, String enc) {
        FitsChan fc = new FitsChan();
        AstObject fs = read(fc, header, epoch);
        fc.setEncoding(enc);
        int ret = fs != null ? fc.write(fs) : -1;
        int ncard = fc.getNcard();
        List<String> cards = FitsHeaders.foundCards(fc);
        List<String> names = new ArrayList<>();
        for (String line : CValues.lines(ChannelText.dump(fc))) {
            if (line.startsWith("    Nm")) {
                int q = line.indexOf('"');
                names.add(line.substring(q + 1, line.indexOf('"', q + 1)));
            }
        }
        return new Written(ret, ncard, cards, names);
    }

    /**
     * The probe makes every case's writes in order in one process, and the FITS-CLASS writes
     * convert the reference point to azimuth and elevation with the LAST from SkyFrame's tables of values
     * already computed, which the earlier cases' conversions fill; the tables are kept per thread,
     * so the writes run in the probe's order on a new thread, whose tables are empty, as the
     * probe's were.
     */
    @Test
    void writesMatchC() throws InterruptedException {
        FreshThread.assertPasses(FitsChanEncodingsOracleTest::compareWrites);
    }

    /** Every case's writes, in the probe's order, compared with C's. */
    private static void compareWrites() {
        List<String> failed = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("fitschan_encodings").get("CASES")) {
            String name = c.get(0).asText();
            List<String> header = FitsHeaders.strings(c.get(1));
            String epoch = c.get(2).asText();
            for (JsonNode w : c.get(3)) {
                String enc = w.get(0).asText();
                String key = name + " " + enc;
                String listed = EXCLUDED.get(key);
                String needs = null;
                Written got = null;
                try {
                    got = written(header, epoch, enc);
                } catch (UnsupportedOperationException e) {
                    needs = FitsHeaders.outOfScope(e);
                }
                if (needs != null || listed != null) {
                    if (needs == null || !needs.equals(listed)) {
                        failed.add(key + ": the write gives \"" + needs + "\" but it is listed as \"" + listed + "\"");
                    }
                    continue;
                }
                run++;
                Written want = new Written(
                        w.get(1).asInt(),
                        w.get(2).asInt(),
                        FitsHeaders.strings(w.get(3)),
                        FitsHeaders.strings(w.get(4)));
                boolean same = got.ret == want.ret
                        && got.ncard == want.ncard
                        && got.names.equals(want.names)
                        && FitsHeaders.cardsMatchC(
                                got.cards,
                                want.cards,
                                RUNTIME_MATH.getOrDefault(name, Collections.<String>emptySet()),
                                "DSS".equals(enc)
                                        ? DSS_RUNTIME_MATH.getOrDefault(name, Collections.<String, Double>emptyMap())
                                        : Collections.<String, Double>emptyMap());
                if (!same) {
                    failed.add(name + " " + enc + ":\n--- Java\n" + got + "\n--- C\n" + want);
                }
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
        assertEquals(7 * 25 - EXCLUDED.size(), run, "writes run, seven encodings for each header");
    }
}
