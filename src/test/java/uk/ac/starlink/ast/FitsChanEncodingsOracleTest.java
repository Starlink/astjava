package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
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
 * <p>Only the FITS-WCS column runs: the encodings in {@link #NOT_WRITTEN}, whose writers this
 * library does not have, are pinned by number. A header whose read needs a class this library
 * does not have is in {@link #EXCLUDED} with the reason its read gives, measured, so the list is
 * checked both ways.
 */
class FitsChanEncodingsOracleTest {

    /** The encodings whose writers this library does not have. */
    private static final Set<String> NOT_WRITTEN =
            new HashSet<>(Arrays.asList("FITS-PC", "FITS-IRAF", "FITS-AIPS", "FITS-AIPS++", "FITS-CLASS", "DSS"));

    /** The number of writes of the encodings in {@link #NOT_WRITTEN}. */
    private static final int NOT_WRITTEN_WRITES = 150;

    /** Headers whose read stops for want of a class, with the reason the read gives. */
    private static final Map<String, String> EXCLUDED = excludedCases();

    private static Map<String, String> excludedCases() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String name : Arrays.asList(
                "freq_cube",
                "class_cube",
                "class_lonpole_180",
                "class_lonpole_90",
                "class_new_epoch",
                "source_zsource",
                "topocent",
                "velolsr_late",
                "velolsr_dup")) {
            m.put(name, "needs SpecFrame");
        }
        return Collections.unmodifiableMap(m);
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

    /** The reason the read of {@code header} stops for want of a class, or null. */
    private static String readNeeds(List<String> header) {
        try {
            read(new FitsChan(), header, "");
            return null;
        } catch (UnsupportedOperationException e) {
            return FitsHeaders.outOfScope(e);
        }
    }

    @Test
    void writesMatchC() {
        List<String> failed = new ArrayList<>();
        int run = 0;
        int notWritten = 0;
        for (JsonNode c : ProbeData.load("fitschan_encodings").get("CASES")) {
            String name = c.get(0).asText();
            List<String> header = FitsHeaders.strings(c.get(1));
            String epoch = c.get(2).asText();
            String needs = readNeeds(header);
            String listed = EXCLUDED.get(name);
            if (needs != null || listed != null) {
                if (needs == null || !needs.equals(listed)) {
                    failed.add(name + ": the read gives \"" + needs + "\" but it is listed as \"" + listed + "\"");
                }
                continue;
            }
            for (JsonNode w : c.get(3)) {
                String enc = w.get(0).asText();
                if (NOT_WRITTEN.contains(enc)) {
                    notWritten++;
                    continue;
                }
                run++;
                Written want = new Written(
                        w.get(1).asInt(),
                        w.get(2).asInt(),
                        FitsHeaders.strings(w.get(3)),
                        FitsHeaders.strings(w.get(4)));
                Written got = written(header, epoch, enc);
                boolean same = got.ret == want.ret
                        && got.ncard == want.ncard
                        && got.names.equals(want.names)
                        && FitsHeaders.cardsMatchC(
                                got.cards, want.cards, RUNTIME_MATH.getOrDefault(name, Collections.<String>emptySet()));
                if (!same) {
                    failed.add(name + " " + enc + ":\n--- Java\n" + got + "\n--- C\n" + want);
                }
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
        assertEquals(25 - EXCLUDED.size(), run, "FITS-WCS writes run");
        assertEquals(NOT_WRITTEN_WRITES - 6 * EXCLUDED.size(), notWritten, "writes of encodings not written");
    }
}
