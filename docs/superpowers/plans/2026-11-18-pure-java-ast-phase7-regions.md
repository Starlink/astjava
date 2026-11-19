# Pure-Java AST Phase 7 (Region Classes) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** jniast's `Region` and its shapes `Box`, `NullRegion`, `Interval`, `PointList`, `Circle`, `Ellipse`, `Polygon`, `Prism` and `CmpRegion` construct, transform, bound, mesh, overlap, mask, map, simplify, compare, dump and load as C's do, so that every C fixture built only from these classes reads, writes and simplifies as C's does.

**Architecture:** Port the Rust port's `src/regions` (without `moc.rs`) and the Region parts of `frames`, `mappings/simplify.rs` and `serial/mod.rs` onto the phase 1 to 6 classes.
`Region` is an abstract `Frame` that encapsulates a FrameSet (base Frame: the Frame the Region was defined in; current Frame: the Frame it now represents), forwarding Frame behaviour to the current Frame as `FrameSet` already does, with C's `region.c` as the authority wherever the two differ.
C (`reference/starlink-ast/src/region.c`, `box.c`, `nullregion.c`, `interval.c`, `pointlist.c`, `circle.c`, `ellipse.c`, `polygon.c`, `prism.c`, `cmpregion.c`, `pointset.c`) is final.

**Tech Stack:** as phases 1 to 6: Java 8 (`--release 8`), Maven, JUnit 5, Jackson (test scope), Error Prone, Checkstyle, PMD, Spotless; JDK 21 to build.

**Spec:** `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md` ("Later phases", item 6, first part).
The spec's item 6 is split: this phase is the Region classes; the next phase (on a branch stacked on this one) is SelectorMap, `RemoveRegions` in SwitchMap, TranMap and RateMap, and `Stc` with its four subclasses and `AstroCoords`.
The earlier plans' Shared Design sections and the rulings in `docs/reports/2026-11-1*-phase*-rulings.md` still apply where this plan does not change them.
The scope survey this plan was written from is summarised in Shared Design; its Rust and C line references are the starting points named below.

## Global Constraints

- Every Global Constraint of the phase 1 plan applies unchanged (Java 8, no runtime dependencies, jniast signatures, C at `3d3f255f`, `default` and final `else` throw `AST__INTER`, `MODERN-JAVA` tags, no heuristics the original lacks, expected values from C only, share where C calls `astClone` and copy where C calls `astCopy`, British spelling, one sentence per line in Markdown).
- Public API: exactly jniast's members for the classes of this phase (`reference/starjava/jniast/src/main/uk/ac/starlink/ast`), already listed in `src/test/resources/jniast-api.txt`:
  - `Region` (abstract, `extends Frame`): `getRegionBounds()`, `getRegionFrame()`, `getRegionPoints()`, `getUnc(boolean)`, `setUnc(Region)`, `mapRegion(Mapping, Frame)`, `negate()`, `overlap(Region)` and the seven `OVERLAP_*` constants, `mask(Mapping, boolean, int, int[], int[], Object, Number)`, `maskB/S/I/L/F/D`, `showMesh(boolean, String)`, and get/set `Adaptive`, `Negated`, `Closed`, `MeshSize`, `FillFactor`, and `getBounded`.
  - `Box(Frame, int, double[], double[], Region)`; `NullRegion(Frame, Region)`; `Interval(Frame, double[], double[], Region)`; `PointList(Frame, int, double[][], Region)` and `getListSize()`; `Circle(Frame, int, double[], double[], Region)`, `Circle(Frame, double[], double, Region)`, `Circle(Frame, double[], double[], Region)`; `Ellipse(Frame, int, double[], double[], double[], Region)`; `Polygon(Frame, int, double[], double[], Region)`; `Prism(Region, Region)`; `CmpRegion(Region, Region, int)` with `AST__AND` and `AST__OR`.
  - Each task adds its classes to `JniastApiTest.IN_SCOPE`; nothing else public is added.
- Before porting a class, read Rust `docs/porting-hazards.md` (H1, H4, H9 above all), its `docs/audits/region-*.md`, and every entry naming the class in `docs/issues/c-library-quirks.md`.
- Rust comments cite older C line numbers; find each C function by name in the pinned source.
- Classes still absent after this phase: SelectorMap, Stc and its subclasses, AstroCoords (next phase); Moc, MocChan, FitsTable, NormMap, UnitNormMap, ChebyMap, XphMap, SplineMap, and the other AST classes jniast lacks.
- Values must match C to the oracle tolerances; when one does not, find the C function that computes it before touching the arithmetic (H7).
- Tolerances for values that pass through `java.lang.Math` (sky geometry, great circles, best-fit ellipses) come from an error bound worked out from the computation, never from the drift seen on this machine: CI runs on x86-64 Linux.
- Expected values of a translated Rust test are C's as the Rust test records them; where the Rust test pins a Rust departure from C, the expected value comes from a C probe instead (Shared Design, "Taken from C rather than Rust").

## Review Focus

1. A Box, Circle or Polygon on a SkyFrame that straddles RA 0 or contains a celestial pole: inside/outside of test points, `getRegionBounds`, a `mask` of a small all-sky grid and `overlap` with a second such Region give C's results (test in Task 5 for Box and Circle, extended in Task 6 for Polygon, from a new C probe `region_sky_probe.c`).
2. A Region added to a FrameSet as a Frame, and a Region used as the template of `findFrame` and `convert`: the FrameSet transforms points through it as C does (bad outside the Region), and its dump reads back (test in Task 2, from a new C probe `region_frameset_probe.c`).
3. Changing an attribute after first use (`setNegated`, `setClosed`, `setMeshSize`, a Frame attribute such as `System` on an Adaptive and on a non-Adaptive Region, `setUnc`): the next `tran*`, `getRegionBounds`, `getRegionPoints` and `getUnc` reflect the change, as C's cache resets make them (test in Task 2, values from `region_frameset_probe.c`).
4. `mask` and `maskX` given an array shorter or longer than its grid, bound arrays shorter than `ndim`, a Mapping whose Nout differs from the Region's axes, a null argument, or the generic `mask` given an array type and a `Number` that differ: the rules phase 6 set for `resample` (`AST__DIMIN` before C's checks, longer arrays accepted, jniast's `IllegalArgumentException`, `NullPointerException` naming the argument before the array is touched) and C's status otherwise; never an `ArrayIndexOutOfBoundsException` (test in Task 3).
5. Constructors given what C rejects or treats specially: a null Frame, an uncertainty Region of the wrong dimensionality or that is unbounded, `AST__BAD` or NaN coordinates, a Polygon with fewer than three vertices, a zero-size Box, an unknown `form`, a `CmpRegion` operator other than C's three, a Prism of Regions in Frames that cannot be combined: C's status and message, never a Java runtime exception (tests in each class's task: `region_errors` oracle cases and the class's C probe rows).

---

## Shared Design

### Where the code goes

- `PointSet` (package-private, final, `extends AstObject`): the nested `Begin PointSet` of a Region dump, with `pointset.c`'s Dump, Load and Equal (its accuracy test); registered in `ObjectRegistry`.
  Region code holds its points as `double[ncoord][npoint]`; `PointSet` exists for serialisation and equality, not as a general container.
- `Region` (public abstract): `region.c`'s state (Rust `RegionData`: the FrameSet, the points, Negated and Closed with their set flags, FillFactor, MeshSize, Adaptive, RegionFS, the uncertainty and the default-uncertainty cache), the Frame forwarding, the attribute handling (`RegSetAttrib`: remap the defining points only when Adaptive), uncertainty (`SetUnc`, `GetUnc`, `GetDefUnc`, `GetUncFrm`), `RegPins`, the mesh and grid, bounds, `getRegionPoints`, `mapRegion`, `negate`, Equal, Cast, the generic Simplify (Rust `parent_region_simplify`), dump and load of the Region part, and the abstract per-class hooks of Rust's `RegionGeometry` (`regBaseInside`, `regBaseBox`, `regBaseMesh`, `isBoundedInner`, `remapDefiningPoints` and the others H1 names), kept as overridable methods per class (H1).
- `RegionOverlap` (package-private): Rust `overlap.rs` (C's generic `OverlapX` and `region_bnd_base_mesh`).
- `RegionMask` (package-private): Rust `masking.rs` (`astMask<X>` through phase 6's `Resample` and `PixelArray`).
- One public class per shape, each with its own `transformInto`, hooks, Overlap, Equal, Simplify, MapMerge, dump and load: `Box`, `NullRegion`, `Interval`, `PointList`, `Circle`, `Ellipse`, `Polygon`, `Prism`, `CmpRegion`.
- `RegionMerge` (package-private): Rust `merge_util.rs` and `simplify.rs`'s `region_map_merge`, the merges of neighbouring Regions in a CmpMap.
- `Frame`, `SkyFrame`, `CmpFrame`, `FrameSet`: package-private `lineDef`, `lineOffset`, `lineCrossing`, `lineContains`, `normBox`, `frameGrid`, as C's `frame.c`, `skyframe.c`, `cmpframe.c` and `frameset.c` define them.
- `ObjectRegistry`: a loader per class; `NativeStream.parentOf` already knows the class chain.
- `Region` follows the pattern of `FrameSet`'s overrides of `Frame` (157 `@Override`s) without changing `FrameSet` or the public class hierarchy.

### The mutable-state model (H4)

- Copy where C copies: `Prism` and `CmpRegion` copy their component Regions at construction (`prism.c`, `cmpregion.c` constructors); `setUnc` stores a copy and `getUnc` returns one; `getRegionFrame` returns a copy of the current Frame and the package-private `regionFrameSet()` a copy of the FrameSet, as `region.c`'s `GetRegionFrame` and `GetRegionFrameSet` call `astCopy`.
- Share where C clones: wherever `region.c` or a shape's file calls `astClone` on a component (search each constructor and `MapRegion`), Java shares the object.
- A change of Negated, Closed, MeshSize, the uncertainty, or (on an Adaptive Region) a Frame attribute resets the caches C resets; the Javadoc of each setter says what it resets.
- One thread per object, as the spec says: a Region's caches are filled lazily, so concurrent use of one Region is not supported, and the class Javadoc says so as `Mapping`'s does.

### Taken from C rather than Rust

1. **A Region's default uncertainty is not reset when the Region moves** (`c-library-quirks.md`, the entry on default uncertainty after a move).
   Rust clears it and pins that in `test_box_region.rs` (`default_uncertainty_follows_a_moved_box`); Java keeps C's behaviour, and that test is translated with expected values from a new C probe `src/test/resources/probes/region_defunc_probe.c`.
2. **`testregions.c`'s checkEllipse Errors 15 to 17** (an Ellipse with a Box uncertainty through a full MatrixMap and back), which Rust excludes as `REGIONS_C_GAP_EXCLUDED` in `test_regions_c.rs`: Java must give C's result (an unrotated mapped uncertainty that round-trips), so the cases are translated, not excluded.
3. **`RegBaseGrid` fails when no grid point lies in the Region** (`astSetNpoint` 0 in C): Java gives C's error.
4. **A CmpRegion ANDing two nested unbounded Regions reports itself bounded**, as C does (C quirk); `docs/rust-c-differences.md` records it.
5. The Javadoc does not copy the documentation errors the quirks file lists for `GetRegionPoints`, `Mask<X>` ("badval") and Interval's limits.

### Not ported

- `Moc`, `MocChan`: not in jniast.
- `Polygon`'s `astConvex<X>`, `astOutline<X>` and `astDownsize` (Rust `polygon.rs` lines 34 to 998 and 1241 to 1430), `astGetRegionDisc`, `astCirclePars`, `astEllipsePars`, `astRegTrace`, `astRegSplit`: not in jniast (the last two wait for Plot).
- Their Rust tests, and each test function elsewhere that calls them, go to `deferred/rust-tests.txt` with the reason `<C function> (not in jniast)` or `Moc (not in jniast)`; `test_huge.rs::huge` stays listed.

### Simplification before Task 8

Until Task 8, a Region's Simplify is the Region-generic part only (Rust `parent_region_simplify`: the encapsulated FrameSet's Mapping simplified, the Region rebuilt in its base Frame when that is simpler), which is a correct, if less simplified, result.
Task 8 adds each class's own Simplify branches (the conversions between classes), the MapMerge of each class and `RegionMerge`.
A test that expects a class-changing simplification waits for Task 8, and the task that translates its file lists it in its report.

### Gates and deferred lists

- Each Rust test file `test_<x>.rs` becomes `<X>Test` (`test_box_region.rs` → `BoxRegionTest`, `test_regions.rs` → `RegionsTest`, `test_regions_c.rs` → `RegionsCTest`); each `test_region_<x>_oracle.rs` becomes `Region<X>OracleTest`, reading `src/test/resources/oracle/region_<x>.json` converted from `tests/region_<x>_oracle/cases.rs` with `CasesToJson`.
- An oracle file whose cases span classes of several tasks is enabled class by class: the test asserts the number of cases it ran, and each task that adds a class raises that number; the task that enables the last case removes the filter.
- None of these Rust files are in `rust-tests.txt` (phase 1 never listed them); each task translates its files and lists only the tests that still need something absent, with that reason.
- Each task removes from `rust-tests.txt`, `serialisation.txt`, `full-dumps.txt`, `read-warnings.txt` and `framesets.txt` the entries whose only missing classes are now present (`-Dast.writeDeferred=true`, then check that the diff removes only entries naming the task's classes, and that `PointSet` is among the classes they needed); `simplify.txt` and `simplify-cases.txt` wait for Task 8.
- Each task removes the in-test skips of its classes from the test classes the survey found (`CmpFrameTest`, `ConvertTest`, `EqualTest`, `FrameInMapSlotTest`, `FrameClassAttrsTest`, `FrameAxisAttrsTest`, `FrameTest`, `IsLinearTest`, `MatchAxesTest`, `NormPointsTest`, `ReportTest`, `RateOracleTest`, `SectionLoadTest`, `SwitchMapTest`, `BulkKernelsTest`, `SerialisationCorpusTest`); skips naming SelectorMap or Stc stay for the next phase.

---

### Task 1: Frame line geometry, NormBox, FrameGrid and PointSet

**Files:**
- Modify: `Frame.java`, `SkyFrame.java`, `CmpFrame.java` (and its Javadoc's "not ported" sentence), `FrameSet.java`, `ObjectRegistry.java`
- Create: `PointSet.java`
- Test: `FrameLineTest` (new: `lineDef`, `lineOffset`, `lineCrossing`, `lineContains` on a 2-D Frame and a SkyFrame, from a new C probe `src/test/resources/probes/frame_line_probe.c`), `CmpFrameTest` (the four `astNormBox` entries of `test_cmpframe.rs` in `rust-tests.txt`), `NormBoxTest` (Frame, SkyFrame across RA 0 and a pole, CmpFrame, FrameSet, from the same probe), `FrameGridTest` (`frameGrid` on Frame, SkyFrame, CmpFrame, FrameSet, from the same probe), `PointSetTest` (a load, dump and equality round trip of the `Begin PointSet` of `ast_tester/fixtures/simplify/cap_slamap_07.map`)

**Interfaces:**
- Produces: package-private `Frame.LineDef lineDef(double[] start, double[] end)` (an opaque per-Frame object holding what C's `AstLineDef` holds, or null where C returns NULL), `boolean lineCrossing(LineDef l1, LineDef l2, double[] cross)`, `boolean lineContains(LineDef l, int def, double[] point)`, `void lineOffset(LineDef l, double par, double prp, double[] point)`, overridden by `SkyFrame` with great circles.
- Produces: package-private `void normBox(double[] lbnd, double[] ubnd, Mapping reg)` on Frame, SkyFrame, CmpFrame and FrameSet (C's `astNormBox`), and `double[][] frameGrid(int size, double[] lbnd, double[] ubnd)` (C's `astFrameGrid`, `[naxes][npoint]`).
- Produces: `final class PointSet extends AstObject` with `PointSet(double[][] points)`, `double[][] points()` (a copy), `boolean equalTo(PointSet other)` as `pointset.c` Equal, dump and load.

- [ ] **Step 1: Write the probe** `frame_line_probe.c` against the pinned C: the line functions, `astNormBox` and `astFrameGrid` on the cases named under Files, printing every result with `%.17g`; build it, commit the source and output.
- [ ] **Step 2: Write the failing tests** named under Files.
- [ ] **Step 3: Run** `mvn test -Dtest='FrameLineTest,CmpFrameTest,NormBoxTest,FrameGridTest,PointSetTest'` — Expected: FAIL (methods and class missing).
- [ ] **Step 4: Implement** from C (`frame.c`, `skyframe.c`, `cmpframe.c`, `frameset.c`, `pointset.c`), with Rust's `frames/mod.rs`, `skyframe.rs`, `cmpframe.rs`, `frameset.rs` and `serial/mod.rs` (PointSet) as the guide; remove the four `rust-tests.txt` entries.
- [ ] **Step 5: Run** the Step 3 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 6: Commit** `Add Frame line geometry, NormBox, FrameGrid and PointSet`.

### Task 2: Region and Box

**Files:**
- Create: `Region.java`, `Box.java`, `src/test/resources/probes/region_defunc_probe.c`, `src/test/resources/probes/region_frameset_probe.c` and their outputs, the oracle JSON files named below
- Modify: `ObjectRegistry.java`, `FrameSet.java` only where a Region in a FrameSet needs it (`span` and `getMapping` already insert a non-unit Frame), `JniastApiTest.java` (`Region`, `Box`)
- Test: `BoxRegionTest` (`test_box_region.rs`, its `default_uncertainty_follows_a_moved_box` from `region_defunc_probe`), `BoxAxInTest`, `RegionBoxOracleTest`, `RegionAttrTestOracleTest`, `RegionAttribOracleTest`, `RegionObjectOracleTest`, `RegionEqualOracleTest` (now off `rust-tests.txt`), `RegionErrorsOracleTest` (the Box cases), `RegionFrameSetTest` (Review Focus 2 and 3), the Box entries of the deferred lists, the in-test Box skips

**Interfaces:**
- Consumes: Task 1's `PointSet`, `normBox`, `frameGrid`, line geometry.
- Produces: `public abstract class Region extends Frame` with the public API above; package-private `FrameSet regionFrameSet()` (C's `astGetRegionFrameSet`, a copy), `Region parentSimplify()`, the methods Rust's `Region` trait in `mod.rs` defines (`regPins`, `baseMesh`, `getUncFrm` and the rest, named in camelCase), and the per-class hooks every later shape overrides (Rust's `RegionGeometry` methods in camelCase: `regBaseInside`, `regBaseBox`, `regBaseMesh`, `regBaseGrid`, `isBoundedInner`, `remapDefiningPoints`, `regCentre`); dump writes the Frame part from the current Frame then `IsA Frame`, the Region items (`Negate`, `Adapt`, `Frm` or `FrSet`, `Points`, `Unc`, `Fill`, `MeshSz`, `Closed`, `RegAxes`) and `IsA Region`, as C's `region.c` Dump.
- Produces: `public class Box extends Region`, the default uncertainty every Region's `GetDefUnc` builds.

- [ ] **Step 1: Convert** the oracles named under Files with `CasesToJson`.
- [ ] **Step 2: Write the probes:** `region_defunc_probe.c` (a Box's default uncertainty before and after it is moved by `astMapRegion` and by a changed Frame attribute) and `region_frameset_probe.c` (Review Focus 2 and 3, with a Box), each printing values with `%.17g`; build them and commit source and output.
- [ ] **Step 3: Write the failing tests** named under Files; `RegionErrorsOracleTest` and the other shared oracles assert the number of cases run (Gates).
- [ ] **Step 4: Run** `mvn test -Dtest='Box*Test,Region*Test'` — Expected: FAIL (classes missing).
- [ ] **Step 5: Implement** `Region` and `Box` from `region.c` and `box.c`, with Rust `mod.rs`, `data.rs`, `box_region.rs`, `frames/frame_attrs.rs` (the forwarded Frame methods), `frames/find_frame.rs` (Convert and FindFrame on a Region) and the Region parts of `serial/mod.rs` as the guide; Simplify as Shared Design says.
- [ ] **Step 6: Run** the Step 4 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS, and the deferred-list diff removes only entries whose missing classes were Box and PointSet.
- [ ] **Step 7: Commit** `Add Region and Box`, and the list changes as `Run the Box fixtures`.

### Task 3: Overlap, masking, mapRegion and showMesh

**Files:**
- Create: `RegionOverlap.java`, `RegionMask.java`, oracle JSON `region_overlap`, `region_mask`, `region_mapregion`, `region_showmesh`
- Modify: `Region.java`
- Test: `RegionOverlapOracleTest`, `RegionMaskOracleTest`, `RegionMapRegionOracleTest`, `RegionShowMeshOracleTest` (each on its Box cases), `RegionMaskArgumentsTest` (Review Focus 4)

**Interfaces:**
- Consumes: Task 2's `Region`, `Box`; phase 6's `Resample`, `PixelArray`.
- Produces: package-private `int RegionOverlap.overlap(Region a, Region b)` (generic `OverlapX`), which shapes override through `Region.overlapX(Region)`; `RegionMask.mask(Region, Mapping, boolean, int, int[], int[], PixelArray, double, String method)`; package-private `String Region.meshText(boolean format, String ttl)`, which `showMesh` writes to `System.out` as C's `astShowMesh` prints it, ending with `ENDMESH`.

- [ ] **Step 1: Convert** the oracles, and write the failing tests named under Files.
- [ ] **Step 2: Run** `mvn test -Dtest='Region*Test'` — Expected: FAIL.
- [ ] **Step 3: Implement** from `region.c` (`Overlap`, `OverlapX`, `Mask<X>`, `MapRegion`, `ShowMesh`, `Negate`) with Rust `overlap.rs` and `masking.rs` as the guide; the generic `mask` dispatches on array type as jniast's does, with jniast's `IllegalArgumentException` message, and the argument checks are phase 6's (`Resample.checkLength`, `checkBounds`).
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Overlap, mask, map and show a Region`.

### Task 4: NullRegion, Interval and PointList

**Files:**
- Create: `NullRegion.java`, `Interval.java`, `PointList.java`, oracle JSON `region_interval`, `region_interval_remap`, `region_nulltran`, `region_bounded`
- Modify: `RegionMask.java` (PointList's own mask, Rust `masking.rs` `pointlist_mask`), `JniastApiTest.java`
- Test: `NullRegionTest`, `IntervalTest`, `PointListTest`, `RegionIntervalOracleTest`, `RegionIntervalRemapOracleTest`, `RegionNullTranOracleTest` (now off `rust-tests.txt`), `RegionBoundedOracleTest` (non-Prism cases), the shared oracles' cases for these classes, the deferred-list entries and in-test skips naming them

**Interfaces:**
- Consumes: Tasks 2 and 3.
- Produces: `public class NullRegion extends Region`, `public class Interval extends Region`, `public class PointList extends Region` with `getListSize()`.

- [ ] **Step 1: Convert** the oracles, and write the failing tests named under Files, including Review Focus 5 rows for these constructors.
- [ ] **Step 2: Run** `mvn test -Dtest='NullRegionTest,IntervalTest,PointListTest,Region*Test'` — Expected: FAIL.
- [ ] **Step 3: Implement** from `nullregion.c`, `interval.c` and `pointlist.c`, with Rust `nullregion.rs`, `interval.rs` and `pointlist.rs` as the guide (Simplify as Shared Design says).
- [ ] **Step 4: Run** the Step 2 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS, list diff as Task 2's rule.
- [ ] **Step 5: Commit** `Add NullRegion, Interval and PointList`, and the list changes as `Run the NullRegion, Interval and PointList fixtures`.

### Task 5: Circle and Ellipse

**Files:**
- Create: `Circle.java`, `Ellipse.java`, oracle JSON `region_ellipse`, `region_frame`, `src/test/resources/probes/region_sky_probe.c` (Box and Circle rows) and its output
- Modify: `JniastApiTest.java`
- Test: `CircleTest`, `EllipseTest`, `RegionEllipseOracleTest`, `RegionFrameOracleTest`, `RegionSkyTest` (Review Focus 1), the shared oracles' cases for these classes, the five `Circle` and one `Ellipse` entries of `rust-tests.txt`, the in-test Circle skips (`MatchAxesTest`, `FrameClassAttrsTest`, `FrameAxisAttrsTest`, `FrameTest`, `IsLinearTest`, `NormPointsTest`, `ReportTest`, `ConvertTest`, `SectionLoadTest`)

**Interfaces:**
- Consumes: Tasks 1 to 3 (`offset2`, `resolvePoints`, `normBox`).
- Produces: `public class Circle extends Region` (three constructors), `public class Ellipse extends Region`; the best-fit conversions between them (`astBestEllipse`, `astBestCircle`) as package-private methods Task 8's Simplify calls.

- [ ] **Step 1: Write the probe** `region_sky_probe.c` (Review Focus 1, Box and Circle), build it and commit source and output; convert the oracles; write the failing tests named under Files, with Review Focus 5 rows; the `circle_pars` and `get_region_disc` tests go to `rust-tests.txt` (Not ported).
- [ ] **Step 2: Run** `mvn test -Dtest='CircleTest,EllipseTest,Region*Test'` — Expected: FAIL.
- [ ] **Step 3: Implement** from `circle.c` and `ellipse.c`, with Rust `circle.rs` and `ellipse.rs` as the guide; sky geometry through the Frame methods from the start (H9).
- [ ] **Step 4: Run** the Step 2 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS, list diff as Task 2's rule.
- [ ] **Step 5: Commit** `Add Circle and Ellipse`, and the list changes as `Run the Circle and Ellipse fixtures`.

### Task 6: Polygon

**Files:**
- Create: `Polygon.java`, oracle JSON `region_polygon`
- Modify: `region_sky_probe.c` and its output (Polygon rows), `JniastApiTest.java`
- Test: `PolygonTest`, `RegionMaskingTest` (`test_region_masking.rs`), `RegionPolygonOracleTest`, `RegionSkyTest` (Polygon rows), the shared oracles' Polygon cases, the `Polygon` entries of the deferred lists and `SectionLoadTest`'s Polygon cases

**Interfaces:**
- Consumes: Tasks 1 to 3 (the line geometry above all).
- Produces: `public class Polygon extends Region`.

- [ ] **Step 1: Extend** the probe, convert the oracle, write the failing tests named under Files with Review Focus 5 rows; list `test_region_convex_oracle.rs`, `test_region_outline_oracle.rs` and the Polygon tests calling `astConvex`, `astOutline`, `astDownsize` or `astGetRegionDisc` in `rust-tests.txt` (Not ported).
- [ ] **Step 2: Run** `mvn test -Dtest='PolygonTest,RegionMaskingTest,Region*Test'` — Expected: FAIL.
- [ ] **Step 3: Implement** from `polygon.c` (without `Convex<X>`, `Outline<X>`, `Downsize`), with Rust `polygon.rs` from line 999 as the guide: the LineDef cache, the geodesic mesh, `SimpVertices`, the sky area.
- [ ] **Step 4: Run** the Step 2 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS, list diff as Task 2's rule.
- [ ] **Step 5: Commit** `Add Polygon`, and the list changes as `Run the Polygon fixtures`.

### Task 7: Prism and CmpRegion

**Files:**
- Create: `Prism.java`, `CmpRegion.java`, oracle JSON `region_cmpregion`, `region_cmplist`, `region_compound_attr`, `region_grid`
- Modify: `Simplifier.java` or `CmpMap.java` where Prism overrides C's `MapList` (Rust `simplify.rs` lines 371 to 383), `JniastApiTest.java`
- Test: `PrismTest`, `CmpRegionTest`, `RegionOverlapTest` (`test_overlap.rs`), `RegionMaskTest` (`test_masking.rs`), `RegionGridTest` (`test_region_grid.rs`), `RegionCmpRegionOracleTest`, `RegionCmpListOracleTest`, `RegionCompoundAttrOracleTest`, `RegionBoundedOracleTest` (all cases), every shared oracle with its filter removed, the deferred-list entries naming Prism or CmpRegion

**Interfaces:**
- Consumes: Tasks 2 to 6.
- Produces: `public class Prism extends Region`, `public class CmpRegion extends Region` with `AST__AND = 1` and `AST__OR = 2` as jniast declares them (C's XOR, 3, is accepted as `oper`).

- [ ] **Step 1: Convert** the oracles and write the failing tests named under Files, with Review Focus 5 rows.
- [ ] **Step 2: Run** `mvn test -Dtest='PrismTest,CmpRegionTest,Region*Test'` — Expected: FAIL.
- [ ] **Step 3: Implement** from `prism.c` and `cmpregion.c`, with Rust `prism.rs` and `cmpregion.rs` as the guide: the copied components (H4), compound attributes, `CmpRegionList`, XOR, `RegGrid` and its C error (Taken from C, item 3), C's bounded quirk (item 4, with its `docs/rust-c-differences.md` entry).
- [ ] **Step 4: Run** the Step 2 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS, list diff as Task 2's rule.
- [ ] **Step 5: Commit** `Add Prism and CmpRegion`, and the list changes as `Run the Prism and CmpRegion fixtures`.

### Task 8: Simplifying Regions, and the cross-class tests

**Files:**
- Create: `RegionMerge.java`
- Modify: every shape class (its own Simplify and MapMerge), `Simplifier.java` (neighbouring Region merges, Rust `simplify.rs` `region_map_merge`), `Region.java`
- Test: `RegionsTest` (`test_regions.rs`, with its `fixtures/simplify` files), `RegionsCTest` (`test_regions_c.rs`, checkEllipse Errors 15 to 17 included), `JniastRegionTest` (jniast's `RegionTest`, 14 tests, `reference/starjava/jniast/src/testcases/uk/ac/starlink/ast/RegionTest.java`), the tests earlier tasks deferred to this one, `simplify.txt` and `simplify-cases.txt` entries naming only this phase's classes

**Interfaces:**
- Consumes: Tasks 2 to 7.
- Produces: each class's Simplify as C's (`box.c` to `cmpregion.c`), including the conversions between classes, and `RegionMerge` for C's MapMerge of neighbouring Regions.

- [ ] **Step 1: Write the failing tests** named under Files.
- [ ] **Step 2: Run** `mvn test -Dtest='RegionsTest,RegionsCTest,JniastRegionTest,Simplify*Test'` — Expected: FAIL.
- [ ] **Step 3: Implement** the Simplify and MapMerge of each class from C, with each Rust class file's `simplify` and `map_merge`, `merge_util.rs` and `simplify.rs` as the guide; keep the fixtures' negative controls (H6, H10 to H12).
- [ ] **Step 4: Run** the Step 2 command, `mvn test -Dast.writeDeferred=true` and then `mvn -q test` — Expected: PASS; the `simplify.txt` and `simplify-cases.txt` diffs remove only entries whose missing classes are this phase's.
- [ ] **Step 5: Commit** `Simplify Regions`, and the list changes as `Run the Region simplification fixtures`.

### Task 9: Sweep and report

**Files:**
- Modify: the deferred lists, the in-test skips that remain for this phase's classes, `rust-tests.txt` (the Moc and MocChan files and every not-ported function, with reasons), `NativeStream.java` and `ObjectRegistry.java` comments, `docs/rust-c-differences.md`
- Create: `docs/reports/2026-11-18-phase7-report.md`, `docs/reports/2026-11-18-phase7-rulings.md`

- [ ] **Step 1: Sweep:** `rg` the Rust `tests/` directory for every Region-related file and test function; each is translated or listed with a reason; `mvn test -Dast.writeDeferred=true` changes nothing.
- [ ] **Step 2: Write the report** in the layout of `docs/reports/2026-11-18-phase6-report.md`: what was ported, the gates and their counts, the deferred-list changes, the C defects found (reported to the user only, never to GitHub), the gaps, and what the next phase takes (SelectorMap, `RemoveRegions`, Stc and AstroCoords); and the rulings file.
- [ ] **Step 3: Run** `mvn -q verify` and `mvn -q test -Djvm=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin/java` — Expected: PASS.
- [ ] **Step 4: Commit** `Sweep the deferred lists after phase 7` and `Report phase 7`.
