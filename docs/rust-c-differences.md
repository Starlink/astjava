# Differences between the C library and the Rust port

The Java port takes its code from the Rust port (`reference/starlink-ast-rs`) and its behaviour from the C library (`reference/starlink-ast`, pinned at `3d3f255f`).
Porting class by class, with the C source as the final reference, turns up places where the Rust port does not behave as C does.
This document records each one, so that the Rust port can be brought into line with C, and so that the Java and Rust ports stay in agreement.

Each entry says what C does, what Rust does, and what Java does.
"Java" is the behaviour of this repository, which follows C unless the entry says otherwise.
Entries where C itself is at fault are marked **C quirk**; those belong in the Rust repository's `docs/issues/c-library-quirks.md` as well.

The entries are grouped by area, in the order the Java classes were ported.

## Errors and formatting

### `encode_float` documentation says a NaN or an infinity gives `None`

- C: `CheckZero` passes "INF" or "NAN" to `RoundFString`, which finds no non-zero digit and writes the simplest zero, so `EncodeFloat` gives `0.0` (`fitschan.c:6222`, `fitschan.c:26656`).
- Rust: the code does the same as C, but the doc comment of `fitschan/format.rs` `encode_float` says it returns `None` for an infinity or a NaN.
- Java: as C; `FitsFormat.encodeFloat` documents it.

## Channel and native serialisation

### KeyMap entries are dumped in signed rather than unsigned key order

- C: `DumpCmp` compares keys with `strcmp`, which orders bytes as unsigned characters.
- Rust: the dump uses `key_cmp`, which compares signed bytes, so keys containing bytes above 127 are dumped in another order.
- Java: as C.

### KeyMap iteration order with `SortBy` unset

- C: `astMapKey` returns keys in hash-table order, and the dumped `MapSz` depends on the history of insertions.
- Rust: keys come back in `KeyCmp` order and `MapSz` is computed from the key set.
- Java: as Rust; dumps still match C.

### `astWarnings` stores each warning with comment `" "`

- C: each warning text is stored with the comment `" "`, which `DumpEntry` writes as a `Com` line.
- Rust: passes no comment, on the reasoning that the dump layer treats it as absent, which `keymap.c` `DumpEntry` does not.
- Java: as C.

### KeyMap string-to-float conversion

- C: `sscanf("%f")`, which rounds the decimal directly to `float`.
- Rust: parses as `f64` and then narrows to `f32`, which can differ in the last bit.
- Java: as C.

### A failed load at the top level loses C's context message

- C: adds "Error while reading a X from a Channel." to the error.
- Rust: omits it.
- Java: as C.

### Full dumps omit `RefCnt` and `Nobj`

- C: a dump with `Full` above zero writes the reference count and object count.
- Rust: does not write them.
- Java: as Rust, since neither count exists in Java; this is a difference from C in both ports.

### A string where a dump expects a number

- C: `ReadDouble`, `ReadInt` and `ReadInt64` scan the text of the value with `sscanf`, which must read all of it, and raise AST__BADIN when it cannot; `ReadDouble` also raises it for a value that is not finite (`channel.c:3049-3063`, `channel.c:3168-3173`).
  The loader fails with the stream read only to the end of its class's part, so the next read starts there.
- Rust: `serial/node.rs` `get_double` and `get_int` give the default for a string and read a NaN or an infinity as it is (`test_channel_read_oracle.rs` excludes `string_for_double` and `string_for_int`).
- Java: as C.
  An integer beyond the range of an `int` (or of a `long`, for `ReadInt64`) is AST__BADIN too, where the result of C's `sscanf` is undefined (the pinned library on macOS keeps the low 32 bits).

## Mappings

### An explicit `Nout = 0` in a Mapping dump

- C: `astLoadMapping` keeps it as 0.
- Rust: reads it as Nin.
- Java: as C.

### Argument-length errors from the transform functions

- C: an array shorter than `npoint` is AST__DIMIN (`ValidateMapping`, `Transform`, `TranN`).
- Rust: reports AST__NPTIN when the arrays have different lengths.
- Java: as C.

### `tranN` and `tranP` with Report set

- C: every transform function reports the points it transforms.
- Rust: only `tran1` and `tran2` report.
- Java: as C.

### Infinite input coordinates

- C: tests only for `AST__BAD`, so an infinite input propagates through ZoomMap, ShiftMap and the other arithmetic Mappings.
- Rust: treats any non-finite input as bad.
- Java: as Rust, by the plan's rule for bad values; this is a difference from C in both ports, for infinite inputs only.

### PermMap with a null forward permutation and more outputs than inputs

- C: `PERMVAL` gives the surplus outputs the first constant when there are constants.
- Rust: gives them AST__BAD.
- Java: as C.

### Cancelling TDBTOTCB with TCBTOTDB in a TimeMap

- **C quirk** (found by reading, not observed): `MapMerge` compares three arguments for TDBTOTCB and TCBTOTDB (`timemap.c:2327-2335`), which store two (`timemap.c:1202-1219`) and have only two copied into rows from `astMalloc` (`timemap.c:2180-2182`), so the third comparison reads uninitialised memory or a value another conversion left in the row.
  The pinned library still cancels the pair in the cases tried, adjacent or made adjacent by earlier removals, since `astSimplify` calls `MapMerge` again with fresh arrays.
- Rust: not compared.
- Java: compares the two stored arguments.

### Cancelling a PolyMap with its inverse

- **C quirk**: when `MapMerge` cancels a nominated PolyMap with its inverse it annuls the PolyMap before restoring its Invert attribute (`polymap.c:4004-4006`), so the PolyMap is left with the value the list applied it with: simplifying the inverse of a series CmpMap of a PolyMap and an inverted copy of it gives a UnitMap and leaves the copy with Invert unset.
  Confirmed with the pinned library.
- Rust: not compared.
- Java: restores the PolyMap's Invert attribute.

### Cancelling WcsMaps whose projection parameters differ

- **C quirk**: `CanMerge` compares the first WcsMap's number of parameters with itself (`wcsmap.c:914`), so only the parameters the first WcsMap holds are compared: an AZP WcsMap with no parameters followed by the inverse of an AZP WcsMap with `PV2_1 = 0.5` simplifies to a UnitMap, although the pair maps (0.1, 1.2) to (0.1, 1.1914111091742374); the other order does not merge.
  Confirmed with the pinned library.
- Rust: not compared.
- Java: as C.

### Swapping a PermMap past a WcsMap

- **C quirk**: `WcsPerm` keeps the PermMap through `astClone` but stores its own Invert attribute rather than the value the list applies it with (`wcsmap.c:4726` and `4744`): the inverse of a series CmpMap of a PermMap from 2 to 3 axes (with a constant 5) and `WcsMap(3, TAN, 1, 2)`, which has 3 inputs and 2 outputs, simplifies to a series CmpMap of the inverted WcsMap and the PermMap from 2 to 3 axes, which has 2 inputs and 3 outputs.
  Confirmed with the pinned library.
- Rust: not compared.
- Java: as C.

### Swapping a parallel CmpMap with a PermMap whose blocks are constant

- **C quirk**: when CmpMap's `MapMerge` swaps a parallel CmpMap with the PermMap before it and both of the PermMap's blocks give constants, three things go wrong (all confirmed with the pinned library):
  - It writes past the end of the constants array (`cmpmap.c:2243`, allocated at `2176`): a series of `PermMap(3, inperm = [-1, -2, -3], 3, outperm = [-4, -5, -6], 6 constants)` and a parallel CmpMap of a TAN WcsMap and `ZoomMap(1, 3)` gives a heap-buffer-overflow.
  - With fewer unit axes than the second block's outputs, the overlapping writes at `cmpmap.c:2183-2201` and `2224-2236` leave entries of the new input permutation uninitialised, which `astStore` then reads (the same PermMap before a parallel CmpMap of `ZoomMap(1, 3)` and a TAN WcsMap).
  - Only the first component is replaced by a UnitMap (`cmpmap.c:2255-2259`), so the second component's inverse is applied to the PermMap's inverse constants: with `PermMap(4, [-1 .. -4], 4, [-5 .. -8], constants 0.1 .. 0.8)` before a parallel CmpMap of TAN and SIN WcsMaps, the inverse of (0.01, 0.02, 0.03, 0.04) is (0.1, 0.2, 0.3, 0.4) before simplification and (2.677945044588987, 1.3452829208967654, 0.3, 0.4) after.
- Rust: swaps by its own pairwise code; not compared.
- Java: as C for the changed inverse; makes room for every constant where C overflows, and leaves the uninitialised entries 0.

### Simplifying a PermMap with no forward permutation and constants

- **C quirk**: `MapMerge` reads each PermMap's forward permutation with the number of outputs as its limit (`maxperm = astGetNout( map )`, `permmap.c:1030` and `1222`), where `Transform` uses the number of inputs, so simplifying can change the transformation.
  A series CmpMap of `PermMap(nin = 1, inperm = [-1], nout = 2, outperm = NULL, constant = [9])` and a PermMap swapping two axes maps 5 to (9, 5); simplified, it is a PermMap with `Out1 = 0` mapping 5 to (bad, 5).
  Confirmed with the pinned library and with PR #104.
- Rust: merges PermMaps pair by pair, not through C's `MapMerge`; not compared.
- Java: as C.

### Simplifying a PermMap whose stored permutation is null

- **C quirk**: `MapMerge` compares the permutations it rebuilds with `permmap->inperm` and `permmap->outperm` without checking for a null array (`permmap.c:1389` and `1398`), so simplifying `PermMap(nin = 2, inperm = NULL, nout = 1, outperm = [-1], constant = [9])` dereferences a null pointer (SEGV in the pinned library and in PR #104).
- Rust: merges PermMaps pair by pair, not through C's `MapMerge`; not compared.
- Java: reads a null array as `MapMerge` reads one elsewhere (through `PERMVAL`), so the PermMap is found already simple and returned as it is.

### Simplifying an inverted PermMap with more inputs than outputs

- **C quirk**: `NullPerm` loops to `astGetNout( this )` and `astGetNin( this )` in the PermMap's current orientation over the stored arrays (`permmap.c:1772-1774` and `1794`), so for an inverted PermMap whose stored inputs outnumber its outputs it reads past the end of `outperm`.
  Simplifying an inverted series CmpMap of an inverted `PermMap(inperm = [1, 2, 0], outperm = [1, 2])` and a 3-axis ZoomMap gives a heap-buffer-overflow in the pinned library and in PR #104.
- Rust: merges PermMaps pair by pair, not through C's `MapMerge`; not compared.
- Java: treats an element beyond the stored array as -1, as C's own `SAFE_PERMVAL` does, which gives a simplification that keeps the transformation.

### MatrixMap rotation and multiplication

- C: `astMtrRot` and `astMtrMult` rotate the inverse matrix alongside the forward one and re-compress (`ExpandMatrix`, `SMtrMult`).
- Rust: rebuilds the inverse, which can differ in the last bits of the inverse coefficients.
- Java: as C.

### A unit or non-square diagonal MatrixMap beside a WinMap

- **C quirk**: `WinDiagMat` returns the WinMap itself for a unit MatrixMap, in series and in parallel alike (`winmap.c:2697-2699`), and treats a non-square diagonal MatrixMap as square, so simplifying changes the Mapping's numbers of axes or its transformation; confirmed with the pinned library:
  - a parallel CmpMap of `ShiftMap([3])` and a 2-axis unit MatrixMap, with 3 inputs and outputs, simplifies to the 1-axis ShiftMap;
  - the inverse of a series CmpMap of a 2-axis unit MatrixMap and a WinMap simplifies to the WinMap applied forward, so (1, 2) maps to (7, 7) instead of (-2, 0.333);
  - a diagonal `MatrixMap(2, 3)` beside a WinMap of 3 axes gives a WinMap of 3 inputs for a Mapping of 2.
  The Mappings left by these changes can make C's later merges read or write past their arrays (`winmap.c:1352`, `2721`, WinWin, WinPerm, `MatWin2`, `cmpmap.c:1895`, `permmap.c`).
- Rust: not compared.
- Java: as C for the changes themselves; where C's later merges would read or write past an array, because the Mappings of the list do not fit together as C's code assumes, Java does not make that merge, so the transformation is kept.

### A non-square diagonal MatrixMap merged with a WinMap by the MatrixMap

- **C quirk**: `MatWin2` builds a WinMap of the MatrixMap's number of rows or columns to replace the pair and, with the WinMap first, reads the scale terms past that number (found by reading).
- Rust: not compared.
- Java: does not merge a MatrixMap with a WinMap unless the MatrixMap is square and as wide as the WinMap.

### Simplifying a full MatrixMap whose off-diagonal elements are zero

- **C quirk**: `MapMerge` replaces a full MatrixMap whose off-diagonal elements are all zero with a diagonal MatrixMap built from the stored forward diagonal and applied forward, even when the list applies the MatrixMap inverted (`matrixmap.c:1896-1925`), so simplifying changes its transformation.
  Only a full matrix that was never compressed reaches this, such as one read from a dump or one with a bad element: a MatrixMap read with `Invert = 1` and diagonal 2 and 4 maps (1, 1) to (0.5, 0.25) before simplification and to (2, 4) after.
- Rust: as C.
- Java: as C.

### Array arguments longer than needed

- C and jniast: accept them and use the leading elements (WinMap corners, MatrixMap matrices, PermMap permutations).
- Rust: rejects them.
- Java: as C.

### A WinMap with no axes

- C: can be created, and raises AST__BADWM when it transforms.
- Rust: refuses it at construction.
- Java: as C.

### A CmpMap dump with a missing or non-Mapping component

- C: leaves a NULL component.
- Rust: raises AST__LDERR.
- Java: as Rust.

### `astMapBox` when no good point is found

- C: `SpecialBounds` returns `slbnd == BAD || subnd == BAD`, so the bounds are refined and AST__MBBNF is raised.
- Rust: inverts that test, returning bad bounds with no error.
- Java: as C.

### `GlobalBounds` carries the bad flag between searches

- C: the `bad` flag set in the minimum search carries into the maximum search.
- Rust: resets it.
- Java: as C.

### CmpMap `MapSplit`

- C: `MapSplit0` over `astMapList`, then `MapSplit1`/`MapSplit2` applied to every component, with each component temporarily given its recorded Invert (left set afterwards).
- Rust: a different decomposition, which gives a different structure for nested compounds.
- Java: as C.

### WcsMap `MapSplit`

- C: builds a fresh WcsMap and copies the projection parameters and Invert, so LonCheck, FITSProj and TPNTan are not copied.
- Rust: copies the whole WcsMap.
- Java: as C.

### Clearing `ProjP(m)` on a WcsMap

- **C quirk**: `ClearAttrib` tests for "prpjp" instead of "projp" (`wcsmap.c:1228`), so clearing ProjP raises AST__BADAT.
- Rust: clears it.
- Java: as C.

### WcsMap with unusable projection parameters

- **C quirk**: reports the error only on the first transformation.
- Rust: reports it on every transformation.
- Java: as Rust.

### PolyMap with all-zero coefficients

- C: the per-output coefficient arrays exist, so the transformation is defined and the IterInverse default follows from it.
- Rust: treats all-zero coefficients as undefined.
- Java: as C.

### Values that are not finite in a PolyMap

- C: `astTransform` replaces values that are not finite in its result with AST__BAD only when the environment variable `AST_REPLACE_NAN` is set (`mapping.c:24815`, `pointset.c:1896`); by default a NaN or an infinity a polynomial makes, including one inside `IterInverse`, is kept.
- Rust: `mappings/polymap.rs` writes a NaN output of a polynomial as bad, and replaces the values that are not finite in each set of values inside the iterative inverse with bad (`substitute_nan_to_bad_in_place`), as C does with `AST_REPLACE_NAN=1`.
- Java: as Rust; both ports differ from C's default here.

### PolyMap `StoreArrays` caches

- C: keeps the `lintrunc` and Jacobian caches when the arrays are stored again.
- Rust: clears them.
- Java: as C.

### PolyMap with no inputs or no outputs

- C: `astInitPolyMap` accepts nin = 0 or nout = 0.
- Rust: rejects them (`polymap_nin_zero_rejected`, `polymap_nout_zero_rejected`).
- Java: as C.

### PolyTran fitting

- C: fits with cminpack's `lmder` through `lmder1`, on a grid whose first axis varies slowest, scaling each pair of columns by one joint RMS, with C's initial guess and running unscaling factors, and forming the Jacobian only once (`init_jac`).
- Rust: uses the `levenberg_marquardt` crate, so fitted coefficients can differ from C's.
- Java: as C.

### SlaMap load errors

- C: AST__BADIN with C's messages.
- Rust: AST__SLAIN.
- Java: as C.

### SlaMap `J2000H` and `HJ2000` with a bad coordinate

- **C quirk**: `J2000H` has no bad-value check, so AST__BAD in gives a finite value out.
- Rust: makes the point bad.
- Java: as Rust.

## Units

Rust's `src/units/` departs from C's `unit.c` in the following ways; Java follows C in each.

- Known units: C searches the most recently defined first, so "year(s)" matches "a"; Rust searches first-defined first and matches "yr".
- Constants: C compares them with `astEQUAL`; Rust uses a relative tolerance of 1e-9.
- `CmpTree`: C returns the swapped comparison when products differ; Rust returns the unswapped one.
- Function names: C accepts only "sqrt(" or "SQRT(" and so on; Rust accepts any case.
- Numbers: C reads them with `sscanf`; Rust has its own reader.
- pi and e: C keeps them as their own node types; Rust turns them into plain constants.
- Complicating the tree: C does it at every level of formatting; Rust does it once.
- Equal cleaned strings: Rust returns a UnitMap at once; C has no such shortcut.
- Unparsable units: C's `astUnitMapper` raises AST__BADUN with a message; Rust returns `None`.

## Frames

### A Frame with no axes

- C: `astFrame(0, ...)` is allowed; a negative count raises AST__NAXIN.
- Rust: rejects zero axes (`builder_zero_axes_rejected`).
- Java: as C.

### Date and time strings

- C: reads `%lf` fields with `strtod`; maps a two-digit year as `palCaldj` does; takes the first year/month pattern that reads all its fields and checks separators afterwards; accepts a day not in its month (**C quirk**: the switch tests ERFA codes 1-3 but `palCaldj` returns negative codes, so the error never fires).
- Rust: differs in each of these.
- Java: as C, except that a bad year or month raises AST__DTERR where C leaves the date unset.

### FrameSet `RemapFrame` and the current Frame

- C: leaves Current set (`frameset.c:8610-8619`).
- Rust: does not.
- Java: as C.

### `astMatchAxesX` axis numbering

- C: uses `frm1_axes[pax] + 1`.
- Rust: searches `target_axes` instead.
- Java: as C.

### FindFrame and the result's Domain

- C: compares the Domain without upper-casing it.
- Rust: upper-cases it.
- Java: as C; the difference is invisible while Domain is stored in upper case.

### SkyFrame `Offset` with a bad offset

- **C quirk**: does not check the offset for AST__BAD.
- Rust: makes the result bad.
- Java: as Rust.

### A SkyFrame dump whose Frame part gives one axis

- C: `astLoadSkyFrame` passes each SkyRef value to `TestSkyRef` for the axis its inverse permutation gives, so a SkyFrame whose Frame part gave `Naxes = 1` fails with AST__AXIIN, "Axis index (2) invalid" (`skyframe.c` `astLoadSkyFrame`).
- Rust: `frames/skyframe.rs` `from_node` makes a two-axis SkyFrame whatever the Frame part gives.
- Java: as C.

### TimeFrame date formatting with a Unit that is not a time

- C: `astFormat` returns NULL without an error when the Unit cannot be converted to days.
- Rust: raises an error.
- Java: as C.

## FitsChan

### An equals sign in a keyword name is reported as an `astPutFits` error from every method

- C: `CheckFitsName` reports the error with the method that called it, so `astGetFitsF( fc, "A=B", ... )` reports "astGetFitsF(FitsChan): An equals sign ('=') was found before column 9 ..." (`fitschan.c:6185`).
- Rust: `FitsCard::split` in `fitschan/card.rs` always names `astPutFits`, whichever getter, setter or `astTestFits` read the name.
- Java: as C; `FitsCard.split` takes the method's name.

### IgnoreBadAlt and SipOK are stored as booleans

- C: `astMAKE_SET` stores the integer given for IgnoreBadAlt and SipOK (`fitschan.c:43022`, `fitschan.c:43241`), so after `astSetI( fc, "SipOK", 5 )` the attribute is 5, and a dump writes 5.
- Rust: `fitschan/mod.rs` holds both as `Option<bool>`, so the value becomes 1.
- Java: as C.

### A NATIVE read parses the whole Object before loading it

- C: `Read` reads a NATIVE Object through the Channel class's reader, whose `GetNextData` the FitsChan overrides to give one item per card (`fitschan.c:26091`, `fitschan.c:17917`), so each class loader takes its part of the Object as it is read: an item no loader reads is reported as a warning, or with Strict set as an error, and with Clean set a failed read removes the cards read up to the failure.
- Rust: `fitschan/enc_native.rs` turns the cards from `BEGASTxx` to the matching `ENDASTxx` into native text and parses it whole, so it reports no unread item, ignores Strict, and cannot know how far C's reader got when Clean removes the cards after a failure (`test_fitschan_cursor_oracle.rs` excludes `native_unknown_item`, `native_unknown_item_strict`, `native_bad_value` and `native_bad_value_clean`).
- Java: as C; `FitsNativeReader` gives the items of the cards to the Channel reader `NativeStream`.

### Floating values of a NATIVE read

- **C quirk**: `GetNextData` formats a floating card value with `%.17g` and then `CheckZero`, which rounds out runs of zeros and nines beyond the FitsRounding digits (`fitschan.c:18297`), so a value such as 0.30000000000000004 is read as 0.3, not the value written.
- Rust: `fitschan/enc_native.rs` formats the value with `{:.17e}`, which keeps it exactly.
- Java: as C.

### Cards in a NATIVE Object that are not items

- C: once an Object has begun, `GetNextData` reports AST__BADIN, "Cannot interpret the input data given by FITS keyword", for any card that is not a comment and not an item: a keyword without two sequence characters at its end, such as `CTYPE1`, or a logical, complex or undefined value (`fitschan.c:18310`).
- Rust: `cards_to_native_text` in `fitschan/enc_native.rs` passes such cards over.
- Java: as C.

### A continued string with no `CONTINUE` card

- C: a string item ending in `&` must be followed by `CONTINUE` cards; another card after it is an AST__BADIN error, "One or more FITS "CONTINUE" cards are missing" (`fitschan.c:18270`).
- Rust: `format_value_with_continues` in `fitschan/enc_native.rs` ends the string at the first card that is not a `CONTINUE` card.
- Java: as C.

### Duplicate WCS keywords in a FITS-WCS header

- C: `WcsToStore` reads every card in order into the FitsStore (`WcsFcRead`), so of two cards with one keyword the last is used, and `RADESYS = 'FK5'` followed by `RADESYS = 'BADVAL'` fails with AST__BDFTS (`fitschan.c:40234`).
- Rust: `fitschan/wcs_keywords.rs` looks each keyword up and takes the first card, so that header reads as FK5.
- Java: as C; of the cards for one keyword the last whose value converts gives the value, and a later card that does not convert is passed over (`fitschan.c:37357-37373`).

### More than 99 WCS axes

- C: the FitsStore holds the keywords of intermediate axes 0 to 98 only, and `GetItem`, `GetItemC`, `SetItem` and `SetItemC` raise AST__INTER for any other axis (`fitschan.c:13354-13357`, `fitschan.c:13869-13872`, `fitschan.c:27993-27996`), so a header with `WCSAXES = 1000` fails with "intermediate axis index 99 is invalid".
  With `WCSAXES = 2147483647` C first builds a Frame of that many axes in `WcsMapFrm`, until its memory runs out.
- Rust: `fitschan/fits_store.rs` `FitsStore::new` makes arrays of WCSAXES elements, and WCSAXES by WCSAXES for the PC and CD matrices, with no limit.
- Java: as C for `WCSAXES = 1000`, and AST__INTER, with the same message, for any number of WCS axes above 99.

### SpecTrans translations of FITS-PC keywords for every encoding

- C: `SpecTrans` reads RADECSYS, LONGPOLE and CmVALi, CmPIXi, CmYPEi and CmNITi, and writes CDELTis and PCj_is from CmELTi, whatever the encoding (`fitschan.c:31656-31675`, `fitschan.c:31962-32098`).
- Rust: `fitschan/spec_trans.rs` does these only for FITS-PC and FITS-IRAF.
- Java: as C.

### SpecTrans translations for FITS-PC and FITS-IRAF

- C: `SpecTrans` writes the RADESYS, LONPOLE, PVi_m (from PROJPm), CRVALis, CRPIXis, CTYPEis and CUNITis it translates into its own FitsChan, whose values `WcsToStore` reads after those of the header, so they replace any the header has: with both RADECSYS and RADESYS the RADECSYS value is used (C probe).
  Each of these cards, and each CDELTis from CmELTi, is given the comment of the current card of the header, `CardComm( this )`, which at the start of a read is the first card, not the comment of the card translated.
  The alternate letter of the CmVALi, CmPIXi, CmYPEi, CmNITi and CmELTi of each `m` is `A` for the lowest `m` the header has for that keyword and the next letter for each `m` after it, so `C2VAL1` alone becomes `CRVAL1A` (`fitschan.c:31656-31675`, `fitschan.c:31948-32098`). **C quirk** (the comment and the letter).
- Rust: `rename_keyword` in `fitschan/spec_trans.rs` keeps a RADESYS, CRVALis or other keyword the header already has, and moves the comment of the card it renames; `translate_cnxxx` gives `m` the letter `A + m - 1`.
- Java: as C.

### SpecTrans PC matrix translations

- C: the size of a CD matrix comes from `astKeyFields(..., 1, ...)`, the highest column index (`fitschan.c:31767`); CROTA becomes a PC matrix only for the primary description (`fitschan.c:31863`); CDELT for CROTA, CAR and CmELTi, and CRVAL for NCP, are read with `report` set, so a missing value is an AST__BDFTS error (`fitschan.c:31870`, `fitschan.c:32218`, `fitschan.c:32479`).
- Rust: `fitschan/spec_trans.rs` takes the larger of the two CD indices, translates CROTA for every description, and skips a translation whose keyword is missing.
- Java: as C.

### SpecTrans string comparisons

- C: CUNIT values are compared including trailing spaces (`fitschan.c:32597`), so `'HZ      '` is not normalised; `LAMBDA` must be exactly `"LAMBDA  "` and SPECSYS is set without the version letter (`fitschan.c:32566-32568`); an EQUINOX string takes a B or J prefix only in upper case at its first character, and allows text after the number (`fitschan.c:32128-32133`). **C quirk.**
- Rust: `fitschan/spec_trans.rs` trims and upper-cases the values and writes `SPECSYS<s>`.
- Java: as C.

### SpecTrans IRAF keywords

- C: the IRAF mini-WCS keywords consumed are those matching `WAT%d_%d`, `LTM%d_%d`, `LTV%d`, `WSV%d_LEN` and `WSV%d_%d` (`fitschan.c:32618-32637`); `WATCoeffs` raises AST__BDFTS for a word that is not a number (`fitschan.c:35745`), tries every occurrence of `cor`, and allows white space in `projp%d=%lf`; the early paper III rewrite keeps the characters after the eighth; NCP, GLS and ATF use the projection of the last celestial axis found.
- Rust: `fitschan/spec_trans.rs` matches prefixes (consuming MWCS too), returns not-ok instead of the error, truncates the order with `as i64`, looks only at the first `cor`, parses `projp` digits only, truncates the CTYPE after eight characters, and requires the longitude and latitude projections to agree.
- Java: as C.

### DATE-OBS scanning

- C: `DateObs` scans with `sscanf` patterns using `%2d`, `%4d` and `%d`, which allow a sign and leading white space, accept a legacy year of any width, and reject a trailing `.`, a repeated `Z` and a five-digit year (`fitschan.c:8662-8710`).
- Rust: `date_str_to_mjd` in `fitschan/wcs_write/mod.rs` splits on `-`, `T`, `:` and `.`, rejects a legacy year outside 0 to 99, and accepts `"12:00:00."` and a repeated `Z`.
- Java: as C (`FitsDates`).

### A PC or CD matrix with no inverse

- C: `WcsPCMatrix` raises AST__BDFTS "Unusable rotation matrix (PC or CD) ..." (`fitschan.c:38502`), so the `badmat` warning of `WcsIntWorld` (`fitschan.c:38134`) is never issued: `Warn` does nothing once an error is set. **C quirk.**
- Rust: `fitschan/wcs_matrix.rs` issues the `badmat` warning and carries on.
- Java: as C.

### Case of the `-SIP` distortion code

- C: `DistortMaps` compares the end of the CTYPE with `strcmp( ..., "-SIP" )` (`fitschan.c:9036`, `fitschan.c:9041`).
- Rust: `extract_sip` in `fitschan/wcs_keywords.rs` upper-cases the value first, so `-sip` matches too.
- Java: as C.

### Observation keywords of an alternate axis description

- C: `WcsFcRead` reads DUT1, DTAI and IMAGFREQ for the primary description only, and SSYSSRC and ZSOURCE for each description (`fitschan.c:37025-37046`, `fitschan.c:37171`); a Frame takes the values stored for its own letter (`fitschan.c:38360-38365`).
- Rust: `fitschan/wcs_keywords.rs` reads `DUT1<s>` and `DTAI<s>`, and gives every description the primary SSYSSRC, ZSOURCE and IMAGFREQ.
- Java: as C.

### The CRPIX shift of a FITS-WCS read

- C: `WcsShift` makes a WinMap from corners, mapping 0 and 1 to `-crpix` and `1-crpix` (`fitschan.c:39532-39539`), so its scale is computed, not given.
- Rust: `fitschan/wcs_matrix.rs` makes the WinMap from a shift and a scale of 1.
- Java: as C.

### SIP coefficient cards

- C: `WcsFcRead` passes over cards already used and converts each value with `CnvType` (`fitschan.c:37354-37365`).
- Rust: `read_sip_coeffs` in `fitschan/wcs_keywords.rs` also reads used cards, and parses strings with Rust's `f64` parser.
- Java: as C.

### Tolerance of `WcsNatPole`

- C: `TOL1` is `10.0*DBL_EPSILON` (`fitschan.c:1587`), as `WcsNatPole` uses it (`fitschan.c:39094`).
- Rust: `fitschan/fset_to_store/sky_pole.rs` uses `1.0e-13`.
- Java: as C.

### LONPOLE and LATPOLE from projection parameters 3 and 4

- C: `WcsNative` takes LONPOLE and LATPOLE from `PVi_3` and `PVi_4` of the longitude axis when they are set, except for TPN, and only otherwise from the LONPOLE and LATPOLE keywords (`fitschan.c:38746-38759`).
- Rust: `fitschan/wcs_celestial.rs` always uses the keywords, so a header with `PV1_3` and no LONPOLE gets the default pole and a `nolonpole` warning.
- Java: as C.

### A fiducial point moved by `PVi_0`

- C: `WcsCelestial` adds a ShiftMap for the projection-plane coordinates of the fiducial point (`GetFiducialPPC`) when `PVi_0` of the longitude axis is not zero and the projection is not TPN (`fitschan.c:36491-36520`).
- Rust: `fitschan/wcs_celestial.rs` has no such shift, so CRVAL does not map to the origin of intermediate world coordinates then.
- Java: as C.

### The native to celestial rotation of a FITS-WCS read

- C: `WcsNative` makes the rotation matrix and its inverse with three `astMtrRot` calls (`fitschan.c:38790-38830`).
- Rust: `fitschan/wcs_celestial.rs` uses its own `mtr_rot` and `compress_matrix`, with the inverse taken as the transpose.
- Java: as C, through `MatrixMap.mtrRot`.

### The WCS Frame of a header with latitude before longitude and other axes

- C: `WcsCelestial` puts an unpermuted SkyFrame into `CmpFrame( others, sky )` and permutes the CmpFrame; it permutes the SkyFrame itself only when the SkyFrame is the whole Frame (`fitschan.c:36574-36601`).
- Rust: `fitschan/wcs_read.rs` permutes the SkyFrame and orders the celestial axes in ascending order; the axes behave the same, but the dumps differ.
- Java: as C.

### AXREF on a SkyFrame with latitude first

- C: AXREF is stored with `astSetSkyRef( sfrm, 0/1 )` after the SkyFrame has been permuted, so its latitude takes the reference longitude (`fitschan.c:36683-36685`). **C quirk.**
- Rust: `set_sky_ref_on_frame` in `fitschan/wcs_read.rs` sets longitude and latitude by name.
- Java: as C.

### Warnings of a foreign read and `FixUsed`

- C: `Read` stores the warnings, then calls `FixUsed` (`fitschan.c:26007`).
- Rust: `finish_foreign_read` in `fitschan/mod.rs` calls `fix_used` first, so the cards already used are passed over by the search for the NAXIS card the warnings go in front of.
- Java: as C.

### The Domain of unclassified axes

- C: `WcsOthers` cuts the Domain made by joining the CTYPE values to 499 characters after each join (`fitschan.c:39316`). **C quirk.**
- Rust: `fitschan/wcs_read.rs` has no limit.
- Java: as C.

### The cards of a write that fails with an error

- C: `Write` removes the cards a write added, and makes the card that was current current again, through `FixNew` and `astSetCard`, which do nothing once the error status is set, so a write that fails with an error leaves the cards it had added and the current card where the failure left it (`fitschan.c` `Write`, `FixNew`). **C quirk.**
- Rust: `Channel::write` for `FitsChan` in `fitschan/mod.rs` removes the cards and restores the current card after any failure, an error included.
- Java: as Rust: `FitsChan.write` undoes a write that throws as it undoes one that writes nothing.

### The largest grid dimension of `FindBasisVectors`

- C: `FindBasisVectors` takes the largest dimension, which limits its search, from the dimensions before the first unknown one (`fitschan.c` `FindBasisVectors`).
- Rust: `fitschan/wcs_decompose.rs` `find_basis_vectors` uses 10240 whenever any dimension is unknown.
- Java: as C.

### The tolerance test of `FitOK`

- C: `FitOK` accepts a fit whose mean squared residual is strictly less than the squared tolerance (`fitschan.c` `FitOK`).
- Rust: `fitschan/wcs_decompose.rs` `fit_ok` accepts a root mean square residual less than or equal to the tolerance.
- Java: as C.

### Celestial axes in `MakeIntWorld`

- C: `MakeIntWorld` finds the longitude axis for `SIPIntWorld` with `astIsASkyFrame`, and calls it when SipOK is set and there is a longitude axis; when scaling degenerate axes it pairs celestial axes with the file's `IsASkyFrame` macro, which also needs the Domain to be `SKY` (`fitschan.c` `MakeIntWorld`).
- Rust: `fitschan/fset_to_store/make_int_world.rs` calls `sip_int_world` only when both a longitude and a latitude axis are found, and pairs any SkyFrame axes whatever their Domain.
- Java: as C.

### CDELT values of a failed `MakeIntWorld`

- C: when no linear description is found, `MakeIntWorld` still calls `SplitMat` on its CD matrix, which is all bad, and stores the CDELT array, which `SplitMat` has not set, so the store holds uninitialised values (`fitschan.c` `MakeIntWorld`). **C quirk.**
- Rust: `fitschan/fset_to_store/make_int_world.rs` stores nothing on failure.
- Java: as Rust; `FsetToStore` discards the store when the primary description fails, but keeps an alternate description's partial values.

### Dimensions of degenerate axes in `MakeInvertable`

- C: `MakeInvertable` reads a dimension for every input of the square matrix, but its caller's array has one per pixel axis, so for degenerate axes it reads past the end of the array (`fitschan.c` `MakeInvertable`, `Write`). **C quirk.**
- Rust: `fitschan/fset_to_store/invertable.rs` stops at the end of the array.
- Java: as Rust: an input with no dimension is not patched.

### `OrthVector` with no vectors, and its normalisation

- C: `OrthVector` with no vectors solves an empty system, which `palDmat` does not find singular, and gives the unit vector of the first column, so `OrthVectorSet` with no vectors gives the unit basis; it normalises by dividing by the length (`fitschan.c` `OrthVector`).
- Rust: `fitschan/fset_to_store/orth.rs` `orth_vector` returns `None` with no vectors, and normalises by multiplying by the reciprocal of the length, which can differ in the last bit.
- Java: as C.

### `WorldAxes`

- C: `WorldAxes` simplifies the Mapping first, takes as the root pixel position the inverse-transformed root world position even when it is bad, compares tangents with `astEQUAL`, and, for a Mapping with only a forward transformation, reads `perm[j]` for each input `j`, past the end of the array when there are more inputs than outputs (`fitschan.c` `WorldAxes`); the last is a **C quirk.**
- Rust: `fitschan/fset_to_store/world_axes.rs` does not simplify, keeps the previous root position on an axis whose inverse-transformed value is not finite, compares tangents with a local `ast_equal` scaled by the larger magnitude rather than the sum of the two, with a floor of the smallest normal double rather than 1e-12, and treats an input with no output of the same index as not retained.
- Java: as C, except that an input with no output of the same index is treated as Rust treats it.

### `NearestPix`

- C: `NearestPix` uses the Mapping split off for the axis whatever its number of outputs (`fitschan.c` `NearestPix`).
- Rust: `nearest_pix_value` in `fitschan/fset_to_store/spectral_axes.rs` uses the split Mapping only when it has one output.
- Java: as C.

### What `SIPIntWorld` stores

- C: `SIPIntWorld` stores the SIP coefficients only, by power, each table first filled with bad values up to the largest powers used, which for an iterative inverse is the single element (0, 0); `WcsFromStore` finds each order when it writes the cards (`fitschan.c` `SIPIntWorld`, `WcsFromStore`).
- Rust: `fitschan/fset_to_store/sip_int_world.rs` also stores the A, B, AP and BP orders, zero for an empty table, and stores no AP or BP table for an iterative inverse.
- Java: as C.

### The `-SIP` code on a short CTYPE

- C: `SIPIntWorld` copies the CTYPE value into a buffer and writes `-SIP` from its ninth character, so a value of fewer than eight characters keeps the null that ends it and is stored unchanged (`fitschan.c` `SIPIntWorld`).
- Rust: `append_sip_suffix` in `fitschan/fset_to_store/sip_int_world.rs` appends `-SIP` to a value of any length.
- Java: as C.

### The grid axes of the other outputs in `SIPIntWorld`

- C: `SIPIntWorld` reads one grid axis for each of the `Nin - 2` other inputs from the array `astMapSplit` returns for the other outputs, past its end if the split has fewer inputs (`fitschan.c` `SIPIntWorld`). **C quirk.**
- Rust: `build_perm_inputs` in `fitschan/fset_to_store/sip_int_world.rs` uses the inputs the split returns, however many there are.
- Java: reports `AST__INTER` when the split has fewer inputs.

### Shifting a PolyMap in `AnalysePoly`

- C: `AnalysePoly` merges the shift into the PolyMap with `astMergeShift`, which expands each term with `ExpandTerm` and simplifies the result, adding coefficients of the same powers in the order the expansion gives them and keeping the order of the terms (`polymap.c` `MergeShift`, `MergeShifts`, `ExpandTerm`); it simplifies the new PolyMap in the same way (`fitschan.c` `AnalysePoly`).
- Rust: `mappings/helpers/analyse_poly.rs` expands the terms with binomial coefficients (`merge_shift_2d`) and adds coefficients in a `BTreeMap`, as does `amalgamate`, so the terms come in sorted order and sums can differ in the last bit.
- Java: as C; `PolyMap.mergeShift` ports `astMergeShift`.

### Failures of `AnalysePoly` and `ScalePolyInputs`

- C: `AnalysePoly` reports `AST__INTER` for a PolyMap without two inputs and two outputs, and goes on with whatever the iterative inverse gives for the shift; `ScalePolyInputs` reports `AST__INTER` for a zero or bad scale, which reaches it from `SIPIntWorld` when the Mapping before the PolyMap has a zero scale (`fitschan.c` `AnalysePoly`, `ScalePolyInputs`).
- Rust: `analyse_poly` returns `None` for these PolyMaps and for a shift that is not finite, and `scale_poly_inputs` returns `None` for a zero or non-finite scale or a scale array of the wrong length; `sip_int_world` returns `None` before calling it for a zero scale.
- Java: as C.

### Output indices past the end of `AnalysePoly`'s coefficient array

- C: `AnalysePoly` sets the output index of the next coefficient before testing whether the coefficient is used, so once the last coefficient of the new PolyMap is written it writes past the end of its array for each remaining unused one (`fitschan.c` `AnalysePoly`). **C quirk.**
- Rust: `analyse_poly` builds a list of terms.
- Java: sets the index only for a coefficient that is used.

### The number of outputs in `astMergeShift`

- C: `MergeShift` reads the number of outputs with `astGetNin`, which matters only for a PolyMap with unequal numbers of inputs and outputs; its one caller, `AnalysePoly`, passes a 2-input, 2-output PolyMap (`polymap.c` `MergeShift`). **C quirk.**
- Rust: has no `astMergeShift`.
- Java: reads the number of outputs.

### Systems, Domain and Labels in `SkySys`

- C: `SkySys` stores nothing and returns 0 for a SkyFrame whose Domain is not `SKY` (`IsASkyFrame`), and stores WCSNAME only when the Domain is set, so only as `SKY`; a SkyFrame in the J2000 system, which is none of the systems it names, is described as an unknown one, its default Symbols giving `UNLN` and `UNLT` with no RADESYS; it stores EQUINOX, bad (unset) for the systems without one, replacing any earlier value; and a Label that is set becomes the CTYPE comment and CNAME even when empty (`fitschan.c` `SkySys`).
- Rust: `fitschan/fset_to_store/sky_sys.rs` stores WCSNAME for any Domain other than `SKY`, describes J2000 as RA and Dec with RADESYS ICRS, leaves an earlier EQUINOX in place, and ignores an empty Label.
- Java: as C.

### The pole of a non-zenithal projection in `SkyPole`

- C: `SkyPole` passes a bad native longitude of the celestial pole to `WcsNatPole`, which replaces it with its default, and stores LONPOLE and LATPOLE, bad (unset) when they take their defaults, replacing earlier values, and copies them, bad or not, to parameters 3 and 4 of the longitude axis (`fitschan.c` `SkyPole`).
- Rust: `fitschan/fset_to_store/sky_pole.rs` stores nothing when the pole's native coordinates are not finite or its Mapping cannot be inverted, and stores or copies only values that are set, leaving earlier ones.
- Java: as C.

### The WcsMap `SplitMap2` makes for a projection found in parallel

- C: `SplitMap2` gives a projection found among Mappings in parallel a new WcsMap with as many axes as the parallel outputs, copies every set projection parameter of every axis, and inverts it whatever the Invert value of the WcsMap found, the new WcsMap having none of that WcsMap's other attributes; and it searches a CmpMap with Ident set, which `astMapList` gives back whole for either decomposition, again without end, a **C quirk** (`fitschan.c` `SplitMap2`).
- Rust: `lift_wcsmap_to_full_axes` in `fitschan/fset_to_store/celestial_axes.rs` copies the parameters of the longitude and latitude axes only and keeps the Invert value of the WcsMap found.
- Java: as C, except that a CmpMap with Ident set is taken to hold no WcsMap.

### The Mapping and axis exchange of `CelestialAxes`

- C: `CelestialAxes` builds the Mapping from intermediate world to world coordinates from a ShiftMap, a MatrixMap, a copy of the WcsMap with parameters 3 and 4 of the longitude axis cleared, and `WcsNative`, between PermMaps when the axes need it; it exchanges the CRVAL values and the projection parameters of an exchanged celestial pair, and their CTYPE and CNAME values only when both are set (`fitschan.c` `CelestialAxes`).
- Rust: `build_wcs_to_iwc` in `fitschan/fset_to_store/celestial_axes.rs` builds its own rotation, and the exchange swaps CTYPE and CNAME whether or not they are set.
- Java: as C.

### A WCS axis fed by several grid axes in `IsMapTab1D`

- C: when the split of a WCS output from the inverse Mapping gives more than one grid axis, `IsMapTab1D` skips the table and returns the split Mapping, with no table columns, so `IsMapTab2D` and `CelestialAxes` go on to use a FitsTable that was never made (`fitschan.c` `IsMapTab1D`). **C quirk.**
- Rust: `is_map_tab_1d` in `fitschan/fset_to_store/map_tab.rs` returns `None` for such an output.
- Java: as C as far as `IsMapTab2D`; the use of the missing table that follows throws `UnsupportedOperationException` (this library has no FitsTable).

### A Mapping without a table in `OtherAxes`

- C: when `IsMapTab1D` returns a Mapping for an axis, `OtherAxes` stores the table version in the header of the FitsTable it expects to have been made, so for a Mapping returned with no table columns it dereferences a null FitsTable (`fitschan.c` `OtherAxes`). **C quirk.**
- Rust: `other_axes` in `fitschan/fset_to_store/other_axes.rs` gets `None` from `is_map_tab_1d` for such an axis.
- Java: throws `UnsupportedOperationException` for any Mapping `IsMapTab1D` returns there (this library has no FitsTable).

### Two SkyFrames in `MakeFitsFrameSet`

- C: `MakeFitsFrameSet` marks a world Frame with axes of two SkyFrames (of Domain `SKY`) unusable and makes no FrameSet, but then passes the null FrameSet to `AddEncodingFrame`, which dereferences it (a C-oracle probe writing a CmpFrame of two SkyFrames crashes in `astGetFrame_`) (`fitschan.c` `MakeFitsFrameSet`, `AddEncodingFrame`). **C quirk.**
- Rust: `make_fits_frameset` in `fitschan/fset_to_store/mod.rs` does not look for a second SkyFrame.
- Java: makes no FrameSet, so that the description cannot be made, which is what `AddVersion` does with the null FrameSet C means to return.

### Units in `MakeFitsFrameSet`

- C: `MakeFitsFrameSet` always builds a new FrameSet from copies of the pixel Frame, the world Frame and the Mapping; for an axis whose internal units cannot be converted to its Unit it sets the Unit to the internal units (degrees for radians, converted with a ZoomMap); and it appends the parallel CmpMap of conversions, not its simplification, to the Mapping (`fitschan.c` `MakeFitsFrameSet`).
- Rust: `apply_unit_remap` in `fitschan/fset_to_store/mod.rs` returns a clone of the FrameSet when nothing converts, leaves the Unit unchanged, and appends the simplified conversion.
- Java: as C.

### The FrameSet an absolute description is made from

- C: `AddVersion` clears SkyRefIs of an offset SkyFrame on the caller's FrameSet, through its integrity, and sets it back afterwards, so the caller's FrameSet is remapped twice and its Mapping can change in the last bits (a C-oracle probe writing the same FrameSet twice gives CRVAL1 1.0357383636595399, then 1.0357383636595427) (`fitschan.c` `AddVersion`). **C quirk.**
- Rust: `fset_to_store_absolute` in `fitschan/fset_to_store/mod.rs` clears SkyRefIs on a clone, leaving the caller's FrameSet unchanged.
- Java: as C.

### Version letters of absolute descriptions

- C: `FsetToStore` gives the description of absolute coordinates of an offset SkyFrame the letter after the last one it handed out, without checking it against the letters Frames took from their Ident, so the two descriptions can share a letter and are then stored together, the second replacing the values both store (a C-oracle probe writes version `A` with the CRVAL, CTYPE and CDELT of the Frame whose Ident is `A`, and the SkyRef, RADESYS and EQUINOX of the absolute sky description) (`fitschan.c` `FsetToStore`). **C quirk.**
- Rust: `collect_plane_stores` in `fitschan/wcs_write/plane_stores.rs` makes the two planes separately, with the same letter.
- Java: as C.

### The order of the versions written

- C: the store holds each value under the letter of its description, and `WcsFromStore` writes the descriptions in letter order, so the description of a Frame whose Ident is `Z` follows that of a later Frame given `A` (`fitschan.c` `WcsFromStore`).
- Rust: `encode_wcs` in `fitschan/wcs_write/mod.rs` writes the planes in the order `collect_plane_stores` made them, primary first and then by Frame.
- Java: the store is indexed by letter, as C's (`FitsPlaneStores`).

### WCSNAME and the pixel axis count in `AddVersion` and `FsetToStore`

- C: `AddVersion` stores the Domain of a world Frame whose Domain is set as WCSNAME, replacing a value `SkySys` stored; the store's `naxis` is the number of pixel axes `FsetToStore` is given (`fitschan.c` `AddVersion`, `FsetToStore`).
- Rust: `fset_to_store` in `fitschan/fset_to_store/mod.rs` stores a Domain that is not empty only when there is no WCSNAME yet, and its store's `naxis` is the larger of the numbers of pixel and world axes.
- Java: as C; the pixel axis count is `FitsPlaneStores.naxis`.

### The comment of a card a write replaces

- C: `SetValue` replaces a card with the keyword it writes, and `SetFits` keeps that card's comment when the new card has none or when the old value, converted to the type of the new one by `CnvValue`, is the same: equal integers, floating values equal to `astEQUAL` (whose floor is 1.0E-12), or strings `Similar` finds alike; a string's comment equal to its value is dropped (`fitschan.c` `SetValue`, `SetFits`, `MAKE_FSET`).
  A probe keeps the comment of `CRPIX1 = 1 / old int comment` when `CRPIX1 = 1.0` is written.
- Rust: `put_or_append` in `fitschan/card_list.rs` keeps the comment only when the two values have the same type and are similar, comparing floating values with a `DBL_MIN` floor and strings ignoring case and trailing blanks only.
- Java: the writers of the foreign encodings follow C (`FitsCardList.setValueAsWriter`); the read's keyword translations keep the comparison they had.

### `Similar` does not compare the last character

- C: `Similar` compares two strings ignoring case and blanks up to, but not including, the last character that is not blank in either, so `ABCD` and `ABCX` are alike; `WcsFromStore` then keeps the old comment of a card it replaces with a value differing only there, and leaves out the WCSNAME of a one-axis description whose CTYPE1 differs from it only there (C probe) (`fitschan.c` `Similar`). **C quirk.**
  Two blank strings make it read past the end of both.
- Rust: `write_store_to_cards` in `fitschan/wcs_write/mod.rs` compares WCSNAME with CTYPE1 with `eq_ignore_ascii_case`.
- Java: as C (`FitsCardList.similar`), two blank strings being alike.

### Where FITS-WCS writes the SIP coefficients and their orders

- C: `WcsFromStore` writes each version's SIP coefficients at the end of its loop over versions, after which `FindWcs` puts the current card after the last card describing WCS, which the SIP cards are not, so the next version's cards go in front of them; the coefficient keywords carry the version letter but `A_ORDER`, `B_ORDER`, `AP_ORDER` and `BP_ORDER` do not, and each order is the largest sum of powers of the coefficients written (`fitschan.c` `WcsFromStore`). **C quirk** (the order keywords).
- Rust: `encode_wcs` writes the primary description's SIP block after the loop over planes, from the orders `fset_to_store` stores.
- Java: as C.

### IMAGFREQ, QV parameters and CNAME in `WcsFromStore`

- C: IMAGFREQ is written, without a version letter, for any version that has one; once one axis's CTYPE ends `-TPN`, the projection parameters of every axis of that version are written with the prefix `QV`; CNAME is left out when it equals the CTYPE or its comment (`fitschan.c` `WcsFromStore`).
- Rust: `write_store_to_cards` writes IMAGFREQ for the primary description only, chooses `QV` axis by axis, and writes no CNAME.
- Java: as C.

### The DATE-OBS of a FITS-WCS write

- C: `WcsFromStore` formats DATE-OBS from MJD-OBS with `palDjcal` before 1999 and with `palDjcl` and `palDd2tf` (milliseconds) after, so a time of day that rounds up is written `T24:00:00.000` on the same day, and a year before 1900 as a negative two-digit year (`fitschan.c` `WcsFromStore`; C probe). **C quirk.**
- Rust: `mjd_to_date_str` in `fitschan/wcs_write/mod.rs` works the date out with Meeus's algorithm and carries a rounded-up time into the next second.
- Java: as C (`FitsDates.mjdToDateStr`).

### Alternate descriptions in `PCFromStore`

- C: `PCFromStore` compares an alternate description's PC matrix with the primary one's element by element in storage order, so matrices of different sizes are compared as flat arrays and C reads past the primary matrix for an alternate with more axes; it compares an alternate RADECSYS with the primary one's even when the primary description has none, reading an uninitialised string, and an alternate's projection parameters with values never set when the primary description has no celestial axes (`fitschan.c` `PCFromStore`). **C quirk** (undefined behaviour).
- Rust: `write_pc` in `fitschan/enc_pc.rs` compares the two matrices as far as the shorter goes, and holds the unset values as absent or `AST__BAD`.
- Java: an element past the primary matrix differs; an unset primary RADECSYS differs from any; unset primary parameters are `AST__BAD` (`FitsEncPc`).

### `IRAFFromStore` writes the primary description only

- C: `IRAFFromStore` writes the primary description alone, its keywords without a version letter, refusing it when the number of CRPIX values differs from the number of pixel axes (`store->naxis`); it stops part way, leaving its cards for `Write` to remove, at a missing CRPIX, CRVAL or CTYPE or a `-TAB` axis; it writes DATE-OBS from MJD-OBS, and MJD-OBS only over a card the header already has (`fitschan.c` `IRAFFromStore`).
- Rust: `encode_wcs_per_plane` in `fitschan/wcs_write/mod.rs` passes the primary plane to `enc_iraf::write_to_cards`, which checks the plane's own axis count, checks CRPIX, CRVAL and CTYPE before writing, and writes DATE-OBS from the plane's stored date string.
- Java: as C (`FitsEncIraf`).

## Test harness

### Round trips where C's own recorded inverse fails

- C: the transform-oracle checker has no exception for rows whose recorded inverse does not round-trip.
- Rust: `test_transform_oracle.rs` excuses them.
- Java: as Rust, pinned to the affected rows by `RTRIP_REFERENCE_GAPS_LICENSED`.
