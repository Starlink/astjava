/* resample_nan_probe -- astResampleF and astResampleD of the pinned C
 * library on data and variances holding NaN, with a bad value that is
 * NaN (as SPLAT and astgui pass it) and one that is a number, with
 * AST__USEBAD set and unset. C recognises a bad input pixel by comparing
 * it with the bad value in the data type, so NaN data is never bad, and
 * what NaN does to the output follows from the arithmetic.
 *
 * The input is a 1-D grid of ten pixels (indices 1 to 10) resampled
 * through a ShiftMap of half a pixel into a grid of the same bounds, with
 * nearest-neighbour and linear interpolation and tol 0. Data pixel k
 * (0-based) holds k + 1, except NaN at 3 and 7 and -999 at 5; variance
 * pixel k holds 0.5 k + 0.25, except NaN at 1 and -999 at 8. The output
 * arrays start at 55.
 *
 * Each case starts with a line "== <type>_<scheme>_<badval>_<usebad>";
 * then "nbad <n>", "out" followed by the ten output values and "var"
 * followed by the ten output variances, NaN written as "nan" and other
 * values with %.9g (F) or %.17g (D), which read back to the same value.
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o resample_nan_probe resample_nan_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./resample_nan_probe > resample_nan_probe.out 2>&1. */
#include <math.h>
#include <stdio.h>
#include "ast.h"

#define N 10

static void value( double v, int digits ) {
   if( isnan( v ) ) {
      printf( " nan" );
   } else {
      printf( " %.*g", digits, v );
   }
}

static double data( int k ) {
   if( k == 3 || k == 7 ) return NAN;
   if( k == 5 ) return -999.0;
   return k + 1.0;
}

static double var( int k ) {
   if( k == 1 ) return NAN;
   if( k == 8 ) return -999.0;
   return 0.5 * k + 0.25;
}

int main( void ) {
   const char *schemes[] = { "nearest", "linear" };
   int interps[] = { AST__NEAREST, AST__LINEAR };
   const char *bads[] = { "nan", "m999" };
   double badvals[] = { NAN, -999.0 };
   int lbnd[] = { 1 };
   int ubnd[] = { N };
   double shift[] = { 0.5 };
   int i, k, s, b, u;

   astBegin;
   AstShiftMap *map = astShiftMap( 1, shift, " " );
   for( s = 0; s < 2; s++ ) {
      for( b = 0; b < 2; b++ ) {
         for( u = 0; u < 2; u++ ) {
            int flags = u ? AST__USEBAD : 0;
            float inf[ N ], invf[ N ], outf[ N ], outvf[ N ];
            double ind[ N ], invd[ N ], outd[ N ], outvd[ N ];
            int nbad;
            for( k = 0; k < N; k++ ) {
               inf[ k ] = (float) data( k );
               invf[ k ] = (float) var( k );
               outf[ k ] = outvf[ k ] = 55.0f;
               ind[ k ] = data( k );
               invd[ k ] = var( k );
               outd[ k ] = outvd[ k ] = 55.0;
            }

            printf( "== F_%s_%s_%s\n", schemes[ s ], bads[ b ], u ? "usebad" : "nousebad" );
            nbad = astResampleF( map, 1, lbnd, ubnd, inf, invf, interps[ s ], NULL, NULL,
                                 flags, 0.0, 100, (float) badvals[ b ], 1, lbnd, ubnd,
                                 lbnd, ubnd, outf, outvf );
            printf( "nbad %d\nout", nbad );
            for( i = 0; i < N; i++ ) value( outf[ i ], 9 );
            printf( "\nvar" );
            for( i = 0; i < N; i++ ) value( outvf[ i ], 9 );
            printf( "\n" );

            printf( "== D_%s_%s_%s\n", schemes[ s ], bads[ b ], u ? "usebad" : "nousebad" );
            nbad = astResampleD( map, 1, lbnd, ubnd, ind, invd, interps[ s ], NULL, NULL,
                                 flags, 0.0, 100, badvals[ b ], 1, lbnd, ubnd,
                                 lbnd, ubnd, outd, outvd );
            printf( "nbad %d\nout", nbad );
            for( i = 0; i < N; i++ ) value( outd[ i ], 17 );
            printf( "\nvar" );
            for( i = 0; i < N; i++ ) value( outvd[ i ], 17 );
            printf( "\n" );
         }
      }
   }
   astEnd;
   return astOK ? 0 : 1;
}
