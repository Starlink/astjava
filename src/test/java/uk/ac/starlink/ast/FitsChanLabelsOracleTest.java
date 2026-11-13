package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_labels_oracle.rs}: the axis Labels a FITS-WCS read
 * gives, against C. C's {@code WcsOthers} labels each axis no other function claims with its CNAME,
 * else with the comment of its CTYPE card, else with its CTYPE value; it uses the comments only when
 * more than one axis is left to it, each has a comment, and no comment repeats an earlier one.
 *
 * <p>For each header of {@code oracle/fitschan_labels.json} (from {@code
 * tools/oracle_fixtures/fitschan_labels_probe.c}) the transcript gives, for each Frame read, its
 * Domain and the Label of each axis.
 */
class FitsChanLabelsOracleTest {

    /** The transcript the port gives for {@code header}. */
    private static String transcript(String[] header) {
        FitsChan fc = FitsHeaders.chan(header);
        AstObject obj;
        try {
            obj = fc.read();
        } catch (AstException e) {
            return "! " + e.getStatus() + "\n";
        }
        if (obj == null) {
            return "= none\n";
        }
        assertInstanceOf(FrameSet.class, obj, "the read gave a " + obj.astClass());
        FrameSet fs = (FrameSet) obj;
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= fs.getNframe(); i++) {
            Frame frm = fs.getFrame(i);
            out.append("= frame ").append(i).append(' ').append(frm.getDomain()).append('\n');
            for (int axis = 1; axis <= frm.getNaxes(); axis++) {
                out.append("| ").append(frm.getLabel(axis)).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void labelsMatchC() {
        List<String> failed = new ArrayList<>();
        for (JsonNode c : ProbeData.load("fitschan_labels").get("CASES")) {
            String name = c.get(0).asText();
            String want = c.get(2).asText();
            String got;
            try {
                got = transcript(FitsHeaders.cards(c.get(1)));
            } catch (UnsupportedOperationException e) {
                failed.add(name + ": the read stops with " + FitsHeaders.outOfScope(e));
                continue;
            }
            if (!got.equals(want)) {
                failed.add(name + ":\n--- Java\n" + got + "--- C\n" + want);
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }
}
