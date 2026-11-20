/* region_and_open_prism_probe -- astOverlap of a Circle and the AND of a
 * Prism with an open end and another Circle, by the pinned C library.
 *
 * The Prism is the Interval x >= 0 extruded along the Box 0 <= y <= 1,
 * which has no boundary mesh of its own; the AND holds it and the Circle of
 * radius 3 about (5, 0.5), and the other Region is the Circle of radius 3
 * about (0, 0.5). The "overlap" line gives astOverlap( and, circle ). The
 * other order, astOverlap( circle, and ), is not run: region.c BndBaseMesh
 * meshes the Prism inside a box with a CmpRegion whose own mesh calls
 * BndBaseMesh with the same box again, and C recurses until the stack
 * overflows (a segmentation fault on AArch64 macOS).
 *
 * Each case starts with a line "== <name>".
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_and_open_prism_probe
 *      region_and_open_prism_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./region_and_open_prism_probe > region_and_open_prism_probe.out
 * 2>&1. */
#include <stdio.h>
#include "ast.h"

static AstRegion *circle( double x, double y, double r ) {
   double cen[ 2 ];
   cen[ 0 ] = x;
   cen[ 1 ] = y;
   return (AstRegion *) astCircle( astFrame( 2, " " ), 1, cen, &r, NULL, " " );
}

int main( void ) {
   double lb = 0.0, ub = AST__BAD, l0 = 0.0, u1 = 1.0;
   AstRegion *open, *side, *prism, *and;
   int r;

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;
   open = (AstRegion *) astInterval( astFrame( 1, " " ), &lb, &ub, NULL, " " );
   side = (AstRegion *) astBox( astFrame( 1, " " ), 1, &l0, &u1, NULL, " " );
   prism = (AstRegion *) astPrism( open, side, " " );
   and = (AstRegion *) astCmpRegion( prism, circle( 5.0, 0.5, 3.0 ), AST__AND, " " );
   printf( "== and_of_open_prism_and_circle\n" );
   r = astOverlap( and, circle( 0.0, 0.5, 3.0 ) );
   if( astOK ) {
      printf( "overlap %d\n", r );
   } else {
      printf( "status %d\n", astStatus );
      astClearStatus;
   }
   astEnd;
   return 0;
}
