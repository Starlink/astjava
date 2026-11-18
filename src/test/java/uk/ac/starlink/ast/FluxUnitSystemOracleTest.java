package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fluxframe.rs::system_from_unit_matches_the_c_library}: a
 * FluxFrame whose System follows from its Unit, checked against C ({@code
 * oracle/flux_unit_system.json}, from {@code tools/oracle_fixtures/flux_unit_system_probe.c}). C
 * reads the SpecVal in the units of the density system of the System the Unit gives (fluxframe.c
 * {@code GetSpecFrame}).
 */
class FluxUnitSystemOracleTest {

    @Test
    void systemFromUnitMatchesTheCLibrary() {
        JsonNode data = ProbeData.load("flux_unit_system");
        // astFluxFrame( 5000.0, NULL, "Unit=W/m^2/Angstrom" ): a default SpecVal, and no System.
        FluxFrame a = new FluxFrame(5000.0, null);
        a.setUnit(1, "W/m^2/Angstrom");
        assertEquals(data.get("SYSTEM").asText(), a.getSystem());
        assertEquals(data.get("TITLE").asText(), a.getTitle());
        // astFluxFrame( 5.99584916e14, NULL, "System=FLXDN" ).
        FluxFrame b = new FluxFrame(5.99584916e14, null);
        b.setSystem("FLXDN");
        FrameSet fs = a.convert(b, "");
        double[] out = fs.tran1(2, new double[] {1.0, 3.5}, true);
        JsonNode want = data.get("CONVERTED");
        assertEquals(ProbeData.toDouble(want.get(0)), out[0]);
        assertEquals(ProbeData.toDouble(want.get(1)), out[1]);
    }
}
