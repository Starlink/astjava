/* rebin_jniast_probe -- astRebinD, astRebinF and astRebinI of the pinned C
 * library with the flags 0, AST__USEBAD, AST__USEVAR and both, and with
 * both variance arrays, neither, only the input one or only the output
 * one. jniast passes AST__USEBAD alone, so through jniast C zeroed an
 * output variance array and never filled it; C rebins variances only with
 * AST__USEVAR and both arrays.
 *
 * The input is a 2-D grid of 4 by 3 pixels (indices 1..4, 1..3) rebinned
 * through a ShiftMap of (0.5, -0.25) into a grid of 6 by 5 pixels
 * (indices 0..5, 0..4), with nearest-neighbour and linear spreading, tol
 * 0, maxpix 100, and wlim 0 and 0.6. Data pixel k (0-based) holds
 * 1.5 k - 4 (D, F) or 3 k - 7 (I), except the bad value -999 at k = 5;
 * variance pixel k holds 0.5 k + 0.25 (D, F) or k + 1 (I), except -999 at
 * k = 8. The output arrays start at 55; an array not passed keeps 55.
 *
 * Each case starts with a line
 * "== <type>_<scheme>_wlim<0|0.6>_<flags>_<vars>", flags being none,
 * usebad, usevar or usebad_usevar and vars both, none, in or out (the
 * variance arrays passed); then "out" followed by the 30 output values and
 * "var" followed by the 30 output variances, written with %.9g (F),
 * %.17g (D) or %d (I), which read back to the same value.
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o rebin_jniast_probe rebin_jniast_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./rebin_jniast_probe > rebin_jniast_probe.out 2>&1. */
#include <stdio.h>
#include "ast.h"

#define NIN 12
#define NOUT 30

int main( void ) {
   const char *schemes[] = { "nearest", "linear" };
   int spreads[] = { AST__NEAREST, AST__LINEAR };
   const char *wlims[] = { "0", "0.6" };
   double wlimv[] = { 0.0, 0.6 };
   const char *flagnames[] = { "none", "usebad", "usevar", "usebad_usevar" };
   int flagv[] = { 0, AST__USEBAD, AST__USEVAR, AST__USEBAD | AST__USEVAR };
   const char *varnames[] = { "both", "none", "in", "out" };
   int lbnd_in[] = { 1, 1 };
   int ubnd_in[] = { 4, 3 };
   int lbnd_out[] = { 0, 0 };
   int ubnd_out[] = { 5, 4 };
   double shift[] = { 0.5, -0.25 };
   double params[] = { 0.0, 0.0 };
   int s, w, f, v, k;

   astBegin;
   AstShiftMap *map = astShiftMap( 2, shift, " " );
   for( s = 0; s < 2; s++ ) {
      for( w = 0; w < 2; w++ ) {
         for( f = 0; f < 4; f++ ) {
            for( v = 0; v < 4; v++ ) {
               int with_in = ( v == 0 || v == 2 );
               int with_out = ( v == 0 || v == 3 );
               double ind[ NIN ], invd[ NIN ], outd[ NOUT ], outvd[ NOUT ];
               float inf[ NIN ], invf[ NIN ], outf[ NOUT ], outvf[ NOUT ];
               int ini[ NIN ], invi[ NIN ], outi[ NOUT ], outvi[ NOUT ];
               for( k = 0; k < NIN; k++ ) {
                  ind[ k ] = ( k == 5 ) ? -999.0 : 1.5 * k - 4.0;
                  invd[ k ] = ( k == 8 ) ? -999.0 : 0.5 * k + 0.25;
                  inf[ k ] = (float) ind[ k ];
                  invf[ k ] = (float) invd[ k ];
                  ini[ k ] = ( k == 5 ) ? -999 : 3 * k - 7;
                  invi[ k ] = ( k == 8 ) ? -999 : k + 1;
               }
               for( k = 0; k < NOUT; k++ ) {
                  outd[ k ] = outvd[ k ] = 55.0;
                  outf[ k ] = outvf[ k ] = 55.0f;
                  outi[ k ] = outvi[ k ] = 55;
               }

               printf( "== D_%s_wlim%s_%s_%s\n", schemes[ s ], wlims[ w ], flagnames[ f ], varnames[ v ] );
               astRebinD( map, wlimv[ w ], 2, lbnd_in, ubnd_in, ind, with_in ? invd : NULL,
                          spreads[ s ], params, flagv[ f ], 0.0, 100, -999.0, 2, lbnd_out,
                          ubnd_out, lbnd_in, ubnd_in, outd, with_out ? outvd : NULL );
               printf( "out" );
               for( k = 0; k < NOUT; k++ ) printf( " %.17g", outd[ k ] );
               printf( "\nvar" );
               for( k = 0; k < NOUT; k++ ) printf( " %.17g", outvd[ k ] );
               printf( "\n" );

               printf( "== F_%s_wlim%s_%s_%s\n", schemes[ s ], wlims[ w ], flagnames[ f ], varnames[ v ] );
               astRebinF( map, wlimv[ w ], 2, lbnd_in, ubnd_in, inf, with_in ? invf : NULL,
                          spreads[ s ], params, flagv[ f ], 0.0, 100, -999.0f, 2, lbnd_out,
                          ubnd_out, lbnd_in, ubnd_in, outf, with_out ? outvf : NULL );
               printf( "out" );
               for( k = 0; k < NOUT; k++ ) printf( " %.9g", outf[ k ] );
               printf( "\nvar" );
               for( k = 0; k < NOUT; k++ ) printf( " %.9g", outvf[ k ] );
               printf( "\n" );

               printf( "== I_%s_wlim%s_%s_%s\n", schemes[ s ], wlims[ w ], flagnames[ f ], varnames[ v ] );
               astRebinI( map, wlimv[ w ], 2, lbnd_in, ubnd_in, ini, with_in ? invi : NULL,
                          spreads[ s ], params, flagv[ f ], 0.0, 100, -999, 2, lbnd_out,
                          ubnd_out, lbnd_in, ubnd_in, outi, with_out ? outvi : NULL );
               printf( "out" );
               for( k = 0; k < NOUT; k++ ) printf( " %d", outi[ k ] );
               printf( "\nvar" );
               for( k = 0; k < NOUT; k++ ) printf( " %d", outvi[ k ] );
               printf( "\n" );
            }
         }
      }
   }
   astEnd;
   return astOK ? 0 : 1;
}
