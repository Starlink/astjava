/* region_frameset_probe -- a Box used as a Frame by the pinned C library,
 * and a Box changed after first use.
 *
 * A Box added to a FrameSet as a Frame is a Mapping on the path between
 * the Frames on either side of it (frameset.c Span), so points outside it
 * become bad; the FrameSet is written and read back. A Box used as the
 * template of astFindFrame and as either Frame of astConvert stands for its
 * current Frame (region.c Match, Convert, ConvertX).
 *
 * A Box changed after it has been used: Negated, Closed, MeshSize (on a Box
 * mapped by a non-linear MathMap, whose bounds come from its boundary
 * mesh), the System of a Box in a SpecFrame, Adaptive and not, and the
 * uncertainty. Each is followed by what C's resets of the kept values make
 * visible: astTran<X>, astGetRegionBounds, astGetRegionPoints and
 * astGetUnc.
 *
 * Each case starts with a line "== <name>". A "tran" line gives a label, the
 * point given to astTranN with the forward ("fwd") or inverse ("inv")
 * transformation and, after the colon, the point it returns. A "bounds" line
 * gives the lower then the upper bound on each axis, a "points" line the
 * defining points point by point, an "unc" line the bounds of astGetUnc(
 * region, 1 ), and a "frames" line the class, Nframe and Domain of the
 * current Frame of a FrameSet. A "dump" line gives one line of what a
 * Channel writes. A "status" line reports a failed call. Values are written
 * with %.17g.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o region_frameset_probe
 *      region_frameset_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./region_frameset_probe > region_frameset_probe.txt 2>&1; the
 * output, with trailing white space removed from every line, is stored as
 * the "output" string of region_frameset_probe.json, since the dumps hold
 * C's item names, which the spelling check rejects. */
#include <stdio.h>
#include <string.h>
#include "ast.h"

AstRegion *astMapRegion_( AstRegion *, AstMapping *, AstFrame *, int * );

static char dumptext[ 20000 ];

static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

static void tran( const char *label, AstMapping *m, int forward, const double *in ) {
   double inbuf[ 3 ], out[ 3 ];
   int nin = forward ? astGetI( m, "Nin" ) : astGetI( m, "Nout" );
   int nout = forward ? astGetI( m, "Nout" ) : astGetI( m, "Nin" );
   int i;
   if( failed() ) return;
   for( i = 0; i < nin; i++ ) inbuf[ i ] = in[ i ];
   astTranN( m, 1, nin, 1, inbuf, forward, nout, 1, out );
   if( failed() ) return;
   printf( "tran %s %s", label, forward ? "fwd" : "inv" );
   for( i = 0; i < nin; i++ ) printf( " %.17g", inbuf[ i ] );
   printf( " :" );
   for( i = 0; i < nout; i++ ) printf( " %.17g", out[ i ] );
   printf( "\n" );
}

static void tran2( const char *label, AstMapping *m, double x, double y ) {
   double in[ 2 ];
   in[ 0 ] = x;
   in[ 1 ] = y;
   tran( label, m, 1, in );
}

static void tran1( const char *label, AstMapping *m, double x ) {
   tran( label, m, 1, &x );
}

static void bounds( const char *what, AstRegion *r ) {
   double lb[ 3 ], ub[ 3 ];
   int i, n = astGetI( r, "Naxes" );
   astGetRegionBounds( r, lb, ub );
   if( failed() ) return;
   printf( "%s", what );
   for( i = 0; i < n; i++ ) printf( " %.17g %.17g", lb[ i ], ub[ i ] );
   printf( "\n" );
}

static void unc( AstRegion *r ) {
   AstRegion *u = astGetUnc( r, 1 );
   if( failed() ) return;
   bounds( "unc", u );
   u = astAnnul( u );
}

static void points( AstRegion *r ) {
   double pts[ 3 * 2 ];
   int npoint, n = astGetI( r, "Naxes" ), ip, ic;
   astGetRegionPoints( r, 2, n, &npoint, pts );
   if( failed() ) return;
   printf( "points" );
   for( ip = 0; ip < npoint; ip++ ) {
      for( ic = 0; ic < n; ic++ ) printf( " %.17g", pts[ ic * 2 + ip ] );
   }
   printf( "\n" );
}

static void frames( AstFrameSet *fs ) {
   if( !fs ) {
      printf( "frames null\n" );
      return;
   }
   printf( "frames %s %d %s\n", astGetC( astGetFrame( fs, AST__CURRENT ), "Class" ),
           astGetI( fs, "Nframe" ), astGetC( fs, "Domain" ) );
   failed();
}

static void sink( const char *line ) {
   strcat( dumptext, line );
   strcat( dumptext, "\n" );
   printf( "dump %s\n", line );
}

static const char *source_ptr;

static const char *source( void ) {
   static char line[ 400 ];
   const char *nl;
   size_t len;
   if( !source_ptr || !*source_ptr ) return NULL;
   nl = strchr( source_ptr, '\n' );
   len = nl ? (size_t) ( nl - source_ptr ) : strlen( source_ptr );
   memcpy( line, source_ptr, len );
   line[ len ] = 0;
   source_ptr = nl ? nl + 1 : source_ptr + len;
   return line;
}

static AstObject *reread( AstObject *obj ) {
   AstChannel *ch;
   AstObject *result;
   dumptext[ 0 ] = 0;
   ch = astChannel( NULL, sink, " " );
   astWrite( ch, obj );
   ch = astAnnul( ch );
   source_ptr = dumptext;
   ch = astChannel( source, NULL, " " );
   result = astRead( ch );
   ch = astAnnul( ch );
   failed();
   return result;
}

static AstBox *square( AstFrame *f, double lo, double hi ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = lb[ 1 ] = lo;
   ub[ 0 ] = ub[ 1 ] = hi;
   return astBox( f, 1, lb, ub, NULL, " " );
}

int main( void ) {
   AstBox *box;
   AstBox *ubox;
   AstFrame *f;
   AstFrameSet *fs;
   AstFrameSet *cvt;
   AstMapping *m;
   AstObject *back;
   AstRegion *r;
   double lb[ 2 ], ub[ 2 ], p[ 2 ];
   double shift[ 2 ] = { 100.0, 100.0 };
   const char *fwd[ 2 ] = { "x2 = x1", "y2 = y1 + 0.1*x1*x1" };
   const char *inv[ 2 ] = { "x1 = x2", "y1 = y2 - 0.1*x2*x2" };

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;

   /* A Box as a Frame in a FrameSet, at the end of the path and between
      two other Frames, then written and read back. */
   printf( "== in_frameset\n" );
   fs = astFrameSet( astFrame( 2, "Domain=PIX" ), " " );
   box = square( astFrame( 2, "Domain=GRID" ), 0.0, 10.0 );
   astAddFrame( fs, AST__BASE, astZoomMap( 2, 2.0, " " ), box );
   failed();
   tran2( "to_box_in", (AstMapping *) fs, 1.0, 1.0 );
   tran2( "to_box_out", (AstMapping *) fs, 6.0, 6.0 );
   astAddFrame( fs, AST__CURRENT, astShiftMap( 2, shift, " " ), astFrame( 2, "Domain=SKYISH" ) );
   failed();
   tran2( "through_box_in", (AstMapping *) fs, 1.0, 1.0 );
   tran2( "through_box_out", (AstMapping *) fs, 6.0, 6.0 );
   p[ 0 ] = 102.0;
   p[ 1 ] = 102.0;
   tran( "through_box_in", (AstMapping *) fs, 0, p );
   p[ 0 ] = 112.0;
   p[ 1 ] = 112.0;
   tran( "through_box_out", (AstMapping *) fs, 0, p );
   back = reread( (AstObject *) fs );
   tran2( "reread_in", (AstMapping *) back, 1.0, 1.0 );
   tran2( "reread_out", (AstMapping *) back, 6.0, 6.0 );

   /* A Box as the template of astFindFrame, against a FrameSet and a
      Frame. */
   printf( "== findframe_template\n" );
   fs = astFrameSet( astFrame( 2, "Domain=PIX" ), " " );
   astAddFrame( fs, AST__BASE, astZoomMap( 2, 0.5, " " ), astFrame( 2, "Domain=GRID" ) );
   astSetI( fs, "Current", 1 );
   box = square( astFrame( 2, "Domain=GRID" ), 0.0, 10.0 );
   cvt = astFindFrame( fs, box, " " );
   failed();
   frames( cvt );
   printf( "current %d\n", astGetI( fs, "Current" ) );
   if( cvt ) {
      tran2( "inside", (AstMapping *) cvt, 4.0, 4.0 );
      tran2( "outside", (AstMapping *) cvt, 100.0, 100.0 );
   }
   cvt = astFindFrame( astFrame( 2, "Domain=GRID" ), box, " " );
   failed();
   frames( cvt );
   if( cvt ) tran2( "frame_outside", (AstMapping *) cvt, 100.0, 100.0 );

   /* A Box as either Frame of astConvert, and as both. */
   printf( "== convert\n" );
   box = square( astFrame( 2, "Domain=GRID" ), 0.0, 10.0 );
   cvt = astConvert( astFrame( 2, "Domain=GRID" ), box, " " );
   failed();
   frames( cvt );
   if( cvt ) tran2( "to_box_outside", (AstMapping *) cvt, 100.0, 100.0 );
   cvt = astConvert( box, astFrame( 2, "Domain=GRID" ), " " );
   failed();
   frames( cvt );
   if( cvt ) tran2( "from_box_outside", (AstMapping *) cvt, 100.0, 100.0 );
   cvt = astConvert( box, square( astFrame( 2, "Domain=GRID" ), 50.0, 60.0 ), " " );
   failed();
   frames( cvt );
   if( cvt ) tran2( "box_to_box", (AstMapping *) cvt, 100.0, 100.0 );

   /* Negated set after use. */
   printf( "== negated\n" );
   box = square( astFrame( 2, " " ), 0.0, 10.0 );
   tran2( "inside", (AstMapping *) box, 5.0, 5.0 );
   tran2( "outside", (AstMapping *) box, 20.0, 5.0 );
   bounds( "bounds", (AstRegion *) box );
   points( (AstRegion *) box );
   unc( (AstRegion *) box );
   astSetI( box, "Negated", 1 );
   failed();
   tran2( "neg_inside", (AstMapping *) box, 5.0, 5.0 );
   tran2( "neg_outside", (AstMapping *) box, 20.0, 5.0 );
   tran2( "neg_boundary", (AstMapping *) box, 10.0, 5.0 );
   bounds( "bounds", (AstRegion *) box );
   points( (AstRegion *) box );
   unc( (AstRegion *) box );

   /* Closed set after use. */
   printf( "== closed\n" );
   box = square( astFrame( 2, " " ), 0.0, 10.0 );
   tran2( "boundary", (AstMapping *) box, 10.0, 5.0 );
   tran2( "near", (AstMapping *) box, 10.0 - 1.0e-7, 5.0 );
   astSetI( box, "Closed", 0 );
   failed();
   tran2( "open_boundary", (AstMapping *) box, 10.0, 5.0 );
   tran2( "open_near", (AstMapping *) box, 10.0 - 1.0e-7, 5.0 );
   astSetI( box, "Negated", 1 );
   tran2( "open_neg_boundary", (AstMapping *) box, 10.0, 5.0 );
   astSetI( box, "Closed", 1 );
   tran2( "closed_neg_boundary", (AstMapping *) box, 10.0, 5.0 );

   /* MeshSize set after use, on a Box mapped by a non-linear MathMap. */
   printf( "== meshsize\n" );
   lb[ 0 ] = -1.0;
   lb[ 1 ] = 0.0;
   ub[ 0 ] = 1.0;
   ub[ 1 ] = 1.0;
   box = astBox( astFrame( 2, " " ), 1, lb, ub, NULL, " " );
   m = (AstMapping *) astMathMap( 2, 2, 2, fwd, 2, inv, " " );
   r = astMapRegion_( astMakePointer( box ), astMakePointer( m ),
                      astMakePointer( astFrame( 2, " " ) ), astGetStatusPtr );
   if( !failed() ) {
      r = astMakeId( r );
      printf( "meshsize %d\n", astGetI( r, "MeshSize" ) );
      bounds( "bounds", r );
      tran2( "inside", (AstMapping *) r, 0.5, 0.5 );
      tran2( "outside", (AstMapping *) r, 0.5, 0.01 );
      astSetI( r, "MeshSize", 20 );
      failed();
      printf( "meshsize %d\n", astGetI( r, "MeshSize" ) );
      bounds( "bounds", r );
      points( r );
      unc( r );
      astClear( r, "MeshSize" );
      bounds( "bounds", r );
   }

   /* System set after use on an Adaptive Box in a SpecFrame: the Box is
      remapped into the new system. */
   printf( "== system_adaptive\n" );
   lb[ 0 ] = 100.0;
   ub[ 0 ] = 110.0;
   box = astBox( astSpecFrame( "System=FREQ" ), 1, lb, ub, NULL, " " );
   tran1( "inside", (AstMapping *) box, 105.0 );
   tran1( "outside", (AstMapping *) box, 120.0 );
   bounds( "bounds", (AstRegion *) box );
   astSetC( box, "System", "WAVE" );
   failed();
   printf( "system %s unit %s\n", astGetC( box, "System" ), astGetC( box, "Unit(1)" ) );
   tran1( "inside", (AstMapping *) box, 2.8e7 );
   tran1( "outside", (AstMapping *) box, 2.5e7 );
   tran1( "old_inside", (AstMapping *) box, 105.0 );
   bounds( "bounds", (AstRegion *) box );
   points( (AstRegion *) box );
   unc( (AstRegion *) box );

   /* The same on a Box that is not Adaptive: both Frames change, so the
      Box keeps its values, now in the new system. */
   printf( "== system_not_adaptive\n" );
   box = astBox( astSpecFrame( "System=FREQ" ), 1, lb, ub, NULL, " " );
   astSetI( box, "Adaptive", 0 );
   tran1( "inside", (AstMapping *) box, 105.0 );
   bounds( "bounds", (AstRegion *) box );
   astSetC( box, "System", "WAVE" );
   failed();
   printf( "system %s unit %s\n", astGetC( box, "System" ), astGetC( box, "Unit(1)" ) );
   tran1( "inside", (AstMapping *) box, 105.0 );
   tran1( "outside", (AstMapping *) box, 2.8e7 );
   bounds( "bounds", (AstRegion *) box );
   points( (AstRegion *) box );
   unc( (AstRegion *) box );

   /* The uncertainty set after use, on a Box of zero width. */
   printf( "== setunc\n" );
   lb[ 0 ] = 0.0;
   lb[ 1 ] = 5.0;
   ub[ 0 ] = 10.0;
   ub[ 1 ] = 5.0;
   box = astBox( astFrame( 2, " " ), 1, lb, ub, NULL, " " );
   tran2( "near", (AstMapping *) box, 5.0, 5.0 + 2.0e-6 );
   tran2( "off", (AstMapping *) box, 5.0, 5.3 );
   unc( (AstRegion *) box );
   ubox = square( astFrame( 2, " " ), -0.5, 0.5 );
   astSetUnc( box, ubox );
   failed();
   tran2( "near", (AstMapping *) box, 5.0, 5.0 + 2.0e-6 );
   tran2( "off", (AstMapping *) box, 5.0, 5.3 );
   tran2( "far", (AstMapping *) box, 5.0, 5.6 );
   bounds( "bounds", (AstRegion *) box );
   points( (AstRegion *) box );
   unc( (AstRegion *) box );

   astEnd;
   return 0;
}
