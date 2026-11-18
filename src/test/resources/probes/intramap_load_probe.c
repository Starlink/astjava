/* intramap_load_probe -- reading back an IntraMap whose function maps
 * different numbers of input and output coordinates.  "sum2" is registered
 * with 2 inputs and 1 output (forward: the sum of the inputs; inverse: half
 * the input on each output).  An IntraMap of it is written, read back and
 * applied, as made and inverted; astLoadIntraMap compares the numbers of
 * coordinates of the IntraMap read, as its Invert attribute leaves them,
 * with those registered.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o intramap_load_probe intramap_load_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./intramap_load_probe > intramap_load_probe.out 2>&1, then
 * trailing white space removed from every line. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

#define MAXLINES 100
static char *lines[ MAXLINES ];
static int nline = 0;
static int iline = 0;

static void sink( const char *line ) {
   if( nline < MAXLINES ) lines[ nline++ ] = strcpy( malloc( strlen( line ) + 1 ), line );
}

static const char *source( void ) {
   return ( iline < nline ) ? lines[ iline++ ] : NULL;
}

static void Sum2( AstMapping *mapping, int npoint, int ncoord_in,
                  const double *ptr_in[], int forward, int ncoord_out,
                  double *ptr_out[] ) {
   (void) mapping;
   (void) ncoord_in;
   (void) ncoord_out;
   for( int i = 0; i < npoint; i++ ) {
      if( forward ) {
         ptr_out[ 0 ][ i ] = ptr_in[ 0 ][ i ] + ptr_in[ 1 ][ i ];
      } else {
         ptr_out[ 0 ][ i ] = 0.5 * ptr_in[ 0 ][ i ];
         ptr_out[ 1 ][ i ] = 0.5 * ptr_in[ 0 ][ i ];
      }
   }
}

/* Writes m, prints the text, reads it back and reports the result. */
static void roundtrip( const char *name, AstMapping *m ) {
   printf( "== %s\n", name );
   nline = iline = 0;
   AstChannel *out = astChannel( NULL, sink, "" );
   astWrite( out, m );
   out = astAnnul( out );
   for( int i = 0; i < nline; i++ ) printf( "%s\n", lines[ i ] );
   printf( "-- read\n" );
   AstChannel *in = astChannel( source, NULL, "" );
   AstMapping *back = astRead( in );
   in = astAnnul( in );
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return;
   }
   int nin = astGetI( back, "Nin" );
   double xin[ 2 ] = { 1.5, 2.25 };
   double xout[ 2 ] = { 0.0, 0.0 };
   if( nin == 2 ) {
      astTranN( back, 1, 2, 1, xin, 1, 1, 1, xout );
   } else {
      astTranN( back, 1, 1, 1, xin, 1, 2, 1, xout );
   }
   printf( "Nin %d forward of (%.17g, %.17g): %.17g %.17g\n", nin, xin[ 0 ],
           xin[ 1 ], xout[ 0 ], nin == 2 ? 0.0 : xout[ 1 ] );
   back = astAnnul( back );
}

int main( void ) {
   int status_value = 0;
   int *status = &status_value;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( status );
   astIntraReg_( "sum2", 2, 1, Sum2, 0, "Sum of two inputs", "AST test suite",
                 "starlink-ast", status );
   AstIntraMap *m = astIntraMap( "sum2", 2, 1, "" );
   roundtrip( "as_made", (AstMapping *) m );
   astInvert( m );
   roundtrip( "inverted", (AstMapping *) m );
   m = astAnnul( m );
   return 0;
}
