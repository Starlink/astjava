package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.NativeClasses;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_channel_dump_oracle.rs}: Channel dumps against C's
 * (channel.c {@code Dump} and loader): every attribute unset, each set, and values the setters
 * change; each dump read back and dumped again; a hand-written dump holding values the setters
 * change; and each case written with Full = 1, which writes every unset value, less C's unset
 * {@code RefCnt} and {@code Nobj}, counts of pointers to the Object and of Objects of its class,
 * which this library does not have. The dumps are compared line for line with C's.
 *
 * <p>{@code oracle/channel_dump.json} comes from {@code tools/oracle_fixtures/channel_dump_probe.c}.
 * Its MocChan and FitsChan cases are out of scope and are not run: the {@code mocchan} cases ({@link
 * #MOCCHAN_CASES}) need MocChan, and the {@code fitschan} cases need FitsChan; the loads of each are
 * recognised by the class they hold. For the same reason {@code a_clone_shares_the_sink} and {@code
 * a_channel_read_from_a_dump_has_no_source} (MocChan), and {@code
 * a_dump_leaves_the_source_cards_in_the_fitschan} and {@code
 * a_keymap_holding_a_fitschan_round_trips} (FitsChan) are deferred.
 */
class ChannelDumpOracleTest {

    /** The cases of the probe made with a Channel. */
    private static final List<String> CHANNEL_CASES =
            Arrays.asList("channel", "channel_set", "channel_set_defaults", "channel_clamped", "channel_full_negative");

    /** The cases of the probe made with a MocChan, which is out of scope. */
    private static final List<String> MOCCHAN_CASES =
            Arrays.asList("mocchan", "mocchan_string", "mocchan_json", "mocchan_ident");

    /** The Channel each Channel case of the probe makes. */
    private static Channel object(String name) {
        Channel ch = new Channel(null, null);
        if ("channel".equals(name)) {
            return ch;
        } else if ("channel_set".equals(name)) {
            ch.setComment(false);
            ch.setFull(1);
            ch.setIndent(5);
            ch.setReportLevel(2);
            ch.setSkip(true);
            ch.setStrict(true);
        } else if ("channel_set_defaults".equals(name)) {
            ch.setComment(true);
            ch.setFull(0);
            ch.setIndent(3);
            ch.setReportLevel(1);
            ch.setSkip(false);
            ch.setStrict(false);
        } else if ("channel_clamped".equals(name)) {
            ch.setComment(true);
            ch.setFull(5);
            ch.setIndent(-2);
        } else if ("channel_full_negative".equals(name)) {
            ch.setFull(-4);
        } else {
            throw new IllegalArgumentException("no case " + name);
        }
        return ch;
    }

    /** Whether a case of the probe needs a class that is out of scope. */
    private static boolean outOfScope(String name) {
        if (CHANNEL_CASES.contains(name)) {
            return false;
        }
        assertTrue(MOCCHAN_CASES.contains(name) || name.startsWith("fitschan"), "unknown case " + name);
        return true;
    }

    private static List<String> strings(JsonNode lines) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : lines) {
            out.add(l.asText());
        }
        return out;
    }

    private static JsonNode consts() {
        return ProbeData.load("channel_dump");
    }

    @Test
    void dumpsMatchC() {
        int run = 0;
        for (JsonNode c : consts().get("DUMPS")) {
            String name = c.get(0).asText();
            if (outOfScope(name)) {
                continue;
            }
            run++;
            String text = ChannelText.dump(object(name));
            assertEquals(strings(c.get(1)), CValues.lines(text), name + ": dump");
            String again = ChannelText.dump(ChannelText.read(text));
            assertEquals(strings(c.get(2)), CValues.lines(again), name + ": dump read back");
        }
        assertEquals(CHANNEL_CASES.size(), run, "cases run");
    }

    @Test
    void fullDumpsMatchC() throws IOException {
        int run = 0;
        for (JsonNode c : consts().get("FULL_DUMPS")) {
            String name = c.get(0).asText();
            if (outOfScope(name)) {
                continue;
            }
            run++;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channel ch = new Channel(null, out);
            ch.setFull(1);
            ch.write(object(name));
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertEquals(strings(c.get(1)), CValues.lines(text), name);
        }
        assertEquals(CHANNEL_CASES.size(), run, "cases run");
    }

    @Test
    void loadsMatchC() {
        int run = 0;
        for (JsonNode c : consts().get("LOADS")) {
            String name = c.get(0).asText();
            String text = String.join("\n", strings(c.get(1)));
            List<String> missing = NativeClasses.missing(text);
            if (missing.contains("MocChan") || missing.contains("FitsChan")) {
                continue;
            }
            run++;
            List<String> dump = strings(c.get(2));
            int status = c.get(3).asInt();
            AstObject obj;
            try {
                obj = ChannelText.read(text);
            } catch (AstException e) {
                assertEquals(status, e.getStatus(), name + ": " + e.getMessage());
                assertTrue(dump.isEmpty(), name + ": C read an object");
                continue;
            }
            assertEquals(0, status, name + ": C reported an error");
            assertEquals(dump, CValues.lines(ChannelText.dump(obj)), name);
        }
        assertEquals(1, run, "cases run");
    }
}
