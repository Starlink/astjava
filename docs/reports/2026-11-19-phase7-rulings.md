# Phase 7: rulings

These are the decisions made while executing the phase 7 plan ([`2026-11-18-pure-java-ast-phase7-regions.md`](../superpowers/plans/2026-11-18-pure-java-ast-phase7-regions.md)) where the plan was wrong, unclear or silent, or where the port departs from Rust, C or jniast or keeps a C quirk.
They are copied from the execution ledger (the controller's rulings) and from each task's report (the implementer's rulings), grouped by task; within a task the implementer's come first, in the order of the report, fix rounds included, then the controller's, in the order they were made.
Each ruling gives what was decided, why, and what it costs if wrong.
A later ruling can reverse an earlier one; a ruling that a later one replaced carries a bracketed note saying so.
There are 130 rulings, 119 by the implementers and 11 by the controller, and one correction by the controller.
Task 8b is the second implementer the controller gave the rest of `test_regions.rs` while Task 8 was reviewed.
The phase report is [`2026-11-19-phase7-report.md`](2026-11-19-phase7-report.md).

## Task 1: Frame line geometry, NormBox, FrameGrid and PointSet

### Implementer

- Ruling: `lineCrossing` returns no `dist` (C's fifth argument) — the brief's interface omits it and the only C caller that reads it is polygon.c `Polywidth` (`astDownsize`, not ported) — cost if wrong: a later task needing the distance adds an overload. [superseded in Task 6: `lineCrossing` takes C's `dist`, which Polywidth reads for every Polygon]
- Ruling: `LineDef` does not keep SkyLineDef's `start_2d`/`end_2d` — C writes them but no C function reads them — cost if wrong: none observable.
- Ruling: `lineContains` keeps C's `int def` and `lineCrossing` writes C's 3-vector into `cross[2..4]` (Rust's `line_contains` has no `def`) — follows C — cost if wrong: none.
- Ruling: CmpFrame `normBox` reproduces C's permuted-CmpFrame quirk (outputs not un-permuted) by building C's PermMaps — C is final; Rust's predicate form differs — cost if wrong: a negated Region on a permuted CmpFrame containing a pole would widen in Rust but not here (recorded in rust-c-differences.md).
- Ruling: C's undefined `(int)` conversions in `FrameGrid` (frame.c, skyframe.c) follow Java's cast (saturating, NaN → 0) and wrapping `int` products, i.e. the pinned library on AArch64, as the project's SwitchMap ruling does — x86-64 C gives `INT_MIN` → 2 — cost if wrong: a Frame grid over a zero-width axis errors (AST__NPTIN, -2 points) where x86 C makes four points, and a SkyFrame grid of zero longitude width walks 2^31 parallels (~15 s in Java, ~30 s in C) before the same AST__NPTIN error x86 C reaches at once. [amended by the controller: the slow SkyFrame row left the probe and FrameGridTest; the zero-width Frame row stays]
- Ruling: SkyFrame grid `ip + nmer >= ipmax` is tested in `long` — C's `int` sum can only overflow after a saturated (undefined) conversion, and wrapping there would index past the array — cost if wrong: none for any defined C behaviour.
- Ruling: PointSet load gives every axis without an `AccN` item no accuracy (bad) — C leaves the one before the first given axis uninitialised (C bug, recorded) — cost if wrong: none; C's value is garbage.
- Ruling: `PointSet.points()` (and so `equalTo`) on a PointSet loaded with `Empty = 1` raises AST__INTER — C's `astGetPoints` returns uninitialised memory there, and H9 prefers an error to a plausible value; the probe does not compare empty PointSets — cost if wrong: a later caller expecting values from an empty PointSet gets an error; no C fixture has one.
- Ruling: the PointSet fixture round trip extracts the four `Begin PointSet` blocks of `simplify/cap_slamap_07.map` at test time (re-indented to the top level) rather than copying them into a test resource — the CmpRegion cannot be read without Region classes, and extracting at run time keeps the fixture the single source — cost if wrong: none.
- Ruling: the four Rust `norm_box_*` predicates are translated as Mappings (bad-if-any-bad MathMap for "rejects a point with a bad axis", constant PermMap for "accepts all") — C's `astNormBox` takes a Mapping; their expected values (captured from C with a Box) agree with the probe's MathMap rows — cost if wrong: none.
- Ruling: `PointSet(double[][])` with rows of different lengths raises AST__INTER — C's `astPointSet` cannot express it — cost if wrong: none.

### Controller

- Ruling: FrameGrid keeps the AArch64 reading of C's undefined `(int)` conversions (project convention), but no test pins an undefined case: the zero-width rows (SkyFrame zero longitude width, Frame zero-width axis) leave FrameGridTest, and `docs/rust-c-differences.md` records the undefined conversion and Java's behaviour — a test of undefined behaviour pins nothing C promises and costs about 15 s per CI runtime — cost if wrong: a later change to that path goes unnoticed by tests. [amended by the next ruling]
- Ruling (amends the one above): only the slow SkyFrame zero-longitude-width row (`grid 40 1 -0.5 1 0.5`) leaves the probe and FrameGridTest; the instant zero-width Frame row stays, pinning the project's AArch64 conversion convention at no cost — the review agreed that the SkyFrame `AST__NPTIN` path is covered by `grid(sky, 1, lb1, ub1)` — cost if wrong: none.

## Task 2: Region and Box

### Implementer

- Ruling: the default uncertainty is kept when a Region moves, as C (`ResetCache` leaves `defunc`) — plan Shared Design item 1; Rust's `default_uncertainty_follows_a_moved_box` is translated with C's values (point at 1000+2e-4 is bad) — cost if wrong: none, C is final.
- Ruling: `mapRegion`/`mapRegionInto` and `getNegation` implemented in Task 2, not Task 3 — `GetUncFrm` (so `getUnc` in a mapped Frame and Box membership after a change of Frame) needs the protected MapRegion — cost if wrong: Task 3 has less to do; it must not reimplement them.
- Ruling: `overlap`, `mask*` and `showMesh` throw AST__INTER ("not yet ported") — JniastApiTest requires the members once Region is in scope, and H9 prefers an error to a plausible value — cost if wrong: none; Task 3 replaces them. [replaced in Task 3, which ported them]
- Ruling: `RegionEqualOracleTest` checks only the equality column and the entry leaves rust-tests.txt, as the brief says; the overlap column is unchecked until Task 3 adds it — cost if wrong: if Task 3 forgets, C's overlap results for these five cases go untested (flagged under Concerns). [Task 3 added the overlap column]
- Ruling: Box keeps two membership hooks: `regBaseInside` (Rust's un-negated test with `closedForBaseInside`) and `regBaseMask` overridden with C's Transform loop, because C's negated Box keeps a point whose earlier axis is outside even when a later axis is bad, where Rust's default makes it bad — cost if wrong: none; C is followed, later classes can use Rust's default.
- Ruling: no `remapDefiningPoints` hook — Rust declares `remap_defining_points` in every class but never calls it, and C has no such function — cost if wrong: a later task adds an unused hook if it finds a caller.
- Ruling: `regCentre` keeps C's signature (`cen`, `ptr`, `index`, `ifrm`; returns the centre or null) rather than Rust's split query/set — the uncertainty re-centring in SetUnc, RegPins and Simplify passes `ptr` — cost if wrong: none.
- Ruling: `getUncFrm(AST__BASE)` returns the stored uncertainty itself, so that RegPins and Simplify re-centre it in place as C does through its clone — C's sharing, H4 — cost if wrong: none observable in C's results.
- Ruling: C's RegSetAttrib quirk kept: the axis of a setting is moved to the base Frame axis only when the value is one word (`%[^(](%d)= %n%*s %n`) — C is final; recorded as a C quirk — cost if wrong: a non-Adaptive Region with permuted axes given a multi-word Label sets the base Frame's label on the wrong axis, as C does.
- Ruling: the package-private `setEpochMjd` on a Region sets `MJD %.17g` on the FrameSet (with integrity) instead of C's `astSetD("Epoch", mjd)`, which C reads as a year (C bug); `setObsLat/Lon/AltValue` go to the FrameSet's own setters (C passes radians as degrees) — no public method reaches them — cost if wrong: none observable through jniast's API.
- Ruling: Box rejects NaN and infinite coordinates as bad (AST__BADIN, C's message), by the library's non-finite-is-bad rule; C rejects only AST__BAD — cost if wrong: a NaN Box errors where C builds a Box that contains nothing.
- Ruling: a loaded `Unc` that is not a Region, and `Frm`/`FrmSet` that are not a Frame/FrameSet, raise AST__REGIN; C does not check and would misuse the object — cost if wrong: none for valid input.
- Ruling: `mapRegionInto` raises AST__INTER if `pickAxes` returns a non-Region where C would use a Frame as a Region — cost if wrong: none for C's defined behaviour.
- Ruling: `regGrid` raises AST__MBBNF for an unbounded Region (C passes a NULL PointSet on) — prefer an error — cost if wrong: none.
- Ruling: `construct_requires_matching_axes` translated with a short second point in a 3-axis Frame (AST__DIMIN), since jniast accepts arrays longer than the Frame; `with_integrity_preserves_points` sets a Title through the integrity path in place of Rust's no-op closure; `negated_copy` uses `getNegation` (C's GetNegation) — cost if wrong: none.
- Ruling: the Rust `simplify_permmap_slice_both_constants_inside_keeps_box` test is translated and passes with the Region-generic Simplify (a Box with two current axes); the three `*_is_null` tests are listed as `needs simplification` (Box Simplify, Task 8) — cost if wrong: none.
- Ruling: the switchmap and rate-oracle entries' reasons changed to SelectorMap, since Box is now present and SelectorMap is what they lack — cost if wrong: none; next phase removes them.
- Ruling: Box does not override C's Set/ClearNegated/Closed (compare-then-reset) because Region's setters already reset the kept values through the overridable `resetCache` — cost if wrong: none (C's parent setters reset unconditionally too).

### Controller

- Ruling: the simplify-list changes in Task 2 stand — the lists are checked both ways, so rows that now pass must leave; the plan's "wait for Task 8" assumed they would need class Simplify — cost if wrong: none (the gates hold either way).

## Task 3: Overlap, masking, mapRegion and showMesh

### Implementer

- Ruling: `OverlapX` and `Mask<X>` negate the Region in place and back (try/finally), as C does, not with `getNegation`/a negated copy as Rust does — C is final and the copy is Rust's borrow rules; the in-place form also cannot meet the stale negation — cost if wrong: `test("Negated")` is true after an overlap or an inside mask, and the Region's kept mesh is discarded (performance only); the probe pins the flag.
- Ruling: the carried "stale negation" item is met with a probe of C's protected `astGetNegation` (Adaptive: same object kept, System still FREQ after a change to WAVE; not Adaptive: discarded) pinned against `Region.getNegation`, and recorded as a C quirk — overlap does not use `getNegation` in C, so the brief's condition ("once overlap uses getNegation") does not arise; only CmpRegion (Task 7) reaches the copy — cost if wrong: none now; Task 7 inherits C's staleness knowingly.
- Ruling: `RegionMask.mask` takes the value to set in the `PixelArray` (its bad value) and has no separate `double` parameter, departing from the brief's interface line `mask(Region, Mapping, boolean, int, int[], int[], PixelArray, double, String method)` — a double cannot hold every `long`, and `PixelArray.of(in, val)` already carries it — cost if wrong: one parameter for Task 4 to add back.
- Ruling: check order for `mask<X>`: null checks (lbnd, ubnd, in; NPE naming each) → C's NGDIN checks → bound-array DIMIN (`Resample.checkBounds`) → `mapRegionInto` → C's GBDIN → data DIMIN (`Resample.checkLength`) — phase 6's resample order (Rust's lengths after C's dimension checks) — cost if wrong: which status a doubly invalid call reports.
- Ruling: the generic `mask` checks types with `instanceof` before calling `mask<X>` (jniast catches `ClassCastException` around the call); a null `val` gives jniast's IAE message (jniast: NPE from unboxing), as phase 6 ruled for `resample`'s `badval` — cost if wrong: exception type for a null `val`.
- Ruling: `GetSubMesh` with no points left raises C's AST__NPTIN (`astInitPointSet(PointSet): Number of points (0) is not valid.`), as C's `astPointSet( 0, ... )` does; Rust makes an empty PointSet — C is final — cost if wrong: an error where Rust returns a code, in a case (regPins false with every mask element set) no class produces today.
- Ruling: when `OverlapX` must double more bounds than the arrays hold (more current than base Frame axes), Java raises AST__INTER where C writes past the arrays — prefer an error to undefined behaviour; recorded as a C bug — cost if wrong: none for defined behaviour.
- Ruling: `Mask<X>`'s `(int)round(bound) ± 2` is computed as C on AArch64 (saturating conversion, wrapping `int` sum), so a Region unbounded above has an empty grid box and masks every pixel as outside (C bug, verified with a scratch probe on an Interval x >= 3: inside sets 0 pixels, outside 10) — C is final, and x86-64 gives the same box — cost if wrong: Task 4's Interval masks follow C's wrong result knowingly.
- Ruling: the generic `bndBaseMesh` throws AST__INTER ("needs a CmpRegion, which is not yet ported") when the Region's boundary crosses the box, since the mesh comes from a CmpRegion (Task 7); OverlapX suppresses that error, as C suppresses any, and reports C's "cannot determine the overlap" AST__INTER — prefer an error to a plausible value (H9) — cost if wrong: unreachable with Box (always bounded); see Concerns for Task 4. [replaced in Task 7 by region.c's BndBaseMesh]
- Ruling: `public_map_region_simplifies` (region_mapregion's Box case) goes to `rust-tests.txt` as `needs simplification` — the Region-generic Simplify keeps the PermMap in a FrmSet where C's Box Simplify re-fits the Box (Task 8) — cost if wrong: none; Task 8 translates it.
- Ruling: the showMesh oracle's Box cases are chosen by the class in the dump's first `Begin` line; the `_ub` cases of region_mask use `maskB` (jniast has no unsigned byte; values 0–2 and 1 are the same) — cost if wrong: none.

### Controller

- Ruling: `RegionMask.mask` takes the mask value inside the `PixelArray`, not as a separate `double` — a `double` cannot hold every `long` value — cost if wrong: none (package-private).

## Task 4: NullRegion, Interval and PointList

### Implementer

- Ruling: each class's Simplify is the Region-generic one (Shared Design); NullRegion's and PointList's own (same-class re-definitions in the current Frame) and Interval's (Box conversion, and a re-definition that needs Box's own Simplify) wait for Task 8 — the plan says so — cost if wrong: Task 8 adds three Simplify methods; region_interval_remap is deferred meanwhile. [NullRegion's own Simplify was ported in fix round 1 (controller ruling below), the others in Task 8]
- Ruling: `RegionIntervalRemapOracleTest` checks only that C's input dumps read back and rewrite identically; the Rust test is listed "needs simplification" — the expected result depends on Box Simplify — cost if wrong: none.
- Ruling: NullRegion's `getDefUnc` raises AST__INTER (C builds a zero-radius Circle, Task 5) — prefer an error to a plausible value (H9) — cost if wrong: `getUnc(true)` on a NullRegion errors until Task 5, which must replace it (its Javadoc names the missing Circle). [replaced in Task 5 by C's zero-radius Circle]
- Ruling: `Region.regPins` becomes region.c's NOIMP default rather than abstract, and `regMesh`/`regionBounds` lose `final`, so NullRegion can override them as C's vtab does — the brief's Files did not list Region.java — cost if wrong: none.
- Ruling: a PointList point with a bad value behaves as C (first axis decides; `astPointInRegion` looks at the first good value), so Rust's `point_with_bad_value_not_inside` is translated with C's values (`pointWithBadValueIsAsCLeavesIt`) and `RegionNullTranOracleTest` uses C's point-in-region rule — C is final — cost if wrong: none; recorded in rust-c-differences.md (C quirk).
- Ruling: PointList `regPins` looks at as many of its own points as were given (C), so `reg_pins_incomplete_cover_reports_false` is translated as C's result (true for the first two of three, false for the first and third); where C reads beyond its points (more points given than it has) or would build a PointList with no Frame (no uncertainty), Java raises AST__INTER — prefer an error to undefined behaviour, as Task 3 ruled for OverlapX — cost if wrong: an error in a case C handles by reading garbage. [the `AST__INTER` for more points given than the PointList has was replaced by the controller's ruling in Task 7]
- Ruling: the probe's three PointList–Box overlaps come from runs that completed: pointlist.c RegPins writes past its mask, most runs of those cases crash, and every completed run (5–6 of 30 per case) gave the same values, which also follow from C's algorithm with the mask held correctly; the probe runs each in a fresh process, retrying until it completes, and its header says so — cost if wrong: the three rows rest on C output taken during heap corruption. [superseded in fix round 1: the rows come from a deterministic build with a one-line correction to pointlist.c, with the same values]
- Ruling: Interval `regCentre` does not mark its kept limits stale (C), so a moved Interval uncertainty keeps its old bounds; pinned (`anIntervalUncertaintyKeepsTheBoundsItHadBeforeItWasMoved`) — C is final; C quirk recorded — cost if wrong: none.
- Ruling: Interval `Overlap` with an axis of code 6 and more than one axis raises AST__INTER where C indexes past its 5x5 table (Rust panics); code 6 on one axis is kept as C's result — prefer an error to UB — cost if wrong: an error where C returns whatever follows its table.
- Ruling: NullRegion `regionBounds` raises AST__INTER when the base Frame has more axes than the current one (C writes past the caller's arrays); with fewer, it fills the base axes and leaves the rest, as C — cost if wrong: an error for a NullRegion mapped to fewer axes.
- Ruling: PointList `regBaseBox` keeps one bound per base Frame axis, where C sizes by the current Frame and writes per base axis (C bug) — cost if wrong: none for C's defined cases.
- Ruling: non-finite Interval limits and PointList coordinates are bad (library rule, as Task 2's Box ruling), so a NaN limit is a missing limit and a NaN coordinate is AST__BAD; tested against C's AST__BAD rows — cost if wrong: a NaN limit means "no limit" where C would compare against NaN.
- Ruling: the bounds of a mapped Interval with an unbounded axis are all bad in Java where C gives inf/NaN (the Mapping makes overflowing corners bad); pinned in `mappedUnboundedIntervalMatchesC`, the rest of the case compared with C — cost if wrong: different garbage for an already meaningless result.
- Ruling: constructor argument checks follow the phase 1 rule (short arrays AST__DIMIN, longer accepted; NPE naming a null argument), not jniast's IllegalArgumentException ("Element %d of points array is wrong length" for any length other than npnt); PointList's npnt < 1 is C's AST__NPTIN message — cost if wrong: exception type for a malformed PointList.
- Ruling: the Interval overlap's `OneToOne` checks a split's output count before indexing (C indexes `tt[0]` first, undefined for a split with no outputs) — same result for C's defined cases — cost if wrong: none.
- Ruling: `Interval.regPins` carries `@SuppressWarnings("PMD.AvoidArrayLoops")` (false positive on the loop computing the band limits), as Box does — cost if wrong: none.

### Controller

- Ruling: port nullregion.c's own Simplify now (re-definition in the current Frame with the mapped uncertainty), since the Shared Design requires the interim Simplify to give a correct result and an exception is not one; class-changing branches still wait for Task 8 — cost if wrong: Task 8 revisits NullRegion's Simplify.
- Ruling: re-grade the review's fourth minor to Important — Interval's `OneToOne` returns null for a split with more than one output where C sets `result[tt[0]]` and carries on, so for C-defined inputs Java falls back to region.c's overlap where C compares axis by axis; a result difference on defined input is a fidelity defect, not polish — cost if wrong: one small fix. [withdrawn in fix round 1: C's `OneToOne` frees its result when a split has more than one output and returns NULL, as Java does; probe cases pin it, and the implementer's ruling above was reworded]

## Task 5: Circle and Ellipse

### Implementer

- Ruling: Ellipse `regCentre` moves each of the three defining points (C's comment's intent, Rust's behaviour) where C's ellipse.c indexes `rptr[ip][0..1]` for ip 0..2 and crashes (null deref at `rptr[2]`, reproduced: every `astBox(..., ellipseUnc)` segfaults); the probe links ellipse.c patched to `rptr[0..1][ip]` for an oracle, as Task 4 patched pointlist.c — an Ellipse must be usable as an uncertainty (jniast `setUnc`) and C defines no result — cost if wrong: a different result where C crashes.
- Ruling: Ellipse `cache` keeps the values out of date when the points do not determine an ellipse, so every use reports C's `AST__BADIN`; C marks them up to date and a second use reads unset values — prefer an error to undefined behaviour — cost if wrong: none for C's defined cases.
- Ruling: `bestEllipse` raises `AST__INTER` when no mesh point is good and away from the centre (C reads past `dist`/`ang`; Rust returns None) — prefer an error to UB — cost if wrong: Task 8's Simplify errors where C's result is undefined.
- Ruling: Circle `regCentre` does not reset the kept values (C quirk), and Circle `regBaseMesh` keeps only the N-D mesh, recomputing 1-D/2-D meshes (and so 2-D non-plain bounds) on each call, as C — cost if wrong: performance only.
- Ruling: Circle `regPins` does not clamp the smaller radius (C), and re-centres the uncertainties (C), where Rust clamps and omits re-centring — C is final — cost if wrong: none.
- Ruling: the Ellipse form error carries C's second message line ("The supplied <class> has 0 axes …"), which C adds because `astGetNaxes` returns 0 once an error is set — C's message — cost if wrong: one message line.
- Ruling: non-finite Circle/Ellipse coordinates are bad (library rule, as Task 2's Box) — cost if wrong: NaN rejected where C accepts it.
- Ruling: array checks follow the phase-1 rule (DIMIN for short, longer accepted; form 1 needs one radius / one angle value, form 0 a value per axis), not jniast's IllegalArgumentException for an unknown form (C's `AST__BADIN`, Review Focus 5) or its point2 length 2 for every Ellipse form — cost if wrong: exception type for malformed calls.
- Ruling: the Ellipse best fits are compared by half-lengths and primary direction modulo π, not by points: which end of the primary axis is "farthest" is a rounding tie on a symmetric mesh (Java took the opposite end from C on the sky ellipse; both pin every mesh point); for an Ellipse fitted to a Circle only the half-lengths are compared (orientation is rounding noise) — cost if wrong: an end-choice difference that changes a later result goes unnoticed. [see the controller's correction under Task 8 for the fixture `cefit_circle_to_ellipse`]
- Ruling: `point_on_boundary_rotated` is listed (no C value), its points being on the boundary to rounding; the axis-aligned boundary test is kept (sin(π/2)=1 and cos(π/2) are correctly rounded everywhere) — cost if wrong: one Rust test untranslated.
- Ruling: the near-pole sky Circle has radius 0.29 (boundary 0.01 rad from the pole), not 0.3: with 0.3 the first mesh point lands on the pole, whose longitude rounding decides, making bounds and masks platform-dependent; the 1-ulp pole membership of `radius_round_trips_exactly_on_a_sky_frame` is likewise not testable across platforms — cost if wrong: that 1-ulp case is untested.
- Ruling: a second probe `region_circle_ellipse_probe.c` was added beside the brief's `region_sky_probe.c`, for the Review Focus 5 rows, RegPins and best fits against C — the brief asks for those rows and direct C tests — cost if wrong: none.
- Ruling: `a_circle_mesh_is_normalised_as_c_does` (astGetRegionMesh, not in jniast) is translated through the package-private `regMesh` and `normalPoints`, as Task 4's minor suggested — cost if wrong: none.
- Ruling: the seven simplify-cases rows (cefit_*, defunc_nullregion_2d, neg_circle_cmpframe_sky_second, regbasepick_circle_permaxes) leave the lists now, matching C with the generic Simplify (their inputs need no refit) — lists are checked both ways (Task 2 ruling) — cost if wrong: none.

### Controller

- Ruling: accept "no C value" as a `rust-tests.txt` reason for a Rust test whose points lie on a boundary to within rounding (membership decided by the last bits of libm) — no C-derived expectation can be portable — cost if wrong: one test untranslated.

## Task 6: Polygon

### Implementer

- Ruling: `Frame.lineCrossing` gains C's `dist` output (four-argument form, the three-argument one a final convenience) — the brief's statement that only Downsize reads it is wrong: `Polywidth`, called by every Polygon's `Cache`, reads it — cost if wrong: none (package-private; C's argument).
- Ruling: Polygon's own (same-class) Simplify is ported now, with SimpVertices — the brief names SimpVertices, `RegionMaskingTest` needs it (C's mask simplifies through it), and it changes no class (the NullRegion precedent); the `cap_poly_*` fixtures now match C — cost if wrong: none for Task 8, which has no Polygon branch left to add.
- Ruling: membership of a Polygon whose edges all have no length (one vertex, coincident vertices) raises `AST__INTER`, where C draws from an uninitialised point; its construction, Bounded, bounds and points are C's, and its mesh C's `AST__BADIN` — prefer an error to undefined behaviour (as Rust) — cost if wrong: an error where C's result is undefined.
- Ruling: a bad vertex read from a dump raises `AST__INTER` when the edges are first described, where C dereferences null — as Rust — cost if wrong: none for defined input.
- Ruling: `Polywidth` raises `AST__INTER` if the line across an edge reaches length 0 without its end being at that distance, where C loops for ever — no Frame here reaches it — cost if wrong: none.
- Ruling: `RegPins` counts a point given with a bad value as on the boundary, as C does (Rust counts it off) — C is final; probe row pins it — cost if wrong: none.
- Ruling: a sky Polygon's membership along a line from its point inside that passes through a vertex is C's arithmetic (double count within `1e-10` rad), kept as C, recorded as a C bug; the tests keep every decision clear of such lines — cost if wrong: none (behaviour is C's; tests never sit on it).
- Ruling: constructor argument checks follow the phase-1 rule (NPE naming the argument, `AST__DIMIN` for an array shorter than `npnt`, longer accepted), not jniast's `IllegalArgumentException` from `jniastCheckArrayLength`; C's own checks (Frame axes, `npnt`, bad values) come first in C's order, the array lengths between `npnt` and the values — cost if wrong: exception type for malformed calls.
- Ruling: non-finite vertex values are bad (library rule, as Box/Circle/Ellipse) — cost if wrong: NaN rejected where C would accept it.
- Ruling: Rust's `construct_rejects_wrong_vertex_length` translated as a `ycoords` shorter than `npnt` (`AST__DIMIN`); `centroid_of_square` listed (astGetRegionDisc) — jniast's arrays have no per-vertex length — cost if wrong: none.
- Ruling: the sky probe's Polygons have no two triangle vertices at one latitude and the RA 0 quadrilateral is slightly irregular, so that no test point, pixel or pole lies on a line from a point inside through a vertex — the symmetric first version put the south pole "inside" a north-pole triangle by C's double count, a rounding-decided result — cost if wrong: none.
- Ruling: `frame_line_probe.c` (Task 1) extended to print `dist`, its output regenerated (only `cross` lines and the error-report line numbers changed) — the new argument needs a C value — cost if wrong: none.
- Ruling: `SectionLoadTest`'s now-empty OUT_OF_SCOPE machinery and `RegionShowMeshOracleTest`'s class filter were removed, as the Gates rule says for the task enabling the last case — cost if wrong: none.

### Controller

- Ruling: Polygon's same-class Simplify is ported in Task 6 (as NullRegion's in Task 4) — `RegionMaskingTest` needs it, and the Shared Design defers only class-changing branches — cost if wrong: none.

## Task 7: Prism and CmpRegion

### Implementer

- Ruling: Prism's `MapList` override lives in `Prism.java` (overriding `Mapping.mapList`), with no change to `Simplifier.java` or `CmpMap.java` — the Java decomposition is already per-class virtual, as C's `astMapList` is — cost if wrong: none.
- Ruling: CmpRegion's and Prism's own Simplify are not ported (Region-generic `parentSimplify` only); both produce other classes (NullRegion, a component, a Prism of simplified components) and depend on Region MapMerge — the Shared Design defers class-changing Simplify to Task 8 — cost if wrong: Task 8 adds them (the six simplify-cases rows and two Interval tests wait).
- Ruling: a PointList component asked by CmpRegion/Prism `RegPins` about more points than it has, all near its points, raises `AST__INTER` where pointlist.c reads beyond its points (Task 4's ruling, now reachable through `overlap` of two CmpRegions with a PointList component); C linked against the mask-fixed pointlist.c answers 5 (the same Regions, which is the right answer, but from reading beyond the array) — prefer an error to undefined behaviour — cost if wrong: `AST__INTER` from `overlap` of such equivalent CmpRegions where C happens to answer 5. [superseded by the controller's ruling below and fix round 1: Java checks every PointList point and gives C's answer, 5]
- Ruling: Prism `Overlap` keeps C's double allowance for the other Region's negation (the picked Regions inherit Negated through region.c `PickAxes`/`RegOverlay`), giving a Prism inside a Box vs the negated Box 2/3, and a Prism vs its negation 5/6 — C is final; recorded as a C bug — cost if wrong: none for C fidelity.
- Ruling: CmpRegion keeps C's Bounded for an AND of two nested unbounded Regions (bounded), and its bounds then fail with C's `AST__INTER` — plan item 4 — cost if wrong: none.
- Ruling: plan item 3 (grid with no point inside → C's `astSetNpoint` error) is pinned with a PointList whose points avoid every grid lattice point, and a Prism of it; the quirk file's sky-Polygon example does not reproduce at the pin (C finds 200 grid points in the negated small Polygon) — cost if wrong: none.
- Ruling: Prism `RegBaseMesh` raises `AST__INTER` when the Prism's current Frame has other than its base Frame's number of axes, where C writes or reads beyond its arrays — cost if wrong: an error where C's result is undefined.
- Ruling: a missing or non-Region `RegionA`/`RegionB` when loading raises `AST__LDERR` (as CmpMap's loader does), where C passes NULL on and fails later — cost if wrong: status code for malformed dumps.
- Ruling: `new CmpRegion(...)`/`new Prism(...)` with a null Region throw `NullPointerException` naming `region1`/`region2`, as jniast's `jniastCheckNotNull` and the phase-1 rule — cost if wrong: none.
- Ruling: Review Focus 5 for Prism: C's `astPrism` rejects nothing beyond `astCheckRegion` (a SkyFrame Circle and a SpecFrame Interval combine; probe case `prism_sky_spec`), so Java has no Prism error but the null check — cost if wrong: none.
- Ruling: CmpRegion's default uncertainty is the component's own uncertainty object (C's `astGetUncFrm` clones it), not a copy — share where C clones — cost if wrong: none (callers copy before changing).
- Ruling: CmpRegion `MatchRegion` inverts the component and back around `convert`, as C does, though inverting a Region changes only its Invert attribute — follow C — cost if wrong: none.
- Ruling: in-place negation of components (CmpRegion mesh/pins for OR, Prism Bounded) is restored in `finally`, as the earlier tasks restore Negated after an error (C does not) — cost if wrong: none for defined input.
- Ruling: the probe's output is committed as JSON (`output` string), as `region_frameset_probe.json` is, because it holds C's message typo "bacause" and item names the spelling check rejects; the Java strings carry `codespell:ignore bacause` — cost if wrong: none.

### Controller

- Ruling: revise Task 4's PointList `RegPins` ruling — loop over `min(np, npl)` and drop the `np > npl` `AST__INTER` (keep the null-uncertainty check): identical to C for `np <= npl`, C's stated intent and answer for `np > npl`, without copying the undefined read; pinned with the case `pl_more_points` of `region_compound_probe.c` — cost if wrong: an overlap of a PointList given more points than it has answers where C's undefined read might differ.

## Task 8: Simplifying Regions, and the cross-class tests

### Implementer

- Ruling: the scaled MeshSize of a parallel merge is truncated as C's conversion to `int` truncates it, where Rust rounds — C is final — cost if wrong: none (merge_box/interval_meshsize_scaled match C).
- Ruling: `Box.polygonOf` throws `AST__OBJIN` when neither corner order gives a Polygon with the Box's inside, where C goes on to pass the annulled Polygon to `astRegPins` (which reports `AST__OBJIN`) and Rust leaves the Box unsimplified; the message is Java's own, not C's address-bearing text — cost if wrong: a different message, or C's error where a caller expected the Box back, on an edge path no test reaches.
- Ruling: `Interval.simplify` of a bounded Interval returns the Interval's own cached equivalent Box's simplification, shared as C's `astSimplify( box )` returns the cached pointer — share where C clones — cost if wrong: a caller changing that result changes the Interval's cache, as in C.
- Ruling: `JniastRegionTest` expects the library's exceptions where jniast's JNI layer throws `IllegalArgumentException` before calling C: `AST__DIMIN` for short arrays (phase 1 ruling), `AST__BADIN` for Circle form 2 (C), `NullPointerException` for a null points element, and `AST__NGDIN` for a mask of 3 grid dimensions with no Mapping on a 2-D Region (C's check comes first, Task 3 design); jniast's PointList element longer than npnt is not tried (accepted, as C) — cost if wrong: exception types.
- Ruling: `RegionsCTest` and `RegionsTest` leave out C's `astGetRegionDisc` checks (not in jniast) and use the package-private `regMesh`/`regGrid` (C's protected `astRegMesh`/`astRegGrid`) where C calls `astGetRegionMesh` — cost if wrong: none for the values checked.
- Ruling: `checkBoxSky` follows C where Rust diverged: it sets Negated on the FrameSet and checks the Box added is unchanged, and transforms the CmpFrame of two Regions directly (a Frame is a Mapping here, as in C) — cost if wrong: none.
- Ruling: `check_remove_regions` is listed as `astRemoveRegions (out-of-scope method)` (next phase), and `region_gaps_still_differ` as Rust-only API (it asserts Rust's gap, which Java does not have) — cost if wrong: none.
- Ruling: JniastRegionTest, RegionsCTest and RegionsTest were written after the implementation (RegionsCTest/RegionsTest RED shown at 8508417 instead) — cost if wrong: none for the code; TDD order not kept for those files.

### Controller

- Ruling: split Task 8 — review its commits `f767bae..306be80`, and give the rest of `test_regions.rs` to a second implementer (Task 8b) in parallel, adding tests and fixing only what they expose — the remaining translation is independent of the review of the delivered code — cost if wrong: a fix from the review and one from Task 8b touch the same lines and need merging in order.
- Correction (of a minor carried from Task 5): the end of the primary axis of the Ellipse fitted to `cefit_circle_to_ellipse` is decided by C's algorithm (exactly equidistant mesh points and a strict `>`), not by rounding, so `SimplifyRegressionTest` compares it as it compares every other row.

## Task 8b: The rest of `test_regions.rs`

### Implementer

- Ruling: `cmpregion_negated_reversal` expects C's (0, 0.5) kept, not Rust's bad value — the probe shows C keeps a boundary point of a negated closed CmpRegion — if wrong, the test pins a value C does not give; the probe output is committed so it can be checked.
- Ruling: `general_circle_bounds_and_disc` and `fitschan_sky_circle_bounds` are translated without their disc checks and not listed in rust-tests.txt — the same treatment `RegionsCTest` gives C's disc checks (left out, said in Javadoc); the bounds checks are the substance — if wrong, two entries with reason `astGetRegionDisc (not in jniast)` should be added.
- Ruling: bounds checks of Rust-only tests compare with C's probe values at 1e-12 instead of Rust's measured values and looser tolerances — expected values from C only — if the bound is too tight for x86 libm, these 7 tests fail on CI; the derivation gives under 1e-14.
- Ruling: `sky_cmpregion_and_circle_box`'s conditional check (asserted only if (1.14, 0.64) is farther than 0.2 from the centre, which it is not, about 0.18) is replaced by an unconditional assertion that the point is inside, C's answer (probe case `sky_circle_and_box_membership`) — a conditional assertion that never runs is a skip mode — if wrong, the test fails rather than passing vacuously.
- Ruling: `fitschan_sky_circle_near_pole_bounds` uses `testregions.c`'s literals and 1e-6 tolerance (Circle Errors AA5–AA8) instead of the Rust test's `1.56 ± 1e-2` — C's value is available — cost if wrong: none beyond a tighter check.
- Ruling: `an_axis_unbounded_above_gets_a_zero_width_default_uncertainty` checks both interval.c's `GetDefUnc` (what C calls on an Interval) and region.c's (reached through a CmpRegion OR of two such Intervals, whose `GetDefUnc` falls back to region.c's when no component has an uncertainty), since Rust's `default_bounding_box_unc` is region.c's function but Java cannot call the overridden base method on an Interval — if wrong, the second check is extra, not missing.
- Ruling: the `simplify_*` fixture tests use the 4-ulp comparator and `withReferenceEnd` of `SimplifyRegressionTest` rather than Rust's byte-exact comparison, and do not strip `IsSimp` — the fits pass through `java.lang.Math`, and Rust's stripping covers a Rust departure — if wrong, a dump difference within 4 ulps goes unnoticed, as it already does in `SimplifyRegressionTest`. [`withReferenceEnd` was removed in Task 8's fix round 1, as it never took effect; the tests use the 4-ulp comparator alone]

### Controller

- Ruling: the disc checks inside `general_circle_bounds_and_disc` and `fitschan_sky_circle_bounds` are left out without a `rust-tests.txt` entry — the file lists tests not translated, these tests are translated, and `RegionsCTest` already leaves C's disc checks out the same way (its Javadoc says so) — cost if wrong: two entries to add.

## Task 9: Sweep and report

### Implementer

- Ruling: `test_frame_in_map_slot.rs::a_region_inside_a_cmpmap_still_reads` is translated (`FrameInMapSlotTest.aRegionInsideACmpMapStillReads`) with the six of its 34 fixtures that also hold a SelectorMap asserted to fail with C's load error naming SelectorMap, and a floor of 28 read where Rust's is 33 — every class but SelectorMap is now present, and a fixture that cannot load is checked for that one reason rather than skipped — cost if wrong: the test fails when SelectorMap arrives, and the next phase raises the floor.
- Ruling: `ObjectIdOracleTest` runs the `cmpregion`, `cmpregion_part_ids`, `prism` and `prism_part_ids` cases, an in-test skip of this phase's classes in a test the plan's Gates section did not name; all four match C — cost if wrong: none.
- Ruling: the Moc and MocChan tests are listed with the plan's reason `Moc (not in jniast)` or `MocChan (not in jniast)`, and the two existing `MocChan (out of scope)` entries of `test_channel_dump_oracle.rs` take the same reason; `SelectorMap (Region, out of scope)` becomes `SelectorMap (out of scope)`, since only SelectorMap is missing — cost if wrong: none.
- Ruling: `test_selectormap.rs` (13 tests) and `test_remove_regions_oracle.rs` are listed now although phase 1 left whole out-of-scope files unlisted — they are Region-related, and the sweep covers every Region-related file — cost if wrong: none; the next phase removes them.
- Ruling: `test_region_stc_oracle.rs::error_ellipse_licence_is_still_needed` is listed as Rust-only API (it checks that the Rust port's licence for its own Ellipse points is still needed off macOS), and `traits_compile.rs`'s two Region trait checks as Rust-only API (Region trait); `traits_compile.rs::dyn_types_are_send`, which asserts that `Box<dyn Region>` is `Send`, as Rust-only API (Send trait bound), as `skyframe_is_send` is listed — cost if wrong: none.
- Ruling: `NativeStream.java` and `ObjectRegistry.java` are unchanged: `parentOf` already names every Region class and Moc and Stc with their parents, `ObjectRegistry` has a loader for each of this phase's classes and PointSet, and neither comment describes a plan or a missing class — cost if wrong: none.
- Ruling: the minor findings the ledger deferred are not fixed in this task; the report lists them, grouped, under Gaps and risks — the brief asks for the sweep and the report, and the minors touch tests and documentation across the phase — cost if wrong: a later pass fixes them.
