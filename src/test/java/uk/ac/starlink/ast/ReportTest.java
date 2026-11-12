package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_report.rs}: the Report attribute makes {@code tran1} and
 * {@code tran2} write the effect of each transformation to the thread's report stream as C's
 * {@code astTran1} and {@code astTran2} print it (mapping.c {@code ReportPoints}), and Report is
 * dumped and read back as C does. The expected text comes from C ({@code oracle/report.json},
 * from {@code tools/oracle_fixtures/report_probe.c}).
 *
 * <p>The FrameSet parts of {@code reports_follow_the_c_library} and {@code a_clone_clears_report}
 * are translated in Task 20 (they need a SkyFrame and a FrameSet); their Circle parts need a class
 * outside the first phase.
 */
class ReportTest {

    private static final JsonNode CASES = ProbeData.load("report");

    /** What {@code r} reports. */
    // Java 8 has no PrintStream constructor taking a Charset.
    @SuppressWarnings("JdkObsolete")
    private static String reportOf(Runnable r) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old;
        try {
            // MODERN-JAVA(10): new PrintStream(buf, true, StandardCharsets.UTF_8)
            old = MappingReport.setSink(new PrintStream(buf, true, StandardCharsets.UTF_8.name()));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        try {
            r.run();
        } finally {
            MappingReport.setSink(old);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static double[] values(String name) {
        JsonNode arr = CASES.get(name);
        double[] out = new double[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).isNull() ? AstObject.AST__BAD : arr.get(i).asDouble();
        }
        return out;
    }

    private static String dumpText(String name) {
        JsonNode arr = CASES.get(name);
        String[] lines = new String[arr.size()];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = arr.get(i).asText();
        }
        return ChannelText.text(lines);
    }

    @Test
    void reportsFollowTheCLibrary() {
        double[] x = values("X");
        double[] y = values("Y");

        ZoomMap zoom = new ZoomMap(2, 2.5);
        zoom.setReport(true);
        String got = reportOf(() -> {
            zoom.tran2(x.length, x, y, true);
            zoom.tran2(x.length, x, y, false);
        });
        assertEquals(CASES.get("ZOOM").asText(), got, "ZoomMap");

        ShiftMap shift = new ShiftMap(new double[] {1.25});
        shift.setReport(true);
        got = reportOf(() -> shift.tran1(x.length, x, true));
        assertEquals(CASES.get("SHIFT").asText(), got, "ShiftMap");

        // Without Report, nothing is written.
        zoom.clear("Report");
        assertEquals("", reportOf(() -> zoom.tran2(x.length, x, y, true)));
        zoom.setReport(true);

        ZoomMap zoomOff = (ZoomMap) zoom.copy();
        zoomOff.setReport(false);
        List<String> failures = new ArrayList<>();
        String[][] cases = {{"ZoomMap", "ZOOM_DUMP"}, {"ZoomMap Report=0", "ZOOM_DUMP_OFF"}};
        Mapping[] objects = {zoom, zoomOff};
        for (int i = 0; i < cases.length; i++) {
            String name = cases[i][0];
            String want = dumpText(cases[i][1]);
            String written = ChannelText.dump(objects[i]);
            if (!written.equals(want)) {
                failures.add(name + ": dump differs\n" + written + "\nC:\n" + want);
            }
            // The dump reads back with Report as C wrote it.
            Mapping m = (Mapping) ChannelText.read(want);
            boolean wantReport = want.contains("    Report = 1");
            if (!m.test("Report") || m.getReport() != wantReport) {
                failures.add(name + ": Report reads back as " + m.getReport());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** A copy clears Report, as C's {@code astCopy} does. */
    @Test
    void aCloneClearsReport() {
        ZoomMap zoom = new ZoomMap(3, 2.0);
        zoom.setReport(true);
        Mapping copy = (Mapping) zoom.copy();
        assertFalse(copy.test("Report"));
        assertTrue(zoom.test("Report") && zoom.getReport());
    }

    /** Writing a Mapping held as a Mapping reference is not a copy, so the dump records Report. */
    @Test
    void aDumpThroughFromMappingKeepsReport() {
        ZoomMap zoom = new ZoomMap(2, 2.5);
        zoom.setReport(true);
        Mapping m = zoom;
        assertEquals(dumpText("ZOOM_DUMP"), ChannelText.dump(m));
    }
}
