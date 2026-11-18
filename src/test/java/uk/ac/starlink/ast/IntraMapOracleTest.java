package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * The IntraMap tests of Rust {@code tests/test_intramap.rs} that compare with the C library, from
 * {@code oracle/intramap.json} (C probe {@code intramap_probe.c}, which registers a doubling
 * function "double" with SIMPFI and SIMPIF set and one without a forward transformation, "nofwd").
 *
 * <p>An IntraMap made here is named after its Transformer's class, so its dump is compared with
 * C's with the function name replaced; an IntraMap read from C's dump through a Channel that has
 * the function registered under C's name keeps that name, and is written back byte for byte.
 */
class IntraMapOracleTest {

    private static final JsonNode CONSTS = ProbeData.load("intramap");

    private static String want(String table, String name) {
        for (JsonNode c : CONSTS.get(table)) {
            if (c.get(0).textValue().equals(name)) {
                return c.get(1).textValue();
            }
        }
        throw new IllegalStateException("no case " + name + " in " + table);
    }

    private static double[] doubles(String name) {
        JsonNode n = CONSTS.get(name);
        double[] out = new double[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(n.get(i));
        }
        return out;
    }

    /** The text a Channel with Full set to {@code full} writes for {@code obj}. */
    private static String dump(AstObject obj, int full) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Channel ch = new Channel(null, out);
        ch.setFull(full);
        ch.write(obj);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** {@code text} with the function name of {@code map} replaced by C's {@code name}. */
    private static String renamed(String text, IntraMap map, String name) {
        return text.replace("Fname = \"" + map.getFname() + "\"", "Fname = \"" + name + "\"");
    }

    /** The IntraMap C's dump {@code text} describes, read with {@code t} registered as {@code name}. */
    private static IntraMap read(String text, String name, Transformer t) throws IOException {
        Channel ch = new Channel(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), null);
        ch.registerIntra(name, 1, 1, t);
        return assertInstanceOf(IntraMap.class, ch.read());
    }

    /**
     * The dumps of the probe's IntraMaps: the IntraFlag, an inverted IntraMap, and the {@code Fwd =
     * 0} C writes for a function without a forward transformation.
     */
    @Test
    void dumpsMatchTheCLibrary() throws IOException {
        for (int full : new int[] {0, 1}) {
            String table = full == 0 ? "DUMPS" : "FULL_DUMPS";
            IntraMap dbl = new IntraMap(new IntraMapTest.Doubler());
            dbl.setC("IntraFlag", "v1");
            assertEquals(want(table, "double_with_flag"), renamed(dump(dbl, full), dbl, "double"));
            dbl.invert();
            assertEquals(want(table, "double_inverted"), renamed(dump(dbl, full), dbl, "double"));
            IntraMap nofwd = new IntraMap(new IntraMapTest.NoFwd());
            assertEquals(want(table, "nofwd"), renamed(dump(nofwd, full), nofwd, "nofwd"));
        }
    }

    /** C's dumps read back through a Channel that knows their functions, and are written again. */
    @Test
    void cDumpsReadBack() throws IOException {
        for (int full : new int[] {0, 1}) {
            String table = full == 0 ? "DUMPS" : "FULL_DUMPS";
            IntraMap dbl = read(want(table, "double_with_flag"), "double", new IntraMapTest.Doubler());
            assertEquals("double", dbl.getFname());
            assertEquals("v1", dbl.getC("IntraFlag"));
            assertEquals(want(table, "double_with_flag"), dump(dbl, full));
            dbl.invert();
            assertEquals(want(table, "double_inverted"), dump(dbl, full));
            IntraMap inverted = read(want(table, "double_inverted"), "double", new IntraMapTest.Doubler());
            assertEquals(want(table, "double_inverted"), dump(inverted, full));
            IntraMap nofwd = read(want(table, "nofwd"), "nofwd", new IntraMapTest.NoFwd());
            assertEquals(want(table, "nofwd"), dump(nofwd, full));
        }
    }

    /** The transformation is applied in the direction the Invert flag makes of the one asked for. */
    @Test
    void transformsMatchTheCLibrary() {
        double[] input = doubles("INPUT");
        IntraMap dbl = new IntraMap(new IntraMapTest.Doubler());
        assertArrayEquals(doubles("FORWARD"), dbl.tran1(input.length, input, true));
        assertArrayEquals(doubles("INVERSE"), dbl.tran1(input.length, input, false));
        IntraMap inverted = (IntraMap) dbl.copy();
        inverted.invert();
        assertArrayEquals(doubles("FORWARD"), inverted.tran1(input.length, input, false));
    }
}
