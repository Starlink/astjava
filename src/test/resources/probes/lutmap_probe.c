/* lutmap_probe -- LutMap behaviour of the pinned C library that no fixture
 * or oracle holds: simplification beside WinMaps, ZoomMaps and its own
 * inverse; native dumps with LutInterp and LutEpsilon set, cleared and
 * unset, through a Channel and a FitsChan, and read back; the attribute
 * strings; the last-value cache of Transform; and FITS-WCS reads of -TAB
 * axes with TabOK set and no tables.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -I<build> -o lutmap_probe lutmap_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./lutmap_probe > lutmap_probe.out 2>&1, then trailing white
 * space removed from every line (LutMapTest compares lines without it). */
#include <float.h>
#include <stdio.h>
#include <string.h>
#include "ast.h"

static double curved[] = { 0.0, 1.0, 4.0, 9.0, 16.0 };
static double linear[] = { 1.0, 3.0, 5.0, 7.0, 9.0 };

static void head( const char *name ) {
   printf( "== %s\n", name );
}

/* The class of the simplified Mapping, then its dump. */
static void simp( const char *name, AstMapping *map ) {
   head( name );
   AstMapping *s = astSimplify( map );
   printf( "class %s\n", astGetC( s, "Class" ) );
   astShow( s );
}

static AstLutMap *lut( int n, double *t, double start, double inc ) {
   return astLutMap( n, t, start, inc, " " );
}

static AstLutMap *inverted( AstLutMap *m ) {
   AstLutMap *c = astCopy( m );
   astInvert( c );
   return c;
}

static void attrs( AstLutMap *m ) {
   printf( "LutInterp %s test %d\n", astGetC( m, "LutInterp" ), astTest( m, "LutInterp" ) );
   printf( "LutEpsilon %s test %d\n", astGetC( m, "LutEpsilon" ), astTest( m, "LutEpsilon" ) );
}

/* The dump, then the cards of a native FitsChan write, then the dump of
   the Object read back from them. */
static void dumps( const char *name, AstObject *obj ) {
   head( name );
   astShow( obj );
   AstFitsChan *fc = astFitsChan( NULL, NULL, "Encoding=NATIVE" );
   astWrite( fc, obj );
   printf( "-- fits\n" );
   char card[ 81 ];
   astClear( fc, "Card" );
   while( astFindFits( fc, "%f", card, 1 ) ) printf( "%s\n", card );
   astClear( fc, "Card" );
   AstObject *back = astRead( fc );
   printf( "-- read back from fits\n" );
   astShow( back );
}

static void tran( AstMapping *m, double x, int fwd ) {
   double y;
   astTran1( m, 1, &x, fwd, &y );
   printf( "%s %.17g -> %.17g\n", fwd ? "fwd" : "inv", x, y );
}

static void tabread( const char *name, const char **cards, const char *attrs ) {
   head( name );
   AstFitsChan *fc = astFitsChan( NULL, NULL, "" );
   if( attrs ) astSet( fc, "%s", attrs );
   for( int i = 0; cards[ i ]; i++ ) astPutFits( fc, cards[ i ], 0 );
   astClear( fc, "Card" );
   AstObject *obj = astRead( fc );
   if( !astOK ) {
      printf( "read status %d\n", astStatus );
      astClearStatus;
      return;
   }
   if( !obj ) {
      printf( "read nothing\n" );
      return;
   }
   AstKeyMap *warn = astWarnings( fc );
   if( warn ) {
      int n = astMapSize( warn );
      for( int i = 0; i < n; i++ ) {
         const char *k = astMapKey( warn, i );
         const char *v;
         astMapGet0C( warn, k, &v );
         printf( "warning %s: %s\n", k, v );
      }
   }
   printf( "nframe %d\n", astGetI( obj, "Nframe" ) );
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* Simplification. */
   AstLutMap *lc = lut( 5, curved, 0.0, 1.0 );
   AstLutMap *ll = lut( 5, linear, 0.0, 1.0 );
   double ina = 0.0, inb = 1.0, outa = 1.0, outb = 3.0;
   AstWinMap *wm = astWinMap( 1, &ina, &inb, &outa, &outb, " " );
   AstZoomMap *zm = astZoomMap( 1, 2.0, " " );

   simp( "win_lut", (AstMapping *) astCmpMap( wm, lc, 1, " " ) );
   simp( "lut_win", (AstMapping *) astCmpMap( lc, wm, 1, " " ) );
   simp( "zoom_lut", (AstMapping *) astCmpMap( zm, lc, 1, " " ) );
   simp( "lut_zoom", (AstMapping *) astCmpMap( lc, zm, 1, " " ) );
   simp( "zoom_linlut", (AstMapping *) astCmpMap( zm, ll, 1, " " ) );
   simp( "linlut_win", (AstMapping *) astCmpMap( ll, wm, 1, " " ) );
   simp( "linlut_alone", (AstMapping *) ll );
   simp( "linlut_inverted", (AstMapping *) inverted( ll ) );
   simp( "lut_lutinv", (AstMapping *) astCmpMap( lc, inverted( lc ), 1, " " ) );
   simp( "lutinv_lut", (AstMapping *) astCmpMap( inverted( lc ), lc, 1, " " ) );
   {
      AstCmpMap *cm = astCmpMap( lc, inverted( lc ), 1, " " );
      astInvert( cm );
      simp( "inverted_cmp_lut_lutinv", (AstMapping *) cm );
   }
   {
      AstCmpMap *cm = astCmpMap( lc, astCopy( lc ), 1, " " );
      astInvert( cm );
      simp( "inverted_cmp_lut_lut", (AstMapping *) cm );
   }
   {
      AstCmpMap *inner = astCmpMap( zm, lc, 1, " " );
      astInvert( inner );
      simp( "inverted_inner_then_lut", (AstMapping *) astCmpMap( inner, lc, 1, " " ) );
   }
   simp( "lut_lut", (AstMapping *) astCmpMap( lc, astCopy( lc ), 1, " " ) );
   simp( "lut_lutinv_parallel", (AstMapping *) astCmpMap( lc, inverted( lc ), 0, " " ) );
   {
      AstLutMap *ln = astCopy( lc );
      astSet( ln, "LutInterp=1" );
      simp( "lut_nearestinv", (AstMapping *) astCmpMap( lc, inverted( ln ), 1, " " ) );
   }
   {
      double flat[] = { 1.0, 1.0 + DBL_EPSILON, 1.0 };
      AstLutMap *lf = lut( 3, flat, 0.0, 1.0 );
      simp( "flat_linear_lutinv", (AstMapping *) astCmpMap( lf, inverted( lf ), 1, " " ) );
      double con[] = { 5.0, 5.0, 5.0 };
      AstLutMap *lk = lut( 3, con, 0.0, 1.0 );
      simp( "constant_lutinv", (AstMapping *) astCmpMap( lk, inverted( lk ), 1, " " ) );
   }
   {
      double near[] = { 1.0, 3.0 + 1.0E-9, 5.0, 7.0, 9.0 };
      AstLutMap *ln = lut( 5, near, 0.0, 1.0 );
      simp( "near_linear_default_eps", (AstMapping *) ln );
      AstLutMap *le = lut( 5, near, 0.0, 1.0 );
      astSet( le, "LutEpsilon=1.0E-7" );
      simp( "near_linear_eps", (AstMapping *) le );
   }

   /* Attributes and dumps. */
   double t4[] = { 0.0, 10.0, 20.0, 30.0 };
   {
      AstLutMap *m = lut( 4, t4, 5.0, 2.0 );
      head( "attrs_default" );
      attrs( m );
      dumps( "dump_default", (AstObject *) m );
      astSet( m, "LutInterp=1" );
      head( "attrs_interp1" );
      attrs( m );
      dumps( "dump_interp1", (AstObject *) m );
      astSet( m, "LutEpsilon=1.0E-7" );
      head( "attrs_both" );
      attrs( m );
      dumps( "dump_both", (AstObject *) m );
      astClear( m, "LutInterp" );
      astClear( m, "LutEpsilon" );
      head( "attrs_cleared" );
      attrs( m );
      dumps( "dump_cleared", (AstObject *) m );
      astSet( m, "LutInterp=5" );
      head( "attrs_interp5" );
      attrs( m );
      astSet( m, "LutInterp=0" );
      astSet( m, "LutEpsilon=0.25" );
      head( "attrs_interp0_eps" );
      attrs( m );
      dumps( "dump_interp0_eps", (AstObject *) m );
   }
   {
      double tb[] = { AST__BAD, 1.0, 2.0, 4.0 };
      AstLutMap *m = lut( 4, tb, 0.0, 1.0 );
      dumps( "dump_bad_first", (AstObject *) m );
      double tn[] = { 0.0, 1.0, 0.5, 4.0 };
      AstLutMap *n = lut( 4, tn, 0.0, 1.0 );
      astInvert( n );
      dumps( "dump_nonmonotonic_inverted", (AstObject *) n );
      AstLutMap *k = lut( 4, t4, 0.0, 1.0 );
      astSet( k, "LutInterp=1,LutEpsilon=1.0E-7" );
      dumps( "dump_in_cmpmap", (AstObject *) astCmpMap( zm, k, 1, " " ) );
   }

   /* The last-value cache. */
   {
      double t3[] = { 0.0, 10.0, 20.0 };
      AstLutMap *m = lut( 3, t3, 1.0, 1.0 );
      head( "cache" );
      tran( (AstMapping *) m, 1.4, 1 );
      astSet( m, "LutInterp=1" );
      tran( (AstMapping *) m, 1.4, 1 );
      tran( (AstMapping *) m, 1.6, 1 );
      tran( (AstMapping *) m, 1.4, 1 );
      tran( (AstMapping *) m, 4.0, 0 );
      astClear( m, "LutInterp" );
      tran( (AstMapping *) m, 4.0, 0 );
      tran( (AstMapping *) m, 5.0, 0 );
      tran( (AstMapping *) m, 4.0, 0 );
      AstLutMap *c = lut( 3, t3, 1.0, 1.0 );
      head( "cache_copy" );
      tran( (AstMapping *) c, 1.4, 1 );
      AstLutMap *cc = astCopy( c );
      astSet( cc, "LutInterp=1" );
      tran( (AstMapping *) cc, 1.4, 1 );
      astInvert( cc );
      tran( (AstMapping *) cc, 1.4, 0 );
      tran( (AstMapping *) cc, 1.6, 0 );
   }

   /* -TAB axes with TabOK set and no tables. */
   {
      const char *h1[] = { "NAXIS   = 1", "NAXIS1  = 10", "CRPIX1  = 1",
                           "CDELT1  = 1.0E6", "CTYPE1  = 'WAVE-TAB'",
                           "CRVAL1  = 1.4E9", "PS1_0   = 'WCS-TAB'", NULL };
      tabread( "tab_spectral", h1, "TabOK=1" );
      const char *h2[] = { "NAXIS   = 1", "NAXIS1  = 10", "CRPIX1  = 1",
                           "CDELT1  = 1.0E6", "CTYPE1  = 'WAVE-TAB'",
                           "CRVAL1  = 1.4E9", NULL };
      tabread( "tab_no_extname", h2, "TabOK=1" );
      const char *h3[] = { "NAXIS   = 2", "NAXIS1  = 10", "NAXIS2  = 10",
                           "CRPIX1  = 1", "CRPIX2  = 1", "CDELT1  = 1.0",
                           "CDELT2  = 1.0", "CTYPE1  = 'XPOS'",
                           "CTYPE2  = 'YPOS-TAB'", "CRVAL1  = 0.0",
                           "CRVAL2  = 0.0", "PS2_0   = 'TABLE2'",
                           "PV2_1   = 3", "PV2_2   = 2", NULL };
      tabread( "tab_other_extver", h3, "TabOK=1" );
      tabread( "tab_other_tabok0", h3, NULL );
      const char *h4[] = { "NAXIS   = 1", "NAXIS1  = 10", "CRPIX1  = 1",
                           "CDELT1  = 1.0", "CTYPE1  = 'XPOS'",
                           "CRVAL1  = 0.0", "CRPIX1A = 1",
                           "CDELT1A = 1.0E6", "CTYPE1A = 'WAVE-TAB'",
                           "CRVAL1A = 1.4E9", "PS1_0A  = 'WCS-TAB'", NULL };
      tabread( "tab_alternate", h4, "TabOK=1" );
   }

   astEnd;
   printf( "== end status %d\n", status );
   return 0;
}
