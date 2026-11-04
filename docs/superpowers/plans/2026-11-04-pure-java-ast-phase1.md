# Pure-Java AST Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pure-Java `uk.ac.starlink.ast` library, API-compatible with jniast for the first-phase classes, that reads native AST dumps and FITS-WCS headers (including SIP) into FrameSets and transforms pixel to sky and back with results that match the C library.

**Architecture:** Port class by class from the Rust port (`../starlink-astrs/crates/starlink-ast/src`), taking behavior from C (`reference/starlink-ast/src`) wherever the Rust structure is shaped by Rust itself.
Public classes mirror jniast's hierarchy in one package with package-private helpers; the numeric libraries (ERFA, PAL, wcslib, cminpack) are ported into internal packages.
Tests are JUnit translations of the Rust tests plus gates over the C-generated oracle files; every expected value comes from C.

**Tech Stack:** Java 8 (`--release 8`), Maven 3.10, JUnit 5 (Jupiter), Jackson 2 (test scope only), Checkstyle (build only), JMH (benchmarks only).

**Spec:** `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md`

## Global Constraints

- Pure Java with no runtime dependencies beyond the JDK.
- Java 8 language level and APIs (`--release 8`); the library runs on Java 8 and every later runtime.
- Licensed LGPL-3.0-or-later; jniast's hand-written Java is GPL and is not copied; its public signatures are matched, and implementations and documentation are written from the C (LGPL) and Rust (LGPL) sources.
- Package `uk.ac.starlink.ast`; class names and public method signatures match jniast (`../starjava/jniast/src/main/uk/ac/starlink/ast`).
- Behavior follows the C library at the commit the Rust port pins, `3d3f255f`, not the AST 5.1 jniast was generated from.
- Built with Maven; tests use JUnit 5; `mvn test` runs every test without needing the C library or the Rust toolchain.
- Every `switch` has a `default`, and every `instanceof` or `if`/`else` chain that stands in for a `match` ends with a final `else`; that branch throws an `AstException` with status `AST__INTER` naming the unexpected value.
- Code that a later Java would simplify carries a comment `// MODERN-JAVA(21): <what would replace it>`.
- Do not add heuristics or shortcuts the original lacks; a heuristic is allowed only where the original uses one, with a comment saying so.
- Expected values come from C only, never from running the Rust port.
- Any tolerance wider than the default is listed with its measured size and cause, and fails if the comparison becomes exact.
- Share where C calls `astClone`, copy where C calls `astCopy`.
- No locks; Rust `thread_local!` caches become `ThreadLocal`; per-object caches are ordinary fields; one object is never used by two threads at once.
- Errors are thrown `AstException`s carrying AST status codes; there is no status variable.
- jniast methods of in-scope classes that need out-of-scope work throw `UnsupportedOperationException`; out-of-scope jniast classes are absent.
- Comments and Javadoc describe the code as it is, never plans or history; Markdown uses one sentence per line and American English.

## Review Focus

1. BAD (`AST__BAD`) and NaN coordinates passed to `tran1`/`tran2`/`tranN`/`tranP` come back as `AST__BAD` in the affected outputs, without an exception, through every Mapping class and through the FrameSet read from `lsst.ast` (test in Task 20).
2. Points outside a projection's domain, and pixels so far off a SIP image that PolyMap's iterative inverse cannot converge, give `AST__BAD` (or exactly what C gives) rather than an exception or a wrong finite value (tests in Tasks 11 and 12).
3. Malformed input (a native dump truncated mid-object, a header with a non-numeric CRVAL, an unknown class name in a dump) raises `AstException` with an AST status code, never `NullPointerException`, `ArrayIndexOutOfBoundsException` or `NumberFormatException` (tests in Tasks 5 and 23).
4. Attribute strings are matched as C matches them: names in any case, with surrounding white space, several settings separated by commas, and axis-indexed names such as `label(1)` (tests in Tasks 4 and 15).
5. Several threads, each transforming through its own `copy()` of one FrameSet, get identical results to a single thread; `ThreadLocal` caches never leak between threads (test in Task 20).

---

## Shared Design

Decisions every task relies on.
The spec leaves these to the builder; this plan fixes them.

### Layout

- `src/main/java/uk/ac/starlink/ast/`: every public jniast class plus package-private helpers (for example `NativeNode`, `FrameFinder`, `FitsCardList`).
  Helpers stay package-private so they are not API; internal methods on public classes are package-private too.
- `src/main/java/uk/ac/starlink/ast/internal/{erfa,pal,wcslib,cminpack}/`: ports of the numeric libraries, with `package-info.java` stating they are not part of the API.
  They depend only on the JDK and `AstException`.
- Units handling depends on Mappings, so it lives in the main package (`Units*.java`, package-private).
- Java file names follow the Rust module they port (`card_list.rs` becomes `FitsCardList.java`), so the origin of each file is obvious.
- `src/test/java/uk/ac/starlink/ast/`: tests, one Java test class per Rust test file, named after it (`test_zoommap.rs` becomes `ZoomMapTest.java`; each `#[test]` becomes an `@Test` method with the same name in camelCase).
- `src/test/java/uk/ac/starlink/ast/testsupport/`: shared test helpers (Task 1).
- `src/test/resources/oracle/`: C probe data, converted to JSON from the Rust repo's committed `cases.rs` files (Task 2).
- `src/test/resources/deferred/`: the deferred lists (below).
- `tools/`: `ExtractApi.java`, `GenErrorCodes.java` and `CasesToJson.java` (single-file Java programs, run with a JDK 11 or later).
- `bench/`: JMH benchmarks, a separate Maven project depending on the library (Task 24).

### Numbers

- `AstObject.AST__BAD = -Double.MAX_VALUE`; a package-private `static boolean isBad(double v)` in `Mapping` is true for `AST__BAD` and any non-finite value (Rust `mappings/helpers/mod.rs:20`).
- Elementary functions use `java.lang.Math`.
  Java's `Math` is not C's libm, so tests compare with C using `CValues.withinLibmRounding` (relative 1e-13, absolute 1e-14), the rule the Rust tests apply off their capture platform, rather than exactly.
- Rust's structural speed-ups are ported with the code they belong to: the NaN protocol (`transform_into_nan`, `transform_nan_protocol`, `mappings/mod.rs:538,1271`), wcslib's whole-array projection loops (`fwd_slice`, `rev_slice`, `slice_apply`, `wcslib/prj.rs:893-1044`), PolyMap's bulk evaluation (`mappings/polymap_simd.rs`) and the other bulk kernels.
- Rust's hardware-SIMD code cannot be expressed in Java 8: the branchless kernels in `src/vmath/`, which rely on fused multiply-add (`Math.fma` arrived in Java 9), and the functions marked `#[target_feature(enable = "avx2,fma")]` (`pal/mod.rs:172,220,402,484`, `wcslib/prj.rs:1422`).
  Java ports the path Rust takes when those features are unavailable, which calls the scalar functions (`Math` in Java), and marks each such place `// MODERN-JAVA(<version>): <Vector API or Math.fma replacement>`.
- C printf formatting (`%g`, `%.*g`, `%e`) used by attribute values, axis formatting and FITS cards is one package-private class, `CFormat`, ported from Rust `frames/axis_format.rs` (`sprintf_double`) and `fitschan/format.rs`.

### Objects

- `AstObject implements Cloneable`; `public AstObject copy()` takes `super.clone()` and calls the package-private hook `void deepCopy()`.
  Each class overrides `deepCopy()`, calls `super.deepCopy()` first, and replaces its mutable fields as its C `Copy` function does (deep where C calls `astCopy`, shared where C calls `astClone`).
  ID is cleared on the copy, as in C.
- `annul()` and `delete()` are deprecated no-ops; `getRefCount()` returns 1; `isThreaded()` returns `true`; `tune(name, value)` follows C's `astTune` for the tuning parameters in scope.
- jniast's `getAstConstantI` and `getAstConstantD` are replaced by ordinary `public static final` constants with C's values from `ast.h`.

### Errors

- `AstException extends RuntimeException`, constructors `AstException(String message)` and `AstException(String message, int status)`, methods `int getStatus()` and `String getStatusName()`, and one `public static final int AST__<NAME>` per entry of `reference/starlink-ast/ast_err.msg`.
- The constants are generated by `tools/GenErrorCodes.java` with the MESSGEN formula `((fac|0x800)<<16)+((num|0x1000)<<3)+sev` (Rust `build.rs:207`) and committed; a test recomputes them from `ast_err.msg`.
- Internal helper: `static AstException err(int status, String message)` in a package-private `Ast` class, so call sites read `throw Ast.err(AstException.AST__NCPIN, "...")`.

### Attributes

Rust has no string attribute layer, so it is ported from C.

- Public jniast methods on `AstObject`: `set(String settings)`, `getC/getD/getF/getI/getL/getB(String)`, `setC/setD/setF/setI/setL/setB(String, value)`, `test(String)`, `clear(String)`.
- They normalize names as C's `object.c` does (`astSet`, `astGetC`, `astTest`, `astClear`), then call four package-private hooks that mirror C's per-class virtual functions:
  `String getAttrib(String attrib)`, `void setAttrib(String attrib, String value)`, `boolean testAttrib(String attrib)`, `void clearAttrib(String attrib)`.
- Each class handles its own attribute names and passes the rest to `super`, as its C `GetAttrib`/`SetAttrib`/`TestAttrib`/`ClearAttrib` functions do; `AstObject`'s versions throw `AST__BADAT`.
  A read-only attribute raises `AST__NOWRT` on set or clear.
- Typed getters convert the string from `getAttrib`, and typed setters format the value and call `setAttrib`, as C's `astGetD`/`astSetD` do; numeric formatting uses `CFormat` with C's precision.
- jniast's named accessors (for example `getEpoch()`/`setEpoch(double)`) call the class's typed fields directly.
- The C source of each class's attribute functions is the reference for names, axis-index handling, defaults and errors; the typed fields and their semantics come from Rust.

### Transforms

- Coordinates are `double[ncoord][npoint]`, the layout of C's PointSet and of jniast's `tranP`.
- `Mapping` declares the per-class transform, mirroring Rust `transform_into`:
  `abstract void transformInto(double[][] in, int npoint, boolean forward, double[][] out)`.
  `forward` is the caller's direction; each class applies `boolean effectiveForward(boolean forward)` (true when `forward != getInvert()`), as in Rust (`zoom.rs:197`).
- The NaN protocol mirrors Rust: `void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out)` defaults to `transformInto` followed by turning BAD into NaN, and classes with a fast path override it; `final double[][] transformNanProtocol(double[][] in, int npoint, boolean forward)` converts BAD to NaN once at input, runs the chain, and converts back once at output (`mappings/mod.rs:538,1271-1338`).
- `Mapping` also provides `final double[][] transform(double[][] in, int npoint, boolean forward)`, which validates with a port of Rust `validate_transform_into` and allocates the output; the public `tran1`/`tran2`/`tranN`/`tranP` all go through it.
- `Frame` is a `Mapping` because jniast's hierarchy (`Frame extends Mapping`, `FrameSet extends Frame`) requires it, and FrameSet must be a Mapping from base to current.
  A plain Frame used as a Mapping copies its input to its output (`nin == nout == naxes`), as in C's `frame.c`, so calls that compile and worked in jniast still work.
  Nothing else of C's handling of Frames as Mappings is ported (for example, simplifying a Frame inside a CmpMap); a dump with a Frame in a Mapping slot loads as Rust loads it (`tests/test_frame_in_map_slot.rs`).
- `public Mapping simplify()` returns `this` in this phase, as C's `astSimplify` returns a clone of an object it cannot simplify; internal callers go through it so the real simplifier can replace it.

### Native serialization

- `NativeNode` (package-private) is one object's dump, mirroring Rust `serial/node.rs`: class name, then ordered items (key, value of kind INT, DOUBLE, STRING, OBJECT or UNSET, comment, the "helpful" flag, and `IsA` class markers).
  It has lookup readers (`int readInt(String key, int dflt)`, `double readDouble(String key, double dflt)`, `String readString(String key, String dflt)`, `NativeNode readObject(String key)`) and writers (`writeInt`, `writeDouble`, `writeString`, `writeObject`, `writeIsA`) with Rust's `(key, set, helpful, value, comment)` arguments.
- `NativeReader.parse(String text)` ports Rust `serial/native_read.rs`; `NativeWriter.render(NativeNode node, ChannelOptions options)` ports `serial/native_write.rs`; streaming over a Channel's source and sink ports `serial/native_stream.rs`.
- Each class has a package-private loading constructor `X(NativeNode node)` that calls `super(node)` and then reads its own items, mirroring C's chained `astLoadX` functions, and overrides `void dump(NativeNode node)`, which calls `super.dump(node)`, writes the `IsA` marker and then its own items, mirroring C's chained `Dump` functions.
- `ObjectRegistry.load(NativeNode node)` maps a class name to its loading constructor with a `switch` on the name; an unknown class raises the AST status C raises for it.
  Each task that adds a class adds its case.

### Tests

- Every `#[test]` in a listed Rust test file is translated, or recorded in `src/test/resources/deferred/rust-tests.txt` as `<rust test file>::<test name> | <reason>`.
  Valid reasons: a named out-of-scope class or method; `needs simplification` for a test that compares the dump text or structure of something C simplifies; `hardware SIMD` for tests of the kernels described in Shared Design › Numbers; `Rust-only API` for tests of Rust types with no Java counterpart; `no C value` (below).
- Rust tests that use probe data (`tests/<x>_oracle/cases.rs`, generated from C by `tools/oracle_fixtures/<probe>.c` in the Rust repo and committed there) read it from `src/test/resources/oracle/<x>.json`, converted by `tools/CasesToJson.java` (Task 2).
  No test data is regenerated and no C library is built; a Java test never reads a Rust `cases.rs` directly.
- Where a Rust test's expected value was produced by the Rust port rather than by C (Rust `tests/test_verification_ledger.rs` records each test's provenance), the Java test checks the property the value demonstrates (for example, a fit's accuracy) instead, or is deferred with the reason `no C value`.
- Where a Java-only test needs C's behavior (Review Focus 2 and 3), the expected result is read from the C code that handles the case and cited in the test (`file.c:line`).
- Deferred lists for the oracle gates are `src/test/resources/deferred/{simplify,framesets,headers}.txt`, one line per fixture: `<path relative to ast_tester/fixtures> | <reason>`.
  The gate fails if a listed fixture passes, so each task that adds a class removes the fixtures it makes pass.

### Environment

- `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home` (JDK 27; `--release 8` works there with an "obsolete" warning, which the build suppresses with `-Xlint:-options`).
- Whole suite: `mvn -q test`.
  One class: `mvn -q test -Dtest=ZoomMapTest`.
- No C compiler or C library is needed.

---

## Tasks

### Task 1: Project skeleton, test helpers and the jniast API check

**Files:**
- Create: `pom.xml`, `LICENSE` (the LGPL-3.0 text, copied from `reference/starlink-ast/COPYING`), `README.md`, `.gitignore` (`target/`, `.DS_Store`), `.gitmodules`, `.github/workflows/ci.yml`, `config/checkstyle.xml`
- Create: `tools/ExtractApi.java`, `src/test/resources/jniast-api.txt`
- Create: `src/test/java/uk/ac/starlink/ast/testsupport/{Fixtures,CValues,OracleFile,OracleSection,Deferred}.java`
- Test: `src/test/java/uk/ac/starlink/ast/testsupport/{CValuesTest,OracleFileTest}.java`, `src/test/java/uk/ac/starlink/ast/JniastApiTest.java`

**Interfaces:**
- Produces:
  - `Fixtures.root()`: `Path` to `reference/starlink-ast/ast_tester/fixtures`, failing with a message naming `git submodule update --init` if absent; `Fixtures.read(String rel)`: `String`.
  - `CValues` (port of Rust `tests/common/mod.rs`): `astEqualClose(double, double)`, `ulpClose(double, double, long maxUlps)`, `withinLibmRounding(double, double)`, `assertCValues(double[] got, double[] want, String what)`, `nativeStringsEquivalent(String got, String want, long maxUlps)`, `nativeStringsAstEqual(String, String)`, `stripIsSimp(String)`, and `DriftTol(long ulps, double atol)`.
  - `OracleFile.parse(Path)`: `List<OracleSection>`; `OracleSection` has `String path`, `int nin`, `int nout`, `boolean forward`, `double[][] rows` (inputs then outputs, `BAD` read as `AST__BAD`), plus the header tolerances.
  - `Deferred.load(String name)`: `Map<String,String>` from `src/test/resources/deferred/<name>.txt`.

- [ ] **Step 1: Add the C repo as a submodule**

Run: `git submodule add https://github.com/Starlink/ast reference/starlink-ast && git -C reference/starlink-ast checkout 3d3f255f`
Expected: `reference/starlink-ast/ast_tester/fixtures/oracle/headers.oracle` exists.

- [ ] **Step 2: Write `pom.xml`**

Group `uk.ac.starlink`, artifact `ast`, version `0.1.0-SNAPSHOT`.
`maven.compiler.release=8` with `-Xlint:-options`; a profile activated by `<jdk>1.8</jdk>` replaces `release` with `source`/`target` `1.8` and skips Checkstyle.
Test dependencies only: `org.junit.jupiter:junit-jupiter` (latest 5.x) and `com.fasterxml.jackson.core:jackson-databind` (latest 2.x).
Checkstyle runs in `verify` with only `MissingSwitchDefault` enabled (`config/checkstyle.xml`).

- [ ] **Step 3: Write the failing helper tests**

`CValuesTest`: `astEqualClose(1.0, 1.0 + 1e-12)` true, `astEqualClose(1.0, 1.0001)` false, `ulpClose(1.0, Math.nextUp(1.0), 1)` true, `ulpClose(1e-300, -1e-300, 4)` false (sign differs), `withinLibmRounding(1.0, 1.0 + 5e-14)` true and `withinLibmRounding(1.0, 1.0 + 5e-13)` false; plus the five tests of Rust `tests/test_dump_comparator.rs`, translated.
`OracleFileTest`: `headers.oracle` has 277 sections, `framesets.oracle` 353 and `simplify_fixtures.oracle` 1631; the first section of `headers.oracle` is `programs/joye_car_headers/CAR_model.head`, `nin=2`, `nout=2`, forward, and its first row is `{1, 1, 1.5046173383249051, -1.0209198256704075}`.

- [ ] **Step 4: Run them to make sure they fail**

Run: `mvn -q test`
Expected: compilation failure (helpers not defined).

- [ ] **Step 5: Implement the helpers**

Port `ast_equal_close`, `ulp_close`, `within_libm_rounding` and the dump comparators from Rust `tests/common/mod.rs` with the same constants (`max(1e5*(|a|+|b|)*eps, 1e-12)` for `astEqualClose`).

- [ ] **Step 6: Write `tools/ExtractApi.java` and generate `jniast-api.txt`**

The program parses jniast's sources with the JDK's `com.sun.source` parser (parse only, no type resolution) and prints one line per public or protected member of each top-level and nested class, sorted: `<Class>|<kind>|<modifiers>|<returnType>|<name>(<paramType>,...)`, with types as simple names (`Iterator`, `double[]`, `Mapping.Interpolator`) and `<init>` for constructors.
Run: `java tools/ExtractApi.java ../starjava/jniast/src/main > src/test/resources/jniast-api.txt`
Expected: lines for all 68 classes in `uk.ac.starlink.ast`, including `Mapping|method|public|double[]|tran1(int,double[],boolean)`.

- [ ] **Step 7: Write `JniastApiTest`**

For every class named in `jniast-api.txt` that exists in `uk.ac.starlink.ast` (looked up without initializing it), every listed member must exist with the same name, parameter simple names, return simple name and public or protected access; the failure message lists every missing member.
A second test asserts the set of jniast classes present equals `IN_SCOPE`, a constant list each task extends (empty now).

- [ ] **Step 8: Write CI and the README**

`ci.yml` runs `mvn -B verify` with submodules on Temurin 8 and Temurin 25.
`README.md` says what the project is, how to build and test, and that the fixtures come from the submodule.

- [ ] **Step 9: Run everything**

Run: `mvn -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add -A && git commit -m "Add Maven project, test helpers and jniast API check"
```

### Task 2: Probe data converter

**Files:**
- Create: `src/test/java/uk/ac/starlink/ast/testsupport/{RustLiteral,CasesToJson,ProbeData}.java`
- Create: `src/test/resources/oracle/README.md`, `src/test/resources/oracle/object_id.json`
- Test: `src/test/java/uk/ac/starlink/ast/testsupport/{RustLiteralTest,ProbeDataTest}.java`

**Interfaces:**
- Produces:
  - `RustLiteral.parse(String source)`: `Map<String,Object>` from each `pub const NAME: Type = <literal>;` of a generated `cases.rs`, with values `Double`, `Long`, `String`, `Boolean`, `List<Object>` (tuples, arrays and `&[...]` slices), `null` (`None`) and the unwrapped value of `Some(x)`; `pub type` items and comments are skipped; anything else throws `IllegalArgumentException` naming the line.
  - `CasesToJson.main(String[] args)`: arguments are the Rust repo path and one or more oracle names `<x>`; reads `<repo>/crates/starlink-ast/tests/<x>_oracle/cases.rs` and writes `src/test/resources/oracle/<x>.json` as `{"source": ..., "generator": <from the "Generated by" header>, "rustCommit": <git rev-parse HEAD of the Rust repo>, "consts": {...}}`, with doubles in Java's shortest round-trip form and NaN, infinities and C's BAD value written as the strings `"NaN"`, `"Infinity"`, `"-Infinity"` and `"BAD"`.
  - `ProbeData.load(String name)`: the Jackson `JsonNode` of `consts` in `oracle/<name>.json`; `ProbeData.toDouble(JsonNode)` reverses the string encodings above (`"BAD"` becomes `AST__BAD`).

- [ ] **Step 1: Write `RustLiteralTest`**

Cases: `pub const NPTS: usize = 10;` gives `10L`; `pub const P: [(f64, f64); 2] = [(0.5, -1e-3), (f64::NAN, 2.0)];` gives `[[0.5, -0.001], [NaN, 2.0]]`; `pub const S: &[&str] = &["a", "b\"c"];` gives `["a", "b\"c"]`; `Some(1.5)`, `None` and `true` give `1.5`, `null` and `true`; a line `pub const X: u8 = foo!();` throws with its line number; a `pub type Case = (...);` line and `//` comments are skipped.

- [ ] **Step 2: Run it to make sure it fails**

Run: `mvn -q test -Dtest=RustLiteralTest`
Expected: compilation failure.

- [ ] **Step 3: Implement `RustLiteral`, `CasesToJson` and `ProbeData`**

`RustLiteral` is a small tokenizer and recursive-descent parser for exactly the subset above, and `CasesToJson` writes JSON itself (no Jackson), so it runs from `target/test-classes` alone; when a generated file uses another construct, extend the parser for that construct and add a test case for it.
Rust's own spellings of special values (`f64::NAN`, `f64::INFINITY`, `f64::MAX`, any BAD constant a file uses) map to the values they denote.

- [ ] **Step 4: Convert `object_id` and test it**

Run: `mvn -q test-compile && java -cp target/test-classes uk.ac.starlink.ast.testsupport.CasesToJson ../starlink-astrs object_id`
Expected: `src/test/resources/oracle/object_id.json` exists.
`ProbeDataTest` checks that `load("object_id")` has every constant name in the Rust file, and one value equal to the literal in the Rust file.
Run: `mvn -q test -Dtest='RustLiteralTest,ProbeDataTest'`
Expected: PASS.

- [ ] **Step 5: Write `src/test/resources/oracle/README.md`**

Where the files come from (C, through the Rust repo's committed `cases.rs`), the conversion command above, and that a file is refreshed by converting again after the Rust repo regenerates its `cases.rs`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add converter for C probe data"
```

### Conventions for the porting tasks (3 to 23)

Each porting task has the same five steps; the task body gives what is specific.

1. **Translate the tests** listed under **Tests**, following Shared Design › Tests, and convert the probe data listed under **Probe data** with Task 2's converter (each entry names a Rust `tests/<x>_oracle` directory or the generator that writes it).
   A Rust test file is translated in the task that ports the last in-scope class it uses; when a listed file turns out to need a later class, translate the tests that can run now and leave the rest to the later task, naming it in a comment at the top of the Java test class.
2. **Run them to make sure they fail:** `mvn -q test -Dtest='<the new test classes>'`; expected: compilation failure or test failures, not success.
3. **Port the code** listed under **Ports** into the files under **Files**, following the Global Constraints and Shared Design and the task's **Decisions**.
   Add each new class to `ObjectRegistry` (from Task 5 on) and to `JniastApiTest.IN_SCOPE`.
   Javadoc for each public member is written from the prologue comment of the corresponding C function (the source jniast's generated documentation came from) and the Rust documentation; jniast's Javadoc text is not copied.
4. **Run the task's tests, then the whole suite:** `mvn -q test -Dtest='<the new test classes>'`, then `mvn -q verify`; expected: BUILD SUCCESS, including `JniastApiTest` for the new classes and the oracle gates from Task 9 on, after removing from the deferred lists the fixtures this task makes pass.
5. **Commit** with a message naming the classes ported: `git add -A && git commit -m "Port <classes>"`.

### Task 3: AstException, status codes and C number formatting

**Files:**
- Create: `tools/GenErrorCodes.java`, `src/main/java/uk/ac/starlink/ast/{AstException,Ast,CFormat}.java`
- Test: `src/test/java/uk/ac/starlink/ast/{ErrorCodesTest,ErrorModelTest,CFormatTest}.java`

**Ports:** Rust `build.rs:141-320` (the code table), `error.rs`; Rust `frames/axis_format.rs` (`sprintf_double` and the C printf emulation it uses) and `fitschan/format.rs`, and their unit tests.

**Tests:** `tests/error_codes.rs`, `tests/error_model.rs`; the `#[cfg(test)]` tests of the ported formatting functions; plus `ErrorCodesTest.everyCodeMatchesAstErrMsg`, which parses `reference/starlink-ast/ast_err.msg`, recomputes each code with the MESSGEN formula, and asserts each equals the `AstException.AST__<NAME>` field found by reflection, and that no extra `AST__` fields exist.

**Interfaces:**
- Produces: `AstException` as in Shared Design › Errors (also `SAI__OK` and `SAI__ERROR`, which jniast declares, with C's values); `Ast.err(int status, String message)`: `AstException`; `CFormat.format(String cFormat, double value)`: `String` and `CFormat.format(String cFormat, int value)`: `String`, giving C's `printf` output for the conversions AST uses.

**Decisions:**
- `getStatusName()` returns `"AST__" + NAME`; `getMessage()` is the message given, as jniast's is.
- `GenErrorCodes` writes the constant block between two marker comments in `AstException.java`, so regenerating rewrites only that block.

### Task 4: AstObject and the attribute layer

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{AstObject,ObjectId}.java`
- Test: `src/test/java/uk/ac/starlink/ast/{IdentTest,ObjectIdOracleTest,AttributeSyntaxTest}.java`

**Ports:** C `object.c` (`astSet`, `astGetC` and the typed getters and setters, `astTest`, `astClear`, `GetAttrib`/`SetAttrib`/`TestAttrib`/`ClearAttrib` for Class, ID, Ident, Nobject, ObjSize, RefCount, UseDefs; `Copy`); Rust `object_id.rs`.

**Tests:** `tests/test_ident.rs`, `tests/test_object_id_oracle.rs`.
`AttributeSyntaxTest` (Review Focus 4), on a minimal test subclass of `AstObject`: `set(" ident = abc , id=x ")` makes `getC("IDENT")` return `"abc"` and `getC(" Id ")` return `"x"`; `test("ident")` is true and false after `clear("Ident")`; `set("NoSuchAttr=1")` throws `AstException` with `getStatus() == AstException.AST__BADAT`; `setC("Class", "x")` throws with `AST__NOWRT`.

**Probe data:** `object_id` (converted in Task 2).

**Interfaces:**
- Consumes: `AstException`, `Ast.err`, `CFormat` (Task 3).
- Produces: the attribute hooks, `copy()`/`deepCopy()`, `AST__BAD` and the other constants, as in Shared Design; `String getID()`, `setID(String)`, `getIdent()`, `setIdent(String)`.

**Decisions:**
- `AstObject`'s constructor is protected, as jniast's is; `Nobject` and `ObjSize` follow C's definitions where they can be computed in Java and otherwise return what C returns for a fresh object, documented in the Javadoc.

### Task 5: Channel, native serialization and KeyMap

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{Channel,KeyMap,NativeNode,NativeReader,NativeWriter,NativeStream,ChannelOptions,ObjectRegistry}.java`, plus package-private KeyMap helpers named after `keymap/*.rs`
- Modify: `AstObject.java` (`equals`, `hashCode`, `toString`, `show`)
- Test: one class per test file below, plus `MalformedNativeTest`

**Ports:** Rust `serial/{node,native_read,native_write,native_stream}.rs` and the object-base parts of `serial/mod.rs` (`write_object_base`), `channel/{mod,base,native,source_sink}.rs` (not `mocchan.rs`), `keymap/*.rs`; C `channel.c` and `keymap.c` attribute functions.

**Tests:** `tests/test_keymap.rs`, `tests/test_keymap_properties.rs`, `tests/test_keymap_serial.rs` (reads `fixtures/oracle/keymap_mapsz.txt`), `tests/test_channel.rs`; the unit tests in `serial/native_read.rs`, `serial/native_write.rs` and `keymap/`.
`MalformedNativeTest` (Review Focus 3): a KeyMap dump cut off after its first item, and a dump whose class is `NoSuchClass`, each make `Channel.read()` throw `AstException` with the status C raises for that input (read from `channel.c` and cited in the test).

**Interfaces:**
- Consumes: Task 4.
- Produces: `NativeNode`, `NativeReader.parse`, `NativeWriter.render`, `ObjectRegistry.load` (Shared Design › Native serialization); jniast's `Channel` API, including `protected String source()`, `protected void sink(String)`, `read()`, `write(AstObject)` and `warnings()` returning `KeyMap`.

**Decisions:**
- `equals` and `hashCode` implement jniast's documented contract (two objects are equal when their Channel dumps are identical); `toString()` and `show()` write through a Channel as jniast's do.

### Task 6: Mapping base, UnitMap, ShiftMap, ZoomMap

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{Mapping,UnitMap,ShiftMap,ZoomMap,MappingReport,ResampleFlags,Ukern1Calculator,UinterpCalculator}.java`
- Test: one class per test file below

**Ports:** Rust `mappings/mod.rs` (counts, Invert, TranForward/TranInverse, `validate_transform_into`, `tran1`/`tran2`, the NaN protocol, `MappingFlags`), `mappings/report.rs`, the Mapping parts of `serial/mod.rs` (`write_mapping_base`, `read_mapping_base`, `apply_mapping_object_items`), `mappings/{unit,shift,zoom}.rs`; C `mapping.c`, `unitmap.c`, `shiftmap.c`, `zoommap.c` attribute functions.

**Tests:** `tests/test_unitmap.rs`, `test_shiftmap.rs`, `test_zoommap.rs`, `test_tran1_tran2.rs`, `test_transform_validation.rs`, `test_nan_protocol.rs`, `test_nan_input_parity.rs`, `test_nan_outputs.rs`, `test_bulk_kernels.rs` (the kernels of later classes are translated in their tasks), `test_report.rs`.
Tests of Rust's `Points`/`PointSet` types (`tests/points.rs`, `tests/pointset.rs`) have no Java counterpart and are deferred with the reason `Rust-only API`.

**Probe data:** `report`, if `test_report.rs` uses one.

**Interfaces:**
- Consumes: Tasks 3 to 5.
- Produces: `Mapping.transformInto`, `transform`, `effectiveForward`, `isBad`, `simplify()` (Shared Design › Transforms); jniast's public `Mapping` API, with `resample*` and `rebin*` throwing `UnsupportedOperationException`; jniast's nested `Mapping.Interpolator` and `Mapping.Spreader` and the three resampling types, so signatures match.

**Decisions:**
- `tranN` and `tranP` use jniast's documented array layouts; the Javadoc of each states the layout.
- The `Report` attribute writes transformed points as C does; the destination is a `ThreadLocal` sink defaulting to `System.out` (Rust `report.rs:19`).

### Task 7: WinMap, MatrixMap, PermMap, CmpMap

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{WinMap,MatrixMap,PermMap,CmpMap}.java`, plus package-private helpers named after the `mappings/helpers/*.rs` files they port
- Test: one class per test file below

**Ports:** Rust `mappings/{winmap,matrix,perm,cmpmap}.rs` and the helpers they use; C `winmap.c`, `matrixmap.c`, `permmap.c`, `cmpmap.c` attribute and `Copy` functions.

**Tests:** `tests/test_winmap.rs`, `test_matrixmap.rs`, `test_permmap.rs`, `test_cmpmap.rs`, `test_constructor_checks.rs`, `test_mapping_load.rs`.
Port the package-private `equal` (Rust `Mapping::equal`) only if an in-scope caller uses it, and then translate `test_equal.rs` and `test_equal_oracle.rs`; otherwise defer them with the reason `out-of-scope method astEqual`.

**Probe data:** `mapping_load_probe`, and `equal_probe` if `equal` is ported.

**Interfaces:**
- Consumes: Task 6.
- Produces: jniast's constructors and methods for the four classes; `CmpMap` exposes package-private `Mapping getMap1()`, `Mapping getMap2()`, `boolean isSeries()`, `boolean getInvert1()`, `boolean getInvert2()` for later tasks.

**Decisions:**
- `CmpMap` keeps shared references to its components and records each one's Invert flag at construction (`cmpmap.c:4848`); `deepCopy` copies the components, as C's `Copy` does.

### Task 8: Generic Mapping algorithms

**Files:**
- Modify: `Mapping.java`, `CmpMap.java`
- Create: package-private `{MapBox,LinearApprox,TranGrid,MapSplit,Rate}.java` named after the Rust modules
- Test: one class per test file below

**Ports:** Rust `mappings/{map_box,linear_approx,tran_grid,rate}.rs`, `map_split` and `is_linear` in `mappings/mod.rs`, `CmpMap::decompose` (`cmpmap.rs:322`).

**Tests:** `tests/test_trangrid.rs`, `test_tran_grid_oracle.rs`, `test_map_split.rs`, `test_map_split_oracle.rs`, `test_tran_definedness.rs`, `test_tran_definedness_oracle.rs`, `test_is_linear.rs`, `test_rate.rs`, `test_numeric_rate.rs`, `test_rate_oracle.rs`; unit tests in `mappings/linear_approx.rs`.

**Probe data:** `tran_grid_probe`, `map_split_probe`, `rate_probe`, and the generator of `tran_definedness_oracle`.

**Interfaces:**
- Consumes: Tasks 6 and 7.
- Produces: jniast's `mapBox`, `rate`, `linearApprox`, `tranGrid`, `mapSplit` and `decompose`, and the package-private `boolean isLinear()`.

### Task 9: Oracle gates and serialization corpus

**Files:**
- Create: `src/test/java/uk/ac/starlink/ast/{TransformOracleTest,SerialisationCorpusTest,NativeRoundTripTest}.java`, `src/test/java/uk/ac/starlink/ast/testsupport/NativeClasses.java`
- Create: `src/test/resources/deferred/{simplify,framesets,headers,serialisation}.txt`

**Ports:** Rust `tests/test_transform_oracle.rs` (three gates, header tolerances, `fixtures/oracle/transform_oracle_overrides.txt`, the 2π wrap on angular axes, inverse-after-forward round trip, `.simp` against `.map` equivalence), `tests/test_serialisation.rs` (read each corpus fixture, write it, compare with `CValues.nativeStringsEquivalent` at 4 ULPs), `tests/test_native_roundtrip_all.rs`.

**Interfaces:**
- Consumes: `OracleFile`, `Deferred`, `CValues` (Task 1); `ObjectRegistry` (Task 5).
- Produces: `NativeClasses.missing(String dumpText)`: the `Begin <Class>` names with no `ObjectRegistry` case, sorted.

**Decisions:**
- `.head` sections load through `FitsChan` (from Task 23); before then they are all deferred with the reason `needs FitsChan`.
- Every section either passes or belongs to a deferred fixture; a deferred fixture must fail; the section total is asserted (2261).
- Running a gate with `-Dast.writeDeferred=true` rewrites its deferred file from the current results, but only with reasons of the form `needs <classes>` from `NativeClasses.missing`; a failure with any other cause is written as `UNEXPLAINED`, which the gate rejects until a person replaces it with the measured cause.
- `NativeRoundTripTest` covers every class ported so far, each with non-default attributes, and grows with each later task.

- [ ] **Step 1: Write the three tests and generate the deferred files**

Run: `mvn -q test -Dtest='TransformOracleTest,SerialisationCorpusTest' -Dast.writeDeferred=true`
Expected: deferred files written, with no `UNEXPLAINED` line; the simplify fixtures that use only Tasks 6 to 8's classes are not listed.

- [ ] **Step 2: Run the gates normally**

Run: `mvn -q test -Dtest='TransformOracleTest,SerialisationCorpusTest,NativeRoundTripTest'`
Expected: PASS.
Any in-scope fixture that fails is investigated in the original and fixed, not deferred.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "Add transform-oracle and serialization gates"
```

### Task 10: ERFA and PAL

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/internal/erfa/*.java`, `src/main/java/uk/ac/starlink/ast/internal/pal/*.java`, a `package-info.java` in each
- Create: `src/test/resources/golden/erfa_pal_roots.tsv` (copied from the Rust repo's `tests/erfa_pal_golden/roots.tsv`, which was captured from the C library), with its origin stated in `src/test/resources/golden/README.md`
- Test: `src/test/java/uk/ac/starlink/ast/internal/pal/{PalTest,PalGoldenTest}.java`, `src/test/java/uk/ac/starlink/ast/internal/erfa/ErfaTest.java`

**Ports:** every file in Rust `src/erfa/` and `src/pal/` (they hold only what AST uses).

**Tests:** the unit tests in `pal/mod.rs`, `pal/golden_tests.rs` and `erfa/`.

**Interfaces:**
- Produces: one Java class per Rust file, named in UpperCamelCase after it (`cal2jd.rs` becomes `Cal2jd`), holding its functions as `public static` methods with the ERFA or PAL name in lowerCamelCase.
  Where Rust returns a named struct, Java returns a final value class of that name; where Rust returns a bare tuple, Java returns a `double[]` whose Javadoc names each element.
  `Erfa`'s leap-second table is `Dat.LEAP_SECONDS`.

### Task 11: wcslib projections, WcsMap, SphMap

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/internal/wcslib/*.java` (one class per Rust file), `src/main/java/uk/ac/starlink/ast/{WcsMap,SphMap}.java`
- Create: `src/test/resources/golden/projections.tsv` (copied from the Rust repo's `tests/wcslib_golden/projections.tsv`; origin in the golden README)
- Test: one class per test file below, plus `ProjectionDomainTest`

**Ports:** Rust `src/wcslib/*.rs`, `mappings/{wcsmap,sphmap}.rs`; C `wcsmap.c` and `sphmap.c` attribute functions.
The whole-array projection loops (`fwd_slice`, `rev_slice`, `slice_apply`) are ported; the `vmath` kernels and the AVX2/FMA function at `wcslib/prj.rs:1422` follow Shared Design › Numbers (the scalar path, tagged `MODERN-JAVA`), so SphMap and the projections call `Math`.

**Tests:** `tests/test_wcsmap.rs`, `test_wcsmap_edges.rs`, `test_wcsmap_pv.rs`, `test_sphmap.rs`; unit tests in `wcslib/prj.rs`, `wcslib/golden_tests.rs` and `mappings/sphmap.rs`.
Tolerances in Rust tests that exist only because of `vmath` drift (`SPHMAP_DRIFT_LICENSED` and similar) are not carried over; Java compares with `CValues.withinLibmRounding`.
Tests of the `vmath` kernels themselves are deferred with the reason `hardware SIMD`; tests that slices give the same results as single points (`tan_slices_give_each_point_the_same_bits_at_any_length` and similar) are translated.
`ProjectionDomainTest` (Review Focus 2): for each projection type, a sphere-to-plane transform of a point outside the projection's domain (for TAN, native latitude -10 degrees) and a plane-to-sphere transform of a plane point outside the projected region (for SIN, a radius greater than the unit circle in its plane units) give `AST__BAD` in that point and finite values in a valid neighbor, with no exception; each expected BAD is confirmed in the C code (`wcsmap.c` and the wcslib projection it calls) and cited in the test.

**Probe data:** `wcsmap_pv` generator.

**Interfaces:**
- Consumes: Tasks 6 to 8.
- Produces: jniast's `WcsMap` (including the `AST__TAN` style projection constants and `getPVi_m`/`setPVi_m` style accessors jniast declares) and `SphMap`.

### Task 12: cminpack and PolyMap

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/internal/cminpack/{Lmder,Lmpar,Qrfac,Qrsolv,Enorm,Dpmpar}.java`, `src/main/java/uk/ac/starlink/ast/{PolyMap,PolyTran}.java`
- Test: one class per test file below, plus `PolyIterInverseDomainTest`

**Ports:** C `reference/starlink-ast/cminpack/{lmder,lmpar,qrfac,qrsolv,enorm,dpmpar}.c`; Rust `mappings/polymap.rs` (including the iterative inverse and the `lintrunc` cache) and `mappings/helpers/poly_tran.rs`; C `polymap.c` attribute functions and its calls into cminpack.
Rust's PolyTran fitting uses the `levenberg_marquardt` crate, which is not a port of C; Java ports C's cminpack and calls it as C's `polymap.c` does, so fitted coefficients follow C.
The Javadoc of `Lmder` (and the `package-info.java` of `internal.cminpack`) says that Apache Commons Math's `LevenbergMarquardtOptimizer` is a Java translation of the same MINPACK routine and could replace this port, at the cost of a runtime dependency.
PolyMap's bulk evaluation (`mappings/polymap_simd.rs`) is ported in scalar form, per Shared Design › Numbers.

**Tests:** `tests/test_polymap.rs`, `test_polytran.rs`, `test_poly_bad.rs`, `test_poly_iter_inverse_oracle.rs`.
`test_polymap_simd.rs` is translated where it checks that bulk evaluation matches point-by-point evaluation; tests of hardware-SIMD code are deferred with the reason `hardware SIMD`.
Expected values in `test_polytran.rs` that came from the Rust solver are replaced by checks of the fit's accuracy, per Shared Design › Tests.
`PolyIterInverseDomainTest` (Review Focus 2): a PolyMap with SIP-like forward coefficients and `IterInverse=1`, asked for the inverse of a point 1e6 pixels from its domain, returns what C's iterative inverse returns for a point that does not converge (`polymap.c`, around line 2700, cited in the test), without an exception.

**Probe data:** `poly_bad`, `poly_iter_inverse`.

**Interfaces:**
- Consumes: Tasks 6 to 8.
- Produces: jniast's `PolyMap` API, and `PolyMap polyTran(boolean forward, double acc, double maxacc, int maxorder, double[] lbnd, double[] ubnd)` (Rust `polymap.rs:606`; null where Rust returns `None`), which FitsChan's SIP reading calls (Task 23); it is package-private because jniast does not declare it.

### Task 13: SlaMap and TimeMap

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{SlaMap,TimeMap}.java`, `src/main/resources/uk/ac/starlink/ast/timemap_fairhd.txt` (the series from Rust `timemap_fairhd.inc`) if it is too large for a Java constant table
- Test: one class per test file below

**Ports:** Rust `mappings/{slamap,timemap}.rs` and `timemap_fairhd.inc`; C `slamap.c` and `timemap.c` attribute functions.
SlaMap's `MAPPA_CACHE` (`slamap.rs:1183`) becomes a `ThreadLocal`; its `extra` cache is an ordinary field.

**Tests:** `tests/test_slamap.rs`, `test_timemap.rs`, `test_time_map_oracle.rs`.

**Probe data:** `time_map_probe`.

**Interfaces:**
- Consumes: Tasks 6 to 10.
- Produces: jniast's `SlaMap` and `TimeMap` (`add(String cvt, double[] args)`).

### Task 14: Units

**Files:**
- Create: package-private `src/main/java/uk/ac/starlink/ast/Units*.java`, named after the files in Rust `src/units/`
- Test: `UnitsTest.java` (the unit tests), `UnitMapperTest.java`

**Ports:** every file in Rust `src/units/`.
A conversion that needs a MathMap throws `UnsupportedOperationException` naming MathMap, since MathMap is a later phase.

**Tests:** the unit tests in `src/units/`, `tests/test_unit_mapper.rs`.

**Probe data:** `unit_mapper_probe`.

**Interfaces:**
- Consumes: Tasks 6 and 7.
- Produces: package-private `static Mapping Units.unitMapper(String inUnit, String outUnit)` and `static Units.LabelledMapping Units.unitMapperLabel(String inUnit, String outUnit, String inLabel)` (fields `Mapping mapping`, `String label`), both returning null where Rust returns `None` (`units/mapper.rs:138,178`), and the unit normalization and parsing functions Frame needs, with the Rust names in lowerCamelCase.

### Task 15: Axis and Frame

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{Axis,Frame,AxisFormat,DateTime}.java`
- Test: one class per test file below, plus `FrameAttributeSyntaxTest`

**Ports:** Rust `frames/{axis,basic,frame_attrs,axis_format,date_time}.rs` and the provided methods of the `Frame` trait in `frames/mod.rs` (format, unformat, distance, angle, offset, offset2, norm, the `ax_*` methods, resolve, intersect, pickAxes, permAxes, active units); C `frame.c` and `axis.c` attribute functions, including the axis-indexed forms (`Label(1)`), and C `frame.c` for the Frame-as-Mapping identity transform.
Rust's attribute macros (`frame_attrs!` and the others in `frame_attrs.rs`) become ordinary fields with get, set, test and clear methods.

**Tests:** `tests/test_basicframe.rs`, `test_frame.rs`, `test_frame_geometry.rs`, `test_frame_format.rs`, `test_frame_equality.rs`, `test_frame_attrs.rs`, `test_frame_axis_attrs.rs`, `test_axis_format.rs`, `test_axis_unformat.rs`, `test_active_unit.rs`, `test_new_unit.rs`, `test_use_defs.rs`, `test_min_max_axes.rs`, `test_norm_points.rs`; unit tests in `frames/date_time.rs`.
`FrameAttributeSyntaxTest` (Review Focus 4): on a 2-axis Frame, `set("label(1)=X, Unit(2) = deg")` makes `getC("Label(1)")` return `"X"` and `getC("unit(2)")` return `"deg"`; `getC("Label(2)")` returns C's default `"Axis 2"`; `getC("Label(3)")` throws with `AST__AXIIN`.

**Probe data:** the generators of `frame_oracle` (`frame_attrs_probe`), `frame_axis_oracle` (`frame_axis_attrs_probe`), `axis_format`, `axis_unformat`, `new_unit`, `use_defs`, `min_max_axes`, `norm_points`.

**Interfaces:**
- Consumes: Tasks 3 to 8, 14.
- Produces: jniast's `Frame` and `Axis` APIs (`findFrame` and `convert` exist but are completed in Task 17); package-private `Axis getAxis(int axis)` (1-based, honoring Permute) and `void setAxis(int axis, Axis ax)` for subclasses.

### Task 16: FrameSet structure

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/FrameSet.java`
- Test: one class per test file below

**Ports:** Rust `frames/frameset.rs` (nodes and Mappings, add_frame, add_frameset, remove_frame, Base and Current, getFrame, getMapping, remapFrame, `with_integrity`, `tidy_nodes`, FrameSet as a Frame forwarding to its current Frame, FrameSet as a Mapping from base to current), `frameset_attr!` (`frameset.rs:1835`); C `frameset.c` attribute functions, including forwarding unknown attributes to the current Frame.

**Tests:** `tests/test_frameset.rs`, `test_frameset_repair.rs`, `test_frameset_current.rs`.

**Probe data:** `frameset_current` generator.

**Interfaces:**
- Consumes: Task 15.
- Produces: jniast's `FrameSet` API and constants (`AST__BASE`, `AST__CURRENT`, `AST__NOFRAME`).

**Decisions:**
- `addFrame` stores copies of the Frame and the Mapping (`frameset.c:1427`).

### Task 17: Frame matching and conversion

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{FrameFinder,FrameOverlay}.java`
- Modify: `Frame.java`, `FrameSet.java`
- Test: one class per test file below

**Ports:** Rust `frames/find_frame.rs` (`MatchResult`, `convert_route`, `find_frame`), `frames/overlay.rs`, and the matching methods of the `Frame` trait (`match_template`, `cast`, `sub_frame`, `primary_frame`, `match_axes`) and `FrameSearch` (`frames/mod.rs:2562-2801`).

**Tests:** `tests/test_find_frame.rs`, `test_convert.rs`, `test_match_axes.rs`, `test_overlay.rs`, `test_frame_in_map_slot.rs`.

**Probe data:** `match_axes`, `overlay` and `frame_in_map_slot` generators.

**Interfaces:**
- Consumes: Tasks 15 and 16.
- Produces: jniast's `Frame.findFrame(Frame template, String domainlist)` and `Frame.convert(Frame to, String domainlist)` returning `FrameSet`, for Frames and FrameSets; package-private overridable `match`, `subFrame` and `overlay` hooks that SkyFrame, TimeFrame and CmpFrame override, as C's virtual functions are overridden.

### Task 18: SkyAxis and SkyFrame

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{SkyAxis,SkyFrame,SkyConvert,SkyLast}.java`
- Test: one class per test file below

**Ports:** Rust `frames/{skyaxis,skyframe,sky_convert,sky_last}.rs`; C `skyaxis.c` and `skyframe.c` attribute functions.
`sky_last.rs`'s thread-local tables become a `ThreadLocal`; SkyFrame's `last`, `eplast`, `klast` and `diurab` caches are ordinary fields, reset by `deepCopy` as C's `Copy` resets them.

**Tests:** `tests/test_skyframe.rs`, `test_skyframe_attrs.rs`, `test_skyframe_format.rs`, `test_sky_convert.rs`, `test_sky_geometry.rs`, `test_sky_interleave.rs`, `test_sky_norm.rs`, `test_sky_plain_axis.rs`, `test_sky_pole_oracle.rs`, `test_sky_use_defs.rs`, `test_sky_last.rs`, `test_picked_skyaxis.rs`, `test_convert_frameset_oracle.rs`, `test_skyref_offset_mapping.rs`; unit tests in `frames/skyframe.rs`, `frames/skyaxis.rs` and `frames/sky_convert.rs`.

**Probe data:** the generators of `skyframe_oracle` (`skyframe_format_probe`), `skyframe_attrs`, `sky_convert`, `sky_geometry`, `sky_interleave`, `sky_norm`, `sky_plain_axis`, `sky_pole`, `sky_use_defs`, `sky_last` (with `frameset_last_probe`), `picked_skyaxis`, `convert_frameset`.

**Interfaces:**
- Consumes: Tasks 10, 13, 15 to 17.
- Produces: jniast's `SkyFrame` and `SkyAxis` APIs, including `skyOffsetMap` and the offset-coordinate attributes `SkyRef`, `SkyRefIs`, `SkyRefP` and `AlignOffset`.

### Task 19: TimeFrame

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/TimeFrame.java`
- Test: one class per test file below

**Ports:** Rust `frames/timeframe.rs`; C `timeframe.c` attribute functions.

**Tests:** `tests/test_timeframe.rs`, `test_time_convert.rs`, `test_time_epoch.rs`, `test_time_scale_set.rs`; unit tests in `frames/timeframe.rs`.

**Probe data:** `time_convert`, `time_epoch` and `time_scale_set` generators.

**Interfaces:**
- Consumes: Tasks 13, 15 to 17.
- Produces: jniast's `TimeFrame` API; the conversion between two MJD TimeFrames in different time scales that FitsChan's epoch handling uses (Task 23, Rust `fitschan/epoch.rs`).

### Task 20: CmpFrame and FrameSet integration

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/CmpFrame.java`
- Test: one class per test file below, plus `BadInputTest` and `ThreadCopyTest`

**Ports:** Rust `frames/cmpframe.rs` (its `domain_default` cache is an ordinary lazily set field); C `cmpframe.c` attribute functions, keeping the quirk that `Label(2)` on a CmpFrame goes to the primary Frame (Rust `PLAN.md` line 71).

**Tests:** `tests/test_cmpframe.rs`, `test_cmp_match_end.rs`, `test_frameset_variants.rs`, `test_frameset_variants_oracle.rs`, `test_frameset_variant_link_oracle.rs`, `test_frameset_integrity.rs`, `test_lsst_oracle.rs`, `test_show_tostring_oracle.rs`, `test_attr_test_clear_oracle.rs`, `test_clear_on_set.rs`, and the in-scope tests of `test_class_attrs.rs`, `test_system_change.rs`, `test_frame_load.rs`, `test_frame_api.rs`, `test_frame_class_attrs.rs`, `test_channel_dump_oracle.rs`, `test_channel_read_oracle.rs`, `test_section_load.rs`, `test_fixture_full_dump_oracle.rs`, `test_serialisation.rs`; unit tests in `frames/cmpframe.rs`.
In `test_lsst_oracle.rs`, the comparisons against C's simplified FrameSet's inverse are deferred with the reason `needs simplification`; the others are translated.
`BadInputTest` (Review Focus 1): through the FrameSet read from `wcsconv/inputs/lsst.ast`, and through one instance of every in-scope Mapping class, `tran2` of the three points `(AST__BAD, 1)`, `(NaN, 1)` and a valid point gives `AST__BAD` in both outputs of the first two and, for the third, exactly the result of transforming it alone, with no exception.
`ThreadCopyTest` (Review Focus 5): eight threads each `copy()` the `lsst.ast` FrameSet and an FK5-to-Galactic conversion FrameSet with a non-default Epoch (which uses SlaMap's and SkyFrame's caches), and transform the same 10,000 points 20 times; every result equals the single-threaded result exactly.

**Probe data:** the generators of `cmp_match_end`, `frameset_variants`, `frameset_variant_link`, `integrity`, `lsst`, `show_tostring`, `attr_test_clear`, `class_attrs`, `system_change`, `frame_load`, `frame_api`, `frame_class_oracle` (`frame_class_attrs_probe`), `channel_dump`, `channel_read`, `section_load`, `fixture_full_dump`.

**Interfaces:**
- Consumes: Tasks 15 to 19.
- Produces: jniast's `CmpFrame` API.

### Task 21: FitsChan cards

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{FitsChan,FitsCard,FitsCardList,FitsCardValue,FitsFormat,FitsMatch,FitsWarnings,FitsChanOptions}.java`
- Test: one class per test file below

**Ports:** Rust `fitschan/mod.rs` card API (about lines 577-1990) and attributes (about 1990-2930), `fitschan/{card_list,card,card_value,format,fits_match,warning,options,source_sink}.rs`; C `fitschan.c` attribute functions.
`fits_match.rs`'s and `options.rs`'s thread-local state become `ThreadLocal`s.

**Tests:** the card-handling tests of `tests/test_fitschan.rs`, `test_fitschan_cursor_oracle.rs` (the steps that call `write` are deferred with the reason `out-of-scope method FitsChan.write`), `test_fitschan_errors.rs`; unit tests in `fitschan/card.rs`, `fitschan/card_list.rs` and the card parts of `fitschan/mod.rs`.

**Probe data:** `fitschan_cursor_probe`, `fitschanerrors_table`.

**Interfaces:**
- Consumes: Tasks 3 to 5.
- Produces: jniast's `FitsChan` card API: `FitsChan()`, `FitsChan(Iterator cardIt)`, `iterator()`, `close()`, `putFits`, `putCards`, `findFits`, `testFits`, every `setFits` overload, `setFitsContinue`, `delFits`, `retainFits`, `purgeWCS`, and the attributes (`Card`, `Ncard`, `Encoding`, `Warnings` and the others jniast declares); `write(AstObject)` throws `UnsupportedOperationException`.
  Package-private `FitsCardList cards()` and `FitsWarnings warnings()` for Tasks 22 and 23.

**Decisions:**
- `FitsChan(Iterator cardIt)` and `iterator()` behave as jniast's Javadoc documents (cards read from the iterator as Strings; iteration over the current cards).

### Task 22: FitsChan encodings, read dispatch and NATIVE reading

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{FitsGuessEncoding,FitsNativeReader}.java`
- Modify: `FitsChan.java`
- Test: one class per test file below

**Ports:** Rust `fitschan/guess_encoding.rs`, `FitsChan::encoding` (`mod.rs:2051`), the `read()` dispatch (`mod.rs:3293-3622`), and the read side of `fitschan/enc_native.rs` (about lines 1-320: `classify`, `native_span`, `unskipped_begin`, `cards_to_native_text`).
`read()` with an explicit or guessed encoding other than FITS-WCS and NATIVE throws `UnsupportedOperationException` naming the encoding.

**Tests:** the NATIVE-reading tests of `tests/test_fitschan.rs`, `test_fitschan_native_keyword.rs`, `test_fixture_read_warnings_oracle.rs`; the read-side unit tests of `fitschan/enc_native.rs` and those of `fitschan/guess_encoding.rs`.

**Probe data:** `fixture_read_warnings_probe`.

**Interfaces:**
- Consumes: Tasks 5, 21 and every class `ObjectRegistry` knows.
- Produces: `FitsChan.read()` for NATIVE; the FITS-WCS branch calls `FitsWcsRead.read(FitsChan fc)` (Task 23), a stub here that throws `UnsupportedOperationException`.

### Task 23: FITS-WCS reading

**Files:**
- Create: `src/main/java/uk/ac/starlink/ast/{FitsSpecTrans,FitsWcsMark,FitsWcsKeywords,FitsStore,FitsWcsRead,FitsWcsMatrix,FitsWcsCelestial,FitsEpoch,FitsDates,FitsFiducial,FitsNativePole}.java`
- Modify: `FitsChan.java`, `src/test/resources/deferred/headers.txt`
- Test: one class per test file below, plus `SipHeadersTest` and `MalformedHeaderTest`

**Ports:**
- Rust `fitschan/spec_trans.rs`, except the PC, IRAF and CLASS translations (RADECSYS, LONGPOLE, `translate_cnxxx`, CDjjjiii, `translate_class`), which only those encodings reach.
- Rust `fitschan/{wcs_mark,wcs_keywords,fits_store,wcs_read,wcs_matrix,wcs_celestial,epoch}.rs`.
- From the write side, the three helpers the read path calls: `wcs_write/mod.rs` `date_str_to_mjd` (`:894`) with `gregorian_to_mjd` and `parse_legacy_date` (to `FitsDates`), `fset_to_store/fiducial.rs` `get_fiducial_nsc` (to `FitsFiducial`) and `fset_to_store/sky_pole.rs` `wcs_nat_pole` (to `FitsNativePole`).
- `wcs_spectral.rs`'s `build_spectral` is reached only for a spectral CTYPE (`fits_store::is_spectral_ctype`), where it throws `UnsupportedOperationException` naming SpecFrame; `-TAB` with `TabOK` above zero and non-spectral `-LOG` axes (which need MathMap) throw the same way.
- Warnings are collected as in Rust (`push_trans_warnings`, `push_sweep_warnings`, `push_read_warnings`), written as ASTWARN cards, and returned by `Channel.warnings()` as a KeyMap with `Warning_N` keys.

**Tests:** `tests/test_fitschan_wcs.rs`, `test_fitschan_read.rs`, `test_fitschan_astwarn.rs`, `test_fitschan_time.rs`, `test_alt_wcs_read.rs`, `test_fitschan_axis_count_oracle.rs`, `test_fitschan_labels_oracle.rs`, `test_fitschan_read_chain_oracle.rs`, and the read tests of `test_fitschan_c_inline.rs` and `test_wcsconv.rs`.
The `native` rows of `fixtures/wcsconv/cases.txt` compare dumps of FrameSets C simplified and are deferred with the reason `needs simplification`; the other encodings' rows are deferred as `out-of-scope method FitsChan.write`.
`SipHeadersTest`: `wcsconv/inputs/sip.head`, `sip2.head` and `sip-alt.head` are absent from `deferred/headers.txt`, so the headers gate checks them.
`MalformedHeaderTest` (Review Focus 3): a celestial header whose `CRVAL1` is `'abc'`, and one with an unterminated string value, make `read()` throw `AstException` with the status C raises, or return what C returns (read from `fitschan.c` and cited in the test), never another exception type.

**Probe data:** `fitschan_axis_count_probe`, `fitschan_labels_probe`, `fitschan_read_chain_probe`.

**Interfaces:**
- Consumes: Tasks 12, 16 to 22.
- Produces: `FitsWcsRead.read(FitsChan fc)`: `FrameSet`, or null when the cards hold no WCS, as Rust's read returns `None`.

**Decisions:**
- After this task the headers gate runs every `.head` section; each fixture still deferred has a measured cause, such as `needs SpecFrame`, `needs MathMap`, `-TAB` or `encoding FITS-IRAF`.
- A row that differs from C only because C's oracle was computed with fused multiply-add contraction (BAD on one side, finite on the other, beside a singularity) goes in `src/test/resources/deferred/oracle-rows.txt` as `<path> <forward|inverse> <row numbers> | <measured cause>`, which the gates read; the gate fails if a listed row matches.

### Task 24: Benchmarks

**Files:**
- Create: `bench/pom.xml` (JMH, depending on `uk.ac.starlink:ast`, `--release 8`), `bench/src/main/java/uk/ac/starlink/ast/bench/{TransformBench,BenchCases}.java`, `bench/README.md`

**Interfaces:**
- Consumes: the library from Tasks 1 to 23; `OracleFile`'s format (re-implemented in `BenchCases`, since the benchmark project does not depend on test classes).

**Decisions:**
- The cases are the sections of the Rust repo's `crates/ast-bench/sets/framesets.txt` and `sets/bench.txt` whose fixtures load in phase 1, so Java and Rust time the same objects; the rest are listed in `bench/README.md` with the class each needs.
- Points follow ast-bench: the 24 oracle rows of a section repeated to 1, 1,000, 100,000 and 1,000,000 points, timed through `tranP` (one array per axis, as `rust-cols` is) and through the NaN protocol.
- Rust is timed with the Rust repo's own tool on the same machine (`cargo run --release -p ast-bench -- run --set framesets`, without the optional C engine), so no C library is built.

- [ ] **Step 1: Write the benchmark project**

`TransformBench` is a JMH benchmark parameterized by case and point count, reporting nanoseconds per point.

- [ ] **Step 2: Run it**

Run: `mvn -q -f bench/pom.xml package && java -jar bench/target/benchmarks.jar -f 2 -wi 5 -i 10 -rf csv -rff bench/results-jdk27.csv`
Expected: one row per case and point count; repeat on a Java 8 runtime if one is available.

- [ ] **Step 3: Time Rust on the same machine**

Run (in `../starlink-astrs`): `cargo run --release -p ast-bench -- run --set framesets`
Expected: a table with `ns/pt` and `cols ns/pt` per case; save it as `bench/results-rust.txt`.

- [ ] **Step 4: Commit**

```bash
git add bench && git commit -m "Add JMH transform benchmarks"
```

### Task 25: Phase gate and report

**Files:**
- Modify: `src/test/java/uk/ac/starlink/ast/JniastApiTest.java`
- Create: `docs/reports/phase1-report.md`

- [ ] **Step 1: Close the class list**

Set `JniastApiTest.IN_SCOPE` to exactly: AstObject, AstException, Mapping, Frame, Axis, SkyAxis, SkyFrame, TimeFrame, CmpFrame, FrameSet, UnitMap, ShiftMap, ZoomMap, WinMap, MatrixMap, PermMap, PolyMap, CmpMap, SphMap, WcsMap, SlaMap, TimeMap, Channel, FitsChan, KeyMap, ResampleFlags, Ukern1Calculator, UinterpCalculator.
Run: `mvn -q test -Dtest=JniastApiTest`
Expected: PASS (every jniast member of these classes exists).

- [ ] **Step 2: Check the deferred lists**

Run: `rg -n 'UNEXPLAINED' src/test/resources/deferred` and `mvn -q verify`
Expected: no matches; BUILD SUCCESS.

- [ ] **Step 3: Run the suite on a Java 8 runtime**

Run the CI workflow's Java 8 job, or `mvn -q verify` with `JAVA_HOME` set to a Java 8 JDK if one is installed (none is on this Mac, and installing one needs Tim's agreement).
Expected: BUILD SUCCESS; any test whose result differs between runtimes is investigated and its cause recorded in the report.

- [ ] **Step 4: Write the report**

`docs/reports/phase1-report.md`, one sentence per line, covering: what was ported (classes; lines of main and test code from `find src -name '*.java' | xargs wc -l`); how long it took (from `git log`); problems found, including any disagreement between C and Rust; every tolerance wider than the default and every deferred oracle row, with its cause; deferred tests and fixtures counted by reason; the number of `MODERN-JAVA` tags (`rg -c 'MODERN-JAVA' src`); the benchmark results from Task 24, Java beside Rust per case and point count, with the JVM and machine named; and an estimate for each later phase in the spec, scaled from this phase's rate by the Rust and C line counts of what remains.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Close phase 1: API gate and report"
```
