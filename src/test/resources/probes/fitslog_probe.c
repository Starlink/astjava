/* fitslog_probe -- FITS-WCS -LOG axes in the pinned C library: headers with
 * a -LOG axis that is not spectral and one that is, read, dumped, and
 * written back in each FITS encoding and read again; FrameSets whose world
 * axis is exponential in the pixel axis written with FITS-WCS; and a
 * FREQ axis whose Unit is log(Hz).
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * A write prints the value it returns and the cards the FitsChan then holds
 * (astFindFits with "%f" from the first card); a read prints its dump and
 * transforms the pixel positions 1, 50 and 100 (and 512 and 1024 for a
 * header with NAXIS1 = 1024).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -I<build> -o fitslog_probe fitslog_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./fitslog_probe > fitslog_probe.out 2>&1, then trailing white
 * space removed from every line. The output holds words of C's (the
 * attribute name of the current Frame and the comment of CTYPE cards) that
 * the spelling check of text files rejects, so it is committed as the
 * string "output" of fitslog_probe.json:
 *   python3 -c 'import json; json.dump({"source": "probes/fitslog_probe.c",
 *      "output": open("fitslog_probe.out").read()},
 *      open("fitslog_probe.json", "w"), indent=1)' */
#include <stdio.h>
#include <string.h>
#include "ast.h"

static const char *encodings[] = { "FITS-WCS", "FITS-PC", "FITS-IRAF",
                                   "FITS-AIPS", "FITS-AIPS++", "FITS-CLASS",
                                   NULL };

static void head( const char *name ) {
   printf( "== %s\n", name );
}

static void cards( AstFitsChan *fc ) {
   char card[ 81 ];
   astClear( fc, "Card" );
   while( astFindFits( fc, "%f", card, 1 ) ) printf( "%s\n", card );
}

/* The pixel positions along the first pixel axis, the others at 1. */
static void trans( AstFrameSet *fs, int big ) {
   double pix[] = { 1.0, 50.0, 100.0, 512.0, 1024.0 };
   int np = big ? 5 : 3;
   int nin = astGetI( fs, "Nin" );
   int nout = astGetI( fs, "Nout" );
   double in[ 3 ][ 5 ], out[ 3 ][ 5 ], back[ 3 ][ 5 ];
   for( int i = 0; i < np; i++ ) {
      in[ 0 ][ i ] = pix[ i ];
      for( int j = 1; j < nin; j++ ) in[ j ][ i ] = 1.0;
   }
   astTranN( fs, np, nin, 5, (const double *) in, 1, nout, 5, (double *) out );
   astTranN( fs, np, nout, 5, (const double *) out, 0, nin, 5, (double *) back );
   for( int i = 0; i < np; i++ ) {
      printf( "pix" );
      for( int j = 0; j < nin; j++ ) printf( " %.17g", in[ j ][ i ] );
      printf( " -> wcs" );
      for( int j = 0; j < nout; j++ ) printf( " %.17g", out[ j ][ i ] );
      printf( " -> pix" );
      for( int j = 0; j < nin; j++ ) printf( " %.17g", back[ j ][ i ] );
      printf( "\n" );
   }
}

/* Writes fs with the encoding into a FitsChan holding the NAXIS cards,
   prints the result and the cards, and reads it back. */
static void writeback( AstFrameSet *fs, const char *enc, const char **naxis, int big ) {
   printf( "-- write %s\n", enc );
   AstFitsChan *fc = astFitsChan( NULL, NULL, "Encoding=%s", enc );
   for( int i = 0; naxis[ i ]; i++ ) astPutFits( fc, naxis[ i ], 0 );
   int n = astWrite( fc, fs );
   if( !astOK ) {
      printf( "write status %d\n", astStatus );
      astClearStatus;
      return;
   }
   printf( "wrote %d\n", n );
   cards( fc );
   if( n ) {
      astClear( fc, "Card" );
      AstFrameSet *back = astRead( fc );
      if( !astOK ) {
         printf( "read back status %d\n", astStatus );
         astClearStatus;
         return;
      }
      printf( "-- read back %s\n", back ? astGetC( back, "Class" ) : "nothing" );
      if( back ) {
         printf( "domain %s\n", astGetC( back, "Domain" ) );
         trans( back, big );
      }
   }
}

/* Reads the header, prints the FrameSet and its transformations, then
   writes it in each encoding. */
static void readhead( const char *name, const char **hdr, const char **naxis, int big ) {
   head( name );
   AstFitsChan *fc = astFitsChan( NULL, NULL, "" );
   for( int i = 0; hdr[ i ]; i++ ) astPutFits( fc, hdr[ i ], 0 );
   astClear( fc, "Card" );
   printf( "encoding %s\n", astGetC( fc, "Encoding" ) );
   AstFrameSet *fs = astRead( fc );
   if( !astOK ) {
      printf( "read status %d\n", astStatus );
      astClearStatus;
      return;
   }
   if( !fs ) {
      printf( "read nothing\n" );
      return;
   }
   printf( "-- read\n" );
   astShow( fs );
   printf( "domain %s\n", astGetC( fs, "Domain" ) );
   int naxes = astGetI( fs, "Naxes" );
   for( int i = 1; i <= naxes; i++ ) {
      char attr[ 20 ];
      sprintf( attr, "Symbol(%d)", i );
      printf( "symbol(%d) %s\n", i, astGetC( fs, attr ) );
   }
   trans( fs, big );
   for( int i = 0; encodings[ i ]; i++ ) writeback( fs, encodings[ i ], naxis, big );
}

/* The PolyMap of the Taylor series to degree 20 of sr exp(slope p). */
static AstPolyMap *exponential( double sr, double slope ) {
   double coeffs[ 21*3 ];
   double term = sr;
   for( int k = 0; k <= 20; k++ ) {
      coeffs[ 3*k ] = term;
      coeffs[ 3*k + 1 ] = 1;
      coeffs[ 3*k + 2 ] = k;
      term = term*slope/( k + 1 );
   }
   return astPolyMap( 1, 1, 21, coeffs, 0, NULL, "IterInverse=1" );
}

static AstFrameSet *frameset( int nin, AstMapping *map, AstFrame *wcs ) {
   AstFrame *pix = astFrame( nin, "Domain=GRID" );
   AstFrameSet *fs = astFrameSet( pix, " " );
   astAddFrame( fs, AST__BASE, map, wcs );
   return fs;
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   const char *naxis1[] = { "NAXIS   = 1", "NAXIS1  = 100", NULL };
   const char *naxis1k[] = { "NAXIS   = 1", "NAXIS1  = 1024", NULL };
   const char *naxis2[] = { "NAXIS   = 2", "NAXIS1  = 100", "NAXIS2  = 100", NULL };

   /* A -LOG axis that is not spectral. */
   {
      const char *h[] = { "NAXIS   = 1", "NAXIS1  = 100", "CTYPE1  = 'ZZZZ-LOG'",
                          "CRPIX1  = 1", "CRVAL1  = 10.0", "CDELT1  = 0.1", NULL };
      readhead( "other_log", h, naxis1, 0 );
   }

   /* A linear axis and a -LOG axis with a unit. */
   {
      const char *h[] = { "NAXIS   = 2", "NAXIS1  = 100", "NAXIS2  = 100",
                          "CTYPE1  = 'XPOS'", "CTYPE2  = 'YPOS-LOG'",
                          "CUNIT2  = 'V'", "CRPIX1  = 1", "CRPIX2  = 50",
                          "CRVAL1  = 3.0", "CRVAL2  = 250.0",
                          "CDELT1  = 2.0", "CDELT2  = 5.0", NULL };
      readhead( "other_log_2d", h, naxis2, 0 );
   }

   /* A spectral -LOG axis. */
   {
      const char *h[] = { "NAXIS   = 1", "NAXIS1  = 1024", "CTYPE1  = 'FREQ-LOG'",
                          "CUNIT1  = 'Hz'", "CRPIX1  = 512", "CRVAL1  = 1.4204E9",
                          "CDELT1  = 1.0E5", "SPECSYS = 'BARYCENT'",
                          "RESTFRQ = 1.4204E9", NULL };
      readhead( "freq_log", h, naxis1k, 1 );
   }

   /* A spectral -LOG axis in velocity. */
   {
      const char *h[] = { "NAXIS   = 1", "NAXIS1  = 100", "CTYPE1  = 'VRAD-LOG'",
                          "CUNIT1  = 'km/s'", "CRPIX1  = 50", "CRVAL1  = 1000.0",
                          "CDELT1  = 2.0", "SPECSYS = 'LSRK'",
                          "RESTFRQ = 1.4204E9", NULL };
      readhead( "vrad_log", h, naxis1, 0 );
   }

   /* An axis exponential in the pixel axis, not spectral: 10 exp(0.01 p). */
   head( "write_other_exp" );
   writeback( frameset( 1, (AstMapping *) exponential( 10.0, 0.01 ),
                        astFrame( 1, "Domain=WORLD" ) ),
              "FITS-WCS", naxis1, 0 );

   /* A frequency axis exponential in the pixel axis: 1.4 exp(0.001 p). */
   head( "write_freq_exp" );
   writeback( frameset( 1, (AstMapping *) exponential( 1.4, 0.001 ),
                        (AstFrame *) astSpecFrame( "System=FREQ" ) ),
              "FITS-WCS", naxis1, 0 );

   /* testfitschan.c 680-683: a MathMap frequency axis. */
   {
      const char *fwd[] = { "s = 1.4204e9 * exp( p * 1.0e-4 )" };
      const char *inv[] = { "p = 1.0e4 * log( s / 1.4204e9 )" };
      AstMathMap *mm = astMathMap( 1, 1, 1, fwd, 1, inv, " " );
      AstSpecFrame *sf = astSpecFrame( "System=FREQ,Unit=Hz,StdOfRest=Bary,RestFreq=1.4204e9 Hz" );
      head( "write_mathmap_freq" );
      writeback( frameset( 1, (AstMapping *) mm, (AstFrame *) sf ), "FITS-WCS", naxis1k, 1 );
   }

   /* A frequency axis in log(Hz), linear in the pixel axis, beside a
      linear axis. */
   {
      AstFrame *f1 = astFrame( 1, " " );
      AstSpecFrame *sf = astSpecFrame( "System=FREQ,Unit=log(Hz)" );
      AstCmpFrame *cf = astCmpFrame( f1, sf, " " );
      double shift[] = { 0.0, 8.95 };
      double zoom[] = { 1.0, 0.001 };
      AstShiftMap *sm = astShiftMap( 2, shift, " " );
      AstMatrixMap *zm = astMatrixMap( 2, 2, 1, zoom, " " );
      head( "write_log_unit" );
      writeback( frameset( 2, (AstMapping *) astCmpMap( zm, sm, 1, " " ), (AstFrame *) cf ),
                 "FITS-WCS", naxis2, 0 );
   }

   /* The header wcsconv/inputs/freq-log.head of the C fixtures, whose
      logarithmic increment is so small that the axis is linear to within
      FitsTol: written back with FITS-WCS. */
   {
      const char *h[] = { "SIMPLE  =                    T / Fits standard",
                          "BITPIX  =                  -32 / Bits per pixel",
                          "NAXIS   =                    1 / Number of dimensions",
                          "NAXIS1  =                 1024 / Length of axis",
                          "CTYPE1  = 'FREQ-LOG'           / Logarithmic frequency axis",
                          "CRVAL1  =           1.4204E+09 / Reference frequency (Hz)",
                          "CRPIX1  =              512.500 / Reference pixel",
                          "CDELT1  =           1.0000E-03 / Log increment",
                          "CUNIT1  = 'Hz      '           / Axis units",
                          "RESTFRQ =           1.4204E+09 / Rest frequency (Hz)",
                          "SPECSYS = 'BARYCENT'           / Spectral reference frame",
                          NULL };
      head( "freq_log_head" );
      AstFitsChan *fc = astFitsChan( NULL, NULL, "" );
      for( int i = 0; h[ i ]; i++ ) astPutFits( fc, h[ i ], 0 );
      astClear( fc, "Card" );
      AstFrameSet *fs = astRead( fc );
      trans( fs, 1 );
      writeback( fs, "FITS-WCS", naxis1k, 1 );
   }

   astEnd;
   return 0;
}
