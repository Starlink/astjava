package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_specflux_cmp.rs}: the SpecFluxFrame methods C inherits
 * from CmpFrame, checked against C ({@code oracle/specflux_cmp.json}, from {@code
 * tools/oracle_fixtures/specflux_cmp_probe.c}): picking one axis keeps its SpecFrame or FluxFrame
 * class, and a SpecFluxFrame template matches the components of a plain CmpFrame (specfluxframe.c
 * {@code Match} and {@code SubFrame} hand both to CmpFrame's). The fixture {@code
 * specfluxframe_swapped.native} is C's {@code astShow} of a SpecFluxFrame with its axes swapped.
 */
class SpecFluxCmpOracleTest {

    private static SpecFrame spec(String unit) {
        SpecFrame s = new SpecFrame();
        s.set("System=FREQ");
        s.setUnit(1, unit);
        return s;
    }

    private static FluxFrame flux() {
        FluxFrame f = new FluxFrame(1.4e9, null);
        f.set("System=FLXDN");
        return f;
    }

    /** Rust's {@code single_axis_picks_match_the_c_library}. */
    @Test
    void singleAxisPicksMatchTheCLibrary() {
        int run = 0;
        for (JsonNode c : ProbeData.load("specflux_cmp").get("PICKS")) {
            boolean swapped = c.get(0).asBoolean();
            int axis = c.get(1).asInt();
            SpecFluxFrame s = new SpecFluxFrame(spec("GHz"), flux());
            if (swapped) {
                s.permAxes(new int[] {2, 1});
            }
            Frame picked = s.pickAxes(1, new int[] {axis}, new Mapping[1]);
            String what = "swapped " + swapped + " axis " + axis;
            assertEquals(c.get(2).asText(), picked.astClass(), what);
            assertEquals(c.get(3).asText(), picked.getLabel(1), what);
            assertEquals(c.get(4).asText(), picked.getUnit(1), what);
            run++;
        }
        assertEquals(4, run);
    }

    /** Rust's {@code template_matches_a_plain_cmpframe_as_c_does}. */
    @Test
    void templateMatchesAPlainCmpFrameAsCDoes() {
        JsonNode found = ProbeData.load("specflux_cmp").get("FOUND");
        CmpFrame target = new CmpFrame(spec("GHz"), flux());
        SpecFluxFrame template = new SpecFluxFrame(spec("MHz"), flux());
        FrameSet fs = target.findFrame(template, "");
        assertNotNull(fs);
        assertEquals(found.get(0).asText(), fs.getFrame(FrameSet.AST__CURRENT).astClass());
        double[][] out = fs.tranP(1, 2, new double[][] {{1.4}, {1.0e-26}}, true, 2);
        assertEquals(ProbeData.toDouble(found.get(1).get(0)), out[0][0]);
        assertEquals(ProbeData.toDouble(found.get(1).get(1)), out[1][0]);
    }

    /**
     * Rust's {@code native_dump_matches_the_c_library}: a SpecFluxFrame is written as the CmpFrame
     * it is in C, with its axis permutation, components and {@code IsA CmpFrame} marker, and reads
     * back to the same Frame.
     */
    @Test
    void nativeDumpMatchesTheCLibrary() {
        SpecFluxFrame s = new SpecFluxFrame(spec("GHz"), flux());
        s.setTitle("MyT");
        s.permAxes(new int[] {2, 1});
        String text = ChannelText.dump(s);
        assertEquals(Fixtures.read("specfluxframe_swapped.native"), text);
        SpecFluxFrame back = assertInstanceOf(SpecFluxFrame.class, ChannelText.read(text));
        assertEquals("MyT", back.getTitle());
        for (int axis = 1; axis <= 2; axis++) {
            assertEquals(s.getLabel(axis), back.getLabel(axis));
            assertEquals(s.getUnit(axis), back.getUnit(axis));
        }
        assertEquals(text, ChannelText.dump(back));
    }
}
