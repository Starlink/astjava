package uk.ac.starlink.ast.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void newReasonPrefersNeedsThenAHandWrittenCause() {
        assertEquals("needs WcsMap", Deferred.newReason("needs WcsMap", "measured cause"));
        assertEquals("measured cause", Deferred.newReason(null, "measured cause"));
        assertEquals(Deferred.UNEXPLAINED, Deferred.newReason(null, "needs WcsMap"));
        assertEquals(Deferred.UNEXPLAINED, Deferred.newReason(null, Deferred.UNEXPLAINED));
        assertEquals(Deferred.UNEXPLAINED, Deferred.newReason(null, null));
    }

    @Test
    void reasonProblemRejectsUnexplainedAndStaleNeeds() {
        assertNull(Deferred.reasonProblem("needs WcsMap", "needs WcsMap"));
        assertNull(Deferred.reasonProblem("measured cause", null));
        assertNotNull(Deferred.reasonProblem(Deferred.UNEXPLAINED, null));
        assertNotNull(Deferred.reasonProblem("needs SphMap, WcsMap", "needs WcsMap"));
        assertNotNull(Deferred.reasonProblem("needs WcsMap", null));
        assertNotNull(Deferred.reasonProblem("measured cause", "needs WcsMap"));
    }
}
