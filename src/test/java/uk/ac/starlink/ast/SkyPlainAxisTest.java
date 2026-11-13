package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_sky_plain_axis.rs}: a SkyFrame holding plain Axis objects,
 * as {@code astLoadFrame} gives one read from a description with no {@code Ax<n>} objects
 * (frame.c), behaves as C's does ({@code oracle/sky_plain_axis.json}, from {@code
 * tools/oracle_fixtures/sky_plain_axis_probe.c}). Format, Unit, formatting and unformatting come
 * from the plain Axis; setting AsTime replaces the axis with a SkyAxis carrying its attributes
 * (skyframe.c {@code SetAsTime}); and picking the axis out gives a SkyAxis (skyframe.c {@code
 * SubFrame}).
 */
class SkyPlainAxisTest {

    private static String text(JsonNode lines) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode l : lines) {
            sb.append(l.asText()).append('\n');
        }
        return sb.toString();
    }

    /** Checks each axis's Format, Unit, formatted 1.2345 and unformatted "1.5" against a probe step. */
    private static void assertAxes(Frame f, JsonNode step) {
        String name = step.get(0).asText();
        for (int axis = 1; axis <= 2; axis++) {
            JsonNode want = step.get(1).get(axis - 1);
            String what = name + " axis " + axis;
            assertEquals(want.get(0).asText(), f.getFormat(axis), what + " Format");
            assertEquals(want.get(1).asText(), f.getUnit(axis), what + " Unit");
            assertEquals(want.get(2).asText(), f.format(axis, 1.2345), what + " format");
            double[] value = new double[1];
            int nc = f.unformat(axis, "1.5", value);
            assertEquals(want.get(3).get(1).asInt(), nc, what + " unformat count");
            double w = ProbeData.toDouble(want.get(3).get(0));
            assertTrue(CValues.cValueEq(value[0], w), what + " unformat: got " + value[0] + ", C gives " + w);
        }
    }

    @Test
    void aSkyframeWithPlainAxesBehavesAsCDoes() {
        JsonNode data = ProbeData.load("sky_plain_axis");
        SkyFrame sky = assertInstanceOf(SkyFrame.class, ChannelText.read(text(data.get("TEXT"))));
        assertAxes(sky, data.get("STEPS").get(0));

        sky.setAsTime(1, true);
        assertAxes(sky, data.get("STEPS").get(1));

        assertEquals(text(data.get("DUMP")), ChannelText.dump(sky));

        Frame picked = sky.pickAxes(1, new int[] {2}, null);
        assertSame(Frame.class, picked.getClass(), "the picked Frame is a plain Frame");
        assertEquals(text(data.get("PICKED_2")), ChannelText.dump(picked));
    }
}
