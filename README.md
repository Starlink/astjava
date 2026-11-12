# astjava

A pure-Java port of the [Starlink AST](https://github.com/Starlink/ast) library for handling world coordinate systems.
Its public API, in package `uk.ac.starlink.ast`, matches that of jniast, the JNI interface to the C library that starjava uses, so applications can use it without native libraries.
Its behaviour follows the C library.

The library runs on Java 8 and later, and has no dependencies beyond the JDK.
It is licensed under the GNU Lesser General Public License, version 3 or later (see `LICENSE`).

## Status

The first phase is in progress: reading native AST dumps and FITS-WCS headers (including SIP distortions) into FrameSets, and transforming between pixel and sky coordinates.
The design is in `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md`.

## Building and testing

The build needs JDK 21 to 26 and Maven 3.9 or later; the JAR it makes runs on Java 8.

    git submodule update --init reference/starlink-ast
    mvn verify

`mvn verify` compiles with Error Prone and runs Checkstyle, PMD, the tests and the formatting check.
`mvn test -Dtest=ZoomMapTest` runs one test class, and `mvn spotless:apply` formats the sources.
To run the tests on another Java runtime, such as Java 8, build as usual and name the runtime:

    mvn test -Djvm=/path/to/jdk8/bin/java

## Test data

The tests read the C library's language-neutral fixtures and oracle files from the `reference/starlink-ast` submodule (`ast_tester/fixtures`), pinned to the commit the Rust port pins.
Expected values come from the C library only: the oracle files directly, and the C probe tables committed in the Rust port, converted to JSON under `src/test/resources/oracle`.

## Reference sources

| Submodule | Contents |
| --- | --- |
| `reference/starlink-ast` | The C library, the final reference for behaviour, and the test fixtures |
| `reference/starlink-ast-rs` | The Rust port, from which most of this library is ported |
| `reference/starjava` | starjava, whose jniast package defines the API to match |
