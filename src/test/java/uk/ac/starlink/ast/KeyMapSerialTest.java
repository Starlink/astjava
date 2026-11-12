package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_keymap_serial.rs}: KeyMap round trips through native text,
 * and {@code MapSz} against the C oracle {@code oracle/keymap_mapsz.txt}.
 *
 * <p>{@code every_scalar_type_round_trips}, {@code a_null_object_entry_round_trips} and {@code
 * a_non_first_object_vector_element_has_no_trailing_space} store UnitMaps, and are translated with
 * UnitMap (Task 6).
 */
class KeyMapSerialTest {

    /**
     * The column at which an item's text starts, counting the {@code #} of an unset item as a
     * column of its own, so that a set and an unset item at the same level agree.
     */
    private static int column(String line) {
        int i = 0;
        if (line.startsWith("#")) {
            i = 1;
        }
        while (i < line.length() && CScan.isCSpace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    @Test
    void columnAgreesForASetAndAnUnsetCardAtTheSameNestingLevel() {
        // Lines 473 and 475 of cap_timemap_02.map: a set Ident and an unset Title one level
        // inside a nested CmpFrame, both with their text at column 22.
        String setLine = "                      Ident = \"ICRS-TT-CXO\" \t# comment";
        String unsetLine = "#                     Title = \"x\" \t# comment";
        assertEquals(22, column(setLine));
        assertEquals(22, column(unsetLine));
        assertEquals(column(setLine), column(unsetLine));
    }

    /** The {@code MapSz} value in native text. */
    private static long mapSzCardOf(String text) {
        for (String line : CValues.lines(text)) {
            String t = line.trim();
            while (t.startsWith("#")) {
                t = t.substring(1).trim();
            }
            if (t.startsWith("MapSz =")) {
                String value = t.substring("MapSz =".length());
                int tab = value.indexOf('\t');
                return Long.parseLong((tab < 0 ? value : value.substring(0, tab)).trim());
            }
        }
        throw new AssertionError("no MapSz item in:\n" + text);
    }

    /** The MapSz a KeyMap is written with. */
    private static long dumpedMapSz(KeyMap km) {
        return mapSzCardOf(ChannelText.dump(km));
    }

    /** One row of the C oracle: {@code <label> sizeguess=<n> nkey=<n> MapSz=<n>}. */
    private static final class OracleRow {
        final String label;
        final int sizeguess;
        final int nkey;
        final long mapSz;

        OracleRow(String line) {
            label = line.substring(0, line.indexOf("sizeguess=")).trim();
            sizeguess = (int) field(line, "sizeguess=");
            nkey = (int) field(line, "nkey=");
            mapSz = field(line, "MapSz=");
        }

        private static long field(String line, String name) {
            int at = line.indexOf(name);
            if (at < 0) {
                throw new AssertionError("no " + name + " in oracle row " + line);
            }
            return Long.parseLong(CValues.splitWhitespace(line.substring(at + name.length()))[0]);
        }
    }

    private static List<OracleRow> oracleRows() {
        List<OracleRow> rows = new ArrayList<>();
        for (String line : CValues.lines(Fixtures.read("oracle/keymap_mapsz.txt"))) {
            if (!CValues.isBlank(line)) {
                rows.add(new OracleRow(line));
            }
        }
        assertFalse(rows.isEmpty(), "oracle fixture is empty");
        return rows;
    }

    /**
     * The first {@code n} keys {@code K<i>} that hash to bucket 5 of a 16-bucket table, by the rule
     * the C probe ({@code keymap_hash.c}) uses, which reads bytes as unsigned; the keys are ASCII,
     * so this agrees with keymap.c's signed reading.
     */
    private static List<String> collidingKeys(int n) {
        List<String> out = new ArrayList<>();
        for (long i = 0; out.size() < n; i++) {
            String key = "K" + i;
            long hash = 5381;
            for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
                if (b != ' ') {
                    hash = (hash << 5) + hash + (b & 0xff);
                }
            }
            if ((hash & 15) == 5) {
                out.add(key);
            }
        }
        return out;
    }

    private static List<String> numbered(String prefix, String suffix, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(prefix + i + suffix);
        }
        return out;
    }

    /** The KeyMap an oracle row describes, or null for a label this test does not know. */
    private static KeyMap keyMapFor(OracleRow row) {
        KeyMap km = new KeyMap();
        if (row.sizeguess > 0) {
            // The probe sets SizeGuess before its puts, as C's SetSizeGuess requires.
            km.setSizeGuess(row.sizeguess);
        }
        List<String> keys;
        switch (row.label) {
            case "empty":
            case "four keys":
            case "100 keys":
            case "500 keys":
            case "1000 keys":
            case "guess 100":
            case "guess 160":
            case "guess 170":
            case "guess 5000":
            case "guess 5000, 500 keys":
                keys = numbered("KEY_", "", row.nkey);
                break;
            case "500 keys, k%d":
                keys = numbered("k", "", row.nkey);
                break;
            case "500 keys, ZZ%d_TAIL":
                keys = numbered("ZZ", "_TAIL", row.nkey);
                break;
            case "cap_timemap_02 keys":
                keys = new ArrayList<>();
                keys.add("Size");
                keys.add("Name");
                keys.add("Resolution");
                keys.add("Error");
                break;
            case "10 colliding":
            case "11 colliding":
            case "40 colliding":
            case "300 colliding":
                keys = collidingKeys(row.nkey);
                break;
            case "40 colliding + 40 spread":
                keys = collidingKeys(40);
                keys.addAll(numbered("SPREAD_", "", 40));
                break;
            default:
                return null;
        }
        for (int i = 0; i < keys.size(); i++) {
            km.mapPut0I(keys.get(i), i, null);
        }
        return km;
    }

    @Test
    void mapSzMatchesCOracle() {
        // Every label the probe writes; a new row needs a case in keyMapFor, not a skip.
        final int expectedRows = 18;
        List<OracleRow> rows = oracleRows();
        assertEquals(expectedRows, rows.size(), "the oracle fixture has a new row; add its label to keyMapFor");
        for (OracleRow row : rows) {
            KeyMap km = keyMapFor(row);
            if (km == null) {
                fail("oracle row \"" + row.label + "\" has no case in keyMapFor");
            }
            assertEquals(
                    row.mapSz,
                    dumpedMapSz(km),
                    "MapSz for \"" + row.label + "\" (sizeguess=" + row.sizeguess + ", nkey=" + row.nkey + ")");
        }
    }

    /** Writes, reads and writes again, requiring the two texts to be equal; gives the KeyMap read. */
    private static KeyMap assertDoubleRoundTrip(KeyMap km, String name) {
        String text1 = ChannelText.dump(km);
        AstObject loaded = ChannelText.read(text1);
        assertInstanceOf(KeyMap.class, loaded, name + ": did not load as a KeyMap");
        String text2 = ChannelText.dump(loaded);
        assertEquals(text1, text2, name + ": dump is not a fixed point");
        return (KeyMap) loaded;
    }

    @Test
    void everyVectorTypeRoundTrips() {
        KeyMap km = new KeyMap();
        km.mapPut1I("ints", new int[] {1, 2, 3}, null);
        km.mapPut1D("doubles", new double[] {1.5, 2.5}, "two doubles");
        km.mapPut1F("floats", new float[] {0.5f, 0.25f, 0.125f}, null);
        km.mapPut1C("strings", new String[] {"a", "", "c c"}, null);
        km.mapPut1S("shorts", new short[] {-1, 0, 1}, null);
        km.mapPut1B("bytes", new int[] {0, 255}, null);
        km.mapPut1K("int64s", new long[] {-9_000_000_000L, 9_000_000_000L}, null);

        KeyMap back = assertDoubleRoundTrip(km, "vectors");

        assertArrayEquals(new int[] {1, 2, 3}, back.mapGet1I("ints"));
        assertArrayEquals(new String[] {"a", "", "c c"}, back.mapGet1C("strings"));
        assertEquals(3, back.mapLength("floats"));
        assertEquals(2, back.mapLength("bytes"));
    }

    @Test
    void aBadDoubleRoundTripsAsBad() {
        // DumpEntry writes no item for an AST__BAD double, and astLoadKeyMap's default for a
        // missing Val item is AST__BAD, so the value survives by its absence.
        KeyMap km = new KeyMap();
        km.mapPut0D("bad", AstObject.AST__BAD, null);
        km.mapPut1D("part_bad", new double[] {1.0, AstObject.AST__BAD, 3.0}, null);

        String text = ChannelText.dump(km);
        // "bad" sorts before "part_bad", so it is entry 1.
        assertFalse(text.contains("Val1 ="), "a BAD scalar writes no Val item");

        KeyMap back = assertDoubleRoundTrip(km, "bad double");
        assertEquals(AstObject.AST__BAD, back.mapGet0D("bad"));
        assertArrayEquals(new double[] {1.0, AstObject.AST__BAD, 3.0}, back.mapGet1D("part_bad"));
    }

    @Test
    void everyAttributeRoundTrips() {
        // MapLocked is set on an empty KeyMap; a locked non-empty one also reloads, as
        // KeyMapSerialModuleTest.aLockedNonEmptyKeymapReloads checks.
        KeyMap km = new KeyMap();
        km.setSizeGuess(320);
        km.setKeyCase(false);
        km.setKeyError(true);
        km.setSortBy(KeyMapSort.KEY_DOWN);
        km.setMapLocked(true);

        KeyMap back = assertDoubleRoundTrip(km, "attributes");

        assertEquals(320, back.getSizeGuess());
        assertFalse(back.getKeyCase());
        assertTrue(back.getKeyError());
        assertSame(KeyMapSort.KEY_DOWN, back.getSortBy());
        assertTrue(back.getMapLocked());
    }

    @Test
    void aNestedKeyMapRoundTrips() {
        KeyMap inner = new KeyMap();
        inner.mapPut0I("b3", 3, null);
        inner.mapPut1C("b4", new String[] {"x", "y"}, null);

        KeyMap km = new KeyMap();
        km.mapPut0I("a1", 1, null);
        km.mapPut0A("nested", inner, "a KeyMap");

        KeyMap back = assertDoubleRoundTrip(km, "nested");

        AstObject got = back.mapGet0A("nested");
        assertNotNull(got);
        assertInstanceOf(KeyMap.class, got, "nested entry did not load as a KeyMap");
        assertEquals(3, ((KeyMap) got).mapGet0I("b3"));
        assertEquals(2, ((KeyMap) got).mapLength("b4"));
    }

    @Test
    void aDumpIsIndependentOfInsertionOrder() {
        // Each key's value is its position in keys, so every rotation stores the same pairs in a
        // different order.
        String[] keys = {"Size", "Name", "Resolution", "Error"};
        String first = null;
        for (int rotate = 0; rotate < keys.length; rotate++) {
            KeyMap km = new KeyMap();
            for (int i = 0; i < keys.length; i++) {
                int idx = (i + rotate) % keys.length;
                km.mapPut0I(keys[idx], idx, null);
            }
            String text = ChannelText.dump(km);
            // MemCnt counts puts, so it is the same for every rotation of fresh puts.
            if (first == null) {
                first = text;
            } else {
                assertEquals(first, text, "rotation " + rotate + " dumped differently");
            }
        }
    }
}
