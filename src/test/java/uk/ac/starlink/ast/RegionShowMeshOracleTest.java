package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_showmesh_oracle.rs}: the table {@link
 * Region#showMesh} writes, against C's {@code astShowMesh} (region.c {@code ShowMesh}): the
 * current-Frame boundary mesh points, with and without a title, unformatted and formatted by the
 * axis Format ({@code oracle/region_showmesh.json}, from {@code
 * tools/oracle_fixtures/region_showmesh_probe.c}). Each Region is read from C's dump, and the
 * table is what {@code astShowMesh} wrote to standard output.
 *
 * <p>A Circle's mesh is placed with {@code sin} and {@code cos}, which may differ in the last bit
 * between {@code java.lang.Math} and the C library, but the table gives each value to at most
 * seven significant figures, none of them within a few ulps of a rounding boundary, so it is
 * compared exactly. A Polygon's mesh in a plain Frame comes from arithmetic on its vertices. The
 * number of cases run is asserted.
 */
class RegionShowMeshOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_showmesh");

    /** The number of cases. */
    private static final int CASES_RUN = 7;

    @Test
    void showMeshTextMatchesC() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            String dump = c.get(1).textValue();
            run++;
            Region region = (Region) ChannelText.read(dump);
            String ttl = c.get(3).isNull() ? null : c.get(3).textValue();
            assertEquals(c.get(4).textValue(), region.meshText(c.get(2).booleanValue(), ttl), name);
        }
        assertEquals(CASES_RUN, run);
    }

    @Test
    // Java 8 has no PrintStream constructor taking a Charset; the saved standard output is
    // restored, not closed.
    @SuppressWarnings({"JdkObsolete", "PMD.CloseResource"})
    void showMeshWritesTheTableToStandardOutput() throws UnsupportedEncodingException {
        JsonNode c = null;
        for (JsonNode k : DATA.get("CASES")) {
            if ("sky_box_wrap_plain".equals(k.get(0).textValue())) {
                c = k;
            }
        }
        Region region = (Region) ChannelText.read(c.get(1).textValue());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream old = System.out;
        // MODERN-JAVA(10): new PrintStream(bytes, true, StandardCharsets.UTF_8)
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8.name()));
        try {
            region.showMesh(false, c.get(3).textValue());
        } finally {
            System.setOut(old);
        }
        assertEquals(c.get(4).textValue(), new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
}
