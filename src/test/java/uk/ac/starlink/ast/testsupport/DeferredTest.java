package uk.ac.starlink.ast.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeferredTest {

    @Test
    void parsesKeysAndReasons() {
        Map<String, String> d = Deferred.parse(
                "# comment\n\nsimplify/a.map | needs SpecMap\n wcsconv/inputs/b.head |  needs FitsChan \n", "test");
        assertEquals(2, d.size());
        assertEquals("needs SpecMap", d.get("simplify/a.map"));
        assertEquals("needs FitsChan", d.get("wcsconv/inputs/b.head"));
    }

    @Test
    void lineWithoutReasonIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Deferred.parse("simplify/a.map\n", "test"));
    }

    @Test
    void duplicateKeyIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Deferred.parse("a | x\na | y\n", "test"));
    }

    @Test
    void missingListIsAnError() {
        assertThrows(IllegalStateException.class, () -> Deferred.load("no-such-list"));
    }
}
