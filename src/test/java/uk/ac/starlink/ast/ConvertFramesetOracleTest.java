package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_convert_frameset_oracle.rs}: {@code convert} between a
 * SkyFrame and a FrameSet whose current Frame is GRID and whose base Frame is an ICRS SkyFrame,
 * each way round, checked against C ({@code oracle/convert_frameset.json}, from {@code
 * tools/oracle_fixtures/convert_frameset_probe.c}). C dispatches {@code astConvertX} on the class
 * of the target: to a FrameSet, frameset.c searches all of its Frames; from a FrameSet to a plain
 * Frame, frame.c's driver tries the FrameSet as the template, and its {@code Match} searches its
 * Frames. Either way the route runs through the FrameSet's SkyFrame.
 */
class ConvertFramesetOracleTest {

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    private static FrameSet frameset() {
        FrameSet fs = new FrameSet(sky("ICRS"));
        Frame grid = new Frame(2);
        grid.setDomain("GRID");
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 0.5), grid);
        return fs;
    }

    private static void check(FrameSet cvt, JsonNode c, String what) {
        assertEquals(c.get(0).asBoolean(), cvt != null, what + ": route found");
        assertNotNull(cvt);
        assertEquals(c.get(1).asText(), cvt.getFrame(FrameSet.AST__CURRENT).getDomain(), what + ": current Domain");
        double[][] pts = {{1.0, 0.5}, {2.5, -0.3}};
        double[] xin = {pts[0][0], pts[1][0]};
        double[] yin = {pts[0][1], pts[1][1]};
        for (int dir = 0; dir < 2; dir++) {
            boolean forward = dir == 0;
            JsonNode want = c.get(2).get(dir);
            double[][] got = cvt.tran2(2, xin, yin, forward);
            for (int i = 0; i < 2; i++) {
                double[] w = {
                    ProbeData.toDouble(want.get(i).get(0)),
                    ProbeData.toDouble(want.get(i).get(1))
                };
                CValues.assertCValues(
                        new double[] {got[0][i], got[1][i]}, w, what + ": forward=" + forward + " point " + i);
            }
        }
    }

    @Test
    void frameToFramesetSearchesItsFrames() {
        FrameSet cvt = sky("GALACTIC").convert(frameset(), "");
        check(cvt, ProbeData.load("convert_frameset").get("FRAME_TO_FRAMESET"), "Frame to FrameSet");
    }

    @Test
    void framesetToFrameSearchesItsFrames() {
        FrameSet cvt = frameset().convert(sky("GALACTIC"), "");
        check(cvt, ProbeData.load("convert_frameset").get("FRAMESET_TO_FRAME"), "FrameSet to Frame");
    }
}
