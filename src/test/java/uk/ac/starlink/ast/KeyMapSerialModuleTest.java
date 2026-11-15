package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests in Rust {@code src/keymap/serial.rs}, which test KeyMap's dump and
 * load (keymap.c {@code Dump}, {@code DumpEntry} and {@code astLoadKeyMap}).
 */
class KeyMapSerialModuleTest {

    private static String render(NativeNode node) {
        return NativeWriter.render(node, ChannelOptions.defaults());
    }

    @Test
    void dumpCardOrderMatchesC() {
        KeyMap km = new KeyMap();
        km.mapPut0I("Size", 1, null);
        km.mapPut1C("Name", new String[] {"Position", "Time"}, null);

        NativeNode node = NativeNode.of(km);
        assertEquals("KeyMap", node.className);
        assertEquals("Map of key/value pairs", node.classComment);

        // The Object and KeyMap attributes are unset, so MapSz and MemCnt, which are always set,
        // come first, then the entries in key order: Name before Size.
        List<String> keys = new ArrayList<>();
        for (NativeNode.Item item : node.items) {
            if (!item.isIsA() && item.set) {
                keys.add(item.key);
            }
        }
        assertEquals(
                Arrays.asList("MapSz", "MemCnt", "Key1", "Typ1", "Nel1", "V1_1", "V1_2", "Key2", "Typ2", "Val2"), keys);

        // Only the first element of a vector carries the comment; the rest carry an empty one.
        assertEquals("Item name", node.item("Key1").comment);
        assertEquals("Item data type (string)", node.item("Typ1").comment);
        assertEquals("Vector length", node.item("Nel1").comment);
        assertEquals("Item values", node.item("V1_1").comment);
        assertEquals("", node.item("V1_2").comment);
        assertEquals("Item value", node.item("Val2").comment);
        assertEquals("Size of hash table", node.item("MapSz").comment);
        assertEquals("Total member count", node.item("MemCnt").comment);
    }

    @Test
    void unsetAttributesAreNotHelpfulAndSetOnesAreActive() {
        // C passes helpful=0 for all five, so channel.c Use writes an unset one only when Full is
        // positive.
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, null);

        NativeNode node = NativeNode.of(km);
        for (String key : new String[] {"SzGss", "SortBy", "KyCas", "KyErr", "MpLck"}) {
            NativeNode.Item item = node.item(key);
            assertNotNull(item, key);
            assertFalse(item.set, key + " is set");
            assertFalse(item.helpful, key + " is helpful");
        }
        assertFalse(render(node).contains("SzGss"));
        // MapSz and MemCnt are unconditional.
        assertSame(NativeNode.Kind.INT, node.item("MapSz").value.kind);
        assertSame(NativeNode.Kind.INT, node.item("MemCnt").value.kind);

        km.setKeyError(true);
        km.setSortBy(KeyMapSort.KEY_UP);
        node = NativeNode.of(km);
        assertEquals(1, node.readInt("KyErr", -1));
        assertEquals("KeyUp", node.readString("SortBy", ""));
    }

    @Test
    void aCommentIsWrittenOnlyWhenNonEmpty() {
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, "has one");
        km.mapPut0I("B", 2, "");
        km.mapPut0I("C", 3, null);

        NativeNode node = NativeNode.of(km);
        assertEquals("has one", node.readString("Com1", ""));
        // C tests *(entry->comment), so an empty comment is not written.
        assertNull(node.item("Com2"));
        assertNull(node.item("Com3"));
    }

    @Test
    void aBadDoubleIsOmittedAndReloadsAsBad() {
        KeyMap km = new KeyMap();
        km.mapPut0D("S", AstObject.AST__BAD, null);
        km.mapPut1D("V", new double[] {AstObject.AST__BAD, 1.5}, null);

        NativeNode node = NativeNode.of(km);
        // No Val item at all for the scalar.
        assertNull(node.item("Val1"));
        // The bad element of the vector is skipped, so the comment goes on the first element
        // actually written.
        assertNull(node.item("V2_1"));
        assertEquals("Item values", node.item("V2_2").comment);

        KeyMap back = new KeyMap(node);
        assertEquals(AstObject.AST__BAD, back.mapGet0D("S"));
        assertArrayEquals(new double[] {AstObject.AST__BAD, 1.5}, back.mapGet1D("V"));
    }

    @Test
    void puttingAZeroLengthVectorIsRefused() {
        // C rejects a vector of length zero with AST__NELIN (keymap.c, upstream b7135abc).
        KeyMap km = new KeyMap();
        AstException[] errs = {
            assertThrows(AstException.class, () -> km.mapPut1I("I", new int[0], null)),
            assertThrows(AstException.class, () -> km.mapPut1D("D", new double[0], null)),
            assertThrows(AstException.class, () -> km.mapPut1C("C", new String[0], null)),
        };
        for (AstException e : errs) {
            assertEquals(AstException.AST__NELIN, e.getStatus());
        }

        // No key was created, and the KeyMap still dumps.
        assertFalse(km.mapHasKey("I"));
        assertFalse(km.mapHasKey("D"));
        assertFalse(km.mapHasKey("C"));
        km.mapPut0I("REAL", 7, null);
        assertEquals(7, new KeyMap(NativeNode.of(km)).mapGet0I("REAL"));
    }

    @Test
    void roundTripPreservesEveryType() {
        KeyMap inner = new KeyMap();
        inner.mapPut0C("NESTED", "yes", null);

        KeyMap km = new KeyMap();
        // KeyCase must be set before any entry is added (keymap.c SetKeyCase).
        km.setKeyCase(false);
        km.mapPut0I("I", -7, "an int");
        km.mapPut0S("S", (short) -3, null);
        km.mapPut0K("K", Long.MIN_VALUE, null);
        km.mapPut0B("B", 200, null);
        km.mapPut0D("D", 1.2345678901234568e-5, null);
        km.mapPut0F("F", 1.5f, null);
        km.mapPut0C("C", "text", null);
        km.mapPut0A("A", inner, null);
        km.mapPutU("U", "undefined");
        km.mapPut1I("VI", new int[] {1, 2, 3}, null);

        KeyMap back = new KeyMap(NativeNode.of(km));

        assertEquals(km.mapSize(), back.mapSize());
        for (String key : new String[] {"I", "S", "K", "B", "D", "F", "C", "A", "U", "VI"}) {
            assertEquals(km.mapType(key), back.mapType(key), "type of " + key);
        }
        assertEquals(-7, back.mapGet0I("I"));
        assertEquals("an int", back.mapComment("I"));
        assertEquals(Long.MIN_VALUE, back.mapGet0K("K"));
        assertEquals(200, back.mapGet0B("B"));
        assertEquals(km.mapGet0D("D"), back.mapGet0D("D"));
        assertEquals(1.5f, back.mapGet0F("F"));
        assertArrayEquals(new int[] {1, 2, 3}, back.mapGet1I("VI"));
        assertFalse(back.mapDefined("U"));
        assertTrue(back.mapHasKey("U"));
        assertEquals("undefined", back.mapComment("U"));
        assertFalse(back.getKeyCase());

        AstObject nested = back.mapGet0A("A");
        assertInstanceOf(KeyMap.class, nested, "expected a nested KeyMap");
        assertEquals("yes", ((KeyMap) nested).mapGet0C("NESTED"));
    }

    @Test
    void anAbsentKycasCardLeavesKeyCaseUnset() {
        // C once read KyCas with the wrong unset sentinel, so an absent item came back set
        // (keymap.c, upstream eec1e53d).
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, null);
        assertFalse(km.test("KeyCase"));

        NativeNode node = NativeNode.of(km);
        assertNull(node.get("KyCas"));
        assertFalse(new KeyMap(node).test("KeyCase"));

        // An item that is present still reloads as a set attribute.
        KeyMap set = new KeyMap();
        set.setKeyCase(true);
        set.mapPut0I("A", 1, null);
        node = NativeNode.of(set);
        assertNotNull(node.item("KyCas"));
        KeyMap back = new KeyMap(node);
        assertTrue(back.test("KeyCase"));
        assertTrue(back.getKeyCase());
    }

    @Test
    void aLockedNonEmptyKeymapReloads() {
        // astLoadKeyMap applies MpLck after reading the entries (keymap.c, upstream f444583f).
        KeyMap km = new KeyMap();
        km.mapPut0I("A", 1, null);
        km.setMapLocked(true);

        KeyMap back = new KeyMap(NativeNode.of(km));
        assertEquals(1, back.mapGet0I("A"));
        assertTrue(back.getMapLocked());
    }

    private static KeyMap build(String... order) {
        KeyMap km = new KeyMap();
        for (int i = 0; i < order.length; i++) {
            km.mapPut0I(order[i], i, null);
        }
        return km;
    }

    private static List<String> keyItems(KeyMap km) {
        List<String> out = new ArrayList<>();
        for (NativeNode.Item item : NativeNode.of(km).items) {
            if (item.key.startsWith("Key")) {
                assertSame(NativeNode.Kind.STRING, item.value.kind, item.key);
                out.add(item.value.stringValue);
            }
        }
        return out;
    }

    @Test
    void theDumpIsIdempotentAndInsertionOrderIndependent() {
        KeyMap a = build("Size", "Error", "Name", "Resolution");
        KeyMap b = build("Resolution", "Name", "Error", "Size");

        assertEquals(Arrays.asList("Error", "Name", "Resolution", "Size"), keyItems(a));
        assertEquals(keyItems(a), keyItems(b));

        // Reloading and dumping again gives the same items.
        String once = render(NativeNode.of(a));
        String twice = render(NativeNode.of(new KeyMap(NativeNode.of(a))));
        assertEquals(once, twice);
    }

    @Test
    void anUnknownTypeCodeIsAnError() {
        NativeNode node = new NativeNode("KeyMap");
        node.writeInt("MapSz", true, true, 16, null);
        node.writeInt("MemCnt", true, true, 2, null);
        node.writeString("Key1", true, true, "A", null);
        node.writeInt("Typ1", true, true, 6, null);
        node.writeString("Val1", true, true, "0x1234", null);

        AstException e = assertThrows(AstException.class, () -> new KeyMap(node));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(
                e.getMessage().contains("Unknown data type code (6) encountered whilst reading a KeyMap."),
                e.getMessage());
    }

    @Test
    void aMissingTypeCodeIsADifferentError() {
        NativeNode node = new NativeNode("KeyMap");
        node.writeInt("MapSz", true, true, 16, null);
        node.writeInt("MemCnt", true, true, 2, null);
        node.writeString("Key1", true, true, "A", null);

        AstException e = assertThrows(AstException.class, () -> new KeyMap(node));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(e.getMessage().contains("No data type code found whilst reading a KeyMap."), e.getMessage());
    }

    @Test
    void theEntryLoopStopsAtTheFirstGap() {
        NativeNode node = new NativeNode("KeyMap");
        node.writeInt("MapSz", true, true, 16, null);
        node.writeInt("MemCnt", true, true, 6, null);
        node.writeString("Key1", true, true, "A", null);
        node.writeInt("Typ1", true, true, 1, null);
        node.writeInt("Val1", true, true, 1, null);
        // Key2 is missing, so Key3 is never reached.
        node.writeString("Key3", true, true, "C", null);
        node.writeInt("Typ3", true, true, 1, null);
        node.writeInt("Val3", true, true, 3, null);

        KeyMap km = new KeyMap(node);
        assertEquals(1, km.mapSize());
        assertTrue(km.mapHasKey("A"));
    }

    @Test
    void mapSzIsWrittenFromTheContents() {
        KeyMap km = new KeyMap();
        for (int i = 0; i < 4; i++) {
            km.mapPut0I("K" + i, i, null);
        }
        NativeNode node = NativeNode.of(km);
        assertEquals(km.mapSzForDump(), node.readInt("MapSz", -1));
        assertEquals(16, node.readInt("MapSz", -1));

        // The MapSz read is discarded: the size written is worked out from the keys.
        for (int i = 0; i < node.items.size(); i++) {
            NativeNode.Item item = node.items.get(i);
            if ("MapSz".equals(item.key)) {
                node.items.set(i, new NativeNode.Item("MapSz", NativeNode.Value.ofInt(4096), true, item.comment, true));
            }
        }
        KeyMap back = new KeyMap(node);
        assertEquals(16, NativeNode.of(back).readInt("MapSz", -1));
    }

    @Test
    void memCntRoundTrips() {
        KeyMap km = new KeyMap();
        for (int i = 0; i < 4; i++) {
            km.mapPut0I("K" + i, i, null);
        }
        // Each new key advances the counter twice; cap_timemap_02.map records MemCnt = 8 for its
        // four keys.
        NativeNode node = NativeNode.of(km);
        assertEquals(8, node.readInt("MemCnt", -1));
        KeyMap back = new KeyMap(node);
        assertEquals(8, NativeNode.of(back).readInt("MemCnt", -1));
    }
}
