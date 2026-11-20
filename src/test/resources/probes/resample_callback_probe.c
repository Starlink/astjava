/* resample_callback_probe -- what astResample8D of the pinned C library
 * leaves in its output when a caller's kernel or interpolator fails part
 * way, as jniast's callbacks fail: by setting the status to AST__UK1ER
 * (kernel) or AST__UINER (interpolator) with astSetStatus.
 *
 * Kernel cases: a UnitMap grid of 10 pixels (1-D), 4 x 4 (2-D) or
 * 3 x 3 x 3 (3-D), lower bounds 1, data pixel k (0-based) holding k + 1,
 * resampled with AST__UKERN1 (params 1 and 0, so one neighbouring pixel
 * either side), tol 0 and maxpix 50. The kernel, 1 - |offset| within one
 * pixel and 0 beyond, fails on its fail-th call, counted from 1.
 *
 * Interpolator cases: a ZoomMap of factor 2 on 1-D grids of 4100 pixels
 * (input and output bounds 1 to 4100), AST__CONSERVEFLUX, tol 0.1 and
 * maxpix 10000, so that one linear fit covers the output and it is
 * resampled in blocks of 2048, 2048 and 4 pixels. Each call of the
 * interpolator sets each output value to its first input coordinate and
 * returns no bad pixels; its fail-th call sets only the first half of its
 * points, then fails.
 *
 * Output arrays start at 77. Each case starts with "== <name>", then
 * "status <n>", "nbad <n>", and "out" followed by every output value
 * (%.17g), ten to a line.
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o resample_callback_probe resample_callback_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./resample_callback_probe > resample_callback_probe.out (the
 * error reports C writes to standard error are not kept). */
#include <stdio.h>
#include <stdlib.h>
#include "ast.h"

static int ncall;
static int fail;

static void kernel( double offset, const double params[], int flags,
                    double *value ) {
   double a = offset < 0.0 ? -offset : offset;
   ncall++;
   if( ncall == fail ) {
      *value = 0.0;
      astSetStatus( AST__UK1ER );
      return;
   }
   *value = a < 1.0 ? 1.0 - a : 0.0;
}

static void uinterp( int ndim_in, const AstDim lbnd_in[], const AstDim ubnd_in[],
                     const double in[], const double in_var[], AstDim npoint,
                     const AstDim offset[], const double *const coords[],
                     const double params[], int flags, double badval,
                     double *out, double *out_var, AstDim *nbad ) {
   AstDim point;
   AstDim n = npoint;
   ncall++;
   if( ncall == fail ) n = npoint / 2;
   for( point = 0; point < n; point++ ) out[ offset[ point ] ] = coords[ 0 ][ point ];
   *nbad = 0;
   if( ncall == fail ) astSetStatus( AST__UINER );
}

static void print( const char *name, int status, AstDim nbad, const double *out,
                   AstDim n ) {
   AstDim k;
   printf( "== %s\nstatus %d\nnbad %lld\nout", name, status, (long long) nbad );
   for( k = 0; k < n; k++ ) {
      printf( "%s%.17g", k % 10 ? " " : "\n", out[ k ] );
   }
   printf( "\n" );
}

static void kernel_case( const char *name, int ndim, AstDim dim, int fail_at ) {
   AstDim lbnd[ 3 ] = { 1, 1, 1 }, ubnd[ 3 ] = { dim, dim, dim };
   double params[ 2 ] = { 1.0, 0.0 };
   AstMapping *map = (AstMapping *) astUnitMap( ndim, " " );
   AstDim n = 1, k, nbad;
   double *in, *out;
   int i, status;
   for( i = 0; i < ndim; i++ ) n *= dim;
   in = malloc( sizeof( double ) * n );
   out = malloc( sizeof( double ) * n );
   for( k = 0; k < n; k++ ) {
      in[ k ] = k + 1;
      out[ k ] = 77.0;
   }
   ncall = 0;
   fail = fail_at;
   nbad = astResample8D( map, ndim, lbnd, ubnd, in, NULL, AST__UKERN1,
                         (void (*)( void )) kernel, params, 0, 0.0, 50, -1.0,
                         ndim, lbnd, ubnd, lbnd, ubnd, out, NULL );
   status = astStatus;
   astClearStatus;
   print( name, status, nbad, out, n );
   map = astAnnul( map );
   free( in );
   free( out );
}

static void uinterp_case( const char *name, int fail_at ) {
   AstDim lbnd[ 1 ] = { 1 }, ubnd[ 1 ] = { 4100 };
   double params[ 2 ] = { 0.0, 0.0 };
   AstMapping *map = (AstMapping *) astZoomMap( 1, 2.0, " " );
   AstDim n = 4100, k, nbad;
   double *in, *out;
   int status;
   in = malloc( sizeof( double ) * n );
   out = malloc( sizeof( double ) * n );
   for( k = 0; k < n; k++ ) {
      in[ k ] = k + 1;
      out[ k ] = 77.0;
   }
   ncall = 0;
   fail = fail_at;
   nbad = astResample8D( map, 1, lbnd, ubnd, in, NULL, AST__UINTERP,
                         (void (*)( void )) uinterp, params, AST__CONSERVEFLUX,
                         0.1, 10000, -1.0, 1, lbnd, ubnd, lbnd, ubnd, out, NULL );
   status = astStatus;
   astClearStatus;
   print( name, status, nbad, out, n );
   map = astAnnul( map );
   free( in );
   free( out );
}

int main( void ) {
   kernel_case( "kernel_1d", 1, 10, 5 );
   kernel_case( "kernel_2d", 2, 4, 11 );
   kernel_case( "kernel_3d", 3, 3, 20 );
   kernel_case( "kernel_none", 1, 10, 1000 );
   uinterp_case( "uinterp_second", 2 );
   uinterp_case( "uinterp_first", 1 );
   uinterp_case( "uinterp_none", 1000 );
   return 0;
}
