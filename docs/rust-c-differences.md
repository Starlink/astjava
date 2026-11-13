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

- C: raises AST__BADIN.
- Rust: reads it without an error (`test_channel_read_oracle.rs` excludes `string_for_double` and `string_for_int`).
- Java: as Rust; both ports differ from C here.

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

### MatrixMap rotation and multiplication

- C: `astMtrRot` and `astMtrMult` rotate the inverse matrix alongside the forward one and re-compress (`ExpandMatrix`, `SMtrMult`).
- Rust: rebuilds the inverse, which can differ in the last bits of the inverse coefficients.
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
- Java: as C; `FitsNativeReader` gives the items of the cards to the Channel reader `NativeStream`, whose limits (see "A string where a dump expects a number") are the Channel's.

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

## Test harness

### Round trips where C's own recorded inverse fails

- C: the transform-oracle checker has no exception for rows whose recorded inverse does not round-trip.
- Rust: `test_transform_oracle.rs` excuses them.
- Java: as Rust, pinned to the affected rows by `RTRIP_REFERENCE_GAPS_LICENSED`.
