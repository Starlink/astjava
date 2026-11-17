/* pcdmap_probe -- PcdMap behaviour of the pinned C library that no
 * fixture or oracle holds: attribute values, tests, clears and settings
 * (PcdCen with and without an axis, an axis out of range, a bad value, a
 * PcdMap shared by a CmpMap); native dumps with each attribute set and
 * unset, read back through a Channel; transformations, rates, a split and
 * astEqual; and simplification of a PcdMap alone, beside a UnitMap, its own
 * inverse, ZoomMaps and PermMaps (swapped past them by PcdZoom and
 * PcdPerm, with the ZoomMap or the PcdMap inverted, before or after the
 * CmpMap is made), and in parallel.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o pcdmap_probe pcdmap_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./pcdmap_probe > pcdmap_probe.out 2>&1, then trailing white
 * space removed from every line (the tests compare lines without it). */
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

static const char *attrs[] = { "Disco", "PcdCen", "PcdCen(1)", "PcdCen(2)" };

/* Each attribute: whether it is set and its value as astGetC gives it. */
static void attributes( AstMapping *m ) {
   for( int i = 0; i < 4; i++ ) {
      int t = astTest( m, attrs[ i ] );
      const char *v = astGetC( m, attrs[ i ] );
      if( failed() ) return;
      printf( "%s %d %s\n", attrs[ i ], t, v );
   }
}

/* The points every transformation is given. */
#define NPT 6
static const double xs[ NPT ] = { 0.1, 0.35, -0.25, 1.5, 0.3, AST__BAD };
static const double ys[ NPT ] = { 0.2, -0.45, 0.05, -2.0, -0.2, 0.7 };

/* The forward and inverse transformations of the points, given to every
   pair of coordinates (a Mapping of four inputs is given each point on
   both pairs). */
static void tran( AstMapping *m ) {
   double in[ 4 ][ NPT ], out[ 4 ][ NPT ];
   int n = astGetI( m, "Nin" );
   for( int k = 0; k < n; k++ ) {
      for( int i = 0; i < NPT; i++ ) in[ k ][ i ] = ( k % 2 ) ? ys[ i ] : xs[ i ];
   }
   for( int dir = 1; dir >= 0; dir-- ) {
      astTranN( m, NPT, n, NPT, (const double *) in, dir, n, NPT, (double *) out );
      if( failed() ) return;
      for( int i = 0; i < NPT; i++ ) {
         printf( "%s", dir ? "fwd" : "inv" );
         for( int k = 0; k < n; k++ ) printf( " %.17g", in[ k ][ i ] );
         printf( " ->" );
         for( int k = 0; k < n; k++ ) printf( " %.17g", out[ k ][ i ] );
         printf( "\n" );
      }
   }
}

static double cen[ 2 ] = { 0.3, -0.2 };

/* The PcdMap most cases use. */
static AstPcdMap *typical( void ) {
   return astPcdMap( 0.01, cen, " " );
}

static AstPcdMap *pcd( double disco, double x, double y ) {
   double c[ 2 ] = { x, y };
   return astPcdMap( disco, c, " " );
}

static AstMapping *inv( void *m ) {
   astInvert( m );
   return m;
}

/* The dump, the Object read back from it through a Channel and that
   Object's dump, then the transformations of both. */
static void roundtrip( const char *name, AstMapping *m ) {
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
   printf( "-- equal %d\n", astEqual( m, back ) );
   printf( "-- transformations\n" );
   tran( m );
   printf( "-- transformations read back\n" );
   tran( back );
   ch = astAnnul( ch );
}

/* The class of the simplified Mapping, its dump, and the transformations
   of the original and the simplified Mapping. */
static void simp( const char *name, AstMapping *m ) {
   head( name );
   AstMapping *s = astSimplify( m );
   if( failed() ) return;
   printf( "class %s\n", astGetC( s, "Class" ) );
   astShow( s );
   printf( "-- transformations\n" );
   tran( m );
   printf( "-- transformations simplified\n" );
   tran( s );
}

static AstZoomMap *zoom( double f ) {
   return astZoomMap( 2, f, " " );
}

/* A PermMap exchanging the two axes. */
static AstPermMap *swap( void ) {
   int perm[ 2 ] = { 2, 1 };
   return astPermMap( 2, perm, 2, perm, NULL, " " );
}

static AstCmpMap *series( void *a, void *b ) {
   return astCmpMap( a, b, 1, " " );
}

static AstCmpMap *series3( void *a, void *b, void *c ) {
   return series( series( a, b ), c );
}

static AstCmpMap *parallel( void *a, void *b ) {
   return astCmpMap( a, b, 0, " " );
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* Attributes. */
   head( "attributes_constructed" );
   attributes( (AstMapping *) typical() );
   head( "attributes_constructed_bad" );
   {
      double c[ 2 ] = { AST__BAD, 5.0 };
      attributes( (AstMapping *) astPcdMap( AST__BAD, c, " " ) );
   }
   head( "attributes_cleared" );
   {
      AstPcdMap *p = typical();
      astClear( p, "Disco,PcdCen" );
      attributes( (AstMapping *) p );
   }
   head( "attributes_cleared_axis_2" );
   {
      AstPcdMap *p = typical();
      astClear( p, "PcdCen(2)" );
      attributes( (AstMapping *) p );
   }
   head( "attributes_set" );
   {
      AstPcdMap *p = pcd( AST__BAD, AST__BAD, AST__BAD );
      astSet( p, "Disco=-2.5E-3,PcdCen(1)=1.25,PcdCen(2)= -7 " );
      attributes( (AstMapping *) p );
   }
   head( "attributes_set_both_axes" );
   {
      AstPcdMap *p = typical();
      astSet( p, "PcdCen=3.5" );
      attributes( (AstMapping *) p );
   }
   head( "set_bad_unsets" );
   {
      AstPcdMap *p = typical();
      astSetD( p, "Disco", AST__BAD );
      astSetD( p, "PcdCen(1)", AST__BAD );
      attributes( (AstMapping *) p );
   }
   head( "axis_out_of_range" );
   {
      AstPcdMap *p = typical();
      printf( "get 3\n" );
      astGetD( p, "PcdCen(3)" );
      failed();
      printf( "get 0\n" );
      astGetD( p, "PcdCen(0)" );
      failed();
      printf( "set 3\n" );
      astSetD( p, "PcdCen(3)", 1.0 );
      failed();
      printf( "set 0\n" );
      astSet( p, "PcdCen(0)=1.0" );
      failed();
      printf( "test 3\n" );
      astTest( p, "PcdCen(3)" );
      failed();
      printf( "clear 3\n" );
      astClear( p, "PcdCen(3)" );
      failed();
      attributes( (AstMapping *) p );
   }
   head( "set_not_a_number" );
   {
      AstPcdMap *p = typical();
      astSet( p, "Disco=abc" );
      failed();
      astSet( p, "PcdCen(1)=abc" );
      failed();
      attributes( (AstMapping *) p );
   }
   head( "shared_set" );
   {
      AstPcdMap *p = typical();
      AstCmpMap *c = series( p, zoom( 2.0 ) );
      astSetD( p, "Disco", 2.0 );
      failed();
      astSetD( p, "PcdCen(2)", 2.0 );
      failed();
      astSet( p, "PcdCen=2.0" );
      failed();
      astClear( p, "Disco" );
      failed();
      astClear( p, "PcdCen(1)" );
      failed();
      astClear( p, "PcdCen" );
      failed();
      printf( "set 3\n" );
      astSetD( p, "PcdCen(3)", 2.0 );
      failed();
      attributes( (AstMapping *) p );
      astInvert( p );
      failed();
      printf( "Invert %d\n", astGetI( p, "Invert" ) );
      AstPcdMap *copy = astCopy( p );
      astSetD( copy, "Disco", 2.0 );
      astClear( copy, "PcdCen(1)" );
      failed();
      printf( "-- copy\n" );
      attributes( (AstMapping *) copy );
      c = astAnnul( c );
   }

   /* Native dumps. */
   roundtrip( "dump_set", (AstMapping *) typical() );
   roundtrip( "dump_inverted", inv( typical() ) );
   roundtrip( "dump_unset", (AstMapping *) pcd( AST__BAD, AST__BAD, AST__BAD ) );
   roundtrip( "dump_zero", (AstMapping *) pcd( 0.0, 0.0, 0.0 ) );
   {
      const char *clr[] = { "Disco", "PcdCen(1)", "PcdCen(2)" };
      const char *names[] = { "dump_without_Disco", "dump_without_PcdCen1", "dump_without_PcdCen2" };
      for( int i = 0; i < 3; i++ ) {
         AstPcdMap *p = typical();
         astClear( p, clr[ i ] );
         roundtrip( names[ i ], (AstMapping *) p );
      }
   }
   roundtrip( "dump_simplified", (AstMapping *) astSimplify( typical() ) );

   /* Transformations, rates, split and equality. */
   head( "transformations" );
   tran( (AstMapping *) typical() );
   head( "transformations_inverted" );
   tran( inv( typical() ) );
   head( "transformations_barrel" );
   tran( (AstMapping *) pcd( -0.3, 0.1, 0.1 ) );
   head( "transformations_large" );
   {
      AstPcdMap *p = pcd( 5.0, 0.0, 0.0 );
      double x[ 3 ] = { 1.0, 10.0, 100.0 }, y[ 3 ] = { 0.0, 10.0, -100.0 };
      double xo[ 3 ], yo[ 3 ], xb[ 3 ], yb[ 3 ];
      astTran2( p, 3, x, y, 1, xo, yo );
      astTran2( p, 3, xo, yo, 0, xb, yb );
      failed();
      for( int i = 0; i < 3; i++ ) {
         printf( "%.17g %.17g -> %.17g %.17g -> %.17g %.17g\n", x[ i ], y[ i ], xo[ i ], yo[ i ],
                 xb[ i ], yb[ i ] );
      }
   }
   head( "rate" );
   {
      AstPcdMap *p = typical();
      double at[ 2 ] = { 0.1, 0.2 };
      for( int i = 1; i <= 2; i++ ) {
         for( int j = 1; j <= 2; j++ ) {
            printf( "fwd %d %d %.17g\n", i, j, astRate( p, at, i, j ) );
         }
      }
      astInvert( p );
      for( int i = 1; i <= 2; i++ ) {
         for( int j = 1; j <= 2; j++ ) {
            printf( "inv %d %d %.17g\n", i, j, astRate( p, at, i, j ) );
         }
      }
      failed();
   }
   head( "split" );
   {
      int in1[ 1 ] = { 1 }, in2[ 2 ] = { 2, 1 }, out[ 2 ];
      AstMapping *s = NULL;
      astMapSplit( inv( typical() ), 1, in1, out, &s );
      failed();
      printf( "one input %s\n", s ? astGetC( s, "Class" ) : "null" );
      s = NULL;
      astMapSplit( inv( typical() ), 2, in2, out, &s );
      failed();
      if( s ) {
         printf( "outputs %d %d\n", out[ 0 ], out[ 1 ] );
         astShow( s );
      } else {
         printf( "two inputs null\n" );
      }
   }
   head( "equal" );
   {
      AstPcdMap *a = typical();
      AstPcdMap *cleared = pcd( 0.01, 0.0, 0.0 );
      astClear( cleared, "PcdCen" );
      AstPcdMap *zero = pcd( 0.01, 0.0, 0.0 );
      printf( "same %d\n", astEqual( a, typical() ) );
      printf( "copy %d\n", astEqual( a, astCopy( a ) ) );
      printf( "different_disco %d\n", astEqual( a, pcd( 0.02, 0.3, -0.2 ) ) );
      printf( "different_centre %d\n", astEqual( a, pcd( 0.01, 0.3, -0.3 ) ) );
      printf( "near_disco %d\n", astEqual( a, pcd( 0.01 * ( 1.0 + 1.0E-12 ), 0.3, -0.2 ) ) );
      printf( "inverted %d\n", astEqual( a, inv( typical() ) ) );
      printf( "both_inverted %d\n", astEqual( inv( typical() ), inv( typical() ) ) );
      printf( "zero_vs_unset %d\n", astEqual( zero, cleared ) );
      printf( "small_vs_unset_disco %d\n", astEqual( pcd( 1.0E-13, 0.0, 0.0 ), pcd( AST__BAD, 0.0, 0.0 ) ) );
      printf( "small_vs_zero_disco %d\n", astEqual( pcd( 1.0E-13, 0.0, 0.0 ), pcd( 0.0, 0.0, 0.0 ) ) );
      printf( "not_a_pcdmap %d\n", astEqual( a, zoom( 2.0 ) ) );
      failed();
   }

   /* Simplification of a PcdMap alone, and replacement of one with no
      distortion by a UnitMap. */
   simp( "alone", (AstMapping *) typical() );
   simp( "alone_inverted", inv( typical() ) );
   simp( "zero_disco", (AstMapping *) pcd( 0.0, 0.3, -0.2 ) );
   simp( "unset_disco", (AstMapping *) pcd( AST__BAD, 0.3, -0.2 ) );
   simp( "zero_disco_inverted", inv( pcd( 0.0, 0.3, -0.2 ) ) );
   simp( "small_disco", (AstMapping *) pcd( 1.0E-13, 0.3, -0.2 ) );
   simp( "tiny_disco", (AstMapping *) pcd( 1.0E-310, 0.3, -0.2 ) );
   simp( "zero_disco_parallel", (AstMapping *) parallel( pcd( 0.0, 0.3, -0.2 ), zoom( 2.0 ) ) );
   simp( "zero_disco_series", (AstMapping *) series3( zoom( 2.0 ), pcd( 0.0, 0.3, -0.2 ), zoom( 3.0 ) ) );

   /* Neighbours a PcdMap merges with: a UnitMap and its own inverse. */
   simp( "unit_after", (AstMapping *) series( typical(), astUnitMap( 2, " " ) ) );
   simp( "unit_before_inverted", (AstMapping *) series( astUnitMap( 2, " " ), inv( typical() ) ) );
   simp( "inverse_after", (AstMapping *) series( typical(), inv( typical() ) ) );
   simp( "inverse_before", (AstMapping *) series( inv( typical() ), typical() ) );
   {
      AstPcdMap *p = pcd( 0.01, 0.0, 0.0 );
      astClear( p, "PcdCen" );
      simp( "inverse_unset_centre", (AstMapping *) series( pcd( 0.01, 0.0, 0.0 ), inv( p ) ) );
   }
   simp( "inverse_near_disco",
         (AstMapping *) series( typical(), inv( pcd( 0.01 * ( 1.0 + 1.0E-12 ), 0.3, -0.2 ) ) ) );
   simp( "inverse_different_disco",
         (AstMapping *) series( typical(), inv( pcd( 0.01 * ( 1.0 + 1.0E-9 ), 0.3, -0.2 ) ) ) );
   simp( "inverse_different_centre", (AstMapping *) series( typical(), inv( pcd( 0.01, 0.3, -0.3 ) ) ) );
   simp( "inverse_small_centre_offset",
         (AstMapping *) series( pcd( 0.01, 0.0, 0.0 ), inv( pcd( 0.01, 1.0E-13, 0.0 ) ) ) );
   simp( "same_direction", (AstMapping *) series( typical(), typical() ) );
   {
      AstPcdMap *p = typical();
      simp( "shared_pair", (AstMapping *) series( p, inv( astCopy( p ) ) ) );
   }
   simp( "parallel_inverse", (AstMapping *) parallel( typical(), inv( typical() ) ) );

   /* Swaps past a ZoomMap (PcdZoom) towards a PcdMap to merge with. */
   simp( "zoom_then_inverse",
         (AstMapping *) series3( typical(), zoom( 2.0 ), inv( pcd( 0.0025, 0.6, -0.4 ) ) ) );
   simp( "inverted_zoom_then_inverse",
         (AstMapping *) series3( typical(), inv( zoom( 2.0 ) ), inv( pcd( 0.04, 0.15, -0.1 ) ) ) );
   simp( "inverted_pcd_zoom_then_pcd",
         (AstMapping *) series3( inv( typical() ), zoom( 2.0 ), pcd( 0.0025, 0.6, -0.4 ) ) );
   simp( "inverted_pcd_inverted_zoom_then_pcd",
         (AstMapping *) series3( inv( typical() ), inv( zoom( 0.5 ) ), pcd( 0.0025, 0.6, -0.4 ) ) );
   simp( "zoom_then_inverse_not_matching",
         (AstMapping *) series3( typical(), zoom( 2.0 ), inv( typical() ) ) );
   {
      AstZoomMap *z = zoom( 2.0 );
      AstCmpMap *c = series3( typical(), z, inv( pcd( 0.04, 0.15, -0.1 ) ) );
      astInvert( z );
      simp( "zoom_inverted_after_combining", (AstMapping *) c );
   }
   {
      AstPcdMap *p = typical();
      AstCmpMap *c = series3( p, zoom( 2.0 ), pcd( 0.0025, 0.6, -0.4 ) );
      astInvert( p );
      simp( "pcd_inverted_after_combining", (AstMapping *) c );
   }
   simp( "two_zooms_then_inverse",
         (AstMapping *) series( series3( typical(), zoom( 2.0 ), zoom( 3.0 ) ), inv( pcd( 0.01 / 36.0, 1.8, -1.2 ) ) ) );
   simp( "zoom_inverse_both_sides",
         (AstMapping *) series( series3( inv( pcd( 0.04, 0.15, -0.1 ) ), zoom( 2.0 ), typical() ),
                                series( zoom( 2.0 ), inv( pcd( 0.0025, 0.6, -0.4 ) ) ) ) );

   /* Swaps past a PermMap exchanging the axes (PcdPerm). */
   simp( "perm_then_inverse",
         (AstMapping *) series3( typical(), swap(), inv( pcd( 0.01, -0.2, 0.3 ) ) ) );
   simp( "inverted_pcd_perm_then_pcd",
         (AstMapping *) series3( inv( typical() ), swap(), pcd( 0.01, -0.2, 0.3 ) ) );
   simp( "inverted_perm_then_inverse",
         (AstMapping *) series3( typical(), inv( swap() ), inv( pcd( 0.01, -0.2, 0.3 ) ) ) );
   simp( "perm_zoom_then_inverse",
         (AstMapping *) series( series3( typical(), swap(), zoom( 2.0 ) ), inv( pcd( 0.0025, -0.4, 0.6 ) ) ) );
   {
      int inperm[ 2 ] = { 1, 2 }, outperm[ 2 ] = { 1, -1 };
      double con[ 1 ] = { 4.0 };
      AstPermMap *pm = astPermMap( 2, inperm, 2, outperm, con, " " );
      simp( "perm_not_a_swap", (AstMapping *) series3( typical(), pm, inv( typical() ) ) );
   }
   simp( "perm_alone_after", (AstMapping *) series( typical(), swap() ) );

   /* Swaps with no PcdMap to merge with, kept only when a swapped Mapping
      then simplifies. */
   simp( "zoom_after_no_target", (AstMapping *) series( typical(), zoom( 2.0 ) ) );
   simp( "zoom_before_no_target", (AstMapping *) series( zoom( 2.0 ), typical() ) );
   simp( "unit_zoom_after", (AstMapping *) series( typical(), zoom( 1.0 ) ) );
   simp( "shift_between", (AstMapping *) series3( typical(), astShiftMap( 2, cen, " " ), inv( typical() ) ) );

   astEnd;
   return 0;
}
