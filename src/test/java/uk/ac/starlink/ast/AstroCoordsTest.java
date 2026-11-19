package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of jniast's {@code AstroCoordsTest}: the conversions between an AstroCoords and a
 * KeyMap. jniast reads the key names through {@code Stc.getAstConstantC("AST__STCNAME")} and so on;
 * here they are Stc's constants.
 */
class AstroCoordsTest {

    @Test
    void emptyCoords() {
        assertEquals(0, Stc.astroCoordsToKeyMap(new AstroCoords()).mapSize());

        AstroCoords ac = Stc.keyMapToAstroCoords(new KeyMap());
        assertNull(ac.getName());
        assertNull(ac.getValue());
        assertNull(ac.getError());
        assertNull(ac.getResolution());
        assertNull(ac.getSize());
        assertNull(ac.getPixSize());
    }

    @Test
    void coords() {
        String[] name = {"X", "Y"};
        Region value = makeBox(1);
        Region error = makeBox(2);
        Region res = makeBox(3);
        Region size = makeBox(4);
        Region pixsz = makeBox(5);

        AstroCoords ac = new AstroCoords();
        ac.setName(new String[] {"X", "Y"});
        ac.setValue(value);
        ac.setError(error);
        ac.setResolution(res);
        ac.setSize(size);
        ac.setPixSize(pixsz);

        assertArrayEquals(name, ac.getName());
        assertSameShape(value, ac.getValue());
        assertSameShape(error, ac.getError());
        assertSameShape(res, ac.getResolution());
        assertSameShape(size, ac.getSize());
        assertSameShape(pixsz, ac.getPixSize());

        AstroCoords ac0 = ac;
        KeyMap km = Stc.astroCoordsToKeyMap(ac);
        assertEquals(6, km.mapSize());
        assertEquals(2, km.mapLength(Stc.AST__STCNAME));
        assertEquals(1, km.mapLength(Stc.AST__STCRES));
        assertEquals(0, km.mapLength("urrgh"));

        ac = Stc.keyMapToAstroCoords(km);
        assertNotSame(ac0, ac);

        assertArrayEquals(name, ac.getName());
        assertSameShape(value, ac.getValue());
        assertSameShape(error, ac.getError());
        assertSameShape(res, ac.getResolution());
        assertSameShape(size, ac.getSize());
        assertSameShape(pixsz, ac.getPixSize());

        assertArrayEquals(name, new AstroCoords(name).getName());
    }

    private static Region makeBox(double origin) {
        return new Box(
                new Frame(2), 1, new double[] {origin, origin + 1.0}, new double[] {origin + 2.0, origin + 4.0}, null);
    }

    private static void assertSameShape(Region region1, Region region2) {
        int ov = region1.overlap(region2);
        assertEquals(Region.OVERLAP_SAME, ov, "Regions don't match (" + ov + ")");
    }
}
