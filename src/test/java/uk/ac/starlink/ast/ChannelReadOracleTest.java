package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_channel_read_oracle.rs}: a Channel's reads against C's
 * (channel.c {@code Read}, {@code ReadClassData}, {@code GetNextData} and {@code ClearValues}):
 * one Object per read, the next starting where the last stopped; with Skip set anything before a
 * {@code Begin} ignored, and without it an error; the errors for input that ends inside an Object,
 * an unknown class and a misplaced {@code IsA}; and the warnings for items no class reads, which
 * Strict makes errors, after which the next read starts where C's would.
 *
 * <p>{@code oracle/channel_read.json} comes from {@code tools/oracle_fixtures/channel_read_probe.c}:
 * per case, the Channel options, the text, and for each read the class of the Object read (empty
 * if none), the status and the warnings.
 */
class ChannelReadOracleTest {

    /** One read as the probe prints it: the class read, the status and the warnings. */
    private static List<Object> read(Channel ch) {
        String cls = "";
        int status = 0;
        try {
            AstObject obj = ch.read();
            if (obj != null) {
                List<String> lines = CValues.lines(ChannelText.dump(obj));
                String[] words = CValues.splitWhitespace(lines.isEmpty() ? "" : lines.get(0));
                cls = words.length > 1 ? words[1] : "";
            }
        } catch (AstException e) {
            status = e.getStatus();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Arrays.asList(cls, status, ch.warningTexts());
    }

    /** A Channel made as the probe makes it, with {@code options}, reading {@code lines}. */
    private static Channel channel(String options, JsonNode lines) {
        List<String> text = new ArrayList<>();
        for (JsonNode l : lines) {
            text.add(l.asText());
        }
        Channel ch = ChannelText.reader(String.join("\n", text));
        for (String option : CValues.splitWhitespace(options.replace(',', ' '))) {
            if ("Skip=1".equals(option)) {
                ch.setSkip(true);
            } else if ("Strict=1".equals(option)) {
                ch.setStrict(true);
            } else if ("ReportLevel=0".equals(option)) {
                ch.setReportLevel(0);
            } else {
                throw new IllegalArgumentException("no option " + option);
            }
        }
        return ch;
    }

    /** C's reads of a case. */
    private static List<List<Object>> want(JsonNode reads) {
        List<List<Object>> out = new ArrayList<>();
        for (JsonNode r : reads) {
            List<String> warnings = new ArrayList<>();
            for (JsonNode w : r.get(2)) {
                warnings.add(w.asText());
            }
            out.add(Arrays.asList(r.get(0).asText(), r.get(1).asInt(), warnings));
        }
        return out;
    }

    /** The reads of a case made here, as many as C made. */
    private static List<List<Object>> got(JsonNode c) {
        Channel ch = channel(c.get(1).asText(), c.get(2));
        List<List<Object>> out = new ArrayList<>();
        for (int i = 0; i < c.get(3).size(); i++) {
            out.add(read(ch));
        }
        return out;
    }

    private static JsonNode cases() {
        return ProbeData.load("channel_read").get("CASES");
    }

    @Test
    void readsMatchC() {
        List<String> failed = new ArrayList<>();
        for (JsonNode c : cases()) {
            String name = c.get(0).asText();
            List<List<Object>> want = want(c.get(3));
            List<List<Object>> got = got(c);
            for (int i = 0; i < want.size(); i++) {
                if (!want.get(i).equals(got.get(i))) {
                    failed.add(name + " read " + (i + 1) + ": got " + got.get(i) + ", C " + want.get(i));
                }
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }
}
