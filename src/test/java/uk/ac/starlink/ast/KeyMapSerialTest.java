package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_keymap_serial.rs}: KeyMap round trips through native text,
 * and {@code MapSz} against the C oracle {@code oracle/keymap_mapsz.txt}.
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
    void everyScalarTypeRoundTrips() {
        KeyMap km = new KeyMap();
        km.mapPut0I("an_int", -7, "int comment");
        km.mapPut0D("a_double", 1.5, null);
        km.mapPut0F("a_float", 0.25f, null);
        km.mapPut0C("a_string", "text with spaces", null);
        km.mapPut0S("a_short", (short) -3, null);
        km.mapPut0B("a_byte", 200, null);
        km.mapPut0K("an_int64", 9_000_000_000L, null);
        km.mapPutU("undefined", null);
        km.mapPut0A("an_object", new UnitMap(2), null);

        KeyMap back = assertDoubleRoundTrip(km, "scalars");

        assertEquals(km.mapSize(), back.mapSize());
        assertEquals(-7, back.mapGet0I("an_int"));
        assertEquals(1.5, back.mapGet0D("a_double"));
        assertEquals(0.25f, back.mapGet0F("a_float"));
        assertEquals("text with spaces", back.mapGet0C("a_string"));
        assertEquals((short) -3, back.mapGet0S("a_short"));
        assertEquals(200, back.mapGet0B("a_byte"));
        assertEquals(9_000_000_000L, back.mapGet0K("an_int64"));
        assertTrue(back.mapHasKey("undefined"));
        assertFalse(back.mapDefined("undefined"));
        assertNotNull(back.mapGet0A("an_object"));
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
    void aNullObjectEntryRoundTrips() {
        // MapPut1A stores a null element rather than rejecting it (keymap.c MAKE_MAPPUT1(A, ...,
        // (value[i]?astClone(value[i]):NULL))), and DumpEntry writes no item for it.
        KeyMap km = new KeyMap();
        km.mapPut1A("objects", new AstObject[] {new UnitMap(1), null}, null);

        KeyMap back = assertDoubleRoundTrip(km, "null object element");
        AstObject[] got = back.mapGet1A("objects");
        assertEquals(2, got.length);
        assertNotNull(got[0]);
        assertNull(got[1]);
    }

    @Test
    void aNonFirstObjectVectorElementHasNoTrailingSpace() {
        // DumpEntry (keymap.c) gives every element after the first an empty comment, and
        // WriteObject (channel.c) then writes " =" with nothing after it.
        KeyMap km = new KeyMap();
        km.mapPut1A("objects", new AstObject[] {new UnitMap(1), new UnitMap(2)}, null);

        String text = ChannelText.dump(km);
        String line = null;
        for (String l : CValues.lines(text)) {
            if (l.trim().startsWith("V1_2 =")) {
                line = l;
                break;
            }
        }
        assertNotNull(line, "no V1_2 item found in:\n" + text);
        assertTrue(line.endsWith("="), "V1_2 item must end at '=', got \"" + line + "\"");

        KeyMap back = assertDoubleRoundTrip(km, "object vector, no trailing space");
        AstObject[] got = back.mapGet1A("objects");
        assertEquals(2, got.length);
        assertNotNull(got[0]);
        assertNotNull(got[1]);
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

    // ------------------------------------------------------------------
    // The KeyMap of simplify/cap_timemap_02.map
    // ------------------------------------------------------------------

    /** The Begin KeyMap ... End KeyMap block of a native document, read on its own. */
    private static String extractKeyMapBlock(String text) {
        List<String> lines = CValues.lines(text);
        int begin = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("Begin KeyMap")) {
                begin = i;
                break;
            }
        }
        assertTrue(begin >= 0, "fixture has no KeyMap");
        int base = column(lines.get(begin));
        StringBuilder sb = new StringBuilder();
        for (int i = begin; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
            if (lines.get(i).trim().startsWith("End KeyMap") && column(lines.get(i)) == base) {
                return sb.toString();
            }
        }
        throw new AssertionError("unterminated KeyMap");
    }

    /** An item, commented or not, with its leading white space and any {@code #} removed. */
    private static String normalise(String line) {
        String t = line.replaceAll("^\\s+", "");
        if (t.startsWith("#")) {
            return "#" + t.substring(1).replaceAll("^\\s+", "");
        }
        return t;
    }

    private static String trimTrailingDigits(String s) {
        int n = s.length();
        while (n > 0 && Character.isDigit(s.charAt(n - 1))) {
            n--;
        }
        return s.substring(0, n);
    }

    /**
     * An item with the entry number of its name replaced by {@code #}, so that {@code Key3} and
     * {@code Key1} compare equal and {@code V3_2} compares equal to {@code V1_2}.
     */
    private static String renumber(String content) {
        String body = content.startsWith("#") ? content.substring(1) : content;
        String hash = content.startsWith("#") ? "#" : "";
        int eq = body.indexOf(" =");
        if (eq < 0) {
            return content;
        }
        String name = body.substring(0, eq);
        String rest = body.substring(eq);
        int us = name.indexOf('_');
        String renamed;
        if (us >= 0) {
            renamed = trimTrailingDigits(name.substring(0, us)) + "#" + name.substring(us);
        } else {
            String stem = trimTrailingDigits(name);
            renamed = stem.length() == name.length() ? name : stem + "#";
        }
        return hash + renamed + rest;
    }

    /**
     * A KeyMap block split into the items before the first entry and the items of each entry,
     * keyed by the entry's key, renumbered: C writes entries in the order of its hash table, so
     * the entries are compared without regard to order. Only items at the block's own column start
     * an entry, so a nested KeyMap's entries stay in the entry holding it.
     */
    private static Map<String, List<String>> splitKeyMap(String text, List<String> header) {
        List<String> lines = new ArrayList<>();
        for (String l : CValues.lines(text)) {
            if (!CValues.isBlank(l)) {
                lines.add(l);
            }
        }
        int begin = 0;
        while (!lines.get(begin).trim().startsWith("Begin KeyMap")) {
            begin++;
        }
        int base = column(lines.get(begin));
        int own = base + 3;
        Map<String, List<String>> entries = new TreeMap<>();
        List<String> current = header;
        for (int i = begin + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("End KeyMap") && column(line) == base) {
                break;
            }
            String card = normalise(line);
            int eq = card.indexOf(" =");
            boolean startsEntry = column(line) == own
                    && !card.startsWith("#")
                    && card.startsWith("Key")
                    && eq > 3
                    && card.substring(3, eq).chars().allMatch(Character::isDigit);
            if (startsEntry) {
                String value = card.substring(eq + 2);
                int tab = value.indexOf('\t');
                String key = (tab < 0 ? value : value.substring(0, tab)).trim().replaceAll("^\"|\"$", "");
                current = entries.computeIfAbsent(key, k -> new ArrayList<>());
            }
            current.add(renumber(card));
        }
        return entries;
    }

    private static boolean isMapSzCard(String card) {
        return card.replaceAll("^[#\\s]+", "").startsWith("MapSz =");
    }

    /** Rust {@code the_c_fixture_keymap_round_trips}: the KeyMap block of a C fixture, read and written. */
    @Test
    void theCFixtureKeyMapRoundTrips() {
        String block = extractKeyMapBlock(Fixtures.read("simplify/cap_timemap_02.map"));
        KeyMap km = assertInstanceOf(KeyMap.class, ChannelText.read(block));
        assertEquals(4, km.mapSize());
        for (String key : new String[] {"Size", "Name", "Resolution", "Error"}) {
            assertTrue(km.mapHasKey(key), "missing " + key);
        }
        assertEquals(4, km.mapLength("Name"));
        String out = ChannelText.dump(km);
        List<String> wantHeader = new ArrayList<>();
        List<String> gotHeader = new ArrayList<>();
        Map<String, List<String>> want = splitKeyMap(block, wantHeader);
        Map<String, List<String>> got = splitKeyMap(out, gotHeader);
        wantHeader.removeIf(KeyMapSerialTest::isMapSzCard);
        gotHeader.removeIf(KeyMapSerialTest::isMapSzCard);
        assertEquals(wantHeader, gotHeader, "header cards differ");
        assertEquals(want.keySet(), got.keySet(), "entry key sets differ");
        for (Map.Entry<String, List<String>> e : want.entrySet()) {
            assertEquals(e.getValue(), got.get(e.getKey()), "entry " + e.getKey() + " differs");
        }
    }

    /** Rust {@code the_c_fixture_map_sz_matches}: C wrote MapSz = 16 for these four keys. */
    @Test
    void theCFixtureMapSzMatches() {
        String block = extractKeyMapBlock(Fixtures.read("simplify/cap_timemap_02.map"));
        assertEquals(16, mapSzCardOf(block));
        KeyMap km = assertInstanceOf(KeyMap.class, ChannelText.read(block));
        assertEquals(16, dumpedMapSz(km));
    }
}
