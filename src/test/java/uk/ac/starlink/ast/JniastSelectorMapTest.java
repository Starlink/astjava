package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Translation of jniast's own {@code AstTest.testSwitchMap} ({@code
 * reference/starjava/jniast/src/testcases/uk/ac/starlink/ast/AstTest.java}), with the values it
 * expects, which jniast found with C's library: a SelectorMap of two 1-D Boxes meeting at 0, and a
 * SwitchMap whose forward selector it is. Its null arguments are refused as jniast's other
 * arguments are, with a {@code NullPointerException} naming the argument.
 */
class JniastSelectorMapTest {

    @Test
    void testSwitchMap() {
        Frame f = new Frame(1);
        Region lReg = new Box(f, 1, new double[] {-100}, new double[] {0}, null);
        Region rReg = new Box(f, 1, new double[] {0}, new double[] {+100}, null);
        SelectorMap fsMap = new SelectorMap(new Region[] {lReg, rReg}, -99);
        assertEquals(1, fsMap.getNout());
        assertArrayEquals(
                new double[] {0, 1, 2, -99}, fsMap.tran1(4, new double[] {9999, -1, +1, AstObject.AST__BAD}, true));
        Mapping lMap = new ShiftMap(new double[] {-4});
        Mapping rMap = new ShiftMap(new double[] {+4});
        SwitchMap swMap = new SwitchMap(fsMap, null, new Mapping[] {lMap, rMap});
        assertArrayEquals(
                new double[] {AstObject.AST__BAD, -5, +5, AstObject.AST__BAD},
                swMap.tran1(4, new double[] {-1000, -1, +1, +1000}, true));
    }

    @Test
    void nullArgumentsAreRefused() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> new SelectorMap(null, 0.0));
        assertEquals("regs", e.getMessage());
        Region box = new Box(new Frame(1), 1, new double[] {0}, new double[] {1}, null);
        e = assertThrows(NullPointerException.class, () -> new SelectorMap(new Region[] {box, null}, 0.0));
        assertEquals("regs[1]", e.getMessage());
    }
}
