# Pure-Java AST: phase 3 report

## Summary

Phase 3 ported FitsChan writing and the FITS encodings other than FITS-WCS on 15 and 16 November 2026, following the plan [`2026-11-15-pure-java-ast-phase3-fitschan-write.md`](../superpowers/plans/2026-11-15-pure-java-ast-phase3-fitschan-write.md).
`FitsChan.write()` now writes FrameSets of this library's classes as FITS-WCS (with SIP distortion), NATIVE, FITS-PC, FITS-IRAF, FITS-AIPS, FITS-AIPS++ and DSS cards, and refuses FITS-CLASS as C does; `FitsChan.read()` now reads FITS-PC, FITS-IRAF, FITS-AIPS, FITS-AIPS++ and DSS.
Of the 194 rows of C's FITS conversion regression table (`ast_tester/fixtures/wcsconv/cases.txt`), 118 now give C's cards, in C's order with C's comments and numbers within 4 units in the last place; at the start of the phase none did, since every row writes cards.
Every one of the other 76 needs a class outside this library: SpecFrame (63), the FITS-CLASS read (6), DSBSpecFrame (4), MathMap (2) or SplineMap (1).
19 more headers of the transform oracle now read and transform as C's do, and six C-probe oracle suites of the Rust port were converted and pass.
Porting found about 20 places where C reads or writes memory it does not own, dereferences a null pointer or recurses without end; they are recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md), and listed below.
The work added 8,441 lines of main code and 8,838 lines of tests; `mvn verify` passes with 2,829 tests on JDK 21, and the tests pass on the JDK 27 runtime.

## What was ported

| Task | What | Main lines | Test lines |
| --- | --- | ---: | ---: |
| 1 | The write entry (`FitsChan.write`, C's `Write` with `FixNew`) and the NATIVE encoding (`FitsNativeWriter`) | 477 | 698 |
| 2 | Intermediate world coordinates and linear axes (`FitsMakeIntWorld`, `FitsIsMapLinear`, `FitsWorldAxes`, `FitsInvertable`, `FitsOrth`, `FitsWcsDecompose`) | 1,329 | 693 |
| 5 | The SIP building blocks (`FitsSipIntWorld`, `AnalysePoly`, `ScalePolyInputs`, PolyMap's `MergeShift`) | 1,086 | 611 |
| 3 | Celestial axes (`FitsCelestialAxes`, `FitsSkySys`, `FitsSkyPole`, `FitsFiducial.getFiducialWcs`, `FitsMapTab`) | 1,546 | 1,255 |
| 4 | FrameSet to store (`FitsFsetToStore`, `FitsPlaneStores`, `FitsOtherAxes`) | 898 | 883 |
| 6 | Writing FITS-WCS (`FitsWcsWrite`, `FitsFromStore`, C's `FindWcs` templates and `SetValue` comment rules) and the C-probe oracle suites | 710 | 1,593 |
| 7 | FITS-PC and FITS-IRAF (`FitsEncPc`, `FitsEncIraf`, their `SpecTrans` translations) | 728 | 711 |
| 8 | FITS-AIPS and FITS-AIPS++ (`FitsEncAips`) | 739 | 622 |
| 9 | DSS (`FitsEncDss`) and the FITS-CLASS refusal (`FitsEncClass`) | 825 | 760 |
| 10 | The remaining Rust write tests; no main code changed | 0 | 786 |
| 11 | Sweep of the deferred lists: nothing left to change | 0 | 0 |
| 12 | This report | | |

Main lines are lines added to `src/main`; 203 lines were removed over the phase.
Task 5 ran before Task 3: MakeIntWorld calls SIPIntWorld whenever SipOK is set, which is its default, so every celestial test of Tasks 3 and 4 needed it.
Each of Tasks 1 to 10 was done by one implementer subagent and verified in the controlling session before it was recorded (the full test suite, the deferred lists changing only by removal or by a more specific class reason, and the C source for each finding); Tasks 3 and 8 needed one fix round each.
From Task 4 on, expected values that no fixture held were taken from probes run against the pinned C library, built without floating-point contraction; the probe sources were kept with the execution record.
The tasks were committed between 22:34 on 15 November and 02:49 on 16 November.

## Departures from the plan

The [rulings](2026-11-16-phase3-rulings.md) give each with its reason and cost; these change what later work builds on.

- **C's signatures.**
  Where the plan guessed a signature (`makeIntWorld` returning a MatrixMap, `fsetToStore` returning a boolean, `celestialAxes` returning a boolean), the port takes C's arguments and results: `makeIntWorld` needs C's FitsTol and an encoding-dependent SipOK, `fsetToStore` returns the store, and `celestialAxes` returns the Mapping from world to intermediate world coordinates.
- **The store indexed by version letter.**
  C writes alternate descriptions in letter order and merges two descriptions given one letter, so the store is a `FitsPlaneStores` of one `FitsStore` per letter (the plan's design item 7).
- **No SIP orders in the store.**
  C stores SIP coefficients only, and `WcsFromStore` finds each order as it writes the cards; Java does the same.
- **Where the cards go.**
  The plan's Review Focus item 4 put new cards before the card current when the write began; C puts them after the last card that describes WCS (`FindWcs`), or at the end when there is none, and the test asserts C's placement.
- **The `-TAB` decision.**
  C decides whether an axis can be a table with classes this library has (separability and a known dimension) before it needs a FitsTable; that decision is ported (`FitsMapTab`), and only a write C would describe with `-TAB` throws `needs FitsTable`.
- **A write that throws has its new cards removed.**
  C leaves the cards a write added when the write fails with an error, because its clean-up does nothing once the error status is set; Java removes them, as the Rust port does.
  As in C, a card that the write replaced before failing is not brought back.

## Gates

| Gate | Before | After |
| --- | --- | --- |
| `wcsconv/cases.txt` rows giving C's cards | 0 of 194 | 118 of 194 |
| `wcsconv` rows by encoding (matching / total) | | NATIVE 66/107, FITS-WCS 17/26, FITS-AIPS 11/17, FITS-AIPS++ 12/18, FITS-IRAF 6/9, FITS-PC 4/5, DSS 1/1, AST 1/2, FITS-CLASS 0/9 |
| `wcsconv` rows deferred as `out-of-scope method FitsChan.write` | 134 | 0 |
| `wcsconv` rows deferred for an encoding | 28 | 6 (all reading FITS-CLASS) |
| `headers.txt` entries (transform oracle) | 70 | 51: SpecFrame 45, FITS-CLASS 4, MathMap 2 |
| `rust-tests.txt` entries | 399 | 376, after adding 13 entries for two Rust test files the list had missed |
| `FitsChanCursorOracleTest` cases run | 147 | 247 |
| `TranGridOracleTest` grids run | 14 | 19 |
| Tests | 2,467 | 2,829 |

New C-probe oracle suites, converted from the Rust port's `cases.rs` with `CasesToJson`: `FitsChanEncodingsOracleTest` (every encoding column), `FitsChanSipokOracleTest`, `FitsChanSplitmapOracleTest`, `FitsChanAltNaxesOracleTest` and `FitsChanAltEpochOracleTest`, and the write cases of `SkyPoleOracleTest`; cases needing an absent class are listed in each and checked both ways.
`FitsChanWriteTest` holds the Review Focus tests: a failed or Strict write leaves the cards, their order and the current card as they were, except that a card the write replaced before failing is lost, as in C; cards go where C puts them; every in-scope header of the transform oracle, read and written in each encoding that accepts it, reads back to transform as C's own round trip does (where C's round trip fails or differs, the test expects that); four threads writing copies of one FrameSet give identical cards.

The native dumps that phase 2 reported as differing from C in the last bits without a known cause (`skyref`, `skyref-alt`, `tidy-pole`, `azel`) now give C's cards within 4 units in the last place.

## Tolerances and excuses

Removed: the `tan` grids of `TranGridOracleTest`, unrun since phase 1 (now run, see below).
Added; each runtime-Math entry was traced, with a debugger on C and replays in C and Java, to an elementary function returning a different last bit for identical argument bits, with every other intermediate value identical:

| Where | What | Measured size and cause |
| --- | --- | --- |
| C-probe oracle suites | Cards compared within 4 ULP, as the `wcsconv` gate, rather than byte for byte; `FitsChanSipokOracleTest` stays exact | Java's `Math` against macOS libm |
| `FitsChanSplitmapOracleTest` `linear`, `linear_3d` | CDELT2 within 1e-13 relative | 5 ULP: `Math.cos(2π − 0.2)` 1 ULP from libm, amplified by `FitLine`'s least squares |
| `FitsChanEncodingsOracleTest` offset-SkyRef cases | PC elements within 1e-13 relative | about 1e-16: identical MatrixMaps, `atan2` 1 ULP apart |
| `FitsChanEncodingsOracleTest` DSS cases (7) | PLTRAS and PLTDECS within the effect of 4 ULP of CRVAL | CRVAL 1 to 2 ULP from C through `atan2` (forward SphMap, and `eraC2s` in `eraFk5hz` with Epoch set); the keywords hold 3600 times CRVAL's fractional part |
| `TranGridOracleTest` `tan` grids (5) | Sampled positions within `ulp(π/2)`; the bit-for-bit hash not checked for them | at most 2.2e-16 rad: `atan2`, `sin` and `cos` 1 ULP apart in 472 of 682 pixels, at identical arguments |
| `FitsChanCursorOracleTest` `alt_axes_offset{,_none}` | CDELT2 and CDELT2A within 1e-14 relative | 1 ULP, runtime Math |
| `FitsChanCursorOracleTest` `sky_offset_system_rw` | CDELT1 and CDELT2 within 1e-14 relative, PC1_2 and PC2_1 within 1e-14 absolute (the PC diagonal is 1) | CDELT1 8 ULP, PC2_1 2.0e-15: identical fitted Mappings, whose SphMap and TAN WcsMap transforms differ first at equal inputs (traced with a debugger and per-component comparisons, not replayed), the slopes of the differences being the differences of the fitted gradients |
| `FitsLogMapTest` | Logarithms and exponentials within 1 ULP of C's MathMap | `Math.exp(1)` is 1 ULP from libm |
| `FitsChanWcsWriteTest` AZEL, Galactic and Supergalactic writes | CDELTn and PCi_j within 1e-14 absolute plus 1e-13 relative; other cards within 4 ULP | identical fitted Mappings; IWC computed from native latitudes near 90° amplify 1-ULP differences; the Galactic PC1_2 differs by 2.8e-15 against a limit of 1.3e-14 |
| `FitsFsetToStoreTest` offset-sky description | Within 1e-14 rather than 4 ULP | the FrameSet read from the header already differs by 15 to 20 ULP, from the same runtime functions |
| `FitsSipIntWorldTest` SIP_2D | CD within 1e-12 relative; CRPIX, the 32 coefficients and the samples exact | 1 to 3 ULP: the Rust oracle's copy of `SIPIntWorld` fits over an older box than current C; current C is checked end to end by the `sip` rows of `wcsconv` |
| `FitsSkyPoleTest`, `FitsCelestialAxesTest` | Stored LONPOLE and LATPOLE within 1e-12 of C's cards | the values come from C's cards, written with 15 and 17 significant digits, and are at most 180 |

## Defects found in the C library

All are in the pinned library (`3d3f255f`), in `fitschan.c` unless named; "probe" means a probe against the pinned library showed it, "reading" that it follows from the code.
Each is recorded in [`docs/rust-c-differences.md`](../rust-c-differences.md) with what Java does.
Java gives a defined result in every case: it treats an index past an array as absent, declines or throws `AstException` with `AST__INTER` where C's behaviour is undefined, and stores nothing C leaves uninitialised.

| Where | Defect | Confirmed |
| --- | --- | --- |
| `MakeFitsFrameSet`, `AddEncodingFrame` | A world Frame holding two SkyFrames makes no FrameSet, and the null FrameSet is then dereferenced | Probe (crash in `astGetFrame_`) |
| `SplitMap2` | A CmpMap with Ident set is decomposed into itself, and the search recurses without end | Reading |
| `IsMapTab1D`, `CelestialAxes`, `OtherAxes` | A WCS axis fed by several grid axes returns a Mapping with no table columns, after which a FitsTable that was never made is used (a null dereference in `OtherAxes`) | Reading |
| `AnalysePoly` | Writes an output index past the end of its coefficient array for each unused coefficient after the last used one | Reading (the Java port's first run hit the bound) |
| `MakeInvertable` | Reads a dimension past the end of its caller's array for degenerate axes | Reading |
| `WorldAxes` | For a Mapping with only a forward transformation, reads `perm[j]` past the array when there are more inputs than outputs | Reading |
| `SIPIntWorld` | Reads grid axes past the array `astMapSplit` returns when the split of the other outputs has fewer inputs | Reading |
| `FsetToStore` | Its second letter pass reads past its table of Frames once the 26 letters are used | Reading |
| `PCFromStore` | Compares an alternate PC matrix with the primary as flat arrays, past the primary for an alternate with more axes; compares RADECSYS with an absent primary value | Reading |
| `MakeIntWorld` | A failed fit stores CDELT values `SplitMat` never set (they never reach a card, since a version without CRPIX is not written) | Probe |
| `CLASSFromStore` | With one-pixel celestial axes, `astTranN` is given three coordinates whatever the FrameSet, so four pixel axes fail with `AST__NCPIN` | Probe |
| `WcsFromStore` (date helper) | An MJD `palDjcl` rejects leaves the date fields uninitialised and formats them | Reading |
| `SkySys` | The offset CTYPE comment reads past an empty Symbol, and a long SkyRef overflows its 80-character buffer | Reading |
| `Similar` | Reads past both strings when both are blank, and ignores the last non-blank character, so `ABCD` and `ABCX` are alike (kept) | Probe |
| `polymap.c` `MergeShift` | Reads the number of outputs with `astGetNin` (latent: its one caller passes a 2-by-2 PolyMap) | Reading |
| `Write`, `FixNew` | A write that fails with an error leaves the cards it added and the current card where it failed | Reading |
| `SetValue`, `FixNew` | A write that replaces a card with the keyword it stores and then fails loses that card (kept) | Probe |

Kept as C has them, since they are defined: DATE-OBS written `T24:00:00.000` when the time of day rounds up, and years before 1900 as negative two-digit years; SIP `*_ORDER` and IMAGFREQ written without a version letter; `QV` used for every axis once one is TPN; FITS-AIPS++ `PROJPm` not read back; the `CardComm` comment and the `CmXXX` letter counting of the PC and IRAF translations; the method and class names exchanged in FsetToStore's warnings; the offset SkyFrame's FrameSet remapped twice by `AddVersion`.

## Final review

A fresh reviewer read the whole branch and ran a differential fuzz against the pinned C library: 1,800 random FrameSets of this library's classes written in all seven encodings (12,600 writes), some into FitsChans already holding cards with a random current card and attributes, and 4,440 mutated FITS-PC, FITS-IRAF, FITS-AIPS, FITS-AIPS++ and DSS headers read.
No exception other than `AstException` and `UnsupportedOperationException` escaped; wherever both libraries wrote, the cards, their order, their placement and the current card matched C's, and wherever both refused, both FitsChans were unchanged; Review Focus items 4 and 5 were confirmed against C.
It found no Critical problem and no phase 3 port error.
The fix pass dealt with:

- **Writes refused that C completes.**
  `LogAxis` threw `needs MathMap` for every non-linear axis with a non-zero CRVAL, where C uses the MathMap only to test whether the axis's logarithm is linear and, when it is not, goes on to describe the axis linearly within FitsTol; the fuzz found 231 writes C completed that Java refused.
  The test is now ported with a package-private Mapping computing what C's MathMap computes, and `needs MathMap` is thrown only where the test passes; of the 735 fuzzed writes that threw there, 700 now give C's outcome and cards, 28 reach `-TAB` and throw `needs FitsTable`, and 7 (one FrameSet) still throw where C builds the `-LOG` Mapping and then writes nothing.
  This reverses the Task 4 ruling, and it made the `lsst`, `offset-sky` and `offset-sky-pole` FITS-WCS rows match C.
- **Documentation.**
  The Javadoc of `write` now says where each encoding puts its cards, and that a failed write loses a card it had already replaced (C does the same; recorded as a C quirk, and pinned by a test); a test name that said the opposite of what it asserts was corrected.

A scoped re-review found all four findings addressed and no new problem.
The minor findings were then fixed.
The `FitsSkyPoleTest` tolerance is 1e-12 (it was 1e-9), and the libm licence of the cursor-oracle cases is 1e-14 relative (it was 1e-13), five times the largest difference.
Two read differences from earlier phases that the fuzz exposed now follow C, each pinned by a test whose expected values come from a C probe:
the read's keyword translations are kept apart from the header's cards and read after them, as C reads its FitsChan of translations, so a translation outranks every card with its keyword (a zero CDELT followed by another CDELT, several CDELTs before a CD matrix) and a repeated translation keeps its comment by `SetFits`'s rules;
and a NAXISn of a described axis that is not a number fails the read with `AST__FTCNV`, as C's `DistortMaps` reads it with `astGetFitsF`.
A CTYPE card without a comment no longer clears the comment an earlier card gave, which with several "other" axes becomes the axis Label.
Rust shares all three; they are in `docs/rust-c-differences.md`.

## Gaps and risks

- **Logarithmic axes need MathMap.**
  `LogAxis` tests an axis for the `-LOG` algorithm as C does, with an internal Mapping computing what C's MathMap computes, and `write` throws `needs MathMap` only where C would then describe the axis with `-LOG`, a description whose Mapping holds a MathMap.
  C sometimes builds that description and then drops it, when `MakeIntWorld` cannot fit it: the absolute description of an offset SkyFrame in the cursor-oracle case `alt_axes_alt_offset`, and one FrameSet of the review's write fuzz, which C writes in no encoding and Java refuses in all seven.
  With the test ported, offset sky coordinates (`offset-sky`, `offset-sky-pole`, `offset-sref`, `skyref`, `skyref-alt`), `lsst`, and non-celestial axes that are linear only within FitsTol are written as C writes them: the `offset-sky`, `offset-sky-pole` and `lsst` rows of `wcsconv` match.
  Of the 735 fuzzed writes that threw at `LogAxis` before, 700 now give C's outcome and cards, 28 reach a `-TAB` description with TabOK set and throw `needs FitsTable`, and 7 are the FrameSet above.
- **Everything spectral needs SpecFrame**, which accounts for 63 of the 76 deferred `wcsconv` rows and 45 of the 51 deferred headers; reading FITS-CLASS needs SpecFrame and DSBSpecFrame.
- **Runtime-Math margins.**
  The Galactic PC1_2 comparison has a margin of about 4.6 times; another JDK or libm could use more of it.
  Only every 97th position of the `tan` grids is compared, because C's oracle holds only those samples and a hash.
- **ReadCrval** reads the FitsChan before a write, so a write into a FitsChan holding a header this library cannot read (spectral, FITS-CLASS) throws `UnsupportedOperationException` naming the class.

## Rulings

Every decision made while executing the plan where it was wrong, unclear or silent is in [`2026-11-16-phase3-rulings.md`](2026-11-16-phase3-rulings.md), with its reason and its cost if wrong.
