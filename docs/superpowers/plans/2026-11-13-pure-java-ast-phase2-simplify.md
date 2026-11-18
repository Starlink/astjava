# Pure-Java AST Phase 2 (Simplification) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Mapping.simplify()` restructures Mappings as C's `astSimplify` does, for every class phase 1 ported, so that simplified dumps, FrameSets read from FITS headers, and FrameSets whose attributes are set match C.

**Architecture:** Port the Rust simplifier (`crates/starlink-ast/src/mappings/simplify.rs`, `simplify_entry.rs`, `winmap_merge.rs`, `matrix_merge.rs` and each class's `map_merge`/`try_merge`) into the existing classes, with C (`reference/starlink-ast/src`) as the final reference.
The engine (C's `MapList`, CmpMap's `Simplify` loop, the base `Simplify` and `astMapMerge`) comes first; each later task adds one family of classes' `MapMerge`.
C's 517-row simplify regression table (`ast_tester/fixtures/simplify/cases.txt`, each `.map` with C's simplified `.simp`) is the main gate, checked both ways through a deferred list as in phase 1.

**Tech Stack:** as phase 1: Java 8 (`--release 8`), Maven, JUnit 5, Jackson (test scope), Error Prone, Checkstyle, PMD, Spotless, JMH (benchmarks only); build with JDK 21.

**Spec:** `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md` ("Later phases", item 1).
Phase 1's plan, `docs/superpowers/plans/2026-11-04-pure-java-ast-phase1.md`, still governs everything this plan does not change; its Shared Design sections apply.

## Global Constraints

- Every Global Constraint of the phase 1 plan applies unchanged (Java 8, no runtime dependencies, jniast signatures, C at `3d3f255f`, `default` and final `else` throw `AST__INTER`, `MODERN-JAVA` tags, no heuristics the original lacks, expected values from C only, share where C calls `astClone` and copy where C calls `astCopy`, British spelling, one sentence per line in Markdown).
- The public API does not change: `simplify()` keeps its jniast signature, and every new method or class is package-private.
- Simplification does not change transform values beyond rounding (spec, "Decisions already made").
- Before porting, read Rust `docs/porting-hazards.md` H1 to H6 and `docs/issues/c-library-quirks.md` entries 557, 592, 679, 1594 and 1970.
  H1 is binding: each class's `MapMerge` lives in that class, never collapsed into shared helpers.
- The per-class audit checklists in `reference/starlink-ast-rs/docs/audits/mapmerge/<class>.md` map each C step to Rust lines; each task checks its class's list row by row and records any row it ports differently as a ledger ruling.
- Rust's tracing (`mod trace` in `simplify.rs` and its 13 call sites) is not ported.
- Branches for classes outside phase 1 (Region, GrismMap, PcdMap, NormMap, RateMap, SelectorMap, UnitNormMap, Prism's `map_list_decompose`) are not ported; each place a Rust or C merge reaches one gets a one-line comment naming the C function whose branch is absent, so the later phase can find it.
- The oscillation check (C `PatternCheck`, cmpmap.c:3055-3199) is a heuristic C has, so it is ported with its constants and a comment saying it is C's heuristic.
  No iteration cap is added: C and Rust have none.

## Review Focus

1. Simplifying never changes what a Mapping computes: every in-scope `.map` fixture, simplified, transforms the oracle inputs of `simplify_fixtures.oracle` to its recorded outputs within the file's tolerance (test in Task 1, run by every task).
2. Simplifying leaves the input as C leaves it: after `simplify()` the input's dump, apart from the `IsSimp` records C also writes on shared components, and its transforms are unchanged (test in Task 2).
3. Deep or oscillating input terminates: a series of 2,000 alternating `ZoomMap`s and their inverses simplifies to a `UnitMap` without `StackOverflowError`, and the oscillating `cap_*` regression rows finish (tests in Tasks 2 and 3).
4. A merge that meets an undefined transformation (`AST__NODEF`, `AST__TRNND`) leaves the Mapping unsimplified, as C's `astSimplify_` does, rather than throwing (test in Task 4, from `simplify_trnnd_oracle`).
5. Two threads, each simplifying its own copy of the `lsst.ast` FrameSet's Mapping, get dumps identical to a single thread's; the recursion guard (C's `simplify_stackmaps`) is per thread (test in Task 2).

---

## Shared Design

Decisions every task relies on.

### Where the code goes

- `MergeList.java` (package-private): C's `map_list` and `invert_list` pair, from Rust `MergeCtx` (`simplify.rs:504-747`) with its `restricted` flag, its first error, and the methods `replacePairWith`, `replaceRangeWith`, `removeAt`, `setPair`, `mergeSubrange`, `mayNominate`.
- `Simplifier.java` (package-private, static methods): `flatten` (C `MapList`), the nominate loop and `patternCheck` (cmpmap.c `Simplify`), `reconstruct`, `baseSimplify` (mapping.c `Simplify`), `dispatchMapMerge` (`astMapMerge_` with the `DoNotSimplify` guard and the AllowSimplify propagation), `defaultMapMerge`, and the per-thread recursion guard.
- `CmpMapMerge.java`: CmpMap's `MapMerge` and its helpers from `simplify.rs` (`cmp_map_merge`, `compute_merge_parallel_pair_in_series`, `dimension_match_parallel_lists`, `compute_merge_series_pair_in_parallel`, `swap_permmap_parallel_at`, `recombine_distributed_branches`).
- `WinMapMerge.java` from `winmap_merge.rs`, `MatrixMapMerge.java` from `matrix_merge.rs`, `WcsMapMerge.java` from the WcsMap helpers in `simplify.rs:2064-2358`.
- Every other class's merge goes in its own class file as overrides of the two hooks below, including the bodies Rust keeps in `simplify.rs` (ZoomMap 997-1155, PermMap 1164-1191, SphMap 3314-3420).

### Hooks on `Mapping`

```java
/** C's protected astMapMerge: returns the index of the first entry changed, or -1 for no change. */
int mapMerge(MergeList list, int where, boolean series)   // default: Simplifier.defaultMapMerge

/** Rust's pairwise merge: the merged Mapping, not inverted, or null. */
Mapping tryMerge(Mapping other, boolean series, boolean thisInverted, boolean otherInverted)   // default: null
```

`simplifyStep()` (already present) is C's per-class `Simplify`: the default becomes `Simplifier.baseSimplify(this)`, CmpMap overrides it with the nominate loop, and FrameSet keeps its own.

### Sharing, not tag trees

- Java Mappings are shared references, as C's are, so Rust's machinery that emulates C's pointer aliasing is not ported: `TagTree`, `stamped_tags`, `MergeCtx.stamped`, and the `origin` and `merge_tag` fields of `MappingFlags`.
  An entry of `MergeList` is the Mapping itself and its effective Invert flag; Rust's third member (`orig_inv`) is the Mapping's own Invert attribute.
- Where Rust clones a Mapping into the list or into a result, Java shares it if C calls `astClone` there and copies it if C calls `astCopy`, and the ledger records any place where this cannot be read from C.
- The `IsSimp` records C writes through its aliases onto the components of the CmpMap being simplified then appear on the same objects in Java, which the regression gate checks byte for byte.
- `Frozen` is a field on `Mapping` (C `astSetFrozen`), separate from `IsSimp` (hazard H3).

### Gates and deferred lists

- New list `src/test/resources/deferred/simplify-cases.txt`, rows of `cases.txt` that do not match C, with reasons `needs <Class>` (an out-of-scope class in the `.map` or `.simp`, from `NativeClasses.needs`) or `needs simplification`.
- A task makes a row pass by porting what it needs; the both-ways check then fails until the row is removed from the list, which is that task's evidence.
- Each task also removes its own entries from `rust-tests.txt` (reason `needs simplification`) by translating those tests, and from the other lists where named.
- `-Dast.writeDeferred=true` rewrites a list from what the rows give; a task commits a rewritten list only after reading the diff, and only with rows removed, never added, unless the ledger has a ruling for the addition.

---

### Task 1: The simplify regression gate

**Files:**
- Create: `src/test/java/uk/ac/starlink/ast/SimplifyRegressionTest.java`, `src/test/resources/deferred/simplify-cases.txt`

**Interfaces:**
- Consumes: `Fixtures.read`, `Channel` reading, `NativeClasses.needs`, `CValues.nativeStringsEquivalent(got, want, 4)`, `CValues.nativeStringsAstEqual`, `CValues.stripIsSimp`, `Deferred.load/write`, `OracleFile` for `oracle/simplify_fixtures.oracle`.
- Produces: the gate every later task runs: `mvn test -Dtest=SimplifyRegressionTest`.

- [ ] **Step 1: Write `SimplifyRegressionTest`**, a translation of Rust `tests/test_simplify_regression.rs::simplify_regression` (542-1010).
  For each of the 517 rows of `simplify/cases.txt`: read the `.map`; if it is a FrameSet and the reference is not, take its base-to-current Mapping (as the Rust test does); simplify; write the result with a default Channel; then compare by the row's `skip_string_compare` field:
  - blank: the dump equals the `.simp` text with numbers within 4 ULP;
  - `astequal`: as blank, numbers within `astEQUAL`;
  - `yes`: the dump with `IsSimp` lines stripped equals the `.map`'s own dump with `IsSimp` stripped.
  Then transform the oracle inputs of the `.map`'s `simplify_fixtures.oracle` sections, forward and inverse where present, through the simplified Mapping and compare with the recorded outputs at the file's tolerance.
  Tests: `everyRowMatchesCOrIsListed`, `listedRowsStillFail` (a listed row still fails with its listed reason), `rowCountIsTheManifests` (517).

- [ ] **Step 2: Generate the list**

Run: `mvn test -Dtest=SimplifyRegressionTest -Dast.writeDeferred=true`
Expected: `simplify-cases.txt` written; every out-of-scope row reads `needs <Class>`; every in-scope blank or `astequal` row whose `.simp` differs from its `.map` reads `needs simplification`.
Record in the ledger the counts per reason and the number of in-scope rows (the Rust test counts 229: 175 compared, 53 `yes`, 1 `astequal`); any in-scope row failing for a reason other than these is a phase 1 bug to fix in this task, with a test.

- [ ] **Step 3: Run the gate**

Run: `mvn test -Dtest=SimplifyRegressionTest`
Expected: PASS (every row matches or is listed with the reason it gives).

- [ ] **Step 4: Commit** — `git commit -m "Add the simplify regression gate"`

### Task 2: The simplification engine and UnitMap

**Files:**
- Create: `MergeList.java`, `Simplifier.java` (in `src/main/java/uk/ac/starlink/ast/`), `src/test/java/uk/ac/starlink/ast/SimplifierTest.java`
- Modify: `Mapping.java` (hooks, `Frozen` field, `simplifyStep` default), `CmpMap.java` (`simplifyStep`), `UnitMap.java` (`mapMerge`, from `unit.rs:146-210`, C unitmap.c:357-627)

**Interfaces:**
- Consumes: Task 1's gate.
- Produces: `MergeList`, `Simplifier.flatten(Mapping, boolean inverted, boolean series)`, `Simplifier.reconstruct(MergeList, boolean series)`, `Simplifier.baseSimplify(Mapping)`, `Simplifier.dispatchMapMerge(MergeList, int, boolean)`, `Simplifier.defaultMapMerge(MergeList, int, boolean)`, `long Simplifier.patternCheck(long val, long check, List<Long> history)`, the hooks `mapMerge` and `tryMerge`, `Mapping.getFrozen()/setFrozen(boolean)`.

- [ ] **Step 1: Write the failing tests** in `SimplifierTest`, translating the `simplify.rs` unit tests that need only UnitMap, ZoomMap construction and CmpMap: every `flatten_*`, every `reconstruct_*`, every `pattern_check_*`, `merge_ctx_freezes_the_mapping_itself`, `merge_loop_no_merges_with_incompatible_types`, `merge_loop_strips_unitmaps_from_series`, `merge_loop_all_unitmaps_preserves_one`, `simplify_mapping_non_cmpmap_delegates`, `simplify_mapping_cmpmap_roundtrip`, `simplify_mapping_inverted_cmpmap`, `base_simplify_reaches_a_classes_own_map_merge`, `base_simplify_returns_a_clone_when_nothing_reduces`.
  Add:
  - `simplifyLeavesTheInputAsCDoes`: simplify `CmpMap(ZoomMap(2), UnitMap)` read from a dump; afterwards the input's dump with `IsSimp` stripped, and its transform of five points, equal those before.
  - `aDeepAlternatingSeriesBecomesAUnitMap`: 2,000 series levels of `ZoomMap(3)` and its inverse simplify to a `UnitMap` with no `StackOverflowError` (passes in Task 3, when ZoomMap merges; mark it `@Disabled("needs ZoomMap merge: Task 3")` here and enable it there).
  - `threadsSimplifyTheirOwnCopiesIdentically`: 4 threads each simplify a `copy()` of the `lsst.ast` FrameSet's Mapping; every dump equals the single-thread dump.
  - `anIdentMappingIsNotMerged`: a `UnitMap` with `Ident` set inside a series CmpMap is still present after simplification (C `DoNotSimplify`, mapping.c:1107-1153).

- [ ] **Step 2: Run them** — `mvn test -Dtest=SimplifierTest` — Expected: FAIL (classes missing).

- [ ] **Step 3: Implement** `MergeList`, `Simplifier` and the hooks from `simplify.rs` (engine sections listed in Shared Design) and `simplify_entry.rs` (the gate is the existing `Mapping.simplify()`; keep its NODEF/TRNND handling), CmpMap's `simplifyStep` from `simplify_cmpmap_into` (3877-3969, C cmpmap.c:3594-3960), and UnitMap's `mapMerge`.
  The recursion guard is a `ThreadLocal<ArrayDeque<Mapping>>` checked with C's identity-then-structure comparison (cmpmap.c:3686-3696); where C calls `astEqual` there, compare dumps, since `astEqual` is not ported, and record that as a ruling.

- [ ] **Step 4: Run** `mvn test -Dtest='SimplifierTest,SimplifyRegressionTest'` — Expected: `SimplifierTest` PASS except the disabled test; the gate fails on rows that now pass (UnitMap-only rows and the `yes` rows still pass).
  Remove those rows from `simplify-cases.txt` and rerun: PASS.

- [ ] **Step 5: Full suite** — `mvn test` — Expected: PASS.
  Phase 1 tests that asserted an unsimplified structure (the "chain of UnitMaps accepted" structure checks) now see C's structure; change each such assertion to C's structure, citing the C source.

- [ ] **Step 6: Commit** — `git commit -m "Port the simplification engine and UnitMap's MapMerge"`

### Task 3: ZoomMap, ShiftMap and WinMap

**Files:**
- Create: `WinMapMerge.java`
- Modify: `ZoomMap.java` (`simplify.rs:997-1155`, `zoom.rs` try_merge 287-362; C zoommap.c:570-1168), `ShiftMap.java` (`shift.rs:207-346`; C shiftmap.c:520-748), `WinMap.java` (`winmap.rs` try_merge 377-444 and helpers 547-615; C winmap.c:896-1896 with WinMat, WinPerm, WinShift, WinUnit, WinWcs, WinWin, WinZoom)
- Test: `ZoomMapTest`, `WinMapTest`, `UnitMapperTest`, `SimplifierTest`, new `AllowSimplifyOracleTest`

**Interfaces:**
- Consumes: Task 2 hooks.
- Produces: `WinMapMerge.winMapMerge(MergeList, int, boolean)`; WinMap's swap helpers (`winMat`, `winPerm`, `winWcs`, `canSwap`) that Tasks 4, 5 and 7 call.

- [ ] **Step 1: Write the failing tests**: the 3 `test_zoommap.rs` and 11 `test_winmap.rs` tests and `test_unit_mapper.rs::zoom_map_chains_simplify_as_the_c_library_does`, all listed `needs simplification` in `rust-tests.txt`; the `simplify.rs` unit tests `simplify_unitmap_series_zoom_removes_unit`, `simplify_zoom_series_*`, `simplify_shift_series_*`, `simplify_preserves_transform_*`, `simplify_unitmap_parallel_merges`, `simplify_winmap_*`, `simplify_*zoommap*`, `simplify_*shiftmap*`, `simplify_mixed_linear_chain`, `simplify_cross_type_multi_axis`; `AllowSimplifyOracleTest`, the 6 C-probe cases of Rust `tests/allow_simplify_oracle/cases.rs` (input dump in, C's simplified dump out, 4 ULP) and the three `testresimp.c` ports in `test_allow_simplify.rs`.
  Remove the `@Disabled` from `aDeepAlternatingSeriesBecomesAUnitMap`.
- [ ] **Step 2: Run** — `mvn test -Dtest='ZoomMapTest,WinMapTest,SimplifierTest,AllowSimplifyOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** the three classes' merges, checking `docs/audits/mapmerge/{zoom,shift,winmap}.md` row by row.
  The UnitNormMap branches (`winmap.rs:425`, `shift.rs:319`) are absent, each with the comment the Global Constraints require.
- [ ] **Step 4: Run** the step 2 tests and `SimplifyRegressionTest`; remove the rows that now pass from `simplify-cases.txt` and the translated tests from `rust-tests.txt` — Expected: PASS.
- [ ] **Step 5: Full suite** — `mvn test` — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Port the MapMerge of ZoomMap, ShiftMap and WinMap"`

### Task 4: MatrixMap

**Files:**
- Create: `MatrixMapMerge.java` (`matrix_merge.rs`; C matrixmap.c:1664-2291 with CanSwap, MatMat, MatPerm, MatPermSwap, MatWin, MatZoom)
- Modify: `MatrixMap.java` (`matrix.rs` try_merge 902-1075)
- Test: `MatrixMapTest`, `SimplifierTest`, new `SimplifyTrnndOracleTest`

**Interfaces:**
- Consumes: Task 3's WinMap helpers.
- Produces: `MatrixMapMerge.matrixMapMerge(MergeList, int, boolean)`, `MatrixMap.replaceWithSimpler()` for Task 6.

- [ ] **Step 1: Write the failing tests**: the 10 `test_matrixmap.rs` tests listed `needs simplification`; the `simplify.rs` unit tests `simplify_matrix_*`, `simplify_zoommap_then_matrixmap`, `simplify_matrixmap_then_*`, `simplify_diagonal_matrix_series_reduces`, `simplify_cross_type_dimension_mismatch_no_merge`; `SimplifyTrnndOracleTest` with the in-scope C dumps `singular_matrix_swap` and `singular_matrix_swap_after_zoom` from Rust `tests/simplify_trnnd_oracle/`, as `test_mapping.rs` uses them (356-762): simplifying gives C's dump and does not throw.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**, checking `docs/audits/mapmerge/matrix.md`; the `EPSILON` comparisons are C's (matrixmap.c:5364).
- [ ] **Step 4: Run** the tests and the gate; remove the rows and list entries that now pass — Expected: PASS.
- [ ] **Step 5: Full suite** — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Port MatrixMap's MapMerge"`

### Task 5: PermMap

**Files:**
- Modify: `PermMap.java` (`simplify.rs:1164-1191`, `perm.rs` try_merge 884-1242 and `canonical_null_form` 239-321; C permmap.c:749-1461)
- Test: `PermMapTest`, `SimplifierTest`

- [ ] **Step 1: Write the failing tests**: the 2 `test_permmap.rs` tests listed `needs simplification`; `simplify_permmap_inverse_cancels`, `simplify_permmap_different_perms_no_merge`.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**, following Rust's pairwise merge.
  `docs/audits/mapmerge/perm.md` lines 15-18 record that C instead brackets a whole run of PermMaps and UnitMaps; if any regression row differs from C because of this, port C's bracketing and record the ruling.
- [ ] **Step 4: Run** tests and gate; remove what passes — Expected: PASS.
- [ ] **Step 5: Full suite** — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Port PermMap's MapMerge"`

### Task 6: CmpMap's MapMerge

**Files:**
- Create: `CmpMapMerge.java` (see Shared Design; C cmpmap.c:1409-2329, CombineMaps 731-879, SeparateMappings 3530-3592)
- Modify: `CmpMap.java` (`mapMerge`, `cmpmap.rs:979-986`)
- Test: `CmpMapTest`, `SimplifierTest`

- [ ] **Step 1: Write the failing tests**: `simplify.rs` unit tests `parallel_in_series_*`, `series_in_parallel_*`, `permmap_swap_*`, `iteration_cap_prevents_infinite_loop` (named `anOscillatingListStopsAtThePatternCheck` in Java, since there is no cap), `series_parallel_absorb_trailing_shift_into_branch`, `unit_scale_winmap_in_a_parallel_branch_narrows_to_a_shiftmap`, `parallel_in_series_with_blocking_delta`; the in-scope simplify tests of `test_cmpmap.rs` (not `series_inverse_pair_of_mathmaps`).
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**, checking `docs/audits/mapmerge/cmpmap.md`.
  Quirk 1970 (C leaves an `IsSimp` stamp from a discarded series-in-parallel probe, cmpmap.c:1608-1628) is C behaviour that Java's shared references reproduce; the gate decides, and the ledger records which way it went.
- [ ] **Step 4: Run** tests and gate; remove what passes — Expected: PASS.
- [ ] **Step 5: Full suite** — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Port CmpMap's MapMerge"`

### Task 7: SphMap and WcsMap

**Files:**
- Create: `WcsMapMerge.java`
- Modify: `SphMap.java` (`simplify.rs:3314-3420`, `sphmap.rs` try_merge 413-469; C sphmap.c:581-968), `WcsMap.java` (`simplify.rs:1206-1376`, `wcsmap.rs` try_merge 1256-1282; C wcsmap.c:2967-3393, CanMerge 839-947, CanSwap 949-1172)
- Test: `SphMapTest`, `WcsMapTest`, `SimplifierTest`

- [ ] **Step 1: Write the failing tests**: the 4 `test_sphmap.rs` and 5 `test_wcsmap.rs` tests listed `needs simplification`; `simplify_sphmap_*`, `simplify_wcsmap_*`.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**, checking `docs/audits/mapmerge/{sphmap,wcsmap}.md`.
- [ ] **Step 4: Run** tests and gate; remove what passes — Expected: PASS.
- [ ] **Step 5: Full suite** — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Port the MapMerge of SphMap and WcsMap"`

### Task 8: PolyMap

**Files:**
- Modify: `PolyMap.java` (`polymap.rs` map_merge 1883-1905, `replace_with_simpler` 1059-1103, try_merge 1912-1950; C polymap.c:3449-4011)
- Test: `PolyMapTest`, `LsstOracleTest`, `SimplifierTest`

- [ ] **Step 1: Write the failing tests**: the 9 `test_polymap.rs` tests and the `test_lsst_oracle.rs` test listed `needs simplification`; `simplify_polymap_inverse_cancels`.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**, checking `docs/audits/mapmerge/polymap.md`; the ChebyMap normalisation in `replace_with_simpler` is absent, with the required comment.
- [ ] **Step 4: Run** tests and gate; remove what passes — Expected: PASS.
- [ ] **Step 5: Full suite** — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Port PolyMap's MapMerge"`

### Task 9: SlaMap and TimeMap

**Files:**
- Modify: `SlaMap.java` (`slamap.rs` 622-786 and try_merge 1513-1548; C slamap.c:2547-3206), `TimeMap.java` (`timemap.rs` 1102-1302 and try_merge 1414-1447; C timemap.c:1945-2471)
- Test: `SlaMapTest`, `TimeMapTest`, `SkyConvertTest`

- [ ] **Step 1: Write the failing tests**: the 5 `test_slamap.rs` and 7 `test_timemap.rs` tests listed `needs simplification`; `simplify_slamap_*`.
  In `SkyConvertTest`, remove the excuse for the 168 conversions (within 5e-8 rad of C, "needs simplification"), so they are held to the default tolerance.
- [ ] **Step 2: Run** — Expected: FAIL (the 168 conversions among them).
- [ ] **Step 3: Implement**, checking `docs/audits/mapmerge/{slamap,timemap}.md`.
  Hazard H2 applies: the SlaMap pair FitsChan builds cancels only if its construction matches C's.
- [ ] **Step 4: Run** tests and gate; remove what passes — Expected: PASS, every `SkyConvertTest` conversion within the default tolerance; any that is not is a finding to trace in C, not a tolerance to widen.
- [ ] **Step 5: Full suite** — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Port the MapMerge of SlaMap and TimeMap"`

### Task 10: FrameSets, Frames and FITS reading

**Files:**
- Modify: `FrameSet.java` (`simplifyStep` against `frameset.rs:2639-2662`, C frameset.c:10134-10261; check every call site listed in `docs/audits/mapmerge/frame_frameset.md`), `CmpFrame.java` (`simplifyStep`, cmpframe.c:9003-9090), `FitsWcsRead.java` (C `WcsMapFrm`, fitschan.c:38350 and 38376) as the gates require
- Test: `FrameSetIntegrityTest`, `WcsconvTest`, `TransformOracleTest`, `MapSplitOracleTest`, `FitsChanWcsTest` (the translation of `test_fitschan_wcs.rs`), new `SimplifySeamsTest`

- [ ] **Step 1: Write the failing tests**:
  - `settingAFrameAttributeKeepsTheFrameSetsSize` in `FrameSetIntegrityTest`: read `wcsconv/inputs/galactic-tan.head`, call `setC("Label(1)", "x" + i)` 200 times; the native dump and the node count after the last call equal those after the first (C's `RestoreIntegrity` gives a UnitMap, which `astRemapFrame` does not add as a node).
  - `capFramesetNochange` in `SimplifySeamsTest`, from `test_simplify_seams.rs`, compared byte for byte with C.
  - The `test_fitschan_wcs.rs` test listed `needs simplification`.
  - Remove from `oracle-rows.txt` the 4 CAR-seam rows of `car6.head` and `CHIPASS_Equ.head`; remove from `MapSplitOracleTest` the exclusions whose reason begins `needs simplification`.
- [ ] **Step 2: Run** — `mvn test -Dtest='FrameSetIntegrityTest,SimplifySeamsTest,FitsChanWcsTest,TransformOracleTest,MapSplitOracleTest,WcsconvTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** what the tests show is missing; by this task the Mapping merges exist, so the work is in the FrameSet and FitsChan call sites matching C's order of simplification (quirk 592).
- [ ] **Step 4: Rewrite `wcsconv.txt`** with `-Dast.writeDeferred=true` and read the diff: every `ast` row listed `needs simplification` must now match C; every `native` row so listed becomes `out-of-scope method FitsChan.write` (its output needs `FitsChan.write`); update `WcsconvTest`'s Javadoc to say so.
- [ ] **Step 5: Run** the step 2 tests and the gate — Expected: PASS.
- [ ] **Step 6: Full suite** — Expected: PASS.
- [ ] **Step 7: Commit** — `git commit -m "Simplify FrameSets and FITS-WCS reads as C does"`

### Task 11: Sweep the deferred lists

**Files:**
- Modify: every list in `src/test/resources/deferred/` and the tests that own them

- [ ] **Step 1: Rewrite every list** with `mvn test -Dast.writeDeferred=true` and read each diff.
  Expected: no entry reads `needs simplification` except where a ruling explains it; no entry is added; `rg UNEXPLAINED src/test/resources/deferred` prints nothing.
  The serialisation, full-dump and read-warnings lists can lose entries whose C dumps hold simplified Mappings; any change in the other direction is a regression to fix.
- [ ] **Step 2: Run `mvn verify`** — Expected: BUILD SUCCESS; record the test count.
- [ ] **Step 3: Run on JDK 27** — `mvn test -Djvm=/opt/homebrew/opt/openjdk/bin/java` — Expected: PASS.
- [ ] **Step 4: Commit** — `git commit -m "Clear the deferrals simplification resolves"`

### Task 12: Benchmarks and the phase 2 report

**Files:**
- Modify: `bench/` (add header-read cases), `bench/README.md`
- Create: `docs/reports/<date>-phase2-report.md`, `docs/reports/<date>-phase2-rulings.md` (`<date>` is the day the task runs)

- [ ] **Step 1: Add benchmark cases** that read `sip.head`, `galactic-tan.head` and `car6.head` through a FitsChan and time `tranP` and the NaN protocol on the FrameSet read, at 1 and 1,000,000 points; run them before (with `simplify()` returning `this`, from a scratch checkout of the phase 1 tip) and after.
- [ ] **Step 2: Run all benchmarks** on JDK 21 and JDK 27 into `bench/results-jdk21.csv` and `bench/results-jdk27.csv` — Expected: CSVs written; the header-read cases are faster than before.
- [ ] **Step 3: Write the report** in the phase 1 report's form: what was ported (Java and Rust lines per task), time taken, the gate counts (rows of `cases.txt` matched, by reason for the rest), every tolerance or excuse added or removed, the FrameSet growth measured again, benchmark changes, and every ledger ruling in the rulings file.
- [ ] **Step 4: Commit** — `git commit -m "Report phase 2"`
