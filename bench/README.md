# Transform benchmarks

JMH benchmarks of the library's transforms, timed on the same objects and points as the Rust port's `ast-bench` so that the two ports can be compared on one machine.

This is a separate Maven project that depends on the library's JAR; it is not part of the library's build.

## Cases

The cases are the sections of the Rust repository's `crates/ast-bench/sets/framesets.txt` and `crates/ast-bench/sets/bench.txt` whose fixtures load in this library.
A case is named `<oracle>:<relpath>:<dir>`: `<oracle>` is `bench` for `ast-bench`'s own fixtures (`reference/starlink-ast-rs/crates/ast-bench/fixtures/bench.oracle`) or the name of a core oracle under `reference/starlink-ast/ast_tester/fixtures/oracle`, `<relpath>` is the fixture, and `<dir>` is `forward` or `inverse`.
Fixtures are loaded as `ast-bench` loads them: a FrameSet is timed whole, and a `.head` file is read through a FitsChan.

    java -cp bench/target/benchmarks.jar uk.ac.starlink.ast.bench.BenchCases

lists every section of the two sets and every local case, the case name of each that loads and the reason for each that does not.
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

### Local cases

The FrameSets of `ast-bench`'s sets are native dumps written by C, whose Mappings C had already simplified, so they do not show what this library's own reading of a header gives.
Six cases of this project's own, not from `ast-bench`'s sets, fill that gap: `wcsconv/inputs/sip.head`, `wcsconv/inputs/galactic-tan.head` and `wcsconv/inputs/car6.head`, read through a FitsChan as above, forward and inverse.
Their names start `local:` (`local:headers:wcsconv/inputs/sip.head:forward`), followed by an ordinary case name; `BenchCases.LOCAL_CASES` lists them.
Their points are those of their sections of the core `headers` oracle, chosen as for every other case: pixel coordinates recorded by C for the forward direction, and the sky coordinates C computed from them for the inverse.
Their outputs at the 24 oracle rows agree with C (bad values the same, largest relative difference 6.4e-12, in the inverse of `sip.head`), and the NaN protocol gives the same bits as `tranP`.

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

These settings (one fork, two one-second warm-up iterations and three one-second measured iterations per case and point count) time the 84 cases at four point counts through both benchmarks, 672 timings, in about an hour on the machine below.
The plan's `-f 2 -wi 5 -i 10` would take several hours.
Add `-p fixture=<case>` (comma-separated) or `-p npoints=<n>` to time a subset.

## Results

The results were taken on an Apple M1 Ultra (20 cores) with 64 GB of memory under macOS 27.2.1:

- `results-jdk21.csv`: OpenJDK 21.0.13 (Homebrew), the runtime the library is built with.
- `results-jdk27.csv`: OpenJDK 27.0.1 (Homebrew), the newest runtime installed.
- `results-phase1-headers-jdk21.csv`: the local cases on OpenJDK 21.0.13 with the library of phase 1 (commit 5673d6f), whose `simplify()` returned its Mapping unchanged, so that a header was read into an unsimplified FrameSet.
  It was built from a scratch checkout of that commit, with this project's benchmark sources, and timed with the settings above just before the full runs.
- `results-rust.txt`: the Rust port at `reference/starlink-ast-rs` commit 10f61689, built by rustc 1.99.1 in its release profile and timed by its own `ast-bench` with `run --set framesets --nan` and `run --set bench --nan`, without the optional C engine.

The Java results are of the library with its Mapping simplifier (phase 2); those of phase 1 are in the history of this directory.
No other benchmark or build ran during the Java runs, but a backup client kept one core busy and the load average was 4.6 to 6.8 on the 20 cores; the phase 1 runs and the Rust runs were taken with the machine otherwise idle.

No Java 8 runtime is installed on this machine, so there are no Java 8 timings; `-jvm` gives JMH another runtime when one is available.
Each full JMH run took an hour; the two Rust runs took 7 minutes and a half together.

`ast-bench` times 1, 10, 100, 100,000 and 1,000,000 points, so it has no 1,000-point timing.
Its timing is a median over at least seven samples within a time budget, whereas JMH's is a mean over three one-second iterations; at 1,000,000 points a call of a slow FrameSet takes about 0.4 seconds, so an iteration holds only a few calls.

Nanoseconds per point for some representative forward cases (the `:points` rows of the CSV files):

| Fixture | Dir | N | tranP, JDK 21 | tranP, JDK 27 | Rust cols | NaN, JDK 21 | NaN, JDK 27 | Rust nan |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `wcsconv/framesets/sao-tan-head.ast` | forward | 1 | 2558.9 | 2204.4 | 2172.69 | 2085.7 | 1899.8 | 2167.48 |
| `wcsconv/framesets/sao-tan-head.ast` | forward | 1,000 | 147.0 | 144.9 | - | 148.0 | 163.5 | - |
| `wcsconv/framesets/sao-tan-head.ast` | forward | 1,000,000 | 140.4 | 146.2 | 87.60 | 143.0 | 159.3 | 89.77 |
| `wcsconv/framesets/sip2-head.ast` | forward | 1 | 3208.2 | 2411.2 | 3834.63 | 2361.8 | 1832.2 | 3830.40 |
| `wcsconv/framesets/sip2-head.ast` | forward | 1,000 | 98.7 | 97.0 | - | 100.8 | 100.5 | - |
| `wcsconv/framesets/sip2-head.ast` | forward | 1,000,000 | 92.6 | 90.8 | 62.57 | 94.8 | 93.1 | 64.58 |
| `wcsconv/framesets/tnx-cheb-head.ast` | forward | 1 | 3947.8 | 3553.5 | 3206.05 | 3534.0 | 3175.2 | 3204.43 |
| `wcsconv/framesets/tnx-cheb-head.ast` | forward | 1,000 | 169.0 | 168.1 | - | 174.8 | 169.8 | - |
| `wcsconv/framesets/tnx-cheb-head.ast` | forward | 1,000,000 | 161.6 | 161.8 | 96.81 | 166.7 | 164.7 | 98.61 |
| `wcsconv/inputs/lsst.ast` | forward | 1 | 4836.3 | 4083.8 | 7643.23 | 3361.8 | 2853.1 | 7620.77 |
| `wcsconv/inputs/lsst.ast` | forward | 1,000 | 122.6 | 117.4 | - | 122.7 | 120.9 | - |
| `wcsconv/inputs/lsst.ast` | forward | 1,000,000 | 110.9 | 110.5 | 78.99 | 113.6 | 113.0 | 80.60 |
| `simplify/lsst.map` | forward | 1 | 2115.1 | 1626.1 | 1545.17 | 1802.7 | 1408.4 | 1059.41 |
| `simplify/lsst.map` | forward | 1,000 | 119.3 | 115.8 | - | 109.7 | 108.4 | - |
| `simplify/lsst.map` | forward | 1,000,000 | 113.3 | 108.1 | 82.16 | 106.1 | 102.2 | 81.56 |
| `cmpmap_series.map` | forward | 1 | 292.8 | 201.8 | 93.81 | 138.3 | 96.0 | 69.53 |
| `cmpmap_series.map` | forward | 1,000 | 3.8 | 3.8 | - | 3.0 | 3.0 | - |
| `cmpmap_series.map` | forward | 1,000,000 | 3.7 | 3.7 | 1.01 | 3.7 | 3.7 | 2.02 |
| `zoommap_2d.map` | forward | 1 | 150.6 | 102.6 | 33.22 | 14.6 | 13.8 | 21.41 |
| `zoommap_2d.map` | forward | 1,000 | 2.3 | 2.2 | - | 2.3 | 2.3 | - |
| `zoommap_2d.map` | forward | 1,000,000 | 1.5 | 2.0 | 0.51 | 6.3 | 2.5 | 1.76 |

Over the 78 cases of `ast-bench`'s sets (the local cases have no Rust timing), the geometric mean of the Java/Rust ratio is:

| Timing | N | JDK 21 | JDK 27 |
| --- | ---: | ---: | ---: |
| `tranP` / `rust-cols` | 1 | 1.88 | 1.49 |
| `tranP` / `rust-cols` | 1,000,000 | 1.70 | 1.95 |
| `nanProtocol` / `rust-nan` | 1 | 0.89 | 0.81 |
| `nanProtocol` / `rust-nan` | 1,000,000 | 1.56 | 1.45 |

Phase 1's results gave 1.89, 1.72, 0.89 and 1.41 on JDK 21, and 1.50, 2.16, 0.80 and 1.43 on JDK 27.
The 78 cases' objects are read from native dumps and transformed without simplification, as in phase 1, and 63 to 75 of the 78 timings of each kind and runtime at 1,000,000 points are within 10% of phase 1's; the changes at 1,000,000 points come from the cheapest Mappings, whose timings step between runs (see below).

The local cases, nanoseconds per point on JDK 21, before (phase 1, unsimplified) and after (simplified):

| Header | Dir | N | tranP before | tranP after | Speed-up | NaN before | NaN after | Speed-up |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `wcsconv/inputs/sip.head` | forward | 1 | 5625.2 | 4714.8 | 1.19 | 3764.4 | 3079.7 | 1.22 |
| `wcsconv/inputs/sip.head` | forward | 1,000,000 | 96.0 | 91.9 | 1.05 | 99.2 | 93.1 | 1.07 |
| `wcsconv/inputs/sip.head` | inverse | 1 | 5779.6 | 4634.9 | 1.25 | 3945.4 | 2964.0 | 1.33 |
| `wcsconv/inputs/sip.head` | inverse | 1,000,000 | 138.4 | 131.6 | 1.05 | 140.0 | 134.2 | 1.04 |
| `wcsconv/inputs/galactic-tan.head` | forward | 1 | 2964.8 | 1611.6 | 1.84 | 2320.2 | 1206.8 | 1.92 |
| `wcsconv/inputs/galactic-tan.head` | forward | 1,000,000 | 78.4 | 71.6 | 1.10 | 81.4 | 74.5 | 1.09 |
| `wcsconv/inputs/galactic-tan.head` | inverse | 1 | 2954.9 | 1501.8 | 1.97 | 2210.2 | 1068.8 | 2.07 |
| `wcsconv/inputs/galactic-tan.head` | inverse | 1,000,000 | 90.8 | 84.3 | 1.08 | 93.8 | 86.7 | 1.08 |
| `wcsconv/inputs/car6.head` | forward | 1 | 2863.7 | 1136.6 | 2.52 | 2152.1 | 688.7 | 3.12 |
| `wcsconv/inputs/car6.head` | forward | 1,000,000 | 49.8 | 12.0 | 4.16 | 52.3 | 14.4 | 3.63 |
| `wcsconv/inputs/car6.head` | inverse | 1 | 2981.9 | 1349.4 | 2.21 | 2219.0 | 812.9 | 2.73 |
| `wcsconv/inputs/car6.head` | inverse | 1,000,000 | 54.8 | 14.1 | 3.88 | 60.3 | 20.6 | 2.92 |

Reading the headers, phase 1 built a Mapping from pixel to sky of 11 CmpMaps and 12 other Mappings (`sip.head`) or 10 CmpMaps and 11 others (`galactic-tan.head`, `car6.head`), including 3 UnitMaps and 4 MatrixMaps; simplified, the Mapping has 6 CmpMaps and 7 others, 4 CmpMaps and 5 others, and 2 CmpMaps and 3 others.
At one point every case is faster, by 1.2 to 3.1 times; at 1,000,000 points `car6.head`, whose Mapping is reduced to a WinMap, a ShiftMap and the WcsMap, is 2.9 to 4.2 times faster, while `sip.head` and `galactic-tan.head`, whose simplified Mappings keep their PolyMap or their SphMaps and MatrixMap, gain 4% to 10%.
Phase 1's `car6.head` also differs from C at its first oracle row in both directions (a point on the projection's seam; see the phase 1 report), so its before and after transforms are not quite the same computation.

What the numbers show:

- At 1,000,000 points the real-world FrameSets and Mappings run 1.1 to 1.9 times as long per point as in Rust (2.6 times for `simplify/lsst1.map`, a short chain that costs only 2.6 ns per point in Rust); the plain Mappings run from 0.7 times (the QSC WcsMap) to 3.7 times as long, the cheapest ones, whose loops Rust vectorises, being the slowest relative to Rust.
- One call of one point through a real-world FrameSet or Mapping takes 0.4 to 12 microseconds, and is faster than in Rust for 10 of the 20 such cases on JDK 21 and 15 of them on JDK 27.
- `tranP` costs a median of 136 ns per call more than the NaN protocol on the same Mapping on JDK 21 (97 ns on JDK 27), in its argument checks, attribute reads and allocation of the result; a one-point ZoomMap takes 151 ns through `tranP` and 15 ns through the NaN protocol, against 33 ns and 21 ns in Rust.
  This per-call cost dominates every Mapping that is cheap per point.
- At 1,000,000 points some timings step up by a factor of 2 to 4 between runs or after the warm-up iterations, with errors larger than the scores: in phase 1's run on JDK 27, `tranP` through the cheapest 2-axis Mappings (`cmpmap_parallel.map`, `shiftmap_2d.map`) took 7 to 10 ms per call instead of about 2.7 ms, and in this run on JDK 21 the NaN protocol through `zoommap_2d.map` takes 6.3 ns per point instead of 2.5 ns; on both runtimes some 3-axis cases (`matrixmap_3x3_diag.map`, `cmpmap_nested_parallel_flatten.map`) take about 9 ns per point instead of 3.7 ns.
  These calls move 30 to 70 MB of data, more than the processor's caches hold, and `tranP` also allocates its 8 MB result arrays on every call, so those rows measure memory traffic and the garbage collector more than the transform; the cause was not investigated further.

## Lint

The benchmark sources are formatted by the root project's Spotless (`bench/src/**/*.java`), and compiled here with `-Xlint:all -Werror`.
They are not checked by Error Prone, Checkstyle or PMD: they are a harness that is not shipped, and JMH's generated code would need its own exceptions.
