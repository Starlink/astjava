/* region_compound_probe -- CmpRegion and Prism, by the pinned C library.
 *
 * Constructors: CmpRegion operators C rejects, Regions with no Mapping
 * between their Frames (different numbers of axes, different Domains),
 * and a Prism of Regions in a SkyFrame and a SpecFrame.
 *
 * Membership and bounds: each "tran" line gives, for each test point, 1 if
 * astTranN leaves every value good and 0 if not; "values" gives the values
 * astTranN returns; "bounded" is astGetBounded; "negated" the Negated
 * attribute; "naxes" the Naxes attribute; "bounds" the lower then upper
 * bound on each axis from astGetRegionBounds; "unc" the class, Negated and
 * bounds of astGetUnc; "points" the number of points astGetRegionPoints
 * gives; "mesh" the number of points then the values of each axis in turn
 * from astGetRegionMesh (the boundary mesh in the current Frame, or the grid
 * over the inside when called "grid"); "fill" the FillFactor.
 *
 * Overlap: each "overlap" line gives astOverlap( a, b ), then
 * astOverlap( b, a ).
 *
 * Masks: each "mask" line gives the count astMaskD returns, then the array:
 * a 2-D grid of pixel centres 0 to 5 by 0 to 4 with no Mapping, the array
 * starting as 0, 1, 2 in turn, the pixels inside set to 9; "maskcount" the
 * count alone, for the grids the Rust tests use.
 *
 * Decomposition: "maplist" gives the number of Mappings the protected
 * astMapList appends for a parallel, then a series, decomposition, and the
 * class of each of the first; "decompose" gives the series flag astDecompose
 * returns and the classes of the two components; "pick" the class of what
 * astPickAxes returns, and its Naxes; "class" the class of an Object.
 *
 * Boundary tests: "pins" gives the protected astRegPins of a Region and the
 * points given, then the mask it returns.
 *
 * Dumps: astShow; each "reload" case is astShow of the Object written by a
 * Channel and read back.
 *
 * Each case starts with a line "== <name>". A "status" line gives the
 * status of a failed call. Values are written with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries, and
 * with pointlist.c recompiled with one change, so that RegPins allocates an
 * int, not a byte, for each element of its mask (line 1998,
 * "*mask = astMalloc( np );" becoming "*mask = astMalloc( np*sizeof(int) );"):
 * the CmpRegions with a PointList component ask it for the mask of other
 * Regions' meshes, which without the change writes beyond the allocation.
 * The changed pointlist.o is linked ahead of libast.a:
 *   sed 's/\*mask = astMalloc( np );/\*mask = astMalloc( np*sizeof(int) );/'
 *      <ast>/src/pointlist.c > pointlist.c
 *   cc -c -std=gnu99 -ffp-contract=off -DHAVE_CONFIG_H -DTHREAD_SAFE -DYAML
 *      '-DYAML_BACKEND="libyaml"' -I<build> -I<build>/src -I<ast> -I<ast>/src
 *      -I<ast>/wcslib pointlist.c
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_compound_probe
 *      region_compound_probe.c pointlist.o <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./region_compound_probe > region_compound_probe.txt 2>&1; the
 * output, with trailing white space removed from every line, is stored as
 * the "output" string of region_compound_probe.json, since it holds C's item
 * names and messages, which the spelling check rejects. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

/* Protected functions, which take and give pointers rather than IDs. */
AstRegion *astMapRegion_( AstRegion *, AstMapping *, AstFrame *, int * );
int astRegPins_( AstRegion *, AstPointSet *, AstRegion *, int **, int * );
AstPointSet *astPointSet_( AstDim, int, const char *, int *, ... );
double **astGetPoints_( AstPointSet *, int * );
AstDim astGetNpoint_( const AstPointSet *, int * );
void *astFree_( void *, int * );
int astMapList_( AstMapping *, int, int, int *, AstMapping ***, int **, int * );
const char *astGetClass_( const AstObject *, int * );

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

/* A Box of "n" axes given by its centre and a corner. */
static AstRegion *boxc( int n, const double *c, const double *p ) {
   return (AstRegion *) astBox( frame( n, NULL ), 0, c, p, NULL, " " );
}

/* A 2-D Box given by its bounds. */
static AstRegion *box2( double x1, double y1, double x2, double y2 ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = x1;
   lb[ 1 ] = y1;
   ub[ 0 ] = x2;
   ub[ 1 ] = y2;
   return (AstRegion *) astBox( frame( 2, NULL ), 1, lb, ub, NULL, " " );
}

/* A 3-D Box given by its bounds. */
static AstRegion *box3( double x1, double y1, double z1, double x2, double y2,
                        double z2 ) {
   double lb[ 3 ], ub[ 3 ];
   lb[ 0 ] = x1;
   lb[ 1 ] = y1;
   lb[ 2 ] = z1;
   ub[ 0 ] = x2;
   ub[ 1 ] = y2;
   ub[ 2 ] = z2;
   return (AstRegion *) astBox( frame( 3, NULL ), 1, lb, ub, NULL, " " );
}

static AstRegion *circle2( double cx, double cy, double r ) {
   double c[ 2 ];
   c[ 0 ] = cx;
   c[ 1 ] = cy;
   return (AstRegion *) astCircle( frame( 2, NULL ), 1, c, &r, NULL, " " );
}

static AstRegion *interval1( double lo, double hi ) {
   return (AstRegion *) astInterval( frame( 1, NULL ), &lo, &hi, NULL, " " );
}

static AstRegion *negated_copy( AstRegion *r ) {
   AstRegion *n = astCopy( r );
   astNegate( n );
   return n;
}

/* Which of "np" points, given point by point with "nc" values, "r" leaves
   good on every axis. */
static void tran( AstRegion *r, int nc, int np, const double *pts ) {
   double in[ 200 ], out[ 200 ];
   int i, j, good;
   for( i = 0; i < np; i++ ) {
      for( j = 0; j < nc; j++ ) in[ j*np + i ] = pts[ i*nc + j ];
   }
   astTranN( r, np, nc, np, in, 1, nc, np, out );
   if( failed() ) return;
   printf( "tran" );
   for( i = 0; i < np; i++ ) {
      good = 1;
      for( j = 0; j < nc; j++ ) if( out[ j*np + i ] == AST__BAD ) good = 0;
      printf( " %d", good );
   }
   printf( "\n" );
}

/* The values "r" gives "np" points, given point by point with "nc" values. */
static void values( AstRegion *r, int nc, int np, const double *pts, int forward ) {
   double in[ 200 ], out[ 200 ];
   int i, j;
   for( i = 0; i < np; i++ ) {
      for( j = 0; j < nc; j++ ) in[ j*np + i ] = pts[ i*nc + j ];
   }
   astTranN( r, np, nc, np, in, forward, nc, np, out );
   if( failed() ) return;
   printf( "values" );
   for( i = 0; i < np; i++ ) {
      for( j = 0; j < nc; j++ ) printf( " %.17g", out[ j*np + i ] );
   }
   printf( "\n" );
}

static void bounds( AstRegion *r ) {
   double lb[ 6 ], ub[ 6 ];
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

static void naxes( AstObject *r ) {
   int n = astGetI( r, "Naxes" );
   if( failed() ) return;
   printf( "naxes %d\n", n );
}

static void fill( AstRegion *r ) {
   double f = astGetD( r, "FillFactor" );
   if( failed() ) return;
   printf( "fill %.17g\n", f );
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
   int n;
   astGetRegionPoints( r, 0, 0, &n, NULL );
   if( failed() ) return;
   printf( "points %d\n", n );
}

/* The boundary mesh ("surface" non-zero) or the grid inside the Region. */
static void mesh( AstRegion *r, int surface ) {
   double *pts;
   int n, i, j, nc = astGetI( r, "Naxes" );
   astGetRegionMesh( r, surface, 0, nc, &n, NULL );
   if( failed() ) return;
   pts = malloc( sizeof( double )*nc*n );
   astGetRegionMesh( r, surface, n, nc, &n, pts );
   if( failed() ) {
      free( pts );
      return;
   }
   printf( "%s %d", surface ? "mesh" : "grid", n );
   for( j = 0; j < nc; j++ ) {
      for( i = 0; i < n; i++ ) printf( " %.17g", pts[ j*n + i ] );
   }
   printf( "\n" );
   free( pts );
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

/* astMaskD of "r" with a Mapping over the grid "lbnd" to "ubnd". */
static void maskcount( AstRegion *r, AstMapping *map, int inside, int ndim,
                       const int *lbnd, const int *ubnd, double val ) {
   double *d;
   int i, n = 1, count;
   for( i = 0; i < ndim; i++ ) n *= ubnd[ i ] - lbnd[ i ] + 1;
   d = calloc( n, sizeof( double ) );
   count = astMaskD( r, map, inside, ndim, lbnd, ubnd, d, val );
   free( d );
   if( failed() ) return;
   printf( "maskcount %d\n", count );
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

static void maplist( AstMapping *m ) {
   AstMapping **list = NULL;
   int *inv = NULL;
   int n = 0, ns = 0, i;
   AstMapping **slist = NULL;
   int *sinv = NULL;
   astMapList_( astMakePointer( m ), 0, 0, &n, &list, &inv, astGetStatusPtr );
   astMapList_( astMakePointer( m ), 1, 0, &ns, &slist, &sinv, astGetStatusPtr );
   if( failed() ) return;
   printf( "maplist %d %d", n, ns );
   for( i = 0; i < n; i++ ) {
      printf( " %s", astGetClass_( (AstObject *) list[ i ], astGetStatusPtr ) );
   }
   printf( "\n" );
}

static void decompose( AstMapping *m ) {
   AstMapping *m1 = NULL, *m2 = NULL;
   int series = -1, inv1, inv2;
   astDecompose( m, &m1, &m2, &series, &inv1, &inv2 );
   if( failed() ) return;
   printf( "decompose %d %s %s\n", series, astGetC( m1, "Class" ),
           m2 ? astGetC( m2, "Class" ) : "none" );
}

static void pick( AstFrame *f, int n, const int *axes ) {
   AstFrame *p = astPickAxes( f, n, axes, NULL );
   if( failed() ) return;
   printf( "pick %s %d\n", astGetC( p, "Class" ), astGetI( p, "Naxes" ) );
   if( astIsARegion( p ) ) bounds( (AstRegion *) p );
}

static void klass( AstObject *o ) {
   printf( "class %s\n", astGetC( o, "Class" ) );
}

static void show( const char *name, AstObject *obj ) {
   title( name );
   astShow( obj );
   failed();
}

/* The text a Channel writes, and the position from which it is read back. */
static char dumptext[ 200000 ];
static size_t dumppos;

static void sink( const char *line ) {
   strcat( dumptext, line );
   strcat( dumptext, "\n" );
}

static const char *source( void ) {
   static char line[ 1000 ];
   size_t n;
   if( !dumptext[ dumppos ] ) return NULL;
   n = strcspn( dumptext + dumppos, "\n" );
   memcpy( line, dumptext + dumppos, n );
   line[ n ] = 0;
   dumppos += n + 1;
   return line;
}

/* astShow of "obj" written by a Channel and read back. */
static void reload( const char *name, AstObject *obj ) {
   AstChannel *ch;
   AstObject *back;
   title( name );
   dumptext[ 0 ] = 0;
   dumppos = 0;
   ch = astChannel( NULL, sink, " " );
   astWrite( ch, obj );
   ch = astAnnul( ch );
   ch = astChannel( source, NULL, " " );
   back = astRead( ch );
   ch = astAnnul( ch );
   if( failed() ) return;
   astShow( back );
   failed();
}

/* The Region "r" mapped by "map" into "frm", unsimplified. */
static AstRegion *mapped( AstRegion *r, AstMapping *map, AstFrame *frm ) {
   return astMakeId( astMapRegion_( astMakePointer( r ), astMakePointer( map ),
                                    astMakePointer( frm ), astGetStatusPtr ) );
}

static AstRegion *cmp( AstRegion *a, AstRegion *b, int oper ) {
   return (AstRegion *) astCmpRegion( a, b, oper, " " );
}

static AstRegion *prism( AstRegion *a, AstRegion *b ) {
   return (AstRegion *) astPrism( a, b, " " );
}

/* The Prism of the Rust tests: the Circle of radius 5 at the origin and
   the Interval 2 to 8. */
static AstRegion *cylinder( void ) {
   return prism( circle2( 0.0, 0.0, 5.0 ), interval1( 2.0, 8.0 ) );
}

int main( void ) {
   AstRegion *a, *b, *c, *r, *u;
   AstPointSet *ps;
   double zero2[ 2 ] = { 0.0, 0.0 }, three2[ 2 ] = { 3.0, 3.0 };
   double two2[ 2 ] = { 2.0, 2.0 };
   double c3[ 3 ] = { 0.0, 0.0, 0.0 }, r1 = 1.0;

   astWatch( NULL );
   setvbuf( stdout, NULL, _IONBF, 0 );

/* Constructors. */
   title( "cmp_oper_0" );
   r = cmp( circle2( 0.0, 0.0, 5.0 ), box2( -3.0, -3.0, 3.0, 3.0 ), 0 );
   failed();
   title( "cmp_oper_4" );
   r = cmp( circle2( 0.0, 0.0, 5.0 ), box2( -3.0, -3.0, 3.0, 3.0 ), 4 );
   failed();
   title( "cmp_naxes" );
   r = cmp( circle2( 0.0, 0.0, 1.0 ),
            (AstRegion *) astCircle( frame( 3, NULL ), 1, c3, &r1, NULL, " " ),
            AST__AND );
   failed();
   title( "cmp_domain" );
   a = (AstRegion *) astBox( frame( 2, "AAA" ), 1, zero2, two2, NULL, " " );
   b = (AstRegion *) astBox( frame( 2, "BBB" ), 1, zero2, two2, NULL, " " );
   r = cmp( a, b, AST__AND );
   failed();
   title( "cmp_xor_naxes" );
   r = cmp( a, a, 3 );
   if( !failed() ) naxes( (AstObject *) r );
   title( "prism_sky_spec" );
   {
      double cen[ 2 ] = { 1.0, 0.5 }, rad = 0.1, lo = 5.0e14, hi = 6.0e14;
      AstRegion *sc = (AstRegion *) astCircle( astSkyFrame( " " ), 1, cen, &rad, NULL, " " );
      AstRegion *si = (AstRegion *) astInterval( astSpecFrame( "System=FREQ,Unit=Hz" ), &lo, &hi, NULL, " " );
      r = prism( sc, si );
      if( !failed() ) {
         naxes( (AstObject *) r );
         printf( "domain %s\n", astGetC( r, "Domain" ) );
         bounds( r );
      }
   }

/* CmpRegion membership, bounds and meshes (the Rust tests). */
   {
      double pts[ 14 ] = { 0.0, 0.0, 2.0, 2.0, 4.0, 0.0, -4.0, 0.0, 6.0, 0.0,
                           BAD, 0.0, 0.0, BAD };
      title( "and_cb" );
      r = cmp( circle2( 0.0, 0.0, 5.0 ), boxc( 2, zero2, three2 ), AST__AND );
      tran( r, 2, 7, pts );
      bounded( r );
      bounds( r );
      points( r );
      unc( r, 1 );
      mesh( r, 1 );
      fill( r );
      title( "and_cb_negated" );
      astNegate( r );
      tran( r, 2, 7, pts );
      bounded( r );
      bounds( r );
      negated( r );
   }
   {
      double pts[ 12 ] = { -5.0, 0.0, -4.0, 0.0, 5.0, 0.0, 6.0, 0.0, 0.0, 0.0,
                           0.0, 10.0 };
      title( "or_cc" );
      r = cmp( circle2( -5.0, 0.0, 2.0 ), circle2( 5.0, 0.0, 2.0 ), AST__OR );
      tran( r, 2, 6, pts );
      bounded( r );
      bounds( r );
      mesh( r, 1 );
   }
   {
      double pts[ 4 ] = { 0.0, 0.0, 10.0, 10.0 };
      title( "or_nested_circles_negated" );
      r = cmp( circle2( 0.0, 0.0, 3.0 ), circle2( 0.0, 0.0, 1.0 ), AST__OR );
      tran( r, 2, 2, pts );
      astNegate( r );
      tran( r, 2, 2, pts );
      bounded( r );
   }
   {
      double pts[ 6 ] = { 1.0, 1.0, 2.5, 0.0, 5.0, 5.0 };
      double pts2[ 4 ] = { 10.0, 10.0, 0.0, 0.0 };
      title( "and_transform" );
      r = cmp( circle2( 0.0, 0.0, 3.0 ), boxc( 2, zero2, two2 ), AST__AND );
      values( r, 2, 3, pts, 1 );
      values( r, 2, 2, pts2, 1 );
      values( r, 2, 2, pts2, 0 );
   }
   {
      double pts[ 8 ] = { 1.0, 1.0, 2.5, 0.0, 10.0, 10.0, 20.0, 20.0 };
      title( "nested" );
      a = cmp( circle2( 0.0, 0.0, 3.0 ), boxc( 2, zero2, two2 ), AST__AND );
      r = cmp( a, circle2( 10.0, 10.0, 1.0 ), AST__OR );
      tran( r, 2, 4, pts );
      bounded( r );
      bounds( r );
   }
   {
      double pts[ 4 ] = { 0.0, 0.0, 2.0, 0.0 };
      title( "and_concentric" );
      r = cmp( circle2( 0.0, 0.0, 3.0 ), circle2( 0.0, 0.0, 1.0 ), AST__AND );
      tran( r, 2, 2, pts );
   }
   title( "bounded_and_negated_circle" );
   r = cmp( circle2( 0.0, 0.0, 3.0 ), negated_copy( circle2( 10.0, 10.0, 1.0 ) ),
            AST__AND );
   bounded( r );
   bounds( r );
   title( "bounded_null" );
   a = (AstRegion *) astNullRegion( frame( 2, NULL ), NULL, " " );
   bounded( a );
   astNegate( a );
   bounded( a );
   title( "bounded_and_two_negated" );
   r = cmp( negated_copy( circle2( 0.0, 0.0, 1.0 ) ),
            negated_copy( circle2( 10.0, 10.0, 1.0 ) ), AST__AND );
   bounded( r );
   bounds( r );
   title( "bounded_or_negated" );
   r = cmp( circle2( 0.0, 0.0, 3.0 ), negated_copy( circle2( 10.0, 10.0, 1.0 ) ),
            AST__OR );
   bounded( r );
   bounds( r );
   title( "bounded_or_two" );
   r = cmp( circle2( 0.0, 0.0, 3.0 ), circle2( 1.0, 1.0, 2.0 ), AST__OR );
   bounded( r );
   bounds( r );
   title( "bounds_or_apart" );
   r = cmp( circle2( -5.0, 0.0, 2.0 ), circle2( 5.0, 0.0, 2.0 ), AST__OR );
   bounds( r );
   title( "and_overlapping_circles" );
   r = cmp( circle2( 0.0, -1.0, 3.0 ), circle2( 0.0, 1.0, 3.0 ), AST__AND );
   mesh( r, 1 );
   bounds( r );
   {
      double c1[ 2 ] = { 1.0, 0.0 }, p1[ 2 ] = { 2.0, 1.0 }, p0[ 2 ] = { 1.0, 1.0 };
      title( "and_closed" );
      r = cmp( boxc( 2, zero2, p0 ), boxc( 2, c1, p1 ), AST__AND );
      mesh( r, 1 );
      astSetI( r, "Closed", 0 );
      mesh( r, 1 );
      astSetI( r, "Closed", 1 );
      mesh( r, 1 );
   }
   {
      double lo[ 2 ], hi[ 2 ] = { BAD, BAD }, pt[ 2 ] = { 1.0e30, 1.0e30 };
      title( "and_nested_unbounded" );
      lo[ 0 ] = 0.0;
      lo[ 1 ] = BAD;
      a = (AstRegion *) astInterval( frame( 2, NULL ), lo, hi, NULL, " " );
      lo[ 0 ] = 1.0;
      b = (AstRegion *) astInterval( frame( 2, NULL ), lo, hi, NULL, " " );
      bounded( a );
      bounded( b );
      printf( "overlap %d\n", astOverlap( a, b ) );
      r = cmp( a, b, AST__AND );
      bounded( r );
      tran( r, 2, 1, pt );
      bounds( r );
   }
   {
      double pts[ 6 ] = { BAD, 5.0, 1.0, BAD, 1.0, 1.0 };
      title( "or_negated_null_values" );
      a = (AstRegion *) astNullRegion( frame( 2, NULL ), NULL, " " );
      astNegate( a );
      r = cmp( a, box2( 0.0, 0.0, 2.0, 2.0 ), AST__OR );
      values( r, 2, 3, pts, 1 );
      title( "and_negated_nulls_values" );
      b = (AstRegion *) astNullRegion( frame( 2, NULL ), NULL, " " );
      astNegate( b );
      r = cmp( a, b, AST__AND );
      values( r, 2, 3, pts, 1 );
      title( "and_null_box_values" );
      r = cmp( a, box2( 0.0, 0.0, 2.0, 2.0 ), AST__AND );
      values( r, 2, 3, pts, 1 );
   }
   title( "disjoint_and" );
   r = cmp( box2( 0.0, 0.0, 1.0, 1.0 ), box2( 3.0, 3.0, 4.0, 4.0 ), AST__AND );
   bounded( r );
   mesh( r, 1 );
   bounds( r );
   mesh( r, 0 );

/* Uncertainty. */
   title( "unc_default" );
   r = cmp( box2( 0.0, 0.0, 2.0, 2.0 ), box2( 1.0, 1.0, 3.0, 3.0 ), AST__OR );
   unc( r, 1 );
   unc( r, 0 );
   title( "unc_second" );
   {
      double cc[ 2 ] = { 0.0, 0.0 }, rr = 0.01;
      u = (AstRegion *) astCircle( frame( 2, NULL ), 1, cc, &rr, NULL, " " );
      b = box2( 1.0, 1.0, 3.0, 3.0 );
      astSetUnc( b, u );
      r = cmp( box2( 0.0, 0.0, 2.0, 2.0 ), b, AST__OR );
      unc( r, 1 );
      unc( r, 0 );
   }
   title( "unc_cmpregion_shape" );
   b = box2( 0.0, 0.0, 2.0, 2.0 );
   astSetUnc( b, cmp( box2( 0.0, 0.0, 0.1, 0.1 ), box2( 0.0, 0.0, 0.2, 0.2 ), AST__AND ) );
   failed();

/* Masks. */
   title( "mask_or_boxes" );
   r = cmp( box2( 0.5, 0.5, 2.5, 1.5 ), box2( 3.5, 1.5, 4.5, 3.5 ), AST__OR );
   mask( r, 1 );
   mask( r, 0 );
   title( "mask_and_negated" );
   r = cmp( box2( 0.5, 0.5, 4.5, 3.5 ), negated_copy( box2( 1.5, 1.5, 2.5, 2.5 ) ),
            AST__AND );
   mask( r, 1 );
   mask( r, 0 );

/* Overlaps. */
   overlap( "ov_cmp_box_inside", cmp( circle2( 0.0, 0.0, 5.0 ), boxc( 2, zero2, three2 ), AST__AND ),
            box2( -10.0, -10.0, 10.0, 10.0 ) );
   overlap( "ov_cmp_box_partial", cmp( circle2( 0.0, 0.0, 5.0 ), boxc( 2, zero2, three2 ), AST__AND ),
            box2( 0.0, 0.0, 10.0, 10.0 ) );
   overlap( "ov_cmp_box_none", cmp( circle2( 0.0, 0.0, 5.0 ), boxc( 2, zero2, three2 ), AST__AND ),
            box2( 20.0, 20.0, 30.0, 30.0 ) );
   overlap( "ov_or_circle", cmp( circle2( -5.0, 0.0, 2.0 ), circle2( 5.0, 0.0, 2.0 ), AST__OR ),
            circle2( -5.0, 0.0, 1.0 ) );
   a = cmp( box2( 0.0, 0.0, 2.0, 2.0 ), box2( 1.0, 1.0, 3.0, 3.0 ), AST__OR );
   overlap( "ov_cmp_same", a, astCopy( a ) );
   overlap( "ov_cmp_negation", a, negated_copy( a ) );

/* The Rust overlap tests. */
   overlap( "ov_identical_circles", circle2( 0.0, 0.0, 5.0 ), circle2( 0.0, 0.0, 5.0 ) );
   overlap( "ov_separated_circles", circle2( 0.0, 0.0, 2.0 ), circle2( 10.0, 0.0, 2.0 ) );
   overlap( "ov_small_in_large", circle2( 0.0, 0.0, 2.0 ), circle2( 0.0, 0.0, 10.0 ) );
   overlap( "ov_partial_circles", circle2( 0.0, 0.0, 5.0 ), circle2( 6.0, 0.0, 5.0 ) );
   overlap( "ov_off_centre", circle2( 3.0, 0.0, 2.0 ), circle2( 0.0, 0.0, 10.0 ) );
   a = (AstRegion *) astNullRegion( frame( 2, NULL ), NULL, " " );
   overlap( "ov_circle_null", circle2( 0.0, 0.0, 5.0 ), a );
   overlap( "ov_circle_negated_null", circle2( 0.0, 0.0, 5.0 ), negated_copy( a ) );
   overlap( "ov_two_nulls", a, astCopy( a ) );
   overlap( "ov_two_negated_nulls", negated_copy( a ), negated_copy( a ) );
   {
      double ten[ 2 ] = { 10.0, 10.0 }, one[ 2 ] = { 1.0, 1.0 };
      double far[ 2 ] = { 100.0, 100.0 }, far1[ 2 ] = { 101.0, 101.0 };
      double cb[ 2 ] = { 5.5, 0.0 }, pb[ 2 ] = { 2.5, 2.0 };
      overlap( "ov_circle_in_box", circle2( 0.0, 0.0, 2.0 ), boxc( 2, zero2, ten ) );
      overlap( "ov_box_in_circle", boxc( 2, zero2, one ), circle2( 0.0, 0.0, 100.0 ) );
      overlap( "ov_circle_box_apart", circle2( 0.0, 0.0, 2.0 ), boxc( 2, far, far1 ) );
      overlap( "ov_circle_box_partial", circle2( 0.0, 0.0, 5.0 ), boxc( 2, cb, pb ) );
   }
   overlap( "ov_negated_vs_small", negated_copy( circle2( 0.0, 0.0, 10.0 ) ),
            circle2( 0.0, 0.0, 2.0 ) );
   overlap( "ov_negated_vs_far", negated_copy( circle2( 0.0, 0.0, 2.0 ) ),
            circle2( 50.0, 0.0, 3.0 ) );
   overlap( "ov_far_apart", circle2( 0.0, 0.0, 1.0 ), circle2( 1000.0, 1000.0, 1.0 ) );

/* A Region with no boundary mesh, which OverlapX meshes inside a box. */
   {
      double lo[ 2 ] = { 0.0, BAD }, hi[ 2 ] = { BAD, BAD };
      a = (AstRegion *) astInterval( frame( 2, NULL ), lo, hi, NULL, " " );
      overlap( "ov_half_plane_circle_across", a, circle2( 0.0, 0.0, 1.0 ) );
      overlap( "ov_half_plane_circle_inside", a, circle2( 5.0, 0.0, 1.0 ) );
      overlap( "ov_half_plane_circle_outside", a, circle2( -5.0, 0.0, 1.0 ) );
      overlap( "ov_negated_circle_circle", negated_copy( circle2( 0.0, 0.0, 1.0 ) ),
               negated_copy( circle2( 0.5, 0.0, 1.0 ) ) );
   }

/* PointList components. */
   {
      double plv[ 4 ] = { 1.0, 2.0, 1.0, 2.0 };
      double pinpts[ 6 ] = { 1.0, 1.0, 2.0, 2.0, 0.5, 0.5 };
      AstRegion *pl = (AstRegion *) astPointList( frame( 2, NULL ), 2, 2, 2, plv, NULL, " " );
      r = cmp( pl, box2( 0.0, 0.0, 3.0, 3.0 ), AST__AND );
      overlap( "ov_pl_and_box_big", r, box2( 0.0, 0.0, 5.0, 5.0 ) );
      overlap( "ov_pl_and_box_small", r, box2( 1.5, 1.5, 5.0, 5.0 ) );
      overlap( "ov_pl_and_box_apart", r, box2( 10.0, 10.0, 15.0, 15.0 ) );
      title( "pl_and_box" );
      tran( r, 2, 3, pinpts );
      bounded( r );
      bounds( r );
      mesh( r, 1 );
      ps = pset2( 3, pinpts );
      pins( r, ps, NULL );
      r = cmp( pl, box2( 0.0, 0.0, 3.0, 3.0 ), AST__OR );
      overlap( "ov_pl_or_box", r, box2( 0.0, 0.0, 5.0, 5.0 ) );
      title( "pl_or_box" );
      tran( r, 2, 3, pinpts );
      bounded( r );
      ps = pset2( 3, pinpts );
      pins( r, ps, NULL );
   }

/* Two CmpRegions holding the PointList (0, 0), (1, 1): the mesh of the
   second is the PointList's two points and the corner of its Box at one of
   them, so pointlist.c RegPins is given more points than the PointList has,
   all near its points. */
   {
      double plv[ 4 ] = { 0.0, 1.0, 0.0, 1.0 };
      AstRegion *pl = (AstRegion *) astPointList( frame( 2, NULL ), 2, 2, 2, plv, NULL, " " );
      a = cmp( pl, box2( -1.0, -1.0, 3.0, 3.0 ), AST__AND );
      b = cmp( pl, box2( 0.0, 0.0, 2.0, 2.0 ), AST__AND );
      title( "pl_more_points" );
      mesh( b, 1 );
      printf( "overlap %d\n", astOverlap( a, b ) );
      failed();
   }

/* Prisms. */
   {
      double pts[ 27 ] = { 1.0, 1.0, 5.0, 1.0, 1.0, 10.0, 10.0, 10.0, 5.0,
                           10.0, 10.0, 10.0, 5.0, 0.0, 5.0, BAD, 0.0, 5.0,
                           0.0, BAD, 5.0, 0.0, 0.0, BAD, 0.0, 0.0, 2.0 };
      double vpts[ 9 ] = { 1.0, 1.0, 5.0, 10.0, 10.0, 5.0, 1.0, 1.0, 10.0 };
      title( "prism" );
      r = cylinder();
      naxes( (AstObject *) r );
      tran( r, 3, 9, pts );
      values( r, 3, 3, vpts, 1 );
      values( r, 3, 3, vpts, 0 );
      bounded( r );
      bounds( r );
      fill( r );
      points( r );
      unc( r, 1 );
      printf( "domain %s\n", astGetC( r, "Domain" ) );
      title( "prism_negated" );
      astNegate( r );
      tran( r, 3, 9, pts );
      bounded( r );
      bounds( r );
      title( "prism_mesh" );
      r = cylinder();
      astSetI( r, "MeshSize", 60 );
      mesh( r, 1 );
   }
   {
      double lo = 2.0, hi = BAD;
      title( "prism_unbounded" );
      r = prism( circle2( 0.0, 0.0, 5.0 ),
                 (AstRegion *) astInterval( frame( 1, NULL ), &lo, &hi, NULL, " " ) );
      bounded( r );
      bounds( r );
      mesh( r, 1 );
   }
   {
      int perm[ 3 ] = { 3, 2, 1 };
      double pts[ 9 ] = { 5.0, 1.0, 1.0, 1.0, 1.0, 5.0, 5.0, 0.0, 4.0 };
      title( "prism_permuted" );
      r = cylinder();
      r = mapped( r, (AstMapping *) astPermMap( 3, perm, 3, perm, NULL, " " ), frame( 3, NULL ) );
      tran( r, 3, 3, pts );
      bounds( r );
      bounded( r );
      title( "prism_zoomed" );
      r = cylinder();
      r = mapped( r, (AstMapping *) astZoomMap( 3, 2.0, " " ), frame( 3, NULL ) );
      bounds( r );
   }
   {
      int ax12[ 2 ] = { 1, 2 }, ax13[ 2 ] = { 1, 3 }, ax3[ 1 ] = { 3 };
      title( "prism_pick" );
      r = cylinder();
      pick( (AstFrame *) r, 2, ax12 );
      pick( (AstFrame *) r, 2, ax13 );
      pick( (AstFrame *) r, 1, ax3 );
      title( "cmp_pick" );
      r = cmp( box2( 0.0, 0.0, 2.0, 2.0 ), box2( 1.0, 1.0, 3.0, 3.0 ), AST__AND );
      pick( (AstFrame *) r, 1, ax12 );
   }
   title( "prism_decompose" );
   r = cylinder();
   decompose( (AstMapping *) r );
   maplist( (AstMapping *) r );
   title( "cmp_decompose" );
   r = cmp( box2( 0.0, 0.0, 2.0, 2.0 ), box2( 1.0, 1.0, 3.0, 3.0 ), AST__AND );
   decompose( (AstMapping *) r );
   maplist( (AstMapping *) r );

/* A Prism as the uncertainty of a 3-D Box, which astSetUnc re-centres. */
   {
      double blo[ 3 ] = { 0.0, 0.0, 0.0 }, bhi[ 3 ] = { 10.0, 10.0, 10.0 };
      title( "prism_as_uncertainty" );
      u = prism( box2( -0.1, -0.1, 0.1, 0.1 ), interval1( -0.2, 0.2 ) );
      r = (AstRegion *) astBox( frame( 3, NULL ), 1, blo, bhi, u, " " );
      unc( r, 0 );
   }

/* Prism overlaps. */
   overlap( "ov_prism_in_box", cylinder(), box3( -10.0, -10.0, 0.0, 10.0, 10.0, 10.0 ) );
   overlap( "ov_prism_across_box", cylinder(), box3( 0.0, -10.0, 0.0, 10.0, 10.0, 10.0 ) );
   overlap( "ov_prism_apart_box", cylinder(), box3( 20.0, 20.0, 0.0, 30.0, 30.0, 10.0 ) );
   overlap( "ov_negated_prism_box", negated_copy( cylinder() ),
            box3( -10.0, -10.0, 0.0, 10.0, 10.0, 10.0 ) );
   overlap( "ov_prism_negated_box", cylinder(),
            negated_copy( box3( -10.0, -10.0, 0.0, 10.0, 10.0, 10.0 ) ) );
   overlap( "ov_negated_prism_negated_box", negated_copy( cylinder() ),
            negated_copy( box3( -10.0, -10.0, 0.0, 10.0, 10.0, 10.0 ) ) );
   overlap( "ov_prism_prism", cylinder(), cylinder() );
   overlap( "ov_prism_negated_prism", cylinder(), negated_copy( cylinder() ) );
   {
      double cen[ 2 ] = { 0.0, 0.0 }, pt[ 2 ] = { 5.0, 0.0 };
      a = (AstRegion *) astCircle( frame( 2, NULL ), 0, cen, pt, NULL, " " );
      overlap( "ov_prism_prism_other_form", cylinder(), prism( a, interval1( 2.0, 8.0 ) ) );
   }

/* Prism masks (the Rust test) and a grid with no point inside. */
   {
      int lb3[ 3 ] = { 1, 1, 1 }, ub3[ 3 ] = { 5, 5, 5 };
      title( "mask_prism" );
      r = prism( circle2( 3.0, 3.0, 1.0 ), interval1( 2.0, 4.0 ) );
      maskcount( r, (AstMapping *) astUnitMap( 3, " " ), 1, 3, lb3, ub3, -1.0 );
   }
   {
      double plv[ 4 ] = { 1.0, 2.0, 1.0, 2.0 };
      AstRegion *pl = (AstRegion *) astPointList( frame( 2, NULL ), 2, 2, 2, plv, NULL, " " );
      title( "prism_pointlist" );
      r = prism( pl, interval1( 0.0, 1.0 ) );
      bounded( r );
      bounds( r );
      mesh( r, 1 );
      mesh( r, 0 );
   }

/* A PointList with no point at a corner of its bounding box: the grid
   over that box finds no point in it. */
   {
      double plv[ 8 ] = { 0.0, 0.2718, 1.0, 0.7071, 0.3141, 0.0, 0.577, 1.0 };
      title( "pointlist_grid" );
      a = (AstRegion *) astPointList( frame( 2, NULL ), 4, 2, 4, plv, NULL, " " );
      bounds( a );
      mesh( a, 0 );
      title( "prism_pointlist_grid" );
      r = prism( a, interval1( 0.0, 1.0 ) );
      mesh( r, 1 );
      mesh( r, 0 );
   }

/* The Rust masking tests. */
   {
      int lb[ 2 ] = { 1, 1 }, ub[ 2 ] = { 5, 5 }, ub3[ 2 ] = { 3, 3 };
      int ub10[ 2 ] = { 10, 10 };
      title( "mask_outside_circle" );
      maskcount( circle2( 3.0, 3.0, 2.0 ), (AstMapping *) astUnitMap( 2, " " ), 0, 2, lb, ub, 0.0 );
      title( "mask_integer_circle" );
      maskcount( circle2( 2.0, 2.0, 1.0 ), (AstMapping *) astUnitMap( 2, " " ), 1, 2, lb, ub3, -999.0 );
      title( "mask_zoom_circle" );
      maskcount( circle2( 2.5, 2.5, 1.5 ), (AstMapping *) astZoomMap( 2, 2.0, " " ), 1, 2, lb, ub10, -1.0 );
   }

/* Dumps. */
   {
      double cen[ 2 ] = { 1.0, 0.5 }, rad = 0.1;
      double glo[ 2 ] = { 2.857, -0.346 }, ghi[ 2 ] = { 3.057, -0.146 };
      a = (AstRegion *) astCircle( astSkyFrame( "System=FK5" ), 1, cen, &rad, NULL, " " );
      b = (AstRegion *) astBox( astSkyFrame( "System=Galactic" ), 1, glo, ghi, NULL, " " );
      show( "show_sky_and", (AstObject *) cmp( a, b, AST__AND ) );
   }
   a = cmp( circle2( 0.0, 0.0, 3.0 ), boxc( 2, zero2, two2 ), AST__AND );
   show( "show_nested", (AstObject *) cmp( a, circle2( 10.0, 10.0, 1.0 ), AST__OR ) );
   show( "show_prism", (AstObject *) cylinder() );
   c = prism( prism( box2( 0.0, 0.0, 1.0, 1.0 ), interval1( 0.0, 1.0 ) ), interval1( 2.0, 3.0 ) );
   show( "show_prism_of_prism", (AstObject *) c );
   title( "prism_of_prism" );
   naxes( (AstObject *) c );
   bounds( c );
   {
      double pts[ 12 ] = { 0.5, 0.5, 0.5, 2.5, 0.5, 0.5, 0.5, 2.5, 0.5, 0.5, 4.0, 0.5 };
      tran( c, 4, 3, pts );
   }
   reload( "reload_prism", (AstObject *) cylinder() );
   reload( "reload_prism_of_prism", (AstObject *) c );
   reload( "reload_nested", (AstObject *) cmp( a, circle2( 10.0, 10.0, 1.0 ), AST__OR ) );
   r = cylinder();
   astSet( r, "Adaptive=0,Label(3)=Height,Label(1)=X" );
   show( "show_prism_labels", (AstObject *) r );
   astClear( r, "Label(3)" );
   show( "show_prism_cleared", (AstObject *) r );
   reload( "reload_prism_cleared", (AstObject *) r );
   {
      double cen[ 2 ] = { 1.0, 0.5 }, rad = 0.1;
      double glo[ 2 ] = { 2.857, -0.346 }, ghi[ 2 ] = { 3.057, -0.146 };
      a = (AstRegion *) astCircle( astSkyFrame( "System=FK5" ), 1, cen, &rad, NULL, " " );
      b = (AstRegion *) astBox( astSkyFrame( "System=Galactic" ), 1, glo, ghi, NULL, " " );
      reload( "reload_sky_and", (AstObject *) cmp( a, b, AST__AND ) );
   }

   return 0;
}
