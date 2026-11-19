package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_remove_regions_oracle.rs}: removing the Regions from a
 * Mapping gives what C's {@code astRemoveRegions} gives. Each class removes them from its own
 * components (cmpmap.c, tranmap.c, ratemap.c, region.c {@code RemoveRegions}), a series CmpMap
 * drops a component that became a UnitMap, and a parallel CmpMap or a TranMap of two UnitMaps
 * becomes one UnitMap. Each case's Mapping is read from C's dump, and the dump of the result must
 * equal C's ({@code oracle/remove_regions.json}, from {@code
 * tools/oracle_fixtures/remove_regions_probe.c}).
 *
 * <p>The cases {@code normmap_region} and {@code normmap_cmpframe} need NormMap, which jniast does
 * not have, and are skipped by name.
 */
class RemoveRegionsOracleTest {

    private static final JsonNode DATA = ProbeData.load("remove_regions");

    /** The cases that need a class astjava does not have. */
    private static final List<String> SKIPPED = Arrays.asList("normmap_region", "normmap_cmpframe");

    private static String text(JsonNode lines) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode l : lines) {
            sb.append(l.textValue()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void removingRegionsMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        int skipped = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            if (SKIPPED.contains(name)) {
                // NormMap (not in jniast).
                skipped++;
                continue;
            }
            Mapping map = (Mapping) ChannelText.read(text(c.get(1)));
            String got = ChannelText.dump(map.removeRegions());
            String want = text(c.get(2));
            if (!want.equals(got)) {
                failures.add(name + ":\n" + got + "\nC gives:\n" + want);
            }
            ran++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(9, ran, "cases run");
        assertEquals(SKIPPED.size(), skipped, "cases skipped");
    }
}
