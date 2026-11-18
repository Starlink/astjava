# Pure-Java AST: phase 6 report

## Summary

Phase 6 ported resampling and rebinning on 18 November 2026, following the plan [`2026-11-18-pure-java-ast-phase6-resample.md`](../superpowers/plans/2026-11-18-pure-java-ast-phase6-resample.md).
jniast's `Mapping.resample`, `resampleB/S/I/L/F/D`, `rebin` and `rebinI/F/D`, which phases 1 to 5 declared as stubs throwing `UnsupportedOperationException`, now work as C's `astResample<X>` and `astRebin<X>` do when called through jniast, with every `Interpolator` and `Spreader` scheme, the caller's own kernel (`Ukern1Calculator`) and interpolator (`UinterpCalculator`), flux conservation, and C's adaptive subdivision and blocks of output pixels.
No public member was added and none changed signature; `JniastApiTest` passes.
The user decided one point before the work started: `rebin` passes C `AST__USEVAR` when both variance arrays are given, so the output variances jniast's Javadoc describes are now computed.
jniast never set the flag, so through jniast C zeroed `out_var` and never filled it.
With either variance array absent the call is jniast's: C gets no `AST__USEVAR`, and an `outVar` given without `inVar` is left untouched, because jniast's JNI code passed C no `out_var` then.
Of the Rust port's `resample_oracle` and `rebin_oracle` C probes, 222 resample cases (the six jniast types) and 81 rebin cases (`int`, `float` and `double`) run: every nearest, linear, block-average, Somb and caller's-function case matches C bit for bit, and the 111 kernel cases whose kernels call `sin`, `cos` or `exp` are compared with new C probes within a bound worked out for each output pixel, on every platform.
A differential run against the pinned C build compared 92,352 random cases (65,472 resample, 26,880 rebin) and found no mismatch.
It also found one wrong result and three kinds of undefined behaviour in C, listed below.
The deferred lists changed only by 18 new `rust-tests.txt` entries: tests of Rust-only API, of `astRebinSeq` (not in jniast), and C's `testhuge.c` port, which needs Regions.
This report also corrects the phase 5 report, which said SelectorMap was moved to phase 6: Regions are the spec's "Later phases" item 6, which is phase 7 (item 5, resampling and rebinning, is this phase).
Against the branch base `7f1919d` the work adds 4,940 lines of main code and 19,204 lines of tests (13,440 of them C probe output), and removes 77 and 25; `mvn verify` passes with 3,845 tests on JDK 21, and the tests pass on the JDK 27 runtime.

## What was ported

| Task | What | Main lines | Test lines |
| --- | --- | ---: | ---: |
| 1 | `PixelArray`, `Resample` (`Resample<X>`'s checks, `ResampleAdaptively`, `ResampleWithBlocking`, `ResampleSection`, `ConserveFlux<X>`, `MatrixDet`), nearest and linear interpolation, `LinearApprox` in either direction, the public `resample` methods | 1,990 | 1,678 |
| 2 | `ResampleKernels` (Sinc, SincCos, SincGauss, SincSinc, Somb, SombCos, Gauss, the Bessel function, the scheme set-up), `InterpolateKernel1<X>`, `InterpolateBlockAverage<X>`, the caller's kernel and interpolator, flux conservation through kernels | 1,156 | 11,994 |
| 3 | `Rebin` (`Rebin<X>`, `RebinAdaptively`, `RebinWithBlocking`, `RebinSection`), `RebinSpreaders` (`SpreadNearest<X>`, `SpreadLinear<X>`, `SpreadKernel1<X>`), the public `rebin` methods | 1,874 | 5,666 |
| 4 | The remaining Rust tests of the six resample and rebin files, the deferred sweep, the differential run (scratch, not committed) | | 14 |
| 5 | This report, and `test_huge.rs` listed | | 1 |

Lines are those each task's commits add, the fix commits included; summed (5,020 and 19,353), they exceed the branch totals by the lines a later task changed again.
The test lines of Task 2 include `resample_kernel_probe.out` (9,036 lines, 417 KB) and `resample_callback_probe.out` (1,265 lines); those of Task 3 include `rebin_kernel_probe.out` (2,499 lines, 222 KB).
Each of Tasks 1 to 4 was done by one implementer subagent, reviewed by a second, and verified in the controlling session before it was recorded.
The reviews found no Critical problem and two Important ones, both fixed in one fix round each: Task 1's review found that two threads resampling through one FrameSet's Mapping could give wrong results (settled by the threading ruling below), and Task 2's found that the derivation of its fixed tolerances assumed a weight ratio the cases exceed.
Tasks 3 and 4 were approved at the first review.
Expected values that no oracle held come from probes run against the pinned C library, committed under `src/test/resources/probes` (`resample_nan_probe`, `resample_callback_probe`, `resample_kernel_probe`, `rebin_jniast_probe`, `rebin_kernel_probe`); the oracles were converted with `CasesToJson` from the Rust port's `resample_oracle` and `rebin_oracle` cases to `oracle/resample.json` and `oracle/rebin.json`.
The tasks were committed between 09:26 and 11:35 on 18 November.
New test classes: `ResampleTest` (11 tests), `ResampleOracleTest` (4), `ResampleNanTest`, `ResampleArgumentsTest` (6), `ResampleThreadsTest`, `ResampleCallbackTest` (5), `ResampleMemoryTest`, `RebinTest` (9), `RebinOracleTest` (4), `RebinJniastTest` and `RebinArgumentsTest` (7).
`docs/rust-c-differences.md` has two new sections, "Resampling" and "Rebinning", with 14 entries.

## Departures from the plan

The [rulings](2026-11-18-phase6-rulings.md) give each with its reason and cost; these change what later work builds on.

- **Threading: the spec's rule governs, and the plan's Review Focus 4 was corrected.**
  Review Focus 4 asked for two threads resampling and rebinning through one Mapping at once.
  The spec's Threading section says an object may be used from any thread but not from two at once, and tells users to `copy()` a FrameSet per thread; the spec wins.
  `ResampleThreadsTest` has eight threads each resampling and rebinning twenty times through its own `copy()` of one FrameSet's unsimplified Mapping, with results equal to the single-threaded ones and the original's dump unchanged, and the Javadoc states the one-thread-at-a-time rule.
  Task 1's reviewer found why sharing fails: resampling more than 1024 pixels simplifies the Mapping, and simplification changes shared components (Invert attributes) temporarily in place, as C does.
  The Simplifier was not changed (see Gaps and risks).
  The fit of the inverse still goes through `LinearApprox.linearApprox(..., forward = false)` rather than C's in-place `astInvert`, which leaves the Mapping's Invert attribute alone and gives the same fit.
- **Arrays longer than their grid are accepted.**
  The plan's Review Focus 2 says an array whose length "does not match" its grid is an error; the project's phase 1 rule ("Array arguments longer than needed"), C and jniast accept longer arrays, so only shorter ones give `AST__DIMIN` (with Rust's message), and a longer one's leading elements are used.
- **Per-pixel bounds replace fixed tolerances.**
  The plan asks for a bound worked out for the computation where a value passes through `java.lang.Math`; Task 2 first derived constants per type (1e-10 for `double`, 1e-2 for `float`, 1 for integers) assuming the ratio R = sum(|w|)/|sum(w)| of a pixel's kernel weights was at most 20.
  Measured, R is 1 to 3.1 for twelve of the thirteen kernel case names but reaches 33 in `sinc_shift`, where bad input pixels left out leave a small sum of weights, so no useful constant exists.
  `ResampleOracleTest.bounds` and `RebinOracleTest.bounds` work out the bound of each output value and variance from that pixel's own weights and sums, and every kernel probe case is compared within it on every platform, not only the cases whose hash differs on this machine.
- **`tooFewParamsIsAnError` is not translated.**
  The plan's Tasks 2 and 3 name it; jniast's `Interpolator` and `Spreader` factories always build the parameters a scheme reads, so too few parameters cannot reach `resample` or `rebin`, and both Rust tests are listed as Rust-only API.
- **`AST__GAUSS`, `AST__SOMB` and `AST__SOMBCOS` are reachable from tests only.**
  C accepts them but jniast has no factory for them; the `Interpolator` and `Spreader` constructors became package-private (no public change) so the oracle's Gauss, Somb and SombCos cases run, and the Gauss kernel was written in Task 2 because Task 3's spreading needs it.
- **`ConserveFlux<X>` arrived in Task 1**, because the oracle's `conserve_zoom` linear case needs it.
- **`PixelArray` has no `square` or `subSquare`.**
  The plan's Task 3 lists them from Rust's `RebinValue`, but only `RebinSeq<X>` uses them, and it is not ported.
- **`Rebin` has a package-private test seam.**
  `RebinOracleTest` records the blocks and output positions through a `BlockSpreading` hook, which per-pixel bounds need and which rebinning, having no caller's function, offers no other way to observe; production passes null.
- **`test_huge.rs` is listed in this task.**
  Task 4 found the test (a port of C's `testhuge.c`) neither translated nor listed, and could change only the six resample and rebin files' entries; it is listed here with the reason `Polygon and Moc (Region, out of scope)`.

## Correction to the phase 5 report

The phase 5 report says SelectorMap "was moved to phase 6" and that "phase 6 must port SelectorMap with Regions".
In the spec's "Later phases" list, item 5 (resampling and rebinning) is phase 6 and item 6 (Regions and STC) is phase 7, so SelectorMap, the Region classes and everything that waits for them belong to phase 7.
The phase 5 report is not edited; this paragraph is the correction.

## Gates

| Gate | Before | After |
| --- | --- | --- |
| `rust-tests.txt` entries | 222 | 240: 3 Rust-only API (`too_few_params_is_an_error` of `test_resample_oracle.rs` and `test_rebin_oracle.rs`, `user_flags_reach_the_callers_interpolator_and_kernel`), 14 `astRebinSeq (not in jniast)` (`test_rebinseq.rs`, `test_rebinseq_oracle.rs`), 1 `Polygon and Moc (Region, out of scope)` (`test_huge.rs::huge`) |
| `framesets.txt`, `full-dumps.txt`, `headers.txt`, `oracle-rows.txt`, `read-warnings.txt`, `serialisation.txt`, `simplify-cases.txt`, `simplify.txt`, `wcsconv.txt` entries | 3, 197, 0, 0, 199, 198, 125, 191, 1 | unchanged |
| `resample.json` cases run (jniast types) | none (stubs) | 222, all matching C (hash, bad count and samples, or per-pixel bounds against the kernel probe) |
| `resample.json` error cases | none | 15, all with C's status (the two user-function cases with jniast's `AST__UK1ER` and `AST__UINER`, where C's probe functions set `AST__BADIN`) |
| `rebin.json` cases run (`int`, `float`, `double`) | none | 81, and 10 error cases |
| Tests | 3,796 | 3,845 |

Every test of `test_resample.rs`, `test_resample_oracle.rs`, `test_rebin.rs`, `test_rebin_oracle.rs`, `test_rebinseq.rs` and `test_rebinseq_oracle.rs` is translated or listed.
Rewriting the lists with `-Dast.writeDeferred=true` changes nothing; the only change from the branch base is the 18 hand-added `rust-tests.txt` lines.
`mvn verify` passes on JDK 21 (3,845 tests, Error Prone, Checkstyle and PMD clean), and the tests pass on the JDK 27 runtime.

## Tolerances and excuses

Removed: none.
Added:

| Where | What | Derivation |
| --- | --- | --- |
| `ResampleOracleTest.bounds` (78 kernel probe cases: 13 case names whose kernels call `sin`, `cos` or `exp`, six types) | Each output value and variance within a bound worked out from that pixel's own weights; bad and untouched pixels exact; an integer may differ by one only where its bound is below a half and its value lies further than the bound from the type's range limits (asserted) | Kernel relative differences with each libm function within 4u and each pair of correct roundings 2u: Sinc 6u, SincCos 12u, SincSinc 14u, Somb 0, SombCos 6u, Gauss 115u and SincGauss 61u at the cases' largest argument; the weight difference RHO_W = 165u (2-D Gauss with FWHM 1.2); with s the sum type's unit roundoff and n the pixel's terms, dV = (e + 2s + 2ns) P/\|W\| + \|val\| (e + 2ns) A/\|W\| + 2s\|val\| from the pixel's own P = sum(\|w v\|), A = sum(\|w\|), W and value, the variance likewise, one more rounding for flux conservation, doubled for second-order terms; `double` bounds come out around 1e-12 |
| `RebinOracleTest.bounds` (33 kernel probe cases: 11 case names, `double`, `float`, `int`) | As above for spreading, through reflection at the edges, the box sums, the weights and the running sum; sums of weights must lie clear of `wlim`; `somb_unit` is exact (J1's rational branch is plain arithmetic) | Task 2's kernel differences propagated through `SpreadKernel1<X>`; `double` bounds come out 2e-14 to 2e-12 |
| `ResampleMemoryChild` | Centre value of the 2048 × 2048 `float` image within 1e-4 of 3 | Constant data gives sum(3w)/sum(w), exactly 3 whatever the weights, so only `float` roundings count: at most 3 × 4 × (2 × 32 + 1) × 2^-24 = 4.7e-5 with sum(\|w\|)/sum(w) at most 2 per axis for sinc reaching two pixels |
| `ResampleTest` tests 1 and 4 | Rust's 1e-10 and 1e-8 for kernels on a UnitMap and on constant data | Taken from the Rust tests; no derivation is written beside them (deferred minor) |

Why per pixel: a pixel's error grows with R, and R is set by which input pixels are bad and how the kernel's lobes fall, so it differs from pixel to pixel; with R reaching 33 a global constant would either fail on x86-64 or be too loose to catch a real error.
Negative controls showed the bounds bite: multiplying Sinc by (1 + 1e-9 offset) fails resampling (`sinc_shift D: ... allowed 4.62e-12`), and by (1 + 1e-12 offset) fails rebinning (`sinc_shift D: ... allowed 2.09e-14`).
On this machine (AArch64 macOS), 8 `double` resample cases and 7 `double` rebin cases miss their hash, by at most a few ULP; every `float` and integer case, and every case that reaches no transcendental function, matches bit for bit.
Everything else is exact: nearest, linear, block averaging, Somb, the caller's functions, `ResampleNanTest`, `RebinJniastTest` and the callback probe.

## Differential run against C

Task 4 ran a seeded generator of random cases through a C driver linked with the pinned static build (`3d3f255f`, `-ffp-contract=off`) and a Java driver calling the public jniast methods (the generic `resample` and `rebin`, so the type dispatch is exercised); the scratch files are not committed.

- **Results:** 92,352 cases over eight seeds (resample 65,472, rebin 26,880), Java on JDK 21 and seeds 4 to 6 also on the JDK 27 runtime: 0 mismatches, 0 undecidable.
  Of them 90,365 were bit-identical (including 15,444 where both gave the same error status and left the same output) and 1,987 differed within the derived bound, all `double` kernel cases and a few `long` and `int` ones.
  The same driver linked with an AddressSanitizer and UBSan build of `3d3f255f` reported no memory error and no crash.
- **Coverage:** the oracles' Mappings, and UnitMap, ShiftMap, ZoomMap, PolyMap, `sqrt` MathMaps, WinMap, PermMap (with constants, changing dimensionality) and MatrixMap, in series and parallel CmpMaps; grids of 1 to 3 dimensions with random lower bounds and output regions (including regions outside the grid); all 11 jniast interpolation schemes over the six types and all 7 spreaders over `int`, `float` and `double`; every combination of `AST__USEBAD`, `AST__NOBAD`, `AST__CONSERVEFLUX` and variances, `wlim` 0, 0.3 and 0.8, `tol` 0 to 0.5, `maxpix` 1 to 1000; bad pixels and variances, NaN data, and bad values inside the data range.
  Error statuses seen and matched: `AST__BADFLG`, `AST__CNFLX`, `AST__GBDIN`, `AST__TRNND`.
- **Comparison:** status, bad count and every output value and variance, bit for bit wherever no transcendental `java.lang.Math` function is reached; kernel cases within the per-pixel bounds of the oracle tests, extended to flux conservation (the factor F is plain arithmetic, taken from a nearest resampling of ones over the same sections); a pixel the bound cannot decide must be equal or the case counts as undecidable.
  A last-bit change to one linear value and a 2^20-ULP change to one sinc value both fail.
- **Gaps, as the run states them:**
  - Somb and SombCos only with reaches of at most 2 pixels (`npix` below 3), where J1 uses its rational branch; J1's asymptotic branch has no relative error bound.
  - No transcendental MathMaps: the bounds assume Java and C put each output pixel at the same input position, which a last-bit difference in a Mapping could break.
  - `rebin` with only one variance array is not in the run; `RebinJniastTest` covers those calls against C's probe.
  - Small grids only (up to 60, 22² and 9³ pixels); large images and blocks beyond 2048 output pixels are covered by the committed tests, not the run.
  - The flux-conservation factor in the bound comes from Java's own run (a small circularity; nearest, linear and block-average conservation cases are compared bit for bit, so a factor bug shows there first).
  - No Java 8 runtime is installed on this machine; the run used JDK 21 and 27 on AArch64 only, and the derived bounds are what protect CI's x86-64 runtimes.

## Defects found in the C library

All are in the pinned library (`3d3f255f`).
Java follows C in every case that has a defined result, and gives a defined result where C's is undefined.

| Where | Behaviour | Confirmed |
| --- | --- | --- |
| `mapping.c` `InterpolateBlockAverage<X>` | Weights each pixel by `1.0 / var` and tests only for a negative variance, so a zero input variance makes the weight infinite and the output NaN; for an integer type C then converts the NaN to the type, which is undefined (AArch64 gives 0; x86-64's conversion instructions give `INT_MIN` for `int` and `LONG_MIN` for `long`); C defines no result, Java gives C's AArch64 result, and integer variances of 0 are ordinary data | Differential run, UBSan (`MAKE_INTERPOLATE_BLOCKAVE` expansions for L, I, S, B) |
| `mapping.c` `InterpolateBlockAverage<X>` | Resets `bad_var` only when it starts a block, so one negative or bad variance can leave out the rest of the block | Rust quirk entry, oracle cases `blockave_novarbad` and `blockave_win3` |
| `mapping.c` `MAKE_INTERPOLATE_BLOCKAVE` for L | Sums `long` data in `long double`, which is `double` on AArch64 macOS and 80-bit on x86-64 Linux, so C's own `long` block averages depend on the platform | Reading |
| `mapping.c` `SombCos` | The first factor is half `Somb`'s except at zero offset | Rust quirk entry, oracle |
| `mapping.c` `Rebin<X>` | After an argument error zeroes only the first output value (and variance) | Oracle probe (`RebinOracleTest.errorsMatchC`) |
| `mapping.c` `ConserveFlux<X>` | Skips scaling any output equal to the bad value, so a good integer output that happens to equal it keeps its unscaled value | Reading |
| `mapping.c` `Interpolate*<X>` | Compare `in == badval`, so a NaN bad value marks no input pixel bad, while bad outputs are still NaN | Probe (`resample_nan_probe`) |

Not a C defect but a jniast one: jniast never passed `AST__USEVAR` to `astRebin<X>`, so its `rebin` zeroed the output variances and never filled them, although its Javadoc promised them (fixed by the user's decision above).

The run also found three kinds of undefined behaviour, now in the PR #104 defect report, reproduced against a sanitiser build of the PR tip: `ConserveFlux<X>` converts products outside an integer type's range back to the type, `SpreadKernel1I` and `SpreadNearestI` overflow `int` addition while accumulating, and `SpreadNearestI` converts out-of-range doubles to `int`.
Java gives C's AArch64 results for each (`int` addition wraps, conversions saturate, and `short` and `byte` keep the low bits of the saturated `int`); `docs/rust-c-differences.md` records these and the zero-variance block average.

Rust-side findings, from the same work: Rust's nearest and linear spreading add an `int` contribution through `double` and saturate, where C's `int` addition wraps; the Rust quirks file records the out-of-range conversion for bytes only (B and UB); and Rust's parameter-count check has no C counterpart.

## Final review

A whole-branch review of `7f1919d..af25abb` found nothing Critical and one Important defect.
An `IntraMap` passed its Transformer's NaN outputs through, against Mapping's class contract that no transformation gives a NaN output, so resampling or rebinning through it could throw `ArrayIndexOutOfBoundsException` (a grid with a positive lower bound) or write wrong pixels.
It was fixed at the source: `IntraMap` now turns a NaN output into `AST__BAD` (`e4ce277`), and resampling and rebinning keep C's tests, which treat only `AST__BAD` as bad; tests compare a Transformer returning NaN with one returning `AST__BAD` for several schemes.
The same pass added derived-bound comments to `ResampleTest`'s tolerances, made `ResampleOracleTest`'s integer check allow a difference of 1 only near a half-integer, as its derivation says, and made `ResampleThreadsTest` compare as it goes so that it holds a few megabytes rather than about 200 (`fbcf3a9`).
A scoped re-review found every finding addressed.
The review confirmed the five Review Focus items (the fourth as ruled), the user's variance decision, and that every bit-exact comparison reaches only arithmetic, `sqrt` and `floor`, so CI's x86-64 runners should give the same results.
After the fixes `mvn verify` passes with 3,848 tests.

Left as minor, and fixed afterwards (see the minor items under Gaps and risks): a null `Interpolator`, `ResampleFlags`, `Spreader` or array gives `NullPointerException` (a null `Spreader` after the output has been zeroed); `IntraMapTest` does not reach `tran2` or the NaN protocol, which use the same path; and block averaging with `npix` of zero or less averages a whole axis for a position beyond about 2^63, through `long` overflow, where C's conversion is undefined.

## Gaps and risks

- **Shared concurrent use.**
  A Mapping may not be resampled or rebinned by two threads at once: resampling more than 1024 pixels simplifies the Mapping, and simplification changes shared components' Invert attributes temporarily, as C does, so a second thread can see them changed.
  The Javadoc says so and tells users to copy a FrameSet per thread; if shared concurrent use is ever wanted, the Simplifier and CmpMap must stop changing shared components in place.
- **`rust-tests.txt` has no automated checker.**
  No test loads it, so a Rust test file that is neither translated nor listed is found only by a sweep, as `test_huge.rs` was.
  `test_huge.rs::huge` also resamples a 60001 × 60001 grid, more pixels than a Java array holds, so when Regions arrive it cannot be translated as it stands.
- **Regions** (phase 7): SelectorMap, the SwitchMap, TranMap and RateMap `RemoveRegions`, and the tests phase 5 listed for them.
- **Differential-run gaps:** Somb with `npix` of 3 or more, transcendental MathMaps, `rebin` with one variance array, small grids only, no Java 8 runtime, and a flux-conservation factor in the bound taken from Java's own run (details above).
- **Runtimes.**
  The tests ran on JDK 21 and the JDK 27 runtime, not on Java 8; the kernel bounds are derived, not tuned, and every kernel probe case is checked against them here, but x86-64 is exercised only in CI.
- **Probe size.**
  `resample_kernel_probe.out` is 417 KB and `rebin_kernel_probe.out` 222 KB.
- **Minor items from the reviews**, fixed after the final review unless stated:
  - `LinearApprox`'s direction overload names the outputs in its `AST__NCPIN` message when `forward` is false.
  - `ResampleOracleTest`'s `RHO_W` is 165u: a = 7.7016 gives 81.016u for each axis of the 2-D Gauss case.
  - A null argument other than a variance array gives a `NullPointerException` naming it, before the output is touched.
  - Block averaging holds a far position's pixel index within 2^32 of the grid, so a block with `npix` of zero or less is empty, and its output bad, wherever its position lies; `docs/rust-c-differences.md` records C's undefined conversion there.
  - `IntraMapTest.nanResultsBecomeBad` covers `tran2` and the NaN protocol, and `IntraMap`'s Javadoc no longer reads as if C meant NaN positions to miss every pixel.
  - `Resample` reads the flags once; `Rebin`'s `BlockSpreading` seam defaults to the scheme's own spreading; `Rebin.rebin`'s Javadoc names `maxpix` among the errors that zero the first value; `RebinTest`'s switch defaults throw `AST__INTER`; the 152- to 157-column Javadoc lines are wrapped.
  - Left as it is: `ResampleInterpolators.kernel1` tests the use-bad flag for the pixel at the position and again for each pixel of the block, as C and Rust do.
  - Settled earlier: `ResampleTest`'s tolerances, `bounds()`'s `@throws` wording and `valueDifference`, which no longer treats any value as a marker, and Task 1's interim `AST__INTER` (Task 2).

## Future work

- **A Javadoc sweep.**
  Wording copied from jniast or C that presents a class as a wrapper around the C library (MathMap's "Java interface to the AST MathMap class") or describes arguments in C terms ("Pointer to an array", "a NULL pointer", as in `FitsWcsKeywords`) should be rewritten to describe the Java class itself.
  Citations of C or jniast source as the origin of behaviour (`jni/IntraMap.c`, `mapping.c`) are provenance and stay, as do comments explaining what C's pointers did.
- **`astRebinSeq`**, if a caller needs it: the spreading functions mark each place C tests a flag only `RebinSeq<X>` sets, and `PixelArray` would need Rust's `square` and `subSquare`.

## Rulings

Every decision made while executing the plan where it was wrong, unclear or silent, every departure from Rust, C or jniast and every C quirk kept is in [`2026-11-18-phase6-rulings.md`](2026-11-18-phase6-rulings.md), with its reason and its cost if wrong: 63 rulings, 57 by the implementers and 6 by the controller and the user.
