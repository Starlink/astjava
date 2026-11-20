/* resample_kernel_probe -- every output value and variance of the cases of
 * the resample oracle (the Rust repository's
 * tools/oracle_fixtures/resample_probe.c) whose kernels call sin, cos or
 * exp, for the types jniast has: D, F, L, I, S and B. The oracle gives
 * only a hash of each case's outputs; these values let a test compare
 * outputs one by one where the Java runtime's elementary functions differ
 * from the C library's in the last bit. The Mappings, data, parameters
 * and flags are the oracle probe's.
 *
 * Each case starts with "== <name> <type>", then "nbad <n>", "out"
 * followed by every output value, and, where variances are resampled,
 * "var" followed by every output variance, ten to a line: %.17g for D,
 * %.9g for F, and the integer for the other types.
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o resample_kernel_probe resample_kernel_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./resample_kernel_probe > resample_kernel_probe.out. */

/* The oracle probe was compiled without fused multiply-adds. */
#pragma STDC FP_CONTRACT OFF

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

static AstMapping *mapping( const char *name ) {
   if( !strcmp( name, "unit2" ) ) return (AstMapping *) astUnitMap( 2, " " );
   if( !strcmp( name, "shift_half" ) ) {
      double sh[ 2 ] = { 0.5, -0.25 };
      return (AstMapping *) astShiftMap( 2, sh, " " );
   }
   if( !strcmp( name, "zoom2" ) ) return (AstMapping *) astZoomMap( 2, 1.7, " " );
   if( !strcmp( name, "zoom1" ) ) return (AstMapping *) astZoomMap( 1, 1.3, " " );
   if( !strcmp( name, "perm" ) ) {
      int p[ 2 ] = { 2, 1 };
      return (AstMapping *) astPermMap( 2, p, 2, p, NULL, " " );
   }
   if( !strcmp( name, "nonlin2" ) ) {
      const char *f[] = { "p=a", "q=b" };
      const char *i[] = { "a=p+0.02*p*q-0.3", "b=q-0.015*p*p+0.2" };
      return (AstMapping *) astMathMap( 2, 2, 2, f, 2, i, " " );
   }
   if( !strcmp( name, "cubic1" ) ) {
      const char *f[] = { "p=a" };
      const char *i[] = { "a=0.001*p*p*p+0.5*p" };
      return (AstMapping *) astMathMap( 1, 1, 1, f, 1, i, " " );
   }
   if( !strcmp( name, "win3" ) ) {
      double a1[ 3 ] = { 0.0, 0.0, 0.0 }, a2[ 3 ] = { 1.0, 1.0, 1.0 };
      double b1[ 3 ] = { 0.3, -0.2, 0.1 }, b2[ 3 ] = { 1.1, 0.7, 1.4 };
      return (AstMapping *) astWinMap( 3, a1, a2, b1, b2, " " );
   }
   if( !strcmp( name, "nonlin3" ) ) {
      const char *f[] = { "p=a", "q=b", "r=c" };
      const char *i[] = { "a=p+0.05*q*r", "b=q-0.03*p*p", "c=0.9*r+0.1*p" };
      return (AstMapping *) astMathMap( 3, 3, 3, f, 3, i, " " );
   }
   return NULL;
}

typedef struct Case {
   const char *name, *map;
   int ndim_in, ndim_out;
   AstDim lbnd_in[ 3 ], ubnd_in[ 3 ], lbnd_out[ 3 ], ubnd_out[ 3 ], lbnd[ 3 ], ubnd[ 3 ];
   int interp, flags, usevar, maxpix;
   double tol;
   double params[ 2 ];
} Case;

static AstDim npix( int n, const AstDim *l, const AstDim *u ) {
   AstDim r = 1;
   int i;
   for( i = 0; i < n; i++ ) r *= u[ i ] - l[ i ] + 1;
   return r;
}

#define RUN(X,Xtype,Floating,Fmt,Cast) \
static void run_##X( const Case *c ) { \
   AstMapping *m = mapping( c->map ); \
   AstDim nin = npix( c->ndim_in, c->lbnd_in, c->ubnd_in ); \
   AstDim nout = npix( c->ndim_out, c->lbnd_out, c->ubnd_out ); \
   Xtype *in = malloc( sizeof( Xtype ) * nin ), *in_var = malloc( sizeof( Xtype ) * nin ); \
   Xtype *out = malloc( sizeof( Xtype ) * nout ), *out_var = malloc( sizeof( Xtype ) * nout ); \
   Xtype badval = (Xtype) 120; \
   AstDim k, nbad; \
   for( k = 0; k < nin; k++ ) { \
      if( Floating ) { \
         in[ k ] = (Xtype) ( ( ( 7 * k ) % 97 ) * 0.37 - 10.0 ); \
         in_var[ k ] = (Xtype) ( ( ( 3 * k ) % 11 ) * 0.5 - 1.0 ); \
      } else { \
         in[ k ] = (Xtype) ( ( ( 7 * k ) % 97 ) - 40 ); \
         in_var[ k ] = (Xtype) ( ( ( 3 * k ) % 11 ) - 2 ); \
      } \
      if( ( c->flags & AST__USEBAD ) && k % 17 == 3 ) in[ k ] = badval; \
      if( ( c->flags & AST__USEBAD ) && k % 19 == 5 ) in_var[ k ] = badval; \
   } \
   for( k = 0; k < nout; k++ ) out[ k ] = out_var[ k ] = (Xtype) 77; \
   nbad = astResample8##X( m, c->ndim_in, c->lbnd_in, c->ubnd_in, in, \
                           c->usevar ? in_var : NULL, c->interp, NULL, c->params, \
                           c->flags, c->tol, c->maxpix, badval, c->ndim_out, \
                           c->lbnd_out, c->ubnd_out, c->lbnd, c->ubnd, out, \
                           c->usevar ? out_var : NULL ); \
   printf( "== %s " #X "\nnbad %lld\nout", c->name, (long long) nbad ); \
   for( k = 0; k < nout; k++ ) printf( "%s" Fmt, k % 10 ? " " : "\n", Cast out[ k ] ); \
   if( c->usevar ) { \
      printf( "\nvar" ); \
      for( k = 0; k < nout; k++ ) printf( "%s" Fmt, k % 10 ? " " : "\n", Cast out_var[ k ] ); \
   } \
   printf( "\n" ); \
   m = astAnnul( m ); \
   free( in ); free( in_var ); free( out ); free( out_var ); \
}

RUN(D,double,1,"%.17g",(double))
RUN(F,float,1,"%.9g",(double))
RUN(L,long int,0,"%lld",(long long))
RUN(I,int,0,"%lld",(long long))
RUN(S,short int,0,"%lld",(long long))
RUN(B,signed char,0,"%lld",(long long))

int main( void ) {
   int status = 0, i;
   static const Case cases[] = {
      { "sinc_shift", "shift_half", 2, 2, {-3,2}, {16,16}, {-5,0}, {18,18}, {-5,0}, {18,18},
        AST__SINC, AST__USEBAD, 1, 50, 0.1, {2.0, 2.0} },
      { "sinc_default", "unit2", 2, 2, {-3,2}, {16,16}, {-3,2}, {16,16}, {-3,2}, {16,16},
        AST__SINC, 0, 0, 50, 0.0, {0.0, 0.0} },
      { "sincsinc_nonlin", "nonlin2", 2, 2, {-3,2}, {16,16}, {-6,-2}, {20,22}, {-6,-2}, {20,22},
        AST__SINCSINC, 0, 1, 8, 0.01, {0.0, 2.5} },
      { "sinccos_cubic", "cubic1", 1, 1, {-30}, {40}, {-50}, {60}, {-50}, {60},
        AST__SINCCOS, AST__USEBAD, 1, 20, 0.001, {3.0, 2.0} },
      { "sincgauss_zoom", "zoom2", 2, 2, {-3,2}, {16,16}, {-10,-5}, {30,30}, {-10,-5}, {30,30},
        AST__SINCGAUSS, 0, 1, 50, 0.1, {0.0, 1.5} },
      { "gauss_nobad", "shift_half", 2, 2, {-3,2}, {16,16}, {-5,0}, {18,18}, {-5,0}, {18,18},
        AST__GAUSS, AST__USEBAD | AST__NOBAD, 1, 50, 0.1, {2.0, 1.2} },
      { "gauss_default", "cubic1", 1, 1, {-30}, {40}, {-50}, {60}, {-50}, {60},
        AST__GAUSS, 0, 0, 20, 0.0, {0.0, 3.0} },
      { "somb_unit", "unit2", 2, 2, {-3,2}, {16,16}, {-3,2}, {16,16}, {-3,2}, {16,16},
        AST__SOMB, AST__USEBAD, 1, 50, 0.0, {2.0, 2.0} },
      { "sombcos_win3", "win3", 3, 3, {-2,0,1}, {6,7,5}, {-3,-1,0}, {9,8,9}, {-3,-1,0}, {9,8,9},
        AST__SOMBCOS, AST__USEBAD, 1, 50, 0.1, {2.0, 2.0} },
      { "sombcos_unit", "unit2", 2, 2, {-3,2}, {16,16}, {-3,2}, {16,16}, {-3,2}, {16,16},
        AST__SOMBCOS, 0, 1, 50, 0.0, {2.0, 2.0} },
      { "sinc_nonlin3", "nonlin3", 3, 3, {-2,0,1}, {6,7,5}, {-3,-1,0}, {9,8,9}, {-3,-1,0}, {9,8,9},
        AST__SINC, 0, 1, 50, 0.0, {1.0, 0.0} },
      { "conserve_perm", "perm", 2, 2, {-3,2}, {16,16}, {0,-5}, {20,20}, {0,-5}, {20,20},
        AST__SINC, AST__CONSERVEFLUX, 1, 50, 0.1, {2.0, 0.0} },
      { "conserve_zoom1", "zoom1", 1, 1, {-30}, {40}, {-50}, {60}, {-50}, {60},
        AST__GAUSS, AST__CONSERVEFLUX, 1, 200, 0.5, {2.0, 1.0} },
   };
   astWatch( &status );
   for( i = 0; i < (int) ( sizeof( cases ) / sizeof( cases[ 0 ] ) ); i++ ) {
      run_D( cases + i ); run_F( cases + i ); run_L( cases + i );
      run_I( cases + i ); run_S( cases + i ); run_B( cases + i );
   }
   return status;
}
