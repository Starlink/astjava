/* switchmap_probe -- SwitchMap behaviour of the pinned C library that no
 * fixture or oracle holds: the route a selector value picks, in range,
 * at the halfway points and beyond the range of int; transformations and
 * rates of the SwitchMaps of testswitchmap.c sections 1 and 2 at full
 * precision; native dumps with selectors and routes inverted at
 * construction and afterwards, one Mapping as both selectors, LutMap,
 * MathMap and PcdMap components, a SwitchMap inside a CmpMap and a
 * TranMap, and a dump without ISInv, each read back through a Channel;
 * astEqual; simplification of a SwitchMap alone, inverted, with route and
 * selector Mappings that simplify, beside its own inverse and other
 * Mappings; and the constructor's errors.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o switchmap_probe switchmap_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./switchmap_probe > switchmap_probe.out 2>&1, then trailing
 * white space removed from every line (the tests compare lines without
 * it). */
#include <float.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

/* The lines a Channel writes, which a second Channel reads back. */
#define MAXLINES 800
static char *lines[ MAXLINES ];
static int nline = 0;
static int iline = 0;

static void sink( const char *line ) {
   if( nline < MAXLINES ) lines[ nline++ ] = strcpy( malloc( strlen( line ) + 1 ), line );
}

static const char *source( void ) {
   return ( iline < nline ) ? lines[ iline++ ] : NULL;
}

static void clear_lines( void ) {
   for( int i = 0; i < nline; i++ ) free( lines[ i ] );
   nline = iline = 0;
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

static AstMapping *inv( void *m ) {
   astInvert( m );
   return m;
}

static AstWinMap *win( double x1, double x2, double l1, double l2 ) {
   return astWinMap( 1, &x1, &x2, &l1, &l2, " " );
}

static AstMathMap *mathmap( const char *f, const char *i ) {
   const char *fwd[] = { f };
   const char *invf[] = { i };
   return astMathMap( 1, 1, 1, fwd, 1, invf, " " );
}

/* The points every transformation is given, on each input. */
#define NPT 5
static const double pts1[ NPT ] = { 1.0, 37.5, 101.0, 140.0, AST__BAD };
static const double pts2[ NPT ] = { 1.0, 2.0, 2.0, 1.0, 1.0 };
static const double wave[ NPT ] = { 1000.0, 1700.0, 1900.0, 2700.0, AST__BAD };

/* The forward and inverse transformations of the points. Positions with
   one coordinate are given pts1 forward and wave inverse; positions with
   two are given pts1 and pts2. */
static void tran( AstMapping *m ) {
   double in[ 2 ][ NPT ], out[ 2 ][ NPT ];
   for( int dir = 1; dir >= 0; dir-- ) {
      if( !astGetL( m, dir ? "TranForward" : "TranInverse" ) ) {
         printf( "%s undefined\n", dir ? "fwd" : "inv" );
         continue;
      }
      int nin = astGetI( m, dir ? "Nin" : "Nout" );
      int nout = astGetI( m, dir ? "Nout" : "Nin" );
      for( int i = 0; i < NPT; i++ ) {
         if( nin == 1 ) {
            in[ 0 ][ i ] = dir ? pts1[ i ] : wave[ i ];
         } else {
            in[ 0 ][ i ] = pts1[ i ];
            in[ 1 ][ i ] = pts2[ i ];
         }
      }
      astTranN( m, NPT, nin, NPT, (const double *) in, dir, nout, NPT, (double *) out );
      if( failed() ) return;
      for( int i = 0; i < NPT; i++ ) {
         printf( "%s", dir ? "fwd" : "inv" );
         for( int k = 0; k < nin; k++ ) printf( " %.17g", in[ k ][ i ] );
         printf( " ->" );
         for( int k = 0; k < nout; k++ ) printf( " %.17g", out[ k ][ i ] );
         printf( "\n" );
      }
   }
}

/* The dump, the Object read back from it through a Channel and that
   Object's dump, whether the two are equal, then the transformations of
   both. */
static void roundtrip( const char *name, AstMapping *m ) {
   head( name );
   astShow( m );
   clear_lines();
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

/* testswitchmap.c section 1: two rows of a 2-D grid, each a spectrum; a
   PermMap forward selector reading the row number and a MathMap inverse
   selector choosing a row by wavelength. */
static AstSwitchMap *section1( void ) {
   AstMapping *rm[ 2 ];
   int outperm[ 1 ] = { 1 };
   int inperm[ 2 ] = { 1, -1 };
   double con = 1.0;
   AstPermMap *pm1 = astPermMap( 2, inperm, 1, outperm, &con, " " );
   rm[ 0 ] = (AstMapping *) astCmpMap( pm1, win( 1.0, 101.0, 1000.0, 2000.0 ), 1, " " );
   con = 2.0;
   AstPermMap *pm2 = astPermMap( 2, inperm, 1, outperm, &con, " " );
   rm[ 1 ] = (AstMapping *) astCmpMap( pm2, win( 1.0, 101.0, 1600.0, 2700.0 ), 1, " " );
   int fsout[ 1 ] = { 2 };
   int fsin[ 2 ] = { 0, 1 };
   con = 0.0;
   AstPermMap *fs = astPermMap( 2, fsin, 1, fsout, &con, " " );
   AstMathMap *is = mathmap( "y", "x=qif(y>1800,2,1)" );
   return astSwitchMap( fs, is, 2, (void **) rm, " " );
}

/* testswitchmap.c section 2: two halves of a 1-D grid, with one MathMap
   as both selectors. */
static AstSwitchMap *section2( AstMathMap **sel ) {
   AstMapping *rm[ 2 ];
   rm[ 0 ] = (AstMapping *) win( 1.0, 101.0, 1000.0, 2000.0 );
   rm[ 1 ] = (AstMapping *) win( 102.0, 202.0, 1600.0, 2700.0 );
   AstMathMap *sm = mathmap( "y=qif(x>101.5,2,1)", "x=qif(y>1800,2,1)" );
   if( sel ) *sel = sm;
   return astSwitchMap( sm, sm, 2, (void **) rm, " " );
}

/* A 1-D SwitchMap whose selectors are both the identity, so that the
   value given picks the route, with routes multiplying by 2 and 3. */
static AstSwitchMap *identity_selected( void ) {
   AstMapping *rm[ 2 ];
   rm[ 0 ] = (AstMapping *) astZoomMap( 1, 2.0, " " );
   rm[ 1 ] = (AstMapping *) astZoomMap( 1, 3.0, " " );
   return astSwitchMap( astUnitMap( 1, " " ), astUnitMap( 1, " " ), 2, (void **) rm, " " );
}

/* A 1-D SwitchMap with the given selectors and routes. */
static AstSwitchMap *switch2( void *fs, void *is, void *r1, void *r2 ) {
   AstMapping *rm[ 2 ] = { r1, r2 };
   return astSwitchMap( fs, is, 2, (void **) rm, " " );
}

/* The MathMap selector of section 2. */
static AstMathMap *halves( void ) {
   return mathmap( "y=qif(x>101.5,2,1)", "x=qif(y>1800,2,1)" );
}

/* The plain 1-D SwitchMap many cases use. */
static AstSwitchMap *plain( void ) {
   return switch2( halves(), halves(), win( 1.0, 101.0, 1000.0, 2000.0 ), win( 102.0, 202.0, 1600.0, 2700.0 ) );
}

static double table[] = { 1000.0, 1100.0, 1300.0, 1600.0, 2000.0 };

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* The route each selector value picks: the input is the selector
      value, and route 1 doubles it, route 2 triples it. */
   head( "selector_values" );
   {
      AstSwitchMap *sw = identity_selected();
      double v[] = { -1.0e30, -2147483649.0, -0.5, 0.0, 0.49, 0.49999999999999994, 0.5,
                     1.4999999999999998, 1.5, 2.25, 2.4999999999999996, 2.5, 3.0,
                     2147483647.0, 2147483648.5, 4294967297.0, 4294967298.0, 1.0e30,
                     -DBL_MAX, DBL_MAX, AST__BAD };
      int n = sizeof( v ) / sizeof( v[ 0 ] );
      double f[ 32 ], i[ 32 ];
      astTran1( sw, n, v, 1, f );
      astTran1( sw, n, v, 0, i );
      for( int k = 0; k < n; k++ ) {
         double r = astRate( sw, &v[ k ], 1, 1 );
         printf( "%.17g fwd %.17g inv %.17g rate %.17g\n", v[ k ], f[ k ], i[ k ], r );
      }
      failed();
   }

   /* testswitchmap.c section 1 and 2, inverted as C's test inverts them,
      then not inverted, with their rates. */
   head( "section1" );
   {
      AstSwitchMap *sw = section1();
      tran( (AstMapping *) sw );
      astInvert( sw );
      printf( "-- inverted\n" );
      tran( (AstMapping *) sw );
      astInvert( sw );
      double at[ 2 ] = { 20.0, 1.0 };
      printf( "rate 20 1: %.17g\n", astRate( sw, at, 1, 1 ) );
      at[ 1 ] = 2.0;
      printf( "rate 20 2: %.17g\n", astRate( sw, at, 1, 1 ) );
      printf( "rate 20 2 axis 2: %.17g\n", astRate( sw, at, 1, 2 ) );
      astInvert( sw );
      double w = 1700.0;
      printf( "rate inverted 1700: %.17g\n", astRate( sw, &w, 1, 1 ) );
      w = 1900.0;
      printf( "rate inverted 1900: %.17g\n", astRate( sw, &w, 1, 2 ) );
      failed();
   }
   head( "section2" );
   {
      AstMathMap *sm;
      AstSwitchMap *sw = section2( &sm );
      astInvert( sm );
      tran( (AstMapping *) sw );
      astInvert( sw );
      printf( "-- inverted\n" );
      tran( (AstMapping *) sw );
      astInvert( sw );
      double x = 20.0;
      printf( "rate 20: %.17g\n", astRate( sw, &x, 1, 1 ) );
      x = 120.0;
      printf( "rate 120: %.17g\n", astRate( sw, &x, 1, 1 ) );
      x = 300.0;
      printf( "rate 300: %.17g\n", astRate( sw, &x, 1, 1 ) );
      astInvert( sw );
      x = 1700.0;
      printf( "rate inverted 1700: %.17g\n", astRate( sw, &x, 1, 1 ) );
      x = 1900.0;
      printf( "rate inverted 1900: %.17g\n", astRate( sw, &x, 1, 1 ) );
      failed();
   }
   head( "forward_only" );
   {
      AstSwitchMap *sw = switch2( halves(), NULL, win( 1.0, 101.0, 1000.0, 2000.0 ),
                                  win( 102.0, 202.0, 1600.0, 2700.0 ) );
      printf( "TranForward %d TranInverse %d\n", astGetI( sw, "TranForward" ), astGetI( sw, "TranInverse" ) );
      tran( (AstMapping *) sw );
      double x = 20.0;
      printf( "rate 20: %.17g\n", astRate( sw, &x, 1, 1 ) );
      astInvert( sw );
      printf( "inverted TranForward %d TranInverse %d\n", astGetI( sw, "TranForward" ),
              astGetI( sw, "TranInverse" ) );
      x = 1700.0;
      printf( "rate inverted 1700: %.17g\n", astRate( sw, &x, 1, 1 ) );
      failed();
   }
   head( "inverted_routes" );
   {
      AstSwitchMap *sw = switch2( halves(), halves(), inv( win( 1000.0, 2000.0, 1.0, 101.0 ) ),
                                  inv( win( 1600.0, 2700.0, 102.0, 202.0 ) ) );
      tran( (AstMapping *) sw );
      double x = 20.0;
      printf( "rate 20: %.17g\n", astRate( sw, &x, 1, 1 ) );
      astInvert( sw );
      x = 1900.0;
      printf( "rate inverted 1900: %.17g\n", astRate( sw, &x, 1, 1 ) );
      failed();
   }
   head( "components_inverted_after" );
   {
      AstMathMap *fs = halves();
      AstMathMap *is = halves();
      AstWinMap *r1 = win( 1.0, 101.0, 1000.0, 2000.0 );
      AstWinMap *r2 = win( 102.0, 202.0, 1600.0, 2700.0 );
      AstSwitchMap *sw = switch2( fs, is, r1, r2 );
      astInvert( fs );
      astInvert( is );
      astInvert( r1 );
      tran( (AstMapping *) sw );
      printf( "Invert fs %d is %d r1 %d r2 %d\n", astGetI( fs, "Invert" ), astGetI( is, "Invert" ),
              astGetI( r1, "Invert" ), astGetI( r2, "Invert" ) );
      failed();
   }

   /* Native dumps and read back. */
   roundtrip( "dump_section1", (AstMapping *) section1() );
   {
      AstMathMap *sm;
      AstSwitchMap *sw = section2( &sm );
      astInvert( sm );
      roundtrip( "dump_section2", (AstMapping *) sw );
   }
   roundtrip( "dump_inverted", inv( plain() ) );
   roundtrip( "dump_forward_only",
              (AstMapping *) switch2( halves(), NULL, win( 1.0, 101.0, 1000.0, 2000.0 ),
                                      win( 102.0, 202.0, 1600.0, 2700.0 ) ) );
   roundtrip( "dump_inverse_only",
              (AstMapping *) switch2( NULL, halves(), win( 1.0, 101.0, 1000.0, 2000.0 ),
                                      win( 102.0, 202.0, 1600.0, 2700.0 ) ) );
   roundtrip( "dump_inverted_at_construction",
              (AstMapping *) switch2( inv( halves() ), inv( halves() ), inv( win( 1000.0, 2000.0, 1.0, 101.0 ) ),
                                      win( 102.0, 202.0, 1600.0, 2700.0 ) ) );
   {
      AstMathMap *fs = halves();
      AstWinMap *r2 = win( 102.0, 202.0, 1600.0, 2700.0 );
      AstSwitchMap *sw = switch2( fs, halves(), win( 1.0, 101.0, 1000.0, 2000.0 ), r2 );
      astInvert( fs );
      astInvert( r2 );
      roundtrip( "dump_inverted_after", (AstMapping *) sw );
   }
   {
      AstLutMap *lut = astLutMap( 5, table, 1.0, 25.0, " " );
      AstMathMap *mm = mathmap( "y = x * 10 + 990", "x = ( y - 990 ) / 10" );
      roundtrip( "dump_lutmap_mathmap", (AstMapping *) switch2( halves(), halves(), lut, mm ) );
   }
   {
      double cen[ 2 ] = { 1.0, 1.5 };
      int fsin[ 2 ] = { 0, 1 }, fsout[ 1 ] = { 2 };
      double con = 0.0;
      AstPermMap *fs = astPermMap( 2, fsin, 1, fsout, &con, " " );
      AstPcdMap *pcd = astPcdMap( 1.0e-3, cen, " " );
      AstZoomMap *z = astZoomMap( 2, 2.0, " " );
      roundtrip( "dump_pcdmap", (AstMapping *) switch2( fs, NULL, pcd, z ) );
   }
   roundtrip( "dump_in_cmpmap",
              (AstMapping *) astCmpMap( astZoomMap( 1, 2.0, " " ), plain(), 1, " " ) );
   roundtrip( "dump_in_tranmap",
              (AstMapping *) astTranMap( plain(), inv( plain() ), " " ) );
   {
      AstMapping *rm[ 1 ] = { (AstMapping *) plain() };
      roundtrip( "dump_in_switchmap", (AstMapping *) astSwitchMap( astUnitMap( 1, " " ), NULL, 1, (void **) rm, " " ) );
   }

   /* A dump with FSInv set and ISInv absent: the inverse selector takes
      the forward selector's flag. */
   head( "load_without_isinv" );
   {
      AstSwitchMap *sw = switch2( inv( halves() ), halves(), win( 1.0, 101.0, 1000.0, 2000.0 ),
                                  win( 102.0, 202.0, 1600.0, 2700.0 ) );
      clear_lines();
      AstChannel *ch = astChannel( source, sink, " " );
      astWrite( ch, sw );
      int out = 0;
      for( int k = 0; k < nline; k++ ) {
         if( strstr( lines[ k ], "ISInv" ) ) {
            free( lines[ k ] );
         } else {
            lines[ out++ ] = lines[ k ];
         }
      }
      nline = out;
      AstMapping *back = astRead( ch );
      if( !failed() ) {
         astShow( back );
         printf( "-- transformations read back\n" );
         tran( back );
      }
      ch = astAnnul( ch );
   }

   /* Equality. */
   head( "equal" );
   {
      AstSwitchMap *a = plain();
      AstSwitchMap *b = plain();
      printf( "same_contents %d\n", astEqual( a, b ) );
      AstSwitchMap *c = switch2( halves(), halves(), win( 1.0, 101.0, 1000.0, 2000.0 ),
                                 win( 102.0, 202.0, 1600.0, 2800.0 ) );
      printf( "different_route %d\n", astEqual( a, c ) );
      AstSwitchMap *d = switch2( halves(), NULL, win( 1.0, 101.0, 1000.0, 2000.0 ),
                                 win( 102.0, 202.0, 1600.0, 2700.0 ) );
      printf( "missing_inverse_selector %d\n", astEqual( a, d ) );
      printf( "missing_inverse_selector_reversed %d\n", astEqual( d, a ) );
      AstSwitchMap *e = switch2( halves(), NULL, win( 1.0, 101.0, 1000.0, 2000.0 ),
                                 win( 102.0, 202.0, 1600.0, 2700.0 ) );
      printf( "both_forward_only %d\n", astEqual( d, e ) );
      AstMapping *rm3[ 3 ] = { (AstMapping *) win( 1.0, 101.0, 1000.0, 2000.0 ),
                               (AstMapping *) win( 102.0, 202.0, 1600.0, 2700.0 ),
                               (AstMapping *) win( 102.0, 202.0, 1600.0, 2700.0 ) };
      AstSwitchMap *f = astSwitchMap( halves(), halves(), 3, (void **) rm3, " " );
      printf( "three_routes %d\n", astEqual( a, f ) );
      AstSwitchMap *ainv = astCopy( a );
      astInvert( ainv );
      printf( "inverted_vs_plain %d\n", astEqual( ainv, a ) );
      AstSwitchMap *n = (AstSwitchMap *) astSimplify( ainv );
      printf( "inverted_vs_normalised %d\n", astEqual( ainv, n ) );
      printf( "normalised_vs_inverted %d\n", astEqual( n, ainv ) );
      AstSwitchMap *g = switch2( halves(), halves(), inv( win( 1000.0, 2000.0, 1.0, 101.0 ) ),
                                 win( 102.0, 202.0, 1600.0, 2700.0 ) );
      printf( "route_inverted_equivalent %d\n", astEqual( a, g ) );
      AstMathMap *fs = halves();
      AstSwitchMap *h = switch2( fs, halves(), win( 1.0, 101.0, 1000.0, 2000.0 ),
                                 win( 102.0, 202.0, 1600.0, 2700.0 ) );
      astInvert( fs );
      printf( "selector_inverted_after %d\n", astEqual( a, h ) );
      printf( "Invert fs after %d\n", astGetI( fs, "Invert" ) );
      AstMathMap *sm;
      AstSwitchMap *s2a = section2( &sm );
      AstSwitchMap *s2b = section2( NULL );
      astInvert( sm );
      printf( "shared_selectors %d\n", astEqual( s2a, s2b ) );
      printf( "Invert shared after %d test %d\n", astGetI( sm, "Invert" ), astTest( sm, "Invert" ) );
      printf( "not_a_switchmap %d\n", astEqual( a, astZoomMap( 1, 2.0, " " ) ) );
      failed();
   }

   /* Simplification. */
   simp( "simp_plain", (AstMapping *) plain() );
   simp( "simp_inverted", inv( plain() ) );
   simp( "simp_routes_simplify",
         (AstMapping *) switch2( halves(), halves(),
                                 astCmpMap( astZoomMap( 1, 2.0, " " ), astZoomMap( 1, 5.0, " " ), 1, " " ),
                                 astCmpMap( win( 102.0, 202.0, 1600.0, 2700.0 ), astUnitMap( 1, " " ), 1, " " ) ) );
   simp( "simp_inverted_route_simplifies",
         (AstMapping *) switch2( halves(), halves(), inv( astZoomMap( 1, 0.1, " " ) ),
                                 win( 102.0, 202.0, 1600.0, 2700.0 ) ) );
   simp( "simp_selector_simplifies",
         (AstMapping *) switch2( astCmpMap( astZoomMap( 1, 2.0, " " ), astZoomMap( 1, 0.5, " " ), 1, " " ), NULL,
                                 win( 0.0, 1.0, 0.0, 10.0 ), win( 0.0, 1.0, 0.0, 20.0 ) ) );
   simp( "simp_inverted_routes_simplify",
         inv( switch2( halves(), halves(),
                       astCmpMap( astZoomMap( 1, 2.0, " " ), astZoomMap( 1, 5.0, " " ), 1, " " ),
                       win( 102.0, 202.0, 1600.0, 2700.0 ) ) ) );
   {
      AstSwitchMap *sw = plain();
      AstSwitchMap *swi = astCopy( sw );
      astInvert( swi );
      simp( "simp_beside_inverse", (AstMapping *) astCmpMap( sw, swi, 1, " " ) );
      simp( "simp_inverse_beside", (AstMapping *) astCmpMap( swi, sw, 1, " " ) );
      simp( "simp_beside_inverse_parallel", (AstMapping *) astCmpMap( sw, swi, 0, " " ) );
      simp( "simp_beside_inverse_inverted_cmpmap", inv( astCmpMap( sw, swi, 1, " " ) ) );
      simp( "simp_beside_inverse_between",
            (AstMapping *) astCmpMap( astCmpMap( astZoomMap( 1, 2.0, " " ), sw, 1, " " ),
                                      astCmpMap( swi, astZoomMap( 1, 0.5, " " ), 1, " " ), 1, " " ) );
      AstSwitchMap *sw2 = plain();
      simp( "simp_beside_equal", (AstMapping *) astCmpMap( sw, sw2, 1, " " ) );
      AstSwitchMap *swc = switch2( halves(), halves(), win( 1.0, 101.0, 1000.0, 2000.0 ),
                                   win( 102.0, 202.0, 1600.0, 2800.0 ) );
      astInvert( swc );
      simp( "simp_beside_unequal_inverse", (AstMapping *) astCmpMap( sw, swc, 1, " " ) );
      simp( "simp_beside_zoommap", (AstMapping *) astCmpMap( astZoomMap( 1, 2.0, " " ), sw, 1, " " ) );
   }
   {
      AstSwitchMap *s1 = section1();
      AstSwitchMap *s1i = astCopy( s1 );
      astInvert( s1i );
      simp( "simp_section1_beside_inverse", (AstMapping *) astCmpMap( s1, s1i, 1, " " ) );
      simp( "simp_section1_inverse_beside", (AstMapping *) astCmpMap( s1i, s1, 1, " " ) );
      simp( "simp_section1_inverted", inv( section1() ) );
   }
   {
      AstMathMap *sm;
      AstSwitchMap *sw = section2( &sm );
      astInvert( sm );
      simp( "simp_section2_shared_selector", (AstMapping *) sw );
      printf( "-- Invert shared after %d test %d\n", astGetI( sm, "Invert" ), astTest( sm, "Invert" ) );
      AstMathMap *sm2;
      AstSwitchMap *swb = section2( &sm2 );
      astInvert( sm2 );
      simp( "simp_section2_shared_selector_inverted", inv( swb ) );
      printf( "-- Invert shared after %d test %d\n", astGetI( sm2, "Invert" ), astTest( sm2, "Invert" ) );
   }
   {
      AstWinMap *r = win( 1.0, 101.0, 1000.0, 2000.0 );
      AstSwitchMap *sw = switch2( halves(), halves(), r, r );
      astInvert( r );
      simp( "simp_shared_route", (AstMapping *) sw );
      printf( "-- Invert shared after %d test %d\n", astGetI( r, "Invert" ), astTest( r, "Invert" ) );
   }

   /* Constructor errors. */
   head( "error_no_routes" );
   astSwitchMap( halves(), halves(), 0, NULL, " " );
   failed();
   head( "error_route_nin" );
   {
      AstMapping *rm[ 2 ] = { (AstMapping *) astZoomMap( 1, 2.0, " " ), (AstMapping *) astZoomMap( 2, 2.0, " " ) };
      astSwitchMap( halves(), NULL, 2, (void **) rm, " " );
      failed();
   }
   head( "error_route_nout" );
   {
      int outperm[ 2 ] = { 1, 1 };
      int inperm[ 1 ] = { 1 };
      AstMapping *rm[ 2 ] = { (AstMapping *) astZoomMap( 1, 2.0, " " ),
                              (AstMapping *) astPermMap( 1, inperm, 2, outperm, NULL, " " ) };
      astSwitchMap( halves(), NULL, 2, (void **) rm, " " );
      failed();
   }
   {
      double coeff[] = { 1.0, 1.0, 1.0 };
      AstMapping *rm[ 1 ] = { (AstMapping *) astZoomMap( 1, 2.0, " " ) };
      head( "error_fsmap_no_forward" );
      AstPolyMap *p = astPolyMap( 1, 1, 1, coeff, 0, NULL, "IterInverse=0" );
      astSwitchMap( inv( p ), NULL, 1, (void **) rm, " " );
      failed();
      head( "error_ismap_no_inverse" );
      AstPolyMap *q = astPolyMap( 1, 1, 1, coeff, 0, NULL, "IterInverse=0" );
      astSwitchMap( NULL, q, 1, (void **) rm, " " );
      failed();
      head( "error_fsmap_nin" );
      astSwitchMap( astZoomMap( 2, 1.0, " " ), NULL, 1, (void **) rm, " " );
      failed();
      head( "error_fsmap_nout" );
      {
         int outperm[ 2 ] = { 1, 1 };
         int inperm[ 1 ] = { 1 };
         astSwitchMap( astPermMap( 1, inperm, 2, outperm, NULL, " " ), NULL, 1, (void **) rm, " " );
         failed();
      }
      head( "error_ismap_nout" );
      astSwitchMap( NULL, astZoomMap( 2, 1.0, " " ), 1, (void **) rm, " " );
      failed();
      head( "error_ismap_nin" );
      {
         int outperm[ 1 ] = { 1 };
         int inperm[ 2 ] = { 1, 1 };
         astSwitchMap( NULL, astPermMap( 2, inperm, 1, outperm, NULL, " " ), 1, (void **) rm, " " );
         failed();
      }
      head( "error_no_selectors" );
      astSwitchMap( NULL, NULL, 1, (void **) rm, " " );
      failed();
   }

   astEnd;
   return 0;
}
