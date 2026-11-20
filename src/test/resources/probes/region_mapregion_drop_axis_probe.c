/* region_mapregion_drop_axis_probe -- mapping and masking a 2-D Region
 * through a PermMap to one pixel axis, whose inverse leaves the axis it
 * drops bad, by the pinned C library with region.c MapRegion corrected.
 *
 * The PermMaps are PermMap( 2, {1,0}, 1, {1} ), which keeps the first axis,
 * and PermMap( 2, {0,1}, 1, {2} ), which keeps the second. MapRegion
 * inverts the Mapping, splits off the inputs that give the new axis
 * (astMapSplit), and, since the inverse gives the dropped axis no good
 * value, uses the split Mapping and the Region of the kept axis
 * (astPickAxes). It releases the split Mapping in that branch and again
 * in the cleanup common to both branches.
 *
 * Each case maps a Box or a PointList through one of the PermMaps with the
 * protected astMapRegion into a 1-D Frame and gives the class and bounds
 * of the mapped Region ("mapped <class>", then "bounds" with the lower
 * then the upper bound), then masks it with astMaskI through the same
 * PermMap on pixels 1 to 6, outside and inside: a "mask" line gives the
 * count, then the array, which starts as 0 and is given the value 1.
 *
 * Each case starts with a line "== <name>". A "status" line gives the
 * status of a failed call. Values are written with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries, and
 * with region.c recompiled with one change, so that the cleanup in
 * MapRegion releases the split Mapping only if it has not been released
 * already (at region.c:5719, "tmap = astAnnul( tmap );" becoming
 * "if( tmap ) tmap = astAnnul( tmap );"): without it, the second release
 * of the null pointer fails with AST__OBJIN, so that astMapRegion returns
 * NULL, which astMaskI reports, and astMaskI of a PointList goes on to read
 * the Region it failed to make (pointlist.c Mask reads its points with no
 * check of the status) and crashes. A Circle is not probed: with the
 * change, astPickAxes of a Circle gives a Frame, which MapRegion goes on
 * to use as a Region. The changed region.o is linked ahead of libast.a:
 *   sed -e '5719s/tmap = astAnnul( tmap );/if( tmap ) tmap = astAnnul( tmap );/'
 *      <ast>/src/region.c > region.c
 *   cc -c -std=gnu99 -ffp-contract=off -DHAVE_CONFIG_H -DTHREAD_SAFE -DYAML
 *      '-DYAML_BACKEND="libyaml"' -I<build> -I<build>/src -I<ast> -I<ast>/src
 *      -I<ast>/wcslib -I<libyaml>/include region.c
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_mapregion_drop_axis_probe
 *      region_mapregion_drop_axis_probe.c region.o <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -L<libyaml>/lib
 *      -lyaml -lm
 * and run as ./region_mapregion_drop_axis_probe >
 * region_mapregion_drop_axis_probe.out 2>&1, with trailing white space then
 * removed from every line. */
#include <stdio.h>
#include "ast.h"

AstRegion *astMapRegion_( AstRegion *, AstMapping *, AstFrame *, int * );

static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

/* The class and bounds of "r" mapped by "map" into a 1-D Frame, by the
   protected astMapRegion. */
static void mapped( AstRegion *r, AstMapping *map ) {
   AstFrame *f = astFrame( 1, "Domain=grid" );
   AstRegion *m;
   double lb[ 1 ], ub[ 1 ];
   m = astMapRegion_( astMakePointer( r ), astMakePointer( map ),
                      astMakePointer( f ), astGetStatusPtr );
   if( failed() ) return;
   m = astMakeId( m );
   printf( "mapped %s\n", astGetC( m, "Class" ) );
   astGetRegionBounds( m, lb, ub );
   if( failed() ) return;
   printf( "bounds %.17g %.17g\n", lb[ 0 ], ub[ 0 ] );
}

/* astMaskI of a copy of "r" through "map" on pixels 1 to 6. */
static void mask( AstRegion *r0, AstMapping *map, int inside ) {
   int lbnd[ 1 ] = { 1 }, ubnd[ 1 ] = { 6 };
   int d[ 6 ] = { 0, 0, 0, 0, 0, 0 };
   int i, count;
   AstRegion *r = astCopy( r0 );
   count = astMaskI( r, map, inside, 1, lbnd, ubnd, d, 1 );
   if( failed() ) return;
   printf( "mask %d", count );
   for( i = 0; i < 6; i++ ) printf( " %d", d[ i ] );
   printf( "\n" );
}

static void run( const char *name, AstRegion *r, AstMapping *map ) {
   printf( "== %s\n", name );
   mapped( r, map );
   mask( r, map, 0 );
   mask( r, map, 1 );
}

int main( void ) {
   double c1[ 2 ] = { 1.5, 2.5 }, c2[ 2 ] = { 3.5, 4.5 };
   double pts[ 6 ] = { 2, 4, 3, 3, 1, 5 };
   int keep1_in[ 2 ] = { 1, 0 }, keep1_out[ 1 ] = { 1 };
   int keep2_in[ 2 ] = { 0, 1 }, keep2_out[ 1 ] = { 2 };
   AstRegion *box, *pl;
   AstMapping *keep1, *keep2;

   astBegin;

/* A Box from (1.5,2.5) to (3.5,4.5), and a PointList of the points (2,3),
   (4,1) and (3,5). */
   box = (AstRegion *) astBox( astFrame( 2, " " ), 1, c1, c2, NULL, " " );
   pl = (AstRegion *) astPointList( astFrame( 2, " " ), 3, 2, 3, pts, NULL, " " );
   keep1 = (AstMapping *) astPermMap( 2, keep1_in, 1, keep1_out, NULL, " " );
   keep2 = (AstMapping *) astPermMap( 2, keep2_in, 1, keep2_out, NULL, " " );

   run( "box_keep_first", box, keep1 );
   run( "box_keep_second", box, keep2 );
   run( "pointlist_keep_first", pl, keep1 );
   run( "pointlist_keep_second", pl, keep2 );

   astEnd;
   return 0;
}
