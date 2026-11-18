/* grismmap_probe -- GrismMap behaviour of the pinned C library that no
 * fixture or oracle holds: attribute values, tests, clears and settings
 * (a fractional or bad value, a GrismMap shared by a CmpMap); native dumps
 * with each attribute set and unset, read back through a Channel;
 * transformations, a rate and astEqual; simplification beside ZoomMaps,
 * WinMaps and another GrismMap; and FITS headers with -GRI and -GRA axes
 * read with each encoding and written back, and FrameSets holding a
 * GrismMap written as FITS-WCS.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o grismmap_probe grismmap_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./grismmap_probe > out 2>&1, then trailing white space removed
 * from every line (the tests compare lines without it). The output is kept
 * as the string "output" of grismmap_probe.json, since the spelling check of
 * text files rejects a spelling in the FITS comments C writes. */
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

static const char *attrs[] = { "GrismNR", "GrismNRP", "GrismWaveR", "GrismAlpha",
                               "GrismG", "GrismM", "GrismEps", "GrismTheta" };

/* Each attribute: whether it is set and its value as astGetC gives it. */
static void attributes( AstMapping *m ) {
   for( int i = 0; i < 8; i++ ) {
      int t = astTest( m, attrs[ i ] );
      const char *v = astGetC( m, attrs[ i ] );
      if( failed() ) return;
      printf( "%s %d %s\n", attrs[ i ], t, v );
   }
}

/* The forward transformation of xf and the inverse of xi, given to every
   coordinate. */
static void tran( AstMapping *m, double xf, double xi ) {
   double in[ 2 ], out[ 2 ];
   int n = astGetI( m, "Nin" );
   for( int i = 0; i < n; i++ ) in[ i ] = xf;
   astTranN( m, 1, n, 1, in, 1, n, 1, out );
   if( failed() ) return;
   printf( "fwd" );
   for( int i = 0; i < n; i++ ) printf( " %.17g", in[ i ] );
   printf( " ->" );
   for( int i = 0; i < n; i++ ) printf( " %.17g", out[ i ] );
   printf( "\n" );
   for( int i = 0; i < n; i++ ) in[ i ] = xi;
   astTranN( m, 1, n, 1, in, 0, n, 1, out );
   if( failed() ) return;
   printf( "inv" );
   for( int i = 0; i < n; i++ ) printf( " %.17g", in[ i ] );
   printf( " ->" );
   for( int i = 0; i < n; i++ ) printf( " %.17g", out[ i ] );
   printf( "\n" );
}

/* A GrismMap with every attribute set, wavelengths in Angstrom. */
static AstGrismMap *typical( void ) {
   return astGrismMap( "GrismNR=1.5,GrismNRP=-1.0E-6,GrismWaveR=5000,GrismAlpha=0.1,"
                       "GrismG=2.0E-4,GrismM=1,GrismEps=0.02,GrismTheta=0.03" );
}

/* The same with GrismM set to m. */
static AstGrismMap *typical_m( int m ) {
   AstGrismMap *g = typical();
   astSetI( g, "GrismM", m );
   return g;
}

static AstMapping *inv( AstMapping *m ) {
   astInvert( m );
   return m;
}

/* The dump, the Object read back from it through a Channel and that
   Object's dump, then the transformations of both. */
static void roundtrip( const char *name, AstMapping *m, double xf, double xi ) {
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
   tran( m, xf, xi );
   printf( "-- transformations read back\n" );
   tran( back, xf, xi );
   ch = astAnnul( ch );
}

/* The class of the simplified Mapping, its dump, and the transformations
   of the original and the simplified Mapping. */
static void simp( const char *name, AstMapping *m, double xf, double xi ) {
   head( name );
   AstMapping *s = astSimplify( m );
   if( failed() ) return;
   printf( "class %s\n", astGetC( s, "Class" ) );
   astShow( s );
   printf( "-- transformations\n" );
   tran( m, xf, xi );
   printf( "-- transformations simplified\n" );
   tran( s, xf, xi );
}

static AstZoomMap *zoom( double f ) {
   return astZoomMap( 1, f, " " );
}

static AstWinMap *win( double scale, double shift ) {
   double ina[] = { 0.0 }, inb[] = { 1.0 };
   double outa[] = { shift }, outb[] = { shift + scale };
   return astWinMap( 1, ina, inb, outa, outb, " " );
}

static AstCmpMap *series( void *a, void *b ) {
   return astCmpMap( a, b, 1, " " );
}

/* The cards of a FitsChan. */
static void cards( AstFitsChan *fc ) {
   char card[ 81 ];
   astClear( fc, "Card" );
   while( astFindFits( fc, "%f", card, 1 ) ) printf( "%s\n", card );
}

/* The forward transformation of pixels 1, 50 and 100, and the inverse of
   the results. */
static void pixels( AstFrameSet *fs ) {
   double pix[] = { 1.0, 50.0, 100.0 }, w[ 3 ], back[ 3 ];
   astTran1( fs, 3, pix, 1, w );
   astTran1( fs, 3, w, 0, back );
   if( failed() ) return;
   for( int i = 0; i < 3; i++ ) printf( "pixel %.17g -> %.17g -> %.17g\n", pix[ i ], w[ i ], back[ i ] );
}

/* Reads the header with the encoding, then writes what it read with that
   encoding and reads the cards written back with it. */
static void fitsrw( const char *name, const char *encoding, const char **header ) {
   head( name );
   AstFitsChan *fc = astFitsChan( NULL, NULL, " " );
   for( int i = 0; header[ i ]; i++ ) astPutFits( fc, header[ i ], 0 );
   astSetC( fc, "Encoding", encoding );
   astClear( fc, "Card" );
   AstFrameSet *fs = astRead( fc );
   if( failed() ) return;
   if( !fs ) {
      printf( "read nothing\n" );
      return;
   }
   printf( "read %s\n", astGetC( fs, "Class" ) );
   pixels( fs );
   AstMapping *map = astGetMapping( fs, AST__BASE, AST__CURRENT );
   astShow( astSimplify( map ) );
   AstFitsChan *out = astFitsChan( NULL, NULL, " " );
   astSetC( out, "Encoding", encoding );
   int n = astWrite( out, fs );
   if( failed() ) return;
   printf( "-- written %d\n", n );
   cards( out );
   if( n ) {
      astClear( out, "Card" );
      AstFrameSet *back = astRead( out );
      if( failed() ) return;
      if( !back ) {
         printf( "-- read back nothing\n" );
         return;
      }
      printf( "-- read back %s\n", astGetC( back, "Class" ) );
      pixels( back );
   }
}

/* Writes a FrameSet from a GRID Frame to a SpecFrame through the Mapping
   as FITS-WCS, and reads back what is written. */
static void fitswrite( const char *name, AstMapping *map, const char *specattrs ) {
   head( name );
   AstFrameSet *fs = astFrameSet( astFrame( 1, "Domain=GRID" ), " " );
   astAddFrame( fs, AST__BASE, map, astSpecFrame( specattrs ) );
   AstFitsChan *out = astFitsChan( NULL, NULL, "Encoding=FITS-WCS" );
   int n = astWrite( out, fs );
   if( failed() ) return;
   printf( "written %d\n", n );
   cards( out );
   pixels( fs );
   if( n ) {
      astClear( out, "Card" );
      AstFrameSet *back = astRead( out );
      if( failed() ) return;
      printf( "-- read back\n" );
      pixels( back );
   }
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* Attributes. */
   head( "attributes_default" );
   attributes( (AstMapping *) astGrismMap( " " ) );
   head( "attributes_set" );
   attributes( (AstMapping *) typical() );
   head( "attributes_cleared" );
   {
      AstGrismMap *g = typical();
      astClear( g, "GrismNR,GrismNRP,GrismWaveR,GrismAlpha,GrismG,GrismM,GrismEps,GrismTheta" );
      attributes( (AstMapping *) g );
   }
   head( "attributes_set_to_defaults" );
   attributes( (AstMapping *) astGrismMap( "GrismNR=1,GrismNRP=0,GrismWaveR=5000,GrismAlpha=0,"
                                           "GrismG=0,GrismM=0,GrismEps=0,GrismTheta=0" ) );
   head( "set_m_fraction" );
   {
      AstGrismMap *g = astGrismMap( " " );
      astSet( g, "GrismM=2.7" );
      printf( "2.7 -> %s\n", astGetC( g, "GrismM" ) );
      astSet( g, "GrismM=-2.7" );
      printf( "-2.7 -> %s\n", astGetC( g, "GrismM" ) );
      astSetD( g, "GrismM", 3.5 );
      printf( "setd 3.5 -> %s\n", astGetC( g, "GrismM" ) );
      printf( "getd %.17g\n", astGetD( g, "GrismM" ) );
      failed();
   }
   head( "set_bad_unsets" );
   {
      AstGrismMap *g = typical();
      astSetD( g, "GrismNR", AST__BAD );
      astSetD( g, "GrismTheta", AST__BAD );
      attributes( (AstMapping *) g );
   }
   head( "set_m_int_max_unsets" );
   {
      AstGrismMap *g = typical();
      astSetI( g, "GrismM", 2147483647 );
      attributes( (AstMapping *) g );
   }
   head( "set_not_a_number" );
   {
      AstGrismMap *g = astGrismMap( " " );
      astSet( g, "GrismNR=abc" );
      failed();
   }
   head( "shared_set" );
   {
      AstGrismMap *g = typical();
      AstCmpMap *c = series( g, zoom( 2.0 ) );
      astSetD( g, "GrismNR", 2.0 );
      failed();
      astClear( g, "GrismM" );
      failed();
      printf( "GrismNR %s\n", astGetC( g, "GrismNR" ) );
      astInvert( g );
      failed();
      printf( "Invert %d\n", astGetI( g, "Invert" ) );
      AstGrismMap *copy = astCopy( g );
      astSetD( copy, "GrismNR", 2.0 );
      failed();
      printf( "copy GrismNR %s\n", astGetC( copy, "GrismNR" ) );
      c = astAnnul( c );
   }

   /* Native dumps, each attribute set alone and each cleared from a
      GrismMap with all set. */
   roundtrip( "dump_default", (AstMapping *) astGrismMap( " " ), 0.01, 5100.0 );
   roundtrip( "dump_all_set", (AstMapping *) typical(), 0.01, 5100.0 );
   roundtrip( "dump_inverted", inv( (AstMapping *) typical() ), 5100.0, 0.01 );
   {
      const char *one[] = { "GrismNR=1.5", "GrismNRP=-1.0E-6", "GrismWaveR=5000", "GrismAlpha=0.1",
                            "GrismG=2.0E-4", "GrismM=1", "GrismEps=0.02", "GrismTheta=0.03" };
      char name[ 64 ];
      for( int i = 0; i < 8; i++ ) {
         snprintf( name, sizeof( name ), "dump_only_%s", attrs[ i ] );
         roundtrip( name, (AstMapping *) astGrismMap( one[ i ] ), 0.01, 5100.0 );
      }
      for( int i = 0; i < 8; i++ ) {
         AstGrismMap *g = typical();
         astClear( g, attrs[ i ] );
         snprintf( name, sizeof( name ), "dump_without_%s", attrs[ i ] );
         roundtrip( name, (AstMapping *) g, 0.01, 5100.0 );
      }
   }
   {
      AstGrismMap *g = typical();
      astSetI( g, "GrismM", -2 );
      roundtrip( "dump_negative_order", (AstMapping *) g, 0.01, 5100.0 );
   }

   roundtrip( "dump_simplified", (AstMapping *) astSimplify( typical() ), 0.01, 5100.0 );
   head( "split" );
   {
      int in[] = { 1 }, out[ 1 ];
      AstMapping *s;
      astMapSplit( inv( (AstMapping *) typical() ), 1, in, out, &s );
      printf( "output %d\n", out[ 0 ] );
      astShow( s );
      failed();
   }

   /* Transformations. */
   head( "transformations" );
   {
      AstGrismMap *g = typical();
      double gp[] = { 0.0, 0.01, -0.01, 0.05, AST__BAD };
      double wl[] = { 4900.0, 5000.0, 5100.0, 5200.0, AST__BAD };
      double out[ 5 ];
      astTran1( g, 5, gp, 1, out );
      for( int i = 0; i < 5; i++ ) printf( "fwd %.17g -> %.17g\n", gp[ i ], out[ i ] );
      astTran1( g, 5, wl, 0, out );
      for( int i = 0; i < 5; i++ ) printf( "inv %.17g -> %.17g\n", wl[ i ], out[ i ] );
      double far[] = { 1.0e6, -1.0e6 };
      astTran1( g, 2, far, 0, out );
      for( int i = 0; i < 2; i++ ) printf( "inv %.17g -> %.17g\n", far[ i ], out[ i ] );
      AstGrismMap *d = astGrismMap( " " );
      double dp[] = { 0.0, 0.5 };
      astTran1( d, 2, dp, 1, out );
      for( int i = 0; i < 2; i++ ) printf( "default fwd %.17g -> %.17g\n", dp[ i ], out[ i ] );
      astTran1( d, 2, wl, 0, out );
      for( int i = 0; i < 2; i++ ) printf( "default inv %.17g -> %.17g\n", wl[ i ], out[ i ] );
      AstGrismMap *e = typical();
      astSetD( e, "GrismEps", 1.5707963267948966 );
      astTran1( e, 2, dp, 1, out );
      for( int i = 0; i < 2; i++ ) printf( "eps 90 fwd %.17g -> %.17g\n", dp[ i ], out[ i ] );
      AstGrismMap *b = typical();
      astSetD( b, "GrismG", 1.0 );
      astTran1( b, 2, dp, 1, out );
      for( int i = 0; i < 2; i++ ) printf( "sinbeta_r over 1 fwd %.17g -> %.17g\n", dp[ i ], out[ i ] );
      failed();
   }
   head( "rate" );
   {
      double at[] = { 0.0 };
      AstGrismMap *g = typical();
      printf( "fwd %.17g\n", astRate( g, at, 1, 1 ) );
      double atw[] = { 5100.0 };
      printf( "inv %.17g\n", astRate( inv( (AstMapping *) g ), atw, 1, 1 ) );
      failed();
   }

   /* Equality. */
   head( "equal" );
   {
      AstGrismMap *a = typical();
      printf( "same %d\n", astEqual( a, typical() ) );
      printf( "copy %d\n", astEqual( a, astCopy( a ) ) );
      AstGrismMap *b = typical();
      astSetD( b, "GrismNR", 1.6 );
      printf( "different_nr %d\n", astEqual( a, b ) );
      printf( "different_m %d\n", astEqual( a, typical_m( -1 ) ) );
      printf( "inverted %d\n", astEqual( a, inv( (AstMapping *) typical() ) ) );
      printf( "both_inverted %d\n", astEqual( inv( (AstMapping *) typical() ), inv( (AstMapping *) typical() ) ) );
      printf( "default %d\n", astEqual( astGrismMap( " " ), astGrismMap( " " ) ) );
      printf( "default_vs_set_to_defaults %d\n",
              astEqual( astGrismMap( " " ),
                        astGrismMap( "GrismNR=1,GrismNRP=0,GrismWaveR=5000,GrismAlpha=0,"
                                     "GrismG=0,GrismM=0,GrismEps=0,GrismTheta=0" ) ) );
      printf( "not_a_grismmap %d\n", astEqual( a, zoom( 2.0 ) ) );
      failed();
   }

   /* Simplification. */
   simp( "zoom_after_forward", (AstMapping *) series( typical(), zoom( 2.0 ) ), 0.01, 10200.0 );
   simp( "inverted_zoom_after_forward", (AstMapping *) series( typical(), inv( (AstMapping *) zoom( 2.0 ) ) ),
         0.01, 2550.0 );
   simp( "zoom_before_inverted", (AstMapping *) series( zoom( 0.5 ), inv( (AstMapping *) typical() ) ),
         10200.0, 0.01 );
   simp( "inverted_zoom_before_inverted",
         (AstMapping *) series( inv( (AstMapping *) zoom( 2.0 ) ), inv( (AstMapping *) typical() ) ), 10200.0, 0.01 );
   simp( "zoom_before_forward", (AstMapping *) series( zoom( 2.0 ), typical() ), 0.01, 5100.0 );
   simp( "zoom_after_inverted", (AstMapping *) series( inv( (AstMapping *) typical() ), zoom( 2.0 ) ), 5100.0, 0.01 );
   simp( "zooms_both_sides",
         (AstMapping *) series( series( zoom( 0.5 ), inv( (AstMapping *) typical() ) ), zoom( 3.0 ) ), 10200.0, 0.03 );
   simp( "zoom_forward_zoom", (AstMapping *) series( series( zoom( 3.0 ), typical() ), zoom( 2.0 ) ), 0.01, 10200.0 );
   {
      AstZoomMap *z = zoom( 2.0 );
      AstCmpMap *c = series( typical(), z );
      astInvert( z );
      simp( "zoom_inverted_after_combining", (AstMapping *) c, 0.01, 10200.0 );
   }
   {
      AstZoomMap *z = zoom( 2.0 );
      AstCmpMap *c = series( z, inv( (AstMapping *) typical() ) );
      astInvert( z );
      simp( "zoom_inverted_after_combining_before", (AstMapping *) c, 10200.0, 0.01 );
   }
   {
      AstGrismMap *g = typical();
      AstCmpMap *c = series( g, zoom( 2.0 ) );
      astInvert( g );
      simp( "grism_inverted_after_combining", (AstMapping *) c, 0.01, 10200.0 );
   }
   simp( "zoom_after_default", (AstMapping *) series( astGrismMap( " " ), zoom( 2.0 ) ), 0.01, 10200.0 );
   simp( "zoom_after_two_grisms",
         (AstMapping *) series( series( typical(), inv( (AstMapping *) typical_m( 2 ) ) ), zoom( 2.0 ) ), 0.01, 0.02 );
   simp( "winmap_scale_after_forward", (AstMapping *) series( typical(), win( 2.0, 0.0 ) ), 0.01, 10200.0 );
   simp( "winmap_after_forward", (AstMapping *) series( typical(), win( 2.0, 10.0 ) ), 0.01, 10210.0 );
   simp( "winmap_scale_before_inverted", (AstMapping *) series( win( 0.5, 0.0 ), inv( (AstMapping *) typical() ) ),
         10200.0, 0.01 );
   simp( "winmap_before_inverted", (AstMapping *) series( win( 0.5, -5.0 ), inv( (AstMapping *) typical() ) ),
         10210.0, 0.01 );
   simp( "shiftmap_after_forward", (AstMapping *) series( typical(), astShiftMap( 1, (double[]){ 10.0 }, " " ) ),
         0.01, 5110.0 );
   simp( "inverse_cancel", (AstMapping *) series( typical(), inv( (AstMapping *) typical_m( -1 ) ) ), 0.01, 0.01 );
   simp( "inverse_cancel_lower", (AstMapping *) series( inv( (AstMapping *) typical_m( -1 ) ), typical() ),
         5100.0, 5100.0 );
   simp( "inverse_same_order", (AstMapping *) series( typical(), inv( (AstMapping *) typical() ) ), 0.01, 0.01 );
   {
      AstGrismMap *b = typical_m( -1 );
      astSetD( b, "GrismNR", 1.6 );
      simp( "inverse_different_nr", (AstMapping *) series( typical(), inv( (AstMapping *) b ) ), 0.01, 0.01 );
   }
   simp( "same_direction", (AstMapping *) series( typical(), typical_m( -1 ) ), 0.0, 0.0 );
   simp( "parallel", (AstMapping *) astCmpMap( typical(), inv( (AstMapping *) typical_m( -1 ) ), 0, " " ), 0.01, 0.01 );
   simp( "parallel_zoom", (AstMapping *) astCmpMap( typical(), zoom( 2.0 ), 0, " " ), 0.01, 0.01 );
   simp( "alone", (AstMapping *) typical(), 0.01, 5100.0 );
   simp( "alone_inverted", inv( (AstMapping *) typical() ), 5100.0, 0.01 );
   {
      AstGrismMap *g = typical();
      simp( "shared_pair", (AstMapping *) series( g, inv( (AstMapping *) astCopy( g ) ) ), 0.01, 0.01 );
   }

   /* FITS headers with grism axes. */
   {
      const char *encodings[] = { "FITS-WCS", "FITS-PC", "FITS-IRAF", "FITS-AIPS", "FITS-AIPS++",
                                  "FITS-CLASS", "NATIVE", "DSS" };
      const char *gri[] = { "CTYPE1  = 'WAVE-GRI'", "CRVAL1  =            5.0E-7",
                            "CRPIX1  =                1.0", "CDELT1  =            1.0E-9",
                            "CUNIT1  = 'm       '", "PV1_0   =            9.0E5",
                            "PV1_1   =                1.0", "PV1_2   =                0.0",
                            "PV1_3   =                1.0", "PV1_4   =                0.0",
                            "PV1_5   =                0.0", "PV1_6   =                0.0", NULL };
      const char *gra[] = { "CTYPE1  = 'AWAV-GRA'", "CRVAL1  =            5.0E-7",
                            "CRPIX1  =               10.0", "CDELT1  =            1.0E-9",
                            "CUNIT1  = 'm       '", "PV1_0   =            6.0E5",
                            "PV1_1   =               -2.0", "PV1_2   =               10.0",
                            "PV1_3   =                1.5", "PV1_4   =             -100.0",
                            "PV1_5   =                2.0", "PV1_6   =                3.0", NULL };
      char name[ 64 ];
      for( int i = 0; i < 8; i++ ) {
         snprintf( name, sizeof( name ), "fits_gri_%s", encodings[ i ] );
         fitsrw( name, encodings[ i ], gri );
         snprintf( name, sizeof( name ), "fits_gra_%s", encodings[ i ] );
         fitsrw( name, encodings[ i ], gra );
      }
      const char *grawave[] = { "CTYPE1  = 'WAVE-GRA'", "CRVAL1  =            5.0E-7",
                                "CRPIX1  =                1.0", "CDELT1  =            1.0E-9",
                                "CUNIT1  = 'm       '", "PV1_0   =            9.0E5",
                                "PV1_1   =                1.0", NULL };
      fitsrw( "fits_wave_gra", "FITS-WCS", grawave );
      const char *freq[] = { "CTYPE1  = 'FREQ-GRI'", "CRVAL1  =           6.0E14",
                             "CRPIX1  =                1.0", "CDELT1  =           1.0E12",
                             "CUNIT1  = 'Hz      '", "PV1_0   =            9.0E5",
                             "PV1_1   =                1.0", NULL };
      fitsrw( "fits_freq_gri", "FITS-WCS", freq );
      const char *nm[] = { "CTYPE1  = 'WAVE-GRI'", "CRVAL1  =              500.0",
                           "CRPIX1  =                1.0", "CDELT1  =                1.0",
                           "CUNIT1  = 'nm      '", "PV1_0   =            9.0E5",
                           "PV1_1   =                1.0", NULL };
      fitsrw( "fits_gri_nm", "FITS-WCS", nm );
      const char *noconv[] = { "CTYPE1  = 'VRAD-GRI'", "CRVAL1  =              100.0",
                               "CRPIX1  =                1.0", "CDELT1  =                1.0",
                               "PV1_0   =            9.0E5", "PV1_1   =                1.0", NULL };
      fitsrw( "fits_vrad_gri", "FITS-WCS", noconv );
      const char *order[] = { "CTYPE1  = 'WAVE-GRI'", "CRVAL1  =            5.0E-7",
                              "CRPIX1  =                1.0", "CDELT1  =            1.0E-9",
                              "CUNIT1  = 'm       '", "PV1_0   =            9.0E5",
                              "PV1_1   =                1.5", NULL };
      fitsrw( "fits_gri_half_order", "FITS-WCS", order );
      const char *order2[] = { "CTYPE1  = 'WAVE-GRI'", "CRVAL1  =            5.0E-7",
                               "CRPIX1  =                1.0", "CDELT1  =            1.0E-9",
                               "CUNIT1  = 'm       '", "PV1_0   =            9.0E5",
                               "PV1_1   =               -2.5", NULL };
      fitsrw( "fits_gri_negative_half_order", "FITS-WCS", order2 );
   }

   /* FrameSets holding a GrismMap, written as FITS-WCS. */
   {
      AstGrismMap *g = astGrismMap( "GrismG=9.0E5,GrismM=1,GrismWaveR=5.0E-7" );
      fitswrite( "write_shift_zoom_grism",
                 (AstMapping *) series( series( astShiftMap( 1, (double[]){ -50.0 }, " " ), zoom( 0.002 ) ), g ),
                 "System=WAVE,Unit=m" );
      AstGrismMap *g2 = astGrismMap( "GrismG=9.0E5,GrismM=1,GrismWaveR=5.0E-7" );
      fitswrite( "write_grism_then_zoom_nm",
                 (AstMapping *) series( series( astShiftMap( 1, (double[]){ -50.0 }, " " ), zoom( 0.002 ) ),
                                        series( g2, zoom( 1.0E9 ) ) ),
                 "System=WAVE,Unit=nm" );
      AstGrismMap *g3 = astGrismMap( "GrismG=9.0E5,GrismM=1,GrismWaveR=5.0E-7" );
      AstSpecMap *w2f = astSpecMap( 1, 0, " " );
      astSpecAdd( w2f, "WVTOFR", 0, NULL );
      fitswrite( "write_grism_freq",
                 (AstMapping *) series( series( astShiftMap( 1, (double[]){ -50.0 }, " " ), zoom( 0.002 ) ),
                                        series( g3, w2f ) ),
                 "System=FREQ,Unit=Hz" );
      AstGrismMap *g4 = astGrismMap( "GrismG=9.0E5,GrismM=1,GrismWaveR=5.0E-7" );
      fitswrite( "write_grism_then_shift",
                 (AstMapping *) series( series( series( astShiftMap( 1, (double[]){ -50.0 }, " " ), zoom( 0.002 ) ), g4 ),
                                        astShiftMap( 1, (double[]){ 1.0E-9 }, " " ) ),
                 "System=WAVE,Unit=m" );
      AstGrismMap *g5 = astGrismMap( "GrismG=9.0E5,GrismM=1,GrismWaveR=5.0E-7" );
      fitswrite( "write_grism_inverted",
                 (AstMapping *) series( series( astShiftMap( 1, (double[]){ -50.0 }, " " ), zoom( 0.002 ) ),
                                        inv( (AstMapping *) inv( (AstMapping *) g5 ) ) ),
                 "System=WAVE,Unit=m" );
   }

   astEnd;
   return 0;
}
