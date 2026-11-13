package uk.ac.starlink.ast.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Access to the C library's language-neutral test fixtures, from the {@code reference/starlink-ast} submodule. */
public final class Fixtures {

    private Fixtures() {}

    /**
     * The fixture directory, {@code reference/starlink-ast/ast_tester/fixtures} under the project
     * root.
     *
     * @throws IllegalStateException if the submodule has not been checked out
     */
    public static Path root() {
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        Path root = Paths.get(base, "reference", "starlink-ast", "ast_tester", "fixtures");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "Test fixtures not found at " + root + "; run `git submodule update --init` in the project root");
        }
        return root;
    }

    /** The text of the fixture at {@code rel}, a path relative to {@link #root}. */
    public static String read(String rel) {
        try {
            return new String(Files.readAllBytes(root().resolve(rel)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
