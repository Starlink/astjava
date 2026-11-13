package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Deferred;

/**
 * Checks the public API against jniast's, as extracted into {@code jniast-api.txt} by
 * {@code tools/ExtractApi.java}: every member of every jniast class this library has exists with
 * the same name, parameter types, return type and access, except the members listed in
 * {@code jniast-api-exclusions.txt}, which must not exist; and every public or protected member
 * of those classes is a jniast member, overrides one or a member of a JDK type, or is listed with
 * its reason in {@code jniast-api-additions.txt}.
 */
class JniastApiTest {

    private static final String PACKAGE = "uk.ac.starlink.ast";

    /** The jniast classes this library provides. */
    static final List<String> IN_SCOPE = Arrays.asList(
            "AstException",
            "AstObject",
            "Axis",
            "Channel",
            "CmpFrame",
            "CmpMap",
            "FitsChan",
            "Frame",
            "FrameSet",
            "KeyMap",
            "Mapping",
            "MatrixMap",
            "PermMap",
            "PolyMap",
            "ResampleFlags",
            "ShiftMap",
            "SkyAxis",
            "SkyFrame",
            "SlaMap",
            "SphMap",
            "TimeFrame",
            "TimeMap",
            "UinterpCalculator",
            "Ukern1Calculator",
            "UnitMap",
            "WcsMap",
            "WinMap",
            "ZoomMap");

    /** One line of {@code jniast-api.txt}. */
    private static final class Member {
        final String owner;
        final String kind;
        final String access;
        final String type;
        final String name;
        final List<String> params;
        final String key;
        final String line;

        Member(String line) {
            this.line = line;
            String[] f = line.split("\\|", -1);
            owner = f[0];
            kind = f[1];
            access = f[2].startsWith("protected") ? "protected" : "public";
            type = f[3];
            int paren = f[4].indexOf('(');
            name = paren < 0 ? f[4] : f[4].substring(0, paren);
            params = paren < 0 || f[4].endsWith("()")
                    ? Collections.<String>emptyList()
                    : Arrays.asList(f[4].substring(paren + 1, f[4].length() - 1).split(",", -1));
            key = owner + "." + f[4];
        }
    }

    private static List<Member> api() throws IOException {
        List<Member> out = new ArrayList<>();
        try (InputStream in = JniastApiTest.class.getResourceAsStream("/jniast-api.txt")) {
            byte[] all = readAll(in);
            for (String l : CValues.lines(new String(all, StandardCharsets.UTF_8))) {
                if (!CValues.isBlank(l)) {
                    out.add(new Member(l));
                }
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** The class for a jniast name such as {@code Mapping.Interpolator} or {@code grf.DefaultGrf}, or null. */
    private static Class<?> lookup(String jniastName) {
        String[] parts = jniastName.split("\\.", -1);
        StringBuilder bin = new StringBuilder(PACKAGE);
        boolean inClass = false;
        for (String p : parts) {
            bin.append(inClass ? '$' : '.').append(p);
            inClass |= Character.isUpperCase(p.charAt(0));
        }
        try {
            return Class.forName(bin.toString(), false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Whether a jniast name is a top-level class rather than a nested one. */
    private static boolean isTopLevel(String jniastName) {
        int lastPkg = -1;
        String[] parts = jniastName.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            if (Character.isLowerCase(parts[i].charAt(0))) {
                lastPkg = i;
            }
        }
        return parts.length - lastPkg == 2;
    }

    /** A type as {@code jniast-api.txt} writes it: simple names, nested types qualified by their owners. */
    static String display(Class<?> c) {
        if (c.isArray()) {
            return display(c.getComponentType()) + "[]";
        }
        if (c.getEnclosingClass() != null) {
            return display(c.getEnclosingClass()) + "." + c.getSimpleName();
        }
        return c.getSimpleName();
    }

    /** The jniast type name without a subpackage prefix ({@code grf.DefaultGrf} becomes {@code DefaultGrf}). */
    private static String withoutPackage(String t) {
        String[] parts = t.split("\\.", -1);
        int first = 0;
        while (first < parts.length - 1 && Character.isLowerCase(parts[first].charAt(0))) {
            first++;
        }
        return String.join(".", Arrays.asList(parts).subList(first, parts.length));
    }

    private static String access(int mods) {
        if (Modifier.isPublic(mods)) {
            return "public";
        }
        return Modifier.isProtected(mods) ? "protected" : "other";
    }

    private static List<String> paramNames(Class<?>[] types) {
        List<String> out = new ArrayList<>();
        for (Class<?> t : types) {
            out.add(display(t));
        }
        return out;
    }

    private static List<String> expectedParams(Member m) {
        return m.params.stream().map(JniastApiTest::withoutPackage).collect(Collectors.toList());
    }

    /**
     * Every class and interface {@code c} inherits from, itself first, except
     * {@code java.lang.Object}, whose members jniast's classes declare again when they use them.
     */
    private static List<Class<?>> hierarchy(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        List<Class<?>> todo = new ArrayList<>();
        todo.add(c);
        while (!todo.isEmpty()) {
            Class<?> k = todo.remove(0);
            if (k == null || k == Object.class || out.contains(k)) {
                continue;
            }
            out.add(k);
            todo.add(k.getSuperclass());
            todo.addAll(Arrays.asList(k.getInterfaces()));
        }
        return out;
    }

    /** Whether {@code m} exists in {@code c}; members may be inherited. */
    private static boolean exists(Member m, Class<?> c) {
        switch (m.kind) {
            case "type":
                return true;
            case "field":
                for (Class<?> k : hierarchy(c)) {
                    for (Field f : k.getDeclaredFields()) {
                        if (f.getName().equals(m.name)
                                && display(f.getType()).equals(withoutPackage(m.type))
                                && access(f.getModifiers()).equals(m.access)) {
                            return true;
                        }
                    }
                }
                return false;
            case "constructor":
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    if (paramNames(k.getParameterTypes()).equals(expectedParams(m))
                            && access(k.getModifiers()).equals(m.access)) {
                        return true;
                    }
                }
                return false;
            case "method":
                for (Class<?> k : hierarchy(c)) {
                    for (Method f : k.getDeclaredMethods()) {
                        if (f.getName().equals(m.name)
                                && paramNames(f.getParameterTypes()).equals(expectedParams(m))
                                && display(f.getReturnType()).equals(withoutPackage(m.type))
                                && access(f.getModifiers()).equals(m.access)) {
                            return true;
                        }
                    }
                }
                return false;
            default:
                throw new IllegalStateException("Unknown member kind " + m.kind + " in " + m.line);
        }
    }

    private static String exclusionKey(Member m) {
        return "field".equals(m.kind) ? m.owner + "." + m.name : m.key;
    }

    @Test
    void everyJniastMemberOfAPresentClassExists() throws IOException {
        Map<String, String> excluded = Deferred.parse(readExclusions(), "jniast-api-exclusions.txt");
        Set<String> unusedExclusions = new TreeSet<>(excluded.keySet());
        List<String> missing = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (Member m : api()) {
            Class<?> c = lookup(m.owner);
            String ek = exclusionKey(m);
            unusedExclusions.remove(ek);
            if (c == null) {
                continue;
            }
            boolean present = exists(m, c);
            if (excluded.containsKey(ek)) {
                if (present) {
                    unexpected.add(m.line + "  (excluded: " + excluded.get(ek) + ")");
                }
            } else if (!present) {
                missing.add(m.line);
            }
        }
        assertTrue(missing.isEmpty(), "Missing jniast members:\n" + String.join("\n", missing));
        assertTrue(unexpected.isEmpty(), "Excluded members that exist:\n" + String.join("\n", unexpected));
        assertTrue(
                unusedExclusions.isEmpty(),
                "Exclusions naming no jniast member:\n" + String.join("\n", unusedExclusions));
    }

    @Test
    void presentClassesAreExactlyThoseInScope() throws IOException {
        Set<String> present = new TreeSet<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Member m : api()) {
            if (seen.put(m.owner, Boolean.TRUE) == null && isTopLevel(m.owner) && lookup(m.owner) != null) {
                present.add(m.owner);
            }
        }
        assertEquals(new TreeSet<>(IN_SCOPE), present);
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = JniastApiTest.class.getResourceAsStream("/" + name)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static String readExclusions() throws IOException {
        return readResource("jniast-api-exclusions.txt");
    }

    /** A class as jniast names it: {@code Mapping.Interpolator} for a nested class. */
    private static String jniastName(Class<?> c) {
        return display(c);
    }

    /** Whether a member with access modifiers {@code mods} is part of the API. */
    private static boolean isApi(int mods) {
        return Modifier.isPublic(mods) || Modifier.isProtected(mods);
    }

    /** The in-scope classes and the public or protected classes nested in them. */
    private static List<Class<?>> apiClasses() {
        List<Class<?>> out = new ArrayList<>();
        List<Class<?>> todo = new ArrayList<>();
        for (String name : IN_SCOPE) {
            todo.add(lookup(name));
        }
        while (!todo.isEmpty()) {
            Class<?> c = todo.remove(0);
            out.add(c);
            for (Class<?> n : c.getDeclaredClasses()) {
                if (isApi(n.getModifiers())) {
                    todo.add(n);
                }
            }
        }
        return out;
    }

    /** Whether {@code k}, a supertype of an API class, belongs to the JDK. */
    private static boolean isJdk(Class<?> k) {
        return !k.getName().startsWith(PACKAGE + ".");
    }

    /**
     * Whether {@code method}, declared in {@code c}, overrides a method of a JDK type {@code c}
     * inherits from, such as {@code Object.toString} or {@code Iterator.next}.
     */
    private static boolean overridesJdk(Class<?> c, Method method) {
        List<Class<?>> types = new ArrayList<>(hierarchy(c));
        types.add(Object.class);
        for (Class<?> k : types) {
            if (k == c || !isJdk(k)) {
                continue;
            }
            for (Method km : k.getDeclaredMethods()) {
                if (km.getName().equals(method.getName())
                        && Arrays.equals(km.getParameterTypes(), method.getParameterTypes())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The keys of the public and protected members of the API classes that are not jniast members
     * of the class or of a class it inherits from, as {@code jniast-api.txt} keys them ({@code
     * Class.name(types)}, {@code Class.name} for a field, {@code Class} for a type).
     */
    private static Set<String> additions(List<Member> api) {
        Set<String> jniast = new TreeSet<>();
        Set<String> types = new TreeSet<>();
        for (Member m : api) {
            if ("type".equals(m.kind)) {
                types.add(m.owner);
            } else {
                jniast.add("field".equals(m.kind) ? m.owner + "." + m.name : m.key);
            }
        }
        Set<String> out = new TreeSet<>();
        for (Class<?> c : apiClasses()) {
            String owner = jniastName(c);
            List<String> owners = new ArrayList<>();
            for (Class<?> k : hierarchy(c)) {
                if (!isJdk(k)) {
                    owners.add(jniastName(k));
                }
            }
            if (!types.contains(owner)) {
                out.add(owner);
            }
            for (Field f : c.getDeclaredFields()) {
                if (isApi(f.getModifiers()) && !f.isSynthetic() && !inherited(jniast, owners, f.getName())) {
                    out.add(owner + "." + f.getName());
                }
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                String sig = "<init>(" + String.join(",", paramNames(k.getParameterTypes())) + ")";
                if (isApi(k.getModifiers()) && !k.isSynthetic() && !jniast.contains(owner + "." + sig)) {
                    out.add(owner + "." + sig);
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!isApi(m.getModifiers()) || m.isSynthetic() || m.isBridge() || overridesJdk(c, m)) {
                    continue;
                }
                String sig = m.getName() + "(" + String.join(",", paramNames(m.getParameterTypes())) + ")";
                if (!inherited(jniast, owners, sig)) {
                    out.add(owner + "." + sig);
                }
            }
        }
        return out;
    }

    /** Whether one of {@code owners} has the jniast member {@code member}. */
    private static boolean inherited(Set<String> jniast, List<String> owners, String member) {
        for (String o : owners) {
            if (jniast.contains(o + "." + member)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyPublicMemberIsAJniastMemberOrAListedAddition() throws IOException {
        Map<String, String> listed =
                Deferred.parse(readResource("jniast-api-additions.txt"), "jniast-api-additions.txt");
        Set<String> extra = additions(api());
        Set<String> unlisted = new TreeSet<>(extra);
        unlisted.removeAll(listed.keySet());
        Set<String> stale = new TreeSet<>(listed.keySet());
        stale.removeAll(extra);
        assertTrue(
                unlisted.isEmpty(),
                "Public members that jniast lacks, not in jniast-api-additions.txt:\n" + String.join("\n", unlisted));
        assertTrue(
                stale.isEmpty(),
                "jniast-api-additions.txt entries naming no such member:\n" + String.join("\n", stale));
    }
}
