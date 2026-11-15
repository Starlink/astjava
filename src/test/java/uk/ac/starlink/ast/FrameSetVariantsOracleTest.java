package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frameset_variants_oracle.rs}: FrameSets whose current Frame
 * has variants, against C's dumps of them ({@code oracle/frameset_variants.json}, from {@code
 * tools/oracle_fixtures/frameset_variants_probe.c}), for a current Frame of each kind: a Frame, a
 * SkyFrame, a CmpFrame, and a Frame with Digits set. frame.c {@code Dump} writes the variants
 * FrameSet as {@code Vrnts}, last in the Frame class's part, so for a subclass of Frame it comes
 * before the {@code IsA Frame} marker, where {@code astLoadFrame} reads it back.
 *
 * <p>C's {@code AddVariant} simplifies the variant Mapping it stores (frameset.c:1824), so the
 * dumps of the FrameSet built here hold an unsimplified Mapping where C's hold a ZoomMap: the Rust
 * test's comparisons of that FrameSet's dump, and of its dump with Full = 1, with C's need
 * simplification and are left out. Instead, C's dump is read and written again, which must give
 * the dump C wrote after reading it back, and the FrameSet read from it must have the variants,
 * the selected variant and the transformation of the FrameSet built here. The FrameSet built here
 * must also read back from its own dump unchanged.
 *
 * <p>The probe's {@code specframe} case needs a SpecFrame, outside the first phase, and is skipped.
 */
class FrameSetVariantsOracleTest {

    private static final JsonNode CASES = ProbeData.load("frameset_variants").get("CASES");

    /** The cases skipped because they need a class outside the first phase. */
    private static final List<String> SKIPPED = Arrays.asList("specframe");

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

    /** The transformation of two points through {@code fs}, axis by axis. */
    private static double[][] transform(FrameSet fs) {
        double[][] in = {{1.5, 3.0}, {-2.25, 4.0}};
        return fs.tranP(2, 2, in, true, 2);
    }

    @Test
    void dumpsMatchC() {
        List<String> skipped = new ArrayList<>();
        int checked = 0;
        for (JsonNode c : CASES) {
            String name = c.get(0).asText();
            if (SKIPPED.contains(name)) {
                skipped.add(name);
                continue;
            }
            String cDump = c.get(1).asText();
            FrameSet fromC = assertInstanceOf(FrameSet.class, ChannelText.read(cDump), name);
            assertEquals(c.get(2).asText(), ChannelText.dump(fromC), name + ": C's dump read back");

            FrameSet fs = frameset(name);
            assertEquals(fromC.getAllVariants(), fs.getAllVariants(), name + ": AllVariants");
            assertEquals(fromC.getVariant(), fs.getVariant(), name + ": Variant");
            assertEquals(fromC.testVariant(), fs.testVariant(), name + ": Variant set");
            double[][] want = transform(fromC);
            double[][] got = transform(fs);
            for (int axis = 0; axis < 2; axis++) {
                assertArrayEquals(want[axis], got[axis], name + ": axis " + (axis + 1));
            }

            String text = ChannelText.dump(fs);
            assertEquals(text, ChannelText.dump(ChannelText.read(text)), name + ": read back");
            checked++;
        }
        assertEquals(SKIPPED, skipped);
        assertEquals(CASES.size() - SKIPPED.size(), checked);
    }
}
