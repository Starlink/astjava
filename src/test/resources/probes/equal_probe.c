/* equal_probe -- astEqual in the pinned C library for the comparisons of the
 * Rust tests tests/test_equal.rs, test_matrixmap.rs, test_permmap.rs,
 * test_cmpmap.rs, test_wcsmap.rs, test_polymap.rs, test_specmap.rs,
 * test_specframe.rs, test_timemap.rs, test_frame_equality.rs,
 * test_timeframe.rs and test_fluxframe.rs, and for comparisons where C's
 * Equal and a comparison of native text disagree: an inverted ZoomMap
 * against one of the reciprocal factor, Mappings differing only in ID or
 * Ident, MathMaps differing only in their last opcode, CmpMaps that share a
 * component (Equal leaves the component with the first CmpMap's Invert
 * value), CmpMaps with Ident set (compared through copies of their
 * components), FrameSets whose Frames differ only in Title, ZoomMaps whose
 * Zoom attribute has been cleared, an inverted FrameSet, and KeyMaps.
 *
 * Each case starts with a line "== <name>", followed by "equal 0" or
 * "equal 1" for each comparison and any further values the case prints;
 * error reports, which the error module writes to standard error, are merged
 * in order (stdout unbuffered). Built against the C library at 3d3f255f with
 * its static libraries:
 *   cc -I<build> -o equal_probe equal_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./equal_probe > equal_probe.out 2>&1. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

static void head( const char *name ) {
   printf( "== %s\n", name );
}

/* Prints the result of one comparison, or the status of a failed one. */
static void eq( void *a, void *b ) {
   int r = astEqual( a, b );
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return;
   }
   printf( "equal %d\n", r ? 1 : 0 );
}

static void *inv( void *m ) {
   astInvert( m );
   return m;
}

static AstZoomMap *zoom( int n, double f ) {
   return astZoomMap( n, f, " " );
}

static AstShiftMap *shift2( double a, double b ) {
   double s[] = { a, b };
   return astShiftMap( 2, s, " " );
}

/* A WinMap of shifts and scales, as Rust's WinMap::new makes it. */
static AstWinMap *winss( int n, const double *shift, const double *scale ) {
   double ina[ 3 ], inb[ 3 ], outa[ 3 ], outb[ 3 ];
   for( int i = 0; i < n; i++ ) {
      ina[ i ] = 0.0;
      inb[ i ] = 1.0;
      outa[ i ] = shift[ i ];
      outb[ i ] = shift[ i ] + scale[ i ];
   }
   return astWinMap( n, ina, inb, outa, outb, " " );
}

static AstWinMap *wincorners( double ina, double inb, double outa, double outb ) {
   return astWinMap( 1, &ina, &inb, &outa, &outb, " " );
}

static AstPermMap *perm1( int in, int out, int ncon, const double *con ) {
   int inperm[] = { in };
   int outperm[] = { out };
   return astPermMap( 1, inperm, 1, outperm, ncon ? con : NULL, " " );
}

static AstMathMap *mathmap( const char *f, const char *i ) {
   const char *fwd[] = { f };
   const char *invf[] = { i };
   return astMathMap( 1, 1, 1, fwd, 1, invf, " " );
}

static AstWcsMap *sin_pv( int n, const int *m, const double *v ) {
   AstWcsMap *w = astWcsMap( 2, AST__SIN, 1, 2, " " );
   char attr[ 20 ];
   for( int i = 0; i < n; i++ ) {
      sprintf( attr, "PV2_%d", m[ i ] );
      astSetD( w, attr, v[ i ] );
   }
   return w;
}

static AstObject *roundtrip( AstObject *o ) {
   char *s = astToString( o );
   AstObject *back = astFromString( s );
   s = astFree( s );
   return back;
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* tests/test_equal.rs */
   head( "float_tolerance_within" );
   eq( zoom( 2, 1.0 ), zoom( 2, 1.0 + 1.0e-14 ) );
   head( "float_tolerance_outside" );
   eq( zoom( 2, 1.0 ), zoom( 2, 1.1 ) );
   {
      double bad[] = { AST__BAD };
      double c42[] = { 42.0 };
      double bad0[] = { AST__BAD, 0.0 };
      double c042[] = { 0.0, 42.0 };
      head( "bad_value_comparison" );
      eq( perm1( -1, -1, 1, bad ), perm1( -1, -1, 1, bad ) );
      head( "bad_vs_non_bad_same_index_is_not_equal" );
      eq( perm1( -1, -1, 1, bad ), perm1( -1, -1, 1, c42 ) );
      head( "bad_vs_non_bad_different_index_is_not_equal" );
      eq( perm1( -1, -1, 2, bad0 ), perm1( -2, -2, 2, c042 ) );
   }
   {
      AstUnitMap *a = astUnitMap( 3, " " );
      head( "unitmap_identity" );
      eq( a, a );
      head( "unitmap_same_ncoord" );
      eq( a, astUnitMap( 3, " " ) );
      head( "unitmap_different_ncoord" );
      eq( a, astUnitMap( 2, " " ) );
   }
   {
      AstZoomMap *a = zoom( 2, 3.5 );
      head( "zoommap_identity" );
      eq( a, a );
      head( "zoommap_same_values" );
      eq( a, zoom( 2, 3.5 ) );
      head( "zoommap_different_factor" );
      eq( a, zoom( 2, 4.0 ) );
      head( "zoommap_different_ncoord" );
      eq( a, zoom( 3, 3.5 ) );
      head( "zoommap_invert_aware" );
      eq( inv( zoom( 2, 2.0 ) ), zoom( 2, 0.5 ) );
      head( "zoommap_invert_aware_mismatch" );
      eq( inv( zoom( 2, 3.0 ) ), zoom( 2, 0.5 ) );
   }
   {
      double s3[] = { 1.0, 2.0, 3.0 };
      AstShiftMap *a = astShiftMap( 3, s3, " " );
      head( "shiftmap_identity" );
      eq( a, a );
      head( "shiftmap_same_values" );
      eq( shift2( 1.0, 2.0 ), shift2( 1.0, 2.0 ) );
      head( "shiftmap_different_shifts" );
      eq( shift2( 1.0, 2.0 ), shift2( 1.0, 3.0 ) );
      head( "shiftmap_different_length" );
      eq( shift2( 1.0, 2.0 ), a );
      head( "shiftmap_invert_aware" );
      eq( inv( shift2( 1.0, 2.0 ) ), shift2( -1.0, -2.0 ) );
      head( "shiftmap_invert_aware_mismatch" );
      eq( inv( shift2( 1.0, 2.0 ) ), shift2( 1.0, 2.0 ) );
   }
   {
      double m23[] = { 2.0, 0.0, 0.0, 3.0 };
      double m24[] = { 2.0, 0.0, 0.0, 4.0 };
      head( "matrixmap_same" );
      eq( astMatrixMap( 2, 2, 0, m23, " " ), astMatrixMap( 2, 2, 0, m23, " " ) );
      head( "matrixmap_different_element" );
      eq( astMatrixMap( 2, 2, 0, m23, " " ), astMatrixMap( 2, 2, 0, m24, " " ) );
      head( "matrixmap_inverted_not_equal" );
      eq( inv( astMatrixMap( 2, 2, 0, m23, " " ) ), astMatrixMap( 2, 2, 0, m23, " " ) );
   }
   {
      int p12[] = { 1, 2 };
      int p21[] = { 2, 1 };
      head( "permmap_same" );
      eq( astPermMap( 2, p12, 2, p21, NULL, " " ), astPermMap( 2, p12, 2, p21, NULL, " " ) );
      head( "permmap_different_perm" );
      eq( astPermMap( 2, p12, 2, p21, NULL, " " ), astPermMap( 2, p12, 2, p12, NULL, " " ) );
      head( "permmap_equal_cross_inversion" );
      eq( astPermMap( 2, p21, 2, p21, NULL, " " ), inv( astPermMap( 2, p21, 2, p21, NULL, " " ) ) );
      int p231[] = { 2, 3, 1 };
      int p312[] = { 3, 1, 2 };
      AstPermMap *pm3 = astPermMap( 3, p231, 3, p312, NULL, " " );
      eq( inv( astCopy( pm3 ) ), astPermMap( 3, p312, 3, p231, NULL, " " ) );
   }
   {
      double a12[] = { 1.0, 2.0 };
      double b34[] = { 3.0, 4.0 };
      double b35[] = { 3.0, 5.0 };
      head( "winmap_same" );
      eq( winss( 2, a12, b34 ), winss( 2, a12, b34 ) );
      head( "winmap_different_scale" );
      eq( winss( 2, a12, b34 ), winss( 2, a12, b35 ) );
      head( "winmap_inverted_not_equal" );
      eq( inv( winss( 2, a12, b34 ) ), winss( 2, a12, b34 ) );
      double s2[] = { 2.0 };
      double c3[] = { 3.0 };
      double sm[] = { -2.0 / 3.0 };
      double c13[] = { 1.0 / 3.0 };
      head( "winmap_equal_cross_inversion" );
      eq( inv( winss( 1, s2, c3 ) ), winss( 1, sm, c13 ) );
   }
   head( "cmpmap_series_same" );
   eq( astCmpMap( zoom( 2, 3.0 ), shift2( 1.0, 2.0 ), 1, " " ),
       astCmpMap( zoom( 2, 3.0 ), shift2( 1.0, 2.0 ), 1, " " ) );
   head( "cmpmap_different_component" );
   eq( astCmpMap( zoom( 2, 3.0 ), shift2( 1.0, 2.0 ), 1, " " ),
       astCmpMap( zoom( 2, 4.0 ), shift2( 1.0, 2.0 ), 1, " " ) );
   head( "cmpmap_different_kind" );
   eq( astCmpMap( astUnitMap( 2, " " ), astUnitMap( 2, " " ), 1, " " ),
       astCmpMap( astUnitMap( 2, " " ), astUnitMap( 2, " " ), 0, " " ) );
   {
      double c1[] = { 2.0, 1.0, 1.0, 0.0, 3.0, 2.0, 0.0, 1.0 };
      double ca[] = { 2.0, 1.0, 1.0 };
      double cb[] = { 3.0, 1.0, 1.0 };
      head( "polymap_same" );
      eq( astPolyMap( 2, 2, 2, c1, 0, NULL, " " ), astPolyMap( 2, 2, 2, c1, 0, NULL, " " ) );
      head( "polymap_different_coeff" );
      eq( astPolyMap( 1, 1, 1, ca, 0, NULL, " " ), astPolyMap( 1, 1, 1, cb, 0, NULL, " " ) );
   }
   {
      double lut[] = { 0.0, 1.0, 4.0, 9.0 };
      double lut16[] = { 0.0, 1.0, 4.0, 16.0 };
      head( "lutmap_same" );
      eq( astLutMap( 4, lut, 0.0, 1.0, " " ), astLutMap( 4, lut, 0.0, 1.0, " " ) );
      head( "lutmap_different_values" );
      eq( astLutMap( 4, lut, 0.0, 1.0, " " ), astLutMap( 4, lut16, 0.0, 1.0, " " ) );
      head( "lutmap_different_start" );
      eq( astLutMap( 2, lut, 0.0, 1.0, " " ), astLutMap( 2, lut, 1.0, 1.0, " " ) );
   }
   head( "wcsmap_same" );
   eq( astWcsMap( 2, AST__TAN, 1, 2, " " ), astWcsMap( 2, AST__TAN, 1, 2, " " ) );
   head( "wcsmap_different_proj" );
   eq( astWcsMap( 2, AST__TAN, 1, 2, " " ), astWcsMap( 2, AST__SIN, 1, 2, " " ) );
   head( "wcsmap_different_axes" );
   eq( astWcsMap( 3, AST__TAN, 1, 2, " " ), astWcsMap( 3, AST__TAN, 1, 3, " " ) );
   {
      double prec1[] = { 50000.0, 51000.0 };
      double prec2[] = { 50000.0, 52000.0 };
      AstSlaMap *a = astSlaMap( 0, " " );
      AstSlaMap *b = astSlaMap( 0, " " );
      astSlaAdd( a, "EQGAL", 0, NULL );
      astSlaAdd( b, "EQGAL", 0, NULL );
      head( "slamap_same" );
      eq( a, b );
      a = astSlaMap( 0, " " );
      b = astSlaMap( 0, " " );
      astSlaAdd( a, "EQGAL", 0, NULL );
      astSlaAdd( b, "GALEQ", 0, NULL );
      head( "slamap_different_type" );
      eq( a, b );
      a = astSlaMap( 0, " " );
      b = astSlaMap( 0, " " );
      astSlaAdd( a, "PREC", 2, prec1 );
      astSlaAdd( b, "PREC", 2, prec2 );
      head( "slamap_different_args" );
      eq( a, b );
   }
   head( "sphmap_same" );
   eq( astSphMap( " " ), astSphMap( " " ) );
   head( "sphmap_different_polar_long" );
   eq( astSphMap( "PolarLong=1.5" ), astSphMap( " " ) );
   head( "sphmap_different_unit_radius" );
   eq( astSphMap( "UnitRadius=1" ), astSphMap( " " ) );
   head( "sphmap_inverted" );
   eq( inv( astSphMap( " " ) ), astSphMap( " " ) );
   {
      AstShiftMap *a = shift2( 1.0, 2.0 );
      AstZoomMap *b = zoom( 2, 3.0 );
      AstShiftMap *c = shift2( 4.0, 5.0 );
      AstCmpMap *abc1 = astCmpMap( astCopy( a ), astCmpMap( astCopy( b ), astCopy( c ), 1, " " ), 1, " " );
      AstCmpMap *abc2 = astCmpMap( astCmpMap( astCopy( a ), astCopy( b ), 1, " " ), astCopy( c ), 1, " " );
      head( "cmpmap_equal_different_tree_structure" );
      eq( abc1, abc2 );
   }
   head( "cross_type_zoom_vs_shift" );
   eq( zoom( 2, 1.0 ), shift2( 0.0, 0.0 ) );
   head( "cross_type_unit_vs_zoom" );
   eq( astUnitMap( 2, " " ), zoom( 2, 1.0 ) );
   {
      double z[] = { 0.0 };
      double o[] = { 1.0 };
      head( "cross_type_winmap_vs_shiftmap" );
      eq( winss( 1, z, o ), astShiftMap( 1, z, " " ) );
   }
   head( "basicframe_same" );
   eq( astFrame( 2, "Title=test,Domain=PIXEL" ), astFrame( 2, "Title=test,Domain=PIXEL" ) );
   head( "basicframe_different_naxes" );
   eq( astFrame( 2, " " ), astFrame( 3, " " ) );
   head( "basicframe_different_title_is_equal" );
   eq( astFrame( 2, "Title=A" ), astFrame( 2, "Title=B" ) );
   head( "basicframe_different_domain" );
   eq( astFrame( 2, "Domain=X" ), astFrame( 2, "Domain=Y" ) );
   head( "basicframe_different_epoch_is_equal" );
   eq( astFrame( 2, "Epoch=MJD 50000" ), astFrame( 2, "Epoch=MJD 51000" ) );
   head( "basicframe_epoch_set_vs_unset_is_equal" );
   eq( astFrame( 2, "Epoch=MJD 50000" ), astFrame( 2, " " ) );
   head( "basicframe_different_labels_is_equal" );
   eq( astFrame( 2, "Label(1)=X" ), astFrame( 2, "Label(1)=Y" ) );
   head( "cmpframe_same" );
   eq( astCmpFrame( astFrame( 1, " " ), astFrame( 1, " " ), " " ),
       astCmpFrame( astFrame( 1, " " ), astFrame( 1, " " ), " " ) );
   head( "cmpframe_different_child" );
   eq( astCmpFrame( astFrame( 1, "Domain=A" ), astFrame( 1, " " ), " " ),
       astCmpFrame( astFrame( 1, "Domain=B" ), astFrame( 1, " " ), " " ) );
   head( "skyframe_same" );
   eq( astSkyFrame( "System=FK5" ), astSkyFrame( "System=FK5" ) );
   head( "skyframe_different_system" );
   eq( astSkyFrame( "System=FK5" ), astSkyFrame( "System=Galactic" ) );
   head( "skyframe_different_equinox" );
   eq( astSkyFrame( "System=FK5,Equinox=MJD 50000" ), astSkyFrame( "System=FK5,Equinox=MJD 51000" ) );
   head( "skyframe_equinox_set_vs_unset_default_is_equal" );
   eq( astSkyFrame( "System=FK5,Equinox=MJD 51544.5" ), astSkyFrame( "System=FK5" ) );
   head( "skyframe_different_epoch" );
   eq( astSkyFrame( "System=FK5,Epoch=MJD 50000" ), astSkyFrame( "System=FK5,Epoch=MJD 51000" ) );
   head( "skyframe_sky_ref_is_origin_without_sky_ref_is_equal" );
   eq( astSkyFrame( "System=FK5,SkyRefIs=Origin" ), astSkyFrame( "System=FK5" ) );
   {
      AstSkyFrame *a = astSkyFrame( "System=FK5,SkyRefIs=Origin" );
      astSetD( a, "SkyRef(1)", 1.0 );
      astSetD( a, "SkyRef(2)", 0.5 );
      head( "skyframe_offset_origin_with_sky_ref_is_not_equal" );
      eq( a, astSkyFrame( "System=FK5" ) );
      a = astSkyFrame( "System=FK5" );
      astSetD( a, "SkyRef(1)", 1.0 );
      astSetD( a, "SkyRef(2)", 0.5 );
      head( "skyframe_sky_ref_without_sky_ref_is_is_equal" );
      eq( a, astSkyFrame( "System=FK5" ) );
   }
   head( "skyframe_different_neglon_is_equal" );
   eq( astSkyFrame( "System=FK5,NegLon=1" ), astSkyFrame( "System=FK5" ) );
   head( "skyframe_different_title_is_equal" );
   eq( astSkyFrame( "System=FK5,Title=A" ), astSkyFrame( "System=FK5,Title=B" ) );
   head( "cross_type_skyframe_vs_basicframe" );
   eq( astSkyFrame( "System=FK5" ), astFrame( 2, " " ) );
   head( "cross_type_basicframe_vs_skyframe" );
   eq( astFrame( 2, " " ), astSkyFrame( "System=FK5" ) );
   {
      AstFrameSet *a = astFrameSet( astFrame( 2, " " ), " " );
      astAddFrame( a, AST__BASE, zoom( 2, 2.0 ), astFrame( 2, "Title=current" ) );
      AstFrameSet *b = astFrameSet( astFrame( 2, " " ), " " );
      astAddFrame( b, AST__BASE, zoom( 2, 2.0 ), astFrame( 2, "Title=current" ) );
      head( "frameset_same" );
      eq( a, b );
      a = astFrameSet( astFrame( 2, " " ), " " );
      astAddFrame( a, AST__BASE, zoom( 2, 2.0 ), astFrame( 2, " " ) );
      b = astFrameSet( astFrame( 2, " " ), " " );
      astAddFrame( b, AST__BASE, zoom( 2, 3.0 ), astFrame( 2, " " ) );
      head( "frameset_different_mapping" );
      eq( a, b );
   }
   {
      double s[] = { 1.5, -2.7, 0.001 };
      AstObject *z = (AstObject *) zoom( 2, 3.14159 );
      AstObject *sm = (AstObject *) astShiftMap( 3, s, " " );
      AstObject *sky = (AstObject *) astSkyFrame( "System=FK5,Epoch=MJD 52000,Equinox=MJD 51544.5,"
                                                  "NegLon=1,Title=Test Sky" );
      head( "serialise_round_trip_zoommap" );
      eq( z, roundtrip( z ) );
      head( "serialise_round_trip_shiftmap" );
      eq( sm, roundtrip( sm ) );
      head( "serialise_round_trip_skyframe" );
      eq( sky, roundtrip( sky ) );
   }
   {
      double m[] = { 1.0, 2.0, 3.0, 4.0 };
      double minv[] = { -2.0, 1.0, 1.5, -0.5 };
      AstMatrixMap *mminv = inv( astMatrixMap( 2, 2, 0, m, " " ) );
      head( "matrixmap_equal_cross_inversion" );
      eq( mminv, astMatrixMap( 2, 2, 0, minv, " " ) );
      eq( astMatrixMap( 2, 2, 0, m, " " ), mminv );
   }

   /* tests/test_matrixmap.rs */
   {
      double m[] = { 1.0, 2.0, 3.0, 4.0, 5.0, 6.0 };
      double m7[] = { 1.0, 2.0, 3.0, 4.0, 5.0, 7.0 };
      AstMatrixMap *a = inv( astMatrixMap( 3, 2, 0, m, " " ) );
      head( "an_inverted_non_square_matrixmap_equals_its_copy" );
      eq( a, astCopy( a ) );
      eq( a, inv( astMatrixMap( 3, 2, 0, m7, " " ) ) );
   }

   /* tests/test_permmap.rs */
   {
      double c5[] = { 5.0 };
      double c7[] = { 7.0 };
      double c90[] = { 9.0, 0.0 };
      double c09[] = { 0.0, 9.0 };
      double c04[] = { 0.0, 4.0 };
      double c1[] = { 1.0 };
      double c2[] = { 2.0 };
      head( "perm_equal_same_constant_index_differing_values" );
      eq( perm1( -1, 1, 1, c5 ), perm1( -1, 1, 1, c7 ) );
      head( "perm_equal_same_constant_index_same_value" );
      eq( perm1( -1, 1, 1, c5 ), perm1( -1, 1, 1, c5 ) );
      head( "perm_equal_different_index_same_referenced_value" );
      eq( perm1( -1, 1, 2, c90 ), perm1( -2, 1, 2, c09 ) );
      head( "perm_equal_different_index_different_referenced_value" );
      eq( perm1( -1, 1, 2, c90 ), perm1( -2, 1, 2, c04 ) );
      head( "perm_equal_ignores_unreferenced_surplus_constants" );
      eq( perm1( 1, 1, 1, c1 ), perm1( 1, 1, 1, c2 ) );
   }

   /* tests/test_cmpmap.rs */
   {
      double one[] = { 1.0 };
      head( "cmpmap_equality_compares_components_as_applied" );
      eq( astCmpMap( inv( astUnitMap( 1, " " ) ), zoom( 1, 2.0 ), 1, " " ),
          astCmpMap( astUnitMap( 1, " " ), zoom( 1, 2.0 ), 1, " " ) );
      eq( astCmpMap( inv( zoom( 1, 2.0 ) ), astShiftMap( 1, one, " " ), 1, " " ),
          astCmpMap( zoom( 1, 0.5 ), astShiftMap( 1, one, " " ), 1, " " ) );
      eq( astCmpMap( zoom( 1, 2.0 ), astShiftMap( 1, one, " " ), 1, " " ),
          astCmpMap( zoom( 1, 0.5 ), astShiftMap( 1, one, " " ), 1, " " ) );
   }

   /* tests/test_wcsmap.rs */
   {
      AstWcsMap *w1 = astWcsMap( 2, AST__ZPN, 1, 2, " " );
      AstWcsMap *w2 = astWcsMap( 2, AST__ZPN, 1, 2, " " );
      head( "wcsmap_equal_different_params" );
      eq( w1, w2 );
      astSetD( w1, "PV2_1", 0.5 );
      astSetD( w2, "PV2_1", 0.7 );
      eq( w1, w2 );
      w1 = astWcsMap( 2, AST__ZPN, 1, 2, " " );
      w2 = astWcsMap( 2, AST__ZPN, 1, 2, " " );
      astSetD( w1, "PV2_1", 0.5 );
      astSetD( w2, "PV2_1", 0.5 );
      head( "wcsmap_equal_same_params" );
      eq( w1, w2 );
   }

   /* tests/test_polymap.rs */
   {
      double fwd[] = { 1.0, 1.0, 2.0 };
      double half[] = { 0.5, 1.0, 1.0 };
      double seven[] = { 0.7, 1.0, 1.0 };
      AstPolyMap *a = astPolyMap( 1, 1, 1, fwd, 1, half, " " );
      AstPolyMap *same = astPolyMap( 1, 1, 1, fwd, 1, half, " " );
      AstPolyMap *differ = astPolyMap( 1, 1, 1, fwd, 1, seven, " " );
      head( "polymap_equal_compares_the_inverse_coefficients" );
      eq( a, same );
      eq( same, a );
      eq( a, differ );
      eq( differ, a );
      AstMapping *s = astSimplify( astCmpMap( astPolyMap( 1, 1, 1, fwd, 1, half, " " ), inv( differ ), 1, " " ) );
      printf( "class %s\n", astGetC( s, "Class" ) );
      s = astSimplify( astCmpMap( astPolyMap( 1, 1, 1, fwd, 1, half, " " ),
                                  inv( astPolyMap( 1, 1, 1, fwd, 1, half, " " ) ), 1, " " ) );
      printf( "class %s\n", astGetC( s, "Class" ) );

      double wfwd[ 24 ];
      double winv[ 10 ];
      for( int io = 1; io <= 8; io++ ) {
         wfwd[ 3*( io - 1 ) ] = io;
         wfwd[ 3*( io - 1 ) + 1 ] = io;
         wfwd[ 3*( io - 1 ) + 2 ] = 1.0;
      }
      winv[ 0 ] = 1.0;
      winv[ 1 ] = 1.0;
      for( int i = 0; i < 8; i++ ) winv[ 2 + i ] = 1.0;
      AstPolyMap *wide = astPolyMap( 1, 8, 8, wfwd, 1, winv, " " );
      AstPolyMap *copy = astCopy( wide );
      head( "polymap_with_more_outputs_than_inputs_equals_its_copy" );
      eq( wide, copy );
      eq( copy, wide );
   }

   /* tests/test_specmap.rs */
   {
      double a1[] = { 1.0, 0.5 };
      double a2[] = { 2.0, 0.5 };
      AstSpecMap *s1 = astSpecMap( 1, 0, " " );
      astSpecAdd( s1, "FRTOWV", 0, NULL );
      astSpecAdd( s1, "LKF2HL", 2, a1 );
      AstSpecMap *s2 = astSpecMap( 1, 0, " " );
      astSpecAdd( s2, "FRTOWV", 0, NULL );
      astSpecAdd( s2, "LKF2HL", 2, a1 );
      AstSpecMap *s3 = astSpecMap( 1, 0, " " );
      astSpecAdd( s3, "FRTOWV", 0, NULL );
      astSpecAdd( s3, "LKF2HL", 2, a2 );
      head( "specmap_equality" );
      eq( s1, s2 );
      eq( s1, s3 );
   }

   /* tests/test_specframe.rs */
   head( "test_equal_same" );
   eq( astSpecFrame( "System=FREQ" ), astSpecFrame( "System=FREQ" ) );
   head( "test_not_equal_different_system" );
   eq( astSpecFrame( "System=FREQ" ), astSpecFrame( "System=WAVE" ) );
   head( "test_not_equal_different_sor" );
   eq( astSpecFrame( "System=FREQ,StdOfRest=Heliocentric" ), astSpecFrame( "System=FREQ,StdOfRest=LSRK" ) );

   /* tests/test_timemap.rs */
   {
      double mj[] = { 0.0, 0.0 };
      double u32[] = { 0.0, 32.0 };
      double u33[] = { 0.0, 33.0 };
      AstTimeMap *t1 = astTimeMap( 0, " " );
      astTimeAdd( t1, "MJDTOJD", 2, mj );
      astTimeAdd( t1, "UTCTOTAI", 2, u32 );
      AstTimeMap *t2 = astTimeMap( 0, " " );
      astTimeAdd( t2, "MJDTOJD", 2, mj );
      astTimeAdd( t2, "UTCTOTAI", 2, u32 );
      AstTimeMap *t3 = astTimeMap( 0, " " );
      astTimeAdd( t3, "MJDTOJD", 2, mj );
      astTimeAdd( t3, "UTCTOTAI", 2, u33 );
      head( "timemap_equality" );
      eq( t1, t2 );
      eq( t1, t3 );
   }

   /* tests/test_frame_equality.rs */
   head( "basic_frames_differing_only_in_cosmetics_are_equal" );
   eq( astFrame( 2, "Title=First frame,Label(1)=X,Symbol(1)=x,Format(1)=3" ),
       astFrame( 2, "Title=Second frame,Label(1)=Different label,Symbol(1)=q,Format(1)=7" ) );
   head( "basic_frames_differing_in_domain_are_not_equal" );
   eq( astFrame( 2, "Domain=PIXEL" ), astFrame( 2, "Domain=GRID" ) );
   head( "skyframes_differing_only_in_title_are_equal" );
   eq( astSkyFrame( "System=ICRS" ), astSkyFrame( "System=ICRS,Title=A different description" ) );
   head( "skyframe_explicit_default_domain_equals_unset_domain" );
   eq( astSkyFrame( "System=ICRS" ), astSkyFrame( "System=ICRS,Domain=SKY" ) );
   head( "skyframes_differing_in_system_are_not_equal" );
   eq( astSkyFrame( "System=ICRS" ), astSkyFrame( "System=Galactic" ) );

   /* tests/test_timeframe.rs */
   head( "timeframe_test_equal" );
   eq( astTimeFrame( "System=MJD" ), astTimeFrame( "System=MJD" ) );
   eq( astTimeFrame( "System=MJD" ), astTimeFrame( "System=JD" ) );

   /* tests/test_fluxframe.rs */
   head( "test_fluxframe_equal_same" );
   eq( astFluxFrame( AST__BAD, NULL, " " ), astFluxFrame( AST__BAD, NULL, " " ) );
   head( "test_fluxframe_equal_different_system" );
   eq( astFluxFrame( AST__BAD, NULL, " " ), astFluxFrame( AST__BAD, NULL, "System=FLXDNW" ) );
   head( "test_fluxframe_equal_different_specval_is_equal" );
   eq( astFluxFrame( AST__BAD, NULL, "SpecVal=1.0E9" ), astFluxFrame( AST__BAD, NULL, "SpecVal=2.0E9" ) );

   /* Comparisons where C's Equal and a comparison of native text disagree. */
   head( "zoommap_inverted_vs_reciprocal" );
   eq( inv( zoom( 1, 2.0 ) ), zoom( 1, 0.5 ) );
   head( "differing_id" );
   eq( astZoomMap( 1, 2.0, "ID=a" ), astZoomMap( 1, 2.0, "ID=b" ) );
   head( "differing_ident" );
   eq( astZoomMap( 1, 2.0, "Ident=a" ), astZoomMap( 1, 2.0, "Ident=b" ) );
   head( "mathmap_last_opcode" );
   eq( mathmap( "y = x + 1", "x = y - 1" ), mathmap( "y = x - 1", "x = y + 1" ) );
   eq( mathmap( "y = x * 2", "x = y / 2" ), mathmap( "y = x / 2", "x = y * 2" ) );
   eq( mathmap( "y = x + 1", "x = y - 1" ), mathmap( "y = x + 2", "x = y - 2" ) );
   head( "cmpmap_shared_component" );
   {
      /* The same ZoomMap is the first component of both CmpMaps, recorded
         not inverted in the first and inverted in the second. */
      AstZoomMap *z = zoom( 1, 2.0 );
      AstUnitMap *u = astUnitMap( 1, " " );
      AstCmpMap *a = astCmpMap( z, u, 1, " " );
      astInvert( z );
      AstCmpMap *b = astCmpMap( z, u, 1, " " );
      printf( "invert before %d set %d\n", astGetI( z, "Invert" ), astTest( z, "Invert" ) );
      eq( a, b );
      printf( "invert after %d set %d\n", astGetI( z, "Invert" ), astTest( z, "Invert" ) );
      eq( b, a );
      printf( "invert after reverse %d set %d\n", astGetI( z, "Invert" ), astTest( z, "Invert" ) );
   }
   head( "cmpmap_ident" );
   {
      /* CmpMaps with Ident set are expanded explicitly and their components
         compared through copies. */
      AstZoomMap *z = zoom( 1, 2.0 );
      AstUnitMap *u = astUnitMap( 1, " " );
      eq( astCmpMap( zoom( 1, 2.0 ), u, 1, "Ident=a" ), astCmpMap( zoom( 1, 2.0 ), u, 1, "Ident=b" ) );
      eq( astCmpMap( zoom( 1, 2.0 ), u, 1, "Ident=a" ), astCmpMap( zoom( 1, 3.0 ), u, 1, "Ident=b" ) );
      eq( astCmpMap( zoom( 1, 2.0 ), u, 1, "Ident=a" ), astCmpMap( zoom( 1, 2.0 ), u, 1, " " ) );
      AstCmpMap *a = astCmpMap( z, u, 1, "Ident=a" );
      astInvert( z );
      AstCmpMap *b = astCmpMap( z, u, 1, "Ident=b" );
      eq( a, b );
      printf( "invert after %d set %d\n", astGetI( z, "Invert" ), astTest( z, "Invert" ) );
      eq( inv( astCmpMap( zoom( 1, 2.0 ), zoom( 1, 3.0 ), 1, "Ident=a" ) ),
          astCmpMap( zoom( 1, 1.0 / 3.0 ), zoom( 1, 0.5 ), 1, "Ident=b" ) );
   }
   head( "frameset_frames_differ_in_title" );
   {
      AstFrameSet *a = astFrameSet( astFrame( 2, "Title=A" ), " " );
      astAddFrame( a, AST__BASE, zoom( 2, 2.0 ), astFrame( 2, "Title=B" ) );
      AstFrameSet *b = astFrameSet( astFrame( 2, "Title=C" ), " " );
      astAddFrame( b, AST__BASE, zoom( 2, 2.0 ), astFrame( 2, "Title=D" ) );
      eq( a, b );
      astSetI( b, "Current", 1 );
      eq( a, b );
      eq( a, astFrame( 2, " " ) );
      eq( astFrame( 2, " " ), a );
   }
   head( "zoommap_cleared_zoom" );
   {
      /* Equal compares the factors as stored: zero once Zoom is cleared. */
      AstZoomMap *a = zoom( 1, 2.0 );
      astClear( a, "Zoom" );
      AstZoomMap *b = zoom( 1, 2.0 );
      astClear( b, "Zoom" );
      eq( a, zoom( 1, 1.0 ) );
      eq( zoom( 1, 1.0 ), a );
      eq( a, inv( b ) );
      eq( zoom( 1, 3.0 ), inv( astCopy( a ) ) );
   }
   head( "frameset_inverted" );
   {
      AstFrameSet *a = astFrameSet( astFrame( 2, " " ), " " );
      astAddFrame( a, AST__BASE, zoom( 2, 2.0 ), astFrame( 2, " " ) );
      eq( a, inv( astCopy( a ) ) );
   }
   head( "object_classes" );
   eq( astKeyMap( " " ), astKeyMap( " " ) );
   {
      AstKeyMap *k1 = astKeyMap( " " );
      AstKeyMap *k2 = astKeyMap( " " );
      astMapPut0I( k1, "A", 1, NULL );
      astMapPut0I( k2, "B", 2, NULL );
      eq( k1, k2 );
      eq( k1, zoom( 1, 1.0 ) );
   }

   astEnd;
   return 0;
}
