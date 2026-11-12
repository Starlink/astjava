# C probe data

Each JSON file here holds the expected values produced by one C probe program, for the tests that need values the transform oracles do not cover.

The probe programs live in the Rust port's repository (`tools/oracle_fixtures/*.c`); each was built against the C library and its output committed there as Rust source, `crates/starlink-ast/tests/<x>_oracle/cases.rs`.
The values therefore come from C, not from the Rust port.
No probe is rebuilt or rerun here, and no test reads a `cases.rs` file: each is converted once to `<x>.json` and committed.

## Converting

From the project root, with the `reference/starlink-ast-rs` submodule checked out and JDK 21:

    mvn -q test-compile
    java -cp target/test-classes uk.ac.starlink.ast.testsupport.CasesToJson reference/starlink-ast-rs <x>...

This reads `reference/starlink-ast-rs/crates/starlink-ast/tests/<x>_oracle/cases.rs` for each `<x>` and writes `src/test/resources/oracle/<x>.json`.

## Format

    {
      "source": "crates/starlink-ast/tests/<x>_oracle/cases.rs",
      "generator": "tools/oracle_fixtures/<probe>.c",
      "rustCommit": "<commit of the Rust repository the file was converted from>",
      "consts": { "<NAME>": <value>, ... }
    }

`consts` holds each `const` of the Rust file in order.
Tuples, arrays and slices become JSON arrays; `Some(x)` becomes `x` and `None` becomes `null`.
A variant of an `enum` the file declares becomes an object: `{"variant": "Split", "value": [[2, 1], "..."]}` for `Outcome::Split(&[2, 1], "...")`, the value being the variant's field, or an array of its fields when it has several; a unit variant such as `Outcome::None` has no `value`.
A `Result` is written the same way: `Ok(x)` becomes `{"variant": "Ok", "value": x}` and `Err(x)` becomes `{"variant": "Err", "value": x}`.
An integer too large for a Java `long` (a `u64` hash) is written as it is, and is read with `JsonNode.bigIntegerValue()`.
Doubles are written in Java's `Double.toString` form, so they read back to the same value; NaN, the infinities and C's bad value (`AST__BAD`) are the strings `"NaN"`, `"Infinity"`, `"-Infinity"` and `"BAD"`, which `ProbeData.toDouble` turns back into numbers.
C's bad value is recognised as `BAD_VALUE`, however qualified, or by the name a `use ... BAD_VALUE as NAME;` item gives it.

## Refreshing

When the Rust repository regenerates a `cases.rs` (for example after the C library it pins moves), update the submodule and convert that file again; `rustCommit` records which commit each file came from.
