# Transform benchmarks

JMH benchmarks of the library's transforms, timed on the same objects and points as the Rust port's `ast-bench` so that the two ports can be compared on one machine.

This is a separate Maven project that depends on the library's JAR; it is not part of the library's build.

## Cases

The cases are the sections of the Rust repository's `crates/ast-bench/sets/framesets.txt` and `crates/ast-bench/sets/bench.txt` whose fixtures load in this library.
A case is named `<oracle>:<relpath>:<dir>`: `<oracle>` is `bench` for `ast-bench`'s own fixtures (`reference/starlink-ast-rs/crates/ast-bench/fixtures/bench.oracle`) or the name of a core oracle under `reference/starlink-ast/ast_tester/fixtures/oracle`, `<relpath>` is the fixture, and `<dir>` is `forward` or `inverse`.
Fixtures are loaded as `ast-bench` loads them: a FrameSet is timed whole, and a `.head` file is read through a FitsChan.

    java -cp bench/target/benchmarks.jar uk.ac.starlink.ast.bench.BenchCases

lists every section of the two sets, the case name of each that loads and the reason for each that does not.
The `fixture` parameter of `TransformBench` lists the cases that load.

The sections left out need classes this library does not have yet:

| Set | Oracle | Fixture | Needs |
| --- | --- | --- | --- |
| framesets | headers | `wcsconv/inputs/vopt-wav.head` | SpecFrame |
| framesets | simplify_fixtures | `simplify/neg_intra_parallel.map` | IntraMap |
| bench | bench | `chebymap_2d_deg3.map` | ChebyMap |
| bench | bench | `grismmap.map` | GrismMap |
| bench | bench | `lutmap_100.map` | LutMap |
| bench | bench | `mathmap_2d_linear.map` | MathMap |
| bench | bench | `normmap_2d.map` | NormMap |
| bench | bench | `pcdmap.map` | PcdMap |
| bench | bench | `ratemap_polymap.map` | RateMap |
| bench | bench | `specmap_wn_to_fr.map` | SpecMap |
| bench | bench | `splinemap_5x5_cubic.map` | SplineMap |
| bench | bench | `tranmap_zoom_shift.map` | TranMap |
| bench | bench | `unitnormmap_2d.map` | UnitNormMap |
| bench | framesets | `wcsconv/inputs/lsst2.ast` | SplineMap |
| bench | framesets | `wcsconv/inputs/a20070718_00010_02_cube.ast` | DSBSpecFrame, SpecMap |
| bench | framesets | `wcsconv/expected/jan.ast` | DSBSpecFrame |
| bench | simplify_fixtures | `simplify/brad.map` | LutMap |
| bench | simplify_fixtures | `simplify/rigby.map` | SpecMap |

That leaves 78 sections: 14 from `framesets` and 64 from `bench`.
Before timing, the outputs of all 78 at their 24 oracle rows were compared with the C oracle: the bad values agree, the largest relative difference is 1.5e-11 (an iterative inverse), and the NaN protocol gives the same bits as `tranP`.

## Points and timings

The points follow `ast-bench`: the 24 oracle rows of a section repeated in order to 1, 1,000, 100,000 and 1,000,000 points.
Up to 100 points only rows that are good in every input and output are used, so that a short run does not time the early exit of bad points.

Two benchmarks time each case at each point count:

- `tranP`: the public `Mapping.tranP`, with one array per axis, which is how `ast-bench`'s `rust-cols` timing calls the Rust port; `tranP` allocates its result on every call, as its signature requires, whereas `rust-cols` writes into a buffer the caller owns.
- `nanProtocol`: the NaN protocol (`transformIntoNan`) with its boundary passes, bad values to NaN before and non-finite values to `AST__BAD` after, into buffers allocated once, which is `ast-bench`'s `rust-nan` timing.
  `transformIntoNan` is package-private, so the benchmark project reaches it through its own class `uk.ac.starlink.ast.BenchAccess`.

A JMH operation is one call of N points, so the primary score of `tranP` and `nanProtocol` (average time, `ns/op`) is nanoseconds per call.
Each call also adds N to an `@AuxCounters` operations counter, which JMH reports as the secondary results `tranP:points` and `nanProtocol:points`; their score, also labelled `ns/op`, is nanoseconds per point, the figure to compare with `ast-bench`'s `ns/pt` columns.

## Building and running

The benchmarks use the library installed in the local Maven repository, so install it first, from the project root, with JDK 21 as `JAVA_HOME`:

    mvn -q install -DskipTests
    mvn -q -f bench/pom.xml package

Run from the project root, so that the `reference` submodules are found (or give their directory with `-Dast.reference=...` through `-jvmArgsAppend`):

    java -jar bench/target/benchmarks.jar -f 1 -wi 2 -w 1s -i 3 -r 1s -rf csv -rff bench/results-jdk21.csv

These settings (one fork, two one-second warm-up iterations and three one-second measured iterations per case and point count) time the 78 cases at four point counts through both benchmarks, 624 timings, in about 56 minutes on the machine below.
The plan's `-f 2 -wi 5 -i 10` would take several hours.
Add `-p fixture=<case>` (comma-separated) or `-p npoints=<n>` to time a subset.

## Results

The results were taken on an Apple M1 Ultra with 64 GB of memory under macOS 27.2.1, with the machine otherwise idle:

- `results-jdk21.csv`: OpenJDK 21.0.13 (Homebrew), the runtime the library is built with.
- `results-jdk27.csv`: OpenJDK 27.0.1 (Homebrew), the newest runtime installed.
- `results-rust.txt`: the Rust port at `reference/starlink-ast-rs` commit 10f61689, built by rustc 1.99.1 in its release profile and timed by its own `ast-bench` with `run --set framesets --nan` and `run --set bench --nan`, without the optional C engine.

No Java 8 runtime is installed on this machine, so there are no Java 8 timings; `-jvm` gives JMH another runtime when one is available.
Each JMH run took 56 minutes; the two Rust runs took 7 minutes and a half together.

`ast-bench` times 1, 10, 100, 100,000 and 1,000,000 points, so it has no 1,000-point timing.
Its timing is a median over at least seven samples within a time budget, whereas JMH's is a mean over three one-second iterations; at 1,000,000 points a call of a slow FrameSet takes about 0.4 seconds, so an iteration holds only a few calls.

Nanoseconds per point for some representative forward cases (the `:points` rows of the CSV files):

| Fixture | Dir | N | tranP, JDK 21 | tranP, JDK 27 | Rust cols | NaN, JDK 21 | NaN, JDK 27 | Rust nan |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `wcsconv/framesets/sao-tan-head.ast` | forward | 1 | 2552.9 | 2180.5 | 2172.69 | 2077.7 | 1861.9 | 2167.48 |
| `wcsconv/framesets/sao-tan-head.ast` | forward | 1,000 | 146.8 | 145.7 | - | 149.5 | 149.0 | - |
| `wcsconv/framesets/sao-tan-head.ast` | forward | 1,000,000 | 143.2 | 140.6 | 87.60 | 146.8 | 142.0 | 89.77 |
| `wcsconv/framesets/sip2-head.ast` | forward | 1 | 3291.4 | 2386.2 | 3834.63 | 2286.0 | 1825.2 | 3830.40 |
| `wcsconv/framesets/sip2-head.ast` | forward | 1,000 | 100.5 | 97.4 | - | 97.9 | 98.9 | - |
| `wcsconv/framesets/sip2-head.ast` | forward | 1,000,000 | 92.9 | 91.1 | 62.57 | 95.6 | 93.9 | 64.58 |
| `wcsconv/framesets/tnx-cheb-head.ast` | forward | 1 | 4068.6 | 3579.6 | 3206.05 | 3363.9 | 3191.2 | 3204.43 |
| `wcsconv/framesets/tnx-cheb-head.ast` | forward | 1,000 | 174.4 | 168.6 | - | 166.8 | 170.6 | - |
| `wcsconv/framesets/tnx-cheb-head.ast` | forward | 1,000,000 | 164.5 | 162.5 | 96.81 | 163.7 | 165.2 | 98.61 |
| `wcsconv/inputs/lsst.ast` | forward | 1 | 4852.9 | 4195.3 | 7643.23 | 3370.1 | 2867.0 | 7620.77 |
| `wcsconv/inputs/lsst.ast` | forward | 1,000 | 123.4 | 119.6 | - | 124.2 | 120.7 | - |
| `wcsconv/inputs/lsst.ast` | forward | 1,000,000 | 113.5 | 110.2 | 78.99 | 112.7 | 112.5 | 80.60 |
| `simplify/lsst.map` | forward | 1 | 2101.7 | 1663.1 | 1545.17 | 1809.8 | 1406.4 | 1059.41 |
| `simplify/lsst.map` | forward | 1,000 | 120.7 | 117.7 | - | 111.6 | 107.4 | - |
| `simplify/lsst.map` | forward | 1,000,000 | 112.1 | 110.9 | 82.16 | 107.2 | 102.7 | 81.56 |
| `cmpmap_series.map` | forward | 1 | 290.7 | 206.7 | 93.81 | 136.6 | 94.0 | 69.53 |
| `cmpmap_series.map` | forward | 1,000 | 3.9 | 3.8 | - | 3.0 | 2.9 | - |
| `cmpmap_series.map` | forward | 1,000,000 | 3.7 | 3.7 | 1.01 | 3.7 | 3.5 | 2.02 |
| `zoommap_2d.map` | forward | 1 | 146.5 | 103.9 | 33.22 | 13.9 | 13.4 | 21.41 |
| `zoommap_2d.map` | forward | 1,000 | 2.3 | 2.3 | - | 2.3 | 2.3 | - |
| `zoommap_2d.map` | forward | 1,000,000 | 1.5 | 1.9 | 0.51 | 2.5 | 2.5 | 1.76 |

Over all 78 cases, the geometric mean of the Java/Rust ratio is:

| Timing | N | JDK 21 | JDK 27 |
| --- | ---: | ---: | ---: |
| `tranP` / `rust-cols` | 1 | 1.89 | 1.50 |
| `tranP` / `rust-cols` | 1,000,000 | 1.72 | 2.16 |
| `nanProtocol` / `rust-nan` | 1 | 0.89 | 0.80 |
| `nanProtocol` / `rust-nan` | 1,000,000 | 1.41 | 1.43 |

What the numbers show:

- At 1,000,000 points the real-world FrameSets and Mappings run 1.1 to 1.9 times as long per point as in Rust (2.6 times for `simplify/lsst1.map`, a short chain that costs only 2.6 ns per point in Rust); the plain Mappings run from 0.7 times (the QSC WcsMap) to 3.7 times as long, the cheapest ones, whose loops Rust vectorises, being the slowest relative to Rust.
- One call of one point through a real-world FrameSet or Mapping takes 0.4 to 12 microseconds, and is faster than in Rust for 9 of the 20 such cases on JDK 21 and 15 of them on JDK 27.
- `tranP` costs a median of 130 ns per call more than the NaN protocol on the same Mapping on JDK 21 (100 ns on JDK 27), in its argument checks, attribute reads and allocation of the result; a one-point ZoomMap takes 147 ns through `tranP` and 14 ns through the NaN protocol, against 33 ns and 21 ns in Rust.
  This per-call cost dominates every Mapping that is cheap per point.
- At 1,000,000 points some timings step up by a factor of 2 to 4 between runs or after the warm-up iterations, with errors larger than the scores: on JDK 27, `tranP` through the cheapest 2-axis Mappings (`cmpmap_parallel.map`, `shiftmap_2d.map`) went from about 2.7 ms to 7 to 10 ms per call, and on both runtimes some 3-axis cases (`matrixmap_3x3_diag.map`, `cmpmap_nested_parallel_flatten.map`) take about 9 ns per point instead of 3.7 ns.
  These calls move 30 to 70 MB of data, more than the processor's caches hold, and `tranP` also allocates its 8 MB result arrays on every call, so those rows measure memory traffic and the garbage collector more than the transform; the cause was not investigated further.

## Lint

The benchmark sources are formatted by the root project's Spotless (`bench/src/**/*.java`), and compiled here with `-Xlint:all -Werror`.
They are not checked by Error Prone, Checkstyle or PMD: they are a harness that is not shipped, and JMH's generated code would need its own exceptions.
