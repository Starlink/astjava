import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Prints the public and protected API of jniast, one line per member, for the API check in
 * {@code JniastApiTest}.
 *
 * <p>Usage: {@code java tools/ExtractApi.java <jniast source root>}, where the root is the
 * directory holding {@code uk/ac/starlink/ast}; for example
 * {@code java tools/ExtractApi.java reference/starjava/jniast/src/main > src/test/resources/jniast-api.txt}.
 *
 * <p>The sources are parsed but not compiled, so no type is resolved. Each line is
 * {@code <Class>|<kind>|<modifiers>|<type>|<name>(<parameter types>)}, sorted, where
 * {@code <Class>} is the type's name relative to {@code uk.ac.starlink.ast} with nested types
 * joined by dots ({@code Mapping.Interpolator}, {@code grf.DefaultGrf}); {@code <kind>} is
 * {@code type}, {@code field}, {@code constructor} or {@code method}; types are written as simple
 * names without type arguments, and a name that refers to a type nested in the class or one of its
 * enclosing classes or superclasses in the package is qualified by that class ({@code
 * Mapping.Interpolator}); constructors are named {@code <init>}. Fields and types have no
 * parameter list. Modifiers are the access modifier and any of {@code static}, {@code final} and
 * {@code abstract}, including those implied by an interface.
 */
public final class ExtractApi {

    private static final String BASE_PACKAGE = "uk.ac.starlink.ast";

    /** One class or interface declaration, with what is needed to resolve names in it. */
    private static final class TypeInfo {
        final String name;
        final String pkg;
        final ClassTree tree;
        final TypeInfo enclosing;
        final Set<String> nested = new TreeSet<>();

        TypeInfo(String name, String pkg, ClassTree tree, TypeInfo enclosing) {
            this.name = name;
            this.pkg = pkg;
            this.tree = tree;
            this.enclosing = enclosing;
        }

        boolean isInterface() {
            return tree.getKind() == Tree.Kind.INTERFACE || tree.getKind() == Tree.Kind.ANNOTATION_TYPE;
        }
    }

    private final Map<String, TypeInfo> types = new HashMap<>();
    private final List<TypeInfo> order = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java tools/ExtractApi.java <jniast source root>");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        List<Path> files;
        try (Stream<Path> s = Files.walk(root.resolve(BASE_PACKAGE.replace('.', '/')))) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        ExtractApi api = new ExtractApi();
        api.parse(files);
        for (String line : api.lines()) {
            System.out.println(line);
        }
    }

    private void parse(List<Path> files) throws IOException {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        JavacTask task = (JavacTask)
                jc.getTask(null, fm, null, List.of("-proc:none"), null, fm.getJavaFileObjectsFromPaths(files));
        for (CompilationUnitTree cu : task.parse()) {
            String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
            String rel = pkg.equals(BASE_PACKAGE) ? "" : pkg.substring(BASE_PACKAGE.length() + 1) + ".";
            for (Tree t : cu.getTypeDecls()) {
                if (t instanceof ClassTree) {
                    collect((ClassTree) t, rel + ((ClassTree) t).getSimpleName(), pkg, null);
                }
            }
        }
    }

    private void collect(ClassTree ct, String name, String pkg, TypeInfo enclosing) {
        TypeInfo info = new TypeInfo(name, pkg, ct, enclosing);
        types.put(name, info);
        order.add(info);
        for (Tree m : ct.getMembers()) {
            if (m instanceof ClassTree) {
                ClassTree inner = (ClassTree) m;
                info.nested.add(inner.getSimpleName().toString());
                collect(inner, name + "." + inner.getSimpleName(), pkg, info);
            }
        }
    }

    /** Whether a member with these modifiers, declared in {@code owner}, is public or protected. */
    private static String access(ModifiersTree mods, TypeInfo owner) {
        Set<Modifier> f = mods.getFlags();
        if (f.contains(Modifier.PUBLIC) || owner.isInterface()) {
            return "public";
        }
        if (f.contains(Modifier.PROTECTED)) {
            return "protected";
        }
        return null;
    }

    private static boolean visible(TypeInfo t) {
        for (TypeInfo c = t; c != null; c = c.enclosing) {
            boolean inInterface = c.enclosing != null && c.enclosing.isInterface();
            if (access(c.tree.getModifiers(), c.enclosing == null ? c : c.enclosing) == null && !inInterface) {
                return false;
            }
        }
        return true;
    }

    private static String modifiers(
            String access, ModifiersTree mods, boolean impliedStatic, boolean impliedFinal, boolean impliedAbstract) {
        Set<Modifier> f = mods.getFlags();
        StringBuilder sb = new StringBuilder(access);
        if (f.contains(Modifier.STATIC) || impliedStatic) {
            sb.append(" static");
        }
        if (f.contains(Modifier.FINAL) || impliedFinal) {
            sb.append(" final");
        }
        if (f.contains(Modifier.ABSTRACT) || impliedAbstract) {
            sb.append(" abstract");
        }
        return sb.toString();
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        for (TypeInfo t : order) {
            if (!visible(t)) {
                continue;
            }
            String kind = t.isInterface() ? "interface" : t.tree.getKind() == Tree.Kind.ENUM ? "enum" : "class";
            boolean nestedInInterface = t.enclosing != null && t.enclosing.isInterface();
            String typeAccess = nestedInInterface ? "public" : access(t.tree.getModifiers(), t);
            out.add(t.name + "|type|"
                    + modifiers(
                            typeAccess,
                            t.tree.getModifiers(),
                            nestedInInterface || t.isInterface() && t.enclosing != null,
                            false,
                            t.isInterface())
                    + "|" + kind + "|" + t.name);
            boolean hasConstructor = false;
            for (Tree m : t.tree.getMembers()) {
                if (m instanceof MethodTree) {
                    MethodTree mt = (MethodTree) m;
                    boolean ctor = mt.getReturnType() == null;
                    hasConstructor |= ctor;
                    String acc = access(mt.getModifiers(), t);
                    if (acc == null) {
                        continue;
                    }
                    boolean implAbstract = t.isInterface()
                            && mt.getBody() == null
                            && !mt.getModifiers().getFlags().contains(Modifier.STATIC);
                    String params = mt.getParameters().stream()
                            .map(p -> typeName(p.getType(), t))
                            .collect(Collectors.joining(","));
                    out.add(t.name + "|" + (ctor ? "constructor" : "method") + "|"
                            + modifiers(acc, mt.getModifiers(), false, false, implAbstract) + "|"
                            + (ctor ? "" : typeName(mt.getReturnType(), t)) + "|"
                            + (ctor ? "<init>" : mt.getName().toString()) + "(" + params + ")");
                } else if (m instanceof VariableTree) {
                    VariableTree vt = (VariableTree) m;
                    String acc = access(vt.getModifiers(), t);
                    if (acc == null) {
                        continue;
                    }
                    boolean enumConstant = t.tree.getKind() == Tree.Kind.ENUM && vt.getType() == null;
                    out.add(t.name + "|field|"
                            + modifiers(
                                    acc,
                                    vt.getModifiers(),
                                    t.isInterface() || enumConstant,
                                    t.isInterface() || enumConstant,
                                    false)
                            + "|" + (enumConstant ? t.name : typeName(vt.getType(), t)) + "|" + vt.getName());
                }
            }
            if (!hasConstructor && t.tree.getKind() == Tree.Kind.CLASS) {
                out.add(t.name + "|constructor|" + typeAccess + "||<init>()");
            }
        }
        Collections.sort(out);
        return out;
    }

    /** The simple name of a type as written in {@code context}, resolved against nested types. */
    private String typeName(Tree type, TypeInfo context) {
        String s = type.toString();
        int lt;
        while ((lt = s.indexOf('<')) >= 0) {
            int depth = 0;
            int end = lt;
            for (; end < s.length(); end++) {
                char c = s.charAt(end);
                if (c == '<') {
                    depth++;
                } else if (c == '>' && --depth == 0) {
                    break;
                }
            }
            s = s.substring(0, lt) + s.substring(end + 1);
        }
        s = s.replace("...", "[]").replace(" ", "");
        String dims = "";
        int br = s.indexOf('[');
        if (br >= 0) {
            dims = s.substring(br);
            s = s.substring(0, br);
        }
        // Drop a package qualifier: leading segments that start with a lower-case letter.
        String[] parts = s.split("\\.");
        int first = 0;
        while (first < parts.length - 1 && Character.isLowerCase(parts[first].charAt(0))) {
            first++;
        }
        String name = String.join(".", java.util.Arrays.copyOfRange(parts, first, parts.length));
        if (first > 0 && s.startsWith(BASE_PACKAGE + ".")) {
            // A fully qualified name in the package keeps its subpackage prefix.
            String rel = s.substring(BASE_PACKAGE.length() + 1);
            name = types.containsKey(rel) ? rel : name;
        }
        return resolve(name, context) + dims;
    }

    private String resolve(String name, TypeInfo context) {
        String head = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String tail = name.contains(".") ? name.substring(name.indexOf('.')) : "";
        for (TypeInfo c = context; c != null; c = c.enclosing) {
            for (TypeInfo s = c; s != null; s = superclass(s)) {
                if (s.nested.contains(head)) {
                    return s.name + "." + head + tail;
                }
            }
        }
        return name;
    }

    /** The superclass of {@code t} if it is declared in the jniast sources, else null. */
    private TypeInfo superclass(TypeInfo t) {
        Tree ext = t.tree.getExtendsClause();
        if (ext == null) {
            return null;
        }
        String n = ext.toString();
        int lt = n.indexOf('<');
        if (lt >= 0) {
            n = n.substring(0, lt);
        }
        if (n.startsWith(BASE_PACKAGE + ".")) {
            n = n.substring(BASE_PACKAGE.length() + 1);
        }
        String pkgRel = t.pkg.equals(BASE_PACKAGE) ? "" : t.pkg.substring(BASE_PACKAGE.length() + 1) + ".";
        TypeInfo s = types.get(pkgRel + n);
        return s != null ? s : types.get(n);
    }
}
