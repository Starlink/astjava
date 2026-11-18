# Pure-Java AST Phase 6 (Resampling and Rebinning) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** jniast's `Mapping.resample`, `resampleB/S/I/L/F/D`, `rebin` and `rebinI/F/D` work as C's `astResample<X>` and `astRebin<X>` do when called through jniast (except that `rebin` now rebins variances, which jniast never did), with every Interpolator and Spreader scheme, including the caller's own kernel (`Ukern1Calculator`) and interpolator (`UinterpCalculator`).

**Architecture:** Port the Rust port's `mappings/resample/{mod,interp,kernels}.rs`, `mappings/rebin/{mod,spread}.rs` and `pixel.rs` onto the phase 1 to 5 classes, with `TranGrid` as the Java pattern for C's adaptive subdivision (`astLinearApprox` over sections, blocks of output pixels, `AST_REPLACE_NAN`).
C (`reference/starlink-ast/src/mapping.c`: `Resample<X>`, `ResampleAdaptively`, `ResampleWithBlocking`, `ResampleSection`, `Interpolate*<X>`, `ConserveFlux<X>`, the kernel functions, `Rebin<X>`, `RebinAdaptively`, `RebinWithBlocking`, `RebinSection`, `Spread*<X>`) is final.
The gates are C's `testresample.c` and `testrebin.c` (through their Rust translations), the Rust repo's `resample_oracle` and `rebin_oracle` C probes, and new C probes for what those do not reach.

**Tech Stack:** as phases 1 to 5: Java 8 (`--release 8`), Maven, JUnit 5, Jackson (test scope), Error Prone, Checkstyle, PMD, Spotless; JDK 21 to build.

**Spec:** `docs/superpowers/specs/2026-11-02-pure-java-ast-design.md` ("Later phases", item 5).
The earlier plans' Shared Design sections and the rulings in `docs/reports/2026-11-1*-phase*-rulings.md` still apply where this plan does not change them.

## Global Constraints

- Every Global Constraint of the phase 1 plan applies unchanged (Java 8, no runtime dependencies, jniast signatures, C at `3d3f255f`, `default` and final `else` throw `AST__INTER`, `MODERN-JAVA` tags, no heuristics the original lacks, expected values from C only, share where C calls `astClone` and copy where C calls `astCopy`, British spelling, one sentence per line in Markdown).
- Public API: exactly the jniast members the port already declares as stubs: `Mapping.resample(int, int[], int[], Object, Object, Interpolator, ResampleFlags, double, int, Number, int, int[], int[], int[], int[], Object, Object)`, `resampleB`, `resampleS`, `resampleI`, `resampleL`, `resampleF`, `resampleD`, `rebin(double, int, int[], int[], Object, Object, Spreader, boolean, double, int, Number, int, int[], int[], int[], int[], Object, Object)`, `rebinI`, `rebinF`, `rebinD`, the `Interpolator` and `Spreader` factories, `ResampleFlags`, `Ukern1Calculator`, `UinterpCalculator`, and the four `*_INTERPOLATOR`/`*_SPREADER` constants.
  Nothing else public is added; their signatures do not change (`JniastApiTest`).
- Before porting, read Rust `docs/porting-hazards.md`, `docs/superpowers/plans/2026-10-09-resample-rebin.md`, the resampling and rebinning entries of `docs/issues/c-library-quirks.md` (search `Resample`, `Rebin`, `SombCos`, `BlockAverage`), and the `Mapping` rows of `docs/audits/mapping-c-parity.md`.
- Rust comments cite older C line numbers; find each C function by name in the pinned source.
- C's types without a jniast counterpart (UL, UI, US, UB, K, UK, LD) are not ported; oracle cases for them are skipped by type, never listed as deferred.
- Values must match C to the oracle tolerances; when one does not, find the C function that computes it before touching the arithmetic (H7).
- Tolerances for values that pass through `java.lang.Math` (sin, cos, exp, the Bessel function, a non-linear Mapping) come from an error bound worked out from the computation, never from the drift seen on this machine: CI runs on x86-64 Linux, where `Math` differs in the last bit from AArch64 (phase 4's `FitsSpectralAxesTest` failed in CI for this reason).

## Review Focus

1. A `Ukern1Calculator` or `UinterpCalculator` that throws a checked or runtime exception gives an `AstException` with C's status (`AST__UK1ER`, `AST__UINER`) and the thrown exception as its cause; an `Error` passes through unchanged and the Javadoc says so; the output holds what C's holds when its user function sets that status (test in Task 2).
2. A data or variance array whose length does not match its grid, an output region outside the output grid, or the generic `resample`/`rebin` given arrays of different types or a `badval` of another `Number` type: an `AstException` with Rust's status and message for lengths (`AST__DIMIN`, which C cannot check), C's for bounds, and jniast's `IllegalArgumentException` message for types; never an `ArrayIndexOutOfBoundsException` or `ClassCastException` (test in Tasks 1 and 3).
3. `float` and `double` data holding NaN, with `badval` NaN (as SPLAT and astgui pass it) and with `badval` a number, with and without `setUseBad(true)`: C's output and bad count, which follow from C comparing `in == badval` (test in Task 1, from a new C probe).
4. Two threads resampling and rebinning through one Mapping (a FrameSet's, so simplification is reached) at once get the results each gets alone, and the Mapping is unchanged (test in Task 1, extended in Task 3).
5. A 2048 × 2048 `float` image resampled through a non-linear Mapping with a kernel interpolator completes in a child JVM with a 64 MB heap, as C's blocks of output pixels allow (test in Task 2, the child-JVM pattern of phase 5's `TransformMemoryTest`).

---

## Shared Design

### Where the code goes

- `PixelArray` (package-private, abstract): one final nested subclass per jniast type (`byte`, `short`, `int`, `long`, `float`, `double`), wrapping a Java array and the call's bad value.
  It is the Java form of Rust's `PixelValue` and `RebinValue` traits and C's `Xtype`/`Xfloattype` macro arguments.
- `Resample` (package-private, final, static methods): `Resample<X>`'s validation, simplification and error reports, `ResampleAdaptively`, `ResampleWithBlocking`, `ResampleSection`, `ConserveFlux<X>`.
- `ResampleInterpolators` (package-private): `InterpolateNearest<X>`, `InterpolateLinear<X>`, `InterpolateKernel1<X>`, `InterpolateBlockAverage<X>`, and the call to a `UinterpCalculator`.
- `ResampleKernels` (package-private): `Sinc`, `SincCos`, `SincGauss`, `SincSinc`, `Somb`, `SombCos`, `Gauss`, the Bessel function and the scheme set-up (Rust `kernels.rs`); Rebin's spreading uses them too.
- `Rebin` and `RebinSpreaders` (package-private): `Rebin<X>`, `RebinAdaptively`, `RebinWithBlocking`, `RebinSection`, and `SpreadNearest<X>`, `SpreadLinear<X>`, `SpreadKernel1<X>`.
- `Mapping`: the public methods call `Resample` and `Rebin`; `noResampling()`, the `@throws UnsupportedOperationException` lines and the class Javadoc's "not available" sentence go.

### Taken from C rather than Rust

1. **Arithmetic in the type's sum type.** C sums each type in its `Xfloattype`: `float` for F, S and B, `double` for D, L and I.
   Java computes each step in `double` and rounds it with `(float)` where C's variable or operation is `float`.
   That gives `float` arithmetic exactly: rounding twice is harmless for +, −, ×, ÷ and square root when 53 ≥ 2 × 24 + 2.
   Each rounding sits where C stores to an `Xfloattype` variable or applies an operator to two `float` operands; C's mixed `float`/`double` expressions are computed in `double`, as C promotes them.
2. **Conversions to the pixel type** follow C as compiled on the platform the oracles were made on (AArch64), as Rust's `PixelValue::from_f64` and `RebinValue::from_c` do.
   Java's `(int)` and `(long)` casts saturate, which matches AArch64; `(byte)` and `(short)` go through `int` as C's do.
3. **Flags are C's integers.** `ResampleFlags.getFlagsInt()` gives the flags C is passed.
   `rebin` passes C `AST__USEBAD` when `usebad` is true, as jniast's `Mapping.c` does, and also `AST__USEVAR` when both `in_var` and `out_var` are given.
   jniast never set `AST__USEVAR`, so through jniast C zeroed `out_var` and never filled it, though jniast's Javadoc promises output variances; C rebins variances only with that flag.
   With it, `rebin` gives the variances jniast's Javadoc describes; with either array absent the flags are jniast's and C's result is unchanged.
   The Javadoc of `rebin` and `rebinI/F/D` says when variances are rebinned; `docs/rust-c-differences.md` records the change from jniast.
4. **The caller's functions.** `Interpolator.ukern1(calc, npix)` passes C `params = {npix}`, and C's kernel callback calls `calc.ukern1(offset)`.
   `Interpolator.uinterp(calc)` calls the `uinterp<X>` method for the data's type with the caller's own `in`, `in_var`, `out` and `out_var` arrays, the block's `offset` (`int[npoint]`) and `coords` (`double[ndim_in][npoint]`), and the `ResampleFlags` object given to `resample`, as jniast does.
   A thrown `Exception` stops the resampling with status `AST__UK1ER` or `AST__UINER`, as jniast's `astSetStatus` does, and becomes the `AstException`'s cause.
5. **`RebinSeq<X>` is not ported:** jniast has no `rebinSeq`.
   The spreading functions take only the flags `Rebin<X>` passes (`AST__USEBAD` and `AST__USEVAR`).
   Each place C tests a flag only `RebinSeq<X>` sets is left out, with a comment naming the flag.
   `test_rebinseq.rs` and `test_rebinseq_oracle.rs` go to `deferred/rust-tests.txt` with the reason `astRebinSeq (not in jniast)`.

### Gates and deferred lists

- `test_resample.rs`, `test_resample_oracle.rs`, `test_rebin.rs`, `test_rebin_oracle.rs`, `test_rebinseq.rs` and `test_rebinseq_oracle.rs` are not in `deferred/rust-tests.txt`, since phase 1 never listed them.
  Each task translates its files, and adds to `rust-tests.txt` only the tests that need something this phase does not port, with that reason.
- `resample_oracle` and `rebin_oracle` are converted from `tests/<name>/cases.rs` with the existing `CasesToJson` to `src/test/resources/oracle/resample.json` and `rebin.json`.
  Their hash is FNV-1a over the bit patterns of every output value and variance converted to `double`, in the order `tools/oracle_fixtures/<name>_probe.c` forms it.
  A case whose values pass through `java.lang.Math` and misses the hash is compared value by value against a probe in `src/test/resources/probes/` that prints them, within a bound worked out for that computation (Global Constraints).
- No entry of the other deferred lists names resampling or rebinning; they stay stable on a rewrite (`-Dast.writeDeferred=true` makes no change).

---

### Task 1: PixelArray, the resampling framework, nearest and linear

**Files:**
- Create: `PixelArray.java`, `Resample.java`, `ResampleInterpolators.java` (nearest and linear only)
- Modify: `Mapping.java` (`resample` and `resampleB/S/I/L/F/D`)
- Create: `src/test/resources/oracle/resample.json`, `src/test/resources/probes/resample_nan_probe.c` and its output
- Test: `ResampleTest` (translates `test_resample.rs` tests 1 nearest and linear, 2, 3 and 5 to 9), `ResampleOracleTest` (`resample.json`, nearest and linear cases), `ResampleNanTest`, `ResampleArgumentsTest`, `ResampleThreadsTest`

**Interfaces:**
- Produces: `abstract class PixelArray` with `static PixelArray of(byte[] a, byte bad)` and one `of` per type; `int length()`; `double get(int i)`; `boolean isBad(int i)` (C's `in[i] == badval` in the type); `void setBad(int i)`; `boolean floatSum()` (C's `Xfloattype` is `float`); `double sum(double v)` (`v` rounded to the sum type); `boolean signed()`; `void setResampled(int i, double v)` (C's assignment of an interpolated value, with its range check and rounding); `Object array()`.
- Produces: `static int Resample.resample(Mapping map, int ndimIn, int[] lbndIn, int[] ubndIn, PixelArray in, PixelArray inVar, Mapping.Interpolator interp, ResampleFlags flags, double tol, int maxpix, int ndimOut, int[] lbndOut, int[] ubndOut, int[] lbnd, int[] ubnd, PixelArray out, PixelArray outVar, String method)`, returning the number of bad output pixels; `method` is C's name for error prefixes (`astResampleD` and so on).
- Produces: an interpolator dispatch in `ResampleInterpolators` keyed on `Interpolator.scheme`, whose `default` throws `AST__INTER` until Task 2 adds the other schemes.

- [ ] **Step 1: Convert** `resample_oracle/cases.rs` to `resample.json` with `CasesToJson`.
- [ ] **Step 2: Write the probe** `resample_nan_probe.c` for Review Focus 3: `astResampleF` and `astResampleD` through a ShiftMap of half a pixel, nearest and linear, input holding NaN at known pixels, `badval` NaN and `badval` -999, `AST__USEBAD` set and unset, printing the bad count and every output value; build it against the pinned C and commit the output.
- [ ] **Step 3: Write the failing tests:**
  - `ResampleTest`: the tests named under Files.
  - `ResampleOracleTest.resamplingMatchesC`: every nearest and linear case of a jniast type; it asserts the number of cases run, so a filter cannot hide cases.
  - `ResampleOracleTest.errorsMatchC`: the status of each invalid-argument case.
  - `ResampleNanTest`: the probe's values.
  - `ResampleArgumentsTest`: Review Focus 2 for resample.
  - `ResampleThreadsTest`: Review Focus 4, eight threads resampling a 200 × 200 `double` image through one FrameSet's Mapping, results equal to a single-threaded run and the Mapping's dump unchanged.
- [ ] **Step 4: Run** `mvn test -Dtest='Resample*Test'` — Expected: FAIL (`UnsupportedOperationException` from the stubs).
- [ ] **Step 5: Implement** `PixelArray`, `Resample` and nearest and linear interpolation from C, the subdivision as `TranGrid` does it, the generic `resample` as jniast's (its `IllegalArgumentException` message verbatim), and Rust's array-length checks before C's.
- [ ] **Step 6: Run** the Step 4 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 7: Commit** `Resample with nearest-neighbour and linear interpolation`.

### Task 2: Kernels, block averaging, the caller's functions and flux conservation

**Files:**
- Create: `ResampleKernels.java`
- Modify: `ResampleInterpolators.java` (kernel, block average, `UinterpCalculator`), `Resample.java` (`ConserveFlux<X>`, `AST__CONSERVEFLUX`), `Mapping.java` (`Interpolator` Javadoc for the caller's functions), `UinterpCalculator.java` and `Ukern1Calculator.java` (Javadoc: exceptions, `Error`)
- Test: `ResampleTest` (test 1 kernels, test 4), `ResampleOracleTest` (every case), `ResampleCallbackTest`, `ResampleMemoryTest`

**Interfaces:**
- Consumes: Task 1's `PixelArray`, `Resample.resample` and the dispatch.
- Produces: `ResampleKernels.Kernel` (a package-private interface `double value(double offset, double[] params)`) and `static Setup ResampleKernels.setup(int scheme, double[] params)` giving the kernel, the neighbourhood half-width and the parameters C passes it, which Task 3's spreading uses (Rust `kernels::setup`).

- [ ] **Step 1: Write the failing tests:**
  - `ResampleTest`: test 1's kernel schemes and test 4.
  - `ResampleOracleTest`: the filter removed, every case of a jniast type, and `tooFewParamsIsAnError`.
  - `ResampleCallbackTest`: Review Focus 1, a `Ukern1Calculator` and a `UinterpCalculator` (one subclass overriding `uinterpD` and `uinterpF`) that compute the probe's `tri_kernel` and `uinterp_<X>`, matching the oracle's user-function cases; each throwing an `IOException`, an `IllegalStateException` and an `AssertionError` at a known call.
  - `ResampleMemoryTest`: Review Focus 5.
  - `user_flags_reach_the_callers_interpolator_and_kernel` goes to `rust-tests.txt`, with a reason naming `AST__URESAMP1`–`4`, which jniast's `ResampleFlags` lacks.
- [ ] **Step 2: Run** `mvn test -Dtest='Resample*Test'` — Expected: FAIL (the new schemes reach the `AST__INTER` default).
- [ ] **Step 3: Implement** the kernels, `InterpolateKernel1<X>`, `InterpolateBlockAverage<X>` (with both C quirks Rust records), the caller's functions (Shared Design item 4), and flux conservation.
  For each oracle case that misses its hash only through `java.lang.Math`, add it to the value-by-value probe (Gates) with the bound and its derivation in a comment.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Commit** `Resample with kernels, block averaging, the caller's functions and flux conservation`.

### Task 3: Rebinning

**Files:**
- Create: `Rebin.java`, `RebinSpreaders.java`, `src/test/resources/oracle/rebin.json`, `src/test/resources/probes/rebin_jniast_probe.c` and its output
- Modify: `PixelArray.java` (Rust `RebinValue`: `conv`, `add`, `square`, `subSquare`, `fromC` for `int`, `float` and `double`), `Mapping.java` (`rebin`, `rebinI/F/D`), `docs/rust-c-differences.md`
- Test: `RebinTest` (translates `test_rebin.rs` for D, F and I), `RebinOracleTest`, `RebinJniastTest`, `RebinArgumentsTest`, `ResampleThreadsTest` (rebinning added)

**Interfaces:**
- Consumes: `PixelArray`, `ResampleKernels.setup`.
- Produces: `static void Rebin.rebin(Mapping map, double wlim, int ndimIn, int[] lbndIn, int[] ubndIn, PixelArray in, PixelArray inVar, Mapping.Spreader spread, int flags, double tol, int maxpix, int ndimOut, int[] lbndOut, int[] ubndOut, int[] lbnd, int[] ubnd, PixelArray out, PixelArray outVar, String method)`.

- [ ] **Step 1: Convert** `rebin_oracle/cases.rs` to `rebin.json`; write `rebin_jniast_probe.c`, which calls `astRebinD`, `astRebinF` and `astRebinI` with flags 0, `AST__USEBAD`, `AST__USEVAR` and both, with and without variance arrays, and prints every output value and variance (Shared Design item 3); build it against the pinned C and commit the output.
- [ ] **Step 2: Write the failing tests:**
  - `RebinTest`: every test of `test_rebin.rs` for D, F and I.
  - `RebinOracleTest`: `rebinningMatchesC` and `errorsMatchC` for the jniast types, each asserting its case count; `tooFewParamsIsAnError`.
  - `RebinJniastTest`: the probe's values: `rebin` with both variance arrays matches C's `AST__USEVAR` call, and with only one of them matches C's call without the flag.
  - `RebinArgumentsTest`: Review Focus 2 for rebin.
  - `ResampleThreadsTest`: rebinning added.
- [ ] **Step 3: Run** `mvn test -Dtest='Rebin*Test,ResampleThreadsTest'` — Expected: FAIL.
- [ ] **Step 4: Implement** `Rebin<X>` (including its zeroing of the output after an argument error, the quirk Rust records), the drivers, and the three spreading functions (Shared Design item 5).
- [ ] **Step 5: Run** the Step 3 command, then `mvn -q test` — Expected: PASS.
- [ ] **Step 6: Commit** `Rebin through a Mapping`.

### Task 4: The remaining Rust tests, the deferred sweep and a differential run against C

**Files:**
- Modify: `src/test/resources/deferred/rust-tests.txt` (`test_rebinseq.rs`, `test_rebinseq_oracle.rs`)
- Test: any test of the six Rust files not yet translated or listed

- [ ] **Step 1: List** every test of `test_rebinseq.rs` and `test_rebinseq_oracle.rs` with the reason `astRebinSeq (not in jniast)`; check that every test of the six files is translated or listed.
- [ ] **Step 2: Differential run** (scratch, not committed): a C driver and a Java driver resample and rebin the same random cases.
  - Mappings: the oracles' Mappings and PolyMap, MathMap, WinMap and PermMap compounds.
  - Grids: 1 to 3 dimensions, with output regions.
  - Every scheme and flag combination jniast can express.
  - The six resample types and three rebin types.
  - Bad pixels and variances.
  - Compared bit for bit where no `java.lang.Math` is reached, and within the derived bound elsewhere.
  A mismatch is traced to C before anything changes.
  A C crash or memory error goes to the user's C defect report (never GitHub); a C wrong result goes to the phase report.
- [ ] **Step 3: Run** `mvn -q test -Dast.writeDeferred=true` and diff every `deferred/*.txt` against the branch base — Expected: only the `rust-tests.txt` lines of Step 1.
- [ ] **Step 4: Run** `mvn verify` on JDK 21 and `mvn test -Djvm=<JDK 27 java>` — Expected: PASS.
- [ ] **Step 5: Commit** `Defer astRebinSeq's Rust tests` (and any fixes the differential run needs, each with its test, as their own commits).

### Task 5: The phase 6 report

- [ ] **Step 1: Write** `docs/reports/2026-11-<dd>-phase6-report.md` and `docs/reports/2026-11-<dd>-phase6-rulings.md` in the form of the phase 5 report:
  - what was ported, and departures from the plan;
  - gates before and after;
  - tolerances with their derivations;
  - the differential run;
  - C defects found;
  - final review;
  - gaps and risks.
  It also corrects the phase 5 report's "SelectorMap moved to phase 6": Regions are spec item 6, which is phase 7.
- [ ] **Step 2: Commit** `Report phase 6`.
