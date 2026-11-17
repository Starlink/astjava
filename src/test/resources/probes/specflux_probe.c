/* specflux_probe -- conversions between SpecFluxFrames in the pinned C
 * library: for each pair, the class of the simplified Mapping astConvert
 * gives, that Mapping's dump, and the transformation of a point forwards
 * and of the result back; astEqual of the SpecFluxFrames of testspecflux.c;
 * and conversions between FrameSets whose current Frames are SpecFluxFrames,
 * as SPLAT makes them for spectra, in both directions.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -I<build> -o specflux_probe specflux_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./specflux_probe > specflux_probe.out 2>&1, then trailing white
 * space removed from every line (SpecFluxFrameTest compares lines without
 * it). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

static void head( const char *name ) {
   printf( "== %s\n", name );
}

/* Reports a failed call, then clears the status. */
static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

/* The transformation of the point "in" forwards by "m", and of the result
   back. */
static void tran( AstMapping *m, const double *in ) {
   double out[ 4 ], back[ 4 ];
   int nin = astGetI( m, "Nin" );
   int nout = astGetI( m, "Nout" );
   astTranN( m, 1, nin, 1, in, 1, nout, 1, out );
   if( failed() ) return;
   printf( "fwd" );
   for( int i = 0; i < nin; i++ ) printf( " %.17g", in[ i ] );
   printf( " ->" );
   for( int i = 0; i < nout; i++ ) printf( " %.17g", out[ i ] );
   printf( "\n" );
   astTranN( m, 1, nout, 1, out, 0, nin, 1, back );
   if( failed() ) return;
   printf( "inv" );
   for( int i = 0; i < nout; i++ ) printf( " %.17g", out[ i ] );
   printf( " ->" );
   for( int i = 0; i < nin; i++ ) printf( " %.17g", back[ i ] );
   printf( "\n" );
}

/* The conversion from "from" to "to": the class and dump of the simplified
   Mapping, then the transformations of the point (x, y) (x alone for a
   one-dimensional "from") by the FrameSet astConvert gives, searching
   "domains". */
static void convd( const char *name, void *from, void *to, const char *domains, double x, double y ) {
   head( name );
   AstFrameSet *fs = astConvert( from, to, domains );
   if( failed() ) return;
   if( !fs ) {
      printf( "no conversion\n" );
      return;
   }
   AstMapping *map = astGetMapping( fs, AST__BASE, AST__CURRENT );
   AstMapping *s = astSimplify( map );
   if( failed() ) return;
   printf( "class %s\n", astGetC( s, "Class" ) );
   astShow( s );
   printf( "-- transformations\n" );
   double in[ 2 ] = { x, y };
   tran( (AstMapping *) fs, in );
}

static void conv( const char *name, void *from, void *to, double x, double y ) {
   convd( name, from, to, " ", x, y );
}

static AstSpecFrame *spec( const char *settings ) {
   return astSpecFrame( "%s", settings );
}

static AstFluxFrame *flux( double specval, AstSpecFrame *sf, const char *settings ) {
   return astFluxFrame( specval, sf, "%s", settings );
}

/* The probe's SpecFluxFrame: a new SpecFrame and a FluxFrame without one. */
static AstSpecFluxFrame *sff( const char *sfset, double dsv, const char *ffset ) {
   return astSpecFluxFrame( spec( sfset ), flux( dsv, NULL, ffset ), " " );
}

/* A FrameSet of a spectrum: a two-dimensional GRID Frame joined to a
   SpecFluxFrame by a UnitMap. Converting between two such FrameSets
   searches the SPECTRUM-FLUX Domain, which the GRID Frames would otherwise
   match first. */
static AstFrameSet *spectrum( AstSpecFluxFrame *s ) {
   AstFrameSet *fs = astFrameSet( astFrame( 2, "Domain=GRID" ), " " );
   astAddFrame( fs, AST__BASE, astUnitMap( 2, " " ), s );
   return fs;
}

int main( void ) {
   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;
   int perm[ 2 ] = { 2, 1 };

   /* testspecflux.c. */
   AstSpecFrame *sf = spec( "system=freq,unit=GHz" );
   AstSpecFluxFrame *jy = astSpecFluxFrame( sf, flux( 123.0, sf, "Unit=Jy" ), " " );
   AstSpecFluxFrame *csff = astCopy( jy );
   AstSpecFluxFrame *dnw = astSpecFluxFrame( sf, flux( 123.1, sf, "System=flxdnw" ), " " );
   conv( "error8", jy, dnw, 2.0, 1.0 );
   astPermAxes( dnw, perm );
   conv( "error11", jy, dnw, 2.0, 1.0 );
   astPermAxes( jy, perm );
   conv( "error14", jy, dnw, 1.0, 2.0 );
   AstSpecFluxFrame *logw = astSpecFluxFrame( sf, flux( AST__BAD, NULL, "Unit=log(W/m2/nm)" ), " " );
   conv( "error18", csff, logw, 2.0, 1.0 );
   AstSpecFluxFrame *logsr = astSpecFluxFrame( sf, flux( AST__BAD, NULL, "Unit=log(W/m2/nm/sr)" ), " " );
   AstSpecFrame *sf2 = spec( "system=freq,unit=GHz" );
   AstSpecFluxFrame *jydeg = astSpecFluxFrame( sf2, flux( 123.0, sf2, "Unit=Jy/deg**2" ), " " );
   conv( "error25", jydeg, logsr, 2.0, 1.0 );

   AstSpecFrame *esf = spec( "system=freq" );
   AstFluxFrame *eff = flux( AST__BAD, NULL, "system=flxdn" );
   AstFluxFrame *eff2 = flux( AST__BAD, NULL, "system=flxdnw" );
   AstSpecFluxFrame *ea = astSpecFluxFrame( esf, eff, " " );
   AstSpecFluxFrame *eb = astSpecFluxFrame( esf, eff, " " );
   AstSpecFluxFrame *ec = astCopy( ea );
   AstSpecFluxFrame *ed = astSpecFluxFrame( esf, eff2, " " );
   head( "equal" );
   printf( "%d %d %d\n", astEqual( ea, eb ), astEqual( ea, ec ), astEqual( ea, ed ) );
   conv( "error32", ed, ea, 1.4e9, 1.0e-26 );

   /* Rust's test_fluxframe.rs SpecFluxFrame tests. */
   conv( "freq_to_wave", sff( "System=FREQ", AST__BAD, "System=FLXDN" ),
         sff( "System=WAVE", AST__BAD, "System=FLXDN" ), 1.4e9, 1.0e-26 );
   AstSpecFrame *g = spec( "system=freq,unit=GHz" );
   conv( "jy_to_fluxdenw",
         astSpecFluxFrame( g, flux( AST__BAD, g, "System=FLXDN,Unit=Jy,SpecVal=123.0" ), " " ),
         astSpecFluxFrame( g, flux( AST__BAD, g, "System=FLXDNW,SpecVal=123.1" ), " " ), 2.0, 1.0 );
   conv( "log_units",
         astSpecFluxFrame( g, flux( AST__BAD, g, "System=FLXDN,Unit=Jy,SpecVal=123.0" ), " " ),
         astSpecFluxFrame( g, flux( AST__BAD, g, "System=FLXDNW,Unit=log(W/m2/nm)" ), " " ), 2.0, 1.0 );
   conv( "surface_brightness",
         astSpecFluxFrame( g, flux( AST__BAD, g, "System=SFCBR,Unit=Jy/deg**2,SpecVal=123.0" ), " " ),
         astSpecFluxFrame( g, flux( AST__BAD, g, "System=SFCBRW,Unit=log(W/m2/nm/sr)" ), " " ), 2.0, 1.0 );

   /* Other changes of one component. */
   AstSpecFluxFrame *s = sff( "System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN,Unit=Jy" );
   conv( "jy_to_mjy", s, sff( "System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN,Unit=mJy" ), 2.0, 1.0 );
   conv( "ghz_to_mhz", s, sff( "System=FREQ,Unit=MHz", 1.4e9, "System=FLXDN,Unit=Jy" ), 2.0, 1.0 );
   conv( "flux_domains", sff( "System=FREQ", AST__BAD, "System=FLXDN,Domain=A" ),
         sff( "System=FREQ", AST__BAD, "System=FLXDN,Domain=B" ), 1.4e9, 1.0e-26 );
   AstSpecFluxFrame *lg = sff( "System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN,Unit=log(Jy)" );
   conv( "jy_to_log_jy", s, lg, 2.0, 1.0 );
   conv( "log_jy_to_jy", lg, s, 2.0, 0.0 );

   /* A FluxFrame in another System and a SpecFluxFrame. */
   AstFluxFrame *fw = flux( AST__BAD, spec( "" ), "System=FLXDNW" );
   AstSpecFluxFrame *sd = astSpecFluxFrame( spec( "" ), flux( AST__BAD, spec( "" ), "" ), " " );
   conv( "fluxframe_to_specfluxframe", fw, sd, 2.0, 0.0 );
   conv( "specfluxframe_to_fluxframe", sd, fw, 5000.0, 2.0 );

   /* Spectra as SPLAT holds them: frequency in GHz and Jy, wavelength in nm
      and mJy, radio velocity and W/m^2/Hz. */
   AstSpecFluxFrame *fa = sff( "System=FREQ,Unit=GHz,RestFreq=1.4GHz", AST__BAD, "System=FLXDN,Unit=Jy" );
   AstSpecFluxFrame *fb = sff( "System=WAVE,Unit=nm,RestFreq=1.4GHz", AST__BAD, "System=FLXDN,Unit=mJy" );
   AstSpecFluxFrame *fc = sff( "System=VRAD,RestFreq=1.4GHz", AST__BAD, "System=FLXDN,Unit=W/m^2/Hz" );
   AstSpecFluxFrame *fd = sff( "System=WAVE,Unit=nm,RestFreq=1.4GHz", AST__BAD, "System=FLXDNW,Unit=W/m^2/nm" );
   AstFrameSet *sa = spectrum( fa );
   AstFrameSet *sb = spectrum( fb );
   AstFrameSet *sc = spectrum( fc );
   AstFrameSet *sd2 = spectrum( fd );
   convd( "splat_freq_jy_to_wave_mjy", sa, sb, "SPECTRUM-FLUX", 1.3, 2.0 );
   convd( "splat_wave_mjy_to_freq_jy", sb, sa, "SPECTRUM-FLUX", 2.3e8, 2000.0 );
   convd( "splat_freq_jy_to_vrad", sa, sc, "SPECTRUM-FLUX", 1.3, 2.0 );
   convd( "splat_vrad_to_freq_jy", sc, sa, "SPECTRUM-FLUX", 1500.0, 2.0e-26 );
   convd( "splat_freq_jy_to_wave_flxdnw", sa, sd2, "SPECTRUM-FLUX", 1.3, 2.0 );
   convd( "splat_wave_flxdnw_to_freq_jy", sd2, sa, "SPECTRUM-FLUX", 2.3e8, 1.0e-30 );

   astEnd;
   return 0;
}
