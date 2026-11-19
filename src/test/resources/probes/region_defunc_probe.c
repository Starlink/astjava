/* region_defunc_probe -- the default uncertainty of a Box in the pinned C
 * library (region.c GetDefUnc, GetUncFrm, ResetCache; box.c Transform,
 * RegCentre) before and after the Box is moved: by the protected
 * astRegCentre, by the protected astMapRegion (the public one simplifies),
 * and by a change of Unit on an Adaptive Box and on one that is not; and
 * astSetUnc given an uncertainty of other dimensionality or an unbounded one.
 *
 * Every Box is 0 to 10 on its first axis and has zero width at 5 on its
 * second, so it is widened by its default uncertainty (box.c Transform),
 * which region.c builds once (GetDefUnc, one millionth of the axis value on
 * an axis of zero width) and does not discard when the Box moves.
 *
 * Each case starts with a line "== <name>". A "tran" line gives a label, the
 * point given to astTran2 and, after the colon, the point it returns. An
 * "unc" line gives a label and the bounds (lower then upper, axis by axis)
 * of astGetUnc( region, 1 ). A "points" line gives astGetRegionPoints, point
 * by point. A "status" line reports a failed call. Values are written with
 * %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_defunc_probe
 *      region_defunc_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./region_defunc_probe > region_defunc_probe.out 2>&1. */
#include <stdio.h>
#include "ast.h"

double *astRegCentre_( AstRegion *, double *, double **, int, int, int * );
AstRegion *astMapRegion_( AstRegion *, AstMapping *, AstFrame *, int * );

static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

static void tran( const char *label, AstMapping *m, double x, double y ) {
   double xin[ 1 ], yin[ 1 ], xout[ 1 ], yout[ 1 ];
   xin[ 0 ] = x;
   yin[ 0 ] = y;
   astTran2( m, 1, xin, yin, 1, xout, yout );
   if( failed() ) return;
   printf( "tran %s %.17g %.17g : %.17g %.17g\n", label, x, y, xout[ 0 ], yout[ 0 ] );
}

static void unc( const char *label, AstRegion *r ) {
   double lb[ 2 ], ub[ 2 ];
   AstRegion *u = astGetUnc( r, 1 );
   astGetRegionBounds( u, lb, ub );
   if( failed() ) return;
   printf( "unc %s %.17g %.17g %.17g %.17g\n", label, lb[ 0 ], ub[ 0 ], lb[ 1 ], ub[ 1 ] );
   u = astAnnul( u );
}

static void points( AstRegion *r ) {
   double pts[ 2 ][ 2 ];
   int npoint;
   astGetRegionPoints( r, 2, 2, &npoint, (double *) pts );
   if( failed() ) return;
   printf( "points %.17g %.17g %.17g %.17g\n", pts[ 0 ][ 0 ], pts[ 1 ][ 0 ], pts[ 0 ][ 1 ], pts[ 1 ][ 1 ] );
}

static AstBox *flat( AstFrame *f ) {
   double lb[ 2 ] = { 0.0, 5.0 };
   double ub[ 2 ] = { 10.0, 5.0 };
   return astBox( f, 1, lb, ub, NULL, " " );
}

int main( void ) {
   AstFrame *f;
   AstBox *box;
   AstMapping *m;
   AstRegion *r;
   double cen[ 2 ];
   double shift[ 2 ] = { 0.0, 995.0 };

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;

   /* Moved by astRegCentre, in the base Frame. */
   printf( "== regcentre\n" );
   box = flat( astFrame( 2, " " ) );
   tran( "near", (AstMapping *) box, 5.0, 5.0 + 2.0e-6 );
   tran( "off", (AstMapping *) box, 5.0, 5.0 + 3.0e-6 );
   unc( "before", (AstRegion *) box );
   cen[ 0 ] = 5.0;
   cen[ 1 ] = 1000.0;
   astRegCentre_( astMakePointer( box ), cen, NULL, 0, AST__BASE, astGetStatusPtr );
   failed();
   points( (AstRegion *) box );
   tran( "moved_near", (AstMapping *) box, 5.0, 1000.0 + 2.0e-6 );
   tran( "moved_far", (AstMapping *) box, 5.0, 1000.0 + 2.0e-4 );
   unc( "after", (AstRegion *) box );

   /* Moved by the protected astMapRegion, through a ShiftMap. */
   printf( "== mapregion\n" );
   box = flat( astFrame( 2, " " ) );
   tran( "near", (AstMapping *) box, 5.0, 5.0 + 2.0e-6 );
   m = (AstMapping *) astShiftMap( 2, shift, " " );
   r = astMapRegion_( astMakePointer( box ), astMakePointer( m ),
                      astMakePointer( astFrame( 2, " " ) ), astGetStatusPtr );
   if( !failed() ) {
      r = astMakeId( r );
      points( r );
      tran( "moved_near", (AstMapping *) r, 5.0, 1000.0 + 2.0e-6 );
      tran( "moved_off", (AstMapping *) r, 5.0, 1000.0 + 3.0e-6 );
      tran( "moved_far", (AstMapping *) r, 5.0, 1000.0 + 2.0e-4 );
      unc( "after", r );
   }

   /* Moved into km by an adaptive change of Unit: the Region is remapped,
      and its default uncertainty, in the base Frame, with it. */
   printf( "== unit_adaptive\n" );
   f = astFrame( 2, "Unit(1)=m,Unit(2)=m" );
   astSetActiveUnit( f, 1 );
   box = flat( f );
   tran( "near", (AstMapping *) box, 5.0, 5.0 + 2.0e-6 );
   unc( "before", (AstRegion *) box );
   astSetC( box, "Unit(2)", "km" );
   failed();
   points( (AstRegion *) box );
   tran( "moved_near", (AstMapping *) box, 5.0, 0.005 + 2.0e-9 );
   tran( "moved_off", (AstMapping *) box, 5.0, 0.005 + 3.0e-9 );
   tran( "moved_far", (AstMapping *) box, 5.0, 0.005 + 2.0e-6 );
   unc( "after", (AstRegion *) box );

   /* The same change on a Box that is not Adaptive: both Frames change, so
      the Box keeps its values, now in km, and its default uncertainty is
      kept. */
   printf( "== unit_not_adaptive\n" );
   f = astFrame( 2, "Unit(1)=m,Unit(2)=m" );
   astSetActiveUnit( f, 1 );
   box = flat( f );
   astSetI( box, "Adaptive", 0 );
   tran( "near", (AstMapping *) box, 5.0, 5.0 + 2.0e-6 );
   unc( "before", (AstRegion *) box );
   astSetC( box, "Unit(2)", "km" );
   failed();
   points( (AstRegion *) box );
   tran( "moved_near", (AstMapping *) box, 5.0, 5.0 + 2.0e-6 );
   tran( "moved_off", (AstMapping *) box, 5.0, 5.0 + 3.0e-6 );
   unc( "after", (AstRegion *) box );

   /* astSetUnc on a Box: an uncertainty of three axes, which is rejected,
      and an unbounded one, a negated Box, which region.c SetUnc negates
      back ("negated" is the Negated attribute of the uncertainty held,
      "given" that of the Box given). */
   printf( "== setunc_dims\n" );
   box = flat( astFrame( 2, " " ) );
   {
      double lb3[ 3 ] = { -1.0, -1.0, -1.0 };
      double ub3[ 3 ] = { 1.0, 1.0, 1.0 };
      astSetUnc( box, astBox( astFrame( 3, " " ), 1, lb3, ub3, NULL, " " ) );
      failed();
   }
   printf( "== setunc_unbounded\n" );
   box = flat( astFrame( 2, " " ) );
   {
      double lbu[ 2 ] = { -0.5, -0.5 };
      double ubu[ 2 ] = { 0.5, 0.5 };
      AstBox *u = astBox( astFrame( 2, " " ), 1, lbu, ubu, NULL, " " );
      astNegate( u );
      astSetUnc( box, u );
      if( !failed() ) {
         tran( "near", (AstMapping *) box, 5.0, 5.4 );
         tran( "off", (AstMapping *) box, 5.0, 5.6 );
         unc( "after", (AstRegion *) box );
         printf( "negated %d\n", astGetI( astGetUnc( box, 0 ), "Negated" ) );
         printf( "given %d\n", astGetI( u, "Negated" ) );
      }
   }

   astEnd;
   return 0;
}
