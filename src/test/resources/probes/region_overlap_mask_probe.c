/* region_overlap_mask_probe -- astOverlap and astMask<X> of Boxes, by the
 * pinned C library, and the negated copy a Region keeps.
 *
 * Overlap: pairs of Boxes beside, inside, across, touching (closed and
 * open), equal, equal to within their uncertainties, negated, in Frames
 * joined by a unit change or a rotation (the protected astMapRegion, so
 * that the rotated Box keeps its FrameSet), and in Frames with no
 * conversion. Each "overlap" line gives astOverlap( a, b ), then
 * astOverlap( b, a ), then whether Negated is set on a and on b after the
 * two calls: region.c OverlapX negates the first Region in place to compare
 * it with the second, and negating it back leaves Negated set.
 *
 * Mask: astMask<X> of a Box, its inside and outside, negated or not, with no
 * Mapping and through a ZoomMap (one taking the Box's bounds in grid
 * coordinates beyond the int range), partly and wholly off the grid, in one
 * and three dimensions, and for each jniast type. Each array starts as 0, 1, 2
 * in turn. A "mask" line gives the count C returns, then the array as %.17g
 * values, then whether Negated is set on the Box afterwards: region.c Mask<X>
 * negates the Region it masks with in place when it masks the inside, and
 * with no Mapping that Region is the one given. Errors: a Mapping with the
 * wrong number of inputs or outputs, a grid with the wrong number of
 * dimensions, and a lower bound above its upper bound.
 *
 * Negation: the negated copy region.c GetNegation keeps, before and after
 * a change of System on a SpecFrame Box, Adaptive and not. A "negation"
 * line gives whether the copy is the one kept before the change, its
 * System and Negated, and whether it contains a test point.
 *
 * Each case starts with a line "== <name>". A "status" line gives the
 * status of a failed call. Values are written with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_overlap_mask_probe
 *      region_overlap_mask_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./region_overlap_mask_probe > region_overlap_mask_probe.out
 * 2>&1, with trailing white space then removed from every line. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

AstRegion *astMapRegion_( AstRegion *, AstMapping *, AstFrame *, int * );
AstRegion *astGetNegation_( AstRegion *, int * );

static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

static AstRegion *box2( AstFrame *f, double x1, double y1, double x2, double y2 ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = x1;
   lb[ 1 ] = y1;
   ub[ 0 ] = x2;
   ub[ 1 ] = y2;
   return (AstRegion *) astBox( f, 1, lb, ub, NULL, " " );
}

static AstRegion *unit2( double x1, double y1, double x2, double y2 ) {
   return box2( astFrame( 2, " " ), x1, y1, x2, y2 );
}

static void overlap( const char *name, AstRegion *a, AstRegion *b ) {
   int ab, ba;
   printf( "== %s\n", name );
   ab = astOverlap( a, b );
   if( failed() ) return;
   ba = astOverlap( b, a );
   if( failed() ) return;
   printf( "overlap %d %d %d %d\n", ab, ba, astTest( a, "Negated" ), astTest( b, "Negated" ) );
}

/* The mapped Region, by the protected astMapRegion. */
static AstRegion *mapped( AstRegion *r, AstMapping *m, AstFrame *f ) {
   AstRegion *result = astMapRegion_( astMakePointer( r ), astMakePointer( m ),
                                      astMakePointer( f ), astGetStatusPtr );
   if( failed() ) return NULL;
   return astMakeId( result );
}

#define NPIX 216

static void prefill( double *d, int n ) {
   int i;
   for( i = 0; i < n; i++ ) d[ i ] = i % 3;
}

static int npixel( int ndim, const int *lbnd, const int *ubnd ) {
   int i, n = 1;
   for( i = 0; i < ndim; i++ ) n *= abs( ubnd[ i ] - lbnd[ i ] ) + 1;
   return n;
}

static void show( int count, const double *d, int n, AstRegion *r ) {
   int i;
   printf( "mask %d", count );
   for( i = 0; i < n; i++ ) printf( " %.17g", d[ i ] );
   printf( " %d\n", astTest( r, "Negated" ) );
}

/* astMaskD of a fresh copy of "r", with val 9. */
static void maskd( const char *name, AstRegion *r0, AstMapping *map, int inside,
                   int ndim, const int *lbnd, const int *ubnd ) {
   double d[ NPIX ];
   int n = npixel( ndim, lbnd, ubnd );
   int count;
   AstRegion *r = astCopy( r0 );
   printf( "== %s\n", name );
   prefill( d, n );
   count = astMaskD( r, map, inside, ndim, lbnd, ubnd, d, 9.0 );
   if( failed() ) return;
   show( count, d, n, r );
}

/* The other jniast types, each through its own astMask<X>. */
#define MASKX(X, T) \
static void mask##X( const char *name, AstRegion *r0, int inside, int ndim, \
                     const int *lbnd, const int *ubnd ) { \
   T t[ NPIX ]; \
   double d[ NPIX ]; \
   int n = npixel( ndim, lbnd, ubnd ); \
   int count, i; \
   AstRegion *r = astCopy( r0 ); \
   printf( "== %s\n", name ); \
   for( i = 0; i < n; i++ ) t[ i ] = (T) ( i % 3 ); \
   count = astMask##X( r, NULL, inside, ndim, lbnd, ubnd, t, (T) 9 ); \
   if( failed() ) return; \
   for( i = 0; i < n; i++ ) d[ i ] = (double) t[ i ]; \
   show( count, d, n, r ); \
}
MASKX(F, float)
MASKX(L, long)
MASKX(I, int)
MASKX(S, short)
MASKX(B, signed char)

static void negation( AstRegion *box, AstRegion *kept ) {
   AstRegion *neg = astMakeId( astGetNegation_( astMakePointer( box ), astGetStatusPtr ) );
   double in[ 1 ] = { 5.0 }, out[ 1 ];
   if( failed() ) return;
   astTran1( neg, 1, in, 1, out );
   if( failed() ) return;
   printf( "negation %d %s %d %d\n", kept != NULL && astSame( neg, kept ),
           astGetC( neg, "System" ), astGetI( neg, "Negated" ), out[ 0 ] != AST__BAD );
}

static void stale( const char *name, int adaptive ) {
   AstFrame *f = (AstFrame *) astSpecFrame( "System=FREQ,Unit=GHz" );
   double lb[ 1 ] = { 4.0 }, ub[ 1 ] = { 6.0 };
   AstRegion *box = (AstRegion *) astBox( f, 1, lb, ub, NULL, " " );
   AstRegion *before;
   printf( "== %s\n", name );
   astSetI( box, "Adaptive", adaptive );
   before = astMakeId( astGetNegation_( astMakePointer( box ), astGetStatusPtr ) );
   if( failed() ) return;
   negation( box, NULL );
   astSetC( box, "System", "WAVE" );
   if( failed() ) return;
   negation( box, before );
}

int main( void ) {
   AstFrame *f;
   AstRegion *a, *b;
   AstMapping *m;
   double rot[ 4 ];
   double lb3[ 3 ] = { 0.0, 0.0, 0.0 }, ub3[ 3 ] = { 1.0, 1.0, 1.0 };
   double lc3[ 3 ] = { 0.5, 0.5, -1.0 }, uc3[ 3 ] = { 2.0, 2.0, 0.5 };
   int g1[ 2 ] = { 1, 1 }, g6[ 2 ] = { 6, 6 }, g2[ 2 ] = { 1, 2 }, g4[ 2 ] = { 4, 4 };
   int lone[ 1 ] = { 1 }, uten[ 1 ] = { 10 };
   int l3[ 3 ] = { 1, 1, 1 }, u3[ 3 ] = { 4, 4, 4 };
   int lbad[ 2 ] = { 4, 1 }, ubad[ 2 ] = { 3, 6 };
   double lb1[ 1 ] = { 3.0 }, ub1[ 1 ] = { 7.0 };

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;

   overlap( "beside", unit2( 0, 0, 1, 1 ), unit2( 2, 0, 3, 1 ) );
   overlap( "inside", unit2( 0, 0, 1, 1 ), unit2( 0.25, 0.25, 0.75, 0.75 ) );
   overlap( "across", unit2( 0, 0, 1, 1 ), unit2( 0.5, 0.5, 1.5, 1.5 ) );
   overlap( "touching_closed", unit2( 0, 0, 1, 1 ), unit2( 1, 0, 2, 1 ) );
   a = unit2( 0, 0, 1, 1 );
   b = unit2( 1, 0, 2, 1 );
   astSetI( a, "Closed", 0 );
   astSetI( b, "Closed", 0 );
   overlap( "touching_open", a, b );
   overlap( "same", unit2( 0, 0, 1, 1 ), unit2( 0, 0, 1, 1 ) );
   overlap( "same_within_uncertainty", unit2( 0, 0, 1, 1 ), unit2( 0, 0, 1.0 + 1.0e-8, 1 ) );
   overlap( "beyond_uncertainty", unit2( 0, 0, 1, 1 ), unit2( 0, 0, 1.0 + 1.0e-4, 1 ) );
   a = unit2( 0, 0, 1, 1 );
   astNegate( a );
   overlap( "negated", a, unit2( 0, 0, 1, 1 ) );
   a = unit2( 0, 0, 1, 1 );
   astNegate( a );
   overlap( "negated_within_uncertainty", a, unit2( 0, 0, 1.0 + 1.0e-8, 1 ) );
   a = unit2( 0, 0, 1, 1 );
   astNegate( a );
   overlap( "negated_around_inside", a, unit2( 0.25, 0.25, 0.75, 0.75 ) );
   a = unit2( 0, 0, 1, 1 );
   astNegate( a );
   overlap( "negated_around_beside", a, unit2( 2, 0, 3, 1 ) );
   a = unit2( 0, 0, 1, 1 );
   astNegate( a );
   overlap( "negated_around_across", a, unit2( 0.5, 0.5, 1.5, 1.5 ) );
   a = unit2( 0, 0, 1, 1 );
   b = unit2( 0.25, 0.25, 0.75, 0.75 );
   astNegate( a );
   astNegate( b );
   overlap( "both_negated", a, b );

   f = astFrame( 2, "Unit(1)=m,Unit(2)=m" );
   astSetActiveUnit( f, 1 );
   a = box2( f, 0, 0, 1000, 1000 );
   f = astFrame( 2, "Unit(1)=km,Unit(2)=km" );
   astSetActiveUnit( f, 1 );
   overlap( "units_inside", a, box2( f, 0.25, 0.25, 0.75, 0.75 ) );
   overlap( "units_same", a, box2( f, 0, 0, 1, 1 ) );

   rot[ 0 ] = cos( 0.5 );
   rot[ 1 ] = -sin( 0.5 );
   rot[ 2 ] = sin( 0.5 );
   rot[ 3 ] = cos( 0.5 );
   m = (AstMapping *) astMatrixMap( 2, 2, 0, rot, " " );
   a = mapped( unit2( -1, -1, 1, 1 ), m, astFrame( 2, " " ) );
   overlap( "rotated_inside", a, unit2( -0.5, -0.5, 0.5, 0.5 ) );
   a = mapped( unit2( -1, -1, 1, 1 ), m, astFrame( 2, " " ) );
   overlap( "rotated_across", a, unit2( 0.9, -0.2, 2, 0.2 ) );
   a = mapped( unit2( -1, -1, 1, 1 ), m, astFrame( 2, " " ) );
   overlap( "rotated_around", a, unit2( -3, -3, 3, 3 ) );
   a = mapped( unit2( -1, -1, 1, 1 ), m, astFrame( 2, " " ) );
   overlap( "rotated_same", a, mapped( unit2( -1, -1, 1, 1 ), m, astFrame( 2, " " ) ) );

   overlap( "three_d_across", (AstRegion *) astBox( astFrame( 3, " " ), 1, lb3, ub3, NULL, " " ),
            (AstRegion *) astBox( astFrame( 3, " " ), 1, lc3, uc3, NULL, " " ) );
   overlap( "domains", box2( astFrame( 2, "Domain=AAA" ), 0, 0, 1, 1 ),
            box2( astFrame( 2, "Domain=BBB" ), 0, 0, 1, 1 ) );

   a = unit2( 2, 2, 4, 4 );
   maskd( "box_inside", a, NULL, 1, 2, g1, g6 );
   maskd( "box_outside", a, NULL, 0, 2, g1, g6 );
   b = astCopy( a );
   astNegate( b );
   maskd( "negated_box_inside", b, NULL, 1, 2, g1, g6 );
   maskd( "negated_box_outside", b, NULL, 0, 2, g1, g6 );
   m = (AstMapping *) astZoomMap( 2, 2.0, " " );
   maskd( "zoomed_inside", unit2( 1, 1, 2, 2 ), m, 1, 2, g1, g6 );
   maskd( "zoomed_outside", unit2( 1, 1, 2, 2 ), m, 0, 2, g1, g6 );
   maskd( "huge_zoom_outside", unit2( -1, -1, 1, 1 ),
          (AstMapping *) astZoomMap( 2, 1.0e12, " " ), 0, 2, g1, g4 );
   maskd( "off_edge_inside", unit2( 4, -3, 9, 2 ), NULL, 1, 2, g1, g6 );
   maskd( "off_edge_outside", unit2( 4, -3, 9, 2 ), NULL, 0, 2, g1, g6 );
   maskd( "off_grid_inside", unit2( 20, 20, 30, 30 ), NULL, 1, 2, g1, g6 );
   maskd( "off_grid_outside", unit2( 20, 20, 30, 30 ), NULL, 0, 2, g1, g6 );
   b = unit2( 20, 20, 30, 30 );
   astNegate( b );
   maskd( "negated_off_grid_inside", b, NULL, 1, 2, g1, g6 );
   maskd( "negated_off_grid_outside", b, NULL, 0, 2, g1, g6 );
   maskd( "grid_inside_box", unit2( -10, -10, 10, 10 ), NULL, 1, 2, g1, g6 );
   maskd( "half_pixel_edges", unit2( 1.5, 2.5, 3.5, 4.5 ), NULL, 1, 2, g1, g6 );
   maskd( "one_d", (AstRegion *) astBox( astFrame( 1, " " ), 1, lb1, ub1, NULL, " " ),
          NULL, 1, 1, lone, uten );
   maskd( "three_d", (AstRegion *) astBox( astFrame( 3, " " ), 1, lb3, ub3, NULL, " " ),
          NULL, 0, 3, l3, u3 );
   maskF( "float_inside", a, 1, 2, g1, g6 );
   maskL( "long_outside", a, 0, 2, g1, g6 );
   maskI( "int_inside", a, 1, 2, g1, g6 );
   maskS( "short_outside", a, 0, 2, g1, g6 );
   maskB( "byte_inside", a, 1, 2, g1, g6 );
   maskd( "map_wrong_inputs", a, (AstMapping *) astUnitMap( 3, " " ), 1, 2, g1, g6 );
   maskd( "map_wrong_outputs", a, (AstMapping *) astPermMap( 2, NULL, 3, NULL, NULL, " " ), 1, 2, g1, g6 );
   maskd( "wrong_ndim", a, NULL, 1, 1, lone, uten );
   maskd( "zero_ndim", (AstRegion *) astBox( astFrame( 1, " " ), 1, lb1, ub1, NULL, " " ),
          NULL, 1, 0, lone, uten );
   maskd( "lower_above_upper", a, NULL, 1, 2, lbad, ubad );
   maskd( "single_row", a, NULL, 1, 2, g2, g2 );

   stale( "negation_adaptive", 1 );
   stale( "negation_not_adaptive", 0 );

   astEnd;
   return 0;
}
