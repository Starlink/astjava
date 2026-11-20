# Pure-Java AST: phase 8 report

## Summary

Phase 8 ported jniast's SelectorMap and STC classes, and C's `astRemoveRegions`, on 19 November 2026, following the plan [`2026-11-19-pure-java-ast-phase8-stc.md`](../superpowers/plans/2026-11-19-pure-java-ast-phase8-stc.md); with phase 7's Region classes it completes the spec's "Later phases" item 6, Regions and STC.
`SelectorMap`, `AstroCoords`, `Stc` and its four subclasses `StcResourceProfile`, `StcSearchLocation`, `StcCatalogEntryLocation` and `StcObsDataLocation` now construct, transform, compare, simplify, dump and load as C's do.
Their public members are exactly jniast's, and `JniastApiTest` passes with all seven classes in scope; everything else new (`removeRegions`, the Stc constructors taking KeyMaps, `StcObsDataLocation.setObs`, the AstroCoords key constants) is package-private.
Every Mapping and Frame class removes its Regions as C's `astRemoveRegions` does (package-private `removeRegions`, which Plot will call), and `Region.cast` copies a Region into the class of a Region it is an instance of (`isInstance`, which would accept a subclass), which changes no result while jniast's Stc is abstract, since no concrete Region class is then a subclass of another.
`Region.dump` now writes a Region's own Negated, Closed, MeshSize, FillFactor and Adaptive values where they are set, as region.c's `Dump` does, which changed one existing dump (a PointList with Closed set).
Every fixture of C's corpora that holds a SelectorMap or an StcResourceProfile now reads, writes and simplifies as C's does, and no deferred list names a class of this phase; of the Rust port's tests for these classes and for `remove_regions`, all are translated except `error_ellipse_licence_is_still_needed`, which checks the Rust port's own licence.
Four new C probes and two converted oracle files give the expected values.
The work found 6 defects in the C library, which were reported to the user privately and are not described here.
Against the branch base `55eba4c` (the end of phase 7) the work adds 2,062 lines of main code and 4,058 lines of tests, and removes 18 and 148; `mvn verify` passes with 4,649 tests on JDK 21 (4,555 at the end of phase 7), and the tests pass on the JDK 27 runtime.

## What was ported

| Task | What | Main lines | Test lines |
| --- | --- | ---: | ---: |
| 1 | `removeRegions` on `Mapping`, `Frame`, `Region`, `CmpMap`, `TranMap`, `RateMap`, `SwitchMap`, `CmpFrame` and `FrameSet` | 277 | 670 |
| 2 | `SelectorMap` (selectormap.c: Transform, Equal, MapMerge, Copy, Dump and load) | 366 | 1,238 |
| 3 | `Region.dump` writing a Region's own attribute values; `Stc` (stc.c: the encapsulated Region and AstroCoords, every override its vtab installs, dump and load), `AstroCoords` and `StcResourceProfile` | 1,180 | 1,740 |
| 4 | `StcSearchLocation`, `StcCatalogEntryLocation`, `StcObsDataLocation` (with its observatory position) and `Region.cast` within the Stc hierarchy | 254 | 547 |
| 5 | The deferred sweep and this report | | |

Lines are those each task's commits add, the fix commits included; summed, they exceed the branch totals by the lines a later task changed again.
The test lines include the probe sources and outputs and the converted oracle files.
Each task was done by one implementer subagent, reviewed by a second, and verified in the controlling session before it was recorded.
The reviews found no Critical problem and three Important ones: a half-length tolerance for the fitted Error Ellipse read off C's output rather than derived, and then a derived bound whose precondition a correct port would fail on one or two platforms in a hundred (Task 3, two fix rounds); and a statement that C's region.c `Cast` always crashes, where it runs once a Region description has been read (Task 4, one fix round).
Tasks 1 and 2 were approved at the first review.
The tasks were committed between 12:51 (Remove the Regions from a Mapping) and 17:36 on 19 November, after the plan at 12:09.

New main classes: `SelectorMap`, `AstroCoords`, `Stc`, `StcResourceProfile`, `StcSearchLocation`, `StcCatalogEntryLocation`, `StcObsDataLocation`.
New test classes, with their tests: `RemoveRegionsOracleTest` (1), `RemoveRegionsTest` (20), `SelectorMapTest` (13), `SelectorMapProbeTest` (8), `JniastSelectorMapTest` (2), `RegionStcOracleTest` (6), `StcProbeTest` (24), `AstroCoordsTest` (2), `StcTest` (1).
Existing test classes gained the cases held back for these classes: `RegionsTest`, `RegionsCTest`, `SwitchMapTest`, `EqualTest`, `RateOracleTest`, `ObjectIdOracleTest`, `FrameInMapSlotTest`, `ConstructorChecksTest` and `JniastApiTest`.
New probes: `remove_regions_extra_probe.c`, `selectormap_probe.c`, `region_dump_probe.c` and `stc_probe.c`; converted oracles: `remove_regions.json` and `region_stc.json`.
`docs/rust-c-differences.md` has 11 new entries (five SelectorMap, four Stc, two `RemoveRegions`), and its entry on casting a Region into the class of another Region is rewritten.

## Departures from the plan

The [rulings](2026-11-19-phase8-rulings.md) give each with its reason and cost; these change what later work builds on.

- **C does not load a SelectorMap without `Reg1`.**
  The plan's "Taken from C" item 6 listed it among the dumps C reads; C fails with `AST__OBJIN` from releasing a null pointer, and Java reports `AST__LDERR`.
- **`Region.dump` reads the Region's own fields directly**, rather than through the package-private raw accessors the plan's Task 3 named; nothing else needed them.
- **The fitted Error Ellipse's axis ends are not compared** (the plan's fallback), but its centre and two half-lengths are, within bounds derived in `RegionStcOracleTest`'s Javadoc; the half-length bound for this platform's fit is worked out after the fact from a bit-for-bit repeat of the fit in the test.
- **`StcObsDataLocation`'s observatory position is held as any `AstObject`**, since C's loader reads an Object of any class there; `setObs` takes a PointList, as C's `astStcSetObs` does.
- **The plan's cast rows do not reach C's `Cast`.**
  `astFindFrame` and `astConvert` between Stcs never call region.c `Cast`, so `Region.cast` is pinned by Java-only tests, and one C row: the probe's last case reads a Region description (which C needs before `Cast` can run) and then converts two CmpFrames of Stcs in different Domains, which C does not convert.
- **jniast's `StcTest` is translated in part**: `testConstructors` runs; `testReads` and `testChandra` need XmlChan.
- **No sweep commit**: Tasks 1 to 4 removed every deferred entry and deferral note for their classes, and rewriting the lists changed nothing.

## Gates

| Gate | Before | After |
| --- | --- | --- |
| `rust-tests.txt` entries | 318 | 283: 35 removed as translated (SelectorMap 25, `astRemoveRegions` 4, Stc and AstroCoords 6), none added |
| `simplify.txt` entries | 70 | 60 (8 SelectorMap, 2 StcResourceProfile) |
| `simplify-cases.txt` entries | 57 | 48 (8 SelectorMap, 1 StcResourceProfile) |
| `full-dumps.txt`, `read-warnings.txt`, `serialisation.txt` entries | 74, 76, 75 | 62, 64, 63 (10 SelectorMap and 2 StcResourceProfile each) |
| `framesets.txt`, `headers.txt`, `oracle-rows.txt`, `wcsconv.txt` entries | 1, 0, 0, 1 | unchanged |
| `remove_regions` oracle cases run | none | 9 of 11; `normmap_region` and `normmap_cmpframe` skipped by name (NormMap, not in jniast) |
| `region_stc` oracle cases run | none | 10 of 10 in each of `stc_matches_c`, `full_dumps_match_c` and `c_dumps_read_back_as_c_reads_them`, and the overlap, AstroCoords and unknown-key tests |
| `object_id.json` cases run | 24 | 26 (`selectormap`, `selectormap_part_ids`, which the Rust test excludes and which matches C) |
| `EqualTest` SelectorMap cases, `RateOracleTest` SwitchMap cases | 0, 5 | 3, 8 |
| `FrameInMapSlotTest` fixtures that must read | 28 | 34 (none refused) |
| `testregions.c` (as `test_regions_c.rs`) | 16 of 18 | 17 of 18 (`checkRemoveRegions`); `region_gaps_still_differ` listed as Rust-only |
| jniast's tests | | `AstTest.testSwitchMap`, `AstroCoordsTest` (2 of 2), `StcTest.testConstructors` (1 of 3) |
| Tests | 4,555 | 4,649 |

Every oracle and probe test asserts the number of cases it ran.
Every test function of `test_selectormap.rs`, `test_remove_regions_oracle.rs` and `test_region_stc_oracle.rs`, and every test naming SelectorMap, an Stc class, AstroCoords or `remove_regions` in `test_switchmap.rs`, `test_regions.rs`, `test_regions_c.rs`, `test_equal_oracle.rs`, `test_rate_oracle.rs`, `test_object_id_oracle.rs` and `test_constructor_checks.rs`, is translated; `rust-tests.txt` keeps only `error_ellipse_licence_is_still_needed` of this phase's entries.
Three Rust meta-tests that name these classes while checking the Rust port's own files are neither translated nor listed (see Gaps and risks).
Rewriting the lists with `-Dast.writeDeferred=true` changes nothing.
`mvn verify` passes on JDK 21 (4,649 tests, Error Prone, Checkstyle and PMD clean), and the tests pass on the JDK 27 runtime; no Java 8 runtime is installed on this machine.

## Tolerances

SelectorMap transformations and dumps, `removeRegions` results (dumps compared byte for byte), plain-Frame Stc dumps, and every dump C wrote and Java reads back are compared exactly.
Values that pass through `java.lang.Math` are compared within a bound derived in the test's Javadoc:

| Where | Bound | Derivation |
| --- | --- | --- |
| `RegionStcOracleTest` sky dumps (`SKY_TOL`) | 1e-12 | The `SkyFrame.offset2` chain of a Circle's second point, bounded at 3e-14 in `RegionEllipseOracleTest` |
| `RegionStcOracleTest` fitted Error Ellipse centre | 1e-12 (`SKY_TOL`) | The Circle's centre converted from Galactic to ICRS (sines and cosines, a product of rotation matrices, then `atan2` and `asin`): a few ulps of values below 2 |
| `RegionStcOracleTest` fitted Error Ellipse half-lengths | computed in the test (1.05e-12 here) | Each mesh coordinate within 1e-14; the primary within 1.5e-13 of C's whatever the direction; the secondary as C's first-order rounding at C's direction (whose precondition holds by a factor of 1.3e4), plus the curvature difference between the two directions (1.3e-17), plus this platform's rounding bounded after the fact from a repeat of its fit that must reproduce the dumped Ellipse bit for bit |
| `RegionsCTest.checkRemoveRegions` | 1e-10 | C's own tolerance in `testregions.c` |

The fitted Ellipse's axis ends are not compared: on the near-circle the fitted direction follows the last bits of the mesh, so no bound on them can be derived, as `docs/rust-c-differences.md` records.
`SelectorMapTest` keeps the Rust tests' 1e-10 tolerances on WinMap outputs that could be compared exactly.

## Departures from C and from Rust

Each is an entry of [`docs/rust-c-differences.md`](../rust-c-differences.md), under "Mappings" (SelectorMap) and "Regions" (`RemoveRegions`, the Stc classes and `Cast`).

- **C's quirks are kept** where C's result is defined: SelectorMap's Transform negates and re-closes its own Regions and leaves the attributes set, so a dump after use writes `Closed = 1`; an Stc's Transform and GetBounded leave Negated set on its encapsulated Region, so `test("Negated")` is true after use; two equal 1-D SelectorMaps applied forward in series simplify to a UnitMap, as in C, since the project follows C where its result is defined; a FrameSet component of a TranMap, or a changed one of a RateMap or SwitchMap, becomes a UnitMap when the Regions are removed, losing its Mapping.
- **Where C's result is undefined or C fails for a reason unrelated to the input, Java gives an error or C's documented result.**
  An Stc dump without its Region or with a missing AstroCoords element, and a SelectorMap dump without Regions, give `AST__LDERR`; a `Begin Stc` gives `AST__LDERR`, since jniast's Stc is abstract; casting a Region into the class of a Region that is not its own class or a parent gives null, the result region.c `Cast` documents.
- **Non-finite coordinates are bad**: SelectorMap's Transform treats a NaN or infinite input as bad, by the library's rule, where C tests `AST__BAD` only.
- **Where Rust departs from C, Java follows C**: SelectorMap's simplification by identity for any Region class, its merge of equal neighbours, its in-place Transform and C's constructor messages; the Regions a container's `removeRegions` turns into UnitMaps; the Stc Negated quirk; the cast within the Stc hierarchy.

## Defects found in the C library

The tasks and the sweep found 6 defects in the pinned C library (`3d3f255f`).
At `1d0714fb`, the tip of the upstream pull request (PR #104) the C library is being hardened in, 1 is still present.
They were reported to the user privately, for upstream, and are not described in this repository beyond the behaviour `docs/rust-c-differences.md` records for each departure.
Java follows C in every case that has a defined result, and gives an error or C's documented result where C's result is undefined.

## Gaps and risks

- **Runtimes.**
  The tests ran on JDK 21 and the JDK 27 runtime on AArch64 macOS only; CI's x86-64 Linux runtimes are the first to exercise the Error Ellipse bounds there, whose platform part is computed from each platform's own fit.
- **`rust-tests.txt` has no automated checker.**
  The sweep matched each Rust test naming this phase's classes against the Java test methods by name and checked each mismatch by hand.
  Three tests in those files are neither translated nor listed: `test_guide.rs::guide_mentions_no_unported_class`, `test_mapmerge_audit.rs::no_checklist_is_orphaned` and the class-name check of `src/serial/object_text.rs`, which test the Rust port's own documents and files; phase 7 found about 350 such unlisted tests outside the Region files.
- **No C value pins `Region.cast` between two Regions.**
  Four texts said or implied that C's `astConvert` between CmpFrames of Stcs casts one Stc into the class of the other with `astCastCopy`: the header of `stc_probe.c` (lines 31-34, the reason its last case uses Stcs of one class), the Javadoc of `StcProbeTest.aCmpFrameOfStcsInOtherDomainsDoesNotConvert` ("into the class of the target's component"), the Cast entry of `docs/rust-c-differences.md`, and Task 4's ruling on that entry; Task 4's report also said an lldb breakpoint showed `astCastCopy` reached from region.c `Cast`.
  It is not: the `astCastCopy` calls `astConvert` makes come from cmpframe.c `Cast`, a CmpFrame into a CmpFrame, and region.c `Cast` is given the target's picked axes, a Frame (cmpframe.c `PartMatch`, region.c `SubFrame`), or a whole CmpFrame (`ComponentMatch`), and casts the Region's current Frame.
  The four texts are corrected; since only the protected `astCast` gives region.c `Cast` a Region to cast into, `Region.cast` between Stcs is pinned by Java-only tests, and C's crash before a Region description is read is the only effect of the CmpFrame path that the probe pins.
- **Minor findings from the reviews** were fixed before the branch was finished: `RemoveRegionsTest` now has C values for a SwitchMap's inverse selector and for the RateMap and SwitchMap cases of a FrameSet component turned into a UnitMap, and checks the probe's case names; `SelectorMap` shares `SwitchMap`'s neighbour cancellation and its Javadoc describes its dump and its 1-D merge; `StcProbeTest` has C values for simplifying a negated Stc and one whose AstroCoords Regions are remapped; `RegionStcOracleTest` refits the Error Ellipse with `Ellipse`'s own package-private steps.
- **The pin will change some results.**
  PR #104 (`55cd143d`) makes C's `Cast` in region.c, cmpframe.c and frameset.c refuse a class the object does not belong to, so a FluxFrame and a SpecFluxFrame no longer convert into each other and a CmpFrame no longer casts into a SpecFluxFrame.
  When the pin moves past it, `SpecFluxFrameTest`, the CmpFrame cast tests and the CmpFrame/SpecFluxFrame and Region cast entries of `docs/rust-c-differences.md` need their C values updated.

## Future work

- **A checker for `rust-tests.txt`**, so that a Rust test file neither translated nor listed fails a test.
- **XmlChan**, for jniast's `StcTest.testReads` and `testChandra` and the `teststc` fixtures of the Rust port, which are STC-X documents.

## What the next phase takes

The next phase is the spec's "Later phases" item 7: **Plot**, after it is ported in Rust, behind jniast's `Grf` interface; jniast's `DefaultGrf` is GPL, so a Graphics2D implementation is either written afresh or relicensed by its authors.
Of jniast's classes, only `Plot`, `Grf` and the `grf` package (item 7) and `XmlChan` with the `xml` package (item 8) are not yet ported.
Plot builds on this phase and the last: it calls `removeRegions` on the Mapping it draws through, and needs the Region functions phase 7 left for it (`astRegTrace`, `astRegSplit` and the boundary breaks of `CmpRegion` and `Prism`) and the Plot methods of `CmpFrame` (`Abbrev`, `Centre`, `Fields` and `Gap`).

## Rulings

Every decision made while executing the plan where it was wrong, unclear or silent, every departure from Rust, C or jniast and every C quirk kept is in [`2026-11-19-phase8-rulings.md`](2026-11-19-phase8-rulings.md), with its reason and its cost if wrong: 55 rulings, 9 from the plan's Shared Design, 43 by the implementers of Tasks 1 to 4, 1 by the controller and 2 in the sweep.
