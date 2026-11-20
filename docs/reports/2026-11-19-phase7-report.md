# Pure-Java AST: phase 7 report

## Summary

Phase 7 ported jniast's Region classes on 18 and 19 November 2026, following the plan [`2026-11-18-pure-java-ast-phase7-regions.md`](../superpowers/plans/2026-11-18-pure-java-ast-phase7-regions.md).
`Region` and its shapes `Box`, `NullRegion`, `Interval`, `PointList`, `Circle`, `Ellipse`, `Polygon`, `Prism` and `CmpRegion`, which no earlier phase declared, now construct, transform, bound, mesh, overlap, mask, map, simplify, compare, dump and load as C's do.
Their public members are exactly jniast's, and `JniastApiTest` passes with all ten classes in scope; everything else new (`PointSet`, `RegionOverlap`, `RegionMask`, `RegionMerge`, the Frame line geometry, `normBox` and `frameGrid`) is package-private.
A `Region` is an abstract `Frame`, and so a `Mapping`, as in C: it encapsulates a FrameSet and forwards Frame behaviour to its current Frame as `FrameSet` does, so a Region can sit in a FrameSet, a CmpFrame or a CmpMap, and be the template of `findFrame` and `convert`.
Every fixture of C's corpora built only from these classes now reads, writes and simplifies as C's does: 121 rows left `simplify.txt` and 68 left `simplify-cases.txt`, and 123 left each of `full-dumps.txt`, `read-warnings.txt` and `serialisation.txt`.
What still keeps fixtures and tests out is SelectorMap and the Stc classes (the next phase), Moc and MocChan (not in jniast), and the Mapping classes jniast lacks.
Of the Rust port's Region tests, every test function is translated or listed in `deferred/rust-tests.txt` with its reason; 21 Region oracle files and `box_axin` were converted with `CasesToJson`, and every case of each runs.
Eleven new C probes give the values no oracle held; four of them link a C file corrected by one line (three C files in all), because the pinned library crashes or fails on the paths they need (see "Patched C builds of four probes").
The work found 28 defects in the C library, which were reported to the user privately and are not described here.
Against the branch base `05f8461` the work adds 12,893 lines of main code and 22,350 lines of tests, and removes 4 and 753; `mvn verify` passes with 4,540 tests on JDK 21 (3,848 at the end of phase 6), and the tests pass on the JDK 27 runtime.

## What was ported

| Task | What | Main lines | Test lines |
| --- | --- | ---: | ---: |
| 1 | Frame line geometry (`lineDef`, `lineOffset`, `lineCrossing`, `lineContains`) on Frame, SkyFrame (great circles) and FrameSet; `normBox` on Frame, SkyFrame, CmpFrame and FrameSet; `frameGrid`; `PointSet` with pointset.c's Dump, Load and Equal | 1,047 | 3,429 |
| 2 | `Region` (region.c: state, Frame forwarding, attributes, uncertainty, mesh and grid, bounds, `mapRegion`, `negate`, Equal, Cast, the generic Simplify, dump and load, the per-class hooks) and `Box` | 4,068 | 2,557 |
| 3 | `RegionOverlap` (`OverlapX`), `RegionMask` (`Mask<X>` through phase 6's resampling), `showMesh` | 813 | 1,280 |
| 4 | `NullRegion`, `Interval`, `PointList` (with PointList's own mask), NullRegion's same-class Simplify | 1,629 | 3,102 |
| 5 | `Circle`, `Ellipse`, the best-fit Circle and Ellipse | 1,262 | 3,600 |
| 6 | `Polygon` (without `Convex<X>`, `Outline<X>`, `Downsize` and `RegTrace`), its same-class Simplify and SimpVertices, and C's `dist` output of `lineCrossing` | 870 | 2,375 |
| 7 | `Prism` and `CmpRegion` (without `RegTrace`, `RegSplit` and their boundary breaks, which serve Plot), region.c's `BndBaseMesh`, Prism's `MapList` | 1,944 | 3,114 |
| 8 | Each class's own Simplify and MapMerge, `RegionMerge`, `RegionsCTest`, `JniastRegionTest`, the simplification tests of `RegionsTest` | 1,390 | 1,911 |
| 8b | The rest of `test_regions.rs` as `RegionsTest`, with a new probe | | 1,846 |
| 9 | The deferred sweep and this report | | 218 |

Lines are those each task's commits add, the fix commits included; summed, they exceed the branch totals by the lines a later task changed again (Task 3 replaced Task 2's interim stubs, for example).
The test lines include the probe outputs (2,521 lines in all, and the JSON outputs of `region_frameset_probe` and `region_compound_probe`) and the converted oracle files.
Each task was done by one implementer subagent, reviewed by a second, and verified in the controlling session before it was recorded; Task 8 was split, and a second implementer (Task 8b) translated the rest of `test_regions.rs` while Task 8 was reviewed.
The reviews found no Critical problem and eight Important ones, each fixed in one fix round: a departure not recorded (an Adaptive Region's attribute value holding `%`, Task 2); a mask grid whose pixel count overflowed a `long` (Task 3); a PointList left changed when `regPins` threw, a NullRegion from `mapRegion` that could not give its bounds, and probe rows taken from a crashing C build (Task 4); a tolerance derived too tightly for a sky dump (Task 5); `overlap` raising `AST__INTER` for CmpRegions holding a PointList, where C answers (Task 7); and a test helper that never took effect (Task 8).
A ninth, a Task 4 minor about Interval's `OneToOne` that the controller re-graded, was withdrawn when C was read again.
Tasks 1, 6 and 8b were approved at the first review.
The tasks were committed between 23:11 on 18 November (Add Frame line geometry, NormBox, FrameGrid and PointSet) and 05:31 on 19 November (Report phase 7), after the plan at 22:27.

New main classes: `PointSet`, `Region`, `Box`, `NullRegion`, `Interval`, `PointList`, `Circle`, `Ellipse`, `Polygon`, `Prism`, `CmpRegion`, `RegionOverlap`, `RegionMask`, `RegionMerge`.
New test classes, with their tests: `FrameLineTest` (6), `NormBoxTest` (7), `FrameGridTest` (4), `PointSetTest` (7), `BoxRegionTest` (38), `BoxAxInTest` (1), `RegionBoxOracleTest` (3), `RegionAttrTestOracleTest` (2), `RegionAttribOracleTest` (1), `RegionAttributeValueTest` (1), `RegionObjectOracleTest` (1), `RegionEqualOracleTest` (1), `RegionErrorsOracleTest` (13), `RegionFrameSetTest` (9), `RegionOverlapOracleTest` (1), `RegionMaskOracleTest` (1), `RegionMapRegionOracleTest` (4), `RegionShowMeshOracleTest` (2), `RegionMaskArgumentsTest` (10), `RegionOverlapMaskProbeTest` (6), `NullRegionTest` (29), `IntervalTest` (47), `PointListTest` (42), `RegionIntervalOracleTest` (2), `RegionIntervalRemapOracleTest` (2), `RegionNullTranOracleTest` (1), `RegionBoundedOracleTest` (4), `CircleTest` (46), `EllipseTest` (35), `RegionBestFitTest` (7), `RegionEllipseOracleTest` (3), `RegionFrameOracleTest` (5), `RegionSkyTest` (6), `PolygonTest` (43), `RegionPolygonOracleTest` (2), `RegionMaskingTest` (3), `CmpRegionTest` (47), `PrismTest` (38), `RegionOverlapTest` (22), `RegionMaskTest` (8), `RegionGridTest` (2), `RegionCmpRegionOracleTest` (4), `RegionCmpListOracleTest` (2), `RegionCompoundAttrOracleTest` (2), `RegionsTest` (110), `RegionsCTest` (16), `JniastRegionTest` (14).
Existing test classes gained the cases their in-test skips had held back for these classes: `MatchAxesTest`, `FrameClassAttrsTest`, `FrameAxisAttrsTest`, `FrameTest`, `IsLinearTest`, `NormPointsTest`, `ReportTest`, `ConvertTest`, `SectionLoadTest`, `CmpFrameTest`, `SerialisationTest`, `KeyMapSerialTest`, `ShowToStringOracleTest`, `TransformValidationTest`, `FrameInMapSlotTest` and `ObjectIdOracleTest`.
`docs/rust-c-differences.md` has a new "Regions" section with 41 entries, and three new entries under "Frames".

## Departures from the plan

The [rulings](2026-11-19-phase7-rulings.md) give each with its reason and cost; these change what later work builds on.

- **The simplification lists changed from Task 2 on, not only in Task 8.**
  The plan held `simplify.txt` and `simplify-cases.txt` for Task 8, but both gates check both ways, so a row that matches C once its classes are present must leave; each task removed its rows, and Task 8 removed the 29 rows that needed a class's own Simplify.
- **NullRegion's and Polygon's same-class Simplify came early** (Tasks 4 and 6): a NullRegion mapped by `mapRegion` to fewer axes could not give its bounds without it, and `RegionMaskingTest` needs Polygon's; neither changes the class, which is what the plan deferred.
- **`lineCrossing` returns C's `dist`.**
  Task 1 left it out, as the plan's interface did, believing only `Downsize` read it; Polygon's `Polywidth`, which every Polygon's `Cache` calls, reads it too, so Task 6 added it and extended the Task 1 probe.
- **`RegionMask.mask` carries the mask value in its `PixelArray`** rather than as a separate `double`, which cannot hold every `long`.
- **`mapRegion` and `getNegation` arrived in Task 2**, because `GetUncFrm` needs the protected `MapRegion`.
- **Prism's `MapList` is an override in `Prism.java`**: `Mapping.mapList` is already per class, as C's `astMapList` is, so neither `Simplifier.java` nor `CmpMap.java` changed.
- **More probes than the plan named.**
  The plan named `frame_line_probe`, `region_defunc_probe`, `region_frameset_probe` and `region_sky_probe`; the tasks added `region_overlap_mask_probe`, `region_null_interval_pointlist_probe`, `region_circle_ellipse_probe`, `region_polygon_probe`, `region_compound_probe` and `regions_cross_probe` for the Review Focus 5 rows and for values no oracle held.
- **The slow FrameGrid row left the probe.**
  A SkyFrame grid of zero longitude width walks 2^31 parallels through C's undefined `int` conversion as AArch64 performs it, taking about 15 s; no test pins that undefined case, and the instant zero-width Frame row stays.
- **Task 8 was split** into Task 8 (the Simplify and MapMerge of every class, `RegionsCTest`, `JniastRegionTest`) and Task 8b (the other 96 translated tests of `test_regions.rs`).
- **"Taken from C", item 3** (a grid with no point inside gives C's `astSetNpoint` error) is pinned with a PointList whose points avoid every grid point, since the quirk file's sky-Polygon example does not reproduce at the pin.
- **Two in-test skips outside the plan's list** were for this phase's classes and are gone: `ObjectIdOracleTest` (CmpRegion and Prism cases) and the Region-in-CmpMap control of `FrameInMapSlotTest`.
- **This report and its rulings are dated 19 November**, the day they were written, not 18 November as the plan's file names say.

## Gates

| Gate | Before | After |
| --- | --- | --- |
| `rust-tests.txt` entries | 240 | 318: 23 removed as translated, 16 re-reasoned to name only what is still absent, 101 added (below) |
| `simplify.txt` entries | 191 | 70 |
| `simplify-cases.txt` entries | 125 | 57 |
| `full-dumps.txt`, `read-warnings.txt`, `serialisation.txt` entries | 197, 199, 198 | 74, 76, 75 |
| `framesets.txt` entries | 3 | 1 (`lsst2.ast`, SplineMap) |
| `headers.txt`, `oracle-rows.txt`, `wcsconv.txt` entries | 0, 0, 1 | unchanged |
| Region oracle cases run | none | every case: `region_attr_test` 11, `region_attrib` 8, `region_box` 4, `region_cmplist` 6, `region_ellipse` 3, `region_equal` 5, `region_interval` 5, `region_interval_remap` 3, `region_mask` 18, `region_nulltran` 3, `region_object` 6, `region_overlap` 10, `region_polygon` 3, `region_showmesh` 7, `box_axin` 9, and the tables of `region_bounded`, `region_cmpregion`, `region_compound_attr`, `region_errors`, `region_frame`, `region_grid` and `region_mapregion` |
| `object_id.json` cases run | 20 | 24 (CmpRegion and Prism) |
| jniast's `RegionTest` | none | 14 of 14 |
| `testregions.c` (as `test_regions_c.rs`) | none | 16 of 18 tests, checkEllipse Errors 15 to 17 with C's result; 2 listed |
| Tests | 3,848 | 4,534 |

The shared oracles were enabled class by class, each test asserting the number of cases it ran, and Task 7 removed the last filters.
After the 101 additions, the Region entries of `rust-tests.txt` name only what is still absent: Moc (36) and MocChan (19), not in jniast; SelectorMap (25), the Stc classes and AstroCoords (6) and `astRemoveRegions` (4), the next phase; the functions jniast lacks, `astGetRegionDisc` (7), `astCirclePars` (4), `astConvex` (3), `astOutline` (2 and `test_huge.rs`), `astDownsize` and `astEllipsePars` (1 each), and `astGetRegionMesh` (1, `test_interval.rs::mesh_of_an_unbounded_interval_names_the_class`, which pins that function's own `AST__MBBNF`; the package-private mesh of an unbounded Interval gives region.c `RegBaseMesh`'s `AST__INTER`, which `IntervalTest.meshOfUnboundedIsAnError` pins); Rust-only API (5); and one "no C value", a Rust test whose points lie on an Ellipse's boundary to within rounding.
No other deferred list names any of this phase's classes.
Every test function of the Rust port's `regions` test program (48 files, `src/regions/box_region.rs` and `src/regions/moc.rs`), of `test_selectormap.rs`, `test_remove_regions_oracle.rs`, `test_frame_in_map_slot.rs` and of `traits_compile.rs`'s three checks naming the Region trait is translated or listed.
Rewriting the lists with `-Dast.writeDeferred=true` changes nothing.
`mvn verify` passes on JDK 21 (4,534 tests, Error Prone, Checkstyle and PMD clean), and the tests pass on the JDK 27 runtime; no Java 8 runtime is installed on this machine.

## Tolerances

Values that reach only arithmetic and `sqrt` are compared exactly: plain-Frame Boxes, Intervals, PointLists, NullRegions, Circles and Polygons, masks, overlaps and the simplification fixtures' structure.
Values that pass through `sin`, `cos`, `atan2`, `asin` or `acos` are compared within a bound derived in the test's Javadoc, never from the drift seen here:

| Where | Bound | Derivation |
| --- | --- | --- |
| `FrameLineTest` | 1e-12 for sky lines, 1e-6 for the sky `dist` | Unit vectors within `e` = 1.1e-15 of C's (5 ulp in each component: `sin` and `cos` within 2 ulp of C's, and the product's rounding); a line's pole turned by at most 150`e` where its two points are 0.013 rad apart, a crossing moved by up to 268`e`, and longitudes amplified 220× at 0.0045 rad from the pole: at most 445`e` in all, 5e-13; `acos` at distance 0 for `dist` |
| `EllipseTest`, `RegionFrameOracleTest`, `RegionEllipseOracleTest` | 1e-12 (1e-14 for plain-Frame Ellipse dumps) | At most three to six libm calls, amplification below 10, values at most 10 |
| `RegionSkyTest` sky Circle bounds | 1e-11 | `atan2` amplification at most 100 at 0.01 rad from a pole |
| `RegionBestFitTest` | 1e-9 | The quadratic fit divides ulp errors by radius differences of about 1e-4 |
| `RegionPolygonOracleTest`, `CmpRegionTest`, `PrismTest`, `RegionGridTest` sky values | 1e-12 | The `offset2` chain, about 4e-14 |
| `CmpRegionTest`, `PrismTest` Circle meshes | 1e-13 | `c + r cos` and `c + r sin`, at most 2.9e-15 |
| `RegionsTest` bounds | 1e-12 | A few calls of `sin`, `cos`, `asin` and `atan2` per mesh point, values below 2π, amplified up to 10× near a pole: under 1e-13 |

Every discrete result that a last-bit difference could change (a point's membership, a pixel of a mask, a mesh point count) has a guard test that measures its clearance from the boundary, the on-edge band or the integer it is truncated to, so a test case near a boundary fails its guard rather than its comparison.

## Departures from C and from Rust

Each is an entry of [`docs/rust-c-differences.md`](../rust-c-differences.md), "Regions" section, and its three new "Frames" entries (PointSet accuracies, `astNormBox` on a permuted CmpFrame, `astFrameGrid` counts beyond `int`); they fall into four kinds.

- **Where C's result is undefined, Java gives an error or C's evident intent.**
  Where C reads or writes past an array, dereferences a null pointer or loops for ever, Java raises `AST__INTER` or C's own status (`BndBaseMesh` making its own CmpRegion again for a Region with no mesh, a dump whose uncertainty or Frame is of another class or whose Frame part needs the current Frame, `RegGrid` of an unbounded Region, `MapRegion` picking axes that are not a Region, an Interval overlap with an axis of code 6 on several axes, a NullRegion's bounds with more base than current axes, a Polygon whose edges have no length or that was read with a bad vertex, `Polywidth`, a Prism mesh with other than its base Frame's axis count, `OverlapX` widening more bounds than its arrays hold, a best-fit Ellipse with no usable mesh point); Ellipse's `RegCentre` moves its three points as C's comment says; PointList's `RegPins` checks each of its points, C's stated intent; `Cast` into the class of another Region gives null, where C gives a copy of the wrong class.
- **C's quirks are kept** where C's result is defined: the default uncertainty kept when a Region moves (Rust differs), `RegSetAttrib`'s one-word axis test, the bounds Circle's and Ellipse's `RegBaseMesh` take from their first mesh point, the overlap of a PointList and a Box holding some of its points (4 one way, 1 the other), the stale negation of an Adaptive Region, the empty grid box in `Mask<X>` of a Region whose grid bounds pass the `int` range, a point with a bad value in a PointList, the bounds an Interval and a Circle keep after a move, Circle's unclamped smaller radius in `RegPins`, bad points counted as on a Polygon's boundary, the sky Polygon line through a vertex, the bounded AND of nested unbounded Regions, a Prism's overlap with a negated Region, the truncated MeshSize of a parallel merge, and the Simplify quirks of Box, Circle, Interval and Prism.
- **Non-finite coordinates are bad.**
  NaN and infinite coordinates of a Box, Interval, PointList, Circle, Ellipse and Polygon are treated as `AST__BAD`, by the library's rule, where C rejects only `AST__BAD`.
- **Where Rust departs from C, Java follows C**, with the Rust test translated using C's values: `default_uncertainty_follows_a_moved_box`, `point_with_bad_value_not_inside`, `reg_pins_incomplete_cover_reports_false`, `cmpregion_negated_reversal`, `checkBoxSky`, Circle's `RegPins`, and checkEllipse Errors 15 to 17.

Java also departs where C's own call is mistaken: an Adaptive Region's attribute value holding `%` is kept as given, where region.c passes it to `astSet` as a format; the protected `setEpoch` and observatory setters of a Region set what jniast means.
The departures that the rulings alone recorded at the final review were added to it afterwards (see "Minors fixed after the final review").

## Patched C builds of four probes

Four probes link the pinned library with one of three C files, each corrected by one line, because the unpatched library crashes or fails on the paths they need and so gives no values:

- `region_null_interval_pointlist_probe.c` and `region_compound_probe.c` link a `pointlist.c` whose `RegPins` allocates its mask with the right size; without it, the overlap of a PointList with another Region, and of a CmpRegion or Prism holding a PointList, crashes in most runs.
- `region_circle_ellipse_probe.c` links an `ellipse.c` whose `RegCentre` indexes its points the right way round; without it, every moved Ellipse crashes the library, and an Ellipse given as the uncertainty of another Region is moved.
- `region_mapregion_drop_axis_probe.c` links a `region.c` whose `MapRegion` releases the Mapping split from the caller's only once; without it, mapping or masking a Region through a Mapping that drops an axis its inverse leaves bad fails, and masking a PointList that way crashes (see "Follow-ups after the minors").

Each probe's header gives the build recipe; each was run several times with identical output, and the values agree with C's algorithm as the C source states it.
Task 4's first probe rows had come from the runs of the unpatched build that completed; the review regenerated them with the corrected file, with the same values, and they now come from the deterministic build.

## Defects found in the C library

The tasks, the final review and the follow-ups found 28 defects in the pinned C library (`3d3f255f`): memory errors, undefined behaviour and wrong results, in region.c, the Region class files, pointset.c, frame.c, skyframe.c and cmpframe.c.
27 of them are still present at the tip of the upstream pull request the C library is being hardened in, and one is already fixed there.
They were reported to the user privately, for upstream, and are not described in this repository beyond the behaviour `docs/rust-c-differences.md` records for each departure.
Java follows C in every case that has a defined result, and gives an error or C's evident intent where C's result is undefined.

## Final review

A whole-branch review of the branch from its base `05f8461` to the report as first revised (05:40 on 19 November) found nothing Critical and one Important defect.
It built and tested that state and compared with C the overlaps of 17 Regions taken in every pair and order, 289 in all: every result C defines agrees, and the others are C crashes.
Simplification of Regions inside CmpMaps, the five Review Focus items and a Channel round trip of the 17 Regions also match C.
The Important defect was that `overlap` could throw `StackOverflowError` on ordinary input.
For a Region with no mesh of its own, such as a Prism with an open end or a negated component, region.c `BndBaseMesh` meshes the part inside a box as the CmpRegion ANDing the two, whose `RegBaseMesh` calls `BndBaseMesh` with the same box again; a Circle, Ellipse, Polygon or AND CmpRegion overlapped with such a Prism, in either order, reaches it, and C recurses until the stack overflows.
The CmpRegion that `BndBaseMesh` makes now reports instead that the overlap cannot be determined (`AST__INTER`, with region.c `OverlapX`'s message) and leaves the Regions as they were (in Add Prism and CmpRegion); tests cover both kinds of Prism against each kind of Region in both orders, and the mesh of an AND that takes the same path.
The same pass fixed three minor findings: the `docs/rust-c-differences.md` entry on `Mask<X>`'s empty grid box now covers any Region whose bounds in grid coordinates pass the `int` range, with a probe case of a Box through a ZoomMap of 1e12 (in Add NullRegion, Interval and PointList); a Polygon whose vertices are all one point reports `AST__BADIN` describing the input rather than an internal error (in Add Polygon); and the Javadoc of `Prism.regPins` says that a component whose Closed is set false stays open (in Add Prism and CmpRegion).
After the fixes `mvn verify` passes with 4,540 tests, and the tests pass on the JDK 27 runtime.

Left as minor: the other minor items, which were fixed afterwards.

### Minors fixed after the final review

The user asked for the deferred minor findings to be fixed; each was checked against the code first, and those already fixed by Task 9 or the final review's fixes, and those about process alone, were left.

- **`docs/rust-c-differences.md`** has entries for the departures the rulings alone recorded: the loader's `AST__REGIN` for an uncertainty or Frame of another class and its `AST__BADIN` for a Frame part that needs the current Frame, `RegGrid`'s `AST__MBBNF`, `MapRegion`'s `AST__INTER` for picked axes that are not a Region, `Cast` giving null for another Region class, Negated restored after an error inside `OverlapX`, `Mask<X>` and the components' in-place negation, the bounds Circle's and Ellipse's `RegBaseMesh` take from their first mesh point, Polygon's rejection of non-finite vertices, and the overlap of a PointList and a Box holding some of its points; C's results for an Interval axis of code 6 among two axes (6, though the Intervals are apart) and for a PointList with a duplicated point (5 both ways) are recorded and pinned by new probe cases; the three Task 8 entries have a Rust bullet; the `Mask<X>` entry says that a PointList, whose `Mask<X>` makes no grid box, is not affected; and the `BndBaseMesh` entry says that `OverlapX` can still give a code where it does without the mesh, as for a Circle and the AND of an open Prism and a Circle (4 in Java either way; 4 in C with the AND first, a stack overflow with the Circle first, from a new probe).
- **New tests from C:** `setUnc` on a Box given an uncertainty of three axes or an unbounded one (`BoxRegionTest`, `region_defunc_probe.c`); `LineIncludes` taking a point 5e-11 rad behind a line's start to be on it, and not one 2e-10 rad behind (`FrameLineTest`, `frame_line_probe.c`); the duplicated-point PointList overlap; the status of each `RegionMaskTest` error that C can be given.
- **New tests of Java's departures:** two-axis Intervals with an axis of code 6, a NullRegion's bounds with more base than current axes, and a one-vertex sky Polygon's bounds; a guard that the sheared-Ellipse fit's boundary test is clear of its band by more than 1e-9 relative; `checkEllipseFit` compares the centre; `RegionErrorsOracleTest` checks that every value of its oracle is read once all its tests have run.
- **Derivations and Javadoc:** `FrameLineTest`'s tolerance derivation (`atan2` within 2 ulp; the largest amplifications, 445×, of `off 2 0.17 0` and `cross 4 0`), `FrameGridTest`'s truncated counts (several exact, two relying on `pow` of a perfect power), `RegionBoxOracleTest`'s exact comparison (only correctly rounded operations, and sky mesh counts at least 0.047 from an integer), `Box`'s `(int) Math.pow` comment, `RegionsTest`'s bounds tolerance (amplification near a pole), probe coverage and FITS-header Javadoc, `RegionSkyTest`'s `atan2` accuracy, and `FrameInMapSlotTest`'s floor; Javadoc lines over 120 columns reflowed.
- **Code:** `Box`'s `AST__OBJIN` message says what happened; `Polygon`'s `Polywidth` messages name the class; Circle's and Ellipse's identical fitting methods are one, `Circle.fittedCircleOrEllipse`; the identical copies of C's `round` in `FitsMakeIntWorld`, `TimeFrame` and `KeyMapConvert` are one, `CMath.roundHalfAway`, which `CmpFrame`, `RegionMask` and the other callers use; `ProbeOutput` reports a probe with both a `.out` and a `.json` output rather than taking the `.out`.

`mvn verify` passes with 4,550 tests, the tests pass on the JDK 27 runtime, and rewriting the deferred lists changes nothing.

## Follow-ups after the minors

- **The mask difference the final review saw through a PermMap is not a PermMap problem.**
  Masking a Box or a PointList through `PermMap(2, {1,0}, 1, {1})` or `PermMap(2, {0,1}, 1, {2})`, which drop an axis their inverse leaves bad, gave different results in Java and C because region.c `MapRegion` releases the Mapping split from the caller's twice, so that C fails (and crashes for a PointList); with the second release guarded, C gives Java's mapped Regions and masks value for value.
  `docs/rust-c-differences.md` ("Releasing the split Mapping twice in `MapRegion`") records it, and `RegionMapRegionDropAxisTest` pins the values from the new `region_mapregion_drop_axis_probe.c`, built with that guard, and Java's `AST__INTER` for a Circle, whose picked axis is a Frame that C goes on to use as a Region.
  These are the two new C defects.
- **The two rounding helpers described as C's `round` are now `CMath.roundHalfAway`.**
  winmap.c `PermGet` and fitschan.c's readers of WCSAXES call `round`, so `WinMapMerge` and `FitsWcsKeywords` use the exact version, where their private helpers computed `floor(x + 0.5)`.
  A header with `WCSAXES = 0.49999999999999994` now fails as one with `WCSAXES = 0` does, as in C, where it read one WCS axis; `FitsWcsKeywordsTest.wcsaxesIsRoundedAsCRoundsIt` pins it from the new `fitschan_wcsaxes_round_probe.c`.
  No input reaches the difference in `PermGet`, whose values are whole axis indices.
- **The review of the minors** found only small faults, now fixed: `FrameLineTest`'s unit-vector bound left out the rounding of the product (5 ulp, 1.1e-15, not 4 ulp; the total, 5e-13, is still within its 1e-12) and this report's table now quotes its figures; `FrameGridTest` says `pow(16, 0.5)` must be at most 4, not exactly 4; this report cited commits by hashes that squashing changes, and named `Cast` among the cases that raise an error; two over-long lines, a split string literal and a missing comment on the duplicated-point overlap.

`mvn verify` passes with 4,555 tests, the tests pass on the JDK 27 runtime, and rewriting the deferred lists changes nothing.

## Gaps and risks

- **Runtimes.**
  The tests ran on JDK 21 and the JDK 27 runtime on AArch64 macOS only; no Java 8 runtime is installed here, and CI's x86-64 Linux runtimes are the first to exercise the derived tolerances there (`RegionsTest`'s 1e-12 bounds among them).
- **`rust-tests.txt` has no automated checker.**
  Task 9 swept the Region files by matching each Rust test name, in camel case, against the Java test methods and checked each mismatch by hand.
  The same match over every Rust test file finds about 350 tests outside the Region files neither matched by name nor listed (among them `src/serial/object_text.rs::every_class_name_matches_the_class_its_dump_names`, a Rust-only check over every fixture); many are translated under other names, but some whole files of classes jniast lacks (ChebyMap, NormMap, XphMap, SplineMap, FitsTable) and the Rust port's own meta-tests are listed nowhere.
- **Test-first order** was not kept for `BoxRegionTest` and the other Task 2 tests, or for `RegionsCTest`, `RegionsTest` and `JniastRegionTest`, whose failing runs were shown against the code before the task instead.
- **Rulings that describe C:** several rulings explain a departure by the C behaviour it avoids, as `docs/rust-c-differences.md` does.

## Future work

- **A checker for `rust-tests.txt`**, so that a Rust test file neither translated nor listed fails a test, and a sweep of the files listed nowhere.
- **The functions jniast lacks**, if a caller needs them: `astGetRegionDisc`, `astCirclePars`, `astEllipsePars`, `astGetRegionMesh`, `astConvex<X>`, `astOutline<X>` and `astDownsize`; and `astRegTrace` and `astRegSplit` with Plot.

## What the next phase takes

The next phase, on a branch stacked on this one, is the rest of the spec's "Later phases" item 6:

- **SelectorMap**, which selects a route by the first of its Regions to hold a point; 25 `rust-tests.txt` entries (`test_selectormap.rs`, ten of `test_switchmap.rs`, `test_rate_oracle.rs`, `test_constructor_checks.rs`), 8 `simplify.txt` and 8 `simplify-cases.txt` rows, 10 rows each of `full-dumps.txt`, `read-warnings.txt` and `serialisation.txt`, the SelectorMap cases of `EqualTest`, `RateOracleTest`, `SwitchMapTest` and `ObjectIdOracleTest`, and the six SelectorMap fixtures `FrameInMapSlotTest` expects to fail, whose floor of 28 then becomes 34.
- **`astRemoveRegions`**, which C implements in mapping.c, cmpmap.c, cmpframe.c, frameset.c, region.c, switchmap.c, tranmap.c, ratemap.c and normmap.c; four `rust-tests.txt` entries (`test_regions.rs`, `test_regions_c.rs`, `test_remove_regions_oracle.rs`).
- **`Stc` and its four subclasses** (`StcResourceProfile`, `StcSearchLocation`, `StcCatalogEntryLocation`, `StcObsDataLocation`) **with `AstroCoords`**; six `rust-tests.txt` entries (`test_region_stc_oracle.rs`) and the StcResourceProfile rows of the fixture lists.
- **`Region.cast`** tests `getClass() == other.getClass()`; for the Stc hierarchy it must accept a subclass where C's truncating cast does (an `StcResourceProfile` cast to `Stc`), as `isInstance` would.

## Rulings

Every decision made while executing the plan where it was wrong, unclear or silent, every departure from Rust, C or jniast and every C quirk kept is in [`2026-11-19-phase7-rulings.md`](2026-11-19-phase7-rulings.md), with its reason and its cost if wrong: 130 rulings, 119 by the implementers and 11 by the controller, and one correction by the controller.
