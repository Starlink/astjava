# Phase 8: rulings

These are the decisions made while executing the phase 8 plan ([`2026-11-19-pure-java-ast-phase8-stc.md`](../superpowers/plans/2026-11-19-pure-java-ast-phase8-stc.md)) where the plan was wrong, unclear or silent, or where the port departs from Rust, C or jniast or keeps a C quirk.
They are copied from the plan's Shared Design (the rulings made when the plan was written), from the execution ledger (the controller's rulings) and from each task's report (the implementer's rulings), grouped by task; within a task the implementer's come first, in the order of the report, fix rounds included, then the controller's, in the order they were made.
Each ruling gives what was decided, why, and what it costs if wrong; the plan's rulings state what and why, and the cost here is added from what the tasks found.
A later ruling can reverse or amend an earlier one; a ruling that a later one replaced carries a bracketed note saying so.
There are 55 rulings: 9 from the plan, 43 by the implementers of Tasks 1 to 4, 1 by the controller, and 2 by the implementer of the sweep (Task 5).
Commits are cited by subject.
The phase report is [`2026-11-19-phase8-report.md`](2026-11-19-phase8-report.md).

## Plan: Shared Design

### Taken from C rather than Rust

- Ruling: a Region's `removeRegions` returns a copy of its current Frame, and each container turns a component that comes back as a Frame into a `UnitMap`, as cmpmap.c, tranmap.c, switchmap.c and frameset.c do; cmpmap.c and tranmap.c convert even a plain Frame component that was already there when another component changed, while switchmap.c and frameset.c convert only a component that changed — Rust returns a UnitMap directly and cannot show the second case, since its Frames are not Mappings — cost if wrong: none found; `RemoveRegionsTest` pins each case from `remove_regions_extra_probe.c`.
- Ruling: SelectorMap's Transform negates and re-closes its own Regions in place, restoring the old values in a `finally`, and leaves the attributes set, as selectormap.c does — Rust transforms negated copies, and C's dump after a transformation shows `Closed = 1` — cost if wrong: a SelectorMap is not safe for concurrent use (its Javadoc says so), and a dump after use differs from Rust's.
- Ruling: SelectorMap's MapMerge detects a simplified Region by identity (`simplify()` returned a different object), for any Region class, as C compares pointers — Rust compares with `equals` against a fixed list of nine classes — cost if wrong: a Region whose `simplify` returns a new but equal object rebuilds the SelectorMap, as in C.
- Ruling: SelectorMap's MapMerge merges neighbours as C's does (invert the neighbour, compare with `Equal`, which ignores Invert, restore it), so two equal 1-D SelectorMaps both applied forward in series become a UnitMap — Rust demands differing Invert flags; C is the authority, and the probe pins the case — cost if wrong: the simplified pair transforms differently from the pair, exactly as in C (a C bug, recorded in `docs/rust-c-differences.md`).
- Ruling: Stc's Transform and GetBounded set the encapsulated Region's Negated to its own value around the call, leaving it set, as stc.c does — C's `test("Negated")` is true after use — cost if wrong: a Negated test differs from Rust's after a transformation.
- Ruling: loaders follow C where C's result is usable (a SelectorMap with no `Reg1`, an `ObsLoc` of any class, `Ncoord` below 0 read as 0, the later dummy-FrameSet Regions given the first Region's FrameSet) and raise `AST__LDERR` where C would store a null and fail later (an Stc without `Region`, a missing `Coord<n>`), and a `Begin Stc` raises `AST__LDERR` since jniast's Stc cannot be instantiated — H9 prefers an error to a plausible value — cost if wrong: a dump C reads (or crashes on) does not load. [amended in Task 2: C does not load a SelectorMap with no `Reg1` either (its `astAnnul( NULL )` fails), and Java raises `AST__LDERR`]
- Ruling: error messages are C's (selectormap.c `AST__BDPAR` and `AST__BADNI`, stc.c's four `AST__STCIND` variants and `AST__STCKEY`), not Rust's — C is the reference for jniast's users — cost if wrong: none.
- Ruling: jniast's null handling: a null `regs` array or element, or a null Stc `region`, is a `NullPointerException` naming the argument, as phase 6 ruled for jniast arguments; a null `coords` array means no AstroCoords, as `jniastConstructStc` reads it — cost if wrong: an exception type or order differs from jniast's where both are null.

### `ERROR_ELLIPSE_LICENSED`

- Ruling: the `region_stc` cases `sky`, `catalog_entry` and `sky_coord`, whose AstroCoords Error Region C re-expresses as the best-fit Ellipse of a 1e-4 rad Circle's mesh, compare the Ellipse's points with a tolerance derived from the fit's conditioning, and if no bound can be derived, the Ellipse's `X` lines are dropped as Rust drops them off macOS and the omission is recorded in `docs/rust-c-differences.md` — the fit's defining points move about 1e-5 relative under last-bit `Math` differences — cost if wrong: an error in the fitted Ellipse goes unnoticed. [Task 3 dropped the axis ends and derived bounds for the centre and the two half-lengths]

## Task 1: removeRegions

### Implementer

- Ruling: `Frame` overrides `removeRegions()` with return type `Frame` (body `return this`, mapping.c's behaviour) — every Frame class's C RemoveRegions returns a Frame, and the override lets CmpFrame and FrameSet hold the results without an unchecked cast; frame.c itself has no RemoveRegions — cost if wrong: none in behaviour; one more override to read.
- Ruling: a component that did not change is marked shared (`markShared`) when installed into the copy, since C's copy holds a clone of it (reference count 2, so `astMAKE_SET1` attributes such as a ZoomMap's Zoom become immutable); a fresh component (new UnitMap, Region's Frame copy, a changed sub-container) is not, as C's count returns to 1 — cost if wrong: a ZoomMap shared between the two results could be changed through one.
- Ruling: the top-level `return this` (C's `astClone( this )`) does not mark the Mapping shared — the caller holds the same object, C callers annul one of the two handles, and Java's mark is never cleared, so marking would make the caller's own Mapping permanently immutable — cost if wrong: a SET1 attribute set on the result of an unchanged removeRegions is allowed where C (until the extra handle is annulled) refuses it.
- Ruling: a FrameSet component of a TranMap (any change) or a changed FrameSet component of a RateMap or SwitchMap becomes a UnitMap, dropping its Mapping, as C's `astIsAFrame` makes it (**C quirk**, kept, pinned by `tranmap_frameset_beside_region`, recorded in `docs/rust-c-differences.md`; Rust keeps the FrameSet) — cost if wrong: Java loses a FrameSet's Mapping exactly where C does.
- Ruling: Rust's `remove_regions_from_frameset` is translated as Rust wrote it, though the FrameSet `convert` gives holds no Region (its Javadoc says so and points to `checkRemoveRegions` and `RemoveRegionsTest`, which remove Regions a FrameSet holds) — cost if wrong: none; the weak test adds no false coverage claim.
- Ruling: the two NormMap oracle cases are skipped by name ("NormMap (not in jniast)") and the test asserts 9 run — as decided in the dispatch — cost if wrong: none until NormMap is ported.

## Task 2: SelectorMap

### Implementer

- Ruling: two equal 1-D SelectorMaps both applied forward in series simplify to a UnitMap, as C does (probe `series_1d_both_forward`) — the dispatcher's instruction and C is the authority; recorded in `docs/rust-c-differences.md` as a C bug — cost if wrong: a simplified pair transforms differently from the unsimplified one, exactly as in C.
- Ruling: a description without `Reg1` is AST__LDERR "astLoadSelectorMap(SelectorMap): The SelectorMap holds no Regions." — C does not load it either, but fails with AST__OBJIN from `astAnnul` of a null FrameSet pointer, a status that describes a C defect rather than the description — cost if wrong: a caller testing for AST__OBJIN gets AST__LDERR.
- Ruling: a `Reg<n>` component that is not a Region is AST__LDERR naming it, as SwitchMap and CmpRegion do — C would fail in `astCheckRegion` inside `astGetRegFS`/`astRegDummyFS` with its own status — cost if wrong: a different status for a corrupt description.
- Ruling: the loader does not compare the Regions' Frames (C does not; Rust rebuilds through its builder and refuses) — follows C — cost if wrong: a hand-made description with Regions in different Frames loads.
- Ruling: Transform restores Closed and Negated in a `finally`, so a failed Region transformation still puts them back, where C, with its status set, leaves them changed — the dispatcher's instruction — cost if wrong: after an exception the Regions differ from C's.
- Ruling: Transform treats a NaN or infinite input as bad (`Mapping.isBad`), where C tests `== AST__BAD` only — the library-wide Mapping contract (Mapping Javadoc) — cost if wrong: a NaN input gives badval here and a Region-dependent index in C.
- Ruling: Transform in a direction the SelectorMap does not define writes nothing, as C (only reachable if a description sets `Inv = 1`; the checks refuse it otherwise) — follows C — cost if wrong: none observable for a SelectorMap the library makes.
- Ruling: MapMerge raises AST__INTER if a Region's `simplify()` returns something that is not a Region (C would fail in `astCheckRegion`) — project rule for unexpected values — cost if wrong: none expected, no Region class does so.
- Ruling: `selectormapSerializationRoundtrip` checks the region count and badval through the dump text, since Rust's `regions()` and `badval()` accessors are not jniast API — keeps the public API exact — cost if wrong: none.
- Ruling: `switchmapRemoveRegions` asserts the SwitchMap comes back as the same object (C's clone), not Rust's comment that "those Regions become UnitMaps inside the selector" — SelectorMap installs no RemoveRegions in C or Rust — cost if wrong: none.

### Controller

- Ruling: Task 2's run command also runs `JniastSelectorMapTest` — the plan names that test under Files but its Step 3 pattern misses it — cost if wrong: none.

## Task 3: Stc, AstroCoords and StcResourceProfile

### Implementer

- Ruling: `Region.dump` reads the private fields directly, so no package-private raw accessors were added (the brief's Files line names them) — nothing outside `Region.dump` needs them; Stc's `regOverlay` already reads raw fields — cost if wrong: none, an accessor can be added when a caller appears.
- Ruling: Adapt is also written from the Region's own field when set, as region.c does — no class overrides Adaptive, so no output changes — cost if wrong: none.
- Ruling: a PointList with Closed set now dumps the value set (it dumped !Negated before) — C's region.c `Dump`, pinned by `region_dump_probe` — cost if wrong: none; this is C's output.
- Ruling: Stc's Transform and GetBounded leave Negated set on the encapsulated Region (C quirk kept; Rust does not) so `test("Negated")` is true after use — Review Focus 2, probe `used_plain`/`used_bounded`; recorded in rust-c-differences — cost if wrong: a Negated test differs from Rust's after a transform.
- Ruling: `Begin Stc` is AST__LDERR ("astLoadStc(Stc): An Object of the abstract class Stc cannot be read; only its subclasses can."), raised in `ObjectRegistry.load` so `isRegistered("Stc")` stays false — C reads it (probe `begin_stc`), jniast's Stc is abstract — cost if wrong: a hand-written or foreign dump of that class does not load.
- Ruling: an Stc dump without `Region`, or with fewer `Coord<n>` than `Ncoord`, is AST__LDERR — C stores null and segfaults (in `astRegDummyFS_` during the load; in `astCopy_` from `astGetStcCoord`), so no probe row; Rust also LDERR — cost if wrong: none (C crashes).
- Ruling: a `Coord<n>` that is not a KeyMap, a Region item that is not a Region, and a loaded AstroCoords value under a Region key that is not a Region are AST__LDERR — C would use the object as a Region/KeyMap (H9) — cost if wrong: such dumps fail to load.
- Ruling: `Ncoord` below 0 reads as 0, as C (probe `negative_ncoord`) — cost if wrong: none.
- Ruling: ERROR_ELLIPSE_LICENSED: no defensible tolerance exists for the Ellipse's axis-end points (on the near-circle the fitted direction follows the last bits of the mesh; Java on this machine is ~2e-5 from C, centre identical), so their values (X3..X6) are dropped from the `sky` and `sky_coord` dump comparisons on every platform; the centre (X1, X2) stays in the comparison at 1e-12, and the two half-lengths are compared within 1e-13, derived from the spread of the mesh distances that C's own two half-lengths show (5.2e-14 apart) — derivation in the test's class Javadoc; omission in `docs/rust-c-differences.md`; follows phase 7's ruling for an Ellipse fitted to a Circle — cost if wrong: an error in the fitted direction goes unnoticed. [the half-length derivation was replaced in fix rounds 1 and 2; the axis ends stay dropped]
- Ruling: numbers in sky dumps (Circle second points from `SkyFrame.offset2`) compared within 1e-12 absolute, citing `RegionEllipseOracleTest`'s 3e-14 bound for the same chain — cost if wrong: a few-ulp error is accepted, as elsewhere.
- Ruling: jniast null handling: a null `region` is `NullPointerException("region")`; a null AstroCoords element is `NullPointerException("coords[i]")`, raised first, as jniast's Java converter runs before its native null check; a null or empty `coords` array means no AstroCoords; a null or empty name array and null Regions are simply omitted from the KeyMap (jniast's converter) — plan item 8 — cost if wrong: exception order when both are null.
- Ruling: the package-private `StcResourceProfile(Region, KeyMap[])` overload is C's `astStcResourceProfile`; an in-package caller passing a literal null must cast (`(AstroCoords[]) null`) — outside the package only the public constructor is visible, so jniast callers are unaffected — cost if wrong: none.
- Ruling: a KeyMap holding null under a Region key gives C's "is a NULL pointer" AST__STCKEY message, pinned by a Java-only test (C's public `astMapPut0A` refuses NULL, so the probe cannot reach it; the message text is stc.c's) — cost if wrong: one message.
- Ruling: `stcCoord` (C's `astGetStcCoord`) skips an object under a Region key that is not a Region, where C casts blindly — such a KeyMap cannot be built or loaded here — cost if wrong: none.
- Ruling: `set("RegionClass=")` with an empty value is passed to Region, as C's `MATCH` needs at least one character of value — cost if wrong: a different error for an empty value.
- Ruling: `RegionStcOracleTest` runs only `stc_matches_c` (six cases, count asserted), overlap, astro_coords and unknown-key; `full_dumps_match_c` and `c_dumps_read_back_as_c_reads_them` are left to Task 4 (brief) — cost if wrong: none.
- Ruling: `rust-tests.txt`'s `stc_matches_c` reason was edited to drop StcResourceProfile, whose cases now run, beyond the removals the brief names — an accurate reason — cost if wrong: one reason string.

#### Fix round 1

- Ruling: the secondary bound depends on how close a mesh point lies to each fitted axis, so it is computed in the test rather than fixed — a platform whose fitted direction falls within about 2e-4 rad of a mesh point fails the precondition assertion and does not pass silently — cost if wrong: such a platform needs a second-order bound. [superseded in fix round 2: the platform's side is bounded after the fact, with no precondition]

#### Fix round 2

- Ruling: the refit duplicates `bestEllipse`'s arithmetic in the test rather than exposing its intermediates from main code — the bit-for-bit check ties the copy to the real fit — cost if wrong: if `bestEllipse` changes, the test fails until the copy follows.

## Task 4: The other Stc classes, and casting within the Stc hierarchy

### Implementer

- Ruling: `StcObsDataLocation.obs` is typed `AstObject`, with `setObs(PointList)` the only setter — C's loader stores an Object of any class there (probe `obs_loc_any_class`) — cost if wrong: none; the field is private.
- Ruling: the `obs_data` oracle case is built with `setObs`, as the C probe built it, rather than read from C's dump as Rust does — Java has C's protected setter — cost if wrong: none; the read-back path is covered by `cDumpsReadBackAsCReadsThem`.
- Ruling: `fullDumpsMatchC` transforms the two test points before writing the Full = 1 dump, as region_stc_probe.c's `emit` does (Rust does not; its Stc does not leave Negated set) — cost if wrong: none; this is C's order.
- Ruling: `catalog_entry` dumps are compared with SKY_TOL and the Ellipse machinery, `cDumpsReadBackAsCReadsThem` exactly (C's own numbers re-read) — as Rust; no new tolerance — cost if wrong: none.
- Ruling: the cast rows the brief asks for (astFindFrame between Stcs of the same and different classes) are in the probe with astConvert alongside, but in C neither reaches region.c `Cast` (lldb breakpoint on `astCastCopy_` never hit): `astFindFrame` matches with matchsub 0 and region.c `ConvertX` replaces Regions by their current Frames; the rows pin outcomes (all found in one Domain, none across Domains, astEqual 0 across classes); `Region.cast` itself is pinned by Java-only tests — cost if wrong: none.
- Ruling (superseded in fix round 1): `aCmpFrameOfStcsInOtherDomainsDoesNotConvert` was Java-only; it now takes its null from the probe's last case.
- Ruling: StcTest translates only `testConstructors`; `testReads`/`testChandra` need XmlChan (not in scope) — brief. [cost added in the sweep: cost if wrong: two of jniast's three Stc tests wait for XmlChan]
- Ruling: the Cast docs entry now says C crashes before `astCastCopy` and keeps the as-written castCopy description (including the Stc overread) as the defect it would be — the brief asked that the entry say the sibling cast reads past the smaller object; it would, but the crash comes first — cost if wrong: one docs paragraph. [amended in fix round 1: C crashes only until a description of the class Region has been read; after one, the CmpFrames do not convert]

## Task 5: Sweep and report

### Implementer

- Ruling: no `Sweep the deferred lists after phase 8` commit — Tasks 1 to 4 had removed every deferred entry and Javadoc deferral note naming this phase's classes, `JniastApiTest.IN_SCOPE` holds all seven, and `mvn test -Dast.writeDeferred=true` changed nothing — cost if wrong: none.
- Ruling: three Rust tests in files that name this phase's classes are neither translated nor listed: `test_guide.rs::guide_mentions_no_unported_class`, `test_mapmerge_audit.rs::no_checklist_is_orphaned` and the fixture-less class check in `src/serial/object_text.rs`; they check the Rust port's own documents and files, and phase 7 left such meta-tests unlisted (its report, Gaps and risks) — cost if wrong: `rust-tests.txt` stays incomplete for Rust-only meta-tests until a checker exists.
