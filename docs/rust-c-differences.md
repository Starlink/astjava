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
- Java: as C for the FITS-WCS read; the FITS-PC, FITS-IRAF and FITS-CLASS reads are out of scope.

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

## Test harness

### Round trips where C's own recorded inverse fails

- C: the transform-oracle checker has no exception for rows whose recorded inverse does not round-trip.
- Rust: `test_transform_oracle.rs` excuses them.
- Java: as Rust, pinned to the affected rows by `RTRIP_REFERENCE_GAPS_LICENSED`.
