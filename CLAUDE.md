# astjava: developer guide for Claude

A pure-Java port of the Starlink AST library, replacing the JNI wrapper jniast with a library whose public API matches it (package `uk.ac.starlink.ast`).

The design is fixed: read [`docs/superpowers/specs/2026-11-02-pure-java-ast-design.md`](docs/superpowers/specs/2026-11-02-pure-java-ast-design.md) before non-trivial work.
The current work is [`docs/superpowers/plans/2026-11-04-pure-java-ast-phase1.md`](docs/superpowers/plans/2026-11-04-pure-java-ast-phase1.md).
When this file and the spec disagree, the spec wins.

## Reference sources

The originals are git submodules under `reference/` (run `git submodule update --init` if they are empty).
The spec and plan were written before the submodules existed and use other paths for them:

| Submodule | What it is | Paths used in the docs |
| --- | --- | --- |
| `reference/starlink-ast-rs` | Rust port, the main source to port from (`crates/starlink-ast/src`, tests in `crates/starlink-ast/tests`) | `../starlink-astrs` |
| `reference/starlink-ast` | C library, the final reference; test fixtures in `ast_tester/fixtures` | `~/work/starlink-ast`, `reference/starlink-ast` |
| `reference/starjava` | starjava; jniast, the API to match, is in `jniast/src/main/uk/ac/starlink/ast` | `../starjava` |

`reference/starlink-ast` must be at the commit the Rust port pins, which `git -C reference/starlink-ast-rs ls-tree HEAD reference/starlink-ast` prints.
Before porting a class, read the Rust repo's `docs/porting-hazards.md`, `docs/issues/c-library-quirks.md` and `c-bugs.md`.

## Environment and commands

The library must run on Java 8, so it is compiled with `--release 8` (Java 8 language and API); downstream users take the JAR, so it is built with a newer JDK.
Build with JDK 21: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
The build needs JDK 21 to 26, and stops with a message on anything else; the formatter fails on JDK 27.

- `mvn verify`: compile with Error Prone, run Checkstyle and PMD, run the tests, check formatting.
- `mvn test` or `mvn test -Dtest=ZoomMapTest`: tests only (static analysis still runs at compile time).
- `mvn spotless:apply`: format all Java sources.
- `mvn process-test-classes`: compile and lint without running tests (what the lint hook runs).
- `mvn test -Djvm=/path/to/jdk8/bin/java`: build as usual but run the tests on another Java runtime, such as Java 8 or the newest JDK; elementary functions (`Math.sin` and friends) can differ in the last bit between runtimes.

Compiler warnings fail the build, and `-q` hides their text; run without `-q` to see them.

## Git hooks

Hooks are run by prek from `.pre-commit-config.yaml`; install them with `prek install` and check everything with `prek run --all-files`.
They fix whitespace, final newlines and line endings, check YAML, XML, JSON and TOML, run codespell (typos everywhere, British spellings in Markdown, YAML and XML), check that Markdown has one sentence per line, format Java and lint Java.
When the formatter rewrites a file the commit fails; stage the result and commit again.

## Static analysis

| Tool | Configured in | Checks |
| --- | --- | --- |
| javac `-Xlint:all -Xdoclint -Werror` | `pom.xml` | Compiler warnings and malformed Javadoc fail the build |
| Error Prone | `pom.xml` (`-Xplugin:ErrorProne ...`) | Bug patterns; every switch has a default |
| Checkstyle | `config/checkstyle.xml` | Naming (allowing jniast names such as `AST__BAD`, `tran1`), structure, public Javadoc |
| PMD | `config/pmd-ruleset.xml` | Bug patterns, unused code, no locks or mutable static state |
| Spotless (palantir-java-format) | `pom.xml` | Layout: 4-space indent, 120 columns |

Fix the code rather than silence a check.
When a finding is wrong for a specific line, suppress it there and say why beside it: `@SuppressWarnings("PMD.RuleName")`, `@SuppressWarnings("ErrorProneCheckName")`, or `// CHECKSTYLE.OFF: CheckName` with `// CHECKSTYLE.ON: CheckName`.
Do not relax the shared configuration without asking.

## Rules that are easy to miss

The spec and plan have the full list; these are the ones that come up on every task.

- Java 8 language and APIs: no `var`, switch expressions, text blocks, records, `List.of` or `Math.fma`.
- No runtime dependencies beyond the JDK; the enforcer rule in `pom.xml` fails the build if one is added.
- Every `switch` has a `default`, and every `instanceof` or `if`/`else` chain standing in for a Rust `match` ends with a final `else`; that branch throws `AstException` with status `AST__INTER` naming the unexpected value.
- Code a later Java would simplify carries `// MODERN-JAVA(<version>): <what would replace it>`.
- No heuristics or shortcuts the original lacks; when results differ, find out why in the Rust or C source.
  A heuristic is allowed only where the original has one, with a comment saying so.
- Expected test values come from C (oracle files and the converted probe tables), never from running the Rust port.
- Share where C calls `astClone`, copy where C calls `astCopy`; Rust's copies follow from its ownership rules, not from AST's behaviour.
- Comments and Javadoc describe the code as it is, never plans or history.
- Markdown uses one sentence per line.
- Use British spelling (behaviour, initialise, serialisation), as AST and starjava do, in prose, comments and Javadoc.
  Names from the JDK keep their own spelling (`Color`, `finalize`, `Serializable`), and so do jniast's public names.

## Git

- Work on a branch, never on `main`; never push.
- Fixes to an earlier commit on the branch use `git commit --fixup <sha>`.
