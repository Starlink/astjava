# Pure-Java AST Phase 5 (The Remaining jniast Mappings) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LutMap, MathMap, TranMap, RateMap, GrismMap, PcdMap, SwitchMap, IntraMap and DssMap work as C's do, with jniast's public API, and every place the earlier phases stop with `needs` one of them carries on as C does: SpecFluxFrame conversions, non-linear unit conversions, and FITS `-LOG`, `-TAB` and grism axes.

**Architecture:** Port the Rust port's `mappings/{lutmap,mathmap/*,tranmap,ratemap,grismmap,pcdmap,switchmap,intramap,dssmap}.rs` onto the phase 1 to 4 classes, with SpecMap and TimeMap as the Java pattern for a Mapping class (attributes, `transformInto`, `mapMerge`, `rateAt`, `dump`, a loader in `ObjectRegistry`).
Take from C (`reference/starlink-ast/src/{lutmap,mathmap,tranmap,ratemap,grismmap,pcdmap,switchmap,intramap,dssmap}.c`) every part whose Rust structure follows from Rust's ownership rules.
The gates are the C test programs (`testlutmap.c`, `testratemap.c`, `testswitchmap.c`, through their Rust translations), the Rust repo's C-probe oracles, C's `wcsconv` rows and the deferred lists, each checked both ways.

**Tech Stack:** as phases 1 to 4: Java 8 (`--release 8`), Maven, JUnit 5, Jackson (test scope), Error Prone, Checkstyle, PMD, Spotless; JDK 21 to build.

**Spec:** `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md` ("Later phases", item 4).
The earlier plans' Shared Design sections and the rulings in `docs/reports/2026-11-1*-phase*-rulings.md` still apply where this plan does not change them.

## Global Constraints

- Every Global Constraint of the phase 1 plan applies unchanged (Java 8, no runtime dependencies, jniast signatures, C at `3d3f255f`, `default` and final `else` throw `AST__INTER`, `MODERN-JAVA` tags, no heuristics the original lacks, expected values from C only, share where C calls `astClone` and copy where C calls `astCopy`, British spelling, one sentence per line in Markdown).
- Public API: exactly jniast's constructors and methods for the classes of this phase: `LutMap(double[], double, double)`; `MathMap(int, int, String[], String[])` with `getSeed`/`setSeed`, `getSimpFI`/`setSimpFI`, `getSimpIF`/`setSimpIF`; `TranMap(Mapping, Mapping)`; `RateMap(Mapping, int, int)`; `GrismMap()` with the eight `getGrism*`/`setGrism*` pairs; `PcdMap(double, double[])` with `getDisco`/`setDisco`, `getPcdCen(int)`/`setPcdCen(int, double)`; `SwitchMap(Mapping, Mapping, Mapping[])`; `IntraMap(Transformer, int, int)`, `IntraMap(Transformer2)`, `IntraMap(Transformer1)`, `channelize()`, `unChannelize()`, `copy()`; the abstract classes `Transformer`, `Transformer1` and `Transformer2` and the interface `NeedsChannelizing` as jniast declares them; `DssMap` with no public constructor.
  Every other attribute C has (LutInterp, LutEpsilon, and so on) is reached through the generic `AstObject` attribute methods, as in jniast.
  Nothing else public is added.
- Before porting a class, read Rust `docs/porting-hazards.md`, its section in `docs/audits/mapping-c-parity.md`, and every entry naming it in `docs/issues/c-library-quirks.md` (search by name).
  An entry marked RESOLVED upstream describes a fix made after the pin: read the pinned C to see whether the fix is already there (phase 4 found four that were, under other hashes); follow the pinned C, and record a real difference in `docs/rust-c-differences.md`.
- Rust comments cite older C line numbers; find each C function by name in the pinned source.
- Classes still absent after this phase: SelectorMap and the Regions (phase 6), FitsTable, NormMap, UnitNormMap, ChebyMap, XphMap, SplineMap, and the other AST classes jniast lacks (phase 9).
  Each place C or Rust reaches one throws `UnsupportedOperationException` whose message ends `needs <Class>`, naming the first absent class C would build (the convention `Deferred.unavailableReason` reads).
- Values must match C to the oracle tolerances; when one does not, find the C function that computes it before touching the arithmetic (H7).

## Review Focus

1. A MathMap expression using each of C's operators and functions, with C's precedence (including the unary-above-`**` quirk), bad inputs, division by zero and out-of-domain arguments gives C's values, and each malformed expression gives C's status and message (test in Task 2).
2. MathMap's random-number functions: a MathMap and its copy give the same sequence for the same Seed, a copy's sequence does not disturb the original's, and two threads transforming two copies get identical results (test in Task 2).
3. A Transformer that throws, returns arrays of the wrong shape, or reports no forward or inverse transformation gives C's IntraMap error (AST__ITFER, AST__TRNND) and never a raw Java exception from inside the library (test in Task 11).
4. Every new class survives a NATIVE dump and read, through Channel and FitsChan, with each attribute set and unset, giving C's cards; a Mapping nested inside CmpMap, TranMap, RateMap or SwitchMap survives too (test in each class's task).
5. Simplifying CmpMaps that mix the new classes with the existing ones (LutMap beside WinMap and its own inverse, a MathMap and its inverse with SimpFI and SimpIF set, an inverted TranMap, a RateMap of a simplifiable Mapping, a SwitchMap whose route Mappings simplify) gives C's result (test in Tasks 1, 3, 5, 6 and 10).

---

## Shared Design

### Where the code goes

- One public class per AST class, built as SpecMap and TimeMap are: attribute values and `getAttrib`/`setAttrib`/`testAttrib`/`clearAttrib`, `transformInto`, `mapMerge`, `rateAt` where C has a `Rate`, `equals` as C's `Equal`, `dump`/`dumpComment`, a loader in `ObjectRegistry`.
- Package-private helpers beside a class when it passes about 2,500 lines, named for the Rust module they port: `MathMapToken`, `MathMapParser`, `MathMapEvaluator`, `MathMapRandom` for `mathmap/{token,parser,evaluator,rng}.rs`.
- The FITS changes go into the phase 3 and 4 classes that throw `needs` today: `FitsWcsRead` (`-TAB` and logarithmic axes), `FitsWcsSpectral` (`-LOG`, `-GRI`, `-GRA`), `FitsOtherAxes` and `FitsSpectralAxes` (`LogAxis`, the grism branch), `UnitsMapper` (non-linear units), `SpecFluxFrame` (`MakeSFMapping`).

### Taken from C rather than Rust

1. TranMap, RateMap and SwitchMap hold the Mappings given, shared where C clones them and copied where C copies them; Rust's clones for borrowing are not reproduced.
2. MathMap keeps C's compiled form, one opcode list and constant list per function, so `Equal`, `MapMerge` and the dump follow C's comparison and card order.
3. MathMap's random-number state is C's per-MathMap `Rcontext`, copied by `astCopy` as C copies it.
4. IntraMap's Transformer is held by reference, as jniast does; C's process-wide `astIntraReg` table is not reproduced (Task 11 sets out the dump format).

### Gates and deferred lists

- The Rust test files of this phase (`test_lutmap.rs`, `test_lutmap_inverse.rs`, `test_mathmap.rs`, `test_tranmap.rs`, `test_ratemap.rs`, `test_grismmap.rs`, `test_pcdmap.rs`, `test_switchmap.rs`, `test_intramap.rs`) are not in `deferred/rust-tests.txt`, since phase 1 never listed them; each task translates its files and adds to `rust-tests.txt` only the tests that still need an absent class, with that reason.
- C-probe oracles `lutmap_forward_oracle`, `intramap_oracle` and `dss_merge_oracle` are converted by the task that needs them, from `tests/<name>/cases.rs` with the existing `CasesToJson`, as earlier phases did.
- `WcsconvTest`, `TransformOracleTest` (`headers.txt`), `SerialisationCorpusTest` (`serialisation.txt`, `full-dumps.txt`), `framesets.txt`, `simplify.txt`, `simplify-cases.txt` and `read-warnings.txt`: an entry whose only missing class arrives in a task passes in that task, or carries the next absent class as its reason; the lists stay stable on a rewrite (`-Dast.writeDeferred=true` makes no change).

---

### Task 1: LutMap

**Files:**
- Create: `LutMap.java` (Rust `mappings/lutmap.rs`; C `lutmap.c`)
- Modify: `ObjectRegistry.java`, `FitsWcsRead.java` (the `-TAB` branch that throws `needs LutMap` carries on to C's next step), `FitsMapTab.java` if it builds a LutMap C builds
- Test: `LutMapTest` (translates `test_lutmap.rs` and `test_lutmap_inverse.rs`, from `testlutmap.c`), `LutMapForwardOracleTest` (new, `lutmap_forward_oracle`), the deferred-list gates

**Interfaces:**
- Produces: `public LutMap(double[] lut, double start, double inc)`; LutInterp and LutEpsilon attributes; package-private accessors for the table, start and increment that `FitsMapTab` and `MapMerge` use.

- [ ] **Step 1: Write the failing tests:** the 54 tests of `test_lutmap.rs`, `test_lutmap_inverse.rs`, the oracle, a native round trip with LutInterp and LutEpsilon set and unset, the LutMap simplification cases of Review Focus 5 (`WinMap` and `ZoomMap` neighbours, a LutMap and its inverse), and a `-TAB` header read with TabOK 1 giving C's error now that LutMap exists.
- [ ] **Step 2: Run** `mvn test -Dtest='LutMapTest,LutMapForwardOracleTest'` — Expected: FAIL (no LutMap).
- [ ] **Step 3: Implement** C's forward interpolation (linear and nearest), its inverse (the monotonic search, the flat-section handling and LutEpsilon), its `MapMerge` and `Rate`, and its dump.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS; deferred lists rewritten with only `needs LutMap` entries gone or carrying the next reason.
- [ ] **Step 5: Commit** `Port LutMap`.

### Task 2: MathMap expressions and transformation

**Files:**
- Create: `MathMap.java`, `MathMapToken.java`, `MathMapParser.java`, `MathMapEvaluator.java`, `MathMapRandom.java` (Rust `mappings/mathmap/*`; C `mathmap.c`)
- Modify: `ObjectRegistry.java`
- Test: `MathMapTest` (translates the expression, transformation, attribute and error tests of `test_mathmap.rs`), the deferred-list gates

**Interfaces:**
- Produces: `public MathMap(int nin, int nout, String[] fwd, String[] inv)`, `getSeed`/`setSeed`, `getSimpFI`/`setSimpFI`, `getSimpIF`/`setSimpIF`; package-private access to the compiled functions for Task 3's `Equal` and `MapMerge`.

- [ ] **Step 1: Write the failing tests:** the `test_mathmap.rs` tests that need no simplification, Review Focus 1 (every operator and function C's symbol table lists, with bad and out-of-domain inputs, and each parse error), Review Focus 2 (Seed, copies, two threads), and a native round trip of a MathMap with intermediate variables, random functions and each attribute set and unset.
- [ ] **Step 2: Run** `mvn test -Dtest=MathMapTest` — Expected: FAIL (no MathMap).
- [ ] **Step 3: Implement** C's `ExtractExpressions`, `ExtractVariables`, `ParseVariable`, `CompileExpression` and `CompileMapping` (with C's error statuses and messages), `EvaluateFunction` and its virtual-machine opcodes, the random-number generator, and the dump.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port MathMap`.

### Task 3: MathMap simplification, equality and units

**Files:**
- Modify: `MathMap.java` (C `MapMerge`, `Equal`, `Rate`), `UnitsMapper.java` (C `astUnitMapper`'s MathMap for non-linear unit conversions)
- Test: `MathMapTest` (the rest of `test_mathmap.rs`), `UnitsMapperTest` or the existing units tests, the deferred-list gates

**Interfaces:**
- Consumes: Task 2's compiled functions.
- Produces: MathMap simplification as C; `Units` conversions that need a MathMap (logarithmic and power units) give C's Mapping.

- [ ] **Step 1: Write the failing tests:** the remaining `test_mathmap.rs` tests, the MathMap cases of Review Focus 5 (a MathMap beside its inverse with SimpFI and SimpIF each set and unset, two MathMaps with equal and unequal functions), and the unit conversions whose expected Mapping C builds as a MathMap (from the units tests and oracles that today expect `needs MathMap`).
- [ ] **Step 2: Run** `mvn test -Dtest='MathMapTest,Units*Test'` — Expected: FAIL.
- [ ] **Step 3: Implement** C's `MapMerge` (cancelling a MathMap and its inverse only as C's SimpFI and SimpIF allow), `Equal`, and the MathMap branch of `astUnitMapper`.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS; `needs MathMap` entries whose only missing class was MathMap pass or carry the next reason.
- [ ] **Step 5: Commit** `Simplify MathMaps and build them for non-linear units`.

### Task 4: FITS logarithmic and tabular axes

**Files:**
- Modify: `FitsWcsRead.java` (C `LogAxis`'s read side and the `-TAB` read), `FitsWcsSpectral.java` (`-LOG` spectral axes), `FitsOtherAxes.java` and `FitsSpectralAxes.java` (C `LogAxis` and the `-LOG` write branch)
- Test: `FitsWcsSpectralTest`, `FitsChanWcsWriteTest`, `TransformOracleTest`, `WcsconvTest`, the `rust-tests.txt` entries whose reason is `needs MathMap` or `needs LutMap` on a FITS path

**Interfaces:**
- Consumes: Tasks 1 to 3.
- Produces: `-LOG` axes, spectral and other, read and written as C; `-TAB` reads go as far as C goes without a FitsTable (C's error when no table is available); `-TAB` writes still throw `needs FitsTable`.

- [ ] **Step 1: Write the failing tests:** the `-LOG` read and write cases in `rust-tests.txt`, `headers.txt` and `wcsconv`, a `-LOG` spectral header read and written back in each encoding that accepts it, transforming as the original, and the `-TAB` read cases with C's outcome.
- [ ] **Step 2: Run** `mvn test -Dtest='FitsWcsSpectralTest,FitsChanWcsWriteTest,TransformOracleTest,WcsconvTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** as C, in C's order of keyword use and warnings.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Read and write logarithmic FITS axes`.

### Task 5: TranMap

**Files:**
- Create: `TranMap.java` (Rust `mappings/tranmap.rs`; C `tranmap.c`)
- Modify: `ObjectRegistry.java`
- Test: `TranMapTest` (translates `test_tranmap.rs`), the deferred-list gates

**Interfaces:**
- Produces: `public TranMap(Mapping map1, Mapping map2)`; package-private access to the two Mappings and their Invert flags for `MapMerge` and dumps.

- [ ] **Step 1: Write the failing tests:** the 31 tests of `test_tranmap.rs`, a native round trip with each component inverted, and the TranMap simplification cases of Review Focus 5.
- [ ] **Step 2: Run** `mvn test -Dtest=TranMapTest` — Expected: FAIL.
- [ ] **Step 3: Implement** as C, with C's `MapMerge` (including the TranMap-of-a-pair cases and `MapSplit`), `Rate`, `Equal` and dump.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port TranMap`.

### Task 6: RateMap

**Files:**
- Create: `RateMap.java` (Rust `mappings/ratemap.rs`; C `ratemap.c`)
- Modify: `ObjectRegistry.java`, `Rate.java` if C's RateMap uses a part of `astRate` not yet ported
- Test: `RateMapTest` (translates `test_ratemap.rs`, from `testratemap.c`), the deferred-list gates

**Interfaces:**
- Produces: `public RateMap(Mapping map, int ax1, int ax2)`.

- [ ] **Step 1: Write the failing tests:** the 32 tests of `test_ratemap.rs`, a native round trip, and the RateMap simplification cases of Review Focus 5.
- [ ] **Step 2: Run** `mvn test -Dtest=RateMapTest` — Expected: FAIL.
- [ ] **Step 3: Implement** as C, with its `MapMerge` (a RateMap of a simplified Mapping, of a UnitMap, and the cancellation C does), `Equal` and dump.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port RateMap`.

### Task 7: SpecFluxFrame conversions

**Files:**
- Modify: `SpecFluxFrame.java` (C `MakeSFMapping`, replacing the `needs RateMap` throws)
- Test: `SpecFluxFrameTest`, `SpecFluxCmpOracleTest`, `SpecFluxPrimaryOracleTest`, the `rust-tests.txt` and oracle cases that expect `needs RateMap`

**Interfaces:**
- Consumes: Tasks 2, 3, 5 and 6.
- Produces: conversions between any two SpecFluxFrames as C builds them.

- [ ] **Step 1: Write the failing tests:** every case that expects `needs RateMap` now expects C's Mapping and values, including Jy to mJy at one spectral system, and FLXDN to FLXDNW across spectral systems and units; a FrameSet holding two SpecFluxFrames converts in both directions.
- [ ] **Step 2: Run** `mvn test -Dtest='SpecFluxFrameTest,SpecFluxCmpOracleTest,SpecFluxPrimaryOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** C's `MakeSFMapping` (the RateMap of the spectral Mapping, the MathMap that scales flux by it, and the TranMap that joins them).
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Convert between SpecFluxFrames with differing components`.

### Task 8: GrismMap and FITS grism axes

**Files:**
- Create: `GrismMap.java` (Rust `mappings/grismmap.rs`; C `grismmap.c`)
- Modify: `ObjectRegistry.java`, `FitsWcsSpectral.java` (`-GRI` and `-GRA` read), `FitsSpectralAxes.java` (C's `grism` branch, which extracts a GrismMap from the Mapping)
- Test: `GrismMapTest` (translates `test_grismmap.rs`), `FitsWcsSpectralTest`, `FitsChanWcsWriteTest`, `WcsconvTest`, the deferred-list gates

**Interfaces:**
- Produces: `public GrismMap()` and the eight attribute pairs; FITS `-GRI`/`-GRA` read and write as C.

- [ ] **Step 1: Write the failing tests:** the 22 tests of `test_grismmap.rs`, a native round trip with each attribute set and unset, the GrismMap simplification cases (C's `MapMerge` with ZoomMap and WinMap neighbours and its own inverse), and `-GRI` and `-GRA` headers read and written back, transforming as the original.
- [ ] **Step 2: Run** `mvn test -Dtest='GrismMapTest,FitsWcsSpectralTest,FitsChanWcsWriteTest,WcsconvTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** as C.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port GrismMap and read and write grism axes`.

### Task 9: PcdMap

**Files:**
- Create: `PcdMap.java` (Rust `mappings/pcdmap.rs`; C `pcdmap.c`)
- Modify: `ObjectRegistry.java`
- Test: `PcdMapTest` (translates `test_pcdmap.rs`), the deferred-list gates

**Interfaces:**
- Produces: `public PcdMap(double disco, double[] pcdcen)`, `getDisco`/`setDisco`, `getPcdCen(int)`/`setPcdCen(int, double)`.

- [ ] **Step 1: Write the failing tests:** the 26 tests of `test_pcdmap.rs`, a native round trip, and the PcdMap simplification cases (C's `PcdZoom` and `PcdPerm` with ZoomMap and PermMap neighbours, inverted on either side).
- [ ] **Step 2: Run** `mvn test -Dtest=PcdMapTest` — Expected: FAIL.
- [ ] **Step 3: Implement** as C.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port PcdMap`.

### Task 10: SwitchMap

**Files:**
- Create: `SwitchMap.java` (Rust `mappings/switchmap.rs`; C `switchmap.c`)
- Modify: `ObjectRegistry.java`
- Test: `SwitchMapTest` (translates `test_switchmap.rs`, from `testswitchmap.c`), the deferred-list gates

**Interfaces:**
- Produces: `public SwitchMap(Mapping fsmap, Mapping ismap, Mapping[] routemaps)`.

- [ ] **Step 1: Write the failing tests:** the `test_switchmap.rs` tests whose selectors are not SelectorMaps (those go to `rust-tests.txt` with the Region class C builds first), a native round trip, and the SwitchMap simplification cases of Review Focus 5.
- [ ] **Step 2: Run** `mvn test -Dtest=SwitchMapTest` — Expected: FAIL.
- [ ] **Step 3: Implement** as C, including its conversion of selector values to route indices (the C quirk on range checks), `MapMerge`, `Equal` and dump.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port SwitchMap`.

### Task 11: IntraMap and Transformers

**Files:**
- Create: `IntraMap.java`, `Transformer.java`, `Transformer1.java`, `Transformer2.java`, `NeedsChannelizing.java` (jniast's Java classes for the API; Rust `mappings/intramap.rs` and C `intramap.c` for the Mapping)
- Modify: `ObjectRegistry.java`, `Channel.java` (jniast's channelize and unChannelize calls around a write and read)
- Test: `IntraMapTest` (translates `test_intramap.rs`), `IntraMapOracleTest` (new, `intramap_oracle`), the deferred-list gates

**Interfaces:**
- Produces: the jniast API listed in Global Constraints.

- [ ] **Step 1: Write the failing tests:** the 11 tests of `test_intramap.rs`, the oracle, Review Focus 3, a Serializable Transformer written and read back through Channel (top level and inside a CmpMap), and a non-Serializable Transformer's write failing as jniast's does.
- [ ] **Step 2: Run** `mvn test -Dtest='IntraMapTest,IntraMapOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** the Mapping as C's IntraMap (Fname, IntraFlag, the forward and inverse flags from the Transformer, SimpFI and SimpIF in `MapMerge`) and the serialisation of the Transformer in jniast's printable encoding, so a dump jniast wrote reads back.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port IntraMap and the Transformer classes`.

### Task 12: DssMap

**Files:**
- Create: `DssMap.java` (Rust `mappings/dssmap.rs`; C `dssmap.c`)
- Modify: `ObjectRegistry.java`
- Test: `DssMapTest` (the unit tests of `dssmap.rs`), `DssMergeOracleTest` (new, `dss_merge_oracle`), the deferred-list gates

**Interfaces:**
- Produces: `DssMap` with no public constructor, loaded from dumps, transforming and merging as C.

- [ ] **Step 1: Write the failing tests:** the unit tests, the oracle, the `serialisation.txt` and `full-dumps.txt` entries whose reason is `needs DssMap`, and a native round trip.
- [ ] **Step 2: Run** `mvn test -Dtest='DssMapTest,DssMergeOracleTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** as C (the plate solution, its `MapMerge` with neighbouring WinMaps and ZoomMaps, and the dump that holds the FITS cards).
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Port DssMap`.

### Task 13: The remaining Rust tests and the deferred sweep

**Files:**
- Test: translations of every remaining test in `rust-tests.txt` whose reason names a class this phase adds (`test_equal.rs`, `test_cmpmap.rs`, `test_native_roundtrip_all.rs`, `test_map_split.rs`, `test_nan_protocol.rs`, `test_constructor_checks.rs`, `test_clear_on_set.rs` and the others the list names)

- [ ] **Step 1: Translate** each test, expected values from C as the Rust test records them.
- [ ] **Step 2: Run** `mvn -q test -Dast.writeDeferred=true` and diff every `deferred/*.txt` against the branch base: no entry names a class this phase adds; every entry that changed reason names a class still absent.
- [ ] **Step 3: Run** `mvn verify` on JDK 21 and `mvn test -Djvm=<JDK 27 java>` — Expected: PASS.
- [ ] **Step 4: Commit** `Translate the remaining Mapping Rust tests` and any list changes as `Sweep the deferred lists after phase 5`.

### Task 14: The phase 5 report

- [ ] **Step 1: Write** `docs/reports/2026-11-<dd>-phase5-report.md` and `docs/reports/2026-11-<dd>-phase5-rulings.md` in the form of the phase 4 report: what was ported, departures from the plan, gates before and after, tolerances with their traced causes, C defects found, final review, gaps and risks.
- [ ] **Step 2: Commit** `Report phase 5`.
