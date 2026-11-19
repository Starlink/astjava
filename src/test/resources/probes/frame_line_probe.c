/* frame_line_probe -- the Frame machinery of the pinned C library that the
 * Region classes use and no fixture or oracle holds: the protected line
 * functions astLineDef, astLineOffset, astLineCrossing and astLineContains
 * on a 2-D Frame, a SkyFrame (lines across RA 0 and over a pole), a
 * SkyFrame with its axes swapped and a FrameSet whose current Frame is a
 * SkyFrame; astNormBox on a Frame, a SkyFrame (boxes across RA 0 and
 * holding a pole), CmpFrames and a FrameSet; astFrameGrid on Frames,
 * SkyFrames, CmpFrames and a FrameSet; the errors of each; and PointSets
 * read from text, written again and compared with astEqual.
 *
 * Each case starts with a line "== <name>". In a line case, "def" lines
 * define lines numbered from 0 in order ("null" where C returns NULL) and
 * give the length, the Infinite flag and the start, end, direction and
 * perpendicular vectors C keeps (3-vectors for a SkyFrame); "off", "cross"
 * and "contains" lines name lines by those numbers and give their inputs
 * before the colon and C's results after it ("cross" gives the five values
 * a SkyFrame writes, which "contains" with def 1 reads). A "box" line gives
 * the bounds and the name of the Mapping (see makemap) before the colon and
 * the normalised bounds after it. A "grid" line gives the size and bounds
 * and the number of points, followed by one line per point. A "pointset"
 * case gives the dump of each PointSet of pstext, after a line naming it,
 * and "equal" lines give astEqual of two of them. Error reports,
 * which the error module writes to standard error, are merged in order
 * (stdout unbuffered).
 *
 * The functions are protected, so their prototypes are declared here and
 * they are called with the pointers astMakePointer gives.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o frame_line_probe
 *      frame_line_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./frame_line_probe > frame_line_probe.out 2>&1, then trailing
 * white space removed from every line. */
#include <float.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include "ast.h"

AstLineDef *astLineDef_( AstFrame *, const double[2], const double[2], int * );
int astLineCrossing_( AstFrame *, AstLineDef *, AstLineDef *, double[5], double *, int * );
int astLineContains_( AstFrame *, AstLineDef *, int, double *, int * );
void astLineOffset_( AstFrame *, AstLineDef *, double, double, double[2], int * );
void astNormBox_( AstFrame *, double[], double[], AstMapping *, int * );
AstPointSet *astFrameGrid_( AstFrame *, int, const double *, const double *, int * );

/* skyframe.c's line structure, which a SkyFrame's astLineDef returns. */
typedef struct SkyLineDef {
   AstFrame *frame;
   double length;
   int infinite;
   double start[3];
   double end[3];
   double dir[3];
   double q[3];
   double start_2d[2];
   double end_2d[2];
} SkyLineDef;

#define D2R ( 3.14159265358979323846 / 180.0 )
#define BAD AST__BAD

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

static void vec( const double *v, int n ) {
   for( int i = 0; i < n; i++ ) printf( " %.17g", v[ i ] );
}

/* The lines of the current line case, and the Frame they were made in. */
#define MAXLINE 20
static AstLineDef *lines[ MAXLINE ];
static int nlines = 0;
static AstFrame *lframe = NULL;
static int lsky = 0;
static double last_cross[ 5 ];

static void line_case( const char *name, AstFrame *frame, int sky ) {
   head( name );
   nlines = 0;
   lframe = astMakePointer( frame );
   lsky = sky;
}

static void def( double x1, double y1, double x2, double y2 ) {
   double s[ 2 ] = { x1, y1 }, e[ 2 ] = { x2, y2 };
   AstLineDef *l = astLineDef_( lframe, s, e, astGetStatusPtr );
   lines[ nlines++ ] = l;
   printf( "def" );
   vec( s, 2 );
   vec( e, 2 );
   printf( " :" );
   if( failed() ) return;
   if( !l ) {
      printf( " null\n" );
   } else if( lsky ) {
      SkyLineDef *sl = (SkyLineDef *) l;
      printf( " %.17g %d", sl->length, sl->infinite );
      vec( sl->start, 3 );
      vec( sl->end, 3 );
      vec( sl->dir, 3 );
      vec( sl->q, 3 );
      printf( "\n" );
   } else {
      printf( " %.17g %d", l->length, l->infinite );
      vec( l->start, 2 );
      vec( l->end, 2 );
      vec( l->dir, 2 );
      vec( l->q, 2 );
      printf( "\n" );
   }
}

static void off( int i, double par, double prp ) {
   double p[ 2 ] = { BAD, BAD };
   astLineOffset_( lframe, lines[ i ], par, prp, p, astGetStatusPtr );
   printf( "off %d %.17g %.17g :", i, par, prp );
   if( failed() ) return;
   vec( p, 2 );
   printf( "\n" );
}

static void cross( int i, int j ) {
   double c[ 5 ] = { BAD, BAD, BAD, BAD, BAD };
   int r = astLineCrossing_( lframe, lines[ i ], lines[ j ], c, NULL, astGetStatusPtr );
   printf( "cross %d %d :", i, j );
   if( failed() ) return;
   printf( " %d", r );
   vec( c, lsky ? 5 : 2 );
   printf( "\n" );
   memcpy( last_cross, c, sizeof( c ) );
}

static void contains( int i, int d, const double *p ) {
   double pt[ 5 ];
   memcpy( pt, p, sizeof( pt ) );
   int r = astLineContains_( lframe, lines[ i ], d, pt, astGetStatusPtr );
   printf( "contains %d %d", i, d );
   vec( pt, ( d && lsky ) ? 5 : 2 );
   printf( " :" );
   if( failed() ) return;
   printf( " %d\n", r );
}

static void contains2( int i, double x, double y ) {
   double p[ 5 ] = { x, y, BAD, BAD, BAD };
   contains( i, 0, p );
}

/* Contains for the point lineOffset gives on line j. */
static void contains_off( int i, int j, double par, double prp ) {
   double p[ 5 ] = { BAD, BAD, BAD, BAD, BAD };
   astLineOffset_( lframe, lines[ j ], par, prp, p, astGetStatusPtr );
   if( failed() ) return;
   contains( i, 0, p );
}

/* The Mapping a "box" line names, for a Frame of naxes axes whose
   latitude is axis ilat (from 0): "none" makes every output bad, "all"
   gives constant good outputs, "unit" is a UnitMap, "north" and "south"
   keep a point whose latitude is above 1.25 or below -1.1 and make every
   output bad otherwise or when any input is bad, and "north_loose" is
   "north" without the last condition. */
static AstMapping *makemap( const char *name, int naxes, int ilat ) {
   if( !strcmp( name, "none" ) ) {
      int outperm[ 3 ] = { 0, 0, 0 };
      return (AstMapping *) astPermMap( naxes, NULL, naxes, outperm, NULL, " " );
   } else if( !strcmp( name, "all" ) ) {
      int outperm[ 3 ] = { -1, -2, -3 };
      double con[ 3 ] = { 0.0, 0.0, 0.0 };
      return (AstMapping *) astPermMap( naxes, NULL, naxes, outperm, con, " " );
   } else if( !strcmp( name, "unit" ) ) {
      return (AstMapping *) astUnitMap( naxes, " " );
   } else {
      const char *cond = !strcmp( name, "south" ) ? "< -1.1" : "> 1.25";
      int loose = !strcmp( name, "north_loose" );
      char fwd[ 3 ][ 200 ], inv[ 3 ][ 200 ], sum[ 100 ] = "";
      const char *pf[ 3 ], *pi[ 3 ];
      for( int i = 0; i < naxes; i++ ) {
         char t[ 10 ];
         sprintf( t, "%si%d", i ? "+" : "", i );
         strcat( sum, t );
      }
      for( int i = 0; i < naxes; i++ ) {
         if( loose ) {
            sprintf( fwd[ i ], "o%d = qif(i%d %s, i%d, <bad>)", i, ilat, cond, i );
         } else {
            sprintf( fwd[ i ], "o%d = qif(i%d %s, i%d, <bad>) + 0*(%s)", i, ilat, cond, i, sum );
         }
         sprintf( inv[ i ], "i%d = o%d", i, i );
         pf[ i ] = fwd[ i ];
         pi[ i ] = inv[ i ];
      }
      return (AstMapping *) astMathMap( naxes, naxes, naxes, pf, naxes, pi, " " );
   }
}

static void box( AstFrame *frame, int ilat, const double *lb, const double *ub, const char *map ) {
   int naxes = astGetI( frame, "Naxes" );
   double l[ 3 ], u[ 3 ];
   memcpy( l, lb, naxes * sizeof( double ) );
   memcpy( u, ub, naxes * sizeof( double ) );
   AstMapping *m = makemap( map, naxes, ilat );
   astNormBox_( astMakePointer( frame ), l, u, astMakePointer( m ), astGetStatusPtr );
   printf( "box" );
   vec( lb, naxes );
   vec( ub, naxes );
   printf( " %s :", map );
   if( failed() ) return;
   vec( l, naxes );
   vec( u, naxes );
   printf( "\n" );
   astAnnul( m );
}

static void grid( AstFrame *frame, int size, const double *lb, const double *ub ) {
   int naxes = astGetI( frame, "Naxes" );
   AstPointSet *ps = astFrameGrid_( astMakePointer( frame ), size, lb, ub, astGetStatusPtr );
   printf( "grid %d", size );
   vec( lb, naxes );
   vec( ub, naxes );
   printf( " :" );
   if( failed() ) return;
   AstObject *id = astMakeId( ps );
   double **ptr = astGetPoints_( ps, astGetStatusPtr );
   int npoint = astGetI( id, "Npoint" );
   int ncoord = astGetI( id, "Ncoord" );
   printf( " %d\n", npoint );
   for( int ip = 0; ip < npoint; ip++ ) {
      for( int ic = 0; ic < ncoord; ic++ ) printf( " %.17g", ptr[ ic ][ ip ] );
      printf( "\n" );
   }
   astAnnul( id );
}

/* PointSets as text, read with a Channel: a name and the lines of its
   description, ending with NULL. */
static const char *pstext[][ 12 ] = {
   { "plain", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2", "    X1 = 0",
     "    X2 = 0", "    X3 = 0.0001", "    X4 = 0.0001", " End PointSet", NULL },
   { "rel_close", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2", "    X1 = 0",
     "    X2 = 0", "    X3 = 0.00010000000000001", "    X4 = 0.0001", " End PointSet", NULL },
   { "rel_far", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2", "    X1 = 0",
     "    X2 = 0", "    X3 = 0.0001000000000001", "    X4 = 0.0001", " End PointSet", NULL },
   { "tiny_close", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2", "    X1 = 1e-152",
     "    X2 = 0", "    X3 = 0.0001", "    X4 = 0.0001", " End PointSet", NULL },
   { "tiny_far", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2", "    X1 = 1e-150",
     "    X2 = 0", "    X3 = 0.0001", "    X4 = 0.0001", " End PointSet", NULL },
   { "bad", " Begin PointSet", "    Npoint = 3", "    Ncoord = 2", "    X1 = 1.5",
     "    X2 = 2.5", "    X4 = -3", "    X6 = 7", " End PointSet", NULL },
   { "bad_other", " Begin PointSet", "    Npoint = 3", "    Ncoord = 2", "    X1 = 1.5",
     "    X2 = 2.5", "    X3 = 4", "    X4 = -3", "    X6 = 7", " End PointSet", NULL },
   { "first_bad", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2",
     "    X2 = 2.5", "    X3 = 4", "    X4 = -3", " End PointSet", NULL },
   { "acc", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2", "    Acc1 = 0.1",
     "    X1 = 0", "    X2 = 0", "    X3 = 0.0001", "    X4 = 0.0001", " End PointSet", NULL },
   { "acc_close", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2", "    Acc1 = 0.1",
     "    X1 = 0.1", "    X2 = 0", "    X3 = 0.0001", "    X4 = 0.0001", " End PointSet", NULL },
   { "acc_far", " Begin PointSet", "    Npoint = 2", "    Ncoord = 2", "    Acc1 = 0.1",
     "    X1 = 0.2", "    X2 = 0", "    X3 = 0.0001", "    X4 = 0.0001", " End PointSet", NULL },
   { "three", " Begin PointSet", "    Npoint = 3", "    Ncoord = 2", "    X1 = 0",
     "    X2 = 0", "    X3 = 0.0001", "    X4 = 0.0001", " End PointSet", NULL },
   { "empty", " Begin PointSet", "    Npoint = 2", "    Ncoord = 3", "    Empty = 1",
     "    X1 = 5", " End PointSet", NULL },
   { "zero", " Begin PointSet", "    Npoint = 0", "    Ncoord = -1", "    X1 = 5",
     " End PointSet", NULL },
   { NULL }
};

static const char **ps_lines;
static int ps_iline;

static const char *ps_source( void ) {
   return ps_lines[ ps_iline ] ? ps_lines[ ps_iline++ ] : NULL;
}

#define NPS 20
static AstObject *ps[ NPS ];

static void show_pointsets( void ) {
   head( "pointset" );
   for( int i = 0; pstext[ i ][ 0 ]; i++ ) {
      ps_lines = pstext[ i ] + 1;
      ps_iline = 0;
      AstChannel *ch = astChannel( ps_source, NULL, " " );
      ps[ i ] = astRead( ch );
      astAnnul( ch );
      printf( "pointset %s :\n", pstext[ i ][ 0 ] );
      if( failed() ) continue;
      astShow( ps[ i ] );
   }
}

static void equal( int i, int j ) {
   int r = astEqual( ps[ i ], ps[ j ] );
   printf( "equal %s %s :", pstext[ i ][ 0 ], pstext[ j ][ 0 ] );
   if( failed() ) return;
   printf( " %d\n", r );
}

static AstSkyFrame *swapped_sky( void ) {
   AstSkyFrame *s = astSkyFrame( " " );
   int perm[ 2 ] = { 2, 1 };
   astPermAxes( s, perm );
   return s;
}

/* A FrameSet with a 2-D base Frame and current Frame cur. */
static AstFrameSet *frameset( AstFrame *cur ) {
   int naxes = astGetI( cur, "Naxes" );
   AstFrameSet *fs = astFrameSet( astFrame( naxes, " " ), " " );
   astAddFrame( fs, AST__BASE, astUnitMap( naxes, " " ), cur );
   return fs;
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* Lines in a 2-D Frame. */
   AstFrame *f2 = astFrame( 2, " " );
   line_case( "line_frame", f2, 0 );
   def( 0, 0, 3, 4 );
   def( 1, 1, 1, 1 );
   def( BAD, 0, 1, 1 );
   def( 0, 0, 4, 0 );
   def( 1, -1, 1, 3 );
   def( 5, -1, 5, 1 );
   def( 0, 1, 4, 1 );
   def( 0, -1, 0, 1 );
   def( 4, -1, 4, 1 );
   def( -2, -3, 2, 5 );
   off( 0, 0, 0 );
   off( 0, 2.5, 1 );
   off( 0, -1, -0.5 );
   off( 0, 10, 0 );
   off( 1, 2, 3 );
   off( 9, 1.5, -2 );
   cross( 3, 4 );
   cross( 3, 5 );
   cross( 3, 6 );
   cross( 3, 7 );
   cross( 3, 8 );
   cross( 4, 3 );
   cross( 0, 9 );
   cross( 9, 0 );
   cross( 1, 1 );
   cross( 1, 3 );
   cross( 0, 3 );
   contains2( 3, 0, 0 );
   contains2( 3, 4, 0 );
   contains2( 3, 2, 0 );
   contains2( 3, 2, 3.9e-7 );
   contains2( 3, 2, -4.1e-7 );
   contains2( 3, -0.1, 0 );
   contains2( 3, BAD, 0 );
   contains2( 0, 1.5, 2 );
   contains2( 0, 3, 4 );
   contains2( 1, 1, 1 );
   cross( 3, 4 );
   contains( 4, 1, last_cross );

   /* Lines on the sky. */
   AstSkyFrame *sky = astSkyFrame( " " );
   line_case( "line_sky", (AstFrame *) sky, 1 );
   def( 10 * D2R, 20 * D2R, 50 * D2R, 60 * D2R );
   def( 350 * D2R, 10 * D2R, 20 * D2R, -5 * D2R );
   def( 10 * D2R, 80 * D2R, 190 * D2R, 80 * D2R );
   def( 60 * D2R, 75 * D2R, 200 * D2R, 78 * D2R );
   def( 1, 0.5, 1, 0.5 );
   def( 0, 0, M_PI, 0 );
   def( -15 * D2R, 0, 15 * D2R, 0 );
   def( 0, -10 * D2R, 0, 10 * D2R );
   def( BAD, 0, 1, 1 );
   def( 5 * D2R, -89 * D2R, 150 * D2R, -88 * D2R );
   off( 0, 0, 0 );
   off( 0, 0.3, 0 );
   off( 0, 0.3, 0.1 );
   off( 0, -0.2, -0.05 );
   off( 0, 1.0, 0 );
   off( 1, 0.2, 0.01 );
   off( 2, 0.17, 0 );
   off( 2, 0.2, 0.02 );
   off( 4, 0.1, 0.05 );
   off( 5, 1.0, 0.1 );
   off( 9, 0.02, -0.01 );
   cross( 6, 7 );
   contains( 6, 1, last_cross );
   contains( 7, 1, last_cross );
   cross( 2, 3 );
   contains( 2, 1, last_cross );
   cross( 0, 1 );
   contains( 0, 1, last_cross );
   cross( 1, 6 );
   cross( 6, 1 );
   cross( 0, 0 );
   cross( 9, 2 );
   cross( 4, 0 );
   cross( 5, 7 );
   contains_off( 0, 0, 0.3, 0 );
   contains_off( 0, 0, 0.3, 1e-9 );
   contains_off( 0, 0, 0.3, 1e-6 );
   contains_off( 0, 0, 0.3, -1e-6 );
   contains_off( 0, 0, 1.2, 0 );
   contains_off( 0, 0, -0.1, 0 );
   contains_off( 1, 1, 0.2, 0 );
   contains_off( 1, 1, 0.2, 2e-6 );
   contains_off( 2, 2, 0.17, 0 );
   contains_off( 9, 9, 0.02, 0 );
   contains_off( 6, 7, 0.1745, 0 );
   contains2( 0, BAD, 0.4 );
   contains2( 6, 0, 0 );
   contains2( 6, 2 * M_PI, 0 );
   contains2( 6, 10 * D2R, 0 );
   contains2( 6, 20 * D2R, 0 );

   /* Lines on a SkyFrame whose axes are (latitude, longitude). */
   AstSkyFrame *swp = swapped_sky();
   line_case( "line_sky_swapped", (AstFrame *) swp, 1 );
   def( 20 * D2R, 10 * D2R, 60 * D2R, 50 * D2R );
   def( 10 * D2R, 350 * D2R, -5 * D2R, 20 * D2R );
   def( 0, -15 * D2R, 0, 15 * D2R );
   off( 0, 0.3, 0.1 );
   off( 1, 0.2, 0.01 );
   cross( 1, 2 );
   contains( 1, 1, last_cross );
   contains_off( 0, 0, 0.3, 0 );
   contains_off( 0, 0, 0.3, 1e-6 );
   contains2( 2, 0, 10 * D2R );

   /* Lines on a FrameSet whose current Frame is a SkyFrame. */
   AstFrameSet *fs = frameset( (AstFrame *) astSkyFrame( " " ) );
   line_case( "line_frameset", (AstFrame *) fs, 1 );
   def( 10 * D2R, 20 * D2R, 50 * D2R, 60 * D2R );
   def( 350 * D2R, 10 * D2R, 20 * D2R, -5 * D2R );
   off( 0, 0.3, 0.1 );
   cross( 0, 1 );
   contains_off( 0, 0, 0.3, 0 );

   /* Errors: a Frame that is not 2-D, and a line made in another Frame. */
   AstFrame *f3 = astFrame( 3, " " );
   line_case( "line_error_naxes", f3, 0 );
   def( 0, 0, 1, 1 );
   line_case( "line_error_frame", f2, 0 );
   def( 0, 0, 1, 1 );
   AstFrame *other = astFrame( 2, " " );
   double s[ 2 ] = { 0, 0 }, e[ 2 ] = { 1, 1 };
   lines[ nlines++ ] = astLineDef_( astMakePointer( other ), s, e, astGetStatusPtr );
   cross( 1, 0 );
   cross( 0, 1 );
   off( 1, 0.5, 0 );
   contains2( 1, 0.5, 0.5 );
   line_case( "line_error_sky_frame", (AstFrame *) sky, 1 );
   def( 0, 0, 1, 1 );
   AstSkyFrame *sky2 = astSkyFrame( " " );
   lines[ nlines++ ] = astLineDef_( astMakePointer( sky2 ), s, e, astGetStatusPtr );
   cross( 1, 0 );
   cross( 0, 1 );
   off( 1, 0.5, 0 );
   contains2( 1, 0.5, 0.5 );
   /* A line a FrameSet makes belongs to its current Frame. */
   line_case( "line_error_frameset", (AstFrame *) sky, 1 );
   lines[ nlines++ ] = astLineDef_( astMakePointer( fs ), s, e, astGetStatusPtr );
   contains2( 0, 0.5, 0.5 );

   /* NormBox. */
   head( "normbox_frame" );
   {
      double lb[ 2 ] = { -1, 2 }, ub[ 2 ] = { 3, -2 };
      box( f2, 1, lb, ub, "unit" );
      box( f2, 1, lb, ub, "none" );
   }
   head( "normbox_sky" );
   {
      double lb1[ 2 ] = { -0.3, -0.2 }, ub1[ 2 ] = { 0.3, 0.2 };
      box( (AstFrame *) sky, 1, lb1, ub1, "north" );
      box( (AstFrame *) sky, 1, lb1, ub1, "none" );
      double lb2[ 2 ] = { 6.0, -0.2 }, ub2[ 2 ] = { 0.3, 0.2 };
      box( (AstFrame *) sky, 1, lb2, ub2, "south" );
      double lb3[ 2 ] = { -0.3, 1.3 }, ub3[ 2 ] = { 0.3, M_PI_2 };
      box( (AstFrame *) sky, 1, lb3, ub3, "north" );
      box( (AstFrame *) sky, 1, lb3, ub3, "south" );
      double lb4[ 2 ] = { 5.0, -1.5 }, ub4[ 2 ] = { 5.5, -1.2 };
      box( (AstFrame *) sky, 1, lb4, ub4, "south" );
      double lb5[ 2 ] = { 1.0, 2.0 }, ub5[ 2 ] = { 2.0, 1.3 };
      box( (AstFrame *) sky, 1, lb5, ub5, "north" );
      double lb6[ 2 ] = { 1.0, -2.0 }, ub6[ 2 ] = { 2.0, -1.3 };
      box( (AstFrame *) sky, 1, lb6, ub6, "south" );
      double lb7[ 2 ] = { 1.0, BAD }, ub7[ 2 ] = { 2.0, 1.3 };
      box( (AstFrame *) sky, 1, lb7, ub7, "north" );
      box( (AstFrame *) sky, 1, lb7, ub7, "south" );
      double lb8[ 2 ] = { -0.3, -0.2 }, ub8[ 2 ] = { 0.3, 0.2 };
      box( (AstFrame *) sky, 1, lb8, ub8, "all" );
      box( (AstFrame *) sky, 1, lb8, ub8, "unit" );
   }
   head( "normbox_sky_swapped" );
   {
      double lb[ 2 ] = { 1.3, -0.3 }, ub[ 2 ] = { M_PI_2, 0.3 };
      box( (AstFrame *) swp, 0, lb, ub, "north" );
      box( (AstFrame *) swp, 0, lb, ub, "none" );
      double lb2[ 2 ] = { -1.5, 5.0 }, ub2[ 2 ] = { -1.2, 5.5 };
      box( (AstFrame *) swp, 0, lb2, ub2, "south" );
   }
   AstCmpFrame *skytime = astCmpFrame( astSkyFrame( " " ), astTimeFrame( "System=MJD" ), " " );
   AstCmpFrame *timesky = astCmpFrame( astTimeFrame( "System=MJD" ), astSkyFrame( " " ), " " );
   head( "normbox_cmpframe_skytime" );
   {
      double lb[ 3 ] = { -0.3, 1.3, 0.0 }, ub[ 3 ] = { 0.3, M_PI_2, 1.0 };
      box( (AstFrame *) skytime, 1, lb, ub, "north" );
      box( (AstFrame *) skytime, 1, lb, ub, "north_loose" );
      box( (AstFrame *) skytime, 1, lb, ub, "all" );
      box( (AstFrame *) skytime, 1, lb, ub, "none" );
   }
   head( "normbox_cmpframe_timesky" );
   {
      double lb2[ 3 ] = { 0.0, -0.3, 1.3 }, ub2[ 3 ] = { 1.0, 0.3, M_PI_2 };
      box( (AstFrame *) timesky, 2, lb2, ub2, "north" );
      box( (AstFrame *) timesky, 2, lb2, ub2, "north_loose" );
      box( (AstFrame *) timesky, 2, lb2, ub2, "all" );
      double lb3[ 3 ] = { 0.0, 5.0, -1.5 }, ub3[ 3 ] = { 1.0, 5.5, -1.2 };
      box( (AstFrame *) timesky, 2, lb3, ub3, "south" );
   }
   head( "normbox_cmpframe_permuted" );
   AstCmpFrame *perm3 = astCmpFrame( astSkyFrame( " " ), astTimeFrame( "System=MJD" ), " " );
   {
      int perm[ 3 ] = { 3, 2, 1 };
      astPermAxes( perm3, perm );
      double lb[ 3 ] = { 0.0, 1.3, -0.3 }, ub[ 3 ] = { 1.0, M_PI_2, 0.3 };
      box( (AstFrame *) perm3, 1, lb, ub, "north_loose" );
      box( (AstFrame *) perm3, 1, lb, ub, "all" );
   }
   head( "normbox_frameset" );
   {
      double lb[ 2 ] = { -0.3, 1.3 }, ub[ 2 ] = { 0.3, M_PI_2 };
      box( (AstFrame *) fs, 1, lb, ub, "north" );
      box( (AstFrame *) fs, 1, lb, ub, "none" );
   }

   /* FrameGrid. */
   head( "grid_frame" );
   {
      double lb[ 2 ] = { 0, 0 }, ub[ 2 ] = { 10, 5 };
      grid( f2, 90, lb, ub );
      double lb2[ 2 ] = { -1, 2 }, ub2[ 2 ] = { 3, -2 };
      grid( f2, 30, lb2, ub2 );
      double lb3[ 2 ] = { 0, 0 }, ub3[ 2 ] = { 1, 0 };
      grid( f2, 30, lb3, ub3 );
      double lb4[ 2 ] = { BAD, 0 }, ub4[ 2 ] = { BAD, 1 };
      grid( f2, 30, lb4, ub4 );
      grid( f2, 0, lb, ub );
   }
   head( "grid_frame_units" );
   {
      AstFrame *fu = astFrame( 2, "Unit(1)=m,Unit(2)=s" );
      double lb[ 2 ] = { 0, 0 }, ub[ 2 ] = { 10, 5 };
      grid( fu, 90, lb, ub );
   }
   head( "grid_frame3_units" );
   {
      AstFrame *f = astFrame( 3, "Unit(3)=Hz" );
      double lb[ 3 ] = { 0, 0, 0 }, ub[ 3 ] = { 1, 2, 4 };
      grid( f, 50, lb, ub );
   }
   head( "grid_frame3" );
   {
      double lb[ 3 ] = { 0, 0, 0 }, ub[ 3 ] = { 1, 2, 4 };
      grid( f3, 50, lb, ub );
   }
   head( "grid_frame1" );
   {
      AstFrame *f = astFrame( 1, " " );
      double lb[ 1 ] = { 0 }, ub[ 1 ] = { 3 };
      grid( f, 7, lb, ub );
      grid( f, 1, lb, ub );
   }
   head( "grid_sky" );
   {
      double lb1[ 2 ] = { -0.3, -0.2 }, ub1[ 2 ] = { 0.3, 0.2 };
      grid( (AstFrame *) sky, 50, lb1, ub1 );
      double lb2[ 2 ] = { 0, 1.3 }, ub2[ 2 ] = { 2 * M_PI, M_PI_2 };
      grid( (AstFrame *) sky, 60, lb2, ub2 );
      double lb3[ 2 ] = { 6.0, 0.3 }, ub3[ 2 ] = { 0.5, -0.1 };
      grid( (AstFrame *) sky, 40, lb3, ub3 );
      double lb5[ 2 ] = { 0.5, -M_PI_2 }, ub5[ 2 ] = { 0.5 + 2 * M_PI, -1.4 };
      grid( (AstFrame *) sky, 30, lb5, ub5 );
      grid( (AstFrame *) sky, 1, lb1, ub1 );
      grid( (AstFrame *) sky, 0, lb1, ub1 );
      double lb6[ 2 ] = { BAD, -0.2 }, ub6[ 2 ] = { 0.3, 0.2 };
      grid( (AstFrame *) sky, 30, lb6, ub6 );
   }
   head( "grid_sky_swapped" );
   {
      double lb[ 2 ] = { -0.2, -0.3 }, ub[ 2 ] = { 0.2, 0.3 };
      grid( (AstFrame *) swp, 50, lb, ub );
   }
   head( "grid_cmpframe_skytime" );
   {
      double lb[ 3 ] = { -0.3, -0.2, 0.0 }, ub[ 3 ] = { 0.3, 0.2, 2.0 };
      grid( (AstFrame *) skytime, 100, lb, ub );
      grid( (AstFrame *) skytime, 0, lb, ub );
   }
   head( "grid_cmpframe_timesky" );
   {
      double lb[ 3 ] = { 0.0, -0.3, -0.2 }, ub[ 3 ] = { 2.0, 0.3, 0.2 };
      grid( (AstFrame *) timesky, 100, lb, ub );
   }
   head( "grid_cmpframe_permuted" );
   {
      double lb[ 3 ] = { 0.0, -0.2, -0.3 }, ub[ 3 ] = { 2.0, 0.2, 0.3 };
      grid( (AstFrame *) perm3, 100, lb, ub );
   }
   head( "grid_frameset" );
   {
      double lb[ 2 ] = { -0.3, -0.2 }, ub[ 2 ] = { 0.3, 0.2 };
      grid( (AstFrame *) fs, 50, lb, ub );
   }

   /* PointSets. Index 12 (empty) is not compared: C compares the values
      astGetPoints allocates for it, which are not initialised. */
   show_pointsets();
   equal( 0, 0 );
   equal( 0, 1 );
   equal( 1, 0 );
   equal( 0, 2 );
   equal( 0, 3 );
   equal( 0, 4 );
   equal( 5, 5 );
   equal( 5, 6 );
   equal( 6, 5 );
   equal( 5, 7 );
   equal( 0, 8 );
   equal( 8, 0 );
   equal( 8, 9 );
   equal( 9, 8 );
   equal( 8, 10 );
   equal( 0, 9 );
   equal( 0, 10 );
   equal( 0, 11 );
   equal( 11, 0 );
   equal( 13, 13 );

   astEnd;
   return 0;
}
