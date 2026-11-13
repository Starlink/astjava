# Golden values

`erfa_pal_roots.tsv` holds, for every PAL routine of the `internal.pal` package's interface (`Pal`), 180 to 900 calls: the inputs and the bits of the outputs.
It is a copy of the Rust port's `crates/starlink-ast/tests/erfa_pal_golden/roots.tsv` at Rust commit 10f61689f19a5f1eacc7e653c7eab9b03809939f.
The values were captured from the C library (the PAL and ERFA of AST's fork at 3d3f255f, built with `-O3 -ffp-contract=off` on macOS aarch64) by the Rust repository's `tools/oracle_fixtures/recapture_erfa_pal_golden.sh`, which recomputes every output from the inputs.
The file's header describes each line.
`PalGoldenTest` reads it.

`projections.tsv` holds, for every wcslib projection and the parameter sets that matter to it, the status and the output bits of forward and reverse calls at points that include the poles, the multiples of 45 degrees, the edges of the quad-cube and HEALPix facets, and points outside each projection's domain.
It is a copy of the Rust port's `crates/starlink-ast/tests/wcslib_golden/projections.tsv` at Rust commit 10f61689f19a5f1eacc7e653c7eab9b03809939f.
The values were captured from the wcslib C of AST's fork at 3d3f255f, built with `-O3 -ffp-contract=off` on macOS aarch64, by the Rust repository's `tools/oracle_fixtures/recapture_wcslib_golden.sh`, which recomputes every result from the inputs.
The file's header describes each line.
`WcslibGoldenTest` reads it.
