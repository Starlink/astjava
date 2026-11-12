package uk.ac.starlink.ast.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeDataTest {

    @Test
    void objectIdHasEveryConstant() {
        JsonNode consts = ProbeData.load("object_id");
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = consts.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        // The constants of the Rust repository's tests/object_id_oracle/cases.rs.
        assertEquals(Collections.singletonList("DUMPS"), names);
    }

    @Test
    void objectIdValueMatchesTheRustLiteral() {
        JsonNode first = ProbeData.load("object_id").get("DUMPS").get(0);
        assertEquals("zoommap", first.get(0).asText());
        // The first line of the first dump, as written in cases.rs.
        assertEquals(
                " Begin ZoomMap \t# Zoom about the origin", first.get(1).get(0).asText());
    }
}
