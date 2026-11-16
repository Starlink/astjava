# Pure-Java AST: phase 4 report

## Summary

Phase 4 ported the spectral and flux classes on 16 November 2026, following the plan [`2026-11-16-pure-java-ast-phase4-spectral.md`](../superpowers/plans/2026-11-16-pure-java-ast-phase4-spectral.md).
SpecMap, SpecFrame, DSBSpecFrame, FluxFrame and SpecFluxFrame now exist with jniast's public API, and `JniastApiTest` passes for all five with no exclusions.
`FitsChan.read()` now reads spectral axes from FITS-WCS, FITS-PC, FITS-IRAF, FITS-AIPS, FITS-AIPS++ and FITS-CLASS headers, and `FitsChan.write()` writes them in each of those encodings.
Of the 194 rows of C's FITS conversion regression table (`ast_tester/fixtures/wcsconv/cases.txt`), 188 now give C's cards, against 118 at the start of the phase; the other six need MathMap (3), GrismMap (2) or SplineMap (1).
47 more headers of the transform oracle now read and transform as C's do, leaving 4, and nine C-probe oracle suites of the Rust port were converted and pass.
SpecFluxFrame converts only between equal components: pinned C builds a RateMap, a MathMap and a TranMap for every other conversion, unit changes included, so those throw `needs RateMap`, and SPLAT's flux-axis conversions wait for the phase that ports those Mappings.
Porting found five places where C writes past a buffer or reads memory it never set, and a new route to the null dereference phase 3 found in `MakeFitsFrameSet`; they are recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md) and listed below.
Against the branch base the work adds 7,223 lines of main code and 8,960 lines of tests, and removes 239 and 1,234; `mvn verify` passes with 3,224 tests on JDK 21, and the tests pass on the JDK 27 runtime.

## What was ported

| Task | What | Main lines | Test lines |
| --- | --- | ---: | ---: |
| 1 | `SpecMap`: every conversion of C's `specmap.c`, its `MapMerge`, rate and dump, 1-D and 3-D | 1,106 | 769 |
| 2 | `SpecFrame` attributes, defaults, the Unit used with each System, Label, Symbol, Title, `Overlay`, dump and loader | 2,059 | 1,193 |
| 3 | `SpecFrame` conversions (`Match`, `SubFrame`, `MakeSpecMapping`), SpecOrigin and AlignSpecOffset | 347 | 1,250 |
| 4 | `DSBSpecFrame`, with the cast of a DSBSpecFrame template into a SpecFrame | 851 | 1,079 |
| 5 | `FluxFrame`, with `MakeFluxMapping` | 913 | 1,250 |
| 6 | `SpecFluxFrame`, as far as C goes without RateMap, MathMap and TranMap | 348 | 922 |
| 7 | Reading spectral axes (`FitsWcsSpectral`, C's `WcsSpectral` and its helpers) | 542 | 1,195 |
| 8 | Writing spectral axes (`FitsSpectralAxes`, C's `SpectralAxes`; the SpecFrame parts of `MakeFitsFrameSet` and `AddEncodingFrame`) | 635 | 1,024 |
| 9 | FITS-CLASS: C's `ClassTrans` on reading and the whole of `CLASSFromStore` on writing (`FitsEncClass`) | 430 | 541 |
| 10 | The remaining Rust tests, and a guard on `CLASSFromStore`'s array over-run | 9 | 65 |
| 11 | Sweep of the deferred lists: nothing left to change | 0 | 0 |
| 12 | This report | | |

Lines are those each task's commits add, the controller's fix commits included; summed, they exceed the branch totals by the lines a later task changed again.
Each of Tasks 1 to 10 was done by one implementer subagent, reviewed by a second, and verified in the controlling session before it was recorded (the full test suite, the deferred lists changing only by removal or by a more specific class reason, and the C source for each finding).
The reviews found no Critical problem and one Important one (Task 3: the velocity chains of `MakeSpecMapping` lacked a final `else` throwing `AST__INTER`); it and the minor findings taken up were fixed by the controller.
Expected values that no fixture or oracle held were taken from probes run against the pinned C library; the probe sources were kept with the execution record.
The probes behind the oracles converted in Tasks 3 to 6 were rebuilt against the pinned library and gave the committed values.
The tasks were committed between 08:29 and 13:06 on 16 November.

## Departures from the plan

The [rulings](2026-11-16-phase4-rulings.md) give each with its reason and cost; these change what later work builds on.

- **A Unit of another kind leaves the System alone.**
  Review Focus 2 says that setting a SpecFrame's Unit to a unit of another kind changes its System; C's SpecFrame `SetUnit` records the Unit for the current System and never changes the System, so the tests follow C.
  (A FluxFrame with no System set does take its System from its Unit, as C's `GetSystem` does.)
- **SpecFluxFrame needs RateMap, for nearly every conversion.**
  The plan has `MakeMapF` and `MakeMapI` throw `needs MathMap`; both build a RateMap before the MathMap, so they throw `needs RateMap`, the first absent class C builds.
  The plan expected unit changes to convert without them, but pinned C's `MakeSFMapping` short-circuits only when both components are equal, and every other conversion whose standardised SpecFrames convert (Jy to mJy, GHz to MHz, FREQ to WAVE) reaches `MakeMapF`.
  What works is what works in C without those classes: equal or swapped components, picking axes, FrameSet searches through CmpFrame's `Match`, and dumps.
  The part of `MakeSFMapping` after `MakeMap3` cannot be reached and is not ported.
- **Upstream fixes already in the pin.**
  The plan treated the FluxFrame `SFCBRW` name and the default reference value of `SpectralAxes` as fixed after the pin, to be recorded and not followed, and the Rust port's quirk file marks FITS-CLASS's LONPOLE of ±180° and its spectral CDELT scaling the same way.
  The pinned C has all four, under other commit names where the Rust file's are not in its history: `3b1f3f5a` (Rust's `b4457da9`), `0dfd0f74` (`e09bc221`), `a66a0cb5` (`aea3090b`) and `17416d33`; so do the AlignSpecOffset fix of `MakeSpecMapping` (`58ba2441`, Rust's `65bd5b6e`) and two `IsMapLinear` and `AddVersion` fixes (`d2c047eb`, `12e9ed4a`).
  C, Rust and Java agree on each, and `docs/rust-c-differences.md` says so.
- **`AddEncodingFrame` came with the spectral write.**
  Task 8 ported it with `MakeFitsFrameSet`'s SpecFrame parts rather than leaving it to Task 9, since without it a FITS-CLASS write fails `CLASSFromStore`'s SPECSYS check where C writes.
- **The grism write builds nothing.**
  Reading a `-GRI` or `-GRA` axis throws `needs GrismMap`, as planned, but C's write extracts a GrismMap already in the FrameSet and builds none; a FrameSet of this library's classes holds none, so the write makes C's System and Unit trials through the FrameSet and falls through to the next algorithm.
- **The SpecMap correction factor is part of the dump.**
  C's 1-D SpecMap works out its frequency correction factor when it first transforms, stores it among the step's arguments and dumps it, so `SimplifyRegressionTest` now takes its dumps before it transforms anything.

## Gates

| Gate | Before | After |
| --- | --- | --- |
| `wcsconv/cases.txt` rows giving C's cards | 118 of 194 | 188 of 194 |
| `wcsconv` rows by encoding (matching / total) | NATIVE 66/107, FITS-WCS 17/26, FITS-AIPS 11/17, FITS-AIPS++ 12/18, FITS-IRAF 6/9, FITS-PC 4/5, DSS 1/1, AST 1/2, FITS-CLASS 0/9 | NATIVE 103/107, FITS-WCS 24/26, FITS-AIPS 17/17, FITS-AIPS++ 18/18, FITS-IRAF 9/9, FITS-PC 5/5, DSS 1/1, AST 2/2, FITS-CLASS 9/9 |
| `wcsconv.txt` entries | 76: SpecFrame 63, FITS-CLASS 6, DSBSpecFrame 4, MathMap 2, SplineMap 1 | 6: MathMap 3 (`freq-log_native`, `polco_native`, `polco2_native`), GrismMap 2 (`grism_native`, `grism_fits-wcs`), SplineMap 1 (`lsst2_fits-wcs`) |
| `headers.txt` entries (transform oracle) | 51: SpecFrame 45, FITS-CLASS 4, MathMap 2 | 4: MathMap 3 (`freq-log`, `polco`, `polco2`), GrismMap 1 (`grism`) |
| `framesets.txt` entries | 69 | 10 |
| `full-dumps.txt` entries | 476 | 365 |
| `read-warnings.txt` entries | 495 | 384 |
| `serialisation.txt` entries | 490 | 383 |
| `simplify-cases.txt` entries | 288 | 255 |
| `simplify.txt` entries | 407 | 356 |
| `rust-tests.txt` entries | 376 | 336, after adding 11: seven `astEqual` tests of SpecMap, SpecFrame and FluxFrame, three of a Rust-only DSBSpecFrame function, one Rust unit test that builds a GrismMap |
| `FitsChanCursorOracleTest` cases run | 247 | 257 |
| `FitsChanErrorsTest` cases run | 27 | 34 |
| `FitsChanEncodingsOracleTest` cases excluded | 9 | 0 |
| Tests | 2,829 | 3,224 |

No deferred entry names a class this phase adds or the FITS-CLASS encoding, and rewriting the lists with `-Dast.writeDeferred=true` changes nothing.
New C-probe oracle suites, converted from the Rust port's `cases.rs` with `CasesToJson`: `SpecMap3dOracleTest`, `SpecConvertOracleTest` (208 cases), `SpecOriginSorOracleTest` (16), `DsbTopoOracleTest`, `FluxBadunOracleTest`, `FluxUnitSystemOracleTest`, `SpecFluxCmpOracleTest`, `SpecFluxPrimaryOracleTest` and `FitsChanSpecUnitOracleTest` (two cases need MathMap, listed and checked both ways).
Cases of earlier oracles that waited for these classes now run: 646 values and 46 dumps of `ClassAttrsTest` (the dumps byte for byte, and read back), 1,280 of `FrameClassAttrsTest`, 1,104 of `FrameAxisAttrsTest`, and `MatchAxesTest` 36 to 144, `OverlayTest` 6 to 13, `FrameSetIntegrityTest` 13 to 21, the conversions of `SpecUseDefsTest` 4 to 28.

The Review Focus tests:

1. `SpecFrameTest` converts with each attribute a standard of rest needs left unset (RefRA, RefDec, Epoch, the observer, SourceVel, StdOfRest, RestFreq): with UseDefs set, 11 cases give C's values; with it cleared, 12 give `AST__NOVAL` with C's message.
2. `SpecFrameTest` sets Units of another kind as C's `SetUnit` takes them, and checks C's `AST__BADUN` errors for a SpecOrigin that cannot be expressed in them.
3. `SpecFrameTest` dumps three SpecFrames before and after conversions and `findFrame` both ways, failing ones included, and after FrameSet searches; two threads converting their own copies 50 times get the single-thread Mapping.
4. Each class's Channel dump is compared with C's byte for byte (the `class_attrs` dumps, `serialisation/specframe.ast`, `jan-dsb.ast`, `specfluxframe_swapped.native`) and read back; NATIVE FitsChan cards of SpecFrames and DSBSpecFrames are compared with C's through the `wcsconv` NATIVE rows of the spectral headers.
5. `FitsWcsSpectralTest` reads `-LOG`, `-GRI`, `-GRA` and `-TAB` axes and gets `needs MathMap`, `needs GrismMap` and `needs LutMap`; `FitsChanWriteTest` writes a spectral cube in FITS-WCS, FITS-PC, FITS-IRAF, FITS-AIPS and FITS-AIPS++ and reads it back to C's values, and round-trips every in-scope spectral and FITS-CLASS header, expecting C's outcome where C's own round trip fails or differs.

## Tolerances and excuses

Removed: none.
Added:

| Where | What | Measured size and cause |
| --- | --- | --- |
| `SpecConvertOracleTest`, `SpecOriginSorOracleTest`, `DsbTopoOracleTest`; the probe values of `SpecFrameTest` and `DSBSpecFrameTest` | Within libm rounding (`CValues.cValueEq`: 1e-13 relative plus 1e-14), the rule the library has used for C values since phase 1 | Not measured per value; the 3-D SpecMap oracle, the SpecFrame unit-mapper cases and the flux and SpecFluxFrame oracles are compared exactly and match bit for bit |
| `SpecMapTest` correction factor | The factor C dumps in fixture `cap_specmap_06.map`, within libm rounding | Computed through PAL's velocity routines with runtime Math; not traced further |
| `SpecMapTest` translated Rust tests | Round trips and textbook formulae within the Rust tests' 1e-10 to 1e-12 relative | These are the Rust tests' own property checks, not C values; C's values for the same conversions are checked by the oracle suites |
| `SpecFrameTest` testspecframe.c errors | testspecframe.c's own tolerances (Error 13 within 1e-6), with C's value within libm rounding where the probe gives one | C's test |
| `FitsChanWcsTest` rows of `class-noproj`, `crota-3d`, `axes-swap` | The Rust tests' tolerances (1e-12 relative; for `class-noproj` 1e-3 Hz and 1e-9 rad forward, 1e-6 pixels inverse) | The same headers pass the transform-oracle gate |
| `FitsSpectralAxesTest` galactic reference position | CDELT2 within 1e-16 and PC within 1e-12 absolute; VELOSYS, CRVAL and the spectral cards exact | Up to 7e-17 and 6e-13: the same differences as C and Java give writing the same celestial axes without the spectral axis (C probe), from `FitLine` over SphMap and SlaMap transforms whose sines and arctangents are 1 ULP from libm, phase 3's cause |
| `FitsChanWriteTest.spectralCubeRoundTripsInEachEncoding` | Read-back values within 4 ULP of C's, as the `wcsconv` gate | Runtime Math; not traced per value |
| `FitsChanEncodingsOracleTest` FITS-CLASS `source_zsource`, `topocent` | ELEVATIO within the existing 4-ULP card gate | 1 ULP: the reference pixel's declination is 1 ULP from C's through `Math.atan2`; the AZEL conversion from it is bit-identical (traced with a C probe and Java on the same chain) |
| `FitsChanCInlineTest.azelWritesAsFitsWcs`, `FitsChanEncodingsOracleTest.writesMatchC` | Run on a fresh thread (`testsupport/FreshThread`) | C's table of local apparent sidereal times, ported per thread, is filled by earlier conversions with an observer, which reading spectral headers now makes; on the test runner's thread CRVAL moved by about 1e-6° and ELEVATIO by 14 ULP, where C's probes start with empty tables |

## Defects found in the C library

All are in the pinned library (`3d3f255f`); "probe" means a probe against the pinned library showed it, "reading" that it follows from the code.
Each is recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md) with what Java does.
Java gives a defined result in every case: it writes the whole text where C overruns a buffer, uses 0 where C reads a value it never set, throws `AstException` with `AST__INTER` where C writes past an array, and makes no FrameSet where C dereferences the null one.

| Where | Defect | Confirmed |
| --- | --- | --- |
| `fluxframe.c` `Dump` | Formats "Preferred units for" and the System's label into `comm[50]`; for SFCBRW that is 55 characters, a stack-buffer overflow | Probe (AddressSanitizer); still present at the tip of PR #104 (`1be1c664`) |
| `specfluxframe.c` `GetTitle` | Formats "<flux Label> versus <spectral Label>" into a static 101-character buffer, so Labels longer than 92 characters between them overrun it | Reading |
| `ClassTrans` | Computes the source velocity from `crval` when the spectral CRVAL was never read (one-axis headers such as `class-spec.head`) | Reading; C's recorded `wcsconv` outputs encode 0 |
| `CLASSFromStore` | Scales `cdelt[axspec]` past its array when the spectral axis is not among the axes with a CRPIX (latent: every axis of a FrameSet written has one) | Reading |
| `CLASSFromStore` | Reads the `crval` elements of axes its CRVAL loop did not reach, for the velocity, AZIMUTH and ELEVATIO cards (latent: the write has already failed) | Reading |
| `MakeFitsFrameSet`, `AddEncodingFrame` | A world Frame holding two SpecFrames, or a SpecFrame whose reference position gives no celestial FrameSet, makes no FrameSet, and the null FrameSet is then dereferenced (phase 3 found the two-SkyFrame route) | Probe (crash in `astGetFrame_`); fixed in PR #104 by `5a3e23e3`, where the probe's write returns 0 |

`SpectralAxes` shares `OtherAxes`'s use of a FitsTable that was never made, for a `-TAB` Mapping without table columns (phase 3); Java throws `needs FitsTable` there as it does for `OtherAxes`, though the entry in `docs/rust-c-differences.md` names only `OtherAxes`.

Kept as C has them, since they are defined: SpecMap's axis-count message saying "1 or 2"; an overflow in six SpecMap conversions giving an infinity where the others give a bad value; SpecFrame's `Overlay` onto another class clearing the Title, Label, Symbol and, usually, the Unit with the System, and its `SubFrame` clearing the System after setting them; an aligned DSBSpecFrame conversion leaving the target's SideBand set; any setting containing `dsbcentre=` taken for DSBCentre; `GetLO`'s message always giving a DSBCentre of 3e+10 Hz and naming `astGetImagFreq`; FluxFrame's `Overlay` changing the template it is given; the Unit a FluxFrame records keeping its trailing spaces; the `-GLS` code of `ClassTrans` not seen by the later GLS translation.

## Final review

Pending: a fresh reviewer reads the whole branch after this report is committed, and its findings and the fix pass will be recorded here and in the rulings.

## Gaps and risks

- **SpecFluxFrame converts only equal components.**
  Every other SpecFluxFrame conversion, and the integrity of a FrameSet whose current Frame is a SpecFluxFrame after its Unit or System changes, throws `needs RateMap` where C converts; SPLAT's flux-axis conversions wait for RateMap, MathMap and TranMap, and the rest of `MakeSFMapping` must then be ported from C.
  Review Focus 3's two-thread check covers SpecFrame only.
- **Spectral axes that need absent Mappings.**
  A `-LOG` spectral axis with a non-zero CRVAL needs MathMap to read and to write, as do logarithmic units (`log(Hz)`, `log(Jy)`); a `-GRI` or `-GRA` axis needs GrismMap to read, and C's `ExtractGrismMap` is to be ported with it; a `-TAB` axis needs LutMap to read with TabOK set and FitsTable to write.
  These, with the MathMap of `polco` and the SplineMap of `lsst2`, are the six `wcsconv` rows and four headers still deferred.
- **No `astEqual`.**
  108 Rust tests wait for it, seven of them added this phase; SpecFluxFrame carries a private port of the comparison `MakeSFMapping` makes.
- **Order-dependent AZEL values.**
  C's per-observer table of local apparent sidereal times is ported as C has it, per thread, so AZEL results depend on what the thread converted before; reading spectral headers with an observer now fills it, and tests comparing AZEL values with C's run on a fresh thread.
- **Runtime-Math margins.**
  The galactic PC elements of `FitsSpectralAxesTest` differ by 6e-13 against a limit of 1e-12, a margin of under two; another JDK or libm could use it up.
- **FITS-CLASS headers that do not write back.**
  `class-noproj`, `class-spec` and `class-spec-hel` write nothing into a new FitsChan, as in C; why was not investigated.
- **Probe sources outside the repository.**
  The test Javadocs of Tasks 2 to 4 cite C probes kept in the git-ignored execution workspace (`.superpowers/sdd/.../task-N-probes`; `DSBSpecFrameTest` names `task-4-probes/dsb_cases.c`, `SpecFrameTest` `spec_convert_cases.c`), so those expected values cannot be regenerated from the repository; from Task 5 on the tests name the C function or fixture instead.
- **Runtimes.**
  The tests were run on JDK 21 and on the JDK 27 runtime, not on a Java 8 runtime.

## Rulings

Every decision made while executing the plan where it was wrong, unclear or silent, every departure from Rust or C and every C quirk kept is in [`2026-11-16-phase4-rulings.md`](2026-11-16-phase4-rulings.md), with its reason and its cost if wrong: 98 rulings, 92 by the implementers and 6 by the controller.
