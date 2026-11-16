# Phase 3: rulings

These are the decisions made while executing the phase 3 plan ([`2026-11-15-pure-java-ast-phase3-fitschan-write.md`](../superpowers/plans/2026-11-15-pure-java-ast-phase3-fitschan-write.md)) where the plan was wrong, unclear or silent.
They are copied from the execution ledger, in the order they were made within each task.
Each gives what was decided, why, and what it costs if wrong.
A later ruling can reverse an earlier one; the final review section records the reversals made in the fix pass.
The phase report is [`2026-11-16-phase3-report.md`](2026-11-16-phase3-report.md).

## Task order

- Ruling: Task 5 (SIP building blocks) runs before Task 3 — SIPIntWorld needs only Task 2, AnalysePoly, ScalePolyInputs and existing Mapping methods, and with SipOK defaulting to 1 every celestial FITS-WCS test in Tasks 3/4 would otherwise hit the interim throw — cost if wrong: none.

## Task 1

- Ruling: a write that throws is undone (Rust), where C leaves partial cards because FixNew/astSetCard do nothing once status is set; recorded in docs/rust-c-differences.md — cost if wrong: after a failed write, Java's cards differ from C's partial state (C's state is an error leftover).
- Ruling: C's nested Write marking cards not-new is not reproduced; matters only when a write fails — cost if wrong: none observable since a failed write removes all new cards.
- Ruling: a comment leaving no room for a string value throws AST__INTER where C's behaviour is undefined — cost if wrong: an exception on an input C mishandles.
- Ruling: FitsChanCursorOracleTest write ops (W, WF, WFT, WC, WCF; 18 cases) pulled forward from Task 10, as the only C source of exact NATIVE cards; native_full_1 excluded (C writes object/pointer counts Java does not keep), the rest of it compared by a separate test; RW/RCIW/RSW stay for Task 10 — cost if wrong: none.

## Task 2

- Ruling: makeIntWorld follows C's signature — boolean makeIntWorld(cmap, fr, wperm, s, store, dim, fitstol, sipok, method, cls) — not the plan's MatrixMap(..., fc); sipok = encoding==FITS-WCS && SipOK — cost if wrong: mechanical call-site change.
  Carried to Tasks 3-5.
- Ruling: SIP branch (C: sipok && lonax != -1) throws "needs FitsSipIntWorld" until Task 5 — cost if wrong: none once Task 5 lands.
- Ruling: on failure makeIntWorld stores nothing where C leaves CDELT from uninitialised SplitMat memory (C quirk, recorded) — cost if wrong: a failed alternate's C cards (if C writes them) cannot be reproduced; Task 4/6 to check whether C writes a failed version at all.
- Ruling: C reads past arrays in MakeInvertable and WorldAxes (forward-only); Java treats the out-of-range index as absent (recorded as C quirks) — cost if wrong: only for heap contents C happened to read.
- Ruling: findBasisVectors largest-dimension, fitOK strict <, IsASkyFrame Domain check, worldAxes simplify + bad root, orthVector divide, orthVectorSet empty → unit basis, nearestPix any nout, C round: all follow C over Rust (recorded) — cost if wrong: none observed.
- Ruling: Rust tests orth.rs::wrong_row_length_returns_none (Rust-only check) not translated; make_int_world.rs::sip_chain_routes_through_sip_int_world left to Task 5 (interim test asserts the needs-FitsSipIntWorld throw) — cost if wrong: none.
- Ruling: Invert restored in finally in isMapLinear/nearestPix (differs from C only on error paths) — cost if wrong: none.

## Task 3

- Ruling: celestialAxes returns C's WCS→IWC Mapping and takes (fc, fs, dim, wperm, s, store, boolean[] axisDone, isoff, method, cls); isSkyOff returns int with a 1-based Frame index — cost if wrong: mechanical.
- Ruling: TDBConv reuses FitsEpoch.tdbConv (TimeFrame, H8) — cost if wrong: none.
- Ruling: SkySys stores nothing for a non-SKY Domain SkyFrame (C IsASkyFrame); J2000 written as unknown system UNLN/UNLT without RADESYS (C), where Rust writes ICRS — cost if wrong: checked by Task 6's wcsconv gate.
- Ruling: SkySys stores MJD-OBS only — CARRY TO TASK 6: DATE-OBS formatted from store.mjdobs as C — cost if wrong: missing DATE-OBS.
- Ruling: SplitMap2 treats an Ident CmpMap as holding no WcsMap where C recurses without end (C quirk, recorded); other C-undefined paths (empty Symbol, 80-char comment overflow, NULL annul in TAN constant branch) handled without C's UB — cost if wrong: none in practice.
- Ruling: C quirk kept: IsMapTab1D returns the split Mapping with no table columns when the inverse split gives several grid axes (recorded) — cost if wrong: none.
- Ruling: skyPoleMatchesTheCLibrary compares stored LONPOLE/LATPOLE with C's 15-digit card values to 1e-9; splitmap oracle LONPOLE/PV1_3 to 1e-12 (C card text is not the shortest repr); CRVAL exact — cost if wrong: sub-tolerance differences surface only at Task 6's card gate.
- Ruling: some test expectations worked from C's code by hand (non_zenithal_with_unit_map3, tan2axisWritesCelestialKeywords, the -TAB cases) where no oracle covers the input — cost if wrong: a test encodes a misreading of C.
- Ruling: Rust tests fiducial.rs::invalid_axes_return_none (C reads past array) not translated; sky_pole wcs_nat_pole_fiducial_at_north_pole already translated — cost if wrong: none.

## Task 4

- Ruling: fsetToStore returns FitsPlaneStores (null for C NULL), indexed by version letter (common item 7; C writes letters in order and merges colliding letters) — cost if wrong: mechanical.
- Ruling: per-version FitsStore capacity max(pixel, world axes); C's store->naxis is FitsPlaneStores.naxis — CARRY TO TASKS 6-9: use FitsPlaneStores.naxis where C uses store->naxis; skip versions without CRPIX1/CRVAL1 as C — cost if wrong: wrong axis count in DSS/CLASS.
- Ruling: writeForeign calls fsetToStore and still returns false (so the Task 4 tests can call write) — cost if wrong: none once Task 6 lands.
- Ruling: C quirk 1527 kept (method/class swapped in Warn text) — cost if wrong: one message text.
- Ruling: C quirks kept: offset-sky absolute description letter not checked against Ident letters (merged into one store); AddVersion clears/restores SkyRefIs on the caller's FrameSet — cost if wrong: last-bit differences.
- Ruling: C undefined behaviour not kept: two SkyFrames (C NULL deref) give no description; letters past Z (C reads past frms) give none; OtherAxes table-less IsMapTab1D result (C NULL FitsTable deref) throws needs FitsTable — cost if wrong: none observable.
- Ruling: LogAxis throws needs MathMap for any non-zero CRVAL (C builds the MathMap before testing log-linearity; probe confirms C reaches it, even with TabOK) — cost if wrong: an axis C would refuse throws instead (convention for a reached class).
- Ruling: a failed alternate keeps partial values but no CRPIX; C's FITS-WCS/PC writers skip versions without CRPIX, so C's uninitialised CDELT never reaches a card (resolves Task 2's open point) — cost if wrong: none.
- Ruling: readCrval propagates UnsupportedOperationException from reading the FitsChan copy (C reads it) and maps AstException to null (C clears status) — cost if wrong: write into a FitsChan holding a header Java cannot yet read throws (FITS-PC/IRAF/AIPS until Tasks 7-8; spectral stays).
- Ruling: SkyRefIs restored in finally (C skips restore on error) — cost if wrong: none.
- Ruling: FitsFsetToStoreTest offset-sky primary description compared to 1e-14 not 4 ULP — the FrameSet read from the TAN header already differs from C by 15-20 ULP before FsetToStore (read-side, phase 1) — cost if wrong: hides an FsetToStore error below 1e-14; Task 6's wcsconv offset rows may hit it.

## Task 5

- Ruling: sipIntWorld follows C: Mapping sipIntWorld(map, tol, lonax, latax, s, store, dim, int[] inaxes, double[] crpix, double[] cd, method, cls), null for C's NULL; analysePoly(PolyMap, Mapping[3]) boolean — cost if wrong: mechanical.
- Ruling: store keeps SIP coefficients in sipA/sipB/sipAp/sipBp via FitsStore.setItem (C SetItem); order fields left empty as C stores no orders — CARRY TO TASK 6: WcsFromStore computes A_ORDER etc. from the tables as C does — cost if wrong: Task 6 writes no orders if it reads the fields.
- Ruling: PolyMap.mergeShift ported from C astMergeShift/MergeShifts/ExpandTerm, not Rust's binomial expansion — C term/sum order; WITH_CONSTANT fixture bit-exact — cost if wrong: none.
- Ruling: C quirks avoided (recorded): MergeShift reads astGetNin for nout; AnalysePoly writes an index past its array; SIPIntWorld reads past an array when the non-celestial split is short (Java throws AST__INTER); CTYPE shorter than 8 left unchanged as C — cost if wrong: only on inputs where C is undefined.
- Ruling: ScalePolyInputs zero/bad scale and AnalysePoly non-2x2 throw AST__INTER as C's astError, where Rust returns None — cost if wrong: exception where Rust declines.
- Ruling: SIP_2D oracle CD values compared to 1e-12 relative, not exactly — the oracle's copy of SIPIntWorld fits over an older box (-ubnd..ubnd with tol); current C uses the transformed-corner box with utol; Java differs by 1-3 ULP; all box-independent values (CRPIX, 32 coefficients, samples) exact.
  Task 6's wcsconv sip rows check current C end to end — cost if wrong: a real 1-3 ULP CD difference from current C would surface only in Task 6's gate.
- Ruling: Rust tests binomial_basics and wrong_length_returns_none not translated (Rust-only helpers/checks); sip3dKeepsTheOtherAxis expectations read from C's code — cost if wrong: none.

## Task 6

- Ruling: wcsFromStore(FitsChan, FitsPlaneStores, method, cls) returns boolean (C int) — cost if wrong: mechanical.
- Ruling: fitsFromStore returns false for PC/IRAF/AIPS/AIPS++/CLASS/DSS until Tasks 7-9 (FsetToStore still runs and warns) — cost if wrong: none.
- Ruling: writers use new FitsCardList.setValueAsWriter (C SetValue/SetFits/CnvValue comment rules); read-side FitsSpecTrans keeps putOrAppend — cost if wrong: a translated card's comment may differ from C on read (deferred minor).
- Ruling: FindWcs check replaced by C's 57 templates via FitsMatch (old check missed CD1_1A and misordered multi-wcs-dup) — cost if wrong: none.
- Ruling: C quirks kept: Similar ignores the last non-blank character; SIP *_ORDER without version letter; IMAGFREQ without letter; QV prefix for every axis once one is TPN; DATE-OBS T24:00:00.000 and negative two-digit years — cost if wrong: cards differ from a fixed C.
- Ruling: mjdToDateStr throws AST__INTER when palDjcl fails (C formats uninitialised fields); no MJD-AVG written (only reads set it); an alternate's AstException dropped as C clears status, UnsupportedOperationException propagates — cost if wrong: none observed.
- Ruling: oracle cards compared within 4 ULP (cardsMatchC, as wcsconv) except Sipok exact; runtime-Math licences rtol 1e-13 on named keywords only (splitmap linear/linear_3d CDELT2 5 ULP from Math.cos vs libm amplified by FitLine; offset-SkyRef PC ~1e-16 from atan2), traced with lldb and probes — cost if wrong: hides a port error below 1e-13 in those keywords.
- Ruling: Review Focus 4 corrected to C: new cards go after the last FindWcs card (or at the end when none), not before the current card; cardsAreWrittenBeforeTheCurrentCard asserts C's placement (probe p1) — cost if wrong: none; plan text was wrong.
- Ruling: everyInScopeHeaderRoundTrips lists 12 headers with C's own round-trip outcome (probe p3: write 0 for CHIPASS_Equ/car6/tsc; transforms differ car1/car2; needs MathMap offset-sky*, offset-sref, skyref*) — cost if wrong: none.
- Ruling: Task 4's offset-sky ULP concern is runtime Math (identical MatrixMaps, 1-ULP transforms), not a read-side error; it does not reach wcsconv (those rows need MathMap) — cost if wrong: an undetected ~1e-16 read-side error.
- Ruling: FitsWcsWriteTest written after the implementation from probe values (never seen failing); Task 1's foreignFrameSetWriteFailsForNow replaced by C's result — cost if wrong: test-first evidence missing for that class; the oracle tests did fail first.

## Task 7

- Ruling: pcFromStore/irafFromStore(FitsChan, FitsPlaneStores, method, cls) → boolean — cost if wrong: mechanical.
- Ruling: SpecTrans PC/IRAF translations replace header values as C does (RADECSYS beats RADESYS; probed); Rust tests keeps_radesys_when_both_present and rename_keyword_no_op_when_destination_already_present translated to C's result — cost if wrong: none.
- Ruling: C quirks kept: translated cards take CardComm(this) (first unconsumed card's comment at read entry); CmXXX letter is A at the lowest m present — cost if wrong: differs from a fixed C.
- Ruling: C undefined behaviour in PCFromStore alternates (element past primary matrix, RADECSYS against none, unset projection parameters) resolved as differs / AST__BAD — cost if wrong: only for such FrameSets.
- Ruling: FitsStore.lonLatSpecAxes takes FitsPlaneStores.naxis (C store->naxis); empty-version stores passed as empty FitsStore — cost if wrong: none.
- Ruling: enc_pc/enc_iraf read_from_cards tests not translated (dead code per plan); translate_projp_retains_source_cards translated now — cost if wrong: none.
- Ruling: everyInScopeHeaderRoundTrips: 8 WRITE_FAILS and scp DIFFERS added to NOT_ROUND_TRIPPED, each C's outcome (probe rt) — cost if wrong: none.

## Task 8

- Ruling: aipsFromStore/aipsppFromStore(FitsChan, FitsPlaneStores, method, cls) → boolean — cost if wrong: mechanical.
- Ruling: C's write flow kept: after a missing CRPIX/CRVAL/CTYPE or -TAB the remaining cards are still written and the result is false (FixNew removes them) — cost if wrong: none.
- Ruling: AIPS spectral string checks ported in full (no SpecFrame needed; CTYPE FREQ reaches the store from a plain Frame) — cost if wrong: none.
- Ruling: C quirks kept: AIPS SIN check resets ok; AIPS++ PROJPm not read back (recorded) — cost if wrong: differs from a fixed C.
- Ruling: Rust write_aipspp_with_lonpole_latpole translated to C's result (LONPOLE 180 written; probe p2) — cost if wrong: none.
- Ruling: read_aips_spectral_felo_geo not translated (SpecFrame); FitsChanErrorsTest write_enc checks implemented (mutation-checked); sky_systems_through_five_encodings left for Task 10 — cost if wrong: none.
- Ruling: TranGridOracleTest tan grids run with positions compared within ulp(pi/2) rad, hash skipped, traced to runtime atan2/sin/cos — cost if wrong: a port error below 2.2e-16 rad or at an unsampled grid position goes unseen.

## Task 9

- Ruling: FITS-CLASS write refuses every FrameSet of this library (no SPECSYS without a SpecFrame); throws needs SpecFrame where C's checks would pass — cost if wrong: none.
- Ruling: DSS PLTRAS/PLTDECS in 7 encodings-oracle cases compared to the effect of 4 ULP of CRVAL — traced (lldb + replays) to atan2 at identical arguments (forward SphMap; eraFk5hz eraC2s with Epoch), libm correctly rounded, Java ~0.6 ULP off; the fields hold 3600×CRVAL's fractional part, amplifying the last bit — cost if wrong: a port error within 4 ULP of CRVAL in those fields goes unseen.
- Ruling: PLTDECSN negative only when its first word is exactly "-" (C; Rust: any leading -); PLTSCALE and PPO1/2/4/5 marked used (C; Rust leaves them) — recorded — cost if wrong: none.
- Ruling: C quirk kept: FITS-CLASS writer with one-pixel celestial axes transforms three coordinates whatever the FrameSet, so a four-pixel-axis FrameSet fails AST__NCPIN (probed) — cost if wrong: differs from a fixed C.

## Task 10

- Ruling: alt_axes_offset{,_none} CDELT2/CDELT2A within 1e-13 relative; azel/supergalactic CDELTn and PCi_j within 1e-14 abs + 1e-13 rel — each traced to atan2/sin/cos at identical argument bits (lldb + replays) — cost if wrong: a port error of that size in those cards goes unseen; Galactic PC1_2 margin only ~4.6x (2.8e-15 vs 1.3e-14).
- Ruling: 13 rust-tests.txt additions for never-listed files: test_fitschan_wcs_write.rs 9 (SpecFrame, GrismMap, SplineMap, MathMap), test_fitschan_encodings.rs 4 (SpecFrame, FITS-CLASS read, MathMap) — reasons measured from the exceptions — cost if wrong: none; the list was incomplete.
- Ruling: roundtrip helper is C's (testfitschan.c, with its pixel round-trip fallback), not Rust's (recorded); sky_systems_through_five_encodings asserts C's exact outcome per System/encoding; translated tests assert C's full card lists where Rust checked presence; C's Galactic block translated with Supergalactic, including C's System-after-remap (conversion held twice) — cost if wrong: none.
