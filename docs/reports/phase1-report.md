# Pure-Java AST: phase 1 report

## Summary

Phase 1 ported the core of AST to pure Java on 12 November 2026, in about ten hours of porting by AI agents and two more of benchmarking: 28 jniast classes with jniast's public API, in 72,840 lines of main code and 46,573 lines of tests.
The library reads the LSST FrameSet, native dumps and FITS-WCS headers, SIP headers included, and transforms pixel to sky and back with results that match the C library within the oracle files' tolerances.
Of the C oracle fixtures, 69 of 139 FITS headers, 109 of 178 FrameSets and 426 of 833 Mappings match; every other fixture is deferred for a named class, encoding or method that is outside phase 1.
Taking behaviour from C where the C library and the Rust port disagree turned up 65 differences between them, recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md).
On large arrays, transforms through FrameSets and Mappings from real data take 1.1 to 2.6 times as long per point as in the Rust port; a call with one point is often faster than Rust's.
The largest gaps are the simplifier, spectral axes, FitsChan writing and the FITS encodings other than FITS-WCS and NATIVE.
The largest risk found is that, without the simplifier, setting attributes through a FrameSet of several Frames grows it without bound, which must be fixed before any release to SPLAT, TOPCAT or Firefly users (see Gaps and risks).
Tests whose guards assumed this Mac's `Math` failed on x86-64 CI; the guards were changed so that they hold on any runtime, and CI has not yet run at the head of the branch, which needs a push.
Scaled from phase 1's rate by Rust source size, the later phases that have a Rust port come to about 7.6 hours of the same kind of work, with a range of 2.6 to 15.9 hours from the spread of per-task rates; the simplifier, SpecFrame and FitsChan writing are not expected to scale linearly, and Plot, XmlChan and YamlChan have no Rust port to scale from.

## Outcome against the success criteria

The spec's first-phase success criteria ([design](../superpowers/specs/2026-11-02-pure-java-ast-design.md), "First-phase success criteria"):

| Criterion | Result |
| --- | --- |
| 1. Every jniast public member of the in-scope classes exists | Met: `JniastApiTest` checks the 549 jniast members of the 28 classes against `src/test/resources/jniast-api.txt`; 5 members tied to JNI (`pointer`, `finalize()` and `getAstConstantI`) are excluded with reasons in `jniast-api-exclusions.txt`; and every public member jniast lacks is listed with its reason in `jniast-api-additions.txt` (26 status codes of the newer C library and the HPX and XPH projection codes). |
| 2. `lsst.ast` forward and inverse match C | Met: forward within libm rounding; inverse within 4.5e-10 pixel, the Rust test's bound away from the platform C's values were captured on (see Tolerances). |
| 3. In-scope FITS headers, SIP included, match `headers.oracle` | Met for 69 of 139 headers, including `sip.head`, `sip2.head` and `sip-alt.head`, with 2 rows in each of two CAR headers deferred as needing simplification; the other 70 headers need SpecFrame, MathMap or another encoding. |
| 4. In-scope sections of `framesets.oracle` and `simplify_fixtures.oracle` match | Met: 109 of 178 and 426 of 833 fixtures. |
| 5. In-scope native fixtures round-trip | Met: 563 of the 1,053 native fixtures round-trip; the other 490 hold out-of-scope classes. |
| 6. A written report | This document. |

The 12 public methods that need out-of-scope work keep their jniast signatures and throw `UnsupportedOperationException`: the 11 `resample*` and `rebin*` methods of `Mapping`, and `FitsChan.write`.
`FitsChan.read` throws it for a header whose encoding is not FITS-WCS or NATIVE, or that needs an out-of-scope class, with a message naming what is needed.

## What was ported

Every class is ported from the Rust port (`reference/starlink-ast-rs`, commit `10f61689`), with behaviour checked against the C library (`reference/starlink-ast`, commit `3d3f255f`).

| Area | Main files | Main lines | Contents |
| --- | ---: | ---: | --- |
| Core | 7 | 2,917 | `AstObject`, `AstException`, `Ast`, the attribute layer, C number formatting and scanning (`CFormat`, `CScan`), `ObjectRegistry` |
| Channel and native serialisation | 6 | 2,404 | `Channel`, native reading and writing |
| KeyMap | 5 | 2,374 | `KeyMap` and its table, sorting and conversions |
| Mapping algorithms | 11 | 3,818 | `Mapping`, `MapSplit`, `MapBox`, `LinearApprox`, `Rate`, `TranGrid`, the resampling types |
| Simple Mappings | 9 | 4,339 | `UnitMap`, `ShiftMap`, `ZoomMap`, `WinMap`, `MatrixMap`, `PermMap`, `CmpMap`, `SphMap`, `WcsMap` |
| PolyMap | 2 | 1,812 | `PolyMap` with its iterative inverse, `PolyTran` fitting |
| SlaMap and TimeMap | 3 | 2,514 | `SlaMap`, `TimeMap` and its Fairhead table |
| Units | 13 | 2,638 | AST's units parser, simplifier and mapper |
| Frames | 11 | 14,001 | `Axis`, `SkyAxis`, `Frame`, `SkyFrame`, `TimeFrame`, `CmpFrame`, frame matching, date parsing, sky conversion |
| FrameSet | 1 | 2,900 | `FrameSet` |
| FitsChan | 21 | 11,512 | Cards and cursor, encoding detection, NATIVE reading, FITS-WCS reading (SIP and the other polynomial distortions included) |
| `internal.erfa` | 79 | 13,474 | The ERFA subset AST uses |
| `internal.pal` | 27 | 1,674 | The PAL subset AST uses |
| `internal.wcslib` | 12 | 4,994 | wcslib's projections |
| `internal.cminpack` | 8 | 1,469 | The Levenberg-Marquardt solver PolyTran uses |
| Total | 215 | 72,840 | |

The tests are 217 files and 46,573 lines: the Rust port's tests translated to JUnit 5, the C oracle and probe checks, and the deferred-list gates.
The build runs 2,284 tests.

## Time and method

The design was written on 2 November and the plan on 4 November; neither is counted here.
All 25 tasks of the plan ran on 12 November 2026, implemented by AI agents (Claude).
Tasks 1 to 4 were done by the controlling agent; from Task 5 each task was given to one implementer subagent, which in Tasks 11, 18, 20 and 23 used helper subagents for parts of the work, and the controller re-ran `mvn verify` and checked the diff and rulings of each before recording it in the ledger.
Tim's review is not counted.

The commits on the branch give these times (local time, UTC-7); a task's time is from the previous task's last commit to its own last commit, and Task 1 started after `main`'s last commit at 09:33.

| Task | Content | Finished | Minutes |
| --- | --- | --- | ---: |
| 1 | Maven project, test helpers, jniast API check | 09:43 | (≤ 10) |
| 2 | Probe data converter | 09:48 | 5 |
| 3 | AstException, status codes, C number formatting | 09:55 | 7 |
| 4 | AstObject and attributes | 10:04 | 9 |
| 5 | Channel, native serialisation, KeyMap | 10:35 | 31 |
| 6 | Mapping, UnitMap, ShiftMap, ZoomMap | 10:53 | 18 |
| 7 | WinMap, MatrixMap, PermMap, CmpMap | 11:22 | 29 |
| 8 | Shared-object immutability, generic Mapping algorithms | 11:58 | 36 |
| 9 | Transform-oracle and serialisation gates | 12:14 | 16 |
| 10 | ERFA and PAL | 12:42 | 28 |
| 11 | wcslib, WcsMap, SphMap | 13:16 | 35 |
| 12 | cminpack, PolyMap, PolyTran | 13:52 | 35 |
| 13 | SlaMap and TimeMap | 14:13 | 22 |
| 14 | Units | 14:41 | 27 |
| 15 | Axis and Frame | 15:20 | 40 |
| 16 | FrameSet | 15:48 | 28 |
| 17 | Frame matching and conversion | 16:12 | 24 |
| 18 | SkyAxis and SkyFrame | 16:53 | 41 |
| 19 | TimeFrame | 17:21 | 28 |
| 20 | CmpFrame, FrameSet integration | 17:58 | 37 |
| | `docs/rust-c-differences.md` | 18:08 | 10 |
| 21 | FitsChan cards | 18:35 | 28 |
| 22 | FitsChan read dispatch, NATIVE reading | 18:58 | 23 |
| 23 | FITS-WCS reading | 19:42 | 44 |
| 24 | Benchmarks | 21:57 | 135 |
| 25 | API gate and this report | | |

Porting (Tasks 1 to 23) took 9 hours 59 minutes from Task 1's commit; benchmarking took 2 hours 15 minutes, of which two JMH runs took 56 minutes each.

The work followed the plan's rules: tests first (each task recorded a failing run before porting, except Tasks 6 and 11, checked instead with mutants and with stubs swapped back in), expected values only from C, every deferred item named with its reason and checked to still fail, and every disagreement between C and Rust resolved by reading both.
The ledger `.superpowers/sdd/2026-11-04-pure-java-ast-phase1/progress.md` records each task's rulings.

## Tests and runtimes

| Runtime | Platform | Tests | Result |
| --- | --- | ---: | --- |
| OpenJDK 21.0.13 (Homebrew), `mvn verify` | macOS aarch64 | 2,284 | All pass |
| OpenJDK 27.0.1 (Homebrew), `mvn test -Djvm=...`, at `7be4d84` | macOS aarch64 | 2,257 | All pass |
| Temurin 21 (CI `build`), at commit `b5404cf` (Task 21) | Linux x86-64 | 2,012 | 1 failure: `WcslibGoldenTest` |
| Temurin 8 (CI `runtime`), at `b5404cf` | Linux x86-64 | 2,012 | 1 failure: `WcslibGoldenTest` |
| Temurin 25 (CI `runtime`), at `b5404cf` | Linux x86-64 | 2,012 | 2 failures: `WcslibGoldenTest`, `PalTest.fk54zSliceGivesEachElementTheSameBitsWhateverItsLength` |
| Temurin 21, 8 and 25 (CI run 43376418963), with some of the review fixes | Linux x86-64 | 2,276 | `WcslibGoldenTest` on all three; `PalTest.fk45zSliceGivesFk45zOutsideTheKernelDomain` on 25 |

No Java 8 runtime is installed on the Mac, and installing one needs Tim's agreement, so Java 8 has run only in CI.
CI has not yet run at the head of the branch, since that needs a push.

The JDK 27 run adds little evidence: it ran on the same machine, and every point `WcslibGoldenTest` lists still differs from C there as it does on JDK 21.
The CI failures were all in the tests, not the library, and the review's fix pass changed the tests:

- `WcslibGoldenTest` lists 23 points whose outputs differ from the C values by more than libm rounding on the Mac (see Tolerances), and failed if a listed point came within libm rounding.
  On x86-64, 12 to 14 of the 23 points agree with C within libm rounding: there, `Math` gives values at those points within libm rounding of the C values captured with Apple's libm; no point exceeded its wider bound.
  Each listed bound is now an upper bound only, since whether a point needs it depends on the runtime's `Math`, which is not C's libm and differs between platforms.
- `PalTest`'s slice tests compared raw bits, NaN payloads included; on Java 25 two identical calls returned `0xFFF8000000000000` (the x86 default NaN, sign bit set) and `0x7FF8000000000000` (Java's canonical NaN), as the JIT compiled the method, first in `fk54zSliceGivesEachElementTheSameBitsWhateverItsLength` and in the later run in `fk45zSliceGivesFk45zOutsideTheKernelDomain`.
  Java does not specify the sign or payload of a NaN from arithmetic, so where the expected value is NaN the tests now require only a NaN, and compare every other value bit for bit.
- The two inverse CAR-seam rows of `oracle-rows.txt` are now marked as depending on the runtime's `Math`, so that the gate does not fail if they match C on another runtime; the forward rows come from multiplications alone and keep the check.
  `SkyConvertTest`'s check that some excused case still differs is kept, since the excused differences come from C's merged conversions, far beyond libm rounding.
  Both passed on x86-64 with Java 8, 21 and 25 in the later CI run.

## Problems found

### Differences between C and Rust

[`docs/rust-c-differences.md`](../rust-c-differences.md) records 65 behaviours in which the Rust port differs from the pinned C library, plus a list of 9 differences in the units code, each with the C source line, the Rust file and what Java does.

| Area | Entries |
| --- | ---: |
| Errors and formatting | 1 |
| Channel and native serialisation | 7 |
| Mappings | 22 |
| Units | 1 section of 9 items |
| Frames | 8 |
| FitsChan | 26 |
| Test harness | 1 |

Java follows C in 56 entries and Rust in 9; in 3 of the 9 both ports differ from C (infinite inputs, Full dumps without `RefCnt` and `Nobj`, and values that are not finite in a PolyMap, which C makes bad only when `AST_REPLACE_NAN` is set).
10 entries are faults of C itself (marked **C quirk**), such as `ClearAttrib` testing for "prpjp" instead of "projp" in `wcsmap.c`, and a NATIVE read rounding floating values through `%.17g` and `CheckZero`.
Task 23, FITS-WCS reading, added 18 of the 26 FitsChan entries, more than any other task.
The ledger records no case where a gate, rerun after a class was added, found a bug in an earlier task's port.

### Items flagged for review

These were flagged in the ledger for this review, or are gaps in behaviour against C that a user could meet.

- **No simplifier.**
  `Mapping.simplify()` returns a Mapping that transforms identically without restructuring it, as the spec decided.
  C simplifies the Mappings it builds while reading a header, adding a Frame and changing a FrameSet attribute, so dumps differ from C's, 4 oracle rows at the CAR seam differ (see Deferred), and transforms through a FrameSet read from a header do more work than C's.
  Setting or clearing a Frame attribute through a FrameSet with more than one Frame remaps the current Frame with `CmpMap(UnitMap, UnitMap⁻¹)` where C's simplification gives a UnitMap, so each such setting adds a layer to the FrameSet (Task 17).
  The review measured this on `galactic-tan.head`: 200 calls of `fs.setC("Label(1)", ...)` grew the dump from 13.7 KB to 6.6 MB and a single-point `tran2` from 3.6 µs to 213 µs.
- **Duplicate WCS keywords** (fixed in the review's fix pass).
  The FITS-WCS read now gives each keyword the value C's `WcsFcRead` stores: of the cards for one keyword, the last whose value converts, a later card that does not convert being passed over; `error-bad-radesys` now runs against C's result.
- **AST__BADIN** (fixed in the review's fix pass).
  A dump value that `sscanf` cannot read whole as the number a loader asks for, or a double that is not finite, now raises AST__BADIN with C's message, and a failed loader leaves the rest of the Object for the next read, as C does; the four probe cases `string_for_double`, `string_for_int`, `native_bad_value` and `native_bad_value_clean` now run against C's results.
- **Malformed input** (checked in the review's fix pass).
  A corrupt native dump or header raises an `AstException` with a status, not another exception: an integer no `int` holds is AST__BADIN (C's `sscanf` result is undefined); a SkyFrame dump with one axis is AST__AXIIN, as C reports it; FrameSet Frame, node and link indices, SphMap coordinate counts and axis permutations that the rest of the dump does not support are AST__BADIN when read, where C reads or writes beyond its arrays or crashes; a failed FrameSet integrity restore keeps its status (C's AST__MTRMT for an infinite SkyRef) where it threw `IllegalStateException`; and a header with more than 99 WCS axes is AST__INTER, as C's FitsStore reports it, where the port ran out of memory.
  A reviewer's fuzzer that changes values in the native fixtures found no other exception in 60,000 reads.
  One case is left: a count an `int` holds but no memory does, such as `Naxes = 2147483647`, makes C try to build that many Axes (the pinned library was still running after 30 seconds) and Java run out of memory; turning it into an AstException needs either a limit C lacks or C's AST__NOMEM for a failed allocation, which is a decision for the next phase.
- **Infinite inputs.**
  The plan's rule treats a non-finite input as bad, where C tests only for AST__BAD; this affects infinite inputs only (Task 6).
- **Round-trip excuse.**
  The transform-oracle gate excuses the rows where C's own recorded inverse does not recover the input, as the Rust test does; C's checker has no such excuse (Task 9).
- **SkyFrame conversions.**
  `SkyConvertTest` excuses 168 of 1,584 conversions, each only where its SlaMap holds a pair of conversions that C's `MapMerge` cancels or merges, and only within 5e-8 rad of C, where the largest measured difference is 1.97e-8 rad (Task 18).
- **Tolerances measured on one platform.**
  `WcslibGoldenTest`'s list, the LSST inverse bound and the 4 CAR-seam rows were measured on aarch64 HotSpot; the guards that assumed those measurements hold everywhere were changed (see Tests and runtimes).
- **Shared objects.**
  C raises AST__IMMUT when an attribute such as ZoomMap's `Zoom` is set on an object whose reference count exceeds 1; Java marks an object as shared when a container takes it by reference and clears the mark on `copy()`, so an object stays immutable after its holder is garbage, where C's count would fall back to 1 (Tasks 7 and 8).
- **Per-call cost of `tranP`.**
  `Mapping.tranP` costs a median of 130 ns per call more than the internal NaN-protocol path on JDK 21 (100 ns on JDK 27), in its argument checks, attribute reads and allocation of the result; this dominates every Mapping that is cheap per point (see Benchmarks).

### Problems in the process

- The implementers of Tasks 6 and 11 wrote code before tests; the controller checked Task 6 with mutants, and Task 11 reconstructed its failing run by swapping stubs back in.
- In Task 20, five edits by helper subagents were lost when their work was merged, and were found and re-applied before the commit.
- Task 22's ruling that a failed foreign read leaves the card cursor where it is was reversed by Task 23 after reading `fitschan.c` again.
- Task 11 added the FITS projection code "coo" to the shared `.codespellrc`; Tim approved it afterwards.
- The plan was wrong in small places that the tasks corrected and recorded, such as jniast's class count (67, not 68), jniast's exception message, and attribute values keeping the spaces after `=` as C's `VSet` does.

## Tolerances

By default, transforms are compared with the tolerances each oracle file states (relative and absolute 1e-7 for `headers.oracle`, `framesets.oracle` and `simplify_fixtures.oracle`), probe values with `CValues.cValueEq` (equal, or within 1e-13 relative plus 1e-14 absolute, called libm rounding here), and native dumps with numbers within 4 units in the last place.
The following are wider than that, or excuse values:

| Where | What | Measured size and cause |
| --- | --- | --- |
| `WcslibGoldenTest` | 18 points with a wider bound (1e-13 to 1e-6), 5 compared by status only | Up to 2.8e-7 relative (COP, 1e-8 from the cone's apex); a one-ULP difference between `Math` and Apple's libm (`sin` near the poles, `tan` and `log` in COO's and COP's set-up constants) is amplified by cancellation; the 5 status-only points have native longitude -DBL_MAX, where a few ULP in COO's constant change the angle by about 1e292 degrees |
| `LsstOracleTest` | Inverse within 4.5e-10 pixel absolute, the Rust test's bound away from the capture platform | Largest measured 4.49e-10; PolyMap's iterative inverse stops once a step is below TolInv (1e-7 pixel), so a last-bit difference in a trigonometric function moves where it stops |
| `TransformOracleTest` | Round-trip rows excused for 5 fixtures: `tnx-cheb.head` and `tnx-cheb-head.ast` (all 24 rows), `tsc.head` and `tsc-head.ast` (row 10), `total_hi.head` (row 3) | C's own recorded inverse does not recover these inputs: tnx-cheb's sky-to-pixel values are bad in every row, the TSC image is wider than the quad-cube period, and `total_hi` is an all-sky CAR header whose corner pixel does not recover |
| `SkyConvertTest` | 168 of 1,584 conversions excused, each within 5e-8 rad of C | C's `MapMerge` cancels or merges a pair in the SlaMap; needs simplification; largest measured 1.97e-8 rad |
| FITS-WCS property tests | Rust's property tolerances kept (CRPIX to CRVAL 0.01 degree, round trips 1e-6, SIP replacement under 1 pixel, PolyTran fit 0.02 pixel) | No C value exists for these properties |
| `SkyFrame` tests | Vernal equinox within Rust's 1e-6; a cast Frame's Unit not checked | Rust-origin expectations with no C value |
| Structure checks | A chain of UnitMaps accepted where C gives one UnitMap | C's simplification |

Some checks are exact and so are sensitive in the other direction: `TranGridOracleTest` compares C's FNV hashes of three grids exactly, the 18 arithmetic-only ERFA and PAL routines are compared bit for bit, and `PalTest`'s slice tests compare bits exactly, except that an expected NaN need only be matched by a NaN.

## Deferred tests, fixtures and oracle rows

Each list in `src/test/resources/deferred` is checked both ways: a listed item must still fail with the reason given, and an item not listed must match C.
`rg UNEXPLAINED src/test/resources/deferred` finds nothing.

Rust tests not translated (`rust-tests.txt`, 458):

| Reason | Tests |
| --- | ---: |
| `astEqual` (not ported: nothing in scope calls it) | 101 |
| Rust-only API (PointSet, `AnyObject`, Debug formatting, call counts and the like) | 64 |
| Needs simplification | 59 |
| Hardware SIMD | 47 |
| Spectral classes (SpecFrame 38, others 8) | 46 |
| Other out-of-scope Mappings (MathMap 20, GrismMap 12 and others) | 74 |
| FitsChan writing or other encodings | 39 |
| Regions (including `astNormBox`) | 25 |
| Other out-of-scope methods (`astQuadApprox`, `astShowFits`) | 3 |

Fixtures (each reason counted under the first match of spectral class, Region, other encoding, simplification, other class):

| List | Total | Pass | Deferred | Spectral | Regions | Other classes | Other encodings | Other |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `headers.txt` (`headers.oracle`) | 139 | 69 | 70 | 39 | 0 | 2 | 29 | |
| `framesets.txt` (`framesets.oracle`) | 178 | 109 | 69 | 63 | 1 | 5 | 0 | |
| `simplify.txt` (`simplify_fixtures.oracle`) | 833 | 426 | 407 | 72 | 120 | 215 | 0 | |
| `serialisation.txt` (round trip) | 1,053 | 563 | 490 | 134 | 125 | 231 | 0 | |
| `full-dumps.txt` (Full = 1 dumps) | 1,014 | 538 | 476 | 138 | 123 | 215 | 0 | |
| `read-warnings.txt` (read warnings) | 1,170 | 675 | 495 | 138 | 125 | 232 | 0 | |
| `wcsconv.txt` (`wcsconv/cases.txt` rows) | 194 | 0 | 194 | 30 | 0 | 2 | 28 | 87 need `FitsChan.write`, 47 need simplification |

The other encodings among the headers are FITS-IRAF 10, FITS-PC 8, FITS-AIPS 7 and FITS-CLASS 4.
No row of `wcsconv/cases.txt` can run yet: each either writes its output through `FitsChan.write`, or holds a FrameSet whose Mapping C simplified.

Oracle rows (`oracle-rows.txt`, 4): row 1 of `wcsconv/inputs/car6.head` and of `programs/joye_car_headers/CHIPASS_Equ.head`, in both directions.
Pixel 5401 is 180 degrees from CRPIX1; without simplification, CDELT1 and then the degree-to-radian scale give a native longitude of -180-1.14e-13 degrees, beyond `wcsmap.c`'s 1e-13 tolerance for CAR, so the point is bad, whereas C's merged WinMap gives -180-8.5e-14.
The inverse row is the same seam the other way; which side of the seam it reaches follows the sign of a near-zero value computed with `Math`'s sine and cosine, so these two rows are marked as depending on the runtime and the gate does not fail if they match C.
No row is deferred for a fused multiply-add, which the spec expected.

## Benchmarks

Task 24 timed 78 cases (39 fixtures, forward and inverse) with JMH, on the objects and points of the Rust port's `ast-bench`; [`bench/README.md`](../../bench/README.md) has the method and the 34 cases left out for classes outside phase 1.
The machine was an Apple M1 Ultra with 64 GB of memory under macOS 27.2.1, otherwise idle.
The JVMs were OpenJDK 21.0.13 and OpenJDK 27.0.1 (Homebrew); Rust was `reference/starlink-ast-rs` at `10f61689`, built by rustc 1.99.1 in its release profile.
`tranP` is the public `Mapping.tranP`, which allocates its result on every call and is compared with Rust's one-array-per-axis timing (`rust-cols`); the NaN protocol is the internal path into buffers allocated once, compared with `rust-nan`.
JMH's figures are means of three one-second iterations after two of warm-up; Rust's are medians.
The FrameSets are native dumps written by C, whose Mappings C had already simplified, so the timings do not show the cost of the unsimplified Mappings this port builds when it reads a header itself.

Geometric mean over all 78 cases of the Java time divided by the Rust time:

| Timing | Points | JDK 21 | JDK 27 |
| --- | ---: | ---: | ---: |
| `tranP` / `rust-cols` | 1 | 1.89 | 1.50 |
| `tranP` / `rust-cols` | 1,000,000 | 1.72 | 2.16 |
| NaN protocol / `rust-nan` | 1 | 0.89 | 0.80 |
| NaN protocol / `rust-nan` | 1,000,000 | 1.41 | 1.43 |

Over the 20 real-world cases (the 10 FrameSets and Mappings from real data below, both directions), the ratio at 1,000,000 points is 1.12 to 2.60 for `tranP` on JDK 21 and 1.08 to 1.91 for the NaN protocol; at one point `tranP` is faster than Rust in 9 of the 20 cases on JDK 21 and 15 on JDK 27.

Nanoseconds per point, one point per call:

| Case | Dir | tranP 21 | tranP 27 | Rust cols | NaN 21 | NaN 27 | Rust nan |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `wcsconv/framesets/dss-ast.ast` | fwd | 2437 | 2034 | 2095 | 1930 | 1677 | 2088 |
| `wcsconv/framesets/dss-ast.ast` | inv | 1877 | 1488 | 1860 | 1408 | 1137 | 1849 |
| `wcsconv/framesets/lsst-ast.ast` | fwd | 4880 | 4184 | 7634 | 3266 | 2888 | 7616 |
| `wcsconv/framesets/lsst-ast.ast` | inv | 11727 | 10159 | 15108 | 10371 | 9119 | 15113 |
| `wcsconv/framesets/sao-tan-head.ast` | fwd | 2553 | 2180 | 2173 | 2078 | 1862 | 2167 |
| `wcsconv/framesets/sao-tan-head.ast` | inv | 1794 | 1444 | 1728 | 1340 | 1104 | 1720 |
| `wcsconv/framesets/sip2-head.ast` | fwd | 3291 | 2386 | 3835 | 2286 | 1825 | 3830 |
| `wcsconv/framesets/sip2-head.ast` | inv | 5958 | 4686 | 7086 | 5159 | 4067 | 7089 |
| `wcsconv/framesets/tnx-cheb-head.ast` | fwd | 4069 | 3580 | 3206 | 3364 | 3191 | 3204 |
| `wcsconv/framesets/tnx-cheb-head.ast` | inv | 2545 | 2041 | 2330 | 1976 | 1656 | 2309 |
| `wcsconv/inputs/dss.ast` | fwd | 2673 | 2029 | 2102 | 1922 | 1683 | 2089 |
| `wcsconv/inputs/dss.ast` | inv | 1852 | 1486 | 1870 | 1403 | 1128 | 1858 |
| `wcsconv/inputs/lsst.ast` | fwd | 4853 | 4195 | 7643 | 3370 | 2867 | 7621 |
| `wcsconv/inputs/lsst.ast` | inv | 11998 | 10143 | 14263 | 9752 | 8913 | 14235 |
| `wcsconv/inputs/timj.ast` | fwd | 2672 | 2030 | 2611 | 2026 | 1572 | 2607 |
| `wcsconv/inputs/timj.ast` | inv | 2607 | 1993 | 2769 | 1951 | 1520 | 2770 |
| `simplify/lsst.map` | fwd | 2102 | 1663 | 1545 | 1810 | 1406 | 1059 |
| `simplify/lsst.map` | inv | 4282 | 3687 | 8130 | 4316 | 3702 | 7727 |
| `simplify/lsst1.map` | fwd | 599 | 421 | 251 | 405 | 279 | 183 |
| `simplify/lsst1.map` | inv | 596 | 421 | 251 | 404 | 277 | 183 |
| `zoommap_2d.map` | fwd | 147 | 104 | 33.2 | 13.9 | 13.4 | 21.4 |
| `cmpmap_series.map` | fwd | 291 | 207 | 93.8 | 137 | 94.0 | 69.5 |
| `wcsmap_tan.map` | fwd | 184 | 138 | 100 | 61.6 | 60.8 | 76.4 |
| `slamap_fk45z.map` | fwd | 216 | 159 | 185 | 139 | 83.8 | 183 |
| `polymap_2d_deg5.map` | fwd | 299 | 247 | 135 | 157 | 151 | 125 |

Nanoseconds per point, 1,000,000 points per call:

| Case | Dir | tranP 21 | tranP 27 | Rust cols | NaN 21 | NaN 27 | Rust nan |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `wcsconv/framesets/dss-ast.ast` | fwd | 160 | 159 | 93.6 | 160 | 161 | 95.6 |
| `wcsconv/framesets/dss-ast.ast` | inv | 291 | 291 | 204 | 296 | 293 | 206 |
| `wcsconv/framesets/lsst-ast.ast` | fwd | 111 | 110 | 82.8 | 114 | 112 | 84.6 |
| `wcsconv/framesets/lsst-ast.ast` | inv | 397 | 379 | 351 | 383 | 408 | 353 |
| `wcsconv/framesets/sao-tan-head.ast` | fwd | 143 | 141 | 87.6 | 147 | 142 | 89.8 |
| `wcsconv/framesets/sao-tan-head.ast` | inv | 308 | 299 | 207 | 314 | 308 | 208 |
| `wcsconv/framesets/sip2-head.ast` | fwd | 92.9 | 91.1 | 62.6 | 95.6 | 93.9 | 64.6 |
| `wcsconv/framesets/sip2-head.ast` | inv | 259 | 251 | 217 | 258 | 247 | 219 |
| `wcsconv/framesets/tnx-cheb-head.ast` | fwd | 165 | 163 | 96.8 | 164 | 165 | 98.6 |
| `wcsconv/framesets/tnx-cheb-head.ast` | inv | 1268 | 1240 | 1004 | 1250 | 1249 | 1008 |
| `wcsconv/inputs/dss.ast` | fwd | 157 | 159 | 93.6 | 164 | 161 | 95.3 |
| `wcsconv/inputs/dss.ast` | inv | 295 | 287 | 204 | 295 | 291 | 206 |
| `wcsconv/inputs/lsst.ast` | fwd | 113 | 110 | 79.0 | 113 | 113 | 80.6 |
| `wcsconv/inputs/lsst.ast` | inv | 437 | 392 | 339 | 392 | 379 | 341 |
| `wcsconv/inputs/timj.ast` | fwd | 186 | 136 | 95.6 | 187 | 137 | 97.6 |
| `wcsconv/inputs/timj.ast` | inv | 287 | 254 | 154 | 299 | 257 | 156 |
| `simplify/lsst.map` | fwd | 112 | 111 | 82.2 | 107 | 103 | 81.6 |
| `simplify/lsst.map` | inv | 396 | 383 | 352 | 404 | 393 | 353 |
| `simplify/lsst1.map` | fwd | 7.01 | 7.01 | 2.69 | 5.04 | 5.09 | 3.46 |
| `simplify/lsst1.map` | inv | 6.96 | 6.97 | 2.72 | 5.00 | 5.06 | 3.48 |
| `zoommap_2d.map` | fwd | 1.52 | 1.94 | 0.51 | 2.48 | 2.51 | 1.76 |
| `cmpmap_series.map` | fwd | 3.74 | 3.74 | 1.01 | 3.66 | 3.52 | 2.02 |
| `wcsmap_tan.map` | fwd | 36.6 | 32.2 | 24.5 | 38.5 | 33.5 | 26.3 |
| `slamap_fk45z.map` | fwd | 105 | 52.2 | 46.7 | 107 | 54.4 | 48.9 |
| `polymap_2d_deg5.map` | fwd | 15.5 | 16.0 | 11.5 | 17.3 | 16.2 | 12.7 |

Every case and point count, including 1,000 and 100,000 points, is in `bench/results-jdk21.csv`, `bench/results-jdk27.csv` (the `:points` rows) and `bench/results-rust.txt`.
The cheapest Mappings are the slowest relative to Rust, since Rust vectorises their loops and `tranP`'s per-call cost is large beside their work; some timings at 1,000,000 points vary by a factor of 2 to 4 between runs, as `bench/README.md` describes.

## MODERN-JAVA tags

`rg -c 'MODERN-JAVA' src` counts 78 tags, 70 in the main code and 8 in the tests.
By the Java version named in the tag: 44 for Java 21 (mostly switch expressions over enums and sealed types, in place of `switch` statements and `instanceof` chains standing in for a Rust `match`), 18 for Java 16 (records), 13 for Java 9 (mostly where Rust has a branchless or vectorisable kernel), 2 for Java 10 and 1 for Java 19.

## Estimates for the later phases

### Method

The rate is phase 1's Rust source lines per hour of porting.
Rust lines are those of `reference/starlink-ast-rs/crates/starlink-ast/src`, including the unit tests inside the source files but not the separate `tests` directory, assigned to phases by file:

- Phase 1 (ported): `erfa`, `pal`, `wcslib`, `units`, `keymap`, `serial`, `vmath`, the top-level files, `channel` except `mocchan.rs`, `frames` except the spectral and flux Frames, the in-scope Mappings, and FitsChan's cards, encoding detection, NATIVE and FITS-WCS reading.
  This is 112,565 lines; less 1,186 lines of the in-scope Mappings' `map_merge` and merge helpers, which belong to the simplifier, it is 111,379 lines, of which a few hundred (SIMD kernels, call counting) were deliberately not ported.
- Simplification: `mappings/simplify.rs`, `matrix_merge.rs`, `winmap_merge.rs` and those 1,186 lines.
- FitsChan writing and other encodings: `fitschan/enc_*.rs`, `fset_to_store`, `wcs_write`, `wcs_decompose.rs`, and the two PolyMap helpers that only writing uses.
- Spectral: the four spectral and flux Frames, `specmap.rs` and `fitschan/wcs_spectral.rs`.
- Remaining jniast Mappings: LutMap, MathMap, TranMap, IntraMap, SwitchMap, SelectorMap, GrismMap, PcdMap, RateMap, DssMap, and `fitschan/wcs_tab.rs` (`-TAB` needs LutMap).
- Resampling: `mappings/rebin`, `mappings/resample`, `pixel.rs`.
- Regions and STC: `regions` except `moc.rs`.
- Classes not in jniast: ChebyMap, NormMap, SplineMap, UnitNormMap, XphMap, `quad_approx.rs`, Moc, MocChan, Table and FitsTable.
- Not counted: `coords` and `guide`, 1,026 lines of Rust-only API.

Porting took 9.99 hours (Task 1's commit to Task 23's), so the rate is 11,150 Rust lines per hour.
As a check, C lines give a second rate: the whole C files of the phase 1 classes (227,708 lines) plus wcslib (5,158) and cminpack (1,892), 234,758 lines in all, give 23,500 C lines per hour.
Those whole files include work phase 1 did not do (resampling in `mapping.c`, writing and other encodings in `fitschan.c`, each class's `MapMerge`), so the C rate overstates phase 1's throughput and the C estimates are low; the C phase 1 count leaves out ERFA and PAL, of which only subsets are used.

### Figures

Hours of agent work at phase 1's rate; the range applies the fastest and slowest per-task rates of phase 1 (below).

| Phase (the spec's order) | Rust lines | Hours | Range | C lines | Hours at the C rate |
| --- | ---: | ---: | --- | ---: | ---: |
| 1. Simplification | 8,918 | 0.8 | 0.3 to 1.7 | at least 7,897 | at least 0.3 |
| 2. FitsChan writing, other encodings | 16,872 | 1.5 | 0.5 to 3.2 | inside `fitschan.c` | |
| 3. Spectral and flux Frames, SpecMap | 9,703 | 0.9 | 0.3 to 1.8 | 22,161 | 0.9 |
| 4. Remaining jniast Mappings | 12,618 | 1.1 | 0.4 to 2.4 | 30,258 | 1.3 |
| 5. Resampling and rebinning | 4,428 | 0.4 | 0.1 to 0.8 | inside `mapping.c` | |
| 6. Regions and STC | 20,236 | 1.8 | 0.6 to 3.8 | 62,724 | 2.7 |
| 7. Plot | no Rust port | | | 41,174 | 1.8 |
| 8. YamlChan and XmlChan | no Rust port | | | 42,041 | 1.8 |
| 9. Classes not in jniast | 11,955 | 1.1 | 0.4 to 2.2 | 42,047 | 1.8 |
| Phases with a Rust port (1 to 6, 9) | 84,730 | 7.6 | 2.6 to 15.9 | | |

The C lines for simplification are the `MapMerge` and `Simplify` functions, with their prologues, of the classes phase 1 ported, without the helpers they call.
KeyMap, which the spec puts in phase 4, was done in phase 1.

### Uncertainty

Per-task rates in phase 1 varied sixfold: ERFA and PAL (14,906 Rust lines) took 28 minutes, 533 lines a minute, partly through generator scripts; wcslib with WcsMap and SphMap (13,569) took 35 minutes with four helper subagents, 392 a minute; Units (4,075) took 27 minutes, 150 a minute; FrameSet (3,103) took 28 minutes, 110 a minute; TimeFrame (2,493) took 28 minutes, 89 a minute.
Code that is a direct translation of numerical routines went fastest, and code with many C extension points, attribute rules and oracle cases to reconcile went slowest; most of the later phases are of the second kind.
The hours are of AI agents working with a controller, and do not include writing each phase's plan, Tim's review, or the C oracle data and Rust tests that phase 1 reused and that the later phases will also need.
Phase 1 also built the test harness, the gates and the core classes that every later phase uses, which favours the later phases.

The following are not expected to scale linearly:

- **The simplifier.**
  Its line count is small, but it changes the behaviour of code already ported: FrameSet integrity, FitsChan reading, `addFrame` and every dump comparison against C.
  It will make many deferred items runnable at once (47 wcsconv rows, 59 Rust tests, 4 oracle rows, 168 SkyFrame conversions), each of which may show a difference to resolve, and `Mapping.equal` (101 deferred tests) belongs with it.
  In the Rust port, 128 of 1,087 commits touched simplification and its first design was rebuilt around C's `MapMerge` (`reference/starlink-ast-rs/docs/porting-hazards.md`, H1, and `docs/issues/simplifier-mapmerge-rebuild-plan.md`).
- **FitsChan writing.**
  It needs the simplifier first, and its tests compare written header text with C's, card by card, where reading compared numbers within a tolerance.
- **SpecFrame.**
  It holds 39 of the 70 deferred headers, 63 of the 69 deferred FrameSets and 46 deferred Rust tests; adding it makes these runnable at once, through FITS-WCS reading code that has so far run only without spectral axes.
- **The MathMap parser.**
  An expression parser and evaluator, whose results must match C's through its own arithmetic.
- **Regions.**
  The Rust port's hazards (H9) record that its nine Region classes passed their tests in one day and needed a parity campaign twelve weeks later; phase 1 was itself a one-day sweep of this kind.
- **Plot, XmlChan and YamlChan.**
  The spec puts Plot after its Rust port, and no Rust port of the two Channels exists, so the C figures above are a rough guide only; Plot also needs a Graphics2D `Grf` written afresh, since jniast's `DefaultGrf` is GPL.

## Gaps and risks

For those deciding on the full port:

- Setting attributes through a FrameSet of several Frames grows it without bound, because there is no simplifier: on `galactic-tan.head`, 200 calls of `fs.setC("Label(1)", ...)` grew the dump from 13.7 KB to 6.6 MB and a single-point `tran2` from 3.6 µs to 213 µs.
  This must be fixed, with the simplifier or at least its UnitMap and CmpMap merging, before any release to SPLAT, TOPCAT or Firefly users, whose sessions set attributes repeatedly.
- The tests that failed on x86-64 CI (`WcslibGoldenTest`'s measured points and `PalTest`'s NaN bits) were changed to hold on any runtime, but CI has not yet run at the head of the branch, which needs a push.
  The LSST inverse bound was measured on aarch64 too.
- Without the simplifier, FrameSets read from headers carry longer Mapping chains than C's, dumps differ from C's, and four edge rows differ.
- Headers with a spectral axis, `-TAB`, `-LOG` on a non-spectral axis, or an encoding other than FITS-WCS and NATIVE cannot be read yet; about half of the header fixtures are of this kind.
- `tranP`'s per-call cost makes single-point calls on cheap Mappings several times slower than Rust's; real FrameSets are not affected much.
- The work so far is a fast breadth sweep; the Rust port's experience is that such sweeps leave debts the existing tests do not show.
