/* region_sky_probe -- Boxes and Circles on a SkyFrame that straddle RA 0
 * or hold a celestial pole, by the pinned C library.
 *
 * Each Region is in an ICRS SkyFrame. For each:
 *
 * - "tran" gives, for each test point, 1 if astTran2 leaves it good and 0
 *   if it makes it bad;
 * - "bounds" gives the lower then upper bound on each axis from
 *   astGetRegionBounds;
 * - "mask" gives the count astMaskD returns and then the array, for a grid
 *   of 24 by 12 pixels over the whole sky: a WinMap takes longitude 0 to
 *   2*pi to grid coordinates 0.5 to 24.5 and latitude -pi/2 to pi/2 to 0.5
 *   to 12.5, so that the pixel centres are at longitudes (i - 0.5)*pi/12
 *   and latitudes -pi/2 + (j - 0.5)*pi/12; the array starts as 0, 1, 2 in
 *   turn, the pixels inside are set to 9, and the values are written as
 *   integers.
 *
 * Each "overlap" line gives astOverlap( a, b ), then astOverlap( b, a ).
 *
 * Each case starts with a line "== <name>". A "status" line gives the
 * status of a failed call. Values are written with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_sky_probe
 *      region_sky_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./region_sky_probe > region_sky_probe.out 2>&1, with trailing
 * white space then removed from every line. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

#define PIBY2 1.5707963267948966

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

static AstFrame *sky( void ) {
   return (AstFrame *) astSkyFrame( "System=ICRS" );
}

/* A Box given by its centre and a corner. */
static AstRegion *box( double cx, double cy, double px, double py ) {
   double c[ 2 ], p[ 2 ];
   c[ 0 ] = cx;
   c[ 1 ] = cy;
   p[ 0 ] = px;
   p[ 1 ] = py;
   return (AstRegion *) astBox( sky(), 0, c, p, NULL, " " );
}

/* A Circle given by its centre and radius. */
static AstRegion *circle( double cx, double cy, double r ) {
   double c[ 2 ];
   c[ 0 ] = cx;
   c[ 1 ] = cy;
   return (AstRegion *) astCircle( sky(), 1, c, &r, NULL, " " );
}

/* Which of "np" points, given as longitude, latitude pairs, "r" leaves good. */
static void tran( AstRegion *r, int np, const double *pts ) {
   double x[ 20 ], y[ 20 ], xo[ 20 ], yo[ 20 ];
   int i;
   for( i = 0; i < np; i++ ) {
      x[ i ] = pts[ 2*i ];
      y[ i ] = pts[ 2*i + 1 ];
   }
   astTran2( r, np, x, y, 1, xo, yo );
   if( failed() ) return;
   printf( "tran" );
   for( i = 0; i < np; i++ ) {
      printf( " %d", ( xo[ i ] != AST__BAD && yo[ i ] != AST__BAD ) ? 1 : 0 );
   }
   printf( "\n" );
}

static void bounds( AstRegion *r ) {
   double lb[ 2 ], ub[ 2 ];
   astGetRegionBounds( r, lb, ub );
   if( failed() ) return;
   printf( "bounds %.17g %.17g %.17g %.17g\n", lb[ 0 ], lb[ 1 ], ub[ 0 ], ub[ 1 ] );
}

/* astMaskD of a copy of "r0" over the all-sky grid. */
static void mask( AstRegion *r0, int inside ) {
   double d[ 24*12 ];
   double ina[ 2 ] = { 0.0, -PIBY2 }, inb[ 2 ] = { 2*AST__DPI, PIBY2 };
   double outa[ 2 ] = { 0.5, 0.5 }, outb[ 2 ] = { 24.5, 12.5 };
   int lbnd[ 2 ] = { 1, 1 }, ubnd[ 2 ] = { 24, 12 };
   int i, count;
   AstRegion *r = astCopy( r0 );
   AstMapping *map = (AstMapping *) astWinMap( 2, ina, inb, outa, outb, " " );
   for( i = 0; i < 24*12; i++ ) d[ i ] = i % 3;
   count = astMaskD( r, map, inside, 2, lbnd, ubnd, d, 9.0 );
   if( failed() ) return;
   printf( "mask %d", count );
   for( i = 0; i < 24*12; i++ ) printf( " %d", (int) d[ i ] );
   printf( "\n" );
}

static void overlap( const char *name, AstRegion *a, AstRegion *b ) {
   int ab, ba;
   title( name );
   ab = astOverlap( a, b );
   if( failed() ) return;
   ba = astOverlap( b, a );
   if( failed() ) return;
   printf( "overlap %d %d\n", ab, ba );
}

/* Membership, bounds and the two masks of "r". */
static void region( const char *name, AstRegion *r, int np, const double *pts ) {
   title( name );
   if( failed() ) return;
   tran( r, np, pts );
   bounds( r );
   mask( r, 1 );
   mask( r, 0 );
}

int main( void ) {

/* Test points near RA 0: on either side of it, at the centre's latitude
   and away from it, and far away. */
   double ra0[ 16 ] = { 0.05, 0.3, 6.25, 0.3, 6.1, 0.35, 0.15, 0.2,
                        3.1, 0.3, 0.05, -0.3, 6.25, 0.6, 0.4, 0.3 };

/* Test points near the north pole: the pole itself, points across it at
   several longitudes, and points further south. */
   double north[ 16 ] = { 0.0, PIBY2, 1.0, 1.5, 4.1, 1.5, 2.5, 1.3,
                          5.5, 1.3, 1.0, 1.0, 4.1, 1.2, 1.0, -1.5 };

/* Test points for the Circle whose boundary passes 0.01 radians from the
   north pole: by the pole on either side of it, and further south. */
   double nearpole[ 16 ] = { 0.0, 1.5707963, 3.14159, 1.5707963, 1.0, 1.5, 4.1, 1.5,
                             2.5, 1.3, 5.5, 1.3, 0.2, 1.3, 0.0, 1.2 };

/* Test points near the south pole. */
   double south[ 12 ] = { 0.0, -PIBY2, 2.0, -1.5, 5.1, -1.5, 5.1, -1.2,
                          2.0, -1.2, 2.0, 1.5 };

   AstRegion *box_ra0, *box_pole, *circle_ra0, *circle_pole;
   AstRegion *circle_south, *circle_near_pole, *circle_ra0_2, *box_pole_2;
   AstRegion *neg;

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;

/* A Box centred just west of RA 0, whose corner is east of it. */
   box_ra0 = box( 6.2, 0.3, 0.1, 0.45 );
   region( "box_ra0", box_ra0, 8, ra0 );

/* A Box centred on the north pole, half a width of 3 radians in longitude
   and 0.2 radians in latitude. */
   box_pole = box( 0.0, PIBY2, 3.0, PIBY2 - 0.2 );
   region( "box_pole", box_pole, 8, north );

/* A Circle straddling RA 0. */
   circle_ra0 = circle( 6.2, 0.3, 0.25 );
   region( "circle_ra0", circle_ra0, 8, ra0 );

/* A Circle holding the north pole. */
   circle_pole = circle( 1.0, 1.4, 0.3 );
   region( "circle_pole", circle_pole, 8, north );

/* A Circle holding the south pole. */
   circle_south = circle( 2.0, -1.45, 0.2 );
   region( "circle_south", circle_south, 6, south );

/* A Circle whose boundary passes 0.01 radians from the north pole. (One
   whose boundary passes through the pole puts the first point of its mesh
   at the pole, where the longitude is decided by rounding.) */
   circle_near_pole = circle( 0.0, PIBY2 - 0.3, 0.29 );
   region( "circle_near_pole", circle_near_pole, 8, nearpole );

/* Overlaps across RA 0 and over the pole. */
   circle_ra0_2 = circle( 0.1, 0.35, 0.2 );
   box_pole_2 = box( 2.0, PIBY2, 1.0, PIBY2 - 0.1 );
   overlap( "overlap_box_circle_ra0", box_ra0, circle_ra0 );
   overlap( "overlap_circles_ra0", circle_ra0, circle_ra0_2 );
   overlap( "overlap_circle_far_ra0", circle_ra0, circle( 3.0, 0.3, 0.2 ) );
   overlap( "overlap_circle_inside_ra0", circle_ra0, circle( 6.25, 0.3, 0.05 ) );
   overlap( "overlap_circle_self_ra0", circle_ra0, circle( 6.2, 0.3, 0.25 ) );
   neg = astCopy( circle_ra0 );
   astNegate( neg );
   overlap( "overlap_circle_negation_ra0", circle_ra0, neg );
   overlap( "overlap_box_circle_pole", box_pole, circle_pole );
   overlap( "overlap_boxes_pole", box_pole, box_pole_2 );
   overlap( "overlap_circles_pole", circle_pole, circle_near_pole );
   overlap( "overlap_circle_inside_pole", circle_pole, circle( 3.0, 1.55, 0.01 ) );
   overlap( "overlap_circle_box_pole_inside", circle_pole, box_pole_2 );
   overlap( "overlap_poles", circle_pole, circle_south );

   astEnd;
   return 0;
}
