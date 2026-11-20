/* remove_regions_extra_probe -- what astRemoveRegions makes of the
 * containers and nesting the remove_regions oracle does not cover, by the
 * pinned C library.
 *
 * A Region gives a copy of its current Frame (region.c RemoveRegions), and
 * a Mapping holding no Region gives itself (mapping.c, astClone). A
 * container replaces a component that comes back as a Frame with a
 * UnitMap: cmpmap.c and tranmap.c every such component, so also a plain
 * Frame or a FrameSet component that was already there when the other
 * component changed; switchmap.c (routes and both selectors), ratemap.c
 * and frameset.c only a component that changed, which includes a FrameSet
 * holding a Region, since astIsAFrame is true of a FrameSet. A CmpFrame
 * (cmpframe.c) and a FrameSet (frameset.c) are copies whose unchanged
 * components are the original's own Objects, so that a change made through
 * one is seen in the other. A SelectorMap installs no
 * RemoveRegions, so its Regions stay.
 *
 * Each case starts with a line "== <name>". "in" lines give the dump of the
 * Mapping, "out" lines the dump of what astRemoveRegions returns, and a
 * "same" line whether that is the given Object itself (astSame). For a
 * CmpFrame, a "component" line gives whether each component of the result
 * is the original's own (astDecompose of each, then astSame); for a
 * FrameSet, a "frame" line whether each Frame is (astGetFrame). An "orig"
 * line gives a line of the dump of the given Mapping after an attribute
 * was set on the result. A "status" line reports a failed call. The cases
 * whose names start with "selectormap" are kept for SelectorMap.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o remove_regions_extra_probe
 *      remove_regions_extra_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./remove_regions_extra_probe > remove_regions_extra_probe.txt
 * 2>&1; the output, with trailing white space removed from every line, is
 * stored as the "output" string of remove_regions_extra_probe.json, since
 * the dumps hold C's item names, which the spelling check rejects. */
#include <stdio.h>
#include "ast.h"

static const char *prefix;

static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

static void sink( const char *line ) {
   printf( "%s %s\n", prefix, line );
}

static void dump( const char *pre, AstObject *obj ) {
   AstChannel *ch;
   if( failed() ) return;
   prefix = pre;
   ch = astChannel( NULL, sink, " " );
   astWrite( ch, obj );
   ch = astAnnul( ch );
   failed();
}

/* Writes the case's header, the given Mapping and what astRemoveRegions
   returns, and returns that (or NULL after an error). */
static AstMapping *run( const char *name, AstMapping *map ) {
   AstMapping *result;
   printf( "== %s\n", name );
   if( failed() ) return NULL;
   dump( "in", (AstObject *) map );
   result = astRemoveRegions( map );
   if( failed() ) return NULL;
   dump( "out", (AstObject *) result );
   printf( "same %d\n", astSame( result, map ) );
   failed();
   return result;
}

static void emit( const char *name, AstMapping *map ) {
   run( name, map );
}

/* Whether each component of the CmpFrame result is the original's own. */
static void components( AstMapping *result, AstMapping *map ) {
   AstMapping *r1, *r2, *m1, *m2;
   int series, inv1, inv2;
   if( !result || failed() ) return;
   astDecompose( result, &r1, &r2, &series, &inv1, &inv2 );
   astDecompose( map, &m1, &m2, &series, &inv1, &inv2 );
   if( failed() ) return;
   printf( "component 1 same %d\n", astSame( r1, m1 ) );
   printf( "component 2 same %d\n", astSame( r2, m2 ) );
   failed();
}

/* Whether each Frame of the FrameSet result is the original's own. */
static void frames( AstMapping *result, AstMapping *map ) {
   int i, n;
   if( !result || failed() ) return;
   n = astGetI( map, "Nframe" );
   for( i = 1; i <= n; i++ ) {
      printf( "frame %d same %d\n", i,
              astSame( astGetFrame( (AstFrameSet *) result, i ),
                       astGetFrame( (AstFrameSet *) map, i ) ) );
   }
   failed();
}

static AstFrame *frame( int naxes, const char *domain ) {
   AstFrame *f = astFrame( naxes, " " );
   if( domain ) astSetC( f, "Domain", domain );
   return f;
}

static AstBox *box2( double lo, double hi ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = lb[ 1 ] = lo;
   ub[ 0 ] = ub[ 1 ] = hi;
   return astBox( astFrame( 2, " " ), 1, lb, ub, NULL, " " );
}

static AstCircle *circle2( void ) {
   double centre[] = { 1.0, 2.0 }, radius[] = { 1.0 };
   return astCircle( astFrame( 2, " " ), 1, centre, radius, NULL, " " );
}

/* A PermMap from two axes to one, giving the first input. */
static AstPermMap *pick_first( void ) {
   int inperm[ 2 ] = { 1, 0 };
   int outperm[ 1 ] = { 1 };
   return astPermMap( 2, inperm, 1, outperm, NULL, " " );
}

static AstSwitchMap *switch2( AstMapping *fs, AstMapping *r1, AstMapping *r2 ) {
   AstMapping *rm[ 2 ];
   rm[ 0 ] = r1;
   rm[ 1 ] = r2;
   return astSwitchMap( fs, NULL, 2, (void **) rm, " " );
}

/* A FrameSet from PIX to SKY whose Mapping is a Box. */
static AstFrameSet *frameset_with_region( void ) {
   AstFrameSet *fs = astFrameSet( frame( 2, "PIX" ), " " );
   astAddFrame( fs, AST__BASE, box2( 0.0, 10.0 ), frame( 2, "SKY" ) );
   return fs;
}

int main( void ) {
   AstCmpFrame *cf;
   AstFrameSet *fs;
   AstMapping *result, *rm[ 2 ];
   AstPermMap *inv;
   AstRegion *sel[ 2 ];
   double lb1[ 1 ] = { 0.0 }, ub1[ 1 ] = { 1.0 };
   double lb10[ 1 ] = { 0.0 }, ub10[ 1 ] = { 10.0 };
   double shift[ 2 ] = { 100.0, 100.0 };
   int status = 0;
   astWatch( &status );

   emit( "region_alone", (AstMapping *) box2( 0.0, 1.0 ) );

   emit( "frame_alone", (AstMapping *) frame( 2, NULL ) );

   emit( "no_regions_cmpmap",
         (AstMapping *) astCmpMap( astZoomMap( 2, 3.0, " " ),
                                   astShiftMap( 2, shift, " " ), 1, " " ) );

   fs = astFrameSet( frame( 2, "PIX" ), " " );
   astAddFrame( fs, AST__BASE, astZoomMap( 2, 2.0, " " ), frame( 2, "SKY" ) );
   result = run( "no_regions_frameset", (AstMapping *) fs );
   frames( result, (AstMapping *) fs );

   emit( "cmpmap_frame_beside_region",
         (AstMapping *) astCmpMap( frame( 1, NULL ),
                                   astCmpMap( box2( 0.0, 1.0 ), astZoomMap( 2, 3.0, " " ), 1, " " ),
                                   0, " " ) );

   emit( "tranmap_frame_beside_region",
         (AstMapping *) astTranMap( frame( 2, NULL ),
                                    astCmpMap( box2( 0.0, 1.0 ), astZoomMap( 2, 3.0, " " ), 1, " " ),
                                    " " ) );

   fs = astFrameSet( frame( 2, "PIX" ), " " );
   astAddFrame( fs, AST__BASE, astZoomMap( 2, 2.0, " " ), frame( 2, "SKY" ) );
   emit( "tranmap_frameset_beside_region", (AstMapping *) astTranMap( fs, box2( 0.0, 1.0 ), " " ) );

   emit( "switchmap_region_route",
         (AstMapping *) switch2( (AstMapping *) astCmpMap( box2( 0.0, 10.0 ), pick_first(), 1, " " ),
                                 (AstMapping *) box2( 0.0, 1.0 ),
                                 (AstMapping *) astZoomMap( 2, 3.0, " " ) ) );

   emit( "switchmap_frame_route",
         (AstMapping *) switch2( (AstMapping *) pick_first(), (AstMapping *) box2( 0.0, 1.0 ),
                                 (AstMapping *) frame( 2, NULL ) ) );

   emit( "switchmap_no_regions",
         (AstMapping *) switch2( (AstMapping *) pick_first(), (AstMapping *) astZoomMap( 2, 2.0, " " ),
                                 (AstMapping *) astZoomMap( 2, 3.0, " " ) ) );

   inv = pick_first();
   astInvert( inv );
   rm[ 0 ] = (AstMapping *) astZoomMap( 2, 2.0, " " );
   rm[ 1 ] = (AstMapping *) astZoomMap( 2, 3.0, " " );
   emit( "switchmap_inverse_selector_region",
         (AstMapping *) astSwitchMap( NULL, astCmpMap( inv, box2( 0.0, 10.0 ), 1, " " ), 2,
                                      (void **) rm, " " ) );

   rm[ 0 ] = (AstMapping *) astZoomMap( 1, 2.0, " " );
   rm[ 1 ] = (AstMapping *) astZoomMap( 1, 3.0, " " );
   emit( "switchmap_inverse_selector_is_region",
         (AstMapping *) astSwitchMap( NULL, astInterval( frame( 1, NULL ), lb10, ub10, NULL, " " ), 2,
                                      (void **) rm, " " ) );

   emit( "switchmap_frameset_route",
         (AstMapping *) switch2( (AstMapping *) pick_first(), (AstMapping *) astZoomMap( 2, 2.0, " " ),
                                 (AstMapping *) frameset_with_region() ) );

   emit( "ratemap_frameset", (AstMapping *) astRateMap( frameset_with_region(), 1, 1, " " ) );

   fs = astFrameSet( frame( 2, "PIX" ), " " );
   astAddFrame( fs, AST__BASE, astZoomMap( 2, 2.0, " " ), box2( 0.0, 10.0 ) );
   astAddFrame( fs, AST__CURRENT, astShiftMap( 2, shift, " " ), frame( 2, "SKY" ) );
   result = run( "frameset_region_frame", (AstMapping *) fs );
   frames( result, (AstMapping *) fs );

   fs = astFrameSet( frame( 2, "PIX" ), " " );
   astAddFrame( fs, AST__BASE,
                astCmpMap( astZoomMap( 2, 2.0, " " ), circle2(), 1, " " ),
                frame( 2, "SKY" ) );
   result = run( "frameset_region_in_cmpmap", (AstMapping *) fs );
   frames( result, (AstMapping *) fs );

   fs = astFrameSet( frame( 2, "PIX" ), " " );
   astAddFrame( fs, AST__BASE, box2( 0.0, 10.0 ), frame( 2, "SKY" ) );
   result = run( "frameset_region_map", (AstMapping *) fs );
   frames( result, (AstMapping *) fs );

   fs = astFrameSet( frame( 2, "PIX" ), " " );
   astAddFrame( fs, AST__BASE, frame( 2, NULL ), box2( 0.0, 10.0 ) );
   result = run( "frameset_frame_map", (AstMapping *) fs );
   frames( result, (AstMapping *) fs );

   cf = astCmpFrame( box2( 0.0, 1.0 ), astInterval( frame( 1, NULL ), lb1, ub1, NULL, " " ), " " );
   result = run( "cmpframe_two_regions", (AstMapping *) cf );
   components( result, (AstMapping *) cf );

   cf = astCmpFrame( box2( 0.0, 1.0 ), frame( 1, NULL ), " " );
   result = run( "cmpframe_shared_component", (AstMapping *) cf );
   components( result, (AstMapping *) cf );
   if( result ) {
      astSetC( result, "Label(3)", "Shared" );
      dump( "orig", (AstObject *) cf );
   }

   cf = astCmpFrame( frame( 2, NULL ), frame( 1, NULL ), " " );
   result = run( "cmpframe_no_regions", (AstMapping *) cf );
   components( result, (AstMapping *) cf );

   sel[ 0 ] = (AstRegion *) box2( 0.0, 1.0 );
   sel[ 1 ] = (AstRegion *) box2( 2.0, 3.0 );
   emit( "selectormap_alone", (AstMapping *) astSelectorMap( 2, (void **) sel, AST__BAD, " " ) );

   emit( "selectormap_switchmap",
         (AstMapping *) switch2( (AstMapping *) astSelectorMap( 2, (void **) sel, AST__BAD, " " ),
                                 (AstMapping *) box2( 0.0, 1.0 ),
                                 (AstMapping *) astZoomMap( 2, 3.0, " " ) ) );

   return 0;
}
