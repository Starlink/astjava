package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/** Translation of Rust {@code tests/error_codes.rs}, plus a check of every code against {@code ast_err.msg}. */
class ErrorCodesTest {

    @Test
    void atgerHasCorrectRawValue() {
        // MESSGEN: ((facility | 0x800) << 16) + ((msg_num | 0x1000) << 3) + severity;
        // AST__ATGER has facility 1521, message number 300 and severity 2 (ERROR).
        assertEquals(233_933_154, AstException.AST__ATGER);
    }

    @Test
    void fromRawRoundTrips() {
        assertEquals("AST__ATGER", new AstException("x", AstException.AST__ATGER).getStatusName());
    }

    @Test
    void fromRawReturnsNoneForInvalid() {
        assertEquals("SAI__OK", new AstException("x", 0).getStatusName());
        assertNull(new AstException("x", -1).getStatusName());
    }

    @Test
    void displayShowsDescription() {
        assertEquals("attribute getting error", AstException.describe(AstException.AST__ATGER));
    }

    @Test
    void errorCode3dfsetAccessible() {
        assertEquals("AST__3DFSET", new AstException("x", AstException.AST__3DFSET).getStatusName());
    }

    @Test
    void saiValuesAreCs() {
        // ast_tester/star_compat/sai_err.h.
        assertEquals(0, AstException.SAI__OK);
        assertEquals(148_013_867, AstException.SAI__ERROR);
        assertEquals("SAI__ERROR", new AstException("x").getStatusName());
    }

    /** The codes of {@code ast_err.msg}, by name, computed with the MESSGEN formula. */
    private static Map<String, Integer> codesFromMsgFile() throws Exception {
        String text = new String(
                Files.readAllBytes(Fixtures.root().resolve("../../ast_err.msg").normalize()), StandardCharsets.UTF_8);
        Map<String, Integer> out = new LinkedHashMap<>();
        long facility = -1;
        long severity = -1;
        long number = -1;
        for (String raw : CValues.lines(text)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("!") || line.startsWith(".TITLE") || line.startsWith(".END")) {
                continue;
            }
            String[] tok = CValues.splitWhitespace(line);
            if (".FACILITY".equals(tok[0])) {
                facility = Long.parseLong(tok[1].substring(tok[1].indexOf(',') + 1, tok[1].indexOf('/')));
            } else if (".SEVERITY".equals(tok[0])) {
                assertEquals("ERROR", tok[1]);
                severity = 2;
            } else if (".BASE".equals(tok[0])) {
                number = Long.parseLong(tok[1]);
            } else {
                assertTrue(line.contains("<") && line.endsWith(">"), line);
                out.put("AST__" + tok[0], (int) (((facility | 0x800) << 16) + ((number | 0x1000) << 3) + severity));
                number++;
            }
        }
        return out;
    }

    @Test
    void everyCodeMatchesAstErrMsg() throws Exception {
        Map<String, Integer> want = codesFromMsgFile();
        assertEquals(179, want.size());
        Map<String, Integer> got = new LinkedHashMap<>();
        for (Field f : AstException.class.getDeclaredFields()) {
            if (f.getName().startsWith("AST__")) {
                int m = f.getModifiers();
                assertTrue(Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m), f.getName());
                got.put(f.getName(), f.getInt(null));
            }
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            Integer g = got.get(e.getKey());
            if (!e.getValue().equals(g)) {
                problems.add(e.getKey() + ": want " + e.getValue() + ", got " + g);
            }
            if (g != null && !e.getKey().equals(new AstException("x", g).getStatusName())) {
                problems.add(e.getKey() + ": status name " + new AstException("x", g).getStatusName());
            }
        }
        for (String name : got.keySet()) {
            if (!want.containsKey(name)) {
                problems.add(name + ": not in ast_err.msg");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
