# Phase 4: rulings

These are the decisions made while executing the phase 4 plan ([`2026-11-16-pure-java-ast-phase4-spectral.md`](../superpowers/plans/2026-11-16-pure-java-ast-phase4-spectral.md)) where the plan was wrong, unclear or silent, or where the port departs from Rust or C or keeps a C quirk.
They are copied from the execution ledger (the controller's rulings) and from each task's report (the implementer's rulings), grouped by task; within a task the implementer's come first, in the order of the report, then the controller's, in the order they were made.
Each gives what was decided, why, and what it costs if wrong.
A later ruling can reverse an earlier one.
There are 104 rulings.
The phase report is [`2026-11-16-phase4-report.md`](2026-11-16-phase4-report.md).

## Plan review

- Ruling: execute without a separate plan review — the user asked to "do the next phase" after approving phase 3's identical process — cost if wrong: the user redirects the plan mid-phase.

## Task 1

- Ruling: the 1-D frequency correction factor is stored in the step's argument slot during `transformInto` and written by Dump, as C's `FrameChange` does (Rust keeps a separate unwritten cache) — C fixtures carry these cards and SpecFrame/FitsChan dumps must match C — cost if wrong: dumps after a transform gain cards Rust's lack; recorded in `docs/rust-c-differences.md`.
- Ruling: `SimplifyRegressionTest` takes its dumps before transforming (second read of the input for the "computes the same" check) — the old ordering asserted an invariant C itself does not satisfy for SpecMaps — cost if wrong: a simplify that mutates the input only through a transform-time cache would go unnoticed (the dump-unchanged and computes-unchanged checks are otherwise intact).
- Ruling: `tests/test_specmap.rs::equality` deferred as `astEqual (out-of-scope method)` — C's `Equal` has no Java consumer and TimeMap's `equality` is deferred the same way — cost if wrong: SpecMap `Equal` must be added when something (e.g. a later CmpMap/FrameSet comparison) needs it.
- Ruling: inputs that are not finite (NaN, infinities, on all three axes of a 3-D map) become AST__BAD when copied, while each step otherwise tests `!= AST__BAD` exactly as C — project rule "Infinite input coordinates" and the no-NaN-output tests — cost if wrong: differs from C only for non-finite inputs.
- Ruling: no final non-finite-to-bad pass (Rust's SP-4), so an ENTOFR/FRTOEN/WNTOFR/FRTOWN/BTTOVL/VLTOBT overflow gives an infinity as in C — follow C — cost if wrong: an infinity reaches callers where Rust gives AST__BAD; recorded in `docs/rust-c-differences.md`.
- Ruling: C quirk kept: `astInitSpecMap`'s AST__BADNI message says "it should be 1 or 2" (trailing space included) — keep C's text — cost if wrong: message text only; recorded as a C quirk.
- Ruling: load errors are AST__BADIN with C's messages (Rust: AST__SPCIN) — follow C — recorded.
- Ruling: `MapMerge` cancels a pair when the second code is the first's inverse and the first `nargs` arguments are astEQUAL — this is exactly the union of C's five `PAIR_CVT2` groups (0, 1, 2, 3 and 6 user arguments, 17 pairs) — cost if wrong: none found; every simplify fixture with SpecMaps now matches C.
- Ruling: `Rate` tests `at[0] != AST__BAD` exactly as C, not `isBad` — follow C.
- Ruling: `Cvt.inverse()` pairs each odd C code with the next even one, the pairing C's SWAP macros spell out; the change-of-rest-frame sign is likewise taken from the code's parity (odd = to heliocentric = -1), matching each `case` of C's `FrameChange`.

## Task 2

### Implementer

- Ruling: jniast's `getSourceVRF()` and `setSourceVRF(double)` kept with jniast's signatures and bodies (`getD`/`setD` of a string attribute, so they always fail, AST__ATGER and AST__ATTIN) — the public API must be exactly jniast's — cost if wrong: none beyond jniast's own; callers use `getC`/`setC("SourceVRF", ...)` as with jniast.
- Ruling: SpecFrame `Overlay` onto a non-SpecFrame result follows C: a copy with System and AlignSystem cleared is overlaid, and when that changes the copy's System its Title, Label, Symbol (and Unit unless one was used with WAVE) are cleared and not overlaid; Rust overlays them; recorded in `docs/rust-c-differences.md` as a C quirk — cost if wrong: a plain Frame made from a SpecFrame template keeps C's lost Title where a reader might expect it.
- Ruling: Review Focus 2 is tested as specframe.c's code gives it: `SetUnit` stores a Unit of another kind and leaves the System alone (C has no System change on SetUnit; the plan's "changes System" wording does not match C), the Label/Symbol keep their defaults, and a SpecOrigin cannot be set (`ToUnits`) or read (`GetSpecOriginCur`) with AST__BADUN and C's messages; these expectations are read from the C source, not a probe run, while the C-oracle cases NewUnitTest spec_* and SystemChangeTest spec_* cover unit changes and restoration — cost if wrong: two SpecFrameTest tests would encode a misreading of C.
- Ruling: `OriginSystem`, `OriginStdOfRest`, `ConvertSourceVel`, `SorConvert` and `EqualSor` are ported in Task 2 because C's attribute setters call them; `OriginSystem`/`OriginStdOfRest` go through `convert`, whose SpecFrame `match`/`subFrame` are Task 3's, so no Task 2 test changes System or StdOfRest with a SpecOrigin set — cost if wrong: until Task 3 such a change re-expresses the SpecOrigin through Frame's matching (wrong value or AST__ATSER).
- Ruling: the used-units record is a fixed `String[11]` indexed by System code where C grows an array to `nuunits`; dumps, copies and restores are the same — cost if wrong: none visible.
- Ruling: the FK5 J2000 SkyFrame C keeps in a static for RefRA/RefDec and `astGetRefPos`/`astSetRefPos` is made per call, as `Frame.obsSkyFrame` does (no mutable static state) — cost if wrong: speed only.
- Ruling: `tests/test_specframe.rs::test_equal_same`, `test_not_equal_different_system`, `test_not_equal_different_sor` added to rust-tests.txt as `astEqual (out-of-scope method)`, as Task 1 listed `test_specmap.rs::equality` — cost if wrong: three tests untranslated until astEqual exists.
- Ruling: tests outside the brief's list were brought in where SpecFrame alone makes them runnable (KeyMap, CmpFrame overlay, FrameLoad, SectionLoad, UseDefs, NewUnit, SystemChange, FrameAxisAttrs, FrameClassAttrs CmpFrame, SkyGeometry kind 0, FrameTest NormUnit); FrameLoadTest and SectionLoadTest gate their skips and would have failed — cost if wrong: none.
- Ruling: `CScan.hasWord`/`numberThenWord` replace private copies in Frame and TimeFrame, and SpecMap's `AST__C`/`AST__H` become package-private, to share them with SpecFrame — cost if wrong: none (full suite passes).
- Ruling: `NativeClassesTest`'s example of an absent class changed from SpecFrame to FluxFrame — the test is of the helper — cost if wrong: none.

### Controller

- Ruling: Review Focus 2 wording (a Unit of another kind changes System) is wrong; C's SpecFrame SetUnit (specframe.c SetUnit) only records the unit for the current System — tests follow C — cost if wrong: none, C is the reference

## Task 3

- Ruling: the Rust conversion tests of `test_specframe.rs` that compare with formulae (frequency to wavelength, energy, wave-number, velocities, LSRK shift, unit changes) assert C's values from the task-3 probe (within `CValues.cValueEq`) and the class of C's simplified Mapping, instead of the formulae and tolerances — expected values must come from C — cost if wrong: none; C's values satisfy the Rust tolerances.
- Ruling: the DSBSpecFrame rows of `spec_convert` (6 named cases) and `spec_origin_sor` (8 rows) and of `spec_use_defs` CONVERSIONS (5 rows) are skipped by name/class with exact count assertions, left to Task 4 (whose plan step names "the DSBSpecFrame cases of the oracles already converted"); not added to rust-tests.txt, as the Rust tests themselves are translated — cost if wrong: Task 4 must enable them (the counts 202, 8 and 23 will need raising).
- Ruling: Review Focus 3 compares the Frames inside a FrameSet, not the FrameSet's own dump, because C's `astConvert` deliberately sets the Base of a FrameSet argument (frameset.c `ConvertX`, as AST documents); Java does the same — cost if wrong: none for Frames; a reader expecting a FrameSet's Base to stay unset would be surprised, as with C.
- Ruling: `MakeSpecMapping` throws `AST__BADUN` at the point C calls `astError` (C continues with steps 0 and 9 under bad status, which do nothing) — cost if wrong: none observable.
- Ruling: the pinned C (3d3f255f) already contains the AlignSpecOffset fix the Rust repo lists as "RESOLVED (upstream 65bd5b6e)" (the `result_origin == AST__BAD` fallback in `MakeSpecMapping`); it is followed, so there is no pinned-C/Rust difference to record and `docs/rust-c-differences.md` is unchanged — cost if wrong: none; the spec_origin_sor oracle and testspecframe.c Error 13 confirm it.
- Ruling: SubFrame's plain-Frame branch keeps C's quirk of clearing System after setting Label, Symbol and Unit explicitly (a System change clears them again); Rust follows it too (audit SP-5), so it is not recorded as a difference — cost if wrong: none; OverlayTest `spec_pick_padded`/`spec_wave_pick_padded` match C.

## Task 4

### Implementer

- Ruling: `Match` calls the target's SubFrame a second time when the target axis is a DSBSpecFrame, and SubFrame finds each observed sideband by setting SideBand to `observed` and setting it back, leaving the target's SideBand set — both as C, where Rust does neither — because the target mutation is visible (probe `sideband_left_set`: `astTest(from, "SideBand")` is 1 after an aligned conversion) and the second call's result copies that state — cost if wrong: none, C is the reference; recorded in rust-c-differences.md.
- Ruling: C quirks kept: any setting holding `dsbcentre=` is taken for DSBCentre (`Title=dsbcentre=3` fails with AST__ATTIN, probed); GetLO's second message line reports the DSBCentre as 3e+10 Hz (C reads it under error status, probed); GetLO messages name `astGetImagFreq` and ToLSB/ToUSBMapping get TopoMap before checking IF/DSBCentre — cost if wrong: messages differ from what a reader expects, as C's do.
- Ruling: `observed_sideband_usb/lsb/lo` added to rust-tests.txt as `Rust-only API (DSBSpecFrame::observed_freq)` — C has no such function — cost if wrong: three tests untranslated.
- Ruling: C's `ToLOMapping` is not ported (C marks it unused and never calls it); Rust's `lo_offset_mapping` is translated as the C-reachable conversion to and from SideBand=LO through ToLSB/ToUSBMapping's LO branches (`loOffsetMappings`, probed) — cost if wrong: none observable.
- Ruling: Rust tests that set the internal topocentric DSBCentre/IF directly are translated with attribute settings in a topocentric FREQ Hz frame (DSBCentre exact) and the IF in GHz, applied identically in Java and the C probe; the direct sideband-Mapping tests call the package-private `toLsbMapping`/`toUsbMapping` and compare with the C value of the equivalent aligned conversion, whose simplified Mapping is the same WinMap — cost if wrong: none; the conversions themselves are also asserted.
- Ruling: `SpecFrame.castCopy` added so that FrameFinder's matchSub cast of a DSBSpecFrame template into a SpecFrame gives a SpecFrame as C's `astCastCopy` does (probe `cast_domain`: findFrame returns NULL, where Java threw AST__INTER) — cost if wrong: none; plain-Frame casts unchanged.
- Ruling: `FitsFsetToStore.makeFitsFrameSet` refuses a world Frame with a SpecFrame axis (`UnsupportedOperationException ... needs SpecFrame`), and the four `a20070718_*` wcsconv rows move from `needs DSBSpecFrame` to `needs SpecFrame` — once DSBSpecFrame loads, the writer (whose `spectralAxes` assumes no SpecFrame) wrote spectral axes as generic axes, giving wrong headers (fits-wcs: no CUNIT3/SPECSYS/RESTFRQ/IMAGFREQ, CRVAL3 off) or failures (fits-aips, fits-aips++), which the wcsconv gate cannot list; the refusal mirrors Task 2's read-side `needs SpecFrame` refusal and leaves Task 8 to replace it — cost if wrong: the reason names a class that exists (as the 66 Task 2 rows already do), and Task 8 must remove the refusal.
- Ruling: `test_dsbspecframe_read.rs` left untranslated and unlisted for Task 7 (needs the FitsChan spectral read), as the dispatch says — cost if wrong: none.

### Controller

- Ruling: accept the interim FitsFsetToStore.makeFitsFrameSet refusal of a world Frame with a SpecFrame axis (`needs SpecFrame`) — since Task 2 such FrameSets were written with the spectral axis as a plain axis, giving wrong headers — cost if wrong: Task 8 must remove it (carried into Task 8's dispatch).

## Task 5

- Ruling: SFCBRW read and write follow the pinned C, which already reads `SFCBRW` and `SRCBR` and writes `SFCBRW` (fix 3b1f3f5a is in the pin; the Rust quirk file dates it after the pin as b4457da9); recorded in rust-c-differences.md — cost if wrong: none, both names read either way.
- Ruling: C quirk kept: Overlay onto a non-FluxFrame clears and re-sets the template's own System and AlignSystem, leaving its AlignSystem set and, when the System changes on clearing, clearing its Label/Symbol/Title and resetting its Unit (probed with `astOverlay_`) — cost if wrong: a template mutated as C mutates it.
- Ruling: C behaviour kept: `SetUnit` records the Unit as given, trailing spaces included, in the per-System record (probed: `UFlxdn = "Jy  "`), where Rust trims — cost if wrong: none observable but in dumps.
- Ruling: existence checks C makes with `astUnitMapper` (GetSystem, SetUnit's `UnitsOK`) use `Units.relabel(from, to, null)`, which finds the conversion without building the Mapping, as `NewUnit` already does; building it would throw "needs a MathMap" for logarithmic units that C accepts (probed: `Unit=log(Jy)` gives System FLXDN, Label `log( Flux density )`) — cost if wrong: none; the conversion found is the same.
- Ruling: C's `astFluxFrame` units check after applying the options string is not ported; jniast's constructors take no options, so the defaults always pass (comment at the constructor) — cost if wrong: none reachable.
- Ruling: C's `SubFrame` alignment System (`align_sys`, and `report`) are not computed, since `MakeFluxMapping` does not use them (comment at `subFrame`) — cost if wrong: none; the getters have no side effects and `GetSystem`'s error is raised by `MakeFluxMapping` first anyway.
- Ruling: the loader refuses a `SpcFr` that is not a SpecFrame with AST__LDERR; C stores any Object (no C counterpart, like `checkLoadedPerm`); recorded in rust-c-differences.md — cost if wrong: a corrupt dump C would load fails here.
- Ruling: a missing `smap_in`/`smap_out` in `MakeFluxMapping`, which C would pass to `astTran1` as NULL, throws AST__INTER; with a SpecFrame template `astConvert` either succeeds or reports an error, so it is not reachable — cost if wrong: a different error status in an unreachable case.
- Ruling: `test_fluxframe.rs`'s three `astEqual` tests listed as `astEqual (out-of-scope method)` (as SpecFrameTest's), and its 16 SpecFluxFrame tests as `SpecFluxFrame (not yet ported)`; the round-trip tests that end in `equal` compare C's dump byte for byte and `equals` instead — cost if wrong: Task 6 must translate and unlist the 16.
- Ruling: Rust's `FluxSystem::parse(" SFCBR")` (Rust-only API) is tested through the package-private `systemCode`, the C function it mirrors; the other parse cases go through `System=` settings checked against C — cost if wrong: none.
- Ruling: Rust tests that build a FluxFrame with `set_spec_frame` + `set_spec_val` give the SpecFrame to the constructor (default SpecVal bad) and set SpecVal, as C's only way to give a SpecFrame; `make_flux_mapping` tests call the package-private `makeFluxMapping` and also check `convert` — cost if wrong: none.
- Ruling: `NativeClassesTest`'s synthetic dump now names GrismMap instead of FluxFrame as its missing class — cost if wrong: none.

## Task 6

### Implementer

- Ruling: `MakeMapF`/`MakeMapI` throw `needs RateMap`, not the brief's `needs MathMap` — both C functions build `astRateMap( m, 0, 0, ... )` before `astMathMap`, and the brief's own rule is "the first absent class C builds" — cost if wrong: a one-word change in two messages and in the tests' `assertNeeds("RateMap", ...)`.
- Ruling: the dispatch's premise that unit-only or same-system conversions avoid the TranMap does not hold in pinned C — `MakeSFMapping` short-circuits only for equal components, and every other conversion whose standardised SpecFrames convert reaches `MakeMapF` (probed: Jy to mJy, GHz to MHz, FREQ to WAVE all give CmpMaps); these throw here, and only equal components and no-conversion cases work — cost if wrong: none, C is the reference.
- Ruling: conversions involving logarithmic flux units throw `needs MathMap` from `UnitsMapper`, because C's `astEqual` of the FluxFrames builds that unit MathMap (fluxframe.c `MakeFluxMapping`'s `astUnitMapper`) before `MakeMapF` (testspecflux.c Errors 18 and 25) — cost if wrong: a different absent class named.
- Ruling: `UnitsMapper`'s message now ends `needs MathMap` (reached by this task's log-unit conversions); the full `writeDeferred` run changed no entry — cost if wrong: none.
- Ruling: the part of `MakeSFMapping` after `MakeMap3` (map1, map4 = inverted `MakeMap2(result)`, map5, the series CmpMaps, `astSimplify`) is not ported, nor `MakeMap3`'s TranMap/CmpMap: `MakeMap3` can only throw or make no Mapping here, so they are unreachable; `makeMap3` keeps the calls C makes (standardised Frames, MakeMapF, MakeMapI, the SpecFrame conversion) — cost if wrong: when RateMap, MathMap and TranMap are ported, that remainder must be ported from C.
- Ruling: C's `astEqual` is ported as a private helper of SpecFluxFrame (object.c `astEqual_` pointer test, frame.c `Equal`), since no public astEqual exists and only Frame's `Equal` applies to SpecFrame/FluxFrame — cost if wrong: none.
- Ruling: the loader refuses components that are not a SpecFrame and a FluxFrame with AST__LDERR (C keeps them and casts later; Rust refuses); recorded in rust-c-differences.md — cost if wrong: a corrupt dump C would load fails here.
- Ruling: C quirk not kept: `GetTitle` formats into a 101-character static buffer; Java returns the whole Title (as Rust); recorded in rust-c-differences.md from reading the code, not probed — cost if wrong: none for Labels under 93 characters.
- Ruling: Rust tests that assert converted values C produces through RateMap/MathMap/TranMap (test_specflux.rs Errors 8-29, `test_specflux_jy_to_fluxdenw`, `_inverse`, `_log_units`, `_surface_brightness`, `test_specfluxframe_convert_different_spec_system_converts`, `test_specfluxframe_not_equal_different_spec`) are translated as assertions that the conversion throws, with C's values in the Javadoc — cost if wrong: none; the values return when the classes do.
- Ruling: `astEqual` tests (testspecflux.c Errors 30-32, `test_specfluxframe_equal`, the two serialisation round trips) check the conversion C's `Equal` makes (simplifies to a UnitMap), plus dump text and `equals` for the round trips; Error 32's conversion throws `needs RateMap` (C: a CmpMap, `astEqual` 0, probed) — cost if wrong: none.
- Ruling: Rust's `pick_axes_snapshot_full_returns_specflux` and `pick_axes_snapshot_partial_returns_basicframe` (Rust-only API) are tested through `pickAxes` with C's classes: CmpFrame for both axes in either order, SpecFrame for axis 1 (probed) — cost if wrong: none.
- Ruling: Rust's `set_rest_freq(4.57e14)` is given as C's attribute string `RestFreq=4.57e14` (GHz), with C's dump (`RstFrq = 4.5700000000000001e+23`) — cost if wrong: none.
- Ruling: Rust tests that set a FluxFrame's SpecFrame and SpecVal give the SpecFrame to the constructor and set SpecVal (as Task 5) — cost if wrong: none.

### Controller

- Ruling: MakeMapF/MakeMapI throw `needs RateMap`, not the plan's `needs MathMap` — specfluxframe.c builds astRateMap (839/985) before astMathMap (846/998), and the rule is the first absent class C builds — cost if wrong: one word in two messages.
- Ruling: accept that in pinned C MakeSFMapping short-circuits only when both components are astEqual, so every other SpecFluxFrame conversion (Jy->mJy, GHz->MHz, FREQ->WAVE) needs RateMap/MathMap/TranMap and throws here; SpecFluxFrame is usable for equal/swapped components, picks and dumps until those Mappings (spec item 4) arrive — cost if wrong: SPLAT's flux-axis conversions wait for phase 5; reported to the user.

## Task 7

### Implementer

- Ruling: `test_fitschan_spec_unit_oracle.rs` tests C's `SpectralAxes` (a write), not the read; it is converted and translated now as `FitsChanSpecUnitOracleTest`, which checks the read of the cube for every case and compares each write with C, with the cases that stop listed in the test with their measured reason, checked both ways: 5 `needs SpecFrame` (the interim `makeFitsFrameSet` refusal) and `unit_log`/`unit_log_no_dims` `needs MathMap` (setting `log(Hz)` through the FrameSet builds the MathMap C builds) — cost if wrong: Task 8 empties the SpecFrame entries; the MathMap ones stay until MathMap.
- Ruling: `test_dsbspecframe_read.rs` reads FITS-CLASS headers, whose read is not available here (`encoding FITS-CLASS`, raised before FitsEncClass's VELO-LSR refusal); translated as `DSBSpecFrameReadTest` with C's values and both fixtures listed in the test with that reason, checked both ways; the DSBSetUp path itself is covered through FITS-WCS by `dsbCentrePrefersAxrefOverCrval` and `FitsWcsSpectralTest.dsbSpecFrameTakesEveryKeywordAsC` (C probe, IF/DSBCentre/SideBand exact) — cost if wrong: Task 9 removes the two entries.
- Ruling: `FitsChanCInlineTest.azelWritesAsFitsWcs` now runs on a fresh thread (the `SkyLastTest` pattern): reading `alt.header` (now possible, a DSBSpecFrame with the same OBSGEO and date) fills C's LAST table for that observer, and the AZEL write then interpolates from it, moving CRVAL by ~1e-6 deg; the expected values come from a C probe with empty tables, and C's LAST cache is ported faithfully (per thread) — cost if wrong: none to behaviour; other order-dependent AZEL tests could appear as more spectral headers are read in one thread.
- Ruling: the exclusion lists of `FitsChanErrorsTest`, `FitsChanCursorOracleTest`, `FitsChanEncodingsOracleTest` and `FitsChanWriteTest` keep or gain `needs SpecFrame` entries measured from the write (the interim refusal Task 4 added), and `FitsChanErrorsTest.check` and `FitsChanEncodingsOracleTest` now measure write refusals as they measured read ones — cost if wrong: Task 8 must empty them.
- Ruling: a `-TAB` spectral axis with TabOK above zero keeps phase 1's `needs LutMap` (TabMapping); with TabOK at most zero it now reaches `WcsSpectral` and gives C's AST__BDFTS (probe `tab_tabok0`) — cost if wrong: none; C confirms.
- Ruling: a `-LOG` spectral axis with CRVAL zero is AST__BDFTS (C's `LogWcs` returns NULL and `WcsSpectral`, unlike `WcsOthers`, has no UnitMap fallback; probe `log_zero`) — cost if wrong: none.
- Ruling: Rust's order of SpecFrame settings, its skipping of a blank Unit, its case-insensitive system parse and its 0 observation centre are not followed (recorded in `docs/rust-c-differences.md`) — cost if wrong: none; C is the reference and the wcsconv native dumps match C byte for byte.
- Ruling: `Pal.gc2gd` returning null (never for WGS84) skips the observer settings, as `FitsWcsCelestial` already does; C ignores `eraGc2gd`'s status — cost if wrong: none (unreachable).

### Controller

- Ruling: keep phase 1's `needs LutMap` for a -TAB axis with TabOK 1 — C reports AST__NOTAB from GetNamedTable when no table is supplied, but supplying tables needs FitsTable support this library lacks; revisit with the -TAB phase — cost if wrong: one reason string.

## Task 8

- Ruling: the write's grism branch throws nothing — C's grism branch extracts an existing GrismMap (`ExtractGrismMap`) and builds none, and a FrameSet of this library's classes holds none (reading a `-GRI`/`-GRA` header already throws `needs GrismMap`, Task 7), so the branch makes only C's System/Unit changes through the FrameSet and falls through, per the common rule "where C finds no such object in a FrameSet of this library's classes, port C's behaviour"; the brief's "`-GRI`/`-GRA` throws `needs GrismMap`" holds for every header that has one — cost if wrong: none observable until GrismMap arrives, when `ExtractGrismMap` must be ported.
- Ruling: `AddEncodingFrame` is ported here as part of `MakeFitsFrameSet` (Task 9's files are FitsEncClass, FitsSpecTrans, FitsChan) — without it a FITS-CLASS write of a spectral cube would fail `CLASSFromStore`'s SPECSYS SOURCE check and return 0 where C writes, instead of reaching the kept refusal — cost if wrong: none; Task 9 builds on it.
- Ruling: `FitsChanEncodingsOracleTest` exclusions are keyed by header and encoding, so the other six encodings of the 8 headers whose FITS-CLASS write stops are compared with C (they match) — cost if wrong: none; the gate is stricter.
- Ruling: `crota-3d.head` is added to `NOT_ROUND_TRIPPED` as WRITE_FAILS — C's write into a new FitsChan also returns 0 (`write_probe`) — cost if wrong: none.
- Ruling: vopt-f2w, vopt-geo, vrad-v2f and vrad-wav are added as DIFFERS — their `-X2P` axes written into a new FitsChan have the reference at pixel 1 and CDELT from the numerical rate there, and read back differ from the header by up to 1e-3 m/s; C's cards and C's read-back values are identical to Java's to the last digit (`roundtrip_probe`) — cost if wrong: none.
- Ruling: the galactic case of `referencePositionIsTheCelestialReferencePoint` compares CDELT2 to 1e-16 and PC to 1e-12 absolute — traced: the same keys differ by the same amounts in the 2-D galactic write without a spectral axis (`gal_probe` vs Java), the phase 3 FitLine/libm cause; VELOSYS, CRVAL1/3 and the spectral cards are exact — cost if wrong: hides a 1e-12 PC error in that one test.
- Ruling: a `-TAB` spectral axis throws `needs FitsTable` for any Mapping `IsMapTab1D` returns, as phase 3 ruled for `OtherAxes` (C dereferences a null FitsTable when the Mapping comes without table columns) — cost if wrong: one reason string.
- Ruling: two SpecFrames, or celestial axes for a SpecFrame's reference position that give no FrameSet or fiducial point, make no FrameSet, as phase 3 ruled for two SkyFrames (C passes NULL to `AddEncodingFrame`, which dereferences it) — cost if wrong: none observable (C crashes).
- Ruling: a bad CRVAL is stored as not set — C's `SetItem` stores `AST__BAD` and `GetItem` gives the same `AST__BAD` for an item not set — cost if wrong: none.
- Ruling: the RESOLVED (upstream e09bc221) quirk is already in the pinned C (as 0dfd0f74), so there is no difference to follow or record beyond noting it (likewise 85e75a4e and 7f4ba71d) — cost if wrong: none; C, Rust and Java agree.
- Ruling: `spectral_axes.rs::try_grism_cascade_emits_gri_pv_and_chain` (a Rust unit test never listed) is added to `rust-tests.txt` with `GrismMap (out of scope)`: it builds a GrismMap and tests a Rust-only helper — cost if wrong: one entry.
- Ruling: `x2p_cascade_picks_freq_for_wavelength_axis` builds its axis by setting a frequency axis to WAVE through the FrameSet (testfitschan.c's construction) instead of a MathMap; `nonlinear_unit_is_cleared` expects `needs MathMap`, since the `log(Hz)` conversion is the MathMap C builds — cost if wrong: none.
- Ruling: Rust's clones in the `-X2P`/grism trials, its `LOCAL` for Local_group, its first-axis-only description, its early return on a bad reference value, its blank-Label skip, its VELOSYS on a clone, and its unformatted RefRA/RefDec are not followed (recorded in `docs/rust-c-differences.md`) — cost if wrong: none; C is the reference and the wcsconv rows match C.

## Task 9

- Ruling: C's `ClassTrans` reads an uninitialised `crval` when the spectral axis has no CTYPE (one-axis headers such as class-spec.head); Java uses 0, the value C's recorded wcsconv outputs (class-spec, class-spec-hel) encode and Rust uses — C has no defined value to follow — cost if wrong: a different source velocity for such headers, which the wcsconv gate would show.
- Ruling: in `CLASSFromStore`, C's `crval[]` elements for axes the CRVAL loop did not reach are uninitialised; Java uses 0 — they are read only in a write that returns 0 and whose cards are removed — cost if wrong: none observable unless C's garbage made `SetValue` fail.
- Ruling: `FitsChanEncodingsOracleTest.writesMatchC` runs on a fresh thread — C's probe runs the cases in order in one process with empty LAST tables at the start; on the surefire thread earlier tests' LAST values changed ELEVATIO by 14 ULP — cost if wrong: none; the test is stricter about order, not looser.
- Ruling: the remaining 1-ULP ELEVATIO difference of `source_zsource`/`topocent` is accepted by the existing 4-ULP card gate — traced to `Math.atan2` in the reference pixel's declination; the AZEL chain itself is bit-identical — cost if wrong: hides a 1-ULP error in that card.
- Ruling: the fresh-thread helper moved to `testsupport/FreshThread` (it was duplicated in `SkyLastTest` and `FitsChanCInlineTest`), rather than adding a third copy — cost if wrong: none.
- Ruling: `FitsChanCursorOracleTest`'s "waiting for another encoding" mechanism is removed, since no encoding is unread; any read stopping now must be listed in `EXCLUDED` — cost if wrong: none.
- Ruling: `writeClassSubtractsRestfreq`, `writeClassSflToGls` and `roundTripClass` pass `CLASSFromStore` a FrameSet with a plain 3-axis Frame (in which C's templates find no SpecFrame or SkyFrame, so no velocity or azimuth cards), since C's writer, unlike Rust's `write_to_cards`, needs a FrameSet — cost if wrong: none.
- Ruling: `spectralCdeltIsScaledToHertzOnce` checks a property (kHz and Hz descriptions write identical cards) rather than C numbers, as `CLASSFromStore` cannot be called on a hand-made store in C, and `AddEncodingFrame` forces Hz on every C write — cost if wrong: none; it pins the fix the pinned C has.
- Ruling: Rust's `translate_class` (runs last, skips what C reports, comments its cards) and `write_to_cards`/`write_velocities_from_frameset`/`write_azel_from_frameset` (pre-checks, default comments, no default EQUINOX, no NAXIS rewrite, CTYPE scans, matched-Frame normalisation) are not followed; recorded in `docs/rust-c-differences.md` — cost if wrong: none; every FITS-CLASS gate matches C.

## Task 10

- Ruling: the over-run guard sits just before the CDELT scaling, where C writes past its array — earlier writes of CRPIX/CRVAL/CTYPE are unchanged — a FrameSet never reaches it, so no cost.
- Ruling: uninitialised crval reads in C are replaced by 0 (as the existing code comment says) — unreachable from a FrameSet — none.
- Ruling: causes of the three FITS-CLASS write failures not investigated beyond C's identical outcome — the test only needs the outcome — none.

## Final review fix pass

### Implementer

- Ruling: a CmpFrame cast into a SpecFluxFrame keeps copies of the CmpFrame's components whatever their classes, as C's `astCastCopy` does, rather than giving null for components other than a SpecFrame and a FluxFrame — the cast SpecFluxFrame is only ever the template of a match against a SpecFluxFrame, whose `Match` and `SubFrame` read its axes and attributes and overlay it, never its SpecFrame and FluxFrame, so C's behaviour is defined there — cost if wrong: a `ClassCastException` if a later path reads the components of such a SpecFluxFrame as a SpecFrame and a FluxFrame.
- Ruling: SpecFluxFrame's default Title reads the components' Labels as Frames, as C's `GetTitle` does through `astGetLabel`, so it works for a SpecFluxFrame made by that cast — cost if wrong: none.
- Ruling: a CmpFrame cast into its own class is a copy, not `castCopy` — C's `astCastCopy` into the same class is `astCopy` followed by `astCleanAttribs`, which changes nothing in a valid object — cost if wrong: none.
- Ruling: C's crash in `Cast` when no plain CmpFrame has been made is not copied; Java gives what C gives once one has been — cost if wrong: none.

### Controller

- Ruling: when re-expressing the SpecOrigin or SourceVel fails, a StdOfRest, SourceVRF or SourceSys setting or clearing throws and keeps the old value, where C throws and stores the new one; recorded as a C quirk in `docs/rust-c-differences.md` — C's state is inconsistent (a new rest frame with the SourceVel of the old one) — cost if wrong: after such a failed setting, later attribute values, the default Title and the dump differ from C's.
- Ruling: the FITS-CLASS write at the edge of `IsMapLinear`'s tolerance (fuzz header `h00886`, residual 5.63e-5 pixels against 4.99e-5; C writes, Java returns 0) is recorded, not fixed — rounding in an ill-conditioned linear fit, not a porting error — cost if wrong: an occasional FITS-CLASS header near the tolerance that C writes and Java does not.
