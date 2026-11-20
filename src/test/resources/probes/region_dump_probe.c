/* region_dump_probe -- the Negate, Fill, MeshSz and Closed items region.c's
 * Dump writes for Regions with those attributes set: a Box, a CmpRegion
 * (whose own setters pass Closed and MeshSize on to its components), and a
 * PointList, whose GetClosed always gives the opposite of Negated while
 * Dump writes the Closed value set on it, read through region.c's own
 * TestClosed and GetClosed.
 *
 * Each case starts with a line "== <name>".
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_dump_probe
 *      region_dump_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./region_dump_probe > region_dump_probe.txt 2>&1; the output,
 * with trailing white space removed from every line, is stored as the
 * "output" string of region_dump_probe.json, since the dumps hold C's item
 * names, which the spelling check rejects. */
#include <stdio.h>
#include "ast.h"

static void head( const char *name ) {
   printf( "== %s\n", name );
}

static AstBox *box2( double xlo, double ylo, double xhi, double yhi ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = xlo;
   lb[ 1 ] = ylo;
   ub[ 0 ] = xhi;
   ub[ 1 ] = yhi;
   return astBox( astFrame( 2, " " ), 1, lb, ub, NULL, " " );
}

int main( void ) {
   AstBox *box;
   AstCmpRegion *cmp;
   AstPointList *pl;
   double pts[ 2 ][ 2 ] = { { 0.0, 1.0 }, { 0.0, 2.0 } };

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;

   head( "box" );
   box = box2( 0.0, 0.0, 1.0, 2.0 );
   astSet( box, "Closed=0,MeshSize=50,FillFactor=0.5" );
   astShow( box );

   head( "cmpregion" );
   cmp = astCmpRegion( box2( 0.0, 0.0, 2.0, 2.0 ), box2( 1.0, 1.0, 3.0, 3.0 ),
                       AST__AND, " " );
   astSet( cmp, "Closed=0,MeshSize=20" );
   astShow( cmp );

   head( "pointlist" );
   pl = astPointList( astFrame( 2, " " ), 2, 2, 2, (const double *) pts, NULL, " " );
   astSet( pl, "Closed=0" );
   printf( "Closed %d\n", astGetI( pl, "Closed" ) );
   astShow( pl );

   head( "pointlist_negated" );
   astSet( pl, "Negated=1,Closed=1" );
   printf( "Closed %d\n", astGetI( pl, "Closed" ) );
   astShow( pl );

   if( !astOK ) printf( "status %d\n", astStatus );
   astEnd;
   return 0;
}
