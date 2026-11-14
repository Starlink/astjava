# Phase 2: rulings

These are the decisions made while executing the phase 2 plan ([`2026-11-13-pure-java-ast-phase2-simplify.md`](../superpowers/plans/2026-11-13-pure-java-ast-phase2-simplify.md)) where the plan was wrong, unclear or silent.
They are copied from the execution ledger, in the order they were made within each task.
Each gives what was decided, why, and what it costs if wrong.
A later ruling can reverse an earlier one; the final review section records the reversals made in the fix pass.
The phase report is [`2026-11-13-phase2-report.md`](2026-11-13-phase2-report.md).

## Before Task 1

- T4→T6: MatrixMap.replaceWithSimpler produced in T4; T6 lists no consumer of it explicitly.
  Ruling: T6 uses it only if the Rust cmp_map_merge does — no conflict.

## Task 1

- Ruling: the reason of a failing in-scope row is computed by the test ("needs simplification"), as WcsconvTest does, not by Deferred.newReason, which treats any "needs " reason as a missing class — cost if wrong: none beyond a helper change.
- Ruling: Rust's equal() check (astEqual) is not made; astEqual is not ported, and the 4-ULP dump comparison covers structure — cost if wrong: a difference equal() sees but the dump does not (base Frame written only through Frm) goes unnoticed.
- Ruling: transforms are checked against C's oracle outputs for the .map sections (plan), not Rust's 10 synthetic points against the port's transform of the .simp — stricter and C-sourced — cost if wrong: none.

## Task 2

- Ruling: the engine follows C's data model, not Rust's: a MergeList entry is the shared Mapping itself plus the absolute Invert value to apply it with (C invert_list); the Mapping's own Invert is never changed except temporarily during reconstruct, as C does.
  Rust's forward-normalised clones, orig_inv, TagTree/origin/merge_tag are not ported.
  Translation rule for later tasks: Rust `entry.0.nin()/nout()` on a list entry → Java `createdNin()/createdNout()`; Rust results with is_inverted() → Java stores the object with list invert = effInv XOR merged.getInvert() (MergeList.replacePairWith callers pass that). — why: spec rule (Rust structure from ownership → take C); C's IsSimp stamps fall out of sharing (cap_cmpmap_nested_tag matched with the engine alone) — cost if wrong: per-class merges ported from Rust read the wrong orientation; the regression gate catches it per row.
- Ruling: the recursion guard compares identity, then the native text without IsSimp lines (astEqual is not ported); texts are made only when the stack is non-empty — cost if wrong: two Mappings C finds astEqual but whose texts differ in rounding are not caught, so a recursion C stops would go on (StackOverflowError); or text comparison costs time on deep simplifications.
- Ruling: Simplifier.defaultMapMerge ports Rust default_map_merge without its parallel linear fallback (WinMap extract_linear_params/compose_linear), which Task 3 adds with the WinMap helpers — cost if wrong: none if Task 3 adds it.
- Ruling: implementation was written before its tests; RED was then shown by moving the engine files aside and stashing Mapping/CmpMap/UnitMap (84 "cannot find symbol"), GREEN after restoring — cost if wrong: none; noted as a process deviation.
- Ruling: Rust tests that need ZoomMap/ShiftMap/WinMap merges (merge_loop_strips_unitmaps_from_series, merge_loop_merges_linear_parallel, simplify_mapping_non_cmpmap_delegates, base_simplify_reaches_a_classes_own_map_merge, unit_scale_winmap_in_a_parallel_branch_narrows_to_a_shiftmap) move to Task 3; aDeepAlternatingSeriesBecomesAUnitMap is @Disabled until Task 3.

## Task 3

- Ruling (supersedes the plan's hooks and Task 2's defaultMapMerge ruling): each class's MapMerge is ported from C, not from Rust's map_merge/try_merge.
  Java shares C's data model (Task 2 ruling), and Rust's per-class merges are shaped by its owned-value model (clone_pair, forward-normalised entries, `simplifies_to_different` type_id test standing in for C's pointer test `smc0 != mc[0]`), which the spec says to take from C.
  Rust remains the source of tests, quirk notes and the audit checklists (docs/audits/mapmerge/*.md), which map C steps to Rust lines.
  Mapping.mapMerge's base returns -1 as C's mapping.c MapMerge does; Mapping.tryMerge and Simplifier.defaultMapMerge are removed (Rust-only pairwise layer).
  Gate still 15+58 rows; SimplifierTest green — cost if wrong: where Rust deliberately departs from C to fix a C defect, Java follows C; the regression gate is against C, so that is the intended result.
- Ruling: implementation came before tests; RED shown afterwards by forcing the three mapMerge bodies to -1 (29 failures) — cost if wrong: none; process deviation.
- Ruling: SimplifierTest.anIdentMappingIsNotMerged rewritten: C's astMapMerge_ guards only the nominee (mapping.c:24256) and zoommap.c MapMerge absorbs a neighbouring UnitMap by class (750-797); added aNeighbourWithIdentIsMergedAsCDoes — cost if wrong: none, follows C.
- Ruling: FrameSetVariantsTest.selectingAVariantOfAFrameOnTheRootNode compares two Java routes; getMapping now simplifies (shift merged into the WinMap), measured difference 4.4e-16, bound 1e-12 — not a C value, so no C tolerance is widened — cost if wrong: hides a real routeing error smaller than 1e-12.
- Ruling: in the distribute branch C reuses `nin` for the first component's width (winmap.c:1331) and the final isshift step reads it (1833/1869); ported as C — cost if wrong: a narrower ShiftMap in both C and Java on a path that needs both halves to come back unsimplified.
- Ruling: WinDiagMat with a unit MatrixMap returns the WinMap clone stored forward (winmap.c:2699); ported as C — cost if wrong: a WinMap applied inverted beside a unit MatrixMap loses its inversion, as in C.
- Ruling: Invert attributes are restored to the exact set/unset state, last saved first (Mapping.swapInvert/restoreInvert), where C restores with astSetInvert(old) first saved first — cost if wrong: none for dumps; differs from C only when one object appears twice in the pair.
- Ruling: the distribute branch's series CmpMaps use new CmpMap (components marked shared) where C annuls them afterwards — cost if wrong: none observable (WinMap has no immutable-once-shared attributes).
- Ruling: C's in-place sub-list astMapMerge after a swap (winmap.c:1562,1572) is MergeList.mergeSubrange (copy suffix, dispatch, splice back) — cost if wrong: none unless a sub-merge lengthens the list.
- Ruling: C class-name tests ported as exact-class checks, astIsA as instanceof — cost if wrong: user subclasses treated as C treats subclasses.
- Ruling (controller, process): from Task 3 on, each porting task was implemented by one fresh subagent with a full brief (task brief + ledger rulings + earlier commits as pattern), and I verified each result (full mvn test, deferred-list diff shows removals only, C probes re-run where defects were claimed) before ledgering it, as phase 1 did from its Task 5 — each task ports 300-1,500 lines of C, which inline reading would push through repeated compactions — cost if wrong: implementer rulings reach the ledger second-hand.

## Task 4

- Ruling: implementation before tests; RED shown afterwards by forcing MatrixMap.mapMerge to -1 (26 failures); the two SimplifyTrnndOracleTest cases pass with or without the merge (WinMap's swap meets AST__TRNND first) — cost if wrong: they guard the result, not the new code.
- Ruling: C quirk kept: a full MatrixMap with zero off-diagonals simplifies to a forward diagonal MatrixMap even when applied inverted (matrixmap.c:1896-1925), changing its transform (breaks Review Focus 1 for uncompressed full matrices from dumps or with bad elements); recorded in docs/rust-c-differences.md as a C quirk — cost if wrong: such a Mapping changes transform on simplify, as in C; worth reporting upstream.
- Ruling: simplify_cross_type_dimension_mismatch_no_merge became a check that CmpMap(zoom2, shift3, series) throws AST__INNCO (no tryMerge exists) — cost if wrong: none.
- Ruling: MatrixMap CanSwap's astError(AST__RDERR) dimension checks thrown with C's text, including C's missing space ("followingMatrixMap") — cost if wrong: none; unreachable from a valid series list.
- Ruling: MatWin2 with a non-square diagonal MatrixMap ported as C (C reads newscales past nrow), so Java would throw ArrayIndexOutOfBounds where C reads out of bounds — cost if wrong: an unchecked exception for a non-square diagonal MatrixMap beside a WinMap (no fixture reaches it).
  Candidate C bug.
- Ruling: MatPermSwap expands then compresses the shared MatrixMap in place, as C does, which can rewrite the stored form of a dump-loaded matrix — cost if wrong: none expected; gate checks dumps.

## Task 5

- Ruling: where C crashes or reads out of bounds (defects 2, 3) Java gives a defined result (unchanged Mapping; -1 as SAFE_PERMVAL) — cost if wrong: Java simplifies where C's behaviour is undefined.
- Ruling: ConstantList.reference uses constantFor (bad past the stored constants) where C indexes permmap->constant directly — cost if wrong: none observed.
- Ruling: PermSplit not carried to the merged PermMap (C astPermMap(..., "")), unlike Rust — cost if wrong: as C.
- Ruling: SimplifierTest.simplifyPermmapDifferentPermsNoMerge asserts the swap PermMap C gives (ngone=1 makes it simpler, permmap.c:1352,1372), stricter than Rust's "not a UnitMap" — cost if wrong: none.

## Task 6

- Ruling: Java enlarges the constants array where C overflows (defect 1) and leaves C's uninitialised entries 0 (defect 2); defect 3 ported as C with a test pinning C's values — cost if wrong: Java simplifies where C is undefined; that shape's inverse changes on simplify as in C.
- Ruling: once the PermMap is found not square or a first check fails, the swap's remaining checks are skipped (C runs them, can only fail again, and can read past an array) — cost if wrong: none.
- Ruling: the swap reads the PermMap's stored permutations whatever Invert the list applies (astGetInPerm/astGetOutPerm), and component shapes ignore the CmpMap's list Invert, as C — cost if wrong: a wrong swap for an inverted PermMap/CmpMap reaching this code (PermMap's own merge turns an inverted PermMap forward first; inverted CmpMaps are rebuilt).
- Ruling: CombineMaps' astClearInvert and the swap's leftover set Invert become exact restores via swapInvert/restoreInvert; temporary CmpMaps use the public constructor (components marked shared) — cost if wrong: none observable.

## Task 7

- Ruling (amends Task 1's transform-check ruling): where a row's reference is another file with an oracle section of the same direction and identical inputs, the gate compares the simplified Mapping with C's recorded outputs for the reference (C's simplified Mapping), not for the .map; C's own simplification changes outputs outside the domain its parts assume (SphMap pairs on unnormalised angles/non-unit vectors, WcsMap pairs on points the projection cannot transform).
  Needed for 6 rows whose dumps already matched C — cost if wrong: on those rows the gate checks "transforms as C's simplified Mapping does" rather than "transform unchanged by simplification" (still C-sourced).
- Ruling: wcsmap.c PermGet is byte-identical to winmap.c's, so WinMapMerge.permGet is reused; CanSwap casts to WcsMap only when the other member is an exact PermMap (C would read any Mapping as a WcsMap) — cost if wrong: none (only a user subclass reaches it).

## Task 8

- Ruling (amends Task 7's): when the reference has no section with identical inputs, the gate uses the reference's first section of the same direction, inputs and outputs both (poly_inverse_cancel, poly_fitted_inverse_cancel: the .map inverse sections take forward outputs as inputs; C's cancellation changes outputs where the iterative inverse does not converge) — cost if wrong: on such rows the gate checks C's simplified Mapping on C's reference inputs, not the .map's (still C data).
- Ruling: Java restores a cancelled nominee PolyMap's Invert where C leaves it at the list value (polymap.c:4004-4006, confirmed with scratchpad/cfuzz/polyprobe.c); recorded in docs/rust-c-differences.md; test aCancelledPolymapKeepsItsInvert — cost if wrong: a caller holding that PolyMap sees a different Invert from C.
- Ruling: PolyMap astEqual (polymap.c Equal) ported as package-private equalTo for the cancellation test; the linear replacement's temporary CmpMap uses the public constructor (components marked shared) — cost if wrong: none observable.
- Ruling: a cancellation leaves two forward UnitMaps and returns the lower index, as C (not Rust's single entry) — cost if wrong: none; a later pass removes the extra UnitMap.

## Task 9

- Ruling: TDBTOTCB/TCBTOTDB cancel when their 2 stored values agree, where C also compares an uninitialised third (timemap.c:2327-2335; found by reading, not observed; C cancels in the cases probed); recorded in docs/rust-c-differences.md — cost if wrong: Java cancels a pair C (with differing garbage) would keep; transforms identical, only the dump differs.
- Ruling: the step list is rebuilt per pass rather than compacted in place (PMD AvoidArrayLoops/AvoidReassigningLoopVariables) — same result as C — cost if wrong: none.
- Ruling: SlaMap MapMerge clears the cached palMappa parameters of shared SlaMaps it gathers, as C frees cvtextra — cost if wrong: none observable.

## Task 10

- Ruling: the new tests passed before any change (Tasks 2-9 supplied what they need); their ability to fail was shown by a temporary no-op simplify (integrity test fails, 6.2 MB) — cost if wrong: none.
- Ruling: FitsChanCursorOracleTest iwc_on_alt relabelled `runtime Math:` (M2 differs by 1 ULP from Math.sin(2°−π/2) vs macOS libm; confirmed with a C replica scratchpad/trig/rot.c; FMA ruled out with a -ffp-contract=off build) and not reported when it matches C, the oracle-rows convention — cost if wrong: a real difference in that one case goes unreported.
- Ruling: native wcsconv read dumps are not compared with C in the repo (expected outputs are FITS cards); a scratch comparison matched 44 of 48 readable rows byte for byte; skyref, skyref-alt, tidy-pole differ in last bits of MatrixMap elements and azel in ObsLat/ObsAlt, cause not found — cost if wrong: IsSimp-placement or last-bit regressions in FITS reads are caught only by FitsChanReadChainOracleTest/FitsChanCursorOracleTest.
- Ruling: SetVariant restores map0's Invert to its exact earlier state where C always calls astSetInvert (Task 3 restore ruling) — cost if wrong: none observable apart from testInvert.

## Task 11

- Ruling: no commit for Task 11 — the plan's commit step has nothing to commit since every list was already current — cost if wrong: none.

## Final review and fix pass

- Ruling: where C's MapMerge would read or write past an array because the list's Mappings do not fit as its code assumes (left by a C defect elsewhere, or a non-square diagonal MatrixMap), Java declines that merge step, keeping the transform — C has no defined result there; declining keeps what the Mapping computes — cost if wrong: Java returns a less simplified Mapping where some C run might crash or return garbage.
- Ruling: C's WinDiagMat defect (unit MatrixMap returns the WinMap in series and parallel; non-square diagonal treated as square; winmap.c:2697-2699), which changes Nin/Nout or the transform, is followed as the spec requires and documented in docs/rust-c-differences.md and the report; whether to depart from C is left to the author — cost if wrong: users meet a shape-changing simplification with trivial triggers until C is fixed upstream or the policy changes.
