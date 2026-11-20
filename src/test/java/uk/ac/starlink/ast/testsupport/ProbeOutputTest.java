package uk.ac.starlink.ast.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProbeOutputTest {

    @Test
    void textIsTheOutFileOrTheJsonOutput() {
        assertEquals("== a\nx\n", ProbeOutput.text("p", "== a\nx\n", null));
        assertEquals("== a\ny\n", ProbeOutput.text("p", null, "{\"output\": \"== a\\ny\\n\"}"));
    }

    @Test
    void bothFormsAreAnError() {
        // A probe whose output moved from one form to the other leaves a copy of the old one in
        // target/test-classes until the build is cleaned; neither is taken over the other.
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> ProbeOutput.text("p", "== a\nx\n", "{\"output\": \"== a\\ny\\n\"}"));
        assertEquals(
                "Probe output /probes/p has both a .out and a .json file; one is stale (mvn clean removes a copy"
                        + " left in target/test-classes)",
                e.getMessage());
    }

    @Test
    void neitherFormIsAnError() {
        assertThrows(IllegalStateException.class, () -> ProbeOutput.text("p", null, null));
    }

    @Test
    void casesAreSplitAtTheirTitles() {
        Map<String, String> cases = ProbeOutput.load("region_defunc_probe");
        assertEquals("regcentre", cases.keySet().iterator().next());
    }
}
