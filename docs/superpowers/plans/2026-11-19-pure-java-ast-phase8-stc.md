# Pure-Java AST Phase 8 (SelectorMap, RemoveRegions and STC) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** jniast's `SelectorMap`, `Stc` with its four subclasses `StcResourceProfile`, `StcSearchLocation`, `StcCatalogEntryLocation` and `StcObsDataLocation`, and `AstroCoords` construct, transform, compare, simplify, dump and load as C's do, and every Mapping and Frame class removes its Regions as C's `astRemoveRegions` does, so that every C fixture and Rust test still deferred for these classes runs.

**Architecture:** Port the Rust port's `src/mappings/selectormap.rs`, `src/regions/stc.rs` and the `remove_regions` overrides onto the phase 1 to 7 classes, with C (`reference/starlink-ast/src/selectormap.c`, `stc.c`, `stcresourceprofile.c`, `stcsearchlocation.c`, `stccatalogentrylocation.c`, `stcobsdatalocation.c`, and the `RemoveRegions` of `mapping.c`, `region.c`, `cmpmap.c`, `cmpframe.c`, `frameset.c`, `switchmap.c`, `tranmap.c`, `ratemap.c`) as the authority wherever the two differ.
`Stc` is an abstract `Region` that encapsulates a copy of another Region and forwards its geometry to it, on the pattern `CmpRegion` set in phase 7.

**Tech Stack:** as phases 1 to 7: Java 8 (`--release 8`), Maven, JUnit 5, Jackson (test scope), Error Prone, Checkstyle, PMD, Spotless; JDK 21 to build.

**Spec:** `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md` ("Later phases", item 6, second part).
Phase 7 (`docs/superpowers/plans/2026-11-18-pure-java-ast-phase7-regions.md`) was the first part; this branch, `u/timj/phase8-stc`, is stacked on it.
The earlier plans' Shared Design sections and the rulings in `docs/reports/2026-11-1*-phase*-rulings.md` still apply where this plan does not change them.
The scope survey this plan was written from is summarised in Shared Design; its C and Rust line references are the starting points named below.

## Global Constraints

- Every Global Constraint of the phase 1 plan applies unchanged (Java 8, no runtime dependencies, jniast signatures, C at `3d3f255f`, `default` and final `else` throw `AST__INTER`, `MODERN-JAVA` tags, no heuristics the original lacks, expected values from C only, share where C calls `astClone` and copy where C calls `astCopy`, British spelling, one sentence per line in Markdown).
- Public API: exactly jniast's members for the classes of this phase (`reference/starjava/jniast/src/main/uk/ac/starlink/ast`), already listed in `src/test/resources/jniast-api.txt`:
  - `SelectorMap(Region[] regs, double badval)`.
  - `Stc` (abstract, `extends Region`): `getStcNCoord()`, `getStcCoord(int)` (1-based), `getStcRegion()`.
  - `StcResourceProfile`, `StcSearchLocation`, `StcCatalogEntryLocation`, `StcObsDataLocation`: each one constructor `(Region region, AstroCoords[] coords)`.
  - `AstroCoords` (a plain class holding references, not copies): `AstroCoords()`, `AstroCoords(String[] names)`, and get/set `Name` (`String[]`), `Value`, `Error`, `Resolution`, `Size`, `PixSize` (`Region`).
  - Each task adds its classes to `JniastApiTest.IN_SCOPE`; nothing else public is added.
  - jniast's package-private `Stc.astroCoordsToKeyMap`, `keyMapToAstroCoords` and `astroCoordsToKeyMaps` are kept package-private with jniast's behaviour; the six `AST__STC*` key names (C `stc.h`: `"Name"`, `"Value"`, `"Error"`, `"Resolution"`, `"Size"`, `"PixSize"`) are package-private constants on `Stc`, since astjava has no `getAstConstantC`.
- `removeRegions()` is package-private (jniast has none; tests and a later Plot call it).
- Before porting a class, read Rust `docs/porting-hazards.md` (H4, H9 above all) and every entry naming the class in `docs/issues/c-library-quirks.md`.
- Rust comments cite older C line numbers; find each C function by name in the pinned source.
- Classes still absent after this phase: Moc, MocChan, NormMap, UnitNormMap, FitsTable, ChebyMap, XphMap, SplineMap, StcsChan, XmlChan, and the other AST classes jniast lacks.
- Values must match C to the oracle tolerances; when one does not, find the C function that computes it before touching the arithmetic (H7).
- Tolerances for values that pass through `java.lang.Math` come from an error bound worked out from the computation, never from the drift seen on this machine: CI runs on x86-64 Linux.
- C probes go in `src/test/resources/probes/` with the build recipe in the header, built against the pinned C in `scratchpad/build-ast`, values printed with `%.17g`; source and output are committed.

## Review Focus

1. A SelectorMap whose Regions overlap, are negated or unbounded, a point on a Region's boundary, and a point bad on only one axis: the index (or `badval`) C returns, the first Region holding a point winning (test in Task 2, from `selectormap_probe.c`).
2. A SelectorMap or Stc used, then dumped, copied and compared: C's Transform changes its own Regions in place (SelectorMap) or the encapsulated Region (Stc) and leaves attributes set, so the dump, `test("Negated")` and `equals` after a transformation are C's (tests in Tasks 2 and 3, from the probes).
3. `removeRegions` on nested containers: a Region inside a CmpMap inside a FrameSet, a Region used as a Frame of a FrameSet, a CmpMap whose other component is a plain Frame, a SwitchMap whose selector is a SelectorMap, and a Mapping with no Regions (returned unchanged, the same object, as C's clone is): C's result for each (test in Task 1, from `remove_regions_extra_probe.c`).
4. AstroCoords given an empty or null name array, null Regions, Regions in another Frame than the Stc's Region, and `getStcCoord(0)` or an index past the end: C's KeyMap contents and `AST__STCIND` messages, and jniast's null handling (test in Task 3).
5. A Frame attribute (`System`), `Negated` or `Closed` set on an Stc: forwarded to the encapsulated Region and the AstroCoords Regions as C's `RegSetAttrib` and the `MAKE_SET` macros do, and visible in the next dump, `getStcRegion` and `getStcCoord` (test in Task 3, from `stc_probe.c`).

---

## Shared Design

### Where the code goes

- `Mapping.removeRegions()` (package-private, returns `this`, as `mapping.c`'s `astClone`), overridden in `Region` (C: `astGetRegionFrame`, a copy of the current Frame, for every Region class, Stc included), `CmpMap`, `TranMap`, `RateMap`, `SwitchMap`, `CmpFrame` (the copy keeps `SpecFluxFrame`'s class) and `FrameSet` (both as a Frame and as a Mapping), each following its C file's `RemoveRegions`.
- `SelectorMap` (public, `extends Mapping`): `selectormap.c`'s state (copies of the Regions, `badval`), Transform, Equal, MapMerge, Copy, Dump and load; registered in `ObjectRegistry`.
- `AstroCoords` (public, plain class, as jniast's).
- `Stc` (public abstract, `extends Region`): `stc.c`'s state (the encapsulated Region, the AstroCoords KeyMaps), every override `stc.c`'s vtab installs, dump and load of the Stc part, the jniast converters and key constants.
- `StcResourceProfile`, `StcSearchLocation`, `StcCatalogEntryLocation` (public, each its class name, dump comment and loader), `StcObsDataLocation` (also the observatory position `obs`, settable package-private as C's protected `astStcSetObs`).
- `Region.dump` reads the Region class's own Negated, Closed, MeshSize and FillFactor where they are set, falling back to the overridable accessors only when unset, as `region.c`'s `Dump` calls its own static `TestNegated` and the others; Stc overrides the accessors to read the encapsulated Region (C `stc.c` `MAKE_GET`/`MAKE_TEST`).

### Copy and share (H4)

- Copy where C copies: SelectorMap copies each Region at construction (`selectormap.c` `astInitSelectorMap_`) and in Copy; Stc copies its Region at construction unless the Region is itself an Stc, which is mapped into the Stc's Frame instead (`stc.c` `astInitStc_`), copies each AstroCoords KeyMap (`MakeAstroCoordsKeyMap`), and `getStcRegion` and `getStcCoord` return copies; StcObsDataLocation copies `obs` in Copy and shares it in `astStcSetObs`.
- Share where C clones: `removeRegions` of an unchanged Mapping returns `this`; a changed CmpFrame or FrameSet is a copy whose unchanged components are the original's own objects, shared, as `cmpframe.c` and `frameset.c` replace the copy's components with clones of the new pointers.
  The sharing is C's and is visible when an attribute is set on a shared Frame; the Javadoc of `removeRegions` says so.

### Taken from C rather than Rust

1. **A Region's `removeRegions` returns a copy of its current Frame**, and each container turns a component that comes back as a Frame into a `UnitMap`, including a plain Frame component that was already there when another component changed (`cmpmap.c`, `tranmap.c`, `switchmap.c`, `frameset.c`); Rust returns a UnitMap directly and cannot show the second case, since its Frames are not Mappings.
2. **SelectorMap's Transform negates and re-closes its own Regions in place**, restoring the old values in a `finally`, as `selectormap.c` does; the attributes are left set, as C's `astSetNegated` and `astSetClosed` leave them, and the probe pins what that changes in a later dump.
   Rust transforms negated copies.
3. **SelectorMap's MapMerge detects a simplified Region by identity** (`simplify()` returned a different object), for any Region class, as C compares pointers; Rust compares with `equals` against a fixed list of nine classes.
4. **SelectorMap's MapMerge merges neighbours as C's does**: invert the neighbour, compare with `Equal` (which ignores Invert and, for a 1-D SelectorMap, cannot tell one from its inverse), restore it; Rust demands differing Invert flags.
   The probe pins two equal, both-forward 1-D SelectorMaps in series.
5. **Stc's Transform and GetBounded set the encapsulated Region's Negated to its own value around the call**, leaving it set, as `stc.c` does.
6. **Loaders follow C where C's result is usable** (a SelectorMap with no `Reg1`, an `ObsLoc` of any class, `Ncoord` below 0 read as 0, the later dummy-FrameSet Regions given the first Region's FrameSet) and raise `AST__LDERR` where C would store a null and fail later (an Stc without `Region`, a missing `Coord<n>`) (H9).
   A `Begin Stc` of the abstract class itself raises `AST__LDERR`, since jniast's `Stc` cannot be instantiated.
7. **Error messages are C's** (`selectormap.c`: `AST__BDPAR` "Bad number of Regions", `AST__BADNI` "does not refer to the same coordinate Frame as the first Region"; `stc.c`: the four `AST__STCIND` variants and `AST__STCKEY`), not Rust's.
8. **jniast's null handling:** a null `regs` array or element, or a null Stc `region`, is a `NullPointerException` naming the argument, as phase 6 ruled for jniast arguments; a null `coords` array means no AstroCoords, as `jniastConstructStc` reads it.

### `ERROR_ELLIPSE_LICENSED`

The `region_stc` oracle cases `sky`, `catalog_entry` and `sky_coord` hold an AstroCoords Error Region that C re-expresses as the best-fit Ellipse of a 1e-4 rad Circle's mesh, an ill-conditioned fit whose defining points move about 1e-5 relative under last-bit `Math` differences (Rust `tests/test_region_stc_oracle.rs`, `PLAN.md`).
Java compares those Ellipse points with a tolerance derived from the fit's conditioning, worked out in the test's comment, and every other value at the oracle's usual tolerance; if no bound can be derived, the Ellipse's `X` lines are compared as Rust does off macOS (dropped) and the omission is recorded in `docs/rust-c-differences.md`.

### Gates and deferred lists

- Each Rust test file becomes a Java test class as before (`test_selectormap.rs` → `SelectorMapTest`, `test_region_stc_oracle.rs` → `RegionStcOracleTest` reading `src/test/resources/oracle/region_stc.json`, `test_remove_regions_oracle.rs` → `RemoveRegionsOracleTest` reading `remove_regions.json`, both converted with `CasesToJson`).
- Each task removes from the deferred lists the entries whose only missing classes or functions are now present (`mvn test -Dast.writeDeferred=true`, then check that the diff removes only entries naming the task's classes).
- The two `remove_regions` oracle cases that need NormMap (`normmap_region`, `normmap_cmpframe`) are skipped by name in the test with the reason `NormMap (not in jniast)`, and the test asserts the number of cases it ran.
- `rust-tests.txt` keeps `test_region_stc_oracle.rs::error_ellipse_licence_is_still_needed` (Rust-only API).

---

### Task 1: removeRegions

**Files:**
- Modify: `Mapping.java`, `Region.java`, `CmpMap.java`, `TranMap.java`, `RateMap.java`, `SwitchMap.java`, `CmpFrame.java` (and its Javadoc's "not ported" sentence), `FrameSet.java`, `RegionsTest.java` and `RegionsCTest.java` Javadoc
- Create: `src/test/resources/oracle/remove_regions.json`, `src/test/resources/probes/remove_regions_extra_probe.c` and its output
- Test: `RemoveRegionsOracleTest` (`test_remove_regions_oracle.rs`, 9 of 11 cases), `RemoveRegionsTest` (Review Focus 3, from the probe: SwitchMap with a Region route, FrameSet with a Region as a Frame and one inside a CmpMap, CmpFrame of two Regions, a CmpMap with a plain Frame component beside a Region, a Mapping with no Regions returned as the same object, the sharing of an unchanged CmpFrame component), `RegionsTest` (`remove_regions_from_frameset`, `remove_regions_unit_passthrough`), `RegionsCTest` (`check_remove_regions`)

**Interfaces:**
- Produces: package-private `Mapping removeRegions()` on `Mapping` and the overriding classes above; SelectorMap does not override it (C installs none), so its Regions stay.

- [ ] **Step 1: Write the probe** `remove_regions_extra_probe.c` (Review Focus 3; the SwitchMap case uses a SwitchMap of Box routes, since SelectorMap is not yet ported in Java; the probe prints for each case the class tree of the result as a dump and whether it is the same object as the input), build it and commit source and output; convert the oracle with `CasesToJson`.
- [ ] **Step 2: Write the failing tests** named under Files.
- [ ] **Step 3: Run** `mvn test -Dtest='RemoveRegions*Test,RegionsTest,RegionsCTest'` — Expected: FAIL (method missing).
- [ ] **Step 4: Implement** from each C file's `RemoveRegions` (Shared Design, "Taken from C" item 1, and the sharing rule), with Rust's `remove_regions` and `*_without_regions` overrides as the guide.
- [ ] **Step 5: Run** the Step 3 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS; the list diff removes only the four `astRemoveRegions` entries.
- [ ] **Step 6: Commit** `Remove the Regions from a Mapping`.

### Task 2: SelectorMap

**Files:**
- Create: `SelectorMap.java`, `src/test/resources/probes/selectormap_probe.c` and its output
- Modify: `ObjectRegistry.java`, `JniastApiTest.java` (`SelectorMap`), `EqualTest.java` (3 cases, count 7 to 10), `RateOracleTest.java` (3 cases, count 5 to 8), `ObjectIdOracleTest.java` (`selectormap`, `selectormap_part_ids`), `FrameInMapSlotTest.java` (floor 28 to 34, no refusals), `SwitchMapTest.java` (the 10 listed tests and its Javadoc), `SwitchMap.java` Javadoc
- Test: `SelectorMapTest` (`test_selectormap.rs`, 13, its `two_regions.ast` copied into `src/test/resources/selectormap/`), `SelectorMapProbeTest` (Review Focus 1 and 2 and "Taken from C" items 2 to 4, from the probe), `ConstructorChecksTest` (`a_selectormap_needs_regions_in_one_frame`), `JniastSelectorMapTest` (new: jniast's `AstTest.testSwitchMap`), the tests named under Modify

**Interfaces:**
- Consumes: Task 1 (`switchmap_remove_regions`), phase 7's `Region`.
- Produces: `public class SelectorMap extends Mapping`.

- [ ] **Step 1: Write the probe** `selectormap_probe.c`: Review Focus 1; a SelectorMap dumped before and after a transformation (item 2); a SelectorMap of Regions whose simplification changes nothing and one whose simplification changes a Region (item 3); two equal both-forward 1-D SelectorMaps in series simplified, and the 2-D pair with the second inverted (item 4); a load of a dump whose later Regions have a dummy FrameSet and whose first Region's base-to-current Mapping is not a unit Mapping; a load with no `Reg1`; `testregions.c`'s `checkSelectorMapEqual` cases; build it and commit source and output.
- [ ] **Step 2: Write the failing tests** named under Files.
- [ ] **Step 3: Run** `mvn test -Dtest='SelectorMap*Test,SwitchMapTest,EqualTest,RateOracleTest,ObjectIdOracleTest,FrameInMapSlotTest,ConstructorChecksTest'` — Expected: FAIL (class missing).
- [ ] **Step 4: Implement** from `selectormap.c` (Shared Design, "Taken from C" items 2 to 4, 6 to 8), with Rust `selectormap.rs` as the guide.
- [ ] **Step 5: Run** the Step 3 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS; the list diff removes only entries whose missing class was SelectorMap (25 `rust-tests.txt`, 10 each of `full-dumps.txt`, `read-warnings.txt`, `serialisation.txt`, 8 of `simplify.txt`, 8 of `simplify-cases.txt`).
- [ ] **Step 6: Commit** `Add SelectorMap`, and the list changes as `Run the SelectorMap fixtures`.

### Task 3: Stc, AstroCoords and StcResourceProfile

**Files:**
- Create: `Stc.java`, `AstroCoords.java`, `StcResourceProfile.java`, `src/test/resources/oracle/region_stc.json`, `src/test/resources/probes/stc_probe.c` and its output
- Modify: `Region.java` (`dump` reads the Region's own set values; package-private raw accessors), `ObjectRegistry.java`, `JniastApiTest.java` (`AstroCoords`, `Stc`, `StcResourceProfile`)
- Test: `RegionStcOracleTest` (`test_region_stc_oracle.rs`: cases `plain`, `negated`, `sky`, `mapped`, `from_mapped`, `mapped_simplified`, with `overlap_with_a_circle_matches_c`, `astro_coords_match_c`, `unknown_astro_coords_key_is_stckey`; the case filter asserts the count run), `StcProbeTest` (Review Focus 2, 4 and 5, `testregions.c`'s `checkStcNegated`, a `Begin Stc` dump, loads without `Region` and without a `Coord<n>`), `AstroCoordsTest` (jniast's 2), `RegionsTest` (a CmpRegion and a Box dump unchanged by the `Region.dump` change), `cap_timemap_02` in the fixture lists

**Interfaces:**
- Consumes: phase 7's `Region` hooks (`regBaseInside`, `regBaseMask`, `regBaseBox`, `regBaseMesh`, `isBoundedInner`, `regBasePick`, `regPins`, `getDefUnc`, `setRegFS`, `regSetAttrib`, `regClearAttrib`, `getUseDefs`, `overlapX`, `simplifyStep`, `equalTo`, `regOverlay`), `KeyMap.mapPut0A`, `mapPut1C`, `mapGet0A`, `mapGet1C`, `mapType`.
- Produces: `public abstract class Stc extends Region` with a package-private constructor taking the Region and the AstroCoords KeyMaps and a class-specific dump comment; `public class StcResourceProfile extends Stc`; `public class AstroCoords`; package-private `Stc.AST__STCNAME` and the other five key names.

- [ ] **Step 1: Write the probe** `stc_probe.c`: Review Focus 2, 4 and 5; `checkStcNegated`; a hand-written `Begin Stc` dump read by C; build it and commit source and output; convert the oracle.
- [ ] **Step 2: Write the failing tests** named under Files, with the `ERROR_ELLIPSE_LICENSED` handling of Shared Design.
- [ ] **Step 3: Run** `mvn test -Dtest='RegionStcOracleTest,StcProbeTest,AstroCoordsTest,Region*Test'` — Expected: FAIL (classes missing).
- [ ] **Step 4: Implement** `Region.dump`'s change first and run `mvn -q test` to show no existing dump changes; then `Stc`, `AstroCoords` and `StcResourceProfile` from `stc.c` and `stcresourceprofile.c`, with Rust `stc.rs` and the Stc part of `serial/mod.rs` as the guide ("Taken from C" items 5 to 8).
- [ ] **Step 5: Run** the Step 3 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS; the list diff removes only the `cap_timemap_02` entries and the Stc `rust-tests.txt` entries for these cases.
- [ ] **Step 6: Commit** `Add Stc, AstroCoords and StcResourceProfile`, and the list changes as `Run the StcResourceProfile fixtures`.

### Task 4: The other Stc classes, and casting within the Stc hierarchy

**Files:**
- Create: `StcSearchLocation.java`, `StcCatalogEntryLocation.java`, `StcObsDataLocation.java`
- Modify: `Region.java` (`cast`: `obj.getClass().isInstance(this)` in place of `getClass() == obj.getClass()`), `ObjectRegistry.java`, `JniastApiTest.java`, `stc_probe.c` and its output (`checkStcObsDump`, a cast between Stc classes through `astFindFrame`), `docs/rust-c-differences.md` (the "Casting a Region into the class of another Region" entry)
- Test: `RegionStcOracleTest` (cases `search`, `catalog_entry`, `obs_data_without_obs`, `obs_data`, `full_dumps_match_c`, `c_dumps_read_back_as_c_reads_them`; the filter removed), `StcProbeTest` (`checkStcObsDump`, the cast rows), `StcTest` (jniast's `testConstructors`; `testReads` and `testChandra` need XmlChan and are not translated, the class Javadoc says so)

**Interfaces:**
- Consumes: Task 3.
- Produces: the three public classes; `StcObsDataLocation`'s package-private `setObs(PointList)` (C's `astStcSetObs`, sharing the PointList as C's clone does).

- [ ] **Step 1: Extend** the probe, and write the failing tests named under Files.
- [ ] **Step 2: Run** `mvn test -Dtest='RegionStcOracleTest,StcProbeTest,StcTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** from `stcsearchlocation.c`, `stccatalogentrylocation.c` and `stcobsdatalocation.c` (`ObsLoc` written only when present, copied in Copy, not compared by Equal, as C installs no Equal), and the `cast` change; casting between sibling Stc classes stays null in Java where C's truncating cast reads past the smaller object (the `docs/rust-c-differences.md` entry says so).
- [ ] **Step 4: Run** the Step 2 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS; the list diff removes only the remaining Stc entries.
- [ ] **Step 5: Commit** `Add the other Stc classes`.

### Task 5: Sweep and report

**Files:**
- Modify: the deferred lists, the Javadoc deferral notes that remain (`SwitchMapTest`, `EqualTest`, `RateOracleTest`, `ObjectIdOracleTest`, `RegionsTest`, `RegionsCTest`, `SwitchMap`, `CmpFrame`), `NativeStream.java` and `ObjectRegistry.java` comments, `docs/rust-c-differences.md`
- Create: `docs/reports/2026-11-19-phase8-report.md`, `docs/reports/2026-11-19-phase8-rulings.md`

- [ ] **Step 1: Sweep:** `rg` the Rust `tests/` directory and the test sources for SelectorMap, Stc, AstroCoords and `remove_regions`; each test is translated or listed with a reason; `rust-tests.txt` keeps only `error_ellipse_licence_is_still_needed` of this phase's entries; `mvn test -Dast.writeDeferred=true` changes nothing.
- [ ] **Step 2: Write the report** in the layout of `docs/reports/2026-11-19-phase7-report.md`: what was ported, the gates and their counts, the deferred-list changes, the C defects found (reported to the user only, never to GitHub), the gaps, and what the next phase takes (spec item 7, Plot, after it is ported in Rust); and the rulings file, citing commits by subject.
- [ ] **Step 3: Run** `mvn -q verify` and `mvn -q test -Djvm=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin/java` — Expected: PASS.
- [ ] **Step 4: Commit** `Sweep the deferred lists after phase 8` and `Report phase 8`.
