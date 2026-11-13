package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frameset_variant_link_oracle.rs}: selecting a variant of a
 * Frame whose node's Mapping is used in its inverse direction, against C ({@code
 * oracle/frameset_variant_link.json}, from {@code tools/oracle_fixtures/frameset_variant_link_probe.c}).
 *
 * <p>A FrameSet joins Frame GRID to the current Frame B of a FrameSet A -> B, so adding it re-roots
 * A -> B at B and reverses the link to A, which is written {@code Inv2 = 1}. Selecting a variant
 * replaces the Mapping of the current Frame's node with that Mapping, used as the link says,
 * followed by the change of variant, simplified; the link then records the Invert of the result.
 * Each case adds a variant to Frame A or B and selects the Frame's own variant again.
 *
 * <p>The cases whose A -> B link is a MathMap need a class outside the first phase and are skipped.
 * {@code selecting_a_variant_matches_c} compares the transformations after adding the variant and
 * after selecting the Frame's own variant again. Its comparisons of the dumps are left out, as they
 * need simplification: C's {@code AddVariant} (frameset.c:1824) and {@code SetVariant}
 * (frameset.c:10075) store the Mappings {@code astSimplify} makes. The C dumps are each read back
 * and written again unchanged, and the FrameSet read from each gives C's transformations.
 */
class FrameSetVariantLinkOracleTest {

    private static final JsonNode CASES =
            ProbeData.load("frameset_variant_link").get("CASES");

    private static Frame frame(String domain) {
        Frame f = new Frame(1);
        f.setDomain(domain);
        return f;
    }

    /** The transformations of 3 from Frame 1 to Frames 2 and 3. */
    private static double[] transforms(FrameSet fs) {
        double[] out = new double[2];
        for (int to = 2; to <= 3; to++) {
            out[to - 2] = fs.getMapping(1, to).tran1(1, new double[] {3.0}, true)[0];
        }
        return out;
    }

    private static Mapping variantMap(String variant) {
        switch (variant) {
            case "unit":
                return new UnitMap(1);
            case "shift":
                return new ShiftMap(new double[] {5.0});
            case "zoom":
                return new ZoomMap(1, 3.0);
            default:
                throw new IllegalArgumentException("variant " + variant);
        }
    }

    /** The outer FrameSet of a case, with the variant added. */
    private static FrameSet build(int current, String variant) {
        FrameSet outer = new FrameSet(frame("GRID"));
        FrameSet inner = new FrameSet(frame("A"));
        inner.addFrame(FrameSet.AST__BASE, new ShiftMap(new double[] {10.0}), frame("B"));
        outer.addFrame(FrameSet.AST__BASE, new ZoomMap(1, 2.0), inner);
        outer.setCurrent(current);
        outer.addVariant(variantMap(variant), "V2");
        return outer;
    }

    private static double[] doubles(JsonNode arr) {
        double[] out = new double[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(arr.get(i));
        }
        return out;
    }

    /** The cases whose A -> B link is a ShiftMap, those the first phase can build. */
    private static List<JsonNode> shiftCases() {
        List<JsonNode> out = new ArrayList<>();
        int skipped = 0;
        for (JsonNode c : CASES) {
            String link = c.get(2).asText();
            if ("shift".equals(link)) {
                out.add(c);
            } else if ("mathmap".equals(link)) {
                skipped++;
            } else {
                throw new IllegalStateException("link " + link);
            }
        }
        assertEquals(6, out.size());
        assertEquals(6, skipped);
        return out;
    }

    @Test
    void selectingAVariantMatchesC() {
        for (JsonNode c : shiftCases()) {
            String name = c.get(0).asText();
            FrameSet outer = build(c.get(1).asInt(), c.get(3).asText());
            assertArrayEquals(doubles(c.get(5)), transforms(outer), name + ": added");

            outer.setVariant(outer.getDomain());
            assertArrayEquals(doubles(c.get(7)), transforms(outer), name + ": selected again");
        }
    }

    @Test
    void cDumpsReadBackUnchanged() {
        for (JsonNode c : shiftCases()) {
            String name = c.get(0).asText();
            for (int i : new int[] {4, 6}) {
                String text = c.get(i).asText();
                FrameSet fs = assertInstanceOf(FrameSet.class, ChannelText.read(text), name);
                assertEquals(text, ChannelText.dump(fs), name);
                assertArrayEquals(doubles(c.get(i + 1)), transforms(fs), name + ": transformations");
            }
        }
    }
}
