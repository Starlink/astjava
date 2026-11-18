/* alt_offset_probe -- the Mapping C writes alternate description B of
 * case alt_axes_alt_offset of oracle/fitschan_cursor.json from: the header
 * of that case read with FITS-WCS, the simplified Mapping from the pixel
 * Frame to Frame 3 (the offset SkyFrame, Ident B, Domain SKY_POLE) dumped,
 * and transformed at a grid of 101 by 11 pixel positions spanning the
 * image, (1 + 0.99 i, 1 + 19.9 j), each output printed with %a.
 *
 * Built against the C library at 3d3f255f with its static libraries (see
 * fitslog_probe.c) and run as ./alt_offset_probe > alt_offset_probe.out;
 * the output is committed as the string "output" of alt_offset_probe.json,
 * as fitslog_probe.c describes. */
#include <stdio.h>
#include "ast.h"

int main( void ) {
   int status = 0;
   astWatch( &status );
   astBegin;
   const char *cards[] = {
      "NAXIS   = 2",
      "NAXIS1  = 100",
      "NAXIS2  = 200",
      "CRPIX1  = 50",
      "CRPIX2  = 100",
      "CRVAL1  = 10",
      "CRVAL2  = 20",
      "CD1_1   = -0.001",
      "CD2_2   = 0.001",
      "RADESYS = 'ICRS'",
      "CTYPE1  = 'RA---TAN'",
      "CTYPE2  = 'DEC--TAN'",
      "MJD-OBS = 55000.0",
      "CTYPE1B = 'OFLN-TAN'",
      "CTYPE2B = 'OFLT-TAN'",
      "CRVAL1B = 0",
      "CRVAL2B = 0",
      "CRPIX1B = 1",
      "CRPIX2B = 1",
      "CD1_1B  = -0.001",
      "CD2_2B  = 0.001",
      "RADESYSB= 'ICRS'",
      "SREF1B  = 0.5",
      "SREF2B  = 0.3",
      "SREFISB = 'POLE'",
      "CTYPE1D = 'GLON-TAN'",
      "CTYPE2D = 'GLAT-TAN'",
      "CRVAL1D = 1",
      "CRVAL2D = 2",
      "CRPIX1D = 1",
      "CRPIX2D = 1",
      "CD1_1D  = -0.001",
      "CD2_2D  = 0.001",
      NULL };
   AstFitsChan *fc = astFitsChan( NULL, NULL, "Encoding=FITS-WCS" );
   for( int i = 0; cards[ i ]; i++ ) astPutFits( fc, cards[ i ], 0 );
   astClear( fc, "Card" );
   AstFrameSet *fs = astRead( fc );
   AstMapping *map = astSimplify( astGetMapping( fs, AST__BASE, 3 ) );
   printf( "== dump\n" );
   astShow( map );
   printf( "== grid\n" );
   for( int j = 0; j < 11; j++ ) {
      for( int i = 0; i < 101; i++ ) {
         double in[ 2 ] = { 1.0 + 0.99*i, 1.0 + 19.9*j }, out[ 2 ];
         astTranN( map, 1, 2, 1, in, 1, 2, 1, out );
         printf( "%a %a\n", out[ 0 ], out[ 1 ] );
      }
   }
   astEnd;
   return 0;
}
