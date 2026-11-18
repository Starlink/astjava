# Pure-Java AST: phase 2 report

## Summary

Phase 2 ported AST's Mapping simplifier on 13 November 2026, following the plan [`2026-11-13-pure-java-ast-phase2-simplify.md`](../superpowers/plans/2026-11-13-pure-java-ast-phase2-simplify.md): `Mapping.simplify()` now restructures Mappings as C's `astSimplify` does, for every class phase 1 ported.
All 229 rows of C's own simplify regression table (`ast_tester/fixtures/simplify/cases.txt`) that use only those classes now give C's simplified dump, byte for byte with numbers to 4 units in the last place, `IsSimp` records included; the other 288 rows need classes outside this phase.
The FrameSet growth that phase 1 flagged as its largest risk is gone: 200 settings of `Label(1)` on the FrameSet read from `galactic-tan.head` leave its dump at 8,367 to 8,373 characters and 2 nodes, where phase 1 grew it to 6.6 MB.
The deferrals phase 1 made for want of simplification are all cleared, among them the 168 SkyFrame conversions excused within 5e-8 rad (now within 4.4e-15 rad of C) and the 4 CAR-seam oracle rows.
Porting each class's `MapMerge` from C, rather than from the Rust port, found 13 defects in C's simplifier, 11 of them confirmed by running the pinned C library, and recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md).
FrameSets read from FITS headers transform 1.2 to 3.1 times as fast for one point, and up to 4.2 times as fast for a million points; the Mappings phase 1 benchmarked, which C had already simplified, are unchanged.
The work added 4,974 lines of main code and 3,404 lines of tests; `mvn verify` passes with 2,467 tests on JDK 21, and the tests pass on the JDK 27 runtime.
The final review found no Critical problems; its fix pass makes simplification decline a merge where C would read or write past an array, so that no exception other than an `AstException` escapes it, and makes the gate check that simplifying leaves its input as C does (see Final review).

## What was ported

| Task | What | Main lines | Test lines |
| --- | --- | ---: | ---: |
| 1 | The simplify regression gate (`SimplifyRegressionTest`, `deferred/simplify-cases.txt`) | 0 | 294 |
| 2 | The engine (`Simplifier`, `MergeList`: C's `MapList`, CmpMap `Simplify` with its nominate loop and `PatternCheck`, base `Simplify`, `astMapMerge`) and UnitMap's `MapMerge` | 685 | 376 |
| 3 | ZoomMap, ShiftMap and WinMap (`WinMapMerge`) | 1,453 | 761 |
| 4 | MatrixMap (`MatrixMapMerge`) | 813 | 427 |
| 5 | PermMap | 278 | 90 |
| 6 | CmpMap (`CmpMapMerge`) | 535 | 247 |
| 7 | SphMap and WcsMap (`WcsMapMerge`) | 477 | 295 |
| 8 | PolyMap | 290 | 236 |
| 9 | SlaMap and TimeMap | 504 | 216 |
| 10 | FrameSets, Frames and FITS reading: every call site checked against C; no main code changed | 0 | 170 |
| 11 | Sweep of the deferred lists: nothing left to change | 0 | 0 |
| 12 | Header-read benchmarks and this report | | |

Main lines are lines added to `src/main`; the totals over the phase are 4,974 added and 13 removed.
Tasks 1 and 2 were executed in the controlling session and Tasks 3 to 10 by one implementer subagent each, whose result was verified there before it was recorded (the full test suite, the deferred lists changing only by removal, and C probes re-run for every defect claimed).
Tasks 1 to 10 were committed between 17:47 and 20:18 on 13 November; the benchmarks took a further two hours.

## Departures from the plan

Two design decisions differ from the plan; the [rulings](2026-11-13-phase2-rulings.md) give the reasons and costs.

- **C's data model, not Rust's.**
  A simplification works on a list of Mappings and the value of Invert to apply each with.
  C's list holds the Mappings themselves, shared with the Mapping being simplified; the Rust port holds copies normalised to their forward orientation and reconstructs C's `IsSimp` records, which C writes through the shared pointers, with a tree of tags.
  Java objects are shared references as C's are, so the Java list holds the Mappings themselves, and Rust's tag machinery is not ported.
  C's records then come out of the sharing: the first engine commit alone made a nested-tag regression row match, and 11 C probe cases compared later give byte-identical dumps of both the result and the input left behind.
- **Each class's `MapMerge` from C.**
  The plan ported the Rust merges, whose structure follows from its copies (it clones pairs to try a swap, and tests whether a class changed where C tests whether `astSimplify` returned the same pointer).
  With C's data model, each class's `MapMerge` was ported from its C source, and Rust's pairwise `try_merge` layer was not ported; the Rust port remained the source of tests, of the audit checklists that map C's steps, and of its notes on C's quirks.

The gate's transform check was also amended twice (Tasks 7 and 8), and applies to 23 rows (`sph_*`, `sla_*`, `wcs*`, `poly_*`): where C's simplification itself changes outputs outside the domain the Mappings assume (SphMaps cancelling on vectors that are not of unit length, WcsMaps on points the projection cannot transform, a PolyMap and its inverse where the iterative inverse does not converge), the simplified Mapping is compared with C's recorded outputs for C's simplified Mapping rather than for the original.

## Gates

| Gate | Phase 1 | Phase 2 |
| --- | --- | --- |
| `cases.txt` rows in scope matching C's simplified dump | not compared | 229 of 229 (58 before any port) |
| `cases.txt` rows needing an out-of-scope class | | 288 |
| Rust tests deferred as needing simplification | 59 | 0 |
| `wcsconv/cases.txt` rows deferred as needing simplification | 47 | 0 (all are `native` rows, whose output needs `FitsChan.write`) |
| Oracle rows deferred (CAR seam) | 4 | 0 |
| SkyFrame conversions excused | 168, within 5e-8 rad | 0; largest difference 4.4e-15 rad |
| Tests | 2,284 | 2,467 |

Phase 1 exclusions removed because they now match C: two FrameSet `Current` steps, the `cmpmap_simplified` ObjectId case, the inverted permuted MatrixMap `MapSplit` case, four FitsChan cursor cases, partial dump comparisons in the FitsChan read-chain test (all cases now compare whole dumps), FrameSetRepair's and FrameSetVariants' comparisons with C, and a structure check that accepted a chain of UnitMaps where C gives one UnitMap.

## Tolerances and excuses

Removed: the SkyFrame conversion excuse (5e-8 rad) and the 4 CAR-seam oracle rows.
Added or changed:

| Where | What | Measured size and cause |
| --- | --- | --- |
| `FrameSetVariantsTest` | Two Java routes through one FrameSet compared within 1e-12 instead of exactly | 4.4e-16: the simplified route merges a shift into a WinMap; no C value is involved |
| `FitsChanCursorOracleTest` | `iwc_on_alt` deferred as `runtime Math:` (not reported if it matches) instead of needing simplification | One unit in the last place of a MatrixMap element: `Math.sin(2° − π/2)` and macOS libm differ in the last bit, confirmed by a C replica of `MtrRot`; a build without contraction rules out fused multiply-add |
| `SimplifyRegressionTest` | Transforms of 23 rows compared with C's outputs for the `.simp` | C's own simplification changes outputs outside the domain of the Mappings (see Departures) |

## Defects found in the C library

All are in the pinned library (`3d3f255f`); those checked against PR #104 are also there.
Each is recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md) with what Java does; the probes are in the session's scratch directory.

| Where | Defect | Confirmed |
| --- | --- | --- |
| `matrixmap.c:1896-1925` | A full MatrixMap with zero off-diagonal elements simplifies to a forward diagonal MatrixMap even when applied inverted, changing its transformation | Yes |
| `winmap.c:2697-2699` | `WinDiagMat` returns the WinMap itself for a unit MatrixMap, in series and in parallel, and treats a non-square diagonal MatrixMap as square, changing the Mapping's numbers of axes or its transformation; the Mappings left can make later merges read or write past their arrays | Yes |
| `matrixmap.c` `MatWin2` | Reads past the end of an array for a non-square diagonal MatrixMap beside a WinMap | By reading |
| `permmap.c:1030`, `1222` | `maxperm = astGetNout` where `Transform` uses the inputs, so simplifying changes the transformation | Yes, and in PR #104 |
| `permmap.c:1389`, `1398` | Null-pointer dereference for a PermMap with a null permutation | Yes (SEGV), and in PR #104 |
| `permmap.c:1774` | `NullPerm` reads past the end of `outperm` for an inverted PermMap | Yes (heap-buffer-overflow), and in PR #104 |
| `cmpmap.c:2243` | Writes past the constants array when swapping a parallel CmpMap with a constant PermMap | Yes (heap-buffer-overflow) |
| `cmpmap.c:2183-2236` | Leaves permutation entries uninitialised in the same swap | Yes (`astStore` reads them) |
| `cmpmap.c:2255-2259` | The same swap applies the second component's inverse to constants, changing the inverse transformation | Yes |
| `wcsmap.c:914` | `CanMerge` compares a parameter count with itself, so WcsMaps with different parameters cancel | Yes |
| `wcsmap.c:4726`, `4744` | `WcsPerm` keeps the PermMap's own Invert, swapping the simplified Mapping's numbers of inputs and outputs | Yes |
| `polymap.c:4004-4006` | A cancelled PolyMap is left with the list's Invert value | Yes |
| `timemap.c:2327-2335` | TDBTOTCB and TCBTOTDB compare an uninitialised third argument | By reading; C cancels in every case tried |

Java follows C where C's result is defined, including the five that change a transformation or the numbers of axes, and gives a defined result where C crashes or reads memory it does not own; it restores the PolyMap's Invert.

## Final review

A fresh reviewer read the whole branch and checked it against the pinned C library with its own probes and a differential fuzzer of random compositions of this phase's Mappings.
It found the results and the inputs left behind byte-identical to C's on all 229 rows, and shape, transform and error status agreeing with C on every fuzzed input where C does not crash; it found no Critical problem.
The fix pass dealt with its Important findings:

- **Unchecked exceptions.**
  About one random composition in 150 (mostly with a non-square MatrixMap) made `simplify()` throw `ArrayIndexOutOfBoundsException` or `IndexOutOfBoundsException`, at the places where C reads or writes past an array: the WinMap direct merges, distribution and swaps, MatrixMap's `MatWin2`, PermMap's run of permutations and CmpMap's merge of parallel CmpMaps in series.
  Each now checks the assumption C makes without checking (a square neighbour, Mappings whose numbers of axes chain) and declines the merge when it fails, which keeps the transformation; `SimplifyRobustnessTest` holds a reproducer of each, and 80,000 fuzzed compositions afterwards gave none.
- **A silently wrong transformation.**
  Distributing a WinMap into a parallel CmpMap wider than its terms padded the missing terms with zeros where C reads past them; the distribution is now declined.
- **C's shape-changing defect** (`winmap.c:2697-2699`, in the table above) is followed, as the spec requires, and recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md); the decision whether this library should depart from C here is left to its author.
- **The gate** now also checks, for every row, that simplifying leaves the input's dump (apart from the records `IsSimp` and `ReSimp` that C also writes) and what it computes unchanged; a deliberately broken Invert restore fails it on 22 rows.

## Benchmarks

Six cases were added that read `sip.head`, `galactic-tan.head` and `car6.head` through a FitsChan and transform C's oracle points; they were timed on JDK 21 with phase 1's library and with this one ([`bench/README.md`](../../bench/README.md), `bench/results-phase1-headers-jdk21.csv`).
Simplification reduces the pixel-to-sky Mapping of `sip.head` from 23 Mappings to 13, of `galactic-tan.head` from 21 to 9, and of `car6.head` from 21 to 5 (a WinMap, a ShiftMap and a WcsMap).

| Header | One point, `tranP` | One point, NaN protocol | 1,000,000 points, `tranP` | 1,000,000 points, NaN protocol |
| --- | --- | --- | --- | --- |
| `sip.head` | 1.19 to 1.25 times as fast | 1.22 to 1.33 | 1.05 | 1.04 to 1.07 |
| `galactic-tan.head` | 1.84 to 1.97 | 1.92 to 2.07 | 1.08 to 1.10 | 1.08 to 1.09 |
| `car6.head` | 2.21 to 2.52 | 2.73 to 3.12 | 3.88 to 4.16 | 2.92 to 3.63 |

Ranges are over the two directions.
For a million points the time of the SIP and TAN headers is in their projections and polynomials, which simplification does not change.
At phase 1, `car6.head` differed from C at the CAR seam in row 1, so its earlier time is of a slightly different computation.

The 78 cases phase 1 timed are native dumps that C had already simplified and are transformed without simplification, so no change was expected; the geometric means of Java's time over Rust's are within 1% of phase 1's at one point, and at a million points move from 1.72 to 1.70 (`tranP`) and 1.41 to 1.56 (NaN protocol) on JDK 21, and from 2.16 to 1.95 and 1.43 to 1.45 on JDK 27.
The largest changes are in the cheapest memory-bound Mappings, whose timings at a million points vary by 2 to 4 times between runs, as phase 1 found.
The machine was an Apple M1 Ultra with 64 GB under macOS 27.2.1, but not idle: a backup process kept one core busy and the load average was 4.6 to 6.8; JDKs 21.0.13 and 27.0.1.

## Gaps and risks

- **Classes outside this phase.** 288 rows of the regression table, and the corresponding fixtures in the other deferred lists, need classes not yet ported (MathMap, SpecMap, Regions, PcdMap, UnitNormMap, LutMap and others); each class's `MapMerge` will be ported with the class, and the gate fails when a row starts to match.
- **Native FITS reads are not compared with C in the tests.**
  The `native` rows of `wcsconv/cases.txt` compare FITS cards, which need `FitsChan.write`.
  A scratch comparison of the dumps of the 48 such inputs the port reads found 44 byte-identical to C's; `skyref`, `skyref-alt` and `tidy-pole` differ in the last bits of MatrixMap elements and `azel` in `ObsLat` and `ObsAlt`, and the cause was not found.
- **The recursion guard** compares the native text of two Mappings where C uses `astEqual`, which is not ported; Mappings C finds equal within `astEQUAL` but whose texts differ would not be caught, and texts are rendered for every nested CmpMap, which makes a chain of 2,000 nested CmpMaps take about a second.
  No test reaches a match.
- **Transform-changing C quirks are copied.**
  Four of the defects above change what a Mapping computes when it is simplified; Java does what C does, so a user of either library meets them; they are worth fixing upstream.
- **`Naxes = 2147483647`** in a native dump still exhausts memory, as phase 1 reported; it is unrelated to simplification and still needs a decision.

## Rulings

Every decision made while executing the plan where it was wrong, unclear or silent is in [`2026-11-13-phase2-rulings.md`](2026-11-13-phase2-rulings.md), with its reason and its cost if wrong.
