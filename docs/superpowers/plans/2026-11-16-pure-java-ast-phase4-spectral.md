# Pure-Java AST Phase 4 (Spectral and Flux Frames) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SpecMap, SpecFrame, DSBSpecFrame, FluxFrame and SpecFluxFrame work as C's do, with jniast's public API, and FitsChan reads and writes spectral axes in every encoding, FITS-CLASS included.

**Architecture:** Port the Rust port's `mappings/specmap.rs`, `frames/{specframe,dsbspecframe,fluxframe,specfluxframe}.rs`, `fitschan/wcs_spectral.rs`, `fitschan/fset_to_store/spectral_axes.rs` and `fitschan/enc_class.rs` onto the phase 1 to 3 classes, with TimeFrame and TimeMap as the Java pattern for a Frame class and its Mapping.
Take from C (`reference/starlink-ast/src/{specmap,specframe,dsbspecframe,fluxframe,specfluxframe,fitschan}.c`) every part whose Rust structure follows from Rust's ownership rules (Shared Design).
The gates are the C test programs (`testspecmap.c`, `testspecframe.c`, `testflux.c`, `testspecflux.c`, through their Rust translations), the Rust repo's C-probe oracles, C's `wcsconv` rows and the deferred lists, each checked both ways.

**Tech Stack:** as phases 1 to 3: Java 8 (`--release 8`), Maven, JUnit 5, Jackson (test scope), Error Prone, Checkstyle, PMD, Spotless; JDK 21 to build.

**Spec:** `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md` ("Later phases", item 3).
The earlier plans' Shared Design sections and the rulings in `docs/reports/2026-11-1*-phase*-rulings.md` still apply where this plan does not change them.

## Global Constraints

- Every Global Constraint of the phase 1 plan applies unchanged (Java 8, no runtime dependencies, jniast signatures, C at `3d3f255f`, `default` and final `else` throw `AST__INTER`, `MODERN-JAVA` tags, no heuristics the original lacks, expected values from C only, share where C calls `astClone` and copy where C calls `astCopy`, British spelling, one sentence per line in Markdown).
- Public API: exactly jniast's constructors and methods for the five classes (`SpecFrame()`, `getRefPos(SkyFrame)`, `setRefPos(SkyFrame, double, double)`, the attribute getters and setters of `SpecFrame.java`, `DSBSpecFrame.java`, `FluxFrame.java`; `FluxFrame(double, SpecFrame)`, `FluxFrame()`; `SpecFluxFrame(SpecFrame, FluxFrame)`; `SpecMap(int, int)`, `specAdd(String, double[])`); every other attribute C has (SpecOrigin, AlignSpecOffset, ImagFreq, and so on) is reached through the generic `AstObject` attribute methods, as in jniast.
  Nothing else public is added.
- Before porting, read Rust `docs/porting-hazards.md` (H7 and H8 above all), `docs/audits/frame-family-c-parity.md`, `docs/audits/mapping-c-parity.md` (SpecMap), `docs/audits/fitschan-c-parity.md` (spectral and CLASS parts), and in `docs/issues/c-library-quirks.md` every entry naming SpecFrame, SpecMap, DSBSpecFrame, FluxFrame, SpecFluxFrame, SpectralAxes, WcsSpectral or CLASS (search by name).
  An entry marked RESOLVED upstream describes a fix made after the pin: follow the pinned C, and record the difference in `docs/rust-c-differences.md`.
- Rust comments cite older C line numbers; find each C function by name in the pinned source.
- Classes still absent after this phase: GrismMap, MathMap, TranMap, LutMap, RateMap, FitsTable and the rest of spec item 4.
  Each place C or Rust reaches one throws `UnsupportedOperationException` whose message ends `needs <Class>`, naming the first absent class C would build (the convention `Deferred.unavailableReason` reads); where C finds no such object in a FrameSet of this library's classes, port C's behaviour.
- Spectral values must match C to the oracle tolerances; when one does not, find the C function that computes it before touching the arithmetic (H7).

## Review Focus

1. Converting between SpecFrames whose standards of rest need a source position, epoch or observer (RefRA/RefDec unset, Epoch unset, ObsLon/ObsLat unset) gives C's result or C's error, never a silently wrong velocity (test in Task 3).
2. Setting Unit on a SpecFrame axis to a unit of another kind (Angstrom on a frequency axis, km/s on a wavelength axis) changes System as C's ActiveUnit rules do, and an impossible unit gives C's error (test in Task 2).
3. `astConvert`, `findFrame` and FrameSet integrity never change the attributes of the Frames given, and two threads converting copies of one SpecFrame or SpecFluxFrame get identical Mappings (test in Task 3).
4. Every new class survives a NATIVE dump and read, through Channel and FitsChan, with each attribute set and unset, giving C's cards (test in Task 2, extended in Tasks 4 to 6).
5. A spectral FITS header read and written back in each encoding that accepts it gives a FrameSet that transforms as the original, and a header whose spectral axis needs an absent class (`-GRI`, `-LOG` through MathMap, `-TAB`) fails with `needs <Class>`, never another exception (test in Task 7, extended in Tasks 8 and 9).

---

## Shared Design

### Where the code goes

- One public class per AST class: `SpecMap` (extends `Mapping`), `SpecFrame` (extends `Frame`), `DSBSpecFrame` (extends `SpecFrame`), `FluxFrame` (extends `Frame`), `SpecFluxFrame` (extends `CmpFrame`), each built as `TimeFrame` and `TimeMap` are: attribute values, `getAttrib`/`setAttrib`/`testAttrib`/`clearAttrib`, `systemCode`/`systemString`/`validateSystem`, `match`/`subFrame`/`overlay`, `dump`/`dumpComment`, and a loader in `ObjectRegistry`.
- Package-private helpers beside them when a class passes about 2,500 lines, named for the Rust module they port.
- FITS code follows the phase 3 naming: `FitsWcsSpectral` (`wcs_spectral.rs`, C `WcsSpectral` and its helpers), `FitsSpectralAxes` (`fset_to_store/spectral_axes.rs`, C `SpectralAxes`), `FitsEncClass` (`enc_class.rs`, C `ClassTrans` and `CLASSFromStore`, replacing the phase 3 refusal).
- PAL's velocity routines (`rvgalc`, `rvlg`, `rvlsrd`, `rvlsrk`, `evp`, `geoc`, `pvobs`) are already in `internal.pal`.

### Taken from C rather than Rust

1. C's `Match` and `SubFrame` work on copies of the template where C copies, and on the caller's Frame where C does; Rust's extra clones for borrowing are not reproduced (phase 2 ruling: Java objects are shared references as C's are).
2. SpecFluxFrame is a CmpFrame of the two Frames given, shared where C clones them, as C's `astSpecFluxFrame` builds it.
3. SpecMap's conversion steps are kept in C's list form (`cvttype` and `cvtargs` per step) so `MapMerge` and the native dump follow C's merge and card order.
4. The FitsChan spectral reader and writer receive the FitsChan, store and FrameSet as C's functions do, as phase 3's writers do.

### Gates and deferred lists

- The Rust test files `test_specmap.rs`, `test_specframe.rs`, `test_dsbspecframe.rs`, `test_fluxframe.rs`, `test_flux.rs`, `test_specflux.rs` and their siblings are not in `deferred/rust-tests.txt`, since phase 1 never listed them; each task translates its files and adds to `rust-tests.txt` only the tests that still need an absent class, with that reason.
- C-probe oracles: `class_attrs`, `frame_class` and `spec_use_defs` are converted already (their deferred cases come back as each class arrives); `specmap_3d`, `spec_convert`, `spec_origin_sor`, `dsb_topo`, `flux_badun`, `flux_unit_system`, `specflux_cmp`, `specflux_primary` and `fitschan_spec_unit` are converted by the task that needs them, from `tests/<name>_oracle/cases.rs` with the existing `CasesToJson`, as phases 1 and 3 did.
- `WcsconvTest`, `TransformOracleTest` (`headers.txt`), `SerialisationCorpusTest` (`serialisation.txt`, `full-dumps.txt`), `framesets.txt`, `simplify.txt`, `simplify-cases.txt` and `read-warnings.txt`: an entry whose only missing class arrives in a task passes in that task, or carries the next absent class as its reason; the lists stay stable on a rewrite (`-Dast.writeDeferred=true` makes no change).

---

### Task 1: SpecMap

**Files:**
- Create: `SpecMap.java` (Rust `mappings/specmap.rs`; C `specmap.c`)
- Modify: `ObjectRegistry.java`, the simplifier's merge dispatch for C's `SpecMap` `MapMerge`
- Test: `SpecMapTest` (translates `test_specmap.rs`), `SpecMap3dOracleTest` (new, `specmap_3d_oracle`), the deferred-list gates

**Interfaces:**
- Produces: `public SpecMap(int nin, int flags)`, `public void specAdd(String cvt, double[] args)`; package-private access to the step list for `MapMerge` and dumps.

- [ ] **Step 1: Write the failing tests:** the 36 tests of `test_specmap.rs` and the 4 of `test_specmap_3d.rs` (from C's `testspecmap.c` and the oracle), the native round trip of a SpecMap with every conversion type, and the simplification cases in `deferred/simplify*.txt` whose only reason is `needs SpecMap`.
- [ ] **Step 2: Run** `mvn test -Dtest='SpecMapTest,SpecMap3dOracleTest'` — Expected: FAIL (no SpecMap).
- [ ] **Step 3: Implement** every conversion C's `specmap.c` has (frequency, energy, wavenumber, wavelength, air wavelength, the velocity definitions, and the rest-frame shifts through PAL), its argument checks and errors, its `MapMerge` (merging and cancelling adjacent steps as C's `MergeSpecMaps` does), and its dump.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS; deferred lists rewritten with only `needs SpecMap` entries gone.
- [ ] **Step 5: Commit** `Port SpecMap`.

### Task 2: SpecFrame attributes, formatting and dumps

**Files:**
- Create: `SpecFrame.java` (Rust `frames/specframe.rs`; C `specframe.c`)
- Modify: `ObjectRegistry.java`, `NativeStream.java` if needed
- Test: `SpecFrameTest` (the attribute, label, unit and dump tests of `test_specframe.rs` and the unit tests of `specframe.rs`), `ClassAttrsTest`, `FrameClassAttrsTest`, `SpecUseDefsOracleTest` (existing deferred cases)

**Interfaces:**
- Produces: the jniast SpecFrame API; System values FREQ, ENER, WAVN, WAVE, AWAV, VRAD, VOPT, ZOPT, BETA, VELO and their default units; StdOfRest, AlignStdOfRest, RestFreq, SourceVel, SourceVRF, SourceSys, RefRA, RefDec, SpecOrigin, AlignSpecOffset, GeoLat and GeoLon as C has them; package-private `int stdOfRestValue()` and similar accessors Tasks 3, 7 and 8 use.

- [ ] **Step 1: Write the failing tests:** every `test_specframe.rs` test that needs no conversion between SpecFrames (attributes, defaults, ActiveUnit and Unit changes, Label and Symbol, Title, Format and Unformat, `getRefPos` and `setRefPos`, Errors 1 to 12 of `testspecframe.c` that are attribute errors), the SpecFrame cases of `class_attrs`, `frame_class` and `spec_use_defs`, and a native dump and read with every attribute set and unset (Review Focus 2 and 4).
- [ ] **Step 2: Run** `mvn test -Dtest='SpecFrameTest,ClassAttrsTest,FrameClassAttrsTest,SpecUseDefsOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** the attributes, their defaults, the ActiveUnit behaviour, formatting, `overlay` and dump.
  `match` and `subFrame` are Task 3's: until then they keep Frame's behaviour, and no Task 2 test converts between SpecFrames.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port SpecFrame attributes and dumps`.

### Task 3: SpecFrame conversions

**Files:**
- Modify: `SpecFrame.java` (C `MakeSpecMapping`, `Match`, `SubFrame`, `Overlay`, `GetRefPos`/`SetRefPos`, `TopoVel`, `SorConvert`, `OriginStdOfRest`, `OriginSystem`)
- Test: `SpecFrameTest` (the conversion tests of `test_specframe.rs`), `SpecConvertOracleTest` (new, `spec_convert_oracle`), `SpecOriginSorOracleTest` (new, `spec_origin_sor_oracle`), `FrameSetTest`/`FindFrameTest` additions, the deferred-list gates

**Interfaces:**
- Consumes: Task 1's SpecMap, Task 2's attribute accessors.
- Produces: `Frame.convert` and `findFrame` between SpecFrames, CmpFrames holding them, and FrameSets; SpecOrigin and AlignSpecOffset behaviour.

- [ ] **Step 1: Write the failing tests:** the remaining `test_specframe.rs` tests, `test_spec_convert.rs`, `test_spec_origin_sor.rs`, every conversion check of `testspecframe.c`, the Review Focus 1 cases (each unset attribute, C's result or error), and Review Focus 3 (a conversion leaves both Frames' attributes as they were; two threads get identical Mappings).
- [ ] **Step 2: Run** `mvn test -Dtest='SpecFrameTest,SpecConvertOracleTest,SpecOriginSorOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** C's conversion path in C's order: unit change, system change through SpecMap, standard of rest through the reference position, epoch and observer, SpecOrigin and AlignSpecOffset; the alignment rules of `Match` (AlignSystem, AlignStdOfRest, AlignSpecOffset).
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS; `needs SpecFrame` entries with no other reason pass or carry the next one.
- [ ] **Step 5: Commit** `Convert between SpecFrames as C does`.

### Task 4: DSBSpecFrame

**Files:**
- Create: `DSBSpecFrame.java` (Rust `frames/dsbspecframe.rs`; C `dsbspecframe.c`)
- Modify: `ObjectRegistry.java`
- Test: `DSBSpecFrameTest` (translates `test_dsbspecframe.rs` and the unit tests), `DsbTopoOracleTest` (new, `dsb_topo_oracle`), the deferred-list gates

**Interfaces:**
- Consumes: Tasks 2 and 3.
- Produces: the jniast DSBSpecFrame API; SideBand, DSBCentre, IF, AlignSideBand and ImagFreq as C has them; the sideband conversions.

- [ ] **Step 1: Write the failing tests:** the 33 tests of `test_dsbspecframe.rs`, `test_dsb_topo.rs`, the DSBSpecFrame cases of the oracles already converted, and a dump round trip.
- [ ] **Step 2: Run** `mvn test -Dtest='DSBSpecFrameTest,DsbTopoOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** as C, including the topocentric conversion of DSBCentre.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port DSBSpecFrame`.

### Task 5: FluxFrame

**Files:**
- Create: `FluxFrame.java` (Rust `frames/fluxframe.rs`; C `fluxframe.c`)
- Modify: `ObjectRegistry.java`
- Test: `FluxFrameTest` (translates `test_fluxframe.rs`, `test_flux.rs` from `testflux.c`, and the unit tests), `FluxBadunOracleTest` and `FluxUnitSystemOracleTest` (new), the deferred-list gates

**Interfaces:**
- Consumes: Tasks 2 and 3 (the SpecFrame a FluxFrame holds).
- Produces: the jniast FluxFrame API; System values FLXDN, FLXDNW, SFCBR, SFCBRW and the unit-driven System choice; conversions between FluxFrames.

- [ ] **Step 1: Write the failing tests:** the 46 tests of `test_fluxframe.rs`, the 9 of `test_flux.rs`, `test_flux_badun.rs`, the two oracles, and a dump round trip.
- [ ] **Step 2: Run** `mvn test -Dtest='FluxFrameTest,FluxBadunOracleTest,FluxUnitSystemOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** as C, at the pinned C's `SFCBRW` read and write behaviour (a quirk resolved upstream; record it in `docs/rust-c-differences.md`).
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port FluxFrame`.

### Task 6: SpecFluxFrame

**Files:**
- Create: `SpecFluxFrame.java` (Rust `frames/specfluxframe.rs`; C `specfluxframe.c`)
- Modify: `ObjectRegistry.java`
- Test: `SpecFluxFrameTest` (translates `test_specflux.rs` from `testspecflux.c` and the unit tests), `SpecFluxCmpOracleTest` and `SpecFluxPrimaryOracleTest` (new), the deferred-list gates

**Interfaces:**
- Consumes: Tasks 2, 3 and 5.
- Produces: the jniast SpecFluxFrame API; conversions between SpecFluxFrames.

- [ ] **Step 1: Write the failing tests:** `test_specflux.rs`, `test_specflux_cmp.rs`, `test_specflux_primary.rs`, the unit tests, and a dump round trip; a conversion that needs C's flux-scaling MathMap and TranMap expects `needs MathMap`.
- [ ] **Step 2: Run** `mvn test -Dtest='SpecFluxFrameTest,SpecFluxCmpOracleTest,SpecFluxPrimaryOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** as C; `MakeMapF`/`MakeMapI` throw `needs MathMap`, the first absent class C builds there.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port SpecFluxFrame`.

### Task 7: Reading spectral axes from FITS

**Files:**
- Create: `FitsWcsSpectral.java` (Rust `fitschan/wcs_spectral.rs`; C `WcsSpectral`, `SpecTrans`'s spectral parts already ported, `IsAIPSSpectral`, the DSB keywords)
- Modify: `FitsWcsRead.java` (`buildSpectral` and the spectral branches that throw `needs SpecFrame`), the AIPS, AIPS++, PC and IRAF read paths where they throw `needs SpecFrame`
- Test: `FitsWcsSpectralTest` (the unit tests), `FitsChanSpecUnitOracleTest` (new, `fitschan_spec_unit_oracle`), `TransformOracleTest`, `WcsconvTest`, `ReadWarningsTest` or its equivalent, the `rust-tests.txt` read entries

**Interfaces:**
- Consumes: Tasks 1 to 4.
- Produces: FitsChan reads of FREQ, ENER, WAVN, WAVE, AWAV, VRAD, VOPT, ZOPT, VELO and BETA axes with the `-F2W`-style non-linear algorithms C supports without absent classes, SPECSYS, SSYSOBS, SSYSSRC, VELOSYS, ZSOURCE, RESTFRQ, RESTWAV and IMAGFREQ.

- [ ] **Step 1: Write the failing tests:** the unit tests of `wcs_spectral.rs`, `test_fitschan_spec_unit_oracle.rs`, `test_dsbspecframe_read.rs`, the `rust-tests.txt` read entries whose reason is `needs SpecFrame` or `needs DSBSpecFrame`, and Review Focus 5's read half (`-GRI`, `-LOG` and `-TAB` spectral axes throw `needs GrismMap`, `needs MathMap` and `needs LutMap` or `needs FitsTable` as C would build them).
- [ ] **Step 2: Run** `mvn test -Dtest='FitsWcsSpectralTest,FitsChanSpecUnitOracleTest,TransformOracleTest,WcsconvTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** C's `WcsSpectral` path, in C's order of keyword use and warnings.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS; `headers.txt`, `wcsconv.txt` and `read-warnings.txt` lose every entry whose only reason was a class this phase adds.
- [ ] **Step 5: Commit** `Read spectral axes from FITS headers`.

### Task 8: Writing spectral axes to FITS

**Files:**
- Create: `FitsSpectralAxes.java` (Rust `fitschan/fset_to_store/spectral_axes.rs`; C `SpectralAxes`)
- Modify: `FitsFsetToStore.java` (the spectral branch), `FitsWcsWrite.java`, `FitsEncPc.java`, `FitsEncIraf.java`, `FitsEncAips.java` (the spectral keywords of `AIPSFromStore` and `AIPSPPFromStore`), wherever phase 3 throws `needs SpecFrame` on the write side
- Test: `FitsSpectralAxesTest` (the unit tests), `FitsChanWcsWriteTest` and `FitsChanEncodingsOracleTest` cases now in reach, `WcsconvTest`, the `rust-tests.txt` write entries

**Interfaces:**
- Consumes: Tasks 1 to 4 and phase 3's writers.
- Produces: spectral axes written in FITS-WCS, FITS-PC, FITS-IRAF, FITS-AIPS and FITS-AIPS++ as C writes them; `-GRI`/`-GRA` (C's `grism` branch) throws `needs GrismMap`.

- [ ] **Step 1: Write the failing tests:** the unit tests of `spectral_axes.rs`, the write entries of `rust-tests.txt` and the oracle cases whose reason is a class this phase adds, `wcsconv` write rows for spectral inputs, and Review Focus 5's write half (each encoding's spectral header written and read back transforms as the original).
- [ ] **Step 2: Run** `mvn test -Dtest='FitsSpectralAxesTest,FitsChanWcsWriteTest,FitsChanEncodingsOracleTest,WcsconvTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** C's `SpectralAxes`, including its choice of algorithm, reference value and keywords, at the pinned C (the upstream fix to its default reference value is recorded, not followed).
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Write spectral axes to FITS headers`.

### Task 9: FITS-CLASS

**Files:**
- Modify: `FitsEncClass.java` (Rust `fitschan/enc_class.rs`; C `ClassTrans`, `CLASSFromStore`), `FitsSpecTrans.java` (the FITS-CLASS refusal becomes C's `ClassTrans`), `FitsChan.java` read dispatch
- Test: `FitsEncClassTest` (the unit tests), `FitsChanClassTest` (translates `test_fitschan_class.rs`), `WcsconvTest` CLASS rows, `TransformOracleTest`

**Interfaces:**
- Consumes: Tasks 1 to 8.
- Produces: FITS-CLASS read and write as C, with the pinned C's quirks (the `astTranN` three-coordinate call and the axis indices recorded in `docs/rust-c-differences.md` stay as C).

- [ ] **Step 1: Write the failing tests:** the unit tests of `enc_class.rs`, `test_fitschan_class.rs`, the 9 FITS-CLASS `wcsconv` rows and the FITS-CLASS headers.
- [ ] **Step 2: Run** `mvn test -Dtest='FitsEncClassTest,FitsChanClassTest,WcsconvTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** as C.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Read and write FITS-CLASS headers`.

### Task 10: The remaining Rust tests

**Files:**
- Test: translations of every remaining test in `rust-tests.txt` whose reason names a class this phase adds (`test_find_frame.rs`, `test_convert.rs`, `test_class_attrs.rs`, `test_fitschan_*`, `cmpframe.rs`, `test_frame_api.rs` and the others the list names), and of `test_spec_use_defs.rs`, `test_class_attrs.rs` and `test_frame_class_attrs.rs` cases now in reach

- [ ] **Step 1: Translate** each test, expected values from C as the Rust test records them.
- [ ] **Step 2: Run** `mvn -q test` — Expected: PASS, or a failure that is a port defect, fixed in the class it belongs to.
- [ ] **Step 3: Commit** `Translate the remaining spectral Rust tests`.

### Task 11: Sweep the deferred lists

- [ ] **Step 1: Run** `mvn -q test -Dast.writeDeferred=true` and diff every `deferred/*.txt` against the branch base: no entry names SpecMap, SpecFrame, DSBSpecFrame, FluxFrame or SpecFluxFrame as its reason; every entry that changed reason names a class still absent.
- [ ] **Step 2: Run** `mvn verify` on JDK 21 and `mvn test -Djvm=<JDK 27 java>` — Expected: PASS.
- [ ] **Step 3: Commit** any list changes as `Sweep the deferred lists after phase 4`.

### Task 12: The phase 4 report

- [ ] **Step 1: Write** `docs/reports/2026-11-<dd>-phase4-report.md` and `docs/reports/2026-11-<dd>-phase4-rulings.md` in the form of the phase 3 report: what was ported, departures from the plan, gates before and after, tolerances with their traced causes, C defects found, final review, gaps and risks.
- [ ] **Step 2: Commit** `Report phase 4`.
