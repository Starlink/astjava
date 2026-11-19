/* regions_cross_probe -- the checks of Rust's tests/test_regions.rs that
 * testregions.c does not make itself, by the pinned C library.
 *
 * Each case starts with a line "== <name>". Its lines are:
 *
 * - "bounds" with the lower then the upper bound on each axis from
 *   astGetRegionBounds;
 * - "overlap" with astOverlap( a, b ) then astOverlap( b, a );
 * - "tran" with, for each test point, 1 if astTranN leaves its first
 *   coordinate good and 0 if it makes it bad;
 * - "status" with the status of a failed call.
 *
 * Values are written with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o regions_cross_probe
 *      regions_cross_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./regions_cross_probe > regions_cross_probe.out 2>&1, with
 * trailing white space then removed from every line. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

#define PIBY2 1.5707963267948966
#define PI 3.141592653589793

static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

static void title( const char *name ) {
   printf( "== %s\n", name );
}

static AstFrame *sky( const char *system ) {
   char attrs[ 40 ];
   sprintf( attrs, "System=%s", system );
   return (AstFrame *) astSkyFrame( "%s", attrs );
}

static AstFrame *frame( int n ) {
   return astFrame( n, " " );
}

/* A Box given by its bounds (form 1) or its centre and a corner (form 0). */
static AstRegion *box( AstFrame *f, int form, const double *a,
                       const double *b ) {
   return (AstRegion *) astBox( f, form, a, b, NULL, " " );
}

static AstRegion *circle_r( AstFrame *f, const double *c, double r ) {
   return (AstRegion *) astCircle( f, 1, c, &r, NULL, " " );
}

static AstRegion *circle_e( AstFrame *f, const double *c, const double *e ) {
   return (AstRegion *) astCircle( f, 0, c, e, NULL, " " );
}

static AstRegion *interval( AstFrame *f, const double *lb, const double *ub ) {
   return (AstRegion *) astInterval( f, lb, ub, NULL, " " );
}

static AstRegion *interval1( double lo, double hi ) {
   return interval( frame( 1 ), &lo, &hi );
}

static AstRegion *ellipse( AstFrame *f, const double *c, double a, double b,
                           double angle ) {
   double ax[ 2 ];
   ax[ 0 ] = a;
   ax[ 1 ] = b;
   return (AstRegion *) astEllipse( f, 1, c, ax, &angle, NULL, " " );
}

/* A Polygon of "n" vertices given as x, y pairs. */
static AstRegion *polygon( AstFrame *f, int n, const double *pts ) {
   double v[ 20 ];
   int i;
   for( i = 0; i < n; i++ ) {
      v[ i ] = pts[ 2*i ];
      v[ n + i ] = pts[ 2*i + 1 ];
   }
   return (AstRegion *) astPolygon( f, n, n, v, NULL, " " );
}

static void bounds( AstRegion *r ) {
   double lb[ 3 ], ub[ 3 ];
   int i, n;
   n = astGetI( r, "Naxes" );
   astGetRegionBounds( r, lb, ub );
   if( failed() ) return;
   printf( "bounds" );
   for( i = 0; i < n; i++ ) printf( " %.17g", lb[ i ] );
   for( i = 0; i < n; i++ ) printf( " %.17g", ub[ i ] );
   printf( "\n" );
}

static void overlap( AstRegion *a, AstRegion *b ) {
   int ab, ba;
   ab = astOverlap( a, b );
   if( failed() ) return;
   ba = astOverlap( b, a );
   if( failed() ) return;
   printf( "overlap %d %d\n", ab, ba );
}

/* Which of "np" points of "nc" coordinates, given point by point, "r"
   leaves good. */
static void tran( AstRegion *r, int nc, int np, const double *pts ) {
   double in[ 3 ][ 10 ], out[ 3 ][ 10 ];
   int i, j;
   for( i = 0; i < np; i++ ) {
      for( j = 0; j < nc; j++ ) in[ j ][ i ] = pts[ nc*i + j ];
   }
   astTranN( r, np, nc, 10, (const double *) in, 1, nc, 10,
             (double *) out );
   if( failed() ) return;
   printf( "tran" );
   for( i = 0; i < np; i++ ) printf( " %d", out[ 0 ][ i ] != AST__BAD );
   printf( "\n" );
}

int main( void ) {
   AstRegion *a, *b, *c, *r1, *r2, *r3, *cr;
   double p0[ 3 ], p1[ 3 ], p2[ 3 ], q1[ 3 ], q2[ 3 ];

   astBegin;

/* cmpregion_basic_or_negated_and_box and cmpregion_negated_reversal: the
   square [0,1]x[0,1], given by its bounds, ANDed with the negation of the
   OR of the unit Circles about (-1,0) and (1,0), then negated; neither is
   simplified. */
   {
      double pts[ 8 ] = { 0.0, 0.5, 0.2, 1.5, 0.5, 0.5, -0.5, 0.5 };
      p1[ 0 ] = 0.0; p1[ 1 ] = 0.0; p2[ 0 ] = 1.0; p2[ 1 ] = 1.0;
      r1 = box( frame( 2 ), 1, p1, p2 );
      p1[ 0 ] = -1.0; p2[ 0 ] = 0.0; p2[ 1 ] = 0.0;
      r2 = circle_e( frame( 2 ), p1, p2 );
      p1[ 0 ] = 1.0;
      r3 = circle_e( frame( 2 ), p1, p2 );
      cr = (AstRegion *) astCmpRegion( r2, r3, AST__OR, " " );
      astNegate( cr );
      a = (AstRegion *) astCmpRegion( cr, r1, AST__AND, " " );
      title( "square_without_circles" );
      tran( a, 2, 4, pts );
      astNegate( a );
      title( "square_without_circles_negated" );
      tran( a, 2, 4, pts );
   }

/* Overlaps of Intervals, Boxes and Prisms in plain Frames. */
   p1[ 0 ] = 0.0; p1[ 1 ] = 0.0; p2[ 0 ] = 4.0; p2[ 1 ] = 4.0;
   q1[ 0 ] = 2.0; q1[ 1 ] = 2.0; q2[ 0 ] = 6.0; q2[ 1 ] = 6.0;
   title( "interval_partial" );
   overlap( interval( frame( 2 ), p1, p2 ), interval( frame( 2 ), q1, q2 ) );
   title( "box_partial" );
   overlap( box( frame( 2 ), 1, p1, p2 ), box( frame( 2 ), 1, q1, q2 ) );
   q1[ 0 ] = 10.0; q1[ 1 ] = 10.0; q2[ 0 ] = 12.0; q2[ 1 ] = 12.0;
   title( "box_none" );
   overlap( box( frame( 2 ), 1, p1, p2 ), box( frame( 2 ), 1, q1, q2 ) );
   q1[ 0 ] = -1.0; q1[ 1 ] = -1.0; q2[ 0 ] = 5.0; q2[ 1 ] = 5.0;
   title( "box_inside" );
   overlap( box( frame( 2 ), 1, p1, p2 ), box( frame( 2 ), 1, q1, q2 ) );
   p1[ 0 ] = 2.0; p1[ 1 ] = 2.0; p2[ 0 ] = 3.0; p2[ 1 ] = 3.0;
   q1[ 0 ] = 0.0; q1[ 1 ] = 0.0; q2[ 0 ] = 5.0; q2[ 1 ] = 5.0;
   title( "interval_inside" );
   overlap( interval( frame( 2 ), p1, p2 ), interval( frame( 2 ), q1, q2 ) );
   p1[ 0 ] = 0.0; p1[ 1 ] = 0.0; p2[ 0 ] = 1.0; p2[ 1 ] = 1.0;
   q1[ 0 ] = 5.0; q1[ 1 ] = 5.0; q2[ 0 ] = 6.0; q2[ 1 ] = 6.0;
   title( "interval_none" );
   overlap( interval( frame( 2 ), p1, p2 ), interval( frame( 2 ), q1, q2 ) );
   p1[ 0 ] = 1.0; p1[ 1 ] = 2.0; p2[ 0 ] = 5.0; p2[ 1 ] = 8.0;
   title( "interval_same" );
   overlap( interval( frame( 2 ), p1, p2 ), interval( frame( 2 ), p1, p2 ) );
   title( "box_same" );
   overlap( box( frame( 2 ), 1, p1, p2 ), box( frame( 2 ), 1, p1, p2 ) );

   p1[ 0 ] = 0.0; p1[ 1 ] = 0.0; p2[ 0 ] = 1.0; p2[ 1 ] = 1.0;
   a = (AstRegion *) astPrism( box( frame( 2 ), 1, p1, p2 ),
                               interval1( 5.0, 6.0 ), " " );
   title( "prism_same" );
   overlap( a, a );
   a = (AstRegion *) astPrism( box( frame( 2 ), 1, p1, p2 ),
                               interval1( 5.0, 10.0 ), " " );
   b = (AstRegion *) astPrism( box( frame( 2 ), 1, p1, p2 ),
                               interval1( 6.0, 8.0 ), " " );
   title( "prism_second_inside" );
   overlap( a, b );
   b = (AstRegion *) astPrism( box( frame( 2 ), 1, p1, p2 ),
                               interval1( 8.0, 12.0 ), " " );
   title( "prism_partial" );
   overlap( a, b );
   a = (AstRegion *) astPrism( box( frame( 2 ), 1, p1, p2 ),
                               interval1( 5.0, 6.0 ), " " );
   b = (AstRegion *) astPrism( box( frame( 2 ), 1, p1, p2 ),
                               interval1( 7.0, 8.0 ), " " );
   title( "prism_none" );
   overlap( a, b );

/* The AND of the Circle of radius 5 about the origin and the Box
   [-3,3]x[-3,3]: overlap with another such, and the bounds of its Prism
   with [100, 200]. */
   p0[ 0 ] = 0.0; p0[ 1 ] = 0.0;
   p1[ 0 ] = -3.0; p1[ 1 ] = -3.0; p2[ 0 ] = 3.0; p2[ 1 ] = 3.0;
   a = (AstRegion *) astCmpRegion( circle_r( frame( 2 ), p0, 5.0 ),
                                   box( frame( 2 ), 1, p1, p2 ), AST__AND, " " );
   b = (AstRegion *) astCmpRegion( circle_r( frame( 2 ), p0, 5.0 ),
                                   box( frame( 2 ), 1, p1, p2 ), AST__AND, " " );
   title( "circle_and_box_same" );
   overlap( a, b );
   title( "prism_circle_and_box_bounds" );
   bounds( (AstRegion *) astPrism( a, interval1( 100.0, 200.0 ), " " ) );

/* Circles on an ICRS SkyFrame: overlaps and bounds. */
   p0[ 0 ] = 1.0; p0[ 1 ] = 0.5;
   title( "sky_circle_same" );
   overlap( circle_r( sky( "ICRS" ), p0, 0.2 ), circle_r( sky( "ICRS" ), p0, 0.2 ) );
   title( "sky_circle_inside" );
   overlap( circle_r( sky( "ICRS" ), p0, 0.1 ), circle_r( sky( "ICRS" ), p0, 0.5 ) );
   q1[ 0 ] = 1.4; q1[ 1 ] = 0.5;
   title( "sky_circle_partial" );
   overlap( circle_r( sky( "ICRS" ), p0, 0.3 ), circle_r( sky( "ICRS" ), q1, 0.3 ) );
   p1[ 0 ] = 0.0; p1[ 1 ] = 0.0; q1[ 0 ] = PI; q1[ 1 ] = 0.0;
   title( "sky_circle_none" );
   overlap( circle_r( sky( "ICRS" ), p1, 0.1 ), circle_r( sky( "ICRS" ), q1, 0.1 ) );
   p1[ 0 ] = 1.0; p1[ 1 ] = 0.0;
   title( "sky_circle_bounds_equator" );
   bounds( circle_r( sky( "ICRS" ), p1, 0.1 ) );
   p1[ 0 ] = 0.8; p1[ 1 ] = PIBY2;
   title( "sky_circle_bounds_near_pole" );
   bounds( circle_r( sky( "ICRS" ), p1, 0.1 ) );
   {
      double pts[ 6 ] = { 1.0, 0.5, 1.1, 0.5, 1.0, 0.5 };
      p1[ 0 ] = 1.1; p1[ 1 ] = 0.5;
      a = circle_e( sky( "ICRS" ), p0, p1 );
      title( "sky_circle_open_boundary" );
      tran( a, 2, 3, pts );
      astSetI( a, "Closed", 0 );
      tran( a, 2, 3, pts );
   }

/* Boxes on an ICRS SkyFrame, each given by its centre and a corner. */
   p1[ 0 ] = 1.1; p1[ 1 ] = 0.6;
   a = box( sky( "ICRS" ), 0, p0, p1 );
   {
      double pts[ 10 ] = { 1.0, 0.5, 1.1, 0.6, 0.9, 0.4, 1.2, 0.5, 1.0, 0.7 };
      title( "sky_box_membership" );
      tran( a, 2, 5, pts );
   }
   title( "sky_box_bounds" );
   bounds( a );
   title( "sky_box_same" );
   overlap( a, box( sky( "ICRS" ), 0, p0, p1 ) );
   q1[ 0 ] = 1.05; q1[ 1 ] = 0.55; q2[ 0 ] = 1.15; q2[ 1 ] = 0.65;
   title( "sky_box_partial" );
   overlap( a, box( sky( "ICRS" ), 0, q1, q2 ) );
   p1[ 0 ] = 0.5; p1[ 1 ] = 0.3; p2[ 0 ] = 0.6; p2[ 1 ] = 0.4;
   q1[ 0 ] = 3.0; q1[ 1 ] = -0.5; q2[ 0 ] = 3.1; q2[ 1 ] = -0.4;
   title( "sky_box_none" );
   overlap( box( sky( "ICRS" ), 0, p1, p2 ), box( sky( "ICRS" ), 0, q1, q2 ) );

/* Ellipses on an ICRS SkyFrame. */
   {
      double pts[ 10 ] = { 1.0, 0.5, 1.05, 0.5, 1.0, 0.52, 1.15, 0.5, 1.0, 0.6 };
      title( "sky_ellipse_membership" );
      tran( ellipse( sky( "ICRS" ), p0, 0.1, 0.05, PIBY2 ), 2, 5, pts );
   }
   {
      double d = 0.05 / sqrt( 2.0 );
      double pts[ 4 ] = { 1.0, 0.0, 1.0 + d, d };
      p1[ 0 ] = 1.0; p1[ 1 ] = 0.0;
      title( "sky_ellipse_rotated_membership" );
      tran( ellipse( sky( "ICRS" ), p1, 0.1, 0.02, PI/4 ), 2, 2, pts );
   }
   p1[ 0 ] = 0.0; p1[ 1 ] = PIBY2 - 0.2;
   title( "sky_ellipse_pole_bounds" );
   bounds( ellipse( sky( "ICRS" ), p1, 0.5, 0.3, PI ) );

/* Polygons on an ICRS SkyFrame. */
   {
      double v[ 8 ] = { 2*PI - 0.05, 0.1, 0.05, 0.1, 0.05, -0.1, 2*PI - 0.05, -0.1 };
      title( "sky_polygon_ra0_bounds" );
      bounds( polygon( sky( "ICRS" ), 4, v ) );
   }
   {
      double v[ 8 ] = { 1.0, 0.5, 1.0, 0.6, 1.1, 0.6, 1.1, 0.5 };
      double pts[ 8 ] = { 1.05, 0.55, 1.0, 0.5, 2.0, 0.55, 1.05, 0.8 };
      title( "sky_polygon_membership" );
      tran( polygon( sky( "ICRS" ), 4, v ), 2, 4, pts );
   }
   {
      double v[ 6 ] = { 0.5, 0.2, 0.6, 0.4, 0.7, 0.2 };
      double pts[ 4 ] = { 0.6, 0.267, 0.5, 0.39 };
      title( "sky_polygon_triangle_membership" );
      tran( polygon( sky( "ICRS" ), 3, v ), 2, 2, pts );
   }
   {
      double v[ 8 ] = { 1.0, 0.5, 1.1, 0.5, 1.1, 0.6, 1.0, 0.6 };
      title( "sky_polygon_bounds" );
      bounds( polygon( sky( "ICRS" ), 4, v ) );
   }

/* CmpRegions on an ICRS SkyFrame. */
   {
      double pts[ 6 ] = { 1.0, 0.5, 1.14, 0.64, 0.84, 0.5 };
      p1[ 0 ] = 1.15; p1[ 1 ] = 0.65;
      a = (AstRegion *) astCmpRegion( circle_r( sky( "ICRS" ), p0, 0.2 ),
                                      box( sky( "ICRS" ), 0, p0, p1 ), AST__AND, " " );
      title( "sky_circle_and_box_membership" );
      tran( a, 2, 3, pts );
   }
   {
      double pts[ 6 ] = { 1.0, 0.5, 1.5, 0.5, 1.25, 0.5 };
      q1[ 0 ] = 1.5; q1[ 1 ] = 0.5;
      a = (AstRegion *) astCmpRegion( circle_r( sky( "ICRS" ), p0, 0.1 ),
                                      circle_r( sky( "ICRS" ), q1, 0.1 ), AST__OR, " " );
      title( "sky_two_circles_membership" );
      tran( a, 2, 3, pts );
   }

/* Circles made in Galactic coordinates. */
   title( "galactic_circle_same" );
   overlap( circle_r( sky( "GALACTIC" ), p0, 0.01 ), circle_r( sky( "GALACTIC" ), p0, 0.01 ) );
   title( "galactic_circle_inside" );
   overlap( circle_r( sky( "GALACTIC" ), p0, 0.005 ), circle_r( sky( "GALACTIC" ), p0, 0.01 ) );

   astEnd;
   return 0;
}
