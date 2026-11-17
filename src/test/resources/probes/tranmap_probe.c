/* tranmap_probe -- TranMap behaviour of the pinned C library that no
 * fixture or oracle holds: native dumps with each component inverted, at
 * construction and afterwards, with a LutMap and a MathMap inside, each
 * read back through a Channel and dumped again; simplification of a
 * TranMap alone, inverted, with equal components (one object or two,
 * inverted or not), beside other Mappings and other TranMaps; rates;
 * astEqual; astDecompose; astMapSplit; and the constructor's errors.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -I<build> -o tranmap_probe tranmap_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./tranmap_probe > tranmap_probe.out 2>&1, then trailing white
 * space removed from every line (TranMapTest compares lines without it). */
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

/* The forward and inverse images of one value under a 1-D Mapping;
   nothing for any other Mapping. */
static void tran( AstMapping *m, double x ) {
   double f, i;
   if( astGetI( m, "Nin" ) != 1 ) return;
   astTran1( m, 1, &x, 1, &f );
   astTran1( m, 1, &x, 0, &i );
   if( failed() ) return;
   printf( "fwd %.17g -> %.17g\n", x, f );
   printf( "inv %.17g -> %.17g\n", x, i );
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

/* As simp, with the transformations at two values. */
static void simp2( const char *name, AstMapping *m, double x1, double x2 ) {
   head( name );
   AstMapping *s = astSimplify( m );
   if( failed() ) return;
   printf( "class %s\n", astGetC( s, "Class" ) );
   astShow( s );
   printf( "-- transformations\n" );
   tran( m, x1 );
   tran( m, x2 );
   printf( "-- transformations simplified\n" );
   tran( s, x1 );
   tran( s, x2 );
}

static AstZoomMap *zoom( int n, double f ) {
   return astZoomMap( n, f, " " );
}

static AstMapping *inv( AstMapping *m ) {
   astInvert( m );
   return m;
}

static double table[] = { 0.0, 1.0, 4.0, 9.0, 16.0 };

static AstMathMap *mathmap( const char *f, const char *i, const char *settings ) {
   const char *fwd[] = { f };
   const char *invf[] = { i };
   return astMathMap( 1, 1, 1, fwd, 1, invf, "%s", settings );
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* Native dumps and read back. */
   roundtrip( "dump_plain", (AstMapping *) astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " ), 6.0 );
   roundtrip( "dump_inva", (AstMapping *) astTranMap( inv( (AstMapping *) zoom( 1, 2.0 ) ), zoom( 1, 3.0 ), " " ), 6.0 );
   roundtrip( "dump_invb", (AstMapping *) astTranMap( zoom( 1, 2.0 ), inv( (AstMapping *) zoom( 1, 3.0 ) ), " " ), 6.0 );
   roundtrip( "dump_both_inverted",
              (AstMapping *) astTranMap( inv( (AstMapping *) zoom( 1, 2.0 ) ),
                                         inv( (AstMapping *) zoom( 1, 3.0 ) ), " " ), 6.0 );
   roundtrip( "dump_tranmap_inverted",
              inv( (AstMapping *) astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " ) ), 6.0 );
   {
      AstZoomMap *a = zoom( 1, 2.0 );
      AstZoomMap *b = zoom( 1, 3.0 );
      AstTranMap *t = astTranMap( a, b, " " );
      astInvert( a );
      astInvert( b );
      roundtrip( "dump_components_inverted_after", (AstMapping *) t, 6.0 );
   }
   {
      AstLutMap *lut = astLutMap( 5, table, 1.0, 1.0, " " );
      AstMathMap *mm = mathmap( "y = x * x", "x = sqrt( y )", " " );
      roundtrip( "dump_lutmap_mathmap", (AstMapping *) astTranMap( lut, mm, " " ), 2.5 );
      AstLutMap *lut2 = astLutMap( 5, table, 1.0, 1.0, " " );
      AstMathMap *mm2 = mathmap( "y = x * x", "x = sqrt( y )", " " );
      roundtrip( "dump_lutmap_mathmap_inverted",
                 (AstMapping *) astTranMap( inv( (AstMapping *) mm2 ), inv( (AstMapping *) lut2 ), " " ), 2.5 );
   }

   /* Simplification. */
   simp( "simp_plain", (AstMapping *) astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " ), 6.0 );
   simp( "simp_inverted", inv( (AstMapping *) astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " ) ), 6.0 );
   simp( "simp_equal", (AstMapping *) astTranMap( zoom( 1, 2.0 ), zoom( 1, 2.0 ), " " ), 6.0 );
   simp( "simp_equal_inverted_components",
         (AstMapping *) astTranMap( inv( (AstMapping *) zoom( 1, 2.0 ) ),
                                    inv( (AstMapping *) zoom( 1, 2.0 ) ), " " ), 6.0 );
   simp( "simp_equal_inverted_tranmap",
         inv( (AstMapping *) astTranMap( zoom( 1, 2.0 ), zoom( 1, 2.0 ), " " ) ), 6.0 );
   {
      AstZoomMap *z = zoom( 1, 2.0 );
      simp( "simp_same_object", (AstMapping *) astTranMap( z, z, " " ), 6.0 );
   }
   simp( "simp_equal_lutmaps",
         (AstMapping *) astTranMap( astLutMap( 5, table, 1.0, 1.0, " " ),
                                    astLutMap( 5, table, 1.0, 1.0, " " ), " " ), 2.5 );
   simp( "simp_equal_mathmaps",
         (AstMapping *) astTranMap( mathmap( "y = x * x", "x = sqrt( y )", "SimpFI=1,SimpIF=1" ),
                                    mathmap( "y = x * x", "x = sqrt( y )", "SimpFI=1,SimpIF=1" ), " " ), 2.5 );
   simp( "simp_equal_inverted_mathmaps",
         (AstMapping *) astTranMap( inv( (AstMapping *) mathmap( "y = x * x", "x = sqrt( y )", "SimpFI=1,SimpIF=1" ) ),
                                    inv( (AstMapping *) mathmap( "y = x * x", "x = sqrt( y )", "SimpFI=1,SimpIF=1" ) ),
                                    " " ), 2.5 );
   simp2( "simp_equal_inverted_lutmaps",
          (AstMapping *) astTranMap( inv( (AstMapping *) astLutMap( 5, table, 1.0, 1.0, " " ) ),
                                     inv( (AstMapping *) astLutMap( 5, table, 1.0, 1.0, " " ) ), " " ), 2.5, 3.0 );
   simp( "simp_unequal_mathmaps",
         (AstMapping *) astTranMap( mathmap( "y = x * x", "x = sqrt( y )", " " ),
                                    mathmap( "y = x * x", "x = sqrt( y )", " " ), " " ), 2.5 );
   simp( "simp_lutmap_beside_its_inverse",
         (AstMapping *) astCmpMap( astTranMap( astLutMap( 5, table, 1.0, 1.0, " " ), zoom( 1, 3.0 ), " " ),
                                   inv( (AstMapping *) astLutMap( 5, table, 1.0, 1.0, " " ) ), 1, " " ), 2.5 );
   simp( "simp_beside_zoommap",
         (AstMapping *) astCmpMap( astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " ), zoom( 1, 4.0 ), 1, " " ), 6.0 );
   simp( "simp_pair_second_inverted",
         (AstMapping *) astCmpMap( astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " ),
                                   inv( (AstMapping *) astTranMap( zoom( 1, 5.0 ), zoom( 1, 7.0 ), " " ) ), 1, " " ), 6.0 );
   simp( "simp_pair_inverted_series",
         inv( (AstMapping *) astCmpMap( astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " ),
                                        astTranMap( zoom( 1, 5.0 ), zoom( 1, 7.0 ), " " ), 1, " " ) ), 6.0 );
   simp( "simp_pair_parallel",
         (AstMapping *) astCmpMap( astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " ),
                                   astTranMap( zoom( 1, 5.0 ), zoom( 1, 7.0 ), " " ), 0, " " ), 6.0 );
   {
      AstZoomMap *z = zoom( 1, 2.0 );
      AstTranMap *t = astTranMap( z, zoom( 1, 3.0 ), " " );
      simp( "simp_pair_shared_tranmap", (AstMapping *) astCmpMap( t, t, 1, " " ), 6.0 );
   }

   /* Rates. */
   head( "rates" );
   {
      double ina[] = { 0.0, 0.0 }, inb[] = { 1.0, 1.0 };
      double outa[] = { 1.0, 3.0 }, outb[] = { 3.0, 5.0 };
      AstWinMap *w = astWinMap( 2, ina, inb, outa, outb, " " );
      AstWinMap *wi = astCopy( w );
      astInvert( wi );
      AstTranMap *t = astTranMap( w, wi, " " );
      double at[] = { 1.0, 2.0 };
      for( int i = 1; i <= 2; i++ ) {
         for( int j = 1; j <= 2; j++ ) printf( "fwd %d %d %.17g\n", i, j, astRate( t, at, i, j ) );
      }
      astInvert( t );
      for( int i = 1; i <= 2; i++ ) {
         for( int j = 1; j <= 2; j++ ) printf( "inv %d %d %.17g\n", i, j, astRate( t, at, i, j ) );
      }
      AstTranMap *tz = astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " );
      double one = 1.0;
      printf( "zoom fwd %.17g\n", astRate( tz, &one, 1, 1 ) );
      astInvert( tz );
      printf( "zoom inv %.17g\n", astRate( tz, &one, 1, 1 ) );
      AstMathMap *m = mathmap( "y = x * x", "x = sqrt( y )", " " );
      AstTranMap *tm = astTranMap( m, zoom( 1, 3.0 ), " " );
      double three = 3.0;
      printf( "mathmap fwd %.17g\n", astRate( tm, &three, 1, 1 ) );
      failed();
   }

   /* Equality. */
   head( "equal" );
   {
      AstTranMap *a = astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " );
      AstTranMap *b = astTranMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), " " );
      AstTranMap *c = astTranMap( zoom( 1, 2.0 ), zoom( 1, 4.0 ), " " );
      AstTranMap *d = astTranMap( inv( (AstMapping *) zoom( 1, 3.0 ) ),
                                  inv( (AstMapping *) zoom( 1, 2.0 ) ), " " );
      AstTranMap *ainv = astCopy( a );
      astInvert( ainv );
      printf( "same_components %d\n", astEqual( a, b ) );
      printf( "different_components %d\n", astEqual( a, c ) );
      printf( "inverted_vs_swapped %d\n", astEqual( ainv, d ) );
      printf( "swapped_vs_inverted %d\n", astEqual( d, ainv ) );
      printf( "not_a_tranmap %d\n", astEqual( a, zoom( 1, 2.0 ) ) );
      printf( "inverted_vs_plain %d\n", astEqual( ainv, a ) );
      AstZoomMap *z2 = zoom( 1, 2.0 );
      AstZoomMap *z3 = zoom( 1, 3.0 );
      AstTranMap *e = astTranMap( z2, z3, " " );
      astInvert( z2 );
      printf( "component_inverted_after %d\n", astEqual( e, a ) );
      failed();
   }

   /* Decomposition: the series flag is left as the caller set it. */
   head( "decompose" );
   {
      AstShiftMap *s;
      double shift = 1.0;
      s = astShiftMap( 1, &shift, " " );
      astInvert( s );
      AstTranMap *t = astTranMap( zoom( 1, 2.0 ), s, " " );
      AstMapping *m1, *m2;
      int series = 7, i1 = 7, i2 = 7;
      astDecompose( t, &m1, &m2, &series, &i1, &i2 );
      printf( "forward %s %s series %d inverts %d %d\n", astGetC( m1, "Class" ), astGetC( m2, "Class" ),
              series, i1, i2 );
      astInvert( t );
      series = 7;
      astDecompose( t, &m1, &m2, &series, &i1, &i2 );
      printf( "inverted %s %s series %d inverts %d %d\n", astGetC( m1, "Class" ), astGetC( m2, "Class" ),
              series, i1, i2 );
      failed();
   }

   /* Splits. */
   {
      double fs[] = { 10.0, 20.0, 30.0 };
      double is[] = { -10.0, -20.0, -30.0 };
      AstShiftMap *f = astShiftMap( 3, fs, " " );
      AstShiftMap *i = astShiftMap( 3, is, " " );
      astInvert( i );
      AstTranMap *t = astTranMap( f, i, " " );
      int in[] = { 2 };
      int out[ 3 ];
      AstMapping *split;
      head( "split_shiftmaps" );
      astMapSplit( t, 1, in, out, &split );
      if( !failed() && split ) {
         printf( "outputs %d\n", out[ 0 ] );
         astShow( split );
      }
      head( "split_inverted" );
      astInvert( t );
      int in2[] = { 3, 1 };
      astMapSplit( t, 2, in2, out, &split );
      if( !failed() && split ) {
         printf( "outputs %d %d\n", out[ 0 ], out[ 1 ] );
         astShow( split );
      }
      head( "split_mismatched" );
      AstPermMap *p;
      int perm[] = { 2, 1, 3 };
      p = astPermMap( 3, perm, 3, perm, NULL, " " );
      AstTranMap *tp = astTranMap( f, p, " " );
      int in3[] = { 1 };
      astMapSplit( tp, 1, in3, out, &split );
      if( !failed() ) printf( "split %s\n", split ? "made" : "none" );
   }

   /* Constructor errors. */
   head( "error_no_forward" );
   {
      double coeff[] = { 1.0, 1.0, 1.0 };
      AstPolyMap *p = astPolyMap( 1, 1, 1, coeff, 0, NULL, "IterInverse=0" );
      astTranMap( inv( (AstMapping *) p ), zoom( 1, 2.0 ), " " );
      failed();
      head( "error_no_inverse" );
      AstPolyMap *q = astPolyMap( 1, 1, 1, coeff, 0, NULL, "IterInverse=0" );
      astTranMap( zoom( 1, 2.0 ), q, " " );
      failed();
   }
   head( "error_nin" );
   astTranMap( zoom( 1, 2.0 ), zoom( 2, 3.0 ), " " );
   failed();
   head( "error_nout" );
   {
      int outperm[] = { 1, 1 };
      int inperm[] = { 1 };
      AstPermMap *p = astPermMap( 1, inperm, 2, outperm, NULL, " " );
      astTranMap( zoom( 1, 2.0 ), p, " " );
      failed();
   }
   head( "error_nin_equal_nout" );
   {
      int outperm[] = { 1 };
      int inperm[] = { 1, 1 };
      AstPermMap *p = astPermMap( 2, inperm, 1, outperm, NULL, " " );
      astTranMap( p, zoom( 1, 2.0 ), " " );
      failed();
   }

   astEnd;
   return 0;
}
