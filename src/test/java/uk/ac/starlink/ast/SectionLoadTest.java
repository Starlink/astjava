package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.NativeClasses;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_section_load.rs}: each class reads its items only from its
 * own part of a native description, as C's loaders do through {@code astReadClassData}
 * (channel.c): an item placed in another class's part, between a different pair of {@code IsA}
 * lines, is ignored.
 *
 * <p>{@code oracle/section_load.json} comes from {@code tools/oracle_fixtures/section_load_probe.c}:
 * descriptions of Frames, Mappings, Regions and a KeyMap that misplace an item, each with a
 * companion placing it where its class reads it, and with each attribute as C's {@code astGetC},
 * {@code astGetI} or {@code astGetD} gives it and whether {@code astTest} finds it set. Each
 * attribute is read here through the same generic calls. As in the Rust test, whether Invert and
 * Zoom are set is not compared.
 *
 * <p>The cases of {@link #OUT_OF_SCOPE} hold a class that is out of scope and are not run.
 */
class SectionLoadTest {

    /** Cases holding a class that is out of scope, with that class. */
    private static final Map<String, String> OUT_OF_SCOPE = outOfScope();

    private static Map<String, String> outOfScope() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("spec_ident_in_frame", "SpecFrame");
        m.put("spec_ident_in_object", "SpecFrame");
        m.put("spec_items_swapped", "SpecFrame");
        m.put("spec_items_placed", "SpecFrame");
        m.put("dsb_sideband_in_spec", "DSBSpecFrame");
        m.put("dsb_sideband_placed", "DSBSpecFrame");
        m.put("circle_negate_in_class", "Circle");
        m.put("circle_negate_placed", "Circle");
        m.put("circle_ident_in_region", "Circle");
        m.put("polygon_simpvt_in_region", "Polygon");
        m.put("polygon_simpvt_placed", "Polygon");
        m.put("polygon_no_order", "Polygon");
        return Collections.unmodifiableMap(m);
    }

    /** An attribute's value as the probe reads it, in the probe's form for {@code variant}. */
    private static Object value(AstObject obj, String name, String variant) {
        if ("S".equals(variant)) {
            return obj.getC(name);
        } else if ("I".equals(variant)) {
            return (long) obj.getI(name);
        } else if ("D".equals(variant) || "Y".equals(variant)) {
            return obj.getD(name);
        } else {
            throw new IllegalArgumentException("unknown variant " + variant);
        }
    }

    /** C's value in the probe's form. */
    private static Object want(JsonNode v) {
        String variant = v.get("variant").asText();
        JsonNode value = v.get("value");
        if ("S".equals(variant)) {
            return value.asText();
        } else if ("I".equals(variant)) {
            return value.asLong();
        } else {
            return ProbeData.toDouble(value);
        }
    }

    private static boolean setUncompared(String name) {
        return "Invert".equals(name) || "Zoom".equals(name);
    }

    @Test
    void eachClassReadsOnlyItsOwnPart() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("section_load").get("CASES")) {
            String name = c.get(0).asText();
            List<String> lines = new ArrayList<>();
            for (JsonNode l : c.get(1)) {
                lines.add(l.asText());
            }
            String text = String.join("\n", lines);
            String cls = OUT_OF_SCOPE.get(name);
            if (cls != null) {
                assertTrue(
                        NativeClasses.missing(text).contains(cls),
                        name + " is left out for want of " + cls + ", which is now loaded; run it");
                continue;
            }
            run++;
            AstObject obj;
            try {
                obj = ChannelText.read(text);
            } catch (AstException e) {
                failures.add(name + ": " + e.getMessage());
                continue;
            }
            for (JsonNode a : c.get(2)) {
                String attr = a.get(0).asText();
                Object want = want(a.get(1));
                boolean wantSet = a.get(2).asBoolean();
                Object got = value(obj, attr, a.get(1).get("variant").asText());
                boolean gotSet = obj.test(attr);
                if (!want.equals(got) || (!setUncompared(attr) && gotSet != wantSet)) {
                    failures.add(name + " " + attr + ": got (" + got + ", " + gotSet + "), C gives (" + want + ", "
                            + wantSet + ")");
                }
            }
        }
        assertEquals(20, run, "cases run");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
