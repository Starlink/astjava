# Pure-Java AST Phase 3 (FitsChan Writing and Encodings) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `FitsChan.write()` writes FrameSets of the classes this library has as FITS-WCS, NATIVE, FITS-PC, FITS-IRAF, FITS-AIPS, FITS-AIPS++ and DSS cards, and `FitsChan.read()` reads the FITS-PC, FITS-IRAF, FITS-AIPS, FITS-AIPS++ and DSS encodings, as C does.

**Architecture:** Port the Rust port's FitsChan write path (`crates/starlink-ast/src/fitschan/`: `wcs_write/`, `fset_to_store/`, `enc_*.rs`, `sip_int_world.rs`) onto the phase 1 read side (`FitsStore`, `FitsCardList`, `FitsSpecTrans`, `FitsWcsRead`), taking from C (`reference/starlink-ast/src/fitschan.c`) every part whose Rust structure follows from Rust's ownership rules (listed under Shared Design).
The FitsStore is built from a FrameSet (`FsetToStore`) and then turned into cards by one writer per encoding (`WcsFromStore`, `PCFromStore`, ...); the reads of the other encodings reuse the FITS-WCS read with their keyword translations.
C's `wcsconv/cases.txt` rows and the Rust port's C-probe oracles are the gates, checked both ways through the deferred lists.

**Tech Stack:** as phases 1 and 2: Java 8 (`--release 8`), Maven, JUnit 5, Jackson (test scope), Error Prone, Checkstyle, PMD, Spotless; JDK 21 to build.

**Spec:** `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md` ("Later phases", item 2).
The phase 1 plan's Shared Design and the phase 2 plan's rulings (`docs/reports/2026-11-13-phase2-rulings.md`) still apply where this plan does not change them.

## Global Constraints

- Every Global Constraint of the phase 1 plan applies unchanged (Java 8, no runtime dependencies, jniast signatures, C at `3d3f255f`, `default` and final `else` throw `AST__INTER`, `MODERN-JAVA` tags, no heuristics the original lacks, expected values from C only, share where C calls `astClone` and copy where C calls `astCopy`, British spelling, one sentence per line in Markdown).
- `FitsChan.write` keeps its jniast signature and return value; nothing else public is added.
- Before porting, read Rust `docs/porting-hazards.md` (H2, H5, H8, H11) and `docs/issues/c-library-quirks.md` entries 592, 1527, 1970, 2304, 2411-2575, 2916, 3280 and 3876, and the Rust repo's `docs/audits/fitschan-c-parity.md`.
- Rust comments cite an older `fitschan.c`; find each C function by name in the pinned `fitschan.c`, not by the line number a Rust comment gives.
- Branches needing a class outside this library (SpecFrame, DSBSpecFrame, SpecMap, GrismMap, MathMap, LutMap, FitsTable, TranMap) are not ported; each place C or Rust reaches one throws `UnsupportedOperationException` whose message ends `needs <Class>` (the convention `Deferred.unavailableReason` reads), except where C itself finds no such Mapping in a FrameSet of this library's classes, which is ported as C behaves (for example `SpectralAxes` finding no SpecFrame).
- Output must match C's cards, card order and comments: the `wcsconv` gate compares written cards with C's within 4 units in the last place.

## Review Focus

1. A write that fails (the encoding cannot describe the FrameSet, or Strict and a warning) returns C's value and leaves the FitsChan's cards, card order and current card exactly as before the call (C `FixNew`) (test in Task 6).
2. Writing a FrameSet and reading the cards back gives a FrameSet that transforms as the original, within the `headers.oracle` tolerance, for every in-scope header and every encoding that accepted it (test in Task 6, extended in Tasks 7 to 9).
3. A FrameSet that needs a class this library lacks (a non-linear axis that would need `-LOG`, a table that would need `-TAB` with TabOK set) makes `write` return C's failure value or throw `UnsupportedOperationException` naming the class, never another exception (test in Task 4).
4. Cards written into a FitsChan that already holds cards go where C puts them (before the card that was current when the write began) and the existing cards keep their order (test in Task 6).
5. Two threads writing copies of one FrameSet into their own FitsChans get identical cards (test in Task 6).

---

## Shared Design

### Where the code goes

- Java file names follow the Rust module they port, prefixed `Fits` as on the read side: `FitsWcsWrite` (`wcs_write/mod.rs`), `FitsPlaneStores` (`plane_stores.rs`), `FitsFsetToStore` (`fset_to_store/mod.rs`), `FitsCelestialAxes`, `FitsSkySys`, `FitsSkyPole`, `FitsOtherAxes`, `FitsIsMapLinear`, `FitsWorldAxes`, `FitsMakeIntWorld`, `FitsInvertable`, `FitsOrth`, `FitsWcsDecompose`, `FitsSipIntWorld`, `FitsNativeWriter` (the write half of `enc_native.rs`), `FitsEncPc`, `FitsEncIraf`, `FitsEncAips`, `FitsEncDss`, `FitsEncClass` (refusal only, Task 9); `AnalysePoly` and `ScalePolyInputs` go beside the Mapping helpers (`MappingHelpers` or their own package-private classes).
- The phase 1 `FitsStore` is the store; the writers fill the fields it already has.
- Every new class and method is package-private.

### Taken from C rather than Rust

Each of these is a place where the Rust code's structure follows from its ownership rules; Java follows C's structure (phase 2 ruling: Java objects are shared references as C's are):

1. Alternate descriptions: C's `AddVersion` works on the caller's FrameSet by base and current Frame index, not on a cloned FrameSet with its current Frame set.
2. The offset-sky absolute description: C clears SkyRefIs on the caller's FrameSet and restores it with `astSetC` (`AddVersion`), through FrameSet integrity, as Java's FrameSet already supports.
3. `MakeFitsFrameSet` builds a new two-Frame FrameSet from copies of the pixel Frame, the WCS Frame and the Mapping, as C does.
4. The writers receive the FitsChan and the FrameSet as C's do (`CLASSFromStore` gets `fs`; `GetCDMatrix` is called, not passed in), so CLASS's adjustments stay inside the CLASS writer.
5. Warnings are inserted at the cursor as C's `Warn` raises them.
6. Mappings are assembled by sharing components where C clones them, so `IsSimp` records fall as C's do.
7. The store is indexed by version letter as C's is, if that is what the `wcsconv` gate needs for C's card order (the SIP block is written inside C's version loop); otherwise the read side's layout is kept and the ledger records it.

The NATIVE write follows Rust's walk over a `NativeNode` tree, since the Java Channel already renders `NativeNode`s; C's overrides of the Channel `Write*` methods give the same cards.

### Gates and deferred lists

- `WcsconvTest` (C's `wcsconv/cases.txt`): a row written with an encoding this phase adds must match C's output; the reason `out-of-scope method FitsChan.write` disappears, rows needing an absent class keep `needs <Class>`, and FITS-CLASS rows keep their class reasons.
- `TransformOracleTest` with `deferred/headers.txt`: the FITS-IRAF, FITS-PC, FITS-AIPS and DSS headers that need no absent class read and transform as C.
- New C-probe oracle tests, each converted from the Rust repo's `tests/<name>_oracle/cases.rs` with the existing `CasesToJson` (as phase 1 did): `FitsChanEncodingsOracleTest`, `FitsChanSipokOracleTest`, `SkyPoleOracleTest` (already exists for read; extend), `FitsChanSplitmapOracleTest`, `FitsChanAltNaxesOracleTest`, `FitsChanAltEpochOracleTest`.
  Cases needing an absent class are listed in each test as phase 1's oracle tests list them, checked both ways.
- `deferred/rust-tests.txt` entries `out-of-scope method FitsChan.write` and the read entries for the encodings added here are translated by the task that makes them possible.

---

### Task 1: The write entry and the NATIVE encoding

**Files:**
- Create: `FitsNativeWriter.java`
- Modify: `FitsChan.java` (`write`, from Rust `mod.rs` `write`/`write_inner`/`write_attrs`/`store_keyword_native`; C `Write`, `FitsFromStore`, `FindWcs`, `CreateKeyword`, `MakeBanner`, `MakeIndentedComment`, `MakeIntoComment`, `PreQuote`)
- Test: `FitsChanNativeWriteTest` (new), `WcsconvTest`, `FitsChanTest`

**Interfaces:**
- Produces: `int FitsChan.write(AstObject)` dispatching on Encoding; for any encoding but NATIVE, a package-private hook `boolean writeForeign(FrameSet fs, FitsChan.Encoding enc)` that returns false (C's failure) until Task 6 fills it; the card-rollback scope (`FitsCardList.fixNew`) around every write.

- [ ] **Step 1: Write the failing tests:** the Rust `test_fitschan.rs` tests `native_round_trip_*`, `native_rewind_and_reread` (listed `out-of-scope method FitsChan.write` in `rust-tests.txt`); the 30 unit tests of `enc_native.rs` that exercise this library's classes; in `WcsconvTest`, every `native` row whose input this library reads must now match C's cards (the reason for such rows becomes the read's reason or nothing).
- [ ] **Step 2: Run** `mvn test -Dtest='FitsChanNativeWriteTest,FitsChanTest,WcsconvTest'` — Expected: FAIL.
- [ ] **Step 3: Implement.** The keyword sequence follows C's `CreateKeyword` with the FitsChan's persistent sequence counters and skips keywords already present (quirk 2575); a nested Object without comments gets the `" ="` card (H11).
- [ ] **Step 4: Rewrite `wcsconv.txt`** with `-Dast.writeDeferred=true`; read the diff: only `native` rows may leave the list, and none may be added.
- [ ] **Step 5: Run** the step 2 tests and the full suite — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Port FitsChan's write entry and the NATIVE encoding"`

### Task 2: Intermediate world coordinates and linear axes

**Files:**
- Create: `FitsMakeIntWorld.java` (`make_int_world.rs`; C `MakeIntWorld`, `NearestPix`), `FitsIsMapLinear.java` (C `IsMapLinear`), `FitsWorldAxes.java` (C `WorldAxes`), `FitsInvertable.java` (C `MakeInvertable`), `FitsOrth.java` (C `OrthVector`, `OrthVectorSet`), `FitsWcsDecompose.java` (C `FindBasisVectors`, `MakeBasisVectors`, `FitLine`, `FitOK`)
- Test: one test class per created class, translating that Rust module's unit tests

**Interfaces:**
- Produces: `static MatrixMap FitsMakeIntWorld.makeIntWorld(Mapping cmap, Frame fr, int[] wperm, char s, FitsStore store, double[] dim, FitsChan fc)` returning C's result (null where C returns NULL), and the helpers' static methods named after their C functions (`isMapLinear`, `worldAxes`, `makeInvertable`, `orthVector`, `orthVectorSet`, `findBasisVectors`, `fitLine`), whose signatures follow the C functions with Java arrays for C's pointer outputs.

- [ ] **Step 1: Write the failing tests:** each module's Rust unit tests, with their hand-derived expectations; `IsMapLinear` far from zero (quirk 2469).
- [ ] **Step 2: Run** them — Expected: FAIL.
- [ ] **Step 3: Implement.** Port `FindBasisVectors`/`FitLine` (hazard H5), not a fitting loop.
- [ ] **Step 4: Run** them and the full suite — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Port MakeIntWorld and the linear-axis helpers of FitsChan writing"`

### Task 3: Celestial axes

**Files:**
- Create: `FitsCelestialAxes.java` (C `CelestialAxes`, `SplitMap`, `SplitMap2`; not the `-TAB` branch), `FitsSkySys.java` (C `SkySys`, `IsSkyOff`, `TDBConv` through TimeFrame, hazard H8), `FitsSkyPole.java` (C `SkyPole`; reuses `FitsNativePole.wcsNatPole`)
- Modify: `FitsFiducial.java` (C `GetFiducialWCS`)
- Test: `FitsCelestialAxesTest`, `FitsSkySysTest`, `FitsSkyPoleTest` translating the Rust unit tests (29 for `sky_sys`)

**Interfaces:**
- Consumes: Task 2's `makeIntWorld`.
- Produces: `static boolean FitsCelestialAxes.celestialAxes(...)` with C's arguments and result; `FitsSkySys.skySys(...)`, `FitsSkyPole.skyPole(...)`, `FitsFiducial.getFiducialWcs(...)`.

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**; the `-TAB` branch throws `UnsupportedOperationException` ending `needs LutMap` when TabOK is set and C would take it.
- [ ] **Step 4: Run** them and the full suite — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Port the celestial axes of FitsChan writing"`

### Task 4: FrameSet to store

**Files:**
- Create: `FitsFsetToStore.java` (C `FsetToStore`, `AddVersion`, `MakeFitsFrameSet`, `FitsAxisOrder`, `FindLonLatSpecAxes`, CRPIX snapping and CRVAL rounding as Rust's `fset_to_store/mod.rs` has them), `FitsPlaneStores.java` (`plane_stores.rs`), `FitsOtherAxes.java` (C `OtherAxes`, linear branch)
- Test: `FitsFsetToStoreTest`, `FitsOtherAxesTest`, `FitsWriteUnsupportedTest`

**Interfaces:**
- Consumes: Tasks 2 and 3.
- Produces: `static boolean FitsFsetToStore.fsetToStore(FitsChan fc, FrameSet fs, int naxis, double[] dim, FitsChan.Encoding enc, String method, String cls)` filling the store C's `FsetToStore` fills; C's `SpectralAxes` path for a FrameSet with no SpecFrame (no spectral axes found).

- [ ] **Step 1: Write the failing tests:** the unit tests of `fset_to_store/mod.rs` and `other_axes.rs` that need no absent class; `FitsWriteUnsupportedTest.aNonLinearAxisNeedingLogIsUnsupported` (a FrameSet whose non-celestial axis is a non-linear PolyMap with a non-zero CRVAL: `write` throws `UnsupportedOperationException` ending `needs MathMap`, or returns C's failure value if C's `OtherAxes` fails before reaching `LogAxis`; read `OtherAxes` to decide and record it) and `aTableWithTabOkIsUnsupported` (TabOK = 1 and an axis C would describe with `-TAB`: ends `needs LutMap`).
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**, following "Taken from C rather than Rust" items 1 to 3, 6 and 7; C's warning-method order quirk (1527) is kept.
- [ ] **Step 4: Run** them and the full suite — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Port FsetToStore"`

### Task 5: The SIP building blocks

**Files:**
- Create: `FitsSipIntWorld.java` (C `SIPIntWorld`, with its nout fix as quirk 3280 records), `AnalysePoly.java` (C `AnalysePoly`), `ScalePolyInputs.java` (C `ScalePolyInputs`)
- Test: `FitsSipIntWorldTest`, `AnalysePolyTest`, `ScalePolyInputsTest`, translating the unit tests of `sip_int_world.rs`, `analyse_poly.rs` and `scale_poly_inputs.rs`

**Interfaces:**
- Consumes: Task 2's `makeIntWorld`.
- Produces: `static MatrixMap FitsSipIntWorld.sipIntWorld(...)` with C's arguments and result (null where C returns NULL), filling the store's SIP orders and coefficient arrays; `AnalysePoly.analysePoly(...)` and `ScalePolyInputs.scalePolyInputs(...)` with C's arguments.

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** them and the full suite — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Port SIPIntWorld, AnalysePoly and ScalePolyInputs"`

### Task 6: Writing FITS-WCS

**Files:**
- Create: `FitsWcsWrite.java` (`wcs_write/mod.rs`; C `WcsFromStore`, with the SIP block written where C writes it, inside the version loop; SipOK applies only to FITS-WCS, quirk 2558)
- Modify: `FitsChan.java` (`writeForeign` for FITS-WCS; C's `GetCDMatrix` saving and restoring the current card, quirk 2446)
- Test: `WcsconvTest`, new `FitsChanWriteTest`, new `FitsChanSipokOracleTest` (the 21 C-probe cases; the non-FITS-WCS encodings' cases are listed until Tasks 7 to 9), new `SkyPoleOracleTest` write cases, `FitsChanSplitmapOracleTest`, `FitsChanAltNaxesOracleTest`, `FitsChanAltEpochOracleTest`, `FitsChanEncodingsOracleTest` (FITS-WCS column)

**Interfaces:**
- Consumes: Task 4's `fsetToStore`; Task 5's `sipIntWorld`; Task 1's `writeForeign` hook.
- Produces: `static void FitsWcsWrite.wcsFromStore(FitsChan fc, FitsStore store, ...)`, the date helpers C uses (`mjd_to_date_str`).

- [ ] **Step 1: Write the failing tests:**
  - The C-probe oracle tests named above (convert each Rust `cases.rs` to JSON with `CasesToJson`), FITS-WCS cases only in `FitsChanEncodingsOracleTest` for now, cases needing an absent class listed and checked both ways.
  - In `FitsChanWriteTest`:
    - `aFailedWriteLeavesTheCardsAsTheyWere`: a FitsChan holding 5 cards with the 3rd current; writing a FrameSet FITS-WCS cannot describe (C: `write` returns 0); afterwards the same 5 cards in the same order, the 3rd current.
    - `strictWriteFailsAndLeavesTheCards`: as above with Strict set and a FrameSet that raises a warning.
    - `cardsAreWrittenBeforeTheCurrentCard`: a FitsChan with cards A, B, C, B current; after a successful write the new cards lie between A and B, and A, B, C keep their order.
    - `everyInScopeHeaderRoundTrips`: each `headers.oracle` FITS-WCS header the read side accepts, read, written FITS-WCS into a new FitsChan, read back: transforms its oracle inputs within the oracle tolerance.
    - `threadsWriteCopiesIdentically`: 4 threads write `copy()`s of the FrameSet read from `sip.head` into their own FitsChans; all card lists are identical.
  - In `WcsconvTest`, the `fits-wcs` rows in scope match C.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Rewrite `wcsconv.txt`** and read the diff (rows only leave).
- [ ] **Step 5: Run** the tests and the full suite — Expected: PASS.
- [ ] **Step 6: Commit** — `git commit -m "Write FrameSets as FITS-WCS"`

### Task 7: FITS-PC and FITS-IRAF

**Files:**
- Create: `FitsEncPc.java` (C `PCFromStore`; alternates as `C<n>xxx`), `FitsEncIraf.java` (C `IRAFFromStore`)
- Modify: `FitsSpecTrans.java` (the PC and IRAF translations: RADECSYS/LONGPOLE renaming, PROJP to PV, `Cnxxx`, the CDjjjiii branch; remove the refusal at its lines 166-176 for these two), `FitsChan.java` (`readForeign` and `writeForeign` for them)
- Test: `TransformOracleTest` (`headers.txt` IRAF and PC rows), `WcsconvTest` (`fits-pc`, `fits-iraf` rows and `native` rows whose input is a PC or IRAF header), `FitsChanEncodingsOracleTest` (PC and IRAF columns), the `rust-tests.txt` entries for reading these encodings, the PC and IRAF unit tests of `enc_pc.rs` and `enc_iraf.rs` (writers only; their `read_from_cards` is dead code and is not ported)

- [ ] **Step 1: Write the failing tests**; in `FitsChanWriteTest`, extend `everyInScopeHeaderRoundTrips` to these two encodings.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**, with the C fixes the Rust writers follow (quirks 2458, 2483).
- [ ] **Step 4: Rewrite `wcsconv.txt` and `headers.txt`**; run the tests and the full suite — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Read and write FITS-PC and FITS-IRAF"`

### Task 8: FITS-AIPS and FITS-AIPS++

**Files:**
- Create: `FitsEncAips.java` (C `AIPSFromStore`, `AIPSPPFromStore`)
- Modify: `FitsChan.java` (`readForeign` no longer refuses AIPS and AIPS++; `writeForeign` for them)
- Test: as Task 7 for these encodings (`headers.txt` AIPS rows cobe, CAR_model, cmap_3years; `fits-aips` and `fits-aips++` `wcsconv` rows; the `enc_aips.rs` unit tests that need no SpecFrame)

- [ ] **Step 1: Write the failing tests**; extend `everyInScopeHeaderRoundTrips` to these encodings.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**; spectral branches are reached only through a SpecFrame, which a FrameSet of this library cannot hold.
- [ ] **Step 4: Rewrite the lists**; run the tests and the full suite — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Read and write FITS-AIPS and FITS-AIPS++"`

### Task 9: DSS, and FITS-CLASS's refusal

**Files:**
- Create: `FitsEncDss.java` (C `DSSToStore`, `DSSFromStore`; a TPN WcsMap, no DssMap, Rust quirk note 409), `FitsEncClass.java` (C `CLASSFromStore` as far as its refusal of a FrameSet with no spectral axis)
- Modify: `FitsChan.java` (DSS read with its mark handling, DSS write; FITS-CLASS write)
- Test: `WcsconvTest` (`dss_ast_dss`, `dss_fits-dss_ast`), `headers.txt` DSS rows if any, `FitsChanEncodingsOracleTest` (DSS and CLASS columns: CLASS cases of FrameSets without a spectral axis expect C's refusal), `FitsChanSipokOracleTest` CLASS rows, the `enc_dss.rs` unit tests

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.** Reading FITS-CLASS stays unavailable: every CLASS header needs SpecFrame and DSBSpecFrame.
- [ ] **Step 4: Rewrite the lists**; run the tests and the full suite — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Read and write DSS, and refuse FITS-CLASS as C does"`

### Task 10: The remaining write tests

**Files:**
- Test: translations of the in-scope tests of Rust `test_fitschan_wcs_write.rs` (14, less the spectral and SplineMap ones), `test_fitschan_c_inline.rs` (its write tests from `testfitschan.c`), and the write tests deferred in `test_fitschan_astwarn.rs` and `test_fitschan_cursor_oracle.rs`
- Modify: whatever the tests show is missing

- [ ] **Step 1: Translate** the tests and remove their `rust-tests.txt` entries.
- [ ] **Step 2: Run** — Expected: they pass, or fail on a gap; fix each gap with the C source and record it.
- [ ] **Step 3: Full suite** — Expected: PASS.
- [ ] **Step 4: Commit** — `git commit -m "Translate the remaining FitsChan write tests"`

### Task 11: Sweep the deferred lists

- [ ] **Step 1: Rewrite every list** with `mvn test -Dast.writeDeferred=true` and read each diff.
  Expected: no entry reads `out-of-scope method FitsChan.write`; the remaining encoding reasons are FITS-CLASS reading only; every other remaining reason names an absent class; nothing added; `rg UNEXPLAINED src/test/resources/deferred` prints nothing.
- [ ] **Step 2: Run `mvn verify`** — Expected: BUILD SUCCESS; record the count.
- [ ] **Step 3: Run on JDK 27** — `mvn test -Djvm=/opt/homebrew/opt/openjdk/bin/java` — Expected: PASS.
- [ ] **Step 4: Commit** if anything changed — `git commit -m "Clear the deferrals FitsChan writing resolves"`

### Task 12: The phase 3 report

**Files:**
- Create: `docs/reports/<date>-phase3-report.md`, `docs/reports/<date>-phase3-rulings.md`

- [ ] **Step 1: Write the report** in phase 2's form: what was ported (lines per task), the gate counts (wcsconv rows by encoding and reason, headers, oracle cases), every tolerance or excuse added or removed, C defects found (in `docs/rust-c-differences.md`), and every ledger ruling in the rulings file.
- [ ] **Step 2: Commit** — `git commit -m "Report phase 3"`
