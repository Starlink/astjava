/* polymap_cancel_probe -- what simplifying a series CmpMap of a PolyMap and
 * an inverted copy of it does to the Invert attributes of the two PolyMaps,
 * which the CmpMap shares, in the pinned C library (polymap.c MapMerge sets
 * the Invert attribute of the PolyMap it nominates to the value the list
 * applies it with, and of the neighbour it compares; it sets back the
 * neighbour's and, unless the pair cancels, the nominee's).
 *
 * The PolyMap has one input and one output: forward 1.0*x*x + 3.0*x, inverse
 * 0.5*x. For each case the probe writes the class of the simplified Mapping
 * and, before and after simplifying, the value of the Invert attribute of
 * the PolyMap and of its copy and whether each is set.
 *
 * Each case starts with a line "== <name>".
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o polymap_cancel_probe polymap_cancel_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as
 *   ./polymap_cancel_probe > polymap_cancel_probe.out 2>&1 */
#include <stdio.h>
#include "ast.h"

static void invert_attrs( const char *when, AstMapping *p, AstMapping *q ) {
   printf( "%s p %d set %d q %d set %d\n", when, astGetI( p, "Invert" ), astTest( p, "Invert" ),
           astGetI( q, "Invert" ), astTest( q, "Invert" ) );
}

/* Simplifies the series CmpMap of the PolyMap and an inverted copy of it,
   itself inverted if "invert_cmp" is non-zero. */
static void cancel( const char *name, int invert_cmp ) {
   static const double fwd[] = { 1.0, 1.0, 2.0, 3.0, 1.0, 1.0 };
   static const double inv[] = { 0.5, 1.0, 1.0 };
   printf( "== %s\n", name );
   AstMapping *p = (AstMapping *) astPolyMap( 1, 1, 2, fwd, 1, inv, " " );
   AstMapping *q = astCopy( p );
   astInvert( q );
   AstMapping *c = (AstMapping *) astCmpMap( p, q, 1, " " );
   if( invert_cmp ) astInvert( c );
   invert_attrs( "before", p, q );
   AstMapping *s = astSimplify( c );
   printf( "class %s\n", astGetC( s, "Class" ) );
   invert_attrs( "after", p, q );
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
   }
   astAnnul( s );
   astAnnul( c );
   astAnnul( q );
   astAnnul( p );
}

int main( void ) {
   setvbuf( stdout, NULL, _IONBF, 0 );
   cancel( "series", 0 );
   cancel( "inverted_series", 1 );
   return 0;
}
