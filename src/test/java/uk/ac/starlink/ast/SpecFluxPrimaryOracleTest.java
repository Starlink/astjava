package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_specflux_primary.rs}: what a SpecFluxFrame inherits from
 * CmpFrame, checked against C ({@code oracle/specflux_primary.json}, from {@code
 * tools/oracle_fixtures/specflux_primary_probe.c}). A SpecFluxFrame's System is {@code Compound}
 * (cmpframe.c {@code GetSystem}), and {@code PrimaryFrame} descends into its SpecFrame and
 * FluxFrame, so a SpecFrame template finds the spectral axis of a SpecFluxFrame, alone or inside a
 * CmpFrame (cmpframe.c {@code PrimaryFrame}).
 */
class SpecFluxPrimaryOracleTest {

    private static SpecFluxFrame specflux() {
        SpecFrame sp = new SpecFrame();
        sp.set("System=FREQ");
        sp.setUnit(1, "GHz");
        return new SpecFluxFrame(sp, flux(0, null));
    }

    /** A FLXDN FluxFrame, with MaxAxes and Unit set if {@code maxAxes} is not 0. */
    private static FluxFrame flux(int maxAxes, String unit) {
        FluxFrame f = new FluxFrame(1.4e9, null);
        f.set("System=FLXDN");
        if (maxAxes != 0) {
            f.setMaxAxes(maxAxes);
            f.setUnit(1, unit);
        }
        return f;
    }

    private static SpecFrame specTemplate(int maxAxes) {
        SpecFrame t = new SpecFrame();
        t.set("System=WAVE");
        t.setUnit(1, "nm");
        t.setMaxAxes(maxAxes);
        return t;
    }

    /** The template's value for the probe's point, or null if the template finds nothing. */
    private static Double found(Frame target, Frame template, JsonNode point) {
        FrameSet fs = target.findFrame(template, "");
        if (fs == null) {
            return null;
        }
        int naxes = target.getNaxes();
        double[][] in = new double[naxes][1];
        for (int i = 0; i < naxes; i++) {
            in[i][0] = point.get(i).asDouble();
        }
        return fs.tranP(1, naxes, in, true, fs.getNout())[0][0];
    }

    /** Rust's {@code system_is_compound_as_in_the_c_library}. */
    @Test
    void systemIsCompoundAsInTheCLibrary() {
        assertEquals(
                ProbeData.load("specflux_primary").get("SYSTEM").asText(),
                specflux().getSystem());
    }

    /** Rust's {@code templates_find_component_axes_as_in_the_c_library}. */
    @Test
    void templatesFindComponentAxesAsInTheCLibrary() {
        JsonNode data = ProbeData.load("specflux_primary");
        JsonNode point = data.get("POINT");
        SpecFluxFrame sff = specflux();
        CmpFrame cmp = new CmpFrame(specflux(), new Frame(1));
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : data.get("CASES")) {
            String name = c.get(0).asText();
            Double want = c.get(1).isNull() ? null : ProbeData.toDouble(c.get(1));
            Double got;
            switch (name) {
                case "spec_in_specflux":
                    got = found(sff, specTemplate(2), point);
                    break;
                case "spec_in_cmp":
                    got = found(cmp, specTemplate(3), point);
                    break;
                case "flux_in_specflux":
                    got = found(sff, flux(2, "mJy"), point);
                    break;
                case "flux_in_cmp":
                    got = found(cmp, flux(3, "mJy"), point);
                    break;
                default:
                    throw new AssertionError("no set-up for case " + name);
            }
            if (want == null ? got != null : !want.equals(got)) {
                failures.add(name + ": got " + got + ", C gives " + want);
            }
            run++;
        }
        assertEquals(4, run);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
