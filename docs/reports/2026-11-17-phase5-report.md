# Pure-Java AST: phase 5 report

## Summary

Phase 5 ported the remaining jniast Mappings on 17 November 2026, following the plan [`2026-11-17-pure-java-ast-phase5-mappings.md`](../superpowers/plans/2026-11-17-pure-java-ast-phase5-mappings.md).
LutMap, MathMap, TranMap, RateMap, GrismMap, PcdMap, SwitchMap, IntraMap (with the `Transformer`, `Transformer1` and `Transformer2` interfaces) and DssMap now exist with jniast's public API, `astEqual` is ported for every class that has an `Equal` method, and `JniastApiTest` passes for all of them; the only exclusions are jniast's implicit `DssMap()` constructor and IntraMap's finalizer.
`FitsChan.read()` and `write()` now handle `-LOG`, `-GRI`, `-GRA` and (as far as LutMap goes) `-TAB` axes, and `MakeSFMapping` is ported, so SpecFluxFrame converts between any components that C converts.
SPLAT's SpecFluxFrame conversions now work: phase 4 left every conversion but the equal-components one throwing `needs RateMap`, and the SPLAT-style FrameSets now give C's Mapping, simplified Mapping and values.
SelectorMap was moved to phase 6: its only constructor and every use take Regions.
Of the 6 rows of C's FITS conversion regression table that phase 4 left, 5 now give C's cards (the sixth, `lsst2_fits-wcs`, needs SplineMap), and the 4 transform-oracle headers that waited for MathMap and GrismMap now read and transform as C's do.
The deferred lists lost between a third and a half of their entries (table below), and the 106 Rust tests that waited for `astEqual` are translated.
Porting found a dozen places where the pinned C library behaves in a way worth reporting upstream, among them a TranMap simplification that reverses a transformation, a GrismMap merge that uses the wrong ZoomMap direction, and a SwitchMap whose own native dump changes its inverse when read back; they are recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md) (46 new entries) and listed below.
The user decided three points about IntraMap, which replaced the pre-flight design: I/O by function name only with no Java deserialisation, a static process-wide registry of weak references, and no lock around Transformer calls.
Against the branch base the work adds 10,321 lines of main code and 37,178 lines of tests, and removes 355 and 1,545; `mvn verify` passes with 3,796 tests on JDK 21, and the tests pass on the JDK 27 runtime.

## What was ported

| Task | What | Main lines | Test lines |
| --- | --- | ---: | ---: |
| 1 | `LutMap` (C's last-value cache, `MapMerge`, `Equal`, dump and loader) and the `-TAB` read as far as C's `GetNamedTable` | 833 | 2,475 |
| 2 | `MathMap` expressions and transformation: the compiler, every function, the random generators, `Equal`, dump and loader | 3,156 | 4,594 |
| 3 | `MathMap` `MapMerge` and `Equal`, and C's `astUnitMapper` for non-linear units | 163 | 1,622 |
| 4 | `-LOG` FITS axes read and written with C's MathMap (`FitsLogMap` deleted); the `-TAB` read gate | 55 | 1,017 |
| 5 | `TranMap` | 565 | 1,982 |
| 6 | `astEqual` for every class with an `Equal` method, and the Simplifier's recursion guard through it (inserted; see below) | 563 | 2,378 |
| 7 | `RateMap` | 373 | 2,096 |
| 8 | SpecFluxFrame conversions (`MakeSFMapping` after `MakeMap3`, `MakeMapF`, `MakeMapI`), SPLAT-style FrameSets | 88 | 7,664 |
| 9 | `GrismMap` and the `-GRI` and `-GRA` FITS read and write (`ExtractGrismMap`) | 1,056 | 2,132 |
| 10 | `PcdMap` | 778 | 3,524 |
| 11 | `SwitchMap`, and GrismMap's treatment of a non-finite input | 714 | 4,088 |
| 12 | `IntraMap`, `Transformer`, `Transformer1`, `Transformer2`, the registries, the `NeedsChannelizing` hooks in `Channel` and `FitsChan` | 1,300 | 1,199 |
| 13 | `DssMap` (loaded from dumps; no public constructor) | 734 | 2,243 |
| 14 | The remaining Rust tests, three carried items, the deferred sweep, the nested-series memory tests | 10 | 460 |
| 15 | This report | | |

Lines are those each task's commits add, the fix commits included; summed (10,388 and 37,474), they exceed the branch totals by the lines a later task changed again.
The test lines of Task 8 include a 566 KB probe output of full dumps.
Each of Tasks 1 to 14 was done by one implementer subagent, reviewed by a second, and verified in the controlling session before it was recorded (the full test suite, the deferred lists changing only by removal or by a more specific class reason, and the C source for each finding).
The reviews found one Critical problem (Task 12, below) and nine Important ones; every one was fixed in a fix round.
Tasks 2, 3, 5, 7, 9 and 10 were approved at the first review; Task 9's review left a minor that the controller re-graded as a fix-round item, because two tests compared a bad value with a bad value.
Expected values that no fixture or oracle held were taken from probes run against the pinned C library; the probe sources and their outputs are committed under `src/test/resources/probes` (`lutmap_probe`, `mathmap_probe`, `mathmap_simplify_probe`, `fitslog_probe`, `alt_offset_probe`, `tranmap_probe`, `equal_probe`, `simplify_input_probe`, `ratemap_probe`, `specflux_probe`, `grismmap_probe`, `pcdmap_probe`, `switchmap_probe`, `intramap_load_probe`, `dssmap_probe`, `polymap_cancel_probe`).
The tasks were committed between 10:27 and 23:08 on 17 November.

## Departures from the plan

The [rulings](2026-11-17-phase5-rulings.md) give each with its reason and cost; these change what later work builds on.

- **A new Task 6, `astEqual`, and a renumbering.**
  After Task 5 the controller inserted a task to port `astEqual`, and the plan's old Tasks 6 to 14 became 7 to 15.
  `ratemap.c` and `switchmap.c` call `astEqual` on the Mappings they hold, and a comparison of native text, which Task 5's `TranMap.equalTo` used, is a heuristic C lacks: ZoomMap(2) inverted equals ZoomMap(0.5) in C.
  The task also replaced the Simplifier's text comparison in its recursion guard with `astEqual`, as C has it, and removed 106 `astEqual` entries from `rust-tests.txt`.
  References in earlier ledger entries to "Task 7" (SpecFluxFrame) and "Task 13" (CmpMap `Equal`) mean Tasks 8 and 14.
- **SelectorMap moved to phase 6.**
  The spec lists it in item 4, but its only constructor and every use take Regions (spec item 6).
  The ten `test_switchmap.rs` tests whose forward selector is a SelectorMap of Boxes, and the three SelectorMap cases of `oracle/equal.json`, wait for it; so do the SwitchMap cases of `test_rate_oracle.rs`.
- **IntraMap's design changed after the review.**
  The pre-flight ruling had the Transformer written into `IntraFlag` in jniast's printable encoding wherever the IntraMap sat in a dumped object, and the loader decoding it at any depth.
  The Task 12 review found that this made reading any dump deserialise attacker-chosen classes, a Critical security finding: jniast's exposure was near zero because it channelizes only the top-level object and sits behind URITF registration.
  The user then decided three points, which superseded the pre-flight ruling:
  - IntraMap I/O is by function name only, as in C and Rust, with no Java deserialisation, and a code note marks where jniast's serialised-Transformer path would go (the user is "99% certain no-one has ever used a Java-only IntraMap in production");
  - constructing an IntraMap registers its Transformer by function name in a static process-wide registry of weak references, as C's `astIntraReg` table and jniast's `construct` do, so same-process round trips work (the per-read registry stays for C fixtures);
  - no lock is held around Transformer calls, and the Javadoc says `tranP` may be called concurrently and must be thread-safe (the review had found that jniast and C both serialise these calls, through `AstLock` and `LOCK_MUTEX2`).
  A later controller ruling made an identity-hash collision between two live Transformers take the next free name (`name#1`, ...) and not fail with `AST__MRITF`.
- **The `-TAB` read change belongs to Task 1.**
  LutMap arrives there, so Task 1 carries the read to C's next step, `needs FitsTable`, and Task 4 only checks it; the five `test_fitschan_tab.rs` tests are listed with `needs FitsTable`.
- **`FitsLogMap` is deleted.**
  C builds a MathMap for a `-LOG` axis and puts the same one in the returned Mapping, so a separate class would dump and simplify unlike C.
- **Phase 4's SpecFluxFrame rulings are undone.**
  Every conversion that phase 4 had throw `needs RateMap` now checks C's Mapping and values from a probe.
- **Java follows C where phase 2 and the Rust port restore an Invert attribute.**
  After a successful DssMap merge the WinMap keeps the list's Invert value, and after a cancellation the PolyMap does, as C leaves them (controller ruling); the PolyMap departure of phase 2 was brought into line in Task 14.
  Where C's `astSetInvert` leaves an attribute set on a neighbour, Java puts it back set or unset as it was, the library-wide convention, which only `test("Invert")` can see.
- **The `brad` row left `simplify-cases.txt`.**
  Task 1 gave it the reason `needs simplification` (C's CmpMap `Equal` changes a shared WcsMap's Invert); Task 6's review had the controller make `SimplifyRegressionTest` compare the input's dump after simplification with C's where a probe records it, which cleared the row without relaxing the shared gate.
- **Pinned C already contains fixes the Rust quirks file lists as open.**
  They are TranMap's `RemoveRegions` axis count (`bf819d17`), PcdMap's inverted-ZoomMap `PcdZoom`, LutMap's value beyond the first entry (`ee96dc91`) and IntraMap's null dereference on a Full dump with no `IntraFlag` (`4fe377c3`); Java follows the pin.
- **Task 14 had nothing to translate.**
  Every `rust-tests.txt` entry removed in the phase has a Java test of the same name; the task made three carried items, swept the lists, and listed four never-listed Rust files.
- **Nested-series memory tests run in a child JVM.**
  The brief suggested `ThreadMXBean` allocated bytes, which measures total allocation (192 MiB with batching against 224 MiB without), so the tests bound peak heap with `-Xmx88m` in a child JVM instead.

## Gates

| Gate | Before | After |
| --- | --- | --- |
| `wcsconv/cases.txt` rows giving C's cards | 188 of 194 | 193 of 194 |
| `wcsconv.txt` entries | 6: MathMap 3, GrismMap 2, SplineMap 1 | 1: SplineMap 1 (`lsst2_fits-wcs`) |
| `headers.txt` entries (transform oracle) | 4: MathMap 3, GrismMap 1 | 0 |
| `framesets.txt` entries | 10 | 3 (Box, Circle, PointList, PointSet, Prism; Box, PointSet; SplineMap) |
| `full-dumps.txt` entries | 365 | 197 |
| `read-warnings.txt` entries | 384 | 199 |
| `serialisation.txt` entries | 383 | 198 |
| `simplify-cases.txt` entries | 255 | 125 |
| `simplify.txt` entries | 356 | 191 |
| `rust-tests.txt` entries | 336 | 222, after adding 5 `needs FitsTable` entries (Task 4) and 39 never-listed entries (Task 14: 32 UnitNormMap, 1 Box, 1 NullRegion, 5 Rust-only allocation-counting tests) |
| `FitsChanCursorOracleTest` cases run | 257 | 257 (`alt_axes_alt_offset` now runs, licensed at libm level, and moved from `needs MathMap` to the runtime-Math exclusions) |
| Tests | 3,229 | 3,796 |

No entry of the lists still names LutMap, MathMap, TranMap, RateMap, GrismMap, PcdMap, SwitchMap, IntraMap, DssMap or `astEqual`; what remains names Regions (Box, Circle, Interval, PointList, PointSet, NullRegion, Prism, SelectorMap), UnitNormMap, NormMap, XphMap, ChebyMap, SplineMap, FitsTable, hardware SIMD or Rust-only API.
Per task, the deferred lists lost: Task 1 84 gate entries (and 6 Rust tests), Task 5 76 (`needs TranMap`) plus a narrowing, Task 6 106 `astEqual` entries, Task 7 86 (`needs RateMap`), Task 9 86, Task 10 127, Task 11 87, Task 12 40 (IntraMap fixtures, 10 in each of four lists), Task 13 31.
Rewriting the lists with `-Dast.writeDeferred=true` after the sweep changes nothing.
New C-probe suites: `LutMapTest` and `LutMapForwardOracleTest`, `MathMapTest` (probe `evaluate`, `simplify` and error cases), `FitsLogAxesTest`, `TranMapTest`, `EqualTest` (92 tests, with `oracle/equal.json`), `RateMapTest` and `RateOracleTest`, `SpecFluxFrameTest` (42 tests, native dumps of C's simplified Mappings), `GrismMapTest`, `PcdMapTest` (43 tests, every comparison exact), `SwitchMapTest`, `IntraMapTest` and `IntraMapOracleTest`, `DssMapTest` (23 tests).
Cases of earlier oracles that waited for these classes now run, among them the 1,088 SpecFluxFrame attribute cases that phase 4 skipped and the SpecFluxFrame cases of the `frame_class`, `frame_axis` and `integrity` oracles.
`mvn verify` passes on JDK 21 (3,796 tests, Checkstyle and PMD clean), and the tests pass on the JDK 27 runtime.

## Tolerances and excuses

Removed: none.
Added:

| Where | What | Measured size and cause |
| --- | --- | --- |
| `MathMapTest` library functions | 2 ULP in the evaluate case, 4 ULP in transformations and Gaussian sequences; bad, zero, integer and non-libm values exact | Last bits of `sin`, `log` and the like between Java's `Math` and macOS libm; not measured per value |
| `MathMapTest` Gaussian sequences | Exact `%.17g` comparisons that depend on the last bit of `Math.log` | Pass on JDK 21 and 27 |
| `MathMapTest` errno-trapped values | 22 probe values listed explicitly (`ERRNO_TRAPPED`, `ERRNO_CASES`): Java gives AST__BAD where the pinned macOS C gives an infinity | C's `CATCH_MATHS_*` traps need glibc's `errno`; macOS's libm only sets `MATH_ERREXCEPT` (ruling) |
| Units MathMaps and the FluxFrame log conversion | 1e-14 relative | Last bits of `exp` and `log10`; dumps, labels and simplification structure exact |
| `FitsChanCursorOracleTest` `alt_axes_alt_offset` | CDELT1B within 1.5e-15 relative, PC2_1B within 2.3e-15 absolute (licensed at libm level) | The simplified pixel to SKY_POLE Mapping dumps identically to C's; outputs differ by at most 2.2e-16 rad (1 ULP of a unit-vector component) on a 101 by 11 grid, through the same SphMaps and TAN WcsMap whose 1-ULP differences already license `alt_axes_offset`'s CDELT2; PC2_1B passes only because a PC element is compared relative to 1 |
| `FitsLogAxesTest` pixel coordinates | 1e-12 absolute (largest measured 3.4e-13); cards within libm rounding | CDELT1 differs from C's by 3 ULP (`...397` against `...402`): the fit uses the axis's `exp` and `log` values, of which `java.lang.Math` gives a different last bit from macOS libm for 13 and 20 of the 1,024 pixels; the inverse multiplies the 5e-16 relative error by the distance from CRPIX; reading C's cards reproduces C's pixels exactly |
| `FitsChanWcsWriteTest.GRISM_RTOL`, `FitsWcsSpectralTest` | 1e-8 relative, on rate-derived lines only (`pixel` lines, the WinMap's `Sft1` and `Scl1`, `CDELT` cards); every other line, cards included, within libm rounding | The grism read divides by C's numerical `astRate`; `Math.asin` and `Math.tan` differ from libm by 1 ULP (up to 32 near CRVAL), and the rate's interval choice amplifies that to 1.7e-10 and 8e-10 relative in the AWAV-GRA and nm cases (measured with a side probe) |
| `DssMapTest` transformations | Pixel outputs within `LIBM_RTOL` of the plate coordinate (about 4e-10 pixel); the right-ascension wrap checked to 2 ULP | `Math.tan` differs from macOS libm by 1 ULP at the fixture's plate declination; the wrap test guards against a two-pi-sized error the relative tolerance cannot see |
| `PcdMapTest`, `LutMapTest` probe comparisons | Exact | No elementary function is involved |
| `FitsChanWriteTest.NOT_ROUND_TRIPPED` `freq-log.head` | Listed as DIFFERS | C's own round trip gives GHz where the header gives Hz (see the C quirk below); Java writes the same cards |

## Defects found in the C library

All are in the pinned library (`3d3f255f`); "probe" means a probe against it showed the behaviour, "reading" that it follows from the code.
Each is recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md) with what Java does; Java follows C in every case that has a defined result, and gives a defined result where C's is undefined.
The first four are the ones most worth reporting upstream.

| Where | Behaviour | Confirmed |
| --- | --- | --- |
| `tranmap.c` `MapMerge` | A TranMap whose two components are the same Mapping inverted when the TranMap was made simplifies to that component applied forward: the else branch copies the Mapping with its invert flag but leaves `invert_list[where]` at 0, so TranMap(inv M, inv M) becomes M forward, and the transformation changes (forward 2.5 gives 1.58 originally and 6.25 simplified) | Probe (`simp_equal_inverted_mathmaps`, `simp_equal_inverted_lutmaps`); the reviewer confirmed it in the source |
| `grismmap.c` `CanMerge` | Takes a ZoomMap's factor from the ZoomMap's own Invert attribute and not from the list's value, so a GrismMap beside an inverted ZoomMap simplifies to a GrismMap that does not transform as the pair | Probe (three cases); the reviewer confirmed it |
| `switchmap.c` `Dump` and loader | `Dump` writes `ISInv` only when it is true and not helpful, so it is never written when 0; the loader takes an absent `ISInv` as `FSInv`; every native round trip of a SwitchMap with `FSInv` 1 and `ISInv` 0 therefore reloads with the inverse selector inverted: `astEqual` is false and the inverse of 1000 changes from 1 to 47.45 | Probe (`dump_fsinv_set_isinv_unset`); the review corrected the first wording, which blamed a hand-edited dump |
| `switchmap.c` `MapMerge` | Sets the Invert attributes back in the order it set them, so a Mapping the SwitchMap holds twice keeps the value recorded for its first place | Task 11's probe cases |
| `switchmap.c` `astInitSwitchMap` | The route-Nout error prints the input counts: "Route Mapping number 2 has 1 output(s) but the first route Mapping has 1 output(s)." | Probe (`error_route_nout`) |
| `cmpmap.c` `Equal` | The non-expanded branch changes the Invert attribute of a component that is the same object in both lists and leaves it at the first list's value, so a shared WcsMap in `brad.map` ends with Invert 0 (transformations agree, because `Transform` resets each component's Invert) | lldb trace and probe |
| `ratemap.c` `MapMerge` | A RateMap beside its inverse, in a series CmpMap that defines neither direction, becomes a UnitMap that defines both: UnitMap(2) for R then inv(R), UnitMap(1) for inv(R) then R | Probe (`cancel_two_inputs_upper`, `cancel_two_inputs_lower`) |
| `lutmap.c` `Transform` | Keeps the last input and output of each direction and returns the kept output for the same input after LutInterp has changed; a copy keeps them too | Probe (`cache`, `cache_copy`) |
| `lutmap.c` `MapMerge` | Compares the nominated LutMap with its own Invert attribute and not the list's value: in an inverted series CmpMap a LutMap and a copy of itself become a UnitMap, which changes the transformation, and a LutMap and its inverse are kept; a linear table whose ends are equal is neither replaced nor cancelled | Probe (`inverted_cmp_lut_lut`, `inverted_cmp_lut_lutinv`) |
| `mathmap.c` `Equal` | Does not compare the last opcode of each function (`y=x+1` equals `y=x-1`, `max` equals `min`) and takes an opcode count of 0, 1, 62 or 63 as a constant; for a one-opcode function such as `y=<pi>` it reads past the constants and segfaults | Probe (`equal`) and a scratch probe for the segfault |
| `mathmap.c` Gaussian generator | The saved second Gaussian value and its state are static variables shared by every MathMap, so interleaved calls on two MathMaps, or on a MathMap and its copy, change each other's values; Java keeps the value in each MathMap's own context | Probe (`random_copies`, `gauss_two_mathmaps`) |
| `mathmap.c` `Rand` | A Seed below 1 gives values far outside 0 to 1 (64-bit `long` plus `LONG_MAX`) | Reading, mirrored |
| `mathmap.c` loader | A dump with `Nfwd` below the number of outputs or `Ninv` below the inputs reads out of bounds; Java refuses it with `AST__INNTF` | Reading |
| `mathmap.c` documentation | The prologue lists the unary operators above `**`, but `EvaluationSort` binds `**` tighter, so `-x**2` is `-(x**2)` | Probe (`evaluate`); documentation only |
| `matrixmap.c` `Equal` | Expands and recompresses both MatrixMaps, which can zero negligible elements of a MatrixMap stored in full form, so a comparison changes its arguments; the Simplifier's guard reaches it through shared components | Reading |
| `zoommap.c` `Equal` | Compares the stored factor, which is zero after Zoom is cleared and has an infinite reciprocal, so a cleared ZoomMap compares unequal to ZoomMap(1) | Reading |
| `pcdmap.c` `Equal`, `MapMerge` | `Equal` uses `astEQUAL` (floor 1e-12) and `MapMerge` and `CanMerge` use the file's own `EQUAL` (floor `1e5*DBL_MIN`), so a Disco of 1e-13 equals zero in `astEqual` but is not reduced to a UnitMap; `Equal` of PcdMaps and GrismMaps compares stored values, so an unset value differs from one set to its default | Probe (`small_disco`, `equal/small_vs_zero_disco`) |
| `dssmap.c` `MapMerge` | After a successful merge the WinMap it merged with keeps the list's Invert value (C annuls its pointer before the restore) | Probe (`invert_attr_both_inverted`: 1 becomes 0) |
| `dssmap.c` `platepos` | Adds its own `twopi`, 6.28318530717959, to a negative right ascension, a little more than the double nearest to two pi, so a wrapped value is about four ULP larger | Probe (`tran_pltra_small`: the round trip through `platepix` is 2.4e-10 pixel short) |
| `intramap.c` `astLoadIntraMap` | Checks the numbers of coordinates as the inverted IntraMap reports them, so an inverted non-square IntraMap reads back as `AST__BADNI` | Probe (`intramap_load_probe.c`) |
| `intramap.c` `Transform` | Holds `LOCK_MUTEX2` around every call of a registered function; not a defect, but the one place Java departs by design (no lock) | Reading |
| `tranmap.c` `Decompose` | Leaves the series flag unset (its documentation says zero), so the caller gets back the value it passed | Probe |
| `fitschan.c` `SpectralAxes` | Changes Hz to GHz for a narrow spectral range: `IsMapLinear` rejects the Hz to GHz ZoomMap over [1420399999.49, 1420400000.51] Hz because the GHz values are rounded at about 2e-7 of their range, so a `-LOG` axis with a tiny increment is written as linear GHz; the caller's FrameSet keeps its Unit | lldb trace, probes `freq_log_head_unit` and `write_log_unit_unitmap` |

Rust-side findings, from the same work: Rust's `DssMap::try_merge` would, by reading, merge a WinMap on the wrong side (a forward DssMap followed by a WinMap), which C does not; Rust's `make_map*` for SpecFluxFrames raise `AST__BADUN` and skip the Domain check where C finds no conversion; Rust's `<dig>` of 15 is a Rust bug where C gives 17; and the Rust `c-library-quirks.md` wrongly lists GrismMap among the loaders that drop `IsSimp`.
That file belongs to a reference submodule and was not edited; the TranMap, RateMap, GrismMap, DssMap and gauss entries are candidates for it.

Kept as C has them, since they are defined: `astInitMathMap`'s `AST__BADNI` status for `nout < 1`; MathMap's NaN made inside an expression passing to the output; ZoomMap `Equal` comparing the stored factor; FrameSet `Equal` ignoring the FrameSets' Invert attributes; the route-Nout message of SwitchMap.
Where C's behaviour is undefined, Java defines it: a negative `Nlut` loads an empty table, a one-opcode function's constants past the end match only when neither function has them, `PermMap` constants past the end give the bad value, and a double outside `int` range converts as Java's cast does.

## Final review

The final review follows.
The controller fills this section in once the reviewer's report arrives.

## Gaps and risks

- **SelectorMap and Regions.**
  Phase 6 must port SelectorMap with Regions, SwitchMap's, TranMap's and RateMap's `RemoveRegions` (the pinned TranMap behaviour returns `astUnitMap( astGetNin( newmap1 ) )`), and translate the ten SwitchMap tests and three `equal.json` cases that wait for them.
- **FitsTable.**
  A `-TAB` axis reads as far as `needs FitsTable`, and the five `test_fitschan_tab.rs` tests are untranslated, though the first two assertions of `check_tab` could run now.
- **IntraMap reachability and threading.**
  A same-process read finds a Transformer through the process-wide table only while an IntraMap using it is reachable, and Java 8 has no `reachabilityFence`, so a round-trip test could in theory lose it first.
  Two threads constructing the first IntraMaps of one Transformer at once, with a lapsed name earlier in its name chain, could get two names.
  A Transformer that is not thread-safe gives wrong results when IntraMaps run on several threads, as the Javadoc says.
  `IntraRegistry.lapse` is package-private only for a test.
  A dump written in another process by jniast with a serialised Transformer reads back as a plain string and needs its Transformer registered.
- **MathMap's per-MathMap random state.**
  This is the one place Java deliberately gives different results from C for MathMaps (interleaved `gauss` calls on two MathMaps), because Review Focus 2 needs a copy to give its original's sequence; mirroring C needs a synchronised static.
- **Overflow trapping follows glibc.**
  An overflowing MathMap expression gives AST__BAD where the pinned macOS C gives an infinity; 30 expected values would revert if the ruling were reversed.
- **Tolerances above libm rounding.**
  GrismMap's rate-derived lines are compared to 1e-8 and a real regression below that would pass; `alt_axes_alt_offset` is licensed at 1.5e-15 relative.
- **Rust literals in equality tests.**
  The equality tests outside `EqualTest` (PermMapTest, CmpMapTest, WcsMapTest, PolyMapTest, MatrixMapTest, SpecMapTest, SpecFrameTest, TimeMapTest, TimeFrameTest, FrameEqualityTest, FluxFrameTest) still assert Rust's literals, though C's answers sit in `equal_probe.out` under the same names; those checked by eye agree, and `EqualTest.assertAsC` could take them.
- **Rust tests of earlier phases.**
  A name scan found tests with no Java test of a matching name and no list entry (the `fitschan` unit tests `enc_iraf`, `enc_pc`, `fset_to_store/*` and `wcs_write/mod.rs`, 31 non-gate tests of `test_simplify_regression.rs`, some FITS oracle gates, the vmath kernels); they are probably translated under other names or covered by gates, but this was not verified.
- **Minor items left.**
  `TranMap`'s constructor runs `count()` twice; `RateTest` and `TranGridTest` have ragged Javadoc; `PcdMapTest`'s NaN-protocol test treats bad and NaN interchangeably; `specflux_probe.out` is 566 KB of full dumps; the Rust repository's `c-library-quirks.md` has no gauss-statics entry.
- **Runtimes.**
  The tests were run on JDK 21 and on the JDK 27 runtime, not on a Java 8 runtime; the child-JVM memory tests use only Java 8 API and HotSpot options but were not tried there.

## Rulings

Every decision made while executing the plan where it was wrong, unclear or silent, every departure from Rust or C and every C quirk kept is in [`2026-11-17-phase5-rulings.md`](2026-11-17-phase5-rulings.md), with its reason and its cost if wrong: 181 rulings, 163 by the implementers and 18 by the controller and the user.
