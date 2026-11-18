/* dssmap_probe -- DssMap behaviour of the pinned C library that no fixture
 * or oracle holds. C has no public DssMap constructor, so every DssMap is
 * read from the fixture serialisation/c_dssmap.ast, some with one item
 * removed or its value replaced: transformations both ways, inverted, with
 * bad and far-off points, and with a plate centre near RA zero; rates; astEqual against altered plate solutions;
 * loads with an item missing; and simplification with WinMaps and ZoomMaps
 * on either side, inverted, and in parallel, with the value of the Invert
 * attribute of the WinMap before and after.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o dssmap_probe dssmap_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as
 *   ./dssmap_probe <fixtures>/serialisation/c_dssmap.ast > dssmap_probe.out 2>&1
 * then trailing white space removed from every line (the tests compare
 * lines without it). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

/* The lines of the fixture. */
#define MAXLINES 400
static char *fixture[ MAXLINES ];
static int nfixture = 0;

/* The lines a Channel reads. */
static char *lines[ MAXLINES ];
static int nline = 0;
static int iline = 0;

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

/* Whether a fixture line holds the item "key". */
static int has_key( const char *line, const char *key ) {
   while( *line == ' ' ) line++;
   size_t n = strlen( key );
   return !strncmp( line, key, n ) && line[ n ] == ' ' && line[ n + 1 ] == '=';
}

/* The DssMap the fixture describes, with the item "drop" left out and the
   value of item "key" replaced by "value" (either may be NULL). */
static AstMapping *dss( const char *drop, const char *key, const char *value ) {
   for( int i = 0; i < nline; i++ ) free( lines[ i ] );
   nline = iline = 0;
   for( int i = 0; i < nfixture; i++ ) {
      if( drop && has_key( fixture[ i ], drop ) ) continue;
      char buf[ 300 ];
      if( key && has_key( fixture[ i ], key ) ) {
         const char *comment = strchr( fixture[ i ], '#' );
         sprintf( buf, "    %s = %s \t%s", key, value, comment );
      } else {
         strcpy( buf, fixture[ i ] );
      }
      lines[ nline++ ] = strcpy( malloc( strlen( buf ) + 1 ), buf );
   }
   AstChannel *ch = astChannel( source, NULL, " " );
   AstMapping *m = astRead( ch );
   ch = astAnnul( ch );
   return m;
}

static AstMapping *inv( void *m ) {
   astInvert( m );
   return m;
}

/* The pixel positions every forward transformation is given. */
#define NPIX 7
static const double xs[ NPIX ] = { 1000.0, 1.0, 500.0, 4000.0, 2000.0, AST__BAD, 100.0 };
static const double ys[ NPIX ] = { 2000.0, 1.0, 500.0, 4000.0, 3000.0, 100.0, AST__BAD };

/* The sky positions (radians) every inverse transformation is given: near
   the plate centre, on the far side of the sky, and ninety degrees from
   the plate centre. */
#define NSKY 6
static const double ras[ NSKY ] = { 2.83, 2.8, 5.97, 1.25, AST__BAD, 2.85 };
static const double decs[ NSKY ] = { -1.05, -1.0, 1.05, 0.0, -1.0, AST__BAD };

static void tran_points( AstMapping *m, int forward, int n, const double *x, const double *y ) {
   double ox[ NPIX ], oy[ NPIX ];
   astTran2( m, n, x, y, forward, ox, oy );
   if( failed() ) return;
   for( int i = 0; i < n; i++ ) {
      printf( "%s %.17g %.17g -> %.17g %.17g\n", forward ? "fwd" : "inv", x[ i ], y[ i ], ox[ i ],
              oy[ i ] );
   }
}

/* The forward transformation of the pixel positions, the inverse of the
   results, and the inverse of the sky positions; for an inverted Mapping
   the directions are exchanged. */
static void tran( AstMapping *m ) {
   int pixfwd = !astGetI( m, "Invert" );
   double ox[ NPIX ], oy[ NPIX ];
   tran_points( m, pixfwd, NPIX, xs, ys );
   astTran2( m, NPIX, xs, ys, pixfwd, ox, oy );
   tran_points( m, !pixfwd, NPIX, ox, oy );
   tran_points( m, !pixfwd, NSKY, ras, decs );
}

/* The class of the simplified Mapping, its dump, and the transformations
   of the original and the simplified Mapping, of the pixel positions in
   the direction "fwd", when it has two inputs. */
static void simp( const char *name, AstMapping *m, int fwd ) {
   head( name );
   AstMapping *s = astSimplify( m );
   if( failed() ) return;
   printf( "class %s\n", astGetC( s, "Class" ) );
   astShow( s );
   if( astGetI( m, "Nin" ) != 2 ) return;
   double ox[ NPIX ], oy[ NPIX ];
   printf( "-- transformations\n" );
   astTran2( m, NPIX, xs, ys, fwd, ox, oy );
   for( int i = 0; i < NPIX; i++ ) printf( "%.17g %.17g\n", ox[ i ], oy[ i ] );
   printf( "-- transformations simplified\n" );
   astTran2( s, NPIX, xs, ys, fwd, ox, oy );
   for( int i = 0; i < NPIX; i++ ) printf( "%.17g %.17g\n", ox[ i ], oy[ i ] );
   failed();
}

/* The WinMap x -> 2x - 0.5 on both axes. */
static AstWinMap *win( void ) {
   double ina[ 2 ] = { 0.0, 0.0 }, inb[ 2 ] = { 1.0, 1.0 };
   double outa[ 2 ] = { -0.5, -0.5 }, outb[ 2 ] = { 1.5, 1.5 };
   return astWinMap( 2, ina, inb, outa, outb, " " );
}

/* The WinMap x -> 2x on both axes. */
static AstWinMap *win_no_shift( void ) {
   double ina[ 2 ] = { 0.0, 0.0 }, inb[ 2 ] = { 1.0, 1.0 };
   double outa[ 2 ] = { 0.0, 0.0 }, outb[ 2 ] = { 2.0, 2.0 };
   return astWinMap( 2, ina, inb, outa, outb, " " );
}

static AstCmpMap *series( void *a, void *b ) {
   return astCmpMap( a, b, 1, " " );
}

int main( int argc, char **argv ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   if( argc < 2 ) return 1;
   FILE *f = fopen( argv[ 1 ], "r" );
   if( !f ) return 1;
   char buf[ 300 ];
   while( nfixture < MAXLINES && fgets( buf, sizeof( buf ), f ) ) {
      buf[ strcspn( buf, "\n" ) ] = 0;
      fixture[ nfixture++ ] = strcpy( malloc( strlen( buf ) + 1 ), buf );
   }
   fclose( f );

   astWatch( &status );
   astBegin;

   /* Transformations. */
   head( "tran" );
   tran( dss( NULL, NULL, NULL ) );
   head( "tran_inverted" );
   tran( inv( dss( NULL, NULL, NULL ) ) );
   head( "tran_cnpix_4200" );
   tran( dss( NULL, "CNPix1", "4200" ) );

   /* A plate centre just east of RA zero, so that pixel positions to the
      west of it have their right ascension wrapped by platepos's own
      value of two pi. */
   head( "tran_pltra_small" );
   tran( dss( NULL, "PltRA", "0.0001" ) );

   /* Rates of the forward transformation. */
   head( "rate" );
   {
      AstMapping *m = dss( NULL, NULL, NULL );
      double at[ 2 ] = { 1000.0, 2000.0 };
      for( int ax1 = 1; ax1 <= 2; ax1++ ) {
         for( int ax2 = 1; ax2 <= 2; ax2++ ) {
            double r = astRate( m, at, ax1, ax2 );
            if( failed() ) continue;
            printf( "rate %d %d %.17g\n", ax1, ax2, r );
         }
      }
   }

   /* astEqual against a DssMap with one item replaced. */
   head( "equal" );
   {
      AstMapping *m = dss( NULL, NULL, NULL );
      static const char *keys[] = {
         "PltRA", "PltRA", "PltDec", "PltScl", "CNPix1", "CNPix2", "XPixSz", "YPixSz",
         "PPO1", "PPO2", "PPO3", "PPO4", "PPO5", "PPO6", "AMDX1", "AMDX13", "AMDX14",
         "AMDX19", "AMDY1", "AMDY13", "AMDY14", "AMDY19"
      };
      static const char *values[] = {
         "2.8272374655684120", "2.8272374655685", "-1.05", "60", "4200", "4200", "25.3", "25.3",
         "1", "1", "105990", "1", "1", "105990", "67.15", "1.5e-12", "1", "1", "67.15",
         "1.4e-12", "1", "1"
      };
      printf( "same %d\n", astEqual( m, dss( NULL, NULL, NULL ) ) );
      printf( "copy %d\n", astEqual( m, astCopy( m ) ) );
      printf( "inverted %d\n", astEqual( m, inv( dss( NULL, NULL, NULL ) ) ) );
      printf( "both_inverted %d\n", astEqual( inv( astCopy( m ) ), inv( dss( NULL, NULL, NULL ) ) ) );
      printf( "winmap %d\n", astEqual( m, win() ) );
      for( size_t i = 0; i < sizeof( keys ) / sizeof( keys[ 0 ] ); i++ ) {
         int eq = astEqual( m, dss( NULL, keys[ i ], values[ i ] ) );
         if( failed() ) continue;
         printf( "%s %s %d\n", keys[ i ], values[ i ], eq );
      }
   }

   /* Loads with one item missing. */
   head( "load_missing" );
   {
      static const char *keys[] = {
         "PltRA", "PltDec", "PltScl", "CNPix1", "CNPix2", "XPixSz", "YPixSz", "PPO1", "PPO2",
         "PPO3", "PPO4", "PPO5", "PPO6", "AMDX1", "AMDX2", "AMDX13", "AMDX14", "AMDX19",
         "AMDY1", "AMDY12", "AMDY13", "AMDY14", "AMDY19"
      };
      for( size_t i = 0; i < sizeof( keys ) / sizeof( keys[ 0 ] ); i++ ) {
         printf( "-- %s\n", keys[ i ] );
         AstMapping *m = dss( keys[ i ], NULL, NULL );
         if( failed() ) continue;
         printf( "class %s\n", astGetC( m, "Class" ) );
      }
   }

   /* A load with AMDX14 non-zero: coefficients 14 to 19 (the magnitude and
      colour terms) take no part in the transformations. */
   head( "load_amdx14_nonzero" );
   tran( dss( NULL, "AMDX14", "5" ) );

   /* Simplification. */
   simp( "alone", dss( NULL, NULL, NULL ), 1 );
   simp( "winmap_then_dssmap_fractional",
         (AstMapping *) series( win(), dss( NULL, NULL, NULL ) ), 1 );
   simp( "winmap_then_dssmap_fractional_cnpix2",
         (AstMapping *) series( win(), dss( NULL, "CNPix1", "4200" ) ), 1 );
   {
      /* Both CNPix values 4200. */
      AstMapping *d = dss( NULL, "CNPix1", "4200" );
      AstMapping *e;
      for( int i = 0; i < nline; i++ ) {
         if( has_key( lines[ i ], "CNPix2" ) ) {
            const char *comment = strchr( lines[ i ], '#' );
            sprintf( buf, "    CNPix2 = 4200 \t%s", comment );
            free( lines[ i ] );
            lines[ i ] = strcpy( malloc( strlen( buf ) + 1 ), buf );
         }
      }
      iline = 0;
      AstChannel *ch = astChannel( source, NULL, " " );
      e = astRead( ch );
      ch = astAnnul( ch );
      d = astAnnul( d );

      simp( "winmap_then_dssmap_4200", (AstMapping *) series( win(), e ), 1 );
      simp( "inverted_winmap_then_dssmap_4200", (AstMapping *) series( inv( win() ), e ), 1 );
      simp( "winmap_no_shift_then_dssmap_4200", (AstMapping *) series( win_no_shift(), e ), 1 );
      simp( "dssmap_then_winmap_4200", (AstMapping *) series( e, win() ), 1 );
      simp( "inverted_dssmap_then_winmap", (AstMapping *) series( inv( astCopy( e ) ), win() ), 0 );
      simp( "winmap_then_inverted_dssmap", (AstMapping *) series( win(), inv( astCopy( e ) ) ), 0 );
      simp( "zoommap_then_dssmap", (AstMapping *) series( astZoomMap( 2, 2.0, " " ), e ), 1 );
      simp( "parallel", (AstMapping *) astCmpMap( win(), e, 0, " " ), 1 );

      /* The WinMap's Invert attribute (its value; whether it is set is
         left out) before and after simplifying the inverse of a CmpMap
         that merges: an inverted DssMap followed by an inverted WinMap,
         the whole inverted, applies the WinMap forward before the DssMap
         forward. */
      {
         head( "invert_attr_both_inverted" );
         AstWinMap *w = (AstWinMap *) inv( win() );
         AstCmpMap *c = series( inv( astCopy( e ) ), w );
         astInvert( c );
         printf( "before %d\n", astGetI( w, "Invert" ) );
         AstMapping *s = astSimplify( c );
         printf( "class %s\n", astGetC( s, "Class" ) );
         printf( "after %d\n", astGetI( w, "Invert" ) );
         failed();
         simp( "inverted_dssmap_then_winmap_both_inverted", (AstMapping *) c, 1 );
      }

      /* The same with a DssMap whose merge fails (fractional CNPix). */
      {
         head( "invert_attr_no_merge" );
         AstWinMap *w = (AstWinMap *) inv( win() );
         AstCmpMap *c = series( inv( dss( NULL, NULL, NULL ) ), w );
         astInvert( c );
         printf( "before %d\n", astGetI( w, "Invert" ) );
         AstMapping *s = astSimplify( c );
         printf( "class %s\n", astGetC( s, "Class" ) );
         printf( "after %d\n", astGetI( w, "Invert" ) );
         failed();
      }
   }

   astEnd;
   return 0;
}
