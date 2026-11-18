package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frameset_variants_oracle.rs}: FrameSets whose current Frame
 * has variants, against C's dumps of them ({@code oracle/frameset_variants.json}, from {@code
 * tools/oracle_fixtures/frameset_variants_probe.c}), for a current Frame of each kind: a Frame, a
 * SkyFrame, a SpecFrame, a CmpFrame, and a Frame with Digits set. frame.c {@code Dump} writes the variants
 * FrameSet as {@code Vrnts}, last in the Frame class's part, so for a subclass of Frame it comes
 * before the {@code IsA Frame} marker, where {@code astLoadFrame} reads it back.
 *
 * <p>Each FrameSet built here is written with a default Channel and with Full = 1, and read back
 * and written again, each compared with C's text. C's {@code AddVariant} simplifies the variant
 * Mapping it stores (frameset.c:1824), so the dumps hold a ZoomMap. The FrameSet read from C's dump
 * must also have the variants, the selected variant and the transformation of the FrameSet built
 * here.
 */
class FrameSetVariantsOracleTest {

    private static final JsonNode CASES = ProbeData.load("frameset_variants").get("CASES");

    private static Frame basic(int naxes, String domain) {
        Frame f = new Frame(naxes);
        f.setDomain(domain);
        return f;
    }

    /** The probe's current Frame for {@code name}. */
    private static Frame current(String name) {
        switch (name) {
            case "frame":
                return basic(2, "CUR");
            case "skyframe":
                SkyFrame sky = new SkyFrame();
                sky.setDomain("SKY");
                return sky;
            case "specframe":
                SpecFrame spec = new SpecFrame();
                spec.setDomain("SPEC");
                return spec;
            case "cmpframe":
                return new CmpFrame(basic(1, "A"), basic(1, "B"));
            case "frame_digits":
                Frame f = basic(2, "CUR");
                f.setDigits(4);
                return f;
            default:
                throw new IllegalArgumentException("no case " + name);
        }
    }

    /**
     * A FrameSet from a Frame with Domain PIX to {@code name}'s current Frame, with a variant of it
     * two times larger.
     */
    private static FrameSet frameset(String name) {
        Frame frame = current(name);
        int naxes = frame.getNaxes();
        FrameSet fs = new FrameSet(basic(naxes, "PIX"));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(naxes), frame);
        fs.addVariant(new ZoomMap(naxes, 2.0), "Two");
        return fs;
    }

    /** The transformation of two points through {@code fs}, axis by axis, on its first one or two axes. */
    private static double[][] transform(FrameSet fs) {
        int naxes = fs.getNin();
        double[][] in = Arrays.copyOf(new double[][] {{1.5, 3.0}, {-2.25, 4.0}}, naxes);
        return fs.tranP(2, naxes, in, true, naxes);
    }

    /** The text a Channel with Full = 1 writes for {@code obj}. */
    private static String fullDump(AstObject obj) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Channel ch = new Channel(null, out);
        ch.setFull(1);
        try {
            ch.write(obj);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void dumpsMatchC() {
        int checked = 0;
        for (JsonNode c : CASES) {
            String name = c.get(0).asText();
            String cDump = c.get(1).asText();
            FrameSet fromC = assertInstanceOf(FrameSet.class, ChannelText.read(cDump), name);
            assertEquals(c.get(2).asText(), ChannelText.dump(fromC), name + ": C's dump read back");

            FrameSet fs = frameset(name);
            assertEquals(fromC.getAllVariants(), fs.getAllVariants(), name + ": AllVariants");
            assertEquals(fromC.getVariant(), fs.getVariant(), name + ": Variant");
            assertEquals(fromC.testVariant(), fs.testVariant(), name + ": Variant set");
            double[][] want = transform(fromC);
            double[][] got = transform(fs);
            for (int axis = 0; axis < want.length; axis++) {
                assertArrayEquals(want[axis], got[axis], name + ": axis " + (axis + 1));
            }

            String text = ChannelText.dump(fs);
            assertEquals(cDump, text, name + ": dump");
            assertEquals(c.get(2).asText(), ChannelText.dump(ChannelText.read(text)), name + ": read back");
            assertEquals(c.get(3).asText(), fullDump(fs), name + ": Full = 1");
            checked++;
        }
        assertEquals(CASES.size(), checked);
    }
}
