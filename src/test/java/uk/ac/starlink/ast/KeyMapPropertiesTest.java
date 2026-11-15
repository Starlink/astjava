package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_keymap_properties.rs}: properties of KeyMaps built from
 * generated entries. A dump does not depend on the order the entries were stored in, and a
 * dump-load cycle is a fixed point.
 */
class KeyMapPropertiesTest {

    /** A deterministic xorshift generator; fixed seeds keep a failure reproducible. */
    private static final class Rng {
        private long state;

        Rng(long seed) {
            // A zero state is a fixed point for xorshift.
            state = seed | 1;
        }

        long nextU64() {
            long x = state;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            state = x;
            return x;
        }

        int below(int n) {
            return (int) Long.remainderUnsigned(nextU64(), n);
        }

        <T> void shuffle(List<T> items) {
            for (int i = items.size() - 1; i >= 1; i--) {
                int j = below(i + 1);
                Collections.swap(items, i, j);
            }
        }
    }

    /** The kinds of put the generator makes. */
    private enum Kind {
        INT,
        SHORT,
        LONG,
        BYTE,
        DOUBLE,
        FLOAT,
        STR,
        INT_VEC,
        STR_VEC,
        DOUBLE_VEC,
        UNDEF,
        NESTED
    }

    /** One instruction for building a KeyMap. */
    private static final class Put {
        final Kind kind;
        String key;
        final Object value;

        Put(Kind kind, String key, Object value) {
            this.kind = kind;
            this.key = key;
            this.value = value;
        }
    }

    private static final String[] STEMS = {
        "Alpha", "beta", "GAMMA", "delta9", "Delta10", "Delta2", "eps", "Epsilon", "z", "Zeta_1", "item", "Item", "a1b",
        "a10b", "a2b"
    };

    /** A random list of puts with unique keys. */
    private static List<Put> genPuts(Rng rng, int count, int depth) {
        List<Put> puts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = STEMS[rng.below(STEMS.length)] + i;
            int n = 1 + rng.below(4);
            int variant = rng.below(depth > 0 ? 12 : 11);
            switch (variant) {
                case 0:
                    puts.add(new Put(Kind.INT, key, (int) rng.nextU64()));
                    break;
                case 1:
                    puts.add(new Put(Kind.SHORT, key, (short) rng.nextU64()));
                    break;
                case 2:
                    puts.add(new Put(Kind.LONG, key, rng.nextU64()));
                    break;
                case 3:
                    puts.add(new Put(Kind.BYTE, key, (int) (rng.nextU64() & 0xff)));
                    break;
                case 4:
                    puts.add(new Put(Kind.DOUBLE, key, pickDouble(rng)));
                    break;
                case 5:
                    puts.add(new Put(Kind.FLOAT, key, (int) rng.nextU64() / 7.0f));
                    break;
                case 6:
                    puts.add(new Put(Kind.STR, key, genString(rng)));
                    break;
                case 7: {
                    int[] values = new int[n];
                    for (int k = 0; k < n; k++) {
                        values[k] = (int) rng.nextU64();
                    }
                    puts.add(new Put(Kind.INT_VEC, key, values));
                    break;
                }
                case 8: {
                    String[] values = new String[n];
                    for (int k = 0; k < n; k++) {
                        values[k] = genString(rng);
                    }
                    puts.add(new Put(Kind.STR_VEC, key, values));
                    break;
                }
                case 9: {
                    double[] values = new double[n];
                    for (int k = 0; k < n; k++) {
                        values[k] = pickDouble(rng);
                    }
                    puts.add(new Put(Kind.DOUBLE_VEC, key, values));
                    break;
                }
                case 10:
                    puts.add(new Put(Kind.UNDEF, key, null));
                    break;
                default:
                    int innerCount = 1 + rng.below(3);
                    puts.add(new Put(Kind.NESTED, key, genPuts(rng, innerCount, depth - 1)));
                    break;
            }
        }
        return puts;
    }

    /** Values that stress the 17-digit formatting, and AST__BAD, which is written as no item. */
    private static final double[] SPECIALS = {AstObject.AST__BAD, 0.0, -0.0, 1.0, -1.0, 1e-300, 1e300, Math.PI};

    private static double pickDouble(Rng rng) {
        if (rng.below(3) == 0) {
            return SPECIALS[rng.below(SPECIALS.length)];
        }
        return rng.nextU64() / 1024.0;
    }

    private static final String[] CHOICES = {"", "a", "Hello", "with space", "0123456789", "]"};

    private static String genString(Rng rng) {
        return CHOICES[rng.below(CHOICES.length)];
    }

    /** The comment for an entry, from its key alone so that it does not depend on put order. */
    private static String commentFor(String key) {
        int sum = 0;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            sum += b & 0xff;
        }
        switch (sum % 4) {
            case 0:
                return null;
            case 1:
                return "note for " + key;
            case 2:
                return key + " needs a \"quoted\" aside";
            default:
                return key + ": trailing detail";
        }
    }

    private static KeyMap build(List<Put> puts) {
        KeyMap km = new KeyMap();
        apply(km, puts);
        return km;
    }

    @SuppressWarnings("unchecked")
    private static void apply(KeyMap km, List<Put> puts) {
        for (Put put : puts) {
            String c = commentFor(put.key);
            switch (put.kind) {
                case INT:
                    km.mapPut0I(put.key, (Integer) put.value, c);
                    break;
                case SHORT:
                    km.mapPut0S(put.key, (Short) put.value, c);
                    break;
                case LONG:
                    km.mapPut0K(put.key, (Long) put.value, c);
                    break;
                case BYTE:
                    km.mapPut0B(put.key, (Integer) put.value, c);
                    break;
                case DOUBLE:
                    km.mapPut0D(put.key, (Double) put.value, c);
                    break;
                case FLOAT:
                    km.mapPut0F(put.key, (Float) put.value, c);
                    break;
                case STR:
                    km.mapPut0C(put.key, (String) put.value, c);
                    break;
                case INT_VEC:
                    km.mapPut1I(put.key, (int[]) put.value, c);
                    break;
                case STR_VEC:
                    km.mapPut1C(put.key, (String[]) put.value, c);
                    break;
                case DOUBLE_VEC:
                    km.mapPut1D(put.key, (double[]) put.value, c);
                    break;
                case UNDEF:
                    km.mapPutU(put.key, c);
                    break;
                case NESTED:
                    km.mapPut0A(put.key, build((List<Put>) put.value), c);
                    break;
                default:
                    throw new AssertionError("unknown put kind " + put.kind);
            }
        }
    }

    private static String dump(KeyMap km) {
        return ChannelText.dump(km);
    }

    private static KeyMap load(String text) {
        AstObject obj = ChannelText.read(text);
        assertInstanceOf(KeyMap.class, obj, "expected a KeyMap, got " + obj.getClass());
        return (KeyMap) obj;
    }

    private static List<String> keysOf(KeyMap km) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < km.mapSize(); i++) {
            keys.add(km.mapKey(i));
        }
        return keys;
    }

    private static final long[] SEEDS = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233};

    @Test
    void theDumpIsIndependentOfInsertionOrder() {
        for (long seed : SEEDS) {
            Rng rng = new Rng(seed);
            int count = 4 + rng.below(20);
            List<Put> puts = genPuts(rng, count, 2);
            KeyMap km = build(puts);
            assertEquals(puts.size(), km.mapSize(), "seed " + seed + ": generator produced a duplicate key");
            String reference = dump(km);
            for (int round = 0; round < 5; round++) {
                List<Put> shuffled = new ArrayList<>(puts);
                rng.shuffle(shuffled);
                assertEquals(
                        reference,
                        dump(build(shuffled)),
                        "seed " + seed + " round " + round + ": dump depends on insertion order");
            }
        }
    }

    @Test
    void theReadOutOrderIsIndependentOfInsertionOrder() {
        for (long seed : SEEDS) {
            Rng rng = new Rng(seed);
            int count = 4 + rng.below(20);
            List<Put> puts = genPuts(rng, count, 0);
            KeyMap km = build(puts);
            assertEquals(puts.size(), km.mapSize(), "seed " + seed + ": generator produced a duplicate key");
            List<String> reference = keysOf(km);
            for (int round = 0; round < 5; round++) {
                List<Put> shuffled = new ArrayList<>(puts);
                rng.shuffle(shuffled);
                KeyMap other = build(shuffled);
                assertEquals(
                        reference,
                        keysOf(other),
                        "seed " + seed + " round " + round + ": mapKey order depends on insertion order");
                List<String> iterated = new ArrayList<>();
                boolean reset = true;
                for (String key = other.mapIterate(reset); key != null; key = other.mapIterate(reset)) {
                    iterated.add(key);
                    reset = false;
                }
                assertEquals(reference, iterated, "seed " + seed + ": mapIterate disagrees");
            }
        }
    }

    @Test
    void aDumpLoadCycleIsAFixedPoint() {
        for (long seed : SEEDS) {
            Rng rng = new Rng(seed);
            int count = 4 + rng.below(20);
            List<Put> puts = genPuts(rng, count, 2);
            KeyMap km = build(puts);
            assertEquals(puts.size(), km.mapSize(), "seed " + seed + ": generator produced a duplicate key");
            String once = dump(km);
            String twice = dump(load(once));
            assertEquals(once, twice, "seed " + seed + ": dump/load is not idempotent");
            // A third cycle catches an asymmetry that cancels on the first.
            String thrice = dump(load(twice));
            assertEquals(once, thrice, "seed " + seed + ": third cycle diverged");
        }
    }

    /** Checks that {@code back} holds the value {@code km} holds under {@code key}. */
    private static void assertSameValue(KeyMap km, KeyMap back, String key, long seed) {
        int ty = km.mapType(key);
        switch (ty) {
            case KeyMap.AST__INTTYPE:
            case KeyMap.AST__SINTTYPE:
            case KeyMap.AST__KINTTYPE:
            case KeyMap.AST__BYTETYPE:
            case KeyMap.AST__DOUBLETYPE:
            case KeyMap.AST__FLOATTYPE:
                assertArrayEquals(km.mapGet1D(key), back.mapGet1D(key), "seed " + seed + ": numeric value of " + key);
                break;
            case KeyMap.AST__STRINGTYPE:
                assertArrayEquals(km.mapGet1C(key), back.mapGet1C(key), "seed " + seed + ": text value of " + key);
                break;
            case KeyMap.AST__OBJECTTYPE: {
                AstObject[] original = new AstObject[1];
                AstObject[] roundTripped = new AstObject[1];
                boolean hasOriginal = km.mapGet0A(key, original);
                boolean hasRoundTripped = back.mapGet0A(key, roundTripped);
                assertEquals(hasOriginal, hasRoundTripped, "seed " + seed + ": object presence of " + key);
                if (hasOriginal) {
                    assertEquals(
                            original[0] == null ? null : ChannelText.dump(original[0]),
                            roundTripped[0] == null ? null : ChannelText.dump(roundTripped[0]),
                            "seed " + seed + ": nested object value of " + key);
                }
                break;
            }
            case KeyMap.AST__UNDEFTYPE:
                // mapGet1C gives null here whatever the asymmetry, so check definedness instead.
                assertFalse(km.mapDefined(key), "seed " + seed + ": " + key + " should be undefined");
                assertFalse(back.mapDefined(key), "seed " + seed + ": round-tripped " + key + " should be undefined");
                break;
            default:
                throw new AssertionError("no entry is stored with data type " + ty);
        }
    }

    @Test
    void aDumpLoadCyclePreservesEveryValue() {
        for (long seed : SEEDS) {
            Rng rng = new Rng(seed);
            int count = 4 + rng.below(20);
            List<Put> puts = genPuts(rng, count, 1);
            KeyMap km = build(puts);
            assertEquals(puts.size(), km.mapSize(), "seed " + seed + ": generator produced a duplicate key");
            KeyMap back = load(dump(km));
            assertEquals(km.mapSize(), back.mapSize(), "seed " + seed + ": size");
            for (int i = 0; i < km.mapSize(); i++) {
                String key = km.mapKey(i);
                assertEquals(km.mapType(key), back.mapType(key), "seed " + seed + ": type of " + key);
                assertEquals(km.mapLength(key), back.mapLength(key), "seed " + seed + ": length of " + key);
                assertEquals(km.mapDefined(key), back.mapDefined(key), "seed " + seed + ": definedness of " + key);
                assertEquals(km.mapComment(key), back.mapComment(key), "seed " + seed + ": comment of " + key);
                assertSameValue(km, back, key, seed);
            }
        }
    }

    @Test
    void everySortModeGivesATotalOrderIndependentOfInsertion() {
        // The age orders depend on the member numbers, which depend on insertion order, so each
        // mode is checked for being a stable permutation of the keys.
        for (long seed : SEEDS) {
            Rng rng = new Rng(seed);
            int count = 4 + rng.below(12);
            List<Put> puts = genPuts(rng, count, 0);
            for (KeyMapSort mode : KeyMapSort.values()) {
                KeyMap km = build(puts);
                assertEquals(puts.size(), km.mapSize(), "seed " + seed + ": generator produced a duplicate key");
                km.setSortBy(mode);
                List<String> keys = keysOf(km);
                assertEquals(
                        km.mapSize(),
                        new TreeSet<>(keys).size(),
                        "seed " + seed + " mode " + mode + ": keys are not a permutation");
                assertEquals(keys, keysOf(km), "seed " + seed + " mode " + mode + ": order is unstable");
                if (mode == KeyMapSort.KEY_DOWN) {
                    KeyMap up = build(puts);
                    up.setSortBy(KeyMapSort.KEY_UP);
                    List<String> reversed = new ArrayList<>(keys);
                    Collections.reverse(reversed);
                    assertEquals(reversed, keysOf(up), "seed " + seed + ": KeyDown is not the reverse of KeyUp");
                }
            }
        }
    }

    @Test
    // PMD takes the assignment of a key to the other list's key for a string append.
    @SuppressWarnings("PMD.UseStringBufferForStringAppends")
    void mapCopyIsOrderIndependent() {
        for (long seed : SEEDS) {
            Rng rng = new Rng(seed);
            int countA = 3 + rng.below(8);
            List<Put> aPuts = genPuts(rng, countA, 1);
            int countB = 3 + rng.below(8);
            List<Put> bPuts = genPuts(rng, countB, 1);
            // Force an overlap so that the replace path is exercised on every seed.
            bPuts.get(0).key = aPuts.get(0).key;

            KeyMap dest = build(aPuts);
            dest.mapCopy(build(bPuts));
            String reference = dump(dest);

            for (int round = 0; round < 3; round++) {
                List<Put> a = new ArrayList<>(aPuts);
                List<Put> b = new ArrayList<>(bPuts);
                rng.shuffle(a);
                rng.shuffle(b);
                KeyMap d = build(a);
                d.mapCopy(build(b));
                assertEquals(
                        reference,
                        dump(d),
                        "seed " + seed + " round " + round + ": mapCopy depends on insertion order");
            }
        }
    }

    /**
     * A re-put advances the member count once where a new key advances it twice; this applies a
     * second batch of puts to keys already present and checks the dump-load cycle is still a fixed
     * point that keeps every value and comment.
     */
    @Test
    void aDumpLoadCycleIsAFixedPointAfterAReput() {
        for (long seed : SEEDS) {
            Rng rng = new Rng(seed);
            int count = 4 + rng.below(12);
            List<Put> puts = genPuts(rng, count, 1);
            KeyMap km = build(puts);
            assertEquals(puts.size(), km.mapSize(), "seed " + seed + ": generator produced a duplicate key");

            int reputCount = 1 + rng.below(6);
            List<Put> reputs = genPuts(rng, reputCount, 0);
            for (int i = 0; i < reputs.size(); i++) {
                reputs.get(i).key = Objects.requireNonNull(puts.get(i % puts.size()).key);
            }
            apply(km, reputs);
            assertEquals(puts.size(), km.mapSize(), "seed " + seed + ": a re-put changed the key count");

            String once = dump(km);
            KeyMap back = load(once);
            assertEquals(once, dump(back), "seed " + seed + ": dump/load is not a fixed point after a re-put");

            for (int i = 0; i < km.mapSize(); i++) {
                String key = km.mapKey(i);
                assertEquals(km.mapType(key), back.mapType(key), "seed " + seed + ": type of " + key);
                assertEquals(km.mapComment(key), back.mapComment(key), "seed " + seed + ": comment of " + key);
                assertSameValue(km, back, key, seed);
            }
        }
    }
}
