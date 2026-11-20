/* region_polygon_probe -- Polygon, by the pinned C library.
 *
 * Constructors: a Frame of other than two axes, no vertex, bad vertex
 * values, an uncertainty with the wrong number of axes, one of a class
 * that cannot be re-centred, a negated (unbounded) Box, which astSetUnc
 * negates back, and a Circle, which astSetUnc moves to the first vertex
 * as given (before the vertices are put in order); a FrameSet and a
 * Region given as the Frame; one and two vertices, and three in a line.
 *
 * Membership and bounds: each "tran" line gives, for each test point, 1 if
 * astTran2 leaves it good and 0 if it makes it bad; "bounded" is
 * astGetBounded; "negated" is the Negated attribute; "bounds" the lower
 * then upper bound on each axis from astGetRegionBounds; "unc" the class,
 * Negated and bounds of astGetUnc; "points" the number of points then
 * their values from astGetRegionPoints; "domain" the Domain; "mesh" the
 * number of points then their values from astGetRegionMesh (the boundary
 * mesh in the current Frame). Mapped Regions are made by the protected
 * astMapRegion, so that they keep their FrameSets.
 *
 * Boundary tests: each "pins" line gives the protected astRegPins of the
 * Region and the points given, then the mask it returns.
 *
 * Masks: each "mask" line gives the count astMaskD returns and then the
 * array, for the grid of pixel centres 0 to 5 on the first axis and 0 to 4
 * on the second, with no Mapping; the array starts as 0, 1, 2 in turn and
 * the pixels inside are set to 9.
 *
 * Overlap: each "overlap" line gives astOverlap( a, b ), then
 * astOverlap( b, a ).
 *
 * Attributes: "simpvertices" gives astGetI, then astTest, of
 * SimpVertices. Simplification: "class" gives the class of the
 * astSimplify of a Region mapped by the protected astMapRegion.
 *
 * Dumps: astShow.
 *
 * Each case starts with a line "== <name>". A "status" line gives the
 * status of a failed call. Values are written with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_polygon_probe
 *      region_polygon_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run, from the repository root, as
 * region_polygon_probe > region_polygon_probe.out 2>&1, with trailing
 * white space then removed from every line. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

/* Protected functions, which take and give pointers rather than IDs. */
AstRegion *astMapRegion_( AstRegion *, AstMapping *, AstFrame *, int * );
int astRegPins_( AstRegion *, AstPointSet *, AstRegion *, int **, int * );
AstPointSet *astRegMesh_( AstRegion *, int * );
AstPointSet *astPointSet_( AstDim, int, const char *, int *, ... );
double **astGetPoints_( AstPointSet *, int * );
AstDim astGetNpoint_( const AstPointSet *, int * );
void *astFree_( void *, int * );

#define BAD AST__BAD

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

static AstFrame *frame( int n, const char *domain ) {
   AstFrame *f = astFrame( n, " " );
   if( domain ) astSetC( f, "Domain", domain );
   return f;
}

/* A Polygon of "n" vertices given as x, y pairs. */
static AstRegion *poly( AstFrame *f, int n, const double *xy, AstRegion *unc ) {
   double v[ 40 ];
   int i;
   for( i = 0; i < n; i++ ) {
      v[ i ] = xy[ 2*i ];
      v[ n + i ] = xy[ 2*i + 1 ];
   }
   return (AstRegion *) astPolygon( f, n, n, v, unc, " " );
}

static AstRegion *box2( double x1, double y1, double x2, double y2 ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = x1;
   lb[ 1 ] = y1;
   ub[ 0 ] = x2;
   ub[ 1 ] = y2;
   return (AstRegion *) astBox( frame( 2, NULL ), 1, lb, ub, NULL, " " );
}

static AstRegion *circle2( double cx, double cy, double r ) {
   double c[ 2 ];
   c[ 0 ] = cx;
   c[ 1 ] = cy;
   return (AstRegion *) astCircle( frame( 2, NULL ), 1, c, &r, NULL, " " );
}

/* Which of "np" points, given as x, y pairs, "r" leaves good. */
static void tran( AstRegion *r, int np, const double *pts ) {
   double x[ 40 ], y[ 40 ], xo[ 40 ], yo[ 40 ];
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
   double lb[ 3 ], ub[ 3 ];
   int i, n = astGetI( r, "Naxes" );
   astGetRegionBounds( r, lb, ub );
   if( failed() ) return;
   printf( "bounds" );
   for( i = 0; i < n; i++ ) printf( " %.17g", lb[ i ] );
   for( i = 0; i < n; i++ ) printf( " %.17g", ub[ i ] );
   printf( "\n" );
}

static void bounded( AstRegion *r ) {
   int b = astGetI( r, "Bounded" );
   if( failed() ) return;
   printf( "bounded %d\n", b );
}

static void negated( AstRegion *r ) {
   int b = astGetI( r, "Negated" );
   if( failed() ) return;
   printf( "negated %d\n", b );
}

static void unc( AstRegion *r, int def ) {
   AstRegion *u = astGetUnc( r, def );
   if( failed() ) return;
   if( !u ) {
      printf( "unc none\n" );
      return;
   }
   printf( "unc %s %d\n", astGetC( u, "Class" ), astGetI( u, "Negated" ) );
   bounds( u );
}

static void points( AstRegion *r ) {
   double pts[ 40 ];
   int n, i, j, nc = astGetI( r, "Naxes" );
   astGetRegionPoints( r, 0, 0, &n, NULL );
   if( failed() ) return;
   astGetRegionPoints( r, n, nc, &n, pts );
   if( failed() ) return;
   printf( "points %d", n );
   for( j = 0; j < nc; j++ ) {
      for( i = 0; i < n; i++ ) printf( " %.17g", pts[ j*n + i ] );
   }
   printf( "\n" );
}

static void mesh( AstRegion *r ) {
   double *pts;
   int n, i, j;
   astGetRegionMesh( r, 1, 0, 2, &n, NULL );
   if( failed() ) return;
   pts = malloc( sizeof( double )*2*n );
   astGetRegionMesh( r, 1, n, 2, &n, pts );
   if( failed() ) {
      free( pts );
      return;
   }
   printf( "mesh %d", n );
   for( j = 0; j < 2; j++ ) {
      for( i = 0; i < n; i++ ) printf( " %.17g", pts[ j*n + i ] );
   }
   printf( "\n" );
   free( pts );
}

static void domain( AstRegion *r ) {
   const char *d = astGetC( r, "Domain" );
   if( failed() ) return;
   printf( "domain %s\n", d );
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

/* A PointSet (a pointer, not an ID) of "np" 2-D points given as x, y pairs. */
static AstPointSet *pset2( int np, const double *xy ) {
   AstPointSet *ps = astPointSet_( np, 2, " ", astGetStatusPtr );
   double **ptr = astGetPoints_( ps, astGetStatusPtr );
   int i;
   for( i = 0; i < np; i++ ) {
      ptr[ 0 ][ i ] = xy[ 2*i ];
      ptr[ 1 ][ i ] = xy[ 2*i + 1 ];
   }
   return ps;
}

/* astRegPins of "r" and "ps", and its mask. */
static void pins( AstRegion *r, AstPointSet *ps, AstRegion *u ) {
   int *mask = NULL;
   int i, np, result;
   result = astRegPins_( astMakePointer( r ), ps, u ? astMakePointer( u ) : NULL,
                         &mask, astGetStatusPtr );
   if( failed() ) return;
   np = (int) astGetNpoint_( ps, astGetStatusPtr );
   printf( "pins %d", result );
   for( i = 0; i < np; i++ ) printf( " %d", mask[ i ] );
   printf( "\n" );
   mask = astFree_( mask, astGetStatusPtr );
}

/* astMaskD of a copy of "r0" over pixels 0 to 5 by 0 to 4, with no Mapping. */
static void mask( AstRegion *r0, int inside ) {
   double d[ 30 ];
   int lbnd[ 2 ] = { 0, 0 }, ubnd[ 2 ] = { 5, 4 };
   int i, count;
   AstRegion *r = astCopy( r0 );
   for( i = 0; i < 30; i++ ) d[ i ] = i % 3;
   count = astMaskD( r, NULL, inside, 2, lbnd, ubnd, d, 9.0 );
   if( failed() ) return;
   printf( "mask %d", count );
   for( i = 0; i < 30; i++ ) printf( " %.17g", d[ i ] );
   printf( "\n" );
}

static void simpvertices( AstRegion *r ) {
   int v = astGetI( r, "SimpVertices" );
   int t = astTest( r, "SimpVertices" );
   if( failed() ) return;
   printf( "simpvertices %d %d\n", v, t );
}

static void show( const char *name, AstObject *obj ) {
   title( name );
   astShow( obj );
   failed();
}

/* The Region "r" mapped by "map" into a 2-D Frame, unsimplified. */
static AstRegion *mapped( AstRegion *r, AstMapping *map ) {
   return astMakeId( astMapRegion_( astMakePointer( r ), astMakePointer( map ),
                                    astMakePointer( frame( 2, NULL ) ), astGetStatusPtr ) );
}

int main( void ) {
   double tri[ 6 ] = { 0.0, 0.0, 4.0, 0.0, 0.0, 3.0 };
   double tricw[ 6 ] = { 0.0, 0.0, 0.0, 3.0, 4.0, 0.0 };
   double tricw2[ 6 ] = { 4.0, 0.0, 0.0, 0.0, 0.0, 3.0 };
   double tritest[ 20 ] = { 1.0, 0.5, 0.5, 0.5, 5.0, 5.0, -1.0, 0.0, 3.0, 3.0,
                            0.0, 0.0, 4.0, 0.0, 0.0, 3.0, 2.0, 0.0, 0.0, 1.5 };
   double square[ 8 ] = { 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0 };
   double sqtest[ 36 ] = { 0.5, 0.5, 0.1, 0.9, 0.99, 0.01, 1.5, 0.5, -0.1, 0.5,
                           0.5, -0.1, 0.5, 1.1, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0,
                           0.0, 1.0, 0.0, 0.5, 1.0, 0.5, 0.5, 0.0, 0.5, 1.0,
                           2.0, 2.0, BAD, 0.5, 0.5, BAD };
   double ell[ 12 ] = { 0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 1.0, 1.0, 1.0, 2.0, 0.0, 2.0 };
   double elltest[ 14 ] = { 0.5, 0.5, 1.5, 0.5, 0.5, 1.5, 1.5, 1.5, 2.5, 0.5,
                            1.0, 1.5, 1.5, 1.0 };
   double arrow[ 10 ] = { 0.0, 0.0, 4.0, 0.0, 4.0, 3.0, 2.0, 1.0, 0.0, 3.0 };
   double arrowtest[ 14 ] = { 2.0, 0.5, 0.5, 0.5, 2.0, 2.5, 5.0, 0.5, -0.5, 1.0,
                              3.5, 2.5, 2.0, 1.0 };
   double two[ 4 ] = { 0.0, 0.0, 1.0, 0.0 };
   double twotest[ 10 ] = { 0.5, 0.0, 0.5, 0.5, 5.0, 5.0, 0.5, -0.5, 1.0, 0.0 };
   double one[ 2 ] = { 1.0, 2.0 };
   double line3[ 6 ] = { 0.0, 0.0, 1.0, 0.0, 2.0, 0.0 };
   double big[ 8 ] = { 0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0 };
   double badx[ 6 ] = { 0.0, 0.0, BAD, 0.0, 0.0, 3.0 };
   double badxy[ 6 ] = { 0.0, 0.0, 4.0, BAD, BAD, 3.0 };
   double tri3[ 9 ] = { 0.0, 4.0, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0 };
   double lb3[ 3 ] = { -0.1, -0.1, -0.1 }, ub3[ 3 ] = { 0.1, 0.1, 0.1 };
   double zero = 0.0;
   double pinpts[ 16 ] = { 2.0, 0.0, 4.0, 4.0, 2.0, 2.0, 10.0, 10.0,
                           2.0, 1.0e-7, 2.0, 1.0e-5, -1.0e-7, 2.0, 4.0, 4.0 + 1.0e-5 };
   double pinbad[ 6 ] = { BAD, BAD, 2.0, 0.0, BAD, 1.0 };
   double pinunc[ 4 ] = { 2.0, 0.04, 2.0, 0.06 };
   AstFrameSet *fs;
   AstRegion *t, *u, *v, *sq;
   int perm[ 2 ] = { 2, 1 };

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;

/* A triangle, closed, negated and open. */
   title( "tri" );
   t = poly( frame( 2, NULL ), 3, tri, NULL );
   if( !failed() ) {
      tran( t, 10, tritest );
      bounded( t );
      negated( t );
      bounds( t );
      points( t );
      unc( t, 1 );
      unc( t, 0 );
      mesh( t );
   }
   title( "tri_negated" );
   u = astCopy( t );
   astNegate( u );
   tran( u, 10, tritest );
   bounded( u );
   bounds( u );
   title( "tri_open" );
   u = astCopy( t );
   astSetI( u, "Closed", 0 );
   tran( u, 10, tritest );
   title( "tri_open_negated" );
   astNegate( u );
   tran( u, 10, tritest );

/* The triangle with its vertices given clockwise. */
   title( "tri_clockwise" );
   u = poly( frame( 2, NULL ), 3, tricw, NULL );
   if( !failed() ) {
      tran( u, 10, tritest );
      bounded( u );
      negated( u );
      bounds( u );
      points( u );
   }

   title( "square" );
   sq = poly( frame( 2, NULL ), 4, square, NULL );
   if( !failed() ) {
      tran( sq, 18, sqtest );
      bounds( sq );
   }
   title( "square_open" );
   u = astCopy( sq );
   astSetI( u, "Closed", 0 );
   tran( u, 18, sqtest );

   title( "ell" );
   u = poly( frame( 2, NULL ), 6, ell, NULL );
   if( !failed() ) {
      tran( u, 7, elltest );
      bounds( u );
      mesh( u );
   }

   title( "arrow" );
   u = poly( frame( 2, NULL ), 5, arrow, NULL );
   if( !failed() ) {
      tran( u, 7, arrowtest );
      bounds( u );
   }

/* Fewer than three vertices, and three in a line. */
   title( "two" );
   u = poly( frame( 2, NULL ), 2, two, NULL );
   if( !failed() ) {
      bounded( u );
      negated( u );
      points( u );
      tran( u, 5, twotest );
      bounds( u );
      mesh( u );
   }
   title( "one" );
   u = poly( frame( 2, NULL ), 1, one, NULL );
   if( !failed() ) {
      bounded( u );
      negated( u );
      points( u );
      bounds( u );
      mesh( u );
   }
   title( "line3" );
   u = poly( frame( 2, NULL ), 3, line3, NULL );
   if( !failed() ) {
      bounded( u );
      negated( u );
      points( u );
      tran( u, 5, twotest );
      bounds( u );
   }

/* Constructor errors. */
   title( "ctor_naxes3" );
   (void) astPolygon( frame( 3, NULL ), 3, 3, tri3, NULL, " " );
   failed();
   title( "ctor_naxes1" );
   (void) astPolygon( frame( 1, NULL ), 3, 3, tri3, NULL, " " );
   failed();
   title( "ctor_empty" );
   (void) astPolygon( frame( 2, NULL ), 0, 0, tri, NULL, " " );
   failed();
   title( "ctor_bad_x" );
   (void) poly( frame( 2, NULL ), 3, badx, NULL );
   failed();
   title( "ctor_bad_xy" );
   (void) poly( frame( 2, NULL ), 3, badxy, NULL );
   failed();
   title( "ctor_unc_dims" );
   (void) poly( frame( 2, NULL ), 3, tri,
                (AstRegion *) astBox( frame( 3, NULL ), 1, lb3, ub3, NULL, " " ) );
   failed();
   title( "ctor_unc_shape" );
   (void) poly( frame( 2, NULL ), 3, tri, t );
   failed();
   title( "ctor_unc_negated" );
   u = box2( -0.1, -0.1, 0.1, 0.2 );
   astNegate( u );
   u = poly( frame( 2, NULL ), 3, tri, u );
   if( !failed() ) unc( u, 0 );
   title( "ctor_unc_circle" );
   u = poly( frame( 2, NULL ), 3, tri, circle2( -5.0, 7.0, 0.01 ) );
   if( !failed() ) unc( u, 0 );
   title( "ctor_unc_circle_clockwise" );
   u = poly( frame( 2, NULL ), 3, tricw2, circle2( -5.0, 7.0, 0.01 ) );
   if( !failed() ) {
      unc( u, 0 );
      points( u );
   }

/* A FrameSet and a Region given as the Frame. */
   title( "frameset" );
   fs = astFrameSet( frame( 2, "X" ), " " );
   astAddFrame( fs, AST__BASE, astZoomMap( 2, 2.0, " " ), frame( 2, "Y" ) );
   u = poly( (AstFrame *) fs, 3, tri, NULL );
   if( !failed() ) {
      domain( u );
      points( u );
   }
   title( "region_frame" );
   v = poly( (AstFrame *) u, 3, tri, NULL );
   if( !failed() ) {
      domain( v );
      points( v );
   }

/* The boundary test, with no uncertainty for the points and with one. */
   title( "pins" );
   u = poly( frame( 2, NULL ), 4, big, NULL );
   if( !failed() ) {
      pins( u, pset2( 1, pinpts ), NULL );
      pins( u, pset2( 1, pinpts + 2 ), NULL );
      pins( u, pset2( 1, pinpts + 4 ), NULL );
      pins( u, pset2( 1, pinpts + 6 ), NULL );
      pins( u, pset2( 2, pinpts + 2 ), NULL );
      pins( u, pset2( 8, pinpts ), NULL );
      pins( u, pset2( 3, pinbad ), NULL );
      pins( u, pset2( 2, pinunc ), NULL );
      pins( u, pset2( 2, pinunc ), box2( -0.05, -0.05, 0.05, 0.05 ) );
      pins( u, astRegMesh_( astMakePointer( u ), astGetStatusPtr ), NULL );
   }

/* Masks of the triangle and its negation. */
   title( "mask_tri" );
   mask( t, 1 );
   mask( t, 0 );
   title( "mask_tri_negated" );
   u = astCopy( t );
   astNegate( u );
   mask( u, 1 );

/* Overlaps. */
   overlap( "overlap_tri_box_inside", t, box2( 0.5, 0.5, 1.0, 1.0 ) );
   overlap( "overlap_tri_box_partial", t, box2( 1.0, 1.0, 5.0, 5.0 ) );
   overlap( "overlap_tri_box_far", t, box2( 10.0, 10.0, 11.0, 11.0 ) );
   overlap( "overlap_tri_box_around", t, box2( -1.0, -1.0, 5.0, 4.0 ) );
   overlap( "overlap_tri_self", t, poly( frame( 2, NULL ), 3, tri, NULL ) );
   overlap( "overlap_tri_clockwise", t, poly( frame( 2, NULL ), 3, tricw, NULL ) );
   u = astCopy( t );
   astNegate( u );
   overlap( "overlap_tri_negation", t, u );
   overlap( "overlap_tri_circle", t, circle2( 1.0, 0.8, 0.3 ) );
   overlap( "overlap_tri_square", t, sq );

/* SimpVertices. */
   title( "simpvertices" );
   u = astCopy( t );
   simpvertices( u );
   astSetI( u, "SimpVertices", 0 );
   simpvertices( u );
   astClear( u, "SimpVertices" );
   simpvertices( u );
   astSet( u, "SimpVertices=5" );
   simpvertices( u );
   show( "show_tri", (AstObject *) t );
   u = astCopy( t );
   astSetI( u, "SimpVertices", 0 );
   show( "show_tri_sv0", (AstObject *) u );

/* Mapped and simplified. */
   title( "mapped_zoom" );
   u = mapped( t, (AstMapping *) astZoomMap( 2, 2.0, " " ) );
   if( !failed() ) {
      tran( u, 10, tritest );
      bounds( u );
      points( u );
      v = astSimplify( u );
      printf( "class %s\n", astGetC( v, "Class" ) );
      negated( v );
      points( v );
      tran( v, 10, tritest );
   }
   title( "mapped_mirror" );
   u = mapped( t, (AstMapping *) astPermMap( 2, perm, 2, perm, &zero, " " ) );
   if( !failed() ) {
      tran( u, 10, tritest );
      bounds( u );
      v = astSimplify( u );
      printf( "class %s\n", astGetC( v, "Class" ) );
      negated( v );
      bounded( v );
      points( v );
      tran( v, 10, tritest );
   }
   title( "mapped_mirror_negated" );
   v = astCopy( t );
   astNegate( v );
   u = mapped( v, (AstMapping *) astPermMap( 2, perm, 2, perm, &zero, " " ) );
   if( !failed() ) {
      v = astSimplify( u );
      printf( "class %s\n", astGetC( v, "Class" ) );
      negated( v );
      points( v );
      tran( v, 10, tritest );
   }

   astEnd;
   return 0;
}
