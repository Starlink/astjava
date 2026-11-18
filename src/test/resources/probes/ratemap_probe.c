/* ratemap_probe -- RateMap behaviour of the pinned C library that no
 * fixture or oracle holds: native dumps of RateMaps of ZoomMaps (with the
 * encapsulated Mapping inverted when the RateMap is made or afterwards, and
 * the RateMap itself inverted) and of a LutMap, a MathMap and a TranMap,
 * each read back through a Channel and dumped again; simplification of a
 * RateMap alone (of a simplifiable Mapping, a UnitMap, an inverted
 * Mapping) and of RateMaps cancelling against their inverses in series;
 * rates; astEqual; astMapSplit; and the constructor's errors.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o ratemap_probe ratemap_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./ratemap_probe > ratemap_probe.out 2>&1, then trailing white
 * space removed from every line (RateMapTest compares lines without it). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

/* The lines a Channel writes, which a second Channel reads back. */
#define MAXLINES 400
static char *lines[ MAXLINES ];
static int nline = 0;
static int iline = 0;

static void sink( const char *line ) {
   if( nline < MAXLINES ) lines[ nline++ ] = strcpy( malloc( strlen( line ) + 1 ), line );
}

static const char *source( void ) {
   return ( iline < nline ) ? lines[ iline++ ] : NULL;
}

static void head( const char *name ) {
   printf( "== %s\n", name );
}

/* Reports a failed call, then clears the status. */
static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

/* The transformations of one point in each direction the Mapping defines:
   input coordinate i is x + i. */
static void tran( AstMapping *m, double x ) {
   double in[ 8 ], out[ 8 ];
   int nin = astGetI( m, "Nin" );
   int nout = astGetI( m, "Nout" );
   if( astGetL( m, "TranForward" ) ) {
      for( int i = 0; i < nin; i++ ) in[ i ] = x + i;
      astTranN( m, 1, nin, 1, in, 1, nout, 1, out );
      if( failed() ) return;
      printf( "fwd" );
      for( int i = 0; i < nin; i++ ) printf( " %.17g", in[ i ] );
      printf( " ->" );
      for( int i = 0; i < nout; i++ ) printf( " %.17g", out[ i ] );
      printf( "\n" );
   }
   if( astGetL( m, "TranInverse" ) ) {
      for( int i = 0; i < nout; i++ ) in[ i ] = x + i;
      astTranN( m, 1, nout, 1, in, 0, nin, 1, out );
      if( failed() ) return;
      printf( "inv" );
      for( int i = 0; i < nout; i++ ) printf( " %.17g", in[ i ] );
      printf( " ->" );
      for( int i = 0; i < nin; i++ ) printf( " %.17g", out[ i ] );
      printf( "\n" );
   }
}

/* The dump, the Object read back from it through a Channel and that
   Object's dump, then the transformations of both. */
static void roundtrip( const char *name, AstMapping *m, double x ) {
   head( name );
   astShow( m );
   for( int i = 0; i < nline; i++ ) free( lines[ i ] );
   nline = iline = 0;
   AstChannel *ch = astChannel( source, sink, " " );
   astWrite( ch, m );
   AstMapping *back = astRead( ch );
   if( failed() ) return;
   printf( "-- read back\n" );
   astShow( back );
   printf( "-- transformations\n" );
   tran( m, x );
   printf( "-- transformations read back\n" );
   tran( back, x );
   ch = astAnnul( ch );
}

/* The class of the simplified Mapping, its dump, and the transformations
   of the original and the simplified Mapping. */
static void simp( const char *name, AstMapping *m, double x ) {
   head( name );
   AstMapping *s = astSimplify( m );
   if( failed() ) return;
   printf( "class %s\n", astGetC( s, "Class" ) );
   astShow( s );
   printf( "-- transformations\n" );
   tran( m, x );
   printf( "-- transformations simplified\n" );
   tran( s, x );
}

static AstZoomMap *zoom( int n, double f ) {
   return astZoomMap( n, f, " " );
}

static AstMapping *inv( AstMapping *m ) {
   astInvert( m );
   return m;
}

static AstRateMap *rate( void *m, int ax1, int ax2 ) {
   return astRateMap( m, ax1, ax2, " " );
}

/* A series CmpMap of two n-dimensional ZoomMaps. */
static AstMapping *zooms( int n, double f1, double f2 ) {
   return (AstMapping *) astCmpMap( zoom( n, f1 ), zoom( n, f2 ), 1, " " );
}

static double table[] = { 0.0, 1.0, 4.0, 9.0, 16.0 };

/* A two-input, two-output MathMap with no inverse. */
static AstMathMap *mathmap2( void ) {
   const char *fwd[] = { "r = x * y", "s = x + y * y" };
   const char *invf[] = { "x", "y" };
   return astMathMap( 2, 2, 2, fwd, 2, invf, " " );
}

static AstMathMap *square( void ) {
   const char *fwd[] = { "y = x * x" };
   const char *invf[] = { "x = sqrt( y )" };
   return astMathMap( 1, 1, 1, fwd, 1, invf, " " );
}

static void split( AstMapping *m, int nin, int *in ) {
   int out[ 8 ];
   AstMapping *s;
   astMapSplit( m, nin, in, out, &s );
   if( failed() ) return;
   if( !s ) {
      printf( "split none\n" );
      return;
   }
   printf( "outputs" );
   for( int i = 0; i < astGetI( s, "Nout" ); i++ ) printf( " %d", out[ i ] );
   printf( "\n" );
   astShow( s );
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* Native dumps and read back. */
   roundtrip( "dump_plain", (AstMapping *) rate( zoom( 2, 3.0 ), 2, 1 ), 1.5 );
   roundtrip( "dump_first_axes", (AstMapping *) rate( zoom( 1, 2.0 ), 1, 1 ), 1.5 );
   roundtrip( "dump_inverted_mapping", (AstMapping *) rate( inv( (AstMapping *) zoom( 1, 2.0 ) ), 1, 1 ), 1.5 );
   {
      AstZoomMap *z = zoom( 1, 2.0 );
      AstRateMap *r = rate( z, 1, 1 );
      astInvert( z );
      roundtrip( "dump_mapping_inverted_after", (AstMapping *) r, 1.5 );
   }
   roundtrip( "dump_ratemap_inverted", inv( (AstMapping *) rate( zoom( 2, 3.0 ), 1, 2 ) ), 1.5 );
   roundtrip( "dump_lutmap", (AstMapping *) rate( astLutMap( 5, table, 1.0, 1.0, " " ), 1, 1 ), 2.5 );
   roundtrip( "dump_mathmap", (AstMapping *) rate( mathmap2(), 2, 2 ), 1.5 );
   roundtrip( "dump_tranmap",
              (AstMapping *) rate( astTranMap( square(), zoom( 1, 3.0 ), " " ), 1, 1 ), 1.5 );
   roundtrip( "dump_inverted_tranmap",
              (AstMapping *) rate( inv( (AstMapping *) astTranMap( zoom( 1, 3.0 ), square(), " " ) ), 1, 1 ), 1.5 );

   /* Simplification of a RateMap alone. */
   simp( "simp_plain", (AstMapping *) rate( zoom( 1, 2.0 ), 1, 1 ), 1.5 );
   simp( "simp_unitmap", (AstMapping *) rate( astUnitMap( 2, " " ), 1, 2 ), 1.5 );
   simp( "simp_interior", (AstMapping *) rate( zooms( 2, 2.0, 3.0 ), 1, 1 ), 1.5 );
   simp( "simp_interior_inverted_mapping", (AstMapping *) rate( inv( zooms( 1, 2.0, 4.0 ) ), 1, 1 ), 1.5 );
   {
      AstMapping *c = zooms( 1, 2.0, 4.0 );
      AstRateMap *r = rate( c, 1, 1 );
      astInvert( c );
      simp( "simp_interior_mapping_inverted_after", (AstMapping *) r, 1.5 );
   }
   simp( "simp_interior_ratemap_inverted", inv( (AstMapping *) rate( zooms( 1, 2.0, 4.0 ), 1, 1 ) ), 1.5 );
   simp( "simp_interior_in_series",
         (AstMapping *) astCmpMap( zoom( 1, 5.0 ), rate( zooms( 1, 2.0, 4.0 ), 1, 1 ), 1, " " ), 1.5 );

   /* RateMaps cancelling against their inverses in series. */
   simp( "cancel_upper",
         (AstMapping *) astCmpMap( rate( zoom( 1, 2.0 ), 1, 1 ), inv( (AstMapping *) rate( zoom( 1, 2.0 ), 1, 1 ) ),
                                   1, " " ), 1.5 );
   simp( "cancel_lower",
         (AstMapping *) astCmpMap( inv( (AstMapping *) rate( zoom( 1, 2.0 ), 1, 1 ) ), rate( zoom( 1, 2.0 ), 1, 1 ),
                                   1, " " ), 1.5 );
   simp( "cancel_two_inputs_upper",
         (AstMapping *) astCmpMap( rate( zoom( 2, 2.0 ), 1, 1 ), inv( (AstMapping *) rate( zoom( 2, 2.0 ), 1, 1 ) ),
                                   1, " " ), 1.5 );
   simp( "cancel_two_inputs_lower",
         (AstMapping *) astCmpMap( inv( (AstMapping *) rate( zoom( 2, 2.0 ), 1, 1 ) ), rate( zoom( 2, 2.0 ), 1, 1 ),
                                   1, " " ), 1.5 );
   simp( "cancel_equivalent_mappings",
         (AstMapping *) astCmpMap( rate( zoom( 1, 2.0 ), 1, 1 ),
                                   inv( (AstMapping *) rate( inv( (AstMapping *) zoom( 1, 0.5 ) ), 1, 1 ) ),
                                   1, " " ), 1.5 );
   {
      AstZoomMap *z = zoom( 1, 2.0 );
      AstRateMap *r = rate( z, 1, 1 );
      astInvert( z );
      simp( "cancel_mapping_inverted_after",
            (AstMapping *) astCmpMap( r, inv( (AstMapping *) rate( zoom( 1, 2.0 ), 1, 1 ) ), 1, " " ), 1.5 );
   }
   simp( "no_cancel_axes",
         (AstMapping *) astCmpMap( rate( zoom( 2, 2.0 ), 1, 1 ), inv( (AstMapping *) rate( zoom( 2, 2.0 ), 1, 2 ) ),
                                   1, " " ), 1.5 );
   simp( "no_cancel_mappings",
         (AstMapping *) astCmpMap( rate( zoom( 1, 2.0 ), 1, 1 ), inv( (AstMapping *) rate( zoom( 1, 3.0 ), 1, 1 ) ),
                                   1, " " ), 1.5 );
   simp( "no_cancel_same_invert",
         (AstMapping *) astCmpMap( rate( zoom( 1, 2.0 ), 1, 1 ), rate( zoom( 1, 2.0 ), 1, 1 ), 1, " " ), 1.5 );
   simp( "no_cancel_parallel",
         (AstMapping *) astCmpMap( rate( zoom( 1, 2.0 ), 1, 1 ), inv( (AstMapping *) rate( zoom( 1, 2.0 ), 1, 1 ) ),
                                   0, " " ), 1.5 );
   {
      AstRateMap *r = rate( zoom( 1, 2.0 ), 1, 1 );
      simp( "no_cancel_shared", (AstMapping *) astCmpMap( r, r, 1, " " ), 1.5 );
   }
   head( "simp_error" );
   {
      int perm_out[] = { 0, 0 };
      int perm_in[] = { -1, -2 };
      double con[] = { 0.1, 0.2 };
      AstPermMap *p = astPermMap( 2, perm_out, 2, perm_in, con, " " );
      AstWcsMap *w = astWcsMap( 2, AST__COE, 1, 2, " " );
      AstRateMap *r = rate( astCmpMap( p, w, 1, " " ), 1, 1 );
      astSimplify( r );
      failed();
   }

   /* Rates. */
   head( "rates" );
   {
      double at[] = { 2.0 };
      double bad = AST__BAD;
      const char *fwd[] = { "y = x * x * x" };
      const char *invf[] = { "x" };
      AstRateMap *r = rate( astMathMap( 1, 1, 1, fwd, 1, invf, " " ), 1, 1 );
      double out;
      printf( "rate of ratemap %.17g\n", astRate( r, at, 1, 1 ) );
      astTran1( r, 1, at, 1, &out );
      printf( "ratemap of cube at 2 %.17g\n", out );
      astTran1( r, 1, &bad, 1, &out );
      printf( "ratemap of cube at bad %.17g\n", out );
      failed();
   }

   /* Equality. */
   head( "equal" );
   {
      AstRateMap *a = rate( zoom( 2, 2.0 ), 1, 2 );
      AstRateMap *b = rate( zoom( 2, 2.0 ), 1, 2 );
      AstRateMap *c = rate( zoom( 2, 2.0 ), 1, 1 );
      AstRateMap *d = rate( zoom( 2, 3.0 ), 1, 2 );
      AstRateMap *e = rate( inv( (AstMapping *) zoom( 2, 0.5 ) ), 1, 2 );
      AstRateMap *ainv = astCopy( a );
      astInvert( ainv );
      AstRateMap *binv = astCopy( b );
      astInvert( binv );
      AstZoomMap *z = zoom( 2, 2.0 );
      AstRateMap *f = rate( z, 1, 2 );
      astInvert( z );
      printf( "same %d\n", astEqual( a, b ) );
      printf( "different_axes %d\n", astEqual( a, c ) );
      printf( "different_mappings %d\n", astEqual( a, d ) );
      printf( "equivalent_mappings %d\n", astEqual( a, e ) );
      printf( "inverted_vs_plain %d\n", astEqual( ainv, a ) );
      printf( "both_inverted %d\n", astEqual( ainv, binv ) );
      printf( "mapping_inverted_after %d\n", astEqual( f, a ) );
      printf( "not_a_ratemap %d\n", astEqual( a, zoom( 2, 2.0 ) ) );
      printf( "mapping_still_inverted %d\n", astGetI( z, "Invert" ) );
      failed();
   }

   /* Splits. */
   {
      double s3[] = { 10.0, 20.0, 30.0 };
      head( "split_output_dropped" );
      int in1[] = { 1 };
      split( (AstMapping *) rate( astShiftMap( 3, s3, " " ), 2, 1 ), 1, in1 );
      head( "split_picks_varied_input" );
      split( (AstMapping *) rate( astShiftMap( 3, s3, " " ), 1, 1 ), 1, in1 );
      head( "split_other_input" );
      int in2[] = { 2 };
      split( (AstMapping *) rate( astShiftMap( 3, s3, " " ), 1, 1 ), 1, in2 );
      head( "split_two_inputs" );
      int in31[] = { 3, 1 };
      split( (AstMapping *) rate( astShiftMap( 3, s3, " " ), 1, 1 ), 2, in31 );
      head( "split_inverted" );
      split( inv( (AstMapping *) rate( astShiftMap( 3, s3, " " ), 1, 1 ) ), 1, in1 );
      head( "split_inverted_mapping" );
      split( (AstMapping *) rate( inv( (AstMapping *) astShiftMap( 3, s3, " " ) ), 1, 1 ), 1, in1 );
   }

   /* Constructor and transformation errors. */
   head( "error_no_forward" );
   rate( inv( (AstMapping *) rate( zoom( 1, 2.0 ), 1, 1 ) ), 1, 1 );
   failed();
   head( "error_output_axis" );
   rate( zoom( 2, 2.0 ), 3, 1 );
   failed();
   head( "error_input_axis" );
   rate( zoom( 2, 2.0 ), 1, 3 );
   failed();
   head( "error_output_axis_zero" );
   rate( zoom( 2, 2.0 ), 0, 1 );
   failed();
   head( "error_input_axis_zero" );
   rate( zoom( 2, 2.0 ), 1, 0 );
   failed();
   head( "error_inverse" );
   {
      double x = 1.0, y;
      astTran1( rate( zoom( 1, 2.0 ), 1, 1 ), 1, &x, 0, &y );
      failed();
   }
   head( "error_inverted_forward" );
   {
      double x = 1.0, y;
      astTran1( inv( (AstMapping *) rate( zoom( 1, 2.0 ), 1, 1 ) ), 1, &x, 1, &y );
      failed();
   }

   astEnd;
   return 0;
}
