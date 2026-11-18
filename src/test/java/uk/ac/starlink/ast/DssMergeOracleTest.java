package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_dss_merge_oracle.rs}: the plate centre of the DssMap a
 * DssMap and a preceding WinMap merge into, bit for bit against C's. C rebuilds the merged DssMap
 * from the FITS cards {@code astDssFits} writes (dssmap.c {@code MapMerge}), which hold the plate
 * centre as sexagesimal hours or degrees, minutes and seconds, so the merged centre is the original
 * after that round trip: a PltRA of 0.1 comes back as 0.09999999999999999.
 *
 * <p>The cases come from {@code oracle/dss_merge.json} (from {@code
 * tools/oracle_fixtures/dss_merge_probe.c}), applied to the C fixture {@code
 * simplify/dssmap_winmap_absorb.map} with its PltRA and PltDec replaced.
 */
class DssMergeOracleTest {

    /** {@code text} with the value of {@code key} (up to the tab before its comment) replaced by {@code value}. */
    private static String setValue(String text, String key, String value) {
        int start = text.indexOf(key) + key.length();
        int end = text.indexOf('\t', start);
        return text.substring(0, start) + value + " " + text.substring(end);
    }

    private static double getValue(String dump, String key) {
        int start = dump.indexOf(key) + key.length();
        int end = dump.indexOf(' ', start);
        return Double.parseDouble(dump.substring(start, end));
    }

    @Test
    void mergedPlateCentreMatchesTheCLibrary() {
        String base = Fixtures.read("simplify/dssmap_winmap_absorb.map");
        for (JsonNode c : ProbeData.load("dss_merge").get("CASES")) {
            String ra = c.get(0).asText();
            String dec = c.get(1).asText();
            String text = setValue(setValue(base, "PltRA = ", ra), "PltDec = ", dec);
            Mapping map = (Mapping) ChannelText.read(text);
            assertEquals("CmpMap", map.astClass());
            Mapping simp = map.simplify();
            assertEquals("DssMap", simp.astClass(), ra + " " + dec + " did not merge");
            String dump = ChannelText.dump(simp);
            double gotRa = getValue(dump, "PltRA = ");
            double gotDec = getValue(dump, "PltDec = ");
            double wantRa = ProbeData.toDouble(c.get(2));
            double wantDec = ProbeData.toDouble(c.get(3));
            assertEquals(
                    Double.doubleToLongBits(wantRa),
                    Double.doubleToLongBits(gotRa),
                    ra + ": PltRA " + gotRa + ", C " + wantRa);
            assertEquals(
                    Double.doubleToLongBits(wantDec),
                    Double.doubleToLongBits(gotDec),
                    dec + ": PltDec " + gotDec + ", C " + wantDec);
        }
    }
}
