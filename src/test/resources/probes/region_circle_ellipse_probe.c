/* region_circle_ellipse_probe -- Circle and Ellipse, by the pinned C
 * library.
 *
 * Constructors: an unknown form, bad coordinates, an Ellipse in a 3-D
 * Frame, an uncertainty with the wrong number of axes, one of a class that
 * cannot be re-centred, a negated (unbounded) Box, which astSetUnc negates
 * back, and a Circle or Ellipse as the uncertainty, which astSetUnc moves
 * to the Region's first point; a FrameSet and a Region given as the Frame;
 * a negative and a zero radius or axis.
 *
 * Membership and bounds: each "tran" line gives, for each test point, 1 if
 * astTranN leaves it good and 0 if it makes it bad; "bounded" is
 * astGetBounded; "bounds" the lower then upper bound on each axis from
 * astGetRegionBounds; "unc" the class, Negated and bounds of astGetUnc;
 * "points" the number of points then their values from
 * astGetRegionPoints; "domain" the Domain. Mapped Regions are made by the
 * protected astMapRegion, so that they keep their FrameSets.
 *
 * Boundary tests: each "pins" line gives the protected astRegPins of the
 * Region and the points given, then the mask it returns.
 *
 * Best fits: each "fit" case gives the class of the Region the protected
 * astBestCircle or astBestEllipse makes from the boundary mesh (the
 * protected astRegMesh) of a Region, its centre in the current Frame and
 * the uncertainty in the current Frame (the protected astGetUncFrm), or
 * "fit none"; then its points; then "pins" of it and the mesh.
 *
 * Overlap: each "overlap" line gives astOverlap( a, b ), then
 * astOverlap( b, a ).
 *
 * Dumps: astShow; meshes: astShowMesh.
 *
 * Each case starts with a line "== <name>". A "status" line gives the
 * status of a failed call. Values are written with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries, and
 * with ellipse.c recompiled with one change, so that RegCentre moves the
 * three points of the Ellipse (in RegCentre, "rptr[ ip ][ 0 ]" becoming
 * "rptr[ 0 ][ ip ]" and "rptr[ ip ][ 1 ]" becoming "rptr[ 1 ][ ip ]"):
 * without it, moving an Ellipse, as astSetUnc does to an Ellipse given as
 * the uncertainty, reads and writes through the pointer beyond the two
 * axis pointers of its PointSet, and crashes. The changed ellipse.o is
 * linked ahead of libast.a:
 *   sed -e 's/rptr\[ ip \]\[ 0 \]/rptr[ 0 ][ ip ]/'
 *       -e 's/rptr\[ ip \]\[ 1 \]/rptr[ 1 ][ ip ]/'
 *      <ast>/src/ellipse.c > ellipse.c
 *   cc -c -std=gnu99 -ffp-contract=off -DHAVE_CONFIG_H -DTHREAD_SAFE -DYAML
 *      '-DYAML_BACKEND="libyaml"' -I<build> -I<build>/src -I<ast> -I<ast>/src
 *      -I<ast>/wcslib ellipse.c
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_circle_ellipse_probe
 *      region_circle_ellipse_probe.c ellipse.o <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run, from the repository root, as
 * region_circle_ellipse_probe > region_circle_ellipse_probe.out 2>&1, with
 * trailing white space then removed from every line. */
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
AstRegion *astGetUncFrm_( AstRegion *, int, int * );
AstRegion *astBestCircle_( AstPointSet *, double *, AstRegion *, int * );
AstRegion *astBestEllipse_( AstPointSet *, double *, AstRegion *, int * );
AstPointSet *astPointSet_( AstDim, int, const char *, int *, ... );
double **astGetPoints_( AstPointSet *, int * );
AstDim astGetNpoint_( const AstPointSet *, int * );
int astGetNcoord_( const AstPointSet *, int * );
void *astFree_( void *, int * );

#define BAD AST__BAD
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

static AstFrame *frame( int n, const char *domain ) {
   AstFrame *f = astFrame( n, " " );
   if( domain ) astSetC( f, "Domain", domain );
   return f;
}

static AstRegion *circle( AstFrame *f, const double *c, double r, AstRegion *unc ) {
   return (AstRegion *) astCircle( f, 1, c, &r, unc, " " );
}

static AstRegion *circle2( double cx, double cy, double r ) {
   double c[ 2 ];
   c[ 0 ] = cx;
   c[ 1 ] = cy;
   return circle( frame( 2, NULL ), c, r, NULL );
}

static AstRegion *ellipse( AstFrame *f, double cx, double cy, double a, double b,
                           double angle, AstRegion *unc ) {
   double c[ 2 ], ax[ 2 ];
   c[ 0 ] = cx;
   c[ 1 ] = cy;
   ax[ 0 ] = a;
   ax[ 1 ] = b;
   return (AstRegion *) astEllipse( f, 1, c, ax, &angle, unc, " " );
}

static AstRegion *box2( double x1, double y1, double x2, double y2 ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = x1;
   lb[ 1 ] = y1;
   ub[ 0 ] = x2;
   ub[ 1 ] = y2;
   return (AstRegion *) astBox( frame( 2, NULL ), 1, lb, ub, NULL, " " );
}

/* Which of "np" points of "naxes" axes, given point by point, "r" leaves good. */
static void tran( AstRegion *r, int naxes, int np, const double *pts ) {
   double in[ 60 ], out[ 60 ];
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

static void show( const char *name, AstObject *obj ) {
   title( name );
   astShow( obj );
   failed();
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

/* The best fitting Circle (circ) or Ellipse through the mesh of "r", centred
   on its first point, in its current Frame, with its uncertainty there. */
static void fit( const char *name, AstRegion *r, int circ ) {
   AstRegion *rp = astMakePointer( r );
   AstPointSet *mesh = astRegMesh_( rp, astGetStatusPtr );
   AstRegion *u = astGetUncFrm_( rp, AST__CURRENT, astGetStatusPtr );
   AstRegion *result, *id;
   double pts[ 40 ], cen[ 2 ];
   int n;
   title( name );
   astGetRegionPoints( r, 0, 0, &n, NULL );
   astGetRegionPoints( r, n, 2, &n, pts );
   if( failed() ) return;
   cen[ 0 ] = pts[ 0 ];
   cen[ 1 ] = pts[ n ];
   result = circ ? astBestCircle_( mesh, cen, u, astGetStatusPtr )
                 : astBestEllipse_( mesh, cen, u, astGetStatusPtr );
   if( failed() ) return;
   if( !result ) {
      printf( "fit none\n" );
      return;
   }
   id = astMakeId( result );
   printf( "fit %s\n", astGetC( id, "Class" ) );
   points( id );
   pins( id, mesh, NULL );
}

static void circles( void ) {
   double c[ 3 ] = { 1.0, 2.0, 0.0 }, p[ 3 ] = { 4.0, 6.0, 0.0 };
   double cbad[ 2 ] = { BAD, 2.0 }, pbad[ 2 ] = { 4.0, BAD };
   double c3[ 3 ] = { 5.0, 10.0, -3.0 }, c1[ 1 ] = { 10.0 };
   double lb3[ 3 ] = { -0.1, -0.1, -0.1 }, ub3[ 3 ] = { 0.1, 0.1, 0.1 };
   double half[ 2 ] = { 0.0, BAD }, nolim[ 2 ] = { BAD, BAD };
   double zero[ 2 ] = { 0.0, 0.0 };
   double test[ 20 ] = { 1.0, 2.0, 4.0, 2.0, 1.0, 5.0, 3.9, 2.0, 4.1, 2.0,
                         BAD, 2.0, 1.0, BAD, 100.0, 100.0, -1.0, 2.0, 1.0, -1.0 };
   double test1[ 5 ] = { 7.0, 13.0, 6.9, 13.1, 10.0 };
   double test3[ 15 ] = { 5.0, 10.0, -3.0, 7.0, 10.0, -3.0, 6.0, 11.0, -2.0,
                          5.0, 10.0, -5.1, 6.5, 11.5, -1.5 };
   AstFrameSet *fs;
   AstRegion *cf, *u;

   title( "circle_flat" );
   cf = circle( frame( 2, NULL ), c, 3.0, NULL );
   if( !failed() ) {
      tran( cf, 2, 10, test );
      bounded( cf );
      bounds( cf );
      points( cf );
      unc( cf, 1 );
      unc( cf, 0 );
   }
   title( "circle_flat_negated" );
   u = astCopy( cf );
   astNegate( u );
   tran( u, 2, 10, test );
   bounded( u );
   bounds( u );
   title( "circle_flat_open" );
   u = astCopy( cf );
   astSetI( u, "Closed", 0 );
   tran( u, 2, 10, test );
   title( "circle_flat_open_negated" );
   astNegate( u );
   tran( u, 2, 10, test );

   title( "circle_form0" );
   u = (AstRegion *) astCircle( frame( 2, NULL ), 0, c, p, NULL, " " );
   if( !failed() ) {
      points( u );
      bounds( u );
      tran( u, 2, 10, test );
   }

   title( "circle_1d" );
   u = circle( frame( 1, NULL ), c1, 3.0, NULL );
   if( !failed() ) {
      tran( u, 1, 5, test1 );
      bounds( u );
      points( u );
      astShowMesh( u, 0, "mesh" );
      failed();
   }

   title( "circle_3d" );
   u = circle( frame( 3, NULL ), c3, 2.0, NULL );
   if( !failed() ) {
      tran( u, 3, 5, test3 );
      bounds( u );
      points( u );
   }

   title( "circle_frameset" );
   fs = astFrameSet( frame( 2, "X" ), " " );
   astAddFrame( fs, AST__BASE, astZoomMap( 2, 2.0, " " ), frame( 2, "Y" ) );
   u = circle( (AstFrame *) fs, zero, 1.0, NULL );
   if( !failed() ) {
      domain( u );
      points( u );
   }
   title( "circle_frameset_inverted" );
   astInvert( fs );
   u = circle( (AstFrame *) fs, zero, 1.0, NULL );
   if( !failed() ) domain( u );
   title( "circle_region_frame" );
   u = circle( (AstFrame *) u, zero, 2.0, NULL );
   if( !failed() ) {
      domain( u );
      points( u );
   }

   title( "circle_form_2" );
   (void) astCircle( frame( 2, NULL ), 2, c, p, NULL, " " );
   failed();
   title( "circle_bad_centre_form1" );
   (void) circle( frame( 2, NULL ), cbad, 1.0, NULL );
   failed();
   title( "circle_bad_radius" );
   (void) circle( frame( 2, NULL ), c, BAD, NULL );
   failed();
   title( "circle_bad_centre_form0" );
   (void) astCircle( frame( 2, NULL ), 0, cbad, p, NULL, " " );
   failed();
   title( "circle_bad_point" );
   (void) astCircle( frame( 2, NULL ), 0, c, pbad, NULL, " " );
   failed();

   title( "circle_unc_dims" );
   (void) circle( frame( 2, NULL ), c, 1.0,
                  (AstRegion *) astBox( frame( 3, NULL ), 1, lb3, ub3, NULL, " " ) );
   failed();
   title( "circle_unc_shape" );
   (void) circle( frame( 2, NULL ), c, 1.0,
                  (AstRegion *) astInterval( frame( 2, NULL ), half, nolim, NULL, " " ) );
   failed();
   title( "circle_unc_negated" );
   u = box2( -0.1, -0.1, 0.1, 0.2 );
   astNegate( u );
   u = circle( frame( 2, NULL ), c, 1.0, u );
   if( !failed() ) unc( u, 0 );
   title( "circle_unc_circle" );
   u = circle( frame( 2, NULL ), c, 1.0, circle2( -5.0, 7.0, 0.01 ) );
   if( !failed() ) {
      unc( u, 0 );
      bounds( u );
   }
   title( "circle_unc_ellipse" );
   u = circle( frame( 2, NULL ), c, 1.0,
               ellipse( frame( 2, NULL ), -5.0, 7.0, 0.02, 0.01, 0.3, NULL ) );
   if( !failed() ) {
      unc( u, 0 );
      bounds( u );
   }
   title( "box_unc_ellipse" );
   u = box2( 10.0, 10.0, 20.0, 20.0 );
   astSetUnc( u, ellipse( frame( 2, NULL ), 0.0, 0.0, 0.2, 0.1, 0.3, NULL ) );
   if( !failed() ) {
      unc( u, 0 );
      points( astGetUnc( u, 0 ) );
   }

   title( "circle_negative_radius" );
   u = circle( frame( 2, NULL ), c, -2.0, NULL );
   if( !failed() ) {
      points( u );
      bounds( u );
      tran( u, 2, 10, test );
   }
   title( "circle_zero_radius" );
   u = circle( frame( 2, NULL ), c, 0.0, NULL );
   if( !failed() ) {
      points( u );
      bounds( u );
      tran( u, 2, 10, test );
      unc( u, 1 );
   }

   title( "circle_mapped" );
   u = astMakeId( astMapRegion_( astMakePointer( cf ),
                                 astMakePointer( astZoomMap( 2, 2.0, " " ) ),
                                 astMakePointer( frame( 2, "Z" ) ), astGetStatusPtr ) );
   if( !failed() ) {
      points( u );
      bounds( u );
      tran( u, 2, 10, test );
   }

   show( "circle_show", (AstObject *) cf );
   u = circle( frame( 2, NULL ), c, 1.0, box2( -0.1, -0.1, 0.1, 0.2 ) );
   show( "circle_show_unc", (AstObject *) u );
   title( "circle_showmesh" );
   astSetI( cf, "MeshSize", 8 );
   astShowMesh( cf, 0, NULL );
   failed();
}

static void ellipses( void ) {
   double c[ 3 ] = { 0.0, 0.0, 0.0 }, p1[ 2 ] = { 2.0, 0.0 }, p2[ 2 ] = { 1.0, 0.866 };
   double bad[ 2 ] = { BAD, 0.0 }, ax[ 2 ] = { 2.0, 1.0 }, axbad[ 2 ] = { 2.0, BAD };
   double ang = 0.3, angbad = BAD, ax0[ 2 ] = { 0.0, 1.0 };
   double lb3[ 3 ] = { -0.1, -0.1, -0.1 }, ub3[ 3 ] = { 0.1, 0.1, 0.1 };
   double test[ 20 ] = { 1.0, 2.0, 2.9, 2.6, 2.95, 2.6, 1.0, 3.0, 0.7, 2.9,
                         BAD, 2.0, 1.0, BAD, 100.0, 100.0, 1.5, 2.1, -1.0, 2.0 };
   double test0[ 10 ] = { 0.0, 0.0, 2.0, 0.0, 1.9, 0.1, 0.0, 1.2, 0.0, 0.95 };
   double radii[ 2 ] = { 4.0, 3.5 };
   int pick1[ 2 ] = { 1, 3 }, pick2[ 2 ] = { 3, 1 };
   AstCmpFrame *cmp;
   AstRegion *e1, *u;

   title( "ellipse_form1" );
   e1 = ellipse( frame( 2, NULL ), 1.0, 2.0, 2.0, 1.0, 0.3, NULL );
   if( !failed() ) {
      tran( e1, 2, 10, test );
      bounded( e1 );
      bounds( e1 );
      points( e1 );
      unc( e1, 1 );
   }
   title( "ellipse_form1_negated" );
   u = astCopy( e1 );
   astNegate( u );
   tran( u, 2, 10, test );
   bounded( u );
   title( "ellipse_form1_open" );
   u = astCopy( e1 );
   astSetI( u, "Closed", 0 );
   tran( u, 2, 10, test );

   title( "ellipse_form0" );
   u = (AstRegion *) astEllipse( frame( 2, NULL ), 0, c, p1, p2, NULL, " " );
   if( !failed() ) {
      tran( u, 2, 5, test0 );
      bounds( u );
      points( u );
   }

   title( "ellipse_form_2" );
   (void) astEllipse( frame( 2, NULL ), 2, c, p1, p2, NULL, " " );
   failed();
   title( "ellipse_naxes" );
   (void) astEllipse( frame( 3, NULL ), 1, c, ax, &ang, NULL, " " );
   failed();
   title( "ellipse_bad_centre" );
   (void) astEllipse( frame( 2, NULL ), 1, bad, ax, &ang, NULL, " " );
   failed();
   title( "ellipse_bad_axis" );
   (void) astEllipse( frame( 2, NULL ), 1, c, axbad, &ang, NULL, " " );
   failed();
   title( "ellipse_bad_angle" );
   (void) astEllipse( frame( 2, NULL ), 1, c, ax, &angbad, NULL, " " );
   failed();
   title( "ellipse_bad_point1" );
   (void) astEllipse( frame( 2, NULL ), 0, c, bad, p2, NULL, " " );
   failed();
   title( "ellipse_bad_point2" );
   (void) astEllipse( frame( 2, NULL ), 0, c, p1, bad, NULL, " " );
   failed();
   title( "ellipse_zero_axis" );
   u = (AstRegion *) astEllipse( frame( 2, NULL ), 1, c, ax0, &ang, NULL, " " );
   if( !failed() ) {
      points( u );
      tran( u, 2, 5, test0 );
   }
   title( "ellipse_on_its_axis" );
   u = (AstRegion *) astEllipse( frame( 2, NULL ), 0, c, p1, c, NULL, " " );
   if( !failed() ) bounds( u );
   title( "ellipse_negative_axis" );
   u = ellipse( frame( 2, NULL ), 1.0, 2.0, -2.0, 1.0, 0.3, NULL );
   if( !failed() ) {
      points( u );
      tran( u, 2, 10, test );
   }
   title( "ellipse_unc_dims" );
   (void) ellipse( frame( 2, NULL ), 1.0, 2.0, 2.0, 1.0, 0.3,
                   (AstRegion *) astBox( frame( 3, NULL ), 1, lb3, ub3, NULL, " " ) );
   failed();
   title( "ellipse_unc_box" );
   u = ellipse( frame( 2, NULL ), 1.0, 2.0, 2.0, 1.0, 0.3, box2( -0.1, -0.1, 0.1, 0.2 ) );
   if( !failed() ) unc( u, 0 );
   title( "ellipse_unc_ellipse" );
   u = ellipse( frame( 2, NULL ), 1.0, 2.0, 2.0, 1.0, 0.3,
                ellipse( frame( 2, NULL ), 5.0, 5.0, 0.02, 0.01, 0.5, NULL ) );
   if( !failed() ) {
      unc( u, 0 );
      points( astGetUnc( u, 0 ) );
   }

   title( "ellipse_axis_rule_sky_first" );
   cmp = astCmpFrame( astSkyFrame( "System=FK5" ), frame( 1, "T" ), " " );
   u = ellipse( astPickAxes( cmp, 2, pick1, NULL ), 0.0, 0.0, radii[ 0 ], radii[ 1 ],
                PIBY2, NULL );
   if( !failed() ) bounds( u );
   title( "ellipse_axis_rule_sky_second" );
   u = ellipse( astPickAxes( cmp, 2, pick2, NULL ), 0.0, 0.0, radii[ 0 ], radii[ 1 ],
                PIBY2, NULL );
   if( !failed() ) bounds( u );

   title( "ellipse_mapped" );
   u = astMakeId( astMapRegion_( astMakePointer( e1 ),
                                 astMakePointer( astZoomMap( 2, 2.0, " " ) ),
                                 astMakePointer( frame( 2, "Z" ) ), astGetStatusPtr ) );
   if( !failed() ) {
      points( u );
      bounds( u );
   }

   show( "ellipse_show", (AstObject *) e1 );
   title( "ellipse_showmesh" );
   astSetI( e1, "MeshSize", 8 );
   astShowMesh( e1, 0, NULL );
   failed();
}

static void boundaries( void ) {
   double edge[ 2 ] = { 4.0, 2.0 }, centre[ 2 ] = { 1.0, 2.0 }, far[ 2 ] = { 20.0, 2.0 };
   double three[ 6 ] = { 4.0, 2.0, 1.0, 2.0, 1.0, 5.0 };
   double ethree[ 6 ] = { 0.0, 5.0, 0.0, 0.0, 3.0, 0.0 };
   double near[ 4 ] = { 4.0 + 1.0e-7, 2.0, 4.0 + 1.0e-5, 2.0 };
   double cen0[ 2 ] = { 0.0, 0.0 };
   AstRegion *c, *e;

   c = circle2( 1.0, 2.0, 3.0 );
   e = ellipse( frame( 2, NULL ), 0.0, 0.0, 5.0, 3.0, PIBY2, NULL );

   title( "pins_circle" );
   pins( c, pset2( 1, edge ), NULL );
   pins( c, pset2( 1, centre ), NULL );
   pins( c, pset2( 1, far ), NULL );
   pins( c, pset2( 3, three ), NULL );
   pins( c, pset2( 2, near ), NULL );
   pins( c, astRegMesh_( astMakePointer( c ), astGetStatusPtr ), NULL );
   pins( c, pset2( 2, near ), box2( -1.0e-4, -1.0e-4, 1.0e-4, 1.0e-4 ) );

   title( "pins_ellipse" );
   pins( e, pset2( 3, ethree ), NULL );
   pins( e, astRegMesh_( astMakePointer( e ), astGetStatusPtr ), NULL );
   pins( e, pset2( 1, cen0 ), NULL );
}

static void fits( void ) {
   double ina[ 2 ] = { 0.0, 0.0 }, inb[ 2 ] = { 1.0, 1.0 };
   double outa[ 2 ] = { 0.0, 0.0 }, outb[ 2 ] = { 1.0, 1.3 };
   double shear[ 4 ] = { 1.0, 0.3, 0.0, 1.0 };
   AstRegion *c, *e, *cs, *es, *m;

   c = circle2( 1.0, 2.0, 3.0 );
   e = ellipse( frame( 2, NULL ), 1.0, 2.0, 2.0, 1.0, 0.3, NULL );
   fit( "fit_circle_of_circle", c, 1 );
   fit( "fit_circle_of_ellipse", e, 1 );
   fit( "fit_ellipse_of_ellipse", e, 0 );
   fit( "fit_ellipse_of_circle", c, 0 );

   m = astMakeId( astMapRegion_( astMakePointer( c ),
                                 astMakePointer( astWinMap( 2, ina, inb, outa, outb, " " ) ),
                                 astMakePointer( frame( 2, NULL ) ), astGetStatusPtr ) );
   fit( "fit_circle_of_stretched_circle", m, 1 );
   fit( "fit_ellipse_of_stretched_circle", m, 0 );
   m = astMakeId( astMapRegion_( astMakePointer( e ),
                                 astMakePointer( astMatrixMap( 2, 2, 0, shear, " " ) ),
                                 astMakePointer( frame( 2, NULL ) ), astGetStatusPtr ) );
   fit( "fit_ellipse_of_sheared_ellipse", m, 0 );

   cs = circle( (AstFrame *) astSkyFrame( "System=ICRS" ), outb, 0.1, NULL );
   es = ellipse( (AstFrame *) astSkyFrame( "System=ICRS" ), 1.0, 0.8, 0.2, 0.1, 0.3, NULL );
   fit( "fit_circle_of_sky_circle", cs, 1 );
   fit( "fit_ellipse_of_sky_ellipse", es, 0 );
   fit( "fit_circle_of_sky_ellipse", es, 1 );
}

static void overlaps( void ) {
   AstRegion *c = circle2( 0.0, 0.0, 2.0 ), *neg;
   AstRegion *e = ellipse( frame( 2, NULL ), 0.0, 0.0, 2.0, 1.0, 0.3, NULL );
   overlap( "overlap_circle_same", c, circle2( 0.0, 0.0, 2.0 ) );
   overlap( "overlap_circle_inside", c, circle2( 0.5, 0.0, 0.5 ) );
   overlap( "overlap_circle_partial", c, circle2( 3.0, 0.0, 2.0 ) );
   overlap( "overlap_circle_none", c, circle2( 10.0, 0.0, 2.0 ) );
   neg = astCopy( c );
   astNegate( neg );
   overlap( "overlap_circle_negation", c, neg );
   overlap( "overlap_circle_box", c, box2( -1.0, -1.0, 1.0, 1.0 ) );
   overlap( "overlap_circle_ellipse", c, e );
   overlap( "overlap_ellipse_same", e,
            ellipse( frame( 2, NULL ), 0.0, 0.0, 2.0, 1.0, 0.3, NULL ) );
   overlap( "overlap_ellipse_inside", e,
            ellipse( frame( 2, NULL ), 0.1, 0.0, 0.5, 0.2, 1.0, NULL ) );
}

static void nullregions( void ) {
   AstRegion *n = (AstRegion *) astNullRegion( frame( 2, NULL ), NULL, " " );
   AstRegion *m, *u;
   int inperm[ 2 ] = { 1, 2 }, outperm[ 3 ] = { 1, 2, -1 };
   double con[ 1 ] = { 5.0 };

   title( "null_default_unc" );
   unc( n, 1 );
   unc( n, 0 );
   title( "null_sky_default_unc" );
   unc( (AstRegion *) astNullRegion( astSkyFrame( " " ), NULL, " " ), 1 );
   title( "null_mapped_default_unc" );
   m = astMakeId( astMapRegion_( astMakePointer( n ),
                                 astMakePointer( astPermMap( 2, inperm, 3, outperm, con, " " ) ),
                                 astMakePointer( frame( 3, NULL ) ), astGetStatusPtr ) );
   if( failed() ) return;
   u = astGetUnc( m, 1 );
   if( failed() ) return;
   printf( "unc %s %d\n", astGetC( u, "Class" ), astGetI( u, "Naxes" ) );
   u = astMakeId( astGetUncFrm_( astMakePointer( m ), AST__BASE, astGetStatusPtr ) );
   if( failed() ) return;
   printf( "base unc %s %d\n", astGetC( u, "Class" ), astGetI( u, "Naxes" ) );
}

int main( void ) {
   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;
   circles();
   ellipses();
   boundaries();
   fits();
   overlaps();
   nullregions();
   astEnd;
   return 0;
}
