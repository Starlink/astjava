package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Attribute strings are parsed as C's object.c parses them (astSet, astGet, astTest, astClear). */
class AttributeSyntaxTest {

    @Test
    void namesInAnyCaseWithWhiteSpaceAndSeveralSettings() {
        AstObject o = new PlainObject();
        o.set(" ident = abc , id=x ");
        // VSet lower-cases the name and removes its white space, but copies the value after the
        // '=' unchanged (object.c:5004-5037), and SetAttrib takes the whole value
        // ("ident=%n%*[^\n]%n", object.c:3453).
        assertEquals(" abc ", o.getC("IDENT"));
        assertEquals("x ", o.getC(" Id "));
        assertTrue(o.test("ident"));
        o.clear("Ident");
        assertFalse(o.test("ident"));
    }

    @Test
    void unknownAttributeIsBadat() {
        AstException e = assertThrows(AstException.class, () -> new PlainObject().set("NoSuchAttr=1"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
    }

    @Test
    void readOnlyAttributeIsNowrt() {
        AstObject o = new PlainObject();
        assertEquals(
                AstException.AST__NOWRT,
                assertThrows(AstException.class, () -> o.setC("Class", "x")).getStatus());
        assertEquals(
                AstException.AST__NOWRT,
                assertThrows(AstException.class, () -> o.clear("Class")).getStatus());
        assertFalse(o.test("Class"));
    }

    @Test
    void oneSettingKeepsItsCommas() {
        // Commas separate settings only when there is more than one '='.
        AstObject o = new PlainObject();
        o.set("Ident=a,b");
        assertEquals("a,b", o.getIdent());
        o.setC("Ident", "c,d=e");
        assertEquals("c,d=e", o.getIdent());
    }

    @Test
    void quotedValuesLoseTheirQuotesAndKeepTheirCommas() {
        AstObject o = new PlainObject();
        o.set("ID=\"p, q\", Ident = \"r\"  ");
        assertEquals("p, q", o.getID());
        // The space between the '=' and the quote is part of the value (object.c:5018-5037).
        assertEquals(" r", o.getIdent());
    }

    @Test
    void anEmptyValueBecomesASpace() {
        // VSet appends a space after a bare '=' so that SetAttrib's pattern matches (object.c:5054).
        AstObject o = new PlainObject();
        o.set("Ident=");
        assertEquals(" ", o.getIdent());
        o.setIdent("");
        assertEquals("", o.getIdent());
        assertTrue(o.test("Ident"));
    }

    @Test
    void blankSettingsAreIgnored() {
        AstObject o = new PlainObject();
        o.set("");
        o.set("ID=a,,  ,Ident=b");
        assertEquals("a", o.getID());
        assertEquals("b", o.getIdent());
        o.clear(" , ID ,, ");
        assertFalse(o.test("ID"));
    }

    @Test
    void aBlankNameIsAnErrorForGetAndTest() {
        AstObject o = new PlainObject();
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> o.getC("  ")).getStatus());
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> o.test("")).getStatus());
    }

    @Test
    void aSettingWithoutEqualsIsBadat() {
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> new PlainObject().set("Ident"))
                        .getStatus());
    }

    @Test
    void numericValuesAreScannedAsC() {
        AstObject o = new PlainObject();
        o.set("UseDefs= 0 ");
        assertEquals(0, o.getI("usedefs"));
        assertFalse(o.getB("UseDefs"));
        o.setB("UseDefs", true);
        assertEquals(1, o.getI("UseDefs"));
        o.setI("UseDefs", 7);
        assertEquals(1, o.getI("UseDefs"));
        assertEquals(1.0, o.getD("UseDefs"), 0.0);
        assertEquals(1.0f, o.getF("UseDefs"), 0.0f);
        assertEquals(1L, o.getL("UseDefs"));
        o.setD("UseDefs", 0.0);
        assertEquals(0, o.getI("UseDefs"));
        o.setL("UseDefs", 3L);
        assertEquals(1, o.getI("UseDefs"));
        o.setF("UseDefs", 0.0f);
        assertEquals(0, o.getI("UseDefs"));
        // A value SetAttrib cannot read leaves every pattern unmatched (object.c:3463).
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> o.set("UseDefs=yes")).getStatus());
        o.clear("UseDefs");
        assertFalse(o.test("UseDefs"));
        assertEquals(1, o.getI("UseDefs"));
    }

    @Test
    void aValueOfTheWrongTypeIsAtger() {
        AstObject o = new PlainObject();
        o.setIdent("abc");
        AstException e = assertThrows(AstException.class, () -> o.getD("Ident"));
        assertEquals(AstException.AST__ATGER, e.getStatus());
        o.setIdent(" 1.5e3 ");
        assertEquals(1500.0, o.getD("Ident"), 0.0);
        assertEquals(
                AstException.AST__ATGER,
                assertThrows(AstException.class, () -> o.getI("Ident")).getStatus());
        o.setIdent("-12");
        assertEquals(-12, o.getI("Ident"));
        assertEquals(-12L, o.getL("Ident"));
    }

    @Test
    void readOnlyObjectAttributes() {
        AstObject o = new PlainObject();
        assertEquals("PlainObject", o.getC("Class"));
        assertEquals(1, o.getI("RefCount"));
        assertEquals(o.getNobject(), o.getI("Nobject"));
        assertEquals(o.getObjSize(), o.getI("ObjSize"));
    }
}
