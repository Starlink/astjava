package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_show_tostring_oracle.rs}: the text of C's {@code astShow}
 * read back and written as C's {@code astToString} writes it ({@code oracle/show_tostring.json},
 * from {@code tools/oracle_fixtures/show_tostring_probe.c}). {@code astToString} writes through a
 * Channel with {@code Comment=0,Full=-1,Indent=0} (object.c {@code ToString}), which {@link
 * #toCompact} sets up.
 *
 * <p>The {@code box} case is skipped: it needs Box (Region, out of scope). The tests of {@code
 * AnyObject} conversions are of Rust-only API and are listed in {@code deferred/rust-tests.txt}.
 */
class ShowToStringOracleTest {

    /** The text C's {@code astToString} writes for {@code obj}. */
    private static String toCompact(AstObject obj) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Channel ch = new Channel(null, out);
        ch.set("Comment=0,Full=-1,Indent=0");
        try {
            ch.write(obj);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Whether a case needs a class this library does not provide. */
    private static boolean outOfScope(String name) {
        return "box".equals(name);
    }

    @Test
    void toNativeStringMatchesAstToString() {
        int run = 0;
        for (JsonNode c : ProbeData.load("show_tostring").get("CASES")) {
            String name = c.get(0).asText();
            if (outOfScope(name)) {
                continue;
            }
            AstObject obj = ChannelText.read(c.get(2).asText());
            assertEquals(c.get(1).asText(), obj.astClass(), name);
            assertEquals(c.get(3).asText(), toCompact(obj), name);
            run++;
        }
        assertEquals(3, run, "the in-scope cases the C probe recorded");
    }

    @Test
    void compactTextReadsBackToTheSameObject() {
        for (JsonNode c : ProbeData.load("show_tostring").get("CASES")) {
            String name = c.get(0).asText();
            if (outOfScope(name)) {
                continue;
            }
            String compact = c.get(3).asText();
            assertEquals(compact, toCompact(ChannelText.read(compact)), name);
        }
    }
}
