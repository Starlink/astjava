/* region_null_interval_pointlist_probe -- NullRegion, Interval and
 * PointList, by the pinned C library.
 *
 * Constructors: an uncertainty with the wrong number of axes, one of a
 * class that cannot be re-centred, and a negated (unbounded) Box, which
 * astSetUnc negates back; an Interval with no limits at all; a PointList
 * holding a bad point; a PointList of no points.
 *
 * Membership and bounds: each "tran" line gives, for each test point,
 * 1 if astTran<N> leaves it good and 0 if it makes it bad; "bounded" is
 * astGetBounded; "bounds" the lower then upper bound on each axis from
 * astGetRegionBounds; "unc" the class, Negated and bounds of astGetUnc;
 * "points" the number of points then their values from
 * astGetRegionPoints; "values" the values astTran2 gives for each test
 * point, and "pir" the protected astPointInRegion of each, which looks at
 * the first good value. Mapped Regions are made by the protected
 * astMapRegion, so that they keep their FrameSets.
 *
 * Masking: a "mask" line gives the count astMaskD returns, then the array
 * as %.17g values; each array starts as 0, 1, 2 in turn and the value set
 * is 9.
 *
 * Overlap: each "overlap" line gives astOverlap( a, b ), then
 * astOverlap( b, a ). The "split" line gives the number of outputs
 * astMapSplit finds fed by the first input of the PermMap one Interval is
 * mapped through, which interval.c OneToOne requires to be one.
 *
 * A NullRegion mapped by the public astMapRegion, which simplifies it,
 * through a rotation then a PermMap to one axis: its class, axes, base
 * Frame axes and bounds.
 *
 * Attributes: PointList's Closed, which is the opposite of Negated, and
 * its read-only ListSize.
 *
 * Dumps: astShow of each class.
 *
 * Each case starts with a line "== <name>". A "status" line gives the
 * status of a failed call. Values are written with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries, and
 * with pointlist.c recompiled with one change, so that RegPins allocates an
 * int, not a byte, for each element of its mask (line 1998,
 * "*mask = astMalloc( np );" becoming "*mask = astMalloc( np*sizeof(int) );"):
 * without it, astOverlap of a PointList and a Box writes beyond the mask,
 * corrupting the heap. The changed pointlist.o is linked ahead of libast.a:
 *   sed 's/\*mask = astMalloc( np );/\*mask = astMalloc( np*sizeof(int) );/'
 *      <ast>/src/pointlist.c > pointlist.c
 *   cc -c -std=gnu99 -ffp-contract=off -DHAVE_CONFIG_H -DTHREAD_SAFE -DYAML
 *      '-DYAML_BACKEND="libyaml"' -I<build> -I<build>/src -I<ast> -I<ast>/src
 *      -I<ast>/wcslib pointlist.c
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_null_interval_pointlist_probe
 *      region_null_interval_pointlist_probe.c pointlist.o <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./region_null_interval_pointlist_probe >
 * region_null_interval_pointlist_probe.out 2>&1, with trailing white space
 * then removed from every line. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

AstRegion *astMapRegion_( AstRegion *, AstMapping *, AstFrame *, int * );
int astPointInRegion_( AstRegion *, const double *, int * );

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

/* The mapped Region, by the protected astMapRegion. */
static AstRegion *mapped( AstRegion *r, AstMapping *m, AstFrame *f ) {
   AstRegion *result = astMapRegion_( astMakePointer( r ), astMakePointer( m ),
                                      astMakePointer( f ), astGetStatusPtr );
   if( failed() ) return NULL;
   return astMakeId( result );
}

static AstRegion *box( int n, const double *lb, const double *ub, AstRegion *unc ) {
   return (AstRegion *) astBox( astFrame( n, " " ), 1, lb, ub, unc, " " );
}

static AstRegion *box2( double x1, double y1, double x2, double y2 ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = x1;
   lb[ 1 ] = y1;
   ub[ 0 ] = x2;
   ub[ 1 ] = y2;
   return box( 2, lb, ub, NULL );
}

static AstRegion *interval( int n, const double *lb, const double *ub, AstRegion *unc ) {
   return (AstRegion *) astInterval( astFrame( n, " " ), lb, ub, unc, " " );
}

static AstRegion *interval2( double x1, double y1, double x2, double y2 ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = x1;
   lb[ 1 ] = y1;
   ub[ 0 ] = x2;
   ub[ 1 ] = y2;
   return interval( 2, lb, ub, NULL );
}

static AstRegion *interval1( double x1, double x2 ) {
   double lb[ 1 ], ub[ 1 ];
   lb[ 0 ] = x1;
   ub[ 0 ] = x2;
   return interval( 1, lb, ub, NULL );
}

static AstRegion *null2( int negated ) {
   AstRegion *r = (AstRegion *) astNullRegion( astFrame( 2, " " ), NULL, " " );
   if( negated ) astNegate( r );
   return r;
}

/* A PointList of "np" 2-D points given as x, y pairs. */
static AstRegion *pointlist2( int np, const double *xy, AstRegion *unc ) {
   double pts[ 20 ];
   int i;
   for( i = 0; i < np; i++ ) {
      pts[ i ] = xy[ 2*i ];
      pts[ np + i ] = xy[ 2*i + 1 ];
   }
   return (AstRegion *) astPointList( astFrame( 2, " " ), np, 2, np, pts, unc, " " );
}

/* Which of "np" points of "naxes" axes, given point by point, "r" leaves good. */
static void tran( AstRegion *r, int naxes, int np, const double *pts ) {
   double in[ 40 ], out[ 40 ];
   int i, j, good;
   for( i = 0; i < np; i++ ) {
      for( j = 0; j < naxes; j++ ) in[ j*np + i ] = pts[ i*naxes + j ];
   }
   astTranN( r, np, naxes, np, in, 1, naxes, np, out );
   if( failed() ) return;
   printf( "tran" );
   for( i = 0; i < np; i++ ) {
      good = 1;
      for( j = 0; j < naxes; j++ ) {
         if( out[ j*np + i ] == AST__BAD ) good = 0;
      }
      printf( " %d", good );
   }
   printf( "\n" );
}

/* The values astTran2 gives for "np" 2-D points given point by point, and
   whether astPointInRegion finds each inside. */
static void values( AstRegion *r, int np, const double *pts ) {
   double x[ 10 ], y[ 10 ], xo[ 10 ], yo[ 10 ];
   int i;
   for( i = 0; i < np; i++ ) {
      x[ i ] = pts[ 2*i ];
      y[ i ] = pts[ 2*i + 1 ];
   }
   astTran2( r, np, x, y, 1, xo, yo );
   if( failed() ) return;
   printf( "values" );
   for( i = 0; i < np; i++ ) printf( " %.17g %.17g", xo[ i ], yo[ i ] );
   printf( "\npir" );
   for( i = 0; i < np; i++ ) {
      printf( " %d", astPointInRegion_( astMakePointer( r ), pts + 2*i, astGetStatusPtr ) );
   }
   printf( "\n" );
   failed();
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

static void overlap( const char *name, AstRegion *a, AstRegion *b ) {
   int ab, ba;
   title( name );
   ab = astOverlap( a, b );
   if( failed() ) return;
   ba = astOverlap( b, a );
   if( failed() ) return;
   printf( "overlap %d %d\n", ab, ba );
}

/* astMaskD of a fresh copy of "r0", with no Mapping. */
static void maskd( const char *name, AstRegion *r0, AstMapping *map, int inside,
                   int ndim, const int *lbnd, const int *ubnd ) {
   double d[ 100 ];
   int n = 1, i, count;
   AstRegion *r = astCopy( r0 );
   title( name );
   for( i = 0; i < ndim; i++ ) n *= ubnd[ i ] - lbnd[ i ] + 1;
   for( i = 0; i < n; i++ ) d[ i ] = i % 3;
   count = astMaskD( r, map, inside, ndim, lbnd, ubnd, d, 9.0 );
   if( failed() ) return;
   printf( "mask %d", count );
   for( i = 0; i < n; i++ ) printf( " %.17g", d[ i ] );
   printf( "\n" );
}

static void show( const char *name, AstObject *obj ) {
   title( name );
   astShow( obj );
   failed();
}

static void constructors( void ) {
   double lb2[ 2 ] = { 0.0, 0.0 }, ub2[ 2 ] = { 1.0, 1.0 };
   double lb3[ 3 ] = { -0.1, -0.1, -0.1 }, ub3[ 3 ] = { 0.1, 0.1, 0.1 };
   double lbu[ 2 ] = { -0.1, -0.1 }, ubu[ 2 ] = { 0.1, 0.1 };
   double half[ 2 ] = { 0.0, BAD }, nolim[ 2 ] = { BAD, BAD };
   double xy[ 6 ] = { 1.0, 2.0, 3.0, 4.0, 5.0, 6.0 };
   double bad[ 6 ] = { 1.0, 2.0, BAD, 3.0, 5.0, 6.0 };
   double test[ 10 ] = { 0.0, 0.0, 1.0e30, -1.0e30, BAD, 0.0, 0.5, BAD, -5.0, 7.0 };
   double bt[ 12 ] = { 1.0, 2.0, 5.0, 6.0, 1.0, 3.0, BAD, 3.0, 0.0, 0.0, 3.0, 3.0 };
   double pts[ 4 ];
   AstRegion *r, *u;

   title( "null_unc_dims" );
   (void) astNullRegion( astFrame( 2, " " ), box( 3, lb3, ub3, NULL ), " " );
   failed();
   title( "interval_unc_dims" );
   (void) interval( 2, lb2, ub2, box( 3, lb3, ub3, NULL ) );
   failed();
   title( "pointlist_unc_dims" );
   (void) pointlist2( 3, xy, box( 3, lb3, ub3, NULL ) );
   failed();

   title( "null_unc_shape" );
   (void) astNullRegion( astFrame( 2, " " ), interval( 2, half, nolim, NULL ), " " );
   failed();
   title( "interval_unc_shape" );
   (void) interval( 2, lb2, ub2, pointlist2( 3, xy, NULL ) );
   failed();
   title( "pointlist_unc_shape" );
   (void) pointlist2( 3, xy, null2( 0 ) );
   failed();

   title( "null_unc_negated" );
   u = box( 2, lbu, ubu, NULL );
   astNegate( u );
   r = (AstRegion *) astNullRegion( astFrame( 2, " " ), u, " " );
   if( !failed() ) unc( r, 0 );
   title( "interval_unc_negated" );
   r = interval( 2, lb2, ub2, u );
   if( !failed() ) unc( r, 0 );
   title( "pointlist_unc_negated" );
   r = pointlist2( 3, xy, u );
   if( !failed() ) unc( r, 0 );

   title( "interval_unc_bounded_interval" );
   r = box2( 0.0, 0.0, 10.0, 10.0 );
   astSetUnc( r, interval( 2, lbu, ubu, NULL ) );
   if( !failed() ) unc( r, 0 );

   title( "interval_no_limits" );
   r = interval( 2, nolim, nolim, NULL );
   if( !failed() ) {
      tran( r, 2, 5, test );
      bounded( r );
      bounds( r );
      points( r );
   }

   title( "pointlist_bad_point" );
   r = pointlist2( 3, bad, NULL );
   if( !failed() ) {
      tran( r, 2, 6, bt );
      bounded( r );
      bounds( r );
      points( r );
   }

   title( "pointlist_no_points" );
   (void) astPointList( astFrame( 2, " " ), 0, 2, 1, pts, NULL, " " );
   failed();
}

static void nullregions( void ) {
   double test[ 6 ] = { 0.0, 0.0, BAD, 1.0, 1.0e30, 2.0 };
   int g3l[ 2 ] = { 1, 1 }, g3u[ 2 ] = { 3, 3 };
   int inp[ 2 ] = { 1, -1 }, outp[ 1 ] = { 1 };
   double con[ 1 ] = { 0.0 }, rot[ 4 ];
   AstMapping *m;
   AstFrameSet *fs;
   AstRegion *r;

   title( "null_plain" );
   r = null2( 0 );
   tran( r, 2, 3, test );
   bounded( r );
   bounds( r );
   points( r );
   unc( r, 0 );

   title( "null_negated" );
   r = null2( 1 );
   tran( r, 2, 3, test );
   bounded( r );
   bounds( r );

   title( "null_mapped" );
   r = mapped( null2( 1 ), (AstMapping *) astZoomMap( 2, 2.0, " " ), astFrame( 2, " " ) );
   if( r ) {
      tran( r, 2, 3, test );
      bounded( r );
      bounds( r );
   }

   title( "null_mapped_fewer_axes" );
   rot[ 0 ] = cos( 0.3 );
   rot[ 1 ] = -sin( 0.3 );
   rot[ 2 ] = sin( 0.3 );
   rot[ 3 ] = cos( 0.3 );
   m = (AstMapping *) astCmpMap( astMatrixMap( 2, 2, 0, rot, " " ),
                                 astPermMap( 2, inp, 1, outp, con, " " ), 1, " " );
   r = astMapRegion( null2( 0 ), m, astFrame( 1, " " ) );
   if( !failed() ) {
      fs = astGetRegionFrameSet( r );
      printf( "class %s naxes %d base %d\n", astGetC( r, "Class" ), astGetI( r, "Naxes" ),
              astGetI( fs, "Nin" ) );
      bounds( r );
   }

   title( "null_bad_input" );
   values( null2( 0 ), 2, test + 2 );
   values( null2( 1 ), 2, test + 2 );

   title( "null_mesh" );
   astShowMesh( null2( 0 ), 0, NULL );
   failed();

   maskd( "null_inside", null2( 0 ), NULL, 1, 2, g3l, g3u );
   maskd( "null_outside", null2( 0 ), NULL, 0, 2, g3l, g3u );
   maskd( "negated_null_inside", null2( 1 ), NULL, 1, 2, g3l, g3u );
   maskd( "negated_null_outside", null2( 1 ), NULL, 0, 2, g3l, g3u );

   overlap( "null_null", null2( 0 ), null2( 0 ) );
   overlap( "null_negated_null", null2( 0 ), null2( 1 ) );
   overlap( "negated_null_negated_null", null2( 1 ), null2( 1 ) );
   overlap( "negated_null_box", null2( 1 ), box2( 0, 0, 1, 1 ) );
   overlap( "null_interval", null2( 0 ), interval2( 0, BAD, BAD, BAD ) );
   overlap( "negated_null_interval", null2( 1 ), interval2( 0, BAD, BAD, BAD ) );
   r = (AstRegion *) astNullRegion( astFrame( 3, " " ), NULL, " " );
   overlap( "null_other_axes", null2( 0 ), r );

   show( "null_dump", (AstObject *) null2( 0 ) );
   show( "negated_null_dump", (AstObject *) null2( 1 ) );
}

static void intervals( void ) {
   double zy[ 8 ] = { 1.5, 5.0, 1.5, 5.000001, 0.5, 5.0, 1.5, 4.0 };
   double x1[ 7 ] = { 1.0, 2.0, 2.5, 5.0, 8.0, 9.0, BAD };
   double xh[ 10 ] = { 1.0, -1.0e30, 0.5, 0.0, 2.0, 1.0e30, BAD, 1.0, 4.0, BAD };
   int lone[ 1 ] = { 1 }, uten[ 1 ] = { 10 };
   double lb3[ 1 ] = { 3.0 }, ub3[ 1 ] = { BAD };
   double lb7[ 1 ] = { BAD }, ub7[ 1 ] = { 7.0 };
   double pin[ 4 ] = { 1.0, 3.0, 4.0, 7.0 };
   double s, c, rot[ 4 ];
   int inperm[ 2 ] = { 1, 2 }, outperm[ 2 ] = { 1, 1 }, splitin[ 1 ] = { 1 }, splitout[ 2 ];
   AstRegion *r, *a;
   AstMapping *m, *split;

   title( "interval_slice_unc" );
   r = interval2( 1.0, 5.0, 2.0, 5.0 );
   tran( r, 2, 4, zy );
   bounded( r );
   unc( r, 1 );

   title( "interval_slice_unbounded" );
   r = interval2( 1.0, 5.0, BAD, 5.0 );
   tran( r, 2, 4, zy );
   bounded( r );
   bounds( r );
   unc( r, 1 );
   points( r );

   title( "interval_excluded" );
   r = interval1( 8.0, 2.0 );
   tran( r, 1, 7, x1 );
   bounded( r );
   bounds( r );

   title( "interval_excluded_open" );
   astSetI( r, "Closed", 0 );
   tran( r, 1, 7, x1 );

   title( "interval_excluded_negated" );
   astSetI( r, "Closed", 1 );
   astNegate( r );
   tran( r, 1, 7, x1 );
   bounded( r );

   title( "interval_half_negated" );
   r = interval2( 1.0, BAD, BAD, BAD );
   astNegate( r );
   tran( r, 2, 5, xh );
   bounded( r );
   bounds( r );

   title( "interval_negated_bounded" );
   r = interval2( 0.0, 0.0, 1.0, 1.0 );
   astNegate( r );
   bounded( r );
   bounds( r );

   title( "interval_mapped" );
   r = mapped( interval1( 1.0, 3.0 ), (AstMapping *) astZoomMap( 1, 2.0, " " ),
               astFrame( 1, " " ) );
   if( r ) {
      tran( r, 1, 4, pin );
      bounded( r );
      bounds( r );
      points( r );
      unc( r, 1 );
   }

   title( "interval_unbounded_mapped" );
   r = mapped( interval2( 1.0, BAD, BAD, BAD ), (AstMapping *) astZoomMap( 2, 2.0, " " ),
               astFrame( 2, " " ) );
   if( r ) {
      tran( r, 2, 5, xh );
      bounded( r );
      bounds( r );
      unc( r, 1 );
   }

   title( "interval_unbounded_mesh" );
   astShowMesh( interval1( BAD, 1.0 ), 0, NULL );
   failed();

   maskd( "interval_above_inside", interval( 1, lb3, ub3, NULL ), NULL, 1, 1, lone, uten );
   maskd( "interval_above_outside", interval( 1, lb3, ub3, NULL ), NULL, 0, 1, lone, uten );
   maskd( "interval_below_inside", interval( 1, lb7, ub7, NULL ), NULL, 1, 1, lone, uten );
   maskd( "interval_below_outside", interval( 1, lb7, ub7, NULL ), NULL, 0, 1, lone, uten );
   title( "interval_above_bounds" );
   bounds( interval( 1, lb3, ub3, NULL ) );

   overlap( "interval_same_half", interval2( 0, BAD, BAD, BAD ), interval2( 0, BAD, BAD, BAD ) );
   overlap( "interval_nested_half", interval2( 0, BAD, BAD, BAD ), interval2( 1, BAD, BAD, BAD ) );
   overlap( "interval_disjoint_half", interval2( BAD, BAD, 0, BAD ), interval2( 1, BAD, BAD, BAD ) );
   overlap( "interval_crossed_half", interval2( 0, BAD, BAD, BAD ), interval2( BAD, 0, BAD, BAD ) );
   a = interval2( 0, BAD, BAD, BAD );
   astNegate( a );
   overlap( "interval_negated_half", a, interval2( 0, BAD, BAD, BAD ) );
   overlap( "interval_excluded_inside", interval1( 2.0, 1.0 ), interval1( 3.0, 4.0 ) );
   overlap( "interval_excluded_across", interval1( 2.0, 1.0 ), interval1( 1.5, 4.0 ) );
   overlap( "interval_excluded_same", interval1( 2.0, 1.0 ), interval1( 2.0, 1.0 ) );
   overlap( "interval_excluded_negation", interval1( 2.0, 1.0 ), interval1( 1.0, 2.0 ) );
   overlap( "interval_bounded_same", interval2( 0, 0, 1, 1 ), interval2( 0, 0, 1, 1 ) );
   overlap( "interval_bounded_inside", interval2( 0, 0, 1, 1 ), interval2( 0.25, 0.25, 0.5, 0.5 ) );
   overlap( "interval_box_inside", interval2( 0, 0, 1, 1 ), box2( 0.25, 0.25, 0.5, 0.5 ) );
   overlap( "interval_zoomed",
            interval2( 2, BAD, BAD, BAD ),
            mapped( interval2( 1, BAD, BAD, BAD ), (AstMapping *) astZoomMap( 2, 2.0, " " ),
                    astFrame( 2, " " ) ) );
   s = sin( 0.5 );
   c = cos( 0.5 );
   rot[ 0 ] = c;
   rot[ 1 ] = -s;
   rot[ 2 ] = s;
   rot[ 3 ] = c;
   m = (AstMapping *) astMatrixMap( 2, 2, 0, rot, " " );
   overlap( "interval_rotated",
            interval2( 0, BAD, BAD, BAD ),
            mapped( interval2( 0, BAD, BAD, BAD ), m, astFrame( 2, " " ) ) );

   title( "interval_split_two_outputs" );
   m = (AstMapping *) astPermMap( 2, inperm, 2, outperm, NULL, " " );
   astMapSplit( m, 1, splitin, splitout, &split );
   printf( "split %d\n", split ? astGetI( split, "Nout" ) : -1 );
   overlap( "interval_split_two_outputs_overlap", interval2( 0, BAD, BAD, BAD ),
            mapped( interval2( 0, BAD, BAD, BAD ), m, astFrame( 2, " " ) ) );

   show( "interval_dump", (AstObject *) interval2( 1.0, BAD, BAD, 2.0 ) );
}

static void pointlists( void ) {
   double xy[ 4 ] = { 1.0, 2.0, 3.0, 4.0 };
   double near[ 16 ] = { 1.0, 2.0, 1.0000001, 2.0, 1.00001, 2.0, 3.0, 4.0,
                         2.0, 3.0, BAD, 2.0, 1.0, BAD, 3.0, 3.9999999 };
   double halfw[ 10 ] = { 1.4, 2.0, 1.5, 2.0, 1.6, 2.0, 0.5, 2.0, 3.0, 4.5 };
   double dup[ 6 ] = { 2.0, 2.0, 2.0, 2.0, 2.0, BAD };
   double badin[ 6 ] = { BAD, 2.0, 1.0, BAD, 7.0, BAD };
   double zp[ 8 ] = { 2.0, 4.0, 6.0, 8.0, 1.0, 2.0, 3.0, 4.0 };
   double sub[ 2 ] = { 1.0, 2.0 };
   double lbu[ 2 ] = { -0.5, -0.5 }, ubu[ 2 ] = { 0.5, 0.5 };
   int g5l[ 2 ] = { 1, 1 }, g5u[ 2 ] = { 5, 5 };
   AstRegion *r;

   title( "pointlist_default_unc" );
   r = pointlist2( 2, xy, NULL );
   tran( r, 2, 8, near );
   bounded( r );
   bounds( r );
   unc( r, 1 );
   points( r );
   printf( "listsize %d\n", astGetI( r, "ListSize" ) );

   title( "pointlist_bad_input" );
   r = pointlist2( 2, xy, NULL );
   values( r, 3, badin );
   astNegate( r );
   values( r, 3, badin );

   title( "pointlist_explicit_unc" );
   r = pointlist2( 2, xy, box( 2, lbu, ubu, NULL ) );
   tran( r, 2, 5, halfw );
   unc( r, 0 );

   title( "pointlist_negated" );
   astNegate( r );
   tran( r, 2, 5, halfw );
   bounded( r );
   unc( r, 0 );

   title( "pointlist_mapped" );
   r = mapped( pointlist2( 2, xy, NULL ), (AstMapping *) astZoomMap( 2, 2.0, " " ),
               astFrame( 2, " " ) );
   if( r ) {
      tran( r, 2, 4, zp );
      bounds( r );
      points( r );
      unc( r, 1 );
   }

   title( "pointlist_closed" );
   r = pointlist2( 2, xy, NULL );
   astSetI( r, "Closed", 0 );
   printf( "closed %d %d\n", astGetI( r, "Closed" ), astTest( r, "Closed" ) );
   astNegate( r );
   printf( "closed %d %d\n", astGetI( r, "Closed" ), astTest( r, "Closed" ) );
   astClear( r, "Closed" );
   printf( "closed %d %d\n", astGetI( r, "Closed" ), astTest( r, "Closed" ) );

   title( "pointlist_listsize" );
   printf( "listsize %s %d\n", astGetC( r, "ListSize" ), astTest( r, "ListSize" ) );
   failed();
   title( "pointlist_set_listsize" );
   astSet( r, "ListSize=3" );
   failed();
   title( "pointlist_clear_listsize" );
   astClear( r, "ListSize" );
   failed();

   maskd( "pointlist_duplicates_inside", pointlist2( 3, dup, NULL ), NULL, 1, 2, g5l, g5u );
   maskd( "pointlist_duplicates_outside", pointlist2( 3, dup, NULL ), NULL, 0, 2, g5l, g5u );
   r = pointlist2( 2, xy, NULL );
   astNegate( r );
   maskd( "pointlist_negated_inside", r, NULL, 1, 2, g5l, g5u );
   maskd( "pointlist_zoomed_inside", pointlist2( 2, xy, NULL ),
          (AstMapping *) astZoomMap( 2, 0.5, " " ), 1, 2, g5l, g5u );

   overlap( "pointlist_same", pointlist2( 2, xy, NULL ), pointlist2( 2, xy, NULL ) );
   overlap( "pointlist_subset", pointlist2( 2, xy, NULL ), pointlist2( 1, sub, NULL ) );
   overlap( "pointlist_null", pointlist2( 2, xy, NULL ), null2( 0 ) );
   overlap( "pointlist_in_box", pointlist2( 2, xy, NULL ), box2( 0, 0, 5, 5 ) );
   overlap( "pointlist_across_box", pointlist2( 2, xy, NULL ), box2( 0, 0, 2, 3 ) );
   overlap( "pointlist_beside_box", pointlist2( 2, xy, NULL ), box2( 10, 10, 11, 11 ) );

   show( "pointlist_dump", (AstObject *) pointlist2( 2, xy, NULL ) );
}

int main( void ) {
   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;
   constructors();
   nullregions();
   intervals();
   pointlists();
   astEnd;
   return 0;
}
