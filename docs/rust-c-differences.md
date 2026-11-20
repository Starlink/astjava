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

### PointSet accuracies and empty PointSets

- **C bug**: `astLoadPointSet` (pointset.c), when the first accuracy item it reads (`AccN`) is for an axis after the first, sets the accuracies of the axes before it to bad only up to the one before last (`i < coord - 1`), leaving that one uninitialised; `Dump` writes it and `Equal` uses it.
  A PointSet read with `Empty` set holds no values, and `astGetPoints` gives it values it allocates without initialising, which `Equal` compares.
- Rust: `read_pointset_node` (`serial/mod.rs`) reads neither the accuracies nor `Empty`, so an empty PointSet reads its `X` items, all bad when there are none, and `write_pointset_node` writes no accuracies.
- Java: an axis without an accuracy item has none; an empty PointSet is read and written as C writes it, and asking for its values, as `Equal` does, raises AST__INTER (`PointSetTest`).

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

- **C quirk**: when `MapMerge` cancels a nominated PolyMap with its inverse it annuls the PolyMap before restoring its Invert attribute (`polymap.c` `MapMerge`), so the PolyMap is left with the value the list applied it with, set: simplifying the inverse of a series CmpMap of a PolyMap and an inverted copy of it gives a UnitMap and leaves the copy, which the CmpMap shares, with Invert set to 0 (probe `src/test/resources/probes/polymap_cancel_probe.c`, case `inverted_series`).
- Rust: not compared.
- Java: as C.
  Java sets back the neighbour's Invert attribute after the comparison as it was, set or unset, where C's `astSetInvert` leaves it set, so the two differ only in what `test("Invert")` gives afterwards.

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

### Comparing two MatrixMaps changes them

- **C quirk**: `Equal` (`matrixmap.c`) stores both MatrixMaps in full form for the comparison and then compresses them (`CompressMatrix`), which sets to zero each off-diagonal element no larger than sqrt(DBL_EPSILON) times the largest in its row and in its column, and stores a matrix that is then diagonal in diagonal form.
  A MatrixMap that was never compressed, such as one read from a dump, can therefore be changed by being compared, and with it what the merge in the entry above does with it; the recursion guard of `Simplify` (`cmpmap.c`) compares the components of CmpMaps, which may share such a MatrixMap with the Mapping being simplified.
- Rust: `equal` (`mappings/matrix.rs`) compares without changing either MatrixMap.
- Java: as C.

### Array arguments longer than needed

- C and jniast: accept them and use the leading elements (WinMap corners, MatrixMap matrices, PermMap permutations).
- Rust: rejects them.
- Java: as C.

### A WinMap with no axes

- C: can be created, and raises AST__BADWM when it transforms.
- Rust: refuses it at construction.
- Java: as C.

### A CmpMap, TranMap or RateMap dump with a missing or non-Mapping component

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

### The frequency correction factor of a SpecMap's change of rest frame

- C: a 1-dimensional SpecMap works out the factor the first time it transforms points and keeps it in the conversion's last argument slot (`specmap.c` `FrameChange`), so a SpecMap written after it has transformed points carries a `Spec<n><x>` card for it, as the fixtures `simplify/cap_specmap_06.map` and `cap_winmap_01.map` do.
- Rust: keeps the factor in a separate cache (`fcorr`), which is not written.
- Java: as C.

### SpecMap values that are not finite

- C: each conversion that can make a value that is not finite tests for it and writes AST__BAD; ENTOFR, FRTOEN, WNTOFR, FRTOWN, BTTOVL and VLTOBT do not, so an overflow gives an infinity (`specmap.c` `SystemChange`).
- Rust: also replaces every value that is not finite with AST__BAD after the last conversion.
- Java: as C, apart from inputs that are not finite, which are bad (see "Infinite input coordinates").

### SpecMap load errors

- C: a missing or unknown conversion is AST__BADIN with C's messages (`astLoadSpecMap`).
- Rust: AST__SPCIN.
- Java: as C.

### The SpecMap axis-count message

- **C quirk**: `astInitSpecMap` refuses a number of axes other than 1 or 3 with a message saying "it should be 1 or 2".
- Rust: gives its own message.
- Java: keeps C's message.

### The last value a LutMap transformed

- **C quirk**: `Transform` (`lutmap.c`) keeps the last input and output of each direction in the LutMap, and gives the kept output for the same input in a later call, even after LutInterp has changed; a copy keeps them too, and only the constructor and the loader reset them (probe `src/test/resources/probes/lutmap_probe.c`, cases `cache` and `cache_copy`).
- Rust: keeps nothing, so each transformation uses the current LutInterp (`lutmap_interp_change_applies_to_a_repeated_value`).
- Java: as C.

### Cancelling a LutMap with its inverse

- **C quirk**: `MapMerge` (`lutmap.c`) compares the nominated LutMap, with its own Invert attribute, with the inverse of its neighbour applied as the list says; it does not use the Invert value the list gives the nominated LutMap.
  In an inverted series CmpMap a LutMap followed by an inverted copy of itself is kept, and a LutMap followed by a copy of itself is replaced by a UnitMap, which changes the transformation (probe cases `inverted_cmp_lut_lutinv` and `inverted_cmp_lut_lut`).
- Rust: applies the list's Invert value to both LutMaps before comparing them.
- Java: as C.

### A linear LutMap whose first and last entries are equal

- C: `MapMerge` (`lutmap.c`) tries the cancellation with a neighbouring LutMap only for a table `GetLinear` finds not linear, so a constant table, or one linear within LutEpsilon whose ends are equal, is neither replaced by a WinMap nor cancelled (probe cases `flat_linear_lutinv` and `constant_lutinv`).
- Rust: `linear_equivalent` gives nothing for such a table, and the cancellation is then tried.
- Java: as C.

### The number of entries of a loaded LutMap

- C: `astLoadLutMap` takes 2 entries when the dump has no `Nlut` item.
- Rust: takes 0.
- Java: as C.

### A CmpMap comparison changes a shared component's Invert attribute

- **C quirk**: `Equal` (`cmpmap.c`), without its expanded lists, saves the Invert attribute of the two components it compares, sets each to the value its list gives, compares, and restores them in the same order.
  When the two components are the same object, the value it saves for the second is the one it has just set for the first, so the object is left with the first (`this`) list's value instead of its own.
  `Simplify` (`cmpmap.c`) compares CmpMaps this way, so simplifying `simplify/brad.map` changes the Invert attribute of its WcsMap, which the simplified CmpMap shares, from 1 to 0 (traced in the pinned library), and `brad.simp` records 0.
  A CmpMap applies each component with the Invert value it recorded when made (`cmpmap.c` `Transform`), so neither the original Mapping nor the simplified one transforms differently: brad.map and brad.simp give the same values in C and in Java.
  The change is seen by anything that uses the WcsMap on its own afterwards, and in dumps.
- Rust: compares owned copies, so nothing changes.
- Java: as C, and the recursion guard of the simplification compares Mappings with `astEqual` as C's does: simplifying `brad.map` gives `brad.simp` and leaves the Mapping read with the WcsMap not inverted (probe `src/test/resources/probes/simplify_input_probe.c`).
  The `brad` row of `simplify/cases.txt` matches C; its gate compares the input's dump after simplification with the one the probe records.
  Java restores each component's Invert attribute as it was, set or unset, where C's `astSetInvert` leaves it set, so the two differ only in what `test("Invert")` gives afterwards.

### The status of a MathMap with no outputs

- **C quirk**: `astInitMathMap` reports a bad number of outputs with AST__BADNO and then its second line, "This number should be one or more.", with AST__BADNI, which replaces the status, so a caller sees AST__BADNI (probe `src/test/resources/probes/mathmap_probe.c`, case `count_errors`).
- Rust: reports AST__BADNO.
- Java: as C.

### The messages of a MathMap's errors

- C: each error names the method and class, quotes the expression up to the character where reading stopped, and adds a line naming the transformation function, as "Error in forward transformation function 3." (probe cases `expression_errors` and `function_errors`).
- Rust: has its own messages, with the same statuses.
- Java: as C.

### The MathMap constant `<dig>`

- C: loads `AST__DBL_DIG`, which is 17 (probe case `evaluate`).
- Rust: loads 15, `DBL_DIG`.
- Java: as C.

### Maths library errors in a MathMap

- C: traps the errors of `exp`, `cosh`, `sinh`, `tan`, `pow` and `ldexp` (in `<<` and `>>`) through `errno`, giving a bad value for an overflow and for an argument outside the domain (a negative number to a fractional power); `csch` and `sech` are then 0.
  The macOS maths library sets no `errno`, so C built there gives the infinity or NaN instead, and a later operator acts on it: `!x**2` at 1e300 is 0 there (probe case `evaluate`).
- Rust: gives a bad value for any of these results that is not finite.
- Java: as C with a library that sets `errno` (glibc): a result that is not finite from finite arguments is bad.

### A NaN made inside a MathMap expression

- C: passes a NaN that an expression makes (such as `1e999-1e999`, whose constant overflows to infinity) to the output.
- Rust: replaces a NaN output by AST__BAD.
- Java: as C; an input that is not finite is bad, the rule for every Mapping.

### The second value of a Gaussian pair

- **C quirk**: `Gauss` (mathmap.c) keeps the second value of each pair it makes, and the flag saying it has one, in static variables shared by every MathMap, so the next `gauss` of any MathMap whose generator is active takes it.
  A MathMap and its copy therefore give different Gaussian sequences, and two MathMaps used in turn take each other's values (probe cases `random_copies` and `gauss_two_mathmaps`); two threads using MathMaps at once race on it.
- Rust: keeps the value in each MathMap's random-number context.
- Java: as Rust, so a copy gives its original's sequence and a MathMap's sequence does not depend on other MathMaps; with one MathMap, or MathMaps whose generators are not yet active, the values are C's.

### A MathMap Seed below 1

- **C quirk**: `Rand` makes a seed below 1 positive by adding `LONG_MAX`, which on a 64-bit `long` leaves the generators outside their ranges, so `rand(0,1)` gives values far outside 0 to 1 for several calls (Seed -7 gives -308527335.2 first; probe case `random_seeds`).
- Rust: the same.
- Java: as C.

### A MathMap dump with fewer functions than its outputs or inputs

- C: `astLoadMathMap` reads the counts `Nfwd` and `Ninv` (defaults the numbers of outputs and inputs) and compiles with them; a smaller count reads before the start of the function arrays.
- Rust: takes a count that is not positive as the default, and refuses a smaller one with AST__INNTF.
- Java: refuses a count smaller than the number of outputs or inputs with AST__INNTF and the message of `astInitMathMap`, naming `astLoadMathMap`.

### The precedence of unary operators and `**` in a MathMap

- **C quirk**: the `astMathMap` prologue, and jniast's MathMap Javadoc taken from it, list the unary operators `+ - ! .not.` above `**`, which says that `-x**2` is `(-x)**2`; `EvaluationSort` (mathmap.c) binds `**` tighter, so `-x**2` is `-(x**2)` and `x**-2` is `x**(-2)` (probe `src/test/resources/probes/mathmap_probe.c`, case `evaluate`).
  The difference is in the documentation only.
- Rust: the parser follows the code, and its documentation lists `**` above the unary operators.
- Java: as Rust; MathMap's Javadoc lists `**` above the unary operators, as the code binds them.

### Comparing two MathMaps

- **C quirk**: `Equal` (mathmap.c) compares the compiled functions of the effective forward and inverse directions, but its loop over a function's opcode array starts at element 0, which holds the number of opcodes, and stops before the last opcode.
  The last operation of a function is therefore never compared, so `y=x+1` equals `y=x-1` and `y=max(x,1)` equals `y=min(x,1)` (probe `src/test/resources/probes/mathmap_simplify_probe.c`, case `equal`).
  A number of opcodes equal to the code of an operation that consumes a constant (0, 1, 62 or 63) consumes one as well; for a function of one operation that loads no constant, such as `y=<pi>`, C reads a list of constants that does not exist and crashes.
- Rust: `equal` compares the function texts and the Invert flags.
- Java: as C; where C reads past the end of the constants, a constant neither function has matches and one only one function has does not.

### Simplifying a CmpMap clears AlSimp on the components it shares

- C: `Simplify` (cmpmap.c) clears the flag that lets a restricted simplification use a Mapping on each component it joins into a new CmpMap; those components are shared with the input, so the input's components lose the flag too (probe case `simplify_input_after`, from `simplify/cap_restricted_component_flags.map`).
- Rust: copies the components, so the input keeps the flag.
- Java: as C.

### A TranMap whose equal components were inverted when it was made

- **C quirk**: `MapMerge` (`tranmap.c`) replaces a TranMap whose first component, in series with the inverse of its second, simplifies to a UnitMap with a copy of the first component.
  The copy keeps the Invert value recorded when the TranMap was made, but the list entry it replaces stays applied forward, and the list's value is the one the result is given.
  A TranMap of two equal Mappings that were inverted when it was made therefore becomes the first Mapping not inverted, which transforms the other way (probe `src/test/resources/probes/tranmap_probe.c`, cases `simp_equal_inverted_mathmaps`, and `simp_equal_inverted_lutmaps` at 3).
  Two inverted ZoomMaps do not show it, because each simplifies on its own to a ZoomMap of the reciprocal factor first (case `simp_equal_inverted_components`).
- Rust: applies the copy inverted, so the transformation is kept.
- Java: as C.

### Comparing two TranMaps

- C: `Equal` (`tranmap.c`) sets each component to the Invert value its TranMap recorded and compares the components with `astEqual`, the `Equal` of their class.
- Rust: compares them with each class's `equal`.
- Java: as C.

### The directions of a loaded TranMap

- C: `astLoadTranMap` leaves TranForward and TranInverse to the Mapping part of the dump (`Fwd` and `Inv`, which a TranMap never writes, so both are defined).
- Rust: recomputes them from the components and the recorded Invert values.
- Java: as C.

### The series flag of a decomposed TranMap

- C: `Decompose` (`tranmap.c`) does not set the series flag, which its documentation gives as zero for a TranMap; the probe's `decompose` case gets back the value it set beforehand, and jniast's `decompose` passes an uninitialised one.
- Rust: its `Decomposition` has no series flag.
- Java: false.

### The split of a TranMap of all its inputs, permuted

- C: the base `MapSplit` (`mapping.c`) simplifies a PermMap and the TranMap in series; the TranMap's equal components make it a ZoomMap, which `astSimplify` records as simplified, so its dump has `IsSimp = 1` (oracle `map_split.json`, case `tranmap_full_permuted`).
- Rust: dumps the ZoomMap without that item, and its oracle test compares the case without `IsSimp` items.
- Java: as C.

### The orientation in which RateMaps compare their Mappings

- C: `Equal` (`ratemap.c`) sets each encapsulated Mapping to the Invert value its RateMap recorded when it was made and compares them with `astEqual`; `MapMerge` does the same before cancelling a RateMap with a neighbouring RateMap applied the other way.
  A RateMap whose Mapping was inverted after the RateMap was made therefore equals, and cancels with, one made from a Mapping that was not (probe `src/test/resources/probes/ratemap_probe.c`, cases `equal` (`mapping_inverted_after`) and `cancel_mapping_inverted_after`).
- Rust: compares the encapsulated Mappings in their current orientations.
- Java: as C.

### Cancelling a RateMap with its inverse

- **C quirk**: `MapMerge` (`ratemap.c`) replaces a RateMap and a neighbouring RateMap of the same indices and an equal Mapping, applied in series the other way, with two UnitMaps, so that a series CmpMap of the two, which defines neither direction (a RateMap defines only one direction, so the pair defines neither), simplifies to a UnitMap that defines both.
  For a Mapping of two inputs the RateMap followed by its inverse becomes a UnitMap of two axes, and the inverse followed by the RateMap a UnitMap of one (probe cases `cancel_two_inputs_upper` and `cancel_two_inputs_lower`).
- Rust: as C.
- Java: as C.

### Comparing two ZoomMaps

- **C quirk**: `Equal` (`zoommap.c`) compares the factors as stored, which is zero once the Zoom attribute has been cleared, though the factor is then 1: a ZoomMap whose Zoom is cleared does not equal a ZoomMap of factor 1.
  For ZoomMaps with different Invert attributes it compares one factor with the reciprocal of the other, which for a cleared Zoom is infinite, and `astEQUAL` finds an infinite value equal to any finite value that is not bad (probe `src/test/resources/probes/equal_probe.c`, case `zoommap_cleared_zoom`).
- Rust: compares the factors in use, and, for different Invert attributes, their product with 1.
- Java: as C.

### The attributes of a new GrismMap

- C: `astGrismMap` leaves all eight attributes unset, so a new GrismMap's dump writes each as a comment holding its default; each can be tested and cleared, and setting `AST__BAD` (`INT_MAX` for GrismM) leaves it unset (`grismmap.c`).
  The setters discard the records of simplification only when the value changes, and the loader re-applies each value read through them, so a dumped `IsSimp` card survives a read (probe `src/test/resources/probes/grismmap_probe.c`, case `dump_simplified`).
- Rust: `GrismMap::new` sets all eight attributes, and only `default_map` leaves them unset; there are no test or clear methods, the setters given the unset values acting as a clear.
  Its loader keeps the `IsSimp` card as C's does, though `docs/issues/c-library-quirks.md` ("The `IsSimp` card does not survive being read back") counts GrismMap among the classes whose loaders discard it.
- Java: as C; jniast's constructor makes a GrismMap with no attribute set.

### Comparing two GrismMaps

- **C quirk**: `Equal` (`grismmap.c`) compares the attributes as stored, `AST__BAD` (or `INT_MAX` for GrismM) when unset, so a GrismMap with no attribute set does not equal one with each attribute set to its default, though the two transform alike (probe `src/test/resources/probes/grismmap_probe.c`, case `equal`, `default_vs_set_to_defaults`).
- Rust: compares the values in use.
- Java: as C.

### Merging a GrismMap with a ZoomMap whose own Invert attribute is set

- **C quirk**: `CanMerge` (`grismmap.c`) merges a GrismMap applied forward with a following ZoomMap, and a GrismMap applied inverted with a preceding one, scaling GrismG, GrismNRP and GrismWaveR by the ZoomMap's factor.
  It takes the factor as the Zoom attribute, or its reciprocal when the ZoomMap's own Invert attribute differs from (following) or equals (preceding) the Invert value the simplification applies it with, so the factor is the right one only while the ZoomMap's own Invert attribute is unset or zero.
  A GrismMap followed by a ZoomMap of factor 2 that was inverted before the two were combined therefore merges into a GrismMap for wavelengths scaled by 2 rather than by 1/2, and so does a ZoomMap inverted after the CmpMap was made, on either side: the simplified Mapping does not transform as the original (probe `src/test/resources/probes/grismmap_probe.c`, cases `inverted_zoom_after_forward`, `zoom_inverted_after_combining` and `zoom_inverted_after_combining_before`).
  A ZoomMap inverted before it is combined that precedes the GrismMap is merged correctly, because the ZoomMap's own merge, which comes first, replaces it with a ZoomMap that is not inverted (case `inverted_zoom_before_inverted`).
- Rust: `grism_zoom_merge_at` in `mappings/simplify.rs` holds every Mapping of the list with its Invert attribute unset and takes the factor from the list's Invert value alone, so it merges these pairs correctly.
- Java: as C.

### Comparing two PcdMaps

- **C quirk**: `Equal` (`pcdmap.c`) compares Disco and the two PcdCen values as stored, `AST__BAD` when unset, so a PcdMap whose centre is unset does not equal one whose centre is set to zero, though the two transform alike (probe `src/test/resources/probes/pcdmap_probe.c`, case `equal`, `zero_vs_unset` and `small_vs_unset_disco`).
- Rust: compares the values in use.
- Java: as C.

### When a PcdMap's coefficient is zero, and when two PcdMaps cancel

- C: `MapMerge` and `CanMerge` (`pcdmap.c`) compare with the file's own `EQUAL` macro, whose floor is `1e5 * DBL_MIN` (about `2e-303`), not with `astEQUAL`, whose floor is `1e-12`.
  So a PcdMap with a coefficient of `1e-13` is not replaced by a UnitMap (probe `src/test/resources/probes/pcdmap_probe.c`, case `small_disco`), though `astEqual` finds it equal to one with a coefficient of zero (case `equal`, `small_vs_zero_disco`), while one of `1e-310` is replaced (case `tiny_disco`); and PcdMaps applied in opposite directions whose centres differ by `1e-13` do not cancel (case `inverse_small_centre_offset`).
- Rust: `pcd_map_merge` (`mappings/simplify.rs`) and `PcdMap::try_merge` (`mappings/pcdmap.rs`) compare with `ast_equal`, which has `astEQUAL`'s floor, so they replace the PcdMap of coefficient `1e-13` with a UnitMap and cancel the pair whose centres differ by `1e-13`.
- Java: as C.

### A SwitchMap selector value beyond the range of `int`

- C: `GetRoute` and `Transform` (`switchmap.c`) exclude only `AST__BAD` and then take `(int) round( sel ) - 1` as the route index, so a selector value beyond the range of `int`, an infinity or a NaN makes the conversion undefined, and a conversion giving `INT_MIN` makes the `- 1` overflow, a second undefined step.
  C's documented contract is that a nearest integer below 1 or above the number of routes picks no route.
  The pinned library built on macOS aarch64 keeps to that contract for such values: `2147483648.5`, `4294967297`, `4294967298`, `±1e30` and `±DBL_MAX` pick no route, so there is no modular wrap into the routes (probe `src/test/resources/probes/switchmap_probe.c`, case `selector_values`); the probe cannot tell whether the conversion saturates or gives `INT_MIN`.
- Rust: compares the rounded value with 1 and the number of routes before converting, so such a value picks no route by definition.
- Java: converts as C does; Java's conversion is defined, giving 0 for a NaN and the nearest `int` for any other value, and `Integer.MIN_VALUE - 1` wraps to `Integer.MAX_VALUE`, so every such value gives an index outside the routes and picks no route, as the contract says and the probe shows.

### Simplifying a SwitchMap that holds one Mapping twice

- **C quirk**: `MapMerge` (`switchmap.c`) saves the Invert attribute of the forward selector, the inverse selector and each route Mapping in turn while setting each to the value the SwitchMap recorded, and sets them back in the same order, not the reverse.
  When one Mapping is held twice, as when it is both selectors (`testswitchmap.c` section 2) or two routes, the value saved for its second place is the one just set for its first, so it is left with the value recorded for its first place instead of its own: a MathMap held as both selectors and inverted after the SwitchMap was made is no longer inverted after the SwitchMap is simplified (probe cases `simp_section2_shared_selector`, `simp_section2_shared_selector_inverted` and `simp_shared_route`).
  The SwitchMap applies each Mapping with the value it recorded, so it transforms as before; anything that uses the Mapping on its own afterwards sees the change.
- Rust: holds its own copies, so nothing changes.
- Java: as C.
  Java sets back each Invert attribute as it was, set or unset, where C's `astSetInvert` leaves it set, so the two differ only in what `test("Invert")` gives afterwards.

### A SwitchMap round trip when only the forward selector was inverted

- **C bug**: `Dump` (`switchmap.c`) writes `ISInv` with its set flag equal to its value and not as a helpful value, so `Channel` writes `ISInv = 0` not at all at Full 0, and only as a comment, which is never read back, at a higher Full; C's own dump therefore never carries `ISInv = 0`.
  `astLoadSwitchMap` takes an absent `ISInv` to be the value of `FSInv`, so a SwitchMap whose forward selector was inverted when it was made and whose inverse selector was not reloads with its inverse selector inverted, its inverse transformation changed and `astEqual` false (probe `src/test/resources/probes/switchmap_probe.c`, case `dump_fsinv_set_isinv_unset`: the inverse transformation of 1000 is 1 before the round trip and 47.454545454545453 after).
  An absent `RInv<n>` is 0, and a dump without `RMap1` loads a SwitchMap of no route Mappings, which gives bad values everywhere.
- Rust: takes an absent `ISInv` to be 0, so its round trips keep the SwitchMap, and refuses a dump without route Mappings with AST__LDERR.
- Java: as C; a component that is not a Mapping is AST__LDERR, as for a CmpMap, TranMap or RateMap.

### The messages of SwitchMap's constructor

- **C quirk**: `astInitSwitchMap` reports a route Mapping with a different number of outputs from the first with the two numbers of inputs, as "Route Mapping number 2 has 1 output(s) but the first route Mapping has 1 output(s)." (probe case `error_route_nout`).
  The public `astSwitchMap` reports no route Mappings with two spaces after the colon, the library's own with one.
- Rust: has its own messages, with the same statuses.
- Java: as C, the public constructor giving the public function's message.

### SelectorMap's transformation changes its own Regions

- **C quirk**: `Transform` (`selectormap.c`) tests the points against each of its own Regions by negating it and reversing its Closed attribute with `astNegate` and `astSetClosed`, and puts both back the same way, so each Region is left with Negated and Closed set to the values they had.
  A dump made after a transformation writes `Closed = 1` for each Region whose Closed was unset (probe `src/test/resources/probes/selectormap_probe.c`, cases `focus_dump_before` and `focus_dump_after`); `astEqual`, which compares the values, is unchanged (case `focus_equal_after`).
- Rust: transforms a negated copy of each Region, so the SelectorMap is unchanged.
- Java: as C, putting the attributes back even when a transformation fails, where C leaves them changed.

### SelectorMap's simplification of its Regions

- C: `MapMerge` (`selectormap.c`) simplifies each Region and rebuilds the SelectorMap when `astSimplify` returns a different pointer for any of them, whatever its class.
- Rust: `reduced_children` (`mappings/selectormap.rs`) takes a Region as changed when its simplification is not `equal` to it or differs in Invert, and keeps the result only for nine listed Region classes.
- Java: as C, comparing the objects `simplify` returns (probe cases `simplify_unchanged` and `simplify_changed`).

### Two equal 1-D SelectorMaps in series simplify to a UnitMap

- **C bug**: `MapMerge` (`selectormap.c`) inverts the neighbouring SelectorMap, compares it with `Equal`, and replaces the pair with a UnitMap if they are equal; `Equal` compares the numbers of inputs, the Regions and the bad value but not the Invert attributes, so for 1-D SelectorMaps, whose inverse has as many inputs as outputs, it cannot tell a SelectorMap from its inverse.
  Two equal 1-D SelectorMaps both applied forward are replaced with a UnitMap, which changes the transformation: the pair gives 1 for every good point, whatever Region holds it, and the UnitMap gives the point (probe case `series_1d_both_forward`).
- Rust: `try_merge` requires the two Invert flags to differ, so the pair is kept.
- Java: as C.

### Loading a SelectorMap

- **C bug**: `astLoadSelectorMap` annuls the first Region's FrameSet pointer after reading the Regions, so a description without `Reg1` fails with AST__OBJIN from `astAnnul` of a null pointer (probe case `load_no_regions`).
  The Regions' Frames are not compared, and a later Region written without its Frame is given the first Region's FrameSet with `astSetRegFS`, which makes a FrameSet of copies of all its Frames, joining a copy of its base Frame, as the Region's base Frame, to a copy of its current Frame, as the Region's current Frame, with a UnitMap; a first Region whose FrameSet holds another Mapping therefore leaves the later Region as it was written (probe case `load_first_region_mapped`, whose first Region's FrameSet holds a MathMap).
- Rust: gives a later Region two copies of the first Region's current Frame, joined by a UnitMap, so only the base Frame differs from C's; it rebuilds the SelectorMap through its builder, so Regions in different Frames are AST__LDERR, and it refuses a description without Regions with AST__LDERR.
- Java: as C, except that a description without Regions is AST__LDERR, and a component that is not a Region is AST__LDERR.

### The messages of SelectorMap's constructor

- C: no Regions is AST__BDPAR "astSelectorMap(SelectorMap): Bad number of Regions (0) specified.", and the first Region whose Frame is not equal to the first Region's is AST__BADNI "astInitSelectorMap(SelectorMap): Region number 2 does not refer to the same coordinate Frame as the first Region." (probe cases `error_no_regions`, `error_naxes`, `error_skyframe`).
- Rust: the same statuses, with its own messages.
- Java: as C; a null array or Region is a NullPointerException naming it, as for jniast's other arguments.

### The directions of a loaded IntraMap

- C: `astLoadIntraMap` leaves TranForward and TranInverse to the Mapping part of the dump (`Fwd` and `Inv`); the registered function's AST__NOFWD and AST__NOINV flags decide them only when an IntraMap is made (`astInitIntraMap`).
- Rust: `has_forward` and `has_inverse` read the transformation's flags each time, so a dump whose `Fwd` or `Inv` disagrees with them reads back with the transformation's directions.
- Java: as C.

### Registering a transformation function a second time

- C: `IntraReg` accepts a second registration of a name only if the numbers of coordinates, the function, the flags and the purpose, author and contact strings are all the same, and otherwise reports AST__MRITF.
  Its table is process-wide.
- Rust: `IntraRegistry::register` accepts a second registration of the same `Arc`, whatever else differs; the registry belongs to a Channel.
- Java: as C, comparing the Transformer object as C compares the function pointer.
  Functions are registered with a Channel for its reads, and the process-wide table holds the Transformer of each IntraMap made in the process, weakly; a read looks in the Channel's first.
  The process-wide names are made from identity hash codes, which two Transformers may share, so where C would refuse a name held by another function, making an IntraMap takes the next free name instead (`#1`, `#2`, ... added); the same Transformer with other details is still AST__MRITF.

### Concurrent calls of an IntraMap's transformation function

- C: `Transform` (`intramap.c`) holds `LOCK_MUTEX2` around each call of a registered function, so no two run at once; jniast's calls were serialised too, through its global `AstLock`.
- Rust: holds no lock, and requires `IntraTransform: Send + Sync`.
- Java: holds no lock; the Javadoc of Transformer and IntraMap says a Transformer must be thread-safe, since copies of an IntraMap share it (a user decision).

### The numbers of coordinates `astLoadIntraMap` checks

- **C quirk**: `astLoadIntraMap` compares the numbers of input and output coordinates the Mapping class's `GetNin` and `GetNout` give, which take account of the Invert attribute already loaded, with those registered.
  An inverted IntraMap of a function with different numbers of inputs and outputs therefore cannot be read back: AST__BADNI (probe `src/test/resources/probes/intramap_load_probe.c`, case `inverted`).
- Rust: `IntraMap::from_node` does not compare the numbers of coordinates with the transformation's.
- Java: as C.

### The status of an error from an IntraMap's transformation function

- C: `Transform` (`intramap.c`) keeps the status the function set; jniast's `tranWrap` sets AST__ITFER for any exception the Transformer throws or any result of the wrong shape.
- Rust: keeps the code of the error the transformation returns.
- Java: AST__ITFER, as jniast, with the Transformer's exception as the cause.

### A NaN from an IntraMap's transformation function

- C: `Transform` (`intramap.c`) and jniast's `tranWrap` (`jni/IntraMap.c`) return the function's values as they are, so a NaN it gives is kept unless the environment variable `AST_REPLACE_NAN` is set (`astTransform`, `pointset.c` `astReplaceNaN`).
  Resampling and rebinning take only AST__BAD to be a bad position, so in C a NaN position reaches the conversion of a coordinate to a pixel index.
- Rust: `IntraMap::transform_into` returns the transformation's values as they are.
- Java: `IntraMap` turns a NaN in the Transformer's result into AST__BAD, as the Mapping class's contract requires of every transformation (no transformation gives a NaN output), so resampling and rebinning treat the position as bad.
  An infinite value is kept, as other Mappings keep one (an overflow in a ZoomMap gives an infinity through `transform`, as C does by default); it lies outside every grid.

### The value of two pi in `platepos`

- C: `platepos` (`dssmap.c`) adds its own `twopi`, 6.28318530717959, to a negative right ascension; it is a little more than the double nearest to two pi, so a wrapped right ascension is about four units in the last place larger (probe `src/test/resources/probes/dssmap_probe.c`, case `tran_pltra_small`, where the round trip through `platepix` comes back 2.4e-10 pixels short).
- Rust: adds `std::f64::consts::TAU`.
- Java: as C.

### The side of a DssMap a WinMap merges from

- C: `MapMerge` (`dssmap.c`) merges only a WinMap applied to the pixel coordinates: before the DssMap when the list applies it forward, after it when the list applies it inverted (probe cases `dssmap_then_winmap_4200` and `winmap_then_inverted_dssmap`, which do not merge).
- Rust (found by reading): `DssMap::try_merge` takes the formula from the DssMap's Invert value alone, and `default_map_merge` offers it the neighbour on either side, so a WinMap after a DssMap applied forward, or before one applied inverted, is folded in as if it were on the other side.
- Java: as C.

### A DssMap merge leaves the WinMap's Invert attribute changed

- **C quirk**: `MapMerge` (`dssmap.c`) sets the WinMap's Invert attribute to the value the list applies it with, and restores it only when the two do not merge, since it annuls the WinMap's pointer before the restore when they do.
  Simplifying the inverse of a series CmpMap of an inverted DssMap and an inverted WinMap merges the two and leaves the WinMap, which the CmpMap shares, with Invert 0 (probe case `invert_attr_both_inverted`).
- Rust: reads the terms of an owned WinMap, so nothing changes.
- Java: as C.
  Java sets back the Invert attribute after a failed merge as it was, set or unset, where C's `astSetInvert` leaves it set, so the two differ only in what `test("Invert")` gives afterwards.

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

### SpecFrame Overlay onto a Frame of another class

- **C quirk**: when the template has its System set, `Overlay` (specframe.c) clears the System and AlignSystem of a copy of the template and overlays the copy; when that changes the copy's System, `ClearSystem` also clears the copy's Title, Label and Symbol, and its Unit unless one was used with the default System, so none of those is overlaid either.
- Rust: `specframe_overlay` overlays the template itself through `frame_overlay`, which transfers the System only to a result of the template's class, so the Title, Label, Symbol and Unit are overlaid.
- Java: as C.

### A SpecFrame Symbol changed by its Unit

- **C quirk**: `GetSymbol` (specframe.c) copies the default Symbol, modified for a Unit other than the System's default, into a static 21-character buffer with `strcpy`, so a longer one overflows it: System `WAVN` with Unit `nm` gives a Symbol beginning `999999999.99999988/ WAVN`, and the fortified `strcpy` traps (probed: SIGTRAP from `astGetC( f, "Symbol(1)" )`, and from a NATIVE write of a header with `CTYPE1 = 'WAVN'`, `CUNIT1 = 'nm'`, through `Dump`).
  `GetSymbol` in fluxframe.c has the same pattern (read from the code, not triggered by the units tried).
- Rust: gives the whole Symbol.
- Java: as Rust.

### A failed StdOfRest, SourceVRF or SourceSys setting

- **C quirk**: when re-expressing a set SpecOrigin or SourceVel in the new standard of rest or source System fails (AST__ATSER, AST__NOVAL, AST__BADUN), setting or clearing StdOfRest, SourceVRF or SourceSys reports the error but stores the new value anyway: `SetStdOfRest` and `ClearStdOfRest` (specframe.c) store it after `OriginStdOfRest` has cleared the SpecOrigin and failed, and the `astMAKE_SET` and `astMAKE_CLEAR` macros for SourceVRF and SourceSys store it after `ConvertSourceVel` has failed, leaving the SourceVel expressed in the old rest frame or System.
  Later attribute values, the default Title (`Wavelength (LSRK)`, say) and the dump then describe the new value.
- Rust: `set_std_of_rest`, `clear_std_of_rest`, `set_source_vrf`, `clear_source_vrf`, `set_source_sys` and `clear_source_sys` in `frames/specframe.rs` return the error before storing the value.
- Java: as Rust: the setter throws and the attribute keeps its old value (a SpecOrigin already cleared by the failed re-expression stays cleared, as in C).

### Aligning DSBSpecFrames in sidebands sets the target's SideBand

- **C quirk**: to find the observed sideband of the target and of the result, `SubFrame` (dsbspecframe.c) reads the SideBand, sets it to `observed` and sets it back with `astSetSideBand`, so after a conversion with AlignSideBand set on both DSBSpecFrames (or one restoring a FrameSet's integrity) the target's SideBand is set, to the value it had.
  `Match` then calls the target's `SubFrame` a second time when the target axis belongs to a DSBSpecFrame, although the parent `Match` has made the same call, so the result of the second call is a copy of a target whose SideBand is set.
- Rust: `DSBSpecFrame::sub_frame` takes the observed sidebands from the sign of the IF without changing either Frame, and `match_template` makes the call once.
- Java: as C.

### DSBSpecFrame settings and errors

- **C quirk**: `SetAttrib` (dsbspecframe.c) takes any setting that holds the text `dsbcentre=` for a DSBCentre setting, so `Title=dsbcentre=3` fails with AST__ATTIN.
  `GetLO` reads the DSBCentre for the second line of its AST__ATTIN message after reporting the first, when the getter gives its default, so the message always says the DSBCentre is 3e+10 Hz.
  Its AST__NOVAL and AST__ATTIN messages name `astGetImagFreq` whichever method calls it, and `ToLSBMapping` and `ToUSBMapping` find the Mapping to topocentric frequency before checking the IF and DSBCentre.
  The loader refuses a SideBand other than `USB`, `LSB` or `LO`, in that case, with AST__ATTIN.
- Rust: has no string attribute layer for a DSBSpecFrame; its `lo_freq` message gives the DSBCentre held, `verify_attrs` names `astMatch`, the sideband Mappings check the attributes first, and `from_node` reads the SideBand in any case and ignores one it does not know.
- Java: as C.

### FluxFrame System names

- C: at the pin, `SystemCode` (fluxframe.c) reads both `SFCBRW` and `SRCBR` as `AST__SBRIGHTW`, and `FluxSystemString` writes `SFCBRW`, so a FluxFrame in that System reads its own dump back.
  The Rust repository's `c-library-quirks.md` lists this as resolved upstream in b4457da9, after the pin, but the pinned C already holds the fix (3b1f3f5a, "fluxframe: read the System name it writes for per-wavelength surface brightness").
- Rust: `FluxSystem::parse` reads both names and writes `SFCBRW`, as C at the pin does.
- Java: as C.

### The SpecVal comparison of `MakeFluxMapping`

- C: the two SpecVals, each converted to the result's density system, must satisfy `astEQUALS( specval_in, specval_out, 1.0E8 )`: a difference of no more than the larger of 1e8 times DBL_EPSILON times the sum of their magnitudes, and 1e-12.
- Rust: allows 1e8 times DBL_EPSILON times the larger of their magnitudes and 1, which is about half C's width for large values and wider than C's for values below 1.
- Java: as C.

### FluxFrame Overlay onto a Frame of another class

- **C quirk**: when the template has its System set, `Overlay` (fluxframe.c) clears the System and AlignSystem of the template itself, overlays the Frame attributes, and sets the System and AlignSystem again with the values they had.
  The template's AlignSystem is left set, to its default if it was not set, and when clearing the System changes it, FluxFrame's `ClearSystem` and `SetSystem` clear the template's Label, Symbol and Title and reset its Unit to the one last used with each System, or clear it (probed through `astOverlay`).
- Rust: `fluxframe_overlay` leaves the template unchanged; `frame_overlay` transfers the System only to a result of the template's class.
- Java: as C.

### The Unit a FluxFrame records for its System

- C: `SetUnit` (fluxframe.c) records the Unit as given, trailing spaces included, as the one used with the System, while the Frame class stores the Unit without them (probed: `Unit(1)=Jy  ` dumps `UFlxdn = "Jy  "`).
- Rust: records the Unit without trailing spaces.
- Java: as C.

### A FluxFrame dump whose `SpcFr` is not a SpecFrame

- C: `astLoadFluxFrame` keeps whatever Object `SpcFr` holds.
- Rust: keeps it only if it is a SpecFrame, and ignores any other.
- Java: refuses it with AST__LDERR, since a FluxFrame here holds only a SpecFrame.

### The comment of a FluxFrame's SFCBRW units in a dump

- C: `Dump` formats "Preferred units for <system label>" into a 50-character buffer (`fluxframe.c` `Dump`); for SFCBRW the comment is 55 characters, so it writes past the buffer (an AddressSanitizer stack-buffer-overflow, C probe). **C quirk.**
- Rust: writes the whole comment.
- Java: as Rust.

### A SpecFluxFrame dump whose components are not a SpecFrame and a FluxFrame

- C: `astLoadSpecFluxFrame` loads the CmpFrame and keeps whatever Frames `FrameA` and `FrameB` hold, which `GetSpecFrame` and `GetFluxFrame` (specfluxframe.c) later cast to a SpecFrame and a FluxFrame.
- Rust: refuses them with AST__LDERR.
- Java: as Rust.

### The default Title of a SpecFluxFrame

- C: `GetTitle` (specfluxframe.c) formats "<flux Label> versus <spectral Label>" with `sprintf` into a static 101-character buffer, so Labels longer than 92 characters between them write past it (read from the code, not probed). **C quirk.**
- Rust: gives the whole Title.
- Java: as Rust.

### SpecFluxFrames whose SpecFrames do not convert

- C: `MakeMap2`, `MakeMap3`, `MakeMapF` and `MakeMapI` (specfluxframe.c) find each component conversion with `astConvert`, which returns NULL when the Frames do not convert (SpecFrames in different Domains, say), so `MakeSFMapping` gives no match and the SpecFluxFrames do not convert.
  `MakeSFMapping` gives a UnitMap when both components are `astEqual` (specfluxframe.c:1141-1163).
- Rust: `make_map2`, `make_map3`, `make_map_f` and `make_map_i` (`frames/specfluxframe.rs`) call `make_spec_mapping` and `make_flux_mapping` directly; `make_spec_mapping` (`frames/specframe.rs`) runs no Domain check and turns no Mapping into an AST__BADUN error.
  Its comment calls the `astEqual` short-circuit a deliberate divergence from C, which it is not for the pinned C.
- Java: as C (`SpecFluxFrameTest.spectralDomainsThatDifferDoNotConvert`).

### Casting between a CmpFrame and a SpecFluxFrame

- C: `Cast` (cmpframe.c) copies a CmpFrame or SpecFluxFrame as an instance of any class of the CmpFrame family, a subclass included (`astCastCopy`), so a SpecFluxFrame template that matches no CmpFrame is cast into a plain CmpFrame and matched again, and a plain CmpFrame template that matches no SpecFluxFrame (the CmpFrame `ConvertX` gets from matching a SpecFluxFrame against a FluxFrame or SpecFrame) is cast into a SpecFluxFrame holding copies of its components, whose `Match` reaches `MakeSFMapping`.
  **C quirk**: `Cast` compares the classes through CmpFrame's own `class_vtab`, which is initialised only when a plain CmpFrame is first made, so in a process that has made SpecFluxFrames but no CmpFrame a SpecFluxFrame match that fails dereferences null in `astClassCompare` (probed: SIGSEGV converting a SpecFluxFrame whose SpecFrame has Domain `FOO` to a default one).
- Rust: SpecFluxFrame has no `cast`, and `CmpFrame::cast` in `frames/cmpframe.rs` copies a CmpFrame only into a CmpFrame, so neither becomes the other and these conversions find nothing.
- Java: as C once a CmpFrame has been made: a FluxFrame or SpecFrame that does not match a SpecFluxFrame directly converts through `MakeSFMapping` (or gives C's error, such as AST__ATSER from `GetSpecFrame`).

### `astNormBox` on a CmpFrame whose axes are permuted

- **C quirk**: `NormBox` (cmpframe.c) applies the caller's Mapping to a position in the CmpFrame's external axis order, through a PermMap made with a null `inperm`, and then inverts that PermMap to bring the outputs back; but the inverse of a PermMap made with a null `inperm` copies each coordinate to the same position, so the outputs stay in the external order and each component tests the outputs on the axes that come first.
  On a CmpFrame(SkyFrame, TimeFrame) whose axes are permuted to (time, latitude, longitude), a Mapping that keeps the north pole and ignores the time axis gives the SkyFrame (bad, pi/2) for its pole, so the box is not widened (probe `src/test/resources/probes/frame_line_probe.c`, case `normbox_cmpframe_permuted`).
  A Region gives either all bad outputs or its inputs unchanged, so only a negated Region (whose outputs for the component's probe position carry the bad values on the other component's axes) is affected.
- Rust: `norm_box` (`frames/cmpframe.rs`) takes a predicate, not a Mapping, and asks it whether the position is inside, so it has no outputs to put back in order, and a predicate that accepts the position widens the box.
- Java: as C (`NormBoxTest.aPermutedCmpFrameAsksEachComponent`).

### `astFrameGrid` counts beyond the range of `int`

- **C quirk**: `FrameGrid` (frame.c) assigns each axis's tick count, `ntick0*(ubnd-lbnd)/gmean`, to an `int` without a range check, and multiplies the counts in `int`; an axis of zero width makes the geometric mean zero, so the count of another axis of the same unit is infinite and its own is a NaN, and the conversions are undefined.
  The pinned library on AArch64 saturates the infinite count and converts the NaN to 0 (then 2), so the product wraps to -2 and `astPointSet` reports AST__NPTIN, "Number of points (-2) is not valid" (probe case `grid_frame`); on x86-64 the conversion gives `INT_MIN`, raised to 2, and C makes a grid of four points.
  `FrameGrid` (skyframe.c) likewise converts `0.5 + (hilat-lolat)/lat_size` to the number of parallels, which is infinite for a box of zero width in longitude: on AArch64 C walks 2^31 parallels (about 30 seconds) before finding no point, on x86-64 two.
  The same box has no grid point on either, so `astSetNpoint` reports AST__NPTIN, the error probe case `grid_sky` shows for a grid of size 1; the probe leaves the box of zero width in longitude out for its run time.
  Apart from the quirk, `FrameGrid` (frame.c) reports bad bounds only when both bounds of an axis are bad, which looks deliberate: `AST__BAD` (-DBL_MAX) is also a usable lower bound for an axis with no lower limit.
- Rust: `frame_grid_default` converts with `as`, which saturates as AArch64 does, and multiplies the counts in `i32`, which panics on overflow in a debug build; its SkyFrame `frame_grid` returns an empty PointSet where C's `astSetNpoint` reports AST__NPTIN.
- Java: as the pinned library on AArch64, Java's conversion saturating and giving 0 for a NaN (`FrameGridTest`); the SkyFrame box of zero width in longitude takes as long as C's and is not tested.

## Regions

### The default uncertainty of a Region that moves

- C: a Region builds its default uncertainty once (region.c `GetUncFrm`) and keeps it until an uncertainty is set or cleared; `ResetCache` leaves it, so a Box moved by `astRegCentre` is still widened, where it has zero width, by the uncertainty made where it was. **C quirk.**
- Rust: discards the default uncertainty whenever the Region changes (`RegionData::invalidate_cache`), and `test_box_region.rs` pins that.
- Java: as C; `BoxRegionTest.defaultUncertaintyFollowsAMovedBox` takes its values from `src/test/resources/probes/region_defunc_probe.c`.

### Setting an axis attribute on a Region that is not Adaptive

- C: `RegSetAttrib` moves the setting to the base Frame axis the current Frame axis feeds only when `sscanf( setting, "%[^(](%d)= %n%*s %n" )` reads the whole setting, so a value holding white space, such as `Label(1)=Right ascension`, is set on the base Frame axis of the same number even when the axes are permuted. **C quirk.**
- Rust: always moves the setting to the base Frame axis.
- Java: as C (`Region.regSetAttrib`).

### Protected Frame setters of a Region

- C: region.c's `SetEpoch`, `SetObsLat` and `SetObsLon` pass the internal value to `astSetD` on the encapsulated FrameSet, which reads it as a year or as degrees. **C bug.**
- Rust: has no string route.
- Java: the package-private `setEpochMjd` sets `MJD <value>` on the FrameSet, which keeps the FrameSet's integrity; `setObsLatValue`, `setObsLonValue` and `setObsAlt` set the FrameSet's current Frame directly.
  No public method reaches them.

### A Region read from a dump with an uncertainty or Frame of another class

- C: region.c `astLoadRegion` reads the `Unc`, `Frm` and `FrmSet` items with `astReadObject` and uses them as a Region, a Frame and a FrameSet without checking their classes, so a corrupt dump holding an Object of another class there is used as one of those. **C bug.**
- Rust: reads each with the reader of its class (`serial/mod.rs`).
- Java: `AST__REGIN`, naming the class found and the class needed, in the words of the loader's other errors (`Region` loader).

### A value in the Frame part of a Region's dump that needs the current Frame

- C: region.c `astLoadRegion` loads the Frame part of the description, which describes the current Frame, before it reads the FrameSet, so a value in that part whose loading asks for the number of axes (region.c `GetNaxes`, which asks the FrameSet) reads the FrameSet before it exists. **C bug.**
- Rust: not examined.
- Java: `AST__BADIN`, saying that the current Frame has not yet been read (`Region.getNaxes`); a dump that C writes holds no such value.

### The grid over an unbounded Region (`RegGrid`)

- C: region.c `RegBaseGrid` returns no PointSet, and reports no error, for an unbounded Region, and `RegGrid` then passes the null pointer on to `astClone` or `astTransform`. **C bug.**
  `astGetRegionMesh` tests Bounded first and reports `AST__MBBNF`, so only the library's own callers reach it.
- Rust: not examined.
- Java: `AST__MBBNF`, in the words of `astGetRegionMesh`'s error (`Region.regGrid`).

### Picking axes of a Region in `MapRegion`

- C: region.c `MapRegion`, given a Mapping with fewer outputs than inputs, picks the Region's axes that the outputs need with `astPickAxes` and uses the result as a Region; `astPickAxes` gives a Frame rather than a Region when the Region cannot be split along those axes, and C then calls Region methods on that Frame. **C bug.**
- Rust: keeps the whole Region and the whole Mapping when the axes picked are not a Region (`reg_map_region`).
- Java: `AST__INTER` when the axes picked are not a Region (`Region.mapRegionInto`).

### Releasing the split Mapping twice in `MapRegion`

- C: region.c `MapRegion`, given a Mapping with fewer outputs than inputs whose inverse leaves the axes it drops bad, such as `PermMap(2, {1,0}, 1, {1})` or `PermMap(2, {0,1}, 1, {2})`, releases the Mapping `astMapSplit` gives in the branch that uses it unchanged (`region.c:5691`, at 3d3f255f) and again in the cleanup common to both branches (`region.c:5719`). **C bug.**
  The second `astAnnul` of the null pointer fails with `AST__OBJIN`, so `astMapRegion`, and so `astMask<X>` of a Box, fails, and the final `astInvert` is skipped, leaving the caller's Mapping inverted.
  PointList's `Mask<X>` then reads the points of the Region `astMapRegion` failed to make with no check of the status (`pointlist.c:902`) and crashes, as it does after any failure there.
  With the second release guarded (`if( tmap )`), C maps the Box or PointList to the Region of the axis kept and masks pixels 1 to 6 with it; a Circle then meets the entry above, `astPickAxes` giving a Frame that C uses as a Region.
- Rust: `reg_map_region` has no second release, so it takes the branch as C intends; for a Circle it keeps the whole Region (entry above).
- Java: what C gives with the second release guarded (`Region.mapRegionInto`), and `AST__INTER` for a Circle (entry above); `RegionMapRegionDropAxisTest` pins the mapped Regions and the masks from `src/test/resources/probes/region_mapregion_drop_axis_probe.c`, which is built with that guard.

### Casting a Region into the class of another Region (`Cast`)

- C: as written, region.c `Cast` copies a Region as an instance of the class of any Region given to it, with `astCastCopy`, which changes the class of the copy without checking that the Region is of that class or of a subclass of it: a Box cast into the class of a Circle is a Circle holding a Box's values, and between the Stc classes, which are siblings, the copy of a smaller StcSearchLocation, StcCatalogEntryLocation or StcResourceProfile cast into an StcObsDataLocation reads past its end, where the observatory position would be. **C bug.**
  It does not get that far: `Cast` first compares the class of its own vtab (`class_vtab`) with that of the Object given (`astClassCompare`), and that vtab is initialised only when an Object of the class Region itself is made, which the public interface never does, so the comparison follows a null class identifier and the program crashes. **C bug.**
  `astFindFrame` matches with `matchsub` 0, and region.c's `ConvertX` aligns the current Frames of Regions, so neither reaches `Cast` with a Region; a CmpFrame holding a Region does: `astConvert` between two CmpFrames, each of an Stc (or a Circle) and a Frame, whose Regions are in different Domains, crashes in `astClassCompare` under `Cast` (`frame.c` `astMatch`, from cmpframe.c `PartMatch`).
  Probe `src/test/resources/probes/stc_probe.c`, case `stc_classes`, holds `astFindFrame` and `astConvert` between Stcs of the same and of different classes, and `astFindFrame` between those CmpFrames, none of which reaches `Cast`.
- Rust: not examined.
- Java: a copy when the Region is an instance of the class of the Region given, and null for any other class (`Region.cast`), so an Stc is not cast into the class of another Stc; the CmpFrames above do not convert (`StcProbeTest`).
  C's cast into the class of a parent is defined, and is not followed: the copy keeps its own class; jniast's Stc is abstract, so no concrete Region class of this library is a parent of another, and the copy is always of the class of the Region given.

### An Stc's transformation sets Negated on its encapsulated Region

- **C quirk**: `Transform` and `GetBounded` (stc.c) set the encapsulated Region's Negated attribute to the value it already has with `astSetNegated`, around the call and again after it, so it is left set; an Stc reads Negated from its encapsulated Region (stc.c `TestNegated`), so `astTest( stc, "Negated" )` is true once the Stc has transformed points or been asked whether it is bounded, and so is that of a copy (probe `src/test/resources/probes/stc_probe.c`, cases `used_plain` and `used_bounded`).
  The dump, which writes `Negate` only when it is 1, and `astEqual`, which compares the values, are unchanged.
- Rust: transforms with the encapsulated Region as it is, so Negated stays unset.
- Java: as C (`Stc.regBaseMask`, `Stc.isBoundedInner`), putting the value back even when a transformation fails, as C does.

### An Stc dump without its Region or an AstroCoords element

- C: stc.c `astLoadStc` stores a null pointer for a missing `Region` or `Coord<n>` item and reports no error; with no `Region`, the load then dereferences it (`astRegDummyFS`) and crashes unless the Stc was itself written without its Frame, and with a missing `Coord<n>`, the load succeeds and `astGetStcCoord` crashes copying the null KeyMap (probed: SIGSEGV in each). **C bug.**
- Rust: `AST__LDERR` for either.
- Java: `AST__LDERR` for either (`Stc(NativeNode)`; `StcProbeTest`).

### An Stc of the abstract class Stc

- C: a dump that begins `Begin Stc` is read by `astLoadStc` as an Stc of the class Stc itself, which transforms points, dumps and compares like any other (`src/test/resources/probes/stc_probe.c`, case `begin_stc`).
- Rust: no loader for the class name Stc, so the read fails.
- Java: jniast's `Stc` is abstract, so no Object can be of that class alone; the read fails with `AST__LDERR` (`ObjectRegistry.load`).

### The axis ends of an AstroCoords Error Ellipse fitted to a Circle

- C: an AstroCoords Region in another SkyFrame is mapped into the Stc's Frame and simplified (stc.c `MakeAstroCoordsKeyMap`), so a small Circle becomes the Ellipse that best fits its mapped mesh (ellipse.c `astBestEllipse`); on a near-circle the direction of the fitted axes, and so the ends of the axes the Ellipse is defined by, follow from the last bits of the mesh.
- Rust: compares the Ellipse's values exactly on the platform its C values were taken on and leaves them out elsewhere (`ERROR_ELLIPSE_LICENSED` in `tests/test_region_stc_oracle.rs`).
- Java: `java.lang.Math` puts the axis ends about `2e-5` from C's on this platform; `RegionStcOracleTest` leaves out their values from the `sky` and `sky_coord` dumps on every platform and compares the centre within `1e-12`.
  It compares the two half-lengths instead, within bounds worked out from `bestEllipse`'s arithmetic: the primary within `1.5e-13`, from the parabola's vertex lying at most a quarter of the rounding spread of the mesh distances above the largest; the secondary within about `1.1e-12`, the sum of three parts: C's rounding at C's direction, propagated to first order (C's axis, oracle data, lies far from every mesh point); the difference between the two directions in the systematic offset the sphere's curvature gives it (`R^3 / 18`, about `5.6e-14` above the primary on both platforms); and this platform's rounding at its own direction, bounded after the fact from the fit's own per-point values, which the test recomputes and checks against the dumped Ellipse bit for bit, so that nothing is assumed about how close this platform's axis lies to a mesh point.

### A value holding `%` set on an Adaptive Region

- C: region.c `SetAttrib` passes a setting of a Frame attribute on an Adaptive Region to `astSet( this->frameset, setting, status )`, so the caller's value is used as a `printf` format (object.c `VSet` hands it to `vsprintf` with no arguments): `Format(1)=%10.3f` stores `     0.000` and `Title=50%s done` stores `50 done`, while a Region that is not Adaptive keeps the value. **C bug.**
- Rust: has no string route.
- Java: keeps the value as given, Adaptive or not (`RegionAttributeValueTest`).

### Bad and non-finite coordinates of a Box

- C: `astInitBox` rejects a coordinate equal to `AST__BAD` (`AST__BADIN`) and accepts NaN.
- Rust: rejects `AST__BAD` and NaN.
- Java: rejects `AST__BAD`, NaN and the infinities with C's message, by the library's rule that a non-finite coordinate is bad; the membership test treats them as bad too.

### Negating a Region in place to test overlap or to mask

- C: region.c `OverlapX` negates the first Region with `astNegate` and back again to compare its negation with the second, and `Mask<X>` does the same to the Region it masks with when it masks the inside, which with no Mapping is the Region given; `astNegate` sets Negated, so afterwards Negated is set (to its old value) and what the Region kept about its boundary is discarded.
  cmpregion.c negates a component in place in the same way to mesh an OR and to test its boundary (`RegBaseMesh`, `RegPins`), and prism.c to find whether a Prism is bounded (`GetBounded`).
  After an error between the two negations, the second does nothing (the status is bad), so the Region or component is left negated.
- Rust: compares and masks with a negated copy, leaving the Region alone.
- Java: as C (`RegionOverlap.overlap`, `RegionMask.mask`); `RegionOverlapMaskProbeTest` pins the Negated flag from `src/test/resources/probes/region_overlap_mask_probe.c`.
  After an error, Java restores Negated (in `finally`) where C leaves the Region or component negated.

### The negated copy of an Adaptive Region after a change of coordinate system

- C: region.c `GetNegation` keeps a negated copy of the Region until `ResetCache`, but a Frame attribute set on an Adaptive Region goes to `astSet` on the encapsulated FrameSet with no `ResetCache`, so the copy keeps the old coordinate system: after a SpecFrame Box changes from System FREQ to WAVE, its negation is still in FREQ.
  A Region that is not Adaptive discards the copy (`RegSetAttrib`).
  Only cmpregion.c uses the copy. **C quirk.**
- Rust: makes a new negated copy each time (`negated_copy`).
- Java: as C (`Region.getNegation`); `RegionOverlapMaskProbeTest.keptNegationMatchesC` takes its values from `src/test/resources/probes/region_overlap_mask_probe.c`.

### Widening the box around a Region with no boundary mesh in `OverlapX`

- C: when the first Region has no boundary mesh, region.c `OverlapX` doubles the bounds of the other Region's mesh in the first Region's base Frame and passes them to `BndBaseMesh` as bounds in its current Frame; it doubles as many as the Region has current Frame axes, in arrays with one element for each base Frame axis, so with more current than base Frame axes it writes beyond them. **C bug.**
- Rust: doubles one bound for each base Frame axis.
- Java: as C when the arrays are long enough, and `AST__INTER` otherwise.

### A grid box beyond the `int` range in `Mask<X>`

- C: region.c `Mask<X>` turns each bound of the Region's bounding box in grid coordinates into a pixel index with `(int)round( bound ) + 2` in `int` arithmetic.
  For a bound beyond the `int` range the conversion is undefined (AArch64 saturates, giving `INT_MAX` or `INT_MIN`; x86-64 gives `INT_MIN` for either), and the sum can then overflow, so any Region whose bounds in grid coordinates pass the `int` range can get an empty box, and every pixel is then taken to be on the side of the Region its outside is.
  An Interval with no upper limit, whose bound is `DBL_MAX`, is one: masking the inside of the Interval x >= 3 on pixels 1 to 10 sets none, and masking its outside sets all ten.
  A bounded Region taken there by the Mapping is another: the Box from (-1, -1) to (1, 1) through a ZoomMap of 1e12, masked outside on a 4 by 4 grid, sets all 16 pixels though every pixel is inside it. **C bug.**
  pointlist.c has a `Mask<X>` of its own, which finds the pixel of each point and makes no grid box, so a PointList is not affected.
- Rust: computes the index in 64-bit integers, which overflow likewise.
- Java: as C (`RegionMask.mask`): the conversion saturates and the `int` sum wraps, giving the same empty box, and the same pixels on both platforms for these cases; `IntervalTest.masksMatchC` pins the Interval from `src/test/resources/probes/region_null_interval_pointlist_probe.c`, and `RegionOverlapMaskProbeTest.gridBoundsBeyondTheIntRangeMatchC` the Box from `src/test/resources/probes/region_overlap_mask_probe.c`.

### Non-finite limits of an Interval and coordinates of a PointList

- C: `astInitInterval` takes `AST__BAD` as a missing limit and any other value as a limit, NaN and the infinities included; `astInitPointList` keeps every value as given.
- Rust: takes a value that is not finite as bad (`is_bad`), a missing limit of an Interval.
- Java: a value that is not finite is bad, by the library's rule, so a NaN or infinite limit is a missing one and a NaN or infinite coordinate of a PointList is `AST__BAD`, which C's code then handles as it handles `AST__BAD`.

### A point with a bad value tested against a PointList

- C: pointlist.c `Transform` passes the points through the PointList's uncertainty, negated and centred on each of its points in turn, and then looks at the first axis alone of the result: a point whose first value is bad is kept as given by a PointList that is not negated (so that `astPointInRegion`, which looks at the first good value, finds (bad, 2) inside the PointList (1, 2), (3, 4)), and one whose first value is good and outside every uncertainty is kept, bad values and all, by a negated PointList. **C quirk.**
- Rust: a point with a bad value is outside, and `test_pointlist.rs` (`point_with_bad_value_not_inside`) pins that.
- Java: as C (`PointList.regBaseMask`); `PointListTest.pointWithBadValueIsAsCLeavesIt` takes its values from `src/test/resources/probes/region_null_interval_pointlist_probe.c`.

### Whether a set of points is the boundary of a PointList (`RegPins`)

- C: pointlist.c `RegPins` allocates one byte, not one `int`, for each element of the mask it returns, so for more than a few points it writes beyond the allocation; `astOverlap` of a PointList and a Box, which asks for the mask of the Box's 200 mesh points, corrupts the heap and usually crashes.
  Its second test, which its comment says checks that each of the PointList's points is near one of the points given, looks at as many of the PointList's points as there are points given: fewer than it has are not all looked at, and with more it reads beyond its points (within PointSet's allocation for all but the last axis, beyond it for the last). **C bug.**
- Rust: holds the mask correctly and looks at every point of the PointList.
- Java: holds the mask correctly; looks at as many of the PointList's points as there are points given when there are no more of them than it has, as C does, and at every PointList point when given more, as C's comment intends, without C's undefined read; the PointList's current Frame and Negated are left as they were.
  `src/test/resources/probes/region_null_interval_pointlist_probe.c` is linked with pointlist.c changed to allocate an `int` for each element of the mask (its header gives the recipe), so its overlaps of a PointList and a Box are C's without the overrun.
  A CmpRegion or Prism with a PointList component asks it about the points of another Region's boundary mesh (cmpregion.c and prism.c `RegPins`), so an overlap of such a Region reaches both defects; `src/test/resources/probes/region_compound_probe.c` is linked with the same change, and `CmpRegionTest.pointListComponentGivenMorePointsThanItHasMatchesC` compares with C's answer for two CmpRegions whose PointList component is given more points than it has (C answers 5, the Regions being the same).
  For the PointList (1, 2), (1, 2), (3, 4) and the PointList (1, 2), (3, 4), C answers 5 in both orders, the second through the read beyond its points, and so does Java (`PointListTest.overlapsMatchC`, probe case `pointlist_duplicated_point`).

### The bounding box of a PointList (`RegBaseBox`)

- C: pointlist.c `RegBaseBox` allocates its kept bounds, and copies them out, with one element for each axis of the current Frame, but finds one for each base Frame axis, so with more base than current Frame axes it writes beyond them. **C bug.**
- Rust: one element for each base Frame axis.
- Java: one element for each base Frame axis, the arrays every caller gives.

### The overlap of a PointList and a Box that holds some of its points

- C: region.c `OverlapX` meshes the boundary of one Region and transforms the mesh through the other; when no mesh point is inside, it decides between "apart" and "one inside the other" by whether more than half the points of the first Region's own mesh are inside the second.
  For the PointList (1, 2), (3, 4) and the Box (0, 0) to (2, 3), which holds only the first point, `astOverlap` gives 4 (partial overlap) with the Box first, from the mesh of the PointList, its two points, one inside the Box and one outside.
  With the PointList first it gives 1 (apart): the mesh of the Box is all outside the PointList, and of the PointList's own mesh one point of two is inside the Box, which is not more than half. **C quirk.**
- Rust: as C (`regions/overlap.rs`).
- Java: as C (`PointListTest.overlapsMatchC`, probe case `pointlist_across_box`).

### The bounds of a NullRegion with more base than current Frame axes

- C: nullregion.c `GetRegionBounds` writes a bound for each base Frame axis into arrays the caller makes with one element for each current Frame axis, so with more base Frame axes it writes beyond them. **C bug.**
- Rust: returns one bound for each base Frame axis.
- Java: as C when there are no more base than current Frame axes, and `AST__INTER` otherwise (`NullRegionTest.boundsWithMoreBaseThanCurrentFrameAxesAreAnError`).

### Moving an Interval leaves the bounds it keeps

- C: interval.c `RegCentre` moves the equivalent Box and takes the new limits from it, but does not mark the limits the Interval keeps as stale, so `RegBaseBox`, and the bounds of an Interval uncertainty, which `astSetUnc` moves to the Region's first point, stay where they were: the uncertainty of the Box (0, 0) to (10, 10) set to the Interval (-0.1, -0.1) to (0.1, 0.1) still has those bounds. **C quirk.**
- Rust: marks them stale.
- Java: as C (`Interval.regCentre`); `IntervalTest.anIntervalUncertaintyKeepsTheBoundsItHadBeforeItWasMoved` takes its values from `src/test/resources/probes/region_null_interval_pointlist_probe.c`.

### The overlap of two Intervals with an axis each other's negation

- C: interval.c `Overlap` combines the codes of the axes through a 5 by 5 table, which an axis whose limits are each other's negation (code 6) indexes beyond its end when the Intervals have more than one axis. **C bug.**
  For one axis C gives 6 (probe case `interval_excluded_negation`); for two Intervals whose limits are each other's negation on one axis and equal on the other, which are apart (1), the pinned build gives 6 in both orders, with that axis first or second (probe cases `interval_negation_on_first_of_two_axes` and `interval_negation_on_second_of_two_axes` of `src/test/resources/probes/region_null_interval_pointlist_probe.c`).
- Rust: indexes the same table, which panics where C reads beyond it.
- Java: as C for one axis (`IntervalTest.overlapsMatchC`), and `AST__INTER` for more (`IntervalTest.limitsEachOthersNegationOnOneOfTwoAxesAreAnError`).

### The bounds of a mapped Interval with no limit on an axis

- C: region.c `GetRegionBounds` maps the corners of the base Frame box, the largest double on an axis with no limit, through the Mapping; a ZoomMap of 2 makes them infinite, and the bounds come out infinite or NaN.
- Rust: not examined.
- Java: the Mapping makes an infinite value bad, so every corner is bad and so is every bound (`IntervalTest.mappedUnboundedIntervalMatchesC`).

### Moving an Ellipse (`RegCentre`)

- C: ellipse.c `RegCentre` moves the three defining points with `rptr[ ip ][ 0 ]` and `rptr[ ip ][ 1 ]` for `ip` from 0 to 2, taking the axis and point indices of its PointSet the wrong way round, so it reads and writes through the pointer beyond the two axis pointers: an Ellipse given as the uncertainty of a Region, which `astSetUnc` moves to the Region's first point, crashes. **C bug.**
- Rust: moves each defining point by the offset from the old centre to the new.
- Java: as Rust, which is what C's comment describes (`Ellipse.regCentre`); `src/test/resources/probes/region_circle_ellipse_probe.c` is linked with ellipse.c changed to index the points correctly (its header gives the recipe), and `EllipseTest.anEllipseUncertaintyIsMovedToTheRegionMatchesC` takes its values from it.

### An Ellipse whose points do not determine an ellipse

- C: `astInitEllipse` accepts such points; ellipse.c `Cache` reports `AST__BADIN` when the Ellipse is first used, but marks its values up to date all the same, so a second use reads values never set. **C bug.**
- Rust: reports the error on every use.
- Java: as Rust: each use reports C's error (`Ellipse.cache`).

### Moving a Circle (`RegCentre`)

- C: circle.c `RegCentre` moves the centre and the point on the circumference, keeping the radius, but does not reset what the Circle and the Region keep, so the bounds and boundary mesh of a Circle of more than two dimensions, which C keeps, stay where they were until something else resets them. **C quirk.**
- Rust: discards them.
- Java: as C (`Circle.regCentre`).

### The bounds a Circle or an Ellipse finds from its boundary mesh

- C: circle.c and ellipse.c `RegBaseMesh` find the bounds of a two-dimensional Circle or Ellipse from the axis distances of its mesh points from the centre, updating the upper bound on an axis only when a point is not below the lower bound found so far (`else if`), so the first mesh point updates only the lower bounds.
  The first point of a Circle's mesh is at angle 0, the end of its radius along the second axis, so its upper bound on that axis is that of the next highest point, short by `r (1 - cos(2 pi / MeshSize))`; an Ellipse's first point is the end of its first axis. **C quirk.**
- Rust: as C (`regions/circle.rs`, `regions/ellipse.rs`).
- Java: as C (`Circle.regBaseMesh`, `Ellipse.regBaseMesh`).

### The smaller Circle of a Circle's boundary test (`RegPins`)

- C: circle.c `RegPins` makes a Circle smaller than the one tested by half the border width, with no lower limit on its radius; a negative radius places the point on the circumference the other way, giving a Circle of the radius's magnitude.
- Rust: limits the radius to zero, and does not re-centre the uncertainties on the Circle before bounding them.
- Java: as C (`Circle.regPins`).

### The best fitting Ellipse through a mesh with no point away from its centre

- C: `astBestEllipse` looks for the first mesh point that is good and away from the centre, and when there is none reads beyond its arrays. **C bug.**
- Rust: returns no Ellipse.
- Java: `AST__INTER` (`Ellipse.bestEllipse`), an error rather than a result C does not define.

### Bad and non-finite coordinates of a Circle or an Ellipse

- C: `astInitCircle` and `astInitEllipse` reject a coordinate equal to `AST__BAD` (`AST__BADIN`) and accept NaN.
- Rust: rejects `AST__BAD` and NaN.
- Java: rejects `AST__BAD`, NaN and the infinities with C's message, by the library's rule that a non-finite coordinate is bad, as for a Box.

### Bad and non-finite vertices of a Polygon

- C: `astInitPolygon` rejects a vertex with a value equal to `AST__BAD` (`AST__BADIN`) and accepts NaN and the infinities.
- Rust: as C.
- Java: rejects `AST__BAD`, NaN and the infinities with C's message, by the library's rule that a non-finite coordinate is bad, as for a Box, a Circle and an Ellipse (`PolygonTest.nonFiniteVertexIsBad`).

### A Polygon whose edges have no length

- C: polygon.c `Cache` finds a point inside the Polygon from the edges of positive length; when none has any (a Polygon of one vertex, or of vertices that coincide) the point is never set, and `Transform` draws its lines from an uninitialised value. **C bug.**
  `astPolygon` accepts such a Polygon, and its Bounded, bounds and defining points are defined; its boundary mesh is `AST__BADIN` ("undefined length").
- Rust: the point is bad, and membership reports `AST__INTER`.
- Java: the constructor, Bounded, bounds and points as C (`PolygonTest.fewerThanThreeVerticesMatchC`); membership, and so masking and overlap, report `AST__BADIN`, the vertices being all the same point so that the Polygon has no inside (`Polygon.inside`, `PolygonTest.polygonWhoseVerticesAreOnePointHasNoInside`), an error rather than a result C does not define.

### A Polygon with a bad vertex read from a dump

- C: `astInitPolygon` rejects a bad vertex, but `astLoadPolygon` does not check, and `Cache` then reads the length of the edge `astLineDef` could not describe, through a null pointer. **C bug.**
- Rust: reports `AST__INTER` when the edges are described.
- Java: as Rust (`Polygon.cache`).

### The line across an edge that never reaches its length (`Polywidth`)

- C: polygon.c `Polywidth` halves the length of the line it draws across an edge until the line's end is that distance from its start; if no length does, the loop does not end.
- Rust: as C.
- Java: `AST__INTER` once the length reaches zero (`Polygon.polywidth`); no Frame of this library reaches it.

### Points with a bad value in a Polygon's boundary test (`RegPins`)

- C: polygon.c `RegPins` marks each point found within the margin of an edge by setting it bad, then reports a point on the boundary if it is bad, so a point given with a bad value counts as on the boundary. **C quirk.**
- Rust: counts such a point as off the boundary.
- Java: as C (`Polygon.regPins`); `PolygonTest.boundaryTestsMatchC` takes its values from `src/test/resources/probes/region_polygon_probe.c`.

### A line from a sky Polygon's point inside that passes through a vertex

- C: polygon.c `Transform` counts the edges crossed by the line from the Polygon's point inside to the point tested; an edge includes its start and not its end, so a line through a vertex should cross one of the two edges there.
  On the sky, skyframe.c `LineIncludes` takes a point up to `1e-10` radians behind the start of a line to be on it (its test of the cosine of the distance from the line's quarter point), so a crossing found within that distance before a vertex, as rounding may place one at the vertex, is on both the edge ending there and the edge starting there, and is counted twice. **C bug.**
  The line across the edge between two vertices at the same latitude, from which `Cache` takes the point inside, is a meridian; for a triangle round the north pole with its vertices at one latitude, the line from the point inside to the south pole passes through a vertex, and C finds the south pole inside the triangle, so `astGetRegionBounds` gives -pi/2 as its lower latitude.
- Rust: as C.
- Java: as C, by the same arithmetic, which rounding decides; `RegionSkyTest` keeps its sky Polygons clear of such lines (`pointsAndPixelsAreClearOfTheBoundaries`).
  `FrameLineTest` pins the quirk itself in the line functions, where rounding does not decide it: a point 5e-11 radians behind the start of a line is on it, and one 2e-10 radians behind is not (probe `src/test/resources/probes/frame_line_probe.c`).

### A CmpRegion that ANDs two unbounded Regions, one inside the other

- C: cmpregion.c `GetBounded` takes an AND of two unbounded Regions to be bounded unless `astOverlap` finds them apart (1), partly overlapping (4) or each other's negation (6); when one is inside the other (2, 3) or they are the same (5) the AND is the inner Region, which is unbounded, yet it is reported bounded, and its bounds then fail with `AST__INTER` ("No mesh can be produced ... neither of its component Regions has a mesh"), since `RegBaseBox` meshes a bounded CmpRegion.
  Conversely an AND of Regions apart is empty but reported unbounded. **C quirk.**
  A negated OR of two nested bounded Regions is such an AND, and is reported bounded too.
- Rust: as C (`CmpRegion::get_bounded`).
- Java: as C (`CmpRegion.isBoundedInner`); `CmpRegionTest.nestedUnboundedRegionsMatchC` and `orOfCirclesMatchesC` take their values from `src/test/resources/probes/region_compound_probe.c`.

### A point on the boundary of a negated closed CmpRegion

- C: cmpregion.c `Transform` applies a negated CmpRegion as the other operator of its components' negations (`GetRegions`), and a closed Region holds its boundary whether or not it is negated, so a point on the boundary of a closed CmpRegion is inside both the CmpRegion and its negation.
  The AND of the square [0, 1] x [0, 1] with the negation of the OR of the unit Circles about (-1, 0) and (1, 0), negated, keeps (0, 0.5), on the square's edge. **C quirk.**
- Rust: `test_regions.rs` `cmpregion_negated_reversal` expects that point to be made bad; the Rust library was not examined beyond its test.
- Java: as C; `RegionsTest.cmpRegionNegatedReversal` takes the value from `src/test/resources/probes/regions_cross_probe.c`.

### The overlap of a Prism and a negated Region

- C: prism.c `Overlap` picks from the other Region the axes of each component of the Prism; region.c `PickAxes` gives the Regions it picks the Negated value of the Region they come from (`astRegOverlay`), so each pair of components is compared with that negation already applied, and `Overlap` then adjusts the combined code for the other Region's negation again.
  A Prism inside a Box and the Box's negation give 2 and 3 (one inside the other) rather than 1, and a Prism and its own negation give 5 one way round and 6 the other. **C bug.**
- Rust: not examined.
- Java: as C (`Prism.prismOverlap`); `PrismTest.overlapsMatchC` takes its codes from `src/test/resources/probes/region_compound_probe.c`.

### The boundary mesh of a Prism with other than its base Frame's number of axes

- C: prism.c `RegBaseMesh` makes a PointSet with one coordinate for each axis of the Prism's current Frame and fills it from its components' meshes and grids, one coordinate for each base Frame axis, so a Prism mapped into a Frame of more or fewer axes is written or read beyond the arrays. **C bug.**
- Rust: not examined.
- Java: `AST__INTER` (`Prism.regBaseMesh`), an error rather than a result C does not define.

### Meshing a Region with no mesh inside a box (`BndBaseMesh`)

- C: region.c `BndBaseMesh` meshes the part of a Region's boundary inside a box as the boundary mesh of the CmpRegion ANDing the Region and the box; when the Region has no mesh of its own (a Prism with an open end, such as the Interval x >= 0 extruded along the Box 0 <= y <= 1, or with a negated component), cmpregion.c `RegBaseMesh` meshes it inside the bounds of the box with `astBndMesh`, which calls `BndBaseMesh` with the same box again, so C recurses until the stack overflows (segfault).
  `astOverlap` of such a Prism and a Circle, Ellipse, Polygon or AND CmpRegion, in either order, reaches it when the other Region is wholly inside or outside the Prism, and so does the mesh of the AND of such a Prism and a Region whose bounds cross its boundary. **C bug.**
- Rust: not examined.
- Java: `AST__INTER` with region.c `OverlapX`'s message that it cannot determine the overlap, from the CmpRegion that `BndBaseMesh` makes (`CmpRegion.baseMeshInsideBox`); the Regions are left as they were (`RegionOverlapTest`).
  Where `OverlapX` asks for a mesh it can do without, it ignores the error, as C ignores any, and goes on, so an overlap that reaches the recursion there gives a code: the Circle of radius 3 about (0, 0.5) and the AND of the Prism above with the Circle of radius 3 about (5, 0.5) give 4 in either order; C gives 4 with the AND first and overflows its stack with the Circle first (`RegionOverlapTest.anAndHoldingAPrismWithNoMeshCanStillOverlap`).

### Moving a Prism (`RegCentre`)

- C: prism.c `RegCentre` moves its components but does not reset what the Prism keeps, so its boundary mesh stays where it was until something else resets it. **C quirk.**
- Rust: discards it.
- Java: as C (`Prism.regCentre`).

### The MeshSize of two Regions merged in parallel

- C: `MergeBox`, `MergeInterval`, `MergeNullRegion` and `MergePointList` pass `fac_this*fac_reg*astGetMeshSize( result )`, a `double`, to `astSetMeshSize`, whose argument is an `int`, so the scaled MeshSize is truncated.
- Rust: rounds it (`merge_util.rs` `apply_merge_scaling_to`).
- Java: as C (`RegionMerge`).

### An Ellipse with a Box uncertainty mapped through a full MatrixMap and back

- C: `testregions.c` `checkEllipse` Errors 15 to 17: each mapped Ellipse has no FrameSet and the round trip overlaps the original as the same Region.
- Rust: excludes the check (`REGIONS_C_GAP_EXCLUDED` in `test_regions_c.rs`): its mapped Ellipse keeps the rotation in its uncertainty's FrameSet, and mapping back fails.
- Java: as C (`RegionsCTest.checkEllipse`).

### The order in which a Box's simplification tries the corners of a Polygon

- C: box.c `Simplify` makes the Polygon of a 2-D Box's transformed corners in clockwise order first (`right_handed` 0), then anticlockwise, keeping the first whose inside is the Box's.
- Rust: tries `right_handed` 1, the anticlockwise order, first.
- Java: as C (`Box.polygonOf`); the order matters only if both orders qualify.

### A Box whose transformed corners make no Polygon with the Box's inside

- C: box.c `Simplify` annuls each Polygon that does not qualify and then passes the annulled (null) pointer to `astRegPins`, which reports `AST__OBJIN`. **C bug.**
- Rust: leaves the Box as it is.
- Java: `AST__OBJIN`, as C, with a message of its own (`Box.polygonOf`).

### A Circle fitted in its current Frame

- C: circle.c `Simplify` does not count a best-fitting Circle as a simplification: it is returned only if the Region-generic simplification changed the Circle, while a best-fitting Ellipse always is. **C quirk.**
- Rust: as C.
- Java: as C (`Circle.simplifyStep`).

### The limits an Interval's simplification makes unequal

- C: interval.c `Simplify` replaces an upper limit equal to its lower limit by the larger of the two times `1.0E6*DBL_EPSILON`, not by a value just above it. **C quirk.**
- Rust: as C (`regions/interval.rs`).
- Java: as C (`Interval.simplifyStep`).

### The mean limit of a best-fitting Box on the negative side of an axis

- C: box.c `BestBox` sets the clipping limit of a mean to `100*mean*DBL_EPSILON` when the values scatter less than that, which is negative for a negative mean, so no value is kept and the limit falls back to the extreme value. **C quirk.**
- Rust: as C (`regions/box_region.rs`).
- Java: as C (`Box.bestBox`).

### An uncertainty set on a Prism that does not simplify

- C: prism.c `Simplify` sets the Prism's own uncertainty on the Region it returns, which is the Prism itself when nothing simplifies, so the Prism given is changed. **C quirk.**
- Rust: sets the uncertainty on a new Region, and returns a copy of the Prism when nothing simplifies, so the Prism given is not changed (`regions/prism.rs`).
- Java: as C (`Prism.simplifyStep`).

### What removing the Regions from a Mapping gives (`RemoveRegions`)

- C: region.c `RemoveRegions` returns a copy of the Region's current Frame, for every Region class.
  A container turns a component result that is a Frame (`astIsAFrame`) into a UnitMap: cmpmap.c and tranmap.c every such component, so also a plain Frame component that did not change when the other one did; ratemap.c, switchmap.c and frameset.c only a component that changed, so a plain Frame route of a SwitchMap or a Frame used as a Mapping of a FrameSet stays a Frame.
  A CmpFrame or FrameSet that changed is a copy holding clones of its unchanged components, so they are shared with the original, and an axis Label set on one is seen in the other.
- Rust: a Region's `mapping_without_regions` returns a UnitMap, and its Frames are not Mappings, so a plain Frame component cannot arise; the copies own their components.
- Java: as C (`removeRegions`); `RemoveRegionsTest` pins each case from `src/test/resources/probes/remove_regions_extra_probe.c`.

### A FrameSet component loses its Mapping when the Regions are removed

- C: `astIsAFrame` is true of a FrameSet, so tranmap.c `RemoveRegions` turns a FrameSet component of a TranMap into a UnitMap whenever either component changed, and ratemap.c and switchmap.c turn a FrameSet component that held a Region into a UnitMap, dropping the FrameSet's Mapping: a TranMap of a FrameSet holding a ZoomMap and a Box becomes a UnitMap. **C quirk.**
  A CmpMap is not affected, since `astInitCmpMap` holds a FrameSet's Mapping in its place.
- Rust: keeps a FrameSet component, with its own Regions removed.
- Java: as C (`Mapping.frameToUnitMap`); `RemoveRegionsTest.aTranMapTurnsAFrameSetIntoAUnitMap` pins it.

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

### SpecTrans translations of a keyword the header repeats

- C: `SpecTrans` writes each translation into a new FitsChan, which `WcsToStore` reads after every card of the header, so a translation outranks every card with its keyword: `CDELT1 = 0.0` then `CDELT1 = 0.25` reads with CDELT1 1.0, and a CD matrix gives CDELTj 1.0 however many CDELTj cards the header has (C probe).
  A second translation of one keyword replaces the first, its comment kept by `SetFits`'s rules in that FitsChan.
  `WcsFcRead` stores the comment of each CTYPE card it visits, but `SetItemC` ignores a card with none, so the comment kept is that of the last card with one; with several "other" axes it becomes the axis Label (`fitschan.c:36848-36860`, `fitschan.c:39283-39304`).
- Rust: `put_or_append` in `fitschan/card_list.rs` writes the translation over the card after the current one or the first card with the keyword, keeping that card's comment when the values are alike, so a later card with the keyword outranks it; the comment stored for a CTYPE is that of its last card, none if that card has none.
- Java: as C (`FitsCardList.setTranslation`, `FitsCardList.getStoredComment`).

### A NAXISn that is not a number

- C: `DistortMaps` reads NAXIS1 to NAXISn, n the number of WCS axes of the description, with `astGetFitsF`, so `NAXIS1 = 'abc'` or `NAXIS1 = (1.0, 2.0)` makes the read fail with AST__FTCNV; a NAXISn beyond the WCS axes is not read (`fitschan.c:9017-9021`, C probe).
- Rust: `fitschan/wcs_keywords.rs` reads the NAXISn it can convert and leaves the others unset, so the read succeeds.
- Java: as C.

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

### Two SkyFrames or two SpecFrames in `MakeFitsFrameSet`

- C: `MakeFitsFrameSet` marks a world Frame with axes of two SkyFrames (of Domain `SKY`), or of two SpecFrames, unusable and makes no FrameSet, but then passes the null FrameSet to `AddEncodingFrame`, which dereferences it (a C-oracle probe writing a CmpFrame of two SkyFrames crashes in `astGetFrame_`); so it does when the celestial axes it makes or finds for a SpecFrame's reference position give no FrameSet or no fiducial point (`fitschan.c` `MakeFitsFrameSet`, `AddEncodingFrame`). **C quirk.**
- Rust: `make_fits_frameset` in `fitschan/fset_to_store/mod.rs` looks for neither a second SkyFrame nor a second SpecFrame.
- Java: makes no FrameSet, so that the description cannot be made, which is what `AddVersion` does with the null FrameSet C means to return.

### A SpecFrame's reference position in `MakeFitsFrameSet`

- C: when the SpecFrame of the world Frame has RefRA and RefDec set, and the world Frame has no SkyFrame and at most two axes, `MakeFitsFrameSet` adds FK5 J2000 celestial axes, read by a FitsChan from a TAN header with the reference position at pixel (0, 0) and the SpecFrame's Epoch as MJD-OBS, the two new pixel axes being given the constant 0; when the world Frame has a SkyFrame, it sets the SkyFrame to FK5 J2000 and sets RefRA and RefDec through the FrameSet to the fiducial point of the projection, formatted by the SkyFrame with Digits 20, then sets back the SkyFrame's System and Equinox if they were set (`fitschan.c` `MakeFitsFrameSet`).
- Rust: `make_fits_frameset` in `fitschan/fset_to_store/mod.rs` adds no celestial axes, and sets RefRA and RefDec on the SpecFrame to the fiducial point, normalised, without formatting it.
- Java: as C (`FitsFsetToStore.makeFitsFrameSet`).

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

### A failed write loses a card it replaced

- C: a writer that stores a keyword a card already has replaces that card with a new one (`SetValue`), and when the write then fails `FixNew` removes the new cards, so the card replaced is lost: a FITS-PC write of a TPN FrameSet into a FitsChan holding `CRVAL1 = 5 / mine` stores CRVAL1 before refusing TPN, returns 0, and leaves the FitsChan without a CRVAL1 card (C probe) (`fitschan.c` `SetValue`, `FixNew`, `PCFromStore`). **C quirk.**
- Rust: the same (`put_or_append` in `fitschan/card_list.rs` replaces the card with one flagged new, which `fix_new` removes).
- Java: as C (`FitsChanWriteTest.aFailedWriteLosesACardItReplaced`).

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

### `AIPSFromStore` and `AIPSPPFromStore`

- C: both write the primary description alone, refusing it when the number of CRPIX values differs from the number of pixel axes (`store->naxis`).
  Writing CRPIX, CRVAL and CTYPE stops at a missing value or a `-TAB` axis, but CDELT, CUNIT, CROTA (or the CD matrix), EPOCH, LATPOLE, LONPOLE, DATE-OBS, MJD-OBS, PROJPm and RESTFREQ are still written before the function returns 0, leaving the cards for `Write` to remove.
  `AIPSFromStore` writes CROTA whenever there is a pair of rotation axes, zero included; `AIPSPPFromStore` writes it only when it is not zero, and a CD matrix when the two estimates of CROTA disagree.
  `AIPSPPFromStore` writes any LONPOLE and LATPOLE stored, 180 degrees included.
  RESTFREQ is written only with a spectral axis; a spectral axis needs a SPECSYS of LSRK, LSRD, BARYCENT or GEOCENTR (not TOPOCENT) and units of Hz or m/s or a multiple of them.
  DATE-OBS is formatted from MJD-OBS, and MJD-OBS is written only over a card the header already has (`fitschan.c` `AIPSFromStore`, `AIPSPPFromStore`).
- Rust: `aips_store_ok` and `write_to_cards`/`write_to_cards_pp` in `fitschan/enc_aips.rs` check every value before writing; `write_to_cards_pp` leaves out a LONPOLE of 180 degrees; `write_to_cards` writes RESTFREQ without a spectral axis and CROTA only with two or more axes; `translate_aips_spectral_write` writes TOPOCENT as `-OBS`; DATE-OBS comes from the store's date string.
- Java: as C (`FitsEncAips`).

### `PROJPm` written by `AIPSPPFromStore` are not read back

- C: `AIPSPPFromStore` writes the projection parameters of the latitude axis as `PROJPm`, but `SpecTrans` translates `PROJPm` to `PV<axlat>_m` only for FITS-PC and FITS-IRAF (`fitschan.c` `SpecTrans`), so a FITS-AIPS++ header read as FITS-AIPS++ loses them. **C quirk**.
- Rust: as C.
- Java: as C.

### The SIN check of `AIPSFromStore`

- C: `AIPSFromStore` sets its status from the SIN projection parameters alone, overriding an earlier refusal for a missing CRVAL on a celestial axis; the description is then refused when CRVAL is written (`fitschan.c` `AIPSFromStore`). **C quirk** (no effect on the result).
- Rust: `aips_store_ok` in `fitschan/enc_aips.rs` combines the checks.
- Java: as C.

### The cards a DSS read uses, and the sign of PLTDECSN

- C: `DSSToStore` reads every keyword with `GetValue`, which marks the card it finds as used: the plate solution's keywords, and PLTSCALE, PPO1, PPO2, PPO4 and PPO5, read only so that they are not copied to another header.
  A required keyword that is missing stops the read, the cards read before it marked (used if Clean is set, made available again otherwise).
  The declination is negative only when the first word of PLTDECSN is `-` (`fitschan.c` `DSSToStore`).
  The description has WCSAXES 2.
- Rust: `read_from_cards` in `fitschan/enc_dss.rs` does not read PLTSCALE, PPO1, PPO2, PPO4 or PPO5, so they stay unused; it takes the declination to be negative when PLTDECSN starts with `-` (so `-5` is negative where C's is positive); and it sets no WCSAXES.
- Java: as C (`FitsEncDss`).

### `CLASSFromStore` with celestial pixel axes of one pixel

- C: when the image has three or more pixel axes and both celestial pixel axes span one pixel, `CLASSFromStore` transforms pixel (1, 1, 1) with the FrameSet written and stores the celestial result as CRVAL, with CRPIX 1, before its other checks; `astTranN` is given three coordinates whatever the number of pixel and world axes, so a FrameSet with four pixel axes makes the write fail with `AST__NCPIN`.
  It looks up the sizes of those pixel axes in `dim` at the indices of the celestial WCS axes (`fitschan.c` `CLASSFromStore`). **C quirk** (three coordinates for any FrameSet; WCS axis indices into the pixel dimensions).
- Rust: `class_store_ok` in `fitschan/enc_class.rs` reads the NAXIS cards for the rotation and LONPOLE checks but neither moves the reference point nor transforms anything, so it writes the reference point unchanged and reports no error for four axes.
- Java: as C (`FitsEncClass`).

### The cards `CLASSFromStore` writes

- C: `CLASSFromStore` writes CRPIX, CRVAL and CTYPE of the first three axes, each loop stopping at a missing value or a `-TAB` axis, and then still writes CDELT, EQUINOX, DATE-OBS (and MJD-OBS over a card the header has), RESTFREQ, IMAGFREQ, OBJECT, LINE, the velocities and the azimuth and elevation before returning 0, leaving the cards for `Write` to remove.
  Each CTYPE comment is the description's, if it has one.
  EQUINOX is 1950 for FK4 and 2000 for FK5 when the description has a RADESYS and no equinox.
  A description of one pixel axis, to which `MakeFitsFrameSet` added celestial axes, is written with NAXIS 3 and NAXIS2 and NAXIS3 of 1.
  VELO-LSR, VLSR and DELTAV convert the spectral CRVAL and CRVAL plus CDELT, both in Hz, with a SpecFrame matched in the current Frame of the FrameSet written; AZIMUTH and ELEVATIO convert the celestial CRVAL with SkyFrames matched there, and are normalised with the AZEL template SkyFrame (`fitschan.c` `CLASSFromStore`).
  When the description has fewer than three axes with a CRPIX and the spectral axis is not among them, C scales and writes `cdelt[axspec]` past the end of its array of one element per axis written (`fitschan.c:7015`).
  C also reads uninitialised elements of its `crval` array, those of axes its CRVAL loop did not reach, when it computes VELO-LSR and DELTAV (the spectral axis) and AZIMUTH and ELEVATIO (the celestial axes).
  **C quirk** (an over-run and uninitialised reads), neither reachable from a FrameSet, since every WCS axis has a CRPIX.
  The pinned C has the fixes the Rust repository's `docs/issues/c-library-quirks.md` lists as upstream aea3090b (a LONPOLE of +180 or -180 degrees is allowed and any other refused, as a66a0cb5) and 17416d33 (the spectral CDELT is scaled to Hz once).
- Rust: `class_store_ok` in `fitschan/enc_class.rs` checks every value before `write_to_cards` writes any; `write_to_cards` gives every CTYPE the default comment, writes EQUINOX only when the description has one, and leaves NAXIS alone; `write_velocities_from_frameset` takes the frequencies from the cards written, finding the spectral axis by its CTYPE; `write_azel_from_frameset` finds the celestial axes by their CTYPE and normalises with the matched AZEL Frame.
- Java: as C (`FitsEncClass`), except that the over-run throws `AstException` with status `AST__INTER` and an unreached `crval` element reads as 0.

### `ClassTrans`

- C: `SpecTrans` calls `ClassTrans` for the primary description of a FITS-CLASS header after translating DATE-OBS and before the SAO, NCP, ATF and GLS translations.
  It reports `AST__BDFTS` when there is neither RESTFRQ nor RESTFREQ, when the spectral CTYPE (of the one of the first three axes that is not celestial, axis 2 when there are none) is not FREQ, ignoring case and trailing spaces, and when that axis has no CRVAL.
  It writes the CRVAL plus the rest frequency, SPECSYS `SOURCE`, SSYSSRC and ZSOURCE with no comment, and warns (`badval`) when there is no VELO-xxx keyword, assuming LSRK and a velocity of zero.
  A blank projection code becomes `-GLS`, which the GLS translation does not change to `-SFL`, since it tests the projection code `SpecTrans` took before `ClassTrans` ran.
  The reference frequency of the radio velocity is a variable set only when the spectral axis has a CTYPE; otherwise it is not initialised, and the recorded outputs of the wcsconv fixtures `class-spec.head` and `class-spec-hel.head`, whose one axis is not axis 2, encode 0 (`fitschan.c` `ClassTrans`). **C quirk** (an uninitialised variable).
- Rust: `translate_class` in `fitschan/spec_trans.rs` runs after every other translation, and skips what C reports: no rest frequency, a spectral CTYPE other than FREQ (compared after trimming spaces at both ends) and a missing CRVAL; it gives the cards it writes comments.
- Java: as C (`FitsSpecTrans`); the uninitialised value is 0, as Rust's.

### Which CTYPE values `WcsSpectral` takes as spectral

- C: `IsSpectral` compares the first four characters of the CTYPE, without trailing spaces, with the ten FITS-WCS paper III codes case by case, and accepts the axis only when the algorithm code after them is blank or starts with `-`; any other axis is left to `WcsOthers` as a linear axis (`fitschan.c` `IsSpectral`), so `freq` gives a plain Frame axis with Domain `FREQ`.
- Rust: `parse_spectral_ctype` in `fitschan/wcs_spectral.rs` takes the first four characters through `SpecSystem::parse`, which ignores case and accepts the long System names, and does not check the algorithm code, so `freq` makes a SpecFrame and `FREQXYZW` fails with an unknown algorithm code.
- Java: as C (`FitsWcsSpectral.spectralAxes`, `FitsStore.isSpectral`).

### The order of the SpecFrame settings in `WcsSpectral`

- C: sets System, the reference position, Unit (CUNIT, or the system's default unit, which is blank for ZOPT and BETA), Epoch, RestFreq (set to `AST__BAD` when there is neither RESTFRQ nor RESTWAV), ObsLat/ObsLon/ObsAlt, SourceVRF, SourceVel, StdOfRest and Label, in that order, and then calls `DSBSetUp` with the AXREF value, else the CRVAL value, which is `AST__BAD` when there is neither (`fitschan.c` `WcsSpectral`).
- Rust: `build_spectral_axis` sets StdOfRest second, skips the Unit when it is blank, so a ZOPT or BETA axis with no CUNIT has no Unit set, and gives `dsb_set_up` an observation centre of 0 when there is neither AXREF nor CRVAL.
- Java: as C (`FitsWcsSpectral`); C's blank Unit is set and recorded as the Unit used with the System.

### How `SpectralAxes` tries the non-linear and grism algorithms

- C: `SpectralAxes` tries each System of the `-X2P` algorithm (FREQ, WAVE, AWAV and VELO, the axis's own System among them) and of the grism algorithm (WAVE and AWAV in m) by setting it, and clearing or setting the Unit, through the FrameSet being written, which remaps its current Frame, and sets the original System and the linear Unit back after each, so the FrameSet's Mapping goes through each conversion and back, and a Unit that was not set is left set; it gives `IsMapTab1D` no unit (`fitschan.c` `SpectralAxes`).
- Rust: `try_x2p_cascade` and `try_grism_cascade` in `fitschan/fset_to_store/spectral_axes.rs` convert clones of the SpecFrame and join the conversion to the pixel to world Mapping themselves, leaving the FrameSet unchanged; they skip the axis's own System, and pass to the next System when the reference value or the rate does not convert; `is_map_tab_1d` is given the linear Unit.
- Java: as C (`FitsSpectralAxes`).

### The keywords `SpectralAxes` stores

- C: `SpectralAxes` describes every SpecFrame axis of the world Frame; uses the reference value it has even when it is bad; takes a set Label, blank or not, as the CTYPE comment and CNAME; gives the Local_group standard of rest as `LOCALGRP` (`GetFitsSor`); and finds VELOSYS by setting SourceVRF and SourceVel on the SpecFrame in the FrameSet, setting them back only when SourceVel was set, so that SourceVRF is left set (`fitschan.c` `SpectralAxes`).
- Rust: `spectral_axes` in `fitschan/fset_to_store/spectral_axes.rs` describes the first spectral axis only; describes none when the reference value is bad; skips a blank Label; gives `LOCAL` for the Local_group standard of rest; and finds VELOSYS on a clone of the SpecFrame.
- Java: as C (`FitsSpectralAxes`).

### The default reference value of `SpectralAxes`

- C: the default reference value is the middle of the spectral range when the size of the one pixel axis that feeds the spectral axis is known, found by splitting the inverted pixel to world Mapping, and the value at pixel 1 otherwise (`fitschan.c` `SpectralAxes`).
  The Rust repository's `docs/issues/c-library-quirks.md` lists this as a fix made after the pin (upstream e09bc221), but the pinned C has it, as commit 0dfd0f74.
  It also has the fixes the same file lists as upstream 85e75a4e (`IsMapLinear` centres the values it fits, as d2c047eb) and 7f4ba71d (`AddVersion` takes the pixel to world Mapping again after the axes are described, as 12e9ed4a).
- Rust: as C.
- Java: as C (`FitsSpectralAxes`, `FitsIsMapLinear`, `FitsFsetToStore.addVersion`).

### `SpectralAxes` changes Hz to GHz for a narrow spectral range

**C quirk.**

- C: `SpectralAxes` keeps the SpecFrame's Unit only if `IsMapLinear` finds the conversion from it to the default Unit linear over the spectral range of the image (`fitschan.c` `SpectralAxes`), and `IsMapLinear` requires every residual of its fit to be within 1e-7 of the range.
  For a range that is narrow beside its values the conversion fails the test although it is a ZoomMap: the axis of `wcsconv/inputs/freq-log.head` spans about 1 Hz at 1.4204e9 Hz, so its values in GHz are rounded to about 2e-7 of their range, and C clears the Unit and writes the axis as `FREQ` in GHz (`CRVAL1 = 1.4204`, `CDELT1 = 1.000000023045475E-12`), where a range of 1e8 Hz keeps Hz.
  The Unit is cleared on the FrameSet the write describes, which is not the caller's, so the caller's FrameSet keeps its Unit.
- Rust: as C (`is_map_linear` has the same tolerance, and `spectral_axes` makes the same test).
- Java: as C (`FitsSpectralAxes`, `FitsIsMapLinear`); probe `src/test/resources/probes/fitslog_probe.c` cases `freq_log_head` and `freq_log_head_unit`.

### The MathMap of `LogAxis`

- C: `LogAxis` makes the MathMap `logs=log(s)`, inverse `s=exp(logs)`, with SimpFI and SimpIF set, tests the axis with it and puts the same MathMap in the `-LOG` Mapping it returns; it tests neither the axis index nor whether the reference value is finite (`fitschan.c` `LogAxis`).
- Rust: `log_axis` in `fitschan/fset_to_store/log_axis.rs` makes the MathMap without SimpFI and SimpIF, and returns `None` for an axis index out of range or a reference value that is not finite.
- Java: as C (`FitsOtherAxes.logAxis`).

## Resampling

### Fitting the inverse of the Mapping while resampling

- C: `ResampleAdaptively` inverts the Mapping in place with `astInvert`, calls `astLinearApprox`, and inverts it back; the Mapping is the caller's own when the output region has 1024 pixels or fewer, or the Mapping is already simplified, since `astSimplify` then returns it.
- Rust: `Resampler::adaptively` in `mappings/resample/mod.rs` copies the Mapping and inverts the copy before each fit.
- Java: `Resample.adaptively` fits the inverse transformation directly (`LinearApprox.linearApprox` with `forward` false), which gives C's fit without inverting or copying the Mapping.

### Array lengths in resampling

- C: cannot check the lengths of the arrays it is given.
- jniast: throws `IllegalArgumentException` ("bound arrays too short", "data/variance arrays too short") when a bound array has fewer elements than its number of dimensions or a data or variance array fewer than its grid has pixels.
- Rust: `resample` reports AST__DIMIN when a data or variance array has a length other than its grid's number of pixels, or bound arrays differ in length.
- Java: AST__DIMIN with Rust's messages when a data or variance array has fewer elements than its grid has pixels, or a bound array fewer than the number of dimensions given; a longer array is accepted and its leading elements used, as in C and jniast (see "Array arguments longer than needed").

### The bad value NaN in resampling

- C: an input pixel is bad when it equals `badval` in the data type, so with `badval` NaN (as SPLAT and astgui pass it) no pixel is ever bad, even with `AST__USEBAD`, and NaN data reaches the output through the interpolation; output pixels with no value are still set to NaN and counted (`InterpolateNearest<X>`, `InterpolateLinear<X>`). **C quirk.**
- Rust: as C.
- Java: as C; probe `src/test/resources/probes/resample_nan_probe.c` (`ResampleNanTest`).

### The `SombCos` resampling kernel is half `Somb` except at zero offset

- C: `SombCos` forms its first factor as `J1(x) / x`, where `Somb` uses `2.0 * J1(x) / x`, but takes it as 1 at zero offset, so the pixel at the interpolation point counts twice as heavily as the documented kernel `somb(pi*x)*cos(k*pi*x)` would make it. **C quirk.**
- Rust: as C (`kernels::somb_cos`).
- Java: as C (`ResampleKernels.sombCos`); the oracle case `sombcos_unit` pins it.

### Block averaging keeps one bad-variance flag per block

- C: `InterpolateBlockAverage<X>` resets `bad_var` only when it starts a block, so for a signed type without `AST__USEBAD` a negative variance leaves out the rest of the block, and with `AST__USEBAD` the output variance is bad exactly when the variance of the block's last good pixel was. **C quirk.**
- Rust: as C (`interp::block_average`).
- Java: as C (`ResampleInterpolators.blockAverage`); the oracle cases `blockave_novarbad` and `blockave_win3` fail if the flag is reset for each pixel.

### Block averaging with a zero variance

- C: `InterpolateBlockAverage<X>` weights each pixel by `1.0 / var` and leaves out only negative variances (and, with `AST__USEBAD`, bad ones), so a zero variance gives an infinite weight, and the value `sum / wtsum` and the variance `sum_var / (wtsum * wtsum)` are NaN.
  A NaN passes `CALC_AND_ASSIGN_OUTPUT`'s range test (`val <= LO_<X> || val >= HI_<X>` is false), so it is assigned and not counted as bad: a `double` or `float` output and its variance are NaN, and for `long`, `int`, `short` and `byte` C converts the NaN to the type, which is undefined (UBSan reports it).
  The pinned library on AArch64 gives 0; other platforms need not (x86-64's conversion instructions give `INT_MIN` for `int` and `LONG_MIN` for `long`). **C quirk.**
- Rust: as C, with Rust's `as`, which gives 0 for a NaN (`interp::block_average`, `PixelValue::from_sum`).
- Java: as the pinned library on AArch64: `PixelArray.overflows` is false for a NaN, and Java's casts give 0 for a NaN (`short` and `byte` through `int`), so an integer output and its variance are 0 (`ResampleInterpolators.blockAverage`, `PixelArray.setResampled`); the differential run against C found the outputs identical.

### Block averaging at a position far outside the input grid

- C: `InterpolateBlockAverage<X>` takes the pixel index of a position as `(int) floor(x)` and the block's bounds as `ix - neighb + 1` and `ix + neighb` in `int`, with no test that the position lies in the grid; for a position beyond the range of an `int` the conversion is undefined, and so is an overflow of the bounds, so with `npix` of zero or less, whose block is empty, a position that far outside need not give a bad output. **C bug.**
- Rust: `x.floor() as i64` saturates, and the bounds are formed in `i64`, which overflows (a panic in a debug build) for a position at or beyond 2^63 with `npix` of zero or less.
- Java: an empty block, and so a bad output, wherever the position lies: `ResampleInterpolators.blockIndex` holds the index within 2^32 of the grid, which leaves every block the same since a block reaches less than 2^31 pixels, and keeps its bounds within a `long` (`ResampleArgumentsTest.anEmptyBlockIsBadAtAnyPosition`).

### Flux conservation of an integer output beyond its type's range

- C: `ConserveFlux<X>` scales each output with `out[off] *= factor`, converting the product back to the type with no range check, which is undefined when it lies outside the type's range (UBSan reports it for `short` and `signed char`); the pinned library on AArch64 converts to a 32-bit (or, for `long`, 64-bit) integer, saturating, and keeps the low 16 or 8 bits for `short` and `signed char`.
  It also leaves alone any output equal to the bad value, so a good integer output that equals it is not scaled. **C quirk.**
- Rust: converts with `as`, which saturates to the type's own range (`PixelValue::from_f64`), so a `short` or `byte` product beyond the range differs from the pinned library.
- Java: as the pinned library on AArch64 (`Resample.conserveFlux`, `PixelArray.set`: Java's `(int)` and `(long)` casts saturate, and `short` and `byte` go through `int`); the differential run against C found the outputs identical.

### A caller's kernel or interpolation function that fails

- C: the caller's function reports a failure by setting the status, and `InterpolateKernel1<X>` or `ResampleSection` adds a message and passes that status on; the output keeps what was set before the failure, and `ConserveFlux<X>` does not scale the failing block.
- jniast: `fukern1` and `fuinterp<X>` set `AST__UK1ER` and `AST__UINER` when the Java method throws.
- Rust: the caller's function returns an error, which `resample` returns with C's message added.
- Java: an `Exception` thrown by a `Ukern1Calculator` or `UinterpCalculator` becomes an `AstException` with jniast's status, C's message and the exception as its cause; an `Error` passes through unchanged; the output is C's (probe `src/test/resources/probes/resample_callback_probe.c`, `ResampleCallbackTest`).

### Elementary functions in the resampling kernels

- C: the kernels call the C library's `sin`, `cos` and `exp`.
- Rust: as C (the `f64` methods, which call the platform's library).
- Java: the kernels call `java.lang.Math`, which can differ from C's library in the last bit; eight `double` cases of the resample oracle miss their hash on AArch64 macOS by at most 7.1e-15.
  `ResampleOracleTest` compares every case whose kernel calls these functions value by value with probe `src/test/resources/probes/resample_kernel_probe.c`, within a bound worked out for each output pixel from its weights (about 1e-12 for `double`; one for an integer type, whose value must also lie clear of the type's range limits).

### Resampling with the `AST__GAUSS` scheme

- C: `astResample<X>` accepts `AST__GAUSS`.
- jniast: `Mapping.Interpolator` has no factory for it, though `Mapping.Spreader` has `gauss`.
- Java: as jniast for the public API; resampling accepts the scheme, which the tests reach through the package-private `Interpolator` constructor.

## Rebinning

### Variances in `rebin`

- C: `astRebin<X>` rebins variances only with the flag `AST__USEVAR` and both variance arrays; given an output variance array without it, C sets the array to zero (and bad where `wlim` sets the data bad).
- jniast: `rebin` and `rebinI/F/D` pass `AST__USEBAD` alone, and the output variance array only with the input one, so C zeroed the output variances and never filled them, though jniast's documentation promises them.
- Rust: `rebin` takes `use_var` as a flag of its own.
- Java: `AST__USEVAR` is added when both variance arrays are given, so the variances are rebinned; with either array absent the flags are jniast's and the result is unchanged, the output variances, if given without input ones, being left as they are, as jniast leaves them (probe `src/test/resources/probes/rebin_jniast_probe.c`, `RebinJniastTest`).

### Array lengths in rebinning

- C: cannot check the lengths of the arrays it is given.
- jniast: throws `IllegalArgumentException` ("bound arrays too short", "data/variance arrays too short") before calling C.
- Rust: `rebin` reports AST__DIMIN before C's checks, when bound arrays differ in length or a data or variance array has a length other than its grid's number of pixels, and leaves the output alone.
- Java: AST__DIMIN with Rust's messages, before C's checks and leaving the output alone, when a bound array has fewer values than the number of dimensions given or a data or variance array fewer elements than its grid has pixels; a longer array is accepted and its leading elements used, as in C and jniast (see "Array arguments longer than needed").

### `astRebin<X>` zeroes only the first output value after an argument error

- C: `Rebin<X>` reports an error in its arguments and carries on to zero the output, whose number of pixels it counts only once its checks have passed, so after an error in the axis counts, bounds, tolerance, initial scale size or region only the first output value (and variance) is zeroed; after a later error, such as a Mapping with no forward transformation, the whole output is. **C quirk.**
- Rust: as C.
- Java: as C, as far as the output array goes (an empty array is left alone); `RebinOracleTest.errorsMatchC` records the first two output values after each error.

### Adding a contribution to an `int` output pixel

- C: `SpreadNearest<X>` and `SpreadLinear<X>` add `CONV(IntType, c)`, an `int` for `int` data, so the sum is `int` arithmetic, as `SpreadKernel1<X>`'s `out[off] += c` is; overflow is undefined (UBSan reports it in `SpreadKernel1I`), and the pinned library wraps.
  Each contribution `c = CONV(IntType, pfac * in)` is converted to `int` with no range check, and each variance contribution `CONV(IntType, in_var * pfac * pfac)` is a `double` (the conditional in `CONV` takes the type of its `double` branch), so `out_var` is summed in `double` and converted back to `int`; both conversions are undefined beyond the range of `int` (UBSan reports the second in `SpreadNearestI`), and the pinned library on AArch64 saturates. **C quirk.**
- Rust: `SpreadNearest<X>` and `SpreadLinear<X>` add through a `double` and convert the sum, which saturates on overflow (`spread::Accum::deposit`).
- Java: as the pinned library on AArch64: `PixelArray.add` wraps, and `PixelArray.conv` and the variance sum use Java's saturating `(int)` cast (`RebinSpreaders.deposit`); two contributions of `INT_MAX - 5` to one pixel give C's -12 for the value and `INT_MAX` for the variance with the nearest, linear and sinc spreaders; no oracle case overflows.

### Rebinning with the `AST__SOMB` and `AST__SOMBCOS` schemes

- C: `astRebin<X>` accepts both schemes.
- jniast: `Mapping.Spreader` has no factory for either.
- Java: as jniast for the public API; rebinning accepts the schemes, which the tests reach through the package-private `Spreader` constructor.

### Elementary functions in the rebinning kernels

- C: the kernels call the C library's `sin`, `cos` and `exp`.
- Rust: as C.
- Java: the kernels call `java.lang.Math`, which can differ from C's library in the last bit; seven `double` cases of the rebin oracle miss their hash on AArch64 macOS.
  `RebinOracleTest` compares every case whose kernel calls these functions value by value with probe `src/test/resources/probes/rebin_kernel_probe.c`, within a bound worked out for each output pixel from the contributions it receives (about 1e-14 to 1e-12 for `double`; for `int`, one for each contribution lying within its bound of a half-integer), and checks that each sum of weights lies clear of the weight limit.

## Test harness

### Round trips where C's own recorded inverse fails

- C: the transform-oracle checker has no exception for rows whose recorded inverse does not round-trip.
- Rust: `test_transform_oracle.rs` excuses them.
- Java: as Rust, pinned to the affected rows by `RTRIP_REFERENCE_GAPS_LICENSED`.

### `roundtrip` of `testfitschan.c` when the current Frames do not align

- C: `roundtrip` compares the pixel coordinates after a round trip through the first FrameSet and back through the second when `astConvert` finds no conversion between the two current Frames, and its sky System block (errors 500-534) accepts a write an encoding refuses.
- Rust: `roundtrip` in `test_fitschan_c_inline.rs` expects the current Frames to align and has no fallback, and `sky_systems_through_five_encodings` accepts either outcome of each write.
- Java: `FitsChanCInlineTest.roundtrip` is C's, fallback included, and each sky System and encoding expects the outcome C gives (FK4-NO-E and ICRS refused by FITS-AIPS and FITS-AIPS++, Supergalactic by FITS-IRAF, FITS-AIPS and FITS-AIPS++).
